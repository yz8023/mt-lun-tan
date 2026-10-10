package com.solosu.mtforum.ui.detail;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Looper;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.ReplacementSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import androidx.annotation.NonNull;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.graphics.Typeface;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.internal.AssetHelper;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.ThreadDetailActivityBinding;
import com.solosu.mtforum.model.PostDetail;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.session.FollowStateManager;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.detail.ReplyAdapter;
import com.solosu.mtforum.ui.message.ChatActivity;
import com.solosu.mtforum.ui.space.UserProfileActivity;
import com.solosu.mtforum.ui.login.LoginBottomSheet;
import com.solosu.mtforum.util.ImageUrl;
import com.solosu.mtforum.util.BBCodeUtil;
import com.solosu.mtforum.util.NavigationHelper;
import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/* JADX INFO: loaded from: classes10.dex */
public class ThreadDetailActivity extends AppCompatActivity {
    private static final String HIDDEN_QUOTE_PLACEHOLDER = "\ue000\ue001\ue002\ue003";
    private static final String KEY_FAVORITED_PREFIX = "fav_";
    private static final String KEY_LIKED_PREFIX = "liked_";
    private static final String PREF_LIKE_FAV = "thread_like_fav_state";
    private ThreadDetailActivityBinding binding;
    private HttpClient httpClient;
    private BottomSheetDialog mBottomSheetDialog;
    private PostDetail postDetail;
    private ReplyAdapter replyAdapter;
    private String tid;
    private String targetPid = "";
    private boolean targetPidJumpInProgress = false;
    /** build87: 列表页带过来的真实 CDN 图，帖子页解析不到图时兜底用 */
    private java.util.List<String> listImageFallback = new java.util.ArrayList<>();

    /**
     * build107: WebView 原帖渲染模式下的「图廊兜底候选」。
     *
     * <p>原帖渲染默认把底部图廊关掉（避免同一张图正文、图廊各出现一次），
     * 但正文里那些需要登录态的附件一旦没渲染出来，用户看到的就是一整片空白 ——
     * 用户报的「这些帖子图片无法加载是白屏」正是这种情况。这里先把候选地址留一份，
     * 等确认正文一张图都没加载成功时再启用图廊兜底。
     */
    private final java.util.List<String> pendingGalleryUrls = new java.util.ArrayList<>();
    /** build107: 图廊兜底只做一次，避免重试回调反复弹。 */
    private boolean fallbackGalleryShown;
    /**
     * build107: 仍在用原生 OkHttp 重取的图片数。
     * 有重取在飞的时候不能下「一张都没有」的结论，必须等它们落地。
     */
    private int postWebImgPending;
    /**
     * build87: 上一次渲染时所在的账号。{@link #onResume()} 发现切号就重拉一次，
     * 否则已经打开的帖子页会一直停留在旧账号的点赞/收藏/关注态 ——
     * 这就是「切换账号后进入帖子还是原账号信息，不会全同步」。
     */
    private String lastRenderUid = null;
    /**
     * build92: 上一次渲染时「按原帖排版渲染」的取值。抽屉里拨开关回帖子页要重渲。
     */
    private Boolean lastWebRender;

    /** build91: 上一次渲染时「正文图片原位显示」的取值。
     * {@link #onResume()} 发现用户在抽屉里拨过开关就立刻本地重渲，
     * 不用等下次进帖 —— 否则开关看起来「没有作用」。
     */
    private Boolean lastImagesInline = null;
    private boolean onlyOpReplies = false;
    private boolean repliesDescending = true;
    /** 当前帖子解析到的全部回复，未应用本地隐藏规则。 */
    private List<ReplyItem> allReplies = new ArrayList<>();
    /** 应用作者黑名单、回复关键词及灌水规则后实际展示的回复。 */
    private List<ReplyItem> displayedReplies = new ArrayList<>();
    private boolean isLiked = false;
    private int likeCount = 0;
    private LikeUsersAdapter likeUsersAdapter;
    private boolean isFavorited = false;
    private int favoriteCount = 0;   // 真实收藏数(来自 ForumParser #comiis_favorite_a)
    private String currentReplyTarget = "";
    private boolean isLoadingMore = false;
    private String currentReplyPid = "";
    // build98: 快捷回复变量 {to} / {floor} 的值（正在回复谁、哪一层）
    private String currentReplyToName = "";
    private String currentReplyFloor = "";
    private String currentLoginUid = null; // build73: 当前登录 uid(判定是否本人)
    // build74b: 打赏目标(空=主楼;有值=评论楼层)
    private String rewardTargetPid = "";
    private String rewardTargetName = "";
    private String rewardTargetAvatar = "";
    private Uri pendingImageUri = null;
    private final List<String> pendingUploadAids = new ArrayList();
    private boolean imageUploadInProgress = false;
    // build71: 多选图片排队,避免上一张上传中时后续图片被静默丢弃
    private final java.util.List<android.net.Uri> imageUploadPendingQueue = new java.util.ArrayList<>();
    private final java.util.List<android.net.Uri> pendingImageUris = new java.util.ArrayList<>();
    private final java.util.Map<android.net.Uri, String> uploadedAidMap = new java.util.HashMap<>();
    private static final int REQUEST_IMAGE_PICK = 1002;
    private static final int REQUEST_EDIT_THREAD = 1003; // build73: 编辑帖子
    private static final int REQUEST_EXPORT_HTML = 1010;
    private static final int REQUEST_EXPORT_TEXT = 1011;
    private String pendingExportContent;

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = ThreadDetailActivityBinding.inflate(getLayoutInflater());
        setContentView(this.binding.getRoot());
        setupCopyContentButton();   // build64: 正文复制按钮
        // build67: AI 总结按钮默认隐藏，需在侧边栏显式开启 —— 避免被当成论坛自带功能
        if (this.binding.btnAiSummary != null) {
            this.binding.btnAiSummary.setVisibility(
                    com.solosu.mtforum.ui.UiSettings.isAiSummaryVisible(this)
                            ? View.VISIBLE : View.GONE);
        }
        this.httpClient = HttpClient.getInstance();
        this.tid = getIntent().getStringExtra("tid");
        if (this.tid == null) {
            finish();
            return;
        }
        String requestedPid = getIntent().getStringExtra("pid");
        if (requestedPid != null && requestedPid.matches("[0-9]{1,20}")) {
            this.targetPid = requestedPid;
        }
        // build87: 列表页带过来的真实 CDN 图，帖子页解析不到图时用它兜底。
        // 先看 Intent extra（列表页直达），没有再查 tid 登记表（搜索、日志中心、
        // 引用回复、相关帖子等其它入口），两条路都断了才真的没有。
        java.util.List<String> listImgs =
                getIntent().getStringArrayListExtra("list_images");
        if (listImgs == null || listImgs.isEmpty()) {
            listImgs = com.solosu.mtforum.util.ListImageRegistry.get(this.tid);
        }
        if (listImgs != null && !listImgs.isEmpty()) {
            this.listImageFallback = new java.util.ArrayList<>(listImgs);
        }
        this.binding.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$0(view);
            }
        });
        this.binding.swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override // androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
            public final void onRefresh() {
                ThreadDetailActivity.this.refreshPostDetail();
            }
        });
        setupRecyclerView();
        this.binding.etReply.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$1(view);
            }
        });
        this.binding.etReply.setFocusable(false);
        this.binding.etReply.setCursorVisible(false);
        this.binding.btnComments.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$3(view);
            }
        });
        ImageButton btnPickImageInline = findViewById(R.id.btn_pick_image_inline);
        if (btnPickImageInline != null) {
            btnPickImageInline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        }
        this.binding.btnLike.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$4(view);
            }
        });
        this.binding.btnFavorite.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$5(view);
            }
        });
        this.binding.btnExport.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showExportMenu(); }
        });
        this.binding.btnShare.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$6(view);
            }
        });
        this.binding.btnViewHidden.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$7(view);
            }
        });
        this.binding.btnLoadMore.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$8(view);
            }
        });
        this.binding.nestedScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override // android.view.View.OnScrollChangeListener
            public final void onScrollChange(View view, int i, int i2, int i3, int i4) {
                ThreadDetailActivity.this.lambda$onCreate$9(view, i, i2, i3, i4);
            }
        });
                this.binding.btnOnlyOp.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$11(view);
            }
        });
        this.binding.btnReplyOrder.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$12(view);
            }
        });
        this.binding.btnReward.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$13(view);
            }
        });
        this.binding.btnKick.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$onCreate$14(view);
            }
        });
        // build64: AI 总结(详情页入口,与列表卡片行为一致)
        // 注意: 本文件是反编译产物,手写 lambda$onCreate$N 合成方法名,
        // 新代码不能用 lambda(会撞名),必须用匿名内部类
        this.binding.btnAiSummary.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                Intent it = new Intent(ThreadDetailActivity.this,
                        com.solosu.mtforum.ai.AiSummarizeActivity.class);
                it.putExtra("tid", ThreadDetailActivity.this.tid);
                it.putExtra("title", ThreadDetailActivity.this.binding.tvThreadTitle.getText() != null
                        ? ThreadDetailActivity.this.binding.tvThreadTitle.getText().toString() : "");
                startActivity(it);
            }
        });
        loadPostDetail();
    }

    /**
     * build87: 切号同步。
     *
     * <p>详情页以前没有 onResume，用户在账号管理里换了账号再退回已打开的帖子页，
     * 界面还挂着旧账号的点赞/收藏/关注态 —— 用户报的就是「切换账号后进入帖子
     * 还是原账号信息，不会全同步」。这里发现账号变了就整页重拉一次。
     */
    protected void onResume() {
        super.onResume();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // build91: 抽屉里拨了「正文图片原位显示」开关，回到帖子页立刻重渲染。
        // 以前只有下次进帖才生效 —— 在当时打开的页面上拨开关毫无反应，
        // 用户报的就是「开关也是没有作用」。用已拿到的 postDetail 本地重渲，
        // 不重新请求网络。
        if (this.postDetail != null
                && ((this.lastImagesInline != null
                        && this.lastImagesInline != com.solosu.mtforum.ui.UiSettings.isImagesInline(this))
                    || (this.lastWebRender != null
                        && this.lastWebRender != com.solosu.mtforum.ui.UiSettings.isWebRender(this)))) {
            bindData(this.postDetail, false);
            return;
        }
        if (this.lastRenderUid == null) {
            return;
        }
        if (!this.lastRenderUid.equals(likeFavScope())) {
            this.lastRenderUid = likeFavScope();
            refreshPostDetail();
        }
    }

    @Override
    protected void onDestroy() {
        // build92: WebView 必须显式销毁。它内部持有 Activity Context 的引用，
        // 帖子详情页来回进出几次不释放就是一条实打实的内存泄漏链。
        if (this.binding.webContent != null) {
            try {
                this.binding.webContent.removeJavascriptInterface("PostBody");
                this.binding.webContent.stopLoading();
                this.binding.webContent.loadUrl("about:blank");
                this.binding.webContent.destroy();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private void lambda$onCreate$0(View v) {
        finish();
    }

    private void lambda$onCreate$1(View v) {
        showReplyBottomSheet(this.currentReplyTarget);
    }

    private void lambda$onCreate$3(View v) {
        this.binding.recyclerReplies.setVisibility(0);
        this.binding.nestedScroll.post(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$onCreate$2();
            }
        });
    }

    private void lambda$onCreate$2() {
        this.binding.nestedScroll.smoothScrollTo(0, this.binding.recyclerReplies.getTop());
    }

    private void lambda$onCreate$4(View v) {
        toggleLike();
    }

    private void lambda$onCreate$5(View v) {
        toggleFavorite();
    }

    private void lambda$onCreate$6(View v) {
        shareThread();
    }

    private void lambda$onCreate$7(View v) {
        viewHiddenContent();
    }

    private void lambda$onCreate$8(View v) {
        loadMoreReplies();
    }

    private void lambda$onCreate$9(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
        NestedScrollView scrollView = (NestedScrollView) v;
        View child = scrollView.getChildAt(0);
        if (child != null) {
            int contentHeight = child.getHeight() - scrollView.getHeight();
            boolean isAtBottom = scrollY >= contentHeight + (-200);
            boolean isNearBottom = scrollY >= contentHeight + (-400);
            if (isNearBottom && this.binding.btnLoadMore.getVisibility() == 8 && this.postDetail != null) {
                int currentPage = this.postDetail.getCurrentPage();
                int totalPages = this.postDetail.getTotalPages();
                if (currentPage < totalPages && !this.isLoadingMore) {
                    loadMoreReplies();
                }
            }

        }
    }

    private void lambda$onCreate$10(View v) {
        this.binding.nestedScroll.smoothScrollTo(0, this.binding.nestedScroll.getChildAt(0).getHeight());
    }

    private void lambda$onCreate$11(View v) {
        this.onlyOpReplies = !this.onlyOpReplies;
        updateReplyFilterAndOrder();
    }

    private void lambda$onCreate$12(View v) {
        this.repliesDescending = !this.repliesDescending;
        refreshPostDetail();
    }

    private void lambda$onCreate$13(View v) {
        showRewardDialog();
    }

    private void lambda$onCreate$14(View v) {
        showKickDialog();
    }

    private void setupRecyclerView() {
        this.replyAdapter = new ReplyAdapter(new ArrayList());
        // build73: 评论长按 -> 回复 / 举报 / (本人)删除
        this.replyAdapter.setOnReplyLongClickListener(new ReplyAdapter.OnReplyLongClickListener() {
            @Override
            public void onReplyLongClick(ReplyItem replyItem, int i) {
                showReplyActionMenu(replyItem);
            }
        });
        this.replyAdapter.setOnReplyClickListener(new ReplyAdapter.OnReplyClickListener() {
            @Override // com.solosu.mtforum.ui.detail.ReplyAdapter.OnReplyClickListener
            public final void onReplyClick(ReplyItem replyItem, int i) {
                ThreadDetailActivity.this.lambda$setupRecyclerView$15(replyItem, i);
            }
        });
        this.replyAdapter.setOnUserClickListener(new ReplyAdapter.OnUserClickListener() {
            @Override // com.solosu.mtforum.ui.detail.ReplyAdapter.OnUserClickListener
            public final void onUserClick(ReplyItem replyItem, int i) {
                ThreadDetailActivity.this.lambda$setupRecyclerView$16(replyItem, i);
            }
        });
        this.binding.recyclerReplies.setLayoutManager(new LinearLayoutManager(this));
        this.binding.recyclerReplies.setAdapter(this.replyAdapter);
    }

    private void lambda$setupRecyclerView$15(ReplyItem item, int position) {
        String author = item != null ? item.getAuthor() : "";
        if (!TextUtils.isEmpty(author)) {
            this.currentReplyPid = item != null ? item.getPid() : "";
            // build71: 不再把"回复 xx:"当预填文本塞进输入框(它会被一起发出去)
            this.currentReplyTarget = "";
            // build98: 快捷回复变量 {to}/{floor} 跟着这个目标走
            this.currentReplyToName = author;
            this.currentReplyFloor = item != null ? item.getFloorLabel() : "";
        } else {
            this.currentReplyPid = "";
            this.currentReplyTarget = "";
            this.currentReplyToName = "";
            this.currentReplyFloor = "";
        }
        showReplyBottomSheet(this.currentReplyTarget);
    }

    private void lambda$setupRecyclerView$16(ReplyItem item, int position) {
        String uid = item.getAuthorUid();
        if (!TextUtils.isEmpty(uid)) {
            Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
            intent.putExtra(ChatActivity.EXTRA_UID, uid);
            intent.putExtra("username", item.getAuthor());
            startActivity(intent);
        }
    }

    private void loadPostDetail() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        this.binding.progressBar.setVisibility(0);
        this.binding.swipeRefresh.setEnabled(false);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$loadPostDetail$19();
            }
        }).start();
    }

    private void lambda$loadPostDetail$19() {
        try {
            // build67: 去掉 &_load=时间戳 这个缓存破坏参数。
            // 每次进帖都强制生成唯一 URL，等于彻底绕开 CDN/连接复用与本地去重，
            // 大帖（几百楼）每次都要整页重下，这是"部分帖子加载特别慢"的主因之一。
            // 真正需要强刷的下拉刷新链路仍然带 &_refresh= 参数。
            String detailUrl = ForumParser.getThreadDetailUrl(this.tid, getReplyOrder());
            final long tLoadStart = System.currentTimeMillis();
            // build69: 用户主动点开的帖子属于前台导航，不进节流队列 ——
            // 实测令牌耗尽时这一个请求要白等 2 秒多，而真实网络只要 300ms。
            com.solosu.mtforum.network.RequestThrottle.markForeground();
            this.httpClient.syncFromCookieManager();
            // build70: 先看内存缓存 —— 刚看过的帖子直接秒开，再后台静默刷新。
            // 网络已经压到 ~320ms 了，想再快只能不发请求。
            String cached = com.solosu.mtforum.network.ThreadHtmlCache.get(
                    this.tid, getReplyOrder());
            if (cached != null) {
                final String cachedHtml = cached;
                final PostDetail cachedDetail = ForumParser.parseThreadDetail(cachedHtml);
                if (cachedDetail != null) {
                    com.solosu.mtforum.util.PerfLog.record("帖子 tid=" + this.tid + "(缓存)",
                            0, System.currentTimeMillis() - tLoadStart, cachedHtml.length());
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        bindData(cachedDetail, false);
                    });
                }
            }

            String html = this.httpClient.get(detailUrl);
            com.solosu.mtforum.network.ThreadHtmlCache.put(this.tid, getReplyOrder(), html);
            final long tFetched = System.currentTimeMillis();
            if (TextUtils.isEmpty(html)) {
                throw new IllegalStateException("服务器返回空页面，请检查网络后重试");
            }
            final PostDetail detail = ForumParser.parseThreadDetail(html);
            // build67: 分阶段耗时埋点，慢的时候能从「运行日志」直接看出卡在网络还是解析
            com.solosu.mtforum.network.RequestThrottle.clearForeground();
            com.solosu.mtforum.util.PerfLog.record("帖子 tid=" + this.tid,
                    tFetched - tLoadStart,
                    System.currentTimeMillis() - tFetched,
                    html == null ? 0 : html.length());
            if (detail == null) {
                throw new IllegalStateException("帖子内容解析失败");
            }
            // build80: 移出首屏，改到渲染后异步补（见 enrichAfterRender）
            // 原位置在<b>解析之后、runOnUiThread 渲染之前</b>同步执行，
            // 等于让「另一个帖子的桌面版整页」和「收藏列表整页」阻塞首屏。
            // build61: 进帖触发解锁——只记录页面,渲染后在后台线程执行(不阻塞首屏)
            this.pendingUnlockHtml = html;
            if (!TextUtils.isEmpty(detail.getAuthorUid())) {
                detail.setFollowed(FollowStateManager.resolve(this, detail.getAuthorUid(), detail.isFollowed()));
            }
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$loadPostDetail$17(detail);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$loadPostDetail$18(e);
                }
            });
        }
    }

    private void lambda$loadPostDetail$17(PostDetail detail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        bindData(detail, true);
        // build80: 首屏渲染完再补这两个非首屏数据（见 enrichAfterRender）
        enrichAfterRender(detail);
        // build61: 渲染完成后,后台线程执行解锁检测→回帖→成功再刷新
        runUnlockInBackground(detail);
    }
    /* build61: 待解锁页面快照(加载线程写,渲染后读一次即清) */
    private String pendingUnlockHtml;
    /** build61: 渲染后异步解锁——16 秒节流在后台线程里等,不卡首屏 */
    /** build74: 本次进入该帖是否已经解锁过一次，防止刷新链路二次回帖 */
    private boolean unlockAttemptedThisVisit = false;

    private void runUnlockInBackground(final PostDetail detail) {
        // build74 关键修复：解锁成功后会调 refreshPostDetail()，
        // 刷新链路又会设置 pendingUnlockHtml 并再次进到这里 ——
        // 服务端刚回帖完，页面状态可能还没更新，于是又回一条。
        // 这就是「有概率对隐藏帖多次自动回复，且概率很大」的主因。
        if (unlockAttemptedThisVisit) {
            com.solosu.mtforum.util.UnlockLog.skip(
                    detail == null ? null : detail.getTid(), "本次进入已尝试过，跳过");
            this.pendingUnlockHtml = null;
            return;
        }
        if (detail == null || !detail.isHasHiddenContent()) return;
        final String pageHtml = this.pendingUnlockHtml;
        this.pendingUnlockHtml = null;
        if (TextUtils.isEmpty(pageHtml)) return;
        // 注意: 本文件 import 了 model.Thread, 必须写全限定名 java.lang.Thread
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                unlockAttemptedThisVisit = true;
                boolean ok = com.solosu.mtforum.ai.AutoReplyEngine
                        .tryUnlockOnOpen(ThreadDetailActivity.this, detail, pageHtml);
                if (ok) {
                    runOnUiThread(new Runnable() {
                        @Override // java.lang.Runnable
                        public final void run() {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(ThreadDetailActivity.this,
                                    "已自动回帖解锁，正在刷新…", 0).show();
                            refreshPostDetail();
                        }
                    });
                }
            }
        }, "unlock-on-open").start();
    }

    private void lambda$loadPostDetail$18(Exception e) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        this.binding.progressBar.setVisibility(8);
        this.binding.swipeRefresh.setEnabled(true);
        String message = TextUtils.isEmpty(e.getMessage()) ? "网络异常，请下拉刷新重试" : e.getMessage();
        Toast.makeText(this, "加载失败: " + message, 0).show();
    }

    private String getReplyOrderUrl() {
        return ForumParser.getThreadDetailUrl(this.tid, getReplyOrder());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshPostDetail() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$refreshPostDetail$22();
            }
        }).start();
    }

    private void lambda$refreshPostDetail$22() {
        try {
            String detailUrl = ForumParser.getThreadDetailUrl(this.tid, getReplyOrder()) + "&_refresh=" + System.currentTimeMillis();
            this.httpClient.syncFromCookieManager();
            String html = this.httpClient.get(detailUrl);
            if (TextUtils.isEmpty(html)) {
                throw new IllegalStateException("服务器返回空页面，请检查网络后重试");
            }
            final PostDetail detail = ForumParser.parseThreadDetail(html);
            if (detail == null) {
                throw new IllegalStateException("帖子内容解析失败");
            }
            // build80: 同进帖链，移出首屏
            // build61: 下拉刷新链同样只记录页面,渲染后异步解锁(同加载链)
            this.pendingUnlockHtml = html;
            if (!TextUtils.isEmpty(detail.getAuthorUid())) {
                detail.setFollowed(FollowStateManager.resolve(this, detail.getAuthorUid(), detail.isFollowed()));
            }
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$refreshPostDetail$20(detail);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$refreshPostDetail$21(e);
                }
            });
        }
    }

    private void lambda$refreshPostDetail$20(PostDetail postDetail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        this.binding.swipeRefresh.setRefreshing(false);
        // build80: 同进帖链，非首屏数据渲染后异步补
        enrichAfterRender(postDetail);
        applyServerActionState(postDetail);
        // build61: 渲染完成后,后台线程执行解锁检测(同加载链)
        runUnlockInBackground(postDetail);
        this.likeCount = Math.max(0, postDetail.getLikeCount());
        updateLikeIcon();
        updateFavoriteIcon();
        this.favoriteCount = Math.max(0, postDetail.getFavoriteCount());
        updateCountBadge(this.binding.tvFavoriteBadge, this.favoriteCount);
        bindData(postDetail, false);
    }

    private void lambda$refreshPostDetail$21(Exception e) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        this.binding.swipeRefresh.setRefreshing(false);
        String message = TextUtils.isEmpty(e.getMessage()) ? "网络异常，请稍后重试" : e.getMessage();
        Toast.makeText(this, "刷新失败: " + message, 0).show();
    }

    private void bindData(final PostDetail postDetail, boolean z) {
        // build66 崩溃修复：loadPostDetail 是异步的，用户在加载完成前退出时
        // Activity 已销毁，这里的 Glide.with(this) 会抛
        // IllegalArgumentException: You cannot start a load for a destroyed activity
        if (isFinishing() || isDestroyed()) return;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (postDetail == null) {
            this.binding.progressBar.setVisibility(8);
            this.binding.swipeRefresh.setEnabled(true);
            Toast.makeText(this, "帖子内容为空，请下拉刷新重试", 0).show();
            return;
        }
        // build87: 记住这次渲染用的是哪个账号，供 onResume 检测切号
        this.lastRenderUid = likeFavScope();
        // build91: 记住这次渲染用的是哪种图片排布，供 onResume 检测开关变动
        this.lastImagesInline = com.solosu.mtforum.ui.UiSettings.isImagesInline(this);
        this.lastWebRender = com.solosu.mtforum.ui.UiSettings.isWebRender(this);
        this.postDetail = postDetail;
        this.binding.progressBar.setVisibility(8);
        this.binding.swipeRefresh.setEnabled(true);
        if (!TextUtils.isEmpty(postDetail.getForumName())) {
            this.binding.tvForumName.setVisibility(0);
            this.binding.tvForumName.setText(postDetail.getForumName());
        } else {
            this.binding.tvForumName.setVisibility(8);
        }
        this.binding.tvThreadTitle.setText(!TextUtils.isEmpty(postDetail.getTitle()) ? postDetail.getTitle() : "");
        // build75: 长按标题 -> 举报帖子
        this.binding.tvThreadTitle.setLongClickable(true);
        this.binding.tvThreadTitle.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                reportPost(null);
                return true;
            }
        });
        String avatarUrl = postDetail.getAvatarUrl();
        if (!TextUtils.isEmpty(avatarUrl)) {
            Glide.with((FragmentActivity) this).load(com.solosu.mtforum.util.ForumImageLoader.model(avatarUrl)).transform(new CircleCrop()).placeholder(R.drawable.ic_account).error(R.drawable.ic_account).into(this.binding.ivAuthorAvatar);
        } else {
            this.binding.ivAuthorAvatar.setImageResource(R.drawable.ic_account);
        }
        this.binding.tvAuthorName.setText(!TextUtils.isEmpty(postDetail.getAuthor()) ? postDetail.getAuthor() : "匿名");
        final String authorUid = postDetail.getAuthorUid();
        if (!TextUtils.isEmpty(authorUid)) {
            this.binding.ivAuthorAvatar.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ThreadDetailActivity.this.lambda$bindData$23(authorUid, postDetail, view);
                }
            });
            this.binding.tvAuthorName.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ThreadDetailActivity.this.lambda$bindData$24(authorUid, postDetail, view);
                }
            });
        }
        if (!TextUtils.isEmpty(postDetail.getAuthorLevel())) {
            this.binding.tvAuthorLevel.setVisibility(0);
            this.binding.tvAuthorLevel.setText(postDetail.getAuthorLevel());
        } else {
            this.binding.tvAuthorLevel.setVisibility(8);
        }
        this.binding.tvPublishTime.setText(!TextUtils.isEmpty(postDetail.getPublishTime()) ? postDetail.getPublishTime() : "");
        this.binding.tvLocation.setVisibility(8);
        // 收藏数回填缓存:详情页拿到数字后存进 FavoritesCache,列表卡片第四格就能显示
        if (postDetail.getFavoriteCount() > 0) {
            com.solosu.mtforum.session.FavoritesCache.put(this, postDetail.getTid(), postDetail.getFavoriteCount());
        }
        if (this.httpClient.isLoggedIn() && !TextUtils.isEmpty(postDetail.getAuthor())) {
            this.binding.btnFollow.setVisibility(0);
            if (isOwnThread(postDetail)) {
                // build73: 自己的帖子 -> 右上角是「编辑」(不是关注)
                this.binding.btnFollow.setText("编辑");
                this.binding.btnFollow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        openEditThread();
                    }
                });
            } else {
                this.binding.btnFollow.setText(getString(postDetail.isFollowed() ? R.string.action_followed : R.string.action_follow));
                this.binding.btnFollow.setOnClickListener(new View.OnClickListener() {
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        ThreadDetailActivity.this.lambda$bindData$25(view);
                    }
                });
            }
        } else {
            this.binding.btnFollow.setVisibility(8);
        }
        String contentHtml = postDetail.getContentHtml();
        String[] strArrReplaceHiddenQuoteWithPlaceholder = {null, ""};
        if (!TextUtils.isEmpty(contentHtml)) {
            String strConvertBBCodeToHtml = BBCodeUtil.convertBBCodeToHtml(contentHtml);
            this.binding.tvContent.setVisibility(0);
            ArrayList arrayList = new ArrayList();
            String[] strArrSplitEditFooter = splitEditFooter(strConvertBBCodeToHtml);
            // build68: 图片位置可配。原位 = 不抽离，交给 ImageGetter 图文混排；
            // 底部汇总 = 抽出来放帖子底部的横滑图廊（旧行为）。
            boolean imagesInline = com.solosu.mtforum.ui.UiSettings.isImagesInline(this);
            // build73: 原位显示时把缩略图 src 换成原图地址，否则放大了也是糊的
            if (imagesInline) {
                strArrSplitEditFooter[0] = upgradeThumbnailsToFull(strArrSplitEditFooter[0]);
                // build90: 站点对游客在详情页根本不下发附件 <img>（实测 tid=173937
                // 详情页 67943 字符里 comiis_loadimages=" 0 次、aid= 1 次且是 JS 里的
                // 懒加载选择器字符串），正文里一张图都没有。这时把列表页拿到的真实
                // CDN 图补写进正文 HTML，让它们跟着正文排版走，而不是退化成帖子底部
                // 那个横滑图廊 —— 用户原话「图片不在正文排版处正常显示，而是全部
                // 解析到正文底部」。
                strArrSplitEditFooter[0] = injectFallbackImagesInline(strArrSplitEditFooter[0]);
            }
            // build92: 是否走 WebView 原帖渲染。必须在下面抽代码块之前就算出来 ——
            // 原帖渲染时代码块本来就在原始 HTML 里，再抽出来单独渲染一份就是重复。
            boolean webRender = com.solosu.mtforum.ui.UiSettings.isWebRender(this)
                    && !TextUtils.isEmpty(strArrSplitEditFooter[0])
                    && this.binding.webContent != null;
            // build92: 原帖渲染下不管「原位开关」怎么设，图都必须按作者插入的位置排，
            // 所以缩略图升级和列表页兜底图无条件做（injectFallbackImagesInline 幂等，
            // 下面 renderContentInWeb 里再调一次不会重复注入）。
            if (webRender) {
                strArrSplitEditFooter[0] = upgradeThumbnailsToFull(strArrSplitEditFooter[0]);
                strArrSplitEditFooter[0] = injectFallbackImagesInline(strArrSplitEditFooter[0]);
            }
            String strExtractAndSeparateImages = imagesInline
                    ? strArrSplitEditFooter[0]
                    : extractAndSeparateImages(strArrSplitEditFooter[0], arrayList);
            List<String> imageUrls = postDetail.getImageUrls();
            // build69: 原位模式下 arrayList 只用来喂「全屏翻页」的图组，
            // 不能再喂底部图廊 —— 否则图片在正文和底部各出现一次（你看到的就是这个）。
            List<String> galleryUrls = new ArrayList<>();
            if (imageUrls != null && !imageUrls.isEmpty()) {
                for (String str : imageUrls) {
                    if (!arrayList.contains(str)) arrayList.add(str);
                }
            }
            if (!imagesInline) galleryUrls.addAll(arrayList);
            if (!TextUtils.isEmpty(strArrSplitEditFooter[1])) {
                this.binding.layoutEditFooter.setVisibility(0);
                this.binding.tvEditFooter.setText(strArrSplitEditFooter[1]);
                this.binding.viewContentTopDivider.setVisibility(8);
                adjustEditFooterDividerWidth();
                LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) this.binding.frameContent.getLayoutParams();
                layoutParams.topMargin = 0;
                this.binding.frameContent.setLayoutParams(layoutParams);
            } else {
                this.binding.layoutEditFooter.setVisibility(8);
                this.binding.viewContentTopDivider.setVisibility(0);
                LinearLayout.LayoutParams layoutParams2 = (LinearLayout.LayoutParams) this.binding.frameContent.getLayoutParams();
                layoutParams2.topMargin = dpToPx(12);
                this.binding.frameContent.setLayoutParams(layoutParams2);
            }
            // 收集当前帖全部图片供全屏翻页
            // build87: 兜底图也要进这个列表，否则点图廊里第 2 张时
            // openImagePreview 只把单张 url 传过去，左右翻页翻不动。
            this.currentImageList = new ArrayList<>(arrayList);
            if (this.currentImageList.isEmpty() && !this.listImageFallback.isEmpty()) {
                this.currentImageList.addAll(this.listImageFallback);
            }
            // build67: 隐藏内容位置可配。就地展开时不再把内容挪到帖子底部，
            // 正文里也就不会留那个碍眼的占位胶囊。
            boolean hiddenInline = com.solosu.mtforum.ui.UiSettings.isHiddenContentInline(this);
            strArrReplaceHiddenQuoteWithPlaceholder = hiddenInline
                    ? new String[]{strExtractAndSeparateImages, ""}
                    : replaceHiddenQuoteWithPlaceholder(strExtractAndSeparateImages);
            // build65: 留一份带标签的原文，复制 BBCode 时用
            this.currentContentHtmlForCopy = strConvertBBCodeToHtml;
            boolean z2 = true;
            // build64: 把代码块从主楼正文里摘出来，单独渲染成可折叠 + 可复制的卡片
            String bodyHtmlForRender = strArrReplaceHiddenQuoteWithPlaceholder[0];
            com.solosu.mtforum.util.BBCodeUtil.Extracted extractedMain =
                    com.solosu.mtforum.util.BBCodeUtil.extractCodeBlocks(bodyHtmlForRender);
            // build92: 原帖渲染时代码块已经在 WebView 里按站点样式排好了，
            // 再抽出来渲染成卡片就是同一段代码出现两次。
            if (webRender) {
                renderMainCodeBlocks(null);
            } else {
                renderMainCodeBlocks(extractedMain);
            }
            if (extractedMain.hasBlocks()) bodyHtmlForRender = extractedMain.html;
            // build92: 原帖渲染优先。
            //
            // 以前无论如何都要把正文塞进 TextView 重新排版一遍。站点模板里
            // 「插进正文的图」的位置信息只存在于原始 HTML 中，一旦解析成
            // 纯文本 + 图片 URL 列表就丢了 —— 克米 mobile 模板把这类图放在
            // .comiis_messages 里，位置就是作者插入的位置，我们的解析器却只
            // 把 URL 收集起来另放。用户原话：「应该直接套用网页原帖内容不要解析」。
            //
            // 所以默认直接用 WebView 按原样渲染站点下发的 HTML，图就落在原处。
            if (webRender) {
                renderContentInWeb(strArrSplitEditFooter[0]);
            } else {
                if (this.binding.webContent != null) {
                    this.binding.webContent.setVisibility(View.GONE);
                }
                this.binding.tvContent.setVisibility(View.VISIBLE);
                this.binding.tvContent.setText(safeFromHtml(bodyHtmlForRender, createInlineImageGetter(this.binding.tvContent), com.solosu.mtforum.util.BBCodeUtil.createTagHandler(this)));
            }
            // build69: 给正文里的内联图片挂点击，之前原位显示的图根本点不开
            boolean unlocked = postDetail.isHasHiddenContent() && this.httpClient.isLoggedIn()
                    && !TextUtils.isEmpty(postDetail.getHiddenContentHtml())
                    && !com.solosu.mtforum.ai.AutoReplyEngine.isLockedHidden(postDetail.getHiddenContentHtml());
            String hiddenNotice = unlocked ? "隐藏内容(已解锁)"
                    : strArrReplaceHiddenQuoteWithPlaceholder[1];
            if (!webRender) {
                applyHiddenNoticeHighlight(this.binding.tvContent.getText(), hiddenNotice);
                setupClickableLinks(this.binding.tvContent);
                // build70: 必须放在 setupClickableLinks 之后 —— 它会把 textIsSelectable 设为 true，
                // 选择模式会吞掉 ClickableSpan 的点击，所以这里改用触摸命中测试，不依赖 MovementMethod
                attachInlineImageClicks(this.binding.tvContent);
            }
            // build87: 帖子页一张图都没解析出来（典型：站点对游客把附件换成
            // 「您需要登录才可以查看」）时，用列表页带过来的真实 CDN 缩略图兜底。
            // 列表页能显示、进帖却什么都没有，是最扎眼的一种「图片不显示」。
            // build90: 但原位模式下兜底图已经补写进正文了，不能再进底部图廊，
            // 否则同一张图在正文和帖子底部各出现一次。
            if (webRender) {
                // 图已经在 WebView 里按原位置渲染了，底部图廊必须让位，否则一图两显。
                // build107: 但先把候选留住 —— 万一正文一张都没加载出来（需要登录态的
                // 附件被站点换成提示页），onReady 时会用它兜底，而不是留一屏空白。
                this.pendingGalleryUrls.clear();
                this.pendingGalleryUrls.addAll(galleryUrls);
                this.fallbackGalleryShown = galleryUrls.isEmpty();
                renderImageGallery(null);
            } else {
                if (!imagesInline && galleryUrls.isEmpty() && !this.listImageFallback.isEmpty()) {
                    galleryUrls.addAll(this.listImageFallback);
                }
                renderImageGallery(galleryUrls);
            }
        } else {
            this.binding.tvContent.setVisibility(0);
            this.binding.tvContent.setTextSize(14.0f);
            this.binding.tvContent.setTextColor(getColor(R.color.text_hint));
            this.binding.tvContent.setGravity(17);
            // build87: 正文为空（站点对游客下登录墙 / 解析失败）时，列表页带过来的
            // 真实 CDN 图照样上图廊。之前这里无条件隐藏 cardImageGallery，
            // 兜底图根本走不到 —— 这就是「进帖还是不显示」的最后一环。
            if (!this.listImageFallback.isEmpty()) {
                this.binding.tvContent.setText("本帖子资源需登录后查看，以下为列表页图片");
                renderImageGallery(this.listImageFallback);
            } else {
                this.binding.cardImageGallery.setVisibility(8);
                this.binding.tvContent.setText("[内容加载中，请刷新重试]");
            }
        }
        boolean z3 = true;
        final long tRenderStart = System.currentTimeMillis();
        // build72: 量「解析完 → 界面真正画出来」这段主线程耗时。
        // 之前 PerfLog 里的「解析」只统计 ForumParser，渲染(BBCode转换 + Html.fromHtml
        // + 图片 getter + 代码块抽取)全在主线程，才是真正的体感来源。
        this.binding.tvContent.post(() ->
                com.solosu.mtforum.util.PerfLog.recordRender(
                        System.currentTimeMillis() - tRenderStart));
        // build72: 附件列表
        renderAttachments(postDetail.getContentHtml());
        // build98: 帖子标签（站点 div.comiis_tags）—— 照原样显示成一排可点胶囊
        renderThreadTags(postDetail);

        // build71: 记浏览历史
        com.solosu.mtforum.session.HistoryStore.record(this, this.tid,
                postDetail.getTitle(), postDetail.getAuthor());
        boolean hasHidden = postDetail.isHasHiddenContent();
        // build68: 记下来，回到列表时给这个帖子打「隐藏」标
        com.solosu.mtforum.session.PostCountsCache.markHasHidden(this.tid, hasHidden);
        boolean hiddenUnlocked = hasHidden && this.httpClient.isLoggedIn()
                && !TextUtils.isEmpty(postDetail.getHiddenContentHtml())
                && !com.solosu.mtforum.ai.AutoReplyEngine.isLockedHidden(postDetail.getHiddenContentHtml());
        if (hiddenUnlocked && com.solosu.mtforum.ui.UiSettings.isHiddenContentInline(this)) {
            // build67: 就地展开模式 —— 内容已经在正文原处了，底部整块不再重复显示
            this.binding.layoutHiddenContent.setVisibility(8);
        } else if (hiddenUnlocked) {
            // 已登录且可获取隐藏内容:正文中的胶囊只显示短提示,下方直接展示完整内容
            this.binding.layoutHiddenContent.setVisibility(0);
            this.binding.tvHiddenContentHint.setVisibility(8);
            this.binding.btnViewHidden.setVisibility(8);
            renderHiddenContent(postDetail.getHiddenContentHtml());
        } else if (hasHidden) {
            // 未登录或暂无内容:显示按钮引导查看(点击会提示登录或重新加载)
            this.binding.layoutHiddenContent.setVisibility(0);
            this.binding.tvHiddenContentHint.setVisibility(0);
            this.binding.btnViewHidden.setVisibility(0);
            this.binding.tvHiddenContent.setVisibility(8);
            // 自动解锁：进入帖子发现是「回复可见」时，后台直接回复解锁
            // build77 修复：这里原本调 maybeAutoUnlock()，它走的是另一个方法
            // AutoReplyEngine.unlockSingleThread()，<b>完全绕过</b>了 v3.5 加的持久化认领，
            // 防重只靠一个 per-Activity 的内存 Set —— 重开帖子就是新实例，于是又回一遍。
            // 现在统一只保留 runUnlockInBackground() 这一条链路（有持久化认领 + 6 小时冷却）。
        } else {
            this.binding.layoutHiddenContent.setVisibility(8);
        }
        // ===== build86: 附件被登录墙挡住时给出明确解释 =====
        // 站点对游客把本帖附件换成「本帖子中包含更多精彩资源 / 您需要登录才可以查看」，
        // 而列表页对游客是正常展示缩略图的。不解释的话，用户从列表点进来只看到
        // 「进帖一张图都没有」，还以为是解析识别错了 —— 其实是站点没把图发给游客。
        // 只在真的没有正文图时提示，避免已登录能看到图时还弹这句。
        if (postDetail.isAttachmentLoginWall()) {
            java.util.List<String> bodyImgs = postDetail.getImageUrls();
            if (bodyImgs == null || bodyImgs.isEmpty()) {
                this.binding.layoutHiddenContent.setVisibility(0);
                this.binding.tvHiddenContentHint.setVisibility(0);
                this.binding.btnViewHidden.setVisibility(0);
                this.binding.tvHiddenContent.setVisibility(8);
                this.binding.tvHiddenContentHint.setText(
                        "本帖配图/附件需要登录后查看。站点对游客隐藏附件（列表页那张缩略图"
                                + "是服务端另外生成的），登录后进来即可正常显示。");
            }
        }
        if (postDetail.isLikedStateKnown()) {
            this.isLiked = postDetail.isLiked();
            saveLikedState(this.isLiked);
        } else {
            this.isLiked = restoreLikedState();
        }
        this.likeCount = Math.max(0, postDetail.getLikeCount());
        updateLikeIcon();
        updateCountBadge(this.binding.tvCommentsBadge, postDetail.getReplyCount());
        updateCountBadge(this.binding.tvLikeBadge, this.likeCount);
        // === 点赞人头像行 ===
        bindLikeUsers(postDetail);
        if (postDetail.isFavoritedStateKnown()) {
            this.isFavorited = postDetail.isFavorited();
            saveFavoritedState(this.isFavorited);
        } else {
            this.isFavorited = restoreFavoritedState();
        }
        updateFavoriteIcon();
        this.favoriteCount = Math.max(0, postDetail.getFavoriteCount());
        updateCountBadge(this.binding.tvFavoriteBadge, this.favoriteCount);
        List<ReplyItem> replies = postDetail.getReplies();
        if (replies == null) {
            replies = new ArrayList();
        }
        // 保留解析出的原始列表；显示层统一应用用户黑名单、回复词条及灌水过滤，
        // 这样加载更多和切换「只看楼主」时也会使用同一套规则。
        this.allReplies = new ArrayList<>(replies);
        this.displayedReplies = new ArrayList<>();
        updateReplyFilterAndOrder();
        int replyCount = postDetail.getReplyCount();
        if (replyCount > 0) {
            this.binding.tvReplyCount.setVisibility(0);
            this.binding.tvReplyCount.setText("(" + replyCount + ")");
        } else {
            this.binding.tvReplyCount.setVisibility(8);
        }
        if (replies == null || replies.isEmpty() || postDetail.getCurrentPage() < postDetail.getTotalPages() || !TextUtils.isEmpty(postDetail.getNextPageUrl())) {
        }
        if (replyCount > (replies != null ? replies.size() : 0)) {
        }
        this.binding.btnLoadMore.setVisibility(8);
        this.binding.layoutReply.setVisibility(this.httpClient.isLoggedIn() ? 0 : 8);
        this.binding.layoutThreadActions.setVisibility(this.httpClient.isLoggedIn() ? 0 : 8);
        int rewardCount = postDetail.getRewardCount();
        int goodReviewCount = postDetail.getGoodReviewCount();
        int rewardCoins = postDetail.getRewardCoins();
        List<String> rewardUserAvatars = postDetail.getRewardUserAvatars();
        List<String> goodReviewUserAvatars = postDetail.getGoodReviewUserAvatars();
        if (rewardCount <= 0 && goodReviewCount <= 0 && ((rewardUserAvatars == null || rewardUserAvatars.isEmpty()) && (goodReviewUserAvatars == null || goodReviewUserAvatars.isEmpty()))) {
            z3 = false;
        }
        if (this.httpClient.isLoggedIn() && z3) {
            this.binding.layoutRewardReviewStats.setVisibility(0);
            this.binding.tvRewardCount.setText(String.valueOf(rewardCount));
            this.binding.tvRewardCoins.setText("共计 " + rewardCoins + " 金币");
            this.binding.tvGoodReviewCount.setText(String.valueOf(goodReviewCount));
            bindAvatarStrip(this.binding.llRewardAvatars, rewardUserAvatars);
            bindAvatarStrip(this.binding.llGoodReviewAvatars, goodReviewUserAvatars);
        } else {
            this.binding.layoutRewardReviewStats.setVisibility(8);
        }
        if (z && TextUtils.isEmpty(this.targetPid)) {
            this.binding.nestedScroll.scrollTo(0, 0);
        }
        if (!TextUtils.isEmpty(this.targetPid)) {
            beginTargetPidJump();
        }
    }

    /** 通知携带 pid 时，先定位当前页；跨页时通过 Discuz findpost 重定向解析目标页。 */
    private void beginTargetPidJump() {
        if (TextUtils.isEmpty(targetPid) || isFinishing() || isDestroyed()) return;
        int visiblePosition = findReplyPositionByPid(displayedReplies, targetPid);
        if (visiblePosition >= 0) {
            replyAdapter.setHighlightedPid(targetPid);
            scrollToReplyPosition(visiblePosition);
            return;
        }
        if (findReplyPositionByPid(allReplies, targetPid) >= 0) {
            Toast.makeText(this, "目标回复被当前筛选条件隐藏", Toast.LENGTH_SHORT).show();
            return;
        }
        if (targetPidJumpInProgress || postDetail == null) return;

        targetPidJumpInProgress = true;
        final String requestTid = tid;
        final String requestPid = targetPid;
        final String replyOrder = getReplyOrder();
        final int knownTotalPages = Math.max(1, postDetail.getTotalPages());
        new java.lang.Thread(() -> {
            PostDetail foundDetail = null;
            int foundPage = -1;
            try {
                String ascendingPage = httpClient.resolveFindPostPage(requestTid, requestPid);
                int asc = -1;
                try { if (!TextUtils.isEmpty(ascendingPage)) asc = Integer.parseInt(ascendingPage); }
                catch (NumberFormatException ignored) { }

                java.util.LinkedHashSet<Integer> candidates = new java.util.LinkedHashSet<>();
                if (asc >= 1 && asc <= knownTotalPages) {
                    int mapped = "desc".equalsIgnoreCase(replyOrder)
                            ? knownTotalPages - asc + 1 : asc;
                    if (mapped >= 1 && mapped <= knownTotalPages) candidates.add(mapped);
                    if (mapped - 1 >= 1) candidates.add(mapped - 1);
                    if (mapped + 1 <= knownTotalPages) candidates.add(mapped + 1);
                } else {
                    // findpost 服务不可用时，先尝试两个边界页，避免无界扫描整个长帖。
                    candidates.add(1);
                    candidates.add(knownTotalPages);
                    if (knownTotalPages > 2) {
                        candidates.add(2);
                        candidates.add(knownTotalPages - 1);
                    }
                }

                for (Integer page : candidates) {
                    if (page == null || page < 1 || page > knownTotalPages) continue;
                    String pageUrl = ForumParser.getThreadDetailUrl(requestTid, page, replyOrder);
                    String html = httpClient.get(pageUrl);
                    PostDetail candidate = ForumParser.parseThreadDetail(html);
                    if (candidate != null
                            && findReplyPositionByPid(candidate.getReplies(), requestPid) >= 0) {
                        foundDetail = candidate;
                        foundPage = page;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }

            final PostDetail result = foundDetail;
            final int resultPage = foundPage;
            runOnUiThread(() -> {
                targetPidJumpInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                if (result == null || resultPage < 1) {
                    Toast.makeText(this, "未能定位到该回复楼层", Toast.LENGTH_SHORT).show();
                    return;
                }
                applyTargetReplyPage(result, resultPage, knownTotalPages);
            });
        }, "reply-pid-jump").start();
    }

    private void applyTargetReplyPage(PostDetail pageDetail, int page, int totalPages) {
        if (pageDetail == null || isFinishing() || isDestroyed()) return;
        List<ReplyItem> replies = pageDetail.getReplies();
        if (replies == null) replies = new ArrayList<>();
        if (findReplyPositionByPid(replies, targetPid) < 0) return;

        this.allReplies = new ArrayList<>(replies);
        if (this.postDetail != null) {
            this.postDetail.setReplies(new ArrayList<>(replies));
            this.postDetail.setCurrentPage(page);
            this.postDetail.setTotalPages(Math.max(totalPages, pageDetail.getTotalPages()));
        }
        updateReplyFilterAndOrder();
        this.replyAdapter.setHighlightedPid(targetPid);
        int total = this.postDetail != null ? this.postDetail.getTotalPages() : totalPages;
        this.binding.btnLoadMore.setVisibility(page < total ? View.VISIBLE : View.GONE);
        this.binding.btnLoadMore.setEnabled(true);
        this.binding.btnLoadMore.setText(R.string.load_more_replies);

        int position = findReplyPositionByPid(displayedReplies, targetPid);
        if (position >= 0) {
            scrollToReplyPosition(position);
        } else {
            Toast.makeText(this, "目标回复被当前筛选条件隐藏", Toast.LENGTH_SHORT).show();
        }
    }

    private int findReplyPositionByPid(List<ReplyItem> replies, String pid) {
        if (replies == null || TextUtils.isEmpty(pid)) return -1;
        for (int i = 0; i < replies.size(); i++) {
            ReplyItem reply = replies.get(i);
            if (reply != null && pid.equals(reply.getPid())) return i;
        }
        return -1;
    }

    private void scrollToReplyPosition(int position) {
        if (position < 0 || binding == null) return;
        binding.recyclerReplies.post(() -> {
            if (binding == null || isFinishing() || isDestroyed()) return;
            androidx.recyclerview.widget.RecyclerView.LayoutManager manager =
                    binding.recyclerReplies.getLayoutManager();
            if (!(manager instanceof LinearLayoutManager)) return;
            LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
            layoutManager.scrollToPositionWithOffset(position, 0);
            binding.recyclerReplies.post(() -> {
                if (binding == null || isFinishing() || isDestroyed()) return;
                View replyView = layoutManager.findViewByPosition(position);
                int y = binding.recyclerReplies.getTop();
                if (replyView != null) y += replyView.getTop();
                binding.nestedScroll.smoothScrollTo(0, Math.max(0, y));
            });
        });
    }

    private void lambda$bindData$23(String authorUid, PostDetail detail, View v) {
        Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
        intent.putExtra(ChatActivity.EXTRA_UID, authorUid);
        intent.putExtra("username", detail.getAuthor());
        startActivity(intent);
    }

    private void lambda$bindData$24(String authorUid, PostDetail detail, View v) {
        Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
        intent.putExtra(ChatActivity.EXTRA_UID, authorUid);
        intent.putExtra("username", detail.getAuthor());
        startActivity(intent);
    }

    private void lambda$bindData$25(View v) {
        toggleFollow();
    }

    private void lambda$bindData$26(String imgUrl, View v) {
        openImagePreview(imgUrl);
    }

    private void lambda$bindData$28(View v) {
        boolean isCollapsed = this.binding.hsvImageGallery.getVisibility() == 8;
        if (isCollapsed) {
            this.binding.hsvImageGallery.setVisibility(0);
            this.binding.hsvImageGallery.setAlpha(0.0f);
            this.binding.hsvImageGallery.animate().alpha(1.0f).setDuration(300L).start();
            this.binding.btnCollapseImages.animate().rotation(90.0f).setDuration(200L).start();
            return;
        }
        this.binding.hsvImageGallery.animate().alpha(0.0f).setDuration(200L).withEndAction(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$bindData$27();
            }
        }).start();
        this.binding.btnCollapseImages.animate().rotation(-90.0f).setDuration(200L).start();
    }

    private void lambda$bindData$27() {
        this.binding.hsvImageGallery.setVisibility(8);
    }

    private String getReplyOrder() {
        return this.repliesDescending ? "desc" : "asc";
    }

    private void updateReplyFilterAndOrder() {
        List<ReplyItem> source = this.allReplies == null ? new ArrayList<>() : this.allReplies;
        List<ReplyItem> result = new ArrayList<>();
        java.util.Set<String> blockedAuthors =
                com.solosu.mtforum.session.BlacklistManager.uidSet(this);
        List<String> keywordTerms =
                com.solosu.mtforum.session.ReplyFilterManager.getKeywordTerms(this);
        boolean keywordFilterEnabled =
                com.solosu.mtforum.session.ReplyFilterManager.isKeywordFilterEnabled(this);
        List<String> spamTerms =
                com.solosu.mtforum.session.ReplyFilterManager.getSpamTerms(this);
        boolean spamFilterEnabled =
                com.solosu.mtforum.session.ReplyFilterManager.isHideSpamEnabled(this);
        String threadTitle = this.postDetail != null ? this.postDetail.getTitle() : "";
        String opUid = this.postDetail != null ? this.postDetail.getAuthorUid() : "";
        String opName = this.postDetail != null ? this.postDetail.getAuthor() : "";
        int filteredByRules = 0;

        for (ReplyItem item : source) {
            if (item == null) continue;

            String authorUid = item.getAuthorUid();
            if (!TextUtils.isEmpty(authorUid) && blockedAuthors.contains(authorUid)) {
                filteredByRules++;
                continue;
            }

            String replyText = item.getContentText();
            if (TextUtils.isEmpty(replyText)) replyText = item.getContentHtml();
            if (keywordFilterEnabled
                    && com.solosu.mtforum.util.ReplyContentFilter.matchesKeyword(replyText, keywordTerms)) {
                filteredByRules++;
                continue;
            }
            if (spamFilterEnabled && (
                    com.solosu.mtforum.util.ReplyContentFilter.matchesExactPhrase(replyText, spamTerms)
                    || com.solosu.mtforum.util.ReplyContentFilter.isSpamReply(replyText, threadTitle))) {
                filteredByRules++;
                continue;
            }

            if (this.onlyOpReplies) {
                boolean isOp = item.isOP();
                if (!isOp && !TextUtils.isEmpty(opUid)) {
                    isOp = opUid.equals(authorUid);
                }
                if (!isOp && !TextUtils.isEmpty(opName)) {
                    isOp = opName.equals(item.getAuthor());
                }
                if (!isOp) continue;
            }
            result.add(item);
        }

        this.displayedReplies = result;
        this.binding.tvReplyFilterCount.setVisibility(filteredByRules > 0 ? View.VISIBLE : View.GONE);
        if (filteredByRules > 0) {
            this.binding.tvReplyFilterCount.setText("过滤 " + filteredByRules);
            this.binding.tvReplyFilterCount.setContentDescription(
                    "当前已加载回复中按内容或作者规则过滤 " + filteredByRules + " 条");
        }
        this.replyAdapter.updateData(result);
        this.binding.btnOnlyOp.setText(this.onlyOpReplies ? R.string.reply_all_users : R.string.reply_only_op);
        this.binding.btnOnlyOp.setTextColor(getColor(this.onlyOpReplies ? R.color.primary : R.color.text_secondary));
        this.binding.btnReplyOrder.setText(this.repliesDescending ? R.string.reply_order_desc : R.string.reply_order_asc);
        this.binding.btnReplyOrder.setTextColor(getColor(this.repliesDescending ? R.color.primary : R.color.text_secondary));
        if (result.isEmpty()) {
            this.binding.recyclerReplies.setVisibility(8);
            if (source.isEmpty()) {
                this.binding.tvEmptyReplies.setText(R.string.no_replies);
            } else {
                this.binding.tvEmptyReplies.setText("没有符合当前筛选条件的回复");
            }
            this.binding.tvEmptyReplies.setVisibility(0);
        } else {
            this.binding.recyclerReplies.setVisibility(0);
            this.binding.tvEmptyReplies.setVisibility(8);
        }
    }

    /**
     * build80: 把非首屏的数据请求挪到「首屏渲染完成之后」。
     *
     * <p>原链路是 {@code 解析 → enrichGoodReviewAvatars → refreshServerActionState
     * → runOnUiThread(渲染)}，两个 enrich 都是同步的整页请求，
     * 于是进帖时要串行发 3 个页面请求才看到第一屏内容。
     *
     * <p>参照项目（qcxs/mtbbs_app）的 {@code _loadInitial} 只发 1 个请求就出首屏，
     * 差额就在这里。挪到渲染后异步补，用户看到内容的时间缩短约 2/3。
     *
     * <p>注意用 {@link java.lang.Thread} 全限定名：本文件 import 了
     * {@code model.Thread}，裸 {@code Thread} 会解析到 model 类（build79 真机构建踩过）。
     */
    private void enrichAfterRender(final PostDetail detail) {
        if (detail == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            new java.lang.Thread(() -> {
                if (isFinishing() || isDestroyed()) return;
                enrichGoodReviewAvatars(detail);
                refreshServerActionState(detail);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    bindRewardReviewStats(detail);
                    applyServerActionState(detail);
                });
            }, "thread-enrich").start();
        } else {
            enrichGoodReviewAvatars(detail);
            refreshServerActionState(detail);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                bindRewardReviewStats(detail);
                applyServerActionState(detail);
            });
        }
    }

    private void enrichGoodReviewAvatars(PostDetail detail) {
        if (detail == null) {
            return;
        }
        try {
            if (detail.getGoodReviewCount() > 0) {
                String desktopHtml = this.httpClient.getDesktop(ForumParser.getThreadDesktopDetailUrl(this.tid));
                List<String> avatars = ForumParser.parseGoodReviewAvatarUrls(desktopHtml);
                if (avatars != null && !avatars.isEmpty()) {
                    detail.setGoodReviewUserAvatars(avatars);
                }
            }
            String desktopHtml2 = detail.getRewardDetailUrl();
            if (!TextUtils.isEmpty(desktopHtml2)) {
                String rewardHtml = this.httpClient.get(detail.getRewardDetailUrl());
                detail.setRewardCoins(ForumParser.parseRewardCoins(rewardHtml));
            }
        } catch (Exception e) {
        }
    }

    /**
     * build80: 只重刷「打赏/好评」那一小块，不重跑整个 {@link #bindData}。
     *
     * <p>{@link #enrichAfterRender} 异步补数据回来后，需要把新拿到的
     * 头像和金币数显示出来。直接调 {@code bindData(detail, false)} 会把
     * 整个帖子列表重新绑定一遍，包括 {@code nestedScroll} 回顶，
     * 用户正看着的楼会被弹走。
     */
    private void bindRewardReviewStats(PostDetail postDetail) {
        if (postDetail == null) return;
        int rewardCount = postDetail.getRewardCount();
        int goodReviewCount = postDetail.getGoodReviewCount();
        int rewardCoins = postDetail.getRewardCoins();
        List<String> rewardUserAvatars = postDetail.getRewardUserAvatars();
        List<String> goodReviewUserAvatars = postDetail.getGoodReviewUserAvatars();
        boolean hasStats = rewardCount > 0 || goodReviewCount > 0
                || (rewardUserAvatars != null && !rewardUserAvatars.isEmpty())
                || (goodReviewUserAvatars != null && !goodReviewUserAvatars.isEmpty());
        if (this.httpClient.isLoggedIn() && hasStats) {
            this.binding.layoutRewardReviewStats.setVisibility(0);
            this.binding.tvRewardCount.setText(String.valueOf(rewardCount));
            this.binding.tvRewardCoins.setText("共计 " + rewardCoins + " 金币");
            this.binding.tvGoodReviewCount.setText(String.valueOf(goodReviewCount));
            bindAvatarStrip(this.binding.llRewardAvatars, rewardUserAvatars);
            bindAvatarStrip(this.binding.llGoodReviewAvatars, goodReviewUserAvatars);
        } else {
            this.binding.layoutRewardReviewStats.setVisibility(8);
        }
    }

    private void bindAvatarStrip(LinearLayout linearLayout, List<String> avatarUrls) {
        if (linearLayout == null) {
            return;
        }
        linearLayout.removeAllViews();
        if (avatarUrls == null || avatarUrls.isEmpty()) {
            return;
        }
        int maxVisible = Math.min(6, avatarUrls.size());
        int size = dpToPx(32);
        int overlap = dpToPx(8);
        for (int i = 0; i < maxVisible; i++) {
            ImageView avatar = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            if (i > 0) {
                params.leftMargin = -overlap;
            }
            avatar.setLayoutParams(params);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setPadding(dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1));
            avatar.setBackgroundResource(R.drawable.circle_avatar_bg);
            String url = avatarUrls.get(i);
            Glide.with((FragmentActivity) this).load(com.solosu.mtforum.util.ForumImageLoader.model(url)).transform(new CircleCrop()).placeholder(R.drawable.ic_account).error(R.drawable.ic_account).into(avatar);
            linearLayout.addView(avatar);
        }
        int i2 = avatarUrls.size();
        if (i2 > 6) {
            TextView more = new TextView(this);
            LinearLayout.LayoutParams params2 = new LinearLayout.LayoutParams(dpToPx(32), dpToPx(32));
            params2.leftMargin = -overlap;
            more.setLayoutParams(params2);
            more.setGravity(17);
            more.setText("+" + (avatarUrls.size() - 6));
            more.setTextSize(10.0f);
            more.setTextColor(-1);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(1);
            bg.setColor(-1728053248);
            bg.setStroke(dpToPx(1), -1);
            more.setBackground(bg);
            linearLayout.addView(more);
        }
    }

    private void showReplyBottomSheet(String prefillText) {
        if (this.mBottomSheetDialog != null && this.mBottomSheetDialog.isShowing()) {
            this.mBottomSheetDialog.dismiss();
        }
        final View dialogView = getLayoutInflater().inflate(R.layout.dialog_reply_bottom_sheet, (ViewGroup) null);
        MaterialCardView dialogCard = (MaterialCardView) dialogView.findViewById(R.id.dialog_card);
        final TextInputEditText etReplyDialog = (TextInputEditText) dialogView.findViewById(R.id.et_reply_dialog);
        MaterialButton btnSend = (MaterialButton) dialogView.findViewById(R.id.btn_send_reply);
        TextView tvTarget = (TextView) dialogView.findViewById(R.id.tv_reply_target);
        if (!TextUtils.isEmpty(prefillText)) {
            etReplyDialog.setText(prefillText);
            etReplyDialog.setSelection(prefillText.length());
            tvTarget.setText(prefillText);
            tvTarget.setVisibility(0);
        }
        // build63: 快捷回复短语条
        buildQuickReplyChips(dialogView, etReplyDialog);

        FrostedGlassHelper.applyToCardViews(dialogCard, this);
        this.mBottomSheetDialog = new BottomSheetDialog(this);
        this.mBottomSheetDialog.setContentView(dialogView);
        DialogHelper.applyToBottomSheet(this.mBottomSheetDialog, dialogView, this);
        // build70: 回复框三件套修复
        //  1) 输入法遮挡输入框 -> ADJUST_RESIZE，让弹窗随键盘上移
        //  2) 输入内容上下滑动会把整个弹窗拖走 -> 关掉 BottomSheet 的拖拽手势
        //  3) 长内容看不全 -> 展开到全高并禁止折叠
        android.view.Window sheetWin = this.mBottomSheetDialog.getWindow();
        if (sheetWin != null) {
            sheetWin.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        this.mBottomSheetDialog.setOnShowListener(d -> {
            View sheet = this.mBottomSheetDialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            com.google.android.material.bottomsheet.BottomSheetBehavior<View> b =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
            b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            b.setSkipCollapsed(true);
            b.setDraggable(false);   // 关键：否则在输入框里上下滑会把弹窗拖下去
        });

        setupBBCodeTools(dialogView, etReplyDialog);
        this.mBottomSheetDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override // android.content.DialogInterface.OnDismissListener
            public final void onDismiss(DialogInterface dialogInterface) {
                ThreadDetailActivity.this.lambda$showReplyBottomSheet$29(dialogInterface);
            }
        });
        this.mBottomSheetDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override // android.content.DialogInterface.OnShowListener
            public final void onShow(DialogInterface dialogInterface) {
                ThreadDetailActivity.this.lambda$showReplyBottomSheet$30(dialogView, dialogInterface);
            }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$showReplyBottomSheet$31(etReplyDialog, view);
            }
        });
        ImageButton btnPickImage = dialogView.findViewById(R.id.btn_pick_image);
        if (btnPickImage != null) {
            btnPickImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        }
        if (!pendingImageUris.isEmpty()) {
            updateDialogImagePreview();
        }
        this.mBottomSheetDialog.show();
    }

    private void lambda$showReplyBottomSheet$29(DialogInterface d) {
        this.currentReplyPid = "";
        this.currentReplyTarget = "";
        this.currentReplyToName = "";
        this.currentReplyFloor = "";
    }

    private void lambda$showReplyBottomSheet$30(View dialogView, DialogInterface d) {
        View parent = (View) dialogView.getParent();
        if (parent != null) {
            parent.setBackgroundResource(android.R.color.transparent);
            BottomSheetBehavior behavior = BottomSheetBehavior.from(parent);
            int peekHeight = (int) (((double) getResources().getDisplayMetrics().heightPixels) * 0.5d);
            behavior.setPeekHeight(peekHeight);
        }
    }

    private void lambda$showReplyBottomSheet$31(TextInputEditText etReplyDialog, View v) {
        String text = etReplyDialog.getText().toString().trim();
        if (TextUtils.isEmpty(text) && pendingImageUris.isEmpty()) {
            etReplyDialog.setError(getString(R.string.reply_hint_empty));
        } else {
            etReplyDialog.setError(null);
            String attachTags = buildAttachTags();
            String finalText = attachTags + text;
            attemptReply(finalText, etReplyDialog);
        }
    }

    /** 未登录操作统一弹出登录底部弹窗(与回复弹窗同风格) */
    private void promptLogin() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (this.mBottomSheetDialog != null && this.mBottomSheetDialog.isShowing()) {
            this.mBottomSheetDialog.dismiss();
        }
        LoginBottomSheet.show(this, null);
    }

    private void attemptReply(final String replyText, TextInputEditText etReplyInput) {
        if (!this.httpClient.isLoggedIn()) {
            this.httpClient.syncFromCookieManager();
        }
        if (!this.httpClient.isLoggedIn()) {
            promptLogin();
        } else {
            if (TextUtils.isEmpty(replyText)) {
                this.binding.tilReply.setError(getString(R.string.reply_hint_empty));
                return;
            }
            this.binding.tilReply.setError(null);
            final String formhash = this.postDetail != null ? this.postDetail.getFormhash() : null;
            new java.lang.Thread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$attemptReply$32(formhash, replyText);
                }
            }).start();
        }
    }

    private void lambda$attemptReply$32(String formhash, String replyText) {
        String fh = formhash;
        try {
            if (TextUtils.isEmpty(fh)) {
                String html = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid));
                fh = ForumParser.parseFormhash(html);
            }
            if (TextUtils.isEmpty(fh)) {
                showReplyFailure("获取回复验证失败，请刷新页面后重试");
                return;
            }
            Map<String, String> params = new HashMap<>();
            params.put("formhash", fh);
            params.put("message", replyText);
            params.put("replysubmit", "yes");
            // build71: 上传得到的 aid 必须随回复一起提交 attachnew,否则附件不会被关联
            //        (发帖页 attemptPost 有这一步,回复页之前漏了 -> "图片传上去了但发不出去")
            synchronized (pendingUploadAids) {
                for (String aid : pendingUploadAids) {
                    if (!TextUtils.isEmpty(aid)) {
                        params.put("attachnew[" + aid + "][description]", "");
                    }
                }
            }
            if (!TextUtils.isEmpty(this.currentReplyPid)) {
                params.put("reppid", this.currentReplyPid);
                params.put("reppost", this.currentReplyPid);
                params.put("addfeed", "1");
                String trimStr = buildReplyQuote(this.currentReplyPid);
                if (!TextUtils.isEmpty(trimStr)) {
                    params.put("noticetrimstr", trimStr);
                }
                params.put("noticeauthormsg", replyText);
            }
            if (this.postDetail != null && !TextUtils.isEmpty(this.postDetail.getNoticeauthor())) {
                params.put("noticeauthor", this.postDetail.getNoticeauthor());
            }
            params.put("posttime", String.valueOf(System.currentTimeMillis() / 1000));
            String replyUrl = "https://bbs.binmt.cc/forum.php?mod=post&action=reply&fid=" + (this.postDetail != null ? this.postDetail.getForumFid() : "") + "&tid=" + this.tid + "&extra=&replysubmit=yes&mobile=2&handlekey=fastpost&loc=1&inajax=1";
            String result = this.httpClient.post(replyUrl, params);
            boolean responseReportsSuccess = isReplyResponseSuccessful(result);
            if (!responseReportsSuccess && !wasReplyPublished(replyText)) {
                showReplyFailure(extractReplyError(result));
            } else {
                completeReplyPublished(null);
            }
        } catch (Exception e) {
            showReplyFailure("回复失败：" + (TextUtils.isEmpty(e.getMessage()) ? "网络异常，请稍后重试" : e.getMessage()));
        }
    }

    private boolean wasReplyPublished(String replyText) {
        try {
            String currentUid = UserSessionManager.getInstance().getUid(getApplicationContext());
            if (TextUtils.isEmpty(currentUid)) {
                return false;
            }
            int lastPage = this.postDetail != null ? Math.max(1, this.postDetail.getTotalPages()) : 1;
            String expectedText = normalizeReplyText(replyText);
            for (int page = lastPage; page <= lastPage + 1; page++) {
                String url = ForumParser.getThreadDetailUrl(this.tid, page, getReplyOrder()) + "&_reply_check=" + System.currentTimeMillis();
                PostDetail latest = ForumParser.parseThreadDetail(this.httpClient.get(url));
                if (latest != null && latest.getReplies() != null) {
                    for (int k = latest.getReplies().size() - 1; k >= 0; k--) {
                        ReplyItem item = latest.getReplies().get(k);
                        if (item != null && currentUid.equals(item.getAuthorUid()) && !TextUtils.isEmpty(expectedText) && normalizeReplyText(item.getContentText()).contains(expectedText)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return false;
    }

    private String normalizeReplyText(String text) {
        return TextUtils.isEmpty(text) ? "" : text.replaceAll("(?is)\\[attach(?:img)?\\]\\d+\\[/attach(?:img)?\\]", "").replaceAll("\\s+", " ").trim();
    }

    private void completeReplyPublished(List<String> dummy) {
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$completeReplyPublished$33();
            }
        });
    }

    private void lambda$completeReplyPublished$33() {
        this.currentReplyPid = "";
        this.currentReplyTarget = "";
        this.currentReplyToName = "";
        this.currentReplyFloor = "";
        this.binding.tilReply.setError(null);
        // build80: 回复已发出 -> 清空待发送图片队列与残留的 [attachimg] 标签,
        //          否则图片会一直留在回复栏里(已提交成功却看着像没发出去)
        this.pendingImageUris.clear();
        this.uploadedAidMap.clear();
        synchronized (this.pendingUploadAids) {
            this.pendingUploadAids.clear();
        }
        synchronized (this.imageUploadPendingQueue) {
            this.imageUploadPendingQueue.clear();
        }
        if (this.binding != null && this.binding.etReply != null) {
            this.binding.etReply.setText("");
        }
        refreshAllImagePreviews();
        Toast.makeText(this, R.string.reply_success, 0).show();
        if (this.mBottomSheetDialog != null && this.mBottomSheetDialog.isShowing()) {
            this.mBottomSheetDialog.dismiss();
        }
        refreshPostDetail();
    }

    private String buildReplyQuote(String pid) {
        if (TextUtils.isEmpty(pid) || this.displayedReplies == null) {
            return null;
        }
        for (ReplyItem item : this.displayedReplies) {
            if (item != null && pid.equals(item.getPid())) {
                String author = !TextUtils.isEmpty(item.getAuthor()) ? item.getAuthor() : "匿名";
                String time = !TextUtils.isEmpty(item.getTime()) ? item.getTime() : "";
                String content = TextUtils.isEmpty(item.getContentText()) ? "" : item.getContentText();
                return "[quote][color=#999999]" + author + " 发表于 " + time + "[/color]\n" + content + "[/quote]";
            }
        }
        return null;
    }

    private String extractRecommendActionUrl(String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        try {
            Document doc = Jsoup.parse(html);
            Element link = doc.select("a.comiis_recommend_addkey, a.comiis_recommend_new").first();
            if (link == null) {
                return null;
            }
            String href = link.attr("href");
            if (!TextUtils.isEmpty(href) && !href.startsWith("javascript:")) {
                if (href.startsWith("/")) {
                    return HttpClient.BASE_URL + href.substring(1);
                }
                if (!href.startsWith("http://") && !href.startsWith("https://")) {
                    return HttpClient.BASE_URL + href;
                }
                return href;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String appendQuery(String url, String query) {
        if (TextUtils.isEmpty(url) || url.contains("inajax=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private boolean containsAny(String text, String... values) {
        if (TextUtils.isEmpty(text) || values == null) {
            return false;
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value) && text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikeAddResponseSuccessful(String result) {
        if (TextUtils.isEmpty(result) || ForumParser.isLoginPage(result) || containsAny(result, "没有点赞权限", "不能点赞", "今日评价机会已用完", "关闭的主题无法执行", "请先登录", "formhash错误", "非法操作")) {
            return false;
        }
        return containsAny(result, "recommendv", "recommendc", "点赞成功", "推荐成功", "succeedhandle_recommend", "评价成功");
    }

    private void updateLikeIcon() {
        ImageButton imageButton = this.binding.btnLike;
        // build66: 改用与列表页一致的拇指标(原 forum_like 是心形),用颜色区分已赞/未赞
        imageButton.setImageResource(R.drawable.ic_like_detail);
        imageButton.setColorFilter(getColor(this.isLiked ? R.color.primary : R.color.icon_secondary));
        updateCountBadge(this.binding.tvLikeBadge, Math.max(0, this.likeCount));
        bounce(this.binding.btnLike);
        // build80: 点赞数回写缓存, 返回列表页时按 tid 回填, 及时同步
        if (!TextUtils.isEmpty(this.tid)) {
            com.solosu.mtforum.session.PostCountsCache.setLikes(this.tid, Math.max(0, this.likeCount));
        }
    }

    private void updateFavoriteIcon() {
        int i;
        ImageButton imageButton = this.binding.btnFavorite;
        if (this.isFavorited) {
            i = R.drawable.forum_favorite_on;
        } else {
            i = R.drawable.forum_favorite_off;
        }
        imageButton.setImageResource(i);
    }

    private void updateCountBadge(TextView badge, int count) {
        if (badge == null) {
            return;
        }
        if (count > 0) {
            // build65: 帖子内角标显示真实数字(原 99+ 截断),超大值才用 999+
            badge.setText(count > 999 ? "999+" : String.valueOf(count));
            badge.setVisibility(0);
        } else {
            badge.setVisibility(8);
        }
    }

    private void insertAtMention() {
        int start = Math.max(0, this.binding.etReply.getSelectionStart());
        String text = this.binding.etReply.getText() == null ? "" : this.binding.etReply.getText().toString();
        this.binding.etReply.setText(text.substring(0, Math.min(start, text.length())) + "@" + text.substring(Math.min(start, text.length())));
        this.binding.etReply.setSelection(Math.min(start, text.length()) + "@".length());
        this.binding.etReply.requestFocus();
    }

    private void toggleFollow() {
        if (this.postDetail == null || TextUtils.isEmpty(this.postDetail.getAuthorUid())) {
            return;
        }
        if (!FollowStateManager.isLoggedIn(this)) {
            promptLogin();
            return;
        }
        final boolean targetState = !this.postDetail.isFollowed();
        this.binding.btnFollow.setEnabled(false);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleFollow$35(targetState);
            }
        }).start();
    }

    private void lambda$toggleFollow$35(final boolean targetState) {
        final boolean success = FollowStateManager.syncFollow(this, this.postDetail.getAuthorUid(), targetState);
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleFollow$34(success, targetState);
            }
        });
    }

    private void lambda$toggleFollow$34(boolean success, boolean targetState) {
        int i;
        this.binding.btnFollow.setEnabled(true);
        if (!success) {
            Toast.makeText(this, "关注操作失败，请稍后重试", 0).show();
            return;
        }
        this.postDetail.setFollowed(targetState);
        this.binding.btnFollow.setText(targetState ? R.string.action_followed : R.string.action_follow);
        if (targetState) {
            i = R.string.action_follow_success;
        } else {
            i = R.string.action_unfollow_success;
        }
        Toast.makeText(this, i, 0).show();
    }

    private void toggleFavorite() {
        if (!this.httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        if (TextUtils.isEmpty(this.tid) || !this.binding.btnFavorite.isEnabled()) {
            return;
        }
        final boolean targetState = !this.isFavorited;
        final boolean oldState = this.isFavorited;
        this.binding.btnFavorite.setEnabled(false);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleFavorite$37(targetState, oldState);
            }
        }).start();
    }

    private void lambda$toggleFavorite$37(final boolean targetState, final boolean oldState) {
        boolean success = false;
        String errorMessage = null;
        try {
            this.httpClient.syncFromCookieManager();
            boolean z = true;
            if (targetState) {
                String pageHtml = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid) + "&_favorite_refresh=" + System.currentTimeMillis());
                String actionUrl = extractFavoriteActionUrl(pageHtml);
                if (TextUtils.isEmpty(actionUrl)) {
                    throw new IllegalStateException("无法获取收藏操作地址");
                }
                String result = this.httpClient.get(appendQuery(actionUrl, "inajax=1"));
                if (!ForumParser.isLoginPage(result) && !containsAny(result, "请先登录", "没有权限", "非法操作", "formhash错误")) {
                    String favoritePostUrl = extractFavoriteFormAction(result, actionUrl);
                    if (!TextUtils.isEmpty(favoritePostUrl)) {
                        Map<String, String> formParams = extractFavoriteFormParams(result);
                        formParams.put("favoritesubmit", "true");
                        formParams.put("favoritesubmit_btn", "确定");
                        if (!formParams.containsKey("description")) {
                            formParams.put("description", "手机收藏");
                        }
                        String postResult = this.httpClient.post(favoritePostUrl, formParams);
                        success = isFavoriteMutationResponseSuccessful(postResult, true);
                    } else {
                        success = isFavoriteMutationResponseSuccessful(result, true);
                    }
                }
            } else {
                success = requestRemoveFavoriteFromServer();
            }
            Boolean serverState = queryServerFavoriteStateWithRetry(targetState);
            if (serverState != null) {
                if (serverState.booleanValue() != targetState) {
                    z = false;
                }
                success = z;
            }
            if (!success) {
                errorMessage = "网页端未确认收藏状态已更新";
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }
        final boolean finalSuccess = success;
        final String finalError = errorMessage;
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleFavorite$36(finalSuccess, targetState, oldState, finalError);
            }
        });
    }

    private void lambda$toggleFavorite$36(boolean z, boolean z2, boolean z3, String str) {
        this.binding.btnFavorite.setEnabled(true);
        if (z) {
            this.isFavorited = z2;
            saveFavoritedState(z2);
            if (this.postDetail != null) {
                this.postDetail.setFavorited(z2);
                this.postDetail.setFavoritedStateKnown(true);
            }
            updateFavoriteIcon();
            this.favoriteCount = Math.max(0, this.favoriteCount + (z2 ? 1 : -1));
            updateCountBadge(this.binding.tvFavoriteBadge, this.favoriteCount);
            Toast.makeText(this, z2 ? "已收藏" : "已取消收藏", 0).show();
            return;
        }
        this.isFavorited = z3;
        updateFavoriteIcon();
        Toast.makeText(this, TextUtils.isEmpty(str) ? "收藏操作失败，请稍后重试" : str, 0).show();
    }

    private String extractFavoriteActionUrl(String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        try {
            Document doc = Jsoup.parse(html);
            Element link = doc.select("#comiis_favorite_a").first();
            if (link == null) {
                return null;
            }
            String href = link.attr("href");
            if (!TextUtils.isEmpty(href) && !href.startsWith("javascript:")) {
                if (href.startsWith("/")) {
                    return HttpClient.BASE_URL + href.substring(1);
                }
                if (!href.startsWith("http://") && !href.startsWith("https://")) {
                    return HttpClient.BASE_URL + href;
                }
                return href;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFavoriteFormAction(String response, String fallbackUrl) {
        if (TextUtils.isEmpty(response)) {
            return null;
        }
        try {
            String html = extractCdata(response);
            Document doc = Jsoup.parse(html);
            Element form = doc.select("form[id^=favoriteform], form[name^=favoriteform]").first();
            if (form == null) {
                return null;
            }
            String action = form.attr("action");
            if (TextUtils.isEmpty(action)) {
                action = fallbackUrl;
            }
            return normalizeForumUrl(action);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> extractFavoriteFormParams(String response) {
        Map<String, String> params = new HashMap<>();
        if (TextUtils.isEmpty(response)) {
            return params;
        }
        try {
            Document doc = Jsoup.parse(extractCdata(response));
            Element form = doc.select("form[id^=favoriteform], form[name^=favoriteform]").first();
            if (form == null) {
                return params;
            }
            for (Element input : form.select("input[name], textarea[name], select[name]")) {
                String name = input.attr(ChatActivity.EXTRA_NAME);
                if (!TextUtils.isEmpty(name)) {
                    String value = input.tagName().equalsIgnoreCase("textarea") ? input.text() : input.attr("value");
                    params.put(name, value == null ? "" : value);
                }
            }
        } catch (Exception e) {
        }
        return params;
    }

    private String extractCdata(String response) {
        if (TextUtils.isEmpty(response)) {
            return "";
        }
        int start = response.indexOf("<![CDATA[");
        if (start < 0) {
            start = response.indexOf("<![cdata[");
        }
        if (start < 0) {
            return response;
        }
        int start2 = start + 9;
        int end = response.indexOf("]]>", start2);
        return end >= 0 ? response.substring(start2, end) : response.substring(start2);
    }

    private String normalizeForumUrl(String url) {
        if (TextUtils.isEmpty(url) || url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return url.startsWith("/") ? HttpClient.BASE_URL + url.substring(1) : HttpClient.BASE_URL + url;
    }

    private boolean isFavoriteMutationResponseSuccessful(String response, boolean targetState) {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response) || containsAny(response, "请先登录", "没有权限", "非法操作", "formhash错误", "收藏失败", "取消收藏失败")) {
            return false;
        }
        if (targetState) {
            return containsAny(response, "succeedhandle_favorite_add", "succeedhandle_favorite_thread", "收藏成功", "信息收藏成功", "已收藏", "重复收藏");
        }
        return containsAny(response, "succeedhandle_favorite_del", "succeedhandle_favorite_thread", "取消收藏成功", "已取消收藏", "删除成功", "取消成功");
    }

    private boolean requestRemoveFavoriteFromServer() throws Exception {
        String page = this.httpClient.get("https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_remove=" + System.currentTimeMillis());
        if (ForumParser.isLoginPage(page)) {
            return false;
        }
        String favid = "";
        List<Thread> favorites = ForumParser.parseFavoriteList(page);
        if (favorites != null) {
            Iterator<Thread> it = favorites.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                Thread item = it.next();
                if (item != null && this.tid.equals(item.getTid())) {
                    favid = item.getFavid();
                    break;
                }
            }
        }
        if (TextUtils.isEmpty(favid)) {
            Matcher matcher = Pattern.compile("(?:favid|fav_id)=(\\d+)[^<>]{0,300}(?:tid=" + Pattern.quote(this.tid) + "|thread-" + Pattern.quote(this.tid) + "-)", 34).matcher(page);
            if (matcher.find()) {
                favid = matcher.group(1);
            }
            if (TextUtils.isEmpty(favid)) {
                Matcher matcher2 = Pattern.compile("(?:tid=" + Pattern.quote(this.tid) + "|thread-" + Pattern.quote(this.tid) + "-)[^<>]{0,300}(?:favid|fav_id)=(\\d+)", 34).matcher(page);
                if (matcher2.find()) {
                    favid = matcher2.group(1);
                }
            }
        }
        if (TextUtils.isEmpty(favid)) {
            return false;
        }
        String formhash = ForumParser.parseFormhash(page);
        if (TextUtils.isEmpty(formhash)) {
            String detailHtml = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid) + "&_favorite_remove_hash=" + System.currentTimeMillis());
            formhash = ForumParser.parseFormhash(detailHtml);
        }
        String deleteUrl = "https://bbs.binmt.cc/home.php?mod=spacecp&ac=favorite&op=delete&favid=" + favid + "&type=all&mobile=2";
        Map<String, String> params = new HashMap<>();
        params.put("referer", "https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2");
        params.put("deletesubmit", "true");
        if (!TextUtils.isEmpty(formhash)) {
            params.put("formhash", formhash);
        }
        params.put("handlekey", "comiis");
        String response = this.httpClient.post(deleteUrl, params);
        String verifyHtml = this.httpClient.get("https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_remove_verify=" + System.currentTimeMillis());
        boolean stillExists = containsFavoriteTid(verifyHtml, this.tid);
        return !stillExists || containsAny(response, "succeedhandle_favorite_del", "删除成功", "取消收藏成功", "取消成功");
    }

    private boolean containsFavoriteTid(String html, String targetTid) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(targetTid)) {
            return false;
        }
        List<Thread> favorites = ForumParser.parseFavoriteList(html);
        if (favorites != null) {
            for (Thread item : favorites) {
                if (item != null && targetTid.equals(item.getTid())) {
                    return true;
                }
            }
        }
        return Pattern.compile("(?:[?&]tid=" + Pattern.quote(targetTid) + "(?:&|\\\"|')|thread-" + Pattern.quote(targetTid) + "(?:-|\\.))", 2).matcher(html).find();
    }

    private Boolean queryServerFavoriteStateWithRetry(boolean targetState) {
        Boolean lastState = null;
        long[] delays = {0, 250, 600, 1200, 2000};
        for (long delay : delays) {
            if (delay > 0) {
                try {
                    java.lang.Thread.sleep(delay);
                } catch (InterruptedException e) {
                    java.lang.Thread.currentThread().interrupt();
                }
            }
            Boolean detailState = queryServerFavoriteDetailState();
            if (detailState != null) {
                lastState = detailState;
                if (detailState.booleanValue() == targetState) {
                    return detailState;
                }
            }
            Boolean listState = queryServerFavoriteListState();
            if (listState != null) {
                lastState = listState;
                if (listState.booleanValue() == targetState) {
                    return listState;
                }
            }
        }
        return lastState;
    }

    private Boolean queryServerFavoriteDetailState() {
        PostDetail server;
        try {
            String html = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid) + "&_favorite_verify=" + System.currentTimeMillis());
            if (!ForumParser.isLoginPage(html) && (server = ForumParser.parseThreadDetail(html)) != null && server.isFavoritedStateKnown()) {
                return Boolean.valueOf(server.isFavorited());
            }
        } catch (Exception e) {
        }
        return null;
    }

    private Boolean queryServerFavoriteListState() {
        try {
            String html = this.httpClient.get("https://bbs.binmt.cc/home.php?mod=space&do=favorite&type=all&mobile=2&_favorite_verify=" + System.currentTimeMillis());
            if (ForumParser.isLoginPage(html)) {
                return null;
            }
            List<Thread> favorites = ForumParser.parseFavoriteList(html);
            boolean z = true;
            if (favorites != null) {
                for (Thread item : favorites) {
                    if (item != null && this.tid.equals(item.getTid())) {
                        return true;
                    }
                }
            }
            if (TextUtils.isEmpty(this.tid) || !Pattern.compile("thread-" + Pattern.quote(this.tid) + "(?:-|\\\\.)", 2).matcher(html).find()) {
                z = false;
            }
            return Boolean.valueOf(z);
        } catch (Exception e) {
            return null;
        }
    }

    private String[] replaceHiddenQuoteWithPlaceholder(String html) {
        int lt;
        int gt;
        if (html == null) {
            return new String[]{"", ""};
        }
        Pattern openP = Pattern.compile("<div\\s+class=\"(?:comiis_quote|locked)[^\"]*\"", 2);
        Matcher m = openP.matcher(html);
        if (!m.find()) {
            return new String[]{html, ""};
        }
        int openQuote = m.start();
        int depth = 0;
        int i = openQuote;
        int end = html.length();
        while (true) {
            if (i >= html.length() || (lt = html.indexOf(60, i)) < 0 || (gt = html.indexOf(62, lt)) < 0) {
                break;
            }
            String tag = html.substring(lt + 1, gt).trim().toLowerCase();
            if (tag.startsWith("/")) {
                if (tag.startsWith("/div") && depth - 1 <= 0) {
                    end = gt + 1;
                    break;
                }
            } else if (tag.startsWith("div")) {
                depth++;
            }
            i = gt + 1;
        }
        if (end >= html.length()) {
            return new String[]{html, ""};
        }
        String block = html.substring(openQuote, end);
        String text = block.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return new String[]{html, ""};
        }
        // 摘要过长时截断,避免胶囊占满整行
        if (text.length() > 28) {
            text = text.substring(0, 28) + "...";
        }
        String clean = html.substring(0, openQuote) + HIDDEN_QUOTE_PLACEHOLDER + html.substring(end);
        return new String[]{clean, text};
    }

    private void applyHiddenNoticeHighlight(CharSequence text, String notice) {
        if ((text instanceof Spannable) && !TextUtils.isEmpty(notice)) {
            Spannable sp = (Spannable) text;
            int idx = sp.toString().indexOf(HIDDEN_QUOTE_PLACEHOLDER);
            if (idx < 0) {
                return;
            }
            int end = HIDDEN_QUOTE_PLACEHOLDER.length() + idx;
            sp.setSpan(new HiddenNoticeSpan(notice, dpToPx(9), dpToPx(12), dpToPx(11), -854017, -14721112), idx, end, 33);
            // build68: 「请回复」这块以前是个普通链接，点了会拉起浏览器。
            // 改成点击直接弹出本机的快捷回复面板。
            sp.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(android.view.View widget) {
                    currentReplyPid = "";
                    currentReplyTarget = "";
                    currentReplyToName = "";
                    currentReplyFloor = "";
                    showReplyBottomSheet("");
                }

                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setUnderlineText(false);
                }
            }, idx, end, 33);
        }
    }

    private static class HiddenNoticeSpan extends ReplacementSpan {
        private final int bgColor;
        private final float paddingPx;
        private final float radiusPx;
        private final String text;
        private final int textColor;
        private final float textSizePx;

        HiddenNoticeSpan(String text, float radiusPx, float paddingPx, float textSizePx, int bgColor, int textColor) {
            this.text = text;
            this.radiusPx = radiusPx;
            this.paddingPx = paddingPx;
            this.textSizePx = textSizePx;
            this.bgColor = bgColor;
            this.textColor = textColor;
        }

        @Override // android.text.style.ReplacementSpan
        public int getSize(Paint paint, CharSequence cs, int start, int end, Paint.FontMetricsInt fm) {
            paint.setTextSize(this.textSizePx);
            float w = paint.measureText(this.text);
            if (fm != null) {
                Paint.FontMetricsInt fmi = paint.getFontMetricsInt();
                fm.ascent = fmi.ascent;
                fm.descent = fmi.descent;
                fm.top = fmi.top;
                fm.bottom = fmi.bottom;
            }
            return Math.round((this.paddingPx * 2.0f) + w);
        }

        @Override // android.text.style.ReplacementSpan
        public void draw(Canvas canvas, CharSequence cs, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            paint.setAntiAlias(true);
            int oldColor = paint.getColor();
            paint.setTextSize(this.textSizePx);
            Paint.FontMetricsInt fmi = paint.getFontMetricsInt();
            float textW = paint.measureText(this.text);
            float left = x + 1.0f;
            float right = ((x + textW) + (this.paddingPx * 2.0f)) - 1.0f;
            float rectTop = top + 3.0f;
            float rectBottom = bottom - 3.0f;
            Paint bg = new Paint(1);
            bg.setColor(this.bgColor);
            canvas.drawRoundRect(new RectF(left, rectTop, right, rectBottom), this.radiusPx, this.radiusPx, bg);
            paint.setColor(this.textColor);
            float baseline = (((top + bottom) - fmi.ascent) - fmi.descent) / 2.0f;
            canvas.drawText(this.text, this.paddingPx + left, baseline, paint);
            paint.setColor(oldColor);
        }
    }

    private void adjustEditFooterDividerWidth() {
        this.binding.tvEditFooter.post(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$adjustEditFooterDividerWidth$38();
            }
        });
    }

    private void lambda$adjustEditFooterDividerWidth$38() {
        if (this.binding.tvEditFooter.getVisibility() != 0) {
            return;
        }
        String text = this.binding.tvEditFooter.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        float textW = this.binding.tvEditFooter.getPaint().measureText(text);
        View divider = this.binding.viewEditDivider;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        int parentW = divider.getMeasuredWidth();
        if (parentW <= 0) {
            return;
        }
        int w = (int) Math.min(dpToPx(2) + textW, parentW);
        lp.width = w;
        lp.gravity = 1;
        divider.setLayoutParams(lp);
        View dividerTop = this.binding.viewEditDividerTop;
        LinearLayout.LayoutParams lpTop = (LinearLayout.LayoutParams) dividerTop.getLayoutParams();
        lpTop.width = w;
        lpTop.gravity = 1;
        dividerTop.setLayoutParams(lpTop);
    }

    private String[] splitEditFooter(String html) {
        int endIdx;
        int lt;
        int gt;
        if (html == null) {
            return new String[]{"", ""};
        }
        String marker = "本帖最后由";
        int idx = html.indexOf("本帖最后由");
        if (idx < 0) {
            marker = "本贴最后由";
            idx = html.indexOf("本贴最后由");
        }
        if (idx >= 0 && (endIdx = html.indexOf("编辑", marker.length() + idx)) >= 0) {
            int start = idx;
            while (true) {
                int lt2 = html.lastIndexOf(60, start - 1);
                if (lt2 < 0 || (gt = html.indexOf(62, lt2)) < 0 || gt > start || !html.substring(gt + 1, start).trim().isEmpty()) {
                    break;
                }
                String tag = html.substring(lt2 + 1, gt).trim();
                if (!tag.startsWith("span") && !tag.startsWith("b") && !tag.startsWith("br") && !tag.startsWith("font") && !tag.startsWith("i") && !tag.startsWith("em") && !tag.startsWith("strong")) {
                    break;
                }
                start = lt2;
            }
            int editEnd = endIdx + 2;
            int end = editEnd;
            while (true) {
                int gt2 = html.indexOf(62, end);
                if (gt2 < 0 || (lt = html.lastIndexOf(60, gt2)) < end || !html.substring(end, lt).trim().isEmpty()) {
                    break;
                }
                String tag2 = html.substring(lt + 1, gt2).trim();
                if (!tag2.startsWith("/span") && !tag2.startsWith("/b") && !tag2.startsWith("/font") && !tag2.startsWith("/i") && !tag2.startsWith("/em") && !tag2.startsWith("/strong") && !tag2.startsWith("br") && !tag2.endsWith("/")) {
                    break;
                }
                end = gt2 + 1;
            }
            String footer = html.substring(idx, editEnd).replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
            String clean = html.substring(0, start) + html.substring(end);
            return new String[]{clean, footer};
        }
        return new String[]{html, ""};
    }

    private void renderHiddenContent(String hiddenHtml) {
        if (this.binding == null || TextUtils.isEmpty(hiddenHtml)) {
            return;
        }
        this.binding.tvHiddenContent.setVisibility(0);
        String bbcodeConverted = BBCodeUtil.convertBBCodeToHtml(hiddenHtml);
        List<String> hiddenImageUrls = new ArrayList<>();
        String cleanHiddenHtml = extractAndSeparateImages(bbcodeConverted, hiddenImageUrls);
        this.binding.tvHiddenContent.setText(Html.fromHtml(cleanHiddenHtml, 63, createInlineImageGetter(this.binding.tvHiddenContent), com.solosu.mtforum.util.BBCodeUtil.createTagHandler(this)));
        setupClickableLinks(this.binding.tvHiddenContent);
        for (final String imgUrl : hiddenImageUrls) {
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(-2, dpToPx(ItemTouchHelper.Callback.DEFAULT_DRAG_ANIMATION_DURATION)));
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ((LinearLayout.LayoutParams) imageView.getLayoutParams()).setMargins(dpToPx(4), 0, dpToPx(4), 0);
            imageView.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ThreadDetailActivity.this.lambda$renderHiddenContent$39(imgUrl, view);
                }
            });
            Glide.with((FragmentActivity) this).load(com.solosu.mtforum.util.ForumImageLoader.model(imgUrl)).placeholder(new ColorDrawable(getColor(R.color.background_secondary))).error((Drawable) new ColorDrawable(getColor(R.color.divider))).into(imageView);
            this.binding.llImageGallery.addView(imageView);
        }
        this.binding.cardImageGallery.setVisibility(0);
        FrostedGlassHelper.applyToCardViews(this.binding.cardImageGallery, this);
    }

    private void lambda$renderHiddenContent$39(String imgUrl, View v) {
        openImagePreview(imgUrl);
    }
private void viewHiddenContent() {
        if (this.postDetail == null || !this.postDetail.isHasHiddenContent()) {
            return;
        }
        if (!this.httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        this.binding.tvHiddenContentHint.setVisibility(8);
        this.binding.btnViewHidden.setVisibility(8);
        if (!TextUtils.isEmpty(this.postDetail.getHiddenContentHtml())) {
            renderHiddenContent(this.postDetail.getHiddenContentHtml());
        } else {
            Toast.makeText(this, R.string.hidden_content_prompt, 0).show();
        }
    }

    // build77: maybeAutoUnlock() 已删除 —— 它是第二条解锁链路，
    // 与 runUnlockInBackground() 并行触发，是「自动解锁重复回复」的根因。

    private void loadMoreReplies() {
        if (this.postDetail == null || this.isLoadingMore) {
            return;
        }
        this.isLoadingMore = true;
        this.binding.btnLoadMore.setEnabled(false);
        this.binding.btnLoadMore.setText(R.string.loading);
        this.binding.loadingMore.setVisibility(View.VISIBLE);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$loadMoreReplies$42();
            }
        }).start();
    }

    private void lambda$loadMoreReplies$42() {
        try {
            int totalPages = this.postDetail.getTotalPages();
            final List<ReplyItem> allNewReplies = new ArrayList<>();
            for (int page = this.postDetail.getCurrentPage() + 1; allNewReplies.size() < 10 && page <= totalPages; page++) {
                String pageUrl = ForumParser.getThreadDetailUrl(this.tid, page, getReplyOrder());
                String html = this.httpClient.get(pageUrl);
                PostDetail pageDetail = ForumParser.parseThreadDetail(html);
                List<ReplyItem> pageReplies = pageDetail.getReplies();
                if (pageReplies != null && !pageReplies.isEmpty()) {
                    allNewReplies.addAll(pageReplies);
                }
                this.postDetail.setCurrentPage(pageDetail.getCurrentPage());
                if (pageDetail.getTotalPages() > totalPages) {
                    int totalPages2 = pageDetail.getTotalPages();
                    this.postDetail.setTotalPages(totalPages2);
                    totalPages = totalPages2;
                }
            }
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$loadMoreReplies$40(allNewReplies);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$loadMoreReplies$41(e);
                }
            });
        }
    }

    private void lambda$loadMoreReplies$40(List allNewReplies) {
        this.isLoadingMore = false;
        this.binding.btnLoadMore.setEnabled(true);
        this.binding.btnLoadMore.setText(R.string.load_more_replies);
        this.binding.loadingMore.setVisibility(View.GONE);
        if (!allNewReplies.isEmpty()) {
            List<ReplyItem> merged = new ArrayList<>(this.postDetail.getReplies());
            merged.addAll(allNewReplies);
            this.postDetail.setReplies(merged);
            this.allReplies = new ArrayList<>(merged);
            updateReplyFilterAndOrder();
        } else {
            Toast.makeText(this, R.string.no_more_replies, 0).show();
        }
        this.binding.btnLoadMore.setVisibility(8);
    }

    private void lambda$loadMoreReplies$41(Exception e) {
        this.binding.btnLoadMore.setEnabled(true);
        this.binding.btnLoadMore.setText(R.string.load_more_replies);
        this.binding.loadingMore.setVisibility(View.GONE);
        Toast.makeText(this, "加载失败: " + e.getMessage(), 0).show();
    }


    private void lambda$new$43(java.util.List<android.net.Uri> uris) {
        if (uris != null && !uris.isEmpty()) {
            addPendingImages(uris);
        }
    }

    /**
     * build87: 点赞/收藏状态的账号作用域。
     *
     * <p>以前 key 只拼 tid，切号后新账号一进帖子就看到旧账号点过的赞、收藏过的帖 ——
     * 「切换账号后进入帖子还是原账号信息」就是这么来的。现在 key 里带上当前 uid，
     * 游客态统一落到 {@code guest}。
     *
     * <p>不走 {@link #loginUid()}：那个字段是懒初始化的，这里要的是「此刻」的账号。
     */
    private String likeFavScope() {
        String uid = com.solosu.mtforum.session.UserSessionManager.getInstance()
                .getUid(getApplicationContext());
        return TextUtils.isEmpty(uid) ? "guest" : uid;
    }

    private boolean restoreLikedState() {
        if (TextUtils.isEmpty(this.tid)) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences(PREF_LIKE_FAV, 0);
        return prefs.getBoolean(KEY_LIKED_PREFIX + likeFavScope() + "_" + this.tid, false);
    }

    private void saveLikedState(boolean liked) {
        if (TextUtils.isEmpty(this.tid)) {
            return;
        }
        getSharedPreferences(PREF_LIKE_FAV, 0).edit()
                .putBoolean(KEY_LIKED_PREFIX + likeFavScope() + "_" + this.tid, liked).apply();
    }

    private boolean restoreFavoritedState() {
        if (TextUtils.isEmpty(this.tid)) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences(PREF_LIKE_FAV, 0);
        return prefs.getBoolean(KEY_FAVORITED_PREFIX + likeFavScope() + "_" + this.tid, false);
    }

    private void saveFavoritedState(boolean favorited) {
        if (TextUtils.isEmpty(this.tid)) {
            return;
        }
        getSharedPreferences(PREF_LIKE_FAV, 0).edit()
                .putBoolean(KEY_FAVORITED_PREFIX + likeFavScope() + "_" + this.tid, favorited).apply();
    }

    private boolean isReplyResponseSuccessful(String response) {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response)) {
            return false;
        }
        String lower = response.toLowerCase(java.util.Locale.ROOT);
        if (containsAny(response, "请先登录", "formhash错误", "非法操作", "没有权限", "回复失败", "附件上传失败", "附件不存在", "上传图片失败")) {
            return false;
        }
        if (lower.contains("succeedhandle_reply") || lower.contains("succeedhandle_post") || lower.contains("succeedhandle_fastpost") || lower.contains("succeedhandle_fastposts") || lower.contains("reply_success") || lower.contains("回复发布成功") || lower.contains("发布成功")) {
            return true;
        }
        return Pattern.compile("[?&](?:tid|pid)=\\d+", 2).matcher(response).find();
    }

    private String extractReplyError(String response) {
        if (TextUtils.isEmpty(response)) {
            return "回复失败,服务器未返回结果";
        }
        if (ForumParser.isLoginPage(response) || containsAny(response, "请先登录")) {
            return "登录状态已失效,请重新登录";
        }
        if (containsAny(response, "formhash错误", "非法操作")) {
            return "验证已失效,请刷新页面后重试";
        }
        if (containsAny(response, "附件上传失败", "附件不存在", "上传图片失败")) {
            return "图片附件关联失败,请重新上传后再发送";
        }
        return "回复发布失败,请稍后重试";
    }

    private void showReplyFailure(final String message) {
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$showReplyFailure$44(message);
            }
        });
    }

    private void lambda$showReplyFailure$44(String message) {
        Toast.makeText(this, message, 0).show();
    }

    private void showKickDialog() {
        performKick();
    }

    private void toggleLike() {
        if (!this.httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        if (TextUtils.isEmpty(this.tid) || !this.binding.btnLike.isEnabled()) {
            return;
        }
        String currentUid = UserSessionManager.getInstance().getUid(getApplicationContext());
        String authorUid = this.postDetail != null ? this.postDetail.getAuthorUid() : null;
        if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid.equals(authorUid)) {
            Toast.makeText(this, "不能点赞自己的帖子", 0).show();
            return;
        }
        final boolean targetState = !this.isLiked;
        final boolean oldState = this.isLiked;
        final int oldCount = this.likeCount;
        this.isLiked = targetState;
        this.likeCount = Math.max(0, this.likeCount + (targetState ? 1 : -1));
        updateLikeIcon();
        this.binding.btnLike.setEnabled(false);
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleLike$46(targetState, oldState, oldCount);
            }
        }).start();
    }

    private void lambda$toggleLike$46(final boolean targetState, final boolean oldState, final int oldCount) {
        boolean success;
        String errorMessage;
        boolean success2 = false;
        try {
            this.httpClient.syncFromCookieManager();
            String pageHtml = this.httpClient.getDesktop(ForumParser.getThreadDetailUrl(this.tid) + "&_action_refresh=" + System.currentTimeMillis());
            String formhash = this.postDetail != null ? this.postDetail.getFormhash() : null;
            if (TextUtils.isEmpty(formhash)) {
                formhash = ForumParser.parseFormhash(pageHtml);
            }
            String recommendUrl = extractRecommendActionUrl(pageHtml);
            if (TextUtils.isEmpty(recommendUrl)) {
                if (!TextUtils.isEmpty(formhash)) {
                    recommendUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=recommend&handlekey=recommend_add&do=add&tid=" + this.tid + "&hash=" + formhash;
                } else {
                    throw new IllegalStateException("无法获取formhash");
                }
            }
            String result = this.httpClient.get(appendQuery(recommendUrl, "inajax=1"));
            if (!targetState) {
                boolean alreadyLiked = containsAny(result, "您已评价过本主题", "您已经评价过本主题", "已经评价过本主题");
                if (alreadyLiked || isLikeAddResponseSuccessful(result)) {
                    String cancelUrl = "https://bbs.binmt.cc/plugin.php?id=comiis_app&comiis=re_recommend&tid=" + this.tid + "&inajax=1";
                    String cancelResult = this.httpClient.get(cancelUrl);
                    success2 = (ForumParser.isLoginPage(cancelResult) || containsAny(cancelResult, "没有权限", "操作失败", "非法操作", "请先登录")) ? false : true;
                }
            } else {
                success2 = isLikeAddResponseSuccessful(result);
            }
            Boolean serverState = queryServerLikeState();
            if (serverState != null) {
                success2 = serverState.booleanValue() == targetState;
            }
            String errorMessage2 = success2 ? null : "网页端未确认点赞状态已更新";
            success = success2;
            errorMessage = errorMessage2;
        } catch (Exception e) {
            String errorMessage3 = e.getMessage();
            success = false;
            errorMessage = errorMessage3;
        }
        final boolean success3 = success;
        final String finalError = errorMessage;
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$toggleLike$45(success3, targetState, oldState, oldCount, finalError);
            }
        });
    }

    private void lambda$toggleLike$45(boolean finalSuccess, boolean targetState, boolean oldState, int oldCount, String finalError) {
        this.binding.btnLike.setEnabled(true);
        if (finalSuccess) {
            saveLikedState(targetState);
            if (this.postDetail != null) {
                this.postDetail.setLiked(targetState);
                this.postDetail.setLikedStateKnown(true);
            }
            Toast.makeText(this, targetState ? "已点赞" : "已取消点赞", 0).show();
            return;
        }
        this.isLiked = oldState;
        this.likeCount = oldCount;
        updateLikeIcon();
        Toast.makeText(this, TextUtils.isEmpty(finalError) ? "点赞操作失败，请稍后重试" : finalError, 0).show();
    }

    private Boolean queryServerLikeState() {
        try {
            String html = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid) + "&_action_refresh=" + System.currentTimeMillis());
            PostDetail server = ForumParser.parseThreadDetail(html);
            if (server == null || !server.isLikedStateKnown()) {
                return null;
            }
            return Boolean.valueOf(server.isLiked());
        } catch (Exception e) {
            return null;
        }
    }

    private String extractAndSeparateImages(String html, List<String> imageUrls) {
        String fullUrl;
        if (TextUtils.isEmpty(html)) {
            return "";
        }
        try {
            Document doc = Jsoup.parse(html);
            doc.select("script").remove();
            doc.select("style").remove();
            doc.select("ignore_js_op").remove();
            doc.select("*:matchesOwn(^border\\s*=\\s*[\"']?\\d)").remove();
            Elements imgs = doc.select("img");
            for (Element img : imgs) {
                String realUrl = pickRealImageUrl(img);
                if (realUrl != null && !realUrl.isEmpty() && (fullUrl = normalizeImageUrl(realUrl)) != null && !isInlineForumImage(fullUrl) && !isPlaceholderImage(fullUrl) && !imageUrls.contains(fullUrl)) {
                    imageUrls.add(fullUrl);
                }
            }
            doc.select("img").remove();
            String cleanedText = doc.body().html();
            return cleanedText.replaceAll("(?i)replyreload\\s*\\+?\\s*=\\s*'[^']*'", "").replaceAll("(?i)replyreload\\s*\\+?\\s*=\\s*\"[^\"]*\"", "").replaceAll("(?i)replyreload\\s*\\+?\\s*=\\s*[^;\\s<]+", "").replaceAll("\\s*border\\s*=\\s*[\"'][^\"']*[\"']", "").replaceAll("\\s*alt\\s*=\\s*[\"'][^\"']*[\"']", "").replaceAll("\\s*title\\s*=\\s*[\"'][^\"']*[\"']", "").replaceAll("<[^>]*>\\s*<", "<");
        } catch (Exception e) {
            return fallbackExtractImages(html, imageUrls);
        }
    }

    private String fallbackExtractImages(String html, List<String> imageUrls) {
        String cleaned = html.replaceAll("(?i)<script[^>]*>.*?</script>", "").replaceAll("(?i)<style[^>]*>.*?</style>", "");
        Pattern imgPattern = Pattern.compile("<img[^>]*(?:file|comiis_loadimages|data-original|data-src|data-file|src)=[\"']([^\"']+)[\"']", 2);
        Matcher matcher = imgPattern.matcher(cleaned);
        while (matcher.find()) {
            String url = matcher.group(1);
            String fullUrl = normalizeImageUrl(url);
            if (fullUrl != null && !isInlineForumImage(fullUrl) && !isPlaceholderImage(fullUrl) && !imageUrls.contains(fullUrl)) {
                imageUrls.add(fullUrl);
            }
        }
        return cleaned.replaceAll("(?i)<img[^>]*>", "");
    }

    private static String normalizeImageUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return HttpClient.BASE_URL + url.substring(1);
        }
        if (url.startsWith("./")) {
            return HttpClient.BASE_URL + url.substring(2);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return HttpClient.BASE_URL + url;
    }

    private void setupClickableLinks(TextView textView) {
        Spannable spannable;
        if (textView == null) {
            return;
        }
        textView.setTextIsSelectable(true);
        textView.setFocusable(true);
        textView.setClickable(true);
        textView.setLongClickable(true);
        textView.setHighlightColor(857839347);
        CharSequence value = textView.getText();
        if (value instanceof Spannable) {
            spannable = (Spannable) value;
        } else {
            spannable = new SpannableString(value == null ? "" : value);
            textView.setText(spannable, TextView.BufferType.SPANNABLE);
        }
        final int linkColor = getColor(R.color.link_color);
        Pattern urlPattern = com.solosu.mtforum.util.PlainTextUrlPattern.WEB_URL;
        FixNestedScrollLinkMovementMethod.matcherLinkify(spannable, urlPattern, new Function<String, String>() {
            @Override // java.util.function.Function
            public final String apply(String obj) {
                return ThreadDetailActivity.this.lambda$setupClickableLinks$47(obj);
            }
        }, new Consumer<String>() {
            @Override // java.util.function.Consumer
            public final void accept(String obj) {
                ThreadDetailActivity.this.lambda$setupClickableLinks$48(obj);
            }
        });
        URLSpan[] urlSpans = (URLSpan[]) spannable.getSpans(0, spannable.length(), URLSpan.class);
        for (URLSpan oldSpan : urlSpans) {
            final String targetUrl = lambda$setupClickableLinks$47(oldSpan.getURL());
            int start = spannable.getSpanStart(oldSpan);
            int end = spannable.getSpanEnd(oldSpan);
            int flags = spannable.getSpanFlags(oldSpan);
            spannable.removeSpan(oldSpan);
            if (start >= 0 && end > start && !TextUtils.isEmpty(targetUrl)) {
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        ThreadDetailActivity.this.lambda$setupClickableLinks$48(targetUrl);
                    }
                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(linkColor);
                        ds.setUnderlineText(true);
                    }
                }, start, end, flags);
            }
        }
        textView.setMovementMethod(new FixNestedScrollLinkMovementMethod());
        textView.setAutoLinkMask(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String lambda$setupClickableLinks$47(String url) {
        if (TextUtils.isEmpty(url)) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return HttpClient.BASE_URL + url.substring(1);
        if (url.startsWith("./")) return HttpClient.BASE_URL + url.substring(2);
        String normalized = com.solosu.mtforum.ui.web.LinkRouter.normalizeWebUrl(url);
        return !TextUtils.isEmpty(normalized) ? normalized : HttpClient.BASE_URL + url;
    }

    /* JADX INFO: Access modifiers changed from: private */
    private void lambda$setupClickableLinks$48(String url) {
        if (TextUtils.isEmpty(url)) return;
        handlePostWebLink(url);
    }

    private boolean isValidUploadUid(String uid) {
        if (TextUtils.isEmpty(uid)) {
            return false;
        }
        try {
            return Long.parseLong(uid) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void applyServerActionState(PostDetail detail) {
        if (detail == null) {
            return;
        }
        if (detail.isLikedStateKnown()) {
            this.isLiked = detail.isLiked();
            saveLikedState(this.isLiked);
        } else {
            this.isLiked = restoreLikedState();
        }
        if (detail.isFavoritedStateKnown()) {
            this.isFavorited = detail.isFavorited();
            saveFavoritedState(this.isFavorited);
        } else {
            this.isFavorited = restoreFavoritedState();
        }
    }

    /**
     * build81: 帖子页本身就能判定收藏态，就别再拉整个收藏列表页了。
     *
     * <p>原实现无条件请求 {@code home.php?mod=space&do=favorite&mobile=2&_refresh=<ts>}，
     * 只为回答「当前这个 tid 收不收藏」这一个布尔值 —— 整页拉下来再逐条比对。
     * 而 Discuz 的帖子页操作栏里 {@code #comiis_favorite_a i.comiis_favorite_a_color}
     * 就带着这个状态，{@code ForumParser} 早已解析并置了
     * {@code favoritedStateKnown}。所以页面能确认时，这个请求纯属浪费，
     * 而且带的 {@code _refresh=<ts>} 还会让服务端缓存也失效。
     *
     * <p>仅在页面读不到状态（模板改版/元素缺失）时才回退到拉收藏页。
     */
    private void syncFavoriteStateFromServer(PostDetail detail) {
        if (detail == null || TextUtils.isEmpty(this.tid) || !this.httpClient.isLoggedIn()) {
            return;
        }
        if (detail.isFavoritedStateKnown()) {
            return;
        }
        try {
            String html = this.httpClient.get("https://bbs.binmt.cc/home.php?mod=space&do=favorite&mobile=2&_refresh=" + System.currentTimeMillis());
            if (ForumParser.isLoginPage(html)) {
                return;
            }
            List<Thread> favorites = ForumParser.parseFavoriteList(html);
            boolean found = false;
            if (favorites != null) {
                Iterator<Thread> it = favorites.iterator();
                while (true) {
                    if (!it.hasNext()) {
                        break;
                    }
                    Thread item = it.next();
                    if (item != null && this.tid.equals(item.getTid())) {
                        found = true;
                        break;
                    }
                }
            }
            detail.setFavorited(found);
            detail.setFavoritedStateKnown(true);
        } catch (Exception e) {
        }
    }

    private void refreshServerActionState(PostDetail detail) {
        if (detail == null) {
            return;
        }
        applyServerActionState(detail);
        syncFavoriteStateFromServer(detail);
        applyServerActionState(detail);
    }

    /** 绑定点赞人头像行(登录态才有数据;未登录/无数据时隐藏) */
    private void bindLikeUsers(PostDetail postDetail) {
        if (likeUsersAdapter == null) {
            likeUsersAdapter = new LikeUsersAdapter((uid, name) -> {
                Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
                intent.putExtra(ChatActivity.EXTRA_UID, uid);
                intent.putExtra("username", name != null ? name : "");
                startActivity(intent);
            });
            this.binding.rvLikeUsers.setLayoutManager(
                    new androidx.recyclerview.widget.LinearLayoutManager(
                            this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
            this.binding.rvLikeUsers.setAdapter(likeUsersAdapter);
            this.binding.tvLikeMore.setOnClickListener(v -> showLikeUsersSheet(postDetail));
            this.binding.layoutLikeUsers.setOnClickListener(v -> showLikeUsersSheet(postDetail));
        }
        List<String> uids = postDetail.getLikeUserUids();
        List<String> avatars = postDetail.getLikeUserAvatars();
        List<String> names = postDetail.getLikeUserNames();
        if (uids != null && !uids.isEmpty()) {
            likeUsersAdapter.setData(uids, avatars, names);
            this.binding.layoutLikeUsers.setVisibility(0);
        } else {
            this.binding.layoutLikeUsers.setVisibility(8);
        }
    }

    /** 底部弹层:全部点赞人(头像+用户名,可滚动) */
    private void showLikeUsersSheet(PostDetail postDetail) {
        List<String> uids = postDetail.getLikeUserUids();
        List<String> avatars = postDetail.getLikeUserAvatars();
        List<String> names = postDetail.getLikeUserNames();
        if (uids == null || uids.isEmpty()) {
            Toast.makeText(this, "暂无点赞数据", Toast.LENGTH_SHORT).show();
            return;
        }
        // 防空兜底: PostDetail 三字段默认 null(ForumParser 只在非空时才 set),
        // 不兜底时 names.size()/avatars.size() 会 NPE, 表现为"查看全部"闪退回主页。
        if (names == null) names = new java.util.ArrayList<>();
        if (avatars == null) avatars = new java.util.ArrayList<>();
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16);
        root.setPadding(pad, pad, pad, pad);
        // 标题
        TextView title = new TextView(this);
        title.setText("赞过此帖的人 (" + uids.size() + ")");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        title.setPadding(0, 0, 0, dpToPx(12));
        root.addView(title);
        // 滚动容器
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        ScrollView.LayoutParams sp = new ScrollView.LayoutParams(-1, -1);
        scroll.setLayoutParams(sp);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        // build67: 收集每行引用, 供接口回填真实昵称/头像
        java.util.List<TextView> nameViews = new java.util.ArrayList<>();
        java.util.List<com.google.android.material.imageview.ShapeableImageView> imgViews = new java.util.ArrayList<>();
        java.util.List<LinearLayout> rowViews = new java.util.ArrayList<>();
        for (int i = 0; i < uids.size(); i++) {
            final String uid = uids.get(i);
            String name = i < names.size() && names.get(i) != null && !names.get(i).isEmpty()
                    ? names.get(i) : "用户" + uid;
            final String avatar = i < avatars.size() ? avatars.get(i) : null;
            // build65: 每行 = 头像 + 名字(原来只有 TextView,头像数据白拿)
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6));
            com.google.android.material.imageview.ShapeableImageView iv =
                    new com.google.android.material.imageview.ShapeableImageView(this);
            int av = dpToPx(36);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(av, av);
            ivLp.setMarginEnd(dpToPx(10));
            iv.setLayoutParams(ivLp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setImageResource(R.drawable.ic_account);
            if (!TextUtils.isEmpty(avatar)) {
                com.bumptech.glide.Glide.with(this).load(com.solosu.mtforum.util.ForumImageLoader.model(avatar)).circleCrop()
                        .placeholder(new android.graphics.drawable.ColorDrawable(0xFFE0E0E0))
                        .error(new android.graphics.drawable.ColorDrawable(0xFFBDBDBD))
                        .into(iv);
            }
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextSize(15);
            tv.setTextColor(getColor(R.color.text_primary));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(iv);
            row.addView(tv);
            row.setOnClickListener(v -> {
                sheet.dismiss();
                Intent intent = new Intent(this, (Class<?>) UserProfileActivity.class);
                intent.putExtra(ChatActivity.EXTRA_UID, uid);
                intent.putExtra("username", name);
                startActivity(intent);
            });
            list.addView(row);
            rowViews.add(row);
            imgViews.add(iv);
            nameViews.add(tv);
        }
        sheet.setContentView(root);
        sheet.show();
        // build67: 弹窗先用详情页数据即时渲染, 后台拉独立接口补"真实昵称+头像"
        // (接口免登录、一次性返回全部点赞人, 只在用户点"查看全部"时发 1 发)
        final String tidForLikers = this.tid;
        new java.lang.Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<com.solosu.mtforum.ui.detail.LikeUserFetcher.Item> items =
                        com.solosu.mtforum.ui.detail.LikeUserFetcher.fetch(tidForLikers);
                if (items == null || items.isEmpty()) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        for (int k = 0; k < rowViews.size() && k < items.size(); k++) {
                            com.solosu.mtforum.ui.detail.LikeUserFetcher.Item it = items.get(k);
                            nameViews.get(k).setText(it.name);
                            if (!TextUtils.isEmpty(it.avatar)) {
                                com.bumptech.glide.Glide.with(ThreadDetailActivity.this)
                                        .load(com.solosu.mtforum.util.ForumImageLoader.model(it.avatar)).circleCrop()
                                        .placeholder(new android.graphics.drawable.ColorDrawable(0xFFE0E0E0))
                                        .error(new android.graphics.drawable.ColorDrawable(0xFFBDBDBD))
                                        .into(imgViews.get(k));
                            }
                        }
                    }
                });
            }
        }, "like-user-fetch").start();
    }

    /** 当前帖全部图片(正文+附件+隐藏区,按 bindData 收集顺序) */
    private java.util.List<String> currentImageList = new ArrayList<>();

    private void openImagePreview(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        Intent intent = new Intent(this, (Class<?>) ImagePreviewActivity.class);
        // 多图: 传整个图组+当前图位置,可左右翻页
        java.util.List<String> list = new ArrayList<>(this.currentImageList);
        if (list.isEmpty() || !list.contains(url)) {
            list.add(url);
        }
        if (list.size() > 1) {
            intent.putStringArrayListExtra("image_urls", new ArrayList<>(list));
            intent.putExtra("image_index", list.indexOf(url));
        } else {
            intent.putExtra("image_url", url);
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开图片", 0).show();
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }
    /** 添加待发送图片到列表,显示预览,并开始逐张上传 */
    private void addPendingImages(java.util.List<android.net.Uri> uris) {
        for (android.net.Uri uri : uris) {
            if (!pendingImageUris.contains(uri)) {
                pendingImageUris.add(uri);
                uploadPendingImage(uri);
            }
        }
        refreshAllImagePreviews();
    }

    /** 删除待发送图片,移除对应的 [attachimg] 标签 */
    private void removePendingImage(android.net.Uri uri) {
        pendingImageUris.remove(uri);
        String aid = uploadedAidMap.remove(uri);
        if (aid != null) {
            String tag = "[attachimg]" + aid + "[/attachimg]";
            if (binding != null) {
                String cur = binding.etReply.getText().toString();
                cur = cur.replace(tag, "");
                binding.etReply.setText(cur);
                binding.etReply.setSelection(binding.etReply.length());
            }
            synchronized (pendingUploadAids) {
                pendingUploadAids.remove(aid);
            }
        }
        refreshAllImagePreviews();
    }

    /** 刷新所有图片预览(底部回复栏 + 弹窗) */
    private void refreshAllImagePreviews() {
        updateInlineImagePreview();
        if (mBottomSheetDialog != null && mBottomSheetDialog.isShowing()) {
            updateDialogImagePreview();
        }
    }

    /** 更新底部回复栏的图片预览 */
    private void updateInlineImagePreview() {
        if (binding == null) return;
        android.widget.HorizontalScrollView hsv = binding.getRoot().findViewById(R.id.hsv_inline_image_preview);
        android.widget.LinearLayout ll = binding.getRoot().findViewById(R.id.ll_inline_image_preview);
        if (hsv == null || ll == null) return;
        if (pendingImageUris.isEmpty()) {
            hsv.setVisibility(android.view.View.GONE);
            return;
        }
        hsv.setVisibility(android.view.View.VISIBLE);
        ll.removeAllViews();
        for (android.net.Uri uri : pendingImageUris) {
            ll.addView(buildPreviewThumbnail(uri));
        }
    }

    /** 更新底部弹窗的图片预览 */
    private void updateDialogImagePreview() {
        if (mBottomSheetDialog == null) return;
        android.widget.HorizontalScrollView hsv = mBottomSheetDialog.findViewById(R.id.hsv_image_preview);
        android.widget.LinearLayout ll = mBottomSheetDialog.findViewById(R.id.ll_image_preview);
        if (hsv == null || ll == null) return;
        if (pendingImageUris.isEmpty()) {
            hsv.setVisibility(android.view.View.GONE);
            return;
        }
        hsv.setVisibility(android.view.View.VISIBLE);
        ll.removeAllViews();
        for (android.net.Uri uri : pendingImageUris) {
            ll.addView(buildPreviewThumbnail(uri));
        }
    }

    /** 构建单个图片缩略图(含右上角删除按钮) */
    private android.view.View buildPreviewThumbnail(android.net.Uri uri) {
        int size = dpToPx(72);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        frame.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));

        com.google.android.material.imageview.ShapeableImageView iv = new com.google.android.material.imageview.ShapeableImageView(this);
        iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(size - dpToPx(4), size - dpToPx(4)));
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setShapeAppearanceModel(com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, dpToPx(6))
                .build());
        com.bumptech.glide.Glide.with(this).load(uri).centerCrop().into(iv);
        frame.addView(iv);

        int btnSize = dpToPx(22);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(btnSize, btnSize);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        android.widget.ImageButton btnDelete = new android.widget.ImageButton(this);
        btnDelete.setLayoutParams(lp);
        btnDelete.setImageResource(R.drawable.ic_cross);
        btnDelete.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        btnDelete.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        btnDelete.setColorFilter(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        bg.setSize(btnSize, btnSize);
        btnDelete.setBackground(bg);
        btnDelete.setOnClickListener(v -> removePendingImage(uri));
        frame.addView(btnDelete);

        return frame;
    }

    /** 上传单张待发送图片,上传成功后 aid 存入 uploadedAidMap */
    private void uploadPendingImage(android.net.Uri uri) {
        if (imageUploadInProgress) {
            synchronized (imageUploadPendingQueue) {
                if (!imageUploadPendingQueue.contains(uri)) imageUploadPendingQueue.add(uri);
            }
            return;
        }
        imageUploadInProgress = true;
        new java.lang.Thread(() -> {
            try {
                java.io.File file = transcodeReplyImageToJpeg(uri);
                if (file == null || !file.exists() || file.length() == 0) {
                    runOnUiThread(() -> {
                        imageUploadInProgress = false;
                        drainUploadQueue();
                        Toast.makeText(ThreadDetailActivity.this, "图片读取失败,请换一张试试", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                if (!httpClient.isLoggedIn()) {
                    httpClient.syncFromCookieManager();
                    if (!httpClient.isLoggedIn()) {
                        runOnUiThread(() -> { imageUploadInProgress = false; });
                        return;
                    }
                }
                String detailHtml = httpClient.getDesktop(com.solosu.mtforum.network.ForumParser.getThreadDetailUrl(tid));
                String uid = extractUploadValue(detailHtml, "discuz_uid");
                String hash = extractUploadValue(detailHtml, "hash");
                if (!isValidUploadUid(uid)) uid = null;
                if (android.text.TextUtils.isEmpty(uid) || android.text.TextUtils.isEmpty(hash)) {
                    String fid = extractForumFid(detailHtml);
                    if (android.text.TextUtils.isEmpty(fid)) fid = "39";
                    String postHtml = httpClient.getDesktop(com.solosu.mtforum.network.HttpClient.BASE_URL
                            + "forum.php?mod=post&action=newthread&fid=" + fid);
                    if (android.text.TextUtils.isEmpty(uid)) uid = extractUploadValue(postHtml, "discuz_uid");
                    if (!isValidUploadUid(uid)) uid = null;
                    if (android.text.TextUtils.isEmpty(hash)) hash = extractUploadValue(postHtml, "hash");
                }
                if (!isValidUploadUid(uid) || android.text.TextUtils.isEmpty(hash)) {
                    runOnUiThread(() -> {
                        imageUploadInProgress = false;
                        drainUploadQueue();
                        Toast.makeText(ThreadDetailActivity.this, "图片上传授权失败,请重新登录后重试", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                java.util.Map<String, String> extra = new java.util.HashMap<>();
                extra.put("uid", uid);
                extra.put("hash", hash);
                String url = com.solosu.mtforum.network.HttpClient.BASE_URL + "misc.php?mod=swfupload&operation=upload"
                        + "&type=image&inajax=yes&infloat=yes&simple=2";
                String result = httpClient.uploadFileWithUserAgent(url, file, "Filedata", extra,
                        com.solosu.mtforum.network.HttpClient.DESKTOP_USER_AGENT);
                String aid = parseUploadAid(result);
                if (!android.text.TextUtils.isEmpty(aid)) {
                    synchronized (pendingUploadAids) {
                        if (!pendingUploadAids.contains(aid)) pendingUploadAids.add(aid);
                    }
                    uploadedAidMap.put(uri, aid);
                    String tag = "\n[attachimg]" + aid + "[/attachimg]";
                    runOnUiThread(() -> {
                        if (binding != null) {
                            binding.etReply.append(tag);
                        }
                    });
                }
            } catch (Exception e) {
            } finally {
                runOnUiThread(() -> {
                    imageUploadInProgress = false;
                    drainUploadQueue();
                });
            }
        }).start();
    }

    /** build71: 上一张传完后,从排队队列里取下一张继续上传 */
    private void drainUploadQueue() {
        android.net.Uri next;
        synchronized (imageUploadPendingQueue) {
            if (imageUploadInProgress || imageUploadPendingQueue.isEmpty()) return;
            next = imageUploadPendingQueue.remove(0);
        }
        uploadPendingImage(next);
    }

    /** 构建回复文本中的 [attachimg] 标签前缀 */
    private String buildAttachTags() {
        StringBuilder sb = new StringBuilder();
        synchronized (pendingUploadAids) {
            for (String aid : pendingUploadAids) {
                sb.append("[attachimg]").append(aid).append("[/attachimg]\n");
            }
        }
        return sb.toString();
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQUEST_EXPORT_HTML || requestCode == REQUEST_EXPORT_TEXT)
                && resultCode == RESULT_OK && data != null) {
            writePendingExport(data.getData());
            return;
        }
        if (requestCode == REQUEST_EDIT_THREAD && resultCode == RESULT_OK) {
            // build73: 编辑保存成功 -> 重新拉一次帖子
            refreshPostDetail();
            return;
        }
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK && data != null) {
            java.util.List<android.net.Uri> imageUris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    android.net.Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) imageUris.add(uri);
                }
            } else if (data.getData() != null) {
                imageUris.add(data.getData());
            }
            if (!imageUris.isEmpty()) {
                addPendingImages(imageUris);
            }
        }
    }



    /* JADX WARN: Removed duplicated region for block: B:36:0x0054  */
    /* JADX WARN: Removed duplicated region for block: B:43:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
        private String getDisplayNameFromUri(Uri uri) {
        if (uri == null) return null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String path = uri.getLastPathSegment();
        return TextUtils.isEmpty(path) ? null : path;
    }

    private String buildUploadFileName(String sourceName, String mimeType) {
        String name = TextUtils.isEmpty(sourceName) ? "" : sourceName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || !name.matches("(?i).*\\.[a-z0-9]{2,5}$")) {
            String extension = extensionFromMimeType(mimeType);
            return "reply_" + System.currentTimeMillis() + extension;
        }
        return name;
    }

    private String extensionFromMimeType(String mimeType) {
        if ("image/png".equalsIgnoreCase(mimeType)) {
            return ".png";
        }
        if ("image/gif".equalsIgnoreCase(mimeType)) {
            return ".gif";
        }
        if ("image/webp".equalsIgnoreCase(mimeType)) {
            return ".webp";
        }
        if ("image/bmp".equalsIgnoreCase(mimeType)) {
            return ".bmp";
        }
        if ("image/heic".equalsIgnoreCase(mimeType) || "image/heif".equalsIgnoreCase(mimeType)) {
            return ".heic";
        }
        return ".jpg";
    }

        private void uploadAndAttachImage(final Uri imageUri) {
        if (imageUploadInProgress) {
            Toast.makeText(this, "已有图片正在上传,请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingImageUri = imageUri;
        imageUploadInProgress = true;
        Toast.makeText(this, "正在上传图片...", Toast.LENGTH_SHORT).show();
        new java.lang.Thread(() -> {
            lambda$uploadAndAttachImage$51(imageUri);
        }).start();
    }

    private void lambda$uploadAndAttachImage$51(Uri imageUri) {
        File file = null;
        try {
            if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager();
            if (!httpClient.isLoggedIn()) {
                runOnUiThread(() -> promptLogin());
                return;
            }
            file = transcodeReplyImageToJpeg(imageUri);
            if (file == null || !file.exists() || file.length() == 0) {
                showUploadError("无法读取或转换图片文件");
                return;
            }
            String detailHtml = httpClient.getDesktop(ForumParser.getThreadDetailUrl(tid));
            String uid = extractUploadValue(detailHtml, "discuz_uid");
            String hash = extractUploadValue(detailHtml, "hash");
            if (!isValidUploadUid(uid)) uid = null;
            if (TextUtils.isEmpty(uid) || TextUtils.isEmpty(hash)) {
                String fid = extractForumFid(detailHtml);
                if (TextUtils.isEmpty(fid)) fid = "39";
                String postHtml = httpClient.getDesktop(HttpClient.BASE_URL
                        + "forum.php?mod=post&action=newthread&fid=" + fid);
                if (TextUtils.isEmpty(uid)) uid = extractUploadValue(postHtml, "discuz_uid");
                if (!isValidUploadUid(uid)) uid = null;
                if (TextUtils.isEmpty(hash)) hash = extractUploadValue(postHtml, "hash");
            }
            if (!isValidUploadUid(uid) || TextUtils.isEmpty(hash)) {
                showUploadError("获取图片上传授权失败,请重新登录后重试");
                return;
            }
            Map<String, String> extra = new HashMap<>();
            extra.put("uid", uid);
            extra.put("hash", hash);
            String url = HttpClient.BASE_URL + "misc.php?mod=swfupload&operation=upload"
                    + "&type=image&inajax=yes&infloat=yes&simple=2";
            String result = httpClient.uploadFileWithUserAgent(url, file, "Filedata", extra,
                    HttpClient.DESKTOP_USER_AGENT);
            String aid = parseUploadAid(result);
            if (TextUtils.isEmpty(aid)) {
                showUploadError(extractUploadError(result));
                return;
            }
            final String finalAid = aid;
            synchronized (pendingUploadAids) {
                if (!pendingUploadAids.contains(finalAid)) pendingUploadAids.add(finalAid);
            }
            runOnUiThread(() -> {
                binding.etReply.append("\n[attachimg]" + finalAid + "[/attachimg]");
                Toast.makeText(this, "图片已上传,发送评论后才会正式关联", Toast.LENGTH_SHORT).show();
                imageUploadInProgress = false;
            });
        } catch (Exception e) {
            showUploadError("图片上传失败:" + (TextUtils.isEmpty(e.getMessage())
                    ? "网络异常,请稍后重试" : e.getMessage()));
        } finally {
            if (file != null) file.delete();
            runOnUiThread(() -> { imageUploadInProgress = false; });
        }
    }


    private String extractUploadError(String response) {
        if (TextUtils.isEmpty(response)) {
            return "图片上传失败，服务器未返回结果";
        }
        if (ForumParser.isLoginPage(response) || containsAny(response, "请先登录", "登录")) {
            return "登录状态已失效，请重新登录";
        }
        String text = response.replaceAll("(?s)<[^>]+>", " ").trim();
        if (text.startsWith("DISCUZUPLOAD|")) {
            String[] parts = text.split("\\|", -1);
            if (parts.length > 3) {
                String error = parts[parts.length - 1].trim();
                if (!TextUtils.isEmpty(error) && !error.matches("\\d+")) {
                    return "图片上传失败：" + error;
                }
                return "图片上传失败，请检查图片格式、大小和登录状态";
            }
            return "图片上传失败，请检查图片格式、大小和登录状态";
        }
        return "图片上传失败，请检查图片格式、大小和登录状态";
    }

    private void lambda$showUploadError$52(String message) {
        Toast.makeText(this, message, 0).show();
    }

    private void showUploadError(final String message) {
        runOnUiThread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                ThreadDetailActivity.this.lambda$showUploadError$52(message);
            }
        });
    }

    private String extractForumFid(String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        Matcher m = Pattern.compile("(?:forum-|[?&]fid=)(\\d+)").matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String extractUploadValue(String html, String key) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        String quotedKey = Pattern.quote(key);
        Matcher m = Pattern.compile("(?:var\\s+|\\b)" + quotedKey + "\\s*(?:=|:)\\s*['\"]([^'\"]+)['\"]", 2).matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        Matcher m2 = Pattern.compile("\\\"" + quotedKey + "\\\"\\s*:\\s*['\"]([^'\"]+)['\"]", 2).matcher(html);
        if (m2.find()) {
            return m2.group(1);
        }
        Matcher m3 = Pattern.compile("<input[^>]+name\\s*=\\s*['\"]" + quotedKey + "['\"][^>]+value\\s*=\\s*['\"]([^'\"]+)['\"]", 2).matcher(html);
        if (m3.find()) {
            return m3.group(1);
        }
        Matcher m4 = Pattern.compile("<input[^>]+value\\s*=\\s*['\"]([^'\"]+)['\"][^>]+name\\s*=\\s*['\"]" + quotedKey + "['\"]", 2).matcher(html);
        if (m4.find()) {
            return m4.group(1);
        }
        Matcher m5 = Pattern.compile("[?&]" + quotedKey + "=([^&\"'<>\\s]+)", 2).matcher(html);
        if (m5.find()) {
            return m5.group(1);
        }
        return null;
    }

    private String parseUploadAid(String response) {
        if (TextUtils.isEmpty(response)) {
            return null;
        }
        String text = response.trim();
        if (text.isEmpty()) {
            return null;
        }
        String text2 = text.replaceAll("(?s)<[^>]+>", "").trim();
        if (text2.toUpperCase(java.util.Locale.ROOT).startsWith("DISCUZUPLOAD|")) {
            String[] parts = text2.split("\\|", -1);
            if (parts.length >= 4 && "0".equals(parts[2]) && parts[3].matches("\\d+")) {
                return parts[3];
            }
            if (parts.length >= 3 && "0".equals(parts[1]) && parts[2].matches("\\d+")) {
                return parts[2];
            }
            Matcher mPipe = Pattern.compile("(?i)DISCUZUPLOAD\\|[^|]*\\|0\\|([^|]+)").matcher(text2);
            if (mPipe.find() && mPipe.group(1).matches("\\d+")) {
                return mPipe.group(1);
            }
        }
        Matcher m = Pattern.compile("(?:aid|attach)(?:Id)?[\\s:='\"]+(\\d+)", 2).matcher(text2);
        if (m.find()) {
            return m.group(1);
        }
        Matcher jsonLike = Pattern.compile("\\\"(?:aid|attach)(?:Id)?\\\"\\s*:\\s*(\\d+)", 2).matcher(text2);
        if (jsonLike.find()) {
            return jsonLike.group(1);
        }
        return null;
    }

    private File transcodeReplyImageToJpeg(Uri uri) throws Exception {
        if (uri == null) return null;
        com.solosu.mtforum.util.MediaUploadProcessor.Result result =
                com.solosu.mtforum.util.MediaUploadProcessor.prepare(this, uri);
        if (result.compressed) {
            runOnUiThread(() -> Toast.makeText(this,
                    result.videoConverted ? "视频已转为 GIF（最长取前 12 秒）并压缩到 1MB 以下" : "图片已自动压缩到 1MB 以下",
                    Toast.LENGTH_SHORT).show());
        }
        return result.file;
    }

    private File copyUriToTempFile(Uri uri, String fileName) throws Exception {
        File dir = new File(getCacheDir(), "reply_uploads");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, fileName);
        InputStream in = getContentResolver().openInputStream(uri);
        try {
            FileOutputStream out = new FileOutputStream(file);
            if (in != null) {
                try {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        int len = in.read(buffer);
                        if (len == -1) {
                            break;
                        }
                        out.write(buffer, 0, len);
                    }
                    out.close();
                    if (in != null) {
                        in.close();
                    }
                    return file;
                } finally {
                }
            }
            out.close();
            if (in != null) {
                in.close();
                return null;
            }
            return null;
        } catch (Throwable th) {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private void showExportMenu() {
        if (postDetail == null) { Toast.makeText(this, "帖子仍在加载", Toast.LENGTH_SHORT).show(); return; }
        String[] items = {"保存离线页面（后台）", "导出 HTML", "导出 PDF", "导出纯文本", "查看已保存帖子"};
        android.app.Dialog d = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("导出与离线")
                .setItems(items, (dlg, which) -> {
                    if (which == 4) { startActivity(new Intent(this, com.solosu.mtforum.offline.OfflinePostsActivity.class)); return; }
                    askIncludeComments(include -> {
                        if (which == 0) enqueueOfflineSave(include);
                        else if (which == 1) createDocument("text/html", "MTForum-" + tid + ".html", REQUEST_EXPORT_HTML, include, false);
                        else if (which == 2) printPdf(include);
                        else createDocument("text/plain", "MTForum-" + tid + ".txt", REQUEST_EXPORT_TEXT, include, true);
                    });
                }).setNegativeButton("取消", null).show();
        com.solosu.mtforum.ui.widget.DialogHelper.applyToAlertDialog(d, this);
    }

    private void askIncludeComments(java.util.function.Consumer<Boolean> callback) {
        final boolean[] checked = {true};
        android.app.Dialog d = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("内容范围")
                .setSingleChoiceItems(new String[]{"正文 + 评论区", "仅保存正文"}, 0,
                        (x, which) -> checked[0] = which == 0)
                .setPositiveButton("继续", (x, w) -> callback.accept(checked[0]))
                .setNegativeButton("取消", null).show();
        com.solosu.mtforum.ui.widget.DialogHelper.applyToAlertDialog(d, this);
    }

    private void enqueueOfflineSave(boolean comments) {
        androidx.work.Data data = new androidx.work.Data.Builder().putString("tid", tid)
                .putBoolean("comments", comments).build();
        androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(
                com.solosu.mtforum.offline.OfflinePostWorker.class).setInputData(data).build();
        androidx.work.WorkManager manager = androidx.work.WorkManager.getInstance(this);
        manager.enqueueUniqueWork("offline-post-" + tid, androidx.work.ExistingWorkPolicy.REPLACE, request);
        manager.getWorkInfoByIdLiveData(request.getId()).observe(this, info -> {
            if (info == null || !info.getState().isFinished()) return;
            if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED)
                Toast.makeText(this, "帖子已保存，可在“已保存帖子”中离线查看", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "离线保存失败：" + info.getOutputData().getString("error"), Toast.LENGTH_LONG).show();
        });
        Toast.makeText(this, "已转入后台保存，可继续正常使用应用", Toast.LENGTH_LONG).show();
    }

    private String currentExportHtml(boolean comments) {
        return com.solosu.mtforum.offline.OfflinePostStore.buildHtml(postDetail,
                comments ? displayedReplies : java.util.Collections.emptyList(), comments);
    }

    private void createDocument(String mime, String name, int requestCode, boolean comments, boolean plainText) {
        String html = currentExportHtml(comments);
        pendingExportContent = plainText ? org.jsoup.Jsoup.parse(html).text() : html;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType(mime); intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, requestCode);
    }

    private android.webkit.WebView printWebView;
    private void printPdf(boolean comments) {
        printWebView = new android.webkit.WebView(this);
        printWebView.getSettings().setJavaScriptEnabled(false);
        printWebView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(android.webkit.WebView view, String url) {
                android.print.PrintManager manager = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
                if (manager != null) manager.print("MTForum-" + tid,
                        view.createPrintDocumentAdapter("MTForum-" + tid), new android.print.PrintAttributes.Builder().build());
            }
        });
        printWebView.loadDataWithBaseURL(HttpClient.BASE_URL, currentExportHtml(comments), "text/html", "UTF-8", null);
        Toast.makeText(this, "请在打印页面选择“保存为 PDF”及保存位置", Toast.LENGTH_LONG).show();
    }

    private void writePendingExport(android.net.Uri uri) {
        if (uri == null || pendingExportContent == null) return;
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("无法打开目标文件");
            out.write(pendingExportContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
        finally { pendingExportContent = null; }
    }

    private void shareThread() {
        if (this.postDetail == null) {
            return;
        }
        String shareText = this.postDetail.getTitle() + "\n" + HttpClient.BASE_URL + "thread-" + this.tid + "-1-1.html";
        Intent shareIntent = new Intent("android.intent.action.SEND");
        shareIntent.setType(AssetHelper.DEFAULT_MIME_TYPE);
        shareIntent.putExtra("android.intent.extra.TEXT", shareText);
        startActivity(Intent.createChooser(shareIntent, "分享帖子"));
    }

    private void showRewardDialog() {
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        // build74b: 目标可以是主楼(空)或某条评论(评论菜单已排除本人,无需再判)
        final boolean isReplyTarget = !TextUtils.isEmpty(rewardTargetPid);
        if (!isReplyTarget) {
            String currentUid = UserSessionManager.getInstance().getUid(getApplicationContext());
            String authorUid = postDetail != null ? postDetail.getAuthorUid() : null;
            if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid.equals(authorUid)) {
                Toast.makeText(this, "不能给自己打赏", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_reward, null);
        dialog.setContentView(view);
        MaterialCardView cardReward = view.findViewById(R.id.card_reward);
        if (cardReward != null) FrostedGlassHelper.applyToCardViews(cardReward, this);
        dialog.setOnShowListener(d -> {
            View parent = (View) view.getParent();
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior behavior = BottomSheetBehavior.from(parent);
                behavior.setPeekHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.5));
            }
        });
        ImageView ivAvatar = view.findViewById(R.id.iv_reward_author_avatar);
        TextView tvAuthorName = view.findViewById(R.id.tv_reward_author_name);
        TextView tvHint = view.findViewById(R.id.tv_reward_hint);
        final Spinner spinnerAmount = view.findViewById(R.id.spinner_reward_amount);
        final SwitchCompat switchNotify = view.findViewById(R.id.switch_notify_author);
        Button btnSubmit = view.findViewById(R.id.btn_submit_reward);
        String displayName = !TextUtils.isEmpty(rewardTargetName)
                ? rewardTargetName
                : (postDetail != null ? postDetail.getAuthor() : "");
        String displayAvatar = !TextUtils.isEmpty(rewardTargetAvatar)
                ? rewardTargetAvatar
                : (postDetail != null ? postDetail.getAvatarUrl() : null);
        if (!TextUtils.isEmpty(displayName)) {
            tvAuthorName.setText(displayName);
            tvHint.setText("给 " + displayName + " 打赏鼓励吧");
            if (!TextUtils.isEmpty(displayAvatar)) {
                Glide.with(this).load(com.solosu.mtforum.util.ForumImageLoader.model(displayAvatar)).transform(new CircleCrop()).placeholder(R.drawable.ic_account).error(R.drawable.ic_account).into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_account);
            }
        }
        final String[] amounts = {"1", "5", "10", "50", "100"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, amounts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAmount.setAdapter(adapter);
        final EditText etRewardMessage = view.findViewById(R.id.et_reward_message);
        btnSubmit.setOnClickListener(v -> {
            int selectedAmount = Integer.parseInt(amounts[spinnerAmount.getSelectedItemPosition()]);
            boolean notifyAuthor = switchNotify.isChecked();
            String message = etRewardMessage.getText().toString().trim();
            dialog.dismiss();
            performReward(selectedAmount, notifyAuthor, "", message);
        });
        dialog.show();
    }

    // ==================== build73: 编辑 / 举报 / 删除 ====================

    /** 当前登录 uid(带缓存) */
    private String loginUid() {
        if (currentLoginUid == null) {
            currentLoginUid = UserSessionManager.getInstance().getUid(getApplicationContext());
            if (TextUtils.isEmpty(currentLoginUid)) currentLoginUid = "";
        }
        return currentLoginUid;
    }

    /** 是否本人在看自己的帖子 */
    private boolean isOwnThread(PostDetail d) {
        if (d == null) return false;
        String me = loginUid();
        if (TextUtils.isEmpty(me)) return false;
        if (!TextUtils.isEmpty(d.getAuthorUid()) && me.equals(d.getAuthorUid())) return true;
        return false;
    }

    /** 打开编辑页(复用发帖页,编辑模式) */
    private void openEditThread() {
        if (postDetail == null) return;
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        if (TextUtils.isEmpty(postDetail.getPostPid())) {
            Toast.makeText(this, "缺少帖子编号,无法编辑", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, com.solosu.mtforum.ui.post.PostActivity.class);
        it.putExtra("edit_tid", tid);
        it.putExtra("edit_pid", postDetail.getPostPid());
        it.putExtra("edit_fid", postDetail.getForumFid());
        it.putExtra("edit_forum_name", postDetail.getForumName());
        it.putExtra("edit_title", postDetail.getTitle());
        it.putExtra("edit_message", stripContentHtml(postDetail.getContentHtml()));
        startActivityForResult(it, REQUEST_EDIT_THREAD);
    }

    /** Restore editing for a reply owned by the active account. */
    private void openEditReply(ReplyItem item) {
        if (item == null || TextUtils.isEmpty(item.getPid())) {
            Toast.makeText(this, "缺少回复编号，无法编辑", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        String editable = com.solosu.mtforum.util.HtmlToBBCode.convert(item.getContentHtml());
        if (TextUtils.isEmpty(editable)) editable = item.getContentText();
        Intent it = new Intent(this, com.solosu.mtforum.ui.post.PostActivity.class);
        it.putExtra("edit_tid", tid);
        it.putExtra("edit_pid", item.getPid());
        it.putExtra("edit_fid", postDetail == null ? "" : postDetail.getForumFid());
        it.putExtra("edit_forum_name", postDetail == null ? "" : postDetail.getForumName());
        it.putExtra("edit_title", "");
        it.putExtra("edit_message", editable);
        it.putExtra("edit_is_reply", true);
        startActivityForResult(it, REQUEST_EDIT_THREAD);
    }

    /** 正文 HTML -> 可编辑的 BBCode/纯文本(交给 BBCodeUtil 反向处理,失败则剥标签) */
    private String stripContentHtml(String html) {
        if (TextUtils.isEmpty(html)) return "";
        // build73c: BBCodeUtil 没有 html->bbcode 的反解能力,改为剥标签但保留附件标记
        String attachmentMarks = "";
        try {
            java.util.regex.Matcher am = java.util.regex.Pattern.compile(
                    "(?is)\\[attach(?:img)?\\]\\d+\\[/attach(?:img)?\\]").matcher(html);
            StringBuilder amsb = new StringBuilder();
            while (am.find()) amsb.append("\n").append(am.group());
            attachmentMarks = amsb.toString();
        } catch (Exception ignored) {
        }
        String t = html.replaceAll("(?is)<br\\s*/?>", "\n");
        t = t.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        t = t.replaceAll("(?is)<[^>]+>", "");
        t = android.text.Html.fromHtml(t).toString().trim();
        return (t + attachmentMarks).trim();
    }

    /** build77: 举报入口 -> 弹理由输入窗(常用理由下拉 + 补充说明) */
    private void reportPost(final String pid) {
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        final boolean isThread = TextUtils.isEmpty(pid);
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View content = getLayoutInflater().inflate(R.layout.dialog_report, null);
        dialog.setContentView(content);
        android.view.Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0));
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            win.setAttributes(lp);
        }
        TextView tvTarget = content.findViewById(R.id.tv_report_target);
        tvTarget.setText(isThread ? "举报对象：本帖" : "举报对象：该评论");
        final com.google.android.material.chip.ChipGroup cgReason = content.findViewById(R.id.cg_report_reason);
        content.findViewById(R.id.btn_close_report).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        final EditText etMessage = content.findViewById(R.id.et_report_message);
        content.findViewById(R.id.btn_cancel_report).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        content.findViewById(R.id.btn_submit_report).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String reason = "其他";
                int checkedId = cgReason.getCheckedChipId();
                View checkedView = checkedId != View.NO_ID ? cgReason.findViewById(checkedId) : null;
                if (checkedView instanceof com.google.android.material.chip.Chip) {
                    reason = ((com.google.android.material.chip.Chip) checkedView).getText().toString();
                }
                String msg = etMessage.getText() != null ? etMessage.getText().toString().trim() : "";
                dialog.dismiss();
                submitReport(pid, reason, msg);
            }
        });
        dialog.show();
    }

    /** build77: 举报提交(理由/说明由弹窗传入) */
    private void submitReport(final String pid, final String reason, final String userMessage) {
        final String rtype = TextUtils.isEmpty(pid) ? "thread" : "post";
        final String rid = TextUtils.isEmpty(pid) ? tid : pid;
        final String finalPid = pid;
        final String finalReason = TextUtils.isEmpty(reason) ? "其他" : reason;
        final String finalMessage = TextUtils.isEmpty(userMessage) ? "该内容涉嫌违规，请核实处理。" : userMessage;
        new java.lang.Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (TextUtils.isEmpty(tid)) throw new IllegalStateException("缺少帖子编号");
                    String fid = postDetail != null && !TextUtils.isEmpty(postDetail.getForumFid())
                            ? postDetail.getForumFid() : "39";
                    String formUrl = HttpClient.BASE_URL + "misc.php?mod=report&rtype=" + rtype
                            + "&rid=" + rid + "&tid=" + tid + "&fid=" + fid + "&inajax=1&mobile=2";
                    String form = httpClient.get(formUrl);
                    String fh = ForumParser.parseFormhash(form);
                    if (TextUtils.isEmpty(fh) && postDetail != null) fh = postDetail.getFormhash();
                    if (TextUtils.isEmpty(fh)) throw new IllegalStateException("获取操作验证失败");

                    Map<String, String> params = new HashMap<>();
                    params.put("formhash", fh);
                    if ("thread".equals(rtype)) {
                        params.put("tid", tid);
                    } else {
                        params.put("tid", tid);
                        params.put("pid", finalPid);
                    }
                    params.put("fid", fid);
                    params.put("rtype", rtype);
                    params.put("rid", rid);
                    params.put("reportsubmit", "yes");
                    params.put("reason", finalReason);
                    params.put("message", finalMessage);
                    String url = HttpClient.BASE_URL + "misc.php?mod=report&rtype=" + rtype
                            + "&rid=" + rid + "&tid=" + tid + "&fid=" + fid + "&reportsubmit=yes&inajax=1&mobile=2";
                    String resp = httpClient.post(url, params);

                    boolean ok = resp != null && !ForumParser.isLoginPage(resp)
                            && !resp.contains("举报理由") && !resp.contains("action=login");
                    final String msg = ok ? "举报已提交，感谢反馈"
                            : "举报未成功，请稍后重试";
                    runOnUiThread(() -> Toast.makeText(ThreadDetailActivity.this, msg, Toast.LENGTH_SHORT).show());
                } catch (final Exception e) {
                    runOnUiThread(() -> Toast.makeText(ThreadDetailActivity.this,
                            "举报失败：" + (TextUtils.isEmpty(e.getMessage()) ? "网络异常" : e.getMessage()),
                            Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    /** 删除自己的回复 */
    /**
     * 删除自己的回复。
     *
     * <p>build75 重写：前两版都在<b>猜参数</b>（v3.4 发 deletesubmit、v3.5 改 editsubmit），
     * 但 Discuz 的编辑接口除了 delete/editsubmit 还要 <b>fid</b> 和一堆隐藏字段
     * （posttime / wysiwyg / checkbox / 各种 hash），少一个就静默失败。
     *
     * <p>现在不猜了：<b>把编辑页那张表单整个解析出来，原样带上所有字段回提，
     * 只额外塞一个 {@code delete=1}</b>。这样不管 Discuz 版本要什么字段都不会漏。
     */
    private void deleteReply(final ReplyItem item) {
        if (item == null || TextUtils.isEmpty(item.getPid())) return;
        final String pid = item.getPid();
        new java.lang.Thread(() -> {
            String fail = null;
            boolean ok = false;
            try {
                String formUrl = HttpClient.BASE_URL
                        + "forum.php?mod=post&action=edit&tid=" + tid + "&pid=" + pid + "&page=1&mobile=2";
                String page = httpClient.get(formUrl);
                if (TextUtils.isEmpty(page)) throw new IllegalStateException("打不开编辑页");
                if (ForumParser.isLoginPage(page)) throw new IllegalStateException("登录态失效");

                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(page, HttpClient.BASE_URL);
                org.jsoup.nodes.Element form = pickEditForm(doc);
                if (form == null) throw new IllegalStateException("页面里找不到编辑表单");

                Map<String, String> params = new HashMap<>();
                for (org.jsoup.nodes.Element in : form.select("input[name]")) {
                    String type = in.attr("type").toLowerCase();
                    String nm = in.attr("name");
                    if (nm.isEmpty()) continue;
                    // 未勾选的复选/单选不提交，跟浏览器行为一致
                    if (("checkbox".equals(type) || "radio".equals(type)) && !in.hasAttr("checked")) {
                        continue;
                    }
                    if ("submit".equals(type) || "button".equals(type) || "file".equals(type)) continue;
                    params.put(nm, in.attr("value"));
                }
                for (org.jsoup.nodes.Element ta : form.select("textarea[name]")) {
                    params.put(ta.attr("name"), ta.val());
                }
                for (org.jsoup.nodes.Element sel : form.select("select[name]")) {
                    org.jsoup.nodes.Element opt = sel.selectFirst("option[selected]");
                    if (opt == null) opt = sel.selectFirst("option");
                    if (opt != null) params.put(sel.attr("name"), opt.attr("value"));
                }
                // 关键三项
                params.put("delete", "1");
                params.put("editsubmit", "yes");
                if (!params.containsKey("formhash")) {
                    String fh = ForumParser.parseFormhash(page);
                    if (!TextUtils.isEmpty(fh)) params.put("formhash", fh);
                }
                if (!params.containsKey("tid")) params.put("tid", tid);
                if (!params.containsKey("pid")) params.put("pid", pid);
                if (!params.containsKey("fid")) {
                    String fid = extractFid(page);
                    if (!TextUtils.isEmpty(fid)) params.put("fid", fid);
                }

                String action = form.absUrl("action");
                if (TextUtils.isEmpty(action)) {
                    action = HttpClient.BASE_URL
                            + "forum.php?mod=post&action=edit&extra=&editsubmit=yes&mobile=2";
                }
                if (!action.contains("editsubmit")) {
                    action += (action.contains("?") ? "&" : "?") + "editsubmit=yes";
                }
                String resp = httpClient.post(action, params);
                ok = isDeleteSuccess(resp);
                if (!ok) fail = extractServerMessage(resp);

                // 最后的验证：重拉帖子页，看这条 pid 还在不在
                if (!ok) {
                    String check = httpClient.get(
                            ForumParser.getThreadDetailUrl(tid) + "&_del_check=" + System.currentTimeMillis());
                    if (check != null && !check.contains("pid" + pid)
                            && !check.contains("pid=" + pid)) {
                        ok = true;                 // 服务端其实删掉了，只是返回没认出来
                        fail = null;
                    }
                }
            } catch (Exception e) {
                fail = TextUtils.isEmpty(e.getMessage()) ? "网络异常" : e.getMessage();
            }

            final boolean success = ok;
            final String reason = fail;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (success) {
                    Toast.makeText(this, "已删除该回复", Toast.LENGTH_SHORT).show();
                    refreshPostDetail();
                } else {
                    Toast.makeText(this,
                            "删除失败：" + (TextUtils.isEmpty(reason) ? "服务端未接受该操作" : reason),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** 从编辑页里挑出真正那张编辑表单 */
    private static org.jsoup.nodes.Element pickEditForm(org.jsoup.nodes.Document doc) {
        org.jsoup.nodes.Element f = doc.selectFirst("form[action*=editsubmit]");
        if (f != null) return f;
        f = doc.selectFirst("form#postform");
        if (f != null) return f;
        for (org.jsoup.nodes.Element cand : doc.select("form[method=post]")) {
            if (cand.selectFirst("input[name=formhash]") != null
                    && (cand.selectFirst("textarea[name=message]") != null
                        || cand.attr("action").contains("action=edit"))) {
                return cand;
            }
        }
        return null;
    }

    private static String extractFid(String html) {
        if (TextUtils.isEmpty(html)) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("[?&]fid=(\\d+)").matcher(html);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("name=\"fid\"[^>]*value=\"(\\d+)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** 判断删除是否真的成功 */
    private static boolean isDeleteSuccess(String resp) {
        if (TextUtils.isEmpty(resp)) return false;
        String r = resp;
        if (r.contains("删除成功") || r.contains("操作成功")
                || r.contains("succeedhandle_delpost")
                || r.contains("已经被删除")) {
            return true;
        }
        if (r.contains("您没有权限") || r.contains("没有权限")
                || r.contains("抱歉") || r.contains("错误")
                || r.contains("请先登录") || r.contains("超时")) {
            return false;
        }
        // 回到主题页且不再包含该操作表单，通常也算成功
        return r.contains("viewthread") && !r.contains("editsubmit");
    }

    /** 从服务端返回里抠出人话错误信息 */
    private static String extractServerMessage(String resp) {
        if (TextUtils.isEmpty(resp)) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?s)<!\\[CDATA\\[(.*?)\\]\\]>").matcher(resp);
            if (m.find()) {
                String t = m.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
                if (!t.isEmpty()) return t.length() > 80 ? t.substring(0, 80) : t;
            }
            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("(?s)id=\"messagetext\"[^>]*>(.*?)</").matcher(resp);
            if (m2.find()) {
                String t = m2.group(1).replaceAll("<[^>]+>", "").trim();
                if (!t.isEmpty()) return t;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** build73c: 从编辑页 HTML 抽取该楼删除用的校验哈希(找不到返回 null,退回通用 formhash) */
    private String extractDeleteHash(String html, String pid) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(pid)) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "formhash=([0-9a-zA-Z]+)[^\"'<>]{0,200}?pid=" + pid).matcher(html);
            if (m.find()) return m.group(1);
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                    "pid=" + pid + "[^\"'<>]{0,200}?formhash=([0-9a-zA-Z]+)").matcher(html);
            if (m2.find()) return m2.group(1);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 评论长按菜单 */
    /** build75: 评论操作菜单(自定义卡片弹窗:图标+文字行) */
    private void showReplyActionMenu(final ReplyItem item) {
        if (item == null) return;
        final String author = TextUtils.isEmpty(item.getAuthor()) ? "匿名" : item.getAuthor();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View content = getLayoutInflater().inflate(R.layout.dialog_action_menu, null);
        dialog.setContentView(content);
        android.view.Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0));
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            win.setAttributes(lp);
        }
        TextView tvMenuTitle = content.findViewById(R.id.tv_menu_title);
        tvMenuTitle.setText(author + " · 评论操作");
        LinearLayout container = content.findViewById(R.id.ll_menu_items);

        addActionRow(container, R.drawable.ic_reply, "回复", false, new Runnable() {
            @Override public void run() {
                currentReplyPid = item.getPid();
                currentReplyTarget = "回复 " + author + "：";
                // build98: 给快捷回复的 {to} / {floor} 变量备好值
                currentReplyToName = author;
                currentReplyFloor = item.getFloorLabel();
                showReplyBottomSheet("");
            }
        }, dialog);
        if (!isOwnReply(item)) {
            addActionRow(container, R.drawable.ic_reward, "打赏", false, new Runnable() {
                @Override public void run() {
                    rewardTargetPid = item.getPid();
                    rewardTargetName = author;
                    rewardTargetAvatar = item.getAvatarUrl();
                    showRewardDialog();
                }
            }, dialog);
            addActionRow(container, R.drawable.ic_flag, "举报", false, new Runnable() {
                @Override public void run() {
                    reportPost(item.getPid());
                }
            }, dialog);
        } else {
            addActionRow(container, R.drawable.ic_post_text, "编辑回复", false, new Runnable() {
                @Override public void run() {
                    openEditReply(item);
                }
            }, dialog);
            addActionRow(container, R.drawable.ic_delete, "删除", true, new Runnable() {
                @Override public void run() {
                    confirmDeleteReply(item);
                }
            }, dialog);
        }
        // build66: 复制项并进同一个菜单，避免「长按复制」与「长按操作」两套手势打架
        addActionRow(container, R.drawable.ic_copy, "复制内容", false, new Runnable() {
            @Override public void run() {
                copyReplyText(item, false);
            }
        }, dialog);
        addActionRow(container, R.drawable.ic_copy, "复制 BBCode 原文", false, new Runnable() {
            @Override public void run() {
                copyReplyText(item, true);
            }
        }, dialog);
        // build73: 拉黑从列表长按挪到这里（有明确上下文，不容易误触）
        if (!isOwnReply(item)) {
            addActionRow(container, R.drawable.ic_block, "拉黑此人", true, new Runnable() {
                @Override public void run() {
                    confirmBlacklist(item.getAuthorUid(), author);
                }
            }, dialog);
        }
        addActionRow(container, 0, "取消", false, null, dialog);
        dialog.show();
    }

    /** build73: 拉黑确认（从帖子列表长按迁过来） */
    private void confirmBlacklist(final String uid, final String name) {
        if (TextUtils.isEmpty(uid)) {
            Toast.makeText(this, "无法拉黑：缺少作者 UID", Toast.LENGTH_SHORT).show();
            return;
        }
        android.app.Dialog d = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("拉黑作者")
                .setMessage("拉黑「" + name + "」后，TA 的帖子和回帖都会被隐藏。\n"
                        + "可在侧边栏「个人小黑屋」里取消。")
                .setPositiveButton("拉黑", (dlg, w) -> {
                    com.solosu.mtforum.session.BlacklistManager.addLocal(this, uid, name);
                    Toast.makeText(this, "已拉黑「" + name + "」", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
        com.solosu.mtforum.ui.widget.DialogHelper.applyToAlertDialog(d, this);
    }

    /** build66: 复制某条回复；asBBCode=true 还原成 BBCode 便于转发引用 */
    private void copyReplyText(ReplyItem item, boolean asBBCode) {
        if (item == null) return;
        String text;
        if (asBBCode) {
            text = com.solosu.mtforum.util.HtmlToBBCode.convert(item.getContentHtml());
            if (TextUtils.isEmpty(text)) text = item.getContentText();
        } else {
            text = item.getContentText();
            if (TextUtils.isEmpty(text)) {
                text = com.solosu.mtforum.util.HtmlToBBCode.convert(item.getContentHtml());
            }
        }
        copyPlainText(this, text, asBBCode ? "已复制 BBCode 原文" : "已复制回复内容");
    }

    /** build75: 菜单行(图标+文字),action==null 视为取消 */
    private void addActionRow(LinearLayout container, int iconRes, final String label,
                              boolean danger, final Runnable action, final Dialog dialog) {
        View row = getLayoutInflater().inflate(R.layout.item_action_menu, container, false);
        ImageView iv = row.findViewById(R.id.iv_action_icon);
        TextView tv = row.findViewById(R.id.tv_action_label);
        if (iconRes != 0) {
            iv.setImageResource(iconRes);
            if (danger) iv.setColorFilter(0xFFE53935);
        } else {
            iv.setVisibility(View.GONE);
        }
        tv.setText(label);
        if (danger) tv.setTextColor(0xFFE53935);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                if (action != null) action.run();
            }
        });
        container.addView(row);
    }

    private boolean isOwnReply(ReplyItem item) {
        if (item == null) return false;
        String me = loginUid();
        if (TextUtils.isEmpty(me)) return false;
        return me.equals(item.getAuthorUid());
    }

    private void confirmDeleteReply(final ReplyItem item) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除回复")
                .setMessage("确定要删除这条回复吗？删除后无法恢复。")
                .setPositiveButton("删除", (d, w) -> deleteReply(item))
                .setNegativeButton("取消", null)
                .show();
    }

    private void performReward(final int amount, boolean notifyAuthor, final String goodReview, final String message) {
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                try {
                    ThreadDetailActivity.this.lambda$performReward$57(amount, message, goodReview);
                } catch (Exception e) {
                }
            }
        }).start();
    }

    private void lambda$performReward$57(final int amount, String message, String goodReview) throws Exception {
        try {
            if (this.postDetail == null) {
                throw new IllegalStateException("帖子数据为空");
            }
            String pid = TextUtils.isEmpty(rewardTargetPid) ? this.postDetail.getPostPid() : rewardTargetPid;
            // 用掉即清(下次默认主楼)
            rewardTargetPid = "";
            rewardTargetName = "";
            rewardTargetAvatar = "";
            if (TextUtils.isEmpty(pid)) {
                String page = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid));
                PostDetail latest = ForumParser.parseThreadDetail(page);
                if (latest != null) {
                    pid = latest.getPostPid();
                    if (!TextUtils.isEmpty(latest.getFormhash())) {
                        this.postDetail.setFormhash(latest.getFormhash());
                    }
                }
            }
            if (TextUtils.isEmpty(pid)) {
                throw new IllegalStateException("无法获取帖子正文编号");
            }
            String rateForm = "";
            try {
                String rateUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=rate&tid=" + this.tid + "&pid=" + pid + "&showratetip=1&inajax=1&mobile=2";
                rateForm = this.httpClient.get(rateUrl);
            } catch (Exception e) {
            }
            String fh = ForumParser.parseFormhash(rateForm);
            if (TextUtils.isEmpty(fh)) {
                fh = this.postDetail.getFormhash();
            }
            if (TextUtils.isEmpty(fh)) {
                String page2 = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid));
                fh = ForumParser.parseFormhash(page2);
            }
            if (TextUtils.isEmpty(fh)) {
                throw new IllegalStateException("无法获取操作验证");
            }
            Map<String, String> params = new HashMap<>();
            params.put("formhash", fh);
            params.put("tid", this.tid);
            params.put("pid", pid);
            params.put("ratesubmit", "yes");
            params.put("referer", ForumParser.getThreadDetailUrl(this.tid));
            params.put("score1", "1");
            params.put("score2", String.valueOf(amount));
            String reason = message;
            if (TextUtils.isEmpty(reason)) {
                reason = goodReview;
            } else if (!TextUtils.isEmpty(goodReview)) {
                reason = goodReview + "：" + reason;
            }
            if (!TextUtils.isEmpty(reason)) {
                params.put("reason", reason);
            }
            String rewardUrl = "https://bbs.binmt.cc/forum.php?mod=misc&action=rate&tid=" + this.tid + "&pid=" + pid + "&ratesubmit=yes&inajax=1&mobile=2";
            String result = this.httpClient.post(rewardUrl, params);
            final boolean success = isForumActionResponseSuccessful(result);
            final String rewardError = success ? "" : extractRewardError(result);
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$performReward$55(success, amount, rewardError);
                }
            });
        } catch (Exception e2) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$performReward$56();
                }
            });
        }
    }

    private void lambda$performReward$55(boolean success, int amount, String rewardError) {
        String string;
        if (success) {
            Toast.makeText(this, getString(R.string.reward_success, new Object[]{Integer.valueOf(amount)}), 0).show();
            refreshPostDetail();
        } else {
            if (TextUtils.isEmpty(rewardError)) {
                string = getString(R.string.reward_failed);
            } else {
                string = rewardError;
            }
            Toast.makeText(this, string, 1).show();
        }
    }

    private void lambda$performReward$56() {
        Toast.makeText(this, R.string.reward_failed, 0).show();
    }

    private boolean isForumActionResponseSuccessful(String response) {
        if (TextUtils.isEmpty(response) || ForumParser.isLoginPage(response) || isRewardLimitOrFailure(response)) {
            return false;
        }
        String lower = response.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("succeedhandle_rate") || lower.contains("rate_success") || containsAny(response, "评分成功", "打赏成功", "您已成功评分", "评价成功");
    }

    private boolean isRewardLimitOrFailure(String response) {
        return containsAny(response, "24小时", "24 小时", "24hours", "24 hours", "每24小时只能评分一次", "每 24 小时只能评分一次", "24小时内已经评分", "24 小时内已经评分", "您已评价过本主题", "您已经评价过本主题", "您已评分过本主题", "您已经评分过本主题", "已经评分过", "已经评价过", "重复评分", "评分过本主题", "thread_rate_duplicate", "rate_duplicate", "评分失败", "评分范围错误", "thread_rate_range_invalid", "credit_limit_invalid", "积分不足", "余额不足", "提交频率过快", "操作频繁", "暂不支持高级操作", "formhash错误", "非法操作", "没有权限", "请先登录", "未定义操作", "undefined action", "操作失败");
    }

    private String extractRewardError(String response) {
        if (TextUtils.isEmpty(response)) {
            return "打赏失败，服务器未返回结果";
        }
        if (!ForumParser.isLoginPage(response) && !containsAny(response, "请先登录")) {
            if (!containsAny(response, "24小时", "24 小时", "24hours", "24 hours", "每24小时只能评分一次", "每 24 小时只能评分一次", "24小时内已经评分", "24 小时内已经评分", "重复评分", "已评价过本主题", "已评分过本主题", "已经评分过", "已经评价过", "评分过本主题")) {
                if (!containsAny(response, "积分不足", "余额不足", "credit_limit_invalid")) {
                    if (!containsAny(response, "formhash错误", "非法操作")) {
                        if (!containsAny(response, "没有权限", "暂不支持高级操作")) {
                            if (containsAny(response, "提交频率过快", "操作频繁")) {
                                return "操作过于频繁，请稍后再试";
                            }
                            return "打赏失败，请重试";
                        }
                        return "当前账号没有评分权限";
                    }
                    return "验证已失效，请刷新页面后重试";
                }
                return "积分余额不足，无法完成打赏";
            }
            return "24小时内只能对同一帖子评分一次，请稍后再试";
        }
        return "登录状态已失效，请重新登录";
    }

    private void submitKickRequest(final String reason) {
        new java.lang.Thread(new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                try {
                    ThreadDetailActivity.this.lambda$submitKickRequest$60(reason);
                } catch (Exception e) {
                }
            }
        }).start();
    }

    private void lambda$submitKickRequest$60(String reason) {
        try {
            String page = this.httpClient.get(ForumParser.getThreadDetailUrl(this.tid));
            String fh = ForumParser.parseFormhash(page);
            if (TextUtils.isEmpty(fh) && this.postDetail != null) {
                fh = this.postDetail.getFormhash();
            }
            if (TextUtils.isEmpty(fh)) {
                throw new IllegalStateException("无法获取操作验证");
            }
            String kickUrl = "https://bbs.binmt.cc/plugin.php?id=comiis_app&comiis=kick&tid=" + this.tid + "&formhash=" + fh + "&inajax=1&mobile=2";
            Map<String, String> params = new HashMap<>();
            params.put("formhash", fh);
            params.put("tid", this.tid);
            params.put("kick_submit", "yes");
            params.put("inajax", "1");
            if (!TextUtils.isEmpty(reason)) {
                params.put("kick_reason", reason);
            }
            String result = this.httpClient.post(kickUrl, params);
            final boolean success = isForumActionResponseSuccessful(result);
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$submitKickRequest$58(success);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override // java.lang.Runnable
                public final void run() {
                    ThreadDetailActivity.this.lambda$submitKickRequest$59();
                }
            });
        }
    }

    private void lambda$submitKickRequest$58(boolean success) {
        if (success) {
            Toast.makeText(this, R.string.kick_success, 0).show();
            refreshPostDetail();
        } else {
            Toast.makeText(this, R.string.kick_failed, 0).show();
        }
    }

    private void lambda$submitKickRequest$59() {
        Toast.makeText(this, R.string.kick_failed, 0).show();
    }

    private void performKick() {
        if (!httpClient.isLoggedIn()) {
            promptLogin();
            return;
        }
        String currentUid = UserSessionManager.getInstance().getUid(getApplicationContext());
        String authorUid = postDetail != null ? postDetail.getAuthorUid() : null;
        if (!TextUtils.isEmpty(currentUid) && !TextUtils.isEmpty(authorUid) && currentUid.equals(authorUid)) {
            Toast.makeText(this, "不能踢自己的帖子", Toast.LENGTH_SHORT).show();
            return;
        }
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_kick, null);
        dialog.setContentView(view);
        dialog.setCancelable(true);
        MaterialCardView cardKick = view.findViewById(R.id.card_kick);
        if (cardKick != null) FrostedGlassHelper.applyToCardViews(cardKick, this);
        dialog.setOnShowListener(d -> {
            View parent = (View) view.getParent();
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior behavior = BottomSheetBehavior.from(parent);
                behavior.setPeekHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.5));
            }
        });
        final EditText etKickReason = view.findViewById(R.id.et_kick_reason);
        Button btnCloseKick = view.findViewById(R.id.btn_close_kick);
        Button btnSubmitKick = view.findViewById(R.id.btn_submit_kick);
        btnCloseKick.setOnClickListener(v -> dialog.dismiss());
        btnSubmitKick.setOnClickListener(v -> {
            String reason = etKickReason.getText().toString().trim();
            if (TextUtils.isEmpty(reason)) {
                etKickReason.setError("请输入踢帖理由");
            } else {
                dialog.dismiss();
                submitKickRequest(reason);
            }
        });
        dialog.show();
    }

    private void showEmojiPanel() {
        // 自绘制快速回复图标,替代 emoji
        int[] iconIds = {
            R.drawable.ic_smile, R.drawable.ic_heart, R.drawable.ic_thumbs_up,
            R.drawable.ic_fire, R.drawable.ic_star_filled, R.drawable.ic_check,
            R.drawable.ic_cross, R.drawable.ic_lightbulb, R.drawable.ic_pin
        };
        String[] labels = {"微笑", "爱心", "点赞", "火热", "收藏", "同意", "反对", "想法", "置顶"};
        int dp4 = dpToPx(4);
        int dp8 = dpToPx(8);
        RecyclerView rvEmoji = new RecyclerView(this);
        rvEmoji.setLayoutManager(new GridLayoutManager(this, 5));
        rvEmoji.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                LinearLayout ll = new LinearLayout(parent.getContext());
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setGravity(Gravity.CENTER);
                ll.setPadding(dp8, dp8, dp8, dp8);
                int size = dpToPx(48);
                ll.setLayoutParams(new RecyclerView.LayoutParams(size, size));

                ImageView iv = new ImageView(parent.getContext());
                iv.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setId(View.generateViewId());
                ll.addView(iv);

                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(9f);
                tv.setTextColor(0xFF9CA3AF);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setId(View.generateViewId());
                ll.addView(tv);

                return new RecyclerView.ViewHolder(ll) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                LinearLayout ll = (LinearLayout) holder.itemView;
                ImageView iv = (ImageView) ll.getChildAt(0);
                TextView tv = (TextView) ll.getChildAt(1);
                iv.setImageResource(iconIds[position]);
                tv.setText(labels[position]);
                ll.setOnClickListener(v -> {
                    int pos = binding.etReply.getSelectionStart();
                    String text = binding.etReply.getText().toString();
                    String tag = "[" + labels[position] + "]";
                    binding.etReply.setText(text.substring(0, pos) + tag + text.substring(pos));
                    binding.etReply.setSelection(pos + tag.length());
                });
            }

            @Override
            public int getItemCount() {
                return iconIds.length;
            }
        });
        PopupWindow popup = new PopupWindow(rvEmoji, -1, dpToPx(160), true);
        popup.setBackgroundDrawable(new ColorDrawable(getColor(R.color.background_primary)));
        popup.setOutsideTouchable(true);
        popup.setElevation(dpToPx(8));
        popup.showAtLocation(binding.getRoot(), Gravity.BOTTOM, 0, 0);
    }

    /** 内嵌图片 getter：UrlDrawable 占位 + Glide 异步回填（修复旧空壳实现图片不显示问题） */
    private Html.ImageGetter createInlineImageGetter(TextView textView) {
        return source -> {
            String imgUrl = normalizeImageUrl(source);
            if (imgUrl == null) {
                imgUrl = source;
            }
            final boolean inlineIcon = isInlineForumImage(imgUrl);
            final TextView tv = textView;
            final int measured = tv.getWidth() - tv.getCompoundPaddingLeft() - tv.getCompoundPaddingRight();
            final int maxW = Math.max(dpToPx(200), measured > 0 ? measured
                    : getResources().getDisplayMetrics().widthPixels - dpToPx(32));
            final com.solosu.mtforum.util.UrlDrawable placeholder =
                    new com.solosu.mtforum.util.UrlDrawable(tv, dpToPx(120));
            com.bumptech.glide.Glide.with(this)
                    .load(com.solosu.mtforum.util.ForumImageLoader.model(imgUrl))
                    .into(new com.bumptech.glide.request.target.CustomTarget<Drawable>() {
                        @Override
                        public void onResourceReady(Drawable resource,
                                com.bumptech.glide.request.transition.Transition<? super Drawable> transition) {
                            int w = resource.getIntrinsicWidth();
                            int h = resource.getIntrinsicHeight();
                            if (w <= 0) {
                                w = maxW;
                            }
                            if (h <= 0) {
                                h = maxW;
                            }
                            if (inlineIcon) {
                                int size = dpToPx(24);
                                float ratio = (float) size / Math.max(1, Math.max(w, h));
                                w = Math.max(1, (int) (w * ratio));
                                h = Math.max(1, (int) (h * ratio));
                            } else {
                                // Content images always occupy the complete text width. The URL
                                // has already been upgraded from lazy thumbnail to original.
                                h = (int) ((long) h * maxW / Math.max(1, w));
                                w = maxW;
                            }
                            resource.setBounds(0, 0, w, h);
                            placeholder.setReal(resource, tv);
                        }

                        @Override
                        public void onLoadCleared(Drawable ph) {
                        }
                    });
            return placeholder;
        };
    }


    /** 安全解析 HTML,捕获 SpannableStringBuilder 的 PARAGRAPH 边界崩溃 */
    private android.text.Spanned safeFromHtml(String html, android.text.Html.ImageGetter imageGetter, android.text.Html.TagHandler tagHandler) {
        if (android.text.TextUtils.isEmpty(html)) {
            return android.text.SpannedString.valueOf("");
        }
        try {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler);
        } catch (Exception e) {
            android.util.Log.w("ThreadDetail", "Html.fromHtml failed, retrying with COMPACT mode", e);
            try {
                return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, imageGetter, tagHandler);
            } catch (Exception e2) {
                android.util.Log.w("ThreadDetail", "Html.fromHtml COMPACT also failed, stripping paragraph tags", e2);
                // 移除可能导致段落边界问题的标签
                String stripped = html
                        .replaceAll("<div[^>]*>", "")
                        .replaceAll("</div>", "<br>")
                        .replaceAll("<p[^>]*>", "")
                        .replaceAll("</p>", "<br>")
                        .replaceAll("<li[^>]*>", "• ")
                        .replaceAll("</li>", "<br>")
                        .replaceAll("<ol[^>]*>", "")
                        .replaceAll("</ol>", "")
                        .replaceAll("<ul[^>]*>", "")
                        .replaceAll("</ul>", "");
                try {
                    return Html.fromHtml(stripped, Html.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler);
                } catch (Exception e3) {
                    android.util.Log.e("ThreadDetail", "All Html.fromHtml attempts failed", e3);
                    return android.text.SpannedString.valueOf(android.text.Html.fromHtml(android.text.TextUtils.htmlEncode(html)));
                }
            }
        }
    }
    private int dpToPx(int dp) {
        return (int) ((dp * getResources().getDisplayMetrics().density) + 0.5f);
    }


    // ==================== build63: 快捷回复 ====================

    /**
     * 渲染快捷回复短语条。
     * 轻点 = 把短语填进输入框（可继续改），长按 = 直接发送。
     * 「自定义」按钮打开多行编辑，一行一条。
     */
    private void buildQuickReplyChips(final View dialogView,
                                      final com.google.android.material.textfield.TextInputEditText input) {
        final android.widget.LinearLayout container =
                dialogView.findViewById(R.id.ll_quick_reply);
        final View btnEdit = dialogView.findViewById(R.id.btn_quick_reply_edit);
        if (container == null) return;

        renderQuickReplyChips(container, input);

        if (btnEdit != null) {
            com.solosu.mtforum.ui.anim.Motion.pressFeedback(btnEdit, 0.92f);
            // build98: 轻点打开「勾选 / 编辑 / 删除 / 变量」管理面板；
            // 长按仍是老的多行批量编辑（一行一条），习惯了的用户可以继续用。
            btnEdit.setOnClickListener(v -> com.solosu.mtforum.ui.widget.QuickReplyManagerSheet.show(
                    this, () -> renderQuickReplyChips(container, input)));
            btnEdit.setOnLongClickListener(v -> {
                showQuickReplyEditor(container, input);
                return true;
            });
        }
    }

    /**
     * build98: 把帖子的标签渲染成一排可点胶囊（站点上它们就在正文上方那一排）。
     *
     * <p>点一下进 {@code TagActivity} 的那个标签，看同类帖子 —— 用户说的
     * 「标签就是这个」（{@code misc.php?mod=tag&id=384&type=thread&mobile=2}）。
     * 帖子没打标签时整行隐藏，不留空白。
     */
    private void renderThreadTags(PostDetail detail) {
        try {
            final android.widget.LinearLayout box = this.binding.llThreadTags;
            final View scroll = this.binding.hsvThreadTags;
            if (box == null || scroll == null) return;
            box.removeAllViews();
            java.util.List<String> names = detail == null ? null : detail.getTagNames();
            java.util.List<String> ids = detail == null ? null : detail.getTagIds();
            if (names == null || names.isEmpty()) {
                scroll.setVisibility(View.GONE);
                return;
            }
            float d = getResources().getDisplayMetrics().density;
            int padH = (int) (10 * d), padV = (int) (5 * d), gap = (int) (6 * d);
            for (int i = 0; i < names.size(); i++) {
                final String name = names.get(i);
                final String id = (ids != null && i < ids.size()) ? ids.get(i) : "";
                TextView chip = new TextView(this);
                chip.setText("#" + name);
                chip.setTextSize(12f);
                chip.setTextColor(getColor(R.color.primary));
                chip.setBackgroundResource(R.drawable.bg_quick_reply_chip);
                chip.setPadding(padH, padV, padH, padV);
                chip.setMaxLines(1);
                chip.setEllipsize(TextUtils.TruncateAt.END);
                android.widget.LinearLayout.LayoutParams lp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = gap;
                chip.setLayoutParams(lp);
                com.solosu.mtforum.ui.anim.Motion.pressFeedback(chip, 0.92f);
                chip.setOnClickListener(v ->
                        com.solosu.mtforum.ui.tag.TagActivity.openTag(this, id, name));
                box.addView(chip);
            }
            scroll.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
            // 标签只是锦上添花，任何异常都不能影响正文
        }
    }

    /**
     * build98: 快捷回复里的变量值表。
     *
     * <p>用户在短语里写 {@code {author} 的 {title} 写得不错}，插入时换成当前帖子的真实内容。
     * 只放「当前确实知道」的值 —— 不知道的（比如没在回复某人时的 {to}）就不放，
     * 占位符原样留给用户自己删，免得替换成空串让人一头雾水。
     */
    private java.util.Map<String, String> quickReplyVars() {
        java.util.Map<String, String> vars =
                com.solosu.mtforum.session.QuickReplyManager.newVarMap();
        if (this.postDetail != null) {
            if (!TextUtils.isEmpty(this.postDetail.getTitle())) {
                vars.put("{title}", this.postDetail.getTitle());
            }
            if (!TextUtils.isEmpty(this.postDetail.getAuthor())) {
                vars.put("{author}", this.postDetail.getAuthor());
            }
            if (!TextUtils.isEmpty(this.postDetail.getForumName())) {
                vars.put("{forum}", this.postDetail.getForumName());
            }
        }
        if (!TextUtils.isEmpty(this.tid)) {
            vars.put("{tid}", this.tid);
            vars.put("{url}", com.solosu.mtforum.network.HttpClient.BASE_URL
                    + "thread-" + this.tid + "-1-1.html");
        }
        if (!TextUtils.isEmpty(this.currentReplyToName)) {
            vars.put("{to}", this.currentReplyToName);
        }
        if (!TextUtils.isEmpty(this.currentReplyFloor)) {
            vars.put("{floor}", this.currentReplyFloor);
        }
        try {
            com.solosu.mtforum.session.UserSessionManager usm =
                    com.solosu.mtforum.session.UserSessionManager.getInstance();
            String name = usm.getUsername(this);
            if (!TextUtils.isEmpty(name)) vars.put("{username}", name);
            String uid = usm.getUid(this);
            if (!TextUtils.isEmpty(uid)) vars.put("{uid}", uid);
        } catch (Exception ignored) {
        }
        java.text.SimpleDateFormat df =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
        java.text.SimpleDateFormat tf =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
        long now = System.currentTimeMillis();
        vars.put("{date}", df.format(new java.util.Date(now)));
        vars.put("{time}", tf.format(new java.util.Date(now)));
        return vars;
    }

    /** build98: 展开快捷短语里的 {变量} */
    private String expandQuickReply(String phrase) {
        return com.solosu.mtforum.session.QuickReplyManager.applyVars(phrase, quickReplyVars());
    }

    private void renderQuickReplyChips(final android.widget.LinearLayout container,
                                       final com.google.android.material.textfield.TextInputEditText input) {
        container.removeAllViews();
        java.util.List<String> items =
                com.solosu.mtforum.session.QuickReplyManager.list(this);
        float density = getResources().getDisplayMetrics().density;
        int gap = (int) (6 * density);
        int padH = (int) (12 * density);
        int padV = (int) (7 * density);

        for (final String phrase : items) {
            TextView chip = new TextView(this);
            chip.setText(phrase);
            chip.setTextSize(13f);
            chip.setTextColor(getResources().getColor(R.color.text_primary, null));
            chip.setBackgroundResource(R.drawable.bg_quick_reply_chip);
            chip.setPadding(padH, padV, padH, padV);
            chip.setMaxLines(1);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            chip.setLayoutParams(lp);

            com.solosu.mtforum.ui.anim.Motion.pressFeedback(chip, 0.92f);

            // 轻点：填进输入框（变量已展开），光标移到末尾
            chip.setOnClickListener(v -> {
                if (input == null) return;
                String text = expandQuickReply(phrase);
                String cur = input.getText() == null ? "" : input.getText().toString();
                String next = android.text.TextUtils.isEmpty(cur) ? text : cur + text;
                input.setText(next);
                input.setSelection(next.length());
            });
            // 长按：填进去并直接发送（同样先展开变量）
            chip.setOnLongClickListener(v -> {
                if (input == null) return false;
                String text = expandQuickReply(phrase);
                input.setText(text);
                input.setSelection(text.length());
                View send = ((View) container.getParent().getParent())
                        .findViewById(R.id.btn_send_reply);
                if (send != null) send.performClick();
                return true;
            });
            container.addView(chip);
        }
    }

    /** 多行编辑器：一行一条短语 */
    private void showQuickReplyEditor(final android.widget.LinearLayout container,
                                      final com.google.android.material.textfield.TextInputEditText input) {
        final android.widget.EditText et = new android.widget.EditText(this);
        // build98: 这里要读「全部」条目（allTexts），不能用 list()（那只是勾选过的）。
        // 用 list() 的话，用户只要用老编辑器保存一次，没勾选的条目就被静默删掉了。
        et.setText(com.solosu.mtforum.session.QuickReplyManager.toLines(
                com.solosu.mtforum.session.QuickReplyManager.allTexts(this)));
        et.setTextSize(14f);
        et.setTextColor(getResources().getColor(R.color.text_primary, null));
        et.setGravity(android.view.Gravity.TOP);
        et.setMinLines(6);
        et.setMaxLines(12);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.Dialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自定义快捷回复")
                .setMessage("一行一条，最多 "
                        + com.solosu.mtforum.session.QuickReplyManager.MAX_COUNT + " 条。\n"
                        + "轻点短语填入输入框，长按直接发送。")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    com.solosu.mtforum.session.QuickReplyManager.save(this,
                            com.solosu.mtforum.session.QuickReplyManager.parseLines(
                                    et.getText().toString()));
                    renderQuickReplyChips(container, input);
                    android.widget.Toast.makeText(this, "已保存",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    com.solosu.mtforum.session.QuickReplyManager.resetToDefault(this);
                    renderQuickReplyChips(container, input);
                    android.widget.Toast.makeText(this, "已恢复默认短语",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    /** build65: 主楼原始 HTML，供「复制 BBCode」还原用 */
    private String currentContentHtmlForCopy;

    // ==================== build64: 正文代码块 + 复制 ====================

    /** 渲染主楼代码块卡片 */
    private void renderMainCodeBlocks(com.solosu.mtforum.util.BBCodeUtil.Extracted extracted) {
        android.widget.LinearLayout container = this.binding.llCodeBlocksMain;
        if (container == null) return;
        container.removeAllViews();
        if (extracted == null || !extracted.hasBlocks()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        int gap = dpToPx(8);
        for (int i = 0; i < extracted.blocks.size(); i++) {
            com.solosu.mtforum.util.BBCodeUtil.CodeBlock b = extracted.blocks.get(i);
            com.solosu.mtforum.ui.widget.CodeBlockView view =
                    new com.solosu.mtforum.ui.widget.CodeBlockView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gap;
            view.setLayoutParams(lp);
            view.bind(b.lang, b.code);
            container.addView(view);
        }
    }

    /**
     * 绑定「复制正文」按钮。
     * 正文是按钮、评论区是长按 —— 两种交互分工来自用户要求。
     */
    private void setupCopyContentButton() {
        final View btn = this.binding.btnCopyContent;
        if (btn == null) return;
        com.solosu.mtforum.ui.anim.Motion.pressFeedback(btn, 0.92f);
        btn.setOnClickListener(v -> copyMainPost(true));
        // build98: 长按不再只是「复制纯文本」，改成一个小面板：纯文本正文 / 逐段复制代码。
        // 帖子里的代码块在 WebView 模式下是站点原样渲染的，万一注入的按钮点不到，
        // 这里就是保底入口。
        btn.setOnLongClickListener(v -> {
            showCopyOptions();
            return true;
        });
    }

    /**
     * build98: 「复制正文」长按面板 —— 纯文本 + 逐段代码。
     */
    private void showCopyOptions() {
        final java.util.List<com.solosu.mtforum.util.BBCodeUtil.CodeBlock> blocks =
                collectPostCodeBlocks();
        java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add("复制纯文本正文");
        for (int i = 0; i < blocks.size(); i++) {
            com.solosu.mtforum.util.BBCodeUtil.CodeBlock b = blocks.get(i);
            String lang = TextUtils.isEmpty(b.lang) ? "代码" : b.lang;
            int lines = b.code.isEmpty() ? 0 : b.code.split("\n", -1).length;
            labels.add("复制第 " + (i + 1) + " 段代码（" + lang + "，" + lines + " 行）");
        }
        if (blocks.isEmpty()) {
            // 没有代码块就没必要弹面板了，保持老行为
            copyMainPost(false);
            return;
        }
        android.app.Dialog d = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("复制正文")
                .setItems(labels.toArray(new String[0]), (dlg, which) -> {
                    if (which == 0) {
                        copyMainPost(false);
                    } else {
                        com.solosu.mtforum.util.BBCodeUtil.CodeBlock b = blocks.get(which - 1);
                        copyPlainText(this, b.code, "已复制第 " + which + " 段代码");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(d, this);
    }

    /**
     * build98: 把当前帖子里的代码块都找出来。
     *
     * <p>两条来源都算上：正文渲染成卡片时（老路径）从卡片里取原始代码；
     * WebView 原帖渲染时卡片是空的，就从抓到的 HTML 里重新抽一遍
     * （{@code extractCodeBlocks} 认得 pre / div.comiis_blockcode / ol&gt;li 三种结构）。
     */
    private java.util.List<com.solosu.mtforum.util.BBCodeUtil.CodeBlock> collectPostCodeBlocks() {
        java.util.List<com.solosu.mtforum.util.BBCodeUtil.CodeBlock> out = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        android.widget.LinearLayout container = this.binding.llCodeBlocksMain;
        if (container != null) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof com.solosu.mtforum.ui.widget.CodeBlockView) {
                    String code = ((com.solosu.mtforum.ui.widget.CodeBlockView) child).getCode();
                    if (!TextUtils.isEmpty(code) && seen.add(code)) {
                        out.add(newCodeBlock(((com.solosu.mtforum.ui.widget.CodeBlockView) child)
                                .getLang(), code));
                    }
                }
            }
        }
        String html = !TextUtils.isEmpty(this.currentContentHtmlForCopy)
                ? this.currentContentHtmlForCopy
                : (this.postDetail != null ? this.postDetail.getContentHtml() : null);
        if (!TextUtils.isEmpty(html)) {
            com.solosu.mtforum.util.BBCodeUtil.Extracted ex =
                    com.solosu.mtforum.util.BBCodeUtil.extractCodeBlocks(html);
            if (ex.hasBlocks()) {
                for (com.solosu.mtforum.util.BBCodeUtil.CodeBlock b : ex.blocks) {
                    if (!TextUtils.isEmpty(b.code) && seen.add(b.code)) out.add(b);
                }
            }
        }
        return out;
    }

    /** CodeBlock 的构造器是包内可见，这里借道同包工具类造一个（只用于展示语言/行数） */
    private static com.solosu.mtforum.util.BBCodeUtil.CodeBlock newCodeBlock(String lang,
                                                                             String code) {
        return com.solosu.mtforum.util.BBCodeUtil.makeCodeBlock(lang, code);
    }

    /**
     * 复制主楼。
     *
     * @param asBBCode true = 复制 BBCode 原文（可直接转发/引用），false = 复制可见纯文本
     */
    private void copyMainPost(boolean asBBCode) {
        if (asBBCode && !TextUtils.isEmpty(this.currentContentHtmlForCopy)) {
            String bb = com.solosu.mtforum.util.HtmlToBBCode.convert(this.currentContentHtmlForCopy);
            if (!TextUtils.isEmpty(bb)) {
                copyPlainText(this, bb, "已复制 BBCode 原文（长按按钮可复制纯文本）");
                return;
            }
        }
        // 纯文本：正文 + 代码块。build98: 代码块统一走 collectPostCodeBlocks() ——
        // WebView 原帖渲染时卡片是空的，正文里那段代码以前会漏掉。
        StringBuilder sb = new StringBuilder();
        CharSequence body = this.binding.tvContent.getText();
        if (body != null && this.binding.tvContent.getVisibility() == View.VISIBLE) {
            sb.append(body.toString().trim());
        }
        for (com.solosu.mtforum.util.BBCodeUtil.CodeBlock b : collectPostCodeBlocks()) {
            if (TextUtils.isEmpty(b.code)) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(b.code);
        }
        copyPlainText(this, sb.toString(), asBBCode ? "已复制正文" : "已复制纯文本");
    }

    /** 统一的剪贴板写入 */
    static void copyPlainText(android.content.Context ctx, String text, String toast) {
        if (ctx == null || TextUtils.isEmpty(text)) return;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("mtforum", text));
            android.widget.Toast.makeText(ctx, toast, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(ctx, "复制失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * build70: 让正文里的内联图片可点击放大。
     *
     * <p>v2.9 用的是「叠一层 ClickableSpan」，实测无效 —— 因为紧随其后的
     * {@code setupClickableLinks()} 会把 {@code textIsSelectable} 设为 true，
     * TextView 进入文本选择模式后，单击会被 Editor 拿去放光标，
     * ClickableSpan 根本收不到。
     *
     * <p>改成直接在 TextView 上做<b>触摸命中测试</b>：按下时算出触点落在第几个字符上，
     * 看该位置有没有 ImageSpan，有就拦下这一次触摸并打开全屏预览。
     * 完全不依赖 MovementMethod，也不影响文本选择和链接点击。
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void attachInlineImageClicks(final TextView tv) {
        if (tv == null) return;
        tv.setOnTouchListener(new View.OnTouchListener() {
            private String pendingUrl;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    pendingUrl = imageUrlAt(tv, event);
                    return pendingUrl != null;      // 命中图片才拦截，否则放行给选择/链接
                }
                if (action == android.view.MotionEvent.ACTION_UP && pendingUrl != null) {
                    String url = pendingUrl;
                    pendingUrl = null;
                    openImagePreview(url);
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_CANCEL) {
                    pendingUrl = null;
                }
                return false;
            }
        });
    }

    /** 触点落在哪张内联图片上；没命中返回 null */
    private String imageUrlAt(TextView tv, android.view.MotionEvent event) {
        CharSequence cs = tv.getText();
        if (!(cs instanceof android.text.Spanned)) return null;
        android.text.Layout layout = tv.getLayout();
        if (layout == null) return null;

        int x = (int) event.getX() - tv.getTotalPaddingLeft() + tv.getScrollX();
        int y = (int) event.getY() - tv.getTotalPaddingTop() + tv.getScrollY();
        int line = layout.getLineForVertical(y);
        int offset = layout.getOffsetForHorizontal(line, x);

        android.text.Spanned sp = (android.text.Spanned) cs;
        android.text.style.ImageSpan[] spans =
                sp.getSpans(offset, offset, android.text.style.ImageSpan.class);
        if (spans == null || spans.length == 0) {
            // 触点可能落在图片字符的右半边，向前退一格再试
            if (offset > 0) {
                spans = sp.getSpans(offset - 1, offset - 1, android.text.style.ImageSpan.class);
            }
            if (spans == null || spans.length == 0) return null;
        }
        String url = spans[0].getSource();
        if (TextUtils.isEmpty(url)) return null;
        // 表情和站点静态图不放大
        if (url.contains("/smiley/") || url.contains("/static/image/")) return null;
        return url;
    }

    // ==================== build70: BBCode 工具条与预览 ====================

    /** build71: BBCode 工具条与实时预览，实现见 ui.widget.BBCodeEditor */
    private void setupBBCodeTools(final View dialogView,
                                  final com.google.android.material.textfield.TextInputEditText input) {
        final android.widget.LinearLayout bar = dialogView.findViewById(R.id.ll_bbcode_tools);
        final View btnPreview = dialogView.findViewById(R.id.btn_preview_bbcode);
        final View btnMarkdown = dialogView.findViewById(R.id.btn_markdown_import);
        final View previewBox = dialogView.findViewById(R.id.sv_bbcode_preview);
        final TextView previewText = dialogView.findViewById(R.id.tv_bbcode_preview);
        if (bar == null || input == null) return;

        com.solosu.mtforum.ui.widget.BBCodeEditor.buildToolbar(this, bar, input, true);
        if (btnMarkdown != null) {
            btnMarkdown.setOnClickListener(v -> com.solosu.mtforum.util.MarkdownImportHelper
                    .show(this, input, null));
        }

        if (previewBox != null && previewText != null) {
            // build71: 改成实时预览，默认就展开 —— 之前要来回点「预览/编辑」切换，
            // 改一个字得切两次，很难用。
            android.view.ViewGroup.LayoutParams lp = previewBox.getLayoutParams();
            lp.height = (int) (150 * getResources().getDisplayMetrics().density);
            previewBox.setLayoutParams(lp);
            previewBox.setVisibility(View.VISIBLE);
            com.solosu.mtforum.ui.widget.BBCodeEditor.bindLivePreview(this, input, previewText);

            if (btnPreview != null) {
                com.solosu.mtforum.ui.anim.Motion.pressFeedback(btnPreview, 0.94f);
                ((com.google.android.material.button.MaterialButton) btnPreview).setText("收起预览");
                btnPreview.setOnClickListener(v -> {
                    boolean showing = previewBox.getVisibility() == View.VISIBLE;
                    previewBox.setVisibility(showing ? View.GONE : View.VISIBLE);
                    ((com.google.android.material.button.MaterialButton) btnPreview)
                            .setText(showing ? "显示预览" : "收起预览");
                });
            }
        }
    }


    // ==================== build72: 附件 ====================

    /** 渲染附件列表。只展示，不下载 —— 下载要点按钮并二次确认 */
    private void renderAttachments(String contentHtml) {
        android.widget.LinearLayout box = this.binding.llAttachments;
        if (box == null) return;
        box.removeAllViews();
        java.util.List<com.solosu.mtforum.util.AttachmentParser.Attachment> list =
                com.solosu.mtforum.util.AttachmentParser.parse(contentHtml);
        if (list.isEmpty()) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        float d = getResources().getDisplayMetrics().density;

        TextView title = new TextView(this);
        title.setText("附件（" + list.size() + "）");
        title.setTextSize(12f);
        title.setTextColor(getColor(R.color.text_hint));
        title.setPadding(0, 0, 0, (int) (6 * d));
        box.addView(title);

        for (final com.solosu.mtforum.util.AttachmentParser.Attachment a : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_code_block);
            row.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = (int) (6 * d);
            row.setLayoutParams(rlp);

            ImageView icon = new ImageView(this);
            icon.setLayoutParams(new LinearLayout.LayoutParams((int) (22 * d), (int) (22 * d)));
            icon.setImageResource(a.isImage() ? R.drawable.ic_image : R.drawable.ic_attach);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.primary)));
            row.addView(icon);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = (int) (10 * d);
            col.setLayoutParams(clp);

            TextView name = new TextView(this);
            name.setText(a.name);
            name.setTextSize(14f);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            name.setTextColor(getColor(R.color.text_primary));
            col.addView(name);

            String sub = a.subtitle();
            if (a.isApk()) {
                sub = TextUtils.isEmpty(sub) ? "APK 安装包" : "APK 安装包 · " + sub;
            }
            if (!TextUtils.isEmpty(sub)) {
                TextView meta = new TextView(this);
                meta.setText(sub);
                meta.setTextSize(11f);
                meta.setTextColor(getColor(R.color.text_hint));
                col.addView(meta);
            }
            row.addView(col);

            TextView btn = new TextView(this);
            btn.setText(a.isImage() ? "查看" : "下载");
            btn.setTextSize(12f);
            btn.setTextColor(getColor(R.color.primary));
            btn.setBackgroundResource(R.drawable.bg_pill_soft);
            btn.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
            com.solosu.mtforum.ui.anim.Motion.pressFeedback(btn, 0.92f);
            btn.setOnClickListener(v -> {
                if (a.isImage() && !TextUtils.isEmpty(a.imageUrl)) {
                    openImagePreview(a.imageUrl);
                } else {
                    confirmDownloadAttachment(a);
                }
            });
            row.addView(btn);
            box.addView(row);
        }
    }

    /**
     * 下载附件前二次确认。论坛附件可能扣金币/积分，只有点右侧「下载」后才会发起请求。
     * 弹窗同时展示将保存的文件名/扩展名，并提供左侧复制直链操作。
     */
    private void confirmDownloadAttachment(
            final com.solosu.mtforum.util.AttachmentParser.Attachment a) {
        final String fileName = a.downloadFileName();
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(18), dpToPx(10), dpToPx(18), dpToPx(22));
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        View handle = new View(this);
        android.graphics.drawable.GradientDrawable handleBg = new android.graphics.drawable.GradientDrawable();
        handleBg.setColor(0x55888888);
        handleBg.setCornerRadius(dpToPx(4));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dpToPx(38), dpToPx(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dpToPx(14);
        root.addView(handle, handleLp);

        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
        card.setRadius(dpToPx(22));
        card.setCardElevation(0f);
        card.setUseCompatPadding(false);
        LinearLayout cardContent = new LinearLayout(this);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        cardContent.setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18));
        card.addView(cardContent);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout iconCell = new LinearLayout(this);
        iconCell.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
        iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        iconBg.setColor(0x222F80ED);
        iconCell.setBackground(iconBg);
        ImageView fileIcon = new ImageView(this);
        fileIcon.setImageResource(R.drawable.ic_attach);
        fileIcon.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
        iconCell.addView(fileIcon, new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)));
        heading.addView(iconCell, new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)));

        LinearLayout headingText = new LinearLayout(this);
        headingText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headingTextLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headingTextLp.leftMargin = dpToPx(12);
        headingText.setLayoutParams(headingTextLp);
        TextView title = attachmentDialogText("下载前确认", 18f, getColor(R.color.text_primary), true);
        headingText.addView(title);
        TextView subtitle = attachmentDialogText(
                a.isApk() ? "APK 安装包" : "论坛附件资源", 12f, getColor(R.color.text_hint), false);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dpToPx(3);
        headingText.addView(subtitle, subtitleLp);
        heading.addView(headingText);
        cardContent.addView(heading);

        TextView filenameLabel = attachmentDialogText("文件名", 12f, getColor(R.color.text_hint), false);
        LinearLayout.LayoutParams filenameLabelLp = new LinearLayout.LayoutParams(-1, -2);
        filenameLabelLp.topMargin = dpToPx(18);
        cardContent.addView(filenameLabel, filenameLabelLp);

        TextView filename = attachmentDialogText(fileName, 15f, getColor(R.color.text_primary), true);
        filename.setMaxLines(2);
        filename.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        android.graphics.drawable.GradientDrawable filenameBg = new android.graphics.drawable.GradientDrawable();
        filenameBg.setColor(getColor(R.color.background_secondary));
        filenameBg.setCornerRadius(dpToPx(14));
        filename.setBackground(filenameBg);
        filename.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        LinearLayout.LayoutParams filenameLp = new LinearLayout.LayoutParams(-1, -2);
        filenameLp.topMargin = dpToPx(7);
        cardContent.addView(filename, filenameLp);

        boolean browserMode = com.solosu.mtforum.ui.DownloadPreferences.getMode(this)
                == com.solosu.mtforum.ui.DownloadPreferences.MODE_BROWSER;
        TextView destination = attachmentDialogText(
                browserMode ? "系统浏览器将接管下载，最终文件名由浏览器/服务器响应决定"
                        : "保存到  Download/" + fileName,
                12f, getColor(R.color.text_hint), false);
        destination.setMaxLines(2);
        LinearLayout.LayoutParams destinationLp = new LinearLayout.LayoutParams(-1, -2);
        destinationLp.topMargin = dpToPx(9);
        cardContent.addView(destination, destinationLp);

        String metaText = a.subtitle();
        if (!TextUtils.isEmpty(metaText)) {
            TextView meta = attachmentDialogText(metaText, 11f, getColor(R.color.text_hint), false);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
            metaLp.topMargin = dpToPx(5);
            cardContent.addView(meta, metaLp);
        }

        TextView warning = attachmentDialogText(
                "部分论坛附件可能需要登录，并可能扣除金币/积分。复制直链不会开始下载。",
                11f, getColor(R.color.text_hint), false);
        LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, -2);
        warningLp.topMargin = dpToPx(14);
        cardContent.addView(warning, warningLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = dpToPx(18);
        cardContent.addView(actions, actionsLp);

        MaterialButton copy = new MaterialButton(this);
        copy.setText("复制直链");
        copy.setTextColor(getColor(R.color.primary));
        copy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.background_secondary)));
        copy.setStrokeColor(android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
        copy.setStrokeWidth(dpToPx(1));
        copy.setCornerRadius(dpToPx(15));
        copy.setInsetTop(0);
        copy.setInsetBottom(0);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dpToPx(48), 1f);
        actions.addView(copy, copyLp);

        MaterialButton download = new MaterialButton(this);
        download.setText("下载");
        download.setTextColor(android.graphics.Color.WHITE);
        download.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
        download.setCornerRadius(dpToPx(15));
        download.setInsetTop(0);
        download.setInsetBottom(0);
        LinearLayout.LayoutParams downloadLp = new LinearLayout.LayoutParams(0, dpToPx(48), 1f);
        downloadLp.leftMargin = dpToPx(10);
        actions.addView(download, downloadLp);

        root.addView(card, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        com.solosu.mtforum.ui.widget.DialogHelper.applyToBottomSheet(dialog, root, this);

        copy.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null) throw new IllegalStateException("剪贴板不可用");
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("附件直链", a.downloadUrl()));
                Toast.makeText(this, "附件直链已复制", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (Exception e) {
                Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            }
        });
        download.setOnClickListener(v -> {
            dialog.dismiss();
            startAttachmentDownload(a, fileName);
        });
        dialog.show();
    }

    private TextView attachmentDialogText(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    /** 用系统下载器下载，带上当前登录 Cookie */
    private void startAttachmentDownload(
            com.solosu.mtforum.util.AttachmentParser.Attachment a, String fileName) {
        if (com.solosu.mtforum.ui.DownloadPreferences.getMode(this)
                == com.solosu.mtforum.ui.DownloadPreferences.MODE_BROWSER) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(a.downloadUrl())));
            } catch (Exception e) {
                Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("无下载服务");
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(
                            android.net.Uri.parse(a.downloadUrl()));
            String cookie = this.httpClient.getCookieHeader();
            if (!TextUtils.isEmpty(cookie)) req.addRequestHeader("Cookie", cookie);
            req.addRequestHeader("User-Agent", com.solosu.mtforum.network.HttpClient.USER_AGENT);
            req.addRequestHeader("Referer",
                    com.solosu.mtforum.network.HttpClient.BASE_URL);
            String safeFileName = com.solosu.mtforum.util.AttachmentFileName.sanitize(fileName);
            req.setTitle(safeFileName);
            req.setDescription(a.isApk() ? "MT 论坛 APK 安装包" : "MT 论坛附件");
            if (a.isApk()) req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, safeFileName);
            dm.enqueue(req);
            Toast.makeText(this, "已加入下载队列：" + safeFileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // 退回浏览器，至少不至于卡死
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(a.downloadUrl())));
            } catch (Exception ignored) {
                Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * build73: 把正文里 img 的 src 从缩略图换成原图。
     *
     * <p>Discuz 的图片附件是 {@code <img src="缩略图" file="原图" zoomfile="原图">}，
     * 只用 src 会拿到小图；原位全宽显示时必须换成 file / zoomfile。
     */
    /**
     * 挑真实图片地址。
     *
     * <p>build76 关键补漏：本论坛用的是 <b>Comiis 模板</b>，懒加载属性叫
     * {@code comiis_loadimages}，v3.6 的候选列表里<b>恰好漏了这一个</b>。
     * 于是这类图的 src 一直停在占位的 {@code none.gif} 上 ——
     * 「选择显示图片到原处时部分帖子图片消失」就是这么来的。
     *
     * <p>顺序和 {@code extractAndSeparateImages} 保持一致，免得两条路径行为不同。
     */
    /**
     * build87: 渲染帖子底部的横滑图廊。
     *
     * <p>从 {@code bindData} 里抽出来，是为了让「正文为空」的分支也能上图廊 ——
     * 站点对游客不下发附件 {@code <img>} 时正文解析结果是空的，以前走的是
     * {@code cardImageGallery.setVisibility(GONE)}，列表页兜底图永远出不来。
     *
     * @param urls 要显示的图片地址；为空则隐藏图廊
     */
    // ==================================================================
    // build92: 原帖正文 WebView 渲染
    //
    // 以前把帖子 HTML 拆成 TextView 重新排版。站点模板（克米 mobile）里
    // 「插进正文的图」的位置信息只在原始 HTML 中，解析成纯文本后必然丢失，
    // 图片只能堆到正文底部 —— 用户连续多版反馈同一个问题。
    //
    // 现在默认直接把站点下发的 HTML 原样交给 WebView 渲染，排版交给站点
    // 自己的模板，图就落在作者插入的位置。
    // ==================================================================

    /** build92: 站点自己的排版样式，套在正文外面 */
    private static final String WEB_CONTENT_CSS =
            "<style>"
            + "html,body{margin:0;padding:0;background:transparent;}"
            + "body{font-size:15px;line-height:1.7;color:#1a1a1a;"
            + "word-wrap:break-word;overflow-wrap:break-word;-webkit-text-size-adjust:100%;}"
            + "*{box-sizing:border-box;}"
            // build95: width:auto !important 是关键。站点 mobile 模板（以及作者
            // 粘贴进来的内容）经常给 img 写死 style="width:120px" 或用 class 控制
            // 成缩略图尺寸 —— 只限 max-width 的话那张 width 仍然生效，图就一直
            // 是缩略图大小。用户报的就是「显示的是缩略图，不能像正常帖子那样显示」。
            // 这里把 width/height/min-width 全部打回 auto，再由 max-width:100%
            // 撑到容器宽，高度按图片真实比例走，绝不变形、也不留白。
            + "img{width:auto !important;min-width:0 !important;max-width:100% !important;"
            + "height:auto !important;max-height:none !important;display:block;"
            + "margin:8px auto;border-radius:6px;cursor:pointer;}"
            // 表情 GIF 属于行内小图，不走正文大图的 block/满宽样式，统一限为 24px。
            + "img[src*='/static/image/smiley/'],img[src*='/smiley/'],"
            + "img[src*='/emoticon/'],img[src*='static/image/common/smiley']{"
            + "width:24px !important;height:24px !important;min-width:24px !important;"
            + "max-width:24px !important;max-height:24px !important;display:inline-block !important;"
            + "vertical-align:middle !important;margin:0 2px !important;"
            + "border-radius:0 !important;object-fit:contain;}"
            // build95: 站点偶尔用 <a> 包图做「点击看大图」，把链接靶区也放开
            + "a:has(img){display:block;}"
            + "a{color:#337ecc;text-decoration:none;word-break:break-all;}"
            + "a:active{opacity:.6;}"
            // build107: 自动识别出来的链接加下划线。站点自己的 <a> 通常带样式或上下文，
            // 而我们补的那批是「作者直接把网址贴进正文」的纯文本，不给点视觉提示，
            // 用户根本不知道那几个字能点 —— 这正是本轮要修的问题。
            + "a.mt-autolink{text-decoration:underline;}"
            + "pre,.blk_code,.blockcode{background:#f4f5f7;padding:10px;border-radius:6px;"
            + "overflow-x:auto;font-size:12.5px;line-height:1.55;white-space:pre-wrap;"
            + "word-break:break-all;font-family:monospace;}"
            // build98: App 自己生成的代码卡片（PostCodeRender）。按钮在正常文档流里，
            // 有语言标签、有整块头部可点，绝不会出现「找不到按钮」的情况。
            + ".mt-code{margin:10px 0;border-radius:8px;overflow:hidden;"
            + "background:#282c34;border:1px solid #3a3f4b;}"
            + ".mt-code-hd{display:flex;align-items:center;justify-content:space-between;"
            + "padding:6px 10px;background:#21252b;color:#9aa4b2;font-size:11px;"
            + "font-family:monospace;}"
            + ".mt-code-lang{opacity:.9;}"
            + ".mt-code-btn{display:inline-block;padding:2px 12px;border-radius:4px;"
            + "background:#3d4453;color:#e6e6e6;font-size:12px;cursor:pointer;}"
            + ".mt-code-bd{margin:0;padding:10px;background:transparent;color:#e6e6e6;"
            + "overflow-x:auto;font-size:12.5px;line-height:1.6;white-space:pre;"
            + "word-break:normal;font-family:monospace;}"
            // build106: 图片加载失败的可视样式。此前附件图取不到时只剩一片空白
            // （tid=160198 整帖附件空白），现在至少看得出这里有一张没加载出来的图。
            + "img.mt-img-failed{min-width:60%;max-width:100% !important;min-height:56px;"
            + "height:56px !important;background:#f2f3f5;border:1px dashed #c9ced6;"
            + "border-radius:6px;}"
            // build106: [attach] 附件未被站点内联渲染时的占位（BBCodeUtil 生成）
            + ".mt-attach-ph{display:inline-block;margin:4px 2px;padding:2px 10px;"
            + "border-radius:12px;background:#f2f3f5;color:#8a919c;font-size:12px;"
            + "border:1px dashed #d5d9e0;}"
            + "blockquote{margin:8px 0;padding:6px 10px;border-left:3px solid #d8d8d8;"
            + "background:#fafafa;color:#555;}"
            + "table{width:auto !important;max-width:100% !important;}"
            + "font{font-size:inherit !important;}"
            + "div,td,th{max-width:100% !important;}"
            + "p{margin:6px 0;}"
            + "</style>";

    /**
     * build92: 用 WebView 原样渲染帖子正文。
     *
     * @param contentHtml 站点下发的正文原始 HTML（未经图片抽离）
     */
    private void renderContentInWeb(String contentHtml) {
        if (this.binding.webContent == null || android.text.TextUtils.isEmpty(contentHtml)) {
            // 极端情况：旧布局没这个控件、或正文为空 —— 退回 TextView，绝不留空页
            if (this.binding.webContent != null) {
                this.binding.webContent.setVisibility(View.GONE);
            }
            this.binding.tvContent.setVisibility(View.VISIBLE);
            if (!android.text.TextUtils.isEmpty(this.postDetail != null
                    ? this.postDetail.getContentHtml() : null)) {
                this.binding.tvContent.setText(safeFromHtml(
                        com.solosu.mtforum.util.BBCodeUtil.extractCodeBlocks(
                                this.postDetail.getContentHtml()).html,
                        createInlineImageGetter(this.binding.tvContent),
                        com.solosu.mtforum.util.BBCodeUtil.createTagHandler(this)));
            } else {
                this.binding.tvContent.setText("");
            }
            // build107: 这条兜底分支以前没挂 linkify —— 正文里纯文本网址照样点不动。
            // 退回 TextView 时它就是唯一的正文视图，必须和 !webRender 那条路一样处理。
            setupClickableLinks(this.binding.tvContent);
            return;
        }
        final android.webkit.WebView web = this.binding.webContent;

        // 正文是论坛用户内容，绝不能让它执行脚本（XSS）。
        // 只保留 JS 用来量高度 + 图片点击回调，脚本全部剥掉。
        String body = contentHtml
                .replaceAll("(?is)<script[^>]*>.*?</script\s*>", "")
                .replaceAll("(?is)<script[^>]*/?>", "")
                .replaceAll("(?is)<iframe[^>]*>.*?</iframe\s*>", "")
                .replaceAll("(?is)<iframe[^>]*/?>", "")
                .replaceAll("(?i)\son[a-z]+\s*=\s*\"[^\"]*\"", "")
                .replaceAll("(?i)\son[a-z]+\s*=\s*'[^']*'", "")
                .replaceAll("(?i)javascript:", "#");

        // 游客态站点会把附件换成「登录可见」，此时正文里一张 <img> 都没有。
        // 用列表页带过来的真实 CDN 图补进去，至少图能看到。
        // （缩略图升级已在 bindData 里做过，这里只兜底补图；该方法幂等）
        body = injectFallbackImagesInline(body);

        // build98: 代码块换成 App 自己的卡片（自带复制按钮）。
        // 放在这里 = 交给 WebView 的 HTML 里已经有一个「我们百分百认识」的按钮，
        // 后面那句 JS 只是把点击接上剪贴板，不依赖站点模板。
        body = com.solosu.mtforum.util.PostCodeRender.decorate(body);

        String html = "<!DOCTYPE html><html><head><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><meta charset=\"utf-8\">"
                + WEB_CONTENT_CSS + "</head><body>" + body + "</body></html>";

        android.webkit.WebSettings ws = web.getSettings();
        if (ws != null) {
            ws.setJavaScriptEnabled(true);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(false);
            ws.setSupportZoom(false);
            ws.setBuiltInZoomControls(false);
            ws.setDisplayZoomControls(false);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setAllowFileAccessFromFileURLs(false);
            ws.setAllowUniversalAccessFromFileURLs(false);
            ws.setMediaPlaybackRequiresUserGesture(true);
            ws.setDomStorageEnabled(false);
            ws.setDatabaseEnabled(false);
            ws.setSaveFormData(false);
            ws.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        }
        web.setBackgroundColor(0);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setLongClickable(true);
        web.setOnLongClickListener(v -> false);

        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view,
                                                    android.webkit.WebResourceRequest req) {
                return handlePostWebLink(req == null || req.getUrl() == null
                        ? null : req.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String url) {
                return handlePostWebLink(url);
            }

            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                // build107: 先补纯文本链接，再挂其余脚本。顺序有讲究 ——
                // 链接化只动文本节点，且跳过 <pre>/<code>/<a> 与 .mt-code 卡片，
                // 所以代码块里 curl https://… 这类示例网址不会被改成链接；
                // 反过来先跑复制注入，就可能把按钮文案里的网址包进来。
                bindPostWebLinkify(view);
                bindPostWebImageClicks(view);
                bindPostWebReady(view);
                // build97: 给代码块注入复制按钮（WebView 原样渲染后 CodeBlockView 卡片
                // 不再渲染，复制能力必须在这里补回来）
                bindPostWebCodeCopy(view);
                // build106: 附件图取不到时原生重试回填，绝不静默空白
                bindPostWebImageErrors(view);
                measurePostWebHeight(view);
            }
        });

        // 图片点击回调 + 图片全部加载完的回调：JS 只暴露这两个方法，且只接受字符串
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void openImage(String url) {
                if (android.text.TextUtils.isEmpty(url) || url.startsWith("data:")) return;
                final String u = url;
                runOnUiThread(() -> openImagePreview(u));
            }

            /**
             * build93: 正文图片全部 onload/onerror 之后回调。
             *
             * <p>图片没加载完时 document.body.scrollHeight 是按「图还没占高度」算的，
             * 按这个高度给 WebView 设 layoutParams，图一加载完就会被截断，或者
             * 反过来留一大片空白。所以必须等图齐了再量一次。
             */
            @android.webkit.JavascriptInterface
            public void onReady() {
                runOnUiThread(() -> {
                    measurePostWebHeight(web);
                    // build107: 图片全部落地后（含失败）再判断要不要上图廊兜底
                    maybeFallbackToGallery(web);
                });
            }

            /**
             * build106: 正文图片加载失败回调。用带会话的 OkHttp 重取一次：
             * 成功转 data: URI 回填，失败打可视样式 —— 不再留静默空白块。
             */
            @android.webkit.JavascriptInterface
            public void imgFailed(String index, String src) {
                final int idx = parseIntSafe(index);
                if (idx < 0) return;
                retryPostWebImage(web, idx, src);
            }

            /**
             * build97: 复制正文代码块。
             *
             * <p>WebView 模式下代码块按站点原样渲染在页面里，v5.11 起就不再抽出来
             * 做成 CodeBlockView 卡片（否则同一段代码出现两遍）。但 CodeBlockView
             * 带的「一键复制」也跟着没了 —— 用户报的就是「正文代码类型不能直接复制」。
             * 这里给每个 pre/.blk_code 注入一个复制按钮，点它经 JS 桥回 native 写剪贴板。
             */
            @android.webkit.JavascriptInterface
            public void copyText(String text) {
                if (android.text.TextUtils.isEmpty(text)) return;
                final String t = text;
                runOnUiThread(() -> {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("code", t));
                            Toast.makeText(ThreadDetailActivity.this,
                                    "已复制", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        }, "PostBody");

        web.setVisibility(View.VISIBLE);
        this.binding.tvContent.setVisibility(View.GONE);
        // build106: 正文里的附件图（forum.php?mod=attachment…）必须带登录 Cookie。
        // 会话在 OkHttp 罐子里，渲染前把最新会话推进 WebView CookieManager，
        // 避免「单账号/新装设备 WebView 无会话 → 附件按游客处理 → 整帖空白」。
        this.httpClient.syncToCookieManager();
        web.loadDataWithBaseURL(com.solosu.mtforum.network.HttpClient.BASE_URL,
                html, "text/html", "utf-8", "about:blank");
    }

    /**
     * build107: 把正文里的纯文本网址补成可点链接。
     *
     * <h3>为什么必须补这一步</h3>
     * <p>build92 起主楼默认走 {@link #renderContentInWeb}：站点下发的正文 HTML 原样
     * 丢进 WebView，图才会落在作者插入的位置。但给 TextView 用的那套 linkify
     * （{@link com.solosu.mtforum.util.PlainTextUrlPattern} + {@code matcherLinkify}）
     * 只在 {@code if (!webRender)} 分支里被调用 —— WebView 这条路上一次都没跑。
     *
     * <p>于是作者没用 {@code [url]} BBCode、直接把网址贴进正文时，Discuz 不会替他
     * 生成 {@code <a href>}，WebView 里就只是一段普通文字：点不动，也不像链接。
     * 用户报的「帖子内链接无法自动识别为可直接点击打开的超链接」就是这一条。
     *
     * <p>补出来的 {@code <a>} 不设 target，导航留在当前 WebView，
     * {@code shouldOverrideUrlLoading} 才能把它交给 {@link #handlePostWebLink} ——
     * 也就是沿用统一的「应用内浏览器 / 系统浏览器」设置，不绕过用户的选择。
     *
     * <p>脚本幂等，每次 onPageFinished 都跑一遍不会重复包。
     */
    private void bindPostWebLinkify(android.webkit.WebView web) {
        if (web == null) return;
        web.evaluateJavascript(
                com.solosu.mtforum.util.PostWebLinkifyScript.js(), null);
    }

    /** build92: 给正文里所有 <img> 挂点击，点了走全屏预览 */
    private void bindPostWebImageClicks(android.webkit.WebView web) {
        if (web == null) return;
        // build95: 用 addEventListener(捕获阶段) 而不是 e.onclick=。站点自己的
        // 脚本后面会给 img 挂委托监听，直接覆盖掉 e.onclick，图就点不动了。
        // 捕获阶段先跑，preventDefault + stopPropagation 之后站点再也收不到。
        String js = "(function(){try{var a=document.getElementsByTagName('img');"
                + "for(var i=0;i<a.length;i++){(function(e){"
                + "e.style.cursor='pointer';"
                + "function go(ev){ev.preventDefault();ev.stopPropagation();"
                + "var s=e.currentSrc||e.src||'';"
                + "if(window.PostBody&&window.PostBody.openImage){PostBody.openImage(s);}}"
                + "e.addEventListener('click',go,true);"
                + "e.addEventListener('touchend',go,true);"
                + "})(a[i]);}}catch(x){}})();";
        web.evaluateJavascript(js, null);
    }

    /**
     * 给正文里的代码块注入「复制」按钮（build97 引入，build98 重写）。
     *
     * <p>v5.11 起主楼改用 WebView 原样渲染站点 HTML，代码块不再抽出来做成
     * CodeBlockView 卡片（抽了就会同一段代码出现两遍），于是 CodeBlockView 自带
     * 的复制按钮也没了。用户报「正文代码类型不能直接复制」。
     *
     * <p>build97 的第一版给每个块塞了个绝对定位按钮，但用户反馈「还是不能复制」，
     * 排查出三个原因，见 {@link com.solosu.mtforum.util.PostWebCodeCopyScript}：
     * 按钮被代码块自己的横向滚动带走 / 复制出来带着“复制”两个字 / 站点懒加载出来的
     * 代码块压根没来得及挂按钮。这一版全部修掉，并额外提供 native 兜底入口
     * （长按「复制正文」→ 选择要复制第几段代码，见 {@link #showCopyOptions()}），
     * 即使 WebView 里一个按钮都没点上，也能把代码拿走。
     */
    private void bindPostWebCodeCopy(android.webkit.WebView web) {
        if (web == null) return;
        // 脚本本体在 util/PostWebCodeCopyScript（纯字符串、零 Android 依赖，
        // 有 JVM 单测守着选择器与「复制内容不带按钮文案」这两条不变量）
        web.evaluateJavascript(com.solosu.mtforum.util.PostWebCodeCopyScript.js(), null);
    }

    /**
     * build106: 给正文 WebView 的图片挂失败兜底。
     * 脚本在 {@link com.solosu.mtforum.util.PostWebImageScript}，纯字符串可单测。
     */
    private void bindPostWebImageErrors(android.webkit.WebView web) {
        if (web == null) return;
        web.evaluateJavascript(com.solosu.mtforum.util.PostWebImageScript.bindErrorsJs(), null);
    }

    /**
     * build106: WebView 正文图片加载失败后的原生重试。
     *
     * <p>WebView 里的附件请求只有浏览器 CookieManager 那点会话；真出问题时
     * （会话没同步过去、站点风控只认原生请求头等），这里用带完整论坛会话的
     * OkHttp 再取一次，成功就以 data: URI 回填。整条链再失败才允许「失败样式」，
     * 绝不再出现无提示的空白块。
     */
    private void retryPostWebImage(final android.webkit.WebView web, final int index, final String src) {
        if (web == null) return;
        if (android.text.TextUtils.isEmpty(src)
                || !(src.startsWith("http://") || src.startsWith("https://"))) {
            markPostWebImageFailed(web, index);
            return;
        }
        postWebImgPending++;   // build107: 有重取在飞，兜底判断必须等它落地
        new java.lang.Thread(() -> {
            try {
                // getBytes 走共享 OkHttpClient：自动带论坛 Cookie 与 UA
                byte[] bytes = com.solosu.mtforum.network.HttpClient.getInstance()
                        .getBytes(src, 8 * 1024 * 1024);
                String mime = sniffImageMime(bytes);
                if (bytes == null || bytes.length == 0 || mime == null) {
                    markPostWebImageFailed(web, index);
                    return;
                }
                final String dataUri = "data:" + mime + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(bytes);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    web.evaluateJavascript(
                            com.solosu.mtforum.util.PostWebImageScript
                                    .setImgDataUriJs(index, dataUri), null);
                    // 回填后图片高度变化，重新量一次正文高度
                    measurePostWebHeight(web);
                });
            } catch (Throwable t) {
                markPostWebImageFailed(web, index);
            } finally {
                // 计数只在主线程改：兜底判断也在主线程读，避免跨线程竞态
                runOnUiThread(() -> {
                    if (postWebImgPending > 0) postWebImgPending--;
                });
            }
        }, "post-img-retry").start();
    }

    /**
     * build107: 正文一张图都没加载出来时，用底部图廊兜底，不再留一屏空白。
     *
     * <p>原帖渲染（默认）为了不「一图两显」，在渲染前就无条件关掉了底部图廊。
     * 这有个前提：正文里的图一定渲染得出来。可帖子里的图常常是 Discuz 附件，
     * 站点对没有登录态（或权限不足）的请求返回的是「本帖子中包含更多资源 /
     * 您需要登录后才能下载或查看附件」的 HTML 提示页 —— 对 {@code <img>} 来说
     * 就是加载「完成」但尺寸为 0，屏幕上是一片空白。这时关掉图廊等于什么都没有，
     * 用户报的「这些帖子图片无法加载是白屏」就是这个。
     *
     * <p>所以这里在图片全部落地之后（含失败、含原生重取结束）统计真正加载成功的
     * 张数；为 0 且有候选地址时才启用图廊。正文正常出图时不会走到这里，
     * 「不重复显示」的语义保持不变。
     */
    private void maybeFallbackToGallery(final android.webkit.WebView web) {
        if (web == null || fallbackGalleryShown || pendingGalleryUrls.isEmpty()) return;
        if (postWebImgPending > 0) {
            // 还有原生重取在飞，等它落地再判断，否则会把「正在抢救」误判成「没救了」
            web.postDelayed(() -> maybeFallbackToGallery(web), 600L);
            return;
        }
        web.evaluateJavascript(
                com.solosu.mtforum.util.PostWebImageScript.countLoadedImagesJs(),
                value -> {
                    if (isFinishing() || isDestroyed()) return;
                    int loaded = parseJsInt(value);
                    if (loaded == 0 && !fallbackGalleryShown && !pendingGalleryUrls.isEmpty()) {
                        fallbackGalleryShown = true;
                        renderImageGallery(pendingGalleryUrls);
                        measurePostWebHeight(web);
                    }
                });
    }

    private void markPostWebImageFailed(final android.webkit.WebView web, final int index) {
        if (web == null) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            web.evaluateJavascript(
                    com.solosu.mtforum.util.PostWebImageScript.markImgFailedJs(index), null);
            measurePostWebHeight(web);
        });
    }

    /**
     * 解析 {@code evaluateJavascript} 回调里的整数。
     *
     * <p>带过来的是 JSON：脚本返回字符串时会带一圈引号，返回数字时不带。
     * {@link #parseIntSafe} 只认裸数字，拿它读脚本返回值会永远解析失败 ——
     * 兜底逻辑就永远不触发了。这里两种都认。
     */
    private static int parseJsInt(String raw) {
        if (raw == null) return -1;
        String v = raw.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static int parseIntSafe(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 按文件头识别图片类型；认不出来返回 null（避免把提示页塞成 data: URI）。 */
    private static String sniffImageMime(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return null;
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes[0] == 'B' && bytes[1] == 'M') return "image/bmp";
        if (bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return null;
    }

    /**
     * build93: 等正文图片全部加载完再回调 onReady 重新量高度。
     *
     * <p>只用 onPageFinished 量一次是不够的：那时图片刚开始下载，高度按「图没占位」算。
     */
    private void bindPostWebReady(android.webkit.WebView web) {
        if (web == null) return;
        String js = "(function(){try{var a=document.getElementsByTagName('img');"
                + "var n=a.length,c=0,done=false;"
                + "function fin(){if(!done){done=true;"
                + "if(window.PostBody&&window.PostBody.onReady){PostBody.onReady();}}}"
                + "if(n===0){fin();return;}"
                + "for(var i=0;i<n;i++){(function(e){"
                + "function one(){if(++c>=n)fin();}"
                + "if(e.complete){one();}"
                + "else{e.addEventListener('load',one);e.addEventListener('error',one);}"
                + "})(a[i]);}"
                + "setTimeout(fin,3000);"
                + "}catch(x){fin();}})();";
        web.evaluateJavascript(js, null);
    }

    /**
     * build92: WebView 嵌在 NestedScrollView 里，必须按内容实际高度撑开，
     * 否则要么被截断、要么自己内部滚动把父滚动器卡住。
     */
    private void measurePostWebHeight(final android.webkit.WebView web) {
        if (web == null) return;
        web.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            web.evaluateJavascript(
                    "document.body.scrollHeight",
                    value -> {
                        try {
                            int h = value == null ? 0
                                    : (int) Float.parseFloat(value.trim());
                            if (h <= 0) return;
                            float d = getResources().getDisplayMetrics().density;
                            android.view.ViewGroup.LayoutParams lp = web.getLayoutParams();
                            if (lp == null) return;
                            lp.height = (int) (h * d);
                            web.setLayoutParams(lp);
                        } catch (Exception ignored) {
                        }
                    });
        }, 140L);
    }

    /**
     * WebView 里点链接的处理：遵循统一的应用内浏览器/系统浏览器设置；
     * 站内帖子和用户页在应用内模式下优先打开原生页面。正文 WebView 不自行导航。
     */
    private boolean handlePostWebLink(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl) || rawUrl.startsWith("about:blank")) return true;
        String url = com.solosu.mtforum.ui.web.LinkRouter.normalizeWebUrl(rawUrl);
        if (TextUtils.isEmpty(url)) return true;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            boolean inApp = com.solosu.mtforum.ui.web.LinkRouter.isInAppMode(this);
            boolean forumHost = host != null && ("bbs.binmt.cc".equalsIgnoreCase(host)
                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".binmt.cc"));

            if (inApp && forumHost) {
                // 站内帖子使用原生详情页，其他站内链接交给统一的应用内浏览器。
                java.util.regex.Matcher threadPath = Pattern.compile(
                        "thread-(\\d+)", Pattern.CASE_INSENSITIVE).matcher(path);
                boolean hasThreadPath = threadPath.find();
                String tid = hasThreadPath ? threadPath.group(1) : uri.getQueryParameter("tid");
                if (!TextUtils.isEmpty(tid)
                        && (hasThreadPath || "viewthread".equalsIgnoreCase(
                                uri.getQueryParameter("mod")))) {
                    NavigationHelper.openThread(this, tid);
                    return true;
                }

                java.util.regex.Matcher uidPath = Pattern.compile(
                        "space-uid-(\\d+)", Pattern.CASE_INSENSITIVE).matcher(path);
                String uid = uidPath.find() ? uidPath.group(1) : null;
                if (TextUtils.isEmpty(uid) && "space".equalsIgnoreCase(
                        uri.getQueryParameter("mod"))) {
                    uid = uri.getQueryParameter("uid");
                }
                if (!TextUtils.isEmpty(uid)) {
                    Intent intent = new Intent(this, UserProfileActivity.class);
                    intent.putExtra("uid", uid);
                    startActivity(intent);
                    return true;
                }
                java.util.regex.Matcher usernamePath = Pattern.compile(
                        "space-username-([^./?&]+)", Pattern.CASE_INSENSITIVE).matcher(path);
                if (usernamePath.find()) {
                    Intent intent = new Intent(this, UserProfileActivity.class);
                    intent.putExtra("username", usernamePath.group(1));
                    startActivity(intent);
                    return true;
                }
            }
            com.solosu.mtforum.ui.web.LinkRouter.open(this, url);
        } catch (Exception ignored) {
            com.solosu.mtforum.ui.web.LinkRouter.open(this, url);
        }
        return true;
    }

    private void renderImageGallery(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            this.binding.cardImageGallery.setVisibility(View.GONE);
            return;
        }
        this.binding.cardImageGallery.setVisibility(View.VISIBLE);
        this.binding.hsvImageGallery.setVisibility(View.VISIBLE);
        this.binding.llImageGallery.removeAllViews();
        FrostedGlassHelper.applyToCardViews(this.binding.cardImageGallery, this);
        int height = dpToPx(200);
        int margin = dpToPx(4);
        for (final String url : urls) {
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(-2, height));
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ((LinearLayout.LayoutParams) imageView.getLayoutParams())
                    .setMargins(margin, 0, margin, 0);
            imageView.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ThreadDetailActivity.this.lambda$bindData$26(url, view);
                }
            });
            Glide.with((FragmentActivity) this)
                    .load(com.solosu.mtforum.util.ForumImageLoader.model(url))
                    .placeholder(new ColorDrawable(getColor(R.color.background_secondary)))
                    .error(new ColorDrawable(getColor(R.color.divider)))
                    .into(imageView);
            this.binding.llImageGallery.addView(imageView);
        }
        this.binding.btnCollapseImages.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ThreadDetailActivity.this.lambda$bindData$28(view);
            }
        });
    }

    /** Only actual Discuz smiley paths are inline; words such as face/icon in a normal
     * attachment filename must never cause a content image to be discarded. */
    private static boolean isInlineForumImage(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String low=url.toLowerCase(java.util.Locale.ROOT);
        return low.contains("/static/image/smiley/") || low.contains("/smiley/")
                || low.contains("/emoticon/") || low.contains("static/image/common/smiley");
    }

    /**
     * 懒加载占位图判定。
     *
     * <p>build80 补 {@code imageloading.gif}：这是 Comiis 模板<b>真正在用</b>的
     * 占位文件名。原列表里只有 none/blank/grey，而本论坛实际下发的 src
     * 恰好是 {@code imageloading.gif} —— 于是「解析真实 URL」这一步
     * 把它当成正常 URL 原样返回，图片自然停在占位图上。
     *
     * <p>统一委托 {@link ImageUrl}，保证 {@code pickRealImageUrl} 和
     * {@code extractAndSeparateImages} 两条路径对「什么是占位图」判断一致。
     */
    /** build80: 委托 {@link ImageUrl}，保证与其它图片路径的补全规则一致。 */
    /**
     * 原位模式下修正正文里的图片地址。
     *
     * <p>做两件事：
     * <ol>
     *   <li>把懒加载占位 src 换成真实地址（含 Comiis 的 {@code comiis_loadimages}）</li>
     *   <li>实在找不到真实地址的图<b>直接删掉</b> —— 留着就是个永远加载不出来的空白块，
     *       还不如不显示</li>
     * </ol>
     */
    /**
     * 把懒加载的占位 {@code src} 换成真实附件地址。
     *
     * <p>build80: 原来「取不到真实地址」的 img 会被<b>整张删掉</b>
     * （{@code dead.add(img)} → {@code e.remove()}）。但取不到地址
     * 不等于这张图不该显示——真机日志里这种图点开后照样能加载，
     * 说明只是 src 换了层壳，删掉就成了「能看的图被吃掉」。
     *
     * <p>改为：能升级就升级，不能升级就<b>原样留着</b>，由 Glide 自己决定成败。
     * 宁可显示失败的空位，也不要让内容凭空消失。
     */
    /**
     * build90: 把列表页拿到的兜底图补写进正文 HTML，让它们跟着正文排版显示。
     *
     * <p>只在一张图都没有的时候补：站点对**游客**在详情页根本不下发附件
     * {@code <img>}（正文里只有文字和头像），登录态则正常下发、不需要补。
     *
     * <p>位置只能追加在正文末尾 —— 列表页只给得到图的地址，给不到它在原帖里的
     * 插入位置。但跟在同一个 TextView 里、同样满宽排版，比抽到帖子底部那个
     * 独立的横滑图廊卡片贴近「正文排版处」得多。
     *
     * <p>实测这些地址虽然是 {@code size=500x480} 的缩略图参数，CDN 实际跳转到
     * OSS 原图（aid=377307 拿到 1080x690 / 69303 字节），满宽显示不糊。
     * 试过把 size 改大会退化成 {@code none.gif}，所以地址原样用。
     */
    private String injectFallbackImagesInline(String html) {
        if (this.listImageFallback.isEmpty()) return html;
        String body = TextUtils.isEmpty(html) ? "" : html;
        // 正文里已经有图就不补，避免和登录态正常下发的配图重复
        if (body.toLowerCase(java.util.Locale.ROOT).contains("<img")) return body;
        StringBuilder sb = new StringBuilder(body);
        for (String url : this.listImageFallback) {
            if (TextUtils.isEmpty(url)) continue;
            sb.append("<br><img src=\"")
                    .append(escapeHtmlAttr(url))
                    .append("\"><br>");
        }
        return sb.toString();
    }

    /**
     * build93: 属性值转义。
     *
     * <p>旧写法只做 {@code url.replace("&", "&amp;")}，两个问题：
     * ① 源串里已经是 {@code &amp;} 的会被二次转义成 {@code &amp;amp;}，
     *    CDN 的 {@code ?aid=X&size=Y&key=Z} 直接就取不到图；
     * ② 不转义双引号，url 里出现 {@code "} 会把属性截断，整段 img 标签烂掉。
     */
    private static String escapeHtmlAttr(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            switch (ch) {
                case '&':
                    // 只在不是实体起点时转义，避免二次转义
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '#') {
                        out.append(ch);
                    } else if (raw.startsWith("&amp;", i) || raw.startsWith("&lt;", i)
                            || raw.startsWith("&gt;", i) || raw.startsWith("&quot;", i)
                            || raw.startsWith("&#", i) || raw.startsWith("&apos;", i)) {
                        out.append(ch);
                    } else {
                        out.append("&amp;");
                    }
                    break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * build97: 已提取到 {@link com.solosu.mtforum.util.PostImageHtml}，
     * 主楼和评论区共用。这里只留一个薄封装，省得改所有调用点。
     */
    private static String upgradeThumbnailsToFull(String html) {
        return com.solosu.mtforum.util.PostImageHtml.upgradeThumbnailsToFull(html);
    }

    /** build97: 委托 PostImageHtml（原私有实现已提取，评论区共用） */
    private static String pickRealImageUrl(org.jsoup.nodes.Element img) {
        return com.solosu.mtforum.util.PostImageHtml.pickRealImageUrl(img);
    }

    /** build97: 委托 ImageUrl */
    private static boolean isPlaceholderImage(String url) {
        return com.solosu.mtforum.util.ImageUrl.isPlaceholder(url);
    }

    /**
     * build76: 点赞 / 收藏的弹性反馈。
     * motion-web handfeel §1 —— 用欠阻尼弹簧而不是 tween，
     * 按下去要有"弹一下"的确认感，纯淡入读不出发生了什么。
     */
    private void bounce(View v) {
        if (v == null) return;
        v.setScaleX(0.78f);
        v.setScaleY(0.78f);
        com.solosu.mtforum.ui.anim.Motion.spring(v,
                androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f,
                com.solosu.mtforum.ui.anim.Motion.springBouncy());
        com.solosu.mtforum.ui.anim.Motion.spring(v,
                androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f,
                com.solosu.mtforum.ui.anim.Motion.springBouncy());
    }
}
