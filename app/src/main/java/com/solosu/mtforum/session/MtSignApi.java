package com.solosu.mtforum.session;

import android.text.TextUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 独立签到引擎（build60 新增，思路参考 Forinxy/mt 的 MTForumApi）。
 *
 * <p>为什么不复用 {@link com.solosu.mtforum.network.HttpClient}：
 * 全局 HttpClient 只有一份 cookieStore，一旦拿它去给别的账号登录/签到，
 * 当前前台账号的登录态会被顶掉。这里每次调用都新建一个带独立内存 CookieJar
 * 的 OkHttpClient，**完全不触碰**前台会话，这样才能"后台给 N 个账号批量签到，
 * 前台账号不受影响"。
 *
 * <p>支持两条链路：
 * <ol>
 *   <li>{@link #signWithCookie(String)} —— Cookie 免登录直签，请求最少、最不容易触发风控</li>
 *   <li>{@link #login(String, String)} —— 账号密码登录，拿回完整 Cookie 串（含 auth + saltkey），
 *       用于 Cookie 过期后的自动重登</li>
 * </ol>
 *
 * <p>注意 Discuz 的登录态是 {@code xxx_auth} 和签发它的 {@code xxx_saltkey} 配套生效的，
 * 只带 auth 会被判定为游客，所以这里始终整串带走 / 整串回存。
 */
public final class MtSignApi {

    public static final String BASE_URL = "https://bbs.binmt.cc/";

    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private MtSignApi() {
    }

    // ==================== 结果模型 ====================

    /** 一次签到的结果 */
    public static class SignResult {
        /** 整体是否算成功（已签到也算成功） */
        public boolean success;
        /** 是否本来就已经签过（没有真正提交签到请求） */
        public boolean alreadySigned;
        /** Cookie 失效 —— 上层据此决定要不要用密码重登 */
        public boolean cookieInvalid;
        /** 站点防护 403 之类的拦截 */
        public boolean blocked;
        /** 展示用状态文案，如 "签到成功" / "今日已签" */
        public String status = "";
        /** 签到排名 */
        public String ranking = "";
        /** 本次奖励 */
        public String reward = "";
        /** 失败原因 / 附加信息 */
        public String message = "";
        /** 若本次链路刷新了 Cookie，这里带回完整 Cookie 头，上层要回存 */
        public String refreshedCookie;

        public String display() {
            StringBuilder sb = new StringBuilder();
            sb.append(TextUtils.isEmpty(status) ? (success ? "签到完成" : "签到失败") : status);
            if (!TextUtils.isEmpty(ranking) && !"未知".equals(ranking)) {
                sb.append(" · 排名 ").append(ranking);
            }
            if (!TextUtils.isEmpty(reward) && !"0".equals(reward)) {
                sb.append(" · +").append(reward);
            }
            return sb.toString();
        }
    }

    /** 一次登录的结果 */
    public static class LoginResult {
        public boolean success;
        /** 完整 Cookie 头（含 auth + saltkey），登录成功才有值 */
        public String cookie;
        public String nickname;
        public String message = "";
    }

    // ==================== 对外能力 ====================

    /**
     * Cookie 免登录直签。
     *
     * @param cookieHeader 完整 Cookie 头，形如 {@code a1b2_2132_auth=xxx; a1b2_2132_saltkey=yyy; ...}
     */
    public static SignResult signWithCookie(String cookieHeader) {
        SignResult result = new SignResult();
        if (TextUtils.isEmpty(cookieHeader)) {
            result.cookieInvalid = true;
            result.message = "没有可用的登录态";
            return result;
        }
        if (SignParser.isBareAuthToken(cookieHeader)) {
            result.cookieInvalid = true;
            result.message = "Cookie 只有 auth 缺 saltkey，需要重新登录";
            return result;
        }

        Session session = new Session();
        session.applyCookieHeader(cookieHeader);
        return doSign(session, result);
    }

    /**
     * 账号密码登录，成功后返回整串 Cookie。
     * 独立会话，不影响前台已登录的账号。
     */
    public static LoginResult login(String username, String password) {
        LoginResult out = new LoginResult();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            out.message = "账号或密码为空";
            return out;
        }
        Session session = new Session();
        try {
            String loginPage = session.get(BASE_URL + "member.php?mod=logging&action=login");
            if (SignParser.isBlocked(loginPage)) {
                out.message = "被站点防护拦截(403)，稍后再试";
                return out;
            }
            String formhash = SignParser.extractFormhash(loginPage);
            if (TextUtils.isEmpty(formhash)) {
                out.message = "登录页解析失败（可能被风控）";
                return out;
            }
            String loginhash = SignParser.extractLoginhash(loginPage);

            Map<String, String> form = new HashMap<>();
            form.put("formhash", formhash);
            form.put("referer", BASE_URL + "forum.php");
            form.put("loginfield", "username");
            form.put("username", username);
            form.put("password", password);
            form.put("questionid", "0");
            form.put("answer", "");
            form.put("cookietime", "2592000");

            String url = BASE_URL + "member.php?mod=logging&action=login&loginsubmit=yes"
                    + "&handlekey=login&inajax=1"
                    + (TextUtils.isEmpty(loginhash) ? "" : "&loginhash=" + loginhash);
            String body = session.post(url, form);

            if (!SignParser.isLoginSuccess(body) && !session.hasAuthCookie()) {
                out.message = SignParser.loginErrorMessage(body);
                return out;
            }
            out.success = true;
            out.cookie = session.cookieHeader();
            out.nickname = SignParser.extractNickname(body);
            if (TextUtils.isEmpty(out.nickname)) out.nickname = username;
            return out;
        } catch (Exception e) {
            out.message = "网络错误：" + safeMessage(e);
            return out;
        }
    }

    /**
     * 密码登录后立刻签到（Cookie 已过期时的完整链路）。
     * 返回的 {@link SignResult#refreshedCookie} 一定要回存，否则下次还要重登。
     */
    public static SignResult loginAndSign(String username, String password) {
        SignResult result = new SignResult();
        LoginResult login = login(username, password);
        if (!login.success) {
            result.message = login.message;
            result.cookieInvalid = true;
            return result;
        }
        Session session = new Session();
        session.applyCookieHeader(login.cookie);
        SignResult signed = doSign(session, result);
        if (TextUtils.isEmpty(signed.refreshedCookie)) {
            signed.refreshedCookie = login.cookie;
        }
        return signed;
    }

    /**
     * 只校验 Cookie 是否还活着（拉个人中心页），不签到。
     * 用于账号列表刷新在线状态。
     */
    public static boolean isCookieAlive(String cookieHeader) {
        if (TextUtils.isEmpty(cookieHeader)) return false;
        try {
            Session session = new Session();
            session.applyCookieHeader(cookieHeader);
            String html = session.get(BASE_URL + "home.php?mod=space&do=profile&mobile=2");
            return !TextUtils.isEmpty(html) && !SignParser.looksLoggedOut(html);
        } catch (Exception e) {
            return false;
        }
    }

    /** 用一串 Cookie 拉取资料页 HTML，交给 ForumParser 解析 uid/昵称/头像 */
    public static String fetchProfileHtml(String cookieHeader) {
        try {
            Session session = new Session();
            session.applyCookieHeader(cookieHeader);
            return session.get(BASE_URL + "home.php?mod=space&do=profile&mobile=2");
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 签到主流程 ====================

    private static SignResult doSign(Session session, SignResult result) {
        try {
            // ① 先取签到页：一次请求同时拿到 formhash、是否已签、排名，也顺带验证 Cookie
            String signPage = session.get(BASE_URL + "k_misign-sign.html");
            if (SignParser.isBlocked(signPage)) {
                result.blocked = true;
                result.message = "被站点防护拦截(403)，请求太频繁，稍后再试";
                return result;
            }
            if (TextUtils.isEmpty(signPage) || SignParser.looksLoggedOut(signPage)) {
                result.cookieInvalid = true;
                result.message = "登录态已失效";
                return result;
            }

            result.ranking = SignParser.extractRanking(signPage);

            // ② 已经签过就别再提交了，省一次请求也避免风控
            if (SignParser.isAlreadySigned(signPage)) {
                result.success = true;
                result.alreadySigned = true;
                result.status = "今日已签";
                result.reward = SignParser.extractReward(signPage);
                result.refreshedCookie = session.cookieHeader();
                return result;
            }

            String formhash = SignParser.extractFormhash(signPage);
            if (TextUtils.isEmpty(formhash)) {
                result.message = "无法获取签到凭证 formhash";
                return result;
            }

            // ③ 提交签到（text 格式返回最干净）
            String raw = session.get(BASE_URL
                    + "plugin.php?id=k_misign:sign&operation=qiandao&format=text&formhash=" + formhash);
            String status = SignParser.parseSignResponse(raw);

            // 伪静态按钮接口兜底：部分账号 plugin.php 会被重写规则拦掉
            if (TextUtils.isEmpty(status)) {
                raw = session.get(BASE_URL + "k_misign-sign.html?operation=qiandao&format=button"
                        + "&formhash=" + formhash + "&inajax=1&ajaxtarget=midaben_sign");
                status = SignParser.parseSignResponse(raw);
            }

            // ④ 回读签到页，以页面真实状态为准（接口文案各版本差异太大）
            String after = session.get(BASE_URL + "k_misign-sign.html");
            boolean signedNow = SignParser.isAlreadySigned(after);
            result.ranking = SignParser.extractRanking(after);

            String rewardFromApi = SignParser.extractRewardFromText(raw);
            result.reward = !"0".equals(rewardFromApi) ? rewardFromApi : SignParser.extractReward(after);

            if (SignParser.isFailureText(status)) {
                result.message = status;
                result.status = status;
                result.success = false;
            } else if (signedNow || SignParser.isSuccessText(status)) {
                result.success = true;
                result.status = SignParser.isAlreadySigned(status) ? "今日已签" : "签到成功";
            } else {
                result.success = false;
                result.status = TextUtils.isEmpty(status) ? "签到状态未知" : status;
                result.message = "签到后页面未显示已签，请稍后手动确认";
            }
            result.refreshedCookie = session.cookieHeader();
            return result;
        } catch (Exception e) {
            result.message = "网络错误：" + safeMessage(e);
            return result;
        }
    }

    // ==================== 会话封装 ====================

    /** 一次性会话：独立 OkHttpClient + 独立内存 CookieJar */
    private static final class Session {
        private final List<Cookie> jar = new ArrayList<>();
        private final OkHttpClient client;

        Session() {
            client = new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    // 论坛服务端 keep-alive/HTTP2 不稳，固定 1.1 + Connection: close 更可靠
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .addInterceptor(new RetryInterceptor(2))
                    .cookieJar(new CookieJar() {
                        @Override
                        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                            synchronized (jar) {
                                for (Cookie c : cookies) {
                                    for (int i = jar.size() - 1; i >= 0; i--) {
                                        if (jar.get(i).name().equals(c.name())) jar.remove(i);
                                    }
                                    // 服务端下发空值 = 删除该 cookie
                                    if (!TextUtils.isEmpty(c.value())) jar.add(c);
                                }
                            }
                        }

                        @Override
                        public List<Cookie> loadForRequest(HttpUrl url) {
                            synchronized (jar) {
                                return new ArrayList<>(jar);
                            }
                        }
                    })
                    .build();
        }

        void applyCookieHeader(String header) {
            if (TextUtils.isEmpty(header)) return;
            String host = HttpUrl.parse(BASE_URL).host();
            synchronized (jar) {
                for (String pair : header.split(";")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String name = pair.substring(0, eq).trim();
                    String value = pair.substring(eq + 1).trim();
                    if (name.isEmpty() || value.isEmpty()) continue;
                    for (int i = jar.size() - 1; i >= 0; i--) {
                        if (jar.get(i).name().equals(name)) jar.remove(i);
                    }
                    jar.add(new Cookie.Builder()
                            .name(name).value(value)
                            .domain(host).path("/")
                            .expiresAt(Long.MAX_VALUE)
                            .build());
                }
            }
        }

        String cookieHeader() {
            StringBuilder sb = new StringBuilder();
            synchronized (jar) {
                for (Cookie c : jar) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(c.name()).append('=').append(c.value());
                }
            }
            return sb.toString();
        }

        boolean hasAuthCookie() {
            synchronized (jar) {
                for (Cookie c : jar) {
                    if (c.name().endsWith("_auth") && !TextUtils.isEmpty(c.value())) return true;
                }
            }
            return false;
        }

        String get(String url) throws IOException {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA_MOBILE)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", BASE_URL + "index.php")
                    .header("Connection", "close")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                return response.body() != null ? response.body().string() : "";
            }
        }

        String post(String url, Map<String, String> params) throws IOException {
            FormBody.Builder form = new FormBody.Builder();
            if (params != null) {
                for (Map.Entry<String, String> e : params.entrySet()) {
                    form.add(e.getKey(), e.getValue());
                }
            }
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA_MOBILE)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", BASE_URL + "member.php?mod=logging&action=login")
                    .header("Connection", "close")
                    .post(form.build())
                    .build();
            try (Response response = client.newCall(request).execute()) {
                return response.body() != null ? response.body().string() : "";
            }
        }
    }

    /** 论坛偶发 unexpected end of stream，签到链路全是幂等请求，直接重试 */
    private static final class RetryInterceptor implements Interceptor {
        private final int maxAttempts;

        RetryInterceptor(int maxAttempts) {
            this.maxAttempts = Math.max(1, maxAttempts);
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            IOException last = null;
            for (int i = 0; i < maxAttempts; i++) {
                try {
                    return chain.proceed(chain.request());
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last != null ? last : new IOException("请求失败");
        }
    }

    // ==================== 小工具 ====================

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return TextUtils.isEmpty(m) ? e.getClass().getSimpleName() : m;
    }
}
