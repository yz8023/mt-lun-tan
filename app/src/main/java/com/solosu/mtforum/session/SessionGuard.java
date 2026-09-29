package com.solosu.mtforum.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.network.HttpClient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 登录态守卫（build61 新增）。
 *
 * <p>解决的问题：浏览过程中被论坛的阿里云 ESA 拦一次 403，或 Cookie 到期，
 * 登录态就没了，用户得手动重新输账号密码。
 *
 * <p>做法：账号密码在登录时已经用 {@link com.solosu.mtforum.util.CryptoUtils}
 * （Android KeyStore AES-GCM）加密存在本机。这里在检测到掉线时，
 * 拿解密出来的密码静默重登一次，把新 Cookie 灌回全局 {@link HttpClient}，
 * 用户全程无感。
 *
 * <p>触发点：
 * <ul>
 *   <li>{@link #onAuthFailure(Context)} —— 网络层收到 403 / 登录页时调用（带冷却，不会狂刷）</li>
 *   <li>{@link #ensureSession(Context, Callback)} —— App 启动 / 回前台时兜底检查</li>
 *   <li>{@link #reloginAccount(Context, String, Callback)} —— 切换到某个掉线账号时手动触发</li>
 * </ul>
 *
 * <p>密码只解密到内存里用一次，绝不回显给 UI。
 */
public final class SessionGuard {

    /** 两次自动重登之间的最小间隔，避免密码错误时疯狂打服务器 */
    private static final long COOLDOWN_MS = 60_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastAttemptAt = 0L;
    private static volatile long lastFailureAt = 0L;

    private SessionGuard() {
    }

    public interface Callback {
        void onResult(boolean success, String message);
    }

    // ==================== 网络层回调 ====================

    /**
     * 网络层发现疑似掉线（HTTP 403 或返回登录页）时调用。
     * 带 60 秒冷却 + 单飞，随便调多少次都安全。
     */
    public static void onAuthFailure(Context context) {
        if (context == null) return;
        lastFailureAt = System.currentTimeMillis();
        final Context app = context.getApplicationContext();
        if (!shouldTry()) return;
        ensureSession(app, null);
    }

    private static boolean shouldTry() {
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < COOLDOWN_MS) return false;
        return !RUNNING.get();
    }

    /** 最近是否刚发生过疑似掉线（给 UI 提示用） */
    public static boolean recentlyFailed() {
        return System.currentTimeMillis() - lastFailureAt < COOLDOWN_MS;
    }

    // ==================== 兜底检查 ====================

    /**
     * 确保当前有可用登录态：已登录直接返回；掉线且存了密码就静默重登。
     * 异步执行，回调在主线程。
     */
    public static void ensureSession(Context context, Callback callback) {
        if (context == null) return;
        final Context app = context.getApplicationContext();

        if (HttpClient.getInstance().isLoggedIn()) {
            post(callback, true, "登录态正常");
            return;
        }
        String uid = AccountManager.activeUid(app);
        if (TextUtils.isEmpty(uid)) {
            post(callback, false, "没有可恢复的账号");
            return;
        }
        reloginAccount(app, uid, callback);
    }

    /**
     * 用已保存的密码重新登录指定账号，并把新 Cookie 应用到全局会话。
     */
    public static void reloginAccount(Context context, final String uid, final Callback callback) {
        if (context == null || TextUtils.isEmpty(uid)) {
            post(callback, false, "账号不存在");
            return;
        }
        final Context app = context.getApplicationContext();

        if (!RUNNING.compareAndSet(false, true)) {
            post(callback, false, "正在重新登录，请稍候");
            return;
        }
        lastAttemptAt = System.currentTimeMillis();

        new Thread(() -> {
            boolean ok = false;
            String message;
            try {
                message = doRelogin(app, uid);
                ok = message == null;
                if (ok) message = "已自动重新登录";
            } catch (Exception e) {
                message = "重登失败：" + e;
            } finally {
                RUNNING.set(false);
            }
            AiLog.i("session", "自动重登 uid=" + uid + " -> " + message);
            post(callback, ok, message);
        }, "mt-session-guard").start();
    }

    /** @return null 表示成功，否则返回失败原因 */
    private static String doRelogin(Context app, String uid) {
        AccountManager.Account account = AccountManager.get(app, uid);
        if (account == null) return "账号不存在";

        String password = AccountManager.decryptPassword(account);
        if (TextUtils.isEmpty(password)) {
            return "未保存密码，请手动登录一次并勾选「记住密码」";
        }
        if (TextUtils.isEmpty(account.username)) {
            return "缺少用户名，无法重登";
        }

        MtSignApi.LoginResult result = MtSignApi.login(account.username, password);
        if (!result.success || TextUtils.isEmpty(result.cookie)) {
            return TextUtils.isEmpty(result.message) ? "重新登录失败" : result.message;
        }

        // 新 Cookie 落到账号快照
        AccountManager.updateCookieHeader(app, uid, result.cookie);

        // 再灌进全局会话，让前台页面立刻恢复登录态
        try {
            HttpClient client = HttpClient.getInstance();
            client.applyCookiesFromString(result.cookie);
            client.commitCookieStore(app);
            client.syncToCookieManager();
        } catch (Exception e) {
            return "Cookie 写入失败：" + e;
        }
        AccountManager.setActiveUid(app, uid);
        return null;
    }

    private static void post(Callback callback, boolean success, String message) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResult(success, message));
    }
}
