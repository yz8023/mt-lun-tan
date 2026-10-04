package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快捷回复短语管理（build63 新增，build98 支持勾选启用 / 变量占位符）。
 *
 * <p>存储：SharedPreferences {@code app_settings} 的 {@code quick_reply_items}。
 * 老版本存的是纯字符串数组 {@code ["感谢分享", ...]}，build98 起升级成对象数组
 * {@code [{"t":"感谢分享","on":true}, ...]} —— 带 {@code on} 字段（是否显示在快捷条上）。
 * 解析器两种都能读，老数据不会丢。
 *
 * <p>另外支持变量占位符：{@code {title}}、{@code {author}}、{@code {forum}}…
 * 插入时按当前帖子上下文替换（见 {@link #applyVars}）。序列化 / 解析 / 替换
 * 三件事都是纯 Java，可被 JVM 单测直接覆盖。
 */
public final class QuickReplyManager {

    private static final String PREF = "app_settings";
    private static final String KEY_ITEMS = "quick_reply_items";
    /** 用户是否动过（动过就不再随版本更新默认值） */
    private static final String KEY_CUSTOMIZED = "quick_reply_customized";

    /** 内置默认短语。build98 起混进几条带变量的示例，方便直接看到占位符怎么用。 */
    public static final List<String> DEFAULTS = Arrays.asList(
            "感谢分享",
            "支持一下",
            "学习了",
            "前排围观",
            "收藏了，谢谢楼主",
            "已下载，回来反馈",
            "{author} 太强了，{title} 已收藏",
            "沙发"
    );

    /** 可用变量：{占位符, 说明}。UI 里列出来给用户点着插。 */
    public static final String[][] VARIABLES = {
            {"{title}", "当前帖子标题"},
            {"{author}", "帖子作者（楼主）"},
            {"{to}", "正在回复的那个人的名字"},
            {"{forum}", "当前版块名"},
            {"{floor}", "正在回复的楼层（如 21#）"},
            {"{url}", "当前帖子的链接"},
            {"{tid}", "当前帖子 ID"},
            {"{username}", "我自己的用户名"},
            {"{uid}", "我自己的 UID"},
            {"{date}", "今天日期（2026-10-05）"},
            {"{time}", "当前时间（21:30）"},
    };

    /** 单条短语的最大长度，太长的就不适合做快捷按钮了 */
    public static final int MAX_LENGTH = 80;
    /** 最多存多少条 */
    public static final int MAX_COUNT = 30;

    private QuickReplyManager() {
    }

    // ==================== 数据模型 ====================

    /** 一条快捷回复：文本 + 是否显示在快捷条上 */
    public static final class Item {
        public String text;
        public boolean enabled;

        public Item(String text, boolean enabled) {
            this.text = text;
            this.enabled = enabled;
        }
    }

    // ==================== 读写 ====================

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 全部条目（含未勾选的）。没自定义过就返回内置默认（全部勾选）。 */
    public static List<Item> items(Context context) {
        String raw = sp(context).getString(KEY_ITEMS, null);
        if (TextUtils.isEmpty(raw)) return defaultItems();
        List<Item> parsed = parseStored(raw);
        if (parsed.isEmpty()) return defaultItems();
        return parsed;
    }

    /** 只返回勾选了的短语文本（快捷条上显示的就是这些） */
    public static List<String> list(Context context) {
        List<String> out = new ArrayList<>();
        for (Item it : items(context)) {
            if (it.enabled && !TextUtils.isEmpty(it.text)) out.add(it.text);
        }
        return out;
    }

    /** 全部文本（不管勾没勾）—— 编辑器用 */
    public static List<String> allTexts(Context context) {
        List<String> out = new ArrayList<>();
        for (Item it : items(context)) out.add(it.text);
        return out;
    }

    public static void saveItems(Context context, List<Item> items) {
        sp(context).edit()
                .putString(KEY_ITEMS, serialize(items))
                .putBoolean(KEY_CUSTOMIZED, true)
                .apply();
    }

    /** 兼容旧调用：整表替换（全部勾选） */
    public static void save(Context context, List<String> texts) {
        List<Item> items = new ArrayList<>();
        if (texts != null) {
            for (String s : texts) items.add(new Item(s, true));
        }
        saveItems(context, items);
    }

    public static void resetToDefault(Context context) {
        sp(context).edit().remove(KEY_ITEMS).remove(KEY_CUSTOMIZED).apply();
    }

    public static boolean isCustomized(Context context) {
        return sp(context).getBoolean(KEY_CUSTOMIZED, false);
    }

    public static List<Item> defaultItems() {
        List<Item> out = new ArrayList<>();
        for (String s : DEFAULTS) out.add(new Item(s, true));
        return out;
    }

    // ==================== 序列化（纯 Java） ====================

    /** 一行一条 -> 列表（老编辑器用） */
    public static List<String> parseLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\\r?\\n")) {
            String v = line.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** 列表 -> 一行一条（导出/复制用） */
    public static String toLines(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 解析存储串。两种格式都认：
     * <pre>
     *   ["感谢分享","支持一下"]                                  ← build63~98
     *   [{"t":"感谢分享","on":true},{"t":"{author} 牛","on":false}]  ← build98+
     * </pre>
     */
    public static List<Item> parseStored(String raw) {
        List<Item> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.length() < 2 || s.charAt(0) != '[') return out;
        int i = 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '{') {                        // 对象条目
                int end = findObjectEnd(s, i);
                if (end < 0) break;
                String body = s.substring(i + 1, end);
                String text = extractQuoted(body, "t");
                if (text == null || text.trim().isEmpty()) {
                    i = end + 1;
                    continue;
                }
                boolean on = !body.contains("\"on\":false");
                out.add(new Item(clampText(text), on));
                i = end + 1;
            } else if (c == '"') {                 // 老格式：纯字符串
                int[] span = readQuoted(s, i);
                if (span == null) break;
                String text = unescape(s.substring(i + 1, span[0]));
                if (!text.trim().isEmpty()) out.add(new Item(clampText(text), true));
                i = span[1];
            } else {
                i++;
            }
            if (out.size() >= MAX_COUNT) break;
        }
        return out;
    }

    /** 序列化成对象数组（永远写新格式） */
    public static String serialize(List<Item> items) {
        StringBuilder sb = new StringBuilder("[");
        if (items != null) {
            int n = 0;
            for (Item it : items) {
                if (it == null || it.text == null || it.text.trim().isEmpty()) continue;
                if (n > 0) sb.append(',');
                sb.append("{\"t\":\"").append(escape(it.text.trim())).append("\",\"on\":")
                        .append(it.enabled).append('}');
                if (++n >= MAX_COUNT) break;
            }
        }
        return sb.append(']').toString();
    }

    private static String extractQuoted(String body, String key) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int q = body.indexOf('"', colon + 1);
        if (q < 0) return null;
        int[] span = readQuoted(body, q);
        if (span == null) return null;
        return unescape(body.substring(q + 1, span[0]));
    }

    /** 从 index 处的引号开始找到配对引号，返回 {引号结束位置, 字符串结束位置} */
    private static int[] readQuoted(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '"') return null;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == '"') return new int[]{i, i + 1};
        }
        return null;
    }

    /** 找对象条目的右花括号（串里的花括号不参与计数） */
    private static int findObjectEnd(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                int[] span = readQuoted(s, i);
                if (span == null) return -1;
                i = span[0];
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == 'r') sb.append('\r');
                else if (n == 't') sb.append('\t');
                else sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String clampText(String text) {
        String v = text.trim();
        if (v.length() > MAX_LENGTH) v = v.substring(0, MAX_LENGTH);
        return v;
    }

    // ==================== 变量占位符（纯 Java） ====================

    /**
     * 把 {@code {title}} 这类占位符换成当前帖子的实际内容。
     *
     * <p>规则：只有「表里给了值」的变量才替换；没给值的原样留着 —— 与其替换成空串
     * 让用户一脸疑惑，不如让他看见 {@code {xxx}} 自己删掉。
     */
    public static String applyVars(String text, Map<String, String> vars) {
        if (text == null || text.isEmpty()) return "";
        if (vars == null || vars.isEmpty()) return text;
        String out = text;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            if (key == null || key.isEmpty() || val == null || val.isEmpty()) continue;
            // 只替换 {xxx} 这种带花括号的写法：不带括号裸替换会把正文里恰好同名的
            // 普通单词（比如英文里的 title）也换掉，那就成 bug 了。
            out = out.replace(key, val);
        }
        return out;
    }

    /** 方便构造变量表 */
    public static Map<String, String> newVarMap() {
        return new LinkedHashMap<>();
    }
}
