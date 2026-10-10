package com.solosu.mtforum.util;

import java.util.regex.Pattern;

/** Shared matcher for URLs pasted as plain text in post and reply bodies. */
public final class PlainTextUrlPattern {

    /**
     * 链接主体允许的字符集：不含空格、尖括号、引号、反引号与中英文句读。
     *
     * <p>包内可见是有意的 —— {@link PostWebLinkifyScript} 要把同一套规则交给
     * 正文 WebView 用。两边必须是同一份来源，否则「TextView 里算链接、
     * WebView 里不算」这种漂移迟早会变成用户报的 bug。
     */
    static final String URL_CHARS = "[^\\s<>\\\"'`,;，。；：！？、“”‘’]+";
    private static final String HOST_LABEL = "[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?";
    /**
     * TLD 候选表（不带分组括号）。命名 TLD 排在通用两字母之前并要求宿主边界，
     * 这样 {@code .com} 不会被截成 {@code .co}。同样与 WebView 侧共用。
     */
    static final String TLD_ALTERNATION =
            "com|net|org|edu|gov|mil|int|biz|info|name|pro|aero|coop|museum|"
                    + "mobi|travel|jobs|cat|asia|tel|xxx|xyz|top|site|online|club|tech|"
                    + "app|dev|shop|store|live|work|cloud|wiki|today|world|vip|ren|xin|"
                    + "wang|[A-Z]{2}";

    private static final String COMMON_TLD = "(?:" + TLD_ALTERNATION + ")";
    private static final String BARE_DOMAIN = "(?:www\\.)?(?:" + HOST_LABEL + "\\.)+"
            + COMMON_TLD + "(?![A-Z0-9-])(?::[0-9]{1,5})?(?:[/?#]" + URL_CHARS + ")?";

    /** Explicit http/https or protocol-relative URLs, www hosts, and common bare domains. */
    public static final Pattern WEB_URL = Pattern.compile(
            "(?<![A-Z0-9_@])(?:"
                    + "(?:https?://|//)" + URL_CHARS
                    + "|www\\." + URL_CHARS
                    + "|" + BARE_DOMAIN
                    + ")(?<![.,;:!?])",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern BARE_DOMAIN_WHOLE = Pattern.compile(
            "^(?:https?://)?" + BARE_DOMAIN + "$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private PlainTextUrlPattern() {
    }

    /** True only for host-like text that can safely be completed with https://. */
    public static boolean isBareWebAddress(String value) {
        return value != null && BARE_DOMAIN_WHOLE.matcher(value).matches();
    }
}
