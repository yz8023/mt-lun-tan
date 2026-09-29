package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 代码块抽取的 JVM 单元测试（build63）。
 *
 * <p>守住两件事：
 * <ol>
 *   <li>{@code <pre>} 块能被完整摘出来，且还原成的代码文本保留换行、缩进、尖括号</li>
 *   <li>正文里不再残留代码 —— 否则会出现"代码卡片 + 正文各显示一份"的重复</li>
 * </ol>
 */
public class CodeBlockExtractTest {

    /** 模拟 BBCodeUtil 渲染 [code] 后产出的 HTML 结构 */
    private String renderedCodeHtml(String langSpan, String... lines) {
        StringBuilder sb = new StringBuilder("<pre class=\"comiis_blockcode\">");
        if (langSpan != null) {
            sb.append("<span style=\"color:#9CA3AF;font-size:12px\">").append(langSpan).append("</span><br>");
        }
        for (String l : lines) sb.append(l).append("<br>");
        sb.append("</pre>");
        return sb.toString();
    }

    @Test
    public void extractsSingleBlockAndKeepsText() {
        String html = "<p>看这段：</p>" + renderedCodeHtml("java",
                "public&nbsp;void&nbsp;main()&nbsp;{",
                "&nbsp;&nbsp;&nbsp;&nbsp;if&nbsp;(a&nbsp;&lt;&nbsp;b)&nbsp;print();",
                "}") + "<p>就这样</p>";

        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);

        assertTrue(r.hasBlocks());
        assertEquals(1, r.blocks.size());
        assertEquals("java", r.blocks.get(0).lang);

        String code = r.blocks.get(0).code;
        assertEquals("public void main() {\n    if (a < b) print();\n}", code);

        // 正文里不能再残留代码
        assertFalse(r.html.contains("<pre"));
        assertFalse(r.html.contains("public"));
        assertTrue(r.html.contains("看这段"));
        assertTrue(r.html.contains("就这样"));
    }

    @Test
    public void extractsMultipleBlocks() {
        String html = renderedCodeHtml("xml", "&lt;a&nbsp;/&gt;")
                + "<p>分隔</p>"
                + renderedCodeHtml(null, "echo&nbsp;1");

        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);
        assertEquals(2, r.blocks.size());
        assertEquals("xml", r.blocks.get(0).lang);
        assertEquals("<a />", r.blocks.get(0).code);
        // 没有语言标签时 lang 为 null
        assertEquals(null, r.blocks.get(1).lang);
        assertEquals("echo 1", r.blocks.get(1).code);
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
        // &amp;lt; 必须还原成字面量 "&lt;" 而不是 "<"，否则转义顺序就错了
        String html = renderedCodeHtml(null, "a&amp;amp;b");
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);
        assertEquals("a&amp;b", r.blocks.get(0).code);
    }

    @Test
    public void trimsTrailingBlankLines() {
        String html = renderedCodeHtml(null, "line1", "", "");
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(html);
        assertEquals("line1", r.blocks.get(0).code);
    }
}
