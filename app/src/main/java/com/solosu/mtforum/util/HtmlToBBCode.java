package com.solosu.mtforum.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * 帖子 HTML → BBCode（build65 新增）。
 *
 * <p>实现思路来自用户提供的油猴脚本「mt手机代码快复制功能」(hw1020) 里的
 * {@code html2bbcode()}，但那边是一串正则替换，顺序敏感、嵌套一多就会串味
 * （比如 {@code <font color>} 里套 {@code <b>} 时闭合标签容易错配）。
 * 这里改成 Jsoup 递归遍历 DOM，天然处理嵌套，也不用为了躲标签去做占位符替换。
 *
 * <p>为什么要 BBCode 而不是可见文本：复制下来的内容要能直接<b>转发 / 引用 / 二次发帖</b>，
 * 纯文本会把代码块、图片、链接、排版全丢掉。
 *
 * <p>纯 Java（只依赖 Jsoup），可跑 JVM 单元测试。
 */
public final class HtmlToBBCode {

    private HtmlToBBCode() {
    }

    /** 把帖子正文 HTML 转成 BBCode */
    public static String convert(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            Document doc = Jsoup.parseBodyFragment(html);
            StringBuilder sb = new StringBuilder();
            for (Node child : doc.body().childNodes()) {
                walk(child, sb);
            }
            return tidy(sb.toString());
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== 遍历 ====================

    private static void walk(Node node, StringBuilder out) {
        if (node instanceof TextNode) {
            out.append(((TextNode) node).getWholeText());
            return;
        }
        if (!(node instanceof Element)) return;

        Element el = (Element) node;
        String tag = el.tagName().toLowerCase();

        // ---- 代码块：优先级最高，内部原样保留 ----
        if (isCodeBlock(el)) {
            BBCodeUtil.Extracted ex = BBCodeUtil.extractCodeBlocks(el.outerHtml());
            if (ex.hasBlocks()) {
                BBCodeUtil.CodeBlock b = ex.blocks.get(0);
                out.append("\n[code]\n").append(b.code).append("\n[/code]\n");
                return;
            }
        }

        switch (tag) {
            case "br":
                out.append('\n');
                return;
            case "hr":
                out.append("\n[hr]\n");
                return;
            case "img": {
                String src = firstNonEmpty(el.attr("src"), el.attr("data-original"), el.attr("file"));
                if (!src.isEmpty()) {
                    String w = el.attr("width");
                    String h = el.attr("height");
                    if (!w.isEmpty() || !h.isEmpty()) {
                        out.append("[img=").append(w).append(',').append(h).append(']')
                           .append(src).append("[/img]");
                    } else {
                        out.append("[img]").append(src).append("[/img]");
                    }
                }
                return;
            }
            case "a": {
                String href = el.attr("href").trim();
                String text = childrenToBB(el).trim();
                if (href.isEmpty()) {
                    out.append(text);
                } else if (href.startsWith("mailto:")) {
                    String mail = href.substring(7);
                    out.append(mail.equals(text)
                            ? "[email]" + mail + "[/email]"
                            : "[email=" + mail + "]" + text + "[/email]");
                } else if (href.contains("wpa.qq.com")) {
                    String uin = between(href, "uin=", "&");
                    out.append(uin.isEmpty() ? text : "[qq]" + uin + "[/qq]");
                } else {
                    out.append(href.equals(text)
                            ? "[url]" + href + "[/url]"
                            : "[url=" + href + "]" + text + "[/url]");
                }
                return;
            }
            case "b":
            case "strong":
                wrap(el, out, "b");
                return;
            case "i":
            case "em":
                wrap(el, out, "i");
                return;
            case "u":
                wrap(el, out, "u");
                return;
            case "s":
            case "strike":
            case "del":
                wrap(el, out, "s");
                return;
            case "font": {
                String inner = childrenToBB(el);
                String color = el.attr("color").trim();
                String size = el.attr("size").trim();
                String back = styleValue(el.attr("style"), "background-color");
                if (!back.isEmpty()) inner = "[backcolor=" + back + "]" + inner + "[/backcolor]";
                if (!size.isEmpty()) inner = "[size=" + size + "]" + inner + "[/size]";
                if (!color.isEmpty()) inner = "[color=" + color + "]" + inner + "[/color]";
                out.append(inner);
                return;
            }
            case "table":
                out.append("\n[table]").append(childrenToBB(el)).append("[/table]\n");
                return;
            case "tr":
                out.append("\n[tr]").append(childrenToBB(el)).append("[/tr]");
                return;
            case "td":
            case "th":
                out.append("[td]").append(childrenToBB(el)).append("[/td]");
                return;
            case "ul":
            case "ol": {
                String type = el.attr("type").trim();
                out.append("\n[list").append(type.isEmpty() ? "" : "=" + type).append(']');
                for (Element li : el.children()) {
                    if (!"li".equalsIgnoreCase(li.tagName())) continue;
                    out.append("\n[*]").append(childrenToBB(li).trim());
                }
                out.append("\n[/list]\n");
                return;
            }
            case "blockquote":
                out.append("\n[quote]").append(childrenToBB(el).trim()).append("[/quote]\n");
                return;
            case "h1": case "h2": case "h3":
            case "h4": case "h5": case "h6":
                out.append('\n').append(childrenToBB(el).trim()).append('\n');
                return;
            case "p":
            case "div": {
                String align = el.attr("align").trim();
                String cls = el.className();
                String inner = childrenToBB(el);
                if (cls.contains("comiis_quote")) {
                    // 论坛的引用 / 隐藏内容都套在 comiis_quote 上
                    String t = inner.trim();
                    if (t.startsWith("本帖隐藏的内容")) {
                        out.append("\n[hide]").append(t.replaceFirst("^本帖隐藏的内容[:：]?\\s*", ""))
                           .append("[/hide]\n");
                    } else {
                        out.append("\n[quote]").append(t).append("[/quote]\n");
                    }
                    return;
                }
                if (!align.isEmpty()) {
                    out.append("\n[align=").append(align).append(']').append(inner).append("[/align]\n");
                    return;
                }
                out.append(inner);
                if (!inner.endsWith("\n")) out.append('\n');
                return;
            }
            case "script":
            case "style":
                return;
            default:
                out.append(childrenToBB(el));
        }
    }

    // ==================== 工具 ====================

    private static boolean isCodeBlock(Element el) {
        String tag = el.tagName().toLowerCase();
        String cls = el.className();
        if ("pre".equals(tag)) return true;
        return ("div".equals(tag)) && (cls.contains("blockcode"));
    }

    private static void wrap(Element el, StringBuilder out, String bb) {
        String inner = childrenToBB(el);
        if (inner.trim().isEmpty()) {
            out.append(inner);
            return;
        }
        out.append('[').append(bb).append(']').append(inner).append("[/").append(bb).append(']');
    }

    private static String childrenToBB(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Node c : el.childNodes()) walk(c, sb);
        return sb.toString();
    }

    private static String styleValue(String style, String key) {
        if (style == null || style.isEmpty()) return "";
        for (String part : style.split(";")) {
            int i = part.indexOf(':');
            if (i <= 0) continue;
            if (part.substring(0, i).trim().equalsIgnoreCase(key)) {
                return part.substring(i + 1).trim();
            }
        }
        return "";
    }

    private static String between(String s, String start, String end) {
        int a = s.indexOf(start);
        if (a < 0) return "";
        a += start.length();
        int b = s.indexOf(end, a);
        return b < 0 ? s.substring(a) : s.substring(a, b);
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return "";
    }

    /** 收尾：合并多余空行、去掉不换行空格、首尾留白 */
    private static String tidy(String s) {
        return s.replace('\u00a0', ' ')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
