package com.solosu.mtforum.ui.search;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ThreadAdapter;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.SearchHistoryStore;
import com.solosu.mtforum.ui.detail.ThreadDetailActivity;
import com.solosu.mtforum.ui.space.UserProfileActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 搜索结果页。
 *
 * <p>build63 改为<b>按需分页</b>：滚到底才取下一页，串行单请求 + 相邻 900ms 间隔。
 * 原来是抓完第 1 页后用 4 线程并发把剩余所有页一口气抓完，命中多的关键词
 * 一次就是二三十个请求，论坛前面的阿里云 ESA 必定 403。
 */
public class SearchActivity extends AppCompatActivity {

    private Toolbar toolbar;
    private EditText etSearch;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextView tvError;
    private ThreadAdapter threadAdapter;

    // 排序相关
    private LinearLayout layoutSortBar;
    private TextView btnSortLastpost, btnSortDateline, btnSortReplies;
    // build63: 默认改为「最新发布」。
    // 原来默认 lastpost（最新回复）—— 服务端要对整个结果集按最后回复时间重排，
    // 命中面通常也更大、页数更多，配合下面那套"一次性并发抓完所有页"极易吃 403。
    private static final String DEFAULT_SORT = "dateline";

    private String currentSortBy = DEFAULT_SORT; // dateline / lastpost / replies
    private String pendingKeyword = null;

    // ==================== build63: 按需分页 ====================
    private ProgressBar progressLoadMore;
    private LinearLayout layoutSearchHistory;
    private ChipGroup chipSearchHistory;
    private TextView btnClearSearchHistory;
    private String searchId = null;
    private int totalPages = 1;
    private int loadedPage = 0;
    private boolean loading = false;
    private boolean exhausted = false;
    private volatile int searchGeneration = 0;
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    /** 上一次请求的时刻，用于给相邻两页之间强制留间隔，避免被风控 */
    private long lastRequestAt = 0L;
    /** 相邻两页之间的最小间隔（毫秒） */
    private static final long PAGE_INTERVAL_MS = 900L;
    private final List<Thread> allResults = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        toolbar = findViewById(R.id.toolbar);
        etSearch = findViewById(R.id.et_search);
        progressBar = findViewById(R.id.progress_bar);
        recyclerView = findViewById(R.id.recycler_view);
        tvEmpty = findViewById(R.id.tv_empty);
        tvError = findViewById(R.id.tv_error);
        layoutSortBar = findViewById(R.id.layout_sort_bar);
        btnSortLastpost = findViewById(R.id.btn_sort_lastpost);
        btnSortDateline = findViewById(R.id.btn_sort_dateline);
        btnSortReplies = findViewById(R.id.btn_sort_replies);

        // === Toolbar ===
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        toolbar.setNavigationOnClickListener(v -> finish());

        // === RecyclerView ===
        threadAdapter = new ThreadAdapter(this);
        threadAdapter.setOnItemClickListener((thread, position) -> {
            Intent intent = new Intent(this, ThreadDetailActivity.class);
            intent.putExtra("tid", thread.getTid());
            startActivity(intent);
        });
        threadAdapter.setOnUserClickListener(thread -> {
            if (thread == null || TextUtils.isEmpty(thread.getAuthorUid())) return;
            Intent intent = new Intent(this, UserProfileActivity.class);
            intent.putExtra("uid", thread.getAuthorUid());
            intent.putExtra("username", thread.getAuthor());
            startActivity(intent);
        });
        final LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(threadAdapter);
        progressLoadMore = findViewById(R.id.progress_load_more);
        layoutSearchHistory = findViewById(R.id.layout_search_history);
        chipSearchHistory = findViewById(R.id.chip_search_history);
        btnClearSearchHistory = findViewById(R.id.btn_clear_search_history);
        btnClearSearchHistory.setOnClickListener(v -> confirmClearSearchHistory());
        renderSearchHistory();

        // build63: 滚到底再取下一页，而不是开搜就把所有页并发抓完
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loading || exhausted) return;
                int visible = layoutManager.getChildCount();
                int total = layoutManager.getItemCount();
                int first = layoutManager.findFirstVisibleItemPosition();
                if (first + visible >= total - 3) {
                    loadNextPage();
                }
            }
        });

        // === 搜索按钮 ===
        findViewById(R.id.btn_search).setOnClickListener(v -> performSearch());

        // === 键盘搜索动作 ===
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });

        // === 排序切换 ===
        btnSortLastpost.setOnClickListener(v -> switchSort("lastpost"));
        btnSortDateline.setOnClickListener(v -> switchSort("dateline"));
        btnSortReplies.setOnClickListener(v -> switchSort("replies"));

        // === Intent 携带关键词自动搜索 ===
        String keyword = getIntent().getStringExtra("keyword");
        if (!TextUtils.isEmpty(keyword)) {
            etSearch.setText(keyword);
            performSearch();
        }
    }

    /**
     * 切换排序方式
     */
    private void switchSort(String orderby) {
        if (orderby.equals(currentSortBy)) return;
        currentSortBy = orderby;
        updateSortChips();
        if (!TextUtils.isEmpty(pendingKeyword)) {
            startSearch(pendingKeyword);
        }
    }

    /**
     * 更新排序按钮的激活/非激活样式
     */
    private void updateSortChips() {
        btnSortLastpost.setBackgroundResource(
                "lastpost".equals(currentSortBy) ? R.drawable.chip_active_bg : R.drawable.chip_inactive_bg);
        btnSortLastpost.setTextColor(ContextCompat.getColor(this,
                "lastpost".equals(currentSortBy) ? R.color.text_white : R.color.text_hint));

        btnSortDateline.setBackgroundResource(
                "dateline".equals(currentSortBy) ? R.drawable.chip_active_bg : R.drawable.chip_inactive_bg);
        btnSortDateline.setTextColor(ContextCompat.getColor(this,
                "dateline".equals(currentSortBy) ? R.color.text_white : R.color.text_hint));

        btnSortReplies.setBackgroundResource(
                "replies".equals(currentSortBy) ? R.drawable.chip_active_bg : R.drawable.chip_inactive_bg);
        btnSortReplies.setTextColor(ContextCompat.getColor(this,
                "replies".equals(currentSortBy) ? R.color.text_white : R.color.text_hint));
    }

    private void performSearch() {
        String keyword = etSearch.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            etSearch.setError(getString(R.string.search_hint));
            return;
        }
        currentSortBy = DEFAULT_SORT;
        updateSortChips();
        layoutSortBar.setVisibility(View.VISIBLE);
        SearchHistoryStore.add(this, keyword);
        renderSearchHistory();
        startSearch(keyword);
    }

    /** 显示最近搜索；点选重搜，点芯片上的叉号删除单项。 */
    private void renderSearchHistory() {
        if (chipSearchHistory == null || layoutSearchHistory == null) return;
        List<String> history = SearchHistoryStore.list(this);
        chipSearchHistory.removeAllViews();
        layoutSearchHistory.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        if (btnClearSearchHistory != null) {
            btnClearSearchHistory.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        }
        for (String term : history) {
            Chip chip = new Chip(this);
            chip.setText(term);
            chip.setCheckable(false);
            chip.setClickable(true);
            chip.setCloseIconVisible(true);
            chip.setCloseIconResource(android.R.drawable.ic_menu_close_clear_cancel);
            chip.setCloseIconContentDescription("删除搜索词 " + term);
            chip.setOnClickListener(v -> {
                etSearch.setText(term);
                etSearch.setSelection(term.length());
                performSearch();
            });
            chip.setOnCloseIconClickListener(v -> {
                SearchHistoryStore.remove(this, term);
                renderSearchHistory();
            });
            chipSearchHistory.addView(chip);
        }
    }

    private void confirmClearSearchHistory() {
        if (SearchHistoryStore.list(this).isEmpty()) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空搜索历史")
                .setMessage("删除本机保存的全部搜索词？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    SearchHistoryStore.clear(this);
                    renderSearchHistory();
                })
                .show();
    }

    /** 重置分页状态并拉第一页 */
    private void startSearch(String keyword) {
        pendingKeyword = keyword;
        searchGeneration++;
        loading = false;
        allResults.clear();
        threadAdapter.setThreadList(null);
        searchId = null;
        totalPages = 1;
        loadedPage = 0;
        exhausted = false;

        if (!HttpClient.getInstance().isLoggedIn()) {
            progressBar.setVisibility(View.GONE);
            recyclerView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.GONE);
            tvError.setText(R.string.search_login_required);
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);
        tvError.setVisibility(View.GONE);

        loadNextPage();
    }

    /**
     * 取下一页。
     *
     * <p>build63 重写：原实现抓完第 1 页后，会用 4 个线程<b>把剩下所有页一次性并发抓完</b>
     * —— 结果多的关键词能一口气打出二三十个请求，bbs.binmt.cc 前面挂着阿里云 ESA，
     * 这种突发流量必吃 IP 级 403，表现就是"搜索经常失败/被风控"。
     *
     * <p>现在改成：滚到底才取下一页，串行、单请求，且相邻两次强制间隔 900ms。
     */
    private void loadNextPage() {
        if (loading || exhausted) return;
        if (TextUtils.isEmpty(pendingKeyword)) return;
        if (loadedPage >= totalPages && loadedPage > 0) {
            exhausted = true;
            return;
        }
        loading = true;
        final int generation = searchGeneration;
        final int page = loadedPage + 1;
        final String keyword = pendingKeyword;
        final String orderby = currentSortBy;
        final String pageSearchId = searchId;
        if (page > 1) progressLoadMore.setVisibility(View.VISIBLE);

        // 单线程队列保证不同关键词的查询也不会并发撞论坛；generation 让旧结果完全失效。
        searchExecutor.execute(() -> {
            String failure = null;
            List<Thread> pageResults = null;
            String nextSearchId = pageSearchId;
            int nextTotalPages = totalPages;
            try {
                long wait = PAGE_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
                if (lastRequestAt > 0 && wait > 0) java.lang.Thread.sleep(wait);
                if (generation != searchGeneration) return;
                lastRequestAt = System.currentTimeMillis();

                String url;
                if (page == 1) {
                    url = ForumParser.getSearchUrl(keyword, 1, orderby);
                } else if (!TextUtils.isEmpty(pageSearchId)) {
                    url = ForumParser.getSearchPageUrl(pageSearchId, page, orderby);
                } else {
                    failure = "论坛没有返回分页标识，无法安全加载后续结果";
                    url = null;
                }
                if (url != null) {
                    String html = HttpClient.getInstance().get(url);
                    if (isBlocked(html)) {
                        failure = "被论坛防护拦截（403），请求太密集了。歇一会儿再搜，或换个更具体的关键词。";
                    } else {
                        pageResults = ForumParser.parseSearchResults(html);
                        if (pageResults == null) pageResults = new ArrayList<>();
                        if (page == 1) {
                            nextSearchId = ForumParser.extractSearchId(html);
                            nextTotalPages = Math.max(1, ForumParser.parseSearchTotalPages(html));
                        }
                    }
                }
            } catch (InterruptedException e) {
                java.lang.Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                failure = "网络错误：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }

            final String fFailure = failure;
            final List<Thread> fResults = pageResults;
            final String fSearchId = nextSearchId;
            final int fTotalPages = nextTotalPages;
            runOnUiThread(() -> {
                if (generation != searchGeneration || isFinishing()
                        || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                loading = false;
                progressBar.setVisibility(View.GONE);
                progressLoadMore.setVisibility(View.GONE);

                if (fFailure != null) {
                    if (allResults.isEmpty()) {
                        tvError.setText(fFailure);
                        tvError.setVisibility(View.VISIBLE);
                    } else {
                        android.widget.Toast.makeText(this, fFailure,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                    exhausted = true;
                    return;
                }

                if (page == 1) {
                    searchId = fSearchId;
                    totalPages = fTotalPages;
                }
                loadedPage = page;
                int added = mergeResults(fResults);
                if (loadedPage >= totalPages || (added == 0 && page > 1)) exhausted = true;

                if (allResults.isEmpty()) {
                    tvEmpty.setText(R.string.search_no_results);
                    tvEmpty.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                    threadAdapter.setThreadList(new ArrayList<>(allResults));
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        searchGeneration++;
        searchExecutor.shutdownNow();
        super.onDestroy();
    }

    /** 合并去重，返回新增条数 */
    private int mergeResults(List<Thread> pageResults) {
        if (pageResults == null || pageResults.isEmpty()) return 0;
        int added = 0;
        for (Thread t : pageResults) {
            boolean dup = false;
            for (Thread existing : allResults) {
                if (existing.getTid() != null && existing.getTid().equals(t.getTid())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                allResults.add(t);
                added++;
            }
        }
        return added;
    }

    private static boolean isBlocked(String html) {
        if (TextUtils.isEmpty(html)) return false;
        return html.contains("you have been blocked")
                || html.contains("403 Forbidden")
                || html.contains("Access Denied")
                || html.contains("Attention Required");
    }
}