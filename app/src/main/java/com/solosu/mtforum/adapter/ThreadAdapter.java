package com.solosu.mtforum.adapter;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.session.FollowStateManager;
import com.solosu.mtforum.ui.space.UserProfileActivity;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * 帖子列表 RecyclerView Adapter
 */
public class ThreadAdapter extends RecyclerView.Adapter<ThreadAdapter.ViewHolder> {

    private List<Thread> threadList = new ArrayList<>();
    private View headerView;
    private static final int VIEW_TYPE_HEADER = -1;
    private Context context;
    private OnItemClickListener listener;
    private OnUserClickListener userClickListener;
    private volatile java.util.Set<String> serverFollowingUids;
    private volatile boolean serverFollowingLoaded;
    private boolean followStateLoading;
    private final Object followStateLock = new Object();

    public interface OnItemClickListener {
        void onItemClick(Thread thread, int position);
    }

    public interface OnUserClickListener {
        void onUserClick(Thread thread);
    }

    public ThreadAdapter(Context context) {
        this.context = context;
    }

    public void setThreadList(List<Thread> list) {
        this.threadList = list != null ? list : new ArrayList<>();
        serverFollowingUids = null;
        serverFollowingLoaded = false;
        followStateLoading = false;
        notifyDataSetChanged();
    }

    /** 按 tid 定位刷新卡片(收藏数预取完成后回调) */
    public void notifyItemChangedByTid(String tid) {
        if (tid == null) return;
        for (int i = 0; i < threadList.size(); i++) {
            Thread t = threadList.get(i);
            if (t != null && tid.equals(t.getTid())) {
                notifyItemChanged(i + (headerView != null ? 1 : 0));
                return;
            }
        }
    }

    public void addThreads(List<Thread> list) {
        if (list != null) {
            int start = threadList.size();
            threadList.addAll(list);
            notifyItemRangeInserted(start, list.size());
        }
    }

    public void setHeaderView(View view) {
        this.headerView = view;
        notifyItemInserted(0);
    }

    public View getHeaderView() {
        return headerView;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnUserClickListener(OnUserClickListener listener) {
        this.userClickListener = listener;
    }

    public Thread getItem(int position) {
        int offset = headerView != null ? position - 1 : position;
        if (offset >= 0 && offset < threadList.size()) return threadList.get(offset);
        return null;
    }

    public void removeItem(int position) {
        int dataPos = headerView != null ? position - 1 : position;
        if (dataPos >= 0 && dataPos < threadList.size()) {
            threadList.remove(dataPos);
            notifyItemRemoved(position);
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (headerView != null && position == 0) return VIEW_TYPE_HEADER;
        return super.getItemViewType(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            return new ViewHolder(headerView);
        }
        View view = LayoutInflater.from(context).inflate(R.layout.item_thread, parent, false);
        // ThreadAdapter 被首页、版块、搜索、个人帖子等所有帖子列表复用，统一挂载磨砂玻璃背景（卡片容器）。
        View cardView = view.findViewById(R.id.thread_card);
        if (cardView != null) {
            cardView.setBackground(FrostedGlassDrawable.create(context, 14f));
        }
        return new ViewHolder(view);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (holder.cardView != null && holder.cardView.getBackground() instanceof FrostedGlassDrawable) {
            holder.cardView.getBackground().setVisible(true, false);
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        if (holder.cardView != null && holder.cardView.getBackground() instanceof FrostedGlassDrawable) {
            holder.cardView.getBackground().setVisible(false, false);
        }
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        if (holder.cardView != null && holder.cardView.getBackground() instanceof FrostedGlassDrawable) {
            holder.cardView.getBackground().setVisible(false, false);
        }
        super.onViewRecycled(holder);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (headerView != null && position == 0) return;
        int dataPos = headerView != null ? position - 1 : position;
        Thread thread = threadList.get(dataPos);
        holder.boundThread = thread;
        holder.tvTitle.setText(thread.getTitle());
        holder.tvAuthor.setText(thread.getAuthor());
        holder.tvTime.setText(thread.getPublishTime());
        String forumName = thread.getForumName();
        if (forumName != null && !forumName.isEmpty()) {
            holder.tvForum.setVisibility(View.VISIBLE);
            holder.tvForum.setText(forumName);
        } else {
            holder.tvForum.setVisibility(View.GONE);
        }

        // 摘要
        String summary = thread.getSummary();
        if (summary != null && !summary.isEmpty()) {
            holder.tvSummary.setVisibility(View.VISIBLE);
            holder.tvSummary.setText(summary);
        } else {
            holder.tvSummary.setVisibility(View.GONE);
        }

        // 等级
        String level = thread.getAuthorLevel();
        if (level != null && !level.isEmpty()) {
            holder.tvLevel.setVisibility(View.VISIBLE);
            holder.tvLevel.setText(level);
        } else {
            holder.tvLevel.setVisibility(View.GONE);
        }

        // 统计信息
        holder.tvViews.setText(formatCount(thread.getViews()));
        holder.tvReplies.setText(formatCount(thread.getReplies()));
        holder.tvLikes.setText(formatCount(thread.getLikes()));

    // 关注状态统一从服务端关注列表恢复；网络确认失败时才使用已有本地状态。
         if (!TextUtils.isEmpty(thread.getAuthorUid())) {
             java.util.Set<String> serverSet = serverFollowingUids;
             if (serverSet != null) {
                 thread.setFollowed(serverSet.contains(thread.getAuthorUid()));
             } else {
                 thread.setFollowed(FollowStateManager.resolve(context, thread.getAuthorUid(), thread.isFollowed()));
                 loadServerFollowingIfNeeded();
             }
         }
         holder.btnFollow.setText(thread.isFollowed() ? "已关注" : "关注");
        // AI 一键总结:拉帖+评论区,有隐藏先固定模板回复解锁再总结
        // build68: 进过详情页确认含隐藏内容的帖子，在列表里打个「隐藏」标，方便快速区分
        if (holder.tvHiddenTag != null) {
            holder.tvHiddenTag.setVisibility(
                    com.solosu.mtforum.session.PostCountsCache.hasHidden(thread.getTid())
                            ? View.VISIBLE : View.GONE);
        }

        // build68: 列表卡片上也有一个 AI 总结按钮，之前只 gate 了详情页那个，
        // 所以侧边栏关掉后每张卡片上还挂着，用户以为没生效。
        holder.btnAiSummarize.setVisibility(
                com.solosu.mtforum.ui.UiSettings.isAiSummaryVisible(context)
                        ? View.VISIBLE : View.GONE);
        holder.btnAiSummarize.setOnClickListener(v -> {
            Intent it = new Intent(context, com.solosu.mtforum.ai.AiSummarizeActivity.class);
            it.putExtra("tid", thread.getTid());
            it.putExtra("title", thread.getTitle());
            if (!(context instanceof android.app.Activity)) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(it);
        });
        holder.btnFollow.setOnClickListener(v -> {
            if (!FollowStateManager.isLoggedIn(context)) {
                if (context instanceof android.app.Activity) {
                    com.solosu.mtforum.ui.login.LoginBottomSheet.show(
                            (android.app.Activity) context, null);
                } else {
                    Toast.makeText(context, "请先登录后再关注", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            String uid = thread.getAuthorUid();
            if (TextUtils.isEmpty(uid)) {
                Toast.makeText(context, "无法获取用户ID", Toast.LENGTH_SHORT).show();
                return;
            }
            final boolean targetState = !thread.isFollowed();
            holder.btnFollow.setEnabled(false);
        new java.lang.Thread(() -> {
            boolean success = FollowStateManager.syncFollow(context, uid, targetState);
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                holder.btnFollow.setEnabled(true);
                if (success) {
                    thread.setFollowed(targetState);
                    holder.btnFollow.setText(targetState ? "已关注" : "关注");
                    Toast.makeText(context, targetState
                            ? R.string.action_follow_success
                            : R.string.action_unfollow_success, Toast.LENGTH_SHORT).show();
                    java.util.Set<String> current = serverFollowingUids;
                    if (current != null) {
                        java.util.Set<String> updated = new java.util.HashSet<>(current);
                        if (targetState) updated.add(uid); else updated.remove(uid);
                        serverFollowingUids = updated;
                    }
                } else {
                    Toast.makeText(context, "关注操作失败，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
        });

        // 置顶标记
        if (thread.isSticky()) {
            holder.tvSticky.setVisibility(View.VISIBLE);
        } else {
            holder.tvSticky.setVisibility(View.GONE);
        }

        // 头像加载
        String avatarUrl = thread.getAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(context)
                    .load(com.solosu.mtforum.util.ForumImageLoader.model(avatarUrl))
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(holder.ivAvatar);
        } else {
            holder.ivAvatar.setImageResource(R.drawable.ic_account);
        }
        // 帖子封面图：2 列网格，最多两张，固定尺寸裁切
        // （用户要求「首页图片显示限制数量最大 2，裁切显示大小为固定尺寸」）。
        // 没有图片列表时回退到单图封面。
        holder.ivThumbnail.setVisibility(View.GONE);
        holder.llThreadImages.setVisibility(View.GONE);
        holder.llThreadImages.removeAllViews();
        Glide.with(context).clear(holder.ivThumbnail);

        List<String> imageUrls = thread.getImageUrls();
        if (imageUrls != null && !imageUrls.isEmpty()) {
            if (imageUrls.size() == 1) {
                // 单图不能占双列网格的一半：用完整卡片宽度显示，避免只看到一小块画面。
                holder.ivThumbnail.setVisibility(View.VISIBLE);
                Glide.with(context)
                        .load(com.solosu.mtforum.util.ForumImageLoader.model(imageUrls.get(0)))
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .centerCrop()
                        .into(holder.ivThumbnail);
            } else {
                // 首页最多两张；每张图在固定比例容器里居中裁切，保持列表几何尺寸一致。
                int count = Math.min(2, imageUrls.size());
                int gap = dp(3);
                int avail = holder.llThreadImages.getWidth();
                if (avail <= 0) {
                    int itemWidth = holder.itemView.getWidth();
                    if (itemWidth <= 0) itemWidth = Math.max(0, screenWidth(context) - dp(18));
                    // item_thread 外边距 9dp × 2、thread_card 内边距 10dp × 2。
                    avail = Math.max(0, itemWidth - dp(20));
                }
                int colW = avail > 0 ? Math.max(1, (avail - gap) / 2) : dp(150);
                holder.llThreadImages.setVisibility(View.VISIBLE);
                for (int i = 0; i < count; i++) {
                    ImageView imageView = new ImageView(context);
                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = colW;
                    // 固定约 4:3 的双列预览；长图不会把整张帖子卡片撑高。
                    params.height = Math.round(colW * 0.75f);
                    params.columnSpec = GridLayout.spec(i, 1f);
                    params.rowSpec = GridLayout.spec(0);
                    params.setMargins(i == 0 ? 0 : gap, 0, 0, 0);
                    imageView.setLayoutParams(params);
                    imageView.setAdjustViewBounds(false);
                    imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    imageView.setBackgroundResource(R.drawable.thread_image_bg);
                    imageView.setClipToOutline(true);
                    Glide.with(context)
                            .load(com.solosu.mtforum.util.ForumImageLoader.model(imageUrls.get(i)))
                            .placeholder(R.drawable.ic_image_placeholder)
                            .error(R.drawable.ic_image_error)
                            .centerCrop()
                            .into(imageView);
                    holder.llThreadImages.addView(imageView);
                }
                // 首次 bind 时容器宽度可能尚未测量；下一帧用真实宽度校准，避免第二张只露出边角。
                holder.llThreadImages.post(() -> {
                    if (holder.boundThread != thread) return;
                    int measuredWidth = holder.llThreadImages.getWidth();
                    if (measuredWidth <= 0) return;
                    int measuredCol = Math.max(1, (measuredWidth - gap) / 2);
                    for (int i = 0; i < holder.llThreadImages.getChildCount(); i++) {
                        View child = holder.llThreadImages.getChildAt(i);
                        GridLayout.LayoutParams lp = (GridLayout.LayoutParams) child.getLayoutParams();
                        lp.width = measuredCol;
                        lp.height = Math.round(measuredCol * 0.75f);
                        lp.setMargins(i == 0 ? 0 : gap, 0, 0, 0);
                        child.setLayoutParams(lp);
                    }
                });
            }
        } else {
            String thumbnailUrl = thread.getThumbnailUrl();
            if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
                holder.ivThumbnail.setVisibility(View.VISIBLE);
                // 单图封面固定为完整卡片宽度 + 固定高度，居中裁切保持主体可辨认。
                Glide.with(context)
                        .load(com.solosu.mtforum.util.ForumImageLoader.model(thumbnailUrl))
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .centerCrop()
                        .into(holder.ivThumbnail);
            }
        }


        holder.ivAvatar.setOnClickListener(v -> {
            if (userClickListener != null && !TextUtils.isEmpty(thread.getAuthorUid())) {
                userClickListener.onUserClick(thread);
            }
        });
        holder.tvAuthor.setOnClickListener(v -> {
            if (userClickListener != null && !TextUtils.isEmpty(thread.getAuthorUid())) {
                userClickListener.onUserClick(thread);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(thread, position);
            }
        });

        // build73: 卡片按压反馈（motion-web handfeel：按下即缩、抬手弹回）
        com.solosu.mtforum.ui.anim.Motion.pressFeedback(holder.itemView, 0.975f);

        // build73: 长按帖子卡片 = 复制帖子链接。
        // 原本是「拉黑作者」——误触代价太大（一不小心整个人的帖子都没了），
        // 拉黑已挪到帖子详情页和用户主页，那里有明确上下文。
        holder.itemView.setOnLongClickListener(v -> {
            String tid = thread.getTid();
            if (android.text.TextUtils.isEmpty(tid)) return false;
            String link = "https://bbs.binmt.cc/thread-" + tid + "-1-1.html";
            String title = android.text.TextUtils.isEmpty(thread.getTitle())
                    ? "" : thread.getTitle();
            android.app.Dialog d = new androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("复制链接")
                    .setMessage(title + "\n\n" + link)
                    .setPositiveButton("复制链接", (dlg, w) ->
                            copyToClipboard(context, link, "帖子链接已复制"))
                    .setNeutralButton("复制标题+链接", (dlg, w) ->
                            copyToClipboard(context, title + "\n" + link, "标题和链接已复制"))
                    .setNegativeButton("取消", null)
                    .show();
            com.solosu.mtforum.ui.widget.DialogHelper.applyToAlertDialog(d, context);
            return true;
        });
    }

    private static void copyToClipboard(android.content.Context ctx, String text, String toast) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("mtforum", text));
            Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    /** 拉黑后即时从数据集移除该作者全部帖子(不需要刷新页面) */
    public void removeThreadsByUid(String uid) {
        if (uid == null || uid.isEmpty()) return;
        List<Thread> keep = new ArrayList<>();
        for (Thread t : threadList) {
            if (t.getAuthorUid() == null || !uid.equals(t.getAuthorUid())) keep.add(t);
        }
        if (keep.size() != threadList.size()) {
            threadList.clear();
            threadList.addAll(keep);
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemCount() {
        return threadList.size() + (headerView != null ? 1 : 0);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        Thread boundThread;
        View cardView;
        ImageView ivAvatar;
        ImageView ivThumbnail;
        GridLayout llThreadImages;
        TextView btnFollow;
        TextView tvTitle, tvAuthor, tvLevel, tvTime, tvForum, tvSummary;
        TextView tvViews, tvReplies, tvLikes, tvSticky;
        TextView btnAiSummarize;
        TextView tvHiddenTag;

        ViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.thread_card);
            ivAvatar = itemView.findViewById(R.id.iv_avatar);
            ivThumbnail = itemView.findViewById(R.id.iv_thumbnail);
            llThreadImages = itemView.findViewById(R.id.ll_thread_images);
            btnFollow = itemView.findViewById(R.id.btn_thread_follow);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvAuthor = itemView.findViewById(R.id.tv_author);
            tvLevel = itemView.findViewById(R.id.tv_level);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvForum = itemView.findViewById(R.id.tv_forum);
            tvSummary = itemView.findViewById(R.id.tv_summary);
            tvViews = itemView.findViewById(R.id.tv_views);
            tvReplies = itemView.findViewById(R.id.tv_replies);
            tvLikes = itemView.findViewById(R.id.tv_likes);
            tvSticky = itemView.findViewById(R.id.tv_sticky);
            btnAiSummarize = itemView.findViewById(R.id.btn_ai_summary);
            tvHiddenTag = itemView.findViewById(R.id.tv_hidden_tag);
        }
    }

    private void openUserProfile(Thread thread) {
        if (thread == null || TextUtils.isEmpty(thread.getAuthorUid())) return;
        if (userClickListener != null) {
            userClickListener.onUserClick(thread);
            return;
        }
        Intent intent = new Intent(context, UserProfileActivity.class);
        intent.putExtra("uid", thread.getAuthorUid());
        intent.putExtra("username", thread.getAuthor());
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private void loadServerFollowingIfNeeded() {
        if (serverFollowingUids != null) return;
        if (serverFollowingLoaded || followStateLoading
                || !FollowStateManager.isLoggedIn(context)) return;
        synchronized (followStateLock) {
            if (serverFollowingUids != null || serverFollowingLoaded || followStateLoading) return;
            followStateLoading = true;
        }
        new java.lang.Thread(() -> {
            java.util.Set<String> result = FollowStateManager.queryServerFollowingUids(context);
            serverFollowingUids = result;
            serverFollowingLoaded = true;
            followStateLoading = false;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(this::notifyDataSetChanged);
        }).start();
    }

    /** build95: 取屏幕宽。Display.getDefaultDisplay() 在 API 36 已移除。 */
    private static int screenWidth(android.content.Context c) {
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    return wm.getCurrentWindowMetrics().getBounds().width();
                }
                return wm.getDefaultDisplay().getWidth();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private String formatCount(int count) {
        if (count >= 10000) {
            return String.format("%.1fw", count / 10000.0);
        } else if (count >= 1000) {
            return String.format("%.1fk", count / 1000.0);
        }
        return String.valueOf(count);
    }
}