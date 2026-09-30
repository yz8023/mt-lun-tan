package com.solosu.mtforum.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** 界面行为开关（build65 新增） */
public final class UiSettings {

    private static final String PREF = "app_settings";
    private static final String KEY_NAV_AUTO_HIDE = "nav_auto_hide";
    private static final String KEY_CODE_WRAP = "code_wrap";
    private static final String KEY_AI_SUMMARY = "show_ai_summary";
    private static final String KEY_HIDDEN_INLINE = "hidden_content_inline";
    private static final String KEY_IMAGES_INLINE = "post_images_inline";

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

    /**
     * 代码块自动换行（默认开）。
     * 关 = 横向滚动、不折行（适合看缩进严格的代码）；
     * 开 = 自动换行、整段可见（手机上更好读）。
     */
    public static boolean isCodeWrap(Context c) {
        return sp(c).getBoolean(KEY_CODE_WRAP, true);
    }

    public static void setCodeWrap(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_CODE_WRAP, v).apply();
    }

    /** 帖子页是否显示「AI 总结」按钮（默认关，避免误会以为是论坛自带功能） */
    public static boolean isAiSummaryVisible(Context c) {
        return sp(c).getBoolean(KEY_AI_SUMMARY, false);
    }

    public static void setAiSummaryVisible(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AI_SUMMARY, v).apply();
    }

    /**
     * 隐藏内容显示位置（默认「原处」）。
     * true  = 就地展开，正文里不留占位胶囊；
     * false = 正文留占位、完整内容放帖子底部（旧行为）。
     */
    public static boolean isHiddenContentInline(Context c) {
        return sp(c).getBoolean(KEY_HIDDEN_INLINE, true);
    }

    public static void setHiddenContentInline(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_HIDDEN_INLINE, v).apply();
    }

    /**
     * 正文图片位置（默认「原位」）。
     * true  = 图片留在正文里原来的位置，图文混排；
     * false = 全部抽出来汇总到帖子底部的横滑图廊（旧行为）。
     */
    public static boolean isImagesInline(Context c) {
        // Full-size images are now always rendered at their original position. Keeping the
        // method avoids a preference migration crash for existing installations.
        return true;
    }

    public static void setImagesInline(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_IMAGES_INLINE, v).apply();
    }
}
