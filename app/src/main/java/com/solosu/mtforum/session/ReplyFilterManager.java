package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;

import com.solosu.mtforum.util.ReplyContentFilter;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 回复内容关键词过滤偏好。词条保存在本机，不与论坛用户小黑屋混用。 */
public final class ReplyFilterManager {

    private static final String PREFS = "reply_filter_settings";
    private static final String KEY_TERMS = "reply_blacklist_terms";
    /** 兼容旧版本保存的键；现由这个开关统一控制关键词与内置模板过滤。 */
    private static final String KEY_HIDE_SPAM = "hide_template_spam_replies";

    private static final List<String> DEFAULT_TERMS = Collections.unmodifiableList(Arrays.asList(
            "看看隐藏",
            "感谢分享",
            "感谢分享，看看隐藏",
            "去看看",
            "11111",
            "看看内容",
            "学习学习",
            "正需要这个"
    ));

    private ReplyFilterManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 默认关键词；返回副本，避免调用方修改内置列表。 */
    public static List<String> getDefaultBlacklistTerms() {
        return new ArrayList<>(DEFAULT_TERMS);
    }

    /**
     * 取得回复关键词。首次使用时加载默认词条；用户主动保存空列表后则保持为空，
     * 不会在下次启动时又自动恢复默认词条。
     */
    public static List<String> getBlacklistTerms(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(KEY_TERMS)) return getDefaultBlacklistTerms();

        String raw = preferences.getString(KEY_TERMS, "[]");
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

    /** 保存关键词时去空行、去重（按归一化后的文本），保留原始可读写法。 */
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

    /** 关键词与内置自动解锁模板过滤默认开启。 */
    public static boolean isHideSpamEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_SPAM, true);
    }

    public static void setHideSpamEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HIDE_SPAM, enabled).apply();
    }
}
