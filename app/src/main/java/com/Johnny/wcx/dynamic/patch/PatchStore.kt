package com.Johnny.wcx.dynamic.patch

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.dexkit.cache.GeneratedMethodHashes
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import org.json.JSONObject
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * 云端适配补丁的读取层（只读）。
 *
 * ## 补丁为什么只能放微信目录下
 *
 * 放 `/data/data/com.Johnny.wcx/`（模块 APK 私有目录）本来更稳 —— 清微信数据动不到它。
 * 但**微信进程读不到**。实测（一加 PHP110 / Android 16）：
 *
 * ```
 * su 10431 -c 'ls /data/data/com.Johnny.wcx/files/'
 * → Permission denied
 * ```
 *
 * 即使 Linux 权限给了 755、SELinux 标签也用 restorecon 对齐过，仍然被拒。
 * 根因是 SELinux 的 category 隔离：
 *
 * ```
 * 模块目录  app_data_file:s0:c92,c257,c512,c768
 * 微信目录  app_data_file:s0:c175,c257,c512,c768
 * ```
 *
 * 两个应用的数据目录带不同 category，这是 Android 沙箱的核心机制，绕不过去。
 *
 * 注意别拿 `ThemeStore.kt:271` 的 `createPackageContext` 当反例 ——
 * 它读的是 APK 里的 `assets/`，不是 `files/` 数据目录，两者权限模型不同。
 *
 * 所以补丁只能放微信目录下，代价是用户清微信数据会连补丁一起删。
 * 那个场景有兜底：补丁没了就走全量解析，结果依然正确，只是慢一次。
 *
 * ## 失败一律静默
 *
 * 补丁是「加速 + 应急」，不是唯一来源。这里任何异常都必须让调用方
 * 回落到全量解析，绝不能让补丁问题导致功能起不来。
 */
object PatchStore {

    private const val TAG = "PatchStore"
    private const val PATCH_DIR = "patches"
    private const val INDEX_FILE = "index.json"

    /** 补丁结构版本。格式不兼容变更时递增，旧补丁自动失效。 */
    private const val SCHEMA = 1

    /**
     * 已加载的补丁。null 表示「没有可用补丁」，调用方据此走原解析流程。
     * 首次访问时惰性加载 —— 启动路径上不做的活，不要硬塞进来。
     */
    private val patch: Patch? by lazy { load() }

    /**
     * 补丁目录。
     *
     * 用模块数据目录（在微信目录下）而非模块 APK 私有目录 —— 后者微信进程读不到，
     * 原因见文件头。`KnownPaths.moduleData` 会自行确保目录存在。
     */
    private val patchDir: Path by lazy { KnownPaths.moduleData.resolve(PATCH_DIR) }

    /**
     * 取当前生效的补丁，无则返回 null。
     */
    fun current(): Patch? = patch

    /**
     * 取某功能的锚点映射；无补丁或不含该功能时返回 null。
     */
    fun anchorsFor(featureName: String): Map<String, String>? =
        patch?.features?.get(featureName)?.anchors?.takeIf { it.isNotEmpty() }

    // ══════════════════════════════════════════════════════════
    // 加载与校验
    // ══════════════════════════════════════════════════════════

    private fun load(): Patch? = runCatching {
        val dir = patchDir
        if (!dir.isDirectory()) {
            WeLogger.d(TAG, "无补丁目录（$dir），走本地解析")
            return@runCatching null
        }

        val indexFile = dir.resolve(INDEX_FILE)
        if (!indexFile.isRegularFile()) {
            WeLogger.d(TAG, "无补丁索引，走本地解析")
            return@runCatching null
        }

        val index = JSONObject(indexFile.readText())

        if (index.optInt("schema", -1) != SCHEMA) {
            WeLogger.w(TAG, "补丁 schema 不匹配：${index.optInt("schema", -1)} != $SCHEMA")
            return@runCatching null
        }

        // 模块版本**不再**卡这里。
        //
        // 原先要求补丁的 moduleVersionCode 等于当前 APK，代价是「模块改一个字，
        // 整份补丁作废」—— 而适配是按**微信版本**维护的（一个 vXXXX.kt 对一个
        // 微信版本），模块发新版不该连累它。
        //
        // 放开后靠什么保证对得上：各功能的 methodHash（见 parse），它比模块版本
        // 细 —— 只剔除「改动过的那几个功能」，而不是全部。
        //
        // 留一条日志是因为「模块版本差很多」往往意味着出事了（补丁是很旧的、
        // 或者来自别的分支），出问题时要能一眼看到，而不是完全无声。
        val patchModuleVer = index.optLong("moduleVersionCode", -1L)
        if (patchModuleVer > 0 && patchModuleVer != BuildConfig.VERSION_CODE.toLong()) {
            WeLogger.i(
                TAG,
                "补丁来自模块版本 $patchModuleVer（当前 ${BuildConfig.VERSION_CODE}），" +
                        "版本不同不再作废，改由各功能 methodHash 单独判定"
            )
        }

        val file = index.optString("file", "")
        if (file.isEmpty()) {
            WeLogger.w(TAG, "补丁索引缺 file 字段")
            return@runCatching null
        }

        val patchFile = dir.resolve(file)
        if (!patchFile.isRegularFile()) {
            WeLogger.w(TAG, "补丁本体不存在：$patchFile")
            return@runCatching null
        }

        // ── 签名校验（强制，无签名即拒绝）────────────────────────────
        //
        // 必须在 parse 之前做，且必须针对**原始字节**：JSON 的键序与空白
        // 不唯一，重新序列化后的字节和签名时的不一样，验不过。
        //
        // 这是补丁链路上唯一的**信任根**。在此之前，能改仓库或能劫持
        // jsDelivr 的人就能让所有用户的模块把 hook 装到任意方法上 ——
        // 锚点指向哪儿就 hook 哪儿，所谓「只是数据」并不构成限制。
        val patchBytes = java.nio.file.Files.readAllBytes(patchFile)
        val signature = index.optString("signature", null)
            ?.takeIf { it.isNotEmpty() && it != "null" }
        if (!PatchSignature.verify(patchBytes, signature)) {
            // 拒绝后不删文件 —— 保留现场便于排查（用户可通过设置页重新下载）。
            // 走本地解析对功能没有影响，只是首次慢一点。
            WeLogger.w(TAG, "补丁签名校验未通过，回退本地解析")
            return@runCatching null
        }

        parse(JSONObject(String(patchBytes, Charsets.UTF_8)))
    }.onFailure {
        WeLogger.w(TAG, "读补丁失败，走本地解析：$it")
    }.getOrNull()

    /**
     * 解析补丁本体并逐功能校验。
     *
     * 校验两件必须同时成立的事：
     * 1. `wxVersionRange` 覆盖当前微信版本
     * 2. 该功能的 `methodHash` 与当前 APK 一致
     *
     * 任一条不满足的功能**单独剔除**，不影响其他功能 —— 一个功能对不上，
     * 不该拖垮整份补丁。
     */
    private fun parse(json: JSONObject): Patch? {
        val wxRange = json.optJSONObject("wxVersionRange")
        val min = wxRange?.optInt("min", -1) ?: -1
        val max = wxRange?.optInt("max", -1) ?: -1
        val wxVer = HostInfo.versionCode

        if (min < 0 || max < 0) {
            WeLogger.w(TAG, "补丁缺 wxVersionRange，忽略")
            return null
        }
        if (wxVer < min || wxVer > max) {
            WeLogger.i(TAG, "补丁不适用当前微信版本：补丁[$min,$max] 当前=$wxVer，忽略")
            return null
        }

        val featuresJson = json.optJSONObject("features") ?: return null
        val accepted = mutableMapOf<String, FeaturePatch>()
        var rejected = 0

        for (name in featuresJson.keys()) {
            val f = featuresJson.optJSONObject(name) ?: continue

            val cachedHash = f.optString("methodHash", "")
            val currentHash = methodHashOf(name)
            if (currentHash.isNullOrBlank()) {
                // 拿不到当前 hash（功能已重命名/移除）—— 这个功能的补丁不可信
                rejected++
                continue
            }
            if (cachedHash != currentHash) {
                WeLogger.i(TAG, "功能[$name] 补丁 hash 不符（补丁=$cachedHash 当前=$currentHash），该功能走本地解析")
                rejected++
                continue
            }

            val anchorsJson = f.optJSONObject("anchors") ?: continue
            val anchors = mutableMapOf<String, String>()
            for (key in anchorsJson.keys()) {
                val v = anchorsJson.optString(key, "")
                if (!v.isNullOrEmpty() && v != "null") anchors[key] = v
            }
            if (anchors.isNotEmpty()) {
                accepted[name] = FeaturePatch(cachedHash, anchors)
            }
        }

        if (accepted.isEmpty()) {
            WeLogger.i(TAG, "补丁无任何可用功能（剔除 $rejected 个），走本地解析")
            return null
        }

        WeLogger.i(TAG, "补丁加载成功：${accepted.size} 个功能命中，$rejected 个被剔除")
        return Patch(accepted)
    }

    /**
     * 读某个功能的当前 methodHash。
     *
     * 与 [com.Johnny.wcx.dexkit.cache.DexCacheManager] 用同一张表、同一套 key
     * （`GeneratedMethodHashes.HASHES[实现类全名]`），两处必须一致，否则
     * 补丁通过了这里的校验、却会被缓存层判为失效。
     *
     * 功能名 → 实现类的映射走 [FeaturesProvider.ALL_HOOK_ITEMS]，按 `name` 匹配
     * （不是类名 —— `name` 是功能显示名，也是缓存文件名）。找不到说明该功能
     * 已被重命名或移除，此时补丁不可信。
     */
    private fun methodHashOf(featureName: String): String? {
        val feature = FeaturesProvider.ALL_HOOK_ITEMS.firstOrNull { it.name == featureName }
            ?: return null
        return GeneratedMethodHashes.HASHES[feature.javaClass.name]
    }

    // ══════════════════════════════════════════════════════════
    // 数据模型
    // ══════════════════════════════════════════════════════════
    data class Patch(val features: Map<String, FeaturePatch>)

    data class FeaturePatch(val methodHash: String, val anchors: Map<String, String>)
}
