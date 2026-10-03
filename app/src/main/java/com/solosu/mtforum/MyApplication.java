package com.solosu.mtforum;

import android.app.Application;

import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.ai.AutoReplyScheduler;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.SessionGuard;
import com.solosu.mtforum.session.SignInNotifier;
import com.solosu.mtforum.session.SignInScheduler;
import com.solosu.mtforum.util.CrashHandler;

/**
 * 全局 Application 类
 * 在所有 Activity 启动前完成初始化,确保登录态 Cookie 从磁盘恢复
 */
public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // build84: 把 Glide 的图片加载栈从 HttpURLConnection 换成 OkHttp。
        // 参照项目 yz8023/mtluntan 用 Coil（底层 OkHttp）加载同一个站的图片一直正常，
        // 因为 OkHttp 默认就带 User-Agent；而本站图片 UA 为空一律 403（cdn 与重定向
        // 后的 oss 两个域名都要求）。详见 util/OkHttpStreamLoader 的说明。
        // 放在最早期，且任何失败都不能影响启动。
        try {
            com.bumptech.glide.Glide.get(this).getRegistry()
                    .replace(com.bumptech.glide.load.model.GlideUrl.class,
                            java.io.InputStream.class,
                            new com.solosu.mtforum.util.OkHttpStreamLoader.Factory());
        } catch (Throwable t) {
            android.util.Log.w("MyApplication", "Glide OkHttp 栈注册失败，沿用默认: " + t);
        }

        // 恢复持久化的 Cookie —— 在任何 Activity 启动前执行
        // 防止从最近任务直接恢复 SearchActivity 等非 MainActivity 时登录态丢失
        // build66: 先应用用户选择的深色模式，避免首屏闪一下
        com.solosu.mtforum.ui.theme.ThemeManager.applySaved(this);

        // build71: 主题色之前只在 MainActivity 创建时刷一次，别的页面根本没应用，
        // 所以看起来「全局不生效」。改为每个 Activity 恢复时都刷一遍内容树。
        registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(android.app.Activity a) {
                com.solosu.mtforum.session.SiteAccessManager.onActivityResumed(a);
                if (!com.solosu.mtforum.ui.theme.ThemeManager.isAccentCustomised(a)) return;
                final android.view.View root = a.findViewById(android.R.id.content);
                if (root == null) return;
                root.post(() ->
                        com.solosu.mtforum.ui.theme.ThemeManager.applyAccent(root, a));
            }

            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(android.app.Activity a) {}
            @Override public void onActivityPaused(android.app.Activity a) {
                com.solosu.mtforum.session.SiteAccessManager.onActivityPaused(a);
            }
            @Override public void onActivityStopped(android.app.Activity a) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });

        com.solosu.mtforum.session.PostCountsCache.attach(this);
        com.solosu.mtforum.ai.AutoReplyEngine.attachClaims(this);
        HttpClient.getInstance().init(this);

        // build61: 网络层一旦发现 403 / 登录页，就用已加密保存的密码静默重登
        HttpClient.setAuthFailureListener(() -> SessionGuard.onAuthFailure(this));
        HttpClient.setChallengeListener(url ->
                com.solosu.mtforum.session.SiteAccessManager.onChallengeDetected(url));

        // 运行日志落盘，App 被杀后仍可回看
        AiLog.attach(this);
        // 一次性迁移：v1.2 起关闭旧的「演练模式」默认值，避免自动回复一直只生成不发送
        com.solosu.mtforum.ai.AiConfigManager.migrateDefaults(this);
        // 启动标记：同时验证日志已开始镜像到公共 Download
        AiLog.i("app", "应用已启动，运行日志开始记录（含进帖自动解锁）");

        // 初始化全局崩溃日志收集
        CrashHandler.getInstance().init(this);

        // 启动自动回复调度（开关未打开时调度器会空转，不会发请求）
        AutoReplyScheduler.start(this);

        // build60: 多账号定时签到 —— 建好通知渠道并按设置重排 WorkManager 周期任务
        SignInNotifier.ensureChannel(this);
        SignInScheduler.reschedule(this);

        // Read-only MCP endpoint for AI clients; disabled by default and bearer-token protected.
        if (com.solosu.mtforum.mcp.McpPreferences.enabled(this)) {
            com.solosu.mtforum.mcp.McpService.start(this);
        }
    }
}
