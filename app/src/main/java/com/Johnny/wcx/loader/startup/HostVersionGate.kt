package com.Johnny.wcx.loader.startup

import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger

/**
 * 宿主版本门禁：微信版本低于最低支持版本时不注入。
 *
 * 背景：本模块大量 hook 依赖微信的类名、方法签名与控件层级。这些结构在小版本之间
 * 会变（8.0.77 → 8.0.78 就有若干处），而适配是按较高版本做的。低版本上强行注入
 * 往往不是「少几个功能」，而是命中错位的类/方法导致启动崩溃或卡死 ——
 * 那种故障现象和模块本身的 bug 难以区分，排查成本极高。
 *
 * 因此这里在注入入口直接拦下低版本：不加载任何 hook，微信照常启动，
 * 由 UI 提示用户升级微信。
 *
 * 判定只看 versionName（形如 "8.0.78"），不看 versionCode：
 * 微信的 versionCode 与 versionName 并非稳定一一对应，实测 8.0.76 与 8.0.78
 * 出现过同值，用它比较会误判。
 */
object HostVersionGate {

    private const val TAG = "HostVersionGate"

    /** 最低支持的微信版本（含）。 */
    private const val MIN_MAJOR = 8
    private const val MIN_MINOR = 0
    private const val MIN_PATCH = 78

    /** 展示用的最低支持版本。 */
    const val MIN_VERSION_TEXT = "$MIN_MAJOR.$MIN_MINOR.$MIN_PATCH"

    /** 低版本时的统一提示。 */
    const val UPDATE_HINT = "请更新微信到 $MIN_VERSION_TEXT 及以上"

    @Volatile
    var passed: Boolean = false
        private set

    /** 判定失败的原因，供 UI 展示；通过时为空。 */
    @Volatile
    var rejectReason: String? = null
        private set

    /** 检测到的宿主版本描述，无论通过与否都记录，便于排查。 */
    @Volatile
    var detected: String = "unknown"
        private set

    /**
     * 判定当前微信版本是否放行。
     *
     * 必须在任何 hook 装载之前、且在 [HostInfo.init] 之后调用。
     * 不通过时返回 false，调用方应当跳过后续全部功能加载。
     */
    fun check(): Boolean {
        val name = runCatching { HostInfo.versionName }.getOrDefault("")
        val code = runCatching { HostInfo.versionCode }.getOrDefault(0L)
        detected = "$name ($code)".trim()

        val parsed = parseVersion(name)
        if (parsed == null) {
            // 解析不出来时不拦：宁可放行让用户遇到真实问题，也不要把可用环境误判为不可用。
            passed = true
            rejectReason = null
            WeLogger.w(TAG, "unparsable host version '$name', gate passes by default")
            cacheToPrefs()
            return true
        }

        val ok = compareToMin(parsed) >= 0
        passed = ok
        rejectReason = if (ok) {
            null
        } else {
            "当前微信版本 $name 低于最低支持版本 $MIN_VERSION_TEXT，$UPDATE_HINT。"
        }

        if (!ok) {
            WeLogger.w(TAG, "rejected: $rejectReason")
        } else {
            WeLogger.i(TAG, "host version gate passed ($detected)")
        }

        cacheToPrefs()
        return ok
    }

    /**
     * 从 versionName 解析出三段版本号。
     *
     * 微信的 versionName 形如 "8.0.78"；个别渠道会带后缀（如 "8.0.78-play"），
     * 这里只取前三段数字，后缀忽略。解析不出三段时返回 null。
     */
    internal fun parseVersion(versionName: String): Triple<Int, Int, Int>? {
        val parts = versionName.trim().split('.')
        if (parts.size < 3) return null
        val major = parts[0].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val minor = parts[1].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val patch = parts[2].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }

    private fun compareToMin(v: Triple<Int, Int, Int>): Int {
        if (v.first != MIN_MAJOR) return v.first.compareTo(MIN_MAJOR)
        if (v.second != MIN_MINOR) return v.second.compareTo(MIN_MINOR)
        return v.third.compareTo(MIN_PATCH)
    }

    /**
     * 把判定结果写进 WePrefs，供微信进程内的 UI 读取。
     *
     * 与框架门禁同理：微信进程拿不到注入侧对象，只能靠这个跨进程缓存。
     */
    private fun cacheToPrefs() {
        runCatching {
            WePrefs.putBool(Preferences.HOST_GATE_PASSED, passed)
            WePrefs.putString(Preferences.HOST_GATE_REASON, rejectReason.orEmpty())
            WePrefs.putString(Preferences.HOST_DETECTED, detected)
        }.onFailure {
            WeLogger.e(TAG, "failed to cache host version gate result", it)
        }
    }
}
