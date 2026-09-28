package com.solosu.mtforum.session;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.solosu.mtforum.MainActivity;
import com.solosu.mtforum.R;

/**
 * 签到结果通知（build60 新增）。
 * 后台定时签到跑完后给用户一条可展开的汇总通知。
 */
public final class SignInNotifier {

    private static final String CHANNEL_ID = "mt_sign_in";
    private static final String CHANNEL_NAME = "签到通知";
    private static final int NOTIFY_ID = 20260601;

    private SignInNotifier() {
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("多账号自动签到的执行结果");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /** 发一条签到汇总通知；没有通知权限时静默忽略 */
    public static void notifySummary(Context context, MultiSignInManager.Summary summary) {
        if (context == null || summary == null) return;
        if (!SignInSettings.isNotifyEnabled(context)) return;
        try {
            ensureChannel(context);
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pending = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_check)
                    .setContentTitle("MT 论坛签到 · " + summary.shortText())
                    .setContentText(summary.items.isEmpty() ? "没有可签到的账号" : summary.items.get(0).line())
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(summary.detailText()))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .setContentIntent(pending);

            NotificationManagerCompat.from(context).notify(NOTIFY_ID, builder.build());
        } catch (SecurityException ignored) {
            // Android 13+ 未授予 POST_NOTIFICATIONS
        } catch (Exception ignored) {
        }
    }
}
