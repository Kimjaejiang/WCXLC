package com.Johnny.wcx.hot;

public interface HotPrefs {
    String getString(String key, String def);
    HotPrefs putString(String key, String value);
    boolean getBoolean(String key, boolean def);
    HotPrefs putBoolean(String key, boolean value);
    long getLong(String key, long def);
    HotPrefs putLong(String key, long value);
    boolean contains(String key);
    HotPrefs remove(String key);
}
