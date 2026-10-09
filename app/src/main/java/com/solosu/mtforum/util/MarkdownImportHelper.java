package com.solosu.mtforum.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.solosu.mtforum.R;
import com.solosu.mtforum.ui.widget.BBCodeEditor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown → BBCode 导入面板，支持预览、复制、插入光标和替换正文。 */
public final class MarkdownImportHelper {
    private static final Pattern FIRST_TITLE = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");

    private MarkdownImportHelper() { }

    public static void show(Activity activity, EditText target, EditText titleField) {
        if (activity == null || target == null || activity.isFinishing() || activity.isDestroyed()) return;
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View root = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_markdown_import, null);
        sheet.setContentView(root);

        EditText input = root.findViewById(R.id.md_input);
        ScrollView inputPanel = root.findViewById(R.id.md_scroll_input);
        ScrollView previewPanel = root.findViewById(R.id.md_scroll_preview);
        TextView preview = root.findViewById(R.id.md_preview);
        TextView count = root.findViewById(R.id.md_char_count);
        TextView inputTab = root.findViewById(R.id.md_tab_input);
        TextView previewTab = root.findViewById(R.id.md_tab_preview);
        TextView copy = root.findViewById(R.id.md_btn_copy);
        TextView insert = root.findViewById(R.id.md_btn_insert);
        TextView replace = root.findViewById(R.id.md_btn_replace);
        int[] activeTab = {0};
        String[] converted = {""};
        String[] extractedTitle = {""};

        Runnable render = () -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            String body = raw;
            extractedTitle[0] = "";
            if (titleField != null && !raw.isEmpty()) {
                int newline = raw.indexOf('\n');
                String firstLine = (newline < 0 ? raw : raw.substring(0, newline)).trim();
                Matcher title = FIRST_TITLE.matcher(firstLine);
                if (title.matches()) {
                    extractedTitle[0] = title.group(1).trim();
                    body = newline < 0 ? "" : raw.substring(newline + 1);
                }
            }
            converted[0] = MarkdownBbcodeConverter.convert(body);
            count.setText(raw.length() + " 字符 → " + converted[0].length() + " 字符");
            if (TextUtils.isEmpty(converted[0])) {
                preview.setText("输入 Markdown 后可预览");
                preview.setTextColor(activity.getColor(R.color.text_hint));
            } else {
                preview.setTextColor(activity.getColor(R.color.text_primary));
                preview.setText(BBCodeEditor.renderPreview(activity, converted[0]));
            }
        };

        Runnable updateTabs = () -> {
            boolean showInput = activeTab[0] == 0;
            inputPanel.setVisibility(showInput ? View.VISIBLE : View.GONE);
            previewPanel.setVisibility(showInput ? View.GONE : View.VISIBLE);
            inputTab.setBackgroundResource(showInput ? R.drawable.chip_active_bg : R.drawable.chip_inactive_bg);
            previewTab.setBackgroundResource(showInput ? R.drawable.chip_inactive_bg : R.drawable.chip_active_bg);
            inputTab.setTextColor(activity.getColor(showInput ? R.color.text_white : R.color.text_secondary));
            previewTab.setTextColor(activity.getColor(showInput ? R.color.text_secondary : R.color.text_white));
        };
        updateTabs.run();
        render.run();

        inputTab.setOnClickListener(v -> { activeTab[0] = 0; updateTabs.run(); });
        previewTab.setOnClickListener(v -> { render.run(); activeTab[0] = 1; updateTabs.run(); });
        root.findViewById(R.id.md_btn_close).setOnClickListener(v -> sheet.dismiss());
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) { render.run(); }
        });

        copy.setOnClickListener(v -> {
            render.run();
            if (TextUtils.isEmpty(converted[0])) {
                Toast.makeText(activity, "没有可复制的转换结果", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("BBCode", converted[0]));
                Toast.makeText(activity, "已复制 BBCode", Toast.LENGTH_SHORT).show();
            }
        });

        insert.setOnClickListener(v -> {
            render.run();
            if (TextUtils.isEmpty(converted[0])) {
                Toast.makeText(activity, "没有可用的转换结果", Toast.LENGTH_SHORT).show();
                return;
            }
            fillTitle(titleField, extractedTitle[0]);
            insertAtCursor(target, converted[0]);
            sheet.dismiss();
            Toast.makeText(activity, titleField != null && !TextUtils.isEmpty(extractedTitle[0])
                    ? "标题已填入，正文已插入" : "已插入到光标处", Toast.LENGTH_SHORT).show();
        });

        replace.setOnClickListener(v -> {
            render.run();
            if (TextUtils.isEmpty(converted[0])) {
                Toast.makeText(activity, "没有可用的转换结果", Toast.LENGTH_SHORT).show();
                return;
            }
            Runnable apply = () -> {
                fillTitle(titleField, extractedTitle[0]);
                target.setText(converted[0]);
                target.setSelection(converted[0].length());
                sheet.dismiss();
                Toast.makeText(activity, titleField != null && !TextUtils.isEmpty(extractedTitle[0])
                        ? "标题已填入，正文已替换" : "已替换正文", Toast.LENGTH_SHORT).show();
            };
            Editable existing = target.getText();
            if (existing != null && !TextUtils.isEmpty(existing.toString().trim())) {
                new AlertDialog.Builder(activity)
                        .setTitle("替换正文")
                        .setMessage("将用转换结果替换编辑器中的全部内容。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("替换", (dialog, which) -> apply.run())
                        .show();
            } else {
                apply.run();
            }
        });

        sheet.show();
    }

    public static void show(Activity activity, EditText target) {
        show(activity, target, null);
    }

    private static void fillTitle(EditText titleField, String title) {
        if (titleField == null || TextUtils.isEmpty(title)) return;
        titleField.setText(title);
        titleField.setSelection(titleField.length());
    }

    private static void insertAtCursor(EditText target, String text) {
        Editable editable = target.getText();
        if (editable == null) {
            target.setText(text);
            return;
        }
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        if (start < 0 || start > editable.length()) start = editable.length();
        if (end < start || end > editable.length()) end = start;
        editable.replace(start, end, text);
        target.setSelection(Math.min(start + text.length(), editable.length()));
    }
}
