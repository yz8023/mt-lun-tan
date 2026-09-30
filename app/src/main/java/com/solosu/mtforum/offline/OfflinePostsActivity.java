package com.solosu.mtforum.offline;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;
import com.solosu.mtforum.ui.widget.DialogHelper;

/** Library of posts retained in app-internal storage. */
public class OfflinePostsActivity extends AppCompatActivity {
    private LinearLayout list;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(getColor(R.color.background));
        TextView title = new TextView(this); title.setText("‹   已保存帖子"); title.setTextSize(20); title.setTextColor(getColor(R.color.text_primary));
        title.setPadding(dp(16),dp(18),dp(16),dp(14)); title.setOnClickListener(v->finish()); root.addView(title);
        ScrollView scroll = new ScrollView(this); list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(12),0,dp(12),dp(20)); scroll.addView(list);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }
    @Override protected void onResume(){super.onResume(); refresh();}
    private void refresh(){
        list.removeAllViews(); java.util.List<OfflinePostStore.Item> items=OfflinePostStore.list(this);
        if(items.isEmpty()){ TextView empty=text("暂无离线帖子\n在帖子右上角选择“保存离线页面”",14); empty.setGravity(17); empty.setPadding(0,dp(80),0,0); list.addView(empty); return; }
        for(OfflinePostStore.Item item:items){
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16),dp(14),dp(16),dp(14)); card.setBackgroundResource(R.drawable.thread_card_bg);
            TextView t=text(item.title,16); t.setTextColor(getColor(R.color.text_primary)); t.setTypeface(null,android.graphics.Typeface.BOLD); card.addView(t);
            TextView sub=text(item.author+" · "+item.savedAt+(item.includesReplies?" · 含评论":" · 仅正文"),12); sub.setTextColor(getColor(R.color.text_secondary)); sub.setPadding(0,dp(6),0,0); card.addView(sub);
            card.setOnClickListener(v->{Intent it=new Intent(this,OfflinePostActivity.class);it.putExtra("path",item.htmlFile.getAbsolutePath());startActivity(it);});
            card.setOnLongClickListener(v->{ android.app.Dialog d=new AlertDialog.Builder(this).setTitle(item.title).setItems(new String[]{"查看","删除"},(x,w)->{if(w==0){v.performClick();}else{OfflinePostStore.delete(this,item.tid);refresh();Toast.makeText(this,"已删除",Toast.LENGTH_SHORT).show();}}).setNegativeButton("取消",null).show(); DialogHelper.applyToAlertDialog(d,this); return true;});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(10); list.addView(card,lp);
        }
    }
    private TextView text(String s,float size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);return v;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
}
