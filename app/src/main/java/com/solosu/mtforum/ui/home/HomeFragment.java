package com.solosu.mtforum.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ThreadAdapter;
import com.solosu.mtforum.databinding.FragmentHomeBinding;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.util.NavigationHelper;
import com.solosu.mtforum.ui.space.UserProfileActivity;
import com.solosu.mtforum.ui.search.SearchActivity;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.List;

/**
 * 首页 Fragment
 * 展示最新帖子列表,支持下拉刷新、翻页加载、热板推荐、搜索跳转
 */
public class HomeFragment extends Fragment implements com.solosu.mtforum.ui.Refreshable {

    private FragmentHomeBinding binding;
    private HttpClient httpClient;
    private ThreadAdapter threadAdapter;
    private int currentPage = 1;
    private boolean isLoading = false;
    private boolean hasMore = true;
    private String guideView = "newthread";
    private int loadGeneration = 0;
    private static final int PAGE_SIZE = 20;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        httpClient = HttpClient.getInstance();

        // build99: 顶栏的 MCP 按钮已随 MCP 功能一起移除（功能无效且白占体积）。

        binding.ivSearch.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SearchActivity.class);
            startActivity(intent);
        });
        binding.guideFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id=checkedIds.get(0);
            // build95: 「热门」chip 已移除。站点 mobile 端的
            // forum.php?mod=guide&view=hot 返回的不是帖子列表，而是导读首页
            // （每日签到 / 精华推荐 / 积分商城 + 一条滚动文字栏），
            // 实测 48887 字节里只有 15 个 thread 链接且全在 comiis_mh_kxtxt 里，
            // 没有 mmlist_li_box / comiis_pyqlist 容器，解析器 0 条 -> 整页空白。
            // 去掉 mobile=2、加 &type=hot、改 forumdisplay&filter=heat 全都试过，
            // 站点 mobile 端就是没有热帖列表视图。留着它只会给用户一个空页面。
            guideView=id==R.id.chip_new?"new":id==R.id.chip_digest?"digest":"newthread";
            refreshThreads();
        });
        // 搜索图标毛玻璃背景（build99: 顶栏只剩搜索了，MCP 按钮已移除）
        binding.ivSearch.setBackground(FrostedGlassDrawable.create(requireContext(), 10f));

        // RecyclerView + ThreadAdapter
        threadAdapter = new ThreadAdapter(requireContext());
        threadAdapter.setOnItemClickListener((thread, position) -> {
            NavigationHelper.openThread(requireContext(), thread);
        });

        threadAdapter.setOnUserClickListener(thread -> {
            if (thread == null || TextUtils.isEmpty(thread.getAuthorUid())) return;
            Intent intent = new Intent(requireContext(), UserProfileActivity.class);
            intent.putExtra("uid", thread.getAuthorUid());
            intent.putExtra("username", thread.getAuthor());
            startActivity(intent);
        });

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(threadAdapter);

        // 滚动监听实现翻页加载
        binding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0 || isLoading || !hasMore) return;
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null) {
                    int visibleItemCount = lm.getChildCount();
                    int totalItemCount = lm.getItemCount();
                    int firstVisibleItemPosition = lm.findFirstVisibleItemPosition();
                    if (visibleItemCount + firstVisibleItemPosition >= totalItemCount - 2) {
                        loadMoreThreads();
                    }
                }
            }
        });

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener(this::refreshThreads);
        // build65: 下滑隐藏底栏 / 上滑显示（可在侧边栏关闭）
        com.solosu.mtforum.ui.NavScrollHelper.attach(binding.recyclerView, this);
        binding.swipeRefresh.setColorSchemeResources(
                com.google.android.material.R.color.design_default_color_primary,
                android.R.color.holo_orange_light,
                android.R.color.holo_green_light
        );

        // build86: 首页不再显示「热帖排行」头部（用户明确要求去掉）。
        // 顺带少发一个 forum.php?mod=guide&view=hot 请求，首屏更快。

        // 首次加载
        refreshThreads();
    }

    private void refreshThreads() {
        currentPage = 1;
        hasMore = true;
        loadGeneration++;
        loadThreads(currentPage, true);
    }

    private void loadMoreThreads() {
        if (isLoading || !hasMore) return;
        currentPage++;
        loadThreads(currentPage, false);
    }

    private void loadThreads(int page, boolean isRefresh) {
        com.solosu.mtforum.network.RequestThrottle.markForeground();
        isLoading = true;
        binding.swipeRefresh.setRefreshing(true);
        final int requestGeneration=loadGeneration;
        final String requestedView=guideView;

        new java.lang.Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = ForumParser.getGuideUrl(requestedView, page);
                    String html = httpClient.get(url);
                    List<Thread> threads = ForumParser.parseThreadList(html);

                    if (!isAdded()) return;
                    // 黑名单过滤:拉黑作者的帖子直接不进列表
                    if (threads != null) {
                        java.util.Set<String> black = com.solosu.mtforum.session.BlacklistManager.uidSet(requireContext());
                        if (!black.isEmpty()) {
                            java.util.Iterator<Thread> it = threads.iterator();
                            while (it.hasNext()) {
                                Thread t = it.next();
                                if (t != null && t.getAuthorUid() != null && black.contains(t.getAuthorUid())) it.remove();
                            }
                        }
                    }
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded() || requestGeneration!=loadGeneration) return;
                        if (threads != null && !threads.isEmpty()) {
                            if (isRefresh) {
                                threadAdapter.setThreadList(threads);
                            } else {
                                threadAdapter.addThreads(threads);
                            }
                            hasMore = threads.size() >= PAGE_SIZE;
                            // build63(分支): 取消列表页收藏数预取。原来每加载一页要发 20 个请求，
                            //   是 403 风控的第二大来源；改为进详情页时回填缓存。
                        } else {
                            hasMore = false;
                            if (isRefresh) {
                                threadAdapter.setThreadList(null);
                            }
                        }
                        isLoading = false;
                        binding.swipeRefresh.setRefreshing(false);
                    });
                } catch (Exception e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        isLoading = false;
                        binding.swipeRefresh.setRefreshing(false);
                        if (isRefresh && threadAdapter.getItemCount() == 0) {
                            android.widget.Toast.makeText(requireContext(),
                                    "加载失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }


    @Override
    public void onResume() {
        super.onResume();
        // build65: 账号切换过就自动重载本页
        if (consumeAccountSwitched()) { onTabReselected(); }
        if (threadAdapter != null && threadAdapter.getItemCount() == 0) {
            refreshThreads();
        } else if (threadAdapter != null) {
            // build80(分支): 从详情页返回时用点赞缓存刷新卡片，零额外请求
            applyCachedLikes();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ==================== build63: 底栏再点刷新 ====================
    @Override
    public void onTabReselected() {
        if (binding == null) return;
        binding.recyclerView.smoothScrollToPosition(0);
        if (binding.swipeRefresh.isRefreshing()) return;   // 正在刷就别叠请求
        binding.swipeRefresh.setRefreshing(true);
        refreshThreads();
    }


    /** build65: 记录上次渲染时的账号代数，切号后自动重载，不用用户手动下拉 */
    private int lastAccountEpoch = com.solosu.mtforum.session.AccountManager.currentEpoch();

    private boolean consumeAccountSwitched() {
        int now = com.solosu.mtforum.session.AccountManager.currentEpoch();
        if (now != lastAccountEpoch) {
            lastAccountEpoch = now;
            return true;
        }
        return false;
    }

    /** build80(分支): 用详情页点赞缓存回填列表卡片点赞数 */
    private void applyCachedLikes() {
        if (threadAdapter == null) return;
        try {
            int n = threadAdapter.getItemCount();
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                com.solosu.mtforum.model.Thread t = threadAdapter.getItem(i);
                if (t == null || android.text.TextUtils.isEmpty(t.getTid())) continue;
                Integer likes = com.solosu.mtforum.session.PostCountsCache.getLikes(t.getTid());
                if (likes != null && likes != t.getLikes()) {
                    t.setLikes(likes);
                    changed = true;
                }
            }
            // build71: 隐藏标记也是进过详情页才知道的，不能只在点赞数变了时才刷新，
            // 否则「隐藏」标签永远不出现 —— 这就是之前标签没显示的原因。
            threadAdapter.notifyDataSetChanged();
        } catch (Exception ignore) {
        }
    }
}
