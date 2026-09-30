package com.solosu.mtforum.mcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;

/** Grouped controls for the read-only MCP endpoint. */
public class McpSettingsActivity extends AppCompatActivity {
    private LinearLayout root;
    private TextView status;
    @Override protected void onCreate(Bundle state){super.onCreate(state);build();}
    private void build(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(18),dp(20),dp(24));root.setBackgroundColor(getColor(R.color.background));
        TextView head=text("‹   MCP 服务",22,true);head.setPadding(0,0,0,dp(18));head.setOnClickListener(v->finish());root.addView(head);
        root.addView(text("服务",16,true));
        Switch enabled=new Switch(this);enabled.setText("启用只读 MCP");enabled.setTextColor(getColor(R.color.text_primary));enabled.setChecked(McpPreferences.enabled(this));root.addView(enabled);
        Switch lan=new Switch(this);lan.setText("允许局域网/隧道转发（强制 Token）");lan.setTextColor(getColor(R.color.text_primary));lan.setChecked(McpPreferences.lan(this));root.addView(lan);
        status=text("",13,false);status.setPadding(0,dp(8),0,dp(14));root.addView(status);
        root.addView(text("连接",16,true));
        EditText port=new EditText(this);port.setHint("端口");port.setText(String.valueOf(McpPreferences.port(this)));port.setInputType(InputType.TYPE_CLASS_NUMBER);root.addView(port);
        Button endpoint=new Button(this);endpoint.setText("复制 MCP 端点");root.addView(endpoint);
        Button token=new Button(this);token.setText("复制访问 Token");root.addView(token);
        TextView note=text("默认仅监听 127.0.0.1。开启局域网后可由同网设备、Tailscale 或外部 HTTPS 隧道转发；所有请求仍必须携带 Authorization: Bearer <Token>。服务只暴露读取工具，不允许发帖等写操作。",12,false);note.setPadding(0,dp(12),0,0);root.addView(note);
        setContentView(root);
        enabled.setOnCheckedChangeListener((b,v)->{McpPreferences.setEnabled(this,v);apply(port);});
        lan.setOnCheckedChangeListener((b,v)->{McpPreferences.setLan(this,v);if(McpPreferences.enabled(this))apply(port);});
        endpoint.setOnClickListener(v->copy("MCP 端点",McpPreferences.endpoint(this)));
        token.setOnClickListener(v->copy("MCP Token",McpPreferences.token(this)));
        port.setOnEditorActionListener((v,a,e)->{apply(port);return true;});
        applyStatus();
    }
    private void apply(EditText port){
        try{int p=Integer.parseInt(port.getText().toString());if(p<1024||p>65535)throw new Exception();getSharedPreferences("mcp_settings",0).edit().putInt("port",p).apply();}catch(Exception e){Toast.makeText(this,"端口需为 1024-65535",Toast.LENGTH_SHORT).show();return;}
        if(McpPreferences.enabled(this)){boolean ok=McpServer.get().start(this);if(!ok)Toast.makeText(this,"启动失败："+McpServer.get().error(),Toast.LENGTH_LONG).show();}else McpServer.get().stop();applyStatus();
    }
    private void applyStatus(){status.setText(McpServer.get().running()?"运行中 · "+McpPreferences.endpoint(this):"已停止" );}
    private void copy(String label,String value){ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(c!=null)c.setPrimaryClip(ClipData.newPlainText(label,value));Toast.makeText(this,label+"已复制",Toast.LENGTH_SHORT).show();}
    private TextView text(String s,int z,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(getColor(bold?R.color.text_primary:R.color.text_secondary));if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);return v;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
}
