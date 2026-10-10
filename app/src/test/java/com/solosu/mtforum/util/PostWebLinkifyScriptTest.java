package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * build107：正文 WebView「纯文本链接自动识别」脚本的 JVM 单测。
 *
 * <p>守三条不变量：
 * <ol>
 *   <li><b>两边同一套规则</b>：脚本里的正则与 {@link PlainTextUrlPattern#WEB_URL}
 *       在同一批语料上必须认出完全一样的链接 —— 用户报的正是「TextView 里是链接、
 *       WebView 里不是」这种不一致；</li>
 *   <li><b>该跳过的都跳过</b>：{@code <a>}（不重复包）、{@code <pre>}/{@code <code>}
 *       （代码里的网址不能被改成链接）、我们自己的 {@code .mt-code} 卡片；</li>
 *   <li><b>不自己开窗口</b>：生成的 {@code <a>} 不设 target，导航才会交给
 *       {@code shouldOverrideUrlLoading} → LinkRouter（应用内/系统浏览器设置）。</li>
 * </ol>
 */
public class PostWebLinkifyScriptTest {

    private static final Pattern JS_URL = Pattern.compile(
            PostWebLinkifyScript.urlRegexSource(), Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- 规则一致性

    @Test
    public void reusesTheSameUrlCharacterClassAsTheJavaPattern() {
        // Java 侧写成 \" 的引号在 JS 侧裸写，两者同义；这里按语义比较，不比字面
        String javaChars = PlainTextUrlPattern.URL_CHARS.replace("\\\"", "\"");
        assertEquals("JS 侧字符集必须与 Java 侧等价",
                javaChars, jsUrlCharsFromScript());
    }

    @Test
    public void reusesTheSameTldListAsTheJavaPattern() {
        String js = PostWebLinkifyScript.js();
        assertTrue("脚本必须带上同一份 TLD 候选表",
                js.contains(PlainTextUrlPattern.TLD_ALTERNATION));
    }

    @Test
    public void findsExactlyTheSameLinksAsTheJavaTextViewPath() {
        String[] corpus = {
                "访问https://a.example.cn/1，或 www.example.com/test；还有example.org。",
                "打开 https://example.com/path.",
                "打开 https://example.com/path。",
                "邮件 user@example.com 已发送",
                "文件名 readme.html 已更新",
                "https://bbs.binmt.cc/thread-160198-1-1.html",
                "下载 https://pan.baidu.com/s/1abc 谢谢",
                "HTTP://EXAMPLE.COM/A",
                "见 https://example.com/a) 后",
                "站点 https://bbs.binmt.cc/forum.php?mod=viewthread&tid=1 链接",
                "//bbs.binmt.cc/thread-1-1-1.html",
                "裸域名 bbs.binmt.cc/forum.php 也要能点",
                "带端口 example.com:8080/x 呢",
                "（见 https://example.com/a）后",
                "无链接的普通一句话。",
                "http://a.cn http://b.cn 两个",
        };
        for (String text : corpus) {
            assertEquals("同一段文字两边必须认出同样的链接：\n" + text,
                    javaLinks(text), jsLinks(text));
        }
    }

    @Test
    public void excludedCasesStillProduceNoLink() {
        assertTrue(javaLinks("邮件 user@example.com 已发送").isEmpty());
        assertTrue(jsLinks("邮件 user@example.com 已发送").isEmpty());
        assertTrue(jsLinks("文件名 readme.html 已更新").isEmpty());
    }

    // ---------------------------------------------------------------- 结构不变量

    @Test
    public void skipsAnchorsCodeBlocksAndScripts() {
        String js = PostWebLinkifyScript.js();
        for (String tag : PostWebLinkifyScript.SKIP_TAGS) {
            assertTrue("必须跳过 <" + tag + "> 里的文本", js.contains(tag + ":1"));
        }
        assertTrue("必须跳过我们自己的代码卡片", js.contains(PostCodeRender.MARK));
        // <a> 在跳过名单里 = 幂等：补出来的链接下次不会被再包一层
        assertTrue("跳过 <a> 才幂等", js.contains("A:1"));
    }

    @Test
    public void collectsTextNodesBeforeMutatingTheDom() {
        String js = PostWebLinkifyScript.js();
        assertTrue("必须先收集再改 DOM，否则 TreeWalker 遍历被打乱",
                js.contains("createTreeWalker"));
        assertTrue(js.contains("all.push(n)"));
    }

    @Test
    public void generatedAnchorsStayInTheCurrentWebView() {
        String js = PostWebLinkifyScript.js();
        assertFalse("设了 target 就绕过 shouldOverrideUrlLoading 了",
                js.contains("setAttribute('target'"));
        assertFalse(js.contains("setAttribute(\"target\""));
        assertTrue(js.contains("rel','noopener noreferrer'"));
        assertTrue(js.contains(PostWebLinkifyScript.LINK_CLASS));
    }

    @Test
    public void completesBareDomainsWithHttpsOnly() {
        String js = PostWebLinkifyScript.js();
        assertTrue("协议相对地址补 https:", js.contains("href='https:'+href"));
        assertTrue("裸域名补 https://", js.contains("href='https://'+href"));
        // 只放行 http(s)，别的 scheme 一律不生成链接
        assertTrue(js.contains("^https?:\\/\\/"));
    }

    @Test
    public void capsTheNumberOfInjectedLinks() {
        String js = PostWebLinkifyScript.js();
        assertTrue("必须有条数上限，长帖不该拖死主线程",
                js.contains("MAX=" + PostWebLinkifyScript.MAX_LINKS));
        assertTrue(js.contains("made<MAX"));
        assertTrue(js.contains("made>=MAX"));
    }

    // ---------------------------------------------------------------- 辅助

    /** Java 侧（TextView 现在走的路）认出的链接。 */
    private static List<String> javaLinks(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = PlainTextUrlPattern.WEB_URL.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 完全按脚本里的循环复刻一遍：正则 + 两个守卫 + 逐格推进。 */
    private static List<String> jsLinks(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = JS_URL.matcher(text);
        int pos = 0;
        while (pos <= text.length()) {
            m.region(pos, text.length());
            if (!m.find()) break;
            int st = m.start();
            int en = m.end();
            if (!PostWebLinkifyScript.isStartAllowed(text, st)) {
                pos = st + 1;
                continue;
            }
            en = PostWebLinkifyScript.trimTrailingPunctuation(text, st, en);
            if (en <= st) {
                pos = st + 1;
                continue;
            }
            out.add(text.substring(st, en));
            pos = en;
        }
        return out;
    }

    /** 从脚本里把 JS 侧字符集原样抠出来，跟 Java 侧比。 */
    private static String jsUrlCharsFromScript() {
        String js = PostWebLinkifyScript.js();
        int i = js.indexOf("new RegExp('");
        assertTrue("脚本里应能找到 RegExp 字面量", i >= 0);
        String body = js.substring(i + "new RegExp('".length());
        int end = body.indexOf("','gi')");
        assertTrue(end > 0);
        String regex = unquote(body.substring(0, end));
        // 第一段就是 URL_CHARS： (?:(?:https?://|//)URL_CHARS|...
        int s = regex.indexOf("|//)") + "|//)".length();
        int e = regex.indexOf("|www\\.");
        assertTrue("应能截出 URL_CHARS 段", s > 0 && e > s);
        return regex.substring(s, e);
    }

    /** 还原 q() 的转义（只还原这里会用到的几种）。 */
    private static String unquote(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(i + 1);
                if (n == '\\') { sb.append('\\'); i++; }
                else if (n == 'x' && i + 3 < value.length()) {
                    sb.append((char) Integer.parseInt(value.substring(i + 2, i + 4), 16));
                    i += 3;
                } else { sb.append(n); i++; }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
