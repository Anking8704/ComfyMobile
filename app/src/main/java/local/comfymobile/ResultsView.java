package local.comfymobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import android.webkit.MimeTypeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

final class ResultsView {
    private final MainActivity activity;
    private final boolean gallery;
    private final LinearLayout root,list;
    private final TextView summary;
    private final ComfyClient client;
    private boolean fetching=false;
    private int version=0;
    private int lastHistoryHash=0;
    private boolean viewingTask=false;
    private final android.util.LruCache<String,Bitmap> thumbs=new android.util.LruCache<String,Bitmap>(12*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}
    };

    ResultsView(MainActivity a,ComfyClient client,boolean gallery){
        activity=a;this.client=client;this.gallery=gallery;root=Ui.column(a);Ui.pad(root,14);
        Ui.add(root,Ui.title(a,gallery?"生成图库":"任务与进度"));
        summary=Ui.text(a,"从电脑读取任务记录",14,Ui.MUTED);Ui.add(root,summary);
        LinearLayout tools=Ui.row(a);
        Ui.weighted(tools,Ui.button(a,"刷新",true,()->{viewingTask=false;lastHistoryHash=0;refresh();}));
        Ui.weighted(tools,Ui.button(a,"完整编辑器",false,()->a.showPage(0)));
        Ui.add(root,tools);list=Ui.column(a);root.addView(Ui.scroll(a,list),new LinearLayout.LayoutParams(-1,0,1));refresh();
    }
    View view(){return root;}
    void refresh(){
        if(fetching)return;fetching=true;final int request=++version;
        activity.worker.execute(()->{
            try{
                JSONObject queue=client.json("/queue"),history=client.json("/history?max_items=30");
                activity.runOnUiThread(()->{fetching=false;if(activity.isFinishing()||request!=version)return;render(queue,history);});
            }catch(Exception e){activity.runOnUiThread(()->{fetching=false;summary.setText("连接失败："+e.getMessage()+"。恢复网络后刷新即可。");});}
        });
    }
    private void render(JSONObject queue,JSONObject history){
        JSONArray running=queue.optJSONArray("queue_running"),pending=queue.optJSONArray("queue_pending");
        summary.setText("运行中 "+(running==null?0:running.length())+" · 等待 "+(pending==null?0:pending.length())+" · 生成记录 "+history.length());
        int hash=history.toString().hashCode();
        if(viewingTask || (hash==lastHistoryHash && list.getChildCount()>0))return;
        lastHistoryHash=hash;
        list.removeAllViews();
        if(!gallery && running!=null)for(int i=0;i<running.length();i++)Ui.add(list,Ui.text(activity,"正在执行 · "+running.optJSONArray(i).optString(1),14,Ui.TEAL));
        ArrayList<String> keys=new ArrayList<>();Iterator<String> it=history.keys();while(it.hasNext())keys.add(it.next());Collections.reverse(keys);
        int count=0;
        for(String id:keys){
            JSONObject item=history.optJSONObject(id);if(item==null)continue;
            if(!gallery){
                JSONObject status=item.optJSONObject("status");String label=status==null?"已结束":status.optString("status_str","已结束");
                LinearLayout card=Ui.column(activity);Ui.pad(card,12);card.setBackground(Ui.shape(activity,Ui.SURFACE));
                Ui.add(card,Ui.text(activity,(label.equals("success")?"已完成":label.equals("error")?"执行失败":label)+" · "+id.substring(0,Math.min(10,id.length())),15,Ui.TEXT));
                JSONArray messages=status==null?null:status.optJSONArray("messages");
                if(messages!=null)for(int j=0;j<messages.length();j++){
                    JSONArray message=messages.optJSONArray(j);
                    if(message!=null&&"execution_error".equals(message.optString(0)))Ui.add(card,Ui.text(activity,message.optJSONObject(1).optString("exception_message","节点执行出错"),13,Ui.MUTED));
                }
                Ui.add(card,Ui.button(activity,"查看此任务图片",false,()->showTaskImages(item)));Ui.add(list,card);count++;continue;
            }
            JSONObject outputs=item.optJSONObject("outputs");if(outputs==null)continue;
            Iterator<String> nodes=outputs.keys();
            while(nodes.hasNext()&&count<40){
                JSONObject output=outputs.optJSONObject(nodes.next());if(output==null)continue;
                JSONArray images=output.optJSONArray("images");if(images==null)continue;
                for(int i=0;i<images.length()&&count<40;i++){JSONObject image=images.optJSONObject(i);if(image!=null&&image.has("filename")){imageCard(image);count++;}}
            }
        }
        if(count==0)Ui.add(list,Ui.text(activity,gallery?"还没有图片。先在工作流页生成，完成后刷新这里。":"还没有历史任务。提交后可以在这里查看。",16,Ui.MUTED));
    }
    private void showTaskImages(JSONObject item){
        JSONObject outputs=item.optJSONObject("outputs");if(outputs==null){activity.message("该任务没有图片输出");return;}
        viewingTask=true;boolean found=false;list.removeAllViews();
        Iterator<String> nodes=outputs.keys();while(nodes.hasNext()){
            JSONObject output=outputs.optJSONObject(nodes.next());if(output==null)continue;
            JSONArray images=output.optJSONArray("images");if(images==null)continue;
            for(int i=0;i<images.length();i++){JSONObject image=images.optJSONObject(i);if(image!=null&&image.has("filename")){imageCard(image);found=true;}}
        }
        if(!found)Ui.add(list,Ui.text(activity,"该任务没有图片输出；视频及插件专用结果请在完整编辑器查看。",15,Ui.MUTED));
    }
    private void imageCard(JSONObject image){
        final String name=image.optString("filename"),path;
        try{path=MediaRef.path(name,image.optString("subfolder",""),image.optString("type","output"));}
        catch(IllegalArgumentException e){return;}
        LinearLayout card=Ui.column(activity);Ui.pad(card,10);card.setBackground(Ui.shape(activity,Ui.SURFACE));
        ImageView preview=new ImageView(activity);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("生成图片："+name);
        card.addView(preview,new LinearLayout.LayoutParams(-1,Ui.dp(activity,230)));
        Ui.add(card,Ui.text(activity,name,13,Ui.MUTED));
        LinearLayout actions=Ui.row(activity);
        Ui.weighted(actions,Ui.button(activity,"查看原图",false,()->openImage(path,name)));
        Ui.weighted(actions,Ui.button(activity,"保存原图",true,()->save(path,name,false)));
        Ui.weighted(actions,Ui.button(activity,"分享",false,()->save(path,name,true)));
        Ui.add(card,actions);Ui.add(list,card);
        Bitmap cached=thumbs.get(path);if(cached!=null){preview.setImageBitmap(cached);return;}
        activity.images.execute(()->{
            try{Bitmap bitmap=decode(client.bytes(path+"&preview=webp",10*1024*1024),1000);
                if(bitmap!=null)thumbs.put(path,bitmap);
                activity.runOnUiThread(()->{if(!activity.isFinishing())preview.setImageBitmap(bitmap);});
            }catch(Exception e){activity.runOnUiThread(()->preview.setContentDescription("缩略图加载失败，可重试或保存原图："+name));}
        });
    }
    private static Bitmap decode(byte[] data,int max){
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
        options.inSampleSize=1;while(options.outWidth/options.inSampleSize>max||options.outHeight/options.inSampleSize>max)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;return BitmapFactory.decodeByteArray(data,0,data.length,options);
    }
    private void openImage(String path,String name){
        ImageView image=new ImageView(activity);image.setAdjustViewBounds(true);image.setContentDescription(name);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(name).setView(Ui.scroll(activity,image)).setPositiveButton("保存原图",(d,w)->save(path,name,false)).setNegativeButton("关闭",null).show();
        activity.worker.execute(()->{try{Bitmap bitmap=decode(client.bytes(path,40*1024*1024),2400);activity.runOnUiThread(()->{if(dialog.isShowing())image.setImageBitmap(bitmap);});}
            catch(Exception e){activity.runOnUiThread(()->activity.message("读取原图失败："+e.getMessage()));}});
    }
    private void save(String path,String name,boolean share){
        activity.message(share?"正在准备原图分享":"正在保存原图");
        activity.worker.execute(()->{
            try{Uri uri=client.saveImage(activity,path,name);
                activity.runOnUiThread(()->{
                    if(share){Intent intent=new Intent(Intent.ACTION_SEND);intent.setType(activity.getContentResolver().getType(uri));intent.putExtra(Intent.EXTRA_STREAM,uri);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(Intent.createChooser(intent,"分享原图"));}
                    else activity.message("已保存到相册 Pictures/ComfyMobile");
                });
            }catch(Exception e){activity.runOnUiThread(()->activity.message("保存失败："+e.getMessage()));}
        });
    }
}
