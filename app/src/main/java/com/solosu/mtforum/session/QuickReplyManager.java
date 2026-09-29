package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 快捷回复短语管理（build63 新增）。
 *
 * <p>存在 SharedPreferences {@code app_settings} 的 {@code quick_reply_items} 里，
 * 一个 JSON 字符串数组。用户可以在回复弹窗里自行增删改，恢复默认也一键搞定。
 */
public final class QuickReplyManager {

    private static final String PREF = "app_settings";
    private static final String KEY_ITEMS = "quick_reply_items";
    /** 用户是否动过（动过就不再随版本更新默认值） */
    private static final String KEY_CUSTOMIZED = "quick_reply_customized";

    /** 内置默认短语 */
    public static final List<String> DEFAULTS = Arrays.asList(
            "感谢分享", "支持一下", "学习了", "前排围观",
            "收藏了，谢谢楼主", "已下载，回来反馈", "沙发", "顶一个"
    );

    /** 单条短语的最大长度，太长的就不适合做快捷按钮了 */
    public static final int MAX_LENGTH = 40;
    /** 最多存多少条 */
    public static final int MAX_COUNT = 20;

    private QuickReplyManager() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 当前短语列表；没自定义过就返回内置默认 */
    public static List<String> list(Context context) {
        String json = sp(context).getString(KEY_ITEMS, null);
        if (TextUtils.isEmpty(json)) return new ArrayList<>(DEFAULTS);
        try {
            JSONArray arr = new JSONArray(json);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i);
                if (!TextUtils.isEmpty(v)) out.add(v);
            }
            return out.isEmpty() ? new ArrayList<>(DEFAULTS) : out;
        } catch (Exception e) {
            return new ArrayList<>(DEFAULTS);
        }
    }

    public static void save(Context context, List<String> items) {
        if (items == null) return;
        JSONArray arr = new JSONArray();
        int n = 0;
        for (String s : items) {
            if (s == null || s.trim().isEmpty()) continue;
            String v = s.trim();
            if (v.length() > MAX_LENGTH) v = v.substring(0, MAX_LENGTH);
            arr.put(v);
            if (++n >= MAX_COUNT) break;
        }
        sp(context).edit()
                .putString(KEY_ITEMS, arr.toString())
                .putBoolean(KEY_CUSTOMIZED, true)
                .apply();
    }

    /** 从多行文本解析（编辑对话框用：一行一条） */
    public static List<String> parseLines(String text) {
        // 纯 Java，无 android 依赖，可跑 JVM 单测
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\\r?\\n")) {
            String v = line.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** 转成多行文本（编辑对话框用） */
    public static String toLines(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    public static void resetToDefault(Context context) {
        sp(context).edit().remove(KEY_ITEMS).remove(KEY_CUSTOMIZED).apply();
    }

    public static boolean isCustomized(Context context) {
        return sp(context).getBoolean(KEY_CUSTOMIZED, false);
    }
}
