package com.Johnny.wcx.loader.startup

import android.content.Context
import android.content.res.Resources
import com.tencent.mm.boot.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.core.FeaturesLoader
import com.Johnny.wcx.dynamic.LocalAdaptationEngine
import com.Johnny.wcx.dynamic.SelfHealingMonitor
import com.Johnny.wcx.loader.utils.ActivityProxy
import com.Johnny.wcx.loader.utils.ParcelableFixer
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.RuntimeConfig
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.invokeOriginal
import com.Johnny.wcx.utils.reflection.int

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        // 两条路径分开传，不要拼成一个字符串。
        //
        // 原先的写法是二选一拼串：默认 `"${HostInfo.versionName}${HostInfo.versionCode}"`，
        // 开关打开时改成 `"${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${CLIENT_VERSION_ARM64}"`。
        // 问题在于两条分支喂给的是**同一个** `host_version` 键 —— 于是模块一升级，
        // `DexCacheManager` 就判定「宿主版本变化」并清空全部缓存，日志也写成
        // "host version changed"。排查时会以为是微信换版本了，实际只是模块重编译。
        //
        // 现在拆成两个参数，各自的语义和日志都由 DexCacheManager 明确区分：
        // 微信版本变 → 必须清（类名重排）；模块版本变 → 只在用户开了开关时才清。
        DexCacheManager.init(
            hostVersion = "${HostInfo.versionName}${HostInfo.versionCode}",
            // 必须显式写全限定名：本文件顶部 `import com.tencent.mm.boot.BuildConfig`
            // 是**微信的** BuildConfig，裸写 `BuildConfig` 会拿到微信版本号
            // （实测拿到 8.0.783180），于是「模块版本变化」永远判定不出来，
            // 日志还会显示模块版本 = 微信版本，非常误导。
            moduleVersion = "${com.Johnny.wcx.BuildConfig.VERSION_NAME}" +
                    "${com.Johnny.wcx.BuildConfig.VERSION_CODE}",
            resetOnModuleUpdate = Preferences.resetDexCacheOnHotUpdate,
        )

        if (TargetProcesses.isInMain) {
            val appContext = context.applicationContext ?: context
            ActivityProxy.init(appContext)
            LocalAdaptationEngine.init(appContext)
            SelfHealingMonitor.init()

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs

            // fix up Jetpack Compose
            // fuck you google
            Resources::class.java.getDeclaredMethod("getString", int).hookBeforeDirectly {
                result = runCatching { invokeOriginal() }.getOrNull() ?: "null"
            }
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load hooks", it) }
    }

    private const val TAG = "WeLauncher"
}
