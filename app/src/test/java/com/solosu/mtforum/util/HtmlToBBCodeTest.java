package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * HTML → BBCode 的 JVM 单元测试（build65）。
 *
 * <p>复制正文要能直接拿去转发 / 引用，所以必须还原成 BBCode 而不是可见文本 ——
 * 纯文本会把代码块、图片、链接、排版全丢掉。
 */
public class HtmlToBBCodeTest {

    @Test
    public void basicTextStyles() {
        assertEquals("[b]粗[/b]", HtmlToBBCode.convert("<strong>粗</strong>"));
        assertEquals("[i]斜[/i]", HtmlToBBCode.convert("<i>斜</i>"));
        assertEquals("[u]下划线[/u]", HtmlToBBCode.convert("<u>下划线</u>"));
        assertEquals("[s]删除[/s]", HtmlToBBCode.convert("<strike>删除</strike>"));
    }

    @Test
    public void nestedStylesKeepOrder() {
        // 正则版最容易在嵌套时闭合错配，Jsoup 递归天然不会
        String bb = HtmlToBBCode.convert(
                "<font color=\"#ff0000\"><strong>红粗</strong>普通</font>");
        assertEquals("[color=#ff0000][b]红粗[/b]普通[/color]", bb);
    }

    @Test
    public void fontSizeAndBackColor() {
        assertEquals("[size=5]大[/size]", HtmlToBBCode.convert("<font size=\"5\">大</font>"));
        assertEquals("[backcolor=#ffff00]高亮[/backcolor]",
                HtmlToBBCode.convert("<font style=\"background-color:#ffff00\">高亮</font>"));
    }

    @Test
    public void linksAndImages() {
        assertEquals("[url=https://a.com]文字[/url]",
                HtmlToBBCode.convert("<a href=\"https://a.com\">文字</a>"));
        // 链接文本等于地址时用短写法
        assertEquals("[url]https://a.com[/url]",
                HtmlToBBCode.convert("<a href=\"https://a.com\">https://a.com</a>"));
        assertEquals("[img]https://a.com/x.png[/img]",
                HtmlToBBCode.convert("<img src=\"https://a.com/x.png\">"));
        assertEquals("[img=100,50]https://a.com/x.png[/img]",
                HtmlToBBCode.convert("<img src=\"https://a.com/x.png\" width=\"100\" height=\"50\">"));
    }

    @Test
    public void emailAndQq() {
        assertEquals("[email]a@b.com[/email]",
                HtmlToBBCode.convert("<a href=\"mailto:a@b.com\">a@b.com</a>"));
        assertEquals("[qq]12345[/qq]",
                HtmlToBBCode.convert("<a href=\"http://wpa.qq.com/msgrd?v=3&uin=12345&site=qq\">聊天</a>"));
    }

    @Test
    public void listsAndTable() {
        String bb = HtmlToBBCode.convert("<ul><li>甲</li><li>乙</li></ul>");
        assertTrue(bb.startsWith("[list]"));
        assertTrue(bb.contains("[*]甲"));
        assertTrue(bb.contains("[*]乙"));
        assertTrue(bb.endsWith("[/list]"));

        String tb = HtmlToBBCode.convert("<table><tr><td>A</td><td>B</td></tr></table>");
        assertTrue(tb.contains("[table]"));
        assertTrue(tb.contains("[tr]"));
        assertTrue(tb.contains("[td]A[/td][td]B[/td]"));
    }

    @Test
    public void codeBlockBecomesCodeTag() {
        // 论坛原生代码块结构
        String html = "<div class=\"comiis_blockcode\"><div class=\"bg_f b_l\"><ol>"
                + "<li>int&nbsp;a&nbsp;=&nbsp;1;</li><li>if&nbsp;(a&nbsp;&lt;&nbsp;2)&nbsp;{}</li>"
                + "</ol></div></div>";
        String bb = HtmlToBBCode.convert(html);
        assertTrue(bb.contains("[code]"));
        assertTrue(bb.contains("int a = 1;"));
        assertTrue(bb.contains("if (a < 2) {}"));
        assertTrue(bb.contains("[/code]"));
        // 代码里不能混进行号
        for (String line : bb.split("\n")) {
            assertFalse(line.matches("^\\d+ .*"));
        }
    }

    @Test
    public void alignAndHr() {
        assertEquals("[align=center]居中[/align]",
                HtmlToBBCode.convert("<div align=\"center\">居中</div>"));
        assertEquals("[hr]", HtmlToBBCode.convert("<hr>"));
    }

    @Test
    public void quoteBlock() {
        String bb = HtmlToBBCode.convert(
                "<div class=\"comiis_quote bg_h\"><blockquote>引用内容</blockquote></div>");
        assertTrue(bb.contains("[quote]"));
        assertTrue(bb.contains("引用内容"));
    }

    @Test
    public void brBecomesNewlineAndExtraBlanksCollapse() {
        assertEquals("一\n二", HtmlToBBCode.convert("一<br>二"));
        assertEquals("一\n\n二", HtmlToBBCode.convert("一<br><br><br><br>二"));
    }

    @Test
    public void scriptAndStyleDropped() {
        assertEquals("正文", HtmlToBBCode.convert("<script>evil()</script>正文<style>a{}</style>"));
    }

    @Test
    public void nullAndEmpty() {
        assertEquals("", HtmlToBBCode.convert(null));
        assertEquals("", HtmlToBBCode.convert(""));
    }

    @Test
    public void realisticPostRoundTrip() {
        String html = "<div>大家好，推荐一个工具：<a href=\"https://t.cn/x\">链接</a><br>"
                + "<font color=\"red\">注意</font>先看说明<br>"
                + "<img src=\"https://i.cn/1.png\"></div>";
        String bb = HtmlToBBCode.convert(html);
        assertTrue(bb.contains("[url=https://t.cn/x]链接[/url]"));
        assertTrue(bb.contains("[color=red]注意[/color]"));
        assertTrue(bb.contains("[img]https://i.cn/1.png[/img]"));
        // 不应残留 HTML 标签
        assertFalse(bb.contains("<"));
        assertFalse(bb.contains(">"));
    }
}
