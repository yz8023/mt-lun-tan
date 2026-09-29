package com.solosu.mtforum.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** 界面行为开关（build65 新增） */
public final class UiSettings {

    private static final String PREF = "app_settings";
    private static final String KEY_NAV_AUTO_HIDE = "nav_auto_hide";

    private UiSettings() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 下滑隐藏底栏、上滑显示（默认开） */
    public static boolean isNavAutoHide(Context c) {
        return sp(c).getBoolean(KEY_NAV_AUTO_HIDE, true);
    }

    public static void setNavAutoHide(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_NAV_AUTO_HIDE, v).apply();
    }
}
