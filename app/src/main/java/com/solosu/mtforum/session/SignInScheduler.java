package com.solosu.mtforum.session;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.solosu.mtforum.ai.AiLog;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * 定时签到排程（build60 新增）。
 *
 * <p>用 WorkManager 的周期任务，周期 1 天，首次延迟对齐到用户设定的时刻。
 * WorkManager 会在设备允许的时间窗内执行，不保证秒级精确 —— 对签到来说足够了，
 * 而且比 AlarmManager 更省电、更不容易被系统杀。
 */
public final class SignInScheduler {

    private static final String WORK_NAME = "mt_forum_daily_sign_in";

    private SignInScheduler() {
    }

    /** 按当前设置重新排程：开了就排，关了就撤 */
    public static void reschedule(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try {
            WorkManager wm = WorkManager.getInstance(app);
            if (!SignInSettings.isScheduleEnabled(app)) {
                wm.cancelUniqueWork(WORK_NAME);
                AiLog.i("sign-in", "定时签到已关闭");
                return;
            }

            long delay = computeInitialDelayMillis(
                    SignInSettings.getHour(app), SignInSettings.getMinute(app));

            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    SignInWorker.class, 1, TimeUnit.DAYS)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,
                            15, TimeUnit.MINUTES)
                    .addTag(WORK_NAME)
                    .build();

            wm.enqueueUniquePeriodicWork(WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE, request);
            AiLog.i("sign-in", "定时签到已排程：每天 " + SignInSettings.getTimeText(app)
                    + "（首次约 " + (delay / 60000L) + " 分钟后）");
        } catch (Exception e) {
            AiLog.i("sign-in", "定时签到排程失败：" + e);
        }
    }

    public static void cancel(Context context) {
        if (context == null) return;
        try {
            WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(WORK_NAME);
        } catch (Exception ignored) {
        }
    }

    /** 距离下一个 hour:minute 还有多少毫秒 */
    static long computeInitialDelayMillis(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return Math.max(0L, target.getTimeInMillis() - now.getTimeInMillis());
    }
}
