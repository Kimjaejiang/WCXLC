package com.Johnny.wcx.hot;

import android.content.Context;

public interface HotHost {
    Context getAppContext();
    String getHostPackage();
    long getShellVersionCode();
    ClassLoader getHostClassLoader();
    void log(String message);
    HotPrefs prefs();
    void track(HotHandle handle);
}
