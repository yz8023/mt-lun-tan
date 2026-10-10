package com.solosu.mtforum.util;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.solosu.mtforum.network.HttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * 用 OkHttp 替换 Glide 默认的 {@link java.net.HttpURLConnection} 图片加载栈。
 *
 * <h3>为什么</h3>
 * 参照项目 {@code yz8023/mtluntan} 用 Coil（底层 OkHttp）加载同一个站的图片，
 * 一直正常。差异在于两边默认发出的请求头：
 * <ul>
 *   <li>OkHttp <b>默认就带</b> {@code User-Agent: okhttp/4.x}（BridgeInterceptor 里写死的）；</li>
 *   <li>Glide 的 {@code HttpUrlFetcher} 用 {@code HttpURLConnection}，
 *       UA 依赖 Android 的 {@code http.agent} 系统属性，行为不透明。</li>
 * </ul>
 * 而本站图片（{@code cdn.binmt.cc/forum.php?mod=image…} 与重定向后的
 * {@code oss.binmt.cc/…}）<b>两个域名都要求非空 UA</b>：实测 UA 为空一律
 * 403/628B，带 UA 则 301 → 200/146KB。cookie 与 Referer 都不影响结果。
 *
 * <p>所以换成 OkHttp 后，图片请求天然带上 UA，重定向也由 OkHttp 原生处理
 * （Glide 那套是关掉自动重定向自己递归跟的）。
 *
 * <h3>与 ForumImageLoader 的关系</h3>
 * 两边不冲突、是双保险：{@code ForumImageLoader.model()} 把地址包成带
 * UA/Referer/Cookie 的 {@link GlideUrl}，那些 header 会被本类照样发出去；
 * 万一某处忘了包（或包出来是裸 String），本类也会兜底补一个 UA。
 */
public final class OkHttpStreamLoader implements ModelLoader<GlideUrl, InputStream> {

    private final Call.Factory client;

    public OkHttpStreamLoader(Call.Factory client) {
        this.client = client;
    }

    @Override
    public LoadData<InputStream> buildLoadData(
            @NonNull GlideUrl model, int width, int height, @NonNull Options options) {
        return new LoadData<>(model, new OkHttpFetcher(client, model));
    }

    @Override
    public boolean handles(@NonNull GlideUrl model) {
        return true;
    }

    static final class OkHttpFetcher implements DataFetcher<InputStream> {

        private static final int MAX_BYTES = 50 * 1024 * 1024;

        private final Call.Factory client;
        private final GlideUrl url;
        private volatile Call call;
        private volatile InputStream stream;

        OkHttpFetcher(Call.Factory client, GlideUrl url) {
            this.client = client;
            this.url = url;
        }

        @Override
        public void loadData(
                @NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
            try {
                Request.Builder builder = new Request.Builder().url(url.toStringUrl());
                Map<String, String> headers = url.getHeaders();
                boolean hasUa = false;
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getValue() == null) continue;
                        builder.addHeader(e.getKey(), e.getValue());
                        if ("User-Agent".equalsIgnoreCase(e.getKey())
                                && !e.getValue().trim().isEmpty()) {
                            hasUa = true;
                        }
                    }
                }
                // 兜底：调用方没带 UA 时补一个。本站图片 UA 为空直接 403。
                if (!hasUa) {
                    builder.header("User-Agent", HttpClient.USER_AGENT);
                }

                call = client.newCall(builder.build());
                okhttp3.Response response = call.execute();
                if (!response.isSuccessful() || response.body() == null) {
                    callback.onLoadFailed(new IOException(
                            "图片请求失败 HTTP " + response.code() + " " + url.toStringUrl()));
                    return;
                }
                // build106: Discuz 附件路由（mod=attachment / mod=image）在
                // 会话缺失或签名无效时返回 200 的「该附件无法读取」HTML 提示页。
                // 不放行 HTML —— 让 Glide 明确失败走错误处理，而不是对着一页
                // HTML 解码出空白/裂图。
                String contentType = response.header("Content-Type");
                if (contentType != null
                        && contentType.toLowerCase(java.util.Locale.ROOT)
                                .startsWith("text/html")) {
                    callback.onLoadFailed(new IOException(
                            "服务器返回页面而非图片 " + url.toStringUrl()));
                    return;
                }
                if (response.body().contentLength() > MAX_BYTES) {
                    callback.onLoadFailed(new IOException("图片过大: " + url.toStringUrl()));
                    return;
                }
                stream = response.body().byteStream();
                callback.onDataReady(stream);
            } catch (IOException e) {
                callback.onLoadFailed(e);
            } catch (Throwable t) {
                callback.onLoadFailed(new IOException(t));
            }
        }

        @Override
        public void cleanup() {
            try {
                if (stream != null) stream.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void cancel() {
            Call c = call;
            if (c != null) c.cancel();
        }

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.REMOTE;
        }
    }

    /** 给图片专用的 OkHttp 客户端：不带业务拦截器，避免和页面请求的风控/节流互相干扰。 */
    public static OkHttpClient newImageClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public static class Factory implements ModelLoaderFactory<GlideUrl, InputStream> {

        private final Call.Factory client;

        public Factory() {
            this(newImageClient());
        }

        public Factory(Call.Factory client) {
            this.client = client;
        }

        @NonNull
        @Override
        public ModelLoader<GlideUrl, InputStream> build(
                @NonNull MultiModelLoaderFactory multiFactory) {
            return new OkHttpStreamLoader(client);
        }

        @Override
        public void teardown() {
        }
    }
}
