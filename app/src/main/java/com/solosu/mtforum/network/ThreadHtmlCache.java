package com.solosu.mtforum.network;

import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 帖子页面 HTML 内存缓存（build70 新增）。
 *
 * <p>实测网络往返 ~320ms、解析 5–80ms，已经没什么可压缩的了。
 * 想再快就只能<b>不发请求</b>：刚看过的帖子直接用缓存瞬间渲染，
 * 同时后台静默拉一份新的，有变化再刷新。
 *
 * <p>典型收益：从帖子退回列表再点进去、或在几个帖子之间来回切，
 * 都是 0ms 出内容。
 *
 * <p>只留 8 篇、5 分钟过期 —— 帖子页动辄 60–190KB，留多了吃内存。
 */
public final class ThreadHtmlCache {

    private static final int MAX_ENTRIES = 8;
    private static final long TTL_MS = 5 * 60_000L;

    private static final class Cached {
        final String html;
        final long at;

        Cached(String html, long at) {
            this.html = html;
            this.at = at;
        }
    }

    /** accessOrder=true 的 LinkedHashMap 即 LRU */
    private static final LinkedHashMap<String, Cached> CACHE =
            new LinkedHashMap<String, Cached>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private ThreadHtmlCache() {
    }

    private static String key(String tid, String order) {
        return tid + "|" + (order == null ? "" : order);
    }

    /** 取缓存；不存在或已过期返回 null */
    public static String get(String tid, String order) {
        if (TextUtils.isEmpty(tid)) return null;
        synchronized (CACHE) {
            Cached e = CACHE.get(key(tid, order));
            if (e == null) return null;
            if (System.currentTimeMillis() - e.at > TTL_MS) {
                CACHE.remove(key(tid, order));
                return null;
            }
            return e.html;
        }
    }

    public static void put(String tid, String order, String html) {
        if (TextUtils.isEmpty(tid) || TextUtils.isEmpty(html)) return;
        synchronized (CACHE) {
            CACHE.put(key(tid, order), new Cached(html, System.currentTimeMillis()));
        }
    }

    /** 回帖/点赞等改变了页面内容后，让该帖缓存立即失效 */
    public static void invalidate(String tid) {
        if (TextUtils.isEmpty(tid)) return;
        synchronized (CACHE) {
            CACHE.keySet().removeIf(k -> k.startsWith(tid + "|"));
        }
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
