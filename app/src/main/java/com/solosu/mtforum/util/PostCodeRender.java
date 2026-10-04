package com.solosu.mtforum.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * build98: 把帖子正文里的代码块换成「App 自己的」代码卡片。
 *
 * <h3>为什么不再只靠 JS 猜站点结构</h3>
 * v5.14（build97）的做法是在 WebView 加载完之后，用 JS 去猜
 * {@code pre / div.comiis_blockcode} 这类站点结构，再往里塞一个复制按钮。
 * 用户反馈「正文里的代码还是不能直接复制」，而这条路要同时赌对三件事：
 * <ol>
 *   <li>站点结构猜对了（模板一换就废）</li>
 *   <li>{@code onPageFinished} 时那个块已经存在（懒加载/二次渲染就错过）</li>
 *   <li>塞进去的按钮不被站点自己的 CSS 盖住或裁掉</li>
 * </ol>
 * 与其继续赌，不如<b>在把 HTML 交给 WebView 之前，就把代码块换成我们自己生成的
 * 结构</b>：卡片、语言标签、复制按钮全是我们写死的 class 与样式，
 * 按钮就在卡片头部的正常文档流里 —— 不可能被盖住、不可能飘走、不可能没挂上。
 *
 * <h3>替换时保留什么</h3>
 * <ul>
 *   <li>代码原文：逐行取文本节点，{@code <br>} 还原成换行，
 *       {@code span.mt-ln}（build64 加的行号）整段丢掉，所以复制出来是干净代码</li>
 *   <li>语言标签：站点写在 {@code data-lang} / {@code .lang} 里，有就用，没有就显示「代码」</li>
 * </ul>
 *
 * <p>替换后的结构（{@link #MARK} / {@link #MARK_BODY} / {@link #MARK_BTN}）：
 * <pre>
 * &lt;div class="mt-code"&gt;
 *   &lt;div class="mt-code-hd"&gt;&lt;span class="mt-code-lang"&gt;java&lt;/span&gt;
 *     &lt;span class="mt-code-btn"&gt;复制&lt;/span&gt;&lt;/div&gt;
 *   &lt;pre class="mt-code-bd"&gt;public class A { ... }&lt;/pre&gt;
 * &lt;/div&gt;
 * </pre>
 */
public final class PostCodeRender {

    /** 卡片根节点 class */
    public static final String MARK = "mt-code";
    /** 代码正文 &lt;pre&gt; 的 class */
    public static final String MARK_BODY = "mt-code-bd";
    /** 复制按钮的 class（JS 就靠它找按钮） */
    public static final String MARK_BTN = "mt-code-btn";
    /** 卡片头部（点它也能复制，靶区比按钮大得多） */
    public static final String MARK_HD = "mt-code-hd";

    /**
     * 站点可能用来装代码块的元素。
     *
     * <p>故意<b>不含</b> {@code code}：正文里的行内 {@code &lt;code&gt;} 到处都是，
     * 把行内代码也做成整块卡片会把排版搞烂。行内代码保持原样。
     */
    private static final String SELECTOR =
            "pre,div.blockcode,div.blk_code,div.comiis_blockcode,div[class*=blockcode]";

    private PostCodeRender() {
    }

    /**
     * 替换正文里的所有代码块，返回新 HTML。解析失败一律返回原文（正文比代码块重要）。
     */
    public static String decorate(String html) {
        if (html == null || html.isEmpty()) return html;
        if (html.indexOf("<pre") < 0 && html.indexOf("blockcode") < 0
                && html.indexOf("blk_code") < 0) {
            return html; // 连块都没有，别白解析一遍
        }
        try {
            Document doc = Jsoup.parseBodyFragment(html);
            Elements hits = doc.select(SELECTOR);
            for (Element el : hits) {
                // 只处理最外层：模板里 .comiis_blockcode 常常套着同 class 的子 div，
                // 不排掉就会出现「卡片套卡片」
                if (hasMatchingAncestor(el)) continue;
                String code = rawText(el);
                if (isBlank(code)) continue;
                Element card = buildCard(code, detectLang(el));
                el.replaceWith(card);
            }
            return doc.body().html();
        } catch (Throwable t) {
            return html;
        }
    }

    private static boolean hasMatchingAncestor(Element el) {
        Element p = el.parent();
        while (p != null) {
            if (p.is(SELECTOR)) return true;
            p = p.parent();
        }
        return false;
    }

    /**
     * 取代码原文：{@code <br>} → 换行，{@code span.mt-ln} 行号丢掉，其余取文本节点原样。
     *
     * <p>不能简单用 {@code el.text()}：那会把所有空白折叠成单空格，代码的缩进全没；
     * 也不能用 {@code textContent}（{@code <br>} 不产生换行）—— 一行都拼成一行。
     */
    public static String rawText(Element el) {
        StringBuilder sb = new StringBuilder();
        collect(el, sb);
        String s = sb.toString();
        // 统一换行符，去掉首尾空白行
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceFirst("^\\n+", "").replaceFirst("\\n+$", "");
        return s;
    }

    private static void collect(Node n, StringBuilder sb) {
        for (Node child : n.childNodes()) {
            if (child instanceof TextNode) {
                sb.append(((TextNode) child).getWholeText());
            } else if (child instanceof Element) {
                Element e = (Element) child;
                String tag = e.tagName();
                if ("br".equals(tag)) {
                    sb.append('\n');
                } else if (e.hasClass("mt-ln")) {
                    continue; // build64 的行号：不进代码
                } else if ("script".equals(tag) || "style".equals(tag)) {
                    continue;
                } else {
                    collect(e, sb);
                }
            }
        }
    }

    /** 站点把语言写在 data-lang / class="lang-xxx" / 一个 .lang 子元素里，都没有就显示「代码」 */
    private static String detectLang(Element el) {
        try {
            String d = el.attr("data-lang");
            if (d != null && !d.trim().isEmpty()) return d.trim();
            for (String cls : el.classNames()) {
                String c = cls.toLowerCase(java.util.Locale.ROOT);
                if (c.startsWith("lang-") || c.startsWith("language-")) {
                    return cls.substring(cls.indexOf('-') + 1);
                }
                if (c.startsWith("brush:")) return cls.substring(6);
            }
            Element sub = el.selectFirst(".lang,.language,[class*=lang-]");
            if (sub != null) {
                String t = sub.text().trim();
                if (!t.isEmpty() && t.length() <= 16) return t;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static Element buildCard(String code, String lang) {
        Element card = new Element("div").addClass(MARK);
        Element hd = new Element("div").addClass(MARK_HD);
        hd.appendChild(new Element("span").addClass("mt-code-lang")
                .text(lang == null || lang.isEmpty() ? "代码" : lang));
        hd.appendChild(new Element("span").addClass(MARK_BTN).text("复制"));
        card.appendChild(hd);
        // 用 .text() 落文本：< > & 会被自动转义，代码里再怪的字符也不会破坏 HTML
        card.appendChild(new Element("pre").addClass(MARK_BODY).text(code));
        return card;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
