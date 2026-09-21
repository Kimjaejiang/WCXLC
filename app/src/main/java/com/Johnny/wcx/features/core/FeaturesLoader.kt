package com.Johnny.wcx.features.core

import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.dynamic.patch.PatchStore
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.ui.content.DexResolver
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    private var healthEntries: List<FeatureHealth.Entry> = emptyList()

    private fun describe(
        feature: BaseFeature,
        status: FeatureHealth.Status,
        detail: String?,
    ) = FeatureHealth.Entry(
        name = feature.name,
        displayName = feature.displayName,
        categories = feature.categories,
        status = status,
        detail = detail,
        // LOADED 与 DISABLED 都采集：
        //   - LOADED  —— 功能已跑，锚点落空意味着它此刻就在空转；
        //   - DISABLED —— 用户还没开，但锚点可能已经坏了。只报 LOADED 的话，
        //     用户要等到「开了却发现用不了」才知道不兼容，错过了提前发现的机会。
        // 其余状态（SKIPPED_* / FAILED）本身已是 problem，再叠锚点信息只会噪音。
        missingAnchors = if (status == FeatureHealth.Status.LOADED ||
            status == FeatureHealth.Status.DISABLED
        ) {
            collectMissingAnchors(feature)
        } else {
            emptyList()
        },
    )

    /**
     * 收集一个功能里所有落空的 dex 锚点 key。
     *
     * 只在 [FeatureHealth.Status.LOADED] 时调用：此时 dexDelegates 已由 startup() 驱动完毕，
     * isPlaceholder 反映的是本轮加载的最终结论。
     *
     * 读 isPlaceholder 是纯属性访问，不会触发 class 解析（不碰 .clazz），因此对未命中的
     * 锚点调用是安全的 —— 这正是它和 getDescriptorString() 的区别。
     *
     * 排除 intentionallyAbsent：那是「按版本刻意作废」，不是查找失败。
     */
    private fun collectMissingAnchors(feature: BaseFeature): List<String> {
        if (feature !is IResolveDex) return emptyList()
        return runCatching {
            // 刻意置空的锚点（版本分支未选中的那一支）不算「未命中」：
            // 它永远不会被这条代码路径用到，也修不好，计入只会长期淹没真正的失效。
            feature.dexDelegates
                .filter { it.isPlaceholder && !it.intentionallyAbsent }
                .map { it.key }
                .sorted()
        }.getOrElse { e ->
            WeLogger.w(TAG, "failed to collect anchors for ${feature.name}", e)
            emptyList()
        }
    }

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        val allDexItems = allFeatures.filterIsInstance<IResolveDex>()

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // 补丁要接在这一步，不能接在 loadDescriptorsFromCache 里 ——
        // 那里只会收到「缓存文件已存在、只是键缺失」的 item；
        // 「缓存文件根本不存在」（清数据/新版本首次启动）的功能在
        // getOutdatedItems 就被分流进 outdatedItems，压根进不去那个函数。
        // 而那恰恰是最需要补丁的场景：没有补丁就得全量解析。
        val patchRescued = mutableSetOf<IResolveDex>()
        for (item in outdatedItems) {
            if (item is BaseFeature && tryApplyPatch(item, item.name)) {
                patchRescued += item
            }
        }
        val stillOutdated = outdatedItems.filterNot { patchRescued.contains(it) }

        // Load what we can from cache. Items with *some* missing keys are still partially loaded —
        // their valid delegates work immediately; only the item itself is queued for re-resolution.
        //
        // patchRescued 不能一起传进去：它们在磁盘上没有缓存文件，而这个函数
        // 一查不到缓存就判 failedItems，会把刚补好的又打回「未生效」。
        // 补丁已经确认填满了全部委托，直接算有效。
        val cacheFailedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (stillOutdated + cacheFailedItems).distinct()

        if (allBrokenItems.isNotEmpty())
            handleBrokenItems(allBrokenItems)

        val elapsed = measureTime {
            healthEntries = allFeatures.map { feature ->
                val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

                // 明确不该在本进程加载的功能：不算故障，与「用户没开」区分开。
                if (feature is SwitchFeature && !feature.shouldLoadInProcessForHealth()) {
                    return@map describe(feature, FeatureHealth.Status.SKIPPED_PROCESS, "当前进程不需加载")
                }

                if (isBroken && feature !is WeSettingsInjector) {
                    val fromCacheFailed = cacheFailedItems.contains(feature)
                    val status = if (fromCacheFailed) {
                        FeatureHealth.Status.SKIPPED_CACHE_FAILED
                    } else {
                        FeatureHealth.Status.SKIPPED_INCOMPLETE_CACHE
                    }
                    WeLogger.w(TAG, "skipping ${feature.name} — incomplete cache, awaiting re-resolution")
                    return@map describe(feature, status, "DEX 缓存未就绪，下次启动生效")
                }

                try {
                    feature.startup()
                    // 未启用的功能属正常状态，单独标出，避免与「异常」混淆。
                    val status = when {
                        feature !is SwitchFeature -> FeatureHealth.Status.LOADED
                        !feature.isEnabled -> FeatureHealth.Status.DISABLED

                        // 用户开着，但 enable() 没把它跑起来。startup() 全程没抛异常
                        // —— 异常已被 BaseFeature.enable 的 runCatching 吞掉，所以
                        // 只有比对 isActive 才能发现。不判的话这里会报 LOADED，
                        // 「开关打开了但功能没用」就被彻底藏住了。
                        !feature.isActive -> FeatureHealth.Status.ENABLE_FAILED

                        else -> FeatureHealth.Status.LOADED
                    }
                    val detail = if (status == FeatureHealth.Status.ENABLE_FAILED) {
                        "功能启动失败，已自动停用（详见日志中 failed to enable feature）"
                    } else null
                    describe(feature, status, detail)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "startup failed for ${feature.name}", e)
                    describe(feature, FeatureHealth.Status.FAILED, e.message)
                }
            }
        }
        FeatureHealth.publish(healthEntries)
        WeLogger.i(TAG, "loading all features took $elapsed")

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            showToast("WCXLC 加载成功!")
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐委托从缓存恢复状态。
     *
     * - 某个委托的 key 缺失 → 其他委托不受影响，仍正常加载。
     * - 有任意 key 缺失的 item 加入返回列表，等待 DexKit 重新扫描。
     * - 缓存文件整体读取失败 → 删除损坏文件，整个 item 加入返回列表。
     */
    private fun loadDescriptorsFromCache(items: List<IResolveDex>): List<IResolveDex> {
        val failedItems = mutableListOf<IResolveDex>()

        for (item in items) {
            val base = item as BaseFeature
            // displayName 是「分类/功能名」，只用于日志；
            // 缓存文件名与补丁的 key 都用纯 name（见 DexCacheManager.getCacheFile），
            // 两者混用会静默查不到 —— 之前 deleteCache(displayName) 就是因此永远删不掉文件。
            val path = base.displayName
            val name = base.name
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
                    // 走到这里说明该 item 既不在 outdatedItems、缓存也不存在 ——
                    // 正常情况下不该发生（缓存缺失的都在 outdatedItems 里被补丁筛过一轮）。
                    WeLogger.w(TAG, "cache missing for $path")
                    failedItems += item
                    continue
                }

                // loadFromCache 逐委托加载；返回未命中的 key 集合
                val missingKeys = item.loadFromCache(cache)
                if (missingKeys.isNotEmpty()) {
                    val total = item.dexDelegates.size
                    val loaded = total - missingKeys.size
                    WeLogger.w(TAG, "$path: loaded $loaded/$total delegates from cache, missing: $missingKeys")
                    // 补丁不在这里兜底：`outdatedItems` 那道筛子已经把所有「缓存缺失」
                    // 的 item 都过了一遍补丁。这里只可能是「缓存存在但内容残缺」，
                    // 那属于解析结果本身的问题，补丁填不了，交给 DexKit 重解析。
                    failedItems += item
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache(name) }
                failedItems += item
            }
        }

        return failedItems
    }

    /**
     * 尝试用云端补丁补齐 [item] 的委托。
     *
     * 补丁里的锚点映射已由 [PatchStore] 完成 methodHash + 微信版本校验，
     * 这里只负责「填值 + 确认填全」。任一委托填值失败或最终仍缺 key，
     * 都返回 false 让该 item 走原有 DexKit 解析 —— 补丁是应急，不是唯一来源。
     *
     * @return true 表示该 item 已被补丁补全，无需再走 DexKit。
     */
    private fun tryApplyPatch(
        item: IResolveDex,
        featureName: String
    ): Boolean = runCatching {
        val anchors = PatchStore.anchorsFor(featureName)
        if (anchors == null) {
            // 必须留痕：这里分两种情形，混淆会白白排查半天
            if (PatchStore.current() != null) {
                WeLogger.w(TAG, "补丁里没有功能[$featureName]，走解析")
            } else {
                WeLogger.d(TAG, "无补丁，[$featureName] 走解析")
            }
            return@runCatching false
        }

        // 逐委托填值；只填空缺的，不覆盖缓存里已恢复的结果
        var applied = 0
        for (delegate in item.dexDelegates) {
            val value = anchors[delegate.key] ?: continue
            if (value.isEmpty()) continue
            try {
                delegate.loadDescriptor(value)
                applied++
            } catch (e: Throwable) {
                // 单个委托填值失败不该毁掉整个补丁 —— 记下来，让它走解析
                WeLogger.w(TAG, "补丁填值失败：${delegate.key}=$value（$e）")
            }
        }

        if (applied == 0) return@runCatching false

        // 补全后仍缺 key → 补丁不完整，不能算命中
        val remaining = item.dexDelegates.filter { delegate ->
            val v = delegate.getDescriptorString()
            v.isNullOrEmpty() || v == "null"
        }
        if (remaining.isNotEmpty()) {
            WeLogger.w(TAG, "$featureName: 补丁只补了 $applied 个，仍缺 ${remaining.map { it.key }}，走解析")
            return@runCatching false
        }

        WeLogger.i(TAG, "$featureName: 补丁命中，跳过 DexKit 解析（$applied 个委托）")
        true
    }.getOrElse {
        WeLogger.w(TAG, "$featureName: 应用补丁异常，走解析（$it）")
        false
    }

    private fun handleBrokenItems(brokenItems: List<IResolveDex>) {
        if (Preferences.noDexResolve) return
        if (!TargetProcesses.isInMain) return

        WeLogger.i(TAG, "launching background coroutine to repair ${brokenItems.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var activity = LauncherUI.getInstance()
            var waited = 0L
            while (activity == null && waited < 30_000L) {
                delay(1_000.milliseconds)
                waited += 1_000
                activity = LauncherUI.getInstance()
            }

            if (activity == null) {
                WeLogger.w(TAG, "no LauncherUI available for dex-repair dialog; skipping")
                return@launch
            }

            val boundActivity = activity
            withContext(Dispatchers.Main) {
                showComposeDialog(boundActivity, directlyDismissable = false) {
                    DexResolver(
                        boundActivity,
                        brokenItems,
                        MainScope(),
                        onDismiss
                    )
                }
            }
        }
    }
}
