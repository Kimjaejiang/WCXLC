package com.Johnny.wcx.dynamic.patch

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText

/**
 * 补丁下载与落盘（模块 App 主进程执行）。
 *
 * 只负责「取回来、校验、放到位」这三件事。真正的判定逻辑在 [PatchStore]，
 * 它会被微信进程读取 —— 两边对同一份文件、同一套校验，这里不能另立标准。
 *
 * ## 为什么在主进程下载
 *
 * 补丁放在微信目录下（`KnownPaths.moduleData/patches`），微信进程和模块主进程
 * 都能写。但下载该由**模块 App** 发起：
 *
 * - 微信进程不该做网络请求 —— 它跑在微信里，任何失败都会连累宿主
 * - 用户点按钮的界面在模块 App，进度和结果得回到这个界面
 *
 * ## 安全边界
 *
 * 走 HTTPS + 长度上限 + **Ed25519 签名校验** + schema/版本校验。
 *
 * 签名是必须的，不是可选的加固：补丁决定了每个功能 hook 微信的哪个方法，
 * 锚点指向哪儿就 hook 哪儿。没有签名时，唯一保底是 HTTPS —— 能改仓库或
 * 能劫持 CDN 的人就能让所有用户的模块去 hook 任意方法。签名把信任根
 * 从「传输通道」移到了离线保管的私钥上（见 [PatchSignature]）。
 *
 * 校验过不了就**拒绝落盘**，用户回落到 DexKit 本地解析 —— 功能照常可用，
 * 只是首次慢一点，不会因为补丁问题起不来。
 */
object PatchDownloader {

    private const val TAG = "PatchDownloader"

    /**
     * 补丁仓库地址。
     *
     * 补丁不是服务端动态签发的，而是**仓库里的静态文件** —— 适配完成后
     * 提交进仓库，用户的模块直接拉 raw 文件。依赖的是普通文件托管，
     * 不需要任何服务端代码。
     */
    private const val REPO = "Kimjaejiang/WCXLC"
    private const val BRANCH = "main"

    /**
     * 按优先级排列的下载源。
     *
     * 多源不是因为「冗余就好」，而是 `raw.githubusercontent.com` 在国内
     * **经常连不上**（DNS 污染），而用户几乎都在国内。只赌它等于赌运气。
     *
     * jsDelivr 是公开的 GitHub CDN，国内可达性好得多；放在前面先试。
     * 两个都失败才走本地解析 —— 那本来就是兜底路径，不会影响功能。
     */
    private val SOURCES = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH",
        "https://raw.githubusercontent.com/$REPO/$BRANCH",
    )

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * 单个补丁文件的下载上限。
     *
     * 补丁是纯文本锚点表，正常几十 KB。给 2MB 足够宽松，
     * 同时挡住「服务端被换成了别的东西」这种情况。
     */
    private const val MAX_PATCH_BYTES = 2 * 1024 * 1024

    private const val PATCH_DIR = "patches"
    private const val INDEX_FILE = "index.json"
    private const val SCHEMA = 1

    sealed interface Result {
        /** 下载并落盘成功。 */
        data class Success(val featureCount: Int, val wxRange: String) : Result

        /** 仓库里没有当前微信版本的补丁 —— 不是错误，正常回落本地解析。 */
        data object NoPatchForThisVersion : Result

        /** 补丁存在但没带签名 —— 拒绝使用，回落本地解析。 */
        data object Unsigned : Result

        /** 签名校验失败（内容被改过或签名不配对）—— 拒绝使用，回落本地解析。 */
        data object BadSignature : Result

        /** 所有下载源都请求失败（网络问题或都不达）。 */
        data class Failure(val message: String, val cause: Throwable? = null) : Result
    }

    private val patchDir: File
        get() = KnownPaths.moduleData.resolve(PATCH_DIR).toFile()

    /**
     * 拉取当前微信版本的补丁并落盘。
     *
     * 依次尝试 [SOURCES]，任一成功即停。全部失败返回 [Result.Failure]，
     * **不影响**已有补丁 —— 下载到临时文件、校验通过才原子替换。
     * 否则一次网络抖动就会把用户手上能用的补丁抹掉。
     */
    fun download(): Result {
        val path = "patches/wx${HostInfo.versionCode}.json"
        val sigPath = "$path.sig"
        var lastError: Exception? = null

        for (base in SOURCES) {
            val url = "$base/$path"
            WeLogger.i(TAG, "尝试拉取补丁：$url")

            val body = try {
                httpGet(url)
            } catch (e: Exception) {
                WeLogger.w(TAG, "该源失败：${e.message}")
                lastError = e
                continue
            }

            // 404：这个源连上了，但仓库里确实没这个微信版本的补丁。
            // 这**不是**网络问题，换源也没用（同一个仓库），直接返回。
            if (body == null) {
                WeLogger.i(TAG, "仓库无微信 ${HostInfo.versionCode} 的补丁")
                return Result.NoPatchForThisVersion
            }

            // 签名是「同目录同名 + .sig」的独立文件，不塞进补丁本体。
            // 原因：签名必须覆盖补丁的**原始字节**，若把签名字段写进本体，
            // 就成了「签名包含自己」，得靠剔除字段再序列化来绕 —— 那种做法
            // 依赖序列化实现稳定，非常脆。
            val sig = try {
                httpGet("$base/$sigPath")
            } catch (e: Exception) {
                WeLogger.w(TAG, "拉取签名失败：${e.message}")
                lastError = e
                continue
            }

            if (sig == null) {
                WeLogger.w(TAG, "补丁存在但无对应 .sig 签名文件，拒绝使用")
                return Result.Unsigned
            }

            return applyBody(body, sig)
        }

        WeLogger.w(TAG, "所有下载源均失败：${lastError?.message}")
        return Result.Failure("所有下载源均失败：${lastError?.message}", lastError)
    }

    /** 校验并落盘已取回的内容。 */
    private fun applyBody(body: String, signatureB64: String): Result {
        // 先验签，再解析。顺序不能反 —— 解析不可信内容本身就不该做。
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        if (!PatchSignature.verify(bodyBytes, signatureB64.trim())) {
            WeLogger.w(TAG, "补丁签名校验失败，拒绝落盘")
            return Result.BadSignature
        }

        val parsed = try {
            validate(body)
        } catch (e: Exception) {
            WeLogger.w(TAG, "补丁校验失败：${e.message}")
            return Result.Failure("补丁格式错误：${e.message}", e)
        } ?: return Result.NoPatchForThisVersion

        return try {
            writeAtomically(body, signatureB64.trim(), parsed)
            WeLogger.i(TAG, "补丁已写入（签名有效）：${parsed.featureCount} 个功能，微信版本区间 ${parsed.wxRange}")
            Result.Success(parsed.featureCount, parsed.wxRange)
        } catch (e: Exception) {
            WeLogger.w(TAG, "补丁写盘失败：${e.message}")
            Result.Failure("写入失败：${e.message}", e)
        }
    }

    /**
     * 清掉本地补丁（调试用；也用于「回退到本地解析」）。
     */
    fun clear(): Boolean = runCatching {
        val dir = patchDir
        if (dir.isDirectory) dir.listFiles()?.forEach { it.delete() }
        WeLogger.i(TAG, "本地补丁已清除")
        true
    }.getOrElse {
        WeLogger.w(TAG, "清除补丁失败：$it")
        false
    }

    /**
     * 描述当前本地补丁状态，用于设置页展示。
     */
    fun describeCurrent(): String {
        val index = File(patchDir, INDEX_FILE)
        if (!index.isFile) return "未安装补丁"

        return runCatching {
            val json = JSONObject(index.readText())
            val ver = json.optLong("moduleVersionCode", -1L)
            val file = json.optString("file", "")
            val body = File(patchDir, file)
            if (!body.isFile) return "补丁索引存在但本体缺失"

            val patch = JSONObject(body.readText())
            val range = patch.optJSONObject("wxVersionRange")
            val count = patch.optJSONObject("features")?.length() ?: 0
            val createdAt = json.optLong("createdAt", 0L)

            buildString {
                append("$count 个功能")
                if (range != null) {
                    append("，微信 ${range.optInt("min")}~${range.optInt("max")}")
                }
                // 模块版本只作参考不判定生效：补丁跟的是微信版本，
                // 编译新模块不会让补丁失效，能否生效取决于各功能的 methodHash。
                if (ver > 0) append("，导出时模块版本 $ver")
                if (createdAt > 0) {
                    append("，下载于 ")
                    append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(createdAt)))
                }
            }
        }.getOrElse { "补丁状态读取失败：${it.message}" }
    }

    // ══════════════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════════════

    /**
     * GET 并返回响应体。
     *
     * 404/204 视为「这个版本没有补丁」，不是错误 —— 服务端没为新微信版本
     * 准备补丁是完全正常的，此时用户走本地解析即可。
     */
    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WCXLC/${BuildConfig.VERSION_CODE}")
            instanceFollowRedirects = true
        }

        return try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // 先看声明长度，超限直接拒绝，避免把大响应读进内存
                    val declared = conn.contentLengthLong
                    if (declared > MAX_PATCH_BYTES) {
                        throw IllegalStateException("补丁过大：${declared} 字节")
                    }
                    conn.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(8 * 1024)
                        val sb = StringBuilder()
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            sb.append(buf, 0, n)
                            if (sb.length > MAX_PATCH_BYTES) {
                                throw IllegalStateException("补丁超过 ${MAX_PATCH_BYTES} 字节上限")
                            }
                        }
                        sb.toString()
                    }
                }
                HttpURLConnection.HTTP_NO_CONTENT,
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    WeLogger.i(TAG, "服务端返回 $code，当前版本无补丁")
                    null
                }
                else -> throw IllegalStateException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private data class Parsed(
        val featureCount: Int,
        val wxRange: String,
        val moduleVersionCode: Long,
        val fileName: String
    )

    /**
     * 校验补丁并算出落地文件信息。
     *
     * 口径必须与 [PatchStore] 一致：schema、微信版本区间。
     * 这里多校验一层是为了**不落盘垃圾** —— PatchStore 那边校验不过只是不用它，
     * 但文件会一直躺在目录里误导人。
     *
     * 模块版本**不校验**：补丁跟的是微信版本，重新编译模块不该让补丁失效。
     * 真要对得上靠的是各功能的 methodHash（PatchStore 那边逐功能判），
     * 那是更细的粒度。
     */
    private fun validate(body: String): Parsed? {
        val json = JSONObject(body)

        if (json.optInt("schema", -1) != SCHEMA) {
            throw IllegalStateException("schema 不匹配：${json.optInt("schema", -1)} != $SCHEMA")
        }

        val moduleVer = json.optLong("moduleVersionCode", -1L)

        val range = json.optJSONObject("wxVersionRange")
            ?: throw IllegalStateException("缺 wxVersionRange")
        val min = range.optInt("min", -1)
        val max = range.optInt("max", -1)
        if (min < 0 || max < 0) throw IllegalStateException("wxVersionRange 非法")

        val wxVer = HostInfo.versionCode
        if (wxVer < min || wxVer > max) {
            WeLogger.i(TAG, "补丁不适用于当前微信版本（补丁[$min,$max] 当前=$wxVer）")
            return null
        }

        val features = json.optJSONObject("features")
            ?: throw IllegalStateException("缺 features")
        if (features.length() == 0) {
            WeLogger.i(TAG, "补丁不含任何功能")
            return null
        }

        return Parsed(
            featureCount = features.length(),
            wxRange = "$min~$max",
            moduleVersionCode = moduleVer,
            // 文件名只含微信版本 —— 补丁跟的是微信版本，不含模块版本，
            // 同一个微信版本永远只有一份补丁，不会因模块迭代而堆积。
            fileName = "patch-wx$wxVer.json"
        )
    }

    /**
     * 原子落盘：先写临时文件，校验通过后 move 覆盖。
     *
     * 顺序很重要 —— 先写本体再写索引。PatchStore 以索引为准，
     * 索引指向的文件必须已经存在，否则会读到半个补丁。
     */
    private fun writeAtomically(body: String, signatureB64: String, parsed: Parsed) {
        val dir = patchDir
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("无法创建补丁目录：$dir")
        }

        val targetDir = dir.toPath()
        val bodyPath = targetDir.resolve(parsed.fileName)
        val bodyTmp = targetDir.resolve("${parsed.fileName}.tmp")

        Files.write(bodyTmp, body.toByteArray(Charsets.UTF_8))
        Files.move(bodyTmp, bodyPath, StandardCopyOption.REPLACE_EXISTING)

        val index = JSONObject().apply {
            put("schema", SCHEMA)
            put("moduleVersionCode", parsed.moduleVersionCode)
            put("moduleVersionName", BuildConfig.TAG)
            put("wxVersionCode", HostInfo.versionCode)
            put("file", parsed.fileName)
            put("featureCount", parsed.featureCount)
            put("createdAt", System.currentTimeMillis())
            // 签名是 PatchStore 侧的强制项：这里落盘前已经验过一次，
            // 但 PatchStore 会再验（它读的是磁盘文件，与下载时是两条路径，
            // 不能假设中间没被改过）。
            put("signature", signatureB64)
        }

        val indexPath = targetDir.resolve(INDEX_FILE)
        val indexTmp = targetDir.resolve("$INDEX_FILE.tmp")
        Files.write(indexTmp, index.toString(2).toByteArray(Charsets.UTF_8))
        Files.move(indexTmp, indexPath, StandardCopyOption.REPLACE_EXISTING)

        // 清掉旧版本的补丁本体，避免目录越积越多（索引只指向最新那份）
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("patch-") && it.name != parsed.fileName }
            ?.forEach { it.delete() }
    }
}
