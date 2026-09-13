package com.Johnny.wcx.hot

import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.loader.utils.HybridClassLoader
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 热更新管理器：外部插件 APK 的加载、校验、下载与重启。
 *
 * 架构是「稳定壳 + 外部插件 APK」——壳（已装模块）不重装，逻辑变更打成独立签名的
 * `plugin-x.y.z.apk`，壳用 [DexClassLoader] 运行时加载，更新后只需重启微信。
 *
 * 关键约束：
 * - 加载失败绝不阻断微信启动，只记日志（[loadInstalled] 整段 runCatching）。
 * - 插件 APK 必须先落到微信内部目录再交给 DexClassLoader：Android 10+ 从可写目录加载 dex
 *   会被 W^X 拦，Android 15+ 更严；内部目录是应用私有、只读执行的。
 * - 下载后必须同时过 SHA-256 与签名两道校验，任一不符即删。
 */
object HotUpdateManager {

    private const val TAG = "HotUpdate"

    /** 远端 manifest 地址。 */
    const val REMOTE_MANIFEST_URL = "https://example.invalid/wcx/hot-update.json"

    /** 加载协议版本，与 [HotApi.VERSION] 同源，此处只做读取别名。 */
    val hotApiVersion: Int get() = HotApi.VERSION

    private const val INSTALLED_MANIFEST = "manifest-installed.json"
    private const val PLUGIN_FILE_PREFIX = "plugin-"
    private const val PLUGIN_FILE_SUFFIX = ".apk"

    /** 微信内部目录下的落地文件名，规避 W^X。 */
    private const val INTERNAL_CURRENT_APK = "plugin-current.apk"

    /** 单次下载上限，防 manifest 被篡改成超大文件把磁盘写满。 */
    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }

    /** 插件包名数据目录：`moduleData/hot`。 */
    private val hotDir get() = (KnownPaths.moduleData / "hot").createDirsSafe()

    /** 下载中转目录：`moduleCache`（与 moduleData 分属 cache 区，清理缓存不会伤到已装插件）。 */
    private val cacheDir get() = KnownPaths.moduleCache

    /** 外置暂存目录：`Android/data/com.tencent.mm/WCXLC/hot`（同一目录，外置可见便于手动塞包）。 */
    private val externalHotDir get() = hotDir

    private val internalDir get() = (KnownPaths.codeCacheDir / "app_hot").createDirsSafe()

    /** 已安装 manifest 文件。 */
    private val installedManifestFile get() = hotDir.resolve(INSTALLED_MANIFEST).toFile()

    // -------------------------------------------------------------------------
    // 检查结果
    // -------------------------------------------------------------------------

    sealed interface CheckResult {
        /** 已是最新。 */
        data object UpToDate : CheckResult

        /** 有可用更新。 */
        data class UpdateAvailable(val manifest: HotManifest) : CheckResult

        /** 插件要求的壳版本高于当前壳，只能走整包更新。 */
        data class ShellTooOld(val required: Long, val current: Long) : CheckResult

        /** 网络/解析失败。 */
        data class Error(val message: String) : CheckResult
    }

    /** 当前已安装的 manifest，未安装返回 null。 */
    fun installedManifest(): HotManifest? = readManifest(installedManifestFile.toPath())

    /** 当前是否已装载插件（供 UI 展示）。 */
    @Volatile
    var loadedPluginVersion: String? = null
        private set

    // -------------------------------------------------------------------------
    // 启动时加载
    // -------------------------------------------------------------------------

    /**
     * 微信启动时调用，必须在 `FeaturesLoader.loadFeatures()` 之前。
     *
     * 全流程失败只记日志，内置 features 照常加载。
     */
    fun loadInstalled() {
        runCatching { loadInstalledInternal() }
            .onFailure { WeLogger.e(TAG, "hot update loadInstalled failed (ignored)", it) }
    }

    private fun loadInstalledInternal() {
        val installedFile = installedManifestFile
        if (!installedFile.isFile) {
            WeLogger.d(TAG, "no installed plugin manifest, skip")
            return
        }

        val manifest = readManifest(installedFile.toPath())
        if (manifest == null) {
            WeLogger.w(TAG, "installed manifest unreadable, skip")
            return
        }

        // 门禁：插件要求的加载协议高于壳，说明是给更新版壳准备的，直接跳过
        if (manifest.hotApi > HotApi.VERSION) {
            WeLogger.w(
                TAG,
                "skip plugin v${manifest.version}: hotApi ${manifest.hotApi} > shell ${HotApi.VERSION}"
            )
            return
        }

        val pluginApk = hotDir.resolve(manifest.fileName).toFile()
        if (!pluginApk.isFile) {
            WeLogger.w(TAG, "plugin apk missing: ${pluginApk.absolutePath}")
            return
        }

        // 拷到微信内部目录：Android 15+ 从外部可写目录加载 dex 会被 W^X 拒绝
        val internalApk = internalDir.resolve(INTERNAL_CURRENT_APK).toFile()
        runCatching {
            pluginApk.copyTo(internalApk, overwrite = true)
        }.onFailure {
            WeLogger.e(TAG, "failed to stage plugin into internal dir, skip", it)
            return
        }

        val host = makeHost()
        val loader = runCatching {
            // parent 用模块 classloader：插件得以解析 de.robv.android.xposed.* 与 com.Johnny.wcx.*
            DexClassLoader(
                internalApk.absolutePath,
                internalDir.toFile().absolutePath,
                null,
                HybridClassLoader.moduleParentClassLoader
            )
        }.onFailure {
            WeLogger.e(TAG, "failed to create DexClassLoader, skip", it)
            return
        }.getOrNull() ?: return

        // 让模块侧代码也能 loadClass 到插件的类型
        if (loader !in HybridClassLoader.additionalLoaders) {
            HybridClassLoader.additionalLoaders += loader
        }

        val plugin = runCatching {
            val clazz = Class.forName(manifest.entryClass, true, loader)
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance() as HotPlugin
        }.onFailure {
            WeLogger.e(TAG, "failed to instantiate entryClass=${manifest.entryClass}, skip", it)
            return
        }.getOrNull() ?: return

        runCatching {
            plugin.onLoad(host)
        }.onFailure {
            WeLogger.e(TAG, "plugin onLoad threw, skip", it)
            return
        }

        loadedPluginVersion = manifest.version
        WeLogger.i(
            TAG,
            "plugin v${manifest.version} loaded (entry=${manifest.entryClass}, hotApi=${manifest.hotApi})"
        )
    }

    // -------------------------------------------------------------------------
    // 远端检查
    // -------------------------------------------------------------------------

    /**
     * GET 远端 manifest 并与已装版本比对。
     *
     * 在 IO 线程执行；调用方负责切回主线程。
     */
    suspend fun checkRemote(manifestUrl: String = REMOTE_MANIFEST_URL): CheckResult =
        withContext(Dispatchers.IO) {
            val remote = runCatching { fetchManifest(manifestUrl) }
                .getOrElse { return@withContext CheckResult.Error(describe(it)) }

            if (remote.hotApi > HotApi.VERSION) {
                return@withContext CheckResult.Error(
                    "插件要求加载协议 ${remote.hotApi}，当前壳仅支持 ${HotApi.VERSION}"
                )
            }

            if (HostInfo.versionCode < remote.minShellVersionCode) {
                return@withContext CheckResult.ShellTooOld(remote.minShellVersionCode, HostInfo.versionCode)
            }

            val installed = installedManifest()
            if (installed != null && remote.version == installed.version) {
                return@withContext CheckResult.UpToDate
            }

            CheckResult.UpdateAvailable(remote)
        }

    private fun fetchManifest(url: String): HotManifest {
        val request = Request.Builder().url(url).get().build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("empty body")
            json.decodeFromString(HotManifest.serializer(), body)
        }
    }

    // -------------------------------------------------------------------------
    // 下载与安装
    // -------------------------------------------------------------------------

    /**
     * 下载并校验插件，通过后落到 [hotDir] 并写入 installed manifest。
     *
     * 校验失败会删掉临时文件（以及任何已落地的半成品），不留下可加载的污染文件。
     *
     * @param onProgress 已下载字节数回调，用于 UI 进度展示；在主线程外调用。
     * @return 成功返回落地的 APK 文件。
     */
    suspend fun downloadAndPrepare(
        manifest: HotManifest,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // 1) 参数合法性：文件名不能带路径分隔符，避免 manifest 被用来做路径穿越
            require(manifest.fileName.isNotBlank()) { "fileName is blank" }
            require(manifest.fileName == File(manifest.fileName).name) {
                "fileName must not contain path separators: ${manifest.fileName}"
            }
            require(manifest.fileName.startsWith(PLUGIN_FILE_PREFIX) &&
                    manifest.fileName.endsWith(PLUGIN_FILE_SUFFIX)) {
                "fileName must look like ${PLUGIN_FILE_PREFIX}*.apk: ${manifest.fileName}"
            }
            require(manifest.sha256.length == 64) { "sha256 must be 64 hex chars" }

            val tmp = cacheDir.resolve("${manifest.fileName}.tmp").toFile()
            tmp.delete()

            // 2) 下载
            val expectedSize = downloadTo(manifest.url, tmp, onProgress)

            // 3) SHA-256
            val actual = sha256Of(tmp)
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                tmp.delete()
                error("sha256 mismatch: expected ${manifest.sha256}, got $actual")
            }
            WeLogger.i(TAG, "sha256 ok (${actual.take(12)}…), size=$expectedSize")

            // 4) 签名一致性：插件必须与壳同签名，否则拒绝
            verifySameSignature(tmp) || run {
                tmp.delete()
                error("signature mismatch: plugin is not signed with the shell's certificate")
            }

            // 5) 原子落盘（同分区 move，失败时原文件不受影响）
            val dst = hotDir.resolve(manifest.fileName).toFile()
            runCatching { dst.delete() }
            tmp.toPath().moveTo(dst.toPath(), overwrite = true)

            // 6) 记录已安装。写失败视为安装失败：宁可下次重下，也不要状态与实际文件不一致
            writeManifest(installedManifestFile.toPath(), manifest)

            WeLogger.i(TAG, "plugin v${manifest.version} prepared at ${dst.absolutePath}")
            dst
        }
    }

    private fun downloadTo(url: String, tmp: File, onProgress: (Long, Long) -> Unit): Long {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} while downloading plugin")
            val body = resp.body ?: error("empty response body")
            val total = body.contentLength()
            if (total > MAX_APK_BYTES) error("plugin too large: $total bytes")

            var written = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        written += n
                        if (written > MAX_APK_BYTES) error("plugin exceeds size cap")
                        output.write(buf, 0, n)
                        onProgress(written, total)
                    }
                }
            }
            if (written == 0L) error("downloaded 0 bytes")
            return written
        }
    }

    /**
     * 校验插件签名与当前壳签名一致。
     *
     * 两边都用 `GET_SIGNING_CERTIFICATES` 取签名集合（Android 9+ 的 SigningInfo；
     * 低版本退到 `signatures`，理论最低支持 21）。取到的证书字节做集合交集，
     * 只要有一枚相同即视为同源（覆盖签名轮换/多签名场景）。
     *
     * 任一环节取不到签名信息时返回 false —— 宁可不装，也不放行未验证的包。
     */
    private fun verifySameSignature(pluginApk: File): Boolean {
        val pm = HostInfo.application.packageManager
        val shellSigs = signaturesOfPackage(pm, PackageNames.MODULE) ?: run {
            WeLogger.w(TAG, "cannot read shell signatures, refusing plugin")
            return false
        }
        val pluginSigs = signaturesOfArchive(pm, pluginApk) ?: run {
            WeLogger.w(TAG, "cannot read plugin signatures, refusing plugin")
            return false
        }
        val matched = shellSigs.intersect(pluginSigs).isNotEmpty()
        WeLogger.i(TAG, "signature check: shell=${shellSigs.size} plugin=${pluginSigs.size} matched=$matched")
        return matched
    }

    private fun signaturesOfPackage(pm: PackageManager, packageName: String): Set<String>? =
        runCatching {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo
            if (signing != null) {
                val signers = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                signers.map { it.toByteArray().toHex() }.toSet()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray().toHex() }?.toSet()
            }
        }.getOrNull()

    private fun signaturesOfArchive(pm: PackageManager, apk: File): Set<String>? =
        runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: error("getPackageArchiveInfo returned null")
            val signing = info.signingInfo
            if (signing != null) {
                val signers = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                signers.map { it.toByteArray().toHex() }.toSet()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray().toHex() }?.toSet()
            }
        }.getOrNull()

    /**
     * 覆盖安装后核对签名是否与本地记录一致，并清理旧版本 APK。
     *
     * 下载校验用；单独暴露是为了让「手动把 APK 塞进 hot/ 目录」的路径也能复用同一套判断。
     */
    fun cleanupOldApks(keepFileName: String) {
        runCatching {
            hotDir.toFile().listFiles()?.forEach { f ->
                if (f.name.endsWith(PLUGIN_FILE_SUFFIX) && f.name != keepFileName) {
                    WeLogger.i(TAG, "removing stale plugin apk ${f.name}")
                    f.delete()
                }
            }
        }.onFailure { WeLogger.w(TAG, "cleanup old apks failed", it) }
    }

    // -------------------------------------------------------------------------
    // 重启
    // -------------------------------------------------------------------------

    /**
     * 强杀微信并重新拉起 LauncherUI，使新插件生效。
     *
     * 需要 root（走 libsu）。失败通过回调回报，UI 不静默。
     */
    fun restartHost(onResult: (Boolean, String?) -> Unit) {
        val userId = android.os.Process.myUid() / 100000
        val hostPkg = PackageNames.WECHAT
        runCatching {
            Shell.cmd(
                "am force-stop --user $userId $hostPkg",
                "am start --user $userId -n $hostPkg/${PackageNames.WECHAT}.ui.LauncherUI"
            ).submit { result ->
                if (result.isSuccess) {
                    onResult(true, null)
                } else {
                    val msg = (result.out + result.err).joinToString("\n").ifBlank { "无法启动微信" }
                    onResult(false, msg)
                }
            }
        }.onFailure { onResult(false, it.message ?: it.javaClass.simpleName) }
    }

    // -------------------------------------------------------------------------
    // manifest 读写
    // -------------------------------------------------------------------------

    private fun readManifest(path: java.nio.file.Path): HotManifest? = runCatching {
        if (!path.exists() || !path.isRegularFile()) return null
        json.decodeFromString(HotManifest.serializer(), path.readText())
    }.onFailure {
        WeLogger.w(TAG, "failed to read manifest $path", it)
    }.getOrNull()

    private fun writeManifest(path: java.nio.file.Path, manifest: HotManifest) {
        path.writeText(json.encodeToString(HotManifest.serializer(), manifest))
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private fun makeHost(): HotHost = object : HotHost {
        override val appContext: Context get() = HostInfo.application
        override val hostPackage: String get() = HostInfo.packageName
        override val shellVersionCode: Long get() = HostInfo.versionCode
        override val hostClassLoader: ClassLoader get() = javaClass.classLoader!!
        override fun log(message: String) = WeLogger.i(TAG, message)
        override fun prefs(): HotPrefs = HotPrefsImpl("legacy")
        override fun track(handle: HotHandle) {
            /* 当前壳不做运行时卸载，句柄仅登记不回收 */
        }
    }
}
