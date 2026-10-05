package com.solosu.mtforum.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlainTextUrlPatternTest {

    @Test
    public void recognizesHttpHttpsWwwAndCnHosts() {
        assertHasLink("http://example.com/a");
        assertHasLink("https://example.cn/path?q=1");
        assertHasLink("WWW.example.cn");
        assertHasLink("www.cn");
    }

    @Test
    public void recognizesBareCommonDomainsAndKeepsUrlPath() {
        assertTrue(PlainTextUrlPattern.isBareWebAddress("example.cn/path?q=1"));
        assertTrue(PlainTextUrlPattern.isBareWebAddress("example.com"));
        assertTrue(PlainTextUrlPattern.isBareWebAddress("www.cn"));
        assertFalse(PlainTextUrlPattern.isBareWebAddress("readme.html"));
    }

    @Test
    public void findsMultipleLinksBesideChineseTextAndPunctuation() {
        String text = "访问https://a.example.cn/1，或 www.example.com/test；还有example.org。";
        List<String> links = find(text);
        assertEquals(3, links.size());
        assertTrue(links.get(0).startsWith("https://a.example.cn/1"));
        assertTrue(links.get(1).startsWith("www.example.com/test"));
        assertTrue(links.get(2).startsWith("example.org"));
    }

    @Test
    public void excludesTrailingSentencePunctuationFromTheLinkSpan() {
        List<String> links = find("打开 https://example.com/path.");
        assertEquals(1, links.size());
        assertEquals("https://example.com/path", links.get(0));
    }

    @Test
    public void doesNotLinkifyEmailDomainOrBareHtmlFilename() {
        assertTrue(find("邮件 user@example.com 已发送").isEmpty());
        assertTrue(find("文件名 readme.html 已更新").isEmpty());
    }

    private static void assertHasLink(String value) {
        assertFalse(find(value).isEmpty());
    }

    private static List<String> find(String text) {
        List<String> links = new ArrayList<>();
        Matcher matcher = PlainTextUrlPattern.WEB_URL.matcher(text);
        while (matcher.find()) links.add(matcher.group());
        return links;
    }
}
