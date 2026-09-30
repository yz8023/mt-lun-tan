package com.solosu.mtforum.mcp;

import android.content.Context;
import android.text.TextUtils;

import java.security.SecureRandom;

public final class McpPreferences {
    private static final String PREF = "mcp_settings";
    private McpPreferences() {}
    public static boolean enabled(Context c) { return c.getSharedPreferences(PREF,0).getBoolean("enabled",false); }
    public static boolean lan(Context c) { return c.getSharedPreferences(PREF,0).getBoolean("lan",false); }
    public static int port(Context c) { return c.getSharedPreferences(PREF,0).getInt("port",8765); }
    public static void setEnabled(Context c, boolean v) { c.getSharedPreferences(PREF,0).edit().putBoolean("enabled",v).apply(); }
    public static void setLan(Context c, boolean v) { c.getSharedPreferences(PREF,0).edit().putBoolean("lan",v).apply(); }
    public static String token(Context c) {
        String value=c.getSharedPreferences(PREF,0).getString("token","");
        if (!TextUtils.isEmpty(value)) return value;
        byte[] b=new byte[24];new SecureRandom().nextBytes(b);
        value=android.util.Base64.encodeToString(b,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
        c.getSharedPreferences(PREF,0).edit().putString("token",value).apply();return value;
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
