package com.solosu.mtforum.util;

import android.text.TextUtils;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.solosu.mtforum.network.HttpClient;

/** Builds authenticated image requests for protected Discuz attachments. */
public final class ForumImageLoader {
    private ForumImageLoader() {}

    public static Object model(String url) {
        if (TextUtils.isEmpty(url)) return url;
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
