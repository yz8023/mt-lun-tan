package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * {@link ImageUrl} 的 JVM 单元测试（build79 新增）。
 *
 * <p>这些规则就是「图片显示异常 / 直接不显示」的第一现场，必须能离线回归。
 * 判据全部来自 bbs.binmt.cc 实测抓包（mobile=2 模板）：
 * <ul>
 *   <li>正文配图：{@code /forum.php?mod=image&aid=…&size=500x480&key=…}</li>
 *   <li>Discuz 缩略图：{@code /data/attachment/common/c8/common_2_icon.png}</li>
 *   <li>占位图：{@code /static/js/imageloading.gif}（Comiis 懒加载占位）</li>
 *   <li>头像：{@code https://avatar.mt2.cn/uc_server/avatar.php?uid=…}</li>
 *   <li>表情：{@code /static/image/smiley/doge/1.png}</li>
 *   <li>勋章：{@code /static/image/common/medal1.gif}</li>
 * </ul>
 */
public class ImageUrlTest {

    private static Element img(String html) {
        Document d = Jsoup.parseBodyFragment("<div id='w'>" + html + "</div>");
        return d.select("img").first();
    }

    // ==================== 占位图 ====================

    @Test
    public void comiisPlaceholderIsDetected() {
        // Comiis 懒加载的真实占位名。旧实现只认 none/blank/grey.gif，
        // 于是这个占位被当真图加载 → 一个永远转圈的空白块
        assertTrue(ImageUrl.isPlaceholder(
                "https://bbs.binmt.cc/static/js/imageloading.gif"));
        assertTrue(ImageUrl.isPlaceholder("/static/image/common/none.gif"));
        assertTrue(ImageUrl.isPlaceholder("data:image/png;base64,iVBORw0KGgo="));
        assertTrue(ImageUrl.isPlaceholder(""));
        assertTrue(ImageUrl.isPlaceholder(null));
    }

    @Test
    public void realImageIsNotPlaceholder() {
        assertFalse(ImageUrl.isPlaceholder(
                "https://cdn.binmt.cc/forum.php?mod=image&aid=377135&size=500x99999&key=09eb"));
        assertFalse(ImageUrl.isPlaceholder(
                "/data/attachment/common/c8/common_2_icon.png"));
    }

    // ==================== 真实地址挑选 ====================

    @Test
    public void prefersLazyLoadAttrOverPlaceholderSrc() {
        // Comiis 的典型形态：src 是占位图，真实地址在 comiis_loadimages
        Element e = img("<img src='/static/js/imageloading.gif' "
                + "comiis_loadimages='/data/attachment/forum/202610/03/abc.jpg'>");
        assertEquals("/data/attachment/forum/202610/03/abc.jpg", ImageUrl.realUrl(e));
    }

    @Test
    public void prefersFileAttrOverSrc() {
        Element e = img("<img src='/static/js/imageloading.gif' "
                + "file='/data/attachment/forum/202610/03/real.jpg'>");
        assertEquals("/data/attachment/forum/202610/03/real.jpg", ImageUrl.realUrl(e));
    }

    @Test
    public void fallsBackToSrcWhenNoLazyAttr() {
        // mobile=2 模板实测：正文 img 没有任何懒加载属性，src 就是真实地址
        Element e = img("<img src='/forum.php?mod=image&aid=377135&size=500x480&key=09eb'>");
        assertEquals("/forum.php?mod=image&aid=377135&size=500x480&key=09eb",
                ImageUrl.realUrl(e));
    }

    @Test
    public void skipsPlaceholderInEarlierAttr() {
        // 前面的属性是占位图时要继续往后找，不能直接把占位图返回
        Element e = img("<img file='/static/js/imageloading.gif' "
                + "src='/data/attachment/forum/202610/03/ok.jpg'>");
        assertEquals("/data/attachment/forum/202610/03/ok.jpg", ImageUrl.realUrl(e));
    }

    @Test
    public void returnsNullWhenEveryAttrIsPlaceholder() {
        Element e = img("<img src='/static/js/imageloading.gif'>");
        assertNull(ImageUrl.realUrl(e));
    }

    @Test
    public void resolveHandlesNullElement() {
        assertNull(ImageUrl.resolve(null));
        assertNull(ImageUrl.realUrl(null));
    }

    // ==================== 相对地址补全 ====================

    @Test
    public void absoluteUrlsPassThrough() {
        assertEquals("https://cdn.binmt.cc/a.jpg",
                ImageUrl.toAbsolute("https://cdn.binmt.cc/a.jpg"));
        assertEquals("http://x.cn/a.jpg", ImageUrl.toAbsolute("http://x.cn/a.jpg"));
    }

    @Test
    public void protocolRelativeGetsHttps() {
        assertEquals("https://oss.binmt.cc/a.jpg",
                ImageUrl.toAbsolute("//oss.binmt.cc/a.jpg"));
    }

    @Test
    public void rootRelativeUsesBaseWhenGiven() {
        // Discuz 页面带 <base href="https://bbs.binmt.cc/">
        assertEquals("https://bbs.binmt.cc/forum.php?mod=image&aid=1",
                ImageUrl.toAbsolute("/forum.php?mod=image&aid=1", "https://bbs.binmt.cc/"));
        // 没有 base 时退回站点根
        assertEquals("https://bbs.binmt.cc/forum.php?mod=image&aid=1",
                ImageUrl.toAbsolute("/forum.php?mod=image&aid=1"));
        // 空 base 视为「没有 base」，退回站点根（与旧 toAbsolute 行为一致）
        assertEquals("https://bbs.binmt.cc/forum.php?mod=image&aid=1",
                ImageUrl.toAbsolute("/forum.php?mod=image&aid=1", ""));
    }

    @Test
    public void baseWithoutTrailingSlashIsNormalized() {
        assertEquals("https://bbs.binmt.cc/x.png",
                ImageUrl.toAbsolute("/x.png", "https://bbs.binmt.cc"));
    }

    // ==================== 缩略图 → 原图 ====================

    @Test
    public void thumbnailSizeIsUpgraded() {
        assertEquals(
                "https://cdn.binmt.cc/forum.php?mod=image&aid=377135&size=99999x99999&key=09eb",
                ImageUrl.toFullSize(
                        "https://cdn.binmt.cc/forum.php?mod=image&aid=377135&size=500x480&key=09eb"));
        assertEquals(
                "https://cdn.binmt.cc/forum.php?mod=image&aid=1&size=99999x99999&key=k",
                ImageUrl.toFullSize(
                        "https://cdn.binmt.cc/forum.php?mod=image&aid=1&size=500x99999&key=k"));
    }

    @Test
    public void nonAttachmentUrlsAreNotTouched() {
        // 头像 / 表情 / 外链图一律不动
        String avatar = "https://avatar.mt2.cn/uc_server/avatar.php?uid=1&size=middle";
        assertEquals(avatar, ImageUrl.toFullSize(avatar));
        assertEquals("", ImageUrl.toFullSize(""));
    }

    // ==================== 是否帖子配图 ====================

    @Test
    public void realAttachmentsCountAsPostImages() {
        // ★ 关键回归：旧实现按 "icon" 子串命中即杀，
        //   而 Discuz 的附件缩略图文件名就带 _icon
        assertTrue(ImageUrl.isPostImage(
                "https://bbs.binmt.cc/data/attachment/common/c8/common_2_icon.png"));
        assertTrue(ImageUrl.isPostImage(
                "https://cdn.binmt.cc/forum.php?mod=image&aid=377135&size=500x480&key=09eb"));
        assertTrue(ImageUrl.isPostImage("/data/attachment/forum/202610/03/x.jpg"));
    }

    @Test
    public void decorationsAreNotPostImages() {
        assertFalse(ImageUrl.isPostImage("https://bbs.binmt.cc/static/image/smiley/doge/1.png"));
        assertFalse(ImageUrl.isPostImage("https://bbs.binmt.cc/static/image/common/medal1.gif"));
        assertFalse(ImageUrl.isPostImage("https://bbs.binmt.cc/static/image/magic/highlight.small.gif"));
        assertFalse(ImageUrl.isPostImage(
                "https://avatar.mt2.cn/uc_server/avatar.php?uid=110920&size=middle"));
        assertFalse(ImageUrl.isPostImage("https://bbs.binmt.cc/beian.png"));
    }

    @Test
    public void blankOrPlaceholderIsNeverPostImage() {
        assertFalse(ImageUrl.isPostImage(""));
        assertFalse(ImageUrl.isPostImage(null));
        assertFalse(ImageUrl.isPostImage("/static/js/imageloading.gif"));
    }

    // ==================== 一步到位 ====================

    @Test
    public void resolveCombinesPickAndAbsolutize() {
        Element e = img("<img src='/static/js/imageloading.gif' "
                + "comiis_loadimages='/data/attachment/forum/202610/03/abc.jpg'>");
        assertEquals("https://bbs.binmt.cc/data/attachment/forum/202610/03/abc.jpg",
                ImageUrl.resolve(e));
        // 带 base
        assertEquals("https://bbs.binmt.cc/data/attachment/forum/202610/03/abc.jpg",
                ImageUrl.resolve(e, "https://bbs.binmt.cc/"));
    }
}
