package com.solosu.mtforum.ui.web;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.AccountManager;
import com.solosu.mtforum.session.SessionGuard;
import com.solosu.mtforum.session.SiteAccessManager;

/** First-party WebView used only to execute the site's ESA challenge and synchronize its Cookie. */
public class SiteVerifyActivity extends AppCompatActivity {
    private WebView web;
    private ProgressBar progress;
    private boolean resolved;
    private boolean autoLoginInFlight;
    private int autoLoginAttempts;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        HttpClient.getInstance().syncToCookieManager();
        CookieManager.getInstance().setAcceptCookie(true);

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(getColor(R.color.background));
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(8),0,dp(8),0);
        TextView close = text("关闭", 15); close.setPadding(dp(12),dp(14),dp(12),dp(14)); close.setOnClickListener(v -> finish());
        TextView title = text("站点验证 · Cookie 同步", 16); title.setGravity(Gravity.CENTER); title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView reload = text("刷新", 15); reload.setPadding(dp(12),dp(14),dp(12),dp(14)); reload.setOnClickListener(v -> web.reload());
        bar.addView(close); bar.addView(title,new LinearLayout.LayoutParams(0,-2,1)); bar.addView(reload); root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        FrameLayout holder = new FrameLayout(this); web = new WebView(this); holder.addView(web,new FrameLayout.LayoutParams(-1,-1)); root.addView(holder,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setUserAgentString(HttpClient.USER_AGENT);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) { progress.setVisibility(View.VISIBLE); }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                HttpClient.getInstance().syncFromCookieManager();
                view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()", value -> {
                    String lower = value == null ? "" : value.toLowerCase();
                    boolean loginPage = lower.contains("type=\\\"password\\\"")
                            || lower.contains("name=\\\"password\\\"")
                            || lower.contains("loginform");
                    if (loginPage && url != null && url.contains("bbs.binmt.cc")) {
                        autoFillSavedAccount();
                        return;
                    }
                    boolean forum = lower.contains("comiis_") || lower.contains("discuz_uid")
                            || lower.contains("discuz_tips") || lower.contains("formhash");
                    // 登录表单也含 formhash，必须先排除；否则会在自动填充前误判恢复完成。
                    if (forum && url != null && url.contains("bbs.binmt.cc") && !resolved) completeRecovery();
                });
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
        });
        String url = getIntent().getStringExtra("url");
        web.loadUrl(url == null || !url.startsWith("http") ? HttpClient.BASE_URL : url);
        Toast.makeText(this, "若出现验证请完成它；通过后 Cookie 会自动同步", Toast.LENGTH_LONG).show();
    }

    /** Automatically signs the active saved account into the verified first-party WebView. */
    private void autoFillSavedAccount() {
        if (autoLoginInFlight || autoLoginAttempts >= 2 || web == null) return;
        AccountManager.Account account = AccountManager.get(this, AccountManager.activeUid(this));
        String password = AccountManager.decryptPassword(account);
        if (account == null || android.text.TextUtils.isEmpty(account.credentialName())
                || android.text.TextUtils.isEmpty(password)) {
            Toast.makeText(this, "该账号未保存密码，无法自动填写", Toast.LENGTH_LONG).show();
            return;
        }
        autoLoginInFlight = true;
        autoLoginAttempts++;
        String user = org.json.JSONObject.quote(account.credentialName());
        String pass = org.json.JSONObject.quote(password);
        String script = "(function(){"
                + "var u=document.querySelector('input[name=username],input[name=email]');"
                + "var p=document.querySelector('input[name=password],input[type=password]');"
                + "if(!u||!p)return 'missing';"
                + "u.value=" + user + ";p.value=" + pass + ";"
                + "['input','change'].forEach(function(e){u.dispatchEvent(new Event(e,{bubbles:true}));p.dispatchEvent(new Event(e,{bubbles:true}));});"
                + "var f=p.form||document.querySelector('form');if(!f)return 'noform';"
                + "var b=f.querySelector('button[type=submit],input[type=submit],button[name=loginsubmit]');"
                + "if(b){b.click();}else if(f.requestSubmit){f.requestSubmit();}else{f.submit();}return 'submitted';})()";
        web.evaluateJavascript(script, result -> {
            autoLoginInFlight = false;
            if (result == null || !result.contains("submitted")) {
                Toast.makeText(this, "自动填写失败，可点击刷新重试", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已使用本机保存的账号自动登录", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void completeRecovery() {
        resolved = true;
        SessionGuard.recoverAfterBrowser(this, (success, message) -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (success) { setResult(RESULT_OK); finish(); }
            else resolved = false;
        });
    }

    @Override protected void onPause() { CookieManager.getInstance().flush(); HttpClient.getInstance().syncFromCookieManager(); super.onPause(); }
    @Override protected void onDestroy() { HttpClient.getInstance().syncFromCookieManager(); SiteAccessManager.markClosed(); if(web!=null) web.destroy(); super.onDestroy(); }
    private TextView text(String s,float z){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(getColor(R.color.text_primary));return v;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
}
