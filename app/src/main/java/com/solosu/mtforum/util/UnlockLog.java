package com.solosu.mtforum.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自动解锁记录（build75 改为结构化，可点击跳转）。
 */
public final class UnlockLog {

    public static class Item {
        public String tid;
        public String mark;     // 已解锁 / 跳过 / 失败
        public String detail;
        public long at;
        public boolean success;
    }

    private static final int MAX = 80;
    private static final List<Item> ENTRIES = new ArrayList<>();

    private UnlockLog() {
    }

    public static void ok(String tid, String reply) {
        add(tid, "已解锁", reply, true);
    }

    public static void fail(String tid, String why) {
        add(tid, "失败", why, false);
    }

    public static void skip(String tid, String why) {
        add(tid, "跳过", why, false);
    }

    private static void add(String tid, String mark, String detail, boolean success) {
        Item it = new Item();
        it.tid = tid;
        it.mark = mark;
        it.detail = detail == null ? "" : detail;
        it.at = System.currentTimeMillis();
        it.success = success;
        synchronized (ENTRIES) {
            // 同一帖子的重复「跳过」只留最新一条，避免刷屏
            if (!success) {
                for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                    Item o = ENTRIES.get(i);
                    if (o.tid != null && o.tid.equals(tid) && o.mark.equals(mark)) {
                        ENTRIES.remove(i);
                        break;
                    }
                }
            }
            ENTRIES.add(it);
            while (ENTRIES.size() > MAX) ENTRIES.remove(0);
        }
    }

    /** 倒序（最新在上） */
    public static List<Item> list() {
        synchronized (ENTRIES) {
            List<Item> out = new ArrayList<>(ENTRIES);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    public static String dump() {
        List<Item> list = list();
        if (list.isEmpty()) return "还没有记录。";
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        for (Item it : list) {
            sb.append(f.format(new Date(it.at))).append("  ").append(it.mark)
              .append("  tid=").append(it.tid)
              .append(it.detail.isEmpty() ? "" : "  " + it.detail).append('\n');
        }
        return sb.toString();
    }
}
