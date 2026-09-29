package com.solosu.mtforum.network;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 全局请求节流器。
 *
 * <p><b>为什么需要</b>：bbs.binmt.cc 前面挂着阿里云 ESA，短时间请求过密会被 IP 级 403。
 * 本工程历史上的三大流量源：角标轮询 5 秒 × 6 并发（72 次/分钟）、
 * 首页列表每页预取 20 个收藏数、社区页每次进入抓 12 个版块头部。
 * 那三处已经分别修掉，这里是最后一道兜底。
 *
 * <p><b>build66 重写</b>：上一版是「每个请求固定等 220ms」的硬间隔，
 * 副作用很严重 —— 打开一个帖子要发好几个请求，于是每次进帖都平白多等一大截；
 * 再加上收到 403 后把 {@code lastRequestAt} 推到 20 秒之后，
 * 导致之后<b>每一个</b>请求都要各等满 8 秒超时，表现就是「进帖加载特别慢」。
 *
 * <p>现在改成<b>令牌桶</b>：
 * <ul>
 *   <li>桶里有令牌就立刻放行 —— 用户点开一个页面连发几个请求完全不受影响</li>
 *   <li>令牌按固定速率回填，只压制<b>持续</b>高频（轮询、批量扫帖）</li>
 *   <li>另有 60 秒滑动窗口硬上限兜底</li>
 *   <li>收到 403 只清空令牌桶（相当于一次性透支），不再把时间轴整体后推</li>
 * </ul>
 */
public final class RequestThrottle {

    /** 桶容量：允许的突发请求数。一个页面的首屏请求应当能一次性走完 */
    private static final int BURST_CAPACITY = 12;
    /** 令牌回填间隔：约合 46 次/分钟的持续速率 */
    private static final long REFILL_INTERVAL_MS = 1_300L;
    /** 滑动窗口长度 */
    private static final long WINDOW_MS = 60_000L;
    /** 窗口内硬上限 */
    private static final int MAX_PER_WINDOW = 50;
    /** 单个请求最多被推迟多久，超过就放行，宁可冒险也不让界面卡死 */
    private static final long MAX_WAIT_MS = 2_500L;

    private static final Deque<Long> WINDOW = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    private static double tokens = BURST_CAPACITY;
    private static long lastRefillAt = System.currentTimeMillis();

    private static long throttledCount = 0L;
    private static long throttledTotalMs = 0L;

    private RequestThrottle() {
    }

    /**
     * 取一个发送许可，必要时阻塞（最多 {@link #MAX_WAIT_MS}）。
     *
     * @return 实际等待的毫秒数，0 表示没被限
     */
    public static long acquire() {
        long waited = 0L;
        while (true) {
            long sleep;
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                refill(now);
                trimWindow(now);

                boolean hasToken = tokens >= 1d;
                boolean windowOk = WINDOW.size() < MAX_PER_WINDOW;

                if ((hasToken && windowOk) || waited >= MAX_WAIT_MS) {
                    tokens = Math.max(0d, tokens - 1d);
                    WINDOW.addLast(now);
                    if (waited > 0) {
                        throttledCount++;
                        throttledTotalMs += waited;
                    }
                    return waited;
                }

                long tokenWait = hasToken ? 0L
                        : (long) Math.ceil((1d - tokens) * REFILL_INTERVAL_MS);
                long windowWait = 0L;
                if (!windowOk) {
                    Long oldest = WINDOW.peekFirst();
                    if (oldest != null) windowWait = WINDOW_MS - (now - oldest) + 10L;
                }
                sleep = Math.max(1L, Math.max(tokenWait, windowWait));
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

    private static void refill(long now) {
        long elapsed = now - lastRefillAt;
        if (elapsed <= 0) return;
        tokens = Math.min(BURST_CAPACITY, tokens + (double) elapsed / REFILL_INTERVAL_MS);
        lastRefillAt = now;
    }

    private static void trimWindow(long now) {
        while (!WINDOW.isEmpty() && now - WINDOW.peekFirst() > WINDOW_MS) {
            WINDOW.pollFirst();
        }
    }

    /** 当前 60 秒窗口内已发出的请求数 */
    public static int currentWindowCount() {
        synchronized (LOCK) {
            trimWindow(System.currentTimeMillis());
            return WINDOW.size();
        }
    }

    /** 运行日志用的一行摘要 */
    public static String stats() {
        synchronized (LOCK) {
            trimWindow(System.currentTimeMillis());
            return "窗口 " + WINDOW.size() + "/" + MAX_PER_WINDOW
                    + "，令牌 " + String.format(java.util.Locale.US, "%.1f", tokens)
                    + "/" + BURST_CAPACITY
                    + "，累计节流 " + throttledCount + " 次 / " + throttledTotalMs + "ms";
        }
    }

    /**
     * 被风控拦下后的退避：只把令牌桶清空（相当于透支一轮），
     * 让后续请求按回填速率慢慢来，<b>不</b>把时间轴整体后推 ——
     * 上一版那样做会让之后每个请求都各等满超时。
     */
    public static void backoff() {
        synchronized (LOCK) {
            tokens = 0d;
            lastRefillAt = System.currentTimeMillis();
        }
    }
}
