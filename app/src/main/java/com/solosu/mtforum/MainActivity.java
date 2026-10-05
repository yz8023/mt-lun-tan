package com.solosu.mtforum;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.solosu.mtforum.ai.AiChatActivity;
import com.solosu.mtforum.ai.AiConfigActivity;
import com.solosu.mtforum.ai.AiConfigManager;
import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.ai.AutoReplyEngine;
import com.solosu.mtforum.ai.AutoReplyScheduler;
import com.solosu.mtforum.databinding.ActivityMainBinding;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.network.NoticeBadgeManager;
//import com.solosu.mtforum.network.UpdateChecker;
import com.solosu.mtforum.ui.MainPagerAdapter;
import com.solosu.mtforum.ui.post.PostActivity;
import com.solosu.mtforum.session.AutoSignInManager;
import com.solosu.mtforum.session.ReplyFilterManager;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import com.solosu.mtforum.ui.widget.DialogHelper;

/** * 主活动 — 胶囊导航栏（首页/版块/凸起发布/消息/我的） */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ViewPager2 mainPager;
    private MainPagerAdapter pagerAdapter;

    // 导航项（5项）
    private View navHome, navCommunity, navPost, navMessage, navProfile;
    // 图标（含凸起发布按钮图标）
    private ImageView ivHomeIcon, ivCommunityIcon, ivPostIcon, ivMessageIcon, ivProfileIcon;
    // 文字
    private TextView tvHomeText, tvCommunityText, tvMessageText, tvProfileText;

    // build62: 底栏弹簧指示器
    private View navIndicator;
    private android.widget.LinearLayout navItems;
    /** 底栏 5 个槽位，按屏幕顺序：首页 / 版块 / 发布 / 消息 / 我的 */
    private View[] navSlots;
    /** 底栏实际占用高度（含系统导航栏补偿），供页面内容预留留白 */
    private int navReservedPx;

    // ★ 消息角标
    private TextView tvMessageBadge;
    private Handler mainHandler;
    private ExecutorService executor;
    /**
     * 角标刷新间隔。
     *
     * build65: 由 5 秒改为 60 秒。原值配合每轮 6 个并发请求，等于光挂在首页
     * 什么都不干就是 <b>72 次/分钟</b>，是论坛 403 风控最大的单一来源。
     * 未读角标没有秒级实时的必要，60 秒足够。
     */
    private static final long BADGE_REFRESH_INTERVAL_MS = 60_000;
    /** build68(分支): onResume 即时刷新节流，防频繁返回重复拉 6 类 */
    private static final long BADGE_RESUME_THROTTLE_MS = 60_000;
    private long lastBadgeRefreshAt = 0L;
    private static final String[] BADGE_TYPES = {"pm", "follower", "mypost", "interactive", "system", "app"};
    private final java.util.Map<String, Integer> previousUnreadCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> previousUnreadFingerprints = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean badgeRefreshInFlight = new AtomicBoolean(false);
    private boolean unreadBaselineReady = false;

    // 双击返回退出
    private static final long EXIT_INTERVAL_MS = 2000;
    private long lastBackPressTime = 0;
    private FrostedGlassDrawable frostedNavBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 底部导航栏统一使用彩色流水灯边缘，并保留胶囊形状。
        float density = getResources().getDisplayMetrics().density;
        frostedNavBackground = FrostedGlassDrawable.create(this, 24f);
        binding.bottomNavContainer.setBackground(frostedNavBackground);

        // 初始化 ViewPager2：首页/版块/消息/我的 四页快速切换
        mainPager = binding.mainPager;
        pagerAdapter = new MainPagerAdapter(this);
        mainPager.setAdapter(pagerAdapter);
        mainPager.setOffscreenPageLimit(MainPagerAdapter.PAGE_COUNT - 1); // 全页保活，切换不重建
        mainPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateNavSelectionByPosition(position);
            }
        });

        // 恢复持久化的 Cookie
        HttpClient.getInstance().init(this);

        // ★ 消息角标初始化
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newFixedThreadPool(7);
        tvMessageBadge = findViewById(R.id.nav_message_badge);
        if (tvMessageBadge != null) {
            tvMessageBadge.setVisibility(View.GONE);
            refreshMessageBadge();
            //checkForUpdateOnStartup();
        }

        // 初始化导航视图引用（含凸起发布按钮）
        initNavigationBar();

        // 初始化侧边栏
        initDrawer();

        // build66: 非默认主题色时，把视图树里用了默认主色的地方换成所选主色
        findViewById(android.R.id.content).post(() ->
                com.solosu.mtforum.ui.theme.ThemeManager.applyAccent(
                        findViewById(android.R.id.content), this));

        // build83(分支): 进入某个消息分类后本地即时清红点，零额外请求
        com.solosu.mtforum.network.NoticeBadgeManager.setOnViewedListener(viewType -> {
            if (mainHandler == null) return;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (tvMessageBadge != null) tvMessageBadge.setVisibility(View.GONE);
            });
        });
    }

    // ==================== 侧边栏 ====================

    private DrawerLayout drawerLayout;
    private View drawerPanel;
    private SwitchMaterial swAutoReply;
    private SwitchMaterial swSilent;
    private SwitchMaterial swSignIn;
    private SwitchMaterial swUnlock;
    private SwitchMaterial swDryRun;
    private TextView tvDrawerName;
    private TextView tvDrawerSubtitle;
    private TextView tvAiDesc;
    private ImageView ivDrawerAvatar;
    // build61: 侧边栏平铺账号列表
    private android.widget.LinearLayout drawerAccountList;
    private TextView drawerAccountEmpty;
    private TextView tvAccountsDesc;
    private TextView tvSignInDesc;
    private TextView tvRunSignInDesc;

    /** 侧边栏开关的读写/跳转 */
    private void initDrawer() {
        View root = findViewById(R.id.main_drawer);
        if (!(root instanceof DrawerLayout)) {
            android.util.Log.e("MainActivity", "DrawerLayout not found");
            return;
        }
        drawerLayout = (DrawerLayout) root;
        // DrawerLayout 默认会在状态栏区域补一块矩形底色，它会盖住悬浮抽屉的右上圆角。
        drawerLayout.setStatusBarBackgroundColor(android.graphics.Color.TRANSPARENT);
        // 遮罩加深，抽屉打开时右侧主内容不会透出文字
        drawerLayout.setScrimColor(0xC0000000);

        // 打开侧边栏时藏掉底部悬浮导航栏，否则两边的文字会叠在一起
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View dv) {
                View nav = findViewById(R.id.bottom_nav_container);
                if (nav != null) nav.setVisibility(View.GONE);
                // build62: 账号卡片错峰淡入上移（standard-list 70ms/项）
                if (drawerAccountList != null) {
                    com.solosu.mtforum.ui.anim.Motion.staggerChildren(drawerAccountList);
                }
            }

            @Override
            public void onDrawerClosed(View dv) {
                View nav = findViewById(R.id.bottom_nav_container);
                if (nav != null) nav.setVisibility(View.VISIBLE);
            }
        });

        drawerPanel = findViewById(R.id.drawer_panel);
        // build77: 侧边栏与底栏共用同一档不透明度，视觉统一（dyparse 那种悬浮玻璃面板）
        if (drawerPanel != null) {
            // 明确用背景轮廓裁切，避免部分系统把右上角子 View 按矩形绘制。
            drawerPanel.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            drawerPanel.setClipToOutline(true);
            int navPct = com.solosu.mtforum.ui.theme.ThemeManager.navOpacity(this);
            drawerPanel.setAlpha(Math.max(0.4f, navPct / 100f));
        }
        swAutoReply = findViewById(R.id.drawer_switch_auto_reply);
        swSilent = findViewById(R.id.drawer_switch_silent);
        swSignIn = findViewById(R.id.drawer_switch_sign_in);
        swUnlock = findViewById(R.id.drawer_switch_unlock);
        swDryRun = findViewById(R.id.drawer_switch_dry_run);
        tvDrawerName = findViewById(R.id.drawer_username);
        tvDrawerSubtitle = findViewById(R.id.drawer_subtitle);
        tvAiDesc = findViewById(R.id.drawer_ai_desc);
        ivDrawerAvatar = findViewById(R.id.drawer_avatar);
        drawerAccountList = findViewById(R.id.drawer_account_list);
        drawerAccountEmpty = findViewById(R.id.drawer_account_empty);
        tvAccountsDesc = findViewById(R.id.drawer_accounts_desc);
        tvSignInDesc = findViewById(R.id.drawer_sign_in_desc);
        tvRunSignInDesc = findViewById(R.id.drawer_run_sign_in_desc);
        // build57: 侧边栏头部(头像/用户名/UID行)点击进自己主页
        View.OnClickListener ownProfile = v -> openOwnProfile();
        ivDrawerAvatar.setOnClickListener(ownProfile);
        if (tvDrawerName != null) tvDrawerName.setOnClickListener(ownProfile);
        if (tvDrawerSubtitle != null) tvDrawerSubtitle.setOnClickListener(ownProfile);

        // 左上角入口打开侧边栏
        View openBtn = findViewById(R.id.btn_open_drawer);
        if (openBtn != null) {
            openBtn.setOnClickListener(v -> drawerLayout.openDrawer(drawerPanel));
        }

        bindSwitchRow(R.id.drawer_auto_reply_row, swAutoReply);
        bindSwitchRow(R.id.drawer_silent_row, swSilent);
        bindSwitchRow(R.id.drawer_sign_in_row, swSignIn);

        // 自动回复开关
        if (swAutoReply != null) {
            swAutoReply.setChecked(AiConfigManager.isAutoReplyEnabled(this));
            swAutoReply.setOnCheckedChangeListener((v, checked) -> {
                AiConfigManager.setAutoReplyEnabled(this, checked);
                AutoReplyScheduler.reschedule(this);
                AiLog.i("drawer", "自动回复 " + (checked ? "开启" : "关闭"));
                if (checked) {
                    Toast.makeText(this, "自动回复已开启（后台静默运行）", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 静默模式开关
        if (swSilent != null) {
            swSilent.setChecked(AiConfigManager.isSilentMode(this));
            swSilent.setOnCheckedChangeListener((v, checked) -> {
                AiConfigManager.setSilentMode(this, checked);
                AiLog.i("drawer", "隐藏运行 " + (checked ? "开启" : "关闭"));
            });
        }

        // 自动签到开关：复用既有 AutoSignInManager
        if (swSignIn != null) {
            swSignIn.setChecked(AutoSignInManager.isEnabled(this));
            swSignIn.setOnCheckedChangeListener((v, checked) -> {
                AutoSignInManager.setEnabled(this, checked);
                AiLog.i("drawer", "自动签到 " + (checked ? "开启" : "关闭"));
                if (checked) {
                    doSignInNow();
                }
            });
        }

        // 自动解锁隐藏内容：一键控制「解锁模式 + 进帖自动解锁」
        bindSwitchRow(R.id.drawer_unlock_row, swUnlock);
        if (swUnlock != null) {
            swUnlock.setChecked(AiConfigManager.isUnlockMode(this));
            updateUnlockDesc();
            swUnlock.setOnCheckedChangeListener((v, checked) -> {
                AiConfigManager.setUnlockMode(this, checked);
                AiConfigManager.setUnlockOnView(this, checked);
                updateUnlockDesc();
                AiLog.i("drawer", "自动解锁隐藏内容 " + (checked ? "开启" : "关闭"));
                Toast.makeText(this, checked
                        ? "已开启：进含「回复可见」的帖子会自动回帖解锁"
                        : "已关闭自动解锁", Toast.LENGTH_SHORT).show();
            });
        }

        // 演练模式：自动回复只生成不发送（不影响进帖解锁）
        bindSwitchRow(R.id.drawer_dry_run_row, swDryRun);
        if (swDryRun != null) {
            swDryRun.setChecked(AiConfigManager.isDryRun(this));
            swDryRun.setOnCheckedChangeListener((v, checked) -> {
                AiConfigManager.setDryRun(this, checked);
                AiLog.i("drawer", "演练模式 " + (checked ? "开启" : "关闭"));
                Toast.makeText(this, checked
                        ? "演练模式：自动回复只生成不发送"
                        : "已关闭演练模式，自动回复将真实发送", Toast.LENGTH_SHORT).show();
            });
        }

        // 解锁回复内容自定义：点击弹对话框编辑模板
        View unlockTextRow = findViewById(R.id.drawer_unlock_text_row);
        if (unlockTextRow != null) {
            updateUnlockTextDesc();
            unlockTextRow.setOnClickListener(v -> showUnlockTextDialog());
        }

        // 立即执行一轮自动回复
        View runReply = findViewById(R.id.drawer_run_reply);
        if (runReply != null) {
            runReply.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                Toast.makeText(this, "开始执行一轮自动回复…", Toast.LENGTH_SHORT).show();
                AutoReplyEngine.runOnce(this, (replied, skipped, detail) ->
                        Toast.makeText(this,
                                "本轮：回复 " + replied + " 条，跳过 " + skipped + " 条\n" + detail,
                                Toast.LENGTH_LONG).show());
            });
        }

        // 立即签到
        View runSignIn = findViewById(R.id.drawer_run_sign_in);
        if (runSignIn != null) {
            runSignIn.setOnClickListener(v -> doSignInNow());
        }

        // AI 配置
        View aiConfig = findViewById(R.id.drawer_ai_config);
        if (aiConfig != null) {
            aiConfig.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                startActivity(new Intent(MainActivity.this, AiConfigActivity.class));
            });
        }

        // AI 助手
        View aiChat = findViewById(R.id.drawer_ai_chat);
        if (aiChat != null) {
            aiChat.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                startActivity(new Intent(MainActivity.this, AiChatActivity.class));
            });
        }

        // build61: 账号已在上方平铺，这一行直接进完整管理页
        View accountsRow = findViewById(R.id.drawer_accounts);
        if (accountsRow != null) {
            accountsRow.setOnClickListener(v -> openAccountManager());
        }

        View quickJump = findViewById(R.id.drawer_quick_jump);
        if (quickJump != null) quickJump.setOnClickListener(v -> {
            drawerLayout.closeDrawer(drawerPanel);
            showQuickJumpDialog();
        });
        // build96: 标签汇（浏览/搜索站点标签，看标签下的帖子）
        View tagsRow = findViewById(R.id.drawer_tags);
        if (tagsRow != null) tagsRow.setOnClickListener(v -> {
            drawerLayout.closeDrawer(drawerPanel);
            startActivity(new Intent(this, com.solosu.mtforum.ui.tag.TagActivity.class));
        });

        View offlinePosts = findViewById(R.id.drawer_offline_posts);
        if (offlinePosts != null) offlinePosts.setOnClickListener(v -> {
            drawerLayout.closeDrawer(drawerPanel);
            startActivity(new Intent(this, com.solosu.mtforum.offline.OfflinePostsActivity.class));
        });

        // build71: 浏览历史（长按运行日志入口打开，避免再加一行占空间）
        // build75: 侧边栏「运行日志」改为进记录中心（四个分页 + 卡片，可点开帖子）
        View logRow = findViewById(R.id.drawer_log);
        if (logRow != null) {
            logRow.setOnClickListener(v -> {
                if (drawerLayout != null && drawerPanel != null
                        && drawerLayout.isDrawerOpen(drawerPanel)) {
                    drawerLayout.closeDrawer(drawerPanel);
                }
                startActivity(new Intent(this, com.solosu.mtforum.ui.LogCenterActivity.class));
            });
            logRow.setOnLongClickListener(v -> {
                showRunLog();
                return true;
            });
        }

        // build67: 帖子页 AI 总结按钮开关
        SwitchMaterial swAiSummary = findViewById(R.id.drawer_switch_ai_summary);
        if (swAiSummary != null) {
            swAiSummary.setChecked(com.solosu.mtforum.ui.UiSettings.isAiSummaryVisible(this));
            swAiSummary.setOnCheckedChangeListener((v, checked) -> {
                com.solosu.mtforum.ui.UiSettings.setAiSummaryVisible(this, checked);
                Toast.makeText(this, checked ? "帖子页将显示 AI 总结按钮" : "已隐藏 AI 总结按钮",
                        Toast.LENGTH_SHORT).show();
            });
            bindSwitchRow(R.id.drawer_ai_summary_row, swAiSummary);
        }

        // build89: 正文图片原位显示开关。
        //
        // build91: 原先这里挂着一句 v5.5 留下的「正文图片现在固定以原图在原位展示，
        // 旧版底部图廊开关不再适用」—— 那是错的，开关一直在、也确实有用，
        // 只是 build91 之前要下次进帖才生效，看起来像没反应。
        // 现在 ThreadDetailActivity.onResume 会检测开关变动并立刻本地重渲。
        //
        // v5.5 把这个行整行 setVisibility(GONE) 藏了、开关也设了 clickable=false，
        // 但 CHANGELOG 里写着「设置里想用原位仍然可以手动打开」—— 文档承诺的
        // 功能实际不存在，用户在抽屉里根本找不到这一项。
        // 现在按 swHiddenInline 的同一套模式接上，死 UI 变成真功能。
        SwitchMaterial swImagesInline = findViewById(R.id.drawer_switch_images_inline);
        if (swImagesInline != null) {
            swImagesInline.setChecked(com.solosu.mtforum.ui.UiSettings.isImagesInline(this));
            swImagesInline.setOnCheckedChangeListener((v, checked) -> {
                com.solosu.mtforum.ui.UiSettings.setImagesInline(this, checked);
                Toast.makeText(this, checked ? "正文图片将原位显示" : "图片将汇总到帖子底部图廊",
                        Toast.LENGTH_SHORT).show();
            });
            bindSwitchRow(R.id.drawer_images_inline_row, swImagesInline);
        }

        // build92: 原帖排版渲染开关
        SwitchMaterial swWebRender = findViewById(R.id.drawer_switch_web_render);
        if (swWebRender != null) {
            swWebRender.setChecked(com.solosu.mtforum.ui.UiSettings.isWebRender(this));
            swWebRender.setOnCheckedChangeListener((v, checked) -> {
                com.solosu.mtforum.ui.UiSettings.setWebRender(this, checked);
                Toast.makeText(this, checked ? "帖子正文将按网页原帖排版渲染"
                        : "帖子正文将用纯文本重新排版", Toast.LENGTH_SHORT).show();
            });
            bindSwitchRow(R.id.drawer_web_render_row, swWebRender);
        }

        // build95: 顶部 FPS 显示开关
        SwitchMaterial swFps = findViewById(R.id.drawer_switch_fps);
        if (swFps != null) {
            swFps.setChecked(com.solosu.mtforum.ui.UiSettings.isShowFps(this));
            swFps.setOnCheckedChangeListener((v, checked) -> {
                com.solosu.mtforum.ui.UiSettings.setShowFps(this, checked);
                if (checked) com.solosu.mtforum.util.FpsOverlay.attach(this);
                else com.solosu.mtforum.util.FpsOverlay.detach(this);
                Toast.makeText(this, checked ? "已在顶部显示 FPS" : "已关闭 FPS 显示",
                        Toast.LENGTH_SHORT).show();
            });
            bindSwitchRow(R.id.drawer_fps_row, swFps);
        }

        // build99: 高刷新率开关（默认开）。开着时每个 Activity 恢复都向系统申请
        // 设备支持的最高刷新率 —— 不申请的话多数 ROM 直接按 60Hz 合成。
        SwitchMaterial swRefresh = findViewById(R.id.drawer_switch_refresh);
        if (swRefresh != null) {
            swRefresh.setChecked(com.solosu.mtforum.ui.UiSettings.isHighRefresh(this));
            final TextView tvRefreshDesc = findViewById(R.id.drawer_refresh_desc);
            if (tvRefreshDesc != null) {
                tvRefreshDesc.setText(com.solosu.mtforum.util.RefreshRate.statusLine(this));
            }
            swRefresh.setOnCheckedChangeListener((v, checked) -> {
                com.solosu.mtforum.ui.UiSettings.setHighRefresh(this, checked);
                if (checked) {
                    float hz = com.solosu.mtforum.util.RefreshRate.apply(this);
                    Toast.makeText(this, hz > 0
                                    ? String.format(java.util.Locale.US,
                                            "已申请 %.0fHz（受屏幕与系统限制）", hz)
                                    : "系统未提供可用的刷新率信息",
                            Toast.LENGTH_SHORT).show();
                } else {
                    // 关掉只影响「以后的申请」，不强行把窗口改回 60Hz —— 改回去
                    // 反而会在系统层面留下一个 60 的首选值，得不偿失。
                    Toast.makeText(this, "已关闭高刷申请，重启应用后完全生效",
                            Toast.LENGTH_SHORT).show();
                }
            });
            bindSwitchRow(R.id.drawer_refresh_row, swRefresh);
        }

        // build95: 正文链接打开方式（应用内 / 系统浏览器）
        SwitchMaterial swLink = findViewById(R.id.drawer_switch_link_open);
        if (swLink != null) {
            boolean internal = com.solosu.mtforum.ui.UiSettings.isLinksInternal(this);
            swLink.setChecked(internal);
            final android.widget.TextView linkDesc = findViewById(R.id.drawer_link_open_desc);
            if (linkDesc != null) {
                linkDesc.setText(internal ? "站内帖子走应用内，其余走浏览器"
                        : "所有链接都交给系统浏览器");
            }
            swLink.setOnCheckedChangeListener((v, checked) -> {
                com.solosu.mtforum.ui.UiSettings.setLinkOpenMode(this,
                        checked ? "internal" : "external");
                if (linkDesc != null) {
                    linkDesc.setText(checked ? "站内帖子走应用内，其余走浏览器"
                            : "所有链接都交给系统浏览器");
                }
                Toast.makeText(this, checked ? "正文链接将在应用内打开" : "正文链接将交给浏览器打开",
                        Toast.LENGTH_SHORT).show();
            });
            bindSwitchRow(R.id.drawer_link_open_row, swLink);
        }

        // build67: 隐藏内容就地展开开关
        SwitchMaterial swHiddenInline = findViewById(R.id.drawer_switch_hidden_inline);
        if (swHiddenInline != null) {
            swHiddenInline.setChecked(com.solosu.mtforum.ui.UiSettings.isHiddenContentInline(this));
            swHiddenInline.setOnCheckedChangeListener((v, checked) -> {
                com.solosu.mtforum.ui.UiSettings.setHiddenContentInline(this, checked);
                Toast.makeText(this, checked ? "隐藏内容将就地展开" : "隐藏内容将放在帖子底部",
                        Toast.LENGTH_SHORT).show();
            });
            bindSwitchRow(R.id.drawer_hidden_inline_row, swHiddenInline);
        }

        // build65: 滚动隐藏底栏开关
        SwitchMaterial swNavHide = findViewById(R.id.drawer_switch_nav_autohide);
        if (swNavHide != null) {
            swNavHide.setChecked(com.solosu.mtforum.ui.UiSettings.isNavAutoHide(this));
            swNavHide.setOnCheckedChangeListener((v, checked) -> {
                com.solosu.mtforum.ui.UiSettings.setNavAutoHide(this, checked);
                if (!checked) setNavBarShown(true);
                Toast.makeText(this, checked ? "滚动时将自动隐藏底栏" : "底栏将始终显示",
                        Toast.LENGTH_SHORT).show();
            });
            bindSwitchRow(R.id.drawer_nav_autohide_row, swNavHide);
        }

        // 添加账号
        View addAccountRow = findViewById(R.id.drawer_add_account);
        if (addAccountRow != null) {
            addAccountRow.setOnClickListener(v -> {
                com.solosu.mtforum.ui.login.LoginBottomSheet.show(this, () -> {
                    Toast.makeText(this, "账号已添加", Toast.LENGTH_SHORT).show();
                    refreshDrawerHeader();
                });
            });
        }

        // 个人小黑屋
        View blacklistRow = findViewById(R.id.drawer_blacklist);
        if (blacklistRow != null) {
            blacklistRow.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                startActivity(new Intent(MainActivity.this,
                        com.solosu.mtforum.ui.BlacklistActivity.class));
            });
        }

        // 设置
        View settings = findViewById(R.id.drawer_settings);
        if (settings != null) {
            settings.setOnClickListener(v -> {
                drawerLayout.closeDrawer(drawerPanel);
                startActivity(new Intent(MainActivity.this,
                        com.solosu.mtforum.ui.space.SettingsActivity.class));
            });
        }

        // 运行日志
        // build75: 旧的 drawer_log 绑定已移除，统一走上面的「记录中心」入口

        setupReplyFilterControls();
        setupDrawerCollapse();

        refreshDrawerHeader();
    }

    // ==================== build100: 回复内容过滤设置 ====================

    private void setupReplyFilterControls() {
        updateReplyBlacklistTermCount();
        TextView manageTerms = findViewById(R.id.drawer_reply_blacklist_manage);
        if (manageTerms != null) manageTerms.setOnClickListener(v -> showReplyBlacklistTermsDialog());

        SwitchMaterial hideSpam = findViewById(R.id.drawer_switch_hide_spam);
        if (hideSpam != null) {
            hideSpam.setChecked(ReplyFilterManager.isHideSpamEnabled(this));
            hideSpam.setOnCheckedChangeListener((button, checked) -> {
                ReplyFilterManager.setHideSpamEnabled(this, checked);
                Toast.makeText(this, checked ? "已开启回复关键词与模板过滤" : "已关闭回复内容过滤",
                        Toast.LENGTH_SHORT).show();
            });
            bindSwitchRow(R.id.drawer_hide_spam_row, hideSpam);
        }
    }

    private void updateReplyBlacklistTermCount() {
        TextView manageTerms = findViewById(R.id.drawer_reply_blacklist_manage);
        if (manageTerms == null) return;
        int count = ReplyFilterManager.getBlacklistTerms(this).size();
        manageTerms.setText("词条 · " + count);
    }

    private void showReplyBlacklistTermsDialog() {
        java.util.List<String> terms = ReplyFilterManager.getBlacklistTerms(this);
        StringBuilder initial = new StringBuilder();
        for (String term : terms) {
            if (initial.length() > 0) initial.append('\n');
            initial.append(term);
        }

        android.widget.EditText editor = new android.widget.EditText(this);
        editor.setText(initial.toString());
        editor.setHint("每行一个关键词，例如：看看隐藏、感谢分享");
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setSingleLine(false);
        editor.setMinLines(5);
        editor.setMaxLines(10);
        editor.setVerticalScrollBarEnabled(true);
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        editor.setPadding(pad, pad, pad, pad);

        android.app.Dialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("回复关键词词条")
                .setMessage("每行一个词条，回复正文包含任一词条即隐藏（不再区分精准/模糊）。\n内置自动解锁模板也会识别，例如“感谢分享 + 帖子标题”“正需要这个”；可删除或添加自己的词条。")
                .setView(editor)
                .setPositiveButton("保存", (d, which) -> {
                    java.util.List<String> updated = new java.util.ArrayList<>();
                    String[] lines = editor.getText().toString().split("\\r?\\n");
                    for (String line : lines) updated.add(line);
                    ReplyFilterManager.setBlacklistTerms(this, updated);
                    updateReplyBlacklistTermCount();
                    Toast.makeText(this, "回复关键词已保存", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("恢复默认", (d, which) -> {
                    ReplyFilterManager.setBlacklistTerms(
                            this, ReplyFilterManager.getDefaultBlacklistTerms());
                    updateReplyBlacklistTermCount();
                    Toast.makeText(this, "已恢复默认回复关键词", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    // ==================== build99: 侧边栏分区折叠 ====================

    /** 侧边栏四个分区：{头部标题 id, 内容容器 id, 偏好键} */
    private static final int[][] DRAWER_GROUPS = {
            {R.id.drawer_head_account, R.id.drawer_group_account, 0},
            {R.id.drawer_head_auto, R.id.drawer_group_auto, 1},
            {R.id.drawer_head_show, R.id.drawer_group_show, 2},
            {R.id.drawer_head_other, R.id.drawer_group_other, 3},
    };

    /**
     * build99: 侧边栏分区折叠。
     *
     * <p>用户反馈「侧边栏功能进行折叠」。侧边栏有一千多行、四大块（账号签到 / AI 自动化 /
     * 显示 / 其它），全展开时要滚好几屏才能找到底部那几个工具入口。
     *
     * <p>点分区标题收起 / 展开，状态存在偏好里下次照旧。
     * <b>默认只展开「显示」</b> —— 那一块是日常会动的开关（图片原位显示、原帖渲染、
     * FPS、链接打开方式…），其余三块默认收起，一眼能看到全部四个分区。
     */
    private void setupDrawerCollapse() {
        for (final int[] g : DRAWER_GROUPS) {
            final View head = findViewById(g[0]);
            final View body = findViewById(g[1]);
            if (head == null || body == null) continue;
            final boolean open = com.solosu.mtforum.ui.UiSettings.isDrawerGroupOpen(this, g[2]);
            applyDrawerGroup(head, body, open);
            head.setOnClickListener(v -> {
                boolean now = body.getVisibility() != View.VISIBLE;
                com.solosu.mtforum.ui.UiSettings.setDrawerGroupOpen(this, g[2], now);
                applyDrawerGroup(head, body, now);
                com.solosu.mtforum.ui.anim.Motion.pressFeedback(head, 0.98f);
            });
        }
    }

    /** 展开/收起一个分区：内容整块显隐，标题右边的箭头换成对应的方向 */
    private void applyDrawerGroup(View head, View body, boolean open) {
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        if (!(head instanceof TextView)) return;
        TextView tv = (TextView) head;
        android.graphics.drawable.Drawable[] ds = tv.getCompoundDrawables();
        android.graphics.drawable.Drawable arrow = ds.length > 2 ? ds[2] : null;
        if (arrow == null) return;
        // 箭头是 vector，setLevel 不会转向 —— 直接换成另一张（向下 = 展开中）
        arrow = androidx.core.content.ContextCompat.getDrawable(this,
                open ? R.drawable.ic_arrow_down : R.drawable.ic_arrow_right);
        if (arrow == null) return;
        int size = (int) (14 * getResources().getDisplayMetrics().density);
        arrow.setBounds(0, 0, size, size);
        tv.setCompoundDrawables(null, null, arrow, null);
    }

    /** 整行点击等于切换开关 */
    private void bindSwitchRow(int rowId, SwitchMaterial sw) {
        if (sw == null) return;
        View row = findViewById(rowId);
        if (row == null) return;
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
    }

    /** 侧边栏「自动解锁」副标题：按当前模式显示正在干什么 */
    private void updateUnlockDesc() {
        TextView desc = findViewById(R.id.drawer_unlock_desc);
        if (desc == null) return;
        desc.setText(AiConfigManager.isUnlockMode(this)
                ? "进帖遇「回复可见」自动回帖解锁"
                : "已关闭，不自动解锁");
    }

    /** 侧边栏「解锁回复内容」副标题：显示当前是自定义还是默认模板 */
    private void updateUnlockTextDesc() {
        TextView desc = findViewById(R.id.drawer_unlock_text_desc);
        if (desc == null) return;
        String custom = AiConfigManager.getUnlockReplyTemplate(this);
        desc.setText(android.text.TextUtils.isEmpty(custom)
                ? "默认模板（点击自定义）"
                : "自定义：" + (custom.length() > 20 ? custom.substring(0, 20) + "…" : custom));
    }

    /** 弹出对话框编辑解锁回复模板，{title} 会被替换为帖子标题关键词 */
    private void showUnlockTextDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(AiConfigManager.getUnlockReplyTemplate(this));
        et.setHint("例如：感谢分享「{title}」，正需要这个！");
        et.setMinLines(2);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("解锁回复内容")
                .setMessage("自定义自动解锁时发送的回复。\n留空则用内置模板池随机选一条。\n用 {title} 插入帖子标题关键词。")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String v = et.getText().toString().trim();
                    AiConfigManager.setUnlockReplyTemplate(this, v);
                    updateUnlockTextDesc();
                    AiLog.i("drawer", "解锁回复模板已更新：" + (v.isEmpty() ? "（恢复默认）" : v));
                    Toast.makeText(this, v.isEmpty() ? "已恢复默认模板" : "已保存自定义回复", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== build61: 侧边栏平铺账号 ====================

    /**
     * 把已保存的账号平铺到侧边栏，点一下直接切换。
     * 每行展示：头像、昵称、是否当前、今日签到状态（时间 + 金币）。
     */
    private void renderAccountList() {
        if (drawerAccountList == null) return;
        drawerAccountList.removeAllViews();

        java.util.List<com.solosu.mtforum.session.AccountManager.Account> accounts =
                com.solosu.mtforum.session.AccountManager.list(this);
        String activeUid = com.solosu.mtforum.session.AccountManager.activeUid(this);

        // 老用户可能在本功能上线前就登录了，账号库里没有记录。
        // 这种情况下用当前会话合成一行，别让侧边栏显示成"没有账号"。
        if (accounts.isEmpty()) {
            com.solosu.mtforum.session.AccountManager.Account current = synthesizeCurrentAccount();
            if (current == null) {
                if (drawerAccountEmpty != null) drawerAccountEmpty.setVisibility(View.VISIBLE);
                return;
            }
            accounts = new java.util.ArrayList<>();
            accounts.add(current);
            activeUid = current.uid;
        }
        if (drawerAccountEmpty != null) drawerAccountEmpty.setVisibility(View.GONE);

        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
        for (com.solosu.mtforum.session.AccountManager.Account account : accounts) {
            View row = inflater.inflate(R.layout.item_drawer_account, drawerAccountList, false);
            bindAccountRow(row, account, activeUid);
            drawerAccountList.addView(row);
        }
    }

    /** 账号库为空但会话还活着时，用当前登录信息拼一个展示用账号 */
    private com.solosu.mtforum.session.AccountManager.Account synthesizeCurrentAccount() {
        UserSessionManager session = UserSessionManager.getInstance();
        boolean cookieAlive;
        try {
            cookieAlive = HttpClient.getInstance().isLoggedIn();
        } catch (Exception e) {
            cookieAlive = false;
        }
        if (!session.isLoggedIn(this) && !cookieAlive) return null;

        com.solosu.mtforum.session.AccountManager.Account a =
                new com.solosu.mtforum.session.AccountManager.Account();
        String uid = session.getUid(this);
        a.uid = android.text.TextUtils.isEmpty(uid) ? "current" : uid;
        String name = session.getUsername(this);
        a.username = android.text.TextUtils.isEmpty(name) ? "当前账号" : name;
        a.avatar = session.getAvatarUrl(this);
        a.level = session.getLevel(this);
        // 当前会话的签到状态沿用全局记录
        if (session.isSignedInToday(this)) {
            a.lastSignDate = com.solosu.mtforum.session.AccountManager.today();
            a.lastSignStatus = "今日已签";
        }
        return a;
    }

    private void bindAccountRow(View row,
                                final com.solosu.mtforum.session.AccountManager.Account account,
                                String activeUid) {
        boolean isActive = activeUid != null && activeUid.equals(account.uid);

        ImageView avatar = row.findViewById(R.id.iv_account_avatar);
        TextView name = row.findViewById(R.id.tv_account_name);
        TextView current = row.findViewById(R.id.tv_account_current);
        TextView signState = row.findViewById(R.id.tv_account_sign);
        TextView signBtn = row.findViewById(R.id.btn_account_sign);

        name.setText(account.displayName());
        current.setVisibility(isActive ? View.VISIBLE : View.GONE);
        row.setBackgroundResource(isActive
                ? R.drawable.bg_drawer_account_active
                : R.drawable.bg_drawer_account);

        boolean signed = account.isSignedToday();
        signState.setText(account.drawerSignText());
        signState.setTextColor(getResources().getColor(
                signed ? R.color.success : R.color.text_hint));
        // build62: 已签标记改用矢量对勾，不再用 ✓ 字形
        signState.setCompoundDrawablesRelativeWithIntrinsicBounds(
                signed ? R.drawable.ic_check : 0, 0, 0, 0);
        signState.setCompoundDrawablePadding(
                (int) (3 * getResources().getDisplayMetrics().density));
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(signState,
                android.content.res.ColorStateList.valueOf(
                        getResources().getColor(R.color.success, null)));

        signBtn.setText(signed ? "已签" : "签到");
        signBtn.setBackgroundResource(signed
                ? R.drawable.bg_sign_chip_done : R.drawable.bg_sign_chip);
        signBtn.setEnabled(!signed);
        signBtn.setOnClickListener(signed ? null : v -> signSingleAccount(account));

        if (!android.text.TextUtils.isEmpty(account.avatar)) {
            try {
                com.bumptech.glide.Glide.with(this)
                        .load(com.solosu.mtforum.util.ForumImageLoader.model(account.avatar))
                        .placeholder(R.drawable.ic_account)
                        .error(R.drawable.ic_account)
                        .circleCrop()
                        .into(avatar);
            } catch (Exception ignored) {
                avatar.setImageResource(R.drawable.ic_account);
            }
        } else {
            avatar.setImageResource(R.drawable.ic_account);
        }

        com.solosu.mtforum.ui.anim.Motion.pressFeedback(row, 0.97f);
        com.solosu.mtforum.ui.anim.Motion.pressFeedback(signBtn, 0.90f);

        // 点整行 = 立即切换
        row.setOnClickListener(v -> {
            if (isActive) {
                Toast.makeText(this, "已是当前账号", Toast.LENGTH_SHORT).show();
                return;
            }
            switchToAccount(account);
        });
        // 长按 = 更多操作
        row.setOnLongClickListener(v -> {
            showAccountActions(account, isActive);
            return true;
        });
    }

    /** 切到指定账号：cookie 快照回灌 + 展示层同步 */
    private void switchToAccount(com.solosu.mtforum.session.AccountManager.Account account) {
        boolean ok = com.solosu.mtforum.session.AccountManager.switchTo(this, account.uid);
        if (!ok) {
            Toast.makeText(this, "该账号登录态已失效，正在尝试用已保存的密码重登…",
                    Toast.LENGTH_SHORT).show();
            com.solosu.mtforum.session.SessionGuard.reloginAccount(this, account.uid, (success, message) -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                if (success) applySwitchedAccount(account);
            });
            return;
        }
        applySwitchedAccount(account);
    }

    private void applySwitchedAccount(com.solosu.mtforum.session.AccountManager.Account account) {
        java.util.Map<String, String> info = new java.util.HashMap<>();
        info.put("username", account.displayName());
        info.put("uid", account.uid);
        info.put("avatarUrl", account.avatar != null ? account.avatar : "");
        info.put("level", account.level != null ? account.level : "");
        UserSessionManager.getInstance().saveLoginInfo(this, info);
        // 签到日期是按账号算的，切号后必须清掉，否则新账号会被误判"今日已签"
        UserSessionManager.getInstance().clearSignInDate(this);
        Toast.makeText(this, "已切换到 " + account.displayName(), Toast.LENGTH_SHORT).show();
        refreshDrawerHeader();
        com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn();
        // build65: 立刻刷新当前可见页，不用等用户手动下拉
        if (mainPager != null) triggerRefresh(mainPager.getCurrentItem());
    }

    /** 单个账号补签 */
    private void signSingleAccount(com.solosu.mtforum.session.AccountManager.Account account) {
        Toast.makeText(this, "正在给 " + account.displayName() + " 签到…", Toast.LENGTH_SHORT).show();
        com.solosu.mtforum.session.MultiSignInManager.signInOne(this, account.uid, true,
                new com.solosu.mtforum.session.MultiSignInManager.Callback() {
                    @Override
                    public void onProgress(int index, int total, String username) {
                    }

                    @Override
                    public void onFinished(
                            com.solosu.mtforum.session.MultiSignInManager.Summary summary) {
                        if (isFinishing() || isDestroyed()) return;
                        String msg = summary.items.isEmpty()
                                ? "签到结束" : summary.items.get(0).line();
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        refreshDrawerHeader();
                        com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn();
                    }
                });
    }

    /** 长按账号行的操作菜单 */
    private void showAccountActions(com.solosu.mtforum.session.AccountManager.Account account,
                                    boolean isActive) {
        java.util.List<String> items = new java.util.ArrayList<>();
        if (!isActive) items.add("切换到该账号");
        items.add(account.hasPassword() ? "修改密码" : "保存密码（用于掉线自动重登）");
        if (account.hasPassword()) items.add("清除已保存的密码");
        items.add("账号与签到管理");
        items.add("删除该账号");

        String[] arr = items.toArray(new String[0]);
        android.app.Dialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(account.displayName())
                .setItems(arr, (d, which) -> {
                    String action = arr[which];
                    switch (action) {
                        case "切换到该账号":
                            switchToAccount(account);
                            break;
                        case "账号与签到管理":
                            openAccountManager();
                            break;
                        case "清除已保存的密码":
                            com.solosu.mtforum.session.AccountManager.clearPassword(this, account.uid);
                            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
                            refreshDrawerHeader();
                            break;
                        case "删除该账号":
                            confirmDeleteAccount(account);
                            break;
                        default:
                            showPasswordDialog(account);
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    /** 密码只写不读：已存密码只显示占位符，永不回显明文 */
    private void showPasswordDialog(com.solosu.mtforum.session.AccountManager.Account account) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(account.hasPassword() ? "输入新密码以覆盖" : "输入论坛密码");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setTextColor(getResources().getColor(R.color.text_primary));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        String msg = account.hasPassword()
                ? "当前状态：已保存密码（••••••••）\n出于安全考虑不回显原密码，输入新密码即可覆盖。"
                : "密码经 Android KeyStore 的 AES-GCM 加密后只存本机，用于 403 掉线时自动重新登录。";

        android.app.Dialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(account.hasPassword() ? "修改密码" : "保存密码")
                .setMessage(msg)
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    String pwd = et.getText().toString().trim();
                    if (android.text.TextUtils.isEmpty(pwd)) {
                        Toast.makeText(this, "密码为空，未保存", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    com.solosu.mtforum.session.AccountManager.setPassword(this, account.uid, pwd);
                    Toast.makeText(this, "已加密保存", Toast.LENGTH_SHORT).show();
                    refreshDrawerHeader();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    private void confirmDeleteAccount(com.solosu.mtforum.session.AccountManager.Account account) {
        android.app.Dialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("确定从本机移除 " + account.displayName() + " 吗？\n"
                        + "只删除本地登录态和密码，不影响论坛账号本身。")
                .setPositiveButton("删除", (d, w) -> {
                    com.solosu.mtforum.session.AccountManager.remove(this, account.uid);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    refreshDrawerHeader();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    private void openOwnProfile() {
        UserSessionManager session = UserSessionManager.getInstance();
        if (!session.isLoggedIn(this)) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        String uid = session.getUid(this);
        if (uid == null || uid.isEmpty()) {
            Toast.makeText(this, "缺少UID", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, com.solosu.mtforum.ui.space.UserProfileActivity.class);
        it.putExtra("uid", uid);
        it.putExtra("username", session.getUsername(this));
        if (drawerLayout != null && drawerPanel != null) {
            drawerLayout.closeDrawer(drawerPanel);
        }
        startActivity(it);
    }

    /**
     * 刷新侧边栏头部与各行副标题。
     *
     * build61 修两个"明明登录了却显示未登录"的坑：
     *  1) drawer_accounts_desc 在 XML 里写死成"当前账号：未登录"，此前代码从未赋值过；
     *  2) UserSessionManager.isLoggedIn() 强依赖 uid，抓资料页失败时 uid 为空就判未登录 ——
     *     这里补一条"Cookie 还活着也算已登录"的兜底，并顺手异步把 uid 补回来。
     */
    private void refreshDrawerHeader() {
        if (tvDrawerName == null) return;
        UserSessionManager session = UserSessionManager.getInstance();
        boolean profileLogged = session.isLoggedIn(this);
        boolean cookieAlive = false;
        try {
            cookieAlive = HttpClient.getInstance().isLoggedIn();
        } catch (Exception ignored) {
        }
        boolean logged = profileLogged || cookieAlive;
        String name = session.getUsername(this);

        if (!logged) {
            tvDrawerName.setText("未登录");
        } else if (android.text.TextUtils.isEmpty(name)) {
            tvDrawerName.setText("已登录");
        } else {
            tvDrawerName.setText(name);
        }

        // Cookie 有效但资料缺失 -> 后台补抓 uid/头像，下次进来就正常了
        if (cookieAlive && !profileLogged) {
            AutoSignInManager.syncCurrentSessionToAccounts(this);
        }

        if (tvDrawerSubtitle != null) {
            String uid = session.getUid(this);
            if (logged && !android.text.TextUtils.isEmpty(uid)) {
                String shield = session.getLevel(this);
                // level 存储值可能自带 Lv 前缀(ForumParser 抓整行文本), 剥掉防 Lv.Lv.
                String lv = shield == null ? "" : shield.trim();
                if (lv.length() >= 2 && (lv.charAt(0) == 'L' || lv.charAt(0) == 'l')
                        && (lv.charAt(1) == 'V' || lv.charAt(1) == 'v')) {
                    lv = lv.substring(2);
                    while (lv.startsWith(".")) lv = lv.substring(1);
                    lv = lv.trim();
                }
                tvDrawerSubtitle.setText(android.text.TextUtils.isEmpty(lv)
                        ? "UID " + uid : "UID " + uid + " · Lv." + lv);
            } else if (logged) {
                tvDrawerSubtitle.setText("正在同步资料…");
            } else {
                tvDrawerSubtitle.setText("点击登录后使用自动化功能");
            }
        }

        if (tvAiDesc != null) {
            tvAiDesc.setText(AiConfigManager.isConfigured(this)
                    ? AiConfigManager.getModel(this) : "未配置模型");
        }

        if (ivDrawerAvatar != null) {
            String avatar = session.getAvatarUrl(this);
            if (logged && !android.text.TextUtils.isEmpty(avatar)) {
                com.bumptech.glide.Glide.with(this)
                        .load(com.solosu.mtforum.util.ForumImageLoader.model(avatar))
                        .placeholder(R.drawable.ic_account)
                        .error(R.drawable.ic_account)
                        .circleCrop()
                        .into(ivDrawerAvatar);
            } else {
                ivDrawerAvatar.setImageResource(R.drawable.ic_account);
            }
        }

        refreshDrawerDescriptions(logged, name);
        renderAccountList();
    }

    /** 各功能行的副标题，之前全是 XML 写死的静态文案 */
    private void refreshDrawerDescriptions(boolean logged, String name) {
        int total = com.solosu.mtforum.session.AccountManager.count(this);
        int signed = 0;
        for (com.solosu.mtforum.session.AccountManager.Account a
                : com.solosu.mtforum.session.AccountManager.list(this)) {
            if (a.isSignedToday()) signed++;
        }

        if (tvAccountsDesc != null) {
            String current = logged && !android.text.TextUtils.isEmpty(name) ? name : "未登录";
            tvAccountsDesc.setText(total > 0
                    ? "当前：" + current + " · 共 " + total + " 个账号"
                    : "当前：" + current);
        }

        if (tvSignInDesc != null) {
            // build67: 把上一轮自动签到的真实结果显示出来。
            // 之前只写死"启动时自动打卡"，用户根本没法判断到底跑没跑、为什么没签上。
            StringBuilder sb = new StringBuilder();
            if (com.solosu.mtforum.session.SignInSettings.isScheduleEnabled(this)) {
                sb.append("每天 ")
                  .append(com.solosu.mtforum.session.SignInSettings.getTimeText(this))
                  .append(" 定时 · ");
            }
            long last = com.solosu.mtforum.session.SignInSettings.getLastRunTime(this);
            String summary = com.solosu.mtforum.session.SignInSettings.getLastSummary(this);
            if (last > 0 && !android.text.TextUtils.isEmpty(summary)) {
                sb.append(new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date(last)))
                  .append(' ').append(summary);
            } else {
                sb.append("启动时自动打卡（还没跑过）");
            }
            tvSignInDesc.setText(sb.toString());
        }

        if (tvRunSignInDesc != null) {
            tvRunSignInDesc.setText(total > 0
                    ? "今日已签 " + signed + "/" + total + " 个账号"
                    : "还没有可签到的账号");
        }
    }

    /** 打开账号与签到管理页 */
    private void openAccountManager() {
        if (drawerLayout != null && drawerPanel != null
                && drawerLayout.isDrawerOpen(drawerPanel)) {
            drawerLayout.closeDrawer(drawerPanel);
        }
        startActivity(new Intent(this,
                com.solosu.mtforum.ui.account.AccountManagerActivity.class));
    }

    /**
     * 立即签到一次（无论开关状态）。
     * build60: 账号库里有多个账号时直接批量签，结果用对话框列出每个账号的明细。
     */
    private void doSignInNow() {
        if (drawerLayout != null && drawerPanel != null
                && drawerLayout.isDrawerOpen(drawerPanel)) {
            drawerLayout.closeDrawer(drawerPanel);
        }
        java.util.List<com.solosu.mtforum.session.AccountManager.Account> enabled =
                com.solosu.mtforum.session.AccountManager.enabledList(this);
        boolean multi = com.solosu.mtforum.session.SignInSettings.isAllAccounts(this)
                && enabled.size() > 1;

        if (multi) {
            Toast.makeText(this, "开始给 " + enabled.size() + " 个账号签到…", Toast.LENGTH_SHORT).show();
            com.solosu.mtforum.session.MultiSignInManager.signInAll(this, true,
                    new com.solosu.mtforum.session.MultiSignInManager.Callback() {
                        @Override
                        public void onProgress(int index, int total, String username) {
                        }

                        @Override
                        public void onFinished(
                                com.solosu.mtforum.session.MultiSignInManager.Summary summary) {
                            if (isFinishing() || isDestroyed()) return;
                            AiLog.i("sign-in", summary.detailText());
                            android.app.Dialog dlg = new androidx.appcompat.app.AlertDialog.Builder(
                                    MainActivity.this)
                                    .setTitle("签到结果 · " + summary.shortText())
                                    .setMessage(summary.detailText())
                                    .setPositiveButton("知道了", null)
                                    .show();
                            DialogHelper.applyToAlertDialog(dlg, MainActivity.this);
                            com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn();
                            refreshDrawerHeader();
                        }
                    });
            return;
        }

        AutoSignInManager.signInNow(this, true, (success, performed, message) -> {
            if (isFinishing() || isDestroyed()) return;
            AiLog.i("sign-in", "success=" + success + " performed=" + performed + " " + message);
            Toast.makeText(this, message == null ? "签到完成" : message, Toast.LENGTH_SHORT).show();
            if (success) {
                com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn();
            }
        });
    }

    /** 弹出运行日志 */
    private void showRunLog() {
        // build68: 先给一段加载耗时摘要，再接完整运行日志 ——
        // 之前性能记录会被自动回复/角标刷新的日志挤出 300 条缓冲，等于看不到
        String text = "═════ 页面加载耗时（最新在上）═════\n"
                + com.solosu.mtforum.util.PerfLog.dump()
                + "\n═════ 自动解锁记录 ═════\n"
                + com.solosu.mtforum.util.UnlockLog.dump()
                + "\n═════ 运行日志 ═════\n"
                + AiLog.dump();
        final android.widget.ScrollView sv = new android.widget.ScrollView(this);
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);

        String path = AiLog.filePath();
        String hint = android.text.TextUtils.isEmpty(path)
                ? "" : "\n\n日志文件：\n" + path;

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("运行日志（" + AiLog.size() + " 条）")
                .setMessage(hint.trim().isEmpty() ? null : hint)
                .setView(sv)
                .setNeutralButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "mtforum-log", text + hint));
                        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("清空", (d, w) -> {
                    AiLog.clear();
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /* UPDATE MODULE - temporarily disabled
    private void checkForUpdateOnStartup() {
        UpdateChecker.check(this, result -> {
            if (!result.success || !result.hasUpdate
                    || isFinishing()
                    || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            android.app.Dialog alertDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("发现新版本")
                    .setMessage("更新文件夹检测到新内容，是否打开更新页面？")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("立即更新", (dialog, which) -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse(UpdateChecker.UPDATE_URL)));
                        } catch (Exception e) {
                            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
                        }
                    .show();
                    DialogHelper.applyToAlertDialog(alertDialog, this);
        });
    }
    */

    /**
     * 初始化悬浮胶囊导航栏（5项：首页/版块/凸起发布/消息/我的）
     */
    private void initNavigationBar() {
        navHome = findViewById(R.id.nav_home_tab);
        navCommunity = findViewById(R.id.nav_community_tab);
        navPost = findViewById(R.id.nav_post_tab);
        navMessage = findViewById(R.id.nav_message_tab);
        navProfile = findViewById(R.id.nav_profile_tab);

        ivHomeIcon = findViewById(R.id.nav_home_icon);
        ivCommunityIcon = findViewById(R.id.nav_community_icon);
        ivPostIcon = findViewById(R.id.nav_post_icon);
        ivMessageIcon = findViewById(R.id.nav_message_icon);
        ivProfileIcon = findViewById(R.id.nav_profile_icon);

        tvHomeText = findViewById(R.id.nav_home_text);
        tvCommunityText = findViewById(R.id.nav_community_text);
        tvMessageText = findViewById(R.id.nav_message_text);
        tvProfileText = findViewById(R.id.nav_profile_text);

        // 空指针保护
        if (navHome == null || navCommunity == null || navPost == null || navMessage == null || navProfile == null) {
            android.util.Log.e("MainActivity", "Navigation bar views are null");
            return;
        }
        if (ivHomeIcon == null || ivCommunityIcon == null || ivPostIcon == null || ivMessageIcon == null || ivProfileIcon == null) {
            android.util.Log.e("MainActivity", "Navigation bar icons are null");
            return;
        }
        if (tvHomeText == null || tvCommunityText == null || tvMessageText == null || tvProfileText == null) {
            android.util.Log.e("MainActivity", "Navigation bar texts are null");
            return;
        }

        // 前4项 — 首页/版块/我的使用NavController导航；消息直接启动NoticeActivity
        navHome.setOnClickListener(v -> onTabTapped(MainPagerAdapter.PAGE_HOME));
        navCommunity.setOnClickListener(v -> onTabTapped(MainPagerAdapter.PAGE_COMMUNITY));
        navMessage.setOnClickListener(v -> onTabTapped(MainPagerAdapter.PAGE_MESSAGE));
        navProfile.setOnClickListener(v -> onTabTapped(MainPagerAdapter.PAGE_PROFILE));

        // 中间凸起发布按钮 — 启动 PostActivity
        navPost.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PostActivity.class);
            startActivity(intent);
        });

        // ===== build62: 参考 kd64i/dyparse 的悬浮胶囊底栏 =====
        navIndicator = findViewById(R.id.nav_indicator);
        navItems = findViewById(R.id.nav_items);
        navSlots = new View[]{navHome, navCommunity, navPost, navMessage, navProfile};

        // 按压反馈：图标类用 0.90，凸起发布键用 0.92
        for (View slot : navSlots) {
            com.solosu.mtforum.ui.anim.Motion.pressFeedback(slot,
                    slot == navPost ? 0.92f : 0.90f);
        }

        // 系统导航栏 inset 补偿：dyparse 的做法是栏体悬在系统导航栏之上固定 12dp。
        // 原来写死 12dp，手势条/三键导航会压住栏体。
        applyNavBarInsets();

        // 初始选中状态
        updateNavSelectionByPosition(MainPagerAdapter.PAGE_HOME);
    }

    /** 底栏避让系统导航栏，并算出内容需要预留的底部留白 */
    private void applyNavBarInsets() {
        final View container = findViewById(R.id.bottom_nav_container);
        if (container == null) return;
        container.post(() -> {
            int navInset = 0;
            try {
                androidx.core.view.WindowInsetsCompat insets =
                        androidx.core.view.ViewCompat.getRootWindowInsets(container);
                if (insets != null) {
                    navInset = insets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                }
            } catch (Exception ignored) {
            }
            float density = getResources().getDisplayMetrics().density;
            int base = (int) (12 * density);
            if (container.getLayoutParams()
                    instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams lp =
                        (android.view.ViewGroup.MarginLayoutParams) container.getLayoutParams();
                lp.bottomMargin = base + navInset;
                container.setLayoutParams(lp);
                navReservedPx = container.getHeight() + lp.bottomMargin;
            }
            // 边到边模式下（navInset>0），内容区再让出系统导航栏的高度，
            // 否则各 Fragment 里写死的底部留白只够避开悬浮栏、避不开手势条。
            View pager = findViewById(R.id.main_pager);
            if (pager != null && navInset > 0) {
                pager.setPadding(pager.getPaddingLeft(), pager.getPaddingTop(),
                        pager.getPaddingRight(), navInset);
            }
        });
    }

    /**
     * 把选中指示器弹到目标槽位。
     *
     * <p>用 snappy 弹簧（stiffness 350 / ζ0.75，见 Motion）而不是 tween ——
     * handfeel.md §1：lerp 单调减速读起来像"滑过去"，欠阻尼弹簧轻微过冲再落位，
     * 才有"啪一下吸附过去"的高级感。
     */
    private void moveIndicatorTo(int slotIndex, boolean animate) {
        if (navIndicator == null || navSlots == null) return;
        if (slotIndex < 0 || slotIndex >= navSlots.length) return;
        final View slot = navSlots[slotIndex];
        if (slot == null) return;

        Runnable move = () -> {
            if (slot.getWidth() == 0) return;
            float targetX = slot.getLeft() + (slot.getWidth() - navIndicator.getWidth()) / 2f;
            if (navItems != null) targetX += navItems.getLeft();
            if (animate) {
                com.solosu.mtforum.ui.anim.Motion.spring(navIndicator,
                        androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X,
                        targetX, com.solosu.mtforum.ui.anim.Motion.springSnappy());
            } else {
                navIndicator.setTranslationX(targetX);
            }
        };
        if (slot.getWidth() == 0) {
            slot.post(move);
        } else {
            move.run();
        }
    }

    /**
     * 底栏 Tab 点击（build63）。
     * 不在当前页 → 切页；已经在当前页 → 触发该页刷新（回到顶部 + 重拉数据）。
     */
    private void onTabTapped(int position) {
        if (mainPager != null && mainPager.getCurrentItem() == position) {
            triggerRefresh(position);
            return;
        }
        switchPage(position);
    }

    /** 找到 ViewPager2 当前页的 Fragment，若实现了 Refreshable 就调它 */
    private void triggerRefresh(int position) {
        try {
            // ViewPager2 + FragmentStateAdapter 的 Fragment tag 固定为 "f" + itemId
            androidx.fragment.app.Fragment fragment =
                    getSupportFragmentManager().findFragmentByTag("f" + position);
            if (fragment instanceof com.solosu.mtforum.ui.Refreshable) {
                ((com.solosu.mtforum.ui.Refreshable) fragment).onTabReselected();
                // 图标回弹一下，给出「已响应」的反馈
                ImageView icon = iconOfPage(position);
                if (icon != null) {
                    icon.setScaleX(0.8f);
                    icon.setScaleY(0.8f);
                    com.solosu.mtforum.ui.anim.Motion.spring(icon,
                            androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f,
                            com.solosu.mtforum.ui.anim.Motion.springBouncy());
                    com.solosu.mtforum.ui.anim.Motion.spring(icon,
                            androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f,
                            com.solosu.mtforum.ui.anim.Motion.springBouncy());
                }
            }
        } catch (Exception e) {
            AiLog.i("nav", "刷新当前页失败：" + e);
        }
    }

    private ImageView iconOfPage(int position) {
        switch (position) {
            case MainPagerAdapter.PAGE_HOME: return ivHomeIcon;
            case MainPagerAdapter.PAGE_COMMUNITY: return ivCommunityIcon;
            case MainPagerAdapter.PAGE_MESSAGE: return ivMessageIcon;
            case MainPagerAdapter.PAGE_PROFILE: return ivProfileIcon;
            default: return null;
        }
    }

    // ==================== build65: 底栏滚动自动隐藏 ====================

    private boolean navBarShown = true;

    /**
     * 下滑隐藏底栏、上滑显示。用弹簧位移而不是 setVisibility，避免布局跳动。
     */
    public void setNavBarShown(boolean shown) {
        if (navBarShown == shown) return;
        View container = findViewById(R.id.bottom_nav_container);
        if (container == null) return;
        // 抽屉打开时底栏本来就是隐藏的，别抢
        if (drawerLayout != null && drawerPanel != null
                && drawerLayout.isDrawerOpen(drawerPanel)) return;
        navBarShown = shown;
        float target = shown ? 0f
                : container.getHeight() + ((android.view.ViewGroup.MarginLayoutParams)
                        container.getLayoutParams()).bottomMargin + 16f;
        com.solosu.mtforum.ui.anim.Motion.spring(container,
                androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y,
                target, com.solosu.mtforum.ui.anim.Motion.springDefault());
    }

    /** pager 页序号 → 底栏槽位（中间第 2 槽是发布键，要跳过） */
    private int slotOfPage(int position) {
        return position < 2 ? position : position + 1;
    }

    /**
     * 导航到指定目的地（仅用于导航栏非发布项）
     */
    private void switchPage(int position) {
        if (mainPager != null) {
            mainPager.setCurrentItem(position, true);
        }
        updateNavSelectionByPosition(position);
    }

    /**
     * 更新导航栏选中状态
     */
    private void updateNavSelectionByPosition(int position) {
        setNavBarShown(true);   // build65: 切页一律把底栏放出来
        resetAllSelection();
        // build62: 指示器弹簧滑到对应槽位（发布键占中间槽，要跳过）
        moveIndicatorTo(slotOfPage(position), true);

        switch (position) {
            case MainPagerAdapter.PAGE_HOME:
                setItemActive(ivHomeIcon, tvHomeText);
                break;
            case MainPagerAdapter.PAGE_COMMUNITY:
                setItemActive(ivCommunityIcon, tvCommunityText);
                break;
            case MainPagerAdapter.PAGE_MESSAGE:
                setItemActive(ivMessageIcon, tvMessageText);
                break;
            case MainPagerAdapter.PAGE_PROFILE:
                setItemActive(ivProfileIcon, tvProfileText);
                break;
            default:
                break;
        }
    }

    /**
     * 重置所有导航项为未选中状态
     */
    private void resetAllSelection() {
        setItemInactive(ivHomeIcon, tvHomeText);
        setItemInactive(ivCommunityIcon, tvCommunityText);
        setItemInactive(ivMessageIcon, tvMessageText);
        setItemInactive(ivProfileIcon, tvProfileText);
    }

    /**
     * 选中态（build62）。
     * 原来是 150ms 淡入 —— 淡入只改透明度，读不出"被选中"的动作感。
     * 改成 bouncy 弹簧把图标弹一下（ζ≈0.35，明显过冲），文字同时提色。
     */
    private void setItemActive(ImageView icon, TextView text) {
        if (icon == null || text == null) return;
        icon.setImageResource(getActiveIconRes(icon.getId()));
        icon.clearAnimation();
        icon.setScaleX(0.82f);
        icon.setScaleY(0.82f);
        com.solosu.mtforum.ui.anim.Motion.spring(icon,
                androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f,
                com.solosu.mtforum.ui.anim.Motion.springBouncy());
        com.solosu.mtforum.ui.anim.Motion.spring(icon,
                androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f,
                com.solosu.mtforum.ui.anim.Motion.springBouncy());
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.nav_icon_active, null)));
        text.setTextColor(getResources().getColor(R.color.nav_text_active, null));
    }

    private void setItemInactive(ImageView icon, TextView text) {
        if (icon == null || text == null) return;
        icon.setImageResource(getInactiveIconRes(icon.getId()));
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.nav_icon_default, null)));
        text.setTextColor(getResources().getColor(R.color.nav_text_default, null));
    }

    private int getActiveIconRes(int viewId) {
        if (viewId == R.id.nav_home_icon) {
            return R.drawable.ic_home;
        } else if (viewId == R.id.nav_community_icon) {
            return R.drawable.ic_discover;
        } else if (viewId == R.id.nav_message_icon) {
            return R.drawable.ic_message;
        } else if (viewId == R.id.nav_profile_icon) {
            return R.drawable.ic_profile;
        }
        return 0;
    }

    private int getInactiveIconRes(int viewId) {
        return getActiveIconRes(viewId);
    }

    // ==================== 消息角标 ====================

    /**
     * 刷新消息角标（获取未读PM数）
     */
    private void refreshMessageBadge() {
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastBadgeRefreshAt < BADGE_RESUME_THROTTLE_MS && lastBadgeRefreshAt > 0) {
            scheduleNextBadgeRefresh();
            return;
        }
        lastBadgeRefreshAt = nowMs;
        if (!HttpClient.getInstance().isLoggedIn()) {
            badgeRefreshInFlight.set(false);
            if (tvMessageBadge != null) tvMessageBadge.setVisibility(View.GONE);
            scheduleNextBadgeRefresh();
            return;
        }
        // 防止上一次网络刷新尚未结束时重复提交任务。
        if (!badgeRefreshInFlight.compareAndSet(false, true)) return;
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            badgeRefreshInFlight.set(false);
            scheduleNextBadgeRefresh();
            return;
        }

        // 六类列表并行请求；原来单线程逐个请求，尤其 PM 还会请求会话详情，导致角标延迟明显。
        try {
            executor.execute(() -> {
                java.util.Map<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();
                java.util.Map<String, String> fingerprints = new java.util.concurrent.ConcurrentHashMap<>();
                String[] types = {"pm", "follower", "mypost", "interactive", "system", "app"};
                try {
                    HttpClient.getInstance().syncFromCookieManager();
                    CountDownLatch latch = new CountDownLatch(types.length);
                    for (String type : types) {
                        final String viewType = type;
                        final String url;
                        if ("pm".equals(viewType)) {
                            url = HttpClient.BASE_URL + "home.php?mod=space&do=pm&mobile=2";
                        } else if ("follower".equals(viewType)) {
                            url = HttpClient.BASE_URL + "home.php?mod=follow&do=follower&uid="
                                    + getCurrentUid() + "&mobile=2";
                        } else {
                            url = HttpClient.BASE_URL + "home.php?mod=space&do=notice&view=" + viewType;
                        }
                        try {
                            executor.execute(() -> {
                                try {
                                    putUnreadData(counts, fingerprints, viewType, url);
                                } finally {
                                    latch.countDown();
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException rejected) {
                            latch.countDown();
                        }
                    }
                    latch.await(25, java.util.concurrent.TimeUnit.SECONDS);
                    runOnUiThread(() -> {
                        badgeRefreshInFlight.set(false);
                        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                        applyUnreadCounts(counts, fingerprints);
                    });
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    runOnUiThread(() -> {
                        badgeRefreshInFlight.set(false);
                        if (!isFinishing()) scheduleNextBadgeRefresh();
                    });
                } catch (Exception ignored) {
                    runOnUiThread(() -> {
                        badgeRefreshInFlight.set(false);
                        if (!isFinishing()) scheduleNextBadgeRefresh();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            badgeRefreshInFlight.set(false);
            scheduleNextBadgeRefresh();
        }
    }

    private String getCurrentUid() {
        String uid = com.solosu.mtforum.session.UserSessionManager
                .getInstance().getUid(this);
        return uid == null || uid.isEmpty() ? "0" : uid;
    }

    private int fetchUnreadCount(String url, String viewType) {
        try {
            String html = ("pm".equals(viewType) || "follower".equals(viewType))
                    ? HttpClient.getInstance().get(url)
                    : HttpClient.getInstance().getDesktop(url);
            if (html == null || html.isEmpty() || ForumParser.isLoginPage(html)) return 0;
            List<com.solosu.mtforum.model.Message> items;
            if ("pm".equals(viewType)) items = ForumParser.parsePmList(html);
            else if ("follower".equals(viewType)) items = ForumParser.parseFollowerList(html);
            else items = ForumParser.parseNoticeList(html);
            int count = 0;
            if (items != null) for (com.solosu.mtforum.model.Message item : items) {
                if (!item.isRead()) count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void putUnreadData(java.util.Map<String, Integer> counts,
                                 java.util.Map<String, String> fingerprints,
                                 String viewType, String url) {
        try {
            String html = ("pm".equals(viewType) || "follower".equals(viewType))
                    ? HttpClient.getInstance().get(url)
                    : HttpClient.getInstance().getDesktop(url);
            if (html == null || html.isEmpty() || ForumParser.isLoginPage(html)) {
                counts.put(viewType, 0);
                fingerprints.put(viewType, "");
                return;
            }

            // 与 NoticeFragment 使用同一套“当前列表 - 已查看快照”算法。
            String snapshot = NoticeBadgeManager.buildSnapshot(
                    viewType, html, HttpClient.getInstance());
            int count = NoticeBadgeManager.saveCurrentAndGetNewCount(
                    MainActivity.this, viewType, snapshot);
            counts.put(viewType, count);
            fingerprints.put(viewType, snapshot);
        } catch (Exception ignored) {
            counts.put(viewType, 0);
            fingerprints.put(viewType, "");
        }
    }

    private void applyUnreadCounts(java.util.Map<String, Integer> counts,
                                   java.util.Map<String, String> fingerprints) {
        // 底部导航栏显示所有消息分类的新增总数，不能只显示私信数量。
        int totalCount = 0;
        String[] allTypes = {"pm", "follower", "mypost", "interactive", "system", "app"};
        for (String type : allTypes) {
            totalCount += getCount(counts, type);
        }
        updateBadgeDisplay(totalCount);

        if (unreadBaselineReady) {
            boolean hasNewPrivateChat = hasNewUnread("pm", counts, fingerprints);
            boolean hasNewOtherNotice = false;
            String[] otherTypes = {"follower", "mypost", "interactive", "system", "app"};
            for (String type : otherTypes) {
                if (hasNewUnread(type, counts, fingerprints)) {
                    hasNewOtherNotice = true;
                    break;
                }
            }
            // 音频已移除
        }

        previousUnreadCounts.clear();
        previousUnreadCounts.putAll(counts);
        previousUnreadFingerprints.clear();
        previousUnreadFingerprints.putAll(fingerprints);
        unreadBaselineReady = true;
        scheduleNextBadgeRefresh();
    }

    private boolean hasNewUnread(String type, java.util.Map<String, Integer> counts,
                                 java.util.Map<String, String> fingerprints) {
        int currentCount = getCount(counts, type);
        int oldCount = getCount(previousUnreadCounts, type);
        String currentFingerprint = fingerprints.get(type);
        String oldFingerprint = previousUnreadFingerprints.get(type);
        return currentCount > oldCount
                || (currentCount > 0 && !java.util.Objects.equals(currentFingerprint, oldFingerprint));
    }

    private int getCount(java.util.Map<String, Integer> counts, String key) {
        Integer value = counts.get(key);
        return value == null ? 0 : value;
    }

    private void updateBadgeDisplay(int count) {
        if (tvMessageBadge == null) return;
        if (count > 0) {
            tvMessageBadge.setVisibility(View.VISIBLE);
            tvMessageBadge.setText(count > 99 ? "99+" : String.valueOf(count));
        } else {
            tvMessageBadge.setVisibility(View.GONE);
        }
        scheduleNextBadgeRefresh();
    }

    private void scheduleNextBadgeRefresh() {
        mainHandler.removeCallbacks(badgeRunnable);
        mainHandler.postDelayed(badgeRunnable, BADGE_REFRESH_INTERVAL_MS);
    }

    private final Runnable badgeRunnable = this::refreshMessageBadge;

    @Override
    protected void onStart() {
        super.onStart();
        // build61: 回前台先确认登录态，掉线则用已保存的密码静默重登
        com.solosu.mtforum.session.SessionGuard.ensureSession(this, (success, message) -> {
            if (isFinishing() || isDestroyed()) return;
            if (success) refreshDrawerHeader();
        });
        // build60: 老用户/首次登录可能还没入账号库，补一次，多账号签到才有数据
        AutoSignInManager.syncCurrentSessionToAccounts(this);
        AutoSignInManager.checkAndSignIn(this, (success, performed, message) -> {
            if (success || "今日已签到".equals(message)) {
                // 通知社区页刷新签到按钮状态
                com.solosu.mtforum.ui.community.CommunityFragment.refreshSignIn();
            }
            if (performed && success && !isFinishing()) {
                Toast.makeText(this, "自动签到成功", Toast.LENGTH_SHORT).show();
            } else if (!performed && !isFinishing() && "请先登录".equals(message)) {
                // 未登录时静默等待,不弹 Toast 打扰用户
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从消息页/后台返回时立即刷新，不等待下一轮定时任务。
        if (mainHandler != null && tvMessageBadge != null) {
            mainHandler.removeCallbacks(badgeRunnable);
            refreshMessageBadge();
        }
        // 登录状态 / AI 配置可能已变化，刷新侧边栏头部
        refreshDrawerHeader();
    }

    @Override
    protected void onStop() {
        if (mainHandler != null) mainHandler.removeCallbacks(badgeRunnable);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (frostedNavBackground != null) {
            frostedNavBackground.setVisible(false, false);
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 侧边栏开着时，返回键先收起侧边栏
        if (drawerLayout != null && drawerPanel != null
                && drawerLayout.isDrawerOpen(drawerPanel)) {
            drawerLayout.closeDrawer(drawerPanel);
            return;
        }
        // 首次返回提示，连续第二次返回才退出
        long now = System.currentTimeMillis();
        if (lastBackPressTime > 0 && now - lastBackPressTime < EXIT_INTERVAL_MS) {
            finishAffinity(); // 直接退出
            super.onBackPressed();
            return;
        }
        lastBackPressTime = now;
        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
    }


    /** build71: 浏览历史（长按侧边栏「运行日志」打开） */
    private void showQuickJumpDialog() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding((int)(20*d), 0, (int)(20*d), 0);
        android.widget.RadioGroup types = new android.widget.RadioGroup(this);
        types.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton thread = new android.widget.RadioButton(this); thread.setId(View.generateViewId()); thread.setText("帖子 TID"); thread.setChecked(true);
        android.widget.RadioButton user = new android.widget.RadioButton(this); user.setId(View.generateViewId()); user.setText("用户 UID");
        types.addView(thread); types.addView(user); box.addView(types);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入数字 ID，也可粘贴论坛链接"); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        box.addView(input, new android.widget.LinearLayout.LayoutParams(-1, (int)(56*d)));
        android.app.Dialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("ID 快速跳转").setView(box)
                .setPositiveButton("跳转", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(x -> ((androidx.appcompat.app.AlertDialog)dialog).getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText() == null ? "" : input.getText().toString().trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:tid=|thread-)?(\\d+)").matcher(raw);
            if (!m.find()) { input.setError("请输入有效数字 ID"); return; }
            String id = m.group(1); dialog.dismiss();
            if (types.getCheckedRadioButtonId() == user.getId()) {
                Intent it = new Intent(this, com.solosu.mtforum.ui.space.UserProfileActivity.class); it.putExtra("uid", id); startActivity(it);
            } else {
                com.solosu.mtforum.util.NavigationHelper.openThread(this, id);
            }
        }));
        dialog.show();
        com.solosu.mtforum.ui.widget.DialogHelper.applyToAlertDialog(dialog, this);
    }

    private void showBrowseHistory() {
        java.util.List<com.solosu.mtforum.session.HistoryStore.Item> list =
                com.solosu.mtforum.session.HistoryStore.list(this);
        if (list.isEmpty()) {
            Toast.makeText(this, "还没有浏览记录", Toast.LENGTH_SHORT).show();
            return;
        }
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            com.solosu.mtforum.session.HistoryStore.Item it = list.get(i);
            items[i] = fmt.format(new java.util.Date(it.at)) + "  " + it.title
                    + (android.text.TextUtils.isEmpty(it.author) ? "" : "\n            " + it.author);
        }
        android.app.Dialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("浏览历史（" + list.size() + "）")
                .setItems(items, (d, which) -> {
                    Intent it = new Intent(this,
                            com.solosu.mtforum.ui.detail.ThreadDetailActivity.class);
                    it.putExtra("tid", list.get(which).tid);
                    startActivity(it);
                })
                .setNeutralButton("清空", (d, w) -> {
                    com.solosu.mtforum.session.HistoryStore.clear(this);
                    Toast.makeText(this, "已清空浏览历史", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }
}