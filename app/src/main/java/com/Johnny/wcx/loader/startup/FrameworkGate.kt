package com.Johnny.wcx.loader.startup

import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger

/**
 * 框架门禁：只允许真正的 LSPosed 运行本模块。
 *
 * 背景：免 root 方案（LSPatch 集成/本地模式、VirtualXposed、太极免 root 等）
 * 虽然也能让模块代码跑起来，但它们把模块 dex 直接塞进宿主 APK 的类加载体系，
 * 与本模块依赖的类加载拓扑不兼容 —— `HybridClassLoader` 的环检测就是为了兜住
 * 那类环境才加的。与其让用户在一个必然出问题的环境里反复踩坑，不如在入口
 * 直接拦下来，明确告诉对方换 LSPosed。
 *
 * 判定依据（libxposed 由框架自己上报，不依赖包名探测）：
 * - `frameworkName`：LSPosed 上报 "LSPosed"
 * - `apiLevel`：LSPosed 新版为 102；LSPatch 等停留在 101
 *
 * 为什么不用「是否装了 org.lsposed.manager」：装没装管理器与「当前是不是 LSPosed
 * 在注入」是两回事。用户可以同时装着管理器又用 LSPatch 打补丁运行，
 * 那种情况下包名探测会误判为通过。
 *
 * 这里刻意只做「基础判定」，即采信框架自报信息。它挡不住刻意伪造
 * frameworkName 的魔改框架，但能准确拦住所有主流的免 root 方案 ——
 * 那些方案没有理由去伪装这个字段。
 */
object FrameworkGate {

    private const val TAG = "FrameworkGate"

    /** 允许的框架名，大小写不敏感。 */
    private val ALLOWED_FRAMEWORKS = setOf("lsposed")

    /** LSPosed 新版 API 等级。低于它的基本都是免 root 方案。 */
    private const val API_LEVEL_LSPSED = 102

    @Volatile
    var passed: Boolean = false
        private set

    /** 判定失败的原因，供 UI 展示；通过时为空。 */
    @Volatile
    var rejectReason: String? = null
        private set

    /** 检测到的框架描述，无论通过与否都记录，便于排查。 */
    @Volatile
    var detected: String = "unknown"
        private set

    /**
     * 判定当前框架是否放行。
     *
     * 必须在任何 hook 装载之前调用。不通过时返回 false，
     * 调用方应当跳过后续全部功能加载。
     */
    fun check(bridge: IHookBridge?): Boolean {
        if (bridge == null) {
            // 拿不到 bridge 说明注入方式本身就异常（正常 LSPosed 一定提供）。
            passed = false
            rejectReason = "无法获取框架信息，请确认使用 LSPosed 并已激活模块"
            detected = "no-bridge"
            WeLogger.w(TAG, "rejected: hook bridge is null (framework did not provide it)")
            cacheToPrefs()
            return false
        }

        val name = runCatching { bridge.frameworkName }.getOrDefault("")
        val api = runCatching { bridge.apiLevel }.getOrDefault(0)
        val fwVer = runCatching { bridge.frameworkVersion }.getOrDefault("")
        detected = "$name $fwVer (API $api)"

        // frameworkName 是框架自报的核心标识，先看它。
        val nameOk = name.lowercase() in ALLOWED_FRAMEWORKS

        // API 等级作为佐证。LSPosed 新版本才有 102；101 是旧 API，
        // LSPatch 长期停在这一档。
        val apiOk = api >= API_LEVEL_LSPSED

        WeLogger.i(TAG, "framework detection: name=$name api=$api version=$fwVer nameOk=$nameOk apiOk=$apiOk")

        passed = nameOk && apiOk
        rejectReason = when {
            passed -> null
            !nameOk && !apiOk ->
                "检测到非 LSPosed 框架（$detected）。本模块仅支持 LSPosed，免 root 框架（LSPatch / VirtualXposed 等）无法正常运行。"
            !nameOk ->
                "当前框架「$name」不受支持。本模块仅支持 LSPosed。"
            else ->
                "框架 API 等级过低（$api < $API_LEVEL_LSPSED），当前为「$name $fwVer」。这通常是免 root 框架，请改用 LSPosed。"
        }

        if (!passed) {
            WeLogger.w(TAG, "rejected: $rejectReason")
        } else {
            WeLogger.i(TAG, "framework gate passed ($detected)")
        }

        cacheToPrefs()
        return passed
    }

    /**
     * 把判定结果写进 WePrefs，供微信进程内的 UI 读取。
     *
     * 微信进程拿不到 IHookBridge（那是注入侧对象），只能靠这个跨进程缓存。
     */
    private fun cacheToPrefs() {
        runCatching {
            WePrefs.putBool(Preferences.FRAMEWORK_GATE_PASSED, passed)
            WePrefs.putString(Preferences.FRAMEWORK_GATE_REASON, rejectReason.orEmpty())
            WePrefs.putString(Preferences.FRAMEWORK_DETECTED, detected)
        }.onFailure {
            WeLogger.e(TAG, "failed to cache framework gate result", it)
        }
    }
}
