package com.solosu.mtforum.ui.account;

import android.app.TimePickerDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.solosu.mtforum.R;
import com.solosu.mtforum.session.AccountManager;
import com.solosu.mtforum.session.MultiSignInManager;
import com.solosu.mtforum.session.SignInScheduler;
import com.solosu.mtforum.session.SignInSettings;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.login.LoginBottomSheet;
import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 账号与签到管理页（build60 新增）。
 *
 * <p>一个页面搞定：多账号增删、切换、单独/批量签到、密码托管、定时签到设置。
 * 功能对标 Forinxy/mt 的多账号签到，但接在本客户端已有的 Cookie 会话体系上。
 */
public class AccountManagerActivity extends AppCompatActivity implements AccountAdapter.Listener {

    private RecyclerView rvAccounts;
    private AccountAdapter adapter;
    private TextView tvOverviewDetail, tvEmpty, tvLastRun;
    private TextView tvScheduleDesc, tvTimeValue, tvIntervalValue;
    private SwitchMaterial swAutoSign, swAllAccounts, swSchedule, swRelogin, swNotify;
    private MaterialButton btnSignAll;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_manager);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvOverviewDetail = findViewById(R.id.tv_overview_detail);
        tvEmpty = findViewById(R.id.tv_empty);
        tvLastRun = findViewById(R.id.tv_last_run);
        tvScheduleDesc = findViewById(R.id.tv_schedule_desc);
        tvTimeValue = findViewById(R.id.tv_time_value);
        tvIntervalValue = findViewById(R.id.tv_interval_value);
        btnSignAll = findViewById(R.id.btn_sign_all);

        rvAccounts = findViewById(R.id.rv_accounts);
        rvAccounts.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AccountAdapter(this);
        rvAccounts.setAdapter(adapter);

        try {
            FrostedGlassHelper.applyToCardViews(findViewById(android.R.id.content), this);
        } catch (Exception ignored) {
        }

        bindSettings();

        findViewById(R.id.btn_add_account).setOnClickListener(v -> addAccount());
        btnSignAll.setOnClickListener(v -> signAll());

        maybeRequestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ==================== 列表 ====================

    private void refresh() {
        List<AccountManager.Account> accounts = AccountManager.list(this);
        String activeUid = AccountManager.activeUid(this);
        adapter.submit(accounts, activeUid);

        boolean empty = accounts.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvAccounts.setVisibility(empty ? View.GONE : View.VISIBLE);

        int enabled = 0, signed = 0, withPwd = 0;
        for (AccountManager.Account a : accounts) {
            if (a.enabled) enabled++;
            if (a.isSignedToday()) signed++;
            if (a.hasPassword()) withPwd++;
        }
        String current = UserSessionManager.getInstance().isLoggedIn(this)
                ? UserSessionManager.getInstance().getUsername(this) : "未登录";
        tvOverviewDetail.setText("当前账号：" + current
                + "\n共 " + accounts.size() + " 个账号，" + enabled + " 个参与批量签到"
                + "\n今日已签 " + signed + " / " + accounts.size()
                + "，已托管密码 " + withPwd + " 个");

        long last = SignInSettings.getLastRunTime(this);
        if (last > 0) {
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(last));
            tvLastRun.setVisibility(View.VISIBLE);
            tvLastRun.setText("最近一次批量签到：" + time + " · " + SignInSettings.getLastSummary(this));
        } else {
            tvLastRun.setVisibility(View.GONE);
        }

        btnSignAll.setEnabled(!MultiSignInManager.isRunning());
    }

    // ==================== 设置项 ====================

    private void bindSettings() {
        swAutoSign = findViewById(R.id.sw_auto_sign);
        swAllAccounts = findViewById(R.id.sw_all_accounts);
        swSchedule = findViewById(R.id.sw_schedule);
        swRelogin = findViewById(R.id.sw_relogin);
        swNotify = findViewById(R.id.sw_notify);

        swAutoSign.setChecked(SignInSettings.isAutoSignInEnabled(this));
        swAllAccounts.setChecked(SignInSettings.isAllAccounts(this));
        swSchedule.setChecked(SignInSettings.isScheduleEnabled(this));
        swRelogin.setChecked(SignInSettings.isAutoReloginEnabled(this));
        swNotify.setChecked(SignInSettings.isNotifyEnabled(this));
        updateScheduleDesc();
        tvTimeValue.setText(SignInSettings.getTimeText(this));
        tvIntervalValue.setText(SignInSettings.getIntervalSeconds(this) + " 秒");

        bindRow(R.id.row_auto_sign, swAutoSign, checked -> {
            SignInSettings.setAutoSignInEnabled(this, checked);
        });
        bindRow(R.id.row_all_accounts, swAllAccounts, checked -> {
            SignInSettings.setAllAccounts(this, checked);
        });
        bindRow(R.id.row_schedule, swSchedule, checked -> {
            SignInSettings.setScheduleEnabled(this, checked);
            SignInScheduler.reschedule(this);
            updateScheduleDesc();
            if (checked) {
                Toast.makeText(this, "已开启每天 " + SignInSettings.getTimeText(this) + " 自动签到",
                        Toast.LENGTH_SHORT).show();
                maybeRequestNotificationPermission();
            }
        });
        bindRow(R.id.row_relogin, swRelogin, checked ->
                SignInSettings.setAutoReloginEnabled(this, checked));
        bindRow(R.id.row_notify, swNotify, checked -> {
            SignInSettings.setNotifyEnabled(this, checked);
            if (checked) maybeRequestNotificationPermission();
        });

        findViewById(R.id.row_time).setOnClickListener(v -> pickTime());
        findViewById(R.id.row_interval).setOnClickListener(v -> pickInterval());
    }

    private interface OnToggle {
        void accept(boolean checked);
    }

    /** 整行可点，点击等同于切换右侧开关 */
    private void bindRow(int rowId, SwitchMaterial sw, OnToggle onToggle) {
        View row = findViewById(rowId);
        if (row == null || sw == null) return;
        sw.setOnCheckedChangeListener((v, checked) -> onToggle.accept(checked));
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
    }

    private void updateScheduleDesc() {
        if (SignInSettings.isScheduleEnabled(this)) {
            tvScheduleDesc.setText("每天 " + SignInSettings.getTimeText(this) + " 左右后台执行");
        } else {
            tvScheduleDesc.setText("未开启");
        }
    }

    private void pickTime() {
        int hour = SignInSettings.getHour(this);
        int minute = SignInSettings.getMinute(this);
        new TimePickerDialog(this, (view, h, m) -> {
            SignInSettings.setTime(this, h, m);
            tvTimeValue.setText(SignInSettings.getTimeText(this));
            updateScheduleDesc();
            if (SignInSettings.isScheduleEnabled(this)) {
                SignInScheduler.reschedule(this);
            }
        }, hour, minute, true).show();
    }

    private void pickInterval() {
        final String[] options = {"0 秒（不等待）", "3 秒", "5 秒（推荐）", "10 秒", "20 秒"};
        final int[] values = {0, 3, 5, 10, 20};
        int current = SignInSettings.getIntervalSeconds(this);
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) checked = i;
        }
        android.app.Dialog dialog = new AlertDialog.Builder(this)
                .setTitle("账号之间的间隔")
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    SignInSettings.setIntervalSeconds(this, values[which]);
                    tvIntervalValue.setText(values[which] + " 秒");
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    // ==================== 动作 ====================

    private void addAccount() {
        LoginBottomSheet.show(this, () -> {
            Toast.makeText(this, "账号已添加", Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    private void signAll() {
        List<AccountManager.Account> enabled = AccountManager.enabledList(this);
        if (enabled.isEmpty()) {
            Toast.makeText(this, "没有启用中的账号", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSignAll.setEnabled(false);
        btnSignAll.setText("签到中…");
        MultiSignInManager.signInAll(this, true, new MultiSignInManager.Callback() {
            @Override
            public void onProgress(int index, int total, String username) {
                if (isFinishing()) return;
                btnSignAll.setText("签到中 " + index + "/" + total);
            }

            @Override
            public void onFinished(MultiSignInManager.Summary summary) {
                if (isFinishing()) return;
                btnSignAll.setEnabled(true);
                btnSignAll.setText("一键全部签到");
                refresh();
                showResultDialog(summary);
            }
        });
    }

    private void showResultDialog(MultiSignInManager.Summary summary) {
        android.app.Dialog dialog = new AlertDialog.Builder(this)
                .setTitle("签到结果 · " + summary.shortText())
                .setMessage(summary.detailText())
                .setPositiveButton("知道了", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    // ==================== 列表回调 ====================

    @Override
    public void onSwitch(AccountManager.Account account) {
        if (AccountManager.switchTo(this, account.uid)) {
            java.util.Map<String, String> info = new java.util.HashMap<>();
            info.put("username", account.displayName());
            info.put("uid", account.uid);
            info.put("avatarUrl", account.avatar == null ? "" : account.avatar);
            info.put("level", account.level == null ? "" : account.level);
            UserSessionManager.getInstance().saveLoginInfo(this, info);
            UserSessionManager.getInstance().clearSignInDate(this);
            Toast.makeText(this, "已切换到 " + account.displayName(), Toast.LENGTH_SHORT).show();
            refresh();
        } else {
            Toast.makeText(this, "切换失败，该账号的登录态已丢失，请重新登录", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onSign(AccountManager.Account account) {
        adapter.setBusyUid(account.uid);
        MultiSignInManager.signInOne(this, account.uid, true, new MultiSignInManager.Callback() {
            @Override
            public void onProgress(int index, int total, String username) {
            }

            @Override
            public void onFinished(MultiSignInManager.Summary summary) {
                if (isFinishing()) return;
                adapter.setBusyUid(null);
                refresh();
                String msg = summary.items.isEmpty() ? "签到结束" : summary.items.get(0).line();
                Toast.makeText(AccountManagerActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onPassword(AccountManager.Account account) {
        final EditText et = new EditText(this);
        et.setHint(account.hasPassword() ? "输入新密码以覆盖" : "输入 " + account.displayName() + " 的论坛密码");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setTextColor(getResources().getColor(R.color.text_primary));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // build61: 已存密码只显示占位符，绝不回显明文；输入新的即覆盖
        String tip = account.hasPassword()
                ? "当前状态：已保存密码（••••••••）\n"
                + "出于安全考虑不回显原密码，输入新密码即可覆盖。"
                : "密码经 Android KeyStore 的 AES-GCM 加密后仅保存在本机，\n"
                + "用于 403 掉线 / Cookie 过期时自动重新登录。";

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(account.hasPassword() ? "修改密码" : "保存密码")
                .setMessage(tip)
                .setView(wrapper)
                .setPositiveButton("保存", (d, w) -> {
                    String pwd = et.getText().toString().trim();
                    if (TextUtils.isEmpty(pwd)) {
                        Toast.makeText(this, "密码为空，未保存", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AccountManager.setPassword(this, account.uid, pwd);
                    Toast.makeText(this, "已保存并加密", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null);
        if (account.hasPassword()) {
            builder.setNeutralButton("清除已存密码", (d, w) -> {
                AccountManager.clearPassword(this, account.uid);
                Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
                refresh();
            });
        }
        DialogHelper.applyToAlertDialog(builder.show(), this);
    }

    @Override
    public void onDelete(AccountManager.Account account) {
        android.app.Dialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("确定要从本机移除 " + account.displayName() + " 吗？\n"
                        + "只会删除本地保存的登录态和密码，不影响论坛账号本身。")
                .setPositiveButton("删除", (d, w) -> {
                    AccountManager.remove(this, account.uid);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    @Override
    public void onEnabledChanged(AccountManager.Account account, boolean enabled) {
        AccountManager.setEnabled(this, account.uid, enabled);
        refresh();
    }

    // ==================== 通知权限 ====================

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (!SignInSettings.isNotifyEnabled(this)) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        } catch (Exception ignored) {
        }
    }
}
