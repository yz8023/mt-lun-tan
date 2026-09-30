package com.solosu.mtforum.ui.space;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;


import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.solosu.mtforum.BuildConfig;
import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.ActivitySettingsBinding;
//import com.solosu.mtforum.network.UpdateChecker;
import com.solosu.mtforum.session.AccountManager;
import com.solosu.mtforum.session.AutoSignInManager;
import com.solosu.mtforum.util.CrashHandler;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.TextView;
import android.widget.ScrollView;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import java.io.File;

import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;

/**
 * 设置页（原生）
 * 展示应用信息和缓存清理功能
 */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.tvVersion.setText(BuildConfig.VERSION_NAME);

        FrostedGlassHelper.applyToCardViews(binding.getRoot(), this);

        boolean autoSignInEnabled = AutoSignInManager.isEnabled(this);
        binding.switchAutoSignIn.setChecked(autoSignInEnabled);
        binding.switchAutoSignIn.setOnCheckedChangeListener((buttonView, isChecked) ->
                AutoSignInManager.setEnabled(this, isChecked));

        // build60: 账号与签到管理入口
        binding.layoutAccountManager.setOnClickListener(v -> startActivity(
                new Intent(this, com.solosu.mtforum.ui.account.AccountManagerActivity.class)));

        // build66: 应用主题
        setupThemeSection();

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // 每次进入设置页自动检查一次更新；点击卡片可手动重试。
        //        binding.layoutCheckUpdate.setOnClickListener(v -> checkForUpdate());
        //        checkForUpdate();

        // 内置同域 WebView：执行 ESA/阿里云 JS 验证，并把 clearance Cookie 双向同步。
        binding.layoutSiteVerify.setOnClickListener(v ->
                com.solosu.mtforum.session.SiteAccessManager.openManually(this));

        // 附件/文件下载方式：系统 DownloadManager（保存到 Download）或浏览器。
        updateDownloadModeText();
        binding.layoutDownloadMode.setOnClickListener(v -> {
            String[] modes = {"应用内打开（文件保存到 Download）", "跳转系统浏览器"};
            int current = com.solosu.mtforum.ui.DownloadPreferences.getMode(this);
            android.app.Dialog dialog = new AlertDialog.Builder(this)
                    .setTitle("文件下载方式")
                    .setSingleChoiceItems(modes, current, (d, which) -> {
                        com.solosu.mtforum.ui.DownloadPreferences.setMode(this, which);
                        updateDownloadModeText();
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            DialogHelper.applyToAlertDialog(dialog, this);
        });

        // Read-only MCP endpoint (loopback by default, optional token-protected LAN forwarding).
        binding.cardMcp.setOnClickListener(v -> startActivity(
                new Intent(this, com.solosu.mtforum.mcp.McpSettingsActivity.class)));

        // 错误日志查看
        binding.layoutErrorLog.setOnClickListener(v -> showErrorLogDialog());
        updateErrorLogCount();
        updateAccountCount();

        // 清除缓存
        binding.btnClearCache.setOnClickListener(v -> {
            try {
                // 清除 Glide 缓存
                new Thread(() -> {
                    com.bumptech.glide.Glide.get(this).clearDiskCache();
                    runOnUiThread(() -> {
                        com.bumptech.glide.Glide.get(this).clearMemory();
                        Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show();
                    });
                }).start();
            } catch (Exception e) {
                Toast.makeText(this, "清除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /* UPDATE MODULE - temporarily disabled
    private void checkForUpdate() {
        binding.tvUpdateStatus.setText("检查中…");
        binding.layoutCheckUpdate.setEnabled(false);
        UpdateChecker.check(this, result -> {
            if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            binding.layoutCheckUpdate.setEnabled(true);
            if (!result.success) {
                binding.tvUpdateStatus.setText("检查失败");
                return;
            }
            if (result.hasUpdate) {
                binding.tvUpdateStatus.setText("有新版本");
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("发现新版本")
                        .setMessage("更新文件夹检测到新内容，是否打开下载页面？")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("立即更新", (dialog, which) -> openUpdatePage())
                        .show();
            } else {
                binding.tvUpdateStatus.setText(result.firstCheck ? "已记录" : "已是最新");
            }
        });
    }
    */

    /* UPDATE MODULE - temporarily disabled
    private void openUpdatePage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.UPDATE_URL)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }
    */

    @Override
    protected void onResume() {
        super.onResume();
        updateAccountCount();
    }

    private void updateDownloadModeText() {
        binding.tvDownloadMode.setText(com.solosu.mtforum.ui.DownloadPreferences.label(this));
    }

    /** 账号数量摘要 */
    private void updateAccountCount() {
        int count = AccountManager.count(this);
        binding.tvAccountCount.setText(count > 0 ? count + " 个账号" : "管理");
    }

    private void updateErrorLogCount() {
        File[] files = CrashHandler.getCrashLogFiles();
        binding.tvErrorLogCount.setText(files.length > 0 ? files.length + "条" : "无");
    }

    private void showErrorLogDialog() {
        File[] files = CrashHandler.getCrashLogFiles();
        if (files.length == 0) {
            android.app.Dialog alertDialog2 = new AlertDialog.Builder(this)
                    .setTitle("错误日志")
                    .setMessage("暂无错误日志")
                    .setPositiveButton("确定", null)
                    .show();
            DialogHelper.applyToAlertDialog(alertDialog2, this);
            return;
        }

        // 构建日志列表
        String[] items = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            items[i] = files[i].getName();
        }

        android.app.Dialog alertDialog3 = new AlertDialog.Builder(this)
                .setTitle("错误日志 (" + files.length + "条)")
                .setItems(items, (dialog, which) -> showLogDetail(files[which]))
                .setNeutralButton("清除全部", (dialog, which) -> {
                    CrashHandler.clearAllLogs();
                    updateErrorLogCount();
                })
                .setPositiveButton("关闭", null)
                .show();
        DialogHelper.applyToAlertDialog(alertDialog3, this);
    }

    private void showLogDetail(File file) {
        String content = CrashHandler.readLogFile(file);
        TextView textView = new TextView(this);
        textView.setText(content);
        textView.setTextSize(12f);
        textView.setTextColor(0xFFE0E0E0);
        textView.setBackgroundColor(0xFF1A1D23);
        textView.setPadding(24, 24, 24, 24);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setLineSpacing(4f, 1.2f);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        android.app.Dialog alertDialog4 = new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setNeutralButton("分享日志", (dialog, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, content);
                    startActivity(Intent.createChooser(share, "分享错误日志"));
                })
                .setNegativeButton("删除此条", (dialog, which) -> {
                    file.delete();
                    updateErrorLogCount();
                })
                .show();
        DialogHelper.applyToAlertDialog(alertDialog4, this);
    }

    // ==================== build66: 应用主题 ====================

    private void setupThemeSection() {
        updateThemeTexts();

        binding.layoutNightMode.setOnClickListener(v -> {
            final String[] items = {"跟随系统", "浅色", "深色"};
            android.app.Dialog d = new AlertDialog.Builder(this)
                    .setTitle("深色模式")
                    .setSingleChoiceItems(items,
                            com.solosu.mtforum.ui.theme.ThemeManager.getNightMode(this),
                            (dlg, which) -> {
                                dlg.dismiss();
                                com.solosu.mtforum.ui.theme.ThemeManager.setNightMode(this, which);
                                recreate();   // 立刻换肤
                            })
                    .setNegativeButton("取消", null)
                    .show();
            DialogHelper.applyToAlertDialog(d, this);
        });

        buildAccentSwatches();
        buildOpacitySliders();
    }

    /** 主题色圆形色板，选中的加一圈描边 */
    private void buildAccentSwatches() {
        android.widget.LinearLayout box = binding.llAccentSwatches;
        box.removeAllViews();
        int current = com.solosu.mtforum.ui.theme.ThemeManager.getAccentIndex(this);
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (34 * density);
        int gap = (int) (12 * density);

        for (int i = 0; i < com.solosu.mtforum.ui.theme.ThemeManager.ACCENT_NAMES.length; i++) {
            final int index = i;
            View dot = new View(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.rightMargin = gap;
            dot.setLayoutParams(lp);

            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(com.solosu.mtforum.ui.theme.ThemeManager.accentAt(this, index));
            if (index == current) {
                bg.setStroke((int) (3 * density),
                        getResources().getColor(R.color.text_primary, getTheme()));
            }
            dot.setBackground(bg);
            com.solosu.mtforum.ui.anim.Motion.pressFeedback(dot, 0.88f);
            dot.setOnClickListener(v -> {
                com.solosu.mtforum.ui.theme.ThemeManager.setAccentIndex(this, index);
                updateThemeTexts();
                buildAccentSwatches();
        buildOpacitySliders();
                com.solosu.mtforum.ui.theme.ThemeManager.applyAccent(
                        findViewById(android.R.id.content), this);
                Toast.makeText(this,
                        "主题色已切换为 "
                                + com.solosu.mtforum.ui.theme.ThemeManager.ACCENT_NAMES[index],
                        Toast.LENGTH_SHORT).show();
            });
            box.addView(dot);
        }
    }

    private void updateThemeTexts() {
        binding.tvNightModeValue.setText(
                com.solosu.mtforum.ui.theme.ThemeManager.nightModeName(this));
        binding.tvAccentName.setText(
                com.solosu.mtforum.ui.theme.ThemeManager.accentName(this));
    }

    /** build73: 卡片 / 对话框 / 底栏 三档不透明度滑块 */
    private void buildOpacitySliders() {
        android.widget.LinearLayout box = binding.llOpacitySliders;
        if (box == null) return;
        box.removeAllViews();
        addOpacityRow(box, "卡片与列表项",
                com.solosu.mtforum.ui.theme.ThemeManager.cardOpacity(this),
                p -> com.solosu.mtforum.ui.theme.ThemeManager.setCardOpacity(this, p));
        addOpacityRow(box, "对话框与弹窗",
                com.solosu.mtforum.ui.theme.ThemeManager.dialogOpacity(this),
                p -> com.solosu.mtforum.ui.theme.ThemeManager.setDialogOpacity(this, p));
        addOpacityRow(box, "底部导航栏",
                com.solosu.mtforum.ui.theme.ThemeManager.navOpacity(this),
                p -> com.solosu.mtforum.ui.theme.ThemeManager.setNavOpacity(this, p));

        TextView tip = new TextView(this);
        tip.setText("下限 40%，再低文字就读不清了。改完重进页面生效。");
        tip.setTextSize(11f);
        tip.setTextColor(getColor(R.color.text_hint));
        box.addView(tip);
    }

    private interface OnPercent {
        void set(int percent);
    }

    private void addOpacityRow(android.widget.LinearLayout box, String label,
                               int initial, final OnPercent sink) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (2 * d));

        final TextView title = new TextView(this);
        title.setText(label + "    " + initial + "%");
        title.setTextSize(13f);
        title.setTextColor(getColor(R.color.text_primary));
        row.addView(title);

        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(60);                        // 40–100 映射到 0–60
        bar.setProgress(initial - 40);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                title.setText(label + "    " + (p + 40) + "%");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
                sink.set(s.getProgress() + 40);
                Toast.makeText(SettingsActivity.this, "已保存，重进页面生效",
                        Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(bar);
        box.addView(row);
    }
}