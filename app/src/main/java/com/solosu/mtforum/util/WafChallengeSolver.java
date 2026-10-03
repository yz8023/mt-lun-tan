package com.solosu.mtforum.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阿里云 ESA 人机验证（{@code acw_sc__v2}）的<b>本地解算器</b>。
 *
 * <h3>为什么要有这个类</h3>
 * 站点 CDN 在每个动态页前面挡了一层 JS 挑战：直接请求会拿到一个 4KB 左右的混淆
 * 脚本页而不是真实内容。旧方案是弹一个 WebView 到前台，让用户看着页面自己跑完
 * 那段 JS 再回来 —— 体验割裂，而且用户明确要求「验证不要跳到前台」。
 *
 * <p>这段挑战其实是<b>固定算法</b>，参数只有脚本里的一个 {@code arg1} 十六进制种子：
 * <ol>
 *   <li>读 {@code arg1}（40 个十六进制字符）</li>
 *   <li>按脚本里的置换表 {@code m} 重排这 40 个字符</li>
 *   <li>每个字节（2 个十六进制字符）与常量 {@link #P} 异或</li>
 * </ol>
 * 结果就是服务端要的 {@code acw_sc__v2} cookie。所以完全不需要 JS 引擎、
 * 不需要 WebView、不需要公网隧道，本地几十行算术就能算出来。
 *
 * <h3>实测验证</h3>
 * 对 bbs.binmt.cc 真实抓包端到端验证过：不带 cookie 请求
 * {@code forum.php?mod=guide&view=newthread&page=1&mobile=2} 得到 4321 字节挑战页，
 * 解算出 cookie 后重试同一 URL 得到 173867 字节真实列表页（{@code comiis_} 出现
 * 1057 次、{@code mod=image} 67 次）。算法与常量均从实时脚本解析，脚本形状变化
 * 时仍能工作。
 *
 * <h3>线程</h3>
 * 纯计算、无 IO，可在 OkHttp 拦截器线程里直接调用。
 */
public final class WafChallengeSolver {

    private WafChallengeSolver() {
    }

    /** 异或常量，来自 ESA 挑战脚本本身。 */
    private static final String P = "3000176000856006061501533003690027800375";

    /** {@code var arg1='40位十六进制'} */
    private static final Pattern ARG1_RE =
            Pattern.compile("var\\s+arg1\\s*=\\s*['\"]([0-9A-Fa-f]{40})['\"]");

    /** {@code var m=[0x0f,0x23,...]} —— 40 个条目的置换表。 */
    private static final Pattern M_RE = Pattern.compile("var\\s+m\\s*=\\s*\\[([^\\]]*)\\]");

    /**
     * 站点当前下发的置换表。脚本里解析不到真实表时用它兜底
     * （表变了算法才会失效，兜底值保证最常见的情况一定能算）。
     */
    private static final int[] DEFAULT_M = {
            0x0f, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x01, 0x26, 0x0a, 0x09,
            0x13, 0x1f, 0x28, 0x1b, 0x16, 0x17, 0x19, 0x0d, 0x06, 0x0b,
            0x27, 0x12, 0x14, 0x08, 0x0e, 0x15, 0x20, 0x1a, 0x02, 0x1e,
            0x07, 0x04, 0x11, 0x05, 0x03, 0x1c, 0x22, 0x25, 0x0c, 0x24,
    };

    /**
     * 尝试解算挑战页。
     *
     * @param html 响应体原文
     * @return {@code "acw_sc__v2=<值>"}；不是挑战页或解算不出来返回 {@code null}
     */
    public static String solve(String html) {
        if (html == null || html.isEmpty()) return null;
        if (!html.contains("var arg1=")) return null;

        Matcher arg1Matcher = ARG1_RE.matcher(html);
        if (!arg1Matcher.find()) return null;
        String arg1 = arg1Matcher.group(1);
        if (arg1 == null || arg1.length() != 40) return null;

        int[] m = extractM(html);
        if (m.length != 40) return null;

        String value = compute(arg1, m);
        if (value.length() != 40) return null;
        return "acw_sc__v2=" + value;
    }

    /** 是不是一个待解算的挑战页（保守判定，避免把正常页面当成挑战）。 */
    public static boolean looksLikeChallenge(String html) {
        if (html == null) return false;
        if (html.length() > 20000) return false;
        return html.contains("var arg1=") && html.contains("acw_sc__v2");
    }

    private static int[] extractM(String html) {
        Matcher matcher = M_RE.matcher(html);
        if (!matcher.find()) return DEFAULT_M;
        String body = matcher.group(1);
        if (body == null) return DEFAULT_M;
        String[] tokens = body.split(",");
        if (tokens.length != 40) return DEFAULT_M;
        int[] out = new int[40];
        for (int i = 0; i < 40; i++) {
            String t = tokens[i].trim();
            if (!t.startsWith("0x") && !t.startsWith("0X")) return DEFAULT_M;
            try {
                int v = Integer.parseInt(t.substring(2), 16);
                if (v < 0 || v > 255) return DEFAULT_M;
                out[i] = v;
            } catch (NumberFormatException e) {
                return DEFAULT_M;
            }
        }
        return out;
    }

    /**
     * q[z] = arg1[x]，其中 m[z] == x+1；然后把 q 每字节与 P 异或。
     */
    private static String compute(String arg1, int[] m) {
        char[] q = new char[40];
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                if (m[z] == x + 1) q[z] = arg1.charAt(x);
            }
        }
        String u = new String(q);
        StringBuilder sb = new StringBuilder(40);
        for (int x = 0; x < 40; x += 2) {
            int a = Integer.parseInt(u.substring(x, x + 2), 16);
            int b = Integer.parseInt(P.substring(x, x + 2), 16);
            String xor = Integer.toHexString(a ^ b);
            if (xor.length() == 1) sb.append('0');
            sb.append(xor);
        }
        return sb.toString();
    }

    /**
     * 把解算出的挑战 cookie 合并进原有 Cookie 头。
     *
     * <p><b>不能直接覆盖</b>：那会丢掉登录态 cookie，用户会被静默登出。
     * 只替换同名的那一项，其余原样保留。
     */
    public static String mergeCookie(String existing, String solved) {
        if (solved == null) return existing;
        if (existing == null) existing = "";
        existing = existing.trim();
        if (existing.isEmpty()) return solved;
        String name = solved.substring(0, Math.max(0, solved.indexOf('='))).trim();
        StringBuilder kept = new StringBuilder();
        for (String part : existing.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String pName = p.substring(0, Math.max(0, p.indexOf('='))).trim();
            if (pName.equals(name)) continue;
            if (kept.length() > 0) kept.append("; ");
            kept.append(p);
        }
        if (kept.length() > 0) kept.append("; ");
        kept.append(solved);
        return kept.toString();
    }
}
