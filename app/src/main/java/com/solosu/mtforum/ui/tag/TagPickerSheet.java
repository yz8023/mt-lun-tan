package com.solosu.mtforum.ui.tag;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.solosu.mtforum.R;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 发帖页的「快捷标签」选择器（build96 新增，build98 重做）。
 *
 * <p>标签来源就是站点的标签云 {@code misc.php?mod=tag&mobile=2}（101 个标签），
 * 不手打 —— 站点标签是 {@code misc.php?mod=tag&id=X&type=thread} 的固定集合，
 * 手打拼错站点直接不认。
 *
 * <p>build98 补齐了用户反馈的两件事：
 * <ul>
 *   <li><b>能搜</b> —— build96 的搜索框是个摆设（布局里压根没有这个控件，
 *       {@code findViewById} 拿到 null，输入框不存在也从不联网）。现在顶部就是一个
 *       真搜索框：本地先过滤（101 个标签瞬间出结果），回车再走站点搜索
 *       {@code misc.php?mod=tag&name=XXX&type=thread&mobile=2}</li>
 *   <li><b>能自己加</b> —— 站点标签汇里没有的词，允许手动填一个（Discuz 允许在
 *       发帖时新建标签），并给出去重与数量提示</li>
 * </ul>
 *
 * <p>多选，确认后以逗号拼接返回（Discuz 的 tags 字段就是逗号分隔）。
 */
public final class TagPickerSheet extends BottomSheetDialog {

    public interface OnPicked {
        void onPicked(String tagsCsv);
    }

    private final OnPicked callback;
    private final Set<String> picked = new LinkedHashSet<>();
    private final List<ForumParser.TagItem> all = new ArrayList<>();
    /** 站点标签汇里的全部标签（本地过滤用，不受搜索结果影响） */
    private final List<ForumParser.TagItem> cloud = new ArrayList<>();

    private RecyclerView recycler;
    private TextView tvEmpty, tvConfirm, tvTitle;
    private EditText etSearch;
    private LinearLayout llPicked;
    private Inner adapter;
    private String remoteQuery = "";

    public TagPickerSheet(@NonNull android.content.Context c, OnPicked cb) {
        this(c, null, cb);
    }

    /**
     * @param initialCsv 已经选好的标签（编辑帖子时带进来的），可为空
     */
    public TagPickerSheet(@NonNull android.content.Context c, String initialCsv, OnPicked cb) {
        super(c);
        this.callback = cb;
        if (!TextUtils.isEmpty(initialCsv)) {
            for (String s : initialCsv.split("[,，\\s]+")) {
                String v = s.trim();
                if (!v.isEmpty()) picked.add(v);
            }
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        View v = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_tag_picker, null, false);
        setContentView(v);

        recycler = v.findViewById(R.id.recycler_tags);
        tvEmpty = v.findViewById(R.id.tv_empty);
        tvConfirm = v.findViewById(R.id.tv_confirm);
        tvTitle = v.findViewById(R.id.tv_title);
        etSearch = v.findViewById(R.id.et_search);
        llPicked = v.findViewById(R.id.ll_picked);

        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new Inner();
        recycler.setAdapter(adapter);

        v.findViewById(R.id.btn_close).setOnClickListener(x -> dismiss());
        v.findViewById(R.id.btn_add_custom).setOnClickListener(x -> addCustomTag());
        tvConfirm.setOnClickListener(x -> {
            StringBuilder sb = new StringBuilder();
            for (String s : picked) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
            if (callback != null) callback.onPicked(sb.toString());
            dismiss();
        });

        // 搜索：边打边本地过滤；回车/搜索键走站点搜索（能用站点自己的标签库兜住长尾）
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b2, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b2, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    String q = s == null ? "" : s.toString().trim();
                    if (q.equals(remoteQuery)) return;
                    remoteQuery = "";
                    filterLocal(q);
                }
            });
            etSearch.setOnEditorActionListener((tv, actionId, ev) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    searchRemote(etSearch.getText() == null ? ""
                            : etSearch.getText().toString().trim());
                    return true;
                }
                return false;
            });
        }
        renderPicked();
        load(ForumParser.getTagIndexUrl());
    }

    // ==================== 数据 ====================

    private void load(String url) {
        showEmpty("加载中…");
        new Thread(() -> {
            List<ForumParser.TagItem> res = new ArrayList<>();
            String err = null;
            try {
                res = ForumParser.parseTagList(HttpClient.getInstance().get(url));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<ForumParser.TagItem> out = res;
            final String ferr = err;
            recycler.post(() -> {
                all.clear();
                all.addAll(out);
                cloud.clear();
                cloud.addAll(out);
                adapter.notifyDataSetChanged();
                if (out.isEmpty()) {
                    showEmpty(ferr != null ? ("加载失败：" + ferr) : "没有找到标签");
                } else {
                    hideEmpty();
                    if (tvTitle != null) tvTitle.setText("标签汇（" + out.size() + "）");
                }
            });
        }).start();
    }

    /** 本地过滤：101 个标签就地筛，不用等网络 */
    private void filterLocal(String q) {
        all.clear();
        if (TextUtils.isEmpty(q)) {
            all.addAll(cloud);
        } else {
            String lower = q.toLowerCase(Locale.ROOT);
            for (ForumParser.TagItem t : cloud) {
                String name = t.name == null ? "" : t.name.toLowerCase(Locale.ROOT);
                if (name.contains(lower) || (t.id != null && t.id.contains(lower))) all.add(t);
            }
        }
        adapter.notifyDataSetChanged();
        if (all.isEmpty()) {
            showEmpty(cloud.isEmpty() ? "加载中…"
                    : "标签汇里没有「" + q + "」\n可以点下方「手动添加」直接用它");
        } else {
            hideEmpty();
        }
        if (tvTitle != null && !TextUtils.isEmpty(q)) {
            tvTitle.setText("搜索：" + q);
        }
    }

    /** 站点标签搜索（GET，实测可行）：补上本地标签汇里没有的标签 */
    private void searchRemote(String q) {
        if (TextUtils.isEmpty(q)) return;
        showEmpty("搜索中…");
        new Thread(() -> {
            List<ForumParser.TagItem> res = new ArrayList<>();
            try {
                res = ForumParser.parseTagList(
                        HttpClient.getInstance().get(ForumParser.getTagSearchUrl(q)));
            } catch (Exception ignored) {
            }
            final List<ForumParser.TagItem> out = res;
            recycler.post(() -> {
                cachedSearchResult.clear();
                cachedSearchResult.addAll(out);
                all.clear();
                all.addAll(out);
                adapter.notifyDataSetChanged();
                remoteQuery = q;
                if (out.isEmpty()) {
                    showEmpty("站点里没有「" + q + "」这个标签\n可以点下方「手动添加」新建一个");
                } else {
                    hideEmpty();
                    if (tvTitle != null) tvTitle.setText("站点搜索：" + q + "（" + out.size() + "）");
                }
            });
        }).start();
    }

    private final List<ForumParser.TagItem> cachedSearchResult = new ArrayList<>();

    private void addCustomTag() {
        String typed = etSearch == null || etSearch.getText() == null
                ? "" : etSearch.getText().toString().trim();
        if (TextUtils.isEmpty(typed)) {
            Toast.makeText(getContext(), "先在搜索框里输入标签名", Toast.LENGTH_SHORT).show();
            return;
        }
        for (String s : typed.split("[,，\\s]+")) {
            String v = s.trim();
            if (!v.isEmpty()) toggle(v);
        }
        if (etSearch != null) etSearch.setText("");
        Toast.makeText(getContext(), "已添加：" + typed, Toast.LENGTH_SHORT).show();
    }

    private void toggle(String name) {
        if (picked.contains(name)) {
            picked.remove(name);
        } else {
            if (picked.size() >= 5) {
                Toast.makeText(getContext(), "站点一般最多 5 个标签，先去掉一个再加",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            picked.add(name);
        }
        adapter.notifyDataSetChanged();
        renderPicked();
    }

    // ==================== 视图 ====================

    private void renderPicked() {
        if (llPicked == null) return;
        llPicked.removeAllViews();
        float d = getContext().getResources().getDisplayMetrics().density;
        for (final String name : new ArrayList<>(picked)) {
            TextView chip = new TextView(getContext());
            chip.setText(name + "  ✕");
            chip.setTextSize(12f);
            chip.setTextColor(getContext().getColor(R.color.primary));
            chip.setBackgroundResource(R.drawable.bg_quick_reply_chip);
            int ph = (int) (10 * d), pv = (int) (5 * d);
            chip.setPadding(ph, pv, ph, pv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * d);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(x -> toggle(name));
            llPicked.addView(chip);
        }
        if (picked.isEmpty()) {
            TextView hint = new TextView(getContext());
            hint.setText("还没选标签（可多选，一般不超过 5 个）");
            hint.setTextSize(11f);
            hint.setTextColor(getContext().getColor(R.color.text_hint));
            hint.setGravity(Gravity.CENTER_VERTICAL);
            llPicked.addView(hint);
        }
        if (tvConfirm != null) {
            tvConfirm.setText(picked.isEmpty() ? "确定" : ("确定（" + picked.size() + "）"));
        }
    }

    private void showEmpty(String text) {
        if (tvEmpty == null) return;
        tvEmpty.setText(text);
        tvEmpty.setVisibility(View.VISIBLE);
        if (recycler != null) recycler.setVisibility(View.GONE);
    }

    private void hideEmpty() {
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
        if (recycler != null) recycler.setVisibility(View.VISIBLE);
    }

    private class Inner extends RecyclerView.Adapter<VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_tag_pick, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ForumParser.TagItem tag = all.get(pos);
            boolean on = picked.contains(tag.name);
            h.name.setText(on ? ("✓ " + tag.name) : tag.name);
            h.name.setSelected(on);
            h.name.setTextColor(on ? 0xFFFFFFFF : 0xFF333333);
            h.name.setBackgroundResource(on
                    ? R.drawable.bg_tag_picked : R.drawable.bg_tag_normal);
            h.name.setOnClickListener(v -> {
                int i = h.getAdapterPosition();
                if (i == RecyclerView.NO_POSITION) return;
                toggle(all.get(i).name);
            });
        }

        @Override
        public int getItemCount() {
            return all.size();
        }
    }

    private static class VH extends RecyclerView.ViewHolder {
        final TextView name;

        VH(View v) {
            super(v);
            name = v.findViewById(R.id.tv_name);
        }
    }
}
