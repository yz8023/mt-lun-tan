package com.solosu.mtforum.session;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.network.InterstitialDetector;
import com.solosu.mtforum.ui.web.SiteVerifyActivity;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Detects Alibaba/ESA browser challenges and opens one shared WebView to obtain its clearance cookie. */
public final class SiteAccessManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean OPENING = new AtomicBoolean(false);
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static volatile long lastLaunch;
    private static volatile String pendingUrl = HttpClient.BASE_URL;

    private SiteAccessManager() {}

    public static void onActivityResumed(Activity activity) { foreground = new WeakReference<>(activity); }
    public static void onActivityPaused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }

    public static boolean isRealForumPage(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String s = html.toLowerCase(Locale.ROOT);
        return s.contains("discuz_uid") || s.contains("comiis_")
                || s.contains("discuz_tips") || s.contains("formhash")
                || s.contains("id=\"wp\"");
    }

    /**
     * 判定「这一坨 HTML 是不是站点防护页」。
     *
     * <p>build80 起改为<b>结构判定优先</b>。原来的实现是厂商特征串匹配
     * （{@code acw_sc__v2}/{@code aliyungf_tc}/{@code __jsl_clearance}/{@code 人机验证}...），
     * 换个 WAF、或者站点把挑战页改个版，这一串<b>全军覆没</b>，
     * 而页面还是那个页面 —— 于是静默退化成「解析不出内容」。
     *
     * <p>结构判定的判据是「<b>看起来像一个完整 HTML 文档，却完全没有论坛骨架</b>」，
     * 与是哪家 WAF 无关；且它自带保守性（见 {@code hasDiscuzSkeleton}：
     * 只要残留 {@code discuz}/{@code formhash}/{@code comiis_} 就不判拦截），
     * 所以放宽判定不会平白弹浏览器。
     *
     * <p>厂商特征保留为<b>兜底</b>：结构判定漏掉、但特征明确的场景
     * （例如页面上恰好残留了少量论坛痕迹）。
     */
    public static boolean isChallengePage(String html) {
        if (TextUtils.isEmpty(html)) return false;
        if (InterstitialDetector.looksLikeInterstitialPage(html, "text/html")) return true;
        String s = html.toLowerCase(Locale.ROOT);
        boolean marker = s.contains("acw_sc__v2") || s.contains("aliyungf_tc")
                || s.contains("__jsl_clearance") || s.contains("window._config_")
                || s.contains("waf challenge") || s.contains("esa challenge")
                || s.contains("人机验证") || s.contains("安全验证")
                || (s.contains("document.cookie") && (s.contains("challenge") || s.contains("arg1")));
        return marker && !isRealForumPage(html);
    }

    public static void onChallengeDetected(String url) {
        if (!TextUtils.isEmpty(url) && url.startsWith("http")) pendingUrl = url;
        long now = System.currentTimeMillis();
        if (OPENING.get() || now - lastLaunch < 15_000L) return;
        Activity activity = foreground.get();
        if (activity == null || activity.isFinishing()) return;
        if (!OPENING.compareAndSet(false, true)) return;
        lastLaunch = now;
        MAIN.post(() -> {
            Activity current = foreground.get();
            if (current == null || current.isFinishing()) { OPENING.set(false); return; }
            Intent intent = new Intent(current, SiteVerifyActivity.class);
            intent.putExtra("url", pendingUrl);
            current.startActivity(intent);
        });
    }

    public static void openManually(Activity activity) {
        if (activity == null) return;
        OPENING.set(true);
        Intent intent = new Intent(activity, SiteVerifyActivity.class);
        intent.putExtra("url", HttpClient.BASE_URL);
        activity.startActivity(intent);
    }

    /** Keep only domain-level anti-bot cookies; never leak/replace an account's auth cookie. */
    public static String protectionCookieHeader(String header) {
        if (TextUtils.isEmpty(header)) return "";
        StringBuilder out = new StringBuilder();
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("clearance") || lower.startsWith("acw_")
                    || lower.startsWith("aliyungf_") || lower.startsWith("__jsl")) {
                if (out.length() > 0) out.append("; ");
                out.append(name).append('=').append(part.substring(eq + 1).trim());
            }
        }
        return out.toString();
    }

    public static void markClosed() { OPENING.set(false); lastLaunch = System.currentTimeMillis(); }

    /**
     * 阻塞等待用户通过站点验证，用于网络层「重放请求」。
     *
     * <p>build80 新增：验证 WebView 通过后会 {@link #markCleared()}，
     * 这里等的就是那个信号。带超时——验证页可能被用户直接关掉，
     * 不能把 OkHttp 线程永久挂住。
     *
     * @param timeoutMillis 最长等待毫秒数
     * @return 是否在超时前拿到了 clearance
     */
    public static boolean awaitClearance(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (CLEARED) {
            while (CLEARED.get()) {
                // 已通过 → 立刻返回
                return true;
            }
        }
        // 用轮询而非 Object.wait：markCleared() 可能由任意线程调用，
        // 轮询足够简单且不会有「漏唤醒」问题；间隔 250ms 对 UX 无感。
        while (System.currentTimeMillis() < deadline) {
            if (CLEARED.get()) return true;
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CLEARED.get();
            }
        }
        return CLEARED.get();
    }

    /** 验证通过后调用：唤醒 {@link #awaitClearance} 的等待者。 */
    public static void markCleared() {
        CLEARED.set(true);
        synchronized (CLEARED) {
            CLEARED.notifyAll();
        }
    }

    /** 站点状态变化（重新被挑战 / 重新登录）时调用，把已通过的标记复位。 */
    public static void resetClearance() {
        CLEARED.set(false);
    }

    public static boolean hasClearance() { return CLEARED.get(); }

    private static final java.util.concurrent.atomic.AtomicBoolean CLEARED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
}
