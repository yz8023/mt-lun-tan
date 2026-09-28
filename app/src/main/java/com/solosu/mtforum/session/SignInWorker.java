package com.solosu.mtforum.session;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.network.HttpClient;

/**
 * 后台定时签到任务（build60 新增）。
 *
 * <p>由 {@link SignInScheduler} 按用户设定的时间每天排一次。
 * 真正干活的是 {@link MultiSignInManager#signInAllSync(Context, boolean)}，
 * 它对每个账号开独立会话，因此即便 App 没打开也能给所有账号签到。
 */
public class SignInWorker extends Worker {

    public SignInWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        try {
            // Worker 进程可能是冷启动，先把 Cookie 从磁盘恢复出来
            HttpClient.getInstance().init(app);
        } catch (Exception ignored) {
        }

        if (AccountManager.enabledList(app).isEmpty()) {
            AiLog.i("sign-in", "定时签到：没有启用中的账号");
            return Result.success();
        }

        try {
            MultiSignInManager.Summary summary = MultiSignInManager.signInAllSync(app, false);
            AiLog.i("sign-in", "定时签到完成 " + summary.shortText());
            SignInNotifier.notifySummary(app, summary);
            // 全挂大概率是网络/风控，交给 WorkManager 退避重试
            if (summary.total > 0 && summary.failed == summary.total) {
                return Result.retry();
            }
            return Result.success();
        } catch (Exception e) {
            AiLog.i("sign-in", "定时签到失败：" + e);
            return Result.retry();
        }
    }
}
