package com.solosu.mtforum.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * build87: 列表页真实配图地址登记表（tid -> 缩略图 URL）。
 *
 * <p>站点对<strong>游客</strong>在帖子详情页根本不下发附件 {@code <img>}：
 * 实测 tid=173937 详情页 67943 字符里 {@code comiis_loadimages="} 出现 0 次、
 * {@code aid=} 只出现 1 次（且是 JS 里的懒加载选择器字符串），正文容器
 * {@code div.comiis_a.comiis_message_table} 中只有文字和头像。
 * 这就是「列表页明明有缩略图、点进帖子却一张图都不显示」的根因 ——
 * 详情页压根没有图可解析，之前几个版本改选择器、改懒加载属性全是空转。
 *
 * <p>列表页是唯一能拿到真实 CDN 附件地址的地方，所以在解析列表卡片时按 tid
 * 登记，详情页无论从哪个入口进来（首页流、版块页、搜索、日志中心、引用回复……）
 * 都能取回兜底，不依赖 Intent 有没有传 extra。
 *
 * <p>只存地址字符串不存 Bitmap，LRU 上限 200 个 tid，内存开销可忽略。
 */
public final class ListImageRegistry {

    private static final int MAX_ENTRIES = 200;

    private static final Object LOCK = new Object();

    private static final LinkedHashMap<String, List<String>> CACHE =
            new LinkedHashMap<String, List<String>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private ListImageRegistry() {
    }

    /** 登记某个帖子的列表页配图地址。空列表不登记。 */
    public static void put(String tid, List<String> urls) {
        if (tid == null || tid.trim().isEmpty()) return;
        if (urls == null || urls.isEmpty()) return;
        List<String> copy = new ArrayList<>(urls);
        synchronized (LOCK) {
            CACHE.put(tid, copy);
        }
    }

    /** 取回登记的地址；没有则返回空列表（不会返回 null）。 */
    public static List<String> get(String tid) {
        if (tid == null || tid.trim().isEmpty()) return Collections.emptyList();
        synchronized (LOCK) {
            List<String> v = CACHE.get(tid);
            return v == null ? Collections.emptyList() : new ArrayList<>(v);
        }
    }

    /** 清空登记表（切号 / 退出登录时调用，避免跨账号残留）。 */
    public static void clear() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }
}
