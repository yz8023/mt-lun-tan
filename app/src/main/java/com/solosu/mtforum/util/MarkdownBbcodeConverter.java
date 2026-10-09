package com.solosu.mtforum.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将常用 Markdown 子集转换为 Discuz BBCode；已有 BBCode 原样保留。 */
public final class MarkdownBbcodeConverter {
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,}).*$");
    private static final Pattern HEADING = Pattern.compile("^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.+)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern BB_BLOCK = Pattern.compile(
            "(?is)\\[([a-z][a-z0-9_-]*)(?:=[^\\]]*)?\\].*?\\[/\\1\\s*\\]");
    private static final Pattern BB_TAG = Pattern.compile("(?i)\\[/?[a-z][a-z0-9_-]*(?:=[^\\]]*)?\\](?!\\()|\\[(?:\\*|hr)\\]");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^]]*)]\\((\\S+?)(?:\\s+[\\\"'].*?[\\\"'])?\\)");
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[([^]]+)]\\((\\S+?)(?:\\s+[\\\"'].*?[\\\"'])?\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern BOLD_STAR = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern BOLD_UNDERLINE = Pattern.compile("__([^_\\n]+)__");
    private static final Pattern STRIKE = Pattern.compile("~~([^~\\n]+)~~");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)");
    private static final Pattern ITALIC_UNDERLINE = Pattern.compile("(?<!_)_([^_\\n]+)_(?!_)");

    private MarkdownBbcodeConverter() { }

    public static String convert(String source) {
        if (source == null || source.isEmpty()) return "";
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length() + 32);
        StringBuilder code = new StringBuilder();
        String fenceMarker = null;
        boolean inUnordered = false;
        boolean inOrdered = false;

        for (String line : lines) {
            String trimmed = line.trim();
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (fenceMarker == null) {
                    closeList(out, inUnordered, inOrdered);
                    inUnordered = false;
                    inOrdered = false;
                    fenceMarker = marker.substring(0, 1);
                    code.setLength(0);
                } else if (marker.startsWith(fenceMarker)) {
                    out.append("[code]").append(code).append("[/code]\n");
                    fenceMarker = null;
                } else {
                    code.append(line).append('\n');
                }
                continue;
            }
            if (fenceMarker != null) {
                code.append(line).append('\n');
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                closeList(out, inUnordered, inOrdered);
                inUnordered = false;
                inOrdered = false;
                int level = heading.group(1).length();
                int size = Math.max(1, 6 - level);
                out.append("[size=").append(size).append("][b]")
                        .append(convertInline(heading.group(2)))
                        .append("[/b][/size]\n");
                continue;
            }
            if (HORIZONTAL_RULE.matcher(line).matches()) {
                closeList(out, inUnordered, inOrdered);
                inUnordered = false;
                inOrdered = false;
                out.append("[hr]\n");
                continue;
            }

            Matcher unordered = UNORDERED.matcher(line);
            Matcher ordered = ORDERED.matcher(line);
            if (unordered.matches()) {
                if (inOrdered) out.append("[/list]\n");
                if (!inUnordered) out.append("[list]\n");
                inOrdered = false;
                inUnordered = true;
                out.append("[*]").append(convertInline(unordered.group(1))).append('\n');
                continue;
            }
            if (ordered.matches()) {
                if (inUnordered) out.append("[/list]\n");
                if (!inOrdered) out.append("[list=1]\n");
                inUnordered = false;
                inOrdered = true;
                out.append("[*]").append(convertInline(ordered.group(1))).append('\n');
                continue;
            }
            if (inUnordered || inOrdered) {
                out.append("[/list]\n");
                inUnordered = false;
                inOrdered = false;
            }

            Matcher quote = QUOTE.matcher(line);
            if (quote.matches()) {
                out.append("[quote]").append(convertInline(quote.group(1))).append("[/quote]\n");
            } else if (trimmed.isEmpty()) {
                out.append('\n');
            } else {
                out.append(convertInline(line)).append('\n');
            }
        }
        if (fenceMarker != null) out.append("[code]").append(code).append("[/code]\n");
        closeList(out, inUnordered, inOrdered);
        return out.toString().replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    private static String convertInline(String value) {
        if (value == null || value.isEmpty()) return "";
        List<String> protectedTokens = new ArrayList<>();
        String text = protect(value, BB_BLOCK, protectedTokens);
        text = protect(text, BB_TAG, protectedTokens);
        text = replace(text, IMAGE, "[img]$2[/img]");
        text = replace(text, LINK, "[url=$2]$1[/url]");
        text = replace(text, INLINE_CODE, "[code]$1[/code]");
        text = replace(text, BOLD_STAR, "[b]$1[/b]");
        text = replace(text, BOLD_UNDERLINE, "[b]$1[/b]");
        text = replace(text, STRIKE, "[s]$1[/s]");
        text = replace(text, ITALIC_STAR, "[i]$1[/i]");
        text = replace(text, ITALIC_UNDERLINE, "[i]$1[/i]");
        for (int i = protectedTokens.size() - 1; i >= 0; i--) {
            text = text.replace(token(i), protectedTokens.get(i));
        }
        return text;
    }

    private static String protect(String input, Pattern pattern, List<String> tokens) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            tokens.add(matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(token(tokens.size() - 1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String token(int index) {
        return "\u0001" + index + "\u0002";
    }

    private static String replace(String input, Pattern pattern, String replacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            StringBuilder expanded = new StringBuilder(replacement.length() + 16);
            for (int i = 0; i < replacement.length(); i++) {
                char ch = replacement.charAt(i);
                if (ch == '$' && i + 1 < replacement.length()
                        && Character.isDigit(replacement.charAt(i + 1))) {
                    int group = replacement.charAt(++i) - '0';
                    if (group <= matcher.groupCount() && matcher.group(group) != null) {
                        expanded.append(matcher.group(group));
                    }
                } else {
                    expanded.append(ch);
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(expanded.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static void closeList(StringBuilder out, boolean unordered, boolean ordered) {
        if (unordered || ordered) out.append("[/list]\n");
    }
}
