package com.solosu.mtforum.session;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.solosu.mtforum.network.HttpClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the site's JavaScript challenge in a windowless WebView for background sign-in. */
final class HeadlessSiteVerifier {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private HeadlessSiteVerifier() {}

    /** Returns the browser Cookie header after one verification attempt, or empty on failure. */
    static String verify(Context context, String url) {
        if (Looper.myLooper() == Looper.getMainLooper()) return "";
        synchronized (LOCK) {
            String cookie = attempt(context.getApplicationContext(), url, 18);
            return cookie == null ? "" : cookie;
        }
    }

    private static String attempt(Context context, String url, int timeoutSeconds) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<WebView> holder = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        MAIN.post(() -> {
            try {
                HttpClient.getInstance().syncToCookieManager();
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                WebView web = new WebView(context);
                holder.set(web);
                web.getSettings().setJavaScriptEnabled(true);
                web.getSettings().setDomStorageEnabled(true);
                web.getSettings().setDatabaseEnabled(true);
                web.getSettings().setUserAgentString(HttpClient.USER_AGENT);
                cm.setAcceptThirdPartyCookies(web, true);
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageStarted(WebView view, String pageUrl, Bitmap icon) { }
                    @Override public void onPageFinished(WebView view, String pageUrl) {
                        cm.flush();
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()", html -> {
                            if (!completed.get() && pageUrl != null && pageUrl.contains("bbs.binmt.cc")
                                    && SiteAccessManager.isRealForumPage(html)) {
                                completed.set(true);
                                HttpClient.getInstance().syncFromCookieManager();
                                result.set(cm.getCookie(HttpClient.BASE_URL));
                                done.countDown();
                            }
                        });
                    }
                });
                web.loadUrl(url == null || !url.startsWith("http") ? HttpClient.BASE_URL : url);
            } catch (Throwable ignored) {
                done.countDown();
            }
        });

        try { done.await(timeoutSeconds, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        completed.set(true);
        MAIN.post(() -> {
            WebView web = holder.get();
            if (web != null) { web.stopLoading(); web.destroy(); }
        });
        return result.get();
    }
}
