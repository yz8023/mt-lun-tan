package com.solosu.mtforum.session;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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

    static final class Result {
        final boolean success;
        final String cookies;
        final String detail;
        Result(boolean success, String cookies, String detail) {
            this.success = success;
            this.cookies = cookies == null ? "" : cookies;
            this.detail = detail == null ? "未知原因" : detail;
        }
    }

    private HeadlessSiteVerifier() {}

    static Result verify(Context context, String url) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return new Result(false, "", "验证任务误在主线程运行");
        }
        synchronized (LOCK) {
            return attempt(context.getApplicationContext(), url, 20);
        }
    }

    private static Result attempt(Context context, String url, int timeoutSeconds) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> cookies = new AtomicReference<>("");
        AtomicReference<String> detail = new AtomicReference<>("验证页面未完成跳转");
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
                    @Override public void onPageStarted(WebView view, String pageUrl, Bitmap icon) {
                        detail.set("正在加载 " + shortUrl(pageUrl));
                    }
                    @Override public void onPageFinished(WebView view, String pageUrl) {
                        cm.flush();
                        detail.set("页面已加载，等待验证脚本完成");
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()", html -> {
                            if (completed.get()) return;
                            if (pageUrl != null && pageUrl.contains("bbs.binmt.cc")
                                    && SiteAccessManager.isRealForumPage(html)) {
                                completed.set(true);
                                HttpClient.getInstance().syncFromCookieManager();
                                cookies.set(cm.getCookie(HttpClient.BASE_URL));
                                detail.set("已进入论坛页面并同步防护 Cookie");
                                done.countDown();
                            } else if (SiteAccessManager.isChallengePage(html)) {
                                detail.set("验证脚本仍在执行");
                            } else {
                                detail.set("页面已加载，但未识别到论坛正文或 ESA 验证标记");
                            }
                        });
                    }
                    @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                              WebResourceResponse response) {
                        if (request != null && request.isForMainFrame()) {
                            detail.set("验证页面 HTTP " + response.getStatusCode());
                        }
                    }
                    @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                           android.webkit.WebResourceError error) {
                        if (request != null && request.isForMainFrame()) {
                            detail.set("WebView 加载失败：" + error.getDescription());
                        }
                    }
                });
                web.loadUrl(url == null || !url.startsWith("http") ? HttpClient.BASE_URL : url);
            } catch (Throwable error) {
                detail.set("无法创建后台 WebView：" + safeMessage(error));
                done.countDown();
            }
        });

        boolean signalled = false;
        try { signalled = done.await(timeoutSeconds, TimeUnit.SECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detail.set("验证任务被系统中断");
        }
        boolean success = completed.get() && !cookies.get().isEmpty();
        if (!signalled && !success) detail.set("等待验证超时（" + timeoutSeconds + "秒），" + detail.get());
        completed.set(true);
        MAIN.post(() -> {
            WebView web = holder.get();
            if (web != null) { web.stopLoading(); web.destroy(); }
        });
        return new Result(success, cookies.get(), detail.get());
    }

    private static String shortUrl(String url) {
        if (url == null) return "未知地址";
        return url.length() > 80 ? url.substring(0, 80) + "…" : url;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "未知错误" : error.getClass().getSimpleName()) : message;
    }
}
