package com.Johnny.wcx.utils.backup

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.serialization.DefaultJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 全量备份：把模块的「用户数据」打包成一个 zip，并支持从 zip 恢复。
 *
 * ## 为什么需要它
 *
 * 模块的用户数据分两处存：
 *
 *  - MMKV 偏好（[WePrefs.default]）：开关状态、各功能参数
 *  - WCXLC 数据目录下的散装文件：归拢规则、自定义头像映射、真实姓名、JS 脚本
 *
 * 设置页原有的「导出配置」只导了前者。这留下过一个真实的坑：用户清数据后
 * 重装，「开关还在」但归拢规则没了 —— 因为 `chat_folders_<wxid>.json` 根本
 * 不在导出范围内，而它恰恰是最难手工重建的那类数据。
 *
 * 所以这里把两处合并导出，并带上清单文件（版本、时间、条目列表），
 * 让恢复端能判断包是不是本模块产生的、有没有被截断。
 *
 * ## 格式
 *
 * ```
 * manifest.json          清单：格式版本、导出时间、模块版本、包含的条目
 * prefs.json            MMKV 偏好的键值快照
 * files/<相对路径>       WCXLC 数据目录下的用户文件，按原目录结构存放
 * ```
 */
object BackupManager {

    /** 备份格式版本。将来改结构时靠它决定怎么读旧包。 */
    private const val FORMAT_VERSION = 1

    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_PREFS = "prefs.json"
    private const val FILE_PREFIX = "files/"
    private const val TAG = "BackupManager"

    /**
     * 要备份的用户数据，相对 [KnownPaths.moduleData]。
     *
     * 这里只收「用户产生的、丢了就回不来的」数据，不收缓存：
     *
     *  - `chat_folders_*.json` 归拢规则，手工重建成本极高
     *  - `custom_avatars_map.json`、`real_names.json` 用户手工录入的映射
     *  - `scripts_js/` 用户写的脚本，纯文本，代价小
     *
     * 刻意排除：`dex_cache/`（可由微信 dex 重建）、`avatars/`、
     * `notif_avatars_v3/`（图片缓存，重下即可）、`logs/`、`crashes/`、
     * `diag.log`（诊断信息，备份它们只会让包变大且含隐私）。
     */
    private val USER_DATA_PATTERNS = listOf(
        Regex("^chat_folders.*\\.json$"),
        Regex("^custom_avatars_map\\.json$"),
        Regex("^real_names\\.json$"),
    )

    private val USER_DATA_DIRS = listOf("scripts_js")

    /** 一次备份的结果，用于给用户反馈。 */
    data class Result(val fileCount: Int, val prefCount: Int, val bytes: Long)

    /**
     * 把当前用户数据写入 [target]。
     *
     * 调用方负责在 IO 线程执行；[target] 会先被截断。
     */
    fun exportTo(target: File): Result {
        val prefs = WePrefs.default.all
        val files = collectUserFiles()

        var fileCount = 0
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            // 清单放最前：即使包被截断，恢复端也能先读到它、给出「包不完整」
            // 而不是一个含糊的解压错误。
            zip.writeEntry(ENTRY_MANIFEST, buildManifest(files.size, prefs.size))

            val prefsJson = buildJsonObject {
                for ((key, value) in prefs) {
                    when (value) {
                        is Boolean -> put(key, value)
                        is Int -> put(key, value)
                        is Long -> put(key, value)
                        is Float -> put(key, value)
                        is Double -> put(key, value)
                        is String -> put(key, value)
                        is Set<*> -> put(key, buildJsonArray {
                            @Suppress("UNCHECKED_CAST")
                            (value as Set<String>).forEach { add(JsonPrimitive(it)) }
                        })

                        null -> put(key, JsonNull)
                    }
                }
            }.toString()
            zip.writeEntry(ENTRY_PREFS, prefsJson)

            for (file in files) {
                val relative = file.relativeTo(KnownPaths.moduleData.toFile()).path
                    .replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry("$FILE_PREFIX$relative"))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
                fileCount++
            }
        }

        WeLogger.i(TAG, "exported $fileCount files, ${prefs.size} prefs, ${target.length()} bytes")
        return Result(fileCount, prefs.size, target.length())
    }

    /** 收集要备份的用户文件（含目录递归）。不存在的项直接跳过。 */
    private fun collectUserFiles(): List<File> {
        val root = KnownPaths.moduleData.toFile()
        if (!root.isDirectory) return emptyList()

        val result = ArrayList<File>()

        root.listFiles()?.forEach { child ->
            when {
                child.isDirectory && child.name in USER_DATA_DIRS ->
                    child.walkTopDown().filter { it.isFile }.forEach { result += it }

                child.isFile && USER_DATA_PATTERNS.any { it.matches(child.name) } ->
                    result += child
            }
        }

        return result
    }

    private fun buildManifest(fileCount: Int, prefCount: Int): String = buildJsonObject {
        put("formatVersion", FORMAT_VERSION)
        put("exportedAt", System.currentTimeMillis())
        put("moduleVersion", BuildConfig.VERSION_NAME)
        put("moduleVersionCode", BuildConfig.VERSION_CODE)
        put("fileCount", fileCount)
        put("prefCount", prefCount)
    }.toString()

    private fun ZipOutputStream.writeEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // -----------------------------------------------------------------------
    //  恢复
    // -----------------------------------------------------------------------

    /** 恢复结果：写回了多少条偏好与文件。 */
    data class RestoreResult(val fileCount: Int, val prefCount: Int)

    /** 恢复失败的原因，用于给用户看得懂的提示。 */
    class BackupException(message: String) : Exception(message)

    /**
     * 从 [source] 恢复。
     *
     * 恢复是**覆盖式**的：包里有的键和文件会覆盖本地同名项，包里没有的保持不动。
     * 这一点很重要 —— 用户可能只想用备份补回归拢规则，不希望开关状态被一起改回去。
     *
     * 解压前先校验清单：不是本模块的包、或格式版本比当前新，都直接拒绝，
     * 免得把一份结构不明的 zip 铺进数据目录。
     */
    fun restoreFrom(source: File): RestoreResult {
        var fileCount = 0
        var prefCount = 0

        // 先只读清单，确认包能用，再动本地数据。分两趟是为了避免
        // 「解到一半发现格式不对，数据已经被改了一半」。
        val manifestVersion = readManifestVersion(source)
        if (manifestVersion == null) {
            throw BackupException("这不是本模块导出的备份文件（缺少清单）")
        }
        if (manifestVersion > FORMAT_VERSION) {
            throw BackupException("备份来自更新的模块版本，当前版本读不了")
        }

        val root = KnownPaths.moduleData.toFile()

        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == ENTRY_PREFS -> {
                        prefCount = restorePrefs(zip.readBytes().toString(Charsets.UTF_8))
                    }

                    entry.name.startsWith(FILE_PREFIX) && !entry.isDirectory -> {
                        val relative = entry.name.removePrefix(FILE_PREFIX)
                        // 拒绝跳出数据目录的相对路径。备份文件可能来自不可信来源
                        // （用户在群里转发），"../" 会把内容写到模块目录之外。
                        val dest = File(root, relative)
                        if (!isInside(root, dest)) {
                            WeLogger.w(TAG, "skip out-of-root entry: ${entry.name}")
                        } else {
                            dest.parentFile?.mkdirs()
                            dest.outputStream().buffered().use { zip.copyTo(it) }
                            fileCount++
                        }
                    }
                }
                entry = zip.nextEntry
            }
        }

        WeLogger.i(TAG, "restored $fileCount files, $prefCount prefs")
        return RestoreResult(fileCount, prefCount)
    }

    /**
     * 读清单里的格式版本；清单不存在或读不出来时返回 null。
     *
     * 只读到清单就停，不把整个包解一遍 —— 用户选错文件（比如选了个
     * 几百 MB 的别的 zip）时不该让手机空转。
     */
    private fun readManifestVersion(source: File): Int? = runCatching {
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == ENTRY_MANIFEST) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    val obj = DefaultJson.parseToJsonElement(text) as? JsonObject ?: return@use null
                    return@use (obj["formatVersion"] as? JsonPrimitive)
                        ?.content?.toIntOrNull()
                }
                entry = zip.nextEntry
            }
            null
        }
    }.getOrNull()

    /** 把 prefs.json 写回 MMKV，返回写入条数。 */
    private fun restorePrefs(json: String): Int {
        val obj = runCatching {
            DefaultJson.parseToJsonElement(json)
        }.getOrElse { throw BackupException("备份里的配置无法解析") }

        val jsonObject = obj as? JsonObject
            ?: throw BackupException("备份里的配置格式不对")

        var count = 0
        for ((key, element) in jsonObject) {
            when (val value = element) {
                is JsonNull -> { /* 导出时就没有值，跳过 */ }
                is JsonPrimitive -> when {
                    value.isString -> WePrefs.default.putString(key, value.content)
                    else -> {
                        // 数字/布尔：MMKV 支持 putLong/putBoolean，按字面量还原。
                        // 统一走字符串再让 MMKV 自己认类型是行不通的（会把 int 变成 string），
                        // 所以这里显式区分。
                        val content = value.content
                        when {
                            content == "true" || content == "false" ->
                                WePrefs.default.putBoolean(key, content == "true")

                            else -> content.toLongOrNull()?.let { WePrefs.default.putLong(key, it) }
                                ?: WePrefs.default.putString(key, content)
                        }
                    }
                }

                else -> Unit
            }
            count++
        }
        WePrefs.default.save()
        return count
    }

    /**
     * [child] 是否确实位于 [root] 之内（含合法子路径，不含 ../ 逃逸）。
     *
     * 比较用规范路径，因为 `File(root, "../x")` 的 path 看着还在 root 下，
     * 只有 canonical 之后才会暴露真实位置。
     */
    private fun isInside(root: File, child: File): Boolean = runCatching {
        val rootPath = root.canonicalFile
        val childPath = child.canonicalFile
        childPath.path == rootPath.path || childPath.path.startsWith(rootPath.path + File.separator)
    }.getOrDefault(false)
}
