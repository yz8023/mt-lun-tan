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

        root.addView(text("连接",16,true));
        EditText port=new EditText(this);port.setHint("端口");port.setText(String.valueOf(McpPreferences.port(this)));port.setInputType(InputType.TYPE_CLASS_NUMBER);root.addView(port);
        Button endpoint=new Button(this);endpoint.setText("复制本机/局域网 MCP 端点");root.addView(endpoint);
        Button token=new Button(this);token.setText("复制访问 Token");root.addView(token);
        Button rotateToken=new Button(this);rotateToken.setText("撤销旧 Token 并重新生成");root.addView(rotateToken);
        TextView note=text("AI 客户端请求头：Authorization: Bearer <Token>。服务只开放读取工具，Cookie、密码和 formhash 会脱敏，不允许发帖、回复、点赞等写操作。",12,false);note.setPadding(0,dp(12),0,0);root.addView(note);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);

        enabled.setOnCheckedChangeListener((b,v)->{McpPreferences.setEnabled(this,v);apply(port);});
        lan.setOnCheckedChangeListener((b,v)->{McpPreferences.setLan(this,v);if(McpPreferences.enabled(this))apply(port);});
        tunnel.setOnCheckedChangeListener((b,v)->{
            McpPreferences.setTunnel(this,v);
            if(v&&!McpPreferences.enabled(this)){enabled.setChecked(true);return;}
            if(McpPreferences.enabled(this))apply(port);else CloudflareTunnelManager.get().stop();
        });
        endpoint.setOnClickListener(v->copy("MCP 端点",McpPreferences.endpoint(this)));
        publicEndpoint.setOnClickListener(v->copy("MCP 公网端点",CloudflareTunnelManager.get().publicUrl()));
        token.setOnClickListener(v->copy("MCP Token",McpPreferences.token(this)));
        rotateToken.setOnClickListener(v->new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("重新生成 Token？").setMessage("所有使用旧 Token 的 AI 客户端会立即失效。")
                .setNegativeButton("取消",null).setPositiveButton("重新生成",(d,w)->{
                    String fresh=McpPreferences.rotateToken(this);copy("新 MCP Token",fresh);
                }).show());
        port.setOnEditorActionListener((v,a,e)->{apply(port);return true;});
        applyStatus();
    }
    private void apply(EditText port){
        try{int p=Integer.parseInt(port.getText().toString());if(p<1024||p>65535)throw new Exception();getSharedPreferences("mcp_settings",0).edit().putInt("port",p).apply();}catch(Exception e){Toast.makeText(this,"端口需为 1024-65535",Toast.LENGTH_SHORT).show();return;}
        if(McpPreferences.enabled(this)) McpService.start(this); else McpService.stop(this);applyStatus();
    }
    private void applyStatus(){
        CloudflareTunnelManager tunnel=CloudflareTunnelManager.get();
        String base=McpServer.get().running()?"MCP 运行中 · "+McpPreferences.endpoint(this):"MCP 已停止";
        if(McpPreferences.tunnel(this)) base+="\n公网隧道："+tunnel.state()+" · "+tunnel.message();
        status.setText(base);
        String url=tunnel.publicUrl();publicEndpoint.setVisibility(url==null||url.isEmpty()?View.GONE:View.VISIBLE);if(url!=null&&!url.isEmpty())publicEndpoint.setText("复制公网端点\n"+url);
    }
    private void copy(String label,String value){ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(c!=null)c.setPrimaryClip(ClipData.newPlainText(label,value));Toast.makeText(this,label+"已复制",Toast.LENGTH_SHORT).show();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private TextView text(String s,int z,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(getColor(bold?R.color.text_primary:R.color.text_secondary));if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);return v;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
}
