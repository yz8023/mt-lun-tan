package com.solosu.mtforum.ui.web;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.solosu.mtforum.ui.DownloadPreferences;

import java.util.Locale;

/** Centralizes the user's in-app-browser / system-browser preference for web links. */
public final class LinkRouter {
    private LinkRouter() {}

    /**
     * Normalize common plain-text URL forms (www.example.com and protocol-relative URLs),
     * remove sentence punctuation accidentally captured by linkification, and reject unsafe schemes.
     */
    public static String normalizeWebUrl(String input) {
        if (input == null) return null;
        String url = trimTrailingSentencePunctuation(input.trim());
        if (url.isEmpty()) return null;
        if (url.startsWith("//")) url = "https:" + url;
        else if (url.regionMatches(true, 0, "www.", 0, 4)) url = "https://" + url;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            return uri.buildUpon().scheme(scheme.toLowerCase(Locale.ROOT)).build().toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String trimTrailingSentencePunctuation(String value) {
        int end = value.length();
        boolean trimmed;
        do {
            trimmed = false;
            if (end <= 0) break;
            char last = value.charAt(end - 1);
            if (".,;:!?，。；：！？、\"'".indexOf(last) >= 0) {
                end--;
                trimmed = true;
                continue;
            }
            char opening = matchingOpeningBracket(last);
            if (opening != 0 && count(value, last, end) > count(value, opening, end)) {
                end--;
                trimmed = true;
            }
        } while (trimmed);
        return value.substring(0, end);
    }

    private static char matchingOpeningBracket(char closing) {
        switch (closing) {
            case ')': return '(';
            case ']': return '[';
            case '}': return '{';
            case '）': return '（';
            case '】': return '【';
            case '》': return '《';
            case '〉': return '〈';
            case '」': return '「';
            case '』': return '『';
            case '”': return '“';
            case '’': return '‘';
            default: return 0;
        }
    }

    private static int count(String value, char target, int end) {
        int total = 0;
        for (int i = 0; i < end; i++) if (value.charAt(i) == target) total++;
        return total;
    }

    public static void open(Context context, String rawUrl) {
        if (context == null) return;
        String url = normalizeWebUrl(rawUrl);
        if (url == null) {
            Toast.makeText(context, "链接格式无效", Toast.LENGTH_SHORT).show();
            return;
        }
        if (DownloadPreferences.getMode(context) == DownloadPreferences.MODE_IN_APP) {
            InAppBrowserActivity.open(context, url);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    /** Used by the reply renderer to keep native forum navigation only in in-app mode. */
    public static boolean isInAppMode(Context context) {
        return context != null && DownloadPreferences.getMode(context) == DownloadPreferences.MODE_IN_APP;
    }
}
