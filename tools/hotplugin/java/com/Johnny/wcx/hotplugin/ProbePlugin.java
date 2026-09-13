package com.Johnny.wcx.hotplugin;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import com.Johnny.wcx.hot.HotHandle;
import com.Johnny.wcx.hot.HotHost;
import com.Johnny.wcx.hot.HotPlugin;
import com.Johnny.wcx.hot.HotPrefs;

/**
 * 热更新验证插件（Java 版）。
 *
 * 只证明「外部 APK 被壳加载并回调 onLoad」，不做任何 hook。
 * 所有验证点通过 host.log() 输出，用 logcat 抓 PROBE 前缀。
 *
 * 用 Java 而非 Kotlin：手工构建链路只需 JDK 自带 javac + SDK 的 d8，
 * 不依赖 kotlinc 的完整依赖树。
 */
public class ProbePlugin implements HotPlugin {

    private HotHost host;

    @Override
    public void onLoad(HotHost host) {
        this.host = host;

        host.log("=== PROBE PLUGIN LOADED (from external apk) ===");
        host.log("PROBE marker=HOTUPDATE_OK build=1");
        host.log("PROBE hostPackage=" + host.getHostPackage());
        host.log("PROBE shellVersionCode=" + host.getShellVersionCode());
        host.log("PROBE androidSdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE);
        host.log("PROBE pluginPid=" + Process.myPid());

        // 证明 DexClassLoader 的 parent 链路正确：插件能解析到壳的类与 Xposed API。
        // 这两条通了，插件才可能真的装 hook。
        ClassLoader cl = host.getHostClassLoader();
        host.log("PROBE resolve HotApi=" + canLoad(cl, "com.Johnny.wcx.hot.HotApi")
                + " XposedBridge=" + canLoad(cl, "de.robv.android.xposed.XposedBridge")
                + " WeLogger=" + canLoad(cl, "com.Johnny.wcx.utils.WeLogger"));

        // 证明 host 暴露的能力真的可用，而不是空实现
        Context ctx = host.getAppContext();
        host.log("PROBE appContext class=" + ctx.getClass().getName());

        HotPrefs prefs = host.prefs();
        String key = "probe_counter";
        long n = prefs.getLong(key, 0L);
        prefs.putLong(key, n + 1);
        host.log("PROBE prefs roundtrip: previous=" + n + " now=" + prefs.getLong(key, -1L));

        final boolean[] tracked = {false};
        host.track(new HotHandle() {
            @Override
            public void dispose() {
                tracked[0] = true;
            }
        });
        host.log("PROBE track accepted=true");

        host.log("=== PROBE PLUGIN onLoad COMPLETE ===");
    }

    @Override
    public void onUnload() {
        if (host != null) host.log("PROBE onUnload called");
    }

    private static boolean canLoad(ClassLoader cl, String name) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
