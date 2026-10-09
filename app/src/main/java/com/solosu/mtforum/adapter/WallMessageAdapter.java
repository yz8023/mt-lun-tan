package com.solosu.mtforum.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.Message;
import com.solosu.mtforum.util.ForumImageLoader;

import java.util.ArrayList;
import java.util.List;

/** 用户留言板条目。操作按钮仅根据服务端实际渲染的链接显示。 */
public final class WallMessageAdapter extends RecyclerView.Adapter<WallMessageAdapter.Holder> {

    public interface Listener {
        void onAuthorClick(Message message);
        void onReply(Message message);
        void onEdit(Message message);
        void onDelete(Message message);
    }

    private final Context context;
    private final List<Message> items = new ArrayList<>();
    private Listener listener;

    public WallMessageAdapter(Context context) {
        this.context = context;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<Message> messages) {
        items.clear();
        if (messages != null) items.addAll(messages);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_wall_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Message item = items.get(position);
        holder.author.setText(TextUtils.isEmpty(item.getAuthor()) ? "匿名" : item.getAuthor());
        holder.time.setText(item.getTime() == null ? "" : item.getTime());
        holder.time.setVisibility(TextUtils.isEmpty(item.getTime()) ? View.GONE : View.VISIBLE);
        String content = TextUtils.isEmpty(item.getSummary()) ? item.getTitle() : item.getSummary();
        holder.content.setText(content == null ? "" : content);

        if (TextUtils.isEmpty(item.getQuotedContent())) {
            holder.quoteLayout.setVisibility(View.GONE);
        } else {
            holder.quoteLayout.setVisibility(View.VISIBLE);
            holder.quote.setText(item.getQuotedContent());
        }

        if (TextUtils.isEmpty(item.getAvatarUrl())) {
            Glide.with(holder.avatar).clear(holder.avatar);
            holder.avatar.setImageResource(R.drawable.ic_account);
        } else {
            Glide.with(holder.avatar)
                    .load(ForumImageLoader.model(item.getAvatarUrl()))
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(holder.avatar);
        }

        bindAction(holder.reply, item.getWallReplyUrl(), v -> notifyAction(item, 0));
        bindAction(holder.edit, item.getWallEditUrl(), v -> notifyAction(item, 1));
        bindAction(holder.delete, item.getWallDeleteUrl(), v -> notifyAction(item, 2));
        View.OnClickListener authorClick = v -> {
            if (listener != null && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                listener.onAuthorClick(item);
            }
        };
        holder.author.setOnClickListener(authorClick);
        holder.avatar.setOnClickListener(authorClick);
    }

    private void bindAction(TextView button, String url, View.OnClickListener listener) {
        boolean visible = !TextUtils.isEmpty(url);
        button.setVisibility(visible ? View.VISIBLE : View.GONE);
        button.setOnClickListener(visible ? listener : null);
    }

    private void notifyAction(Message item, int action) {
        if (listener == null) return;
        if (action == 0) listener.onReply(item);
        else if (action == 1) listener.onEdit(item);
        else listener.onDelete(item);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView author;
        final TextView time;
        final TextView content;
        final LinearLayout quoteLayout;
        final TextView quote;
        final TextView reply;
        final TextView edit;
        final TextView delete;

        Holder(View view) {
            super(view);
            avatar = view.findViewById(R.id.iv_wall_avatar);
            author = view.findViewById(R.id.tv_wall_author);
            time = view.findViewById(R.id.tv_wall_time);
            content = view.findViewById(R.id.tv_wall_content);
            quoteLayout = view.findViewById(R.id.layout_wall_quote);
            quote = view.findViewById(R.id.tv_wall_quote);
            reply = view.findViewById(R.id.btn_wall_reply);
            edit = view.findViewById(R.id.btn_wall_edit);
            delete = view.findViewById(R.id.btn_wall_delete);
        }
    }
}
