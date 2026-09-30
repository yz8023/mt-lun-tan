package com.solosu.mtforum.mcp;

import android.content.Context;

import com.solosu.mtforum.ai.AiLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Account-free Cloudflare tunnel adapted for Android's DNS environment. */
public final class CloudflareTunnelManager {
    public enum State { STOPPED, STARTING, RUNNING, FAILED }
    private static final CloudflareTunnelManager INSTANCE = new CloudflareTunnelManager();
    public static CloudflareTunnelManager get() { return INSTANCE; }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Process process;
    private volatile State state = State.STOPPED;
    private volatile String publicUrl = "";
    private volatile String pendingUrl = "";
    private volatile String message = "";
    private volatile String lastError = "";
    private volatile String lastOutput = "";
    private Context app;
    private CloudflareTunnelManager() {}

    public State state() { return state; }
    public String publicUrl() { return publicUrl; }
    public String message() { return message; }

    public synchronized void start(Context context) {
        app = context.getApplicationContext();
        int run = generation.incrementAndGet();
        stopProcessOnly();
        state = State.STARTING;
        message = "正在注册免费公网隧道";
        publicUrl = "";
        pendingUrl = "";
        lastError = "";
        lastOutput = "";
        new Thread(() -> startInternal(run), "mcp-cloudflare-start").start();
    }

    public synchronized void stop() {
        generation.incrementAndGet();
        stopProcessOnly();
        state = State.STOPPED;
        message = "已停止";
        publicUrl = "";
        pendingUrl = "";
        lastError = "";
        lastOutput = "";
    }

    private void startInternal(int run) {
        try {
            File binary = new File(app.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
            if (!binary.isFile()) throw new IllegalStateException("当前安装包不含 cloudflared（公网隧道仅支持 arm64 设备）");

            // Registration is deliberately done by OkHttp. cloudflared's Go resolver reads
            // Android's placeholder /etc/resolv.conf ([::1]:53) and cannot resolve the API.
            Quick quick = registerQuickTunnel();
            if (generation.get() != run) return;
            pendingUrl = "https://" + quick.hostname + "/mcp";

            File credentials = new File(app.getCacheDir(), "mt_mcp_tunnel_creds.json");
            write(credentials, new JSONObject().put("AccountTag", quick.accountTag)
                    .put("TunnelID", quick.id).put("TunnelSecret", quick.secret).toString());
            File config = new File(app.getCacheDir(), "mt_mcp_tunnel.yml");
            write(config, "tunnel: " + quick.id + "\ncredentials-file: " + credentials.getAbsolutePath()
                    + "\nprotocol: http2\nno-autoupdate: true\nedge-ip-version: \"4\"\nretry-dns-errors: true\n"
                    + "ingress:\n  - hostname: " + quick.hostname + "\n    service: http://127.0.0.1:"
                    + McpPreferences.port(app) + "\n  - service: http_status:404\n");

            List<String> edges = resolveEdgeIps();
            if (edges.isEmpty()) throw new IllegalStateException("无法解析 Cloudflare 边缘节点 IP");
            List<String> command = new ArrayList<>();
            command.add(binary.getAbsolutePath());
            command.add("tunnel"); command.add("--config"); command.add(config.getAbsolutePath());
            command.add("--no-autoupdate");
            for (String edge : edges) { command.add("--edge"); command.add(edge); }
            command.add("run"); command.add(quick.id);
            AiLog.i("mcp-tunnel", "使用 Android 预解析边缘节点：" + edges);

            ProcessBuilder pb = new ProcessBuilder(command).directory(app.getCacheDir()).redirectErrorStream(true);
            pb.environment().put("NO_AUTOUPDATE", "true");
            Process p = pb.start();
            process = p;
            message = "隧道连接中";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while (generation.get() == run && (line = reader.readLine()) != null) {
                    parseLine(line);
                    AiLog.i("mcp-tunnel", line.length() > 500 ? line.substring(0, 500) : line);
                }
            }
            int exit = p.waitFor();
            if (generation.get() == run) {
                state = State.FAILED;
                publicUrl = "";
                String detail = !lastError.isEmpty() ? lastError : lastOutput;
                message = detail.isEmpty() ? "隧道进程无输出退出（代码 " + exit + "）" : detail + "（代码 " + exit + "）";
                if (McpPreferences.tunnel(app) && McpPreferences.enabled(app)) scheduleRestart(run);
            }
        } catch (Exception e) {
            if (generation.get() == run) {
                state = State.FAILED;
                publicUrl = "";
                message = safe(e);
                AiLog.i("mcp-tunnel", "启动失败：" + message);
                if (McpPreferences.tunnel(app) && McpPreferences.enabled(app)
                        && !message.contains("不含 cloudflared")) scheduleRestart(run);
            }
        }
    }

    private void parseLine(String line) {
        if (line != null && !line.trim().isEmpty()) lastOutput = cleanError(line);
        String low = line.toLowerCase(Locale.ROOT);
        if (low.contains("registered tunnel connection") || low.contains("connection registered")) {
            publicUrl = pendingUrl;
            state = State.RUNNING;
            message = "公网隧道运行中";
        }
        if (low.contains("level=error") || low.contains("\"level\":\"error\"")
                || low.contains(" err=") || low.contains(" error")) {
            lastError = cleanError(line);
            if (state != State.RUNNING) message = lastError;
        }
    }

    private Quick registerQuickTunnel() throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Request request = new Request.Builder().url("https://api.trycloudflare.com/tunnel")
                        .header("User-Agent", "MTForum-Android")
                        .post(RequestBody.create(MediaType.parse("application/json"), new byte[0])).build();
                try (Response response = client.newCall(request).execute()) {
                    String body = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) throw new IllegalStateException("Cloudflare HTTP " + response.code());
                    JSONObject result = new JSONObject(body).getJSONObject("result");
                    return new Quick(result.getString("id"), result.getString("hostname"),
                            result.getString("account_tag"), result.getString("secret"));
                }
            } catch (Exception e) { last = e; if (attempt == 0) Thread.sleep(800); }
        }
        throw last == null ? new IllegalStateException("Cloudflare 注册失败") : last;
    }

    /** Resolve with Android first; DoH by literal IP avoids cloudflared's broken localhost DNS. */
    private List<String> resolveEdgeIps() {
        Set<String> result = new LinkedHashSet<>();
        String[] hosts = {"region1.argotunnel.com", "region2.argotunnel.com"};
        for (String host : hosts) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host))
                    if (address instanceof Inet4Address) result.add(address.getHostAddress());
            } catch (Exception e) { AiLog.i("mcp-tunnel", "系统 DNS 解析失败 " + host + "：" + safe(e)); }
        }
        if (result.isEmpty()) for (String host : hosts) resolveByDoh(host, result);
        List<String> values = new ArrayList<>(result);
        return values.size() > 4 ? values.subList(0, 4) : values;
    }

    private void resolveByDoh(String host, Set<String> output) {
        try {
            Request request = new Request.Builder()
                    .url("https://1.1.1.1/dns-query?name=" + host + "&type=A")
                    .header("Accept", "application/dns-json").build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                JSONArray answers = new JSONObject(response.body().string()).optJSONArray("Answer");
                if (answers == null) return;
                for (int i = 0; i < answers.length(); i++) {
                    String ip = answers.optJSONObject(i) == null ? "" : answers.optJSONObject(i).optString("data");
                    if (ip.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) output.add(ip);
                }
            }
        } catch (Exception e) { AiLog.i("mcp-tunnel", "DoH 解析失败 " + host + "：" + safe(e)); }
    }

    private void scheduleRestart(int oldRun) {
        new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            if (generation.get() == oldRun && McpPreferences.tunnel(app) && McpServer.get().running()) start(app);
        }, "mcp-tunnel-restart").start();
    }

    private synchronized void stopProcessOnly() {
        Process p = process; process = null;
        if (p != null) try { p.destroy(); } catch (Exception ignored) {}
    }
    private static void write(File f, String value) throws Exception { try (FileWriter w = new FileWriter(f, false)) { w.write(value); } }
    private static String cleanError(String line) {
        String value = line.replaceAll("(?i)token=[^ ,]+", "token=[已脱敏]").trim();
        return value.length() > 260 ? value.substring(0, 260) : value;
    }
    private static String safe(Exception e) {
        String m = e.getMessage(); return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
    private static final class Quick {
        final String id, hostname, accountTag, secret;
        Quick(String id, String hostname, String accountTag, String secret) {
            this.id=id; this.hostname=hostname; this.accountTag=accountTag; this.secret=secret;
        }
    }
}
