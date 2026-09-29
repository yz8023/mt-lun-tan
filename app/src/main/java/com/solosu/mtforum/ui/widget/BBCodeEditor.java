package com.solosu.mtforum.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.solosu.mtforum.R;
import com.solosu.mtforum.ui.anim.Motion;
import com.solosu.mtforum.util.BBCodeUtil;

/**
 * BBCode 编辑器工具（build71 新增）。
 *
 * <p>回复弹窗与发帖页共用一套：
 * <ul>
 *   <li><b>实时预览</b> —— 输入停顿 300ms 自动重渲染，不用来回点「预览」切换</li>
 *   <li><b>21 个常用标签</b> —— 套在选区上，没选区就插一对并把光标放中间</li>
 *   <li><b>RGB 取色器</b> —— 三条滑块 + 实时色块，直接生成 {@code [color=#RRGGBB]}</li>
 *   <li><b>彩虹字</b> —— 把选中文字按 HSV 均匀分色，逐字包 {@code [color]}</li>
 * </ul>
 *
 * <p>标签集参考论坛上「发帖预览插件」的实现（作者提到的那套：
 * 加粗/斜体/下划线/颜色/字号/链接/图片/代码/引用/隐藏/免费 等）。
 */
public final class BBCodeEditor {

    /** 预设标签：{显示名, 前缀, 后缀} */
    public static final String[][] PRESETS = {
            {"加粗", "[b]", "[/b]"},
            {"斜体", "[i]", "[/i]"},
            {"下划线", "[u]", "[/u]"},
            {"删除线", "[s]", "[/s]"},
            {"字号1", "[size=1]", "[/size]"},
            {"字号3", "[size=3]", "[/size]"},
            {"字号5", "[size=5]", "[/size]"},
            {"字号7", "[size=7]", "[/size]"},
            {"链接", "[url=]", "[/url]"},
            {"图片", "[img]", "[/img]"},
            {"代码", "[code]\n", "\n[/code]"},
            {"引用", "[quote]", "[/quote]"},
            {"隐藏", "[hide]", "[/hide]"},
            {"免费", "[free]", "[/free]"},
            {"居中", "[align=center]", "[/align]"},
            {"居左", "[align=left]", "[/align]"},
            {"居右", "[align=right]", "[/align]"},
            {"分割线", "[hr]", ""},
            {"列表", "[list]\n[*]", "\n[/list]"},
            {"表格", "[table]\n[tr][td]", "[/td][/tr]\n[/table]"},
            {"背景色", "[backcolor=#FFF000]", "[/backcolor]"},
    };

    /** 常用色板，点一下直接套 [color] */
    private static final int[] SWATCHES = {
            0xFFE74C3C, 0xFFE67E22, 0xFFF1C40F, 0xFF2ECC71, 0xFF1ABC9C,
            0xFF3498DB, 0xFF9B59B6, 0xFF34495E, 0xFF95A5A6, 0xFF000000,
    };

    private BBCodeEditor() {
    }

    // ==================== 工具条 ====================

    /**
     * 往容器里铺一排标签按钮。
     *
     * @param extraColor 是否附带「取色」「彩虹字」两个特殊按钮
     */
    public static void buildToolbar(final Activity act, LinearLayout bar,
                                    final EditText input, boolean extraColor) {
        if (act == null || bar == null || input == null) return;
        bar.removeAllViews();
        float d = act.getResources().getDisplayMetrics().density;
        int gap = (int) (6 * d), padH = (int) (10 * d), padV = (int) (6 * d);

        if (extraColor) {
            bar.addView(chip(act, "取色", padH, padV, gap,
                    v -> showColorPicker(act, input)));
            bar.addView(chip(act, "彩虹字", padH, padV, gap,
                    v -> applyRainbow(act, input)));
            bar.addView(chip(act, "渐变字", padH, padV, gap,
                    v -> showGradientPicker(act, input)));
        }
        for (final String[] p : PRESETS) {
            bar.addView(chip(act, p[0], padH, padV, gap,
                    v -> wrapSelection(input, p[1], p[2])));
        }
        // 快速色板
        for (final int c : SWATCHES) {
            TextView dot = new TextView(act);
            int size = (int) (26 * d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = gap;
            dot.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(c);
            dot.setBackground(bg);
            Motion.pressFeedback(dot, 0.88f);
            dot.setOnClickListener(v -> wrapSelection(input,
                    "[color=" + hex(c) + "]", "[/color]"));
            bar.addView(dot);
        }
    }

    private static TextView chip(Activity act, String text, int padH, int padV, int gap,
                                 View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(act.getColor(R.color.primary));
        t.setBackgroundResource(R.drawable.bg_quick_reply_chip);
        t.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = gap;
        t.setLayoutParams(lp);
        Motion.pressFeedback(t, 0.92f);
        t.setOnClickListener(click);
        return t;
    }

    // ==================== 实时预览 ====================

    /**
     * 绑定实时预览：输入停顿 300ms 后自动重渲染。
     *
     * <p>之前是点「预览」按钮来回切换，改一个字就得切两次，很难用。
     */
    public static void bindLivePreview(final Activity act, final EditText input,
                                       final TextView preview) {
        if (act == null || input == null || preview == null) return;
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable render = () -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            String raw = input.getText() == null ? "" : input.getText().toString();
            if (TextUtils.isEmpty(raw)) {
                preview.setText("（预览区：在上面输入内容，这里会实时显示效果）");
                preview.setTextColor(act.getColor(R.color.text_hint));
                return;
            }
            preview.setTextColor(act.getColor(R.color.text_primary));
            try {
                String html = BBCodeUtil.convertBBCodeToHtml(raw);
                preview.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT,
                        null, BBCodeUtil.createTagHandler(act)));
            } catch (Exception e) {
                preview.setText(raw);
            }
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                h.removeCallbacks(render);
                h.postDelayed(render, 300);   // 防抖：别每敲一个字就重渲染
            }
        });
        render.run();
    }

    // ==================== 选区操作 ====================

    /** 把标签套在选区上；没选区就插一对并把光标放中间 */
    public static void wrapSelection(EditText input, String open, String close) {
        if (input == null) return;
        Editable e = input.getText();
        if (e == null) return;
        int st = Math.max(0, input.getSelectionStart());
        int en = Math.max(st, input.getSelectionEnd());
        String sel = e.subSequence(st, en).toString();
        String ins = open + sel + close;
        e.replace(st, en, ins);
        int caret = sel.isEmpty() ? st + open.length() : st + ins.length();
        input.setSelection(Math.min(caret, e.length()));
    }

    // ==================== 彩虹字 ====================

    /**
     * 把选中文字变成彩虹色：按 HSV 色相均匀铺开，逐字包 {@code [color]}。
     * 没选中就提示先选文字 —— 全文彩虹通常不是想要的效果。
     */
    public static void applyRainbow(Activity act, EditText input) {
        if (input == null) return;
        Editable e = input.getText();
        if (e == null) return;
        int st = Math.max(0, input.getSelectionStart());
        int en = Math.max(st, input.getSelectionEnd());
        if (en <= st) {
            Toast.makeText(act, "先选中要变彩虹的文字", Toast.LENGTH_SHORT).show();
            return;
        }
        String sel = e.subSequence(st, en).toString();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < sel.length(); i++) {
            char ch = sel.charAt(i);
            if (Character.isWhitespace(ch)) {      // 空白不着色，省得一堆空标签
                sb.append(ch);
                continue;
            }
            n++;
        }
        int idx = 0;
        for (int i = 0; i < sel.length(); i++) {
            char ch = sel.charAt(i);
            if (Character.isWhitespace(ch)) {
                sb.append(ch);
                continue;
            }
            float hue = n <= 1 ? 0f : (360f * idx / n);
            int color = Color.HSVToColor(new float[]{hue, 1f, 1f});
            sb.append("[color=").append(hex(color)).append(']').append(ch).append("[/color]");
            idx++;
        }
        e.replace(st, en, sb.toString());
        input.setSelection(Math.min(st + sb.length(), e.length()));
    }

    // ==================== RGB 取色器 ====================

    /** 三滑块 RGB 取色，实时预览色块，确定后套 [color=#RRGGBB] */
    public static void showColorPicker(final Activity act, final EditText input) {
        float d = act.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        final View swatch = new View(act);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * d));
        sp.bottomMargin = (int) (10 * d);
        swatch.setLayoutParams(sp);

        final TextView hexText = new TextView(act);
        hexText.setGravity(Gravity.CENTER);
        hexText.setTextSize(15f);
        hexText.setTextColor(act.getColor(R.color.text_primary));

        final int[] rgb = {0xE7, 0x4C, 0x3C};
        final SeekBar[] bars = new SeekBar[3];
        final String[] names = {"R", "G", "B"};

        final Runnable refresh = () -> {
            int c = Color.rgb(rgb[0], rgb[1], rgb[2]);
            GradientDrawable g = new GradientDrawable();
            g.setColor(c);
            g.setCornerRadius(10 * d);
            swatch.setBackground(g);
            hexText.setText(hex(c) + "    R" + rgb[0] + " G" + rgb[1] + " B" + rgb[2]);
        };

        root.addView(swatch);
        root.addView(hexText);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView lbl = new TextView(act);
            lbl.setText(names[i]);
            lbl.setTextColor(act.getColor(R.color.text_secondary));
            lbl.setWidth((int) (22 * d));
            SeekBar bar = new SeekBar(act);
            bar.setMax(255);
            bar.setProgress(rgb[i]);
            bar.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    rgb[k] = p;
                    refresh.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            bars[i] = bar;
            row.addView(lbl);
            row.addView(bar);
            root.addView(row);
        }
        refresh.run();

        Dialog dlg = new AlertDialog.Builder(act)
                .setTitle("选择颜色")
                .setView(root)
                .setPositiveButton("套用", (d2, w) ->
                        wrapSelection(input,
                                "[color=" + hex(Color.rgb(rgb[0], rgb[1], rgb[2])) + "]",
                                "[/color]"))
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dlg, act);
    }

    private static String hex(int color) {
        return String.format("#%02X%02X%02X",
                Color.red(color), Color.green(color), Color.blue(color));
    }

    // ==================== 渐变字 ====================

    /** 内置渐变预设：{名称, 起色, 末色} */
    private static final Object[][] GRADIENTS = {
            {"日落 橙→粉", 0xFFFF8008, 0xFFFFC837},
            {"海洋 蓝→青", 0xFF2193B0, 0xFF6DD5ED},
            {"极光 紫→蓝", 0xFF8E2DE2, 0xFF4A00E0},
            {"薄荷 绿→青", 0xFF11998E, 0xFF38EF7D},
            {"樱花 粉→紫", 0xFFFF758C, 0xFFFF7EB3},
            {"火焰 红→黄", 0xFFF12711, 0xFFF5AF19},
            {"深海 深蓝→紫", 0xFF141E30, 0xFF243B55},
            {"彩虹 全色相", 0, 0},          // 特殊：走 HSV 全环
    };

    /**
     * 渐变字：让每个字的颜色在起末两色之间<b>平滑过渡</b>。
     *
     * <p>和「彩虹字」的区别 —— 彩虹字是 HSV 色相跑满 360°（七彩），
     * 渐变字是在你选的两个颜色之间线性插值，观感更柔和、更适合标题。
     */
    public static void showGradientPicker(final Activity act, final EditText input) {
        if (input == null) return;
        Editable e = input.getText();
        if (e == null) return;
        if (input.getSelectionEnd() <= input.getSelectionStart()) {
            Toast.makeText(act, "先选中要做渐变的文字", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[GRADIENTS.length];
        for (int i = 0; i < GRADIENTS.length; i++) names[i] = (String) GRADIENTS[i][0];
        Dialog d = new AlertDialog.Builder(act)
                .setTitle("选择渐变")
                .setItems(names, (dlg, which) -> {
                    int from = (Integer) GRADIENTS[which][1];
                    int to = (Integer) GRADIENTS[which][2];
                    applyGradient(input, from, to, which == GRADIENTS.length - 1);
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(d, act);
    }

    /**
     * @param hsvSweep true = 忽略起末色，走 HSV 全色相（即彩虹）
     */
    public static void applyGradient(EditText input, int from, int to, boolean hsvSweep) {
        Editable e = input.getText();
        if (e == null) return;
        int st = Math.max(0, input.getSelectionStart());
        int en = Math.max(st, input.getSelectionEnd());
        String sel = e.subSequence(st, en).toString();
        if (sel.isEmpty()) return;

        // 先数一遍需要着色的字符（空白跳过，避免生成一堆空标签）
        int n = 0;
        for (int i = 0; i < sel.length(); i++) {
            if (!Character.isWhitespace(sel.charAt(i))) n++;
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (int i = 0; i < sel.length(); i++) {
            char ch = sel.charAt(i);
            if (Character.isWhitespace(ch)) {
                sb.append(ch);
                continue;
            }
            float t = (n <= 1) ? 0f : (float) idx / (n - 1);
            int color = hsvSweep
                    ? Color.HSVToColor(new float[]{360f * t, 1f, 1f})
                    : lerpColor(from, to, t);
            sb.append("[color=").append(hex(color)).append(']').append(ch).append("[/color]");
            idx++;
        }
        e.replace(st, en, sb.toString());
        input.setSelection(Math.min(st + sb.length(), e.length()));
    }

    /** 两色之间线性插值 */
    private static int lerpColor(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(clamp(r), clamp(g), clamp(bl));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
