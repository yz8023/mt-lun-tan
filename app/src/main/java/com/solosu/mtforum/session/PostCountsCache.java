package com.solosu.mtforum.session;

import android.text.TextUtils;

/**
 * build80: 帖子点赞数进程内缓存 (tid -> likes)。
 * 列表页 DOM 的点赞数与详情页操作后的真实值可能不同步；
 * 详情页点赞/取消点赞后把最新数字写入这里，返回列表页时按 tid 回填，
 * 使"点赞后退出到帖子列表"能立刻看到新数字，无需整页重拉。
 */
public class PostCountsCache {

    private static final java.util.Map<String, Integer> LIKES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private PostCountsCache() {}

    /** 写入最新点赞数 */
    public static void setLikes(String tid, int likes) {
        if (TextUtils.isEmpty(tid) || likes < 0) return;
        LIKES.put(tid, likes);
    }

    /** build68: 已知含隐藏内容的帖子（进过详情页就记下，列表里据此打标） */
    private static final java.util.Set<String> HIDDEN_TIDS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * build73: 隐藏标记必须落盘。
     * 原来只存在内存的 Set 里，杀进程/重启后全没了，
     * 所以「隐藏帖一直没有标注」——重开 App 就是一张白纸。
     */
    private static android.content.SharedPreferences prefs;

    public static void attach(android.content.Context c) {
        if (prefs != null || c == null) return;
        prefs = c.getApplicationContext()
                .getSharedPreferences("post_flags", android.content.Context.MODE_PRIVATE);
        java.util.Set<String> saved = prefs.getStringSet("hidden_tids", null);
        if (saved != null) HIDDEN_TIDS.addAll(saved);
    }

    public static void markHasHidden(String tid, boolean hasHidden) {
        if (TextUtils.isEmpty(tid)) return;
        boolean changed = hasHidden ? HIDDEN_TIDS.add(tid) : HIDDEN_TIDS.remove(tid);
        if (changed && prefs != null) {
            // 只留最近 500 条，防止无限增长
            java.util.Set<String> set = new java.util.HashSet<>(HIDDEN_TIDS);
            if (set.size() > 500) {
                java.util.Iterator<String> it = set.iterator();
                while (set.size() > 500 && it.hasNext()) { it.next(); it.remove(); }
            }
            prefs.edit().putStringSet("hidden_tids", set).apply();
        }
    }

    public static boolean hasHidden(String tid) {
        return !TextUtils.isEmpty(tid) && HIDDEN_TIDS.contains(tid);
    }

    /** 读取最新点赞数, 无则返回 null */
    public static Integer getLikes(String tid) {
        if (TextUtils.isEmpty(tid)) return null;
        return LIKES.get(tid);
    }
}
