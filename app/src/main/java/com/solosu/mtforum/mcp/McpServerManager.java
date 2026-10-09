package com.solosu.mtforum.mcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.solosu.mtforum.BuildConfig;
import com.solosu.mtforum.model.Friend;
import com.solosu.mtforum.model.ForumCategory;
import com.solosu.mtforum.model.Message;
import com.solosu.mtforum.model.PostDetail;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.model.UserProfile;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机只读 Streamable HTTP MCP 服务。
 *
 * 安全边界：只绑定 127.0.0.1；默认关闭；Bearer Token 鉴权；只提供白名单读取工具；
 * 不输出 Cookie、密码、Token、formhash 等认证材料，也没有任何写入工具或公网隧道。
 */
public final class McpServerManager {
    private static final String TAG = "McpServer";
    private static final String PREFS = "mcp_settings";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TOKEN = "token";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9797;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_PAGE = 500;
    private static final int MAX_TOOL_TEXT_CHARS = 240_000;
    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";

    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)(cookie|cookies|password|access[_-]?token|refresh[_-]?token|token|secret|authorization|formhash)\\s*[=:]\\s*[^\\s&;\\\"']+");
    private static final Set<String> NOTICE_TYPES;
    private static final Set<String> GUIDE_VIEWS;

    static {
        Set<String> noticeTypes = new HashSet<>();
        Collections.addAll(noticeTypes, "mypost", "interactive", "system", "app");
        NOTICE_TYPES = Collections.unmodifiableSet(noticeTypes);
        Set<String> guideViews = new HashSet<>();
        Collections.addAll(guideViews, "new", "digest", "newthread");
        GUIDE_VIEWS = Collections.unmodifiableSet(guideViews);
    }

    private static volatile McpServerManager instance;

    private final Context appContext;
    private final ThreadPoolExecutor clients;
    private volatile ServerSocket serverSocket;
    private volatile java.lang.Thread serverThread;
    private volatile boolean running;
    private volatile boolean starting;
    private long generation;

    private McpServerManager(Context context) {
        appContext = context.getApplicationContext();
        ThreadFactory factory = runnable -> {
            java.lang.Thread thread = new java.lang.Thread(runnable, "mcp-client");
            thread.setDaemon(true);
            return thread;
        };
        clients = new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), factory, new ThreadPoolExecutor.AbortPolicy());
        clients.allowCoreThreadTimeOut(true);
    }

    public static McpServerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (McpServerManager.class) {
                if (instance == null) instance = new McpServerManager(context);
            }
        }
        return instance;
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public boolean isRunning() {
        ServerSocket socket = serverSocket;
        return running && socket != null && !socket.isClosed();
    }

    public boolean isStarting() {
        return starting;
    }

    public String getEndpoint() {
        return "http://" + HOST + ":" + PORT + "/mcp";
    }

    /** 设置页只展示掩码；完整令牌仅在用户显式复制连接配置时进入剪贴板。 */
    public String getTokenMasked() {
        String token = getToken();
        if (token.length() <= 12) return token;
        return token.substring(0, 6) + "…" + token.substring(token.length() - 4);
    }

    private String getToken() {
        String token = prefs().getString(KEY_TOKEN, "");
        if (token == null || token.isEmpty()) {
            token = generateToken();
            prefs().edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }

    public synchronized void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) start(); else stop();
    }

    public synchronized String regenerateToken() {
        String token = generateToken();
        prefs().edit().putString(KEY_TOKEN, token).apply();
        return token;
    }

    public void copyConfigToClipboard(Context context) {
        if (context == null) return;
        try {
            JSONObject config = new JSONObject()
                    .put("url", getEndpoint())
                    .put("transport", "streamable-http")
                    .put("headers", new JSONObject().put("Authorization", "Bearer " + getToken()));
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("MT Forum MCP configuration", config.toString()));
            }
        } catch (JSONException ignored) {
        }
    }

    /** Application 启动时调用；若用户此前已开启，则恢复本机服务。 */
    public void start() {
        final long myGeneration;
        synchronized (this) {
            if (!isEnabled() || running || starting) return;
            starting = true;
            myGeneration = ++generation;
            getToken();
            java.lang.Thread thread = new java.lang.Thread(() -> serve(myGeneration), "mcp-server");
            thread.setDaemon(true);
            serverThread = thread;
            thread.start();
        }
    }

    public synchronized void stop() {
        generation++;
        running = false;
        starting = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        java.lang.Thread thread = serverThread;
        serverThread = null;
        if (thread != null) thread.interrupt();
    }

    private void serve(long myGeneration) {
        ServerSocket listener = null;
        try {
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getByName(HOST), PORT), 8);
            synchronized (this) {
                if (myGeneration != generation || !isEnabled()) return;
                serverSocket = listener;
                running = true;
                starting = false;
            }
            while (isGenerationActive(myGeneration)) {
                Socket client;
                try {
                    client = listener.accept();
                } catch (IOException e) {
                    if (isGenerationActive(myGeneration)) Log.w(TAG, "Local MCP accept loop stopped");
                    break;
                }
                if (!client.getInetAddress().isLoopbackAddress()) {
                    closeQuietly(client);
                    continue;
                }
                try {
                    clients.execute(() -> handleClient(client));
                } catch (RuntimeException rejected) {
                    closeQuietly(client);
                }
            }
        } catch (IOException e) {
            // Do not log request data or credentials. The Settings page reports that the port is unavailable.
            Log.w(TAG, "Local MCP server could not bind to loopback port " + PORT);
        } finally {
            closeQuietly(listener);
            synchronized (this) {
                if (myGeneration == generation) {
                    if (serverSocket == listener) serverSocket = null;
                    running = false;
                    starting = false;
                    serverThread = null;
                }
            }
        }
    }

    private synchronized boolean isGenerationActive(long myGeneration) {
        return myGeneration == generation && running && isEnabled();
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket;
             InputStream input = client.getInputStream();
             OutputStream output = client.getOutputStream()) {
            client.setSoTimeout(12_000);
            if (!client.getInetAddress().isLoopbackAddress()) {
                writeHttp(output, 403, rpcError(JSONObject.NULL, -32001, "仅允许本机回环访问"));
                return;
            }

            int[] bytesRead = {0};
            String requestLine = readHttpLine(input, 4096, bytesRead);
            if (requestLine == null || requestLine.isEmpty()) {
                writeHttp(output, 400, rpcError(JSONObject.NULL, -32600, "无效 HTTP 请求"));
                return;
            }
            String[] requestParts = requestLine.split("\\s+");
            if (requestParts.length != 3 || !requestParts[2].startsWith("HTTP/1.")) {
                writeHttp(output, 400, rpcError(JSONObject.NULL, -32600, "无效 HTTP 请求"));
                return;
            }
            String method = requestParts[0].toUpperCase(Locale.ROOT);
            String path = requestParts[1];
            if (!"/mcp".equals(path)) {
                writeHttp(output, 404, rpcError(JSONObject.NULL, -32600, "端点不存在"));
                return;
            }
            if (!"POST".equals(method)) {
                writeHttp(output, 405, rpcError(JSONObject.NULL, -32600, "仅支持 POST"));
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while ((headerLine = readHttpLine(input, 8192, bytesRead)) != null && !headerLine.isEmpty()) {
                String lower = headerLine.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    // Content-Length is parsed below after duplicate/format checks.
                }
                int colon = headerLine.indexOf(':');
                if (colon <= 0) continue;
                String name = headerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = headerLine.substring(colon + 1).trim();
                if (headers.containsKey(name)) {
                    writeHttp(output, 400, rpcError(JSONObject.NULL, -32600, "重复 HTTP 请求头"));
                    return;
                }
                headers.put(name, value);
                if (bytesRead[0] > MAX_HEADER_BYTES) {
                    writeHttp(output, 431, rpcError(JSONObject.NULL, -32600, "HTTP 请求头过大"));
                    return;
                }
            }
            if (headerLine == null || bytesRead[0] > MAX_HEADER_BYTES) {
                writeHttp(output, 431, rpcError(JSONObject.NULL, -32600, "HTTP 请求头无效或过大"));
                return;
            }
            if (headers.containsKey("transfer-encoding")) {
                writeHttp(output, 400, rpcError(JSONObject.NULL, -32600, "不支持分块传输"));
                return;
            }
            if (!authorized(headers.get("authorization"))) {
                writeHttp(output, 401, rpcError(JSONObject.NULL, -32001, "需要有效的 Bearer 令牌"));
                return;
            }
            String contentType = headers.get("content-type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                writeHttp(output, 415, rpcError(JSONObject.NULL, -32600, "Content-Type 必须为 application/json"));
                return;
            }
            if (headers.containsKey("expect")) {
                writeHttp(output, 417, rpcError(JSONObject.NULL, -32600, "不支持 Expect 请求"));
                return;
            }
            int contentLength;
            try {
                contentLength = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
            } catch (NumberFormatException e) {
                contentLength = -1;
            }
            if (contentLength <= 0) {
                writeHttp(output, 411, rpcError(JSONObject.NULL, -32600, "缺少有效 Content-Length"));
                return;
            }
            if (contentLength > MAX_BODY_BYTES) {
                writeHttp(output, 413, rpcError(JSONObject.NULL, -32600, "请求体超过 1 MiB 限制"));
                return;
            }
            byte[] body = readBody(input, contentLength);
            if (body == null) {
                writeHttp(output, 400, rpcError(JSONObject.NULL, -32700, "请求体不完整"));
                return;
            }
            final JSONObject response;
            try {
                JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                response = handleRpc(request);
            } catch (JSONException e) {
                writeHttp(output, 400, rpcError(JSONObject.NULL, -32700, "JSON 解析失败"));
                return;
            }
            if (response == null) {
                writeHttp(output, 202, null);
            } else {
                writeHttp(output, 200, response);
            }
        } catch (IOException ignored) {
            // Client disconnects and local timeouts are expected; do not log request content.
        } catch (Exception ignored) {
            // Avoid exposing internal exception messages or page contents to unauthenticated logs.
        }
    }

    private JSONObject handleRpc(JSONObject request) {
        Object id = request.has("id") ? request.opt("id") : JSONObject.NULL;
        boolean hasId = request.has("id") && !request.isNull("id");
        if (!"2.0".equals(request.optString("jsonrpc", ""))) {
            return rpcError(hasId ? id : JSONObject.NULL, -32600, "JSON-RPC 2.0 required");
        }
        String method = request.optString("method", "");
        if (method.isEmpty()) {
            return hasId ? rpcError(id, -32600, "缺少 method") : null;
        }
        if ("notifications/initialized".equals(method) || "notifications/cancelled".equals(method)) {
            return null;
        }
        if ("ping".equals(method)) {
            if (!hasId) return null;
            return rpcResult(id, new JSONObject());
        }
        if (!hasId) return null;
        try {
            switch (method) {
                case "initialize": {
                    JSONObject params = request.optJSONObject("params");
                    String requested = params == null ? "" : params.optString("protocolVersion", "");
                    String version = "2024-11-05".equals(requested)
                            || MCP_PROTOCOL_VERSION.equals(requested) ? requested : MCP_PROTOCOL_VERSION;
                    JSONObject result = new JSONObject()
                            .put("protocolVersion", version)
                            .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                            .put("serverInfo", new JSONObject()
                                    .put("name", "mt-lun-tan-local")
                                    .put("version", BuildConfig.VERSION_NAME))
                            .put("instructions", "只读本机服务；私信与通知可能包含当前账号的私人内容。"
                                    + "不会返回 Cookie、密码、Token 或 formhash；不支持写操作。");
                    return rpcResult(id, result);
                }
                case "tools/list":
                    return rpcResult(id, new JSONObject().put("tools", toolList()));
                case "tools/call": {
                    JSONObject params = request.optJSONObject("params");
                    if (params == null) return rpcError(id, -32602, "缺少 params");
                    String name = params.optString("name", "");
                    JSONObject args = params.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    try {
                        JSONObject data = callTool(name, args);
                        return rpcResult(id, textResult(data, false));
                    } catch (IllegalArgumentException e) {
                        return rpcResult(id, textResult(errorData(e.getMessage()), true));
                    } catch (Exception e) {
                        return rpcResult(id, textResult(errorData("读取失败，请检查网络或登录状态后重试。"), true));
                    }
                }
                default:
                    return rpcError(id, -32601, "方法未实现");
            }
        } catch (JSONException e) {
            return rpcError(id, -32603, "MCP 响应生成失败");
        }
    }

    private JSONArray toolList() throws JSONException {
        JSONArray tools = new JSONArray();
        addTool(tools, "get_app_info", "读取应用基本信息（不含账号和认证数据）", new JSONObject(), new JSONArray());
        addTool(tools, "get_thread_list", "读取导读帖子列表。view 支持 new、digest、newthread；hot 在本站移动模板中不可用。",
                schema(new String[][]{{"view", "string", "导读类型，默认 newthread"}, {"page", "integer", "页码，默认 1"}}), new JSONArray());
        addTool(tools, "get_thread_detail", "读取帖子正文和指定页回复。",
                schema(new String[][]{{"tid", "string", "帖子 ID"}, {"page", "integer", "回复页码，默认 1"}}), new JSONArray().put("tid"));
        addTool(tools, "get_forum_list", "读取论坛版块分类及版块 ID。", new JSONObject(), new JSONArray());
        addTool(tools, "get_forum_threads", "读取指定版块的帖子列表。",
                schema(new String[][]{{"fid", "string", "版块 ID"}, {"page", "integer", "页码，默认 1"}}), new JSONArray().put("fid"));
        addTool(tools, "get_user_profile", "读取公开用户资料。",
                schema(new String[][]{{"uid", "string", "用户 UID"}}), new JSONArray().put("uid"));
        addTool(tools, "search_threads", "搜索论坛帖子。",
                schema(new String[][]{{"keyword", "string", "搜索关键词"}, {"page", "integer", "页码，默认 1"}}), new JSONArray().put("keyword"));
        addTool(tools, "get_notices", "读取当前登录账号的通知；返回内容可能包含私人通知。",
                schema(new String[][]{{"type", "string", "mypost、interactive、system、app；留空时使用全部通知"}, {"page", "integer", "页码，默认 1"}}), new JSONArray());
        addTool(tools, "get_pm_list", "读取当前登录账号的私信会话预览（属于私人内容）。",
                schema(new String[][]{{"page", "integer", "页码，默认 1"}}), new JSONArray());
        addTool(tools, "get_favorites", "读取当前登录账号的收藏帖子。",
                schema(new String[][]{{"page", "integer", "页码，默认 1"}}), new JSONArray());
        addTool(tools, "get_followers", "读取指定 UID 的粉丝列表；UID 留空时读取当前账号。",
                schema(new String[][]{{"uid", "string", "用户 UID，留空表示当前账号"}, {"page", "integer", "页码，默认 1"}}), new JSONArray());
        addTool(tools, "get_friends", "读取指定 UID 的好友列表；UID 留空时读取当前账号。",
                schema(new String[][]{{"uid", "string", "用户 UID，留空表示当前账号"}, {"page", "integer", "页码，默认 1"}}), new JSONArray());
        return tools;
    }

    private void addTool(JSONArray tools, String name, String description,
                         JSONObject properties, JSONArray required) throws JSONException {
        JSONObject inputSchema = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)
                .put("additionalProperties", false);
        tools.put(new JSONObject().put("name", name)
                .put("description", description)
                .put("inputSchema", inputSchema));
    }

    private JSONObject schema(String[][] fields) throws JSONException {
        JSONObject properties = new JSONObject();
        for (String[] field : fields) {
            properties.put(field[0], new JSONObject()
                    .put("type", field[1])
                    .put("description", field[2]));
        }
        return properties;
    }

    private JSONObject callTool(String name, JSONObject args) throws Exception {
        switch (name) {
            case "get_app_info": return getAppInfo();
            case "get_thread_list": return getThreadList(args);
            case "get_thread_detail": return getThreadDetail(args);
            case "get_forum_list": return getForumList();
            case "get_forum_threads": return getForumThreads(args);
            case "get_user_profile": return getUserProfile(args);
            case "search_threads": return searchThreads(args);
            case "get_notices": return getNotices(args);
            case "get_pm_list": return getPmList(args);
            case "get_favorites": return getFavorites(args);
            case "get_followers": return getFollowers(args);
            case "get_friends": return getFriends(args);
            default: throw new IllegalArgumentException("未知工具名称");
        }
    }

    private JSONObject getAppInfo() throws JSONException {
        return new JSONObject()
                .put("app", "MT论坛")
                .put("version", BuildConfig.VERSION_NAME)
                .put("platform", "Android")
                .put("mcp", "只读本机回环服务")
                .put("security", "认证材料不输出；无写操作；无公网隧道");
    }

    private JSONObject getThreadList(JSONObject args) throws Exception {
        String view = args.optString("view", "newthread").trim().toLowerCase(Locale.ROOT);
        if ("latest".equals(view)) view = "new";
        if (!GUIDE_VIEWS.contains(view)) throw new IllegalArgumentException("不支持的导读类型；可用 new、digest、newthread");
        int page = page(args);
        String html = fetch(ForumParser.getGuideUrl(view, page), false);
        List<Thread> threads = ForumParser.parseThreadList(html);
        return threadListJson(threads, view, page);
    }

    private JSONObject getThreadDetail(JSONObject args) throws Exception {
        String tid = numericId(args.optString("tid", ""), "tid");
        int page = page(args);
        String url = ForumParser.getThreadDetailUrl(tid, page, null);
        String html = fetch(url, false);
        PostDetail detail = ForumParser.parseThreadDetail(html);
        if (detail == null) throw new IllegalArgumentException("帖子不存在或无法解析");
        JSONObject result = new JSONObject()
                .put("tid", safeText(detail.getTid()))
                .put("title", safeText(detail.getTitle()))
                .put("forumName", safeText(detail.getForumName()))
                .put("author", safeText(detail.getAuthor()))
                .put("authorUid", safeText(detail.getAuthorUid()))
                .put("authorLevel", safeText(detail.getAuthorLevel()))
                .put("publishTime", safeText(detail.getPublishTime()))
                .put("replyCount", detail.getReplyCount())
                .put("likeCount", detail.getLikeCount())
                .put("favoriteCount", detail.getFavoriteCount())
                .put("currentPage", detail.getCurrentPage())
                .put("totalPages", detail.getTotalPages())
                .put("contentText", htmlToText(detail.getContentHtml()));
        JSONArray replies = new JSONArray();
        List<ReplyItem> replyItems = detail.getReplies();
        if (replyItems != null) {
            int limit = Math.min(replyItems.size(), 100);
            for (int i = 0; i < limit; i++) {
                ReplyItem reply = replyItems.get(i);
                if (reply == null) continue;
                replies.put(new JSONObject()
                        .put("pid", safeText(reply.getPid()))
                        .put("floor", reply.getFloorNumber())
                        .put("author", safeText(reply.getAuthor()))
                        .put("authorUid", safeText(reply.getAuthorUid()))
                        .put("authorLevel", safeText(reply.getAuthorLevel()))
                        .put("time", safeText(reply.getTime()))
                        .put("contentText", safeText(reply.getContentText()))
                        .put("isOP", reply.isOP()));
            }
        }
        result.put("replies", replies).put("returnedReplies", replies.length());
        return result;
    }

    private JSONObject getForumList() throws Exception {
        String html = fetch(ForumParser.getBaseDomain() + "forum.php?forumlist=1&mobile=2", false);
        List<ForumCategory> categories = ForumParser.parseForumCategories(html);
        JSONArray result = new JSONArray();
        for (ForumCategory category : categories) {
            JSONObject item = new JSONObject().put("category", safeText(category.getName()));
            JSONArray forums = new JSONArray();
            if (category.getForums() != null) {
                for (ForumCategory.Forum forum : category.getForums()) {
                    forums.put(new JSONObject()
                            .put("fid", safeText(forum.getFid()))
                            .put("name", safeText(forum.getName()))
                            .put("description", safeText(forum.getDescription()))
                            .put("todayPosts", forum.getTodayPosts())
                            .put("totalPosts", forum.getTotalPosts())
                            .put("totalThreads", forum.getTotalThreads()));
                }
            }
            item.put("forums", forums);
            result.put(item);
        }
        return new JSONObject().put("categories", result).put("count", result.length());
    }

    private JSONObject getForumThreads(JSONObject args) throws Exception {
        String fid = numericId(args.optString("fid", ""), "fid");
        int page = page(args);
        String html = fetch(ForumParser.getThreadListUrl(fid, page), false);
        return threadListJson(ForumParser.parseForumThreadList(html), "fid=" + fid, page);
    }

    private JSONObject getUserProfile(JSONObject args) throws Exception {
        String uid = numericId(args.optString("uid", ""), "uid");
        String html = fetch(ForumParser.getUserSpaceUrl(uid), false);
        UserProfile profile = ForumParser.parseUserProfile(html);
        if (profile == null) throw new IllegalArgumentException("用户资料不存在或无法解析");
        return new JSONObject()
                .put("uid", safeText(profile.getUid()))
                .put("username", safeText(profile.getUsername()))
                .put("level", safeText(profile.getLevel()))
                .put("groupName", safeText(profile.getGroupName()))
                .put("credits", profile.getCredits())
                .put("gold", profile.getGold())
                .put("threads", profile.getThreads())
                .put("posts", profile.getPosts())
                .put("friends", profile.getFriends())
                .put("followers", profile.getFollowers())
                .put("following", profile.getFollowing())
                .put("views", profile.getViews())
                .put("regDate", safeText(profile.getRegDate()))
                .put("lastVisit", safeText(profile.getLastVisit()))
                .put("signature", safeText(profile.getSignature()))
                .put("online", profile.isOnline());
    }

    private JSONObject searchThreads(JSONObject args) throws Exception {
        String keyword = args.optString("keyword", "").trim();
        if (keyword.isEmpty()) throw new IllegalArgumentException("keyword 不能为空");
        if (keyword.length() > 100) throw new IllegalArgumentException("keyword 最多 100 个字符");
        int page = page(args);
        String encoded = URLEncoder.encode(keyword, "UTF-8");
        String url = ForumParser.getBaseDomain()
                + "search.php?mod=forum&searchsubmit=yes&srchtxt=" + encoded
                + "&page=" + page + "&mobile=2";
        String html = fetch(url, false);
        return threadListJson(ForumParser.parseSearchResults(html), "search", page);
    }

    private JSONObject getNotices(JSONObject args) throws Exception {
        String type = args.optString("type", "").trim().toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !NOTICE_TYPES.contains(type)) {
            throw new IllegalArgumentException("通知类型仅支持 mypost、interactive、system、app 或留空");
        }
        int page = page(args);
        String url = ForumParser.getBaseDomain() + "home.php?mod=space&do=notice"
                + (type.isEmpty() ? "" : "&view=" + type) + "&page=" + page;
        String html = fetch(url, true);
        List<Message> messages = ForumParser.parseNoticeList(html);
        JSONArray items = new JSONArray();
        for (Message message : messages) {
            items.put(new JSONObject()
                    .put("noticeId", safeText(message.getPmid()))
                    .put("pid", safeText(message.getPid()))
                    .put("username", safeText(message.getAuthor()))
                    .put("uid", safeText(message.getAuthorUid()))
                    .put("title", safeText(message.getTitle()))
                    .put("summary", safeText(message.getSummary()))
                    .put("time", safeText(message.getTime()))
                    .put("type", message.getType())
                    .put("unread", !message.isRead()));
        }
        return new JSONObject().put("notices", items).put("type", type).put("page", page).put("count", items.length());
    }

    private JSONObject getPmList(JSONObject args) throws Exception {
        int page = page(args);
        String url = ForumParser.getBaseDomain() + "home.php?mod=space&do=pm&mobile=2&page=" + page;
        String html = fetch(url, true);
        List<Message> messages = ForumParser.parsePmList(html);
        JSONArray items = new JSONArray();
        for (Message message : messages) {
            items.put(new JSONObject()
                    .put("conversationUid", safeText(message.getAuthorUid()))
                    .put("username", safeText(message.getAuthor()))
                    .put("preview", safeText(message.getSummary()))
                    .put("time", safeText(message.getTime()))
                    .put("unread", !message.isRead()));
        }
        return new JSONObject().put("conversations", items).put("page", page).put("count", items.length());
    }

    private JSONObject getFavorites(JSONObject args) throws Exception {
        int page = page(args);
        String url = ForumParser.getBaseDomain()
                + "home.php?mod=space&do=favorite&type=thread&page=" + page + "&mobile=2";
        String html = fetch(url, true);
        return threadListJson(ForumParser.parseFavoriteList(html), "favorites", page);
    }

    private JSONObject getFollowers(JSONObject args) throws Exception {
        String uid = optionalNumericId(args.optString("uid", ""), "uid");
        int page = page(args);
        String url = ForumParser.getBaseDomain() + "home.php?mod=follow&do=follower"
                + (uid.isEmpty() ? "" : "&uid=" + uid) + "&page=" + page + "&mobile=2";
        String html = fetch(url, uid.isEmpty());
        return friendListJson(ForumParser.parseFriendList(html), "followers", page);
    }

    private JSONObject getFriends(JSONObject args) throws Exception {
        String uid = optionalNumericId(args.optString("uid", ""), "uid");
        int page = page(args);
        String url = ForumParser.getBaseDomain() + "home.php?mod=space&do=friend"
                + (uid.isEmpty() ? "" : "&uid=" + uid) + "&page=" + page + "&mobile=2";
        String html = fetch(url, uid.isEmpty());
        return friendListJson(ForumParser.parseFriendList(html), "friends", page);
    }

    private JSONObject threadListJson(List<Thread> threads, String source, int page) throws JSONException {
        JSONArray items = new JSONArray();
        if (threads != null) {
            int limit = Math.min(threads.size(), 100);
            for (int i = 0; i < limit; i++) {
                Thread thread = threads.get(i);
                if (thread == null) continue;
                items.put(new JSONObject()
                        .put("tid", safeText(thread.getTid()))
                        .put("title", safeText(thread.getTitle()))
                        .put("author", safeText(thread.getAuthor()))
                        .put("authorUid", safeText(thread.getAuthorUid()))
                        .put("forumName", safeText(thread.getForumName()))
                        .put("summary", safeText(thread.getSummary()))
                        .put("publishTime", safeText(thread.getPublishTime()))
                        .put("views", thread.getViews())
                        .put("replies", thread.getReplies())
                        .put("likes", thread.getLikes())
                        .put("hasImage", thread.isHasImage())
                        .put("sticky", thread.isSticky()));
            }
        }
        return new JSONObject().put("threads", items)
                .put("source", safeText(source)).put("page", page).put("count", items.length());
    }

    private JSONObject friendListJson(List<Friend> friends, String source, int page) throws JSONException {
        JSONArray items = new JSONArray();
        if (friends != null) {
            int limit = Math.min(friends.size(), 200);
            for (int i = 0; i < limit; i++) {
                Friend friend = friends.get(i);
                if (friend == null) continue;
                items.put(new JSONObject()
                        .put("uid", safeText(friend.getUid()))
                        .put("username", safeText(friend.getUsername()))
                        .put("level", safeText(friend.getLevel()))
                        .put("groupName", safeText(friend.getGroupName()))
                        .put("credits", safeText(friend.getCredits())));
            }
        }
        return new JSONObject().put("users", items).put("source", safeText(source))
                .put("page", page).put("count", items.length());
    }

    private String fetch(String url, boolean requiresLogin) throws Exception {
        HttpClient client = HttpClient.getInstance();
        client.syncFromCookieManager();
        String html = client.get(url);
        if (html == null || html.trim().isEmpty()) throw new IllegalArgumentException("论坛返回空页面");
        if (requiresLogin && ForumParser.isLoginPage(html)) {
            throw new IllegalArgumentException("当前账号未登录或登录已过期");
        }
        return html;
    }

    private int page(JSONObject args) {
        int page = args.optInt("page", 1);
        if (page < 1 || page > MAX_PAGE) {
            throw new IllegalArgumentException("page 必须在 1 到 " + MAX_PAGE + " 之间");
        }
        return page;
    }

    private String numericId(String value, String name) {
        String id = value == null ? "" : value.trim();
        if (!NUMERIC_ID.matcher(id).matches()) throw new IllegalArgumentException(name + " 必须为数字 ID");
        return id;
    }

    private String optionalNumericId(String value, String name) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty()) return "";
        return numericId(id, name);
    }

    private static String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        return safeText(Jsoup.parseBodyFragment(html).text());
    }

    private static String safeText(String text) {
        if (text == null) return "";
        String cleaned = SENSITIVE_TEXT.matcher(text.replace('\u00a0', ' ').trim())
                .replaceAll("$1=[REDACTED]");
        if (cleaned.length() > 12_000) cleaned = cleaned.substring(0, 12_000) + "…";
        return cleaned;
    }

    private JSONObject textResult(JSONObject data, boolean isError) throws JSONException {
        String text = data.toString();
        if (text.length() > MAX_TOOL_TEXT_CHARS) {
            text = "{\"error\":\"MCP 工具结果过大，请缩小页码或查询范围\"}";
            isError = true;
        }
        return new JSONObject()
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "text").put("text", text)))
                .put("isError", isError);
    }

    private JSONObject errorData(String message) throws JSONException {
        return new JSONObject().put("error", safeText(message));
    }

    private boolean authorized(String authorization) {
        if (authorization == null || authorization.length() < 8
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String presented = authorization.substring(7).trim();
        String expected = prefs().getString(KEY_TOKEN, "");
        return !expected.isEmpty() && constantTimeEquals(presented, expected);
    }

    private static JSONObject rpcResult(Object id, JSONObject result) {
        try {
            return new JSONObject().put("jsonrpc", "2.0")
                    .put("id", id == null ? JSONObject.NULL : id)
                    .put("result", result);
        } catch (JSONException e) {
            return rpcError(id, -32603, "MCP 响应生成失败");
        }
    }

    private static JSONObject rpcError(Object id, int code, String message) {
        try {
            return new JSONObject().put("jsonrpc", "2.0")
                    .put("id", id == null ? JSONObject.NULL : id)
                    .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void writeHttp(OutputStream output, int status, JSONObject body) throws IOException {
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 202: reason = "Accepted"; break;
            case 400: reason = "Bad Request"; break;
            case 401: reason = "Unauthorized"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 405: reason = "Method Not Allowed"; break;
            case 411: reason = "Length Required"; break;
            case 413: reason = "Content Too Large"; break;
            case 415: reason = "Unsupported Media Type"; break;
            case 417: reason = "Expectation Failed"; break;
            case 431: reason = "Request Header Fields Too Large"; break;
            default: reason = "Error";
        }
        byte[] bytes = body == null ? new byte[0]
                : body.toString().getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (bytes.length > 0) output.write(bytes);
        output.flush();
    }

    private static String readHttpLine(InputStream input, int lineLimit, int[] totalBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) return buffer.size() == 0 ? null
                    : new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
            totalBytes[0]++;
            if (totalBytes[0] > MAX_HEADER_BYTES || buffer.size() >= lineLimit) {
                throw new IOException("HTTP headers too large");
            }
            if (value == '\n') break;
            if (value != '\r') buffer.write(value);
        }
        return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readBody(InputStream input, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count < 0) return null;
            offset += count;
        }
        return body;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int difference = a.length ^ b.length;
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            int x = i < a.length ? a[i] & 0xff : 0;
            int y = i < b.length ? b[i] & 0xff : 0;
            difference |= x ^ y;
        }
        return difference == 0;
    }
}
