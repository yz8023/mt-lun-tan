package com.solosu.mtforum.util;

import android.content.Context;

import com.bumptech.glide.Glide;

/**
 * 切号 / 登出时清掉上一个账号留下的图片缓存。
 *
 * <h3>为什么需要</h3>
 * build65 在 HTTP 层已经处理过同一类问题：{@code AccountManager.switchTo} 的注释
 * 写着"必须先清去重缓存，否则新账号会复用上一个账号的页面结果"，于是清了
 * {@code clearPendingCache()} + {@code clearCookies()}。<b>图片层被漏掉了</b>：
 *
 * <ul>
 *   <li>Glide 的磁盘缓存 key 只有图片 URL，<b>不带</b> Cookie / UA / Referer。</li>
 *   <li>本站配图是 {@code cdn.binmt.cc/forum.php?mod=image&aid=…&key=…}，
 *      UA 为空直接 403 —— 也就是说"能不能看到这张图"取决于会话。</li>
 *   <li>于是账号 A 读过的图，切到账号 B（或退出登录）之后照样从磁盘命中显示。</li>
 * </ul>
 *
 * <h3>用户看到的现象</h3>
 * 多账号用户反馈的"图片显示异常"：退出登录后还能看到上一个账号浏览过的帖子配图，
 * 甚至新账号会话已失效时依然显示旧图。
 *
 * <h3>线程</h3>
 * {@code clearDiskCache()} 不能在主线程调用，内部已切线程，调用方直接调即可。
 */
public final class ImageCacheJanitor {

    private ImageCacheJanitor() {
    }

    /**
     * 后台清 Glide 磁盘缓存。
     *
     * <p>只清磁盘缓存是有意的：内存缓存随进程消亡、且有容量上限，
     * 真正会跨账号/跨会话串味的是落盘的那份。{@code clearMemory()} 要求主线程，
     * 在登出这种场景下收益不抵复杂度。
     *
     * @param context 任意 Context，内部取 applicationContext，不会泄漏 Activity
     */
    public static void clearPreviousAccountImages(Context context) {
        if (context == null) return;
        try {
            final Context app = context.getApplicationContext();
            Thread t = new Thread(() -> {
                try {
                    Glide.get(app).clearDiskCache();
                } catch (Throwable ignored) {
                    // 缓存清理失败不该影响登出 / 切号主流程 —— 用户该登出还是得登出
                }
            }, "mt-imgcache-clear");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (Throwable ignored) {
        }
    }
}
