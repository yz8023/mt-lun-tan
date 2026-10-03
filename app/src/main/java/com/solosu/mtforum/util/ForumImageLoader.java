package com.solosu.mtforum.util;

import android.text.TextUtils;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.solosu.mtforum.network.HttpClient;

/** Builds authenticated image requests for protected Discuz attachments. */
public final class ForumImageLoader {
    private ForumImageLoader() {}

    /**
     * 把网络图片地址包成带 UA / Referer / Cookie 的 {@link GlideUrl}。
     *
     * <p>本站配图是 {@code cdn.binmt.cc/forum.php?mod=image&aid=…&key=…}，
     * 实测 <b>UA 为空直接 403</b>，而 Glide 默认不发 UA —— 这就是"图片不显示"的根因。
     * 头像（{@code avatar.mt2.cn/uc_server/avatar.php}）同理会 403。
     *
     * <p><b>只包 http(s) 地址。</b>本地路径（{@code /storage/…}、{@code file://}、
     * {@code content://}）包成 GlideUrl 会让 Glide 走网络加载器、必然失败，
     * 所以原样返回交给 Glide 自己处理。这样调用方可以无脑全量使用本方法。
     */
    public static Object model(String url) {
        if (TextUtils.isEmpty(url)) return url;
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return url;
        try {
            LazyHeaders.Builder headers = new LazyHeaders.Builder()
                    .addHeader("User-Agent", HttpClient.USER_AGENT)
                    .addHeader("Referer", HttpClient.BASE_URL);
            String cookie = HttpClient.getInstance().getCookieHeader();
            if (!TextUtils.isEmpty(cookie)) headers.addHeader("Cookie", cookie);
            return new GlideUrl(url, headers.build());
        } catch (Throwable ignored) {
            return url;
        }
    }
}
