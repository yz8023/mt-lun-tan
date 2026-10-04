package com.solosu.mtforum.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「用了 {@code [code]} 之后颜色全变成字面量」的回归测试（build98 · 用户反馈第 5 条）。
 *
 * <p>用户原话：{@code 快捷键如果用了[code][/code]，会导致预览异常，导致之前的全部代码失效
 * 比如颜色什么的，直接不显示颜色了，直接把颜色代码露出来了}，例子是
 * {@code 天天[color=#B30000]向上[/color]}。
 *
 * <h3>根因（在真机 / AOSP 上定位到的）</h3>
 * {@code Html.fromHtml} 在收尾时会把所有 ParagraphStyle span 重新
 * {@code setSpan(..., SPAN_PARAGRAPH)}，而 {@code SPAN_PARAGRAPH} 要求起点落在段落边界
 * （index 0 或前一个字符是 {@code \n}）。代码块的 TagHandler 给 {@code <pre>} 套的
 * LineBackgroundSpan 正好是 ParagraphStyle 的子接口 —— 于是只要 {@code <pre>} 前面
 * 还有同行内容（{@code 天天[color=…]向上[/color]} 直接粘 {@code [code]}），
 * {@code setSpan} 就抛
 * {@code PARAGRAPH span must start at paragraph boundary}，
 * 整个 {@code Html.fromHtml} 失败 → 预览只能显示原文 → 用户看到的满屏
 * {@code [color=#B30000]}。
 *
 * <h3>这条测试守什么</h3>
 * <ol>
 *   <li>{@code [code]} 之前有同行内容时，还原出来的 {@code <pre>} 必须自带一个
 *       {@code <br>} 把它顶到新段落（否则 Html.fromHtml 会崩）</li>
 *   <li>裸换行 {@code \n} <b>不算</b>安全边界 —— Html 会把源码里的 {@code \n} 变成
 *       「空格+换行」，多出来的空格照样让判定失败（这是第一版修法漏掉的地方）</li>
 *   <li>代码块里的 {@code [b]} 等字面量不能被当作标签处理，块外的 {@code [color]}
 *       必须正常转成 {@code <span style="color:…">}</li>
 * </ol>
 */
public class PreviewCodeBreakTest {

    @Test
    public void colorBeforeCodeBlockStillRenders() {
        String out = BBCodeUtil.convertBBCodeToHtml(
                "天天[color=#B30000]向上[/color]\n[code]x = 1[/code]");
        assertTrue("颜色必须转成 span，而不是留成字面量",
                out.contains("<span style=\"color:#B30000\">向上</span>"));
        assertFalse("不该露出 [color= 字面量", out.contains("[color="));
        assertTrue("代码块还是要有 <pre>", out.contains("<pre"));
        assertTrue("同行内容之后必须补 <br>，否则 Html.fromHtml 抛段落边界异常",
                out.contains("<br><pre"));
    }

    @Test
    public void rawNewlineBeforeCodeStillGetsABreak() {
        // BBCode 里换行就是裸 \n，而 Html.fromHtml 会把源码里的 \n 变成「空格 + 换行」，
        // 多出来的那个空格照样让段落边界判定失败（实测过）—— 所以这里也必须补 <br>。
        // 代价只是预览里多一个空行，比整个 Html.fromHtml 崩掉、颜色全丢好得多。
        String out = BBCodeUtil.convertBBCodeToHtml("第一段\n\n[code]y = 2[/code]");
        assertTrue(out.contains("<br><pre"));
        assertTrue(out.contains("<pre"));
    }

    @Test
    public void tagsInsideCodeStayLiteral() {
        String out = BBCodeUtil.convertBBCodeToHtml("[code][b]不是加粗[/b][/code]");
        assertTrue("代码块里的 [b] 是字面量，必须转义显示", out.contains("[b]不是加粗[/b]"));
        assertFalse("不能被当成真的加粗", out.contains("<strong>不是加粗</strong>"));
    }

    @Test
    public void breakRuleMatchesHtmlFromHtmlBehaviour() {
        assertFalse("空前缀：safe", BBCodeUtil.needsBreakBefore(""));
        assertTrue("行内文字：must break", BBCodeUtil.needsBreakBefore("前面有字"));
        assertTrue("</span> 结尾：must break", BBCodeUtil.needsBreakBefore("<span>x</span>"));
        assertTrue("&nbsp; 结尾：must break", BBCodeUtil.needsBreakBefore("x&nbsp;"));
        assertTrue("裸换行：must break（Html 会加一个空格）", BBCodeUtil.needsBreakBefore("x\n"));
        assertFalse("<br> 结尾：safe", BBCodeUtil.needsBreakBefore("x<br>"));
        assertFalse("</p> 结尾：safe", BBCodeUtil.needsBreakBefore("<p>x</p>"));
        assertFalse("</div> 结尾：safe", BBCodeUtil.needsBreakBefore("<div>x</div>"));
        assertFalse("已有 <pre> 结尾：safe", BBCodeUtil.needsBreakBefore("<pre>x</pre>"));
    }

    @Test
    public void twoCodeBlocksBothGetBreaks() {
        String out = BBCodeUtil.convertBBCodeToHtml(
                "前言[code]A[/code]中间[code]B[/code]结尾");
        int first = out.indexOf("A");
        int second = out.indexOf("B");
        assertTrue(first > 0 && second > first);
        assertTrue("第二个代码块前同样要补 <br>", out.contains("<br><pre"));
    }
}
