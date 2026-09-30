package com.solosu.mtforum.mcp;

import android.content.Context;

import com.solosu.mtforum.ai.AiLog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Free trycloudflare.com quick tunnel targeting the phone's local MCP endpoint. */
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
    private volatile String message = "";
    private Context app;
    private CloudflareTunnelManager() {}

    public State state() { return state; }
    public String publicUrl() { return publicUrl; }
    public String message() { return message; }

    public synchronized void start(Context context) {
        app = context.getApplicationContext();
        int run = generation.incrementAndGet();
        stopProcessOnly();
        state = State.STARTING; message = "正在注册免费 Cloudflare 临时隧道"; publicUrl = "";
        new Thread(() -> startInternal(run), "mcp-cloudflare-start").start();
    }

    public synchronized void stop() {
        generation.incrementAndGet();
        stopProcessOnly();
        state = State.STOPPED; message = "已停止"; publicUrl = "";
    }

    private void startInternal(int run) {
        try {
            File binary = new File(app.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
            if (!binary.isFile()) throw new IllegalStateException("当前安装包不含 cloudflared（仅支持 arm64）");
            Quick quick = register();
            if (generation.get() != run) return;
            File creds = new File(app.getCacheDir(), "mt_mcp_tunnel_creds.json");
            write(creds, new JSONObject().put("AccountTag", quick.accountTag)
                    .put("TunnelID", quick.id).put("TunnelSecret", quick.secret).toString());
            File config = new File(app.getCacheDir(), "mt_mcp_tunnel.yml");
            write(config, "tunnel: " + quick.id + "\ncredentials-file: " + creds.getAbsolutePath()
                    + "\nprotocol: http2\nno-autoupdate: true\nedge-ip-version: auto\n"
                    + "ingress:\n  - hostname: " + quick.hostname + "\n    service: http://127.0.0.1:"
                    + McpPreferences.port(app) + "\n  - service: http_status:404\n");
            publicUrl = "https://" + quick.hostname + "/mcp";
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "tunnel", "--config",
                    config.getAbsolutePath(), "--no-autoupdate", "run", quick.id);
            pb.directory(app.getCacheDir()).redirectErrorStream(true);
            pb.environment().put("NO_AUTOUPDATE", "true");
            Process p = pb.start(); process = p;
            message = "隧道连接中";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while (generation.get() == run && (line = reader.readLine()) != null) {
                    String low = line.toLowerCase(java.util.Locale.ROOT);
                    if (low.contains("registered tunnel connection")) {
                        state = State.RUNNING; message = "公网隧道运行中";
                    } else if (low.contains(" err ") || low.contains("error")) {
                        message = line.length() > 180 ? line.substring(0, 180) : line;
                    }
                    AiLog.i("mcp-tunnel", line.length() > 300 ? line.substring(0, 300) : line);
                }
            }
            int exit = p.waitFor();
            if (generation.get() == run) {
                state = State.FAILED; message = "隧道进程退出，代码 " + exit;
                if (McpPreferences.tunnel(app) && McpPreferences.enabled(app)) scheduleRestart(run);
            }
        } catch (Exception e) {
            if (generation.get() == run) {
                state = State.FAILED; message = safe(e); AiLog.i("mcp-tunnel", "启动失败：" + message);
                if (McpPreferences.tunnel(app) && McpPreferences.enabled(app)
                        && !message.contains("不含 cloudflared")) scheduleRestart(run);
            }
        }
    }

    private void scheduleRestart(int oldRun) {
        new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            if (generation.get() == oldRun && McpPreferences.tunnel(app) && McpServer.get().running()) start(app);
        }, "mcp-tunnel-restart").start();
    }

    private Quick register() throws Exception {
        Exception last=null;
        for(int attempt=0;attempt<2;attempt++){
            try{
                Request request = new Request.Builder().url("https://api.trycloudflare.com/tunnel")
                        .header("User-Agent", "MTForum-Android")
                        .post(RequestBody.create(MediaType.parse("application/json"), new byte[0])).build();
                try (Response response = client.newCall(request).execute()) {
                    String body = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) throw new IllegalStateException("Cloudflare HTTP " + response.code() + ": " + body.substring(0,Math.min(160,body.length())));
                    JSONObject result = new JSONObject(body).getJSONObject("result");
                    return new Quick(result.getString("id"), result.getString("hostname"),
                            result.getString("account_tag"), result.getString("secret"));
                }
            }catch(Exception e){last=e;if(attempt==0)Thread.sleep(800);}
        }
        throw last==null?new IllegalStateException("Cloudflare 注册失败"):last;
    }

    private synchronized void stopProcessOnly() {
        Process p = process; process = null;
        if (p != null) try { p.destroy(); } catch (Exception ignored) {}
    }
    private static void write(File f, String value) throws Exception { try (FileWriter w = new FileWriter(f, false)) { w.write(value); } }
    private static String safe(Exception e) { String m=e.getMessage(); return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m; }
    private static final class Quick { final String id,hostname,accountTag,secret; Quick(String i,String h,String a,String s){id=i;hostname=h;accountTag=a;secret=s;} }
}
