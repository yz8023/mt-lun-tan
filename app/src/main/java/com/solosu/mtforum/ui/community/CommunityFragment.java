package com.solosu.mtforum.ui.community;

import android.content.Intent;
import android.text.TextUtils;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.ForumGridAdapter;
import com.solosu.mtforum.databinding.FragmentCommunityBinding;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;
import com.solosu.mtforum.model.ForumCategory;
import java.util.concurrent.TimeUnit;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.forum.ForumDetailActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Map;

public class CommunityFragment extends Fragment implements com.solosu.mtforum.ui.Refreshable {

    private static CommunityFragment currentInstance;

    private FragmentCommunityBinding binding;
    private HttpClient httpClient;
    private ForumGridAdapter forumGridAdapter;
    private String currentFormhash;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentCommunityBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FrostedGlassHelper.applyToCardViews(view, requireContext());

        // 数据统计:4 个单元格各自使用玻璃卡片背景
        FrostedGlassDrawable statsGlass = FrostedGlassDrawable.create(requireContext(), 12f);
        binding.llStats1.setBackground(statsGlass);
        binding.llStats2.setBackground(statsGlass);
        binding.llStats3.setBackground(statsGlass);
        binding.llStats4.setBackground(statsGlass);

        httpClient = HttpClient.getInstance();
        // 版块页面不启用下拉刷新，避免普通滑动被误触发。

        // Setup forum grid (2 columns, nested scrolling disabled)
        forumGridAdapter = new ForumGridAdapter(requireContext());
        binding.rvForumGrid.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.rvForumGrid.setAdapter(forumGridAdapter);
        forumGridAdapter.setOnForumClickListener((forum, position) -> {
            Intent intent = new Intent(requireContext(), ForumDetailActivity.class);
            intent.putExtra("fid", forum.getFid());
            intent.putExtra("forumName", forum.getName());
            intent.putExtra("description", forum.getDescription());
            intent.putExtra("iconUrl", forum.getIconUrl());
            intent.putExtra("totalPosts", forum.getTotalPosts());
            intent.putExtra("totalThreads", forum.getTotalThreads());
            startActivity(intent);
        });

        // Setup sign-in button click
        binding.btnSignIn.setOnClickListener(v -> {
            // 检查登录状态
            if (!httpClient.isLoggedIn()) {
                Toast.makeText(requireContext(), R.string.login_required, Toast.LENGTH_SHORT).show();
                return;
            }
            // 自动签到成功后可能仍停留在社区页，先读取本地签到状态，避免重复请求和误播放音效。
            if (UserSessionManager.getInstance().isSignedInToday(requireContext())) {
                showAlreadySignedIn();
                return;
            }
            if (currentFormhash == null || currentFormhash.isEmpty()) {
                Toast.makeText(requireContext(), "formhash 获取失败，请刷新页面", Toast.LENGTH_SHORT).show();
                return;
            }
            // 禁用按钮防止重复点击
            binding.btnSignIn.setEnabled(false);
            binding.btnSignIn.setText("签到中...");
            performSignIn();
        });

        // Initial data load
        loadCommunityData();
    }

    /**
     * Load community page data: sign-in info, stats, and forum grid.
     * Uses ForumParser.getForumlistMobileUrl() as data source.
     */
    /**
     * 刷新签到状态(由外部如自动签到完成时调用)
     */
    public static void refreshSignIn() {
        CommunityFragment instance = currentInstance;
        if (instance == null || instance.binding == null) return;
        instance.requireActivity().runOnUiThread(() -> {
            if (instance.binding == null) return;
            if (UserSessionManager.getInstance().isSignedInToday(instance.requireContext())) {
                instance.showAlreadySignedIn();
            } else {
                instance.binding.btnSignIn.setEnabled(true);
                instance.binding.btnSignIn.setText(R.string.action_sign_in);
                instance.binding.btnSignIn.setBackgroundResource(R.drawable.rounded_btn_primary);
            }
        });
    }

    private void loadCommunityData() {
        // NestedScrollView 不显示刷新指示器，直接加载数据。

        new java.lang.Thread(() -> {
            try {
                String html = httpClient.get(ForumParser.getForumlistMobileUrl());
                ForumParser.CommunityPageData data = ForumParser.parseCommunityPage(html);

                requireActivity().runOnUiThread(() -> {
                    if (binding == null) return;

                    // 0. 提取 formhash
                    currentFormhash = data.getFormhash();
                    if (currentFormhash != null && !currentFormhash.isEmpty()) {
                        binding.btnSignIn.setEnabled(true);
                    }

                    // 1. Sign-in card — 优先检查本地持久化签到状态
                    boolean alreadySignedIn = UserSessionManager.getInstance()
                            .isSignedInToday(requireContext());
                    if (alreadySignedIn) {
                        showAlreadySignedIn();
                    } else if (data.getSignInText() != null && !data.getSignInText().isEmpty()) {
                        binding.tvSignInStatus.setText(data.getSignInText());
                        binding.btnSignIn.setText(data.getSignInText());
                        // If text contains "已签到" or "已", treat as already signed in
                        if (data.getSignInText().contains("已")) {
                            binding.btnSignIn.setBackgroundResource(R.drawable.rounded_btn_success);
                            binding.btnSignIn.setEnabled(false);
                            // 同步到本地持久化
                            UserSessionManager.getInstance().saveSignInDate(requireContext());
                        }
                    }

                    // 2. Stats cards
                    binding.tvStatsValue1.setText(String.valueOf(data.getTodayPosts()));
                    binding.tvStatsValue2.setText(String.valueOf(data.getYesterdayPosts()));
                    binding.tvStatsValue3.setText(String.valueOf(data.getTotalPosts()));
                    binding.tvStatsValue4.setText(String.valueOf(data.getTotalMembers()));

                    // 3. Forum grid — deduplicate by fid
                    java.util.LinkedHashMap<String, ForumCategory.Forum> dedupMap = new java.util.LinkedHashMap<>();
                    List<ForumCategory.Forum> forums = data.getForums();
                    if (forums != null && !forums.isEmpty()) {
                        for (ForumCategory.Forum f : forums) {
                            dedupMap.put(f.getFid(), f);
                        }
                    } else {
                        // Fallback: use categories to flatten forums
                        List<ForumCategory> categories = data.getCategories();
                        if (categories != null) {
                            for (ForumCategory cat : categories) {
                                if (cat.getForums() != null) {
                                    for (ForumCategory.Forum f : cat.getForums()) {
                                        dedupMap.put(f.getFid(), f);
                                    }
                                }
                            }
                        }
                    }
                    forumGridAdapter.setForumList(new java.util.ArrayList<>(dedupMap.values()));

                    // ★ 完善数据:桌面版 forumlist 统计(主题/总帖数)+ 版块页描述/精确热度
                    List<ForumCategory.Forum> gridForums = forumGridAdapter.getForumList();
                    if (gridForums != null && !gridForums.isEmpty()) {
                        enrichForumsWithStatsAndDescription(gridForums);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> {
                    if (binding != null) {
                        binding.tvSignInStatus.setText(R.string.network_error);
                    }
                });
            }
        }).start();
    }

    /**
     * 从服务器响应中提取纯文本消息（去除 XML/CDATA 包裹）
     * 例如: <root><![CDATA[今日已签]]></root> → "今日已签"
     */
    /**
     * 完善版块卡片数据:
     * 1) 桌面版 forumlist 页 → 主题数/总帖数(热度)/今日新帖(精确值)
     * 2) 各版块首页头部 → 描述 / 今日数 / 总帖数(更精确)
     * 先用桌面统计快速刷新,再线程池逐个抓版块头部补齐描述。
     */
    private void enrichForumsWithStatsAndDescription(List<ForumCategory.Forum> forums) {
        new java.lang.Thread(() -> {
            try {
                // ---- 第一步:桌面版 forumlist 统计 ----
                String desktopHtml = httpClient.get(ForumParser.getBaseDomain() + "forum.php?forumlist=1&mobile=no");
                java.util.Map<String, long[]> statsMap = ForumParser.parseDesktopForumStats(desktopHtml);
                if (statsMap != null && !statsMap.isEmpty()) {
                    for (int i = 0; i < forums.size(); i++) {
                        ForumCategory.Forum f = forums.get(i);
                        long[] st = statsMap.get(f.getFid());
                        if (st == null) continue;
                        if (st[1] > 0) f.setTotalPosts((int) Math.min(st[1], Integer.MAX_VALUE));
                        if (st[0] > 0) f.setTotalThreads((int) Math.min(st[0], Integer.MAX_VALUE));
                        if (st[2] > 0) f.setTodayPosts((int) Math.min(st[2], Integer.MAX_VALUE));
                    }
                    final List<ForumCategory.Forum> snapshot = new java.util.ArrayList<>(forums);
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null && forumGridAdapter != null) {
                            forumGridAdapter.setForumList(snapshot);
                        }
                    });
                }
            } catch (Exception ignored) {
                // 桌面统计失败不影响后续描述补充
            }

            // ---- 第二步:线程池逐个抓版块头部补齐描述与精确热度 ----
            ExecutorService pool = Executors.newFixedThreadPool(3);
            for (int i = 0; i < forums.size(); i++) {
                final ForumCategory.Forum f = forums.get(i);
                final int index = i;
                pool.execute(() -> {
                    try {
                        String url = ForumParser.getBaseDomain() + "forum-" + f.getFid() + "-1.html";
                        String page = null;
                        // 失败重试一次(网络抖动/服务器限流)
                        for (int attempt = 0; attempt < 2; attempt++) {
                            try {
                                page = httpClient.get(url);
                                break;
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                            }
                        }
                        if (page == null) return;
                        String[] info = ForumParser.parseForumHeaderInfo(page);
                        if (info == null) return;
                        boolean changed = false;
                        if (!TextUtils.isEmpty(info[0])) {
                            f.setDescription(info[0]);
                            changed = true;
                        }
                        if (!TextUtils.isEmpty(info[1])) {
                            int today = Integer.parseInt(info[1]);
                            if (today > 0) { f.setTodayPosts(today); changed = true; }
                        }
                        if (!TextUtils.isEmpty(info[2])) {
                            long posts = Long.parseLong(info[2]);
                            if (posts > 0) { f.setTotalPosts((int) Math.min(posts, Integer.MAX_VALUE)); changed = true; }
                        }
                        if (changed) {
                            requireActivity().runOnUiThread(() -> {
                                if (binding != null && forumGridAdapter != null) {
                                    forumGridAdapter.updateForumAt(index, f);
                                }
                            });
                        }
                    } catch (Exception ignored) {
                        // 单版块抓取失败静默跳过
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private String extractSignMessage(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // 尝试提取 <![CDATA[...]]> 中的内容
        java.util.regex.Pattern cdataPattern = java.util.regex.Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = cdataPattern.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // 尝试提取 <root>...</root> 中的内容（非CDATA情况）
        java.util.regex.Pattern rootPattern = java.util.regex.Pattern.compile("<root>(.*?)</root>", java.util.regex.Pattern.DOTALL);
        matcher = rootPattern.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // 去掉可能的XML标签
        String cleaned = raw.replaceAll("<[^>]+>", "").trim();
        if (!cleaned.isEmpty()) return cleaned;
        return raw.trim();
    }

    private void showAlreadySignedIn() {
        if (binding == null) return;
        binding.tvSignInStatus.setText("已签到");
        binding.btnSignIn.setBackgroundResource(R.drawable.rounded_btn_success);
        binding.btnSignIn.setText("已签到");
        binding.btnSignIn.setEnabled(false);
    }

    /**
     * 执行签到请求
     */
    private void performSignIn() {
        new java.lang.Thread(() -> {
            try {
                String signUrl = HttpClient.BASE_URL + "plugin.php?id=k_misign:sign&operation=qiandao&format=text";
                Map<String, String> params = new HashMap<>();
                params.put("formhash", currentFormhash);
                String result = httpClient.post(signUrl, params);

                requireActivity().runOnUiThread(() -> {
                    if (binding == null) return;

                    // 提取纯文本消息
                    String msg = result != null ? extractSignMessage(result) : "";
                    // 服务器明确返回“已签到”时只更新状态；其余只要不是明确失败，就视为本次手动签到成功。
                    boolean alreadySigned = isAlreadySignedMessage(msg);
                    boolean isSuccess = !alreadySigned && isSuccessfulSignResponse(msg, result);

                    if (isSuccess || alreadySigned) {
                        String displayMsg = msg.isEmpty() ? (alreadySigned ? "今日已签到" : "签到成功") : msg;
                        Toast.makeText(requireContext(), displayMsg, Toast.LENGTH_SHORT).show();
                        showAlreadySignedIn();
                        if (isSuccess) {
                            playRandomSignInSound();
                        }
                        // 持久化签到状态
                        UserSessionManager.getInstance().saveSignInDate(requireContext());
                    } else {
                        String displayMsg = !msg.isEmpty() ? msg : (result != null ? result.trim() : "签到失败");
                        Toast.makeText(requireContext(), displayMsg, Toast.LENGTH_SHORT).show();
                        binding.btnSignIn.setEnabled(true);
                        binding.btnSignIn.setText(R.string.action_sign_in);
                    }
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    if (binding == null) return;
                    Toast.makeText(requireContext(), "签到失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    binding.btnSignIn.setEnabled(true);
                    binding.btnSignIn.setText(R.string.action_sign_in);
                });
            }
        }).start();
    }
    private boolean isAlreadySignedMessage(String text) {
        if (text == null) return false;
        return text.contains("今日已签") || text.contains("已签到")
                || text.contains("已经签到") || text.contains("已签");
    }

    private boolean isSuccessfulSignResponse(String message, String rawResult) {
        String text = message == null ? "" : message.trim();
        String raw = rawResult == null ? "" : rawResult.trim();
        String lower = text.toLowerCase();
        if (text.isEmpty() && raw.isEmpty()) return false;
        return !(text.contains("失败") || text.contains("错误") || text.contains("异常")
                || text.contains("请先登录") || text.contains("没有权限") || text.contains("非法操作")
                || lower.contains("fail") || lower.contains("error"));
    }

    private void playRandomSignInSound() {
        if (isAdded() && getContext() != null) {
        }
    }

    /**
     * 页面已取消下拉刷新，仅保留首次进入时的自动加载。
     */

    @Override
    public void onResume() {
        super.onResume();
        // build65: 账号切换过就自动重载本页
        if (consumeAccountSwitched()) { loadCommunityData(); }
        currentInstance = this;
    }

    @Override
    public void onPause() {
        super.onPause();
        currentInstance = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ==================== build63: 底栏再点刷新 ====================
    @Override
    public void onTabReselected() {
        loadCommunityData();
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
}