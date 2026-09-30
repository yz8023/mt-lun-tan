package com.solosu.mtforum.mcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.solosu.mtforum.R;

/** Foreground owner for the local MCP socket and cloudflared child process. */
public final class McpService extends Service {
    private static final String CHANNEL="mcp_service";
    private static final int NOTIFICATION_ID=9472;

    public static void start(android.content.Context context){
        androidx.core.content.ContextCompat.startForegroundService(context,
                new Intent(context,McpService.class));
    }
    public static void stop(android.content.Context context){
        context.stopService(new Intent(context,McpService.class));
        McpServer.get().stop();
    }

    @Override public void onCreate(){super.onCreate();createChannel();startForeground(NOTIFICATION_ID,notification("正在启动只读 MCP…"));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!McpPreferences.enabled(this)){stopSelf();return START_NOT_STICKY;}
        boolean ok=McpServer.get().start(this);
        String detail=ok?(McpPreferences.tunnel(this)?"MCP 与公网隧道保持运行":"只读 MCP 正在运行"):("启动失败："+McpServer.get().error());
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification(detail));
        return START_STICKY;
    }
    @Override public void onDestroy(){McpServer.get().stop();super.onDestroy();}
    @Nullable @Override public IBinder onBind(Intent intent){return null;}

    private void createChannel(){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel c=new NotificationChannel(CHANNEL,"MCP 公网读取服务",NotificationManager.IMPORTANCE_LOW);
        c.setDescription("保持只读 MCP 与已启用的 Cloudflare 隧道运行");nm.createNotificationChannel(c);
    }
    private Notification notification(String detail){
        Intent open=new Intent(this,McpSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_ai_spark)
                .setContentTitle("MTForum 只读 MCP").setContentText(detail).setContentIntent(pi)
                .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setOnlyAlertOnce(true).build();
    }
}
