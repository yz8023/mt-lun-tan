package com.solosu.mtforum.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 回复文本过滤规则。此类不依赖 Android，便于对匹配边界做单元测试。
 */
public final class ReplyContentFilter {

    /** 只包含常见、独立成句的填充/客套用语；不对含有实际信息的整段回复做模糊判定。 */
    private static final String[] FILLER_PHRASES = {
            "感谢楼主分享", "谢谢楼主分享", "多谢楼主分享", "感谢分享", "谢谢分享", "多谢分享",
            "感谢楼主", "谢谢楼主", "支持楼主", "楼主辛苦", "前来学习", "路过看看",
            "支持一下", "顶一下", "帮顶", "留个名", "收藏了", "mark一下",
            "看看", "来看看", "围观", "路过", "支持", "顶", "学习了", "收藏",
            "mark", "留名", "辛苦了", "好帖", "不错", "感谢", "谢谢", "666", "沙发"
    };
    private static final List<String> NORMALIZED_FILLER_PHRASES = buildFillerPhrases();

    private ReplyContentFilter() {
    }

    /**
     * 判断回复是否命中用户维护的黑名单词条。
     *
     * @param fuzzy false 时全文相等才命中；true 时正文包含词条即命中
     */
    public static boolean matchesBlacklist(String replyText, List<String> terms, boolean fuzzy) {
        String body = normalize(replyText);
        if (body.isEmpty() || terms == null || terms.isEmpty()) return false;
        for (String term : terms) {
            String needle = normalize(term);
            if (needle.isEmpty()) continue;
            if (fuzzy ? body.contains(needle) : body.equals(needle)) return true;
        }
        return false;
    }

    /**
     * 判断是否为模板化灌水回复：只隐藏纯填充/客套语，或「帖子标题 + 客套语」；
     * 含有其他实质内容时不隐藏。
     */
    public static boolean isSpamReply(String replyText, String threadTitle) {
        String body = normalizeForTemplate(replyText);
        if (body.isEmpty()) return false;
        if (isFillerOnly(body)) return true;

        String title = normalizeForTemplate(threadTitle);
        if (title.length() < 2 || !body.contains(title)) return false;

        // 标题可出现在客套语前、后或中间；删除一次标题后，剩余部分必须仍然
        // 完全由填充/客套短语组成，避免误伤有实际观点、说明或问题的回复。
        int from = 0;
        while (from <= body.length() - title.length()) {
            int at = body.indexOf(title, from);
            if (at < 0) break;
            String remainder = body.substring(0, at) + body.substring(at + title.length());
            if (!remainder.isEmpty() && isFillerOnly(remainder)) return true;
            from = at + 1;
        }
        return false;
    }

    /** 精准/模糊匹配用：忽略空白与零宽格式符，统一英文大小写，但保留标点。 */
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

    /** 归一化并按长度降序，先匹配「感谢楼主分享」这类较长短语。 */
    private static List<String> buildFillerPhrases() {
        List<String> phrases = new ArrayList<>(FILLER_PHRASES.length);
        for (String phrase : FILLER_PHRASES) {
            String normalized = normalize(phrase);
            if (!normalized.isEmpty() && !phrases.contains(normalized)) phrases.add(normalized);
        }
        Collections.sort(phrases, (a, b) -> Integer.compare(b.length(), a.length()));
        return Collections.unmodifiableList(phrases);
    }
}
