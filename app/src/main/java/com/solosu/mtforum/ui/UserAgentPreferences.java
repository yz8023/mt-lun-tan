package com.solosu.mtforum.ui;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * build110：内置浏览器的 User-Agent 配置。
 *
 * <p><b>只影响「额外打开途径」</b>——也就是应用内浏览器（点外链、点附件走浏览器时打开的那个
 * WebView）。论坛数据请求（HttpClient）的 UA 保持原样一动不动：站点靠它区分移动/桌面模板，
 * 改了会直接把解析逻辑搞坏。
 *
 * <p>内置两条（移动版 / 电脑版）不可删除；用户可自行添加多条自定义 UA。
 * 默认是「内置移动版」，与改动前的行为完全一致。
 */
public final class UserAgentPreferences {

    private static final String PREFS = "ua_preferences";
    private static final String KEY_CUSTOM = "custom_list";   // JSONArray<String>
    private static final String KEY_SELECTED = "selected";    // ""=内置移动版 "desktop"=内置电脑版 其余=自定义UA本身

    /** 选中项标识：内置移动版（默认，等价于改动前的行为） */
    public static final String ID_BUILTIN_MOBILE = "";
    /** 选中项标识：内置电脑版 */
    public static final String ID_BUILTIN_DESKTOP = "desktop";

    /** 列表项：显示名 + UA 字符串 */
    public static final class Entry {
        public final String id;         // 选中的键
        public final String label;      // 列表里显示的名字
        public final String ua;         // 实际 User-Agent
        public final boolean custom;    // 可否删除

        Entry(String id, String label, String ua, boolean custom) {
            this.id = id;
            this.label = label;
            this.ua = ua;
            this.custom = custom;
        }
    }

    private UserAgentPreferences() {
    }

    /** 读回所有可选条目：两条内置 + 用户自定义 */
    public static List<Entry> entries(Context context) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(ID_BUILTIN_MOBILE, "内置移动版（默认）",
                com.solosu.mtforum.network.HttpClient.USER_AGENT, false));
        list.add(new Entry(ID_BUILTIN_DESKTOP, "内置电脑版",
                com.solosu.mtforum.network.HttpClient.DESKTOP_USER_AGENT, false));
        for (String ua : customList(context)) {
            if (TextUtils.isEmpty(ua)) continue;
            list.add(new Entry(ua, ua, ua, true));
        }
        return list;
    }

    /** 当前选中的标识；默认内置移动版 */
    public static String selectedId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED, ID_BUILTIN_MOBILE);
    }

    public static void setSelectedId(Context context, String id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SELECTED, id == null ? ID_BUILTIN_MOBILE : id).apply();
    }

    /**
     * 当前生效的 UA 字符串。
     *
     * <p>选中的自定义 UA 若已被删除，自动回落到内置移动版，不会留下一个失效配置。
     */
    public static String resolve(Context context) {
        String id = selectedId(context);
        if (ID_BUILTIN_DESKTOP.equals(id)) {
            return com.solosu.mtforum.network.HttpClient.DESKTOP_USER_AGENT;
        }
        if (TextUtils.isEmpty(id)) {
            return com.solosu.mtforum.network.HttpClient.USER_AGENT;
        }
        // 自定义项：确认还在列表里
        if (customList(context).contains(id)) return id;
        return com.solosu.mtforum.network.HttpClient.USER_AGENT;
    }

    /** 设置页显示用的一句话 */
    public static String label(Context context) {
        String id = selectedId(context);
        if (ID_BUILTIN_DESKTOP.equals(id)) return "内置电脑版";
        if (TextUtils.isEmpty(id)) return "内置移动版（默认）";
        if (customList(context).contains(id)) return id;
        return "内置移动版（默认）";
    }

    public static List<String> customList(Context context) {
        List<String> out = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (!TextUtils.isEmpty(v) && !out.contains(v)) out.add(v);
            }
        } catch (Exception ignored) {
            // 坏数据就当没有自定义项，不至于让设置页崩掉
        }
        return out;
    }

    private static void saveCustom(Context context, List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CUSTOM, arr.toString()).apply();
    }

    /** 新增一条自定义 UA。返回 false 表示重复或为空。 */
    public static boolean addCustom(Context context, String ua) {
        if (context == null || TextUtils.isEmpty(ua)) return false;
        String v = ua.trim();
        if (v.isEmpty()) return false;
        List<String> list = customList(context);
        if (list.contains(v)) return false;
        list.add(v);
        saveCustom(context, list);
        return true;
    }

    public static void removeCustom(Context context, String ua) {
        if (context == null || TextUtils.isEmpty(ua)) return;
        List<String> list = customList(context);
        if (list.remove(ua)) {
            saveCustom(context, list);
            // 删掉的正好是当前选中项时回到默认，避免留下悬空配置
            if (ua.equals(selectedId(context))) setSelectedId(context, ID_BUILTIN_MOBILE);
        }
    }

    /** 内置电脑版是不是当前选中项（应用内浏览器「桌面版网站」开关的初始值用它） */
    public static boolean isDesktop(Context context) {
        return ID_BUILTIN_DESKTOP.equals(selectedId(context));
    }

    /** 兼容旧代码：把 JSONObject 之类的配置塞进来时用得到 */
    public static JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("selected", selectedIdSafe());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String selectedIdSafe() {
        return ID_BUILTIN_MOBILE;
    }
}
