package com.solosu.mtforum.util;

import java.util.regex.Pattern;

/**
 * 抓来的文本清洗（build65 新增）。
 *
 * <p>从论坛 HTML 里 scrape 出来的字符串常混进肉眼不可见的字符：
 * 零宽空格、零宽连字、BOM、软连字符、方向控制符。它们在 TextView 上会
 * 渲染成方块/问号之类的「异常符号」，而在日志里看起来一切正常 —— 极难排查。
 *
 * <p>楼层标签就是重灾区：论坛的结构是
 * {@code <span class="f_d y">\n21<sup>#</sup></span>}，
 * 不同模板/插件还可能在 {@code <sup>} 前后塞控制字符。
 */
public final class TextClean {

    /** 零宽与格式控制字符：ZWSP/ZWNJ/ZWJ/LRM/RLM/BOM/软连字符/行分隔符 */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2028\\u2029\\uFEFF]");
    /** C0/C1 控制字符（保留 \n \t） */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]");

    private TextClean() {
    }

    /** 通用清洗：去不可见字符与控制符，NBSP 归一为空格 */
    public static String clean(String s) {
        if (s == null) return null;
        String out = INVISIBLE.matcher(s).replaceAll("");
        out = CONTROL.matcher(out).replaceAll("");
        return out.replace('\u00a0', ' ');
    }

    /**
     * 楼层标签专用。
     *
     * <p>论坛 2~10 层用「沙发/椅子/板凳/地毯/凉席/报纸/地板/地下室/下水道」，
     * 11 层起是 {@code N<sup>#</sup>}。这里把两种都归一成干净短文本：
     * 数字型统一输出 {@code N#}，文字型原样保留。
     */
    public static String floorLabel(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        s = s.replaceAll("\\s+", "").trim();
        if (s.isEmpty()) return "";
        // 数字型：抓出数字，统一补一个 #（防止 sup 丢失或重复）
        java.util.regex.Matcher m = Pattern.compile("^(\\d+)\\s*#*$").matcher(s);
        if (m.matches()) return m.group(1) + "#";
        return s;
    }
}
