package com.solosu.mtforum.offline;

import android.os.Bundle;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/** Read-only viewer for a self-contained post saved under app-internal storage. */
public class OfflinePostActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra("path");
        File file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) { Toast.makeText(this,"离线帖子不存在",Toast.LENGTH_SHORT).show(); finish(); return; }
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(false);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.loadUrl(android.net.Uri.fromFile(file).toString());
        setContentView(web);
    }
}
