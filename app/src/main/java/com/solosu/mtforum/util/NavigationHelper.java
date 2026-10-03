package com.solosu.mtforum.util;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.ui.detail.ThreadDetailActivity;

/**
 * 导航跳转辅助类
 * 统一管理页面跳转逻辑,防止重复点击,传递必要参数
 */
public class NavigationHelper {

    private static long lastClickTime;

    /**
     * 打开帖子详情(防重复点击,间隔 < 500ms 忽略)
     */
    public static void openThread(Context context, @Nullable Thread thread) {
        if (thread == null) return;
        java.util.ArrayList<String> imgs = null;
        if (thread.getImageUrls() != null && !thread.getImageUrls().isEmpty()) {
            imgs = new java.util.ArrayList<>(thread.getImageUrls());
        }
        openThread(context, thread.getTid(), thread.getTitle(), thread.getAuthor(), imgs);
    }

    /**
     * 打开帖子详情(仅用 tid,用于热帖排行等场景)
     */
    public static void openThread(Context context, @Nullable String tid) {
        openThread(context, tid, null, null, null);
    }

    private static void openThread(Context context, @Nullable String tid,
                                   @Nullable String title, @Nullable String author) {
        openThread(context, tid, title, author, null);
    }

    /**
     * build87: 把列表页已经拿到的真实 CDN 图一起带进详情页。
     *
     * <p>列表卡片用的 {@code cdn.binmt.cc/forum.php?mod=image&aid=…&key=…} 是服务端
     * 另外生成的缩略图地址，实测可用。而帖子页对游客会把附件整段换成
     * 「您需要登录才可以查看」，于是详情页解析出来的 imageUrls 是空的 ——
     * 用户从列表点进来看到「一张图都没有」。带上这几条地址做兜底，
     * 至少图廊能显示，与列表页观感一致。
     */
    private static void openThread(Context context, @Nullable String tid,
                                   @Nullable String title, @Nullable String author,
                                   @Nullable java.util.ArrayList<String> listImages) {
        if (context == null || tid == null || tid.isEmpty()) return;

        // 防重复点击:500ms 内只响应一次
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickTime < 500) return;
        lastClickTime = now;

        Intent intent = new Intent(context, ThreadDetailActivity.class);
        intent.putExtra("tid", tid);
        if (title != null) intent.putExtra("title", title);
        if (author != null) intent.putExtra("author", author);
        if (listImages != null && !listImages.isEmpty()) {
            intent.putStringArrayListExtra("list_images", listImages);
        }
        context.startActivity(intent);
    }
}
