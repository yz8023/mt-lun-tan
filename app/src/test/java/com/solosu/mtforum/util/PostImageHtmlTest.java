package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.Test;

public class PostImageHtmlTest {

    private static final String BASE = "https://bbs.binmt.cc/";

    private static Element firstImage(String html) {
        return Jsoup.parseBodyFragment(html, BASE).selectFirst("img");
    }

    private static String signedAid(int aid) throws Exception {
        String payload = aid + "|deadbeef|" + (System.currentTimeMillis() / 1000L) + "|0|123";
        String raw = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return URLEncoder.encode(raw, StandardCharsets.UTF_8.name());
    }

    @Test
    public void prefersOriginalAttachmentLinkAroundThumbnailAndRequestsNoThumb() throws Exception {
        String aid = signedAid(4102);
        String html = "<a href=\"/forum.php?mod=attachment&amp;aid=" + aid + "\">"
                + "<img src=\"/forum.php?mod=image&amp;aid=4102&amp;size=300x300&amp;key=x\"></a>";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        Element image = firstImage(upgraded);
        assertNotNull(image);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=" + aid + "&nothumb=yes",
                image.attr("src"));
    }

    @Test
    public void directAttachmentLinkOutranksLargeDiscuzImageVariant() throws Exception {
        String aid = signedAid(4103);
        String html = "<a href=\"/forum.php?mod=attachment&amp;aid=" + aid + "&amp;nothumb=yes\">"
                + "<img file=\"/forum.php?mod=image&amp;aid=4103&amp;size=500x480&amp;key=x\""
                + " src=\"/forum.php?mod=image&amp;aid=4103&amp;size=300x300&amp;key=x\"></a>";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=" + aid + "&nothumb=yes",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void usesOnlySignedAidFormatFromImageElement() throws Exception {
        String aid = signedAid(6172);
        String html = "<img aid=\"" + aid + "\" src=\"https://cdn.binmt.cc/forum.php?mod=image"
                + "&amp;aid=6172&amp;size=300x0&amp;key=k\">";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=" + aid + "&nothumb=yes",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void doesNotInventAttachmentUrlFromUnsignedNumericImageAid() {
        String html = "<img src=\"https://cdn.binmt.cc/forum.php?mod=image"
                + "&amp;aid=6172&amp;size=300x300&amp;key=k\">";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://cdn.binmt.cc/forum.php?mod=image&aid=6172&size=300x300&key=k",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void explicitOriginalAttributeWinsOverThumbnail() {
        String html = "<img src=\"/forum.php?mod=image&amp;aid=41&amp;size=300x300\""
                + " file=\"/data/attachment/forum/202610/original.jpg\">";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/data/attachment/forum/202610/original.jpg",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void explicitOriginalAttributeOutranksSignedAidRoute() throws Exception {
        String aid = signedAid(91);
        String html = "<img aid=\"" + aid + "\""
                + " file=\"/data/attachment/forum/202610/original.jpg\""
                + " src=\"/forum.php?mod=image&amp;aid=91&amp;size=300x300\">";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/data/attachment/forum/202610/original.jpg",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void explicitAttachmentLinkIsUsedEvenWithoutConstructingFromThumbnailAid() {
        String html = "<a href=\"/forum.php?mod=attachment&amp;aid=91\">"
                + "<img src=\"/forum.php?mod=image&amp;aid=91&amp;size=300x300&amp;key=x\"></a>";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=91&nothumb=yes",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void directAttachmentSrcExplicitlyRequestsOriginal() throws Exception {
        String aid = signedAid(91);
        String html = "<img src=\"/forum.php?mod=attachment&amp;aid=" + aid + "\">";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=" + aid + "&nothumb=yes",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void preservesLargerImageRouteAlreadyUsedByForumCdn() {
        String original = "https://cdn.binmt.cc/forum.php?mod=image&aid=877&size=500x480&key=k";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(
                "<img src=\"" + original + "\">", BASE);
        assertEquals(original, firstImage(upgraded).attr("src"));
    }

    @Test
    public void signedAidUpgradesMediumThumbnailEvenWhenParentLinksToMediumVariant() throws Exception {
        String aid = signedAid(8123);
        String html = "<a href=\"/forum.php?mod=image&amp;aid=8123&amp;size=500x480&amp;key=x\">"
                + "<img aid=\"" + aid + "\" src=\"/forum.php?mod=image&amp;aid=8123&amp;size=300x300&amp;key=x\"></a>";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=" + aid + "&nothumb=yes",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void signedAidOnAncestorCanUpgradeDiscuzImageVariant() throws Exception {
        String aid = signedAid(8124);
        String html = "<div data-aid=\"" + aid + "\"><img src=\"/forum.php?mod=image"
                + "&amp;aid=8124&amp;size=500x480&amp;key=x\"></div>";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=" + aid + "&nothumb=yes",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void overridesExplicitNoThumbFalseOnDirectAttachmentLink() {
        String html = "<a href=\"/forum.php?mod=attachment&amp;aid=91&amp;nothumb=no&amp;foo=1\">"
                + "<img src=\"/forum.php?mod=image&amp;aid=91&amp;size=300x300\"></a>";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=91&nothumb=yes&foo=1",
                firstImage(upgraded).attr("src"));
    }

    @Test
    public void doesNotTreatOrdinaryThreadLinkAsOriginalImage() {
        String html = "<a href=\"thread-123-1-1.html\">"
                + "<img src=\"/forum.php?mod=image&amp;aid=82&amp;size=300x300\"></a>";
        String upgraded = PostImageHtml.upgradeThumbnailsToFull(html, BASE);
        assertEquals("https://bbs.binmt.cc/forum.php?mod=image&aid=82&size=300x300",
                firstImage(upgraded).attr("src"));
    }
}
