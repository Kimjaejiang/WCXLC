package com.Johnny.wcx.hot

import android.content.Context
import com.Johnny.wcx.preferences.WePrefs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 热更新加载协议版本。
 *
 * 插件 manifest 里的 [HotManifest.hotApi] 必须 <= 此值，否则壳拒绝加载：
 * 高版本插件可能依赖壳尚未提供的 SPI，加载进去只会在运行时炸。
 */
object HotApi {
    const val VERSION = 1
}

/**
 * 远端 `hot-update.json` 的映射，同时也用于 `manifest-installed.json` 的持久化。
 *
 * 字段与文档一致：hotApi, version, minShellVersionCode, minShellVersionName,
 * fileName, url, sha256, entryClass, changelog。
 */
@Serializable
data class HotManifest(
    @SerialName("hotApi") val hotApi: Int,
    @SerialName("version") val version: String,
    /**
     * 要求的最低宿主 versionCode。
     *
     * 注意：微信的 versionCode 并不随小版本递增（实测 8.0.76 / 8.0.78 均为 3180），
     * 所以它只能卡大版本，区分不了 8.0.77 与 8.0.78。要卡小版本请用
     * [minShellVersionName]。
     */
    @SerialName("minShellVersionCode") val minShellVersionCode: Long = 0,
    /**
     * 要求的最低宿主版本名，如 "8.0.77"。
     *
     * 这是卡小版本的正确手段：[minShellVersionCode] 做不到，因为微信多次灰度
     * 复用同一个 versionCode。空字符串表示不限制。
     */
    @SerialName("minShellVersionName") val minShellVersionName: String = "",
    @SerialName("fileName") val fileName: String,
    @SerialName("url") val url: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("entryClass") val entryClass: String,
    @SerialName("changelog") val changelog: String = "",
)

/**
 * 宿主能力出口，注入给插件。
 *
 * 刻意只暴露最小集：插件拿到的是壳的 Context 与它在插件状态里的 handle，
 * 而不是整个模块的对象图。
 */
interface HotHost {

    /** 宿主 Application Context（微信进程内）。 */
    val appContext: Context

    /** 宿主包名，微信恒为 `com.tencent.mm`。 */
    val hostPackage: String

    /** 宿主（微信）versionCode，不是壳的。 */
    val shellVersionCode: Long

    /**
     * 加载插件用的 ClassLoader。
     *
     * 插件自己的类要走这个 loader 才会命中插件 dex；同时它已把模块 classloader 设成 parent，
     * 所以插件能正常解析 `de.robv.android.xposed.*` 与 `com.Johnny.wcx.*`。
     */
    val hostClassLoader: ClassLoader

    /** 写入微信日志，带热更新 tag 前缀，便于 grep。 */
    fun log(message: String)

    /** 插件私有持久化（按插件类名隔离）。 */
    fun prefs(): HotPrefs

    /**
     * 登记一个卸载动作。
     *
     * 插件只能通过它来清理 hook：壳在 [HotPlugin.onUnload] 之后会反向执行这些动作。
     * 当前壳不做运行时卸载（卸载只能靠重启进程），此机制为后续扩展预留。
     */
    fun track(handle: HotHandle)
}

/** 可回收句柄。 */
interface HotHandle {
    /** 撤销这个句柄；实现方必须幂等，重复调用不抛。 */
    fun dispose()
}

/** 插件私有键值存储。 */
interface HotPrefs {
    fun getString(key: String, def: String? = null): String?
    fun putString(key: String, value: String?): HotPrefs
    fun getBoolean(key: String, def: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean): HotPrefs
    fun getLong(key: String, def: Long = 0L): Long
    fun putLong(key: String, value: Long): HotPrefs
    fun contains(key: String): Boolean
    fun remove(key: String): HotPrefs
}

/**
 * 壳内核的 [HotPrefs] 实现：每个插件一份，键名前缀隔离。
 *
 * 用 MMKV（[WePrefs.default]）而不是 SharedPreferences，与模块其余部分保持一致。
 */
internal class HotPrefsImpl(
    private val prefix: String,
) : HotPrefs {

    private fun key(k: String) = "$PREFS_NS.$prefix.$k"

    override fun getString(key: String, def: String?): String? =
        WePrefs.getStringOrDef(key(key), def as String)

    override fun putString(key: String, value: String?): HotPrefs {
        if (value == null) WePrefs.remove(key(key)) else WePrefs.putString(key(key), value)
        return this
    }

    override fun getBoolean(key: String, def: Boolean): Boolean =
        WePrefs.getBoolOrDef(key(key), def)

    override fun putBoolean(key: String, value: Boolean): HotPrefs {
        WePrefs.putBool(key(key), value)
        return this
    }

    override fun getLong(key: String, def: Long): Long =
        WePrefs.getLongOrDef(key(key), def)

    override fun putLong(key: String, value: Long): HotPrefs {
        WePrefs.putLong(key(key), value)
        return this
    }

    override fun contains(key: String): Boolean = WePrefs.containsKey(key(key))

    override fun remove(key: String): HotPrefs {
        WePrefs.remove(key(key))
        return this
    }

    private companion object {
        const val PREFS_NS = "hot_plugin"
    }
}

/**
 * 插件入口 SPI。
 *
 * 实现类必须：public、有 public 无参构造（壳用 `Class.getDeclaredConstructor()` 反射实例化）。
 * 建议打 `@Keep` 防 R8 混淆删构造；manifest 的 `entryClass` 用混淆后的真实名字。
 */
interface HotPlugin {

    /**
     * 宿主初始化完成后调用，插件在此安装自己的 hook。
     *
     * 抛异常会被壳捕获并记日志，不会阻断模块其余功能。
     */
    fun onLoad(host: HotHost)

    /**
     * 预留卸载回调。当前壳不会主动调用（进程内卸载不可靠），插件可留空实现。
     */
    fun onUnload() {}
}
