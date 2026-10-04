package com.solosu.mtforum.mcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;

/** Grouped controls for the read-only MCP endpoint and its free public tunnel. */
public class McpSettingsActivity extends AppCompatActivity {
    private TextView status;
    private Button publicEndpoint;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() { @Override public void run(){ applyStatus(); handler.postDelayed(this,1000); } };

    @Override protected void onCreate(Bundle state){super.onCreate(state);build();}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(18),dp(20),dp(24));root.setBackgroundColor(getColor(R.color.background));
        TextView head=text("‹   MCP 服务",22,true);head.setPadding(0,0,0,dp(18));head.setOnClickListener(v->finish());root.addView(head);
        root.addView(text("服务",16,true));
        Switch enabled=new Switch(this);enabled.setText("启用只读 MCP");enabled.setTextColor(getColor(R.color.text_primary));enabled.setChecked(McpPreferences.enabled(this));root.addView(enabled);
        Switch lan=new Switch(this);lan.setText("允许局域网访问（强制 Token）");lan.setTextColor(getColor(R.color.text_primary));lan.setChecked(McpPreferences.lan(this));root.addView(lan);
        status=text("",13,false);status.setPadding(0,dp(8),0,dp(14));root.addView(status);

        root.addView(text("免费公网隧道",16,true));
        Switch tunnel=new Switch(this);tunnel.setText("启用 Cloudflare Quick Tunnel");tunnel.setTextColor(getColor(R.color.text_primary));tunnel.setChecked(McpPreferences.tunnel(this));root.addView(tunnel);
        TextView tunnelNote=text("无需 Cloudflare 账号，自动生成 trycloudflare.com HTTPS 地址。地址在重启后会变化，访问仍必须携带 MCP Token。",12,false);tunnelNote.setPadding(0,0,0,dp(8));root.addView(tunnelNote);
        publicEndpoint=new Button(this);publicEndpoint.setText("公网地址准备中…");publicEndpoint.setVisibility(View.GONE);root.addView(publicEndpoint);

        // ═══ build97: 隧道模式 / 协议 / 边缘 IP 版本 ═══
        //
        // 原实现把 protocol(http2) 和 edge-ip-version("4") 全硬编码，而且只有
        // quick tunnel 一条路。quick tunnel 是匿名注册，Cloudflare 对它限速，
        // 重连几次就 429 —— 用户报的「开启无效，429 / 错误1」主要就是这个。
        // 这里把三项都开放出来，并补上永久隧道（Token）模式。
        root.addView(text("隧道模式",16,true));

        final String[] modes={"临时隧道（Quick，免账号，地址每次变）","永久隧道（Token，地址固定，无 429）"};
        final android.widget.ListView modeList=new android.widget.ListView(this);
        final android.widget.ArrayAdapter<String> modeAdapter=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_list_item_single_choice,modes);
        modeList.setAdapter(modeAdapter);
        modeList.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        modeList.setItemChecked(McpPreferences.isTokenTunnel(this)?1:0,true);
        root.addView(modeList);

        final EditText tokenInput=new EditText(this);
        tokenInput.setHint("永久隧道 Token（Cloudflare Dashboard → 网络 → 隧道）");
        tokenInput.setText(McpPreferences.tunnelToken(this));
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                |android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        tokenInput.setMinLines(2);tokenInput.setMaxLines(4);
        tokenInput.setVisibility(McpPreferences.isTokenTunnel(this)?View.VISIBLE:View.GONE);
        root.addView(tokenInput);
        TextView tokenNote=text("永久隧道用你在 Cloudflare 建好的 tunnel，不走匿名注册，所以没有 429，公网地址也是固定的（在 Dashboard 里配置）。",12,false);tokenNote.setPadding(0,0,0,dp(8));root.addView(tokenNote);

        root.addView(text("cloudflared 协议",14,true));
        final String[] protos={"http2","quic"};
        final android.widget.ListView protoList=new android.widget.ListView(this);
        protoList.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice,protos));
        protoList.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        protoList.setItemChecked("quic".equals(McpPreferences.tunnelProtocol(this))?1:0,true);
        root.addView(protoList);

        root.addView(text("边缘 IP 版本",14,true));
        final String[] edges={"4","6","auto"};
        final android.widget.ListView edgeList=new android.widget.ListView(this);
        edgeList.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice,edges));
        edgeList.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        String ev=McpPreferences.edgeIpVersion(this);
        edgeList.setItemChecked("6".equals(ev)?1:("auto".equals(ev)?2:0),true);
        root.addView(edgeList);
        TextView edgeNote=text("IPv6 网络下用 4 连不上边缘节点时，改成 6 或 auto 再试。改完这三项都会让隧道重连。",12,false);edgeNote.setPadding(0,dp(4),0,dp(10));root.addView(edgeNote);

        modeList.setOnItemClickListener((p,v,pos,id)->{
            boolean tokenMode=(pos==1);
            McpPreferences.setTunnelMode(this,tokenMode?"token":"quick");
            tokenInput.setVisibility(tokenMode?View.VISIBLE:View.GONE);
        });
        protoList.setOnItemClickListener((p,v,pos,id)->{
            McpPreferences.setTunnelProtocol(this,pos==1?"quic":"http2");
        });
        edgeList.setOnItemClickListener((p,v,pos,id)->{
            McpPreferences.setEdgeIpVersion(this,edges[pos]);
        });
        tokenInput.setOnFocusChangeListener((v,hasFocus)->{
            if(!hasFocus) McpPreferences.setTunnelToken(this,tokenInput.getText().toString());
        });

        root.addView(text("连接",16,true));
        EditText port=new EditText(this);port.setHint("端口");port.setText(String.valueOf(McpPreferences.port(this)));port.setInputType(InputType.TYPE_CLASS_NUMBER);root.addView(port);
        Button copyConfig=new Button(this);copyConfig.setText("一键复制完整 MCP 配置");root.addView(copyConfig);
        Button rotateToken=new Button(this);rotateToken.setText("撤销旧授权并重新生成");root.addView(rotateToken);
        TextView note=text("复制内容已经包含公网地址和授权信息，直接整体粘贴给支持 MCP 的 AI 或放入 MCP 配置即可，无需再分别填写 Token。服务只开放读取工具，敏感登录字段会脱敏。",12,false);note.setPadding(0,dp(12),0,0);root.addView(note);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);

        enabled.setOnCheckedChangeListener((b,v)->{McpPreferences.setEnabled(this,v);apply(port);});
        lan.setOnCheckedChangeListener((b,v)->{McpPreferences.setLan(this,v);if(McpPreferences.enabled(this))apply(port);});
        tunnel.setOnCheckedChangeListener((b,v)->{
            McpPreferences.setTunnel(this,v);
            if(v&&!McpPreferences.enabled(this)){enabled.setChecked(true);return;}
            if(McpPreferences.enabled(this))apply(port);else CloudflareTunnelManager.get().stop();
        });
        copyConfig.setOnClickListener(v->copy("完整 MCP 配置",McpPreferences.clientConfig(this)));
        publicEndpoint.setOnClickListener(v->copy("完整 MCP 配置",McpPreferences.clientConfig(this)));
        rotateToken.setOnClickListener(v->new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("重新生成 Token？").setMessage("所有使用旧 Token 的 AI 客户端会立即失效。")
                .setNegativeButton("取消",null).setPositiveButton("重新生成",(d,w)->{
                    String fresh=McpPreferences.rotateToken(this);copy("新 MCP Token",fresh);
                }).show());
        port.setOnEditorActionListener((v,a,e)->{apply(port);return true;});
        applyStatus();
    }
    /** build97: 找回 Token 输入框（布局是代码拼的，没有 id） */
    private EditText findViewByIdToken(){
        try{
            android.view.ViewGroup root=(android.view.ViewGroup)((android.widget.ScrollView)findViewById(android.R.id.content)).getChildAt(0);
            if(root==null)return null;
            for(int i=0;i<root.getChildCount();i++){
                android.view.View c=root.getChildAt(i);
                if(c instanceof EditText){
                    EditText et=(EditText)c;
                    CharSequence h=et.getHint();
                    if(h!=null&&h.toString().contains("Token"))return et;
                }
            }
        }catch(Exception ignored){}
        return null;
    }

    private void apply(EditText port){
        try{int p=Integer.parseInt(port.getText().toString());if(p<1024||p>65535)throw new Exception();getSharedPreferences("mcp_settings",0).edit().putInt("port",p).apply();}catch(Exception e){Toast.makeText(this,"端口需为 1024-65535",Toast.LENGTH_SHORT).show();return;}
        // build97: 把 Token 输入框里未失焦的内容也存一次，防止用户改完直接点别处
        try{
            EditText ti=findViewByIdToken();
            if(ti!=null) McpPreferences.setTunnelToken(this,ti.getText().toString());
        }catch(Exception ignored){}
        if(McpPreferences.enabled(this)) McpService.start(this); else McpService.stop(this);applyStatus();
    }
    private void applyStatus(){
        CloudflareTunnelManager tunnel=CloudflareTunnelManager.get();
        String base=McpServer.get().running()?"MCP 运行中 · "+McpPreferences.endpoint(this):"MCP 已停止";
        if(McpPreferences.tunnel(this)) base+="\n公网隧道："+tunnel.state()+" · "+tunnel.message();
        status.setText(base);
        String url=tunnel.publicUrl();publicEndpoint.setVisibility(url==null||url.isEmpty()?View.GONE:View.VISIBLE);if(url!=null&&!url.isEmpty())publicEndpoint.setText("一键复制完整公网 MCP 配置\n"+url);
    }
    private void copy(String label,String value){ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(c!=null)c.setPrimaryClip(ClipData.newPlainText(label,value));Toast.makeText(this,label+"已复制",Toast.LENGTH_SHORT).show();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private TextView text(String s,int z,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(getColor(bold?R.color.text_primary:R.color.text_secondary));if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);return v;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
}
