package com.Johnny.wcx.features.core

import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.dexkit.resolution.resolveAllDex
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.ui.content.DexResolver
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.reflection.withDexKit
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

    /**
     * [resolveOutdatedInline] 的整批时间预算（毫秒）。
     *
     * 取值理由是「别把启动拖成明显卡顿」而不是「扫得完」：全量 300+ 功能冷解析要几十秒，
     * 那必须走 DexResolver 的后台路径。这里只求把最常见的情况（几十个功能过期）救回来，
     * 超出预算的交给 DexResolver 下一轮生效 —— 与改动前行为一致，只是不再是唯一出路。
     */
    private const val INLINE_RESOLVE_BUDGET_MS = 8_000L

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
        //
        // 不在此进程加载的功能也不采集 —— 它们本来就不会解析自己的锚点，
        // 计进去会让每个进程报出一批不同的假异常。
        missingAnchors = if (shouldCollectAnchors(feature, status)) {
            collectMissingAnchors(feature)
        } else {
            emptyList()
        },
        // hook 计数是纯字段读取，无副作用，所以任何状态都可以采集。
        installedHooks = feature.hookInstalledCount,
        hookCalls = feature.hookCallCount,
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

    /**
     * 同名功能只报日志，不拦截。
     *
     * 同名会让用户的开关落在其中一个上，另一个永远跟着走 —— 但这不是能靠
     * 启动期报错解决的事：一对同名功能里哪一个该留下，得人看代码才知道。
     * 所以这里只把冲突叫出来（带全限定类名），不让整个模块启动失败。
     *
     * 实际踩过：同步上游时把上游的 `UnlockCustomEmojiLimit` 拿进来，
     * 与本地已有的 `RemoveCustomStickersLimit` 撞了同名 —— 两者 @Feature
     * 的 name/分类/描述逐字相同，只是后者多了 -434 错误码修复。
     */
    private fun reportDuplicateFeatureNames(features: Collection<BaseFeature>) {
        features.groupBy { it.name }
            .filterValues { it.size > 1 }
            .forEach { (name, dupes) ->
                val classes = dupes.joinToString { it.javaClass.name }
                WeLogger.e(TAG, "duplicate feature name \"$name\" shared by ${dupes.size} items: $classes")
            }
    }

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        reportDuplicateFeatureNames(allFeatures)
        val allDexItems = allFeatures.filterIsInstance<IResolveDex>()

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // Load what we can from cache. Items with *some* missing keys are still partially loaded —
        // their valid delegates work immediately; only the item itself is queued for re-resolution.
        val cacheFailedItems = loadDescriptorsFromCache(validItems)

        // 缓存缺失/过期时，先尝试**现场解析**再决定跳过。
        //
        // 背景：缓存被整体清空（「热更新后重置 DEX 缓存」开着时，每次重装模块都会触发）
        // 会让全部功能落入 outdated，若直接跳过则本次启动所有 hook 都不装 ——
        // 用户看到的是「功能突然全失效」，而且必须重启两次才能恢复。
        //
        // DexKit 桥接器在启动期本就可用（DexResolver 用的就是它），所以这里能直接补上：
        // 解析成功的功能立刻可用，只有真正解不出来的才退回「跳过 + 进 DexResolver 队列」。
        //
        // 该路径只在主进程走：其他进程没有可用的 dex 桥接器，也不该重复承担解析开销。
        val resolvedInline = if (TargetProcesses.isInMain) {
            resolveOutdatedInline(outdatedItems)
        } else {
            emptyList()
        }

        val allBrokenItems = (outdatedItems + cacheFailedItems).distinct() - resolvedInline.toSet()

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

    /**
     * 对缓存缺失/过期的功能做一次**现场 dex 解析**，返回其中解析成功的那些。
     *
     * 与 [DexResolver] 的关系：DexResolver 是带 UI 的后台修复（主进程、等 LauncherUI 就绪、
     * 让用户看着进度），补完缓存但**本次启动不生效**；本方法是启动路径上的同步兜底，
     * 目的是让「缓存刚被清空」这个场景不再等于「本次启动全废」。
     * 两者不冲突：这里的每个功能都只有一次机会，成功即写回缓存并正常 startup()，
     * 失败的仍会进 DexResolver 队列，行为与改动前完全一致。
     *
     * 返回成功的功能列表；任何单个功能失败都只影响它自己。
     */
    private fun resolveOutdatedInline(items: List<IResolveDex>): List<IResolveDex> {
        if (items.isEmpty()) return emptyList()
        if (Preferences.noDexResolve) return emptyList()

        WeLogger.i(TAG, "attempting inline dex resolution for ${items.size} outdated items")

        val resolved = mutableListOf<IResolveDex>()
        val startNanos = System.nanoTime()

        try {
            withDexKit { dexKit ->
                for (item in items) {
                    // 预算保护：整批共享一个时限。超时后剩余功能退回旧行为（留给 DexResolver），
                    // 而不是把启动期无限拖长 —— 卡启动比功能晚一轮生效更糟。
                    val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                    if (elapsedMs > INLINE_RESOLVE_BUDGET_MS) {
                        WeLogger.w(
                            TAG,
                            "inline resolution budget exhausted after ${elapsedMs}ms; " +
                                    "${items.size - resolved.size} item(s) deferred to DexResolver"
                        )
                        break
                    }

                    val name = if (item is BaseFeature) item.name else item.javaClass.name
                    try {
                        // 必须用 resolveAllDex 而不是 resolveDex：前者还会建立
                        // DexResolutionContext（带宿主元数据）并执行 resolveInlineDex，
                        // 后者只是其中一步 —— 漏掉会让依赖上下文的锚点解析失败。
                        // DexResolver 走的也是这个入口，保持一致。
                        item.resolveAllDex(dexKit)
                        // 解析成功才写回缓存：失败时留着旧缓存（可能仍是过期数据），
                        // 但那次 DexResolver 会覆盖它，不必在这里删。
                        DexCacheManager.saveItemCache(item)
                        resolved += item
                    } catch (e: Throwable) {
                        WeLogger.w(TAG, "inline resolve failed for $name", e)
                    }
                }
            }
        } catch (e: Throwable) {
            // 桥接器不可用（拿不到 lease、宿主 dex 尚未就绪等）：整体退回旧行为。
            WeLogger.w(TAG, "inline resolution unavailable; falling back to DexResolver", e)
            return resolved
        }

        if (resolved.isNotEmpty()) {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            WeLogger.i(TAG, "inline resolution recovered ${resolved.size}/${items.size} items in ${elapsedMs}ms")
        }
        return resolved
    }

    // ---------------------------------------------------------------------------

    /**
     * 重新采集一次健康快照，供自检页面使用。
     *
     * 与 [loadFeatures] 的区别：**不跑 startup()、不装 hook**。
     * 它只重读各功能当前的 dexDelegates 占位状态与 hook 计数，
     * 把最新结论写回 [FeatureHealth]。
     *
     * 存在的理由：启动期各进程的 DEX 解析进度不同，主进程可能在上游
     * 锚点还没解析完时就发布了快照，而自检页读到的就是那份半成品。
     * 重新采集能拿到此刻的真实结果，而不必重启微信。
     *
     * 状态判定依赖 `feature.isEnabled` / `isActive`，这些在 startup() 后就已固定，
     * 因此这里直接用快照里的旧 status，只刷新锚点与计数 —— 把重算范围
     * 限制在不会引发副作用的部分。
     */
    fun recheckHealth() {
        val previous = healthEntries.associateBy { it.name }
        val fresh = FeaturesProvider.ALL_HOOK_ITEMS.map { feature ->
            val old = previous[feature.name]
                ?: return@map describe(feature, FeatureHealth.Status.LOADED, null)
            // 状态沿用旧结论（重启前不会变），锚点与计数重采。
            old.copy(
                missingAnchors = if (shouldCollectAnchors(feature, old.status)) {
                    collectMissingAnchors(feature)
                } else {
                    emptyList()
                },
                installedHooks = feature.hookInstalledCount,
                hookCalls = feature.hookCallCount,
            )
        }
        FeatureHealth.publish(fresh)
        WeLogger.i(TAG, "recheckHealth: 重采 ${fresh.size} 个功能的健康状态")
    }

    /**
     * 是否该为 [feature] 采集锚点信息。
     *
     * 两个条件缺一不可：
     * 1. [status] 为 LOADED / DISABLED（其他状态本身已是 problem，再叠锚点只会噪音）
     * 2. 该功能**确实会在本进程加载**（[SwitchFeature.shouldLoadInProcessForHealth]）
     *
     * 第二点是关键：设置在非主进程的功能根本不会去解析自己的锚点，
     * 把它们的未命中计进去，会让自检页在每个进程里报出一批不同的
     * 假异常 —— 那些锚点在本进程本来就不需要。
     */
    private fun shouldCollectAnchors(feature: BaseFeature, status: FeatureHealth.Status): Boolean {
        if (status != FeatureHealth.Status.LOADED && status != FeatureHealth.Status.DISABLED) return false
        if (feature is SwitchFeature && !feature.shouldLoadInProcessForHealth()) return false
        return true
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
            val path = (item as BaseFeature).displayName
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
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
                    failedItems += item
                    // 已命中的委托此时已经可用；hook 仍然跳过（见 loadFeatures），
                    // 等 DexKit 把缺失的部分补齐、cache 更新后下次启动即完整。
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache(path) }
                failedItems += item
            }
        }

        return failedItems
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
