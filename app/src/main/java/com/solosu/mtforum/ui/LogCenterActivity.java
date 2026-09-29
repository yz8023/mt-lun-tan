package com.solosu.mtforum.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.solosu.mtforum.R;
import com.solosu.mtforum.session.HistoryStore;
import com.solosu.mtforum.ui.anim.Motion;
import com.solosu.mtforum.ui.detail.ThreadDetailActivity;
import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.util.PerfLog;
import com.solosu.mtforum.util.UnlockLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 记录中心（build76 重做）。
 *
 * <p>四个分页全部卡片平铺：浏览历史 / 自动解锁 / 加载耗时 / 运行日志。
 *
 * <p>两种显示模式：
 * <ul>
 *   <li><b>简洁</b> —— 只留关键信息，一行一条，扫着看</li>
 *   <li><b>详细</b> —— 完整时间、原因、参数全展开</li>
 * </ul>
 * 模式<b>只影响显示和复制内容，不影响实际记录</b> ——
 * 底层照常记全量，切到详细随时能看到完整信息。
 */
public class LogCenterActivity extends AppCompatActivity {

    private static final String PREF = "app_settings";
    private static final String KEY_MODE = "logcenter_detail_mode";

    private static final String[] TABS = {"浏览历史", "自动解锁", "加载耗时", "运行日志"};

    private int current = 0;
    private boolean detailMode = false;

    private LinearLayout tabBar;
    private LinearLayout listBox;
    private TextView empty;
    private TextView btnMode;

    private final SimpleDateFormat fShort = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private final SimpleDateFormat fLong =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    /** 当前页所有条目的可复制文本，供「复制本页」用 */
    private final List<String> copyBuffer = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_log_center);

        detailMode = getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_MODE, false);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        tabBar = findViewById(R.id.ll_tabs);
        listBox = findViewById(R.id.ll_list);
        empty = findViewById(R.id.tv_empty);
        btnMode = findViewById(R.id.btn_mode);
        TextView btnCopyAll = findViewById(R.id.btn_copy_all);
        TextView btnClear = findViewById(R.id.btn_clear);

        Motion.pressFeedback(btnMode, 0.93f);
        Motion.pressFeedback(btnCopyAll, 0.93f);
        Motion.pressFeedback(btnClear, 0.93f);

        btnMode.setOnClickListener(v -> {
            detailMode = !detailMode;
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean(KEY_MODE, detailMode).apply();
            updateModeLabel();
            render();
        });
        btnCopyAll.setOnClickListener(v -> copyAll());
        btnClear.setOnClickListener(v -> clearCurrent());

        updateModeLabel();
        buildTabs();
        render();
    }

    private void updateModeLabel() {
        btnMode.setText(detailMode ? "详细" : "简洁");
    }

    private void buildTabs() {
        tabBar.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setTextSize(13f);
            t.setGravity(Gravity.CENTER);
            t.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
            boolean on = i == current;
            t.setBackgroundResource(on ? R.drawable.chip_active_bg : R.drawable.bg_quick_reply_chip);
            t.setTextColor(getColor(on ? R.color.text_white : R.color.text_secondary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (8 * d);
            t.setLayoutParams(lp);
            Motion.pressFeedback(t, 0.93f);
            t.setOnClickListener(v -> {
                current = idx;
                buildTabs();
                render();
            });
            tabBar.addView(t);
        }
    }

    private void render() {
        listBox.removeAllViews();
        copyBuffer.clear();
        switch (current) {
            case 0: renderHistory(); break;
            case 1: renderUnlock(); break;
            case 2: renderPerf(); break;
            default: renderRunLog(); break;
        }
        empty.setVisibility(listBox.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        Motion.staggerChildren(listBox, 35);
    }

    // ==================== 浏览历史 ====================

    private void renderHistory() {
        for (final HistoryStore.Item it : HistoryStore.list(this)) {
            final String link = "https://bbs.binmt.cc/thread-" + it.tid + "-1-1.html";
            String sub;
            String copy;
            if (detailMode) {
                sub = "tid " + it.tid
                        + (TextUtils.isEmpty(it.author) ? "" : "   作者 " + it.author)
                        + "\n" + fLong.format(new Date(it.at))
                        + "\n" + link;
                copy = it.title + "\n" + link + "\n" + fLong.format(new Date(it.at))
                        + (TextUtils.isEmpty(it.author) ? "" : "  by " + it.author);
            } else {
                sub = fShort.format(new Date(it.at))
                        + (TextUtils.isEmpty(it.author) ? "" : "  ·  " + it.author);
                copy = it.title + "\n" + link;
            }
            copyBuffer.add(copy);
            addCard(it.title, sub, "#1A73E8", it.tid, link, copy);
        }
    }

    // ==================== 自动解锁 ====================

    private void renderUnlock() {
        for (final UnlockLog.Item it : UnlockLog.list()) {
            final String link = "https://bbs.binmt.cc/thread-" + it.tid + "-1-1.html";
            String title;
            String sub;
            String copy;
            if (detailMode) {
                title = it.mark + "   帖子 " + it.tid;
                sub = fLong.format(new Date(it.at))
                        + (TextUtils.isEmpty(it.detail) ? "" : "\n" + it.detail)
                        + "\n" + link;
                copy = "[" + it.mark + "] tid=" + it.tid + "  "
                        + fLong.format(new Date(it.at))
                        + (TextUtils.isEmpty(it.detail) ? "" : "\n" + it.detail)
                        + "\n" + link;
            } else {
                title = it.mark + "   " + it.tid;
                sub = fShort.format(new Date(it.at))
                        + (TextUtils.isEmpty(it.detail) ? ""
                            : "  ·  " + shorten(it.detail, 28));
                copy = "[" + it.mark + "] " + link;
            }
            copyBuffer.add(copy);
            String color = it.success ? "#16A34A"
                    : ("跳过".equals(it.mark) ? "#9CA3AF" : "#DC2626");
            addCard(title, sub, color, it.tid, link, copy);
        }
    }

    // ==================== 加载耗时 ====================

    private void renderPerf() {
        for (String line : splitLines(PerfLog.dump())) {
            String show = detailMode ? line : shorten(stripBracket(line), 72);
            copyBuffer.add(line);
            addMonoCard(show, line, "#EA8C00");
        }
    }

    // ==================== 运行日志（美化） ====================

    private void renderRunLog() {
        for (String line : splitLines(com.solosu.mtforum.ai.AiLog.dump())) {
            // 形如：09-30 02:21:56 [auto-unlock] 内容
            String time = "", tag = "", body = line;
            int lb = line.indexOf('[');
            int rb = line.indexOf(']');
            if (lb > 0 && rb > lb) {
                time = line.substring(0, lb).trim();
                tag = line.substring(lb + 1, rb);
                body = line.substring(rb + 1).trim();
            }
            String title = detailMode
                    ? (TextUtils.isEmpty(tag) ? body : tag + "  ·  " + body)
                    : shorten(body.isEmpty() ? line : body, 80);
            String sub = detailMode && !time.isEmpty() ? time : null;
            copyBuffer.add(line);
            addMonoCard2(title, sub, line, colorOfTag(tag));
        }
    }

    private static String colorOfTag(String tag) {
        if (tag == null) return "#9CA3AF";
        switch (tag) {
            case "auto-unlock": return "#16A34A";
            case "sign-in": return "#1A73E8";
            case "perf": return "#EA8C00";
            case "session": return "#7C3AED";
            case "auto-reply": return "#0891B2";
            default: return "#9CA3AF";
        }
    }

    // ==================== 卡片 ====================

    /** 带「打开帖子」的卡片 */
    private void addCard(String title, String sub, String accent,
                         final String tid, final String link, final String copyText) {
        View card = buildCard(title, sub, accent, false);
        card.setOnClickListener(v -> openThread(tid));
        card.setOnLongClickListener(v -> {
            showActions(title, tid, link, copyText);
            return true;
        });
        Motion.pressFeedback(card, 0.975f);
    }

    /** 等宽字体的纯文本卡片（加载耗时） */
    private void addMonoCard(String show, final String raw, String accent) {
        View card = buildCard(show, null, accent, true);
        card.setOnClickListener(v -> copy(raw, "已复制这条"));
        Motion.pressFeedback(card, 0.985f);
    }

    /** 运行日志卡片 */
    private void addMonoCard2(String title, String sub, final String raw, String accent) {
        View card = buildCard(title, sub, accent, true);
        card.setOnClickListener(v -> copy(raw, "已复制这条"));
        Motion.pressFeedback(card, 0.985f);
    }

    private View buildCard(String title, String sub, String accent, boolean mono) {
        float d = getResources().getDisplayMetrics().density;
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(12 * d);
        card.setCardElevation(1 * d);
        card.setCardBackgroundColor(getColor(R.color.surface));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = (int) (8 * d);
        card.setLayoutParams(clp);
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));

        View bar = new View(this);
        LinearLayout.LayoutParams blp =
                new LinearLayout.LayoutParams((int) (3 * d), LinearLayout.LayoutParams.MATCH_PARENT);
        blp.rightMargin = (int) (10 * d);
        bar.setLayoutParams(blp);
        bar.setBackgroundColor(Color.parseColor(accent));
        row.addView(bar);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(mono ? 11.5f : 14f);
        t.setTextColor(getColor(R.color.text_primary));
        if (mono) t.setTypeface(Typeface.MONOSPACE);
        if (!detailMode) {
            t.setMaxLines(mono ? 2 : 2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        col.addView(t);

        if (!TextUtils.isEmpty(sub)) {
            TextView s2 = new TextView(this);
            s2.setText(sub);
            s2.setTextSize(11.5f);
            s2.setTextColor(getColor(R.color.text_hint));
            s2.setPadding(0, (int) (3 * d), 0, 0);
            if (mono) s2.setTypeface(Typeface.MONOSPACE);
            col.addView(s2);
        }
        row.addView(col);
        card.addView(row);
        listBox.addView(card);
        return card;
    }

    // ==================== 操作 ====================

    private void showActions(String title, final String tid,
                             final String link, final String copyText) {
        final String[] items = {"打开帖子", "复制链接", "复制这条记录", "复制标题"};
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(shorten(title, 30))
                .setItems(items, (dlg, which) -> {
                    switch (which) {
                        case 0: openThread(tid); break;
                        case 1: copy(link, "链接已复制"); break;
                        case 2: copy(copyText, "记录已复制"); break;
                        default: copy(title, "标题已复制"); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(d, this);
    }

    private void openThread(String tid) {
        if (TextUtils.isEmpty(tid) || "null".equals(tid)) {
            Toast.makeText(this, "这条记录没有帖子编号", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, ThreadDetailActivity.class);
        it.putExtra("tid", tid);
        startActivity(it);
    }

    private void copyAll() {
        if (copyBuffer.isEmpty()) {
            Toast.makeText(this, "本页没有内容", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("===== ").append(TABS[current])
          .append(detailMode ? "（详细）" : "（简洁）").append(" =====\n");
        for (String s : copyBuffer) sb.append(s).append('\n');
        copy(sb.toString(), "已复制本页 " + copyBuffer.size() + " 条");
    }

    private void copy(String text, String toast) {
        if (TextUtils.isEmpty(text)) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("mtforum-log", text));
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private void clearCurrent() {
        switch (current) {
            case 0: HistoryStore.clear(this); break;
            case 1: UnlockLog.clear(); break;
            case 2: PerfLog.clear(); break;
            default:
                Toast.makeText(this, "运行日志由系统滚动清理", Toast.LENGTH_SHORT).show();
                return;
        }
        render();
        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
    }

    // ==================== 小工具 ====================

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return out;
        for (String l : text.split("\n")) {
            if (!l.trim().isEmpty()) out.add(l.trim());
        }
        return out;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 简洁模式去掉方括号里的节流统计，太占地方 */
    private static String stripBracket(String line) {
        int i = line.indexOf('[');
        return i > 0 ? line.substring(0, i).trim() : line;
    }
}
