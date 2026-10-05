package com.solosu.mtforum.util;

import java.util.regex.Pattern;

/** Shared matcher for URLs pasted as plain text in post and reply bodies. */
public final class PlainTextUrlPattern {

    private static final String URL_CHARS = "[^\\s<>\\\"'`,;，。；：！？、“”‘’]+";
    private static final String HOST_LABEL = "[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?";
    // Put named TLDs first and require a host boundary, so .com cannot be truncated to .co.
    private static final String COMMON_TLD =
            "(?:com|net|org|edu|gov|mil|int|biz|info|name|pro|aero|coop|museum|mobi|travel|"
                    + "jobs|cat|asia|tel|xxx|xyz|top|site|online|club|tech|app|dev|shop|store|"
                    + "live|work|cloud|wiki|today|world|vip|ren|xin|wang|[A-Z]{2})";
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
