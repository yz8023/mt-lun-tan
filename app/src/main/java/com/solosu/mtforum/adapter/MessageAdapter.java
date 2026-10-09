package com.solosu.mtforum.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.Message;
import com.solosu.mtforum.network.NoticeBadgeManager;
import com.solosu.mtforum.session.SameThreadReadManager;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息/私信列表 RecyclerView Adapter
 * 支持删除和屏蔽按钮回调
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {

    private List<Message> messageList = new ArrayList<>();
    private Context context;
    private OnItemClickListener listener;
    private OnActionClickListener actionListener;
    private String viewType = "";

    public interface OnItemClickListener {
        void onItemClick(Message message, int position);
    }

    /** 删除/屏蔽按钮回调 */
    public interface OnActionClickListener {
        void onDelete(Message message, int position);
        void onBlock(Message message, int position);
    }

    public MessageAdapter(Context context) {
        this.context = context;
    }

    public void setMessageList(List<Message> list) {
        this.messageList = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    /** 设置当前消息详情页类型，用于与角标一致地显示逐条未读状态。 */
    public void setViewType(String viewType) {
        this.viewType = viewType == null ? "" : viewType;
        notifyDataSetChanged();
    }

    public List<Message> getMessageList() {
        return messageList;
    }

    public void setOnActionClickListener(OnActionClickListener listener) {
        this.actionListener = listener;
    }

    public void removeItem(int position) {
        if (position >= 0 && position < messageList.size()) {
            messageList.remove(position);
            notifyItemRemoved(position);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_message, parent, false);
        float density = context.getResources().getDisplayMetrics().density;
        view.setBackground(FrostedGlassDrawable.create(context, 8f));
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Message msg = messageList.get(position);
        holder.tvAuthor.setText(msg.getAuthor());
        holder.tvTitle.setText(msg.getTitle());
        holder.tvTime.setText(msg.getTime());

        String summary = msg.getSummary();
        if (summary != null && !summary.isEmpty()) {
            holder.tvSummary.setVisibility(View.VISIBLE);
            holder.tvSummary.setText(summary);
        } else {
            holder.tvSummary.setVisibility(View.GONE);
        }

        // 已读/未读与本地角标使用同一套状态；通知点击后即刻隐藏未读标识。
        boolean unread = isUnreadMessage(msg);
        holder.viewUnreadBar.setVisibility(unread ? View.VISIBLE : View.GONE);
        holder.tvUnread.setVisibility(unread ? View.VISIBLE : View.GONE);
        holder.tvTitle.setTextColor(holder.itemView.getContext().getColor(
                unread ? R.color.text_primary : R.color.text_secondary));

        // 消息类型
        if (msg.getType() == 1) {
            holder.tvType.setVisibility(View.VISIBLE);
            holder.tvType.setText("系统");
        } else if (msg.getType() == 2) {
            holder.tvType.setVisibility(View.VISIBLE);
            holder.tvType.setText("回复");
        } else {
            holder.tvType.setVisibility(View.GONE);
        }

        // 显示删除/屏蔽按钮（仅通知类有pmid时显示）
        boolean hasNoticeId = msg.getPmid() != null && !msg.getPmid().isEmpty();
        boolean hasUid = msg.getAuthorUid() != null && !msg.getAuthorUid().isEmpty();
        holder.ivDelete.setVisibility(hasNoticeId ? View.VISIBLE : View.GONE);
        holder.ivBlock.setVisibility(hasUid ? View.VISIBLE : View.GONE);

        // 头像
        String avatarUrl = msg.getAvatarUrl();
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

        holder.itemView.setOnClickListener(v -> {
            Context itemContext = v.getContext();
            if (!TextUtils.isEmpty(viewType) && itemContext != null) {
                String tid = extractThreadId(msg);
                boolean batchSameThread = !"pm".equals(viewType)
                        && !"follower".equals(viewType)
                        && SameThreadReadManager.isEnabled(itemContext)
                        && !TextUtils.isEmpty(tid);
                if (batchSameThread) {
                    for (Message candidate : messageList) {
                        if (tid.equals(extractThreadId(candidate))) {
                            NoticeBadgeManager.markItemRead(itemContext, viewType,
                                    getItemReadKey(candidate));
                        }
                    }
                    notifyDataSetChanged();
                } else {
                    NoticeBadgeManager.markItemRead(itemContext, viewType, getItemReadKey(msg));
                    if ("pm".equals(viewType)) msg.setRead(true);
                    int currentPosition = holder.getBindingAdapterPosition();
                    if (currentPosition != RecyclerView.NO_POSITION) notifyItemChanged(currentPosition);
                }
            }
            if (listener != null) {
                int currentPosition = holder.getBindingAdapterPosition();
                listener.onItemClick(msg,
                        currentPosition != RecyclerView.NO_POSITION ? currentPosition : position);
            }
        });

        // 删除按钮点击
        holder.ivDelete.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onDelete(msg, position);
            }
        });

        // 屏蔽按钮点击
        holder.ivBlock.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onBlock(msg, position);
            }
        });
    }

    private String getItemReadKey(Message message) {
        if (message == null) return "";
        if ("pm".equals(viewType) || "follower".equals(viewType)) {
            return firstNonEmpty(message.getAuthorUid(), message.getPmid(), message.getAuthor());
        }
        return NoticeBadgeManager.stableKeyFor(message);
    }

    private boolean isUnreadMessage(Message message) {
        if (message == null) return false;
        if (TextUtils.isEmpty(viewType) || context == null) return !message.isRead();
        String key = getItemReadKey(message);
        if (TextUtils.isEmpty(key)) return !message.isRead();
        if ("pm".equals(viewType)) {
            if (message.isRead()) {
                NoticeBadgeManager.clearPmClickedIfServerRead(context, key);
                return NoticeBadgeManager.isNoticeUnread(context, viewType, key);
            }
            return NoticeBadgeManager.isNoticeUnread(context, viewType, key)
                    || !NoticeBadgeManager.isPmClicked(context, key);
        }
        if ("follower".equals(viewType)) {
            return NoticeBadgeManager.isNoticeUnread(context, viewType, key);
        }
        if (NoticeBadgeManager.isNoticeClicked(context, viewType, key)) return false;
        if (NoticeBadgeManager.isNoticeUnread(context, viewType, key)) return true;
        return !message.isRead();
    }

    /** 从通知标题、摘要或删除链接提取帖子 tid，用于同帖通知批量已读。 */
    private String extractThreadId(Message message) {
        if (message == null) return null;
        String[] sources = {message.getTitle(), message.getSummary(), message.getDeleteUrl()};
        Pattern[] patterns = {
                Pattern.compile("(?:[?&]|\\b)tid=(\\d+)"),
                Pattern.compile("thread-(\\d+)")
        };
        for (String source : sources) {
            if (TextUtils.isEmpty(source)) continue;
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(source);
                if (matcher.find()) return matcher.group(1);
            }
        }
        return null;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.itemView.getBackground().setVisible(true, false);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        holder.itemView.getBackground().setVisible(false, false);
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        holder.itemView.getBackground().setVisible(false, false);
        super.onViewRecycled(holder);
    }



    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivAvatar, ivDelete, ivBlock;
        View viewUnreadBar;
        TextView tvAuthor, tvTitle, tvSummary, tvTime, tvUnread, tvType;

        ViewHolder(View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.iv_avatar);
            viewUnreadBar = itemView.findViewById(R.id.view_unread_bar);
            tvAuthor = itemView.findViewById(R.id.tv_author);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvSummary = itemView.findViewById(R.id.tv_summary);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvUnread = itemView.findViewById(R.id.tv_unread);
            tvType = itemView.findViewById(R.id.tv_type);
            ivDelete = itemView.findViewById(R.id.iv_delete);
            ivBlock = itemView.findViewById(R.id.iv_block);
        }
    }
}