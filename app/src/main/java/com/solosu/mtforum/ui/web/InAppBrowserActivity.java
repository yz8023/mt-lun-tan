package com.solosu.mtforum.ui.web;

import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;
import com.solosu.mtforum.network.HttpClient;

/** Authenticated in-app browser used when “应用内下载” is selected. */
public class InAppBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";
    private WebView web;
    private TextView title;
    private ProgressBar progress;
    private boolean desktop;

    public static void open(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        Intent intent = new Intent(context, InAppBrowserActivity.class).putExtra(EXTRA_URL, url);
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        HttpClient.getInstance().syncToCookieManager();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.background));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(4), 0);
        TextView back = button("‹");
        back.setTextSize(34);
        back.setOnClickListener(v -> onBackPressed());
        title = button("网页");
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView more = button("⋮");
        more.setTextSize(30);
        more.setOnClickListener(this::showMenu);
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        bar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(52)));
        root.addView(bar);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        FrameLayout holder = new FrameLayout(this);
        web = new WebView(this);
        holder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        root.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(HttpClient.USER_AGENT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri == null ? "" : uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                title.setText(view.getTitle() == null ? Uri.parse(url).getHost() : view.getTitle());
                CookieManager.getInstance().flush();
                HttpClient.getInstance().syncFromCookieManager();
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                progress.setVisibility(View.VISIBLE);
            }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> download(url, disposition, mime));
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            finish(); return;
        }
        web.loadUrl(url);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "浏览器打开");
        menu.getMenu().add(0, 2, 1, "复制链接");
        menu.getMenu().add(0, 3, 2, desktop ? "关闭电脑模式" : "电脑模式");
        menu.setOnMenuItemClickListener(item -> onMenu(item));
        menu.show();
    }

    private boolean onMenu(MenuItem item) {
        String url = web == null ? null : web.getUrl();
        if (item.getItemId() == 1) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception e) { Toast.makeText(this, "没有可用浏览器", Toast.LENGTH_SHORT).show(); }
        } else if (item.getItemId() == 2) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("链接", url));
            Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
        } else if (item.getItemId() == 3) {
            desktop = !desktop;
            web.getSettings().setUserAgentString(desktop ? HttpClient.DESKTOP_USER_AGENT : HttpClient.USER_AGENT);
            web.getSettings().setUseWideViewPort(true);
            web.reload();
        }
        return true;
    }

    private void download(String url, String disposition, String mime) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String name = URLUtil.guessFileName(url, disposition, mime);
            request.setTitle(name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.addRequestHeader("User-Agent", web.getSettings().getUserAgentString());
            request.addRequestHeader("Referer", web.getUrl());
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this, "已开始下载到 Download", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onPause() {
        CookieManager.getInstance().flush();
        HttpClient.getInstance().syncFromCookieManager();
        super.onPause();
    }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
    private TextView button(String text) { TextView v=new TextView(this);v.setText(text);v.setTextColor(getColor(R.color.text_primary));v.setGravity(Gravity.CENTER);return v; }
    private int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density+.5f); }
}
