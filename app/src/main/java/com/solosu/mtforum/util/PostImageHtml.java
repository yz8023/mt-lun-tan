package com.solosu.mtforum.util;

import android.text.TextUtils;

import java.util.Locale;

/**
 * build97: 帖子 HTML 里的图片地址升级（主楼 + 评论区共用）。
 *
 * <p>站点（克米 mobile 模板）下发的正文/回帖 HTML 里，配图 src 常是懒加载占位
 * 或者缩略图地址，真实地址放在 {@code file} / {@code comiis_loadimages} /
 * {@code zoomfile} / {@code data-original} 等属性里。不替换的话显示出来就是
 * 一小块糊图。
 *
 * <p>以前这套逻辑是 {@code ThreadDetailActivity} 的私有静态方法，只有主楼正文
 * 用得上 —— 评论区（{@code ReplyAdapter}）从来没调，于是回帖里的图一直是缩略图。
 * 用户报的就是「评论区图片无法显示原图（像网页那样）」。
 */
public final class PostImageHtml {

    private PostImageHtml() {}

    /** 带基地址的重载：HTML 里有相对路径的 img 时需要 */
    public static String upgradeThumbnailsToFull(String html, String baseUri) {
        if (TextUtils.isEmpty(html)) return html;
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(html, baseUri);
            for (org.jsoup.nodes.Element img : doc.select("img")) {
                String real = pickRealImageUrl(img);
                if (real == null) continue;
                String abs = ImageUrl.toAbsolute(real, baseUri);
                if (TextUtils.isEmpty(abs)) continue;
                if (!abs.equals(img.attr("src"))) img.attr("src", abs);
            }
            return doc.body().html();
        } catch (Throwable t) {
            return html;
        }
    }

    /** 懒加载真图属性，按优先级 */
    private static final String[] REAL_ATTRS = {
            "file", "comiis_loadimages", "zoomfile",
            "data-original", "data-src", "data-file", "src"
    };

    /**
     * 把 HTML 里所有 {@code <img>} 的 src 换成真实大图地址。
     *
     * @return 替换后的 HTML；解析失败时原样返回，绝不让帖子变空白
     */
    public static String upgradeThumbnailsToFull(String html) {
        if (TextUtils.isEmpty(html)) return html;
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(html);
            for (org.jsoup.nodes.Element img : doc.select("img")) {
                String real = pickRealImageUrl(img);
                if (real == null) continue;
                String abs = ImageUrl.toAbsolute(real);
                if (TextUtils.isEmpty(abs)) continue;
                if (!abs.equals(img.attr("src"))) img.attr("src", abs);
            }
            return doc.body().html();
        } catch (Throwable t) {
            return html;
        }
    }

    /** 从 img 元素挑出真实图片地址 */
    public static String pickRealImageUrl(org.jsoup.nodes.Element img) {
        for (String a : REAL_ATTRS) {
            String v = img.attr(a);
            if (v == null) continue;
            v = v.trim();
            if (!isUsableImageValue(v) || ImageUrl.isPlaceholder(v)) continue;
            return v;
        }
        return null;
    }

    /** 这个属性值能不能当图片地址用 */
    public static boolean isUsableImageValue(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String low = value.trim().toLowerCase(Locale.ROOT);
        if (low.matches("\\d+") || "true".equals(low) || "false".equals(low)
                || "lazy".equals(low) || low.startsWith("javascript:") || low.startsWith("data:")) {
            return false;
        }
        return low.startsWith("http://") || low.startsWith("https://") || low.startsWith("//")
                || low.startsWith("/") || low.startsWith("./") || low.contains("/") || low.contains(".");
    }
}
