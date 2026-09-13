package com.Johnny.wcx.hot

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.topjohnwu.superuser.Shell
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.loader.utils.HybridClassLoader
import com.Johnny.wcx.utils.reflection.ClassLoaders
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

    /**
     * 远端 manifest 地址。
     *
     * 用**固定 tag**（`hot-latest`）而不是 `releases/latest` 别名：
     * 仓库里的 Release 还有模块整包（时间戳 tag），而 `latest` 指向
     * “最近发布的那个”。发一次模块整包就会把 `latest` 抢走，热更新
     * 地址随之 404。固定 tag 则永远指向插件发布，互不干扰。
     *
     * 发布插件时：新建 tag 为 `v<版本号>` 的 Release 放 APK，
     * 再把 hot-update.json 覆盖到本 tag（`hot-latest`）的 Release 里。
     * APK 不用本 tag 是为了保留历史版本：若每次发布都覆盖同一个
     * Release，旧二进制就再也下不到，出问题无法回滚。
     *
     * 发布时必须取消 GitHub 默认勾选的 "Set as the latest release"：
     * 模块自身的整包更新（AppUpdater.checkForUpdate）读的是
     * `releases/latest`，一旦插件 Release 被标成 latest，那个接口就
     * 返回插件包，解析不出 12 位时间戳版本号，模块会永远以为已是最新、
     * 再也收不到整包更新。
     *
     * 仓库：https://github.com/Kimjaejiang/WCXLC
     */
    const val REMOTE_MANIFEST_URL =
        "https://github.com/Kimjaejiang/WCXLC/releases/download/hot-latest/hot-update.json"

    /** 加载协议版本，与 [HotApi.VERSION] 同源，此处只做读取别名。 */
    val hotApiVersion: Int get() = HotApi.VERSION

    private const val INSTALLED_MANIFEST = "manifest-installed.json"
    private const val PLUGIN_FILE_PREFIX = "plugin-"
    private const val PLUGIN_FILE_SUFFIX = ".apk"

    /** 微信内部目录下的落地文件名，规避 W^X。 */
    private const val INTERNAL_CURRENT_APK = "plugin-current.apk"

    /** 单次下载上限，防 manifest 被篡改成超大文件把磁盘写满。 */
    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    /**
     * 启动时加载插件的最大等待时间。
     *
     * 类加载可能死循环而非报错（微信 loader 的 findClass 会重试 loadClass），
     * 超时后主线程不再等待，避免微信启动被拖死。正常加载只需几十毫秒。
     */
    private const val LOAD_TIMEOUT_MS = 5_000L

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
        data class ShellTooOld(
            val required: Long,
            val current: Long,
            /** 要求的最低版本名（如 "8.0.77"），空表示 manifest 未声明。 */
            val requiredName: String = "",
            /** 当前宿主版本名（如 "8.0.78"）。 */
            val currentName: String = "",
        ) : CheckResult

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
     *
     * 为什么用独立线程 + 超时：类加载阶段可能进入**无限递归**而非抛异常
     * （微信的 BaseDexClassLoader 子类在 findClass 失败时会重试 loadClass，
     * 若父链回指自身就成环）。那种情况下 runCatching 完全无效 ——
     * 它只捕异常，捕不了死循环，主线程会 100% CPU 卡死、微信起不来。
     * 这里用守护线程 + 超时把风险隔离开：超时就放弃等待，微信照常启动。
     */
    fun loadInstalled() {
        val worker = Thread({ runCatching { loadInstalledInternal() }
            .onFailure { WeLogger.e(TAG, "hot update loadInstalled failed (ignored)", it) } }, "wcx-hot-loader")
        worker.isDaemon = true
        worker.start()
        // 主线程最多等 LOAD_TIMEOUT_MS。超时不中断线程（中断对死循环的类加载无用），
        // 只是不再等它——微信因此不会被拖死。
        worker.join(LOAD_TIMEOUT_MS)
        if (worker.isAlive) {
            WeLogger.w(TAG, "plugin load exceeded ${LOAD_TIMEOUT_MS}ms, giving up wait (host startup continues)")
        }
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

        // 门禁：宿主版本不满足插件下限就跳过。已装的插件在壳降级/换低版微信后
        // 可能不再适配，这里主动拦住，而不是加载进去再在 hook 里炸。
        if (!shellMeetsRequirement(manifest)) {
            WeLogger.w(
                TAG,
                "skip plugin v${manifest.version}: ${describeVersionGap(manifest)}"
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

        // Android 14+ 拒绝从可写文件加载 dex（"Writable dex file ... is not allowed"）。
        // 拷进来的文件默认可写，必须去掉写位；失败则 ART 直接抛 SecurityException。
        if (internalApk.canWrite() && !internalApk.setReadOnly()) {
            WeLogger.w(TAG, "staged apk is still writable: ${internalApk.absolutePath}, dex load may be rejected")
        }

        // ART 为 code_cache 下的 dex 写入 profile/vdex 的位置固定为 <dex 所在目录>/oat/<abi>/。
        // 该目录不存在时 ART 只打一行
        //   DexLoadReporter: Could not create the profile directory: .../oat/xxx.cur.prof
        // 然后静默地一个类都加载不出来，对外表现为 ClassNotFoundException。
        val oatDir = internalDir.resolve("oat").resolve(artAbiDirName())
        runCatching {
            oatDir.toFile().mkdirs()
        }.onFailure {
            WeLogger.w(TAG, "failed to prepare oat dir $oatDir, plugin may fail to load", it)
        }

        val host = makeHost()
        val loader = runCatching {
            // parent 必须是 ClassLoaders.MODULE（模块 APK 自己的 classloader），不能是
            // moduleParentClassLoader，也不能是 HybridClassLoader。三者的区别很致命：
            //
            // - moduleParentClassLoader 存的是 MODULE 的**上游**（见 UnifiedEntryPoint:
            //   `HybridClassLoader.moduleParentClassLoader = self.parent`），看不到模块 APK 里的类；
            // - HybridClassLoader 虽然叫“三路路由”，但它的路由表里**没有 MODULE 自己**
            //   （只有 boot / moduleParent / host / additional），所以同样加载不到 SPI；
            //   更糟的是它会回退到 hostClassLoader（微信的 loader），而微信的
            //   loader（混淆名 z83）在 findClass 失败时会重试 loadClass，
            //   形成 z83.findClass → loadClass → z83.findClass 的**无限递归**，
            //   主线程 100% CPU、栈涨到上千帧，表现为微信卡死无法启动。
            //
            // - ClassLoaders.MODULE 自己就能加载 com.Johnny.wcx.*（含 HotPlugin 接口）；
            //   且 MODULE 的 parent 已被改写成 HybridClassLoader，
            //   所以插件仍能解析 de.robv.android.xposed.* 与微信的类，
            //   同时**不会**走进 hostClassLoader 那条引发递归的路。
            DexClassLoader(
                internalApk.absolutePath,
                null,
                null,
                ClassLoaders.MODULE
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

            if (!shellMeetsRequirement(remote)) {
                return@withContext CheckResult.ShellTooOld(
                    required = remote.minShellVersionCode,
                    current = HostInfo.versionCode,
                    requiredName = remote.minShellVersionName,
                    currentName = HostInfo.versionName,
                )
            }

            val installed = installedManifest()
            // 必须是「远端比已装的新」才算更新，不能只判不等。
            // 远端回滚/重新发布旧版时（例如把 1.0.0 发到通道上，而设备
            // 已装 1.0.2），只判不等会提示「发现新版本 1.0.0」并把用户降级。
            if (installed != null && compareVersionName(remote.version, installed.version) <= 0) {
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
     * 需要 root（走 libsu）。
     *
     * 注意本函数**运行在微信进程内**（热更新入口是微信里的设置页），这决定了
     * 命令的写法：不能用 `Shell.cmd("am force-stop …", "am start …").submit{}`。
     * 那种写法是 MainActivity（模块自己的进程）里的形态，那里杀微信 ≠ 杀自己，
     * 回调能正常回来；而在这里 `am force-stop com.tencent.mm` 杀的就是本进程，
     * 连同 libsu 的 shell 子进程一起被 SIGKILL，第二条命令根本没机会执行，
     * submit 的回调也永远不会触发 —— 对外表现就是「重启微信失败」。
     *
     * 因此命令必须**脱离本进程**执行：`nohup sh -c '…' &` 让 sh 成为会话首进程的
     * 孤儿后代，微信被杀后由 init 收养，继续把 force-stop → start 跑完。
     * 前后各留 sleep 是给 am 一点时间：force-stop 是异步的，
     * 立刻 start 可能被仍在退出的旧进程吃掉。
     *
     * 回报语义随之改变：本进程即将被杀，无法得知重启是否真的成功，
     * 所以只回报「指令已发出」。拿不到结果比假装有结果更诚实。
     */
    fun restartHost(onResult: (Boolean, String?) -> Unit) {
        // 先问 libsu 有没有 root：没有就直接给明确原因，而不是等 shell 命令失败
        // 再解析 stderr。isAppGrantedRoot 返回 null 表示 libsu 未初始化（此时
        // 视为未知，放行让 Shell 自己去试）。
        if (Shell.isAppGrantedRoot() == false) {
            WeLogger.w(TAG, "restart refused: no root granted to ${PackageNames.WECHAT}")
            onResult(false, "微信未获得 root 权限，请先在 KernelSU 中授权")
            return
        }

        val userId = android.os.Process.myUid() / 100000
        val hostPkg = PackageNames.WECHAT
        val script = "sleep 1; am force-stop --user $userId $hostPkg; " +
                "sleep 2; am start --user $userId -n $hostPkg/${PackageNames.WECHAT}.ui.LauncherUI"

        val launched = runCatching {
            // exec() 而非 submit{}：要在本进程被杀之前确认命令已交出去。
            // 这里等的是 sh 把 nohup 派生出去就返回，不会等到重启跑完。
            Shell.cmd("nohup sh -c '$script' >/dev/null 2>&1 &").exec()
        }

        launched.fold(
            onSuccess = { result ->
                if (result.isSuccess) {
                    WeLogger.i(TAG, "restart command dispatched: $script")
                    onResult(true, null)
                } else {
                    val msg = (result.out + result.err).joinToString("\n")
                        .ifBlank { "无法执行重启命令（是否已授予微信 root？）" }
                    WeLogger.w(TAG, "restart command rejected: $msg")
                    onResult(false, msg)
                }
            },
            onFailure = { onResult(false, it.message ?: it.javaClass.simpleName) }
        )
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

    /**
     * 比较两个点分版本名，返回 current 相对 required 的方向。
     *
     * 微信版本名形如 "8.0.77"，纯数字，点分三段。逐段数值比较，
     * 不足的段视为 0（"8.0" 与 "8.0.0" 等价），非数字段直接当 0
     * 处理——这种情况下宁可不拦，也不要因为解析异常把正常用户挡在外面。
     */
    private fun compareVersionName(current: String, required: String): Int {
        val a = current.split('.')
        val b = required.split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * 宿主是否满足 manifest 声明的版本下限。
     *
     * 两个条件都要满足：
     * - [HotManifest.minShellVersionName]：卡小版本，如 "8.0.77"。
     * - [HotManifest.minShellVersionCode]：卡大版本，兜底用。
     *
     * 为什么不只用 versionCode：微信不随小版本递增 versionCode
     * （实测 8.0.76 与 8.0.78 均为 3180），单靠它区分不了 8.0.77 与 8.0.78。
     */
    private fun shellMeetsRequirement(manifest: HotManifest): Boolean {
        val name = manifest.minShellVersionName
        if (name.isNotBlank() && compareVersionName(HostInfo.versionName, name) < 0) return false
        if (HostInfo.versionCode < manifest.minShellVersionCode) return false
        return true
    }

    /** 描述不满足的原因，用于日志与 UI 提示。 */
    private fun describeVersionGap(manifest: HotManifest): String =
        "requires shell >= ${manifest.minShellVersionName.ifBlank { "code ${manifest.minShellVersionCode}" }}, " +
            "current v${HostInfo.versionName} (code ${HostInfo.versionCode})"

    /**
     * ART 在 `<dex-dir>/oat/<name>/` 下存放 vdex 与 profile，<name> 取自运行时 ABI。
     * 映射规则来自 art/runtime/instruction_set.cc：arm64-v8a→arm64、armeabi-v7a→arm、
     * x86_64→x86_64、x86→x86。
     *
     * 只映射实际可能遇到的取值，未知值回退为首个 ABI 原名：建错目录顶多让 ART 自己再建一次，
     * 不会比完全不建更差。
     */
    private fun artAbiDirName(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return "arm64"
        return when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> abi
        }
    }

    /**
     * 后台静默检查一次，把「有无可用新版」记进偏好供 UI 读角标。
     *
     * 与 [checkRemote] 的区别：不弹界面、不下载、失败静默。
     * 只处理 [CheckResult.UpdateAvailable]：其余情况（已是最新、微信版本
     * 过低、网络失败）都清空标记，避免残留一个已经不适用的旧版本号。
     */
    suspend fun checkRemoteSilently(manifestUrl: String = REMOTE_MANIFEST_URL) {
        val available = when (val r = checkRemote(manifestUrl)) {
            is CheckResult.UpdateAvailable -> r.manifest.version
            else -> ""
        }
        Preferences.setHotUpdateAvailableVersion(available)
        WeLogger.i(TAG, "silent check: available='$available'")
    }

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
