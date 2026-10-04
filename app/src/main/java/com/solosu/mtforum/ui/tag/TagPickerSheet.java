package com.solosu.mtforum.ui.tag;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

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
import java.util.Set;

/**
 * build96: 发帖页的「快捷标签」选择器。
 *
 * <p>标签来源就是站点的标签云 {@code misc.php?mod=tag&mobile=2}，
 * 不手打 —— 站点标签是 {@code misc.php?mod=tag&id=X&type=thread} 的固定集合，
 * 手打拼错站点直接不认。
 *
 * <p>支持多选，确认后以逗号拼接返回（Discuz 的 tags 字段就是逗号分隔）。
 */
public final class TagPickerSheet extends BottomSheetDialog {

    public interface OnPicked { void onPicked(String tagsCsv); }

    private final OnPicked callback;
    private final Set<String> picked = new LinkedHashSet<>();
    private final List<ForumParser.TagItem> all = new ArrayList<>();

    private RecyclerView recycler;
    private TextView tvEmpty, tvConfirm;
    private EditText etSearch;
    private Inner adapter;

    public TagPickerSheet(@NonNull android.content.Context c, @NonNull OnPicked cb) {
        super(c);
        this.callback = cb;
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
        etSearch = v.findViewById(R.id.et_search);

        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new Inner();
        recycler.setAdapter(adapter);

        v.findViewById(R.id.btn_close).setOnClickListener(x -> dismiss());
        tvConfirm.setOnClickListener(x -> {
            StringBuilder sb = new StringBuilder();
            for (String s : picked) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
            if (callback != null) callback.onPicked(sb.toString());
            dismiss();
        });

        load(ForumParser.getTagIndexUrl());
    }

    private void load(String url) {
        tvEmpty.setText("加载中…");
        tvEmpty.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<ForumParser.TagItem> res = new ArrayList<>();
            String err = null;
            try {
                res = ForumParser.parseTagList(HttpClient.getInstance().get(url));
            } catch (Exception e) { err = e.getMessage(); }
            final List<ForumParser.TagItem> out = res;
            final String ferr = err;
            recycler.post(() -> {
                all.clear();
                all.addAll(out);
                adapter.notifyDataSetChanged();
                boolean empty = out.isEmpty();
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (empty) {
                    tvEmpty.setText(ferr != null ? ("加载失败：" + ferr) : "没有找到标签");
                }
            });
        }).start();
    }

    private class Inner extends RecyclerView.Adapter<VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_tag_pick, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ForumParser.TagItem tag = all.get(pos);
            boolean on = picked.contains(tag.name);
            h.name.setText(tag.name);
            h.name.setSelected(on);
            h.name.setTextColor(on ? 0xFFFFFFFF : 0xFF333333);
            h.name.setBackgroundResource(on
                    ? R.drawable.bg_tag_picked : R.drawable.bg_tag_normal);
            h.name.setOnClickListener(v -> {
                if (picked.contains(tag.name)) picked.remove(tag.name);
                else picked.add(tag.name);
                notifyItemChanged(pos);
                tvConfirm.setText(picked.isEmpty() ? "确定"
                        : ("确定（" + picked.size() + "）"));
            });
        }

        @Override public int getItemCount() { return all.size(); }
    }

    private static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        VH(View v) { super(v); name = v.findViewById(R.id.tv_name); }
    }
}
