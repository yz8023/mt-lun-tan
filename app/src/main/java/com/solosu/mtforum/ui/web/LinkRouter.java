package com.solosu.mtforum.ui.web;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.solosu.mtforum.ui.DownloadPreferences;

/** Centralizes the user's external-link preference. */
public final class LinkRouter {
    private LinkRouter() {}
    public static void open(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        if (DownloadPreferences.getMode(context) == DownloadPreferences.MODE_IN_APP) {
            InAppBrowserActivity.open(context, url);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }
}
