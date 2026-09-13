package com.Johnny.wcx.constants

import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption

object Preferences {

    const val VERBOSE_LOG = "verbose_log"
    const val NO_DEX_RESOLVE = "no_dex_resolve"
    const val SHOW_STARTUP_TOAST = "toast_startup"
    const val RESET_DEX_ON_HOT_UPDATE = "reset_dex_on_hot_upd"
    const val MATCH_GENERIC_WXID_EXP = "match_generic_wxid"

    // Settings UI theming
    const val THEME_MODE = "settings_theme_mode"
    const val THEME_CUSTOM_COLOR = "settings_theme_custom_color"
    const val THEME_DYNAMIC_WALLPAPER = "settings_theme_dynamic_wallpaper"
    const val THEME_PALETTE_STYLE = "settings_theme_palette_style"
    const val THEME_COLOR_SPEC = "settings_theme_color_spec"
    const val THEME_SEED_COLOR = "settings_theme_seed_color"
    const val THEME_APPLY_TO_WECHAT = "settings_theme_apply_to_wechat"

    // Cross-process cached device info (written by main app, read by WeChat process)
    const val CACHED_LSP_ENVIRONMENT = "cached_lsp_environment"
    const val CACHED_LSP_API_VERSION = "cached_lsp_api_version"

    // 框架门禁结果（由注入侧写入，微信进程内 UI 读取）
    const val FRAMEWORK_GATE_PASSED = "framework_gate_passed"
    const val FRAMEWORK_GATE_REASON = "framework_gate_reason"
    const val FRAMEWORK_DETECTED = "framework_detected"

    // 后台静默检查到的可用插件版本（功能列表读它显示角标）。空串表示无已知新版。
    const val HOT_UPDATE_AVAILABLE_VERSION = "hot_update_available_version"

    var verboseLog by prefOption(VERBOSE_LOG, false)
    var noDexResolve by prefOption(NO_DEX_RESOLVE, false)
    var showStartupToast by prefOption(SHOW_STARTUP_TOAST, false)
    var resetDexCacheOnHotUpdate by prefOption(RESET_DEX_ON_HOT_UPDATE, false)

    // ALWAYS check whether sender is group chat!!!
    var matchGenericWxIdExp by prefOption(MATCH_GENERIC_WXID_EXP, true)

    // use this when Google fucked up itself again
//    var useActivityInsteadOfDialog: Boolean
//        get() = false
//        set(value) { WePrefs.putBool(USE_ACTIVITY_INSTEAD_OF_DIALOG, value) }

    /**
     * 上次注入是否通过了框架门禁（只放行 LSPosed）。
     *
     * 默认返回 true：主进程首次启动、还没被注入过时，不该因为读不到标记
     * 就声称"环境不支持"。只有注入侧明确写过 false 才算拒绝。
     */
    fun frameworkGatePassed(): Boolean =
        runCatching { WePrefs.getBoolOrDef(FRAMEWORK_GATE_PASSED, true) }.getOrDefault(true)

    /** 门禁拒绝原因，未拒绝时为空串。 */
    fun frameworkGateReason(): String =
        runCatching { WePrefs.getStringOrDef(FRAMEWORK_GATE_REASON, "") }.getOrDefault("")

    /** 检测到的框架描述，用于排查。 */
    fun frameworkDetected(): String =
        runCatching { WePrefs.getStringOrDef(FRAMEWORK_DETECTED, "") }.getOrDefault("")

    /**
     * 后台静默检查发现的可用插件版本，无新版（或尚未检查）时为空串。
     *
     * 只用于功能列表的角标提示；对话框打开时会重新检查，不依赖这个值。
     */
    fun hotUpdateAvailableVersion(): String =
        runCatching { WePrefs.getStringOrDef(HOT_UPDATE_AVAILABLE_VERSION, "") }.getOrDefault("")

    fun setHotUpdateAvailableVersion(version: String) {
        runCatching { WePrefs.putString(HOT_UPDATE_AVAILABLE_VERSION, version) }
    }
}
