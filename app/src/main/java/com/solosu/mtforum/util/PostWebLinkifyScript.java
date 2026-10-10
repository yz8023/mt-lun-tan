package com.solosu.mtforum.util;

import java.util.Locale;

/**
 * 帖子正文（WebView 原帖渲染）里「纯文本链接自动识别」的注入脚本（build107）。
 *
 * <h3>它补的是什么洞</h3>
 * <p>v5.11（build92）起主楼默认走 {@code renderContentInWeb}：把站点下发的正文 HTML
 * 原样丢进 WebView，图才会落在作者插入的位置。但这条路上只有 {@code setupClickableLinks}
 * —— 那套给 TextView 用的 linkify（{@link PlainTextUrlPattern} +
 * {@code matcherLinkify}）只在 <b>非</b> WebView 分支被调用，见 ThreadDetailActivity
 * 里 {@code if (!webRender)} 的守卫。
 *
 * <p>结果就是：作者没用 {@code [url]} BBCode、直接把网址贴进正文时，Discuz 不会替他
 * 生成 {@code <a href>}，WebView 里就是一段普通文字 —— 点不动、也不像链接。用户报的
 * 「帖子内链接无法自动识别为可直接点击打开的超链接」正是这一条。
 *
 * <h3>为什么是 JS 而不是回退到 TextView</h3>
 * <p>回退到 TextView 等于把 build92「图停在原位」的成果退掉，正文排版会散。这里沿用
 * 正文已有的做法（{@link PostWebCodeCopyScript}、{@link PostWebImageScript}）：脚本是
 * 纯字符串、零 Android 依赖，能被 JVM 单测守住不变量。
 *
 * <h3>跟 Java 侧的一致性</h3>
 * <p>正则直接由 {@link PlainTextUrlPattern#URL_CHARS} 与
 * {@link PlainTextUrlPattern#TLD_ALTERNATION} 拼出，两边不可能漂移；Java 侧正则里的
 * 两个后行断言（{@code (?<![A-Z0-9_@])}、{@code (?<![.,;:!?])}）在 JS 里用
 * {@link #isStartAllowed} / {@link #trimTrailingPunctuation} 的等价逻辑手写，
 * 于是同一份正则源既能给 WebView，也能在 JVM 上跟 {@link PlainTextUrlPattern#WEB_URL}
 * 逐条比对（见 {@code PostWebLinkifyScriptTest}）。
 */
public final class PostWebLinkifyScript {

    /** 自动补出来的 {@code <a>} 打的 class：样式靠它，重跑时靠祖先判断天然去重。 */
    public static final String LINK_CLASS = "mt-autolink";

    /** 整篇正文最多补多少个链接。恶意长帖不该把主线程拖死。 */
    public static final int MAX_LINKS = 400;

    /**
     * 这些标签里的文本不参与识别：要么是代码/脚本（改了就坏），
     * 要么已经在 {@code <a>} 里（重复包一层会嵌套，且失去「只补一次」的语义）。
     */
    static final String[] SKIP_TAGS = {"A", "PRE", "CODE", "SCRIPT", "STYLE", "TEXTAREA", "NOSCRIPT"};

    /** ourselves 生成的代码卡片（{@link PostCodeRender}）里面的文本同样跳过。 */
    static final String SKIP_CLASS = PostCodeRender.MARK;

    /**
     * 与 {@link PlainTextUrlPattern#URL_CHARS} 等价的 JS 侧字符集。
     * 唯一差别是 Java 正则里写成 {@code \"} 的引号在这里写成裸 {@code "} —— 同义，
     * 但 JS 的 {@code u} 模式不接受 {@code \"}，裸写更稳。
     */
    private static final String JS_URL_CHARS = "[^\\s<>\"'`,;，。；：！？、“”‘’]+";

    private static final String JS_HOST_LABEL = "[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?";

    private static final String JS_BARE_DOMAIN = "(?:www\\.)?(?:" + JS_HOST_LABEL + "\\.)+"
            + "(?:" + PlainTextUrlPattern.TLD_ALTERNATION + ")"
            + "(?![A-Z0-9-])(?::[0-9]{1,5})?(?:[/?#]" + JS_URL_CHARS + ")?";

    private PostWebLinkifyScript() {
    }

    /**
     * 交给 WebView 的正则源（不带 flags）。
     *
     * <p>包内可见：单测把它用 {@link java.util.regex.Pattern} 编一遍，跟
     * {@link PlainTextUrlPattern#WEB_URL} 在同一批语料上比对，确保 JS 侧与 Java 侧
     * 认的是同一批链接。
     */
    static String urlRegexSource() {
        return "(?:(?:https?://|//)" + JS_URL_CHARS
                + "|www\\." + JS_URL_CHARS
                + "|" + JS_BARE_DOMAIN
                + ")";
    }

    /**
     * 前驱字符守卫，等价 Java 正则里的 {@code (?<![A-Z0-9_@])}。
     * 用来挡掉 {@code user@example.com} 里的 {@code example.com} —— 那是邮箱不是网址。
     */
    static boolean isStartAllowed(String text, int start) {
        if (text == null || start <= 0) return true;
        char c = text.charAt(start - 1);
        return !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '@');
    }

    /** 收尾标点，等价 Java 正则里的 {@code (?<![.,;:!?])}。 */
    static int trimTrailingPunctuation(String text, int start, int end) {
        int e = end;
        while (e > start) {
            char c = text.charAt(e - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?') {
                e--;
            } else {
                break;
            }
        }
        return e;
    }

    /**
     * 生成注入脚本，调用方直接 {@code WebView.evaluateJavascript(js, null)}。
     *
     * <p><b>幂等</b>：补出来的文字都进了 {@code <a>}，而 {@code <a>} 在跳过名单里，
     * 所以重复执行不会再动它们 —— 正文 WebView 每次 {@code onPageFinished} 都会跑，
     * 这一点必须成立。
     *
     * <p>只包文字节点，从不碰属性，所以不会把 {@code href}/{@code src} 改坏。
     */
    public static String js() {
        return "(function(){try{"
                // ---- 常量 ----
                + "var MAX=" + MAX_LINKS + ";"
                + "var CLS=" + q(LINK_CLASS) + ";"
                + "var SK=" + skipTagsJs() + ";"
                + "var MCODE=" + q(SKIP_CLASS) + ";"
                + "var RE=new RegExp(" + q(urlRegexSource()) + ",'gi');"
                // ---- 先把文本节点收集成数组，再改 DOM。
                // TreeWalker 是活的，边走边 replaceChild 会打乱遍历。 ----
                + "var root=document.body||document.documentElement;"
                + "if(!root)return;"
                + "var all=[],w=document.createTreeWalker(root,4,null,false),n;"
                + "while((n=w.nextNode()))all.push(n);"
                + "var made=0;"
                + "for(var i=0;i<all.length&&made<MAX;i++){"
                + "var t=all[i];"
                + "if(!t||t.nodeType!==3)continue;"
                + "var p=t.parentNode;if(!p)continue;"
                + "var s=t.nodeValue||'';if(!s)continue;"
                // ---- 祖先里只要有一个在跳过名单里，整段文本都不动 ----
                + "var a=p,bad=false,g=0;"
                + "while(a&&g++<64){"
                + "var gn=(a.nodeName||'').toUpperCase();"
                + "if(SK[gn]){bad=true;break;}"
                + "if(a.classList&&a.classList.contains(MCODE)){bad=true;break;}"
                + "if(a===root)break;"
                + "a=a.parentNode;}"
                + "if(bad)continue;"
                // ---- 先用 test 快速过一遍，绝大多数文本节点到此为止 ----
                + "RE.lastIndex=0;if(!RE.test(s))continue;RE.lastIndex=0;"
                + "var frag=document.createDocumentFragment(),last=0,cnt=0,m;"
                + "while((m=RE.exec(s))!==null){"
                + "var st=m.index,en=st+m[0].length;"
                // 前驱字符守卫（代替后行断言）。被挡掉时只前进一格 ——
                // 和 Java 正则引擎「该位置失败就从下一格再试」的推进方式一致，
                // 两边才不会在同一段文字上认出不同的链接。
                + "if(st>0){var pc=s.charAt(st-1);"
                + "if(/[A-Za-z0-9_@]/.test(pc)){RE.lastIndex=st+1;continue;}}"
                // 收尾标点（代替后行断言）
                + "while(en>st){var lc=s.charAt(en-1);"
                + "if(lc==='.'||lc===','||lc===';'||lc===':'||lc==='!'||lc==='?')en--;else break;}"
                + "if(en<=st){RE.lastIndex=st+1;continue;}"
                + "if(made>=MAX)break;"
                + "var raw=s.slice(st,en),href=raw;"
                // 补协议：//host 补 https:，裸域名补 https://；已有 :// 的原样保留
                + "if(href.slice(0,2)==='//')href='https:'+href;"
                + "else if(!/^[A-Za-z][A-Za-z0-9+.\\-]*:\\/\\//.test(href))href='https://'+href;"
                + "if(!/^https?:\\/\\//i.test(href))continue;"
                + "if(st>last)frag.appendChild(document.createTextNode(s.slice(last,st)));"
                + "var el=document.createElement('a');"
                + "el.setAttribute('href',href);"
                + "el.setAttribute('class',CLS);"
                + "el.setAttribute('rel','noopener noreferrer');"
                // 不设 target：留在当前 WebView 里导航，才轮得到
                // shouldOverrideUrlLoading 把链接交给 LinkRouter 处理
                + "el.appendChild(document.createTextNode(raw));"
                + "frag.appendChild(el);"
                + "last=en;made++;cnt++;}"
                + "if(cnt>0){"
                + "if(last<s.length)frag.appendChild(document.createTextNode(s.slice(last)));"
                + "p.replaceChild(frag,t);}"
                + "}"
                + "}catch(x){}})();";
    }

    /** {@code {A:1,PRE:1,...}} —— 用对象当集合，比反复 indexOf 快也更好读。 */
    private static String skipTagsJs() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < SKIP_TAGS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(SKIP_TAGS[i]).append(":1");
        }
        return sb.append('}').toString();
    }

    /** 生成 JS 单引号字符串字面量（转义反斜杠、引号、尖括号与控制字符）。 */
    private static String q(String value) {
        if (value == null) return "''";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '<': sb.append("\\x3c"); break;
                case '>': sb.append("\\x3e"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\x%02x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('\'');
        return sb.toString();
    }
}
