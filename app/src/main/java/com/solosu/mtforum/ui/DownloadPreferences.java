package com.solosu.mtforum.ui;

import android.content.Context;

/** User choice for attachment/file links. */
public final class DownloadPreferences {
    private static final String PREFS = "download_preferences";
    private static final String KEY_MODE = "file_download_mode";
    public static final int MODE_IN_APP = 0;
    public static final int MODE_BROWSER = 1;

    private DownloadPreferences() {}

    public static int getMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, MODE_IN_APP);
    }

    public static void setMode(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MODE, mode == MODE_BROWSER ? MODE_BROWSER : MODE_IN_APP).apply();
    }

    public static String label(Context context) {
        return getMode(context) == MODE_BROWSER ? "跳转系统浏览器" : "应用内打开与下载";
    }
}
