package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.util.CryptoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 多账号管理器（build60 重写）。
 *
 * <p>数据落在 SharedPreferences {@code sqapp_accounts}：
 * <ul>
 *   <li>{@code accounts}：JSON 数组，每项一个账号</li>
 *   <li>{@code active_uid}：当前前台账号 uid</li>
 * </ul>
 *
 * <p>相比旧版新增：
 * <ul>
 *   <li>{@code passwordEnc}：KeyStore 加密的密码，Cookie 过期时自动重登用</li>
 *   <li>{@code enabled}：是否参与"一键全部签到"</li>
 *   <li>{@code lastSignDate/Status/Ranking/Reward/Time}：每账号独立的签到记录</li>
 * </ul>
 *
 * <p>切换账号 = 用该账号的 cookie 快照整体覆盖 {@code sqapp_cookies} 持久层，
 * 再让 {@link HttpClient} 清内存重新读盘。
 */
public class AccountManager {

    // ==================== 模型 ====================

    public static class Account {
        public String uid;
        public String username;
        public String avatar;
        public String level;
        /** 该账号完整 cookie 快照（HttpClient 的 cookies_json 格式） */
        public String cookies;
        /** 加密后的密码，空 = 未保存 */
        public String passwordEnc;
        /** 是否参与批量签到 */
        public boolean enabled = true;
        public long createTime;

        // ---- 签到记录 ----
        /** yyyy-MM-dd */
        public String lastSignDate;
        public String lastSignStatus;
        public String lastSignRanking;
        public String lastSignReward;
        public long lastSignTime;

        public boolean hasPassword() {
            return !TextUtils.isEmpty(passwordEnc);
        }

        public boolean isSignedToday() {
            return !TextUtils.isEmpty(lastSignDate) && lastSignDate.equals(today());
        }

        public String displayName() {
            return TextUtils.isEmpty(username) ? ("UID_" + uid) : username;
        }

        /** 列表二级文案：今日签到状态 */
        public String signSummary() {
            if (isSignedToday()) {
                StringBuilder sb = new StringBuilder("今日已签");
                if (!TextUtils.isEmpty(lastSignStatus) && !"今日已签".equals(lastSignStatus)) {
                    sb.setLength(0);
                    sb.append(lastSignStatus);
                }
                if (!TextUtils.isEmpty(lastSignRanking)) sb.append(" · 排名 ").append(lastSignRanking);
                if (!TextUtils.isEmpty(lastSignReward) && !"0".equals(lastSignReward)) {
                    sb.append(" · +").append(lastSignReward);
                }
                return sb.toString();
            }
            if (!TextUtils.isEmpty(lastSignStatus)) {
                return "今日未签 · 上次：" + lastSignStatus;
            }
            return "今日未签到";
        }

        /** 最近一次签到的时刻，形如 08:32；没签过返回空串 */
        public String lastSignTimeText() {
            if (lastSignTime <= 0) return "";
            return new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(lastSignTime));
        }

        /**
         * 侧边栏平铺账号行的签到文案（build61）。
         * 已签：{@code 已签 08:32 · +5 金币}（前缀对勾由矢量 drawable 提供）；未签：{@code 今日未签到}。
         */
        public String drawerSignText() {
            if (isSignedToday()) {
                StringBuilder sb = new StringBuilder("已签");
                String time = lastSignTimeText();
                if (!TextUtils.isEmpty(time)) sb.append(' ').append(time);
                if (!TextUtils.isEmpty(lastSignReward) && !"0".equals(lastSignReward)) {
                    sb.append(" · +").append(lastSignReward).append(" 金币");
                }
                if (!TextUtils.isEmpty(lastSignRanking)) {
                    sb.append(" · 第").append(lastSignRanking).append("名");
                }
                return sb.toString();
            }
            if (!TextUtils.isEmpty(lastSignStatus)) {
                return "今日未签 · 上次：" + lastSignStatus;
            }
            return "今日未签到";
        }

        @Override
        public String toString() {
            return displayName() + "(" + uid + ")";
        }
    }

    // ==================== 存储键 ====================

    private static final String PREF = "sqapp_accounts";
    private static final String KEY_LIST = "accounts";
    private static final String KEY_ACTIVE = "active_uid";
    private static final String COOKIE_PREF = "sqapp_cookies";
    private static final String COOKIE_KEY = "cookies_json";

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    // ==================== 读写 ====================

    /** 账号列表（按加入顺序） */
    public static List<Account> list(Context c) {
        List<Account> out = new ArrayList<>();
        try {
            String json = prefs(c).getString(KEY_LIST, null);
            if (TextUtils.isEmpty(json)) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Account a = fromJson(arr.getJSONObject(i));
                if (a != null) out.add(a);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 参与批量签到的账号 */
    public static List<Account> enabledList(Context c) {
        List<Account> out = new ArrayList<>();
        for (Account a : list(c)) {
            if (a.enabled) out.add(a);
        }
        return out;
    }

    public static Account get(Context c, String uid) {
        if (TextUtils.isEmpty(uid)) return null;
        for (Account a : list(c)) {
            if (uid.equals(a.uid)) return a;
        }
        return null;
    }

    public static int count(Context c) {
        return list(c).size();
    }

    /** 插入或更新单个账号（按 uid 去重），不改动 active_uid */
    public static void put(Context c, Account account) {
        if (account == null || TextUtils.isEmpty(account.uid)) return;
        List<Account> list = list(c);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (account.uid.equals(list.get(i).uid)) {
                list.set(i, account);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (account.createTime <= 0) account.createTime = System.currentTimeMillis();
            list.add(account);
        }
        persist(c, list);
    }

    private static void persist(Context c, List<Account> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Account a : list) arr.put(toJson(a));
            prefs(c).edit().putString(KEY_LIST, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject toJson(Account a) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uid", a.uid);
        o.put("username", a.username);
        o.put("avatar", a.avatar);
        o.put("level", a.level);
        o.put("cookies", a.cookies);
        o.put("passwordEnc", a.passwordEnc);
        o.put("enabled", a.enabled);
        o.put("createTime", a.createTime);
        o.put("lastSignDate", a.lastSignDate);
        o.put("lastSignStatus", a.lastSignStatus);
        o.put("lastSignRanking", a.lastSignRanking);
        o.put("lastSignReward", a.lastSignReward);
        o.put("lastSignTime", a.lastSignTime);
        return o;
    }

    private static Account fromJson(JSONObject o) {
        String uid = o.optString("uid");
        if (TextUtils.isEmpty(uid)) return null;
        Account a = new Account();
        a.uid = uid;
        a.username = o.optString("username");
        a.avatar = o.optString("avatar");
        a.level = o.optString("level");
        a.cookies = o.optString("cookies");
        a.passwordEnc = o.optString("passwordEnc");
        a.enabled = o.optBoolean("enabled", true);
        a.createTime = o.optLong("createTime", 0L);
        a.lastSignDate = o.optString("lastSignDate");
        a.lastSignStatus = o.optString("lastSignStatus");
        a.lastSignRanking = o.optString("lastSignRanking");
        a.lastSignReward = o.optString("lastSignReward");
        a.lastSignTime = o.optLong("lastSignTime", 0L);
        return a;
    }

    // ==================== 当前登录态入库 ====================

    /** 兼容旧调用：把当前登录态保存为账号（不带密码） */
    public static void saveCurrent(Context c, String uid, String username, String avatar, String level) {
        saveCurrent(c, uid, username, avatar, level, null);
    }

    /**
     * 把当前登录态存成账号快照。
     *
     * @param plainPassword 明文密码，非空时加密保存；传 null 保留原有密码
     */
    public static void saveCurrent(Context c, String uid, String username, String avatar,
                                   String level, String plainPassword) {
        if (TextUtils.isEmpty(uid)) return;
        String cookiesJson = readCurrentCookiesJson(c);
        if (TextUtils.isEmpty(cookiesJson)) return;

        Account old = get(c, uid);
        Account acc = old != null ? old : new Account();
        acc.uid = uid;
        acc.username = TextUtils.isEmpty(username) ? acc.displayName() : username;
        if (!TextUtils.isEmpty(avatar)) acc.avatar = avatar;
        if (!TextUtils.isEmpty(level)) acc.level = level;
        acc.cookies = cookiesJson;
        if (!TextUtils.isEmpty(plainPassword)) {
            acc.passwordEnc = CryptoUtils.encrypt(plainPassword);
        }
        if (acc.createTime <= 0) acc.createTime = System.currentTimeMillis();

        put(c, acc);
        prefs(c).edit().putString(KEY_ACTIVE, uid).apply();
    }

    // ==================== 密码 ====================

    public static void setPassword(Context c, String uid, String plainPassword) {
        Account a = get(c, uid);
        if (a == null) return;
        a.passwordEnc = TextUtils.isEmpty(plainPassword) ? "" : CryptoUtils.encrypt(plainPassword);
        put(c, a);
    }

    /** 取明文密码，没存过 / 解密失败返回空串 */
    public static String getPassword(Context c, String uid) {
        Account a = get(c, uid);
        return a == null ? "" : CryptoUtils.decrypt(a.passwordEnc);
    }

    public static String decryptPassword(Account a) {
        return a == null ? "" : CryptoUtils.decrypt(a.passwordEnc);
    }

    public static void clearPassword(Context c, String uid) {
        setPassword(c, uid, null);
    }

    // ==================== 开关 / 签到记录 ====================

    public static void setEnabled(Context c, String uid, boolean enabled) {
        Account a = get(c, uid);
        if (a == null) return;
        a.enabled = enabled;
        put(c, a);
    }

    /** 记录一次签到结果 */
    public static void recordSign(Context c, String uid, boolean success,
                                  String status, String ranking, String reward) {
        Account a = get(c, uid);
        if (a == null) return;
        a.lastSignStatus = status;
        // build68: "今日已签"这种情况服务端页面上拿不到当天的奖励数字
        // （lxreward 只在未签到的按钮上有值），此时不能用空串把之前记下的覆盖掉，
        // 否则同一天再点一次签到，金币/排名就没了 —— 这就是"有的显示详情有的只显示今日已签"。
        if (!TextUtils.isEmpty(ranking)) a.lastSignRanking = ranking;
        if (!TextUtils.isEmpty(reward) && !"0".equals(reward)) a.lastSignReward = reward;
        a.lastSignTime = System.currentTimeMillis();
        if (success) a.lastSignDate = today();
        put(c, a);
    }

    /** WebView 完成人机验证后，把新增的 clearance Cookie 合并保存到当前账号快照。 */
    public static void refreshActiveCookieSnapshot(Context c) {
        if (c == null) return;
        String uid = activeUid(c);
        Account a = get(c, uid);
        if (a == null) return;
        String snapshot = readCurrentCookiesJson(c);
        if (TextUtils.isEmpty(snapshot)) return;
        a.cookies = snapshot;
        put(c, a);
    }

    /** 自动重登后回存新的 cookie 快照 */
    public static void updateCookieHeader(Context c, String uid, String cookieHeader) {
        if (TextUtils.isEmpty(cookieHeader)) return;
        Account a = get(c, uid);
        if (a == null) return;
        a.cookies = headerToCookiesJson(cookieHeader);
        put(c, a);
        // 如果刷新的正好是前台账号，顺手把活动会话也同步了，省得用户看到"未登录"
        if (uid.equals(activeUid(c))) {
            try {
                c.getApplicationContext()
                        .getSharedPreferences(COOKIE_PREF, Context.MODE_PRIVATE)
                        .edit().putString(COOKIE_KEY, a.cookies).apply();
                HttpClient.getInstance().clearCookies();
                HttpClient.getInstance().restoreCookieStore(c.getApplicationContext());
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 切换 / 删除 ====================

    public static String activeUid(Context c) {
        return prefs(c).getString(KEY_ACTIVE, null);
    }

    public static void setActiveUid(Context c, String uid) {
        prefs(c).edit().putString(KEY_ACTIVE, uid).apply();
    }

    /**
     * 账号切换代数。每切一次 +1，页面据此判断"我展示的数据是不是上一个账号的"。
     */
    private static final java.util.concurrent.atomic.AtomicInteger SWITCH_EPOCH =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static int currentEpoch() {
        return SWITCH_EPOCH.get();
    }

    /** 切换账号：cookie 快照整体回灌到 HttpClient */
    public static synchronized boolean switchTo(Context c, String uid) {
        try {
            Account target = get(c, uid);
            if (target == null || TextUtils.isEmpty(target.cookies)) return false;

            prefs(c).edit().putString(KEY_ACTIVE, uid).apply();
            c.getApplicationContext()
                    .getSharedPreferences(COOKIE_PREF, Context.MODE_PRIVATE)
                    .edit().putString(COOKIE_KEY, target.cookies).apply();

            HttpClient client = HttpClient.getInstance();
            // build65: 必须先清去重缓存，否则新账号会复用上一个账号的页面结果
            client.clearPendingCache();
            client.clearCookies();
            client.restoreCookieStore(c.getApplicationContext());
            client.syncToCookieManager();

            SWITCH_EPOCH.incrementAndGet();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void remove(Context c, String uid) {
        List<Account> list = list(c);
        List<Account> out = new ArrayList<>();
        for (Account a : list) {
            if (!uid.equals(a.uid)) out.add(a);
        }
        persist(c, out);
        if (uid.equals(activeUid(c))) {
            prefs(c).edit().remove(KEY_ACTIVE).apply();
        }
    }

    public static void removeAll(Context c) {
        prefs(c).edit().remove(KEY_LIST).remove(KEY_ACTIVE).apply();
    }

    // ==================== Cookie 格式互转 ====================

    /**
     * HttpClient 的 cookies_json 快照 → 请求头字符串。
     * 签到引擎 {@link MtSignApi} 只认 {@code name=value; name=value} 形式。
     */
    public static String cookiesJsonToHeader(String cookiesJson) {
        if (TextUtils.isEmpty(cookiesJson)) return "";
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(cookiesJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("name");
                String value = o.optString("value");
                if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(name).append('=').append(value);
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /** 请求头字符串 → HttpClient 的 cookies_json 快照 */
    public static String headerToCookiesJson(String cookieHeader) {
        if (TextUtils.isEmpty(cookieHeader)) return "";
        String host = "bbs.binmt.cc";
        JSONArray arr = new JSONArray();
        try {
            for (String pair : cookieHeader.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (name.isEmpty() || value.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("host", host);
                o.put("name", name);
                o.put("value", value);
                o.put("domain", host);
                o.put("path", "/");
                o.put("expiresAt", Long.MAX_VALUE);
                o.put("secure", true);
                o.put("httpOnly", false);
                o.put("persistent", true);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    /** 取某账号的 Cookie 请求头 */
    public static String cookieHeaderOf(Account a) {
        return a == null ? "" : cookiesJsonToHeader(a.cookies);
    }

    /** 读取当前 sqapp_cookies 的原始 JSON（保存快照用） */
    private static String readCurrentCookiesJson(Context c) {
        try {
            SharedPreferences sp = c.getApplicationContext()
                    .getSharedPreferences(COOKIE_PREF, Context.MODE_PRIVATE);
            String json = sp.getString(COOKIE_KEY, null);
            return json != null && !json.isEmpty() ? json : null;
        } catch (Exception e) {
            return null;
        }
    }
}
