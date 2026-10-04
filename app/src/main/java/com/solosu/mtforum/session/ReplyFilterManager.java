package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;

import com.solosu.mtforum.util.ReplyContentFilter;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 回复内容黑名单与灌水过滤偏好。词条保存在本机，不与论坛用户小黑屋混用。 */
public final class ReplyFilterManager {

    public static final String MODE_EXACT = "exact";
    public static final String MODE_FUZZY = "fuzzy";

    private static final String PREFS = "reply_filter_settings";
    private static final String KEY_TERMS = "reply_blacklist_terms";
    private static final String KEY_MATCH_MODE = "reply_blacklist_match_mode";
    private static final String KEY_HIDE_SPAM = "hide_template_spam_replies";

    private ReplyFilterManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 默认精准匹配，避免新装后因短词产生意外误屏蔽。 */
    public static String getMatchMode(Context context) {
        String mode = prefs(context).getString(KEY_MATCH_MODE, MODE_EXACT);
        return MODE_FUZZY.equals(mode) ? MODE_FUZZY : MODE_EXACT;
    }

    public static boolean isFuzzyMatch(Context context) {
        return MODE_FUZZY.equals(getMatchMode(context));
    }

    public static void setMatchMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MATCH_MODE,
                MODE_FUZZY.equals(mode) ? MODE_FUZZY : MODE_EXACT).apply();
    }

    /** 取得回复文字黑名单；空名单默认不拦截。 */
    public static List<String> getBlacklistTerms(Context context) {
        String raw = prefs(context).getString(KEY_TERMS, "[]");
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

    /** 保存词条时去空行、去重（按归一化后的文本），保留原始可读写法。 */
    public static void setBlacklistTerms(Context context, List<String> source) {
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
        prefs(context).edit().putString(KEY_TERMS, array.toString()).apply();
    }

    /** 模板化灌水过滤默认开启。 */
    public static boolean isHideSpamEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_SPAM, true);
    }

    public static void setHideSpamEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HIDE_SPAM, enabled).apply();
    }
}
