package com.solosu.mtforum.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帖子 HTML 里的图片地址升级（主楼 + 评论区共用）。
 *
 * <p>移动版可能用 {@code mod=image&size=300x300} 显示 Discuz 附件缩略图；原图可能
 * 出现在 file / zoomfile / 懒加载属性，图片外层的下载链接，或签名 aid 属性里。
 * 只有确认原图 URL 时才替换 src；Discuz 原始 mod=attachment aid 带签名，不能
 * 从 mod=image 的裸数字 aid 直接拼出来。
 */
public final class PostImageHtml {

    private static final String[] REAL_ATTRS = {
            "file", "comiis_loadimages", "zoomfile",
            "data-original", "data-src", "data-file", "data-lazy-src"
    };
    private static final Pattern SIZE_QUERY = Pattern.compile(
            "[?&]size=(\\d+)[xX](\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AID_QUERY = Pattern.compile(
            "[?&]aid=([^&#\\s\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_EXTENSION = Pattern.compile(
            "(?i).*\\.(?:jpe?g|png|gif|webp|bmp|avif)(?:[?#].*)?$");
    private static final Pattern NO_THUMB_PARAM = Pattern.compile(
            "(?i)(?:[?&])nothumb(?:=|&|$)");
    private static final Pattern SIGNED_AID_PAYLOAD = Pattern.compile(
            "^\\d+\\|[0-9a-fA-F]{8}\\|\\d+\\|\\d+\\|\\d+$");

    private PostImageHtml() {}

    /** 带基地址的重载：HTML 里有相对路径的 img 时需要 */
    public static String upgradeThumbnailsToFull(String html, String baseUri) {
        if (isEmpty(html)) return html;
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(html, baseUri);
            for (org.jsoup.nodes.Element img : doc.select("img")) {
                String real = pickRealImageUrl(img);
                if (real == null) continue;
                String abs = ImageUrl.toAbsolute(real, baseUri);
                if (isEmpty(abs)) continue;
                if (!abs.equals(img.attr("src"))) img.attr("src", abs);
            }
            return doc.body().html();
        } catch (Throwable t) {
            return html;
        }
    }

    public static String upgradeThumbnailsToFull(String html) {
        return upgradeThumbnailsToFull(html, null);
    }

    /**
     * 从 img 元素挑出真实图片地址。
     *
     * <p>明确的原图属性优先。如果属性本身还是 Discuz 小尺寸缩略图，继续查看
     * 包裹图片的直接原图链接，再尝试使用元素上格式符合 Discuz 的签名 attachment aid。
     * 若只剩 {@code mod=image&size=300x300&aid=123} 这种裸数字地址，则保留原 URL：
     * Discuz 的 mod=attachment 要求服务端签名 aid，不能安全地由这个裸数字构造。
     */
    public static String pickRealImageUrl(org.jsoup.nodes.Element img) {
        if (img == null) return null;

        String discuzImageCandidate = null;
        for (String attr : REAL_ATTRS) {
            String value = usable(img.attr(attr));
            if (value == null) continue;
            if (isDiscuzImageRoute(value)) {
                // 即便是 500x480 这类较大变体，也先给外层原图链接一次机会。
                if (discuzImageCandidate == null) discuzImageCandidate = value;
                continue;
            }
            return ensureFullAttachmentUrl(value);
        }

        String linkedOriginal = linkedImageUrl(img);
        if (linkedOriginal != null) return linkedOriginal;

        String src = usable(img.attr("src"));
        if (src != null) {
            if (isGeneratedThumbnail(src)) {
                String attachment = originalAttachmentUrl(img, src);
                if (attachment != null) return attachment;
            }
            return ensureFullAttachmentUrl(src);
        }

        if (discuzImageCandidate != null) {
            if (isGeneratedThumbnail(discuzImageCandidate)) {
                String attachment = originalAttachmentUrl(img, discuzImageCandidate);
                if (attachment != null) return attachment;
            }
            return discuzImageCandidate;
        }
        return null;
    }

    private static String usable(String value) {
        if (isEmpty(value)) return null;
        String v = value.trim();
        if (!PostImageHtml.isUsableImageValue(v) || ImageUrl.isPlaceholder(v)) return null;
        return v;
    }

    /** 找包住图片的直接原图 / 附件链接，忽略一般帖子链接和缩略图链接。 */
    private static String linkedImageUrl(org.jsoup.nodes.Element img) {
        String src = img.attr("src").trim();
        org.jsoup.nodes.Element parent = img.parent();
        while (parent != null) {
            if ("a".equalsIgnoreCase(parent.normalName()) && parent.hasAttr("href")) {
                String href = parent.absUrl("href");
                if (isEmpty(href)) href = parent.attr("href").trim();
                if (!isEmpty(href) && !href.equals(src)
                        && !href.toLowerCase(Locale.ROOT).startsWith("javascript:")
                        && usable(href) != null
                        && !isGeneratedThumbnail(href)
                        && isDirectImageLink(href)) {
                    return ensureFullAttachmentUrl(href);
                }
            }
            parent = parent.parent();
        }
        return null;
    }

    private static boolean isDirectImageLink(String url) {
        String low = url.toLowerCase(Locale.ROOT);
        return low.contains("mod=attachment") || low.contains("mod=image")
                || IMAGE_EXTENSION.matcher(url).matches();
    }

    private static boolean isDiscuzImageRoute(String url) {
        return !isEmpty(url) && url.toLowerCase(Locale.ROOT).contains("mod=image")
                && SIZE_QUERY.matcher(url).find();
    }

    /** Discuz mod=image 的小尺寸派生图；较大的 500x480 路由可能已经返回原图。 */
    private static boolean isGeneratedThumbnail(String url) {
        if (!isDiscuzImageRoute(url)) return false;
        Matcher m = SIZE_QUERY.matcher(url);
        if (!m.find()) return false;
        try {
            int width = Integer.parseInt(m.group(1));
            int height = Integer.parseInt(m.group(2));
            return (width > 0 || height > 0)
                    && (width == 0 || width <= 400)
                    && (height == 0 || height <= 400);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * 只在 HTML 元素提供 Discuz 标准签名 aid 时才构造原图附件 URL；裸数字 aid
     * 只是 mod=image 的缩略图参数，不等同于 mod=attachment 所需的签名 aid。
     */
    private static String originalAttachmentUrl(org.jsoup.nodes.Element img, String imageUrl) {
        String aid = img.attr("aid").trim();
        if (isEmpty(aid)) aid = img.attr("data-aid").trim();
        if (!isDiscuzSignedAid(aid)) {
            Matcher m = AID_QUERY.matcher(imageUrl == null ? "" : imageUrl);
            aid = m.find() ? m.group(1).trim() : "";
        }
        String encodedAid = encodeDiscuzSignedAid(aid);
        if (encodedAid == null) return null;
        return "forum.php?mod=attachment&aid=" + encodedAid + "&nothumb=yes";
    }

    private static boolean isDiscuzSignedAid(String candidate) {
        return decodeDiscuzSignedAid(candidate) != null;
    }

    /** 返回 aid 的原始 Base64 文本；仅接受 Discuz 常见 aid|hash|time|uid|tid 签名结构。 */
    private static String decodeDiscuzSignedAid(String candidate) {
        if (isEmpty(candidate)) return null;
        try {
            // rawurlencode 会把 + / = 编成 %2B / %2F / %3D；保留原始 + 再解码。
            String decodedUrl = URLDecoder.decode(
                    candidate.replace("+", "%2B"), StandardCharsets.UTF_8.name());
            byte[] bytes = Base64.getDecoder().decode(decodedUrl);
            String payload = new String(bytes, StandardCharsets.UTF_8);
            return SIGNED_AID_PAYLOAD.matcher(payload).matches() ? decodedUrl : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String encodeDiscuzSignedAid(String candidate) {
        String decoded = decodeDiscuzSignedAid(candidate);
        if (decoded == null) return null;
        try {
            return URLEncoder.encode(decoded, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 图片附件 URL 默认可能返回缩略图；加 nothumb=yes 明确请求原始图片。 */
    private static String ensureFullAttachmentUrl(String url) {
        if (isEmpty(url) || !url.toLowerCase(Locale.ROOT).contains("mod=attachment")
                || NO_THUMB_PARAM.matcher(url).find()) {
            return url;
        }
        int fragmentAt = url.indexOf('#');
        String fragment = fragmentAt >= 0 ? url.substring(fragmentAt) : "";
        String withoutFragment = fragmentAt >= 0 ? url.substring(0, fragmentAt) : url;
        return withoutFragment + (withoutFragment.contains("?") ? "&" : "?")
                + "nothumb=yes" + fragment;
    }

    /** 这个属性值能不能当图片地址用 */
    public static boolean isUsableImageValue(String value) {
        if (isEmpty(value)) return false;
        String low = value.trim().toLowerCase(Locale.ROOT);
        if (low.matches("\\d+") || "true".equals(low) || "false".equals(low)
                || "lazy".equals(low) || low.startsWith("javascript:") || low.startsWith("data:")) {
            return false;
        }
        return low.startsWith("http://") || low.startsWith("https://") || low.startsWith("//")
                || low.startsWith("/") || low.startsWith("./") || low.contains("/") || low.contains(".");
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
