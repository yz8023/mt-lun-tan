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
import com.solosu.mtforum.ai.AiChatActivity;
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
    private final android.os.Handler mcpHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mcpRefresh=new Runnable(){@Override public void run(){updateMcpCard();mcpHandler.postDelayed(this,1000);}};
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

        // 搜索图标点击 -> 打开搜索页面
        binding.ivAi.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AiChatActivity.class)));

        binding.ivSearch.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SearchActivity.class);
            startActivity(intent);
        });
        binding.guideFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id=checkedIds.get(0);
            guideView=id==R.id.chip_new?"new":id==R.id.chip_hot?"hot":id==R.id.chip_digest?"digest":"newthread";
            refreshThreads();
        });
        binding.cardHomeMcp.setOnClickListener(v->startActivity(new Intent(requireContext(),com.solosu.mtforum.mcp.McpSettingsActivity.class)));
        binding.btnHomeMcp.setOnClickListener(v->handleMcpButton());
        updateMcpCard();
        // 搜索图标毛玻璃背景
        binding.ivAi.setBackground(FrostedGlassDrawable.create(requireContext(), 10f));
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

        // 加载热板推荐
        loadHotBoards();

        // 首次加载
        refreshThreads();
    }

    /**
     * 加载热帖排行列表
     */
    private void loadHotBoards() {
        new java.lang.Thread(() -> {
            try {
                String url = "https://bbs.binmt.cc/forum.php?mod=guide&view=hot&mobile=2";
                String html = httpClient.get(url);
                if (!isAdded() || android.text.TextUtils.isEmpty(html)) return;

                // 从 HTML 中提取所有带排名序号的热帖
                // 匹配: <li class="b_t"><a href="...thread-数字-1-1.html" title="标题"><em class="...">排名</em>标题</a></li>
                java.util.ArrayList<String[]> list = new java.util.ArrayList<>();
                int searchFrom = 0;
                while (list.size() < 10) {
                    int tidx = html.indexOf("thread-", searchFrom);
                    if (tidx < 0) break;
                    int endIdx = html.indexOf("-1-1.html", tidx);
                    if (endIdx < 0) { searchFrom = tidx + 7; continue; }
                    String tid = html.substring(tidx + 7, endIdx);
                    
                    // 检查这个链接后面是否有 <em>数字</em>(排名序号),有则说明是热帖排行
                    int afterHref = html.indexOf(">", endIdx + 9);
                    if (afterHref < 0) { searchFrom = endIdx + 9; continue; }
                    int emStart = html.indexOf("<em", afterHref);
                    if (emStart < 0 || emStart > afterHref + 200) { searchFrom = endIdx + 9; continue; }
                    int emContentStart = html.indexOf(">", emStart);
                    if (emContentStart < 0) { searchFrom = endIdx + 9; continue; }
                    int emContentEnd = html.indexOf("</em>", emContentStart);
                    if (emContentEnd < 0) { searchFrom = endIdx + 9; continue; }
                    String rankStr = html.substring(emContentStart + 1, emContentEnd);
                    // 排名序号必须是纯数字
                    if (!rankStr.matches("\\d+")) { searchFrom = endIdx + 9; continue; }
                    
                                        // 提取 title 属性(在 href 后面查找)
                    int titleStart = html.indexOf("title=\"", endIdx);
                    if (titleStart < 0 || titleStart > endIdx + 300) { searchFrom = endIdx + 9; continue; }
                    int titleEnd = html.indexOf("\"", titleStart + 7);
                    if (titleEnd < 0) { searchFrom = endIdx + 9; continue; }
                    String title = html.substring(titleStart + 7, titleEnd);
                    
                    list.add(new String[]{tid, title});
                    searchFrom = endIdx + 9;
                }
                if (list.isEmpty()) return;

                final java.util.ArrayList<String[]> topThreads = new java.util.ArrayList<>(list);
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || binding == null || threadAdapter == null) return;

                    float density = requireContext().getResources().getDisplayMetrics().density;
                    LinearLayout headerContainer = new LinearLayout(requireContext());
                    headerContainer.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins((int)(12*density), (int)(8*density), (int)(12*density), (int)(10*density));
                    headerContainer.setLayoutParams(lp);
                    headerContainer.setPadding((int)(6*density), (int)(10*density), (int)(10*density), (int)(10*density));
                    headerContainer.setBackground(
                            com.solosu.mtforum.ui.widget.FrostedGlassDrawable.create(requireContext(), 14f));
                    headerContainer.setClipToOutline(true);

                    // 标题行: 热帖排行 + 查看更多
                    LinearLayout headerRow = new LinearLayout(requireContext());
                    headerRow.setOrientation(LinearLayout.HORIZONTAL);
                    headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    TextView tvHeader = new TextView(requireContext());
                    tvHeader.setText("热帖排行");
                    tvHeader.setTextSize(19f);
                    tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvHeader.setTextColor(requireContext().getColor(R.color.text_primary));
                    tvHeader.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    headerRow.addView(tvHeader);
                    headerContainer.addView(headerRow);

                    for (int i = 0; i < topThreads.size(); i++) {
                        String[] item = topThreads.get(i);
                        LinearLayout row = new LinearLayout(requireContext());
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.BOTTOM);
                        int rank = i + 1;

                        // 排名序号圆角背景
                        TextView tvRank = new TextView(requireContext());
                        tvRank.setText(String.valueOf(rank));
                        tvRank.setTextSize(11f);
                        tvRank.setTextColor(0xFFFFFFFF);
                        tvRank.setGravity(android.view.Gravity.CENTER);
                        int rankSize = (int)(20*density);
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(rankSize, rankSize);
                        rlp.topMargin = (int)(8*density);
                        tvRank.setLayoutParams(rlp);
                        // 不同排名不同颜色
                        int bgColor;
                        if (rank == 1) bgColor = 0xFFFF705E;
                        else if (rank == 2) bgColor = 0xFFFFB900;
                        else if (rank == 3) bgColor = 0xFFA8C500;
                        else bgColor = 0xFFCCCCCC;
                        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                        bg.setCornerRadius(10);
                        bg.setColor(bgColor);
                        tvRank.setBackground(bg);

                        // 标题
                        TextView tvTitle = new TextView(requireContext());
                        tvTitle.setText(item[1]);
                        tvTitle.setTextSize(13f);
                        tvTitle.setTextColor(requireContext().getColor(R.color.text_primary));
                        tvTitle.setSingleLine(true);
                        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        tvTitle.setPadding((int)(8*density), (int)(8*density), 0, (int)(8*density));

                        row.addView(tvRank);
                        row.addView(tvTitle);
                        row.setTag(item[0]);
                        row.setClickable(true);
                        row.setFocusable(true);
                        // 透明波纹点击效果
                        android.util.TypedValue rippleVal = new android.util.TypedValue();
                        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleVal, true);
                        row.setBackgroundResource(rippleVal.resourceId);
                        final String tid = item[0];
                        row.setOnClickListener(v -> {
                            NavigationHelper.openThread(requireContext(), tid);
                        });

                        headerContainer.addView(row);
                        if (i < topThreads.size() - 1) {
                            View divider = new View(requireContext());
                            divider.setLayoutParams(new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
                            divider.setBackgroundColor(0x1A000000);
                            divider.setPadding((int)(28*density), 0, 0, 0);
                            headerContainer.addView(divider);
                        }
                    }

                    // 底部隔离线
                    View bottomDivider = new View(requireContext());
                    bottomDivider.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0));
                    bottomDivider.setBackgroundColor(0x00000000);
                    headerContainer.addView(bottomDivider);

                    // 设置为 RecyclerView 头部
                    threadAdapter.setHeaderView(headerContainer);
                });
            } catch (Exception ignored) {
                // 热帖加载失败不影响主列表
            }
        }).start();
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

    private void handleMcpButton(){
        com.solosu.mtforum.mcp.CloudflareTunnelManager tunnel=com.solosu.mtforum.mcp.CloudflareTunnelManager.get();
        if(!android.text.TextUtils.isEmpty(tunnel.publicUrl())){
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MTForum MCP 完整配置",com.solosu.mtforum.mcp.McpPreferences.clientConfig(requireContext())));
            android.widget.Toast.makeText(requireContext(),"完整配置已复制，直接整体粘贴给 AI 即可",android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        com.solosu.mtforum.mcp.McpPreferences.setEnabled(requireContext(),true);
        com.solosu.mtforum.mcp.McpPreferences.setTunnel(requireContext(),true);
        com.solosu.mtforum.mcp.McpService.start(requireContext());
        binding.btnHomeMcp.setEnabled(false);
        binding.tvHomeMcpStatus.setText("正在建立免费公网连接…");
    }

    private void updateMcpCard(){
        if(binding==null)return;
        com.solosu.mtforum.mcp.CloudflareTunnelManager tunnel=com.solosu.mtforum.mcp.CloudflareTunnelManager.get();
        String url=tunnel.publicUrl();
        if(!android.text.TextUtils.isEmpty(url)){
            binding.tvHomeMcpStatus.setText("已就绪 · "+url);
            binding.btnHomeMcp.setText("复制配置");binding.btnHomeMcp.setEnabled(true);
        }else if(com.solosu.mtforum.mcp.McpPreferences.tunnel(requireContext())){
            binding.tvHomeMcpStatus.setText("公网连接："+tunnel.message());
            binding.btnHomeMcp.setText(tunnel.state()==com.solosu.mtforum.mcp.CloudflareTunnelManager.State.FAILED?"重试":"连接中");
            binding.btnHomeMcp.setEnabled(tunnel.state()==com.solosu.mtforum.mcp.CloudflareTunnelManager.State.FAILED);
        }else{
            binding.tvHomeMcpStatus.setText("点一下自动建立免费公网连接；完成后复制一份完整配置");
            binding.btnHomeMcp.setText("立即启用");binding.btnHomeMcp.setEnabled(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mcpHandler.removeCallbacks(mcpRefresh);mcpHandler.post(mcpRefresh);
        // build65: 账号切换过就自动重载本页
        if (consumeAccountSwitched()) { onTabReselected(); }
        if (threadAdapter != null && threadAdapter.getItemCount() == 0) {
            refreshThreads();
        } else if (threadAdapter != null) {
            // build80(分支): 从详情页返回时用点赞缓存刷新卡片，零额外请求
            applyCachedLikes();
        }
    }

    @Override public void onPause(){mcpHandler.removeCallbacks(mcpRefresh);super.onPause();}

    @Override
    public void onDestroyView() {
        mcpHandler.removeCallbacks(mcpRefresh);
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
