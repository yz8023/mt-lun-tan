package com.solosu.mtforum.ui.widget;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.solosu.mtforum.R;
import com.solosu.mtforum.ui.anim.Motion;

/**
 * 可折叠 + 一键复制的代码块（build63 新增）。
 *
 * <p>之前代码块是直接用 {@code Html.fromHtml} 的 TagHandler 画成正文里的一段 span ——
 * 几百行的代码会把整条回复撑到翻不完，而且没法单独复制。
 * 现在把 {@code <pre>} 从正文里抽出来，每块独立成一个可折叠卡片。
 *
 * <p>超过 {@link #COLLAPSE_THRESHOLD} 行默认折叠，只露前 {@link #COLLAPSED_LINES} 行。
 */
public class CodeBlockView extends LinearLayout {

    /** 超过多少行才折叠（短代码没必要折） */
    private static final int COLLAPSE_THRESHOLD = 12;
    /** 折叠态显示多少行 */
    private static final int COLLAPSED_LINES = 8;

    private TextView tvLang;
    private TextView tvLines;
    private TextView btnCopy;
    private TextView btnToggle;
    private TextView tvContent;
    private TextView tvGutter;
    private TextView tvFade;
    private View header;

    private String rawCode = "";
    private boolean expanded = true;
    private boolean collapsible = false;

    public CodeBlockView(Context context) {
        this(context, null);
    }

    public CodeBlockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_code_block, this, true);
        tvLang = findViewById(R.id.tv_code_lang);
        tvLines = findViewById(R.id.tv_code_lines);
        btnCopy = findViewById(R.id.btn_code_copy);
        btnToggle = findViewById(R.id.btn_code_toggle);
        tvContent = findViewById(R.id.tv_code_content);
        tvGutter = findViewById(R.id.tv_code_gutter);
        tvFade = findViewById(R.id.tv_code_fade);
        header = findViewById(R.id.code_header);

        Motion.pressFeedback(btnCopy, 0.90f);
        Motion.pressFeedback(btnToggle, 0.90f);

        btnCopy.setOnClickListener(v -> copyToClipboard());
        btnToggle.setOnClickListener(v -> setExpanded(!expanded));
        tvFade.setOnClickListener(v -> setExpanded(true));
        // 点工具条空白处也能折叠/展开
        header.setOnClickListener(v -> {
            if (collapsible) setExpanded(!expanded);
        });
    }

    /**
     * 绑定一段代码。
     *
     * @param lang 语言标签，可为空
     * @param code 代码原文（已还原实体、保留换行）
     */
    public void bind(String lang, String code) {
        rawCode = code == null ? "" : code;
        tvContent.setText(rawCode);

        tvLang.setText(TextUtils.isEmpty(lang) ? "代码" : lang.trim());

        int lineCount = countLines(rawCode);
        tvLines.setText(lineCount + " 行");
        buildGutter(lineCount);

        collapsible = lineCount > COLLAPSE_THRESHOLD;
        btnToggle.setVisibility(collapsible ? VISIBLE : GONE);
        // 长代码默认折叠
        setExpanded(!collapsible);
    }

    private void setExpanded(boolean expand) {
        expanded = expand;
        int total = countLines(rawCode);
        if (!collapsible) {
            tvContent.setMaxLines(Integer.MAX_VALUE);
            tvFade.setVisibility(GONE);
            btnToggle.setVisibility(GONE);
            buildGutter(total);
            return;
        }
        tvContent.setMaxLines(expand ? Integer.MAX_VALUE : COLLAPSED_LINES);
        tvFade.setVisibility(expand ? GONE : VISIBLE);
        btnToggle.setText(expand ? "收起" : "展开");
        // 折叠时装订线也只画可见的那几行，否则会比代码高出一截
        buildGutter(expand ? total : Math.min(total, COLLAPSED_LINES));
    }

    /** 画行号装订线；这些数字只在这里，不进 rawCode，所以复制永远干净 */
    private void buildGutter(int lines) {
        if (tvGutter == null) return;
        if (lines <= 0) {
            tvGutter.setVisibility(GONE);
            return;
        }
        tvGutter.setVisibility(VISIBLE);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            if (i > 1) sb.append('\n');
            sb.append(i);
        }
        tvGutter.setText(sb.toString());
    }

    /** 取当前这块的干净代码（不含行号） */
    public String getCode() {
        return rawCode;
    }

    private void copyToClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("code", rawCode));
            Toast.makeText(getContext(),
                    "已复制 " + countLines(rawCode) + " 行代码", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static int countLines(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }
}
