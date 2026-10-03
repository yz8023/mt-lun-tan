package com.solosu.mtforum.util;


import com.solosu.mtforum.network.HttpClient;

import org.jsoup.nodes.Element;

import java.util.Arrays;
import java.util.List;

/**
 * 正文 / 评论图片地址的<b>唯一</b>解析入口。
 *
 * <p>原来项目里有三份属性候选表、三种不同顺序，是「有的帖子图正常、有的异常」的直接原因：
 * <ul>
 *   <li>{@code ForumParser.extractAndSeparateImages}：file → comiis_loadimages → data-original → data-src → data-file → src</li>
 *   <li>{@code ThreadDetailActivity.pickRealImageUrl}：file → comiis_loadimages → zoomfile → data-original → data-src → data-file → src</li>
 *   <li>{@code ForumParser.populateThreadImages}：comiis_loadimages → data-original → data-src → data-file → file → data-lazy-src → src</li>
 *   <li>{@code ReplyAdapter.extractImagesFromHtml}：<b>只读 src</b> ← 评论区图片异常的根因</li>
 * </ul>
 *
 * <p>统一成一份，并补齐两件原来漏掉的事：
 * <ol>
 *   <li><b>占位图黑名单</b>：Comiis 的懒加载占位是 {@code imageloading.gif}，原来的
 *       {@code isPlaceholderImage} 只认 none/blank/grey.gif，于是占位图被当真图加载，
 *       表现就是「一个永远加载不出来的空白块」。</li>
 *   <li><b>相对路径按文档 base 解析</b>（Discuz 页面有 {@code <base href>}），
 *       不再一律拼站点根。</li>
 * </ol>
 */
public final class ImageUrl {

    /**
     * 空串判定。刻意自己实现而不引 {@code android.text.TextUtils} ——
     * 这三个类是纯字符串逻辑，不碰任何 android.* 就能在 JVM 上跑单元测试
     * （本项目的 {@code SignParser} 从 build60 起就是同一条约定）。
     */
    private static boolean isBlank(CharSequence s) {
        return s == null || s.length() == 0;
    }

    /** 懒加载 / 原图属性的统一候选顺序（对齐 qcxs/mtbbs_app 的 _resolveImageSrc） */
    private static final List<String> ATTRS = Arrays.asList(
            "file",             // PC 模板 zoom 图的原图
            "comiis_loadimages",// Comiis 模板懒加载真实地址
            "zoomfile",
            "data-original",
            "data-src",
            "data-file",
            "data-lazy-src",
            "src"               // 兜底
    );

    /** 占位图名单（小写包含即判定为占位） */
    private static final List<String> PLACEHOLDERS = Arrays.asList(
            "none.gif", "none.png",
            "nophoto.gif", "nophoto.png",
            "blank.gif", "grey.gif", "gray.gif",
            "loading.gif", "loading.png",
            "imageloading.gif", "imageloading.png",
            "/image/common/none",
            "static/image/common/blank"
    );

    private ImageUrl() {
    }

    /** 是否是占位图 */
    public static boolean isPlaceholder(String url) {
        if (isBlank(url)) return true;
        String low = url.trim().toLowerCase();
        if (low.startsWith("data:")) return true;
        for (String p : PLACEHOLDERS) {
            if (low.contains(p)) return true;
        }
        return false;
    }

    /**
     * 从 {@code <img>} 元素挑真实图片地址。
     *
     * @return 可直接使用的地址；挑不到返回 null（调用方应删掉这个 img，
     *         留着就是个永远加载不出来的空白块）
     */
    public static String realUrl(Element img) {
        if (img == null) return null;
        for (String a : ATTRS) {
            if (!img.hasAttr(a)) continue;
            String v = img.attr(a);
            if (isBlank(v)) continue;
            v = v.trim();
            if (v.isEmpty() || isPlaceholder(v)) continue;
            return v;
        }
        return null;
    }

    /** 把真实地址补全为绝对地址（协议相对 / 根相对 / 文档相对） */
    public static String toAbsolute(String url) {
        return toAbsolute(url, null);
    }

    /**
     * @param base 文档 base（Discuz 页面里的 {@code <base href="https://bbs.binmt.cc/">}）；
     *             传 null 时用站点根
     */
    public static String toAbsolute(String url, String base) {
        if (isBlank(url)) return "";
        String u = url.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("//")) return "https:" + u;
        String root = isBlank(base)
                ? HttpClient.BASE_URL
                : (base.endsWith("/") ? base : base + "/");
        if (u.startsWith("/")) return root + u.substring(1);
        if (u.startsWith("./")) return root + u.substring(2);
        return root + u;
    }

    /** 一步到位：挑真实地址 + 补全；挑不到返回 null */
    public static String resolve(Element img, String base) {
        String real = realUrl(img);
        if (real == null) return null;
        return toAbsolute(real, base);
    }

    /** 一步到位（无 base） */
    public static String resolve(Element img) {
        return resolve(img, null);
    }

    /**
     * 该 URL 是否是「帖子正文 / 评论里的配图」而不是表情、图标、头像。
     *
     * <p>与旧 {@code ForumParser.isPostImageUrl} 的区别：旧版用一串松垮的子串
     * （{@code icon}、{@code face}、{@code stamp}、{@code magic}、{@code mini}…）
     * 命中即杀。<b>实测这些子串会命中真实附件</b>：
     * 本站正文配图就是 {@code /data/attachment/common/c8/common_2_icon.png}
     * 这种带 {@code _icon} 后缀的 Discuz 缩略图，旧版把它们全杀了，
     * 表现就是"部分帖子的图片整片消失"。
     * 这里只排除明确的目录（{@code /static/image/}、{@code /smiley/}）和头像接口。
     */
    public static boolean isPostImage(String url) {
        if (isBlank(url)) return false;
        String low = url.toLowerCase();
        if (isPlaceholder(low)) return false;
        // 表情
        if (low.contains("/static/image/smiley") || low.contains("/smiley/")) return false;
        if (low.contains("emoticon")) return false;
        // 勋章 / 魔法道具 / 表情 / 站点图标 —— 全在 /static/image/ 下（实测该目录只有
        // medal*.gif、mt*.gif、magic/*.small.gif、smiley/**）
        if (low.contains("/static/image/")) return false;
        // 表情
        if (low.contains("/smiley/")) return false;
        // 头像接口（实测为 https://avatar.mt2.cn/uc_server/avatar.php?uid=...）
        if (low.contains("avatar.php") || low.contains("/uc_server/avatar")) return false;
        if (low.contains("avatar.mt2.cn")) return false;
        // 站点杂图
        if (low.contains("/beian.png") || low.contains("mini_logo.png")) return false;
        // 注意：<b>不能</b>按 "icon"/"face"/"stamp"/"magic"/"mini" 这些裸子串过滤。
        // 实测 /data/attachment/common/c8/common_2_icon.png 就是用户真实附件
        // （Discuz 的缩略图带 _icon 后缀），旧版用子串命中即杀，
        // 直接造成"部分帖子的图片整片消失"。
        return true;
    }
}
