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
    /**
     * build90/v5.9: 用户是否显式设置过「正文图片原位显示」。
     *
     * <p>{@code getBoolean(key, default)} 的默认值<b>只在这条偏好从来没被写过时</b>
     * 生效。v5.5~v5.7 期间默认值是 {@code false}，设备上很可能已经躺着一条
     * {@code post_images_inline=false}（误触一下抽屉开关就会落盘），
     * 于是 v5.8 把默认值改成 {@code true} 对那些设备<b>永远不生效</b>，
     * 用户会一直看到帖子底部那个图廊 —— 这正是「改了两版还是老样子」的原因。
     *
     * <p>加一条「是否显式设置过」的标记：没标记就一律用新默认值 {@code true}，
     * 只有用户本人在抽屉里拨过开关才听他的。
     */
    private static final String KEY_IMAGES_INLINE_SET = "post_images_inline_user_set";
    /**
     * build92: 帖子正文是否用 WebView 原样渲染站点下发的 HTML。
     *
     * <p>默认开。以前把正文拆成 TextView 重新排版，站点模板里的图片位置信息
     * 全丢了（克米 mobile 模板把「插进正文的图」放在 {@code .comiis_messages} 里，
     * 位置就是作者插入的位置；解析器却只把图 URL 收集起来另放）。用户原话：
     * 「应该直接套用网页原帖内容不要解析」。
     *
     * <p>关掉则退回旧的 TextView 解析渲染（保留 BBCode 复制、代码块卡片等
     * 依赖文本的能力）。
     */
    private static final String KEY_WEB_RENDER = "post_web_render";

    /**
     * build95: 帖子正文里点链接怎么打开。
     *
     * <p>internal = 站内帖子链接走应用内详情页，其余链接（站外、站内非帖子页）
     * 交给系统浏览器；external = 一律交给系统浏览器。
     */
    private static final String KEY_LINK_OPEN = "post_link_open";
    /** build95: 是否在界面顶部显示 FPS */
    private static final String KEY_SHOW_FPS = "show_fps";

    /**
     * build99: 是否向系统申请设备支持的最高刷新率。
     *
     * <p>默认<b>开</b>。原因：多数国产 ROM 对没有主动申请的第三方 App 一律按
     * 60Hz 合成，用户看到的就是「论坛 FPS 锁 60」——那不是 App 的限制，
     * 但只有 App 主动申请，系统才会给高刷。想省电可以在侧边栏关掉。
     */
    private static final String KEY_HIGH_REFRESH = "high_refresh_rate";

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
     * 之前硬编码 {@code return true} 是为了兼容老安装的偏好迁移，改为真实读偏好。
     *
     * <p><b>build90：默认值从 false 改回 true（原位显示）。</b>
     * v5.5 把它默认成 false（全部抽到帖子底部图廊），用户实测后的反馈是
     * 「图片不在正文排版处正常显示，而是全部解析到正文底部」—— 抽离+图廊
     * 并不是他要的观感，图片应该跟在正文排版里。抽屉里「正文图片原位显示」
     * 这一项现在也真的能用了（v5.7 接上的），想用底部图廊仍然可以手动关。
     *
     * <p>注意 build87 那段「原位那条路实际不出图」的判断只对了一半：
     * 真正不出图的原因是站点对**游客**在详情页根本不下发附件 {@code <img>}，
     * 跟原位/图廊选哪条路无关。build87 已经把列表页的真实 CDN 图接成了兜底，
     * build90 又让原位模式下也能把这些图补写进正文，所以两条路现在都有图。
     */
    public static boolean isImagesInline(Context c) {
        SharedPreferences sp = sp(c);
        if (!sp.getBoolean(KEY_IMAGES_INLINE_SET, false)) {
            // 用户没显式设置过 -> 用当前版本的默认值（原位显示）
            return true;
        }
        return sp.getBoolean(KEY_IMAGES_INLINE, true);
    }

    public static void setImagesInline(Context c, boolean v) {
        sp(c).edit()
                .putBoolean(KEY_IMAGES_INLINE, v)
                .putBoolean(KEY_IMAGES_INLINE_SET, true)
                .apply();
    }

    /** build92: 正文是否用 WebView 原样渲染（默认开） */
    public static boolean isWebRender(Context c) {
        return sp(c).getBoolean(KEY_WEB_RENDER, true);
    }

    public static void setWebRender(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_WEB_RENDER, v).apply();
    }

    /** build95: 链接打开方式，"internal"(默认) 或 "external" */
    public static String linkOpenMode(Context c) {
        return sp(c).getString(KEY_LINK_OPEN, "internal");
    }

    public static void setLinkOpenMode(Context c, String v) {
        sp(c).edit().putString(KEY_LINK_OPEN, "external".equals(v) ? "external" : "internal").apply();
    }

    public static boolean isLinksInternal(Context c) {
        return !"external".equals(linkOpenMode(c));
    }

    /**
     * build99: 侧边栏分区是否展开（0=账号 1=AI 自动化 2=显示 3=其它）。
     *
     * <p>默认只展开「显示」：那一块是用户平时真会动的开关，其余三块默认收起，
     * 这样一屏就能看到四个分区标题，不用滚半天找底部的设置 / 标签 / 运行日志入口。
     */
    public static boolean isDrawerGroupOpen(android.content.Context c, int index) {
        return sp(c).getBoolean("drawer_group_open_" + index, index == 2);
    }

    public static void setDrawerGroupOpen(android.content.Context c, int index, boolean open) {
        sp(c).edit().putBoolean("drawer_group_open_" + index, open).apply();
    }

    /** build95: 顶部 FPS 显示 */
    public static boolean isShowFps(Context c) {
        return sp(c).getBoolean(KEY_SHOW_FPS, false);
    }

    public static void setShowFps(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SHOW_FPS, v).apply();
    }

    /** build99: 是否申请高刷新率（默认开） */
    public static boolean isHighRefresh(Context c) {
        return sp(c).getBoolean(KEY_HIGH_REFRESH, true);
    }

    public static void setHighRefresh(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_HIGH_REFRESH, v).apply();
    }
}
