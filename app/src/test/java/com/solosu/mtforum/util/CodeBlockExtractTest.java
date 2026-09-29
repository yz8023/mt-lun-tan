package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 代码块抽取的 JVM 单元测试（build63 起，build64 扩充）。
 *
 * <p>守住四件事：
 * <ol>
 *   <li>论坛移动版原生结构 {@code div.comiis_blockcode > div > ol > li} 能被摘出来
 *       —— 这是油猴脚本 {@code .blockcode>div,.comiis_blockcode>div} 用的同一个结构</li>
 *   <li><b>复制出来的代码不能带行号</b>。ForumParser 会给每行加
 *       {@code <span class="mt-ln">N </span>} 用于显示，抽取时必须剥掉，
 *       否则粘到编辑器里每行开头都是数字</li>
 *   <li>正文里不再残留代码，避免"卡片 + 正文各显示一份"</li>
 *   <li>实体还原顺序正确（{@code &amp;amp;} → 字面量 {@code &amp;}，不能二次解码）</li>
 * </ol>
 */
public class CodeBlockExtractTest {

    /** 论坛移动版原生结构：行号来自 <ol> 计数器，不在文本里 */
    private String forumNativeHtml(String... lines) {
        StringBuilder sb = new StringBuilder(
                "<div class=\"comiis_blockcode\"><div class=\"bg_f b_l\"><ol>");
        for (String l : lines) sb.append("<li>").append(l).append("</li>");
        sb.append("</ol></div></div>");
        return sb.toString();
    }

    /** ForumParser.normalizeCodeBlocks 的产物：行号被包进 span.mt-ln */
    private String normalizedHtml(String lang, String... lines) {
        StringBuilder sb = new StringBuilder("<pre class=\"comiis_blockcode\">");
        if (lang != null) {
            sb.append("<span class=\"mt-lang\" style=\"color:#9CA3AF\">").append(lang).append("</span><br>");
        }
        int n = 1;
        for (String l : lines) {
            sb.append("<span class=\"mt-ln\">").append(n++).append(" </span>").append(l).append("<br>");
        }
        sb.append("</pre>");
        return sb.toString();
    }

    // ==================== 论坛原生结构 ====================

    @Test
    public void extractsForumNativeBlockcode() {
        String html = "<p>代码如下</p>" + forumNativeHtml(
                "public&nbsp;void&nbsp;main()&nbsp;{",
                "&nbsp;&nbsp;&nbsp;&nbsp;if&nbsp;(a&nbsp;&lt;&nbsp;b)&nbsp;go();",
                "}");

        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);

        assertTrue(r.hasBlocks());
        assertEquals(1, r.blocks.size());
        assertEquals("public void main() {\n    if (a < b) go();\n}", r.blocks.get(0).code);
        assertFalse(r.html.contains("blockcode"));
        assertTrue(r.html.contains("代码如下"));
    }

    // ==================== 行号必须被剥掉 ====================

    @Test
    public void stripsInjectedLineNumbers() {
        String html = normalizedHtml("java",
                "public&nbsp;class&nbsp;A&nbsp;{", "}");

        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);

        assertEquals("java", r.blocks.get(0).lang);
        String code = r.blocks.get(0).code;
        // 关键断言：不能出现 "1 public" 这种带行号的结果
        assertEquals("public class A {\n}", code);
        assertFalse("复制出的代码不应包含行号", code.startsWith("1 "));
    }

    @Test
    public void lineNumbersStrippedEvenWithManyLines() {
        String[] lines = new String[15];
        for (int i = 0; i < 15; i++) lines[i] = "line" + (i + 1);
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(normalizedHtml(null, lines));
        String code = r.blocks.get(0).code;
        String[] got = code.split("\n");
        assertEquals(15, got.length);
        for (int i = 0; i < 15; i++) {
            assertEquals("line" + (i + 1), got[i]);
        }
    }

    // ==================== 语言标签 ====================

    @Test
    public void readsLangAndRemovesItFromCode() {
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(normalizedHtml("kotlin", "val&nbsp;a&nbsp;=&nbsp;1"));
        assertEquals("kotlin", r.blocks.get(0).lang);
        assertEquals("val a = 1", r.blocks.get(0).code);
    }

    @Test
    public void noLangGivesNull() {
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(normalizedHtml(null, "echo&nbsp;1"));
        assertNull(r.blocks.get(0).lang);
        assertEquals("echo 1", r.blocks.get(0).code);
    }

    // ==================== 多块 / 边界 ====================

    @Test
    public void extractsMultipleBlocks() {
        String html = forumNativeHtml("&lt;a&nbsp;/&gt;") + "<p>分隔</p>" + normalizedHtml(null, "echo&nbsp;1");
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);
        assertEquals(2, r.blocks.size());
        assertEquals("<a />", r.blocks.get(0).code);
        assertEquals("echo 1", r.blocks.get(1).code);
        assertTrue(r.html.contains("分隔"));
    }

    @Test
    public void noCodeBlockLeavesHtmlUntouched() {
        String html = "<p>只有普通文本，没有代码</p>";
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);
        assertFalse(r.hasBlocks());
        assertEquals(html, r.html);
    }

    @Test
    public void handlesNullAndEmpty() {
        assertFalse(BBCodeUtil.extractCodeBlocks(null).hasBlocks());
        assertFalse(BBCodeUtil.extractCodeBlocks("").hasBlocks());
    }

    @Test
    public void unescapesAmpersandLast() {
        // &amp;amp; 必须还原成字面量 "&amp;"，不能被二次解码成 "&"
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(forumNativeHtml("a&amp;amp;b"));
        assertEquals("a&amp;b", r.blocks.get(0).code);
    }

    @Test
    public void trimsTrailingBlankLines() {
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(normalizedHtml(null, "line1", "", ""));
        assertEquals("line1", r.blocks.get(0).code);
    }

    @Test
    public void nestedPreInsideBlockcodeExtractedOnce() {
        // div.blockcode 里套 pre：只应摘一次，不能出现两块
        String html = "<div class=\"blockcode\"><pre>a&nbsp;=&nbsp;1</pre></div>";
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);
        assertEquals(1, r.blocks.size());
        assertEquals("a = 1", r.blocks.get(0).code);
    }
}
