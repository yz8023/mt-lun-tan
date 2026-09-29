package com.solosu.mtforum.network;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 全局请求节流器（build65 新增）。
 *
 * <p>bbs.binmt.cc 前面挂着阿里云 ESA，短时间内请求过密会被 IP 级 403 拦下来，
 * 表现就是「浏览着浏览着就被禁止访问」。
 *
 * <p>本工程里曾经的实测数据：主界面角标轮询间隔 5 秒、每轮并发 6 个请求，
 * <b>光挂在首页不动就是 72 次/分钟</b>，再叠加自动回复扫帖、搜索翻页，
 * 分分钟撞上风控。
 *
 * <p>这里做两层限制，挂在 OkHttp 拦截器上，所有走 {@link HttpClient} 的请求都过这一关：
 * <ol>
 *   <li><b>最小间隔</b>：相邻两个请求至少隔 {@link #MIN_GAP_MS}，削掉并发突刺</li>
 *   <li><b>滑动窗口</b>：任意 60 秒内最多 {@link #MAX_PER_WINDOW} 个请求，
 *       超了就阻塞等待最早那个请求滑出窗口</li>
 * </ol>
 *
 * <p>注意这是<b>阻塞式</b>的，只能在工作线程上调用 —— 本工程所有网络请求本来就在子线程。
 */
public final class RequestThrottle {

    /** 相邻请求最小间隔 */
    private static final long MIN_GAP_MS = 220L;
    /** 滑动窗口长度 */
    private static final long WINDOW_MS = 60_000L;
    /** 窗口内最大请求数 */
    private static final int MAX_PER_WINDOW = 45;
    /** 单次最长等待，避免极端情况把界面卡死 */
    private static final long MAX_WAIT_MS = 8_000L;

    private static final Deque<Long> WINDOW = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static long lastRequestAt = 0L;

    /** 统计：被节流推迟的总次数与总时长，用于运行日志排查 */
    private static long throttledCount = 0L;
    private static long throttledTotalMs = 0L;

    private RequestThrottle() {
    }

    /**
     * 取一个发送许可，必要时阻塞。
     *
     * @return 实际等待的毫秒数（0 表示没被限）
     */
    public static long acquire() {
        long waited = 0L;
        while (true) {
            long sleep;
            synchronized (LOCK) {
                long now = System.currentTimeMillis();

                // 清掉滑出窗口的记录
                while (!WINDOW.isEmpty() && now - WINDOW.peekFirst() > WINDOW_MS) {
                    WINDOW.pollFirst();
                }

                long gapWait = Math.max(0L, MIN_GAP_MS - (now - lastRequestAt));
                long windowWait = 0L;
                if (WINDOW.size() >= MAX_PER_WINDOW) {
                    Long oldest = WINDOW.peekFirst();
                    if (oldest != null) {
                        windowWait = Math.max(0L, WINDOW_MS - (now - oldest) + 10L);
                    }
                }
                sleep = Math.max(gapWait, windowWait);

                if (sleep <= 0L || waited >= MAX_WAIT_MS) {
                    // 放行并记账
                    lastRequestAt = now;
                    WINDOW.addLast(now);
                    if (waited > 0) {
                        throttledCount++;
                        throttledTotalMs += waited;
                    }
                    return waited;
                }
                sleep = Math.min(sleep, MAX_WAIT_MS - waited);
            }
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return waited;
            }
            waited += sleep;
        }
    }

    /** 当前 60 秒窗口内已发出的请求数 */
    public static int currentWindowCount() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            while (!WINDOW.isEmpty() && now - WINDOW.peekFirst() > WINDOW_MS) {
                WINDOW.pollFirst();
            }
            return WINDOW.size();
        }
    }

    /** 给运行日志用的一行摘要 */
    public static String stats() {
        synchronized (LOCK) {
            return "窗口内 " + currentWindowCount() + "/" + MAX_PER_WINDOW
                    + " 次，累计节流 " + throttledCount + " 次 / " + throttledTotalMs + "ms";
        }
    }

    /** 被风控拦下后主动退避：把窗口填满，强制冷却一段时间 */
    public static void backoff(long coolDownMs) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            lastRequestAt = now + Math.max(0L, coolDownMs);
        }
    }
}
