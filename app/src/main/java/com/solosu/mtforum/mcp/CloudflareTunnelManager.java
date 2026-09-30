package com.solosu.mtforum.mcp;

import android.content.Context;

import com.solosu.mtforum.ai.AiLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs cloudflared's built-in account-free Quick Tunnel against the local MCP endpoint. */
public final class CloudflareTunnelManager {
    public enum State { STOPPED, STARTING, RUNNING, FAILED }
    private static final CloudflareTunnelManager INSTANCE = new CloudflareTunnelManager();
    private static final Pattern QUICK_URL = Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com");
    public static CloudflareTunnelManager get() { return INSTANCE; }

    private final AtomicInteger generation = new AtomicInteger();
    private volatile Process process;
    private volatile State state = State.STOPPED;
    private volatile String publicUrl = "";
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
        message = "正在创建免费公网地址";
        publicUrl = "";
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
        lastError = "";
    }

    private void startInternal(int run) {
        try {
            File binary = new File(app.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
            if (!binary.isFile()) throw new IllegalStateException("当前安装包不含 cloudflared（公网隧道仅支持 arm64 设备）");
            String target = "http://127.0.0.1:" + McpPreferences.port(app);
            // Let cloudflared provision its own Quick Tunnel. This is the documented, robust
            // account-free path and avoids temporary credential/config incompatibilities.
            // Keep the invocation identical to Cloudflare's documented Quick Tunnel command.
            // In particular, --no-autoupdate is a global flag in recent builds; placing it
            // after the tunnel subcommand makes cloudflared print usage and exit with code 1.
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), "tunnel", "--url", target);
            pb.directory(app.getCacheDir()).redirectErrorStream(true);
            pb.environment().put("NO_AUTOUPDATE", "true");
            Process p = pb.start();
            process = p;
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
                String detail=!lastError.isEmpty()?lastError:lastOutput;
                message = detail.isEmpty() ? "隧道进程无输出退出（代码 " + exit + "）" : detail + "（代码 " + exit + "）";
                if (McpPreferences.tunnel(app) && McpPreferences.enabled(app)) scheduleRestart(run);
            }
        } catch (Exception e) {
            if (generation.get() == run) {
                state = State.FAILED;
                message = safe(e);
                AiLog.i("mcp-tunnel", "启动失败：" + message);
                if (McpPreferences.tunnel(app) && McpPreferences.enabled(app)
                        && !message.contains("不含 cloudflared")) scheduleRestart(run);
            }
        }
    }

    private void parseLine(String line) {
        if(line!=null&&!line.trim().isEmpty())lastOutput=cleanError(line);
        Matcher matcher = QUICK_URL.matcher(line);
        if (matcher.find()) {
            publicUrl = matcher.group() + "/mcp";
            message = "公网地址已就绪";
        }
        String low = line.toLowerCase(Locale.ROOT);
        if (low.contains("registered tunnel connection") || low.contains("connection registered")) {
            state = State.RUNNING;
            message = "公网隧道运行中";
        }
        if (low.contains("level=error") || low.contains("\"level\":\"error\"")
                || low.contains(" err=") || low.contains(" error")) {
            lastError = cleanError(line);
            if (publicUrl.isEmpty()) message = lastError;
        }
    }

    private void scheduleRestart(int oldRun) {
        new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            if (generation.get() == oldRun && McpPreferences.tunnel(app) && McpServer.get().running()) start(app);
        }, "mcp-tunnel-restart").start();
    }

    private synchronized void stopProcessOnly() {
        Process p = process;
        process = null;
        if (p != null) try { p.destroy(); } catch (Exception ignored) {}
    }
    private static String cleanError(String line) {
        String value=line.replaceAll("(?i)token=[^ ,]+", "token=[已脱敏]").trim();
        return value.length()>220?value.substring(0,220):value;
    }
    private static String safe(Exception e) {
        String m=e.getMessage();
        return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;
    }
}
