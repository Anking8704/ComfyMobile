package local.comfymobile;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * While the app is in the background and the computer is still generating, polls the job list and
 * posts a notification when the queue finishes. It stops by itself after the queue empties or 3 hours.
 */
public final class GenerationWatch extends Service {
    private static final String CHANNEL="generation",ORIGIN="origin";
    private static final int ONGOING=1,DONE=2;
    private volatile boolean stopped=false;
    private Thread thread;

    static void start(Context context,String origin){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        try{context.startForegroundService(new Intent(context,GenerationWatch.class).putExtra(ORIGIN,origin));}catch(Exception ignored){}
    }
    static void stop(Context context){try{context.stopService(new Intent(context,GenerationWatch.class));}catch(Exception ignored){}}
    static void askPermission(Activity activity){
        if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7);
    }
    private Notification notification(String text,boolean ongoing){
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"生成进度",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.app_icon).setContentTitle("ComfyMobile").setContentText(text)
            .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(ongoing).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        String origin=intent==null?null:intent.getStringExtra(ORIGIN);
        if(origin==null){stopSelf();return START_NOT_STICKY;}
        Notification n=notification("电脑正在生成，完成后会通知你",true);
        // Android 14+ may refuse or time-limit data-sync services; then there is simply no notification.
        try{startForeground(ONGOING,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);}catch(Exception e){stopSelf();return START_NOT_STICKY;}
        if(thread!=null)return START_NOT_STICKY;
        thread=new Thread(()->watch(origin));thread.start();
        return START_NOT_STICKY;
    }
    private void watch(String origin){
        long deadline=System.currentTimeMillis()+3*3600_000L;int failures=0;
        try{
            ComfyClient client=new ComfyClient(ServerAddress.parse(origin));
            while(!stopped&&System.currentTimeMillis()<deadline){
                Thread.sleep(5000);
                try{
                    JSONObject queue=client.json("/queue");failures=0;
                    JSONArray runningJobs=queue.optJSONArray("queue_running"),pending=queue.optJSONArray("queue_pending");
                    int left=(runningJobs==null?0:runningJobs.length())+(pending==null?0:pending.length());
                    if(left==0){
                        JSONArray jobs=client.json("/api/jobs?limit=1").optJSONArray("jobs");JSONObject latest=jobs==null?null:jobs.optJSONObject(0);
                        String status=latest==null?"":latest.optString("status");
                        String text=status.equals("failed")?"生成失败，打开查看任务记录":status.equals("cancelled")?"生成已停止":"生成完成，点击查看结果";
                        getSystemService(NotificationManager.class).notify(DONE,notification(text,false));
                        break;
                    }
                }catch(Exception e){if(++failures>=6)break;}
            }
        }catch(Exception ignored){}
        stopSelf();
    }
    @Override public void onTimeout(int startId,int type){stopSelf();}
    @Override public void onDestroy(){stopped=true;if(thread!=null)thread.interrupt();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
