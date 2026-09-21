package com.Johnny.wcx.features.adapt

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.dexkit.cache.GeneratedMethodHashes
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 真机导出：把**已解析**的锚点真值写成补丁 JSON。
 *
 * ## 为什么必须在真机上做
 *
 * 补丁里存的是**解析结果**（`Lcom/tencent/mm/ui/g9;->a()Z`），不是 matcher。
 * 这个值只有 DexKit 真扫过微信的 dex 才有 —— 特征是混淆类名，服务端和
 * 电脑上都算不出来。
 *
 * 所以流程绕不开真机：
 *
 * ```
 * ① 写 v8079/V8_0_79.kt      （怎么找）
 * ② 真机上装微信 8.0.79 + 模块，跑一次
 * ③ 本文件导出               （找到的结果）  ← 导出后提交进仓库
 * ④ 用户端从仓库拉补丁
 * ```
 *
 * ## 导出的是「当前微信版本」的真值
 *
 * 所以**必须先升级微信再导出** —— 在 8.0.78 的机器上导出，拿到的是
 * 8.0.78 的类名，推给 8.0.79 的用户是错的。导出的 JSON 里带
 * `wxVersionCode`，[com.Johnny.wcx.dynamic.patch.PatchStore] 会据此校验，
 * 版本对不上直接忽略，不会静默用错。
 *
 * ## 只导出**命中**的锚点
 *
 * 落空的锚点（`isPlaceholder`）不导出 —— 写进补丁会让用户端把
 * 「找不到」当成「找到了」，掩盖真正的问题。
 */
object AdaptExporter {

    private const val TAG = "AdaptExporter"
    private const val FILE_NAME = "adapt-patch.json"

    /**
     * 导出到模块数据目录，返回文件；失败返回 null。
     *
     * 放 [KnownPaths.moduleData] 是为了方便 `adb pull` ——
     * 那目录本来就在微信目录下，权限没问题。
     */
    fun export(): File? = runCatching {
        val features = FeaturesProvider.ALL_HOOK_ITEMS
        if (features.isEmpty()) {
            WeLogger.w(TAG, "功能列表为空，无法导出")
            return@runCatching null
        }

        val wxVer = HostInfo.versionCode
        val moduleVer = BuildConfig.VERSION_CODE

        val entries = StringBuilder()
        var featureCount = 0
        var anchorCount = 0

        for (feature in features) {
            val base = feature as? BaseFeature ?: continue

            val anchors = LinkedHashMap<String, String>()
            for (delegate in base.dexDelegates) {
                // 落空的不写 —— 否则用户端会把「找不到」当成「找到了」
                if (delegate.isPlaceholder) continue
                val descriptor = delegate.getDescriptorString()
                if (descriptor.isNullOrEmpty()) continue
                anchors[delegate.key] = descriptor
            }
            if (anchors.isEmpty()) continue

            val className = base.javaClass.name
            // methodHash 用编译期生成的那份，保证与运行时校验口径一致。
            // 自己再算一遍 md5 早晚会跑偏，而跑偏的后果是补丁被静默拒绝。
            val hash = GeneratedMethodHashes.HASHES[className] ?: continue
            val name = base.name

            if (featureCount > 0) entries.append(",\n")
            entries.append("    {\n")
            entries.append("      \"name\": ").append(quote(name)).append(",\n")
            entries.append("      \"class\": ").append(quote(className)).append(",\n")
            entries.append("      \"methodHash\": \"").append(hash).append("\",\n")
            entries.append("      \"anchors\": {")
            entries.append(
                anchors.entries.joinToString(", ") { (k, v) ->
                    quote(k) + ": " + quote(v)
                }
            )
            entries.append("}\n")
            entries.append("    }")

            featureCount++
            anchorCount += anchors.size
        }

        val json = buildString {
            append("{\n")
            append("  \"schema\": 1,\n")
            append("  \"moduleVersionCode\": ").append(moduleVer).append(",\n")
            append("  \"wxVersionCode\": ").append(wxVer).append(",\n")
            append("  \"features\": [\n")
            append(entries)
            append("\n  ]\n}\n")
        }

        val out = KnownPaths.moduleData.resolve(FILE_NAME).toFile()
        out.writeText(json, StandardCharsets.UTF_8)

        WeLogger.i(
            TAG,
            "导出完成：$featureCount 个功能 / $anchorCount 个锚点，" +
                    "微信 $wxVer，模块 $moduleVer → ${out.absolutePath}"
        )
        out
    }.onFailure {
        WeLogger.e(TAG, "导出失败", it)
    }.getOrNull()

    /** 导出文件的路径，供 UI 显示。 */
    fun exportPath(): String = KnownPaths.moduleData.resolve(FILE_NAME).toString()

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
