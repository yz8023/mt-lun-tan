package com.solosu.mtforum.network;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 全局请求节流器。
 *
 * <p><b>build69 重做 —— 加前台优先级，别再对用户操作收税。</b>
 *
 * <p>实测日志摊开来看得很清楚：
 * <pre>
 *   令牌 11.0/12 → 网络 344ms / 364ms / 266ms / 306ms     均值 320ms
 *   令牌  0.0/12 → 网络 2813ms / 2830ms / 2799ms / 2730ms  均值 2337ms
 * </pre>
 * 差的那 2000ms 就是本类的等待上限。也就是说「进帖很慢」<b>完全是节流造成的</b>，
 * 真实网络只要 300ms 左右，解析更是只要 5–80ms。
 *
 * <p>而当初引入节流要挡的三个流量源（角标 5 秒 × 6 并发、首页每页 20 发收藏预取、
 * 社区页每次 12 发版块头部）<b>都已经在源头修掉了</b>。留着一个对所有请求
 * 一视同仁收税的节流器，已经是帮倒忙。
 *
 * <p>所以改成两条车道：
 * <ul>
 *   <li><b>前台车道</b>（用户点开帖子/用户页这类导航）：<b>不等待</b>，只记账。
 *       用户的每一次点击都必须立刻出结果。</li>
 *   <li><b>后台车道</b>（轮询、批量扫帖、预取）：正常令牌桶限速，
 *       这才是真正需要被压住的部分。</li>
 * </ul>
 * 同时把桶放大、回填加快、最长等待砍到 1.2 秒 —— 兜底而非主控。
 */
public final class RequestThrottle {

    /** 桶容量：允许的突发请求数 */
    private static final int BURST_CAPACITY = 20;
    /** 令牌回填间隔：约合 85 次/分钟的持续速率 */
    private static final long REFILL_INTERVAL_MS = 700L;
    /** 滑动窗口长度 */
    private static final long WINDOW_MS = 60_000L;
    /** 窗口内硬上限（仅约束后台车道） */
    private static final int MAX_PER_WINDOW = 90;
    /** 后台车道单个请求最多被推迟多久 */
    private static final long MAX_WAIT_MS = 1_200L;

    private static final Deque<Long> WINDOW = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    private static double tokens = BURST_CAPACITY;
    private static long lastRefillAt = System.currentTimeMillis();

    private static long throttledCount = 0L;
    private static long throttledTotalMs = 0L;

    /**
     * 前台标记。用户主动导航发起的请求把它置上，本次请求就不排队。
     * 用 ThreadLocal 是因为每个页面加载都在自己的工作线程里跑。
     */
    private static final ThreadLocal<Boolean> FOREGROUND = new ThreadLocal<>();

    private RequestThrottle() {
    }

    /** 标记当前线程后续的请求属于前台导航（不排队） */
    public static void markForeground() {
        FOREGROUND.set(Boolean.TRUE);
    }

    /** 清除前台标记 */
    public static void clearForeground() {
        FOREGROUND.remove();
    }

    public static boolean isForeground() {
        return Boolean.TRUE.equals(FOREGROUND.get());
    }

    /**
     * 取一个发送许可。
     *
     * @return 实际等待的毫秒数，0 表示没被限
     */
    public static long acquire() {
        // 前台车道：只记账，绝不等待
        if (isForeground()) {
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                refill(now);
                trimWindow(now);
                tokens = Math.max(0d, tokens - 1d);
                WINDOW.addLast(now);
            }
            return 0L;
        }

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
                    + (throttledCount > 0
                        ? "，后台累计排队 " + throttledCount + " 次 / " + throttledTotalMs + "ms"
                        : "，无排队");
        }
    }

    /** 被风控拦下后的退避：清空令牌桶（只影响后台车道） */
    public static void backoff() {
        synchronized (LOCK) {
            tokens = 0d;
            lastRefillAt = System.currentTimeMillis();
        }
    }
}
