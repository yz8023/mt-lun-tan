package com.solosu.mtforum.ui;

import android.content.Context;

/**
 * build110：进入帖子后自动预加载的评论页数。
 *
 * <p>用户明确说过「不希望预加载所有楼层」，所以这里是<b>有上限的、只往下预取</b>：
 * 进帖子后最多再抓 {@code N} 页（默认 3），而不是把整栋楼一口气拉完。
 * 抓回来的回复先放在内存里等用户滑下去，滑到时零等待直接显示。
 *
 * <p>设为 0 即关闭预加载，行为与改动前一致。
 */
public final class ReplyPrefetchPreferences {

    private static final String PREFS = "reply_prefetch";
    private static final String KEY_PAGES = "pages";

    /** 默认预加载 3 页（用户要求） */
    public static final int DEFAULT_PAGES = 3;
    /** 上限：再高就违背「不要预加载所有楼层」了 */
    public static final int MAX_PAGES = 5;

    private ReplyPrefetchPreferences() {
    }

    public static int getPages(Context context) {
        if (context == null) return DEFAULT_PAGES;
        int v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PAGES, DEFAULT_PAGES);
        return Math.max(0, Math.min(MAX_PAGES, v));
    }

    public static void setPages(Context context, int pages) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_PAGES, Math.max(0, Math.min(MAX_PAGES, pages))).apply();
    }

    /** 设置页显示用 */
    public static String label(Context context) {
        int n = getPages(context);
        return n <= 0 ? "关闭" : ("预加载 " + n + " 页");
    }
}
