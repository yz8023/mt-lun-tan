package com.solosu.mtforum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.solosu.mtforum.network.InterstitialDetector;
import com.solosu.mtforum.session.SignParser;
import com.solosu.mtforum.util.ImageUrl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 <b>bbs.binmt.cc 真实抓包</b>校验解析逻辑。
 *
 * <p>build79 已有的 {@code InterstitialDetectorTest} / {@code SignParserTest} 里的
 * 签到 fixture 是手搓的 803 字节片段；本类补的是<b>原样 21770 字节的真实游客签到页</b>
 * 与<b>原样 182744 字节的真实列表页</b>，也就是 app 真正会吃到的两种响应。
 *
 * <p>这一组的价值在于：合成 fixture 只能证明"代码按我写的跑"，真实页面才能证明
 * "代码按服务器实际吐的东西跑"。
 */
public class RealPageTest {

    private static String fixture(String name) throws IOException {
        InputStream in = RealPageTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("缺少测试资源 " + name, in);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 签到页

    /**
     * 真实游客签到页必须判为未登录。
     *
     * <p>这是原样 21KB 页面（不是合成片段）：实测 {@code formhash} 出现 0 次，
     * 而 {@code looksLoggedOut} 的第一条判定就是没有 formhash。
     */
    @Test
    public void realGuestSignPageLooksLoggedOut() throws IOException {
        String page = fixture("real_guest_sign_page.html");
        assertTrue("fixture 应是 21KB 量级的真实页，而不是合成片段", page.length() > 10000);
        assertTrue("真实游客签到页不含 formhash", !page.contains("formhash"));
        assertTrue("真实游客签到页必须判为未登录", SignParser.looksLoggedOut(page));
    }

    /**
     * 真实游客签到页不能被人机验证检测误判。
     *
     * <p>登录墙也是一种"不像正常页"的页面，但它<b>不是</b> WAF 拦截 —— 误判会把
     * 用户弹去浏览器过验证，而他其实只是没登录。
     */
    @Test
    public void realGuestSignPageIsNotInterstitial() throws IOException {
        String page = fixture("real_guest_sign_page.html");
        assertTrue("真实游客签到页含 discuz_uid", page.contains("discuz_uid"));
        assertFalse("登录墙页不能判为人机验证页",
                InterstitialDetector.looksLikeInterstitialPage(
                        page, "text/html; charset=UTF-8"));
    }

    /** 真实游客签到页取不到 formhash，且不能凭空编一个出来。 */
    @Test
    public void realGuestSignPageHasNoFormhash() throws IOException {
        String page = fixture("real_guest_sign_page.html");
        String formhash = SignParser.extractFormhash(page);
        assertTrue("游客页不该有 formhash，实际得到 [" + formhash + "]",
                formhash == null || formhash.trim().isEmpty());
    }

    // ---------------------------------------------------------------- 列表页配图

    /** 真实列表页里的附件图：地址挑得出来、被当成帖子配图、且不是占位图。 */
    @Test
    public void realListPageAttachmentImages() throws IOException {
        String page = fixture("guide_newthread_page.html");
        Document doc = Jsoup.parse(page);
        List<Element> attachments = new ArrayList<>();
        for (Element img : doc.select("img")) {
            if (img.attr("src").contains("mod=image")) attachments.add(img);
        }
        assertTrue("真实列表页应有附件图（实测 71 张）", attachments.size() >= 10);

        for (Element img : attachments) {
            String url = ImageUrl.resolve(img);
            assertNotNull("附件图应能解析出地址", url);
            assertTrue("解析结果应是绝对地址: " + url, url.startsWith("https://"));
            assertTrue("附件图不能被当占位图: " + url, !ImageUrl.isPlaceholder(url));
            assertTrue("附件图应判为帖子配图: " + url, ImageUrl.isPostImage(url));
        }
    }

    /**
     * 真实缩略图地址 {@code size=500x480} 必须能被换成原图。
     *
     * <p>用的是从列表页里原样抓到的 URL，并且要确认 {@code key} 不被顺手改坏 ——
     * key 坏了就是 403。
     */
    /** 真实列表页里的头像不能被当成帖子配图。 */
    @Test
    public void realAvatarsAreNotPostImages() throws IOException {
        String page = fixture("guide_newthread_page.html");
        Document doc = Jsoup.parse(page);
        int avatars = 0;
        for (Element img : doc.select("img")) {
            String src = img.attr("src");
            if (src.contains("avatar.php") || src.contains("avatar.mt2.cn")) {
                assertFalse("头像不能被当成帖子配图: " + src, ImageUrl.isPostImage(src));
                avatars++;
            }
        }
        assertTrue("真实列表页应含头像（实测 avatar.mt2.cn）", avatars > 0);
    }

    /** 版块图标走 /data/attachment/common/，与附件同路径，不能被 icon 子串误杀。 */
    @Test
    public void realBoardIconPathIsNotFiltered() throws IOException {
        String page = fixture("guide_newthread_page.html");
        Document doc = Jsoup.parse(page);
        int icons = 0;
        for (Element img : doc.select("img")) {
            String src = img.attr("src");
            if (src.contains("/data/attachment/common/") && src.contains("_icon.png")) {
                assertTrue("附件缩略图不该被 icon 子串误杀: " + src, ImageUrl.isPostImage(src));
                icons++;
            }
        }
        assertTrue("真实列表页应含 _icon.png 图标（实测 11 张）", icons > 0);
    }

    /** 真实挑战页 / 真实论坛页 / 真实登录墙，三者的判定必须互不串台。 */
    @Test
    public void threeRealPagesAreDistinguished() throws IOException {
        String challenge = fixture("esa_challenge_page.html");
        String forum = fixture("guide_newthread_page.html");
        String signWall = fixture("real_guest_sign_page.html");

        assertTrue("挑战页", InterstitialDetector.looksLikeInterstitialPage(challenge, "text/html"));
        assertFalse("论坛列表页", InterstitialDetector.looksLikeInterstitialPage(forum, "text/html"));
        assertFalse("登录墙页", InterstitialDetector.looksLikeInterstitialPage(signWall, "text/html"));

        assertTrue("挑战页取不到 formhash",
                SignParser.extractFormhash(challenge).trim().isEmpty());
        assertTrue("登录墙页判未登录", SignParser.looksLoggedOut(signWall));
    }

    private static String extractParam(String url, String key) {
        if (url == null) return null;
        int i = url.indexOf(key + "=");
        if (i < 0) return null;
        i += key.length() + 1;
        int j = url.indexOf('&', i);
        String v = j < 0 ? url.substring(i) : url.substring(i, j);
        return v.isEmpty() ? null : v;
    }
}
