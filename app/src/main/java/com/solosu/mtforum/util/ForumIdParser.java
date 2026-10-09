package com.solosu.mtforum.util;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for forum thread/user IDs pasted as plain numbers or forum links. */
public final class ForumIdParser {
    private static final Pattern TID_QUERY = Pattern.compile(
            "(?:^|[?&])(?:tid|threadid)=([0-9]+)(?=$|[&#])", Pattern.CASE_INSENSITIVE);
    private static final Pattern TID_PATH = Pattern.compile(
            "(?:^|/)thread-([0-9]+)(?=[-./?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UID_QUERY = Pattern.compile(
            "(?:^|[?&])uid=([0-9]+)(?=$|[&#])", Pattern.CASE_INSENSITIVE);
    private static final Pattern UID_PATH = Pattern.compile(
            "(?:^|/)space-uid-([0-9]+)(?=[-./?#]|$)", Pattern.CASE_INSENSITIVE);

    private ForumIdParser() { }

    /** Returns a positive TID, or null when the input is not an unambiguous thread ID/link. */
    public static String parseTid(String input) {
        return parse(input, TID_QUERY, TID_PATH);
    }

    /** Returns a positive UID, or null when the input is not an unambiguous user ID/link. */
    public static String parseUid(String input) {
        return parse(input, UID_QUERY, UID_PATH);
    }

    private static String parse(String input, Pattern queryPattern, Pattern pathPattern) {
        if (input == null) return null;
        String raw = input.trim();
        if (raw.isEmpty()) return null;
        if (raw.matches("[0-9]+")) return normalizePositive(raw);

        Matcher query = queryPattern.matcher(raw);
        if (query.find()) return normalizePositive(query.group(1));
        Matcher path = pathPattern.matcher(raw);
        if (path.find()) return normalizePositive(path.group(1));
        return null;
    }

    private static String normalizePositive(String digits) {
        try {
            BigInteger value = new BigInteger(digits);
            return value.signum() > 0 ? value.toString() : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
