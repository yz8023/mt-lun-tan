package com.solosu.mtforum.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import com.solosu.mtforum.session.SiteAccessManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.FormBody;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络请求管理器
 * 基于 OkHttp 封装，支持会话保持（Cookie 持久化）
 */
public class HttpClient {
    public static final String BASE_URL = "https://bbs.binmt.cc/";
    public static final String MOBILE_SUFFIX = "&mobile=2";
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    public static final String DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 请求级标记：本请求已由验证守门器处理过，避免递归触发 */
    public static final String HEADER_INTERSTITIAL_HANDLED = "X-MTBBS-Interstitial-Handled";

    /**
     * Discuz「浏览方式」Cookie 的名字后缀 —— {@code {cookiepre}_mobile}。
     * 前缀由站点配置决定（MT 论坛是 {@code cQWy_2132_}），所以按<b>后缀</b>匹配，不写死全名。
     */
    public static final String BROWSE_MODE_SUFFIX = "_mobile";

    /** 等人通过站点验证的最长等待（毫秒）——避免 OkHttp 线程被永久挂住 */

    private static volatile HttpClient instance;
    private OkHttpClient client;
    private Map<String, List<Cookie>> cookieStore;
    private static final String PREF_NAME = "sqapp_cookies";
    private static final String KEY_COOKIES = "cookies_json";
    private static boolean initialized = false;
    /** 保存 Application 上下文，用于在每次请求后自动持久化 Cookie */
    private Context appContext;

    /** 飞行中请求去重：相同 URL 的请求未完成时复用同一结果，避免重复发送 */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingGets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingPosts = new ConcurrentHashMap<>();

    private HttpClient() {
        cookieStore = new HashMap<>();
        client = new OkHttpClient.Builder()
                // build65: 全局请求节流 —— 所有请求先过 RequestThrottle。
                // 之前主界面角标 5 秒一轮、每轮 6 个并发，光挂首页就 72 次/分钟，
                // 稳稳撞上论坛的阿里云 ESA 风控（403 禁止访问）。
                                // ==================== 站点验证守门器 ====================
                // 放在节流器<b>之后</b>（OkHttp 应用拦截器后添加的先执行 → 写在后面的先跑）。
                // 这样验证判定发生在节流 backoff 之后，两个机制互不打架。
                //
                // 覆盖到<b>每一个</b> HTML 响应，而不是只靠 checkAuthFailure 那几个调用点：
                // 原来只有走 checkAuthFailure 的路径才会发现挑战页，
                // 列表页 / 帖子页的其它请求命中挑战时，就只是「解析不出东西」，没有任何提示。
                .addInterceptor(chain -> {
                    okhttp3.Request request = chain.request();
                    okhttp3.Response response = chain.proceed(request);
                    try {
                        return recoverInterstitialIfNeeded(chain, request, response);
                    } catch (Throwable t) {
                        return response;
                    }
                })
.addInterceptor(chain -> {
                    // build69: 前台车道不排队，后台车道走令牌桶
                    RequestThrottle.acquire();
                    REQ_COUNT.incrementAndGet();
                    okhttp3.Response resp = chain.proceed(chain.request());
                    if (resp.code() == 403) {
                        // 被拦了就清空令牌桶透支一轮，让后续请求按回填速率慢慢来。
                        // 注意不要把时间轴整体后推——上一版那么做会让之后每个请求都各等满超时，
                        // 表现就是"进帖加载特别慢"。
                        RequestThrottle.backoff();
                    }
                    return resp;
                })
                .connectTimeout(20, TimeUnit.SECONDS)
                // 图片在后台先被规范化，移动网络上传时仍可能超过普通页面请求时长。
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        String host = url.host();
                        List<Cookie> existing = cookieStore.get(host);
                        if (existing == null) {
                            existing = new ArrayList<>();
                            cookieStore.put(host, existing);
                        }
                        for (Cookie cookie : cookies) {
                            boolean found = false;
                            for (int i = 0; i < existing.size(); i++) {
                                if (existing.get(i).name().equals(cookie.name())) {
                                    existing.set(i, cookie);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                existing.add(cookie);
                            }
                        }
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        String host = url.host();
                        List<Cookie> cookies = cookieStore.get(host);
                        if (cookies == null || cookies.isEmpty()) {
                            return Collections.emptyList();
                        }
                        // build79: 剔除「浏览方式」Cookie（{cookiepre}_mobile）。
                        // 站点在「该页面无手机版 → 继续访问电脑版」时会下发它，
                        // 而它的优先级<b>高于</b> UA 和 URL 里的 mobile=2 ——
                        // 一旦罐里躺着这条，所有请求都会被强制返回 PC 模板，
                        // 而本项目的解析器是按 Comiis 移动模板写的，
                        // 于是正文 / 图片整套解析失效（实测：同 UA 同 URL，
                        // 有它 89KB PC 表格 vs 没它 173KB 移动卡片）。
                        // 只改「发什么」，不动罐里的数据。
                        boolean hasMobile = false;
                        for (Cookie c : cookies) {
                            if (c.name() != null && c.name().toLowerCase().endsWith(BROWSE_MODE_SUFFIX)) {
                                hasMobile = true;
                                break;
                            }
                        }
                        if (!hasMobile) return cookies;
                        List<Cookie> kept = new ArrayList<>(cookies.size());
                        for (Cookie c : cookies) {
                            if (c.name() != null && c.name().toLowerCase().endsWith(BROWSE_MODE_SUFFIX)) continue;
                            kept.add(c);
                        }
                        return kept;
                    }
                })
                .build();
    }

    /** 下载二进制资源，沿用论坛 Cookie；超过 maxBytes 时中止，避免离线保存撑爆内存。 */
    public byte[] getBytes(String url, int maxBytes) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            java.io.InputStream in = response.body().byteStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int total = 0, n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > maxBytes) throw new IOException("资源过大");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ==================== build61: 掉线回调 ====================

    /**
     * 登录态疑似失效时的回调。由 MyApplication 注册到 SessionGuard，
     * 这样 network 包不用反向依赖 session 包。
     */
    public interface AuthFailureListener {
        void onAuthFailure();
    }

    private static volatile AuthFailureListener authFailureListener;
    public interface ChallengeListener { void onChallenge(String url); }
    private static volatile ChallengeListener challengeListener;

    public static void setAuthFailureListener(AuthFailureListener listener) {
        authFailureListener = listener;
    }
    public static void setChallengeListener(ChallengeListener listener) { challengeListener = listener; }

    /**
     * 判定一次响应是否意味着掉线，是则通知守卫去静默重登。
     *
     * <p>两种情形：
     * <ul>
     *   <li>HTTP 403 —— 论坛挂的阿里云 ESA 拦截，常常连带把会话打掉</li>
     *   <li>本以为已登录，结果返回的是登录页</li>
     * </ul>
     * 只在"本地 cookie 还认为自己登录着"时才触发，避免游客状态下瞎重登。
     */
    // ==================== 站点验证守门器 ====================

    /**
     * 命中「非论坛页」（人机验证 / 防火墙拦截页）时尝试自动恢复。
     *
     * <p>与 {@link com.solosu.mtforum.session.SiteAccessManager} 的分工：
     * 后者是「检测 + 打开验证 WebView」的既有链路（本类已复用），
     * 这里负责把它接到<b>全部</b> HTML 响应上，并在验证通过后重放请求。
     *
     * <p>仅 GET 自动重放：写操作（发帖/评论）重放有重复提交风险，
     * 验证通过后交给用户手动重试。
     */
    private okhttp3.Response recoverInterstitialIfNeeded(
            okhttp3.Interceptor.Chain chain,
            okhttp3.Request request,
            okhttp3.Response response) throws java.io.IOException {
        // 已处理过 / 探测请求自己 → 直接放行，避免递归
        if ("1".equals(request.header(HEADER_INTERSTITIAL_HANDLED))) return response;
        if (response.code() != 200) return response;

        okhttp3.ResponseBody body = response.body();
        if (body == null) return response;
        String contentType = body.contentType() == null ? "" : String.valueOf(body.contentType());
        if (!InterstitialDetector.mimeOf(contentType).contains("text/html")) return response;

        // 先用 Content-Length 提前放行。真实 Discuz 页面远大于判定上限
        // （实测列表页 174KB、帖子页 ~50KB），而验证/挑战页只有几 KB。
        // 少了这一步，每个 HTML 响应都要整个读进内存再判断——
        // 大帖（几百楼）会明显拖慢首屏，这是「加载速度」不能忽略的一段。
        long declared = body.contentLength();
        if (declared > InterstitialDetector.MAX_BODY_LENGTH) return response;

        // ===== 判定要读正文，但<b>绝不能把调用方的响应体读干</b> =====
        //
        // build81 修一个致命 bug：这里原来用 body.bytes() 读正文，
        // 而 ResponseBody.bytes() 会把源读完<b>并关闭</b>。读完发现「不是挑战页」
        // 之后又直接 return response —— 调用方拿到的是一个已关闭的 body，
        // 再调 body.string() 直接抛 IllegalStateException("closed") 或返回空串。
        //
        // 后果：所有走这条拦截器的 HTML 响应对上层都是空的，
        // 登录链路（member.php?mod=logging&action=login 返回 HTML）
        // 表现为「cookie 获取登录失效」，列表页/帖子页表现为「服务器返回空页面」。
        //
        // 正确做法是用 okio 的 peek()：<b>预读但不消费</b>。
        // 数据被读进底层 buffer，但源的位置没动，调用方照样能读到完整响应。
        byte[] bytes;
        try {
            long toRead = declared >= 0
                    ? Math.min(declared, InterstitialDetector.MAX_BODY_LENGTH)
                    : InterstitialDetector.MAX_BODY_LENGTH;
            okio.BufferedSource peeked = body.source().peek();
            okio.Buffer sniff = new okio.Buffer();
            while (toRead > 0) {
                long n = peeked.read(sniff, toRead);
                if (n < 0) break; // 实际比 Content-Length 短
                toRead -= n;
            }
            bytes = sniff.readByteArray();
        } catch (Exception e) {
            // 预读失败也不要影响正常响应，直接放行
            return response;
        }
        if (bytes.length == 0) return response;

        // 没带 Content-Length 时，如果预读已经把判定上限读满，说明页面很可能很大，
        // 不再继续判定，直接放行（避免把大页硬读进内存）
        if (declared < 0 && bytes.length >= InterstitialDetector.MAX_BODY_LENGTH) return response;

        String text = InterstitialDetector.decode(bytes, contentType);
        if (!SiteAccessManager.isChallengePage(text)) {
            return response;
        }

        android.util.Log.w("HttpClient", request.url() + " 命中非论坛页（" + bytes.length + "B），尝试自动恢复");

        // ===== 第一优先：本地解算，完全不弹界面 =====
        // 用户明确要求「验证不要跳到前台」。ESA 的 acw_sc__v2 挑战是固定算法，
        // 见 util/WafChallengeSolver：读 arg1 → 按置换表重排 → 与常量异或。
        // 已对 bbs.binmt.cc 端到端实测（4321B 挑战页 → 173867B 真实列表页）。
        // 只有解算不出来时才退回 WebView 方案。
        if ("GET".equalsIgnoreCase(request.method())) {
            String solved = com.solosu.mtforum.util.WafChallengeSolver.solve(text);
            if (solved != null) {
                // ===== 关键修正（build86）：写进 cookie 罐，不要塞 Cookie 头 =====
                // OkHttp 4.x 的 BridgeInterceptor 是这么干的：
                //     val cookies = cookieJar.loadForRequest(userRequest.url)
                //     if (cookies.isNotEmpty()) requestBuilder.header("Cookie", cookieHeader(cookies))
                // —— cookie 罐<b>非空时无条件覆盖</b>调用方自设的 Cookie 头。
                // 而本类的请求都不带 Cookie 头（cookie 统一由罐子管），
                // 所以旧的 mergeCookie(request.header("Cookie"), solved) 拿到的是 null，
                // 合并结果只剩 acw_sc__v2 一项；重放时它又被罐子里的会话 cookie 覆盖掉
                // → 挑战照旧 → isStillChallenge 为 true → 落到下面 onChallengeDetected
                // → 弹 WebView。这正是「说了全自动无感、实测还在跳验证页」的根因。
                //
                // 写进罐子有两个好处：① 重放请求由罐子自然带上，不会被覆盖；
                // ② 后续所有请求都带着它，不用每个请求重新解算一遍。
                injectChallengeCookie(solved);

                // 重试两轮：挑战页有偶发的服务端抖动，别一失败就放弃。
                for (int attempt = 1; attempt <= 2; attempt++) {
                    try {
                        okhttp3.Request replay = request.newBuilder()
                                .header(HEADER_INTERSTITIAL_HANDLED, "1")
                                .build();
                        okhttp3.Response replayed = chain.proceed(replay);
                        try {
                            response.close();
                        } catch (Throwable ignored) {
                        }
                        if (!isStillChallenge(replayed)) {
                            android.util.Log.i("HttpClient", request.url()
                                    + " 本地解算通过验证，已自动重放（无界面，第 " + attempt + " 次）");
                            return replayed;
                        }
                        try {
                            replayed.close();
                        } catch (Throwable ignored) {
                        }
                        // 仍是挑战页：解算值可能过期，重新算一个再试
                        solved = com.solosu.mtforum.util.WafChallengeSolver.solve(text);
                        if (solved != null) injectChallengeCookie(solved);
                    } catch (Throwable t) {
                        android.util.Log.w("HttpClient", "本地解算重放失败: " + t);
                    }
                }
                // 解算值拿到了、也重试过了还是过不去 —— 不弹界面。
                // 弹了就是用户最反感的那种「打断」；这里只记日志，按原样返回。
                android.util.Log.w("HttpClient", request.url()
                        + " 本地解算已产出 cookie 但重放仍未通过，不弹验证界面");
                return response;
            }
            // 连解算值都算不出来（站点改了挑战算法/页面结构）—— 这才需要人工介入，
            // 属于真正的兜底，正常情况走不到。
            android.util.Log.w("HttpClient", request.url()
                    + " 不是本地可解算的挑战形态（缺 var arg1=/acw_sc__v2），不弹验证界面");
        }

        // ===== build87: 不再自动弹验证 WebView =====
        // 用户明确要求「全自动无感，不能影响我的体验」。参照项目 yz8023/mtluntan 的
        // WafInterceptor 判据是<b>窄</b>的：只有 body 同时含 var arg1= 与 acw_sc__v2
        // 才当挑战处理，其余一律原样放行，<b>从不弹界面</b>。
        //
        // 本类原来用的是 mtbbs_app 那套宽泛的<b>结构</b>判定（有 <html>、无论坛骨架、
        // 可见正文少于 256 字符即算拦截页）。它的命中面远大于本地解算器能处理的形态：
        // WAF 拦截页、登录页、错误桩都会命中，于是 solve() 返回 null 就弹 WebView ——
        // 这就是「说了无感、实测还在跳浏览器」的原因。
        //
        // 更糟的是兜底路径还会 awaitClearance(150_000)，把 OkHttp 线程挂住最多 150 秒
        // 等用户手工验证，界面直接卡死。
        //
        // 现在：本地解算是唯一自动路径；解算不了只记日志、原样放行，界面一概不弹。
        // 确需人工验证时，设置页保留「手动打开站点验证」入口（SiteAccessManager.openManually）。
        return response;
    }

    /**
     * 重放后的响应是否仍是挑战页。
     *
     * <p>用 peek 只读前几 KB 判定，<b>不消费 body</b>——调用方还要正常读这个响应。
     * 判定失败（读不了）时返回 false，即「当它已经过了」，不要因为探测失败就把
     * 一个好响应退回 WebView 方案。
     */
    private boolean isStillChallenge(okhttp3.Response r) {
        try {
            okhttp3.ResponseBody b = r.body();
            if (b == null) return false;
            long want = b.contentLength() > 0 ? Math.min(b.contentLength(), 8192) : 8192;
            okio.BufferedSource peeked = b.source().peek();
            okio.Buffer sniff = new okio.Buffer();
            peeked.read(sniff, want);
            String head = sniff.readUtf8();
            return head.contains("var arg1=") && head.contains("acw_sc__v2");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把本地解算出的站点防护 cookie（{@code acw_sc__v2}）写进自己的 cookie 罐。
     *
     * <p><b>为什么不能用 {@code request.header("Cookie", …)} 传</b>：OkHttp 4.x 的
     * {@code BridgeInterceptor} 在 cookie 罐非空时，会用罐子里的值<b>无条件覆盖</b>
     * 调用方自设的 Cookie 头。塞进去也会被抹掉。写进罐子才是唯一可靠的路，
     * 而且顺带让后续所有请求都带着它，不用每个请求重新解算一遍。
     *
     * <p>只写站点防护类 cookie，不碰登录态 cookie。
     */
    private synchronized void injectChallengeCookie(String solved) {
        if (solved == null || solved.isEmpty()) return;
        try {
            String host = okhttp3.HttpUrl.parse(BASE_URL).host();
            if (host == null) return;
            List<okhttp3.Cookie> existing = cookieStore.get(host);
            if (existing == null) {
                existing = new ArrayList<>();
                cookieStore.put(host, existing);
            }
            for (String pair : solved.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (name.isEmpty() || value.isEmpty()) continue;
                // 只接受站点防护 cookie，避免误伤登录态
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                boolean protective = lower.startsWith("acw_") || lower.contains("clearance")
                        || lower.startsWith("aliyungf_") || lower.startsWith("__jsl");
                if (!protective) continue;
                okhttp3.Cookie.Builder b = new okhttp3.Cookie.Builder()
                        .name(name).value(value).domain(host).path("/")
                        .expiresAt(Long.MAX_VALUE);
                boolean replaced = false;
                for (int i = 0; i < existing.size(); i++) {
                    if (existing.get(i).name().equals(name)) {
                        existing.set(i, b.build());
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) existing.add(b.build());
            }
            // 落盘，下次启动不用再解算
            if (appContext != null) commitCookieStore(appContext);
        } catch (Throwable ignored) {
            // 写罐失败不影响主流程
        }
    }

    private void checkAuthFailure(int httpCode, String body, String url) {
        // build86: 这里<b>绝不</b>再触发验证 WebView。
        //
        // 挑战页的处置权已经整体收敛到 recoverInterstitialIfNeeded 那一个拦截器：
        // 它挂在所有请求上、能读到完整响应体、能本地解算、能重放、能把 cookie 写进罐子。
        // 以前这里也 onChallenge(url)，造成两个后果：
        //   ① 拦截器解算失败时弹一次，调用方拿到没解算掉的挑战页时这里再判一次又弹一次；
        //   ② 这里是同步调用链，用户看到的就是「还是跳到验证页」。
        // 用户明确要求全自动无感，所以这里只记日志，界面一概不弹。
        if (com.solosu.mtforum.session.SiteAccessManager.isChallengePage(body)) {
            android.util.Log.w("HttpClient",
                    url + " 命中挑战页，但拦截器已尝试自动解算；不再弹验证界面");
            return;
        }
        AuthFailureListener listener = authFailureListener;
        if (listener == null) return;
        boolean loginPage = body != null && (body.contains("您需要先登录")
                || body.contains("请先登录后继续")
                || (body.contains("loginform") && body.contains("action=login"))
                || body.contains("member.php?mod=logging&action=login"));
        // build80: 原来把 HTTP 401/403 也当掉线信号。但 403 现在绝大多数是
        // 阿里云 ESA 的 WAF 拦截（含图片 CDN 对空 UA 的 403），跟登录态无关。
        // 拿它去触发静默重登会形成「疯狂重登还是 403」的死循环，
        // 而且重登拿到的新 Cookie 根本解不开 JS 挑战，只会更糟。
        // 掉线只认一件事：本以为已登录，结果返回的是登录页。
        // 挑战页由上面的 SiteAccessManager 链路单独处理。
        boolean suspicious = isLoggedIn() && loginPage;
        if (!suspicious) return;
        try { listener.onAuthFailure(); } catch (Throwable ignored) {}
    }

    public static HttpClient getInstance() {
        if (instance == null) {
            synchronized (HttpClient.class) {
                if (instance == null) {
                    instance = new HttpClient();
                }
            }
        }
        return instance;
    }

    /**
     * GET 请求
     */
    public String get(String url) throws Exception {
        // 去重：相同 URL 正在请求则等待已有结果，避免重复发送
        CompletableFuture<String> existing = pendingGets.get(url);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { /* 上次请求失败，继续发起新请求 */ }
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> prev = pendingGets.putIfAbsent(url, future);
        if (prev != null) {
            try { return prev.get(); }
            catch (Exception e) { /* 上同 */ }
        }
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (appContext != null) {
                    commitCookieStore(appContext);
                }
                checkAuthFailure(response.code(), body, url);
                future.complete(body);
                return body;
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingGets.remove(url, future);
        }
    }

    /**
     * GET 请求返回 byte[]（用于下载图片等二进制内容）
     */
    /**
     * GET 请求(带 Referer,用于需要来源校验的 Comiis 插件/表单接口)。
     */
    public String getWithReferer(String url, String referer) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", referer)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (appContext != null) {
                commitCookieStore(appContext);
            }
            return body;
        }
    }

    public byte[] getBytes(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.body() != null ? response.body().bytes() : new byte[0];
        }
    }

    /**
     * GET 请求（强制使用桌面端 UA，用于获取桌面版页面中的 hash 等仅桌面版存在的字段）
     */
    public String getDesktop(String url) throws Exception {
        // 去重：相同 URL 正在请求则等待已有结果，避免重复发送
        CompletableFuture<String> existing = pendingGets.get(url);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { /* 上次请求失败，继续发起新请求 */ }
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> prev = pendingGets.putIfAbsent(url, future);
        if (prev != null) {
            try { return prev.get(); }
            catch (Exception e) { /* 上同 */ }
        }
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (appContext != null) {
                    commitCookieStore(appContext);
                }
                checkAuthFailure(response.code(), body, url);
                future.complete(body);
                return body;
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingGets.remove(url, future);
        }
    }

    /**
     * POST 请求（表单提交）
     */
    public String post(String url, Map<String, String> params) throws Exception {
        // POST 去重：URL + 参数的摘要作为 key
        String key = url + (params != null ? params.toString() : "");
        CompletableFuture<String> existing = pendingPosts.get(key);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { /* 上次请求失败，继续发起新请求 */ }
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> prev = pendingPosts.putIfAbsent(key, future);
        if (prev != null) {
            try { return prev.get(); }
            catch (Exception e) { /* 上同 */ }
        }
        try {
            FormBody.Builder formBuilder = new FormBody.Builder();
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    formBuilder.add(entry.getKey(), entry.getValue());
                }
            }
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(formBuilder.build())
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (appContext != null) {
                    commitCookieStore(appContext);
                }
                checkAuthFailure(response.code(), body, url);
                future.complete(body);
                return body;
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingPosts.remove(key, future);
        }
    }

    /**
     * POST 请求（表单提交），带 Referer。
     * Discuz 的回复/发帖接口会校验来源页，缺失 Referer 时可能被判为非法操作。
     */
    public String postWithReferer(String url, Map<String, String> params, String referer)
            throws Exception {
        String key = url + "@ref@" + (params != null ? params.toString() : "");
        CompletableFuture<String> existing = pendingPosts.get(key);
        if (existing != null) {
            try { return existing.get(); }
            catch (Exception e) { /* 上次请求失败，继续发起新请求 */ }
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> prev = pendingPosts.putIfAbsent(key, future);
        if (prev != null) {
            try { return prev.get(); }
            catch (Exception e) { /* 上同 */ }
        }
        try {
            FormBody.Builder formBuilder = new FormBody.Builder();
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    formBuilder.add(entry.getKey(), entry.getValue());
                }
            }
            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Content-Type", "application/x-www-form-urlencoded");
            if (!android.text.TextUtils.isEmpty(referer)) rb.header("Referer", referer);
            Request request = rb.post(formBuilder.build()).build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (appContext != null) {
                    commitCookieStore(appContext);
                }
                checkAuthFailure(response.code(), body, url);
                future.complete(body);
                return body;
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingPosts.remove(key, future);
        }
    }

    /**
     * 上传文件（multipart/form-data）
     * 用于 Discuz! 附件上传接口 forum.php?mod=ajax&action=upload&mobile=2
     */
    public String uploadFile(String url, File file, String fieldName, Map<String, String> extraFields) throws IOException {
        return uploadFileWithUserAgent(url, file, fieldName, extraFields, DESKTOP_USER_AGENT);
    }

    /**
     * multipart 上传并显式指定 UA/Cookie 头。Discuz Comiis 的 swfupload
     * 令牌由桌面版页面生成，上传请求也使用桌面 UA 更稳定。
     */
    public String uploadFileWithUserAgent(String url, File file, String fieldName,
                                          Map<String, String> extraFields, String userAgent) throws IOException {
        return uploadFileWithUserAgent(url, file, fieldName, extraFields, userAgent,
                guessContentType(file));
    }

    /**
     * multipart 上传，允许调用方保留系统相册提供的真实 MIME 类型。
     * 部分 Discuz/Comiis 站点会同时校验文件名扩展名和 Content-Type；把 PNG、WebP、HEIC
     * 一律伪装为 jpg/octet-stream 会导致网页端能传、原生端被拒绝。
     */
    public String uploadFileWithUserAgent(String url, File file, String fieldName,
                                          Map<String, String> extraFields, String userAgent,
                                          String contentType) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0) {
            throw new IOException("上传文件不存在或为空");
        }

        // 登录可能发生在 WebView。仅当原生会话缺失时才拉取，避免空/旧 WebView Cookie
        // 覆盖仍然有效的 OkHttp 登录态；随后仍显式把当前 Cookie 写入上传请求。
        if (!isLoggedIn()) syncFromCookieManager();
        String cookieHeader = getCookieHeader();

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(fieldName, file.getName(),
                        okhttp3.RequestBody.create(MediaType.parse(
                                contentType == null || contentType.trim().isEmpty()
                                        ? "application/octet-stream" : contentType), file));

        if (extraFields != null) {
            for (Map.Entry<String, String> entry : extraFields.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.addFormDataPart(entry.getKey(), entry.getValue());
                }
            }
        }

        okhttp3.RequestBody requestBody = builder.build();
        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent == null || userAgent.isEmpty()
                        ? DESKTOP_USER_AGENT : userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", BASE_URL)
                .post(requestBody);
        // 手动设置 Cookie 时 OkHttp 不会重复由 CookieJar 注入同名 Header。
        if (cookieHeader != null && !cookieHeader.isEmpty()) {
            requestBuilder.header("Cookie", cookieHeader);
        }

        try (okhttp3.Response response = client.newCall(requestBuilder.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (appContext != null) {
                commitCookieStore(appContext);
            }
            if (!response.isSuccessful()) {
                throw new IOException("上传请求失败（HTTP " + response.code() + "）");
            }
            return body;
        }
    }

    private static String guessContentType(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".heic") || name.endsWith(".heif")) return "image/heic";
        return "application/octet-stream";
    }

    /**
     * ★ 使用 HttpClient 的 OkHttpClient（含 Cookie）执行自定义 Request
     * 用于头像上传等需要保持会话的外部请求
     * 返回 okhttp3.Response（调用方记得关闭 response.body()）
     */
    public okhttp3.Response executeDirect(okhttp3.Request request) throws Exception {
        okhttp3.Response response = client.newCall(request).execute();
        // 请求完成后自动持久化 Cookie
        if (appContext != null) {
            commitCookieStore(appContext);
        }
        return response;
    }

    /**
     * 获取当前 cookie 值
     */
    public String getCookieValue(String host, String name) {
        List<Cookie> cookies = cookieStore.get(host);
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (c.name().equals(name)) {
                    return c.value();
                }
            }
        }
        return null;
    }

/**
     * 获取指定 URL 域名的所有 Cookie 字符串（用于同步到 WebView）
     * 格式: "name1=value1; name2=value2"
     */
    public String getCookieString() {
        String host = HttpUrl.parse(BASE_URL).host();
        List<Cookie> cookies = cookieStore.get(host);
        if (cookies == null || cookies.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Cookie c : cookies) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name()).append("=").append(c.value());
        }
        return sb.toString();
    }

    /**
     * 判断是否已登录（检查是否有 Discuz 的 auth cookie）
     * 遍历所有 cookie，查找名称以 _auth 结尾的 cookie
     * 兼容不同论坛实例的不同 cookiepre 前缀
     */
    public boolean isLoggedIn() {
        String host = HttpUrl.parse(BASE_URL).host();
        List<Cookie> cookies = cookieStore.get(host);
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (c.name().endsWith("_auth") && !c.value().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
    /** 返回当前论坛域名的 Cookie Header，供需要显式携带会话的 multipart 请求使用。 */
    public synchronized String getCookieHeader() {
        return getCookieString();
    }

    /**
     * 从 WebView CookieManager 同步 Cookie。不同 Android/Chromium 版本对
     * BASE_URL、host URL 和末尾斜杠的处理不同，因此依次读取多个等价地址。
     */
    public synchronized void syncFromCookieManager() {
        try {
            CookieManager manager = CookieManager.getInstance();
            String[] urls = new String[] {
                    BASE_URL,
                    "https://bbs.binmt.cc",
                    "https://bbs.binmt.cc/",
                    "http://bbs.binmt.cc/"
            };
            StringBuilder merged = new StringBuilder();
            for (String url : urls) {
                String value = manager.getCookie(url);
                if (value == null || value.trim().isEmpty()) continue;
                if (merged.length() > 0) merged.append("; ");
                merged.append(value);
            }
            if (merged.length() == 0) return;

            String host = HttpUrl.parse(BASE_URL).host();
            List<Cookie> existing = cookieStore.get(host);
            if (existing == null) {
                existing = new ArrayList<>();
                cookieStore.put(host, existing);
            }
            String[] pairs = merged.toString().split(";\\s*");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (name.isEmpty() || value.isEmpty()) continue;
                Cookie old = null;
                for (Cookie c : existing) {
                    if (c.name().equals(name)) { old = c; break; }
                }
                Cookie.Builder b = new Cookie.Builder().name(name).value(value)
                        .domain(host).path("/").expiresAt(Long.MAX_VALUE);
                if (old != null && old.secure()) b.secure();
                boolean replaced = false;
                for (int i = 0; i < existing.size(); i++) {
                    if (existing.get(i).name().equals(name)) {
                        existing.set(i, b.build()); replaced = true; break;
                    }
                }
                if (!replaced) existing.add(b.build());
            }
            if (appContext != null) commitCookieStore(appContext);
        } catch (Exception ignored) {
        }
    }


    /**
     * 将 HttpClient CookieJar 中的 Cookie 同步到 Android WebView CookieManager。
     * 用于登录由原生 OkHttp 完成、随后需要在 WebView 或混合页面中继续使用登录态的场景。
     */
    public synchronized void syncToCookieManager() {
        try {
            CookieManager webViewCookieMgr = CookieManager.getInstance();
            String cookies = getCookieString();
            if (cookies == null || cookies.isEmpty()) return;
            String[] pairs = cookies.split(";\\s*");
            for (String pair : pairs) {
                if (pair == null || pair.trim().isEmpty()) continue;
                webViewCookieMgr.setCookie(BASE_URL, pair.trim());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                webViewCookieMgr.flush();
            } else {
                CookieSyncManager.getInstance().sync();
            }
        } catch (Exception ignored) {
            // Cookie 同步失败不应阻断原生请求流程
        }
    }
/**
     * 初始化 — 从磁盘恢复持久化的 Cookie
     * 在 Application.onCreate() 或首次使用前调用一次
     * 初始化后自动从 WebView CookieManager 拉取一次 Cookie，确保双向同步
     */
    public synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        this.appContext = context;
        restoreCookieStore(context);
        // ★ 初始化后立即从 WebView CookieManager 拉取 Cookie，确保双向同步
        syncFromCookieManager();
    }

    /**
     * 将当前内存中的 Cookie 持久化到 SharedPreferences（JSON 序列化）
     * 在登录成功后调用
     */
    public synchronized void commitCookieStore(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            JSONArray cookiesArray = new JSONArray();

            for (Map.Entry<String, List<Cookie>> entry : cookieStore.entrySet()) {
                String host = entry.getKey();
                for (Cookie cookie : entry.getValue()) {
                    JSONObject obj = new JSONObject();
                    obj.put("host", host);
                    obj.put("name", cookie.name());
                    obj.put("value", cookie.value());
                    obj.put("domain", cookie.domain());
                    obj.put("path", cookie.path());
                    obj.put("expiresAt", cookie.expiresAt());
                    obj.put("secure", cookie.secure());
                    obj.put("httpOnly", cookie.httpOnly());
                    obj.put("persistent", cookie.persistent());
                    if (cookie.persistent()) {
                        // 只持久化 persistent cookie（登录态 cookie 通常是 persistent 的）
                        cookiesArray.put(obj);
                    } else {
                        // session cookie 也存一下，避免某些情况丢失
                        cookiesArray.put(obj);
                    }
                }
            }

            prefs.edit().putString(KEY_COOKIES, cookiesArray.toString()).apply();
        } catch (Exception ignored) {
            // 序列化失败不抛出
        }
    }

    /**
     * 从 SharedPreferences 恢复 Cookie 到内存
     * 在应用启动时调用
     */
    public synchronized void restoreCookieStore(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_COOKIES, null);
            if (json == null || json.isEmpty()) return;

            JSONArray cookiesArray = new JSONArray(json);
            cookieStore.clear();

            for (int i = 0; i < cookiesArray.length(); i++) {
                JSONObject obj = cookiesArray.getJSONObject(i);
                String host = obj.optString("host", HttpUrl.parse(BASE_URL).host());

                Cookie.Builder builder = new Cookie.Builder()
                        .name(obj.getString("name"))
                        .value(obj.getString("value"))
                        .domain(obj.optString("domain", HttpUrl.parse(BASE_URL).host()))
                        .path(obj.optString("path", "/"))
                        .expiresAt(obj.optLong("expiresAt", Long.MAX_VALUE));

                if (obj.optBoolean("secure")) {
                    builder.secure();
                }
                if (obj.optBoolean("httpOnly")) {
                    // Cookie.Builder 没有 httpOnly() 方法，跳过
                }

                Cookie cookie = builder.build();

                List<Cookie> cookies = cookieStore.get(host);
                if (cookies == null) {
                    cookies = new ArrayList<>();
                    cookieStore.put(host, cookies);
                }
                cookies.add(cookie);
            }
        } catch (Exception ignored) {
            // 反序列化失败时使用空 cookieStore（ignored）
        }
    }

    /**
     * 清除所有 Cookie（内存 + 磁盘）
     */
    /**
     * 从原始 Cookie 字符串设置 Cookie(用于 Cookie 登录)。
     * 格式: "key1=value1; key2=value2; ..."
     * 设置后可通过 isLoggedIn() 检查 _auth cookie 是否有效。
     */
    public synchronized void applyCookiesFromString(String cookieString) {
        if (cookieString == null || cookieString.trim().isEmpty()) return;
        String host = HttpUrl.parse(BASE_URL).host();
        List<Cookie> existing = cookieStore.get(host);
        if (existing == null) {
            existing = new ArrayList<>();
            cookieStore.put(host, existing);
        }
        String[] pairs = cookieString.split(";\\s*");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            Cookie.Builder b = new Cookie.Builder().name(name).value(value)
                    .domain(host).path("/").expiresAt(Long.MAX_VALUE);
            boolean replaced = false;
            for (int i = 0; i < existing.size(); i++) {
                if (existing.get(i).name().equals(name)) {
                    existing.set(i, b.build());
                    replaced = true;
                    break;
                }
            }
            if (!replaced) existing.add(b.build());
        }
    }

    /**
     * 清空「飞行中请求去重」缓存（build65）。
     *
     * <p>pendingGets/pendingPosts 是按 URL 做的结果复用。切换账号后如果不清，
     * 新账号请求同一个 URL 会拿到<b>上一个账号的页面</b>，
     * 表现就是「切号后还是旧账号的数据」或「莫名其妙 403」。
     */
    /** build71: 累计请求计数，用来算「打开一个页面到底发了几个请求」 */
    private static final java.util.concurrent.atomic.AtomicInteger REQ_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static int totalRequests() {
        return REQ_COUNT.get();
    }

    public void clearPendingCache() {
        pendingGets.clear();
        pendingPosts.clear();
    }

    public void clearCookies() {
        cookieStore.clear();
    }
    /**
     * 清除所有 Cookie（内存 + 磁盘 + WebView）
     * 在登出时调用
     */
    public void clearCookies(Context context) {
        cookieStore.clear();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_COOKIES).apply();
        } catch (Exception ignored) {
        }
        // build59: WebView CookieManager 也要清, 不然下次启动 syncFromCookieManager 又拉回来
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Exception ignored) {
        }
    }
}