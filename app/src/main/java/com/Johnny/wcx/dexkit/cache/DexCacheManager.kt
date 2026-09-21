package com.Johnny.wcx.dexkit.cache

import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import com.Johnny.wcx.utils.unreachable
import org.json.JSONObject
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Dex 缓存管理器
 * 负责管理 Dex 查找结果的缓存，支持版本控制和增量更新。
 *
 * 缓存的 key → value 由各 [com.Johnny.wcx.dexkit.dsl.BaseDexDelegate] 直接提供
 */
object DexCacheManager {

    private const val TAG = "DexCacheManager"

    private const val CACHE_DIR_NAME = "dex_cache"
    private const val CACHE_FILE_SUFFIX = ".json"
    private const val KEY_HOST_VERSION = "host_version"

    /**
     * 模块版本。**与 [KEY_HOST_VERSION] 分开存**，因为它触发的是另一件事。
     *
     * 拆开的原因是个踩过的坑：原先模块版本被拼进同一个字符串里
     * （`WeLauncher` 的 else 分支塞了 `BuildConfig.VERSION_*`），于是
     * 「升级模块」会让 `cachedVer != currentVer` 成立，走进
     * `host version changed` 这条分支 —— 日志和语义全错，排查时会让人
     * 以为微信版本变了，实际只是模块重新编译了。
     *
     * 两条路径要处理的事本来就不同：
     * - 微信版本变 → 混淆类名整体重排，缓存**必须**全清
     * - 模块版本变 → 缓存本身仍然有效，只是用户开了「热更新后重置」想重扫一遍
     */
    private const val KEY_MODULE_VERSION = "module_version"

    private val cacheDir: Path by lazy {
        (KnownPaths.moduleData / CACHE_DIR_NAME).createDirsSafe()
    }

    /**
     * 初始化并与缓存里记录的版本比对。
     *
     * @param hostVersion 微信版本（如 `8.0.78.3180`）。变化 → 清空全部缓存。
     * @param moduleVersion 模块版本（如 `260922250000`）。仅当 [resetOnModuleUpdate]
     *   为 true 时，其变化才触发清缓存；否则只更新记录值，不动缓存。
     * @param resetOnModuleUpdate 对应设置项「热更新后重置 DEX 缓存」。
     *   默认 false —— 模块升级不该连累适配结果（适配是按**微信版本**维护的）。
     */
    fun init(
        hostVersion: String,
        moduleVersion: String,
        resetOnModuleUpdate: Boolean,
    ) {
        val cachedHost = WePrefs.getString(KEY_HOST_VERSION)

        // 路径一：微信版本变化 —— 无条件清缓存。
        // 这不是「用户想不想」的问题，是「缓存还在但已经没意义」：
        // 微信换版本后混淆类名会整体重排，旧锚点指向的类可能已不存在。
        if (cachedHost != hostVersion) {
            WeLogger.i(
                TAG,
                "宿主版本变化：$cachedHost -> $hostVersion，清空全部 DEX 缓存"
            )
            clearAllCache()
            Preferences.noDexResolve = false
            WeLogger.i(TAG, "因宿主版本变化，已关闭 NO_DEX_RESOLVE")
        }

        // 路径二：模块版本变化 —— 只有用户显式要求时才清。
        //
        // 与上面严格分开：这里**不**复用「宿主版本变化」的措辞，
        // 否则又会出现「日志说微信变了、其实只是模块重编译」的误导。
        val cachedModule = WePrefs.getString(KEY_MODULE_VERSION)
        if (cachedModule != moduleVersion) {
            if (resetOnModuleUpdate) {
                WeLogger.i(
                    TAG,
                    "模块版本变化：$cachedModule -> $moduleVersion，" +
                            "且已开启「热更新后重置」，清空全部 DEX 缓存"
                )
                clearAllCache()
                // 清缓存后必须允许重新解析，否则会卡在「缓存没了但也不许扫」
                Preferences.noDexResolve = false
            } else {
                WeLogger.d(
                    TAG,
                    "模块版本变化：$cachedModule -> $moduleVersion，" +
                            "未开启「热更新后重置」，缓存保持不变"
                )
            }
        }

        WePrefs.putString(KEY_HOST_VERSION, hostVersion)
        WePrefs.putString(KEY_MODULE_VERSION, moduleVersion)
    }

    /**
     * 检查 Feature 的缓存是否完整有效。
     *
     * 有效条件：
     * 1. 缓存文件存在
     * 2. methodHash 匹配（检测代码变化）
     * 3. [item] 的每个委托 key 都有非空值
     */
    fun isItemCacheValid(item: IResolveDex): Boolean {
        if (item !is BaseFeature) unreachable()

        val cacheFile = getCacheFile(item.name)
        if (!cacheFile.exists()) {
            WeLogger.d(TAG, "cache not found for ${item.name}")
            return false
        }

        return try {
            val json = JSONObject(cacheFile.readText())

            val cachedHash = json.optString("methodHash", "")
            val currentHash = calculateMethodHash(item)
            if (cachedHash != currentHash) {
                WeLogger.d(TAG, "resolveDex of ${item.displayName} changed: cached=$cachedHash, current=$currentHash")
                return false
            }

            // 每个委托对应一个 key，全部必须存在且非空
            val missingOrEmpty = item.dexDelegates.filter { delegate ->
                val v = json.optString(delegate.key, "")
                v.isEmpty() || v == "null"
            }

            if (missingOrEmpty.isNotEmpty()) {
                WeLogger.d(TAG, "cache incomplete for ${item.displayName}, missing keys: ${missingOrEmpty.map { it.key }}")
                return false
            }

            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to read cache for: ${item.displayName}", e)
            false
        }
    }

    /**
     * 将 [item] 所有委托的当前描述符持久化到缓存文件。
     * 数据来自 [IResolveDex.collectDescriptors]。
     */
    fun saveItemCache(item: IResolveDex) {
        if (item !is BaseFeature) {
            error("item is not BaseFeature")
        }

        val cacheFile = getCacheFile(item.name)
        try {
            val json = JSONObject()
            json.put("methodHash", calculateMethodHash(item))
            json.put("timestamp", System.currentTimeMillis())

            item.collectDescriptors().forEach { (key, value) ->
                json.put(key, value)
            }

            cacheFile.writeText(json.toString(2))
            WeLogger.d(TAG, "cache saved for: ${item.displayName}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to save cache for: ${item.displayName}", e)
        }
    }

    /**
     * 从缓存文件加载原始 Map（不包含元数据 key）。
     * 由 [IResolveDex.loadFromCache] 消费，后者负责逐委托分发。
     */
    fun loadItemCache(item: IResolveDex): Map<String, Any>? {
        if (item !is BaseFeature) {
            error("item is not BaseFeature")
        }

        val cacheFile = getCacheFile(item.name)
        if (!cacheFile.exists()) return null

        return try {
            val json = JSONObject(cacheFile.readText())
            buildMap {
                for (key in json.keys()) {
                    if (key !in META_KEYS) put(key, json.get(key))
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to load cache for: ${item.displayName}", e)
            null
        }
    }

    fun deleteCache(path: String) {
        getCacheFile(path).deleteIfExists()
    }

    fun clearAllCache() {
        cacheDir.listDirectoryEntries().forEach { path ->
            path.deleteIfExists()
        }
        WeLogger.i(TAG, "all cache cleared")
    }

    fun getOutdatedItems(items: List<IResolveDex>): List<IResolveDex> =
        items.filter { !isItemCacheValid(it) }

    // ---------------------------------------------------------------------------

    private val META_KEYS = setOf("methodHash", "timestamp")

    private fun getCacheFile(path: String): Path =
        cacheDir / (path.replace("/", "_") + CACHE_FILE_SUFFIX)

    /**
     * 获取 resolveDex 方法编译时生成的哈希，用于检测实现变化。
     */
    private fun calculateMethodHash(item: IResolveDex): String {
        val className = item.javaClass.name
        val hash = GeneratedMethodHashes.HASHES[className]
        if (hash.isNullOrBlank())
            error("failed to retrieve method hash for item $className; this shouldn't happen")
        return hash
    }
}
