package com.solosu.mtforum.ui.account;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.solosu.mtforum.R;
import com.solosu.mtforum.session.AccountManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 账号列表适配器（build60 新增）。
 * 每张卡片给出：头像、昵称、UID/等级、今日签到状态、是否参与批量签到，
 * 以及 切换 / 单独签到 / 管理密码 / 删除 四个动作。
 */
public class AccountAdapter extends RecyclerView.Adapter<AccountAdapter.VH> {

    public interface Listener {
        void onSwitch(AccountManager.Account account);

        void onSign(AccountManager.Account account);

        void onPassword(AccountManager.Account account);

        void onDelete(AccountManager.Account account);

        void onEnabledChanged(AccountManager.Account account, boolean enabled);
    }

    private final List<AccountManager.Account> data = new ArrayList<>();
    private final Listener listener;
    private String activeUid;
    /** 正在签到中的 uid，用于禁用按钮避免重复点 */
    private String busyUid;

    public AccountAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<AccountManager.Account> accounts, String activeUid) {
        this.data.clear();
        if (accounts != null) this.data.addAll(accounts);
        this.activeUid = activeUid;
        notifyDataSetChanged();
    }

    public void setBusyUid(String uid) {
        this.busyUid = uid;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_account, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final AccountManager.Account account = data.get(position);
        boolean isActive = activeUid != null && activeUid.equals(account.uid);
        boolean busy = busyUid != null && busyUid.equals(account.uid);

        holder.tvUsername.setText(account.displayName());
        holder.tvCurrentBadge.setVisibility(isActive ? View.VISIBLE : View.GONE);
        holder.tvPwdBadge.setVisibility(account.hasPassword() ? View.VISIBLE : View.GONE);

        StringBuilder sub = new StringBuilder("UID ").append(account.uid);
        if (!TextUtils.isEmpty(account.level)) sub.append(" · ").append(account.level);
        holder.tvUid.setText(sub.toString());

        holder.tvSignState.setText(busy ? "签到中…" : account.signSummary());
        holder.tvSignState.setTextColor(holder.itemView.getResources().getColor(
                account.isSignedToday()
                        ? R.color.success
                        : R.color.text_secondary));

        // 头像
        if (!TextUtils.isEmpty(account.avatar)) {
            try {
                Glide.with(holder.ivAvatar.getContext())
                        .load(account.avatar)
                        .placeholder(R.drawable.ic_account)
                        .error(R.drawable.ic_account)
                        .circleCrop()
                        .into(holder.ivAvatar);
            } catch (Exception ignored) {
                holder.ivAvatar.setImageResource(R.drawable.ic_account);
            }
        } else {
            holder.ivAvatar.setImageResource(R.drawable.ic_account);
        }

        // 启用开关：先摘监听再设值，避免复用触发误回调
        holder.swEnabled.setOnCheckedChangeListener(null);
        holder.swEnabled.setChecked(account.enabled);
        holder.swEnabled.setOnCheckedChangeListener((v, checked) -> {
            account.enabled = checked;
            if (listener != null) listener.onEnabledChanged(account, checked);
        });

        holder.btnSwitch.setEnabled(!isActive && !busy);
        holder.btnSwitch.setText(isActive ? "使用中" : "切换");
        holder.btnSign.setEnabled(!busy);
        holder.btnPassword.setText(account.hasPassword() ? "改密码" : "存密码");

        holder.btnSwitch.setOnClickListener(v -> {
            if (listener != null) listener.onSwitch(account);
        });
        holder.btnSign.setOnClickListener(v -> {
            if (listener != null) listener.onSign(account);
        });
        holder.btnPassword.setOnClickListener(v -> {
            if (listener != null) listener.onPassword(account);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(account);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivAvatar;
        final TextView tvUsername;
        final TextView tvCurrentBadge;
        final TextView tvPwdBadge;
        final TextView tvUid;
        final TextView tvSignState;
        final SwitchMaterial swEnabled;
        final MaterialButton btnSwitch;
        final MaterialButton btnSign;
        final MaterialButton btnPassword;
        final MaterialButton btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.iv_avatar);
            tvUsername = itemView.findViewById(R.id.tv_username);
            tvCurrentBadge = itemView.findViewById(R.id.tv_current_badge);
            tvPwdBadge = itemView.findViewById(R.id.tv_pwd_badge);
            tvUid = itemView.findViewById(R.id.tv_uid);
            tvSignState = itemView.findViewById(R.id.tv_sign_state);
            swEnabled = itemView.findViewById(R.id.sw_enabled);
            btnSwitch = itemView.findViewById(R.id.btn_switch);
            btnSign = itemView.findViewById(R.id.btn_sign);
            btnPassword = itemView.findViewById(R.id.btn_password);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
