package com.solosu.mtforum.ui.space;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.WallMessageAdapter;
import com.solosu.mtforum.databinding.ActivityUserWallBinding;
import com.solosu.mtforum.model.Message;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.DiscuzUserActionManager;
import com.solosu.mtforum.session.FollowStateManager;
import com.solosu.mtforum.ui.login.LoginBottomSheet;
import com.solosu.mtforum.ui.widget.DialogHelper;

import java.util.List;

/** 查看、发布和管理用户空间留言板。 */
public class UserWallActivity extends AppCompatActivity {

    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_USERNAME = "username";

    private ActivityUserWallBinding binding;
    private HttpClient httpClient;
    private WallMessageAdapter adapter;
    private String targetUid;
    private int currentPage = 1;
    private int maxPage = 1;
    private int requestGeneration;
    private int pendingPage = -1;
    private boolean loading;
    private boolean sending;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUserWallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        targetUid = getIntent().getStringExtra(EXTRA_UID);
        String username = getIntent().getStringExtra(EXTRA_USERNAME);
        if (!isDigits(targetUid)) {
            Toast.makeText(this, "缺少有效的用户 UID", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        httpClient = HttpClient.getInstance();
        binding.toolbarWall.setTitle(TextUtils.isEmpty(username) ? "留言板" : username + " 的留言板");
        binding.toolbarWall.setNavigationIcon(R.drawable.ic_back);
        binding.toolbarWall.setNavigationOnClickListener(v -> finish());

        adapter = new WallMessageAdapter(this);
        adapter.setListener(new WallMessageAdapter.Listener() {
            @Override public void onAuthorClick(Message message) { openAuthor(message); }
            @Override public void onReply(Message message) { showMessageDialog(message, false); }
            @Override public void onEdit(Message message) { showMessageDialog(message, true); }
            @Override public void onDelete(Message message) { confirmDelete(message); }
        });
        binding.recyclerWall.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerWall.setAdapter(adapter);

        binding.btnWallPrevious.setOnClickListener(v -> {
            if (!loading && currentPage > 1) loadPage(currentPage - 1);
        });
        binding.btnWallNext.setOnClickListener(v -> {
            if (!loading && currentPage < maxPage) loadPage(currentPage + 1);
        });
        binding.etWallMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!sending) binding.btnWallSend.setEnabled(!TextUtils.isEmpty(s.toString().trim()));
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        binding.btnWallSend.setOnClickListener(v -> sendMessage());
        loadPage(1);
    }

    private void loadPage(int page) {
        if (destroyed || page < 1) return;
        if (loading) {
            pendingPage = page;
            return;
        }
        int generation = ++requestGeneration;
        loading = true;
        updatePagination();
        binding.progressWall.setVisibility(View.VISIBLE);
        binding.tvWallEmpty.setVisibility(View.GONE);

        new Thread(() -> {
            String html = null;
            String error = null;
            try {
                if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager();
                html = httpClient.get(HttpClient.BASE_URL + "home.php?mod=space&uid="
                        + targetUid + "&do=wall&mobile=2&page=" + page);
                if (ForumParser.isLoginPage(html)) error = "登录已过期，请先登录";
            } catch (Exception e) {
                error = "留言板加载失败，请稍后重试";
            }
            final String result = html;
            final String message = error;
            runOnUiThread(() -> {
                if (destroyed || isFinishing() || generation != requestGeneration) return;
                loading = false;
                binding.progressWall.setVisibility(View.GONE);
                if (message != null) {
                    adapter.setItems(null);
                    binding.tvWallEmpty.setText(message);
                    binding.tvWallEmpty.setVisibility(View.VISIBLE);
                    updatePagination();
                    loadPendingPage();
                    return;
                }
                List<Message> items = ForumParser.parseWallMessages(result);
                currentPage = page;
                maxPage = Math.max(currentPage, ForumParser.parseWallMaxPage(result));
                adapter.setItems(items);
                binding.tvWallEmpty.setText("暂无留言，来抢沙发吧～");
                binding.tvWallEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                if (!items.isEmpty()) binding.recyclerWall.scrollToPosition(0);
                updatePagination();
                loadPendingPage();
            });
        }, "wall-load-" + page).start();
    }

    private void loadPendingPage() {
        if (pendingPage < 1 || destroyed) return;
        int page = pendingPage;
        pendingPage = -1;
        loadPage(page);
    }

    private void updatePagination() {
        if (binding == null) return;
        boolean multiplePages = maxPage > 1;
        binding.layoutWallPagination.setVisibility(multiplePages ? View.VISIBLE : View.GONE);
        binding.tvWallPage.setText("第 " + currentPage + " / " + maxPage + " 页");
        binding.btnWallPrevious.setEnabled(!loading && currentPage > 1);
        binding.btnWallPrevious.setAlpha(!loading && currentPage > 1 ? 1f : 0.38f);
        binding.btnWallNext.setEnabled(!loading && currentPage < maxPage);
        binding.btnWallNext.setAlpha(!loading && currentPage < maxPage ? 1f : 0.38f);
    }

    private void sendMessage() {
        if (sending || destroyed) return;
        String message = binding.etWallMessage.getText() == null
                ? "" : binding.etWallMessage.getText().toString().trim();
        if (TextUtils.isEmpty(message)) {
            Toast.makeText(this, "请输入留言内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!FollowStateManager.isLoggedIn(this)) {
            LoginBottomSheet.show(this, null);
            return;
        }
        sending = true;
        binding.btnWallSend.setEnabled(false);
        new Thread(() -> {
            boolean success = DiscuzUserActionManager.postWallMessage(
                    getApplicationContext(), targetUid, message);
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) return;
                sending = false;
                binding.btnWallSend.setEnabled(!TextUtils.isEmpty(
                        binding.etWallMessage.getText().toString().trim()));
                if (success) {
                    binding.etWallMessage.setText("");
                    Toast.makeText(this, "留言成功", Toast.LENGTH_SHORT).show();
                    loadPage(1);
                } else {
                    Toast.makeText(this, "留言失败，请确认登录状态后重试", Toast.LENGTH_LONG).show();
                }
            });
        }, "wall-send").start();
    }

    private void showMessageDialog(Message item, boolean edit) {
        if (item == null || !isDigits(item.getPmid())) {
            Toast.makeText(this, "无法识别这条留言", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!FollowStateManager.isLoggedIn(this)) {
            LoginBottomSheet.show(this, null);
            return;
        }
        EditText input = new EditText(this);
        input.setMinLines(3);
        input.setMaxLines(7);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(edit ? "编辑留言内容" : "回复留言内容");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        if (edit && item.getSummary() != null) input.setText(item.getSummary());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(edit ? "编辑留言" : "回复留言")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(edit ? "保存" : "回复", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = input.getText() == null ? "" : input.getText().toString().trim();
                if (TextUtils.isEmpty(text)) {
                    input.setError("请输入内容");
                    return;
                }
                dialog.dismiss();
                runWallAction(edit ? "正在保存留言…" : "正在回复留言…",
                        () -> edit
                                ? DiscuzUserActionManager.editWallMessage(getApplicationContext(), item.getPmid(), text)
                                : DiscuzUserActionManager.replyWallMessage(getApplicationContext(), item.getPmid(), text),
                        edit ? "留言已更新" : "回复成功", edit ? "编辑留言失败" : "回复失败");
            });
        });
        dialog.show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    private void confirmDelete(Message item) {
        if (item == null || !isDigits(item.getPmid())) {
            Toast.makeText(this, "无法识别这条留言", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!FollowStateManager.isLoggedIn(this)) {
            LoginBottomSheet.show(this, null);
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除留言")
                .setMessage("确定删除这条留言吗？此操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, which) -> runWallAction("正在删除留言…",
                        () -> DiscuzUserActionManager.deleteWallMessage(
                                getApplicationContext(), item.getPmid()),
                        "留言已删除", "删除留言失败"))
                .show();
        DialogHelper.applyToAlertDialog(dialog, this);
    }

    private interface WallAction { boolean run(); }

    private void runWallAction(String progress, WallAction action, String success, String failure) {
        Toast.makeText(this, progress, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = action.run();
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) return;
                Toast.makeText(this, ok ? success : failure, Toast.LENGTH_SHORT).show();
                if (ok) loadPage(currentPage);
            });
        }, "wall-action").start();
    }

    private void openAuthor(Message message) {
        if (message == null || !isDigits(message.getAuthorUid())) return;
        android.content.Intent intent = new android.content.Intent(this, UserProfileActivity.class);
        intent.putExtra("uid", message.getAuthorUid());
        intent.putExtra("username", message.getAuthor());
        startActivity(intent);
    }

    private static boolean isDigits(String value) {
        return !TextUtils.isEmpty(value) && value.matches("\\d+");
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        requestGeneration++;
        super.onDestroy();
    }
}
