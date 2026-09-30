package com.solosu.mtforum.session;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.solosu.mtforum.network.HttpClient;
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

    public static boolean isChallengePage(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String s = html.toLowerCase(Locale.ROOT);
        boolean marker = s.contains("acw_sc__v2") || s.contains("aliyungf_tc")
                || s.contains("__jsl_clearance") || s.contains("window._config_")
                || s.contains("waf challenge") || s.contains("esa challenge")
                || s.contains("人机验证") || s.contains("安全验证")
                || (s.contains("document.cookie") && (s.contains("challenge") || s.contains("arg1")));
        boolean forumPage = s.contains("discuz_uid") || s.contains("comiis_")
                || s.contains("discuz_tips") || s.contains("name=\"formhash\"")
                || s.contains("id=\"wp\"");
        return marker && !forumPage;
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

    public static void markClosed() { OPENING.set(false); lastLaunch = System.currentTimeMillis(); }
}
