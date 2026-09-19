package com.Johnny.wcx.features.core

import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
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
    )

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        val allDexItems = allFeatures.filterIsInstance<IResolveDex>()

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // Load what we can from cache. Items with *some* missing keys are still partially loaded —
        // their valid delegates work immediately; only the item itself is queued for re-resolution.
        val cacheFailedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (outdatedItems + cacheFailedItems).distinct()

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
                    val status = if (feature is SwitchFeature && !feature.isEnabled) {
                        FeatureHealth.Status.DISABLED
                    } else {
                        FeatureHealth.Status.LOADED
                    }
                    describe(feature, status, null)
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
