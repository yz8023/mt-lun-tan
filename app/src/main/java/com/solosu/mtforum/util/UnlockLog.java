package com.solosu.mtforum.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自动解锁记录（build69 新增）。
 *
 * <p>之前解锁的成功/跳过全混在运行日志里，和签到、角标、调度器的输出搅在一起，
 * 想看「哪些帖子解锁了、哪些跳过了、为什么跳过」根本看不出来。
 * 这里单独开一条，一行一个帖子，结果一目了然。
 */
public final class UnlockLog {

    private static final int MAX = 60;
    private static final List<String> ENTRIES = new ArrayList<>();

    private UnlockLog() {
    }

    /** 解锁成功 */
    public static void ok(String tid, String reply) {
        add("✓ 已解锁", tid, reply);
    }

    /** 解锁失败 */
    public static void fail(String tid, String why) {
        add("✗ 失败", tid, why);
    }

    /** 跳过（带原因） */
    public static void skip(String tid, String why) {
        add("○ 跳过", tid, why);
    }

    private static void add(String mark, String tid, String detail) {
        String line = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date())
                + "  " + mark
                + "  tid=" + (tid == null ? "?" : tid)
                + (detail == null || detail.isEmpty() ? "" : "  " + detail);
        synchronized (ENTRIES) {
            ENTRIES.add(line);
            while (ENTRIES.size() > MAX) ENTRIES.remove(0);
        }
    }

    /** 倒序输出（最新在上） */
    public static String dump() {
        synchronized (ENTRIES) {
            if (ENTRIES.isEmpty()) {
                return "还没有记录。\n\n开启「自动解锁隐藏内容」后，打开含隐藏块的帖子即可看到。";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                sb.append(ENTRIES.get(i)).append('\n');
            }
            return sb.toString();
        }
    }
}
