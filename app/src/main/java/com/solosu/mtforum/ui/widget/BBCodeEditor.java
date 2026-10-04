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
 * BBCode 编辑器工具（build71 新增，build98 扩充）。
 *
 * <p>回复弹窗与发帖页共用一套：
 * <ul>
 *   <li><b>实时预览</b> —— 输入停顿 300ms 自动重渲染，不用来回点「预览」切换。
 *       build98：预览走 {@link #renderPreview}，任何一步失败都不会再把 BBCode 原文
 *       甩给用户（用了 [code] 之后满屏 [color=#B30000] 的那个问题）。</li>
 *   <li><b>常用标签</b> —— 套在选区上，没选区就插一对并把光标放中间</li>
 *   <li><b>弹窗填充</b> —— QQ / 邮箱 / 链接 / 视频 / 图片 这类带参数的标签，
 *       点一下弹小窗填地址和名称，不用自己记 {@code [email=xx]yy[/email]} 的写法</li>
 *   <li><b>RGB 取色器</b> —— 三条滑块 + 实时色块，直接生成 {@code [color=#RRGGBB]}</li>
 *   <li><b>彩虹字 / 渐变字</b> —— 把选中文字按色相逐字包 {@code [color]}</li>
 * </ul>
 */
public final class BBCodeEditor {

    /** 预设标签：{显示名, 前缀, 后缀}（一次点击直接套选区，不需要额外输入） */
    public static final String[][] PRESETS = {
            {"加粗", "[b]", "[/b]"},
            {"斜体", "[i]", "[/i]"},
            {"下划线", "[u]", "[/u]"},
            {"删除线", "[s]", "[/s]"},
            {"字号1", "[size=1]", "[/size]"},
            {"字号3", "[size=3]", "[/size]"},
            {"字号5", "[size=5]", "[/size]"},
            {"字号7", "[size=7]", "[/size]"},
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

    /**
     * 需要「弹窗快速填充」的标签（build98）。
     *
     * <p>用户在反馈里点名要的几条：{@code [qq]}、{@code [media=x,500,375]}、
     * {@code [email=地址]名称[/email]}、{@code [url=地址]名称[/url]}。
     * 这些标签都带参数，直接在正文里手写容易写错，改成弹个小窗填。
     *
     * <p>字段定义：{显示名, 弹窗标题, 字段1提示, 字段1默认, 字段2提示, 字段2默认, 模板}
     * —— 模板里 {@code %1$s}=字段1、{@code %2$s}=字段2；字段2提示为 null 表示只有一个输入框。
     * 模板中出现 {@code %2$s} 但用户没填时，用字段1的值兜底（例：链接不写名字就显示地址）。
     */
    private static final String[][] FILLS = {
            {"链接", "插入链接", "链接地址（http://…）", "", "显示文字（留空=用地址）", "",
                    "[url=%1$s]%2$s[/url]"},
            {"QQ", "插入 QQ 号", "QQ 号", "", null, null, "[qq]%1$s[/qq]"},
            {"邮箱", "插入邮箱", "邮箱地址", "", "显示名称（留空=用地址）", "",
                    "[email=%1$s]%2$s[/email]"},
            {"视频", "插入网络视频", "视频地址（mp4/m3u8/flv…）", "",
                    "尺寸（默认 x,500,375，可留空）", "x,500,375",
                    "[media=%2$s]%1$s[/media]"},
            {"图片", "插入图片", "图片地址", "", "尺寸 W,H（可留空）", "",
                    "[img=%2$s]%1$s[/img]"},
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
     * @param extraColor 是否附带「取色」「彩虹字」「渐变字」三个特殊按钮
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
        // build98: 带参数的四条排在最前面（弹窗填充），随后是免输入的固定标签
        for (final String[] f : FILLS) {
            bar.addView(chip(act, f[0], padH, padV, gap,
                    v -> showFillDialog(act, input, f)));
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

    // ==================== 弹窗填充（带参数的标签） ====================

    /**
     * 弹出小窗填参数，再把标签插进正文（build98）。
     *
     * <p>有选区的时侯，选区内容会预填进第二个字段（显示文字）—— 先写好文字再套链接是最常见的写法。
     */
    private static void showFillDialog(final Activity act, final EditText input,
                                       final String[] spec) {
        if (act == null || input == null || spec == null || spec.length < 7) return;
        final String title = spec[1];
        final String hint1 = spec[2];
        final String def1 = spec[3];
        final String hint2 = spec[4];
        final String def2 = spec[5];
        final String template = spec[6];

        // 选区文字默认填到「显示文字」里
        String selected = "";
        Editable e = input.getText();
        if (e != null) {
            int st = Math.max(0, input.getSelectionStart());
            int en = Math.max(st, input.getSelectionEnd());
            if (en > st) selected = e.subSequence(st, en).toString();
        }

        float d = act.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView label1 = new TextView(act);
        label1.setText(hint1);
        label1.setTextSize(12f);
        label1.setTextColor(act.getColor(R.color.text_hint));
        final EditText et1 = new EditText(act);
        et1.setSingleLine(true);
        et1.setTextSize(14f);
        et1.setHint(hint1);
        et1.setText(def1 == null ? "" : def1);
        box.addView(label1);
        box.addView(et1);

        final EditText et2;
        if (TextUtils.isEmpty(hint2)) {
            et2 = null;
        } else {
            TextView label2 = new TextView(act);
            label2.setText(hint2);
            label2.setTextSize(12f);
            label2.setTextColor(act.getColor(R.color.text_hint));
            label2.setPadding(0, (int) (10 * d), 0, 0);
            et2 = new EditText(act);
            et2.setSingleLine(true);
            et2.setTextSize(14f);
            et2.setHint(hint2);
            et2.setText(selected.isEmpty() && def2 != null ? def2 : selected);
            box.addView(label2);
            box.addView(et2);
        }

        Dialog dlg = new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("插入", (d2, w) -> {
                    String v1 = et1.getText() == null ? "" : et1.getText().toString().trim();
                    if (TextUtils.isEmpty(v1)) {
                        Toast.makeText(act, "第一项不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String v2 = et2 == null || et2.getText() == null
                            ? "" : et2.getText().toString().trim();
                    if (TextUtils.isEmpty(v2)) v2 = v1;    // 没写名字就用地址/号码
                    String open;
                    String close;
                    if (template.contains("%2$s")) {
                        String[] parts = template.split("%2\\$s", -1);
                        open = String.format(parts[0], v1);
                        close = parts.length > 1 ? parts[1] : "";
                    } else {
                        open = String.format(template, v1);
                        close = "";
                    }
                    // 显示文字来自弹窗（已预填选区），所以整块替换掉选区，不能再套一层
                    String ins = open + v2 + close;
                    replaceSelection(input, ins);
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dlg, act);
        if (et1.getText() != null && et1.getText().length() == 0) et1.requestFocus();
    }

    /** 用一段现成文本替换当前选区（没选区就插在光标处），光标落到末尾 */
    private static void replaceSelection(EditText input, String ins) {
        if (input == null) return;
        Editable e = input.getText();
        if (e == null) return;
        int st = Math.max(0, input.getSelectionStart());
        int en = Math.max(st, input.getSelectionEnd());
        e.replace(st, en, ins);
        input.setSelection(Math.min(st + ins.length(), e.length()));
    }

    /** 把标签套在选区上（有选区就包住选区，没选区就插一对并把光标放进中间） */
    private static void insertWrapped(EditText input, String open, String close) {
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
            preview.setText(renderPreview(act, raw));
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

    /**
     * BBCode → 可显示的富文本（build98）。
     *
     * <p><b>为什么不能直接 {@code Html.fromHtml} 了事</b>：只要有一步抛异常，旧实现就
     * {@code preview.setText(raw)} 把 BBCode 原文贴出来 —— 用户看到的就是
     * 「天天[color=#B30000]向上[/color]」，以为颜色坏了。真正的异常源已经修掉（代码块
     * {@code <pre>} 的段落边界，见 {@code BBCodeUtil}），但预览这种「边打字边跑」的地方
     * 必须再加一层兜底：逐级降级，任何一级都不至于把标记语言暴露给用户。
     *
     * <p>降级顺序：带样式的完整渲染 → 不带代码块底色的完整渲染 → 去掉 {@code <pre>}
     * 的渲染 → 纯文本（剥掉所有 BBCode/HTML 标记）。
     */
    public static CharSequence renderPreview(Activity act, String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String html;
        try {
            html = BBCodeUtil.convertBBCodeToHtml(raw);
        } catch (Throwable t) {
            return BBCodeUtil.toPlainText(raw);
        }
        try {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, null,
                    BBCodeUtil.createTagHandler(act));
        } catch (Throwable ignore) {
            // 代码块样式最可能是问题源：丢掉 TagHandler，颜色/字号仍然保留
        }
        try {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
        } catch (Throwable ignore) {
            // 还是不行就把 <pre> 整个拆掉（只降级代码块的样式，不动其余内容）
        }
        try {
            String flat = html.replaceAll("(?i)</?pre[^>]*>", "<br>");
            return Html.fromHtml(flat, Html.FROM_HTML_MODE_COMPACT);
        } catch (Throwable ignore) {
            // 到这一步就是输入本身有结构性问题了
        }
        return BBCodeUtil.toPlainText(raw);
    }

    // ==================== 选区操作 ====================

    /** 把标签套在选区上；没选区就插一对并把光标放中间 */
    public static void wrapSelection(EditText input, String open, String close) {
        insertWrapped(input, open, close);
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
            if (!Character.isWhitespace(sel.charAt(i))) n++;
        }
        int idx = 0;
        for (int i = 0; i < sel.length(); i++) {
            char ch = sel.charAt(i);
            if (Character.isWhitespace(ch)) {      // 空白不着色，省得一堆空标签
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
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(8 * d);
            bg.setColor(c);
            swatch.setBackground(bg);
            hexText.setText(hex(c));
        };

        root.addView(swatch);
        root.addView(hexText);
        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView lbl = new TextView(act);
            lbl.setText(names[i]);
            lbl.setTextSize(13f);
            lbl.setWidth((int) (24 * d));
            lbl.setTextColor(act.getColor(R.color.text_secondary));
            final int k = i;
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
