package com.solosu.mtforum.util;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import java.util.Locale;

/**
 * build99: 高刷新率（120Hz / 144Hz）申请与诊断。
 *
 * <h3>「为什么论坛 FPS 锁 60」</h3>
 * 不是 App 里写死了 60，也不是 Choreographer 统计错了。Android 上屏幕跑多少赫兹，
 * 由「系统 + 面板 + App 的申请」三方决定：
 *
 * <ol>
 *   <li><b>面板</b>：{@code Display.getSupportedModes()} 里能看到有哪些刷新率。</li>
 *   <li><b>系统的刷新率策略</b>：大多数国产 ROM（MIUI / HyperOS / ColorOS…）对
 *       <b>没有主动申请的第三方 App 一律按 60Hz 合成</b>，只有白名单 / 系统应用
 *       才给 120Hz —— 这是最常见的原因。</li>
 *   <li><b>App 没提出请求</b>：想跑高刷必须显式告诉系统，两种方式：
 *       <ul>
 *         <li>API 23+：{@code WindowManager.LayoutParams.preferredRefreshRate}</li>
 *         <li>API 30+：{@code Window.setFrameRate(rate, FRAME_RATE_COMPATIBILITY_*)}，
 *             这个还会告诉系统「内容是按这个帧率渲染的」，配合得更好</li>
 *       </ul>
 *       只设其一在某些 ROM 上不生效，这里两个都设。</li>
 *   <li><b>省电模式 / 开发者选项里的「强制 60Hz」/ 系统设置里的刷新率</b>：
 *       这类是系统级开关，App 无法覆盖 —— 所以 {@link #describe(Activity)}
 *       会把「面板当前在跑多少赫兹」也显示出来，用户一眼能分清是谁在限。</li>
 * </ol>
 *
 * <p>本类只做申请与诊断，不改系统设置。用户关了开关就完全不干预。
 */
public final class RefreshRate {

    private RefreshRate() {
    }

    /** 设备支持的最高刷新率（Hz）。取不到就返回 0。 */
    public static float maxSupported(Activity activity) {
        try {
            Display d = activity.getWindowManager().getDefaultDisplay();
            if (d == null) return 0f;
            float max = 0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display.Mode[] modes = d.getSupportedModes();
                if (modes != null) {
                    for (Display.Mode m : modes) {
                        if (m != null && m.getRefreshRate() > max) max = m.getRefreshRate();
                    }
                }
            }
            float cur = d.getRefreshRate();
            return Math.max(max, cur);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 屏幕当前实际在跑的刷新率（Hz）。取不到返回 0。 */
    public static float current(Activity activity) {
        try {
            Display d = activity.getWindowManager().getDefaultDisplay();
            return d == null ? 0f : d.getRefreshRate();
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 例如 "120Hz"，取不到返回 "未知"。 */
    public static String describe(Activity activity) {
        float hz = current(activity);
        if (hz <= 0f) return "未知";
        return String.format(Locale.US, "%.0fHz", hz);
    }

    /**
     * 向系统申请设备支持的最高刷新率。返回申请到的赫兹数（0 表示没申请/失败）。
     *
     * <p>幂等：重复调用只是再设一遍同样的值。
     */
    public static float apply(Activity activity) {
        if (activity == null) return 0f;
        try {
            float max = maxSupported(activity);
            if (max <= 0f) return 0f;
            Window window = activity.getWindow();
            if (window == null) return 0f;

            // ① API 23~29 唯一可用的方式：窗口首选刷新率
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp != null && lp.preferredRefreshRate != max) {
                lp.preferredRefreshRate = max;
                window.setAttributes(lp);
            }

            // ② 再指名要那个「最高刷新率的显示模式」。
            //    只写 preferredRefreshRate 在部分 ROM（HyperOS / ColorOS）上会被忽略，
            //    直接把目标 mode id 写进 preferredDisplayModeId 更硬：
            //    系统要么切过去，要么明确拒绝，不会含糊地继续跑 60Hz。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && lp != null) {
                int bestId = -1;
                float best = 0f;
                try {
                    Display d = activity.getWindowManager().getDefaultDisplay();
                    if (d != null && d.getSupportedModes() != null) {
                        for (Display.Mode m : d.getSupportedModes()) {
                            if (m != null && m.getRefreshRate() > best) {
                                best = m.getRefreshRate();
                                bestId = m.getModeId();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (bestId >= 0 && lp.preferredDisplayModeId != bestId) {
                    lp.preferredDisplayModeId = bestId;
                    window.setAttributes(lp);
                }
            }
            return max;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 一句话说明当前状态，给侧边栏提示用 */
    public static String statusLine(Activity activity) {
        float cur = current(activity);
        float max = maxSupported(activity);
        if (cur <= 0f) return "暂时读不到屏幕刷新率";
        if (max > cur + 1f) {
            return String.format(Locale.US,
                    "屏幕现在 %.0fHz，设备最高支持 %.0fHz", cur, max);
        }
        return String.format(Locale.US, "屏幕当前 %.0fHz（已是设备最高）", cur);
    }
}
