package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览历史（build71 新增）。
 *
 * <p>只记 tid / 标题 / 作者 / 时间，存 SharedPreferences，最多 200 条。
 * 不联网、不上传，清空即彻底删除。
 */
public final class HistoryStore {

    private static final String PREF = "app_settings";
    private static final String KEY = "browse_history";
    private static final int MAX = 200;

    public static class Item {
        public String tid;
        public String title;
        public String author;
        public long at;
    }

    private HistoryStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 记一次浏览；同一帖子只保留最新一条并置顶 */
    public static void record(Context c, String tid, String title, String author) {
        if (c == null || TextUtils.isEmpty(tid)) return;
        List<Item> list = list(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (tid.equals(list.get(i).tid)) list.remove(i);
        }
        Item it = new Item();
        it.tid = tid;
        it.title = TextUtils.isEmpty(title) ? ("帖子 " + tid) : title;
        it.author = author == null ? "" : author;
        it.at = System.currentTimeMillis();
        list.add(0, it);
        while (list.size() > MAX) list.remove(list.size() - 1);
        persist(c, list);
    }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            String json = sp(c).getString(KEY, null);
            if (TextUtils.isEmpty(json)) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.tid = o.optString("tid");
                it.title = o.optString("title");
                it.author = o.optString("author");
                it.at = o.optLong("at");
                if (!TextUtils.isEmpty(it.tid)) out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void persist(Context c, List<Item> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : list) {
                JSONObject o = new JSONObject();
                o.put("tid", it.tid);
                o.put("title", it.title);
                o.put("author", it.author);
                o.put("at", it.at);
                arr.put(o);
            }
            sp(c).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context c) {
        sp(c).edit().remove(KEY).apply();
    }

    public static int count(Context c) {
        return list(c).size();
    }
}
