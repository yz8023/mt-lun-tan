package com.solosu.mtforum.util;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * build95: 顶部 FPS 悬浮窗。
 *
 * <p>用户要求「可设置是否显示 FPS 在顶部」。用 Choreographer 统计每帧回调，
 * 每 500ms 求一次平均帧率，比 VSYNC 时间戳累加更贴近实际观感。
 *
 * <p>通过给 Activity 的 DecorView 加一个子 View 实现，不占用 WindowManager
 * 类型窗口的权限，也不会跨 Activity 残留 —— onDestroy 时随 DecorView 一起回收。
 */
public final class FpsOverlay {

    private static final String TAG_FPS = "fps_overlay_tag";
    private static final long WINDOW_MS = 500L;

    private FpsOverlay() {}

    /** 在 activity 的顶部挂上 FPS 显示。重复调用是幂等的。 */
    public static void attach(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (decor.findViewWithTag(TAG_FPS) != null) return;

        final TextView tv = new TextView(activity);
        tv.setTag(TAG_FPS);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11f);
        tv.setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 6), dp(activity, 2));
        tv.setBackgroundColor(0x99000000);
        tv.setGravity(Gravity.CENTER);
        tv.setElevation(dp(activity, 8));
        tv.setText("FPS --");

        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        decor.addView(tv, lp);

        // 挂到 DecorView 的右上角。用 post 拿到测量后的位置，避免此时还未布局。
        tv.post(() -> {
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) tv.getLayoutParams();
            if (mlp == null) return;
            mlp.topMargin = dp(activity, 28);
            mlp.leftMargin = decor.getWidth() - tv.getWidth() - dp(activity, 8);
            tv.setLayoutParams(mlp);
        });

        startCounting(tv, activity);
    }

    /** 摘掉 FPS 显示。 */
    public static void detach(Activity activity) {
        if (activity == null) return;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        View v = decor.findViewWithTag(TAG_FPS);
        if (v != null) decor.removeView(v);
    }

    private static void startCounting(final TextView tv, final Activity activity) {
        final android.view.Choreographer choreographer =
                android.view.Choreographer.getInstance();
        final android.view.Choreographer.FrameCallback callback =
                new android.view.Choreographer.FrameCallback() {
                    int frames = 0;
                    long startNs = 0;

                    @Override
                    public void doFrame(long frameTimeNanos) {
                        if (startNs == 0) startNs = frameTimeNanos;
                        frames++;
                        long elapsed = frameTimeNanos - startNs;
                        if (elapsed >= WINDOW_MS * 1_000_000L) {
                            float fps = frames * 1_000_000_000f / elapsed;
                            // build99: 同时显示「屏幕当前在跑多少赫兹」。
                            // 用户问「为什么论坛 FPS 锁 60」—— 把这两个数字并排显示，
                            // 一眼就能分清是 App 渲染慢，还是屏幕本来就在 60Hz
                            // （多数国产 ROM 对未申请高刷的第三方 App 就是 60Hz）。
                            float hz = RefreshRate.current(activity);
                            tv.setText(hz > 0
                                    ? String.format(Locale.US, "FPS %.0f · 屏 %.0fHz", fps, hz)
                                    : String.format(Locale.US, "FPS %.0f", fps));
                            frames = 0;
                            startNs = frameTimeNanos;
                        }
                        choreographer.postFrameCallback(this);
                    }
                };
        tv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                choreographer.postFrameCallback(callback);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                choreographer.removeFrameCallback(callback);
            }
        });
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
