package com.solosu.mtforum.offline;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solosu.mtforum.model.PostDetail;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;

import java.util.ArrayList;
import java.util.List;

/** Background download: fetches all requested pages, embeds images and stores one offline HTML file. */
public class OfflinePostWorker extends Worker {
    public OfflinePostWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override public Result doWork() {
        String tid = getInputData().getString("tid");
        boolean comments = getInputData().getBoolean("comments", true);
        if (tid == null || !tid.matches("\\d+")) return Result.failure();
        try {
            HttpClient http = HttpClient.getInstance();
            http.syncFromCookieManager();
            PostDetail first = ForumParser.parseThreadDetail(http.get(ForumParser.getThreadDetailUrl(tid)));
            if (first == null) throw new IllegalStateException("帖子解析失败");
            List<ReplyItem> replies = new ArrayList<>();
            if (comments && first.getReplies() != null) replies.addAll(first.getReplies());
            if (comments) {
                int pages = Math.max(1, first.getTotalPages());
                for (int page = 2; page <= pages; page++) {
                    if (isStopped()) return Result.failure();
                    String url = HttpClient.BASE_URL + "forum.php?mod=viewthread&tid=" + tid
                            + "&page=" + page + "&mobile=2";
                    PostDetail part = ForumParser.parseThreadDetail(http.get(url));
                    if (part != null && part.getReplies() != null) replies.addAll(part.getReplies());
                    setProgressAsync(new Data.Builder().putInt("page", page).putInt("pages", pages).build());
                }
            }
            OfflinePostStore.save(getApplicationContext(), tid, first, replies, comments);
            return Result.success(new Data.Builder().putString("title", first.getTitle()).build());
        } catch (Exception e) {
            return Result.failure(new Data.Builder().putString("error", e.getMessage()).build());
        }
    }
}
