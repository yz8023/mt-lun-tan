package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local, bounded and de-duplicated recent search history. */
public final class SearchHistoryStore {
    private static final String PREFS = "search_history_v1";
    private static final String KEY_TERMS = "terms";
    public static final int MAX_ITEMS = 20;

    private SearchHistoryStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<String> list(Context context) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_TERMS, "[]"));
            for (int i = 0; i < array.length() && result.size() < MAX_ITEMS; i++) {
                String term = array.optString(i, "").trim();
                if (!TextUtils.isEmpty(term) && !containsIgnoreCase(result, term)) result.add(term);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static synchronized void add(Context context, String rawTerm) {
        if (context == null || TextUtils.isEmpty(rawTerm)) return;
        String term = rawTerm.trim();
        if (term.isEmpty()) return;
        List<String> terms = list(context);
        for (int i = terms.size() - 1; i >= 0; i--) {
            if (terms.get(i).toLowerCase(Locale.ROOT).equals(term.toLowerCase(Locale.ROOT))) {
                terms.remove(i);
            }
        }
        terms.add(0, term);
        while (terms.size() > MAX_ITEMS) terms.remove(terms.size() - 1);
        save(context, terms);
    }

    public static synchronized void remove(Context context, String term) {
        if (context == null || TextUtils.isEmpty(term)) return;
        List<String> terms = list(context);
        for (int i = terms.size() - 1; i >= 0; i--) {
            if (terms.get(i).equalsIgnoreCase(term.trim())) terms.remove(i);
        }
        save(context, terms);
    }

    public static synchronized void clear(Context context) {
        if (context != null) prefs(context).edit().remove(KEY_TERMS).apply();
    }

    private static void save(Context context, List<String> terms) {
        JSONArray array = new JSONArray();
        for (String term : terms) array.put(term);
        prefs(context).edit().putString(KEY_TERMS, array.toString()).apply();
    }

    private static boolean containsIgnoreCase(List<String> terms, String candidate) {
        for (String term : terms) if (term.equalsIgnoreCase(candidate)) return true;
        return false;
    }
}
