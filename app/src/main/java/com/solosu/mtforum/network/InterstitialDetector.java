package com.solosu.mtforum.network;


import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「非论坛页」检测（人机验证 / 防火墙 / WAF）—— 与厂商无关。
 *
 * <p>移植自 qcxs/mtbbs_app 的 {@code looksLikeInterstitialPage}。
 * 刻意<b>不做厂商特征匹配</b>（不认 {@code acw_sc__v2}、不认具体验证码厂商），
 * 只判断「结构」：真实 Discuz 页面必然有 {@code <body>} 且必然带论坛骨架痕迹。
 * 因此任何新出现的人机验证页，只要它同样「不是一个能用的论坛页」，就会被命中。
 *
 * <p>实测样本（阿里云 ESA JS 挑战页）：
 * {@code <html><script>var arg1='…'}，4321 字节、无 {@code <body>}、无 DOCTYPE —— 命中。
 * 实测样本（真实帖子页）：含 {@code <body>}、{@code Discuz!}、{@code formhash} —— 不命中。
 *
 * <p>两道门槛排除误判：
 * <ul>
 *   <li>{@code content-type} 必须含 {@code text/html}（JSON / 图片接口天然排除）</li>
 *   <li>必须含 {@code <html}（Discuz 的 {@code inajax=1} 响应是 XML/CDATA 包装，天然排除）</li>
 * </ul>
 */
public final class InterstitialDetector {

    /**
     * 空串判定。刻意自己实现而不引 {@code android.text.TextUtils} ——
     * 这三个类是纯字符串逻辑，不碰任何 android.* 就能在 JVM 上跑单元测试
     * （本项目的 {@code SignParser} 从 build60 起就是同一条约定）。
     */
    private static boolean isBlank(CharSequence s) {
        return s == null || s.length() == 0;
    }

    /** 判定上限：真实 Discuz 页面远大于此（实测帖子页 ~160KB），验证/跳转桩通常在几 KB 量级 */
    public static final int MAX_BODY_LENGTH = 64 * 1024;

    private static final Pattern SCRIPT = Pattern.compile("<script[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE = Pattern.compile("<style[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WS = Pattern.compile("\\s+");

    /** 论坛页骨架痕迹 —— 任一命中即认为「这是论坛页」，不是拦截页 */
    private static final Pattern SKELETON_ID = Pattern.compile(
            "id\\s*=\\s*[\"']?(ct|hd|ft|postlist|thread_subject)\\b",
            Pattern.CASE_INSENSITIVE);

    private InterstitialDetector() {
    }

    /**
     * 判断响应是否「不是一个能用的论坛页」。
     *
     * @param body        响应体（已解码文本）
     * @param contentType 响应 Content-Type，可为 null
     */
    public static boolean looksLikeInterstitialPage(String body, String contentType) {
        if (isBlank(body)) return false;
        if (contentType == null || !contentType.toLowerCase().contains("text/html")) return false;
        if (body.length() > MAX_BODY_LENGTH) return false;

        final String lower = body.toLowerCase();
        if (!lower.contains("<html")) return false;

        // 有 <body>：可能是「带 body 的验证页」——仅当完全没有论坛痕迹且正文极少时才判定
        if (lower.contains("<body")) {
            if (hasDiscuzSkeleton(lower)) return false;
            return visibleTextLength(body) < 256;
        }

        // 无 <body> 的完整文档：脚本挑战 / 跳转桩（当前阿里云 WAF 即此类）
        return lower.contains("<script") || lower.contains("<meta");
    }

    /** 论坛页骨架痕迹。判定必须「宁可判成论坛页」——误判会平白弹出浏览器打扰用户。 */
    private static boolean hasDiscuzSkeleton(String lowerBody) {
        if (lowerBody.contains("discuz")
                || lowerBody.contains("formhash")
                || lowerBody.contains("comiis")) {
            return true;
        }
        return SKELETON_ID.matcher(lowerBody).find();
    }

    /** 粗略统计可见文本长度（剔除 script/style 与标签） */
    private static int visibleTextLength(String html) {
        String text = SCRIPT.matcher(html).replaceAll(" ");
        text = STYLE.matcher(text).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = WS.matcher(text).replaceAll(" ");
        return text.trim().length();
    }

    /**
     * 站点防护拦截的显式特征（403 正文 / 登录链路用）。
     *
     * <p>注意与 {@link #looksLikeInterstitialPage} 的分工：后者是「结构判定」，
     * 覆盖一切「不是论坛页」的响应；这里是「已知文案」，只用于给用户一句可读原因。
     */
    public static boolean isBlockedText(String html) {
        if (isBlank(html)) return false;
        return html.contains("you have been blocked")
                || html.contains("403 Forbidden")
                || html.contains("Access Denied")
                || html.contains("Attention Required")
                || html.contains("Sorry, you have been blocked");
    }

    /** 站点防护拦截的可读原因 */
    public static String blockedMessage(String html) {
        if (isBlank(html)) return "被站点防护拦截，请稍后再试";
        if (isBlockedText(html)) return "被站点防护拦截，请求太频繁，稍后再试";
        return "页面被拦截，可能需要人机验证";
    }

    /** 按 content-type 的 charset 解码；取不到按 UTF-8（MT 论坛全站 UTF-8） */
    public static String decode(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return "";
        Charset cs = Charset.forName("UTF-8");
        if (!isBlank(contentType)) {
            Matcher m = Pattern.compile("charset=([^;\\s]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(contentType);
            if (m.find()) {
                try {
                    String name = m.group(1).toLowerCase();
                    if (name.equals("gbk") || name.equals("gb2312")) cs = Charset.forName("GBK");
                    else cs = Charset.forName(name);
                } catch (Exception ignored) {
                    cs = Charset.forName("UTF-8");
                }
            }
        }
        return new String(bytes, cs);
    }

    /** content-type 的「类型/子类型」部分，用于快速排除非 HTML 响应 */
    public static String mimeOf(String contentType) {
        if (isBlank(contentType)) return "";
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase();
    }
}
