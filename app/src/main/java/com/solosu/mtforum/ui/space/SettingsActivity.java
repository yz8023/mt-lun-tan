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
import com.solosu.mtforum.session.SameThreadReadManager;
import com.solosu.mtforum.mcp.McpServerManager;
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

        binding.switchSameThreadRead.setChecked(SameThreadReadManager.isEnabled(this));
        binding.switchSameThreadRead.setOnCheckedChangeListener((buttonView, isChecked) ->
                SameThreadReadManager.setEnabled(this, isChecked));

        McpServerManager mcp = McpServerManager.getInstance(getApplicationContext());
        binding.switchMcp.setChecked(mcp.isEnabled());
        binding.switchMcp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mcp.setEnabled(isChecked);
            updateMcpInfo();
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) updateMcpInfo();
            }, 700L);
            Toast.makeText(this, isChecked ? "本机 MCP 服务已开启" : "本机 MCP 服务已关闭",
                    Toast.LENGTH_SHORT).show();
        });
        binding.btnMcpCopyConfig.setOnClickListener(v -> {
            mcp.copyConfigToClipboard(this);
            Toast.makeText(this, "连接配置已复制。电脑端需使用 USB adb forward 转发本机端口。",
                    Toast.LENGTH_LONG).show();
        });
        binding.btnMcpRegenerateToken.setOnClickListener(v -> {
            android.app.Dialog dialog = new AlertDialog.Builder(this)
                    .setTitle("重新生成 MCP 令牌")
                    .setMessage("旧令牌将立即失效。继续后，需要在 MCP 客户端更新认证配置。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重新生成", (d, which) -> {
                        mcp.regenerateToken();
                        updateMcpInfo();
                        Toast.makeText(this, "新令牌已生成", Toast.LENGTH_SHORT).show();
                    }).show();
            DialogHelper.applyToAlertDialog(dialog, this);
        });
        updateMcpInfo();

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

        // build110: 评论预加载页数
        updateReplyPrefetchText();
        binding.layoutReplyPrefetch.setOnClickListener(v -> {
            String[] opts = {"关闭（不预加载）", "预加载 1 页", "预加载 2 页",
                    "预加载 3 页", "预加载 4 页", "预加载 5 页"};
            int cur = com.solosu.mtforum.ui.ReplyPrefetchPreferences.getPages(this);
            android.app.Dialog dialog = new AlertDialog.Builder(this)
                    .setTitle("评论预加载页数")
                    .setSingleChoiceItems(opts, cur, (d, which) -> {
                        com.solosu.mtforum.ui.ReplyPrefetchPreferences.setPages(this, which);
                        updateReplyPrefetchText();
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            DialogHelper.applyToAlertDialog(dialog, this);
        });

        // build110: 内置浏览器 UA（只影响应用内浏览器，论坛请求 UA 不动）
        updateBrowserUaText();
        binding.layoutBrowserUa.setOnClickListener(v -> showBrowserUaDialog());

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
        updateMcpInfo();
    }

    private void updateMcpInfo() {
        if (binding == null) return;
        McpServerManager mcp = McpServerManager.getInstance(getApplicationContext());
        boolean enabled = mcp.isEnabled();
        binding.switchMcp.setChecked(enabled);
        if (!enabled) {
            binding.tvMcpSummary.setText("默认关闭；仅本机回环访问，不含公网隧道");
        } else if (mcp.isRunning()) {
            binding.tvMcpSummary.setText("服务运行中；12 个只读工具，认证材料不会输出");
        } else if (mcp.isStarting()) {
            binding.tvMcpSummary.setText("服务正在启动；仅绑定 127.0.0.1");
        } else {
            binding.tvMcpSummary.setText("已开启但未运行；本机端口 9797 可能不可用");
        }
        int visibility = enabled ? View.VISIBLE : View.GONE;
        binding.tvMcpEndpoint.setText("端点：" + mcp.getEndpoint());
        binding.tvMcpTokenValue.setText(mcp.getTokenMasked());
        binding.tvMcpEndpoint.setVisibility(visibility);
        binding.layoutMcpToken.setVisibility(visibility);
        binding.tvMcpConnectionNote.setVisibility(visibility);
        binding.btnMcpCopyConfig.setVisibility(visibility);
        binding.btnMcpRegenerateToken.setVisibility(visibility);
    }

    private void updateDownloadModeText() {
        binding.tvDownloadMode.setText(com.solosu.mtforum.ui.DownloadPreferences.label(this));
    }

    /** 账号数量摘要 */
    private void updateReplyPrefetchText() {
        binding.tvReplyPrefetch.setText(
                com.solosu.mtforum.ui.ReplyPrefetchPreferences.label(this));
    }

    private void updateBrowserUaText() {
        binding.tvBrowserUa.setText(com.solosu.mtforum.ui.UserAgentPreferences.label(this));
    }

    /**
     * build110：内置浏览器 UA 选择。
     *
     * <p>两条内置项（移动版 / 电脑版）不可删；下面是用户自己加的自定义 UA，
     * 可就地新增，长按可删除。默认仍是「内置移动版」，与改动前行为一致。
     */
    private void showBrowserUaDialog() {
        java.util.List<com.solosu.mtforum.ui.UserAgentPreferences.Entry> items =
                com.solosu.mtforum.ui.UserAgentPreferences.entries(this);
        String[] labels = new String[items.size()];
        int checked = 0;
        String sel = com.solosu.mtforum.ui.UserAgentPreferences.selectedId(this);
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).label;
            if (items.get(i).id.equals(sel)) checked = i;
        }

        android.app.Dialog dialog = new AlertDialog.Builder(this)
                .setTitle("内置浏览器 UA")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    com.solosu.mtforum.ui.UserAgentPreferences.setSelectedId(
                            this, items.get(which).id);
                    updateBrowserUaText();
                    d.dismiss();
                })
                .setPositiveButton("新增自定义", null)
                .setNegativeButton("取消", null)
                .show();

        // 接管「新增自定义」，避免点一下就把弹窗关掉
        ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setSingleLine(false);
            input.setMaxLines(4);
            input.setHint("Mozilla/5.0 ...");
            android.app.Dialog add = new AlertDialog.Builder(this)
                    .setTitle("新增自定义 UA")
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存", (dd, w) -> {
                        String ua = input.getText().toString().trim();
                        if (ua.isEmpty()) return;
                        if (!com.solosu.mtforum.ui.UserAgentPreferences.addCustom(this, ua)) {
                            android.widget.Toast.makeText(this, "这条已经存在了",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        com.solosu.mtforum.ui.UserAgentPreferences.setSelectedId(this, ua);
                        updateBrowserUaText();
                        showBrowserUaDialog();   // 刷新列表
                    })
                    .show();
            DialogHelper.applyToAlertDialog(add, this);
        });

        // 长按自定义项删除
        ((AlertDialog) dialog).getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            com.solosu.mtforum.ui.UserAgentPreferences.Entry e = items.get(position);
            if (!e.custom) return true;
            new AlertDialog.Builder(this)
                    .setTitle("删除自定义 UA")
                    .setMessage(e.ua)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dd, w) -> {
                        com.solosu.mtforum.ui.UserAgentPreferences.removeCustom(this, e.ua);
                        updateBrowserUaText();
                        showBrowserUaDialog();
                    })
                    .show();
            return true;
        });

        DialogHelper.applyToAlertDialog(dialog, this);
    }

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