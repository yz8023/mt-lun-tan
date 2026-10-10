package com.solosu.mtforum.ui.space;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.ActivityUserProfileBinding;
import com.solosu.mtforum.model.UserProfile;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.ui.message.ChatActivity;
import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;
import com.solosu.mtforum.session.DiscuzUserActionManager;
import com.solosu.mtforum.session.FollowStateManager;
import com.solosu.mtforum.session.UserSessionManager;

/**
 * 用户个人资料页（原生 Activity）
 * 通过 Intent 接收 uid 参数，从服务端加载目标用户的资料并展示
 * 可从好友列表、关注列表、粉丝列表、帖子详情、评论区等入口跳转
 */
public class UserProfileActivity extends AppCompatActivity {

    private ActivityUserProfileBinding binding;
    private HttpClient httpClient;
    private String targetUid;
    // 仅保存本次网页端响应的状态，禁止从本地缓存推导按钮状态。
    private boolean serverFollowed;
    private boolean serverFollowStateKnown;
    private boolean requestInFlight;
    private volatile boolean destroyed = false;
    private boolean retryAfterSiteVerify;
    private boolean sessionRecoveryAttempted;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUserProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        FrostedGlassHelper.applyToCardViews(binding.getRoot(), this);

        httpClient = HttpClient.getInstance();

        targetUid = getIntent().getStringExtra("uid");
        String username = getIntent().getStringExtra("username");

        // 设置 Toolbar
        binding.toolbar.setTitle(username != null ? username : "用户资料");
        // ★ 移除返回箭头图标（仅保留点击返回功能）
        binding.toolbar.setNavigationIcon(null);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        if (targetUid == null) {
            Toast.makeText(this, "缺少用户ID", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 用户操作按钮与网页端空间页底部的“加好友/打招呼/聊天”对应。
        setupProfileActions();
        loadUserProfile();
    }

    @Override protected void onResume() {
        super.onResume();
        if (retryAfterSiteVerify && !requestInFlight) {
            retryAfterSiteVerify = false;
            binding.getRoot().postDelayed(this::loadUserProfile, 500L);
        }
    }

    private boolean canUpdateUi() {
        return !destroyed && !isFinishing()
                && (android.os.Build.VERSION.SDK_INT < 17 || !isDestroyed());
    }

    private void loadUserProfile() {
        if (!canUpdateUi() || requestInFlight || TextUtils.isEmpty(targetUid)) return;
        requestInFlight = true;
        binding.progressBar.setVisibility(View.VISIBLE);

        new Thread(() -> {
            UserProfile profile = null;
            String error = null;
            try {
                httpClient.syncFromCookieManager();
                // URL 保持稳定，避免 _ts 每次变化造成重复请求；相同主页请求可由 HttpClient 去重。
                String profileUrl = HttpClient.BASE_URL
                        + "home.php?mod=space&uid=" + targetUid + "&mobile=2";
                final long tPerfStart = System.currentTimeMillis();
                String html;
                com.solosu.mtforum.network.RequestThrottle.markForeground();
                try {
                    html = httpClient.get(profileUrl);
                } finally {
                    com.solosu.mtforum.network.RequestThrottle.clearForeground();
                }
                final long tPerfFetched = System.currentTimeMillis();
                com.solosu.mtforum.util.PerfLog.record("用户主页",
                        tPerfFetched - tPerfStart, 0, html == null ? 0 : html.length());

                if (com.solosu.mtforum.session.SiteAccessManager.isChallengePage(html)) {
                    error = "站点触发人机验证，正在打开内置验证页";
                } else if (ForumParser.isLoginPage(html)) {
                    error = "登录已过期";
                } else {
                    profile = ForumParser.parseUserProfile(html);
                    if (profile == null || profile.getUsername() == null) {
                        String altUrl = HttpClient.BASE_URL
                                + "home.php?mod=space&uid=" + targetUid
                                + "&do=profile&mobile=2";
                        String altHtml;
                        com.solosu.mtforum.network.RequestThrottle.markForeground();
                        try {
                            altHtml = httpClient.get(altUrl);
                        } finally {
                            com.solosu.mtforum.network.RequestThrottle.clearForeground();
                        }
                        if (com.solosu.mtforum.session.SiteAccessManager.isChallengePage(altHtml)) {
                            error = "站点触发人机验证，正在打开内置验证页";
                        } else if (ForumParser.isLoginPage(altHtml)) {
                            error = "登录已过期";
                        } else {
                            profile = ForumParser.parseUserProfile(altHtml);
                        }

                        // build112：以上两条都是移动模板（&mobile=2）。
                        // 本站在移动模板的空间页里根本不下发目标用户的数据 ——
                        // 目标用户名/uid 出现 0 次，页面里只剩全局头部当前登录用户的信息。
                        // 结果就是：头像兜底 img[src*=avatar] 抓到"我自己"的头像，
                        // 用户名也拿不到，于是「点谁都像在看自己的资料」。
                        // 桌面模板里数据是齐的，只是没有 Comiis 那套类名，
                        // 所以换用专门针对桌面页的解析。
                        if (profile == null || profile.getUsername() == null) {
                            String deskUrl = HttpClient.BASE_URL
                                    + "home.php?mod=space&uid=" + targetUid;
                            String deskHtml;
                            com.solosu.mtforum.network.RequestThrottle.markForeground();
                            try {
                                deskHtml = httpClient.get(deskUrl);
                            } finally {
                                com.solosu.mtforum.network.RequestThrottle.clearForeground();
                            }
                            if (com.solosu.mtforum.session.SiteAccessManager.isChallengePage(deskHtml)) {
                                error = "站点触发人机验证，正在打开内置验证页";
                            } else if (ForumParser.isLoginPage(deskHtml)) {
                                error = "登录已过期";
                            } else {
                                UserProfile deskProfile =
                                        ForumParser.parseUserProfileDesktop(deskHtml, targetUid);
                                if (deskProfile != null && deskProfile.getUsername() != null) {
                                    profile = deskProfile;
                                }
                            }
                        }
                    }

                    // 关注状态由当前资料页 HTML 中的 followmod 控件直接解析；不要再遍历
                    // 当前账号完整关注列表（最多 50 页），否则只是打开主页就会排队等待大量请求。
                }
            } catch (Exception e) {
                error = "加载失败，请稍后重试";
            }

            final UserProfile resultProfile = profile;
            final String resultError = error;
            runOnUiThread(() -> {
                if (!canUpdateUi()) return;
                requestInFlight = false;
                binding.progressBar.setVisibility(View.GONE);
                if (resultError != null) {
                    if (resultError.contains("人机验证")) {
                        retryAfterSiteVerify = true;
                        Toast.makeText(this, resultError, Toast.LENGTH_LONG).show();
                    } else if (resultError.contains("登录已过期") && !sessionRecoveryAttempted) {
                        sessionRecoveryAttempted = true;
                        Toast.makeText(this, "登录态已过期，正在自动重新登录…", Toast.LENGTH_SHORT).show();
                        String activeUid = com.solosu.mtforum.session.AccountManager.activeUid(this);
                        com.solosu.mtforum.session.SessionGuard.reloginAccount(this, activeUid, (ok, message) -> {
                            sessionRecoveryAttempted = false;
                            if (ok) loadUserProfile();
                            else {
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                                com.solosu.mtforum.ui.login.LoginBottomSheet.show(this, null);
                            }
                        });
                    } else {
                        Toast.makeText(this, resultError, Toast.LENGTH_SHORT).show();
                    }
                } else if (resultProfile != null && resultProfile.getUsername() != null) {
                    serverFollowStateKnown = resultProfile.isFollowStateKnown();
                    if (serverFollowStateKnown) serverFollowed = resultProfile.isFollowed();
                    displayProfile(resultProfile);
                } else {
                    Toast.makeText(this, "无法加载用户资料", Toast.LENGTH_SHORT).show();
                }
            });
        }, "user-profile-load").start();
    }

    /** 当前资料是否属于当前登录账号本人。 */
    private boolean isOwnProfile() {
        if (TextUtils.isEmpty(targetUid)) return false;
        String currentUid = UserSessionManager.getInstance().getUid(this);
        return !TextUtils.isEmpty(currentUid)
                && !"0".equals(currentUid)
                && currentUid.equals(targetUid);
    }
    private void setupProfileActions() {
        binding.layoutProfileActions.setVisibility(View.GONE);
        binding.btnProfileFriend.setOnClickListener(v -> showFriendDialog());
        binding.btnProfilePoke.setOnClickListener(v -> showPokeDialog());
        binding.btnProfileMessage.setOnClickListener(v -> openChat());
        binding.btnProfileBlock.setOnClickListener(v -> showBlockDialog());
        binding.btnProfileWall.setOnClickListener(v -> openWall());
    }

    private void openWall() {
        Intent intent = new Intent(this, UserWallActivity.class);
        intent.putExtra(UserWallActivity.EXTRA_UID, targetUid);
        String username = binding.tvUsername.getText() == null
                ? "" : binding.tvUsername.getText().toString();
        intent.putExtra(UserWallActivity.EXTRA_USERNAME, username);
        startActivity(intent);
    }

    /** 打开与当前资料用户的原生聊天页面，不再弹出“发私信”输入框。 */
    private void openChat() {
        if (!canUseProfileAction()) return;

        String chatName = binding.tvUsername.getText() == null
                ? "用户"
                : binding.tvUsername.getText().toString().trim();
        if (TextUtils.isEmpty(chatName)) chatName = "用户";

        Intent intent = new Intent(this, ChatActivity.class);
        // Comiis 模板使用 touid 作为会话标识，ChatActivity.EXTRA_PMID 实际传入目标 UID。
        intent.putExtra(ChatActivity.EXTRA_PMID, targetUid);
        intent.putExtra(ChatActivity.EXTRA_UID, targetUid);
        intent.putExtra(ChatActivity.EXTRA_NAME, chatName);
        startActivity(intent);
    }


    private boolean canUseProfileAction() {
        if (TextUtils.isEmpty(targetUid)) return false;
        if (!FollowStateManager.isLoggedIn(this)) {
            com.solosu.mtforum.ui.login.LoginBottomSheet.show(this, null);
            return false;
        }
        return true;
    }

    private EditText makeInput(String hint, boolean multiLine) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(!multiLine);
        if (multiLine) {
            input.setMinLines(3);
            input.setGravity(android.view.Gravity.TOP);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        return input;
    }

    private void showFriendDialog() {
        if (!canUseProfileAction()) return;
        EditText input = makeInput("附加留言（可选）", true);
        android.app.Dialog alertDialog1 = new AlertDialog.Builder(this)
                .setTitle("添加好友")
                .setView(input)
                .setPositiveButton("发送申请", (dialog, which) -> {
                    String note = input.getText().toString().trim();
                    runUserAction("正在发送好友申请…", () ->
                            DiscuzUserActionManager.addFriend(this, targetUid, note),
                            "好友申请已发送", "好友申请发送失败");
                })
                .show();
                DialogHelper.applyToAlertDialog(alertDialog1, this);
    }

    private void showPokeDialog() {
        if (!canUseProfileAction()) return;
        EditText input = makeInput("招呼内容，例如：你好！", false);
        input.setText("你好！");
        android.app.Dialog alertDialog2 = new AlertDialog.Builder(this)
                .setTitle("打招呼")
                .setView(input)
                .setPositiveButton("发送", (dialog, which) -> {
                    String message = input.getText().toString().trim();
                    if (TextUtils.isEmpty(message)) {
                        Toast.makeText(this, "请输入招呼内容", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runUserAction("正在发送招呼…", () ->
                            DiscuzUserActionManager.sendPoke(this, targetUid, message),
                            "打招呼成功", "打招呼失败");
                })
                .show();
                DialogHelper.applyToAlertDialog(alertDialog2, this);
    }

    private void showBlockDialog() {
        if (!canUseProfileAction()) return;
        String name = binding.tvUsername.getText() == null ? "该用户"
                : binding.tvUsername.getText().toString();
        android.app.Dialog alertDialog3 = new AlertDialog.Builder(this)
                .setTitle("屏蔽用户")
                .setMessage("屏蔽“" + name + "”后将减少看到该用户的内容（加入黑名单）。"
                        + "如需恢复，可再次打开本窗口选择“取消屏蔽”。")
                .setPositiveButton("屏蔽", (dialog, which) ->
                        runUserAction("正在屏蔽用户…", () ->
                                DiscuzUserActionManager.blockUser(this, targetUid),
                                "已屏蔽该用户", "屏蔽用户失败"))
                .setNeutralButton("取消屏蔽", (dialog, which) ->
                        runUserAction("正在取消屏蔽…", () ->
                                DiscuzUserActionManager.unblockUser(this, targetUid),
                                "已取消屏蔽", "取消屏蔽失败"))
                .show();
                DialogHelper.applyToAlertDialog(alertDialog3, this);
    }

    private interface UserAction {
        boolean run();
    }

    private void runUserAction(String loadingText, UserAction action,
                               String successText, String failureText) {
        binding.layoutProfileActions.setEnabled(false);
        Toast.makeText(this, loadingText, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean success = action.run();
            runOnUiThread(() -> {
                binding.layoutProfileActions.setEnabled(true);
                Toast.makeText(this, success ? successText : failureText,
                        Toast.LENGTH_SHORT).show();
                if (success && "已屏蔽该用户".equals(successText)) finish();
            });
        }).start();
    }
    private void displayProfile(UserProfile profile) {
        // 自己的资料不显示“关注”以及加好友、打招呼、发私信、屏蔽用户等针对他人的操作。
        boolean ownProfile = isOwnProfile();
        boolean loggedIn = !ownProfile
                && FollowStateManager.isLoggedIn(this)
                && !TextUtils.isEmpty(targetUid);
        binding.layoutProfileActions.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        binding.btnProfileFollow.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        if (loggedIn) {
            if (serverFollowStateKnown) {
                binding.btnProfileFollow.setEnabled(!requestInFlight);
                binding.btnProfileFollow.setText(serverFollowed
                        ? R.string.action_followed : R.string.action_follow);
                binding.btnProfileFollow.setOnClickListener(v -> toggleFollowProfile());
            } else {
                // 服务端没有明确状态时，禁止操作，也不能伪显示为“关注”。
                binding.btnProfileFollow.setEnabled(false);
                binding.btnProfileFollow.setText("状态未提供");
                binding.btnProfileFollow.setOnClickListener(null);
            }
        } else {
            binding.btnProfileFollow.setOnClickListener(null);
            binding.btnProfileFollow.setEnabled(false);
        }

        // 更新标题
        if (profile.getUsername() != null) {
            binding.toolbar.setTitle(profile.getUsername());
        }

        // 头像
        String avatarUrl = profile.getAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(this)
                    .load(com.solosu.mtforum.util.ForumImageLoader.model(avatarUrl))
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(binding.ivAvatar);
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_account);
        }

        // 用户名
        binding.tvUsername.setText(profile.getUsername() != null ? profile.getUsername() : "未知用户");

        // UID
        binding.tvUid.setText("UID: " + (profile.getUid() != null ? profile.getUid() : "—"));

        // 等级
        binding.tvLevel.setText(profile.getLevel() != null ? profile.getLevel() : "");

        // 用户组
        binding.tvGroup.setText(profile.getGroupName() != null ? profile.getGroupName() : "");

        // 五列统计
        binding.tvThreads.setText(String.valueOf(profile.getThreads()));
        binding.tvReplies.setText(String.valueOf(profile.getPosts()));
        binding.tvFriends.setText(String.valueOf(profile.getFriends()));
        // 目标用户资料页必须显示目标用户自己的服务端关注数，不能套用当前账号的本地关注集合。
        binding.tvFollowing.setText(String.valueOf(profile.getFollowing()));
        binding.tvFollowers.setText(String.valueOf(profile.getFollowers()));

        // 积分/金币/在线时长
        binding.tvCredits.setText(String.valueOf(profile.getCredits()));
        binding.tvGold.setText(String.valueOf(profile.getGold()));
        binding.tvOnlineTime.setText(profile.getOnlineTime() != null ? profile.getOnlineTime() : "0小时");

        // 账号信息
        binding.tvRegDate.setText(profile.getRegDate() != null ? profile.getRegDate() : "—");
        binding.tvLastVisit.setText(profile.getLastVisit() != null ? profile.getLastVisit() : "—");

        // 帖子统计项：查看该用户发布的全部帖子
        binding.layoutThreads.setOnClickListener(v -> {
            Intent intent = new Intent(this, SpaceThreadListActivity.class);
            intent.putExtra("mode", "uid_threads");
            intent.putExtra("uid", targetUid);
            intent.putExtra("username", binding.tvUsername.getText().toString());
            startActivity(intent);
        });
        binding.layoutReplies.setOnClickListener(v -> {
            Intent intent = new Intent(this, SpaceThreadListActivity.class);
            intent.putExtra("mode", "uid_replies");
            intent.putExtra("uid", targetUid);
            intent.putExtra("username", binding.tvUsername.getText().toString());
            startActivity(intent);
        });
        binding.layoutFollowers.setOnClickListener(v -> {
            Intent intent = new Intent(this, FriendListActivity.class);
            intent.putExtra("mode", "followers");
            intent.putExtra("uid", targetUid);
            intent.putExtra("username", binding.tvUsername.getText().toString());
            startActivity(intent);
        });

        // 性别
        String genderText = "保密";
        if ("boy".equals(profile.getGender())) {
            genderText = "男 男";
        } else if ("girl".equals(profile.getGender())) {
            genderText = "女 女";
        }
        binding.tvGender.setText(genderText);
    }

    private void toggleFollowProfile() {
        if (TextUtils.isEmpty(targetUid) || requestInFlight || !serverFollowStateKnown) return;
        // 切换目标状态只能基于当前网页端刚返回的状态，不能读取本地 SharedPreferences。
        final boolean targetState = !serverFollowed;
        requestInFlight = true;
        binding.btnProfileFollow.setEnabled(false);
        new Thread(() -> {
            boolean success = FollowStateManager.syncFollow(UserProfileActivity.this, targetUid, targetState);
            if (success) {
                // 操作后立即从网页端读取并确认，确认前不直接修改按钮状态。
                runOnUiThread(() -> {
                    Toast.makeText(this, targetState
                            ? R.string.action_follow_success
                            : R.string.action_unfollow_success, Toast.LENGTH_SHORT).show();
                    requestInFlight = false;
                    loadUserProfile();
                });
            } else {
                runOnUiThread(() -> {
                    requestInFlight = false;
                    binding.btnProfileFollow.setEnabled(serverFollowStateKnown);
                    Toast.makeText(this, "关注操作失败，请稍后重试", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        requestInFlight = false;
        binding = null;
        super.onDestroy();
    }
}