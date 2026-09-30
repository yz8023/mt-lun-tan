package com.solosu.mtforum.mcp;

import android.content.Context;
import android.text.TextUtils;

import com.solosu.mtforum.ai.AiClient;
import com.solosu.mtforum.ai.AiLog;
import com.solosu.mtforum.ai.ForumTools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small authenticated, read-only Streamable-HTTP MCP server backed by ForumTools. */
public final class McpServer {
    private static final McpServer INSTANCE = new McpServer();
    private static final Set<String> READ_ONLY = new HashSet<>();
    static {
        java.util.Collections.addAll(READ_ONLY, "search_forum", "list_threads", "list_forums",
                "get_thread", "get_replies", "my_threads", "get_notices", "get_user_profile");
    }
    public static McpServer get() { return INSTANCE; }
    private volatile ServerSocket server;
    private ExecutorService pool;
    private Context app;
    private volatile String error = "";
    private McpServer() {}

    public synchronized boolean start(Context context) {
        stop();
        app=context.getApplicationContext();
        try {
            InetAddress bind=InetAddress.getByName(McpPreferences.lan(app)?"0.0.0.0":"127.0.0.1");
            server=new ServerSocket(McpPreferences.port(app),16,bind);
            pool=Executors.newFixedThreadPool(4);
            pool.execute(this::acceptLoop);
            error="";
            if (McpPreferences.tunnel(app)) CloudflareTunnelManager.get().start(app);
            return true;
        } catch(Exception e){ error=e.getMessage()==null?e.toString():e.getMessage(); stop(); return false; }
    }
    public synchronized void stop(){CloudflareTunnelManager.get().stop();try{if(server!=null)server.close();}catch(Exception ignored){}server=null;if(pool!=null)pool.shutdownNow();pool=null;}
    public boolean running(){return server!=null&&!server.isClosed();}
    public String error(){return error;}

    private void acceptLoop(){
        while(running()) try { Socket s=server.accept(); ExecutorService p=pool;if(p!=null)p.execute(()->handle(s)); }
        catch(Exception e){if(running())error=e.getMessage();}
    }

    private void handle(Socket socket){
        try(Socket s=socket; BufferedInputStream in=new BufferedInputStream(s.getInputStream()); BufferedOutputStream out=new BufferedOutputStream(s.getOutputStream())){
            s.setSoTimeout(35_000);
            String requestLine=readLine(in); if(requestLine==null)return;
            String[] first=requestLine.split(" "); if(first.length<2){send(out,400,"bad request");return;}
            int length=0;String auth="",origin="",host="";
            for(String line;(line=readLine(in))!=null&&!line.isEmpty();){
                int c=line.indexOf(':');if(c<0)continue;String k=line.substring(0,c).trim().toLowerCase(Locale.ROOT);String v=line.substring(c+1).trim();
                if("content-length".equals(k))try{length=Integer.parseInt(v);}catch(Exception ignored){}
                else if("authorization".equals(k))auth=v;else if("origin".equals(k))origin=v;else if("host".equals(k))host=v;
            }
            if(!isAllowedHost(host)){send(out,403,"host forbidden");return;}
            if(!constantEquals(auth,"Bearer "+McpPreferences.token(app))){AiLog.i("mcp-audit","拒绝未授权请求："+s.getInetAddress().getHostAddress());send(out,403,"forbidden");return;}
            if(!origin.isEmpty()&&!isAllowedOrigin(origin)){send(out,403,"origin forbidden");return;}
            if("GET".equals(first[0])&&first[1].startsWith("/health")){sendJson(out,200,new JSONObject().put("ok",true).toString());return;}
            if(!"POST".equals(first[0])||!first[1].startsWith("/mcp")||length<=0||length>1_048_576){send(out,404,"not found");return;}
            byte[] body=new byte[length];int off=0,n;while(off<length&&(n=in.read(body,off,length-off))>0)off+=n;
            JSONObject req=new JSONObject(new String(body,0,off,StandardCharsets.UTF_8));
            String method=req.optString("method");Object id=req.opt("id");
            if(method.startsWith("notifications/")){send(out,202,"");return;}
            JSONObject response=new JSONObject().put("jsonrpc","2.0").put("id",id==null?JSONObject.NULL:id);
            try{response.put("result",dispatch(method,req.optJSONObject("params")));}
            catch(Exception e){response.put("error",new JSONObject().put("code",-32000).put("message",safe(e)));}
            sendJson(out,200,response.toString());
        }catch(Exception ignored){}
    }

    private JSONObject dispatch(String method,JSONObject params)throws Exception{
        if("initialize".equals(method)) return new JSONObject().put("protocolVersion","2025-03-26")
                .put("capabilities",new JSONObject().put("tools",new JSONObject()))
                .put("serverInfo",new JSONObject().put("name","MTForum Android").put("version","1.0"));
        if("ping".equals(method))return new JSONObject();
        if("tools/list".equals(method)){
            JSONArray tools=new JSONArray();
            tools.put(tool("help","说明可用能力和安全限制",new JSONObject().put("type","object").put("properties",new JSONObject())));
            for(AiClient.ToolDef d:ForumTools.definitions())if(READ_ONLY.contains(d.name))
                tools.put(tool(d.name,d.description,new JSONObject(d.parametersJson)));
            return new JSONObject().put("tools",tools);
        }
        if("tools/call".equals(method)){
            if(params==null)throw new IllegalArgumentException("缺少参数");
            String name=params.optString("name"); JSONObject args=params.optJSONObject("arguments");if(args==null)args=new JSONObject();
            AiLog.i("mcp-audit","只读工具调用："+name);
            String text;
            if("help".equals(name))text="MTForum 只读 MCP。可读取版块、搜索、帖子全文与回复、用户资料和当前账号主题；不提供发帖、回复、点赞等写操作。所有请求复用手机当前登录态。";
            else { if(!READ_ONLY.contains(name))throw new SecurityException("该工具不存在或不是只读工具"); text=ForumTools.execute(app,name,args); }
            text=sanitize(text);
            return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text))).put("isError",false);
        }
        throw new IllegalArgumentException("不支持的方法："+method);
    }
    private JSONObject tool(String n,String d,JSONObject schema)throws Exception{return new JSONObject().put("name",n).put("description",d).put("inputSchema",schema);}
    private String sanitize(String s){if(s==null)return"";s=s.replaceAll("(?i)\\\"(cookie|cookies|password|passwordEnc|formhash|saltkey|auth)\\\"\\s*:\\s*\\\"[^\\\"]*\\\"","\"$1\":\"[已脱敏]\"");s=s.replaceAll("(?i)(formhash|auth|saltkey)=([^&\\s]+)","$1=[已脱敏]");return s.length()>500_000?s.substring(0,500_000)+"\n[内容已截断]":s;}
    private boolean isAllowedOrigin(String o){try{String h=android.net.Uri.parse(o).getHost();return isAllowedName(h);}catch(Exception e){return false;}}
    private boolean isAllowedHost(String value){try{if(TextUtils.isEmpty(value))return false;String h=android.net.Uri.parse("http://"+value).getHost();return isAllowedName(h);}catch(Exception e){return false;}}
    private boolean isAllowedName(String h){
        if("127.0.0.1".equals(h)||"localhost".equalsIgnoreCase(h)||"::1".equals(h)||"[::1]".equals(h))return true;
        if(McpPreferences.lan(app))return true;
        try{String publicHost=android.net.Uri.parse(CloudflareTunnelManager.get().publicUrl()).getHost();return !TextUtils.isEmpty(publicHost)&&publicHost.equalsIgnoreCase(h);}catch(Exception ignored){return false;}
    }
    private boolean constantEquals(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private String safe(Exception e){String m=e.getMessage();return TextUtils.isEmpty(m)?e.getClass().getSimpleName():m;}
    private String readLine(BufferedInputStream in)throws Exception{StringBuilder b=new StringBuilder();int c;while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.append((char)c);if(b.length()>8192)throw new Exception("header too long");}return c==-1&&b.length()==0?null:b.toString();}
    private void send(BufferedOutputStream out,int code,String text)throws Exception{sendRaw(out,code,"text/plain; charset=utf-8",text.getBytes(StandardCharsets.UTF_8));}
    private void sendJson(BufferedOutputStream out,int code,String text)throws Exception{sendRaw(out,code,"application/json; charset=utf-8",text.getBytes(StandardCharsets.UTF_8));}
    private void sendRaw(BufferedOutputStream out,int code,String type,byte[] body)throws Exception{String reason=code==200?"OK":code==202?"Accepted":code==403?"Forbidden":"Error";String h="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
}
