package com.solosu.mtforum.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 页面加载耗时日志（build68 新增）。
 *
 * <p>为什么不塞进 AiLog：AiLog 是 300 条的环形缓冲，自动回复/签到/角标刷新的
 * 日志一多，性能记录几条就被挤没了 —— 用户反馈「没有 log 可供参考」就是这个原因。
 * 这里单独开一个小缓冲，只装加载耗时，页面上可单独查看。
 */
public final class PerfLog {

    private static final int MAX = 80;
    private static final List<String> ENTRIES = new ArrayList<>();

    private PerfLog() {
    }

    /**
     * 记一次页面加载。
     *
     * @param what    页面标识，如 thread-173642 / user-146665
     * @param netMs   网络耗时
     * @param parseMs 解析耗时
     * @param bytes   页面字节数
     */
    public static void record(String what, long netMs, long parseMs, int bytes) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
                + "  " + what
                + "  网络 " + netMs + "ms"
                + " | 解析 " + parseMs + "ms"
                + " | 合计 " + (netMs + parseMs) + "ms"
                + " | " + (bytes / 1024) + "KB"
                + "  [" + com.solosu.mtforum.network.RequestThrottle.stats() + "]";
        synchronized (ENTRIES) {
            ENTRIES.add(line);
            while (ENTRIES.size() > MAX) ENTRIES.remove(0);
        }
        // 同时进运行日志，方便和其它事件对时间线
        com.solosu.mtforum.ai.AiLog.i("perf", line);
    }

    /** 倒序输出（最新在上） */
    public static String dump() {
        synchronized (ENTRIES) {
            if (ENTRIES.isEmpty()) {
                return "还没有记录。\n\n打开任意帖子或用户主页后再回来看。";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                sb.append(ENTRIES.get(i)).append('\n');
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }
}
