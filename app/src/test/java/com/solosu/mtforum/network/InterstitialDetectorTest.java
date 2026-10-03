package com.solosu.mtforum.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 「非论坛页」检测的 JVM 单元测试（build79 新增）。
 *
 * <p>fixture 是 bbs.binmt.cc 的<b>真实抓包</b>：
 * <ul>
 *   <li>{@code esa_challenge_page.html} —— 不带任何 Cookie 直接请求列表页得到的
 *       阿里云 ESA JS 挑战页（4321B）</li>
 *   <li>{@code guide_newthread_page.html} —— 带上有效 {@code acw_sc__v2} 后
 *       同一 URL 返回的真实列表页（182KB）</li>
 * </ul>
 * 这两个文件正好是「拦截器要区分的两种响应」的真实样本。
 */
public class InterstitialDetectorTest {

    private static String fixture(String name) throws IOException {
        InputStream in = InterstitialDetectorTest.class.getClassLoader()
                .getResourceAsStream(name);
        assertTrue("缺少测试资源 " + name, in != null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 真实 ESA 挑战页必须被判定为「非论坛页」，否则整个过验证链路根本不会触发 */
    @Test
    public void realEsaChallengePageIsDetected() throws IOException {
        String challenge = fixture("esa_challenge_page.html");
        assertTrue("fixture 应含 arg1", challenge.contains("arg1"));
        assertTrue("fixture 应含 <html", challenge.contains("<html"));
        assertFalse("挑战页不该有 <body>", challenge.toLowerCase().contains("<body"));
        assertTrue("ESA 挑战页必须判为非论坛页",
                InterstitialDetector.looksLikeInterstitialPage(
                        challenge, "text/html; charset=UTF-8"));
    }

    /** 真实论坛页不能被误判，否则用户每次进 app 都被弹一次浏览器 */
    @Test
    public void realForumPageIsNotDetected() throws IOException {
        String forum = fixture("guide_newthread_page.html");
        // Comiis 移动模板的骨架痕迹（实测该页 comiis 出现 1056 次、postlist 12 次）
        assertTrue("fixture 应含 comiis", forum.contains("comiis"));
        assertTrue("fixture 应含 <body>", forum.toLowerCase().contains("<body"));
        assertFalse("真实论坛页不能被判为非论坛页",
                InterstitialDetector.looksLikeInterstitialPage(
                        forum, "text/html; charset=UTF-8"));
    }

    /** content-type 门槛：JSON / 图片 / inajax 的 XML 包装天然排除 */
    @Test
    public void nonHtmlContentTypesAreNeverInterstitial() throws IOException {
        String challenge = fixture("esa_challenge_page.html");
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(
                challenge, "application/json"));
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(
                challenge, "image/jpeg"));
        // Discuz 的 inajax=1 响应是 XML/CDATA 包装，content-type 不是 text/html
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(
                challenge, "text/xml"));
        // content-type 缺失时保守起见不判（宁可漏判也不要打扰用户）
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(challenge, null));
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(challenge, ""));
    }

    @Test
    public void blankBodyIsNeverInterstitial() {
        assertFalse(InterstitialDetector.looksLikeInterstitialPage("", "text/html"));
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(null, "text/html"));
    }

    /** 巨型页面（>64KB）直接放行：真实 Discuz 页面远大于此，验证页只有几 KB */
    @Test
    public void hugePagesAreNeverInterstitial() {
        StringBuilder sb = new StringBuilder("<html><head><title>t</title></head>");
        while (sb.length() < 200 * 1024) sb.append("<div>x</div>");
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(sb.toString(), "text/html"));
    }

    /** 带 <body> 的验证页：没有论坛痕迹 + 可见文本极少 → 判为非论坛页 */
    @Test
    public void bodiedChallengePageWithNoSkeletonIsDetected() {
        String html = "<html><head><title>验证</title></head><body>"
                + "<script>location.reload()</script></body></html>";
        assertTrue(InterstitialDetector.looksLikeInterstitialPage(html, "text/html"));
    }

    /** 带 <body> 且有一点点论坛痕迹 → 宁可判成论坛页 */
    @Test
    public void bodiedPageWithForumTraceIsNotDetected() {
        String html = "<html><head><title>t</title></head><body>"
                + "<div id=\"postlist\"><formhash>abc</formhash></div></body></html>";
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(html, "text/html"));
    }

    /** 带 <body> 但既无论坛痕迹、可见文本又很长的页面（如纯公告页）→ 不判，避免打扰 */
    @Test
    public void bodiedPageWithLongTextIsNotDetected() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) text.append("这是一段公告内容。");
        String html = "<html><body><div>" + text + "</div></body></html>";
        assertFalse(InterstitialDetector.looksLikeInterstitialPage(html, "text/html"));
    }

    // ==================== 站点防护文案 ====================

    @Test
    public void blockedTextDetection() {
        assertTrue(InterstitialDetector.isBlockedText("Sorry, you have been blocked"));
        assertTrue(InterstitialDetector.isBlockedText("403 Forbidden"));
        assertTrue(InterstitialDetector.isBlockedText("Access Denied"));
        assertFalse(InterstitialDetector.isBlockedText("<html><body>正常页面</body></html>"));
    }

    @Test
    public void blockedMessageIsReadable() {
        assertEquals("被站点防护拦截，请求太频繁，稍后再试",
                InterstitialDetector.blockedMessage("Sorry, you have been blocked"));
        assertEquals("页面被拦截，可能需要人机验证",
                InterstitialDetector.blockedMessage("<html><body>验证一下</body></html>"));
        assertEquals("被站点防护拦截，请稍后再试",
                InterstitialDetector.blockedMessage(""));
    }

    // ==================== 解码 / MIME ====================

    @Test
    public void decodeHonoursCharset() {
        assertEquals("中文", new String(
                InterstitialDetector.decode("中文".getBytes(StandardCharsets.UTF_8),
                        "text/html; charset=utf-8").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        // GBK 站点（Discuz 常见）要按 GBK 解
        byte[] gbk = "中文".getBytes(java.nio.charset.Charset.forName("GBK"));
        assertEquals("中文", InterstitialDetector.decode(gbk, "text/html; charset=gbk"));
        // charset 缺失 / 非法名 → 退回 UTF-8，不能抛异常
        assertEquals("abc", InterstitialDetector.decode(
                "abc".getBytes(StandardCharsets.UTF_8), "text/html"));
        assertEquals("abc", InterstitialDetector.decode(
                "abc".getBytes(StandardCharsets.UTF_8), "text/html; charset=not-a-charset"));
        assertEquals("", InterstitialDetector.decode(new byte[0], "text/html"));
        assertEquals("", InterstitialDetector.decode(null, "text/html"));
    }

    @Test
    public void mimeOfStripsParameters() {
        assertEquals("text/html", InterstitialDetector.mimeOf("text/html; charset=UTF-8"));
        assertEquals("text/html", InterstitialDetector.mimeOf("TEXT/HTML"));
        assertEquals("", InterstitialDetector.mimeOf(""));
        assertEquals("", InterstitialDetector.mimeOf(null));
    }
}
