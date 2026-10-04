package com.solosu.mtforum.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.solosu.mtforum.R;
import com.solosu.mtforum.session.QuickReplyManager;
import com.solosu.mtforum.ui.anim.Motion;

import java.util.ArrayList;
import java.util.List;

/**
 * 快捷回复管理面板（build98 新增）。
 *
 * <p>用户反馈：「默认回复增加一些快捷选择的词条，用勾选的方式，增加编辑或者删除，
 * 以及一些快捷键，比如已知的 {title}，诸如此类」。
 *
 * <p>于是这个面板里每条短语三件事：
 * <ul>
 *   <li><b>勾选框</b> —— 勾上的才出现在回复框的快捷条上（不删也能临时收起）</li>
 *   <li><b>编辑</b> —— 改文字；编辑器顶部有「插入变量」按钮和变量说明</li>
 *   <li><b>删除</b> —— 单条删除，带二次确认</li>
 * </ul>
 * 底部是「新增一条 / 全部勾选 / 全不勾选 / 恢复默认」。
 */
public final class QuickReplyManagerSheet extends BottomSheetDialog {

    /** 面板关闭时回调（用于刷新回复框里的快捷条） */
    public interface OnChanged {
        void onChanged();
    }

    private final Activity act;
    private final OnChanged callback;
    private final List<QuickReplyManager.Item> items = new ArrayList<>();

    private LinearLayout listBox;
    private TextView tvCount;

    public QuickReplyManagerSheet(@NonNull Activity activity, OnChanged cb) {
        super(activity);
        this.act = activity;
        this.callback = cb;
        this.items.addAll(QuickReplyManager.items(activity));
    }

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        View v = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_quick_reply_manager, null, false);
        setContentView(v);

        listBox = v.findViewById(R.id.ll_items);
        tvCount = v.findViewById(R.id.tv_count);

        v.findViewById(R.id.btn_close).setOnClickListener(x -> close());
        v.findViewById(R.id.btn_add).setOnClickListener(x -> editItem(null));
        v.findViewById(R.id.btn_check_all).setOnClickListener(x -> {
            for (QuickReplyManager.Item it : items) it.enabled = true;
            render();
        });
        v.findViewById(R.id.btn_uncheck_all).setOnClickListener(x -> {
            for (QuickReplyManager.Item it : items) it.enabled = false;
            render();
        });
        v.findViewById(R.id.btn_reset).setOnClickListener(x -> confirmReset());
        v.findViewById(R.id.btn_vars).setOnClickListener(x -> showVariableHelp());

        render();
    }

    // ==================== 列表 ====================

    private void render() {
        if (listBox == null) return;
        listBox.removeAllViews();
        float d = act.getResources().getDisplayMetrics().density;
        int pad = (int) (6 * d);

        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final QuickReplyManager.Item item = items.get(i);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad, 0, pad);

            // 勾选：控制这条要不要出现在快捷条上
            CheckBox cb = new CheckBox(act);
            cb.setChecked(item.enabled);
            cb.setOnCheckedChangeListener((btn, checked) -> {
                item.enabled = checked;
                persist();
                updateCount();
            });

            TextView text = new TextView(act);
            text.setText(item.text);
            text.setTextSize(14f);
            text.setTextColor(act.getColor(item.enabled
                    ? R.color.text_primary : R.color.text_hint));
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPadding((int) (4 * d), 0, (int) (4 * d), 0);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            text.setLayoutParams(tlp);
            text.setOnClickListener(x -> editItem(item));

            TextView edit = smallChip("编辑");
            edit.setOnClickListener(x -> editItem(item));

            TextView del = smallChip("删除");
            del.setTextColor(act.getColor(R.color.text_secondary));
            del.setOnClickListener(x -> confirmDelete(index));

            row.addView(cb);
            row.addView(text);
            row.addView(edit);
            row.addView(del);
            listBox.addView(row);

            View divider = new View(act);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) d));
            divider.setBackgroundColor(act.getColor(R.color.divider));
            listBox.addView(divider);
        }

        if (items.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("还没有快捷回复，点下面「新增一条」加一个吧");
            empty.setTextSize(13f);
            empty.setTextColor(act.getColor(R.color.text_hint));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, (int) (24 * d), 0, (int) (24 * d));
            listBox.addView(empty);
        }
        updateCount();
    }

    private TextView smallChip(String label) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(12f);
        t.setTextColor(act.getColor(R.color.primary));
        t.setBackgroundResource(R.drawable.bg_quick_reply_chip);
        float d = act.getResources().getDisplayMetrics().density;
        t.setPadding((int) (10 * d), (int) (5 * d), (int) (10 * d), (int) (5 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (6 * d);
        t.setLayoutParams(lp);
        Motion.pressFeedback(t, 0.92f);
        return t;
    }

    private void updateCount() {
        if (tvCount == null) return;
        int on = 0;
        for (QuickReplyManager.Item it : items) if (it.enabled) on++;
        tvCount.setText("已选 " + on + " / " + items.size() + " 条");
    }

    private void persist() {
        QuickReplyManager.saveItems(act, items);
        if (callback != null) callback.onChanged();
    }

    // ==================== 增删改 ====================

    private void editItem(final QuickReplyManager.Item item) {
        final boolean isNew = item == null;
        if (isNew && items.size() >= QuickReplyManager.MAX_COUNT) {
            Toast.makeText(act, "最多 " + QuickReplyManager.MAX_COUNT + " 条",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        float d = act.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setTextSize(14f);
        et.setMinLines(2);
        et.setMaxLines(4);
        et.setHint("例如：{author} 的 {title} 写得不错");
        if (item != null) et.setText(item.text);
        box.addView(et);

        TextView hint = new TextView(act);
        hint.setText("可插入变量：" + shortVarList());
        hint.setTextSize(11f);
        hint.setTextColor(act.getColor(R.color.text_hint));
        hint.setPadding(0, (int) (6 * d), 0, 0);
        box.addView(hint);

        TextView vars = new TextView(act);
        vars.setText("查看/插入变量");
        vars.setTextSize(12f);
        vars.setTextColor(act.getColor(R.color.primary));
        vars.setPadding(0, (int) (8 * d), 0, 0);
        vars.setOnClickListener(x -> showVarInserter(et));
        box.addView(vars);

        Dialog dlg = new AlertDialog.Builder(act)
                .setTitle(isNew ? "新增快捷回复" : "编辑快捷回复")
                .setView(box)
                .setPositiveButton("保存", (dd, w) -> {
                    String text = et.getText() == null ? "" : et.getText().toString().trim();
                    if (TextUtils.isEmpty(text)) {
                        Toast.makeText(act, "内容不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (text.length() > QuickReplyManager.MAX_LENGTH) {
                        text = text.substring(0, QuickReplyManager.MAX_LENGTH);
                    }
                    if (isNew) {
                        items.add(new QuickReplyManager.Item(text, true));
                    } else {
                        item.text = text;
                    }
                    persist();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dlg, act);
    }

    private void confirmDelete(final int index) {
        if (index < 0 || index >= items.size()) return;
        final QuickReplyManager.Item item = items.get(index);
        Dialog d = new AlertDialog.Builder(act)
                .setTitle("删除快捷回复")
                .setMessage("确定删除「" + item.text + "」？")
                .setPositiveButton("删除", (dd, w) -> {
                    items.remove(index);
                    persist();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(d, act);
    }

    private void confirmReset() {
        Dialog d = new AlertDialog.Builder(act)
                .setTitle("恢复默认")
                .setMessage("恢复成内置的默认短语，当前自定义的内容会被清掉。")
                .setPositiveButton("恢复", (dd, w) -> {
                    QuickReplyManager.resetToDefault(act);
                    items.clear();
                    items.addAll(QuickReplyManager.defaultItems());
                    persist();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(d, act);
    }

    // ==================== 变量 ====================

    private String shortVarList() {
        StringBuilder sb = new StringBuilder();
        for (String[] v : QuickReplyManager.VARIABLES) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(v[0]);
        }
        return sb.toString();
    }

    /** 变量说明弹窗（只读） */
    private void showVariableHelp() {
        StringBuilder sb = new StringBuilder();
        for (String[] v : QuickReplyManager.VARIABLES) {
            sb.append(v[0]).append("　→　").append(v[1]).append('\n');
        }
        sb.append("\n在短语里写上这些占位符，插入到回复框时会自动换成当前帖子的内容。");
        Dialog d = new AlertDialog.Builder(act)
                .setTitle("可用变量")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
        DialogHelper.applyToAlertDialog(d, act);
    }

    /** 变量点选插入（在编辑短语时用） */
    private void showVarInserter(final EditText target) {
        final String[] labels = new String[QuickReplyManager.VARIABLES.length];
        for (int i = 0; i < QuickReplyManager.VARIABLES.length; i++) {
            labels[i] = QuickReplyManager.VARIABLES[i][0]
                    + "　" + QuickReplyManager.VARIABLES[i][1];
        }
        Dialog d = new AlertDialog.Builder(act)
                .setTitle("插入变量")
                .setItems(labels, (dd, which) -> {
                    String token = QuickReplyManager.VARIABLES[which][0];
                    int st = Math.max(0, target.getSelectionStart());
                    android.text.Editable e = target.getText();
                    if (e == null) return;
                    e.insert(st, token);
                    target.setSelection(Math.min(st + token.length(), e.length()));
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(d, act);
    }

    private void close() {
        if (callback != null) callback.onChanged();
        dismiss();
    }

    /** 静态入口，省得调用方每次 new 一遍 */
    public static void show(Activity act, OnChanged cb) {
        if (act == null) return;
        QuickReplyManagerSheet sheet = new QuickReplyManagerSheet(act, cb);
        sheet.show();
        DialogHelper.applyToBottomSheet(sheet, act);
    }

}
