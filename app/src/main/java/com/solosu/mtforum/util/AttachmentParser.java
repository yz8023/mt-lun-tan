package com.solosu.mtforum.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帖子附件解析（build72 新增）。
 *
 * <p>DOM 结构参考 <a href="https://github.com/Forinxy/mtbbs_app">Forinxy/mtbbs_app</a>
 * 的 {@code core/parser/html2bbcode.dart}（我这边 IP 被论坛封了抓不到登录后的页面，
 * 结构是从那个项目的解析代码里对出来的）：
 *
 * <pre>
 * 普通附件：
 *   &lt;ignore_js_op&gt;
 *     &lt;img src="...filetype/xxx.gif"&gt;
 *     &lt;span id="attach_N"&gt;
 *       &lt;a href="forum.php?mod=attachment&amp;aid=XXX"&gt;name.ext&lt;/a&gt;
 *       &lt;em class="xg1"&gt;(2.3 MB, 下载次数: 12)&lt;/em&gt;
 *     &lt;/span&gt;
 *   &lt;/ignore_js_op&gt;
 *
 * 图片附件：
 *   &lt;ignore_js_op&gt;
 *     &lt;img class="zoom" aid="XXX" src="..."&gt;
 *     &lt;div class="aimg_tip"&gt;&lt;strong&gt;名字&lt;/strong&gt;&lt;em class="xg1"&gt;(大小, 下载次数: N)&lt;/em&gt;
 *       &lt;p class="xg1 y"&gt;时间 上传&lt;/p&gt;&lt;/div&gt;
 *   &lt;/ignore_js_op&gt;
 * </pre>
 *
 * <p>下载地址统一是 {@code forum.php?mod=attachment&aid={aid}}。
 * <b>点击才下载</b>，解析阶段不发任何请求，不会消耗金币。
 */
public final class AttachmentParser {

    public static final String BASE = "https://bbs.binmt.cc/";

    public static class Attachment {
        public String aid = "";
        public String name = "";
        public String size = "";
        public String downloads = "";
        public String uploadTime = "";
        /** 图片附件才有：可直接预览的图片地址 */
        public String imageUrl = "";

        public boolean isImage() {
            return imageUrl != null && !imageUrl.isEmpty();
        }

        /** 下载地址（点击才访问，避免误扣金币） */
        public String downloadUrl() {
            return BASE + "forum.php?mod=attachment&aid=" + aid;
        }

        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (!size.isEmpty()) sb.append(size);
            if (!downloads.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("下载 ").append(downloads).append(" 次");
            }
            if (!uploadTime.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(uploadTime);
            }
            return sb.toString();
        }
    }

    private static final Pattern P_AID = Pattern.compile("aid=([^&\"']+)");
    private static final Pattern P_SIZE = Pattern.compile("\\(([^,)]+)");
    private static final Pattern P_DL = Pattern.compile("下载次数[：:]\\s*(\\d+)");

    private AttachmentParser() {
    }

    /** 从帖子正文 HTML 里抽出所有附件 */
    public static List<Attachment> parse(String html) {
        List<Attachment> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        if (!html.contains("mod=attachment") && !html.contains("img.zoom")
                && !html.contains("aimg_tip") && !html.contains("ignore_js_op")) {
            return out;
        }
        try {
            Document doc = Jsoup.parseBodyFragment(html);
            // ignore_js_op 是 Discuz 包装附件的标准容器；没有就退回全局找链接
            org.jsoup.select.Elements wrappers = doc.select("ignore_js_op");
            if (wrappers.isEmpty()) wrappers = doc.select("body");

            for (Element w : wrappers) {
                // ① 图片附件
                for (Element zoom : w.select("img.zoom[aid]")) {
                    Attachment a = new Attachment();
                    a.aid = zoom.attr("aid").trim();
                    a.imageUrl = absolute(firstNonEmpty(
                            zoom.attr("src"), zoom.attr("file"), zoom.attr("zoomfile")));
                    Element tip = w.selectFirst(".aimg_tip");
                    if (tip != null) {
                        Element strong = tip.selectFirst("strong");
                        if (strong != null) a.name = strong.text().trim();
                        Element em = tip.selectFirst("em.xg1");
                        if (em != null) fillSizeAndDownloads(a, em.text());
                        Element t = tip.selectFirst("p.xg1.y");
                        if (t != null) {
                            a.uploadTime = t.text().replaceAll("上传\\s*$", "").trim();
                        }
                    }
                    if (a.name.isEmpty()) {
                        Element link = w.selectFirst("a[href*=mod=attachment]");
                        if (link != null) a.name = link.text().trim();
                    }
                    if (a.name.isEmpty()) a.name = "图片附件";
                    if (!a.aid.isEmpty()) out.add(a);
                }

                // ② 普通文件附件
                for (Element link : w.select("a[href*=mod=attachment]")) {
                    String href = link.attr("href");
                    Matcher m = P_AID.matcher(href);
                    if (!m.find()) continue;
                    String aid = m.group(1);
                    if (contains(out, aid)) continue;      // 图片附件里已经收过
                    Attachment a = new Attachment();
                    a.aid = aid;
                    a.name = link.text().trim();
                    if (a.name.isEmpty()) a.name = "附件 " + aid;
                    Element em = link.parent() == null ? null
                            : link.parent().selectFirst("em.xg1");
                    if (em != null) fillSizeAndDownloads(a, em.text());
                    out.add(a);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void fillSizeAndDownloads(Attachment a, String text) {
        if (text == null) return;
        Matcher sm = P_SIZE.matcher(text);
        if (sm.find()) a.size = sm.group(1).trim();
        Matcher dm = P_DL.matcher(text);
        if (dm.find()) a.downloads = dm.group(1);
    }

    private static boolean contains(List<Attachment> list, String aid) {
        for (Attachment a : list) {
            if (a.aid.equals(aid)) return true;
        }
        return false;
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return "";
    }

    private static String absolute(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http")) return url;
        if (url.startsWith("//")) return "https:" + url;
        return BASE + url.replaceFirst("^/", "");
    }
}
