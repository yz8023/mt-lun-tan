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
     * 正文图片位置。
     * true  = 图片留在正文里原来的位置，图文混排；
     * false = 全部抽出来汇总到帖子底部的横滑图廊（v2.2 的行为）。
     *
     * <p><b>build87：默认值改回 false。</b>
     * build68 起默认改成「原位」，理由是图文混排更贴近原站观感。但那条路实际不出图：
     * 它依赖 {@code Html.fromHtml} + {@code createInlineImageGetter} + Glide 三者配合，
     * 而同一批图片在 v2.2 的「抽离 + 底部图廊」下是正常的（用户实测 v2.2 可用、
     * 之后版本不行）。改动的同时还把 {@code galleryUrls} 的填充条件设成了
     * {@code !imagesInline}，于是底部图廊被一起关掉 —— 两条路都没图。
     *
     * <p>现在默认回到 v2.2 的抽离模式（图廊），「原位」仍可在设置里手动打开。
     * 之前硬编码 {@code return true} 是为了兼容老安装的偏好迁移，改为真实读偏好，
     * 老安装没有这条偏好时用下面的默认值 false，行为与 v2.2 一致。
     */
    public static boolean isImagesInline(Context c) {
        return sp(c).getBoolean(KEY_IMAGES_INLINE, false);
    }

    public static void setImagesInline(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_IMAGES_INLINE, v).apply();
    }
}
