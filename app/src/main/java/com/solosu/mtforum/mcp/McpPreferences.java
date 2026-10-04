package com.solosu.mtforum.mcp;

import android.content.Context;
import android.text.TextUtils;

import java.security.SecureRandom;

public final class McpPreferences {
    private static final String PREF = "mcp_settings";
    private McpPreferences() {}
    public static boolean enabled(Context c) { return c.getSharedPreferences(PREF,0).getBoolean("enabled",false); }
    public static boolean lan(Context c) { return c.getSharedPreferences(PREF,0).getBoolean("lan",false); }
    public static boolean tunnel(Context c) { return c.getSharedPreferences(PREF,0).getBoolean("tunnel",false); }
    public static int port(Context c) { return c.getSharedPreferences(PREF,0).getInt("port",8765); }

    // ═══ build97: 隧道可配项 ═══
    // 原实现 protocol / edge-ip-version 全硬编码（http2 + "4"），且只有 quick
    // tunnel 一条路。Cloudflare 对 quick tunnel 的匿名注册有速率限制，重连几次
    // 就是 429 —— 用户报的「开启无效，429 / 错误1」主要就是这个。
    // 永久隧道（named tunnel + Token）不走匿名注册，天然没有这个限制。

    /** 隧道模式："quick"(临时，默认) 或 "token"(永久) */
    public static String tunnelMode(Context c) {
        return c.getSharedPreferences(PREF,0).getString("tunnel_mode","quick");
    }
    public static void setTunnelMode(Context c, String v) {
        c.getSharedPreferences(PREF,0).edit()
                .putString("tunnel_mode","token".equals(v)?"token":"quick").apply();
    }
    public static boolean isTokenTunnel(Context c) { return "token".equals(tunnelMode(c)); }

    /** 永久隧道 Token（Cloudflare Dashboard -> Zero Trust -> Tunnels 给的） */
    public static String tunnelToken(Context c) {
        return c.getSharedPreferences(PREF,0).getString("tunnel_token","");
    }
    public static void setTunnelToken(Context c, String v) {
        c.getSharedPreferences(PREF,0).edit().putString("tunnel_token", v==null?"":v.trim()).apply();
    }

    /** cloudflared 协议："http2"(默认) 或 "quic" */
    public static String tunnelProtocol(Context c) {
        return c.getSharedPreferences(PREF,0).getString("tunnel_protocol","http2");
    }
    public static void setTunnelProtocol(Context c, String v) {
        c.getSharedPreferences(PREF,0).edit()
                .putString("tunnel_protocol","quic".equals(v)?"quic":"http2").apply();
    }

    /** 边缘 IP 版本："4"(默认) / "6" / "auto" */
    public static String edgeIpVersion(Context c) {
        return c.getSharedPreferences(PREF,0).getString("edge_ip_version","4");
    }
    public static void setEdgeIpVersion(Context c, String v) {
        String n = ("6".equals(v)||"auto".equals(v)) ? v : "4";
        c.getSharedPreferences(PREF,0).edit().putString("edge_ip_version",n).apply();
    }
    public static void setEnabled(Context c, boolean v) { c.getSharedPreferences(PREF,0).edit().putBoolean("enabled",v).apply(); }
    public static void setLan(Context c, boolean v) { c.getSharedPreferences(PREF,0).edit().putBoolean("lan",v).apply(); }
    public static void setTunnel(Context c, boolean v) { c.getSharedPreferences(PREF,0).edit().putBoolean("tunnel",v).apply(); }
    public static String token(Context c) {
        String value=c.getSharedPreferences(PREF,0).getString("token","");
        if (!TextUtils.isEmpty(value)) return value;
        byte[] b=new byte[24];new SecureRandom().nextBytes(b);
        value=android.util.Base64.encodeToString(b,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
        c.getSharedPreferences(PREF,0).edit().putString("token",value).apply();return value;
    }
    public static String rotateToken(Context c) {
        c.getSharedPreferences(PREF,0).edit().remove("token").commit();
        return token(c);
    }
    /** One paste contains everything an AI client needs; no separate URL/token setup. */
    public static String clientConfig(Context c) {
        String url=CloudflareTunnelManager.get().publicUrl();
        if(TextUtils.isEmpty(url))url=endpoint(c);
        try {
            org.json.JSONObject server=new org.json.JSONObject().put("url",url)
                    .put("headers",new org.json.JSONObject().put("Authorization","Bearer "+token(c)));
            return new org.json.JSONObject().put("mcpServers",
                    new org.json.JSONObject().put("MTForum",server)).toString(2);
        } catch(Exception ignored) { return url+"\nAuthorization: Bearer "+token(c); }
    }
    public static String endpoint(Context c) {
        String host="127.0.0.1";
        if(lan(c)) try {
            java.util.Enumeration<java.net.NetworkInterface> all=java.net.NetworkInterface.getNetworkInterfaces();
            while(all.hasMoreElements()){
                java.util.Enumeration<java.net.InetAddress> addresses=all.nextElement().getInetAddresses();
                while(addresses.hasMoreElements()){
                    java.net.InetAddress a=addresses.nextElement();
                    if(!a.isLoopbackAddress()&&a instanceof java.net.Inet4Address){host=a.getHostAddress();break;}
                }
                if(!"127.0.0.1".equals(host))break;
            }
        }catch(Exception ignored){}
        return "http://"+host+":"+port(c)+"/mcp";
    }
}
