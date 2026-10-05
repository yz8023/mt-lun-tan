package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;

import com.solosu.mtforum.util.ReplyContentFilter;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 回复灌水短语精准过滤与用户自定义关键词包含过滤；均独立于作者黑名单。 */
public final class ReplyFilterManager {

    private static final String PREFS = "reply_filter_settings";
    private static final String KEY_SPAM_TERMS = "reply_spam_exact_terms";
    private static final String KEY_KEYWORD_TERMS = "reply_keyword_terms";
    private static final String KEY_KEYWORD_ENABLED = "reply_keyword_filter_enabled";
    /** 旧版 v5.19 保存的回复关键词；首次读取时迁移自定义项。 */
    private static final String KEY_LEGACY_TERMS = "reply_blacklist_terms";
    /** 兼容旧设置：现在只控制内置/默认灌水回复的精准过滤。 */
    private static final String KEY_HIDE_SPAM = "hide_template_spam_replies";

    private static final List<String> DEFAULT_SPAM_TERMS = Collections.unmodifiableList(Arrays.asList(
            "看看隐藏",
            "感谢分享",
            "感谢分享，看看隐藏",
            "去看看",
            "11111",
            "看看内容",
            "学习学习",
            "正需要这个",
            "正需要这个！"
    ));

    private ReplyFilterManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 默认精准灌水短语；返回副本，避免调用方修改内置列表。 */
    public static List<String> getDefaultSpamTerms() {
        return new ArrayList<>(DEFAULT_SPAM_TERMS);
    }

    /** 精准匹配的灌水词条列表。 */
    public static List<String> getSpamTerms(Context context) {
        migrateLegacyTerms(context);
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(KEY_SPAM_TERMS)) return getDefaultSpamTerms();
        return decodeTerms(preferences.getString(KEY_SPAM_TERMS, "[]"));
    }

    public static void setSpamTerms(Context context, List<String> terms) {
        prefs(context).edit().putString(KEY_SPAM_TERMS, encodeTerms(terms)).apply();
    }

    /** 自定义包含匹配词条；新安装默认留空，避免把灌水短语误作子串屏蔽。 */
    public static List<String> getKeywordTerms(Context context) {
        migrateLegacyTerms(context);
        return decodeTerms(prefs(context).getString(KEY_KEYWORD_TERMS, "[]"));
    }

    public static void setKeywordTerms(Context context, List<String> terms) {
        prefs(context).edit().putString(KEY_KEYWORD_TERMS, encodeTerms(terms)).apply();
    }

    public static boolean isKeywordFilterEnabled(Context context) {
        migrateLegacyTerms(context);
        return prefs(context).getBoolean(KEY_KEYWORD_ENABLED, false);
    }

    public static void setKeywordFilterEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEYWORD_ENABLED, enabled).apply();
    }

    /** 精准灌水词条与内置自动解锁模板过滤默认开启。 */
    public static boolean isHideSpamEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_SPAM, true);
    }

    public static void setHideSpamEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HIDE_SPAM, enabled).apply();
    }

    /**
     * v5.19 曾把默认灌水短语与自定义子串词条放在同一列表。迁移时将默认短语留给精准过滤，
     * 只把用户新增的非默认项放入包含匹配列表，保持自定义设置同时修正默认词条语义。
     */
    private static void migrateLegacyTerms(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(KEY_KEYWORD_TERMS) || !preferences.contains(KEY_LEGACY_TERMS)) return;

        List<String> legacy = decodeTerms(preferences.getString(KEY_LEGACY_TERMS, "[]"));
        Set<String> defaultSpam = new LinkedHashSet<>();
        for (String term : DEFAULT_SPAM_TERMS) {
            defaultSpam.add(ReplyContentFilter.normalize(term));
        }
        List<String> spam = new ArrayList<>();
        List<String> custom = new ArrayList<>();
        for (String term : legacy) {
            if (defaultSpam.contains(ReplyContentFilter.normalize(term))) spam.add(term);
            else custom.add(term);
        }

        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_KEYWORD_TERMS, encodeTerms(custom));
        if (!preferences.contains(KEY_SPAM_TERMS)) {
            // 保存旧列表中的精准子集，避免恢复用户此前移除的默认短语。
            editor.putString(KEY_SPAM_TERMS, encodeTerms(spam));
        }
        if (!custom.isEmpty() && !preferences.contains(KEY_KEYWORD_ENABLED)) {
            editor.putBoolean(KEY_KEYWORD_ENABLED,
                    preferences.getBoolean(KEY_HIDE_SPAM, true));
        }
        editor.apply();
    }

    private static List<String> decodeTerms(String raw) {
        List<String> terms = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            Map<String, String> unique = new LinkedHashMap<>();
            for (int i = 0; i < array.length(); i++) {
                String term = array.optString(i, "").trim();
                String normalized = ReplyContentFilter.normalize(term);
                if (!term.isEmpty() && !normalized.isEmpty() && !unique.containsKey(normalized)) {
                    unique.put(normalized, term);
                }
            }
            terms.addAll(unique.values());
        } catch (Exception ignored) {
        }
        return terms;
    }

    private static String encodeTerms(List<String> source) {
        JSONArray array = new JSONArray();
        Map<String, String> unique = new LinkedHashMap<>();
        if (source != null) {
            for (String raw : source) {
                if (raw == null) continue;
                String term = raw.trim();
                String normalized = ReplyContentFilter.normalize(term);
                if (term.isEmpty() || normalized.isEmpty() || unique.containsKey(normalized)) continue;
                unique.put(normalized, term);
            }
        }
        for (String term : unique.values()) array.put(term);
        return array.toString();
    }
}
