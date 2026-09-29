package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.solosu.mtforum.network.ForumParser;

import org.junit.Test;

/**
 * 端到端管线测试（build64）。
 *
 * <p>走的是线上真实链路：
 * <pre>
 *   论坛原始 HTML（div.comiis_blockcode &gt; div &gt; ol &gt; li）
 *     → ForumParser.normalizeCodeBlocks()   给每行加 &lt;span class="mt-ln"&gt; 行号（仅供显示）
 *     → BBCodeUtil.extractCodeBlocks()      摘出代码块并剥掉行号
 *     → CodeBlockView 显示 + 复制
 * </pre>
 *
 * <p>这个测试存在的唯一理由：确认<b>复制出来的代码不带行号</b>。
 * 上一版 normalizeCodeBlocks 把行号直接拼成 {@code "1 code"} 写进文本，
 * 复制结果每行都顶着个数字，粘到编辑器里全是语法错误 —— 而这种问题
 * 只看界面是发现不了的，界面上行号本来就该显示。
 *
 * <p>HTML 结构取自用户提供的油猴脚本里的选择器与正则：
 * {@code .blockcode>div, .comiis_blockcode>div} 以及
 * {@code /<div class="comiis_blockcode[^>]*><div class="bg_f b_l"><ol>([\s\S]*?)<\/ol><\/div><\/div>/}
 */
public class CodePipelineEndToEndTest {

    /** 论坛移动版真实结构 */
    private static String forumHtml(String... lines) {
        StringBuilder sb = new StringBuilder(
                "<div class=\"comiis_blockcode\"><div class=\"bg_f b_l\"><ol>");
        for (String l : lines) sb.append("<li>").append(l).append("</li>");
        sb.append("</ol></div></div>");
        return sb.toString();
    }

    @Test
    public void fullPipelineYieldsCleanCode() {
        String raw = "<p>试试这段：</p>" + forumHtml(
                "public&nbsp;class&nbsp;Demo&nbsp;{",
                "&nbsp;&nbsp;&nbsp;&nbsp;public&nbsp;static&nbsp;void&nbsp;main(String[]&nbsp;a)&nbsp;{",
                "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;if&nbsp;(a.length&nbsp;&gt;&nbsp;0&nbsp;&amp;&amp;&nbsp;a[0]&nbsp;!=&nbsp;null)&nbsp;{",
                "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;System.out.println(&quot;hi&quot;);",
                "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}",
                "&nbsp;&nbsp;&nbsp;&nbsp;}",
                "}");

        // ① 标准化（这一步会注入显示用行号）
        String normalized = ForumParser.normalizeCodeBlocks(raw);
        assertTrue("标准化后应带显示用行号", normalized.contains("mt-ln"));

        // ② 抽取（这一步必须把行号剥掉）
        BBCodeUtil.Extracted extracted = BBCodeUtil.extractCodeBlocks(normalized);
        assertTrue(extracted.hasBlocks());
        assertEquals(1, extracted.blocks.size());

        String code = extracted.blocks.get(0).code;

        String expected =
                "public class Demo {\n"
              + "    public static void main(String[] a) {\n"
              + "        if (a.length > 0 && a[0] != null) {\n"
              + "            System.out.println(\"hi\");\n"
              + "        }\n"
              + "    }\n"
              + "}";
        assertEquals(expected, code);

        // 逐行确认没有行号前缀
        for (String line : code.split("\n")) {
            assertFalse("行首不应出现行号：" + line, line.matches("^\\d+ .*"));
        }

        // 正文里不能再残留代码
        assertFalse(extracted.html.contains("blockcode"));
        assertFalse(extracted.html.contains("System.out"));
        assertTrue(extracted.html.contains("试试这段"));
    }

    @Test
    public void indentationIsPreserved() {
        String raw = forumHtml(
                "def&nbsp;f():",
                "&nbsp;&nbsp;&nbsp;&nbsp;return&nbsp;1");
        BBCodeUtil.Extracted r =
                BBCodeUtil.extractCodeBlocks(ForumParser.normalizeCodeBlocks(raw));
        assertEquals("def f():\n    return 1", r.blocks.get(0).code);
    }

    @Test
    public void blankLinesInsideCodeDoNotBreakNumbering() {
        // 空行会被 normalizeCodeBlocks 跳过（不输出），剥完行号后仍要保持代码可读
        String raw = forumHtml("a&nbsp;=&nbsp;1", "", "b&nbsp;=&nbsp;2");
        BBCodeUtil.Extracted r =
                BBCodeUtil.extractCodeBlocks(ForumParser.normalizeCodeBlocks(raw));
        String code = r.blocks.get(0).code;
        assertTrue(code.contains("a = 1"));
        assertTrue(code.contains("b = 2"));
        for (String line : code.split("\n")) {
            assertFalse(line.matches("^\\d+ .*"));
        }
    }

    @Test
    public void rawForumHtmlWithoutNormalizeAlsoWorks() {
        // 万一某条链路没走 normalize，直接抽原始结构也要能拿到干净代码
        BBCodeUtil.Extracted r = BBCodeUtil.extractCodeBlocks(
                forumHtml("echo&nbsp;&quot;hello&quot;"));
        assertEquals("echo \"hello\"", r.blocks.get(0).code);
    }
}
