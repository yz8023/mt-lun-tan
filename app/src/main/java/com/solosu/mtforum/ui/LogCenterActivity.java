package com.solosu.mtforum.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.solosu.mtforum.R;
import com.solosu.mtforum.session.HistoryStore;
import com.solosu.mtforum.ui.anim.Motion;
import com.solosu.mtforum.ui.detail.ThreadDetailActivity;
import com.solosu.mtforum.util.PerfLog;
import com.solosu.mtforum.util.UnlockLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志中心（build75 新增）。
 *
 * <p>把原来挤在一个 AlertDialog 里的四类记录拆成独立页面 + 分段卡片：
 * 浏览历史 / 自动解锁 / 加载耗时 / 运行日志。
 * 前两类每条都能<b>点击直接打开对应帖子</b>。
 */
public class LogCenterActivity extends AppCompatActivity {

    private static final String[] TABS = {"浏览历史", "自动解锁", "加载耗时", "运行日志"};
    private int current = 0;

    private LinearLayout tabBar;
    private LinearLayout listBox;
    private TextView empty;

    private final SimpleDateFormat fmt =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_log_center);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_clear).setOnClickListener(v -> clearCurrent());
        tabBar = findViewById(R.id.ll_tabs);
        listBox = findViewById(R.id.ll_list);
        empty = findViewById(R.id.tv_empty);

        buildTabs();
        render();
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
        switch (current) {
            case 0: renderHistory(); break;
            case 1: renderUnlock(); break;
            case 2: renderText(PerfLog.dump()); break;
            default: renderText(com.solosu.mtforum.ai.AiLog.dump()); break;
        }
        empty.setVisibility(listBox.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        Motion.staggerChildren(listBox, 40);
    }

    // ==================== 浏览历史 ====================

    private void renderHistory() {
        List<HistoryStore.Item> list = HistoryStore.list(this);
        for (final HistoryStore.Item it : list) {
            String sub = fmt.format(new Date(it.at))
                    + (TextUtils.isEmpty(it.author) ? "" : "  ·  " + it.author);
            addCard(it.title, sub, "#1A73E8", () -> openThread(it.tid));
        }
    }

    // ==================== 自动解锁 ====================

    private void renderUnlock() {
        List<UnlockLog.Item> list = UnlockLog.list();
        for (final UnlockLog.Item it : list) {
            String title = it.mark + "  ·  帖子 " + it.tid;
            String sub = fmt.format(new Date(it.at))
                    + (TextUtils.isEmpty(it.detail) ? "" : "\n" + it.detail);
            String color = it.success ? "#16A34A" : ("跳过".equals(it.mark) ? "#9CA3AF" : "#DC2626");
            addCard(title, sub, color, () -> openThread(it.tid));
        }
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

    // ==================== 纯文本类 ====================

    private void renderText(String text) {
        if (TextUtils.isEmpty(text)) return;
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            addCard(line, null, null, null);
        }
    }

    // ==================== 卡片 ====================

    private void addCard(String title, String sub, String accent, final Runnable onClick) {
        float d = getResources().getDisplayMetrics().density;
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(12 * d);
        card.setCardElevation(1 * d);
        card.setCardBackgroundColor(getColor(R.color.surface));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = (int) (8 * d);
        card.setLayoutParams(clp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));

        if (accent != null) {
            View bar = new View(this);
            LinearLayout.LayoutParams blp =
                    new LinearLayout.LayoutParams((int) (3 * d), LinearLayout.LayoutParams.MATCH_PARENT);
            blp.rightMargin = (int) (10 * d);
            bar.setLayoutParams(blp);
            bar.setBackgroundColor(android.graphics.Color.parseColor(accent));
            row.addView(bar);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(accent == null ? 11.5f : 14f);
        t.setTextColor(getColor(R.color.text_primary));
        if (accent == null) t.setTypeface(android.graphics.Typeface.MONOSPACE);
        else { t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        col.addView(t);

        if (!TextUtils.isEmpty(sub)) {
            TextView s2 = new TextView(this);
            s2.setText(sub);
            s2.setTextSize(11.5f);
            s2.setTextColor(getColor(R.color.text_hint));
            s2.setPadding(0, (int) (3 * d), 0, 0);
            col.addView(s2);
        }
        row.addView(col);

        if (onClick != null) {
            TextView go = new TextView(this);
            go.setText("打开");
            go.setTextSize(12f);
            go.setTextColor(getColor(R.color.primary));
            go.setBackgroundResource(R.drawable.bg_pill_soft);
            go.setPadding((int) (10 * d), (int) (5 * d), (int) (10 * d), (int) (5 * d));
            row.addView(go);
            Motion.pressFeedback(card, 0.97f);
            card.setOnClickListener(v -> onClick.run());
        }
        card.addView(row);
        listBox.addView(card);
    }

    private void clearCurrent() {
        switch (current) {
            case 0: HistoryStore.clear(this); break;
            case 1: UnlockLog.clear(); break;
            case 2: PerfLog.clear(); break;
            default:
                Toast.makeText(this, "运行日志请在设置里清理", Toast.LENGTH_SHORT).show();
                return;
        }
        render();
        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
    }
}
