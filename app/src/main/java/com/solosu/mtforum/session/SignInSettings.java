package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 签到相关设置集中管理（build60 新增）。
 *
 * <p>沿用旧的 {@code app_settings} 文件，保证老用户的"自动签到"开关不丢。
 */
public final class SignInSettings {

    public static final String PREF_NAME = "app_settings";

    /** 进 App 自动签到（旧键，沿用） */
    public static final String KEY_AUTO_SIGN_IN = "auto_sign_in_enabled";
    /** 自动签到时是否覆盖所有已启用账号 */
    private static final String KEY_ALL_ACCOUNTS = "sign_in_all_accounts";
    /** 每天定时后台签到 */
    private static final String KEY_SCHEDULE = "sign_in_schedule_enabled";
    private static final String KEY_HOUR = "sign_in_hour";
    private static final String KEY_MINUTE = "sign_in_minute";
    /** 签到结果通知 */
    private static final String KEY_NOTIFY = "sign_in_notify";
    /** 多账号之间的间隔秒数，防 ESA 403 */
    private static final String KEY_INTERVAL = "sign_in_interval_sec";
    /** Cookie 掉线时用已保存的密码自动重登 */
    private static final String KEY_AUTO_RELOGIN = "sign_in_auto_relogin";
    /** 最近一次批量签到的汇总，给设置页/账号页展示 */
    private static final String KEY_LAST_SUMMARY = "sign_in_last_summary";
    private static final String KEY_LAST_RUN = "sign_in_last_run";

    private SignInSettings() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ---- 自动签到总开关 ----
    public static boolean isAutoSignInEnabled(Context c) {
        return sp(c).getBoolean(KEY_AUTO_SIGN_IN, false);
    }

    public static void setAutoSignInEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_SIGN_IN, v).apply();
    }

    // ---- 多账号 ----
    public static boolean isAllAccounts(Context c) {
        return sp(c).getBoolean(KEY_ALL_ACCOUNTS, true);
    }

    public static void setAllAccounts(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ALL_ACCOUNTS, v).apply();
    }

    // ---- 定时任务 ----
    public static boolean isScheduleEnabled(Context c) {
        return sp(c).getBoolean(KEY_SCHEDULE, false);
    }

    public static void setScheduleEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SCHEDULE, v).apply();
    }

    public static int getHour(Context c) {
        return sp(c).getInt(KEY_HOUR, 8);
    }

    public static int getMinute(Context c) {
        return sp(c).getInt(KEY_MINUTE, 30);
    }

    public static void setTime(Context c, int hour, int minute) {
        sp(c).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply();
    }

    public static String getTimeText(Context c) {
        return String.format(Locale.getDefault(), "%02d:%02d", getHour(c), getMinute(c));
    }

    // ---- 通知 ----
    public static boolean isNotifyEnabled(Context c) {
        return sp(c).getBoolean(KEY_NOTIFY, true);
    }

    public static void setNotifyEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_NOTIFY, v).apply();
    }

    // ---- 请求间隔 ----
    public static int getIntervalSeconds(Context c) {
        return Math.max(0, sp(c).getInt(KEY_INTERVAL, 5));
    }

    public static void setIntervalSeconds(Context c, int seconds) {
        sp(c).edit().putInt(KEY_INTERVAL, Math.max(0, seconds)).apply();
    }

    // ---- 自动重登 ----
    public static boolean isAutoReloginEnabled(Context c) {
        return sp(c).getBoolean(KEY_AUTO_RELOGIN, true);
    }

    public static void setAutoReloginEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_RELOGIN, v).apply();
    }

    // ---- 最近一次结果 ----
    public static void saveLastRun(Context c, String summary) {
        sp(c).edit()
                .putString(KEY_LAST_SUMMARY, summary)
                .putLong(KEY_LAST_RUN, System.currentTimeMillis())
                .apply();
    }

    public static String getLastSummary(Context c) {
        return sp(c).getString(KEY_LAST_SUMMARY, "");
    }

    public static long getLastRunTime(Context c) {
        return sp(c).getLong(KEY_LAST_RUN, 0L);
    }
}
