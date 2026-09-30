package com.solosu.mtforum.offline;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.solosu.mtforum.model.PostDetail;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.network.HttpClient;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Internal offline-post archive. Each thread is a self-contained HTML file plus metadata. */
public final class OfflinePostStore {
    private OfflinePostStore() {}

    public static final class Item {
        public String tid, title, author, savedAt;
        public boolean includesReplies;
        public File htmlFile;
    }

    public static File root(Context context) {
        File dir = new File(context.getFilesDir(), "offline_posts");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void save(Context context, String tid, PostDetail detail,
                            List<ReplyItem> replies, boolean includeReplies) throws Exception {
        File dir = new File(root(context), safe(tid));
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建离线目录");
        String html = buildHtml(detail, replies, includeReplies);
        html = embedImages(html, HttpClient.getInstance());
        write(new File(dir, "index.html"), html);
        JSONObject meta = new JSONObject();
        meta.put("tid", tid);
        meta.put("title", value(detail.getTitle(), "帖子 " + tid));
        meta.put("author", value(detail.getAuthor(), ""));
        meta.put("savedAt", java.time.LocalDateTime.now().toString().replace('T', ' '));
        meta.put("includesReplies", includeReplies);
        write(new File(dir, "meta.json"), meta.toString());
    }

    public static String buildHtml(PostDetail detail, List<ReplyItem> replies, boolean includeReplies) {
        String title = value(detail == null ? null : detail.getTitle(), "离线帖子");
        String author = value(detail == null ? null : detail.getAuthor(), "未知作者");
        String content = detail == null ? "" : value(detail.getContentHtml(), "");
        String hidden = detail == null ? "" : value(detail.getHiddenContentHtml(), "");
        boolean hiddenUnlocked = !TextUtils.isEmpty(hidden)
                && !com.solosu.mtforum.ai.AutoReplyEngine.isLockedHidden(hidden);
        StringBuilder body = new StringBuilder();
        body.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escape(title)).append("</title><style>")
                .append("body{max-width:900px;margin:auto;padding:20px;font-family:sans-serif;line-height:1.75;color:#202124;background:#fff}")
                .append("h1{font-size:24px}.meta{color:#777;border-bottom:1px solid #ddd;padding-bottom:12px}.post,.reply{overflow-wrap:anywhere}")
                .append("img{max-width:100%;height:auto}.offline-content-image{display:block;width:100%;height:auto;object-fit:contain;margin:10px 0}.hidden{margin:20px 0;padding:14px;border-left:4px solid #ff9800;background:#fff8e8}.hidden h2{margin-top:0;font-size:17px}.replies{margin-top:30px}.reply{border-top:1px solid #ddd;padding:16px 0}.who{font-weight:bold}.when{color:#888;font-size:12px}")
                .append("pre{white-space:pre-wrap;background:#f5f5f5;padding:12px;overflow:auto}@media(prefers-color-scheme:dark){body{background:#121212;color:#eee}.reply,.meta{border-color:#444}pre{background:#222}}")
                .append("</style></head><body><h1>").append(escape(title)).append("</h1><div class=\"meta\">")
                .append(escape(author)).append(" · ").append(escape(value(detail == null ? null : detail.getPublishTime(), "")))
                .append("</div><article class=\"post\">").append(content).append("</article>");
        // Preserve content that this authenticated session has already unlocked. Locked prompts
        // are intentionally not duplicated, while actual hidden HTML remains available offline
        // and in exported HTML for later analysis.
        if (hiddenUnlocked && !content.contains(hidden)) {
            body.append("<section class=\"hidden\"><h2>已解锁的隐藏内容</h2>")
                    .append(hidden).append("</section>");
        }
        if (includeReplies) {
            body.append("<section class=\"replies\"><h2>评论区（")
                    .append(replies == null ? 0 : replies.size()).append("）</h2>");
            if (replies != null) for (ReplyItem r : replies) {
                body.append("<div class=\"reply\"><div class=\"who\">")
                        .append(escape(value(r.getAuthor(), "匿名"))).append(" · ")
                        .append(escape(value(r.getFloorLabel(), r.getFloorNumber() + "#")))
                        .append("</div><div class=\"when\">").append(escape(value(r.getTime(), "")))
                        .append("</div><div>").append(value(r.getContentHtml(), escape(value(r.getContentText(), ""))))
                        .append("</div></div>");
            }
            body.append("</section>");
        }
        body.append("</body></html>");
        return body.toString();
    }

    /** Convert up to 40 images into data URIs, making the HTML genuinely usable without network. */
    private static String embedImages(String html, HttpClient client) {
        try {
            Document doc = Jsoup.parse(html, HttpClient.BASE_URL);
            Set<String> done = new HashSet<>();
            int count = 0;
            for (Element img : doc.select("img[src]")) {
                if (count >= 40) break;
                // Discuz often puts a thumbnail in src and the original in one of these attrs.
                String original = firstImageSource(img, "file", "comiis_loadimages", "zoomfile",
                        "data-original", "data-src", "data-file", "src");
                img.attr("src", original);
                String url = img.absUrl("src");
                String lowerSource = original.toLowerCase(java.util.Locale.ROOT);
                if (!lowerSource.contains("smiley") && !lowerSource.contains("emoticon")
                        && !lowerSource.contains("/static/image/") && !lowerSource.contains("icon")) {
                    img.addClass("offline-content-image");
                }
                if (TextUtils.isEmpty(url) || url.startsWith("data:") || !done.add(url)) continue;
                try {
                    byte[] bytes = client.getBytes(url, 5 * 1024 * 1024);
                    String lower = url.toLowerCase();
                    String mime = lower.contains(".png") ? "image/png" : lower.contains(".gif") ? "image/gif"
                            : lower.contains(".webp") ? "image/webp" : "image/jpeg";
                    String data = "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                    for (Element same : doc.select("img[src=\"" + cssEscape(img.attr("src")) + "\"]")) same.attr("src", data);
                    img.attr("src", data);
                    count++;
                } catch (Exception ignored) {}
            }
            return doc.outerHtml();
        } catch (Exception e) {
            return html;
        }
    }

    private static String firstImageSource(Element img, String... attrs) {
        for (String attr : attrs) {
            String value = img.attr(attr).trim();
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            boolean flag = lower.matches("\\d+") || "true".equals(lower) || "false".equals(lower) || "lazy".equals(lower);
            boolean looksLikeUrl = lower.startsWith("http://") || lower.startsWith("https://")
                    || lower.startsWith("//") || lower.startsWith("/") || lower.startsWith("./")
                    || lower.contains("/") || lower.contains(".");
            if (!value.isEmpty() && !flag && looksLikeUrl && !lower.startsWith("data:")
                    && !lower.endsWith("none.gif") && !lower.endsWith("blank.gif")) return value;
        }
        return img.attr("src");
    }

    public static List<Item> list(Context context) {
        List<Item> out = new ArrayList<>();
        File[] dirs = root(context).listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File dir : dirs) try {
            File metaFile = new File(dir, "meta.json");
            File html = new File(dir, "index.html");
            if (!metaFile.isFile() || !html.isFile()) continue;
            JSONObject meta = new JSONObject(new String(java.nio.file.Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8));
            Item item = new Item();
            item.tid = meta.optString("tid", dir.getName()); item.title = meta.optString("title", item.tid);
            item.author = meta.optString("author", ""); item.savedAt = meta.optString("savedAt", "");
            item.includesReplies = meta.optBoolean("includesReplies", false); item.htmlFile = html;
            out.add(item);
        } catch (Exception ignored) {}
        Collections.sort(out, (a, b) -> b.savedAt.compareTo(a.savedAt));
        return out;
    }

    public static void delete(Context context, String tid) { deleteTree(new File(root(context), safe(tid))); }
    private static void deleteTree(File f) { if (f.isDirectory()) { File[] c=f.listFiles(); if(c!=null) for(File x:c) deleteTree(x); } f.delete(); }
    private static void write(File file, String value) throws Exception { try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));} }
    private static String safe(String s) { return value(s,"unknown").replaceAll("[^0-9A-Za-z_-]", "_"); }
    private static String value(String s, String fallback) { return TextUtils.isEmpty(s) ? fallback : s; }
    private static String escape(String s) { return org.jsoup.nodes.Entities.escape(value(s, "")); }
    private static String cssEscape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
