package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;

/** 同帖通知批量已读设置；默认关闭，保持逐条阅读语义。 */
public final class SameThreadReadManager {
    private static final String PREFS = "same_thread_read_settings";
    private static final String KEY_ENABLED = "enabled";

    private SameThreadReadManager() { }

    public static boolean isEnabled(Context context) {
        return context != null && context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
}
