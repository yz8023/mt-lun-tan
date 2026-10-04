package com.solosu.mtforum.ui.tag;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ThreadAdapter;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.util.NavigationHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * build96: 标签页。
 *
 * <p>站点（bbs.binmt.cc，克米 mobile 模板）的标签系统在 {@code misc.php?mod=tag}：
 * <ul>
 *   <li>标签云：{@code misc.php?mod=tag&mobile=2}，项是
 *       {@code <a href="misc.php?mod=tag&id=413&type=thread&mobile=2" title="剧情">}</li>
 *   <li>搜索：{@code misc.php?mod=tag&name=剧情&type=thread&mobile=2}（GET 实测可行）</li>
 *   <li>标签详情：{@code misc.php?mod=tag&id=413&type=thread&mobile=2}，
 *       帖子卡片复用列表页的 mmlist_li_box 结构</li>
 * </ul>
 *
 * <p>一个页面同时承担「标签云 / 快速搜索 / 标签下帖子列表」三件事：
 * 搜索框常驻顶部，搜出来是标签列表，点标签进该标签下的帖子列表。
 */
public class TagActivity extends AppCompatActivity {

    private static final int MODE_TAGS = 0;
    private static final int MODE_THREADS = 1;

    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    private RecyclerView recycler;
    private TextView tvTitle, tvSubtitle, tvEmpty;
    private EditText etSearch;

    private TagAdapter tagAdapter;
    private ThreadAdapter threadAdapter;
    private int mode = MODE_TAGS;
    private String currentTagId, currentTagName;

    /** build98: 其它页面（帖子详情里的标签胶囊）用这个直接打开某个标签 */
    public static void openTag(android.content.Context ctx, String id, String name) {
        if (ctx == null || TextUtils.isEmpty(id)) return;
        android.content.Intent it = new android.content.Intent(ctx, TagActivity.class);
        it.putExtra("tag_id", id);
        it.putExtra("tag_name", name == null ? "" : name);
        ctx.startActivity(it);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_tag);

        swipe = findViewById(R.id.swipe_refresh);
        recycler = findViewById(R.id.recycler_view);
        tvTitle = findViewById(R.id.tv_title);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        tvEmpty = findViewById(R.id.tv_empty);
        etSearch = findViewById(R.id.et_tag_search);

        recycler.setLayoutManager(new LinearLayoutManager(this));

        tagAdapter = new TagAdapter();
        tagAdapter.setOnTagClick(t -> loadThreads(t.id, t.name));

        threadAdapter = new ThreadAdapter(this);
        threadAdapter.setOnItemClickListener((thread, pos) ->
                NavigationHelper.openThread(this, thread));

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_tag_search).setOnClickListener(v -> {
            String q = etSearch.getText() == null ? "" : etSearch.getText().toString().trim();
            if (TextUtils.isEmpty(q)) loadTagCloud();
            else searchTags(q);
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            String q = etSearch.getText() == null ? "" : etSearch.getText().toString().trim();
            if (!TextUtils.isEmpty(q)) searchTags(q);
            return true;
        });
        swipe.setOnRefreshListener(this::reload);

        // build98: 从帖子详情页的标签胶囊进来时，直接落在该标签的帖子列表上
        String initId = getIntent() == null ? null : getIntent().getStringExtra("tag_id");
        String initName = getIntent() == null ? null : getIntent().getStringExtra("tag_name");
        if (!TextUtils.isEmpty(initId)) {
            loadThreads(initId, TextUtils.isEmpty(initName) ? "标签" : initName);
        } else {
            loadTagCloud();
        }
    }

    private void reload() {
        if (mode == MODE_THREADS && !TextUtils.isEmpty(currentTagId)) {
            loadThreads(currentTagId, currentTagName);
        } else {
            loadTagCloud();
        }
    }

    // ═══ 标签云 ═══
    private void loadTagCloud() {
        mode = MODE_TAGS;
        tvTitle.setText("标签");
        tvSubtitle.setText("全部标签");
        load(ForumParser.getTagIndexUrl(), false, null);
    }

    // ═══ 按名字搜标签 ═══
    private void searchTags(String q) {
        mode = MODE_TAGS;
        tvTitle.setText("搜索标签");
        tvSubtitle.setText("“" + q + "” 的搜索结果");
        load(ForumParser.getTagSearchUrl(q), false, null);
    }

    // ═══ 某标签下的帖子 ═══
    private void loadThreads(String id, String name) {
        mode = MODE_THREADS;
        currentTagId = id;
        currentTagName = TextUtils.isEmpty(name) ? "标签" : name;
        tvTitle.setText("标签：" + currentTagName);
        tvSubtitle.setText("该标签下的帖子");
        load(ForumParser.getTagDetailUrl(id), true, currentTagName);
    }

    /** build96: 统一加载。threads=true 时把结果当帖子列表，否则当标签列表。 */
    private void load(String url, boolean threads, String tagName) {
        swipe.setRefreshing(true);
        // build96: 必须写全限定名 —— 本类 import 了 com.solosu.mtforum.model.Thread，
        // 裸写 Thread 会解析到模型类上去。
        new java.lang.Thread(() -> {
            List<ForumParser.TagItem> tags = new ArrayList<>();
            List<Thread> list = new ArrayList<>();
            String err = null;
            try {
                String html = HttpClient.getInstance().get(url);
                if (threads) list = ForumParser.parseTagThreads(html);
                else tags = ForumParser.parseTagList(html);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<ForumParser.TagItem> ft = tags;
            final List<Thread> fl = list;
            final String ferr = err;
            runOnUiThread(() -> {
                swipe.setRefreshing(false);
                boolean empty;
                if (threads) {
                    threadAdapter.setThreadList(fl.isEmpty() ? null : fl);
                    recycler.setAdapter(threadAdapter);
                    empty = fl.isEmpty();
                } else {
                    tagAdapter.setTags(ft);
                    recycler.setAdapter(tagAdapter);
                    empty = ft.isEmpty();
                }
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
                // 站点在该标签没有关联帖子时，页面正文就是「没有相关内容」，
                // 解析出来自然是空列表 —— 这里给一句人话，别让用户以为页面坏了。
                if (empty && threads) {
                    tvEmpty.setText("该标签下暂无帖子");
                } else if (empty) {
                    tvEmpty.setText(ferr != null ? ("加载失败：" + ferr) : "没有找到相关标签");
                } else {
                    tvEmpty.setText("暂无标签");
                }
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        // 从「标签下帖子」返回到标签列表，而不是直接退出
        if (mode == MODE_THREADS) {
            loadTagCloud();
            return;
        }
        super.onBackPressed();
    }

    // ═══ 标签列表适配器 ═══
    public interface OnTagClick { void onClick(ForumParser.TagItem tag); }

    private static class TagAdapter extends RecyclerView.Adapter<TagVH> {
        private List<ForumParser.TagItem> data = new ArrayList<>();
        private OnTagClick listener;

        void setTags(List<ForumParser.TagItem> d) {
            this.data = d == null ? new ArrayList<>() : d;
            notifyDataSetChanged();
        }

        void setOnTagClick(OnTagClick l) { this.listener = l; }

        @NonNull @Override
        public TagVH onCreateViewHolder(@NonNull ViewGroup p, int v) {
            return new TagVH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_tag, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull TagVH h, int pos) {
            ForumParser.TagItem t = data.get(pos);
            h.tv.setText(t.name);
            h.tv.setOnClickListener(v -> { if (listener != null) listener.onClick(t); });
        }

        @Override public int getItemCount() { return data.size(); }
    }

    private static class TagVH extends RecyclerView.ViewHolder {
        final TextView tv;
        TagVH(View v) { super(v); tv = v.findViewById(R.id.tv_tag); }
    }
}
