package com.solosu.mtforum.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.solosu.mtforum.model.UserProfile;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动签到管理器。
 *
 * <p>build60 起分两条路：
 * <ul>
 *   <li><b>多账号模式</b>（默认开）：交给 {@link MultiSignInManager}，
 *       给账号库里所有已启用账号逐个签到，每个账号独立会话，前台登录态不受影响。</li>
 *   <li><b>单账号模式</b>：沿用旧逻辑，只签当前前台登录的账号。</li>
 * </ul>
 *
 * <p>单账号流程:1.检查登录态 2.检查设置开关 3.本地签到记录 4.网络请求签到状态 5.本地判断后执行签到。
 */
public final class AutoSignInManager {

    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AutoSignInManager() {
    }

    public interface Callback {
        void onFinished(boolean success, boolean performed, String message);
    }

    // ==================== 开关（沿用旧键，老用户设置不丢） ====================

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        return SignInSettings.isAutoSignInEnabled(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SignInSettings.setAutoSignInEnabled(context, enabled);
    }

    // ==================== 入口 ====================

    /**
     * 自动签到（受开关控制，进 App / onStart 时调用）。
     */
    public static void checkAndSignIn(Context context, Callback callback) {
        if (context == null) return;
        if (!isEnabled(context)) return;
        run(context, false, callback);
    }

    /**
     * 立即签到一次（忽略开关，用户主动点击时调用）。
     *
     * @param force true = 忽略"今日已签"的本地记录强制重跑
     */
    public static void signInNow(Context context, boolean force, Callback callback) {
        if (context == null) return;
        run(context, force, callback);
    }

    private static void run(Context context, boolean force, Callback callback) {
        final Context app = context.getApplicationContext();
        // 按“全部账号签到”设置选择范围；关闭时仅对当前会话账号执行旧链路。
        // 不再因为账号库里恰好有多条记录而无条件串行等待所有账号。
        if (!AccountManager.enabledList(app).isEmpty()
                && SignInSettings.isAllAccounts(app)) {
            runMulti(app, force, callback);
            return;
        }

        signInCurrentAccount(app, force, callback);
    }

    /** 多账号链路（会记录时间/排名/奖励） */
    private static void runMulti(Context app, boolean force, Callback callback) {
        {
            MultiSignInManager.signInAll(app, force, new MultiSignInManager.Callback() {
                @Override
                public void onProgress(int index, int total, String username) {
                }

                @Override
                public void onFinished(MultiSignInManager.Summary summary) {
                    if (callback == null) return;
                    boolean success = summary.failed == 0 && summary.total > 0;
                    boolean performed = summary.success > 0 && summary.skipped < summary.total;
                    String message = summary.total == 0 ? "没有可签到的账号" : summary.shortText();
                    callback.onFinished(success, performed, message);
                }
            });
        }
    }

    // ==================== 单账号（旧逻辑，保留） ====================

    /** 只给当前前台账号签到，用全局 HttpClient 的登录态 */
    public static void signInCurrentAccount(Context context, boolean force, Callback callback) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        final HttpClient client = HttpClient.getInstance();
        if (!CHECKING.compareAndSet(false, true)) return;

        // 第一步:检查登录状态
        if (!client.isLoggedIn()) {
            CHECKING.set(false);
            if (callback != null) {
                MAIN_HANDLER.post(() -> callback.onFinished(false, false, "请先登录"));
            }
            return;
        }

        // 第二步:检查本地签到记录(今天已签到则跳过,防止重复签到)
        if (!force && UserSessionManager.getInstance().isSignedInToday(appContext)) {
            CHECKING.set(false);
            if (callback != null) {
                MAIN_HANDLER.post(() -> callback.onFinished(true, false, "今日已签到"));
            }
            return;
        }

        new Thread(() -> {
            boolean success = false;
            boolean performed = false;
            String message = "";
            try {
                // 第三步:网络请求获取签到状态
                String html = client.get(ForumParser.getForumlistMobileUrl());
                if (TextUtils.isEmpty(html) || ForumParser.isLoginPage(html)) {
                    success = false;
                    message = "登录状态已失效";
                } else {
                    ForumParser.CommunityPageData data = ForumParser.parseCommunityPage(html);
                    String signText = data.getSignInText();

                    // 第四步:本地判断是否已签到
                    boolean pageShowsSignedIn = isAlreadySigned(signText)
                            || data.isAlreadySignedIn()
                            || html.contains("今日已签")
                            || html.contains("已签到");
                    if (pageShowsSignedIn && !force) {
                        success = true;
                        message = "今日已签到";
                        UserSessionManager.getInstance().saveSignInDate(appContext);
                    } else if (data.isLoginRequired()) {
                        message = "请先登录";
                    } else if (!force && !isNotSigned(signText)) {
                        message = "无法确认当前签到状态";
                    } else {
                        // 未签到 -> 执行自动签到
                        String formhash = data.getFormhash();
                        if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(html);
                        if (TextUtils.isEmpty(formhash)) {
                            message = "无法获取签到凭证";
                        } else {
                            Map<String, String> params = new HashMap<>();
                            params.put("formhash", formhash);
                            String signUrl = HttpClient.BASE_URL
                                    + "plugin.php?id=k_misign:sign&operation=qiandao&format=text";
                            String result = client.post(signUrl, params);
                            message = cleanMessage(result);
                            success = isSignSuccess(message);
                            performed = true;
                            if (success) {
                                UserSessionManager.getInstance().saveSignInDate(appContext);
                                syncActiveAccountRecord(appContext, message);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                message = e.getMessage() == null ? "自动签到失败" : e.getMessage();
            } finally {
                CHECKING.set(false);
            }

            if (callback != null) {
                final boolean finalSuccess = success;
                final boolean finalPerformed = performed;
                final String finalMessage = message;
                MAIN_HANDLER.post(() -> callback.onFinished(finalSuccess, finalPerformed, finalMessage));
            }
        }).start();
    }

    // ==================== 当前会话入库 ====================

    /**
     * 把当前前台登录的账号补进账号库（老用户 / 首次登录没走新链路的情况）。
     * 在后台线程调用，联网拉一次资料页补 uid/头像。
     */
    public static void syncCurrentSessionToAccounts(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!HttpClient.getInstance().isLoggedIn()) return;
                UserSessionManager session = UserSessionManager.getInstance();
                String uid = session.getUid(app);
                String username = session.getUsername(app);

                if (TextUtils.isEmpty(uid) || "0".equals(uid)) {
                    String html = HttpClient.getInstance().get(
                            ForumParser.getBaseDomain() + "home.php?mod=space&do=profile&mobile=2");
                    UserProfile profile = ForumParser.parseUserProfile(html);
                    if (profile != null) {
                        uid = profile.getUid();
                        if (TextUtils.isEmpty(username)) username = profile.getUsername();
                        Map<String, String> info = new HashMap<>();
                        if (!TextUtils.isEmpty(username)) info.put("username", username);
                        if (!TextUtils.isEmpty(uid)) info.put("uid", uid);
                        if (!TextUtils.isEmpty(profile.getAvatarUrl())) {
                            info.put("avatarUrl", profile.getAvatarUrl());
                        }
                        if (!TextUtils.isEmpty(profile.getLevel())) {
                            info.put("level", profile.getLevel());
                        }
                        session.saveLoginInfo(app, info);
                    }
                }
                if (TextUtils.isEmpty(uid) || "0".equals(uid)) return;

                HttpClient.getInstance().commitCookieStore(app);
                AccountManager.saveCurrent(app, uid, session.getUsername(app),
                        session.getAvatarUrl(app), session.getLevel(app));
            } catch (Exception ignored) {
            }
        }, "mt-sync-account").start();
    }

    /** 单账号签到成功后，把结果同步一份到账号库记录里 */
    private static void syncActiveAccountRecord(Context app, String message) {
        try {
            String uid = UserSessionManager.getInstance().getUid(app);
            if (TextUtils.isEmpty(uid)) return;
            if (AccountManager.get(app, uid) == null) return;
            AccountManager.recordSign(app, uid, true, cleanMessage(message), "", "");
        } catch (Exception ignored) {
        }
    }

    // ==================== 文本判定 ====================

    private static boolean isAlreadySigned(String text) {
        if (TextUtils.isEmpty(text)) return false;
        return text.contains("今日已签") || text.contains("已签到") || text.contains("已签");
    }

    private static boolean isNotSigned(String text) {
        if (TextUtils.isEmpty(text)) return false;
        return text.contains("签到") && !isAlreadySigned(text);
    }

    private static boolean isSignSuccess(String text) {
        if (TextUtils.isEmpty(text)) return false;
        if (text.contains("失败") || text.contains("错误") || text.contains("请先登录")
                || text.contains("没有权限") || text.contains("非法操作")) return false;
        return text.contains("签到成功") || text.contains("今日已签")
                || text.contains("已签到") || text.contains("成功")
                || text.toLowerCase().contains("success")
                || text.toLowerCase().contains("succeed");
    }

    private static String cleanMessage(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String text = raw.replaceAll("<!\\[CDATA\\[(.*?)\\]\\]>", "$1")
                .replaceAll("<[^>]+>", "")
                .trim();
        return text.isEmpty() ? raw.trim() : text;
    }
}
