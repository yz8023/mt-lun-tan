package com.solosu.mtforum.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.network.HttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多账号批量签到调度器（build60 新增，功能对标 Forinxy/mt）。
 *
 * <p>单账号签到链路（每个账号独立会话，互不串味）：
 * <pre>
 *   ① 取该账号 cookie 快照 → Cookie 直签
 *   ② 若 Cookie 失效且存了密码且开启自动重登 → 密码登录 → 回存新 Cookie → 再签
 *   ③ 写回签到记录（状态 / 排名 / 奖励 / 日期）
 *   ④ 账号之间 sleep N 秒，避免 bbs.binmt.cc 的阿里云 ESA 按 IP 限流 403
 * </pre>
 *
 * <p>整个过程走 {@link MtSignApi} 的独立 OkHttpClient，
 * <b>不会动全局 HttpClient</b>，所以前台正在用的账号不会被顶掉。
 */
public final class MultiSignInManager {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MultiSignInManager() {
    }

    // ==================== 结果模型 ====================

    public static class ItemResult {
        public String uid;
        public String username;
        public boolean success;
        public boolean alreadySigned;
        public boolean skipped;
        public String status = "";
        public String ranking = "";
        public String reward = "";
        public String message = "";
        /** 是否发生了自动重登 */
        public boolean reLogged;

        public String line() {
            StringBuilder sb = new StringBuilder(username == null ? uid : username).append("：");
            if (skipped) {
                sb.append("今日已签，跳过");
            } else if (success) {
                sb.append(TextUtils.isEmpty(status) ? "签到成功" : status);
                if (!TextUtils.isEmpty(ranking)) sb.append(" 排名").append(ranking);
                boolean statusAlreadyHasReward = !TextUtils.isEmpty(status)
                        && (status.contains("奖励") || status.contains("获得") || status.contains("增加"));
                if (!statusAlreadyHasReward && !TextUtils.isEmpty(reward) && !"0".equals(reward)) {
                    sb.append(" 签到奖励 +").append(reward);
                }
                if (reLogged) sb.append("（已自动重登）");
            } else {
                sb.append(TextUtils.isEmpty(message) ? "失败" : message);
            }
            return sb.toString();
        }
    }

    public static class Summary {
        public int total;
        public int success;
        public int failed;
        public int skipped;
        public final List<ItemResult> items = new ArrayList<>();

        public String shortText() {
            if (total == 0) return "没有可签到的账号";
            return "成功 " + success + " · 跳过 " + skipped + " · 失败 " + failed;
        }

        public String detailText() {
            if (items.isEmpty()) return "没有可签到的账号";
            StringBuilder sb = new StringBuilder(shortText()).append('\n');
            for (ItemResult r : items) sb.append('\n').append(r.line());
            return sb.toString();
        }
    }

    public interface Callback {
        /** 主线程回调；index 从 1 开始 */
        void onProgress(int index, int total, String username);

        void onFinished(Summary summary);
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    // ==================== 异步入口 ====================

    /**
     * 给所有已启用账号签到。
     *
     * @param force true = 忽略"今日已签"本地记录，强制重跑
     */
    public static void signInAll(Context context, boolean force, Callback callback) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            if (callback != null) {
                MAIN.post(() -> callback.onFinished(busySummary()));
            }
            return;
        }
        new Thread(() -> {
            Summary summary;
            try {
                summary = runBatch(app, AccountManager.enabledList(app), force, callback);
            } catch (Exception e) {
                summary = new Summary();
                AiLog.i("sign-in", "批量签到异常：" + e);
            } finally {
                RUNNING.set(false);
            }
            final Summary out = summary;
            if (callback != null) MAIN.post(() -> callback.onFinished(out));
        }, "mt-multi-signin").start();
    }

    /** 只给一个账号签到（账号列表里的单条操作） */
    public static void signInOne(Context context, String uid, boolean force, Callback callback) {
        if (context == null || TextUtils.isEmpty(uid)) return;
        final Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            if (callback != null) MAIN.post(() -> callback.onFinished(busySummary()));
            return;
        }
        new Thread(() -> {
            Summary summary = new Summary();
            try {
                AccountManager.Account a = AccountManager.get(app, uid);
                List<AccountManager.Account> one = new ArrayList<>();
                if (a != null) one.add(a);
                summary = runBatch(app, one, force, callback);
            } catch (Exception e) {
                AiLog.i("sign-in", "单账号签到异常：" + e);
            } finally {
                RUNNING.set(false);
            }
            final Summary out = summary;
            if (callback != null) MAIN.post(() -> callback.onFinished(out));
        }, "mt-single-signin").start();
    }

    // ==================== 同步核心（Worker 直接调这个） ====================

    /** 同步执行批量签到，供 WorkManager 后台任务调用 */
    public static Summary signInAllSync(Context context, boolean force) {
        Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return busySummary();
        try {
            return runBatch(app, AccountManager.enabledList(app), force, null);
        } catch (Exception e) {
            AiLog.i("sign-in", "定时签到异常：" + e);
            return new Summary();
        } finally {
            RUNNING.set(false);
        }
    }

    private static Summary runBatch(Context app, List<AccountManager.Account> accounts,
                                    boolean force, Callback callback) {
        Summary summary = new Summary();
        if (accounts == null || accounts.isEmpty()) {
            SignInSettings.saveLastRun(app, "没有可签到的账号");
            return summary;
        }
        summary.total = accounts.size();
        int intervalSec = SignInSettings.getIntervalSeconds(app);

        for (int i = 0; i < accounts.size(); i++) {
            AccountManager.Account account = accounts.get(i);
            final int index = i + 1;
            if (callback != null) {
                MAIN.post(() -> callback.onProgress(index, accounts.size(), account.displayName()));
            }

            ItemResult item = signSingle(app, account, force);
            summary.items.add(item);
            if (item.skipped) summary.skipped++;
            else if (item.success) summary.success++;
            else summary.failed++;

            // build69: 「今日已签，跳过」以前每次回前台都为每个账号刷一行，
            // 把运行日志冲得没法看。跳过的不再逐条记，只在真正动作时记。
            if (!item.skipped) AiLog.i("sign-in", item.line());

            // 账号之间歇一会儿，论坛挂了 ESA，连发必吃 403
            if (i < accounts.size() - 1 && intervalSec > 0) {
                try {
                    Thread.sleep(intervalSec * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        SignInSettings.saveLastRun(app, summary.shortText());
        return summary;
    }

    /** 单账号完整链路：Cookie 直签 →（失败）密码重登再签 */
    private static ItemResult signSingle(Context app, AccountManager.Account account, boolean force) {
        ItemResult item = new ItemResult();
        item.uid = account.uid;
        item.username = account.displayName();

        // 本地记录显示今天签过了就别打扰服务器
        if (!force && account.isSignedToday()) {
            item.skipped = true;
            item.success = true;
            item.status = "今日已签";
            item.ranking = account.lastSignRanking == null ? "" : account.lastSignRanking;
            item.reward = account.lastSignReward == null ? "" : account.lastSignReward;
            return item;
        }

        String cookieHeader = AccountManager.cookieHeaderOf(account);
        MtSignApi.SignResult result = null;

        if (!TextUtils.isEmpty(cookieHeader)) {
            result = MtSignApi.signWithCookie(cookieHeader);
        }

        // ESA challenge cookies are shared by the browser/domain rather than by forum account.
        // Resolve it in an invisible WebView, copy only protection cookies into this account's
        // header (never overwrite its auth cookie), and retry at most three times.
        String verifyFailure = "";
        for (int attempt = 1; result != null && result.blocked && attempt <= 3; attempt++) {
            AiLog.i("sign-in", account.displayName() + " 遇到站点验证，后台处理 " + attempt + "/3");
            HeadlessSiteVerifier.Result verification =
                    HeadlessSiteVerifier.verify(app, HttpClient.BASE_URL);
            if (!verification.success) {
                verifyFailure = verification.detail;
                AiLog.i("sign-in", account.displayName() + " 后台验证 " + attempt + "/3 未通过："
                        + verification.detail);
                continue;
            }
            AiLog.i("sign-in", account.displayName() + " 后台验证 " + attempt
                    + "/3 已通过，重新签到");
            cookieHeader = mergeProtectionCookies(cookieHeader, verification.cookies);
            result = MtSignApi.signWithCookie(cookieHeader);
        }
        if (result != null && result.blocked) {
            result.message = "站点验证连续 3 次未恢复"
                    + (TextUtils.isEmpty(verifyFailure) ? "" : "（" + verifyFailure + "）")
                    + "；请打开设置中的“站点验证与 Cookie 同步”后重试";
        }

        boolean needRelogin = result == null || (result.cookieInvalid && !result.blocked);
        if (needRelogin && SignInSettings.isAutoReloginEnabled(app)) {
            String password = AccountManager.decryptPassword(account);
            if (!TextUtils.isEmpty(password) && !TextUtils.isEmpty(account.credentialName())) {
                String protection = SiteAccessManager.protectionCookieHeader(
                        HttpClient.getInstance().getCookieHeader());
                MtSignApi.SignResult relogin = MtSignApi.loginAndSign(
                        account.credentialName(), password, protection);
                if (!TextUtils.isEmpty(relogin.refreshedCookie)) {
                    AccountManager.updateCookieHeader(app, account.uid, relogin.refreshedCookie);
                }
                relogin.message = TextUtils.isEmpty(relogin.message) ? "" : relogin.message;
                item.reLogged = true;
                result = relogin;
            } else if (result == null) {
                result = new MtSignApi.SignResult();
                result.message = "登录态已失效，且未保存密码";
            } else if (result.cookieInvalid) {
                result.message = "登录态已失效，且未保存密码";
            }
        } else if (result != null && !TextUtils.isEmpty(result.refreshedCookie)) {
            AccountManager.updateCookieHeader(app, account.uid, result.refreshedCookie);
        }

        if (result == null) {
            item.message = "没有可用的登录态";
            return item;
        }

        item.success = result.success;
        item.alreadySigned = result.alreadySigned;
        item.status = result.status;
        item.ranking = result.ranking;
        item.reward = result.reward;
        item.message = result.message;

        AccountManager.recordSign(app, account.uid, result.success,
                TextUtils.isEmpty(result.status) ? result.message : result.status,
                result.ranking, result.reward);

        // 前台账号签到成功后，同步一下全局签到日期，避免 AutoSignInManager 重复跑
        if (result.success && account.uid.equals(AccountManager.activeUid(app))) {
            UserSessionManager.getInstance().saveSignInDate(app);
        }
        return item;
    }

    private static String mergeProtectionCookies(String accountHeader, String browserHeader) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String part : (accountHeader == null ? "" : accountHeader).split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) values.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        for (String part : (browserHeader == null ? "" : browserHeader).split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("clearance") || lower.startsWith("acw_")
                    || lower.startsWith("aliyungf_") || lower.startsWith("__jsl")) {
                values.put(name, part.substring(eq + 1).trim());
            }
        }
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    private static Summary busySummary() {
        Summary s = new Summary();
        ItemResult r = new ItemResult();
        r.username = "系统";
        r.message = "上一轮签到还在跑，请稍候";
        s.items.add(r);
        s.total = 1;
        s.failed = 1;
        return s;
    }
}
