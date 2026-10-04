package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 正文代码卡片替换的 JVM 单元测试（build98）。
 *
 * <p>用户反馈「帖子正文里面的代码部分还是不能直接复制」。修法是不再靠 JS 去猜站点结构，
 * 而是在把 HTML 交给 WebView 之前，由 {@link PostCodeRender} 把代码块换成 App 自己
 * 生成的卡片（卡片里就有一个写死 class 的复制按钮）。这个测试守住三件事：
 * <ol>
 *   <li>替换后一定存在 {@code div.mt-code} + {@code span.mt-code-btn}（按钮必然存在）</li>
 *   <li>卡片里的代码是<b>干净</b>的：不带 {@code span.mt-ln} 行号、缩进与换行都在</li>
 *   <li>嵌套的 {@code .comiis_blockcode} 只生成一张卡片，行内 {@code <code>} 保持原样</li>
 * </ol>
 */
public class PostCodeRenderTest {

    /** 论坛移动版原生结构：行号来自 &lt;ol&gt; 计数器，不在文本里 */
    private static String forumNative(String... lines) {
        StringBuilder sb = new StringBuilder(
                "<div class=\"comiis_blockcode\"><div class=\"bg_f b_l\"><ol>");
        for (String l : lines) sb.append("<li>").append(l).append("</li>");
        sb.append("</ol></div></div>");
        return sb.toString();
    }

    /** normalizeCodeBlocks 的产物：&lt;pre class="comiis_blockcode"&gt; + span.mt-ln 行号 + &lt;br&gt; 分行 */
    private static String normalized(String lang, String... lines) {
        StringBuilder sb = new StringBuilder("<pre class=\"comiis_blockcode\">");
        if (lang != null) {
            sb.append("<span class=\"mt-lang\" style=\"color:#9CA3AF\">").append(lang)
                    .append("</span><br>");
        }
        for (int i = 0; i < lines.length; i++) {
            sb.append("<span class=\"mt-ln\">").append(i + 1).append(" </span>")
                    .append(lines[i]).append("<br>");
        }
        return sb.append("</pre>").toString();
    }

    @Test
    public void normalizedPreBecomesCardWithCleanCode() {
        String html = "<p>看看这段：</p>" + normalized("java",
                "public class Demo {",
                "    if (a > 0 && b < 2) {",
                "    }",
                "}");
        String out = PostCodeRender.decorate(html);

        assertTrue("必须生成卡片", out.contains("class=\"" + PostCodeRender.MARK + "\""));
        assertTrue("卡片里必须有复制按钮",
                out.contains(PostCodeRender.MARK_BTN));
        assertEquals("一段代码只该有一个按钮", 1, count(out, PostCodeRender.MARK_BTN));
        assertFalse("行号不能进卡片", out.contains("mt-ln"));
        assertFalse("原来的 pre 不应残留", out.contains("comiis_blockcode"));

        // 代码文本：换行保留、缩进保留、尖括号被转义成实体
        assertTrue(out.contains("public class Demo {"));
        assertTrue(out.contains("    if (a &gt; 0 &amp;&amp; b &lt; 2) {"));
    }

    @Test
    public void forumNativeOlStructureBecomesCard() {
        String out = PostCodeRender.decorate(
                forumNative("#!/bin/sh", "echo \"hi\"", "exit 0"));
        assertTrue(out.contains(PostCodeRender.MARK));
        assertEquals(1, count(out, PostCodeRender.MARK_BTN));
        assertTrue(out.contains("#!/bin/sh"));
        assertTrue(out.contains("echo \"hi\""));
    }

    @Test
    public void nestedBlockcodeProducesSingleCard() {
        String nested = "<div class=\"comiis_blockcode\"><div class=\"blockcode\">"
                + "<pre class=\"blockcode\">\nX=1\n</pre></div></div>";
        String out = PostCodeRender.decorate(nested);
        assertEquals("嵌套结构只能有一张卡片", 1, count(out, "class=\"" + PostCodeRender.MARK + "\""));
        assertEquals(1, count(out, PostCodeRender.MARK_BTN));
        assertTrue(out.contains("X=1"));
    }

    @Test
    public void inlineCodeIsLeftAlone() {
        String html = "<p>用 <code>adb shell</code> 就能连上</p>";
        String out = PostCodeRender.decorate(html);
        assertFalse("行内代码不该变成卡片", out.contains(PostCodeRender.MARK));
        assertTrue(out.contains("<code>adb shell</code>"));
    }

    @Test
    public void noCodeBlocksReturnsInputUntouched() {
        String html = "<p>纯文字，没有任何代码块</p>";
        assertEquals(html, PostCodeRender.decorate(html));
    }

    @Test
    public void languageLabelFallsBackToCode() {
        String out = PostCodeRender.decorate(forumNative("ls -la"));
        assertTrue(out.contains("代码"));
    }

    @Test
    public void codeWithEmptyLinesKeepsStructure() {
        String out = PostCodeRender.decorate(normalized(null, "def f():", "", "    return 1"));
        // 空行要保留（否则代码粘连）
        assertTrue(out.contains("def f():\n\n    return 1"));
    }

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
