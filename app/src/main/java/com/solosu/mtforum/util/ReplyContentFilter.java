package com.solosu.mtforum.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 回复过滤规则：灌水短语是全文精准匹配；用户自定义关键词才是包含匹配。
 * 此类不依赖 Android，便于对匹配边界做单元测试。
 */
public final class ReplyContentFilter {

    /** AutoReplyEngine 在无帖子标题时使用的内置完整解锁回复。 */
    private static final List<String> BUILT_IN_UNLOCK_TEMPLATES = Collections.unmodifiableList(Arrays.asList(
            "感谢分享，内容看着不错，回复支持一下",
            "谢谢分享，正需要这个，先回复看看",
            "支持一下，感谢分享好资源",
            "感谢楼主分享，回复支持",
            "正需要这个！"
    ));

    private ReplyContentFilter() {
    }

    /** 用户自定义关键词过滤：回复正文包含任一词条即命中。 */
    public static boolean matchesKeyword(String replyText, List<String> terms) {
        String body = normalize(replyText);
        if (body.isEmpty() || terms == null || terms.isEmpty()) return false;
        for (String term : terms) {
            String needle = normalize(term);
            if (!needle.isEmpty() && body.contains(needle)) return true;
        }
        return false;
    }

    /** 灌水词条精准匹配：归一化空白与英文大小写后，整条回复必须与词条相等。 */
    public static boolean matchesExactPhrase(String replyText, List<String> terms) {
        String body = normalize(replyText);
        if (body.isEmpty() || terms == null || terms.isEmpty()) return false;
        for (String term : terms) {
            String expected = normalize(term);
            if (!expected.isEmpty() && body.equals(expected)) return true;
        }
        return false;
    }

    /**
     * 识别 AutoReplyEngine 的内置自动解锁完整模板。
     * 只有完整回复与模板一致时才命中；不会因长回复中出现“感谢分享”等片段而隐藏。
     */
    public static boolean isSpamReply(String replyText, String threadTitle) {
        String body = normalize(replyText);
        if (body.isEmpty()) return false;
        for (String template : BUILT_IN_UNLOCK_TEMPLATES) {
            if (body.equals(normalize(template))) return true;
        }

        List<String> titleKeywords = titleKeywords(threadTitle);
        for (String keyword : titleKeywords) {
            // 兼容“感谢分享{title}”以及带常见中文引号的精准短模板。
            if (matchesAny(body,
                    "感谢分享" + keyword,
                    "感谢分享「" + keyword + "」",
                    "感谢分享『" + keyword + "』",
                    "感谢分享《" + keyword + "》")) {
                return true;
            }

            // 与 AutoReplyEngine.buildUnlockText 的五条带标题模板保持一致。
            if (matchesAny(body,
                    "感谢分享「" + keyword + "」，正需要这个，回复支持一下",
                    "「" + keyword + "」看着不错，谢谢分享，下来试试",
                    "支持「" + keyword + "」，感谢分享，先回复看看",
                    "感谢分享「" + keyword + "」，正好用得上",
                    "「" + keyword + "」不错，感谢分享，先收下了")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String body, String... candidates) {
        for (String candidate : candidates) {
            if (body.equals(normalize(candidate))) return true;
        }
        return false;
    }

    /** AutoReplyEngine 会去掉标题标点并最多取前 10 个字符；同时兼容旧版完整标题。 */
    private static List<String> titleKeywords(String threadTitle) {
        if (threadTitle == null || threadTitle.trim().isEmpty()) return Collections.emptyList();
        String clean = threadTitle.replaceAll(
                "[\\s\\p{Punct}（）【】「」『』《》，。！？、~·:：]", "");
        if (clean.isEmpty()) return Collections.emptyList();
        List<String> candidates = new ArrayList<>();
        candidates.add(clean);
        int count = clean.codePointCount(0, clean.length());
        if (count > 10) {
            int end = clean.offsetByCodePoints(0, 10);
            String shortTitle = clean.substring(0, end);
            if (!shortTitle.equals(clean)) candidates.add(shortTitle);
        }
        return candidates;
    }

    /** 匹配时忽略空白与零宽格式符，统一英文大小写；标点仍参与全文精准比较。 */
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
}
