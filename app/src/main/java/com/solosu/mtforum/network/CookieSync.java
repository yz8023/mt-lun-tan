package com.solosu.mtforum.network;


import java.util.ArrayList;
import java.util.List;

/**
 * Cookie 的「核心 / 临时」分类 + WebView ↔ OkHttp 双向同步。
 *
 * <p>移植自 qcxs/mtbbs_app 的 {@code cookie_sync.dart}（docs/07 #73）。
 *
 * <p><b>为什么必须分类</b>：把「人机验证 / 防火墙 cookie」当成账号身份持久化，
 * 会让每次启动 / 切账号 / 导入导出都用一条旧值覆盖罐里刚刷新出来的新值，
 * 服务端只肯回挑战页 → 永远刷不新，形成「每次冷启动都要验证」的死锁。
 * 实测某账号里躺着一条 9.7 小时前的 {@code acw_tc}。
 *
 * <ul>
 *   <li><b>核心 cookie</b> —— 站点自己的会话 / 身份（Discuz 的 {@code {cookiepre}auth}、{@code sid}…）</li>
 *   <li><b>临时 cookie</b> —— 边缘下发的防护 cookie（人机验证、CDN）与站点配置类</li>
 * </ul>
 * 只有核心 cookie 会被持久化成「登录态」（{@code Account.cookies}）。临时 cookie 只留在
 * 运行期 cookieStore 里，由服务端响应按需刷新。
 */
public final class CookieSync {

    /**
     * 空串判定。刻意自己实现而不引 {@code android.text.TextUtils} ——
     * 这三个类是纯字符串逻辑，不碰任何 android.* 就能在 JVM 上跑单元测试
     * （本项目的 {@code SignParser} 从 build60 起就是同一条约定）。
     */
    private static boolean isBlank(CharSequence s) {
        return s == null || s.length() == 0;
    }

    private CookieSync() {
    }

    // ==================== 核心 / 临时 Cookie ====================

    /**
     * 从 cookie 名列表推断站点核心 cookie 前缀（Discuz 的 {@code cookiepre}）。
     *
     * <p>① Discuz 用 {@code {cookiepre}auth} 判定是否登录，所以形如 {@code cQWy_2132_auth}
     *    的名字去掉末尾 {@code auth} 就是前缀——有它时以此为准；
     * ② 没有 {@code auth} 时退化为统计：取出现 ≥2 次的「下划线前缀」里最多的那个；
     * ③ 都推不出则返回空串，此时 {@link #isCoreCookie} 不筛选（保持旧行为）。
     */
    public static String inferCookiePrefix(List<String> names) {
        List<String> list = new ArrayList<>();
        for (String n : names) {
            if (!isBlank(n)) list.add(n);
        }
        final String authSuffix = "auth";
        for (String n : list) {
            if (n.length() > authSuffix.length() && n.endsWith(authSuffix)) {
                return n.substring(0, n.length() - authSuffix.length());
            }
        }

        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String n : list) {
            int i = n.lastIndexOf('_');
            if (i <= 0) continue;
            String p = n.substring(0, i + 1);
            Integer c = counts.get(p);
            counts.put(p, c == null ? 1 : c + 1);
        }
        if (counts.isEmpty()) return "";
        String best = null;
        int bestCount = 0;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best != null && bestCount >= 2 ? best : "";
    }

    /** 该 cookie 名是否属于核心 cookie（prefix 为空 = 未识别出前缀，一律视为核心） */
    public static boolean isCoreCookie(String name, String prefix) {
        return isBlank(prefix) || name.startsWith(prefix);
    }

    /** 过滤 cookie 串，只留核心 cookie（用于登录态持久化与注入 WebView） */
    public static String coreCookiesOf(String cookieStr) {
        if (isBlank(cookieStr)) return cookieStr;
        String[] pairs = cookieStr.split(";");
        List<String> trimmed = new ArrayList<>();
        for (String p : pairs) {
            String t = p.trim();
            if (!t.isEmpty()) trimmed.add(t);
        }
        if (trimmed.isEmpty()) return cookieStr;

        List<String> names = new ArrayList<>();
        for (String p : trimmed) {
            int eq = p.indexOf('=');
            if (eq > 0) names.add(p.substring(0, eq).trim());
        }
        String prefix = inferCookiePrefix(names);
        if (isBlank(prefix)) return cookieStr;

        StringBuilder sb = new StringBuilder();
        for (String p : trimmed) {
            int eq = p.indexOf('=');
            String name = eq > 0 ? p.substring(0, eq).trim() : "";
            if (isCoreCookie(name, prefix)) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(p);
            }
        }
        return sb.length() == 0 ? cookieStr : sb.toString();
    }

    /**
     * 判断 cookie 串是否只有 {@code xxx_auth}、缺配套 saltkey 的裸 token。
     * Discuz 的 auth 必须和签发它的 saltkey 一起用，否则服务端重算 saltkey 会解密失败判游客。
     */
    public static boolean isBareAuthToken(String cookie) {
        if (isBlank(cookie)) return false;
        int valid = 0;
        boolean onlyAuth = true;
        for (String p : cookie.split(";")) {
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            valid++;
            if (!p.substring(0, eq).trim().endsWith("_auth")) onlyAuth = false;
        }
        return valid == 1 && onlyAuth;
    }
}
