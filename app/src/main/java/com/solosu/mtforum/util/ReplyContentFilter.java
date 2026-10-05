package com.solosu.mtforum.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 回复文本过滤规则。此类不依赖 Android，便于对匹配边界做单元测试。
 */
public final class ReplyContentFilter {

    /**
     * 软件内置自动解锁模板中的填充片段，以及常见独立客套语。
     * 只在回复全文都能由这些片段拼成时才由模板识别器隐藏，避免误伤带有实质内容的回复。
     */
    private static final String[] FILLER_PHRASES = {
            "感谢分享，内容看着不错，回复支持一下", "谢谢分享，正需要这个，先回复看看",
            "支持一下，感谢分享好资源", "感谢楼主分享，回复支持",
            "感谢分享", "谢谢分享", "多谢分享", "感谢分享好资源",
            "内容看着不错", "看着不错", "正需要这个", "正好用得上",
            "回复支持一下", "先回复看看", "回复支持", "下来试试", "先收下了", "好资源",
            "感谢楼主分享", "谢谢楼主分享", "多谢楼主分享",
            "感谢楼主", "谢谢楼主", "支持楼主", "楼主辛苦", "前来学习", "路过看看",
            "支持一下", "顶一下", "帮顶", "留个名", "收藏了", "mark一下",
            "看看", "来看看", "围观", "路过", "支持", "顶", "学习了", "收藏",
            "mark", "留名", "辛苦了", "好帖", "不错", "感谢", "谢谢", "666", "沙发"
    };
    private static final List<String> NORMALIZED_FILLER_PHRASES = buildFillerPhrases();

    private ReplyContentFilter() {
    }

    /**
     * 判断回复是否命中用户维护的黑名单词条。固定为关键词/包含匹配：正文包含任一词条即命中。
     * 匹配时忽略空白与零宽格式符、统一英文大小写，但保留标点。
     */
    public static boolean matchesBlacklist(String replyText, List<String> terms) {
        String body = normalize(replyText);
        if (body.isEmpty() || terms == null || terms.isEmpty()) return false;
        for (String term : terms) {
            String needle = normalize(term);
            if (!needle.isEmpty() && body.contains(needle)) return true;
        }
        return false;
    }

    /**
     * 判断是否为模板化灌水回复：纯填充/客套语，或「帖子标题关键词 + 客套模板」。
     * 对带有其它实质文字的回复保持不过滤。
     */
    public static boolean isSpamReply(String replyText, String threadTitle) {
        String body = normalizeForTemplate(replyText);
        if (body.isEmpty()) return false;
        if (isFillerOnly(body)) return true;

        String title = normalizeForTemplate(threadTitle);
        if (title.length() < 2) return false;

        // 自动解锁模板与 AutoReplyEngine 一致：标题去掉标点后最长只取前 10 个字符。
        // 同时兼容较早模板中插入完整帖子标题的情况。
        List<String> titleCandidates = new ArrayList<>();
        titleCandidates.add(title);
        String keyword = firstCodePoints(title, 10);
        if (!keyword.equals(title) && keyword.length() >= 2) titleCandidates.add(keyword);

        for (String candidate : titleCandidates) {
            int from = 0;
            while (from <= body.length() - candidate.length()) {
                int at = body.indexOf(candidate, from);
                if (at < 0) break;
                String remainder = body.substring(0, at) + body.substring(at + candidate.length());
                if (!remainder.isEmpty() && isFillerOnly(remainder)) return true;
                from = at + 1;
            }
        }
        return false;
    }

    /** 关键词匹配用：忽略空白与零宽格式符，统一英文大小写，但保留标点。 */
    public static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                    || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) {
                continue;
            }
            out.appendCodePoint(Character.toLowerCase(cp));
        }
        return out.toString();
    }

    /** 灌水识别专用：忽略空白、标点、表情和零宽格式符，取正文的可见字母/数字。 */
    private static String normalizeForTemplate(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return "";
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length();) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isLetterOrDigit(cp)
                    || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    private static String firstCodePoints(String value, int limit) {
        if (value == null || value.isEmpty()) return "";
        int end = value.offsetByCodePoints(0, Math.min(limit, value.codePointCount(0, value.length())));
        return value.substring(0, end);
    }

    private static boolean isFillerOnly(String normalized) {
        if (normalized == null || normalized.isEmpty()) return false;
        String rest = normalized;
        while (!rest.isEmpty()) {
            String matched = null;
            for (String phrase : NORMALIZED_FILLER_PHRASES) {
                if (!phrase.isEmpty() && rest.startsWith(phrase)) {
                    matched = phrase;
                    break;
                }
            }
            if (matched == null) return false;
            rest = rest.substring(matched.length());
        }
        return true;
    }

    /** 归一化并按长度降序，优先识别完整的自动解锁模板片段。 */
    private static List<String> buildFillerPhrases() {
        List<String> phrases = new ArrayList<>(FILLER_PHRASES.length);
        for (String phrase : FILLER_PHRASES) {
            String normalized = normalizeForTemplate(phrase);
            if (!normalized.isEmpty() && !phrases.contains(normalized)) phrases.add(normalized);
        }
        Collections.sort(phrases, (a, b) -> Integer.compare(b.length(), a.length()));
        return Collections.unmodifiableList(phrases);
    }
}
