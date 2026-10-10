package com.solosu.mtforum.ui.login;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.UserProfile;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.AccountManager;
import com.solosu.mtforum.session.UserSessionManager;
import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录底部弹窗(与回复弹窗同风格)。
 * 支持两种模式:账号密码登录 / Cookie 粘贴登录。
 */
public class LoginBottomSheet {

    @FunctionalInterface
    public interface OnLoginListener {
        void onLoginSuccess();
    }

    private static BottomSheetDialog current;

    private LoginBottomSheet() {
    }

    public static void show(Activity activity, OnLoginListener listener) {
        show(activity, null, listener);
    }

    /**
     * build60: 支持预填用户名，账号列表里点"重新登录"时省得用户再敲一遍。
     */
    public static void show(Activity activity, String presetUsername, OnLoginListener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        dismissCurrent();
        final BottomSheetDialog dialog = new BottomSheetDialog(activity);
        current = dialog;
        final WeakReference<Activity> activityRef = new WeakReference<>(activity);
        final View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_login_bottom_sheet, null);

        MaterialCardView dialogCard = dialogView.findViewById(R.id.dialog_card);
        ImageView ivClose = dialogView.findViewById(R.id.iv_close);
        final LinearLayout formNormal = dialogView.findViewById(R.id.login_form_normal);
        final LinearLayout formCookie = dialogView.findViewById(R.id.login_form_cookie);
        TextInputEditText etUsername = dialogView.findViewById(R.id.et_username);
        TextInputEditText etPassword = dialogView.findViewById(R.id.et_password);
        TextInputEditText etCookie = dialogView.findViewById(R.id.et_cookie);
        TextInputLayout tilUsername = dialogView.findViewById(R.id.til_username);
        TextInputLayout tilPassword = dialogView.findViewById(R.id.til_password);
        TextInputLayout tilCookie = dialogView.findViewById(R.id.til_cookie);
        MaterialButton btnLogin = dialogView.findViewById(R.id.btn_login);
        ProgressBar progressBar = dialogView.findViewById(R.id.progress_bar);
        TextView tvError = dialogView.findViewById(R.id.tv_error);
        final TextView tvToggle = dialogView.findViewById(R.id.tv_toggle_mode);
        final MaterialCheckBox cbRemember = dialogView.findViewById(R.id.cb_remember_password);

        if (!TextUtils.isEmpty(presetUsername)) {
            etUsername.setText(presetUsername);
            // The password is already encrypted in Android Keystore when “记住密码” was used.
            // Reuse it locally instead of forcing the user to type the same account twice.
            for (AccountManager.Account account : AccountManager.list(activity)) {
                if (presetUsername.equals(account.username)
                        || presetUsername.equals(account.credentialName())) {
                    etUsername.setText(account.credentialName());
                    String saved = AccountManager.decryptPassword(account);
                    if (!TextUtils.isEmpty(saved)) {
                        etPassword.setText(saved);
                        cbRemember.setChecked(true);
                    }
                    break;
                }
            }
            etPassword.requestFocus();
        }

        // 当前模式: false = 账号密码, true = Cookie
        final boolean[] isCookieMode = new boolean[1];

        if (dialogCard != null) {
            FrostedGlassHelper.applyToCardViews(dialogCard, activity);
        }

        dialog.setContentView(dialogView);
        DialogHelper.applyToBottomSheet(dialog, dialogView, activity);
        dialog.setOnDismissListener(d -> {
            if (current == dialog) {
                current = null;
            }
        });

        // Kept for the call signature. Password login now uses an isolated MtSignApi session;
        // opening this sheet must never clear the freshly verified global Cookie again.
        final String[] formhashRef = new String[2];

        ivClose.setOnClickListener(v -> dialog.dismiss());

        // 切换登录模式
        tvToggle.setOnClickListener(v -> {
            isCookieMode[0] = !isCookieMode[0];
            boolean cookie = isCookieMode[0];
            formNormal.setVisibility(cookie ? View.GONE : View.VISIBLE);
            formCookie.setVisibility(cookie ? View.VISIBLE : View.GONE);
            tvError.setVisibility(View.GONE);
            if (cookie) {
                tvToggle.setText("账号密码登录");
                btnLogin.setText("Cookie 登录");
            } else {
                tvToggle.setText("Cookie 登录");
                btnLogin.setText(activity.getString(R.string.action_login));
            }
        });

        // 键盘回车触发登录
        etPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                performLogin(dialog, activityRef, formNormal, formCookie, etUsername, etPassword,
                        etCookie, tilUsername, tilPassword, tilCookie, btnLogin, progressBar,
                        tvError, formhashRef, isCookieMode, cbRemember, listener);
                return true;
            }
            return false;
        });

        btnLogin.setOnClickListener(v -> performLogin(dialog, activityRef, formNormal, formCookie,
                etUsername, etPassword, etCookie, tilUsername, tilPassword, tilCookie, btnLogin,
                progressBar, tvError, formhashRef, isCookieMode, cbRemember, listener));

        dialog.setOnShowListener(d -> {
            View parent = (View) dialogView.getParent();
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior behavior = BottomSheetBehavior.from(parent);
                parent.getViewTreeObserver().addOnGlobalLayoutListener(
                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                parent.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                int contentHeight = dialogView.getHeight();
                                if (contentHeight > 0) {
                                    int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.90d);
                                    behavior.setPeekHeight(Math.min(contentHeight + dp(activity, 48), maxHeight));
                                    behavior.setSkipCollapsed(true);
                                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                                }
                            }
                        });
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        etUsername.setOnFocusChangeListener((v, focused) -> {
            if (focused) expandForKeyboard(dialogView);
        });
        etPassword.setOnFocusChangeListener((v, focused) -> {
            if (focused) expandForKeyboard(dialogView);
        });
        etCookie.setOnFocusChangeListener((v, focused) -> {
            if (focused) expandForKeyboard(dialogView);
        });
    }

    private static void expandForKeyboard(View dialogView) {
        try {
            View parent = (View) dialogView.getParent();
            if (parent != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(parent);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        } catch (Exception ignored) {}
    }

    private static int dp(Activity activity, float dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }

    private static void dismissCurrent() {
        if (current != null && current.isShowing()) {
            current.dismiss();
        }
        current = null;
    }

    private static void performLogin(final BottomSheetDialog dialog,
                                     final WeakReference<Activity> activityRef,
                                     final LinearLayout formNormal,
                                     final LinearLayout formCookie,
                                     final TextInputEditText etUsername,
                                     final TextInputEditText etPassword,
                                     final TextInputEditText etCookie,
                                     final TextInputLayout tilUsername,
                                     final TextInputLayout tilPassword,
                                     final TextInputLayout tilCookie,
                                     final MaterialButton btnLogin,
                                     final ProgressBar progressBar,
                                     final TextView tvError,
                                     final String[] formhashRef,
                                     final boolean[] isCookieMode,
                                     final MaterialCheckBox cbRemember,
                                     final OnLoginListener listener) {
        final Activity activity = activityRef != null ? activityRef.get() : null;
        if (activity == null || activity.isFinishing()) {
            return;
        }

        if (isCookieMode[0]) {
            doCookieLogin(dialog, activity, formNormal, formCookie, etCookie, tilCookie,
                    btnLogin, progressBar, tvError, listener);
        } else {
            doPasswordLogin(dialog, activity, etUsername, etPassword, tilUsername, tilPassword,
                    btnLogin, progressBar, tvError, formhashRef,
                    cbRemember == null || cbRemember.isChecked(), listener);
        }
    }

    // build58: 兜底时提取响应正文片段, 让用户看到真实拒绝原因
    private static String snippet(String html) {
        if (html == null) return "(无响应)";
        try {
            String text = android.text.TextUtils.isEmpty(html) ? "" : html
                    .replaceAll("(?s)<script.*?</script>", " ")
                    .replaceAll("(?s)<style.*?</style>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&#\\d+;", " ").replaceAll("&[a-z]+;", " ")
                    .replaceAll("\\s+", " ").trim();
            return text.length() > 80 ? text.substring(0, 80) : text;
        } catch (Exception e) {
            return "(解析失败)";
        }
    }

    // ==================== 密码登录 ====================
    private static void doPasswordLogin(final BottomSheetDialog dialog,
                                        final Activity activity,
                                        final TextInputEditText etUsername,
                                        final TextInputEditText etPassword,
                                        final TextInputLayout tilUsername,
                                        final TextInputLayout tilPassword,
                                        final MaterialButton btnLogin,
                                        final ProgressBar progressBar,
                                        final TextView tvError,
                                        final String[] formhashRef,
                                        final boolean rememberPassword,
                                        final OnLoginListener listener) {
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString();

        if (TextUtils.isEmpty(username)) {
            tilUsername.setError("请输入帐号");
            etUsername.requestFocus();
            return;
        }
        tilUsername.setError(null);
        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("请输入密码");
            etPassword.requestFocus();
            return;
        }
        tilPassword.setError(null);

        setLoading(btnLogin, progressBar, true);
        tvError.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                String protection = com.solosu.mtforum.session.SiteAccessManager.protectionCookieHeader(
                        HttpClient.getInstance().getCookieHeader());
                com.solosu.mtforum.session.MtSignApi.LoginResult result =
                        com.solosu.mtforum.session.MtSignApi.login(username, password, protection);
                if (!result.success || TextUtils.isEmpty(result.cookie)) {
                    final String msg = TextUtils.isEmpty(result.message)
                            ? "登录失败，请检查账号状态" : result.message;
                    runOnUi(activity, () -> {
                        setLoading(btnLogin, progressBar, false);
                        showError(tvError, msg);
                    });
                    return;
                }

                // Replace the active account only after isolated login succeeds. This preserves
                // the just-obtained ESA clearance while avoiding old-account auth contamination.
                HttpClient client = HttpClient.getInstance();
                client.clearCookies();
                client.applyCookiesFromString(result.cookie);
                client.commitCookieStore(activity);
                client.syncToCookieManager();
                for (AccountManager.Account saved : AccountManager.list(activity)) {
                    if (username.equals(saved.credentialName()) || username.equals(saved.username)) {
                        AccountManager.updateCookieHeader(activity, saved.uid, result.cookie);
                        if (rememberPassword) AccountManager.setPassword(activity, saved.uid, password);
                        AccountManager.setActiveUid(activity, saved.uid);
                        break;
                    }
                }
                finishLoginSuccess(dialog, activity, username,
                        rememberPassword ? password : null,
                        btnLogin, progressBar, listener);
            } catch (Exception e) {
                runOnUi(activity, () -> {
                    setLoading(btnLogin, progressBar, false);
                    showError(tvError, "登录失败：" + (TextUtils.isEmpty(e.getMessage())
                            ? "网络异常，请稍后重试" : e.getMessage()));
                });
            }
        }).start();
    }

    // ==================== Cookie 登录 ====================

    private static void doCookieLogin(final BottomSheetDialog dialog,
                                      final Activity activity,
                                      final LinearLayout formNormal,
                                      final LinearLayout formCookie,
                                      final TextInputEditText etCookie,
                                      final TextInputLayout tilCookie,
                                      final MaterialButton btnLogin,
                                      final ProgressBar progressBar,
                                      final TextView tvError,
                                      final OnLoginListener listener) {
        final String cookieStr = etCookie.getText().toString().trim();

        if (TextUtils.isEmpty(cookieStr)) {
            tilCookie.setError("请粘贴 Cookie 内容");
            etCookie.requestFocus();
            return;
        }
        tilCookie.setError(null);

        setLoading(btnLogin, progressBar, true);
        tvError.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                // 清除旧 Cookie,设置新 Cookie
                HttpClient.getInstance().clearCookies();
                HttpClient.getInstance().applyCookiesFromString(cookieStr);

                // 验证是否包含 _auth cookie(登录态标志)
                if (!HttpClient.getInstance().isLoggedIn()) {
                    runOnUi(activity, () -> {
                        setLoading(btnLogin, progressBar, false);
                        showError(tvError, "Cookie 无效或已过期,请检查后重试");
                    });
                    return;
                }

                // 进一步验证:请求个人中心确认未跳转登录页
                String verifyUrl = ForumParser.getBaseDomain() + "home.php?mod=space&do=profile&mobile=2";
                String verifyHtml;
                try {
                    verifyHtml = HttpClient.getInstance().get(verifyUrl);
                } catch (Exception e) {
                    runOnUi(activity, () -> {
                        setLoading(btnLogin, progressBar, false);
                        showError(tvError, "Cookie 验证失败:" + (e.getMessage() != null ? e.getMessage() : "网络异常"));
                    });
                    return;
                }

                if (ForumParser.isLoginPage(verifyHtml)) {
                    runOnUi(activity, () -> {
                        setLoading(btnLogin, progressBar, false);
                        showError(tvError, "Cookie 已过期,请重新获取");
                    });
                    return;
                }

                // Cookie 有效,保存并获取用户信息
                HttpClient.getInstance().commitCookieStore(activity);
                // build106: Cookie 登录不经过 WebView，登录态只在 OkHttp 罐子里；
                // 推进 WebView CookieManager，正文 WebView 的附件图才能带会话加载。
                HttpClient.getInstance().syncToCookieManager();
                try {
                    UserProfile profile = ForumParser.parseUserProfile(verifyHtml);
                    Map<String, String> loginInfo = new HashMap<>();
                    loginInfo.put("username", profile != null && profile.getUsername() != null
                            ? profile.getUsername() : "用户");
                    loginInfo.put("uid", profile != null && profile.getUid() != null ? profile.getUid() : "0");
                    loginInfo.put("avatarUrl", profile != null && profile.getAvatarUrl() != null
                            ? profile.getAvatarUrl() : "");
                    loginInfo.put("level", profile != null && profile.getLevel() != null ? profile.getLevel() : "");
                    UserSessionManager.getInstance().saveLoginInfo(activity, loginInfo);
                    // build60: Cookie 登录同样入账号库（无密码，掉线需手动重登）
                    AccountManager.saveCurrent(activity,
                            loginInfo.get("uid"), loginInfo.get("username"),
                            loginInfo.get("avatarUrl"), loginInfo.get("level"));
                    UserSessionManager.getInstance().clearSignInDate(activity);
                } catch (Exception ignored) {
                }

                runOnUi(activity, () -> {
                    setLoading(btnLogin, progressBar, false);
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    if (listener != null) {
                        listener.onLoginSuccess();
                    }
                });
            } catch (Exception e) {
                runOnUi(activity, () -> {
                    setLoading(btnLogin, progressBar, false);
                    showError(tvError, "Cookie 登录失败:" + (TextUtils.isEmpty(e.getMessage())
                            ? "请稍后重试" : e.getMessage()));
                });
            }
        }).start();
    }

    // ==================== 公共方法 ====================

    private static void finishLoginSuccess(BottomSheetDialog dialog, Activity activity,
                                           String username, String plainPassword,
                                           MaterialButton btnLogin,
                                           ProgressBar progressBar, OnLoginListener listener) {
        // 持久化 Cookie
        // build59: 先落盘再后续操作, 防崩溃留下半状态
        HttpClient.getInstance().commitCookieStore(activity);
        // 获取用户信息
        try {
            String spaceHtml = HttpClient.getInstance().get(
                    ForumParser.getBaseDomain() + "home.php?mod=space&do=profile&mobile=2");
            UserProfile profile = ForumParser.parseUserProfile(spaceHtml);
            if (profile != null && profile.getUsername() == null) {
                profile.setUsername(username);
            }
            Map<String, String> loginInfo = new HashMap<>();
            loginInfo.put("username", profile != null && profile.getUsername() != null
                    ? profile.getUsername() : username);
            loginInfo.put("uid", profile != null && profile.getUid() != null ? profile.getUid() : "0");
            loginInfo.put("avatarUrl", profile != null && profile.getAvatarUrl() != null
                    ? profile.getAvatarUrl() : "");
            loginInfo.put("level", profile != null && profile.getLevel() != null ? profile.getLevel() : "");
            UserSessionManager.getInstance().saveLoginInfo(activity, loginInfo);
            // build60: 登录成功即入账号库，可选连密码一起加密托管
            AccountManager.saveCurrent(activity,
                    loginInfo.get("uid"), loginInfo.get("username"),
                    loginInfo.get("avatarUrl"), loginInfo.get("level"),
                    plainPassword, username);
            UserSessionManager.getInstance().clearSignInDate(activity);
        } catch (Exception ignored) {
        }

        runOnUi(activity, () -> {
            setLoading(btnLogin, progressBar, false);
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            if (listener != null) {
                listener.onLoginSuccess();
            }
        });
    }

    private static void setLoading(MaterialButton btn, ProgressBar pb, boolean show) {
        if (btn == null || pb == null) return;
        pb.setVisibility(show ? View.VISIBLE : View.GONE);
        btn.setEnabled(!show);
        btn.setText(show ? "" : btn.getContext().getString(R.string.action_login));
    }

    private static void showError(TextView tv, String msg) {
        if (tv == null) return;
        tv.setText(msg);
        tv.setVisibility(View.VISIBLE);
    }

    private static void runOnUi(Activity activity, Runnable runnable) {
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(runnable);
        }
    }
}
