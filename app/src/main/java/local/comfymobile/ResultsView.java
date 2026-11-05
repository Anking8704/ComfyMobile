package local.comfymobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;

/** Tasks and gallery, read from ComfyUI's paginated /api/jobs list (small, without the full workflow of every job). */
final class ResultsView {
    private static final int PAGE=24;
    private static final android.util.LruCache<String,Bitmap> THUMBS=new android.util.LruCache<String,Bitmap>((int)Math.min(48L*1024*1024,Runtime.getRuntime().maxMemory()/8)){
        @Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}
    };
    private final MainActivity activity;
    private final boolean gallery;
    private final LinearLayout root,selectionBar;
    private final TextView summary,selectionText;
    private final GridView grid;
    private final ComfyClient client;
    private final ArrayList<JSONObject> items=new ArrayList<>();
    private final HashSet<String> selected=new HashSet<>();
    private final Adapter adapter=new Adapter();
    private boolean fetching=false,hasMore=false,selecting=false;
    private int version=0,limit=PAGE,builtColumns;
    private String lastSignature="";
    private JSONObject openJob;
    private final DateFormat time=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);

    ResultsView(MainActivity a,ComfyClient client,boolean gallery){
        activity=a;this.client=client;this.gallery=gallery;root=Ui.column(a);Ui.pad(root,12);
        LinearLayout head=Ui.row(a);
        TextView title=Ui.title(a,gallery?"生成图库":"任务与进度");head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        head.addView(Ui.button(a,"返回",false,a::leaveSubpage));root.addView(head);
        summary=Ui.text(a,"从电脑读取任务记录",13,Ui.MUTED);Ui.add(root,summary);
        LinearLayout tools=Ui.row(a);
        Ui.weighted(tools,Ui.button(a,"刷新",true,()->{back();lastSignature="";refresh();}));
        if(!gallery){gap(tools);Ui.weighted(tools,Ui.button(a,"停止当前",false,a::interrupt));gap(tools);Ui.weighted(tools,Ui.button(a,"清空排队",false,this::clearQueue));}
        else{gap(tools);Ui.weighted(tools,Ui.button(a,"多选保存",false,()->{selecting=true;selected.clear();updateSelection();}));}
        Ui.add(root,tools);
        selectionBar=Ui.row(a);selectionText=Ui.text(a,"",14,Ui.TEXT);selectionBar.addView(selectionText,new LinearLayout.LayoutParams(0,-2,1));
        selectionBar.addView(Ui.button(a,"保存所选",true,this::saveSelected));selectionBar.addView(Ui.button(a,"取消",false,()->{selecting=false;selected.clear();updateSelection();}));
        selectionBar.setVisibility(View.GONE);Ui.add(root,selectionBar);
        grid=new GridView(a);builtColumns=columns();grid.setNumColumns(builtColumns);grid.setVerticalSpacing(Ui.dp(a,8));grid.setHorizontalSpacing(Ui.dp(a,8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setAdapter(adapter);grid.setSelector(android.R.color.transparent);
        grid.setOnItemClickListener((p,v,i,id)->tap(items.get(i)));
        grid.setOnItemLongClickListener((p,v,i,id)->{if(!gallery)return false;selecting=true;toggle(items.get(i));return true;});
        grid.setOnScrollListener(new android.widget.AbsListView.OnScrollListener(){
            public void onScrollStateChanged(android.widget.AbsListView v,int s){}
            public void onScroll(android.widget.AbsListView v,int first,int visible,int total){if(hasMore&&!fetching&&openJob==null&&total>0&&first+visible>=total-2){limit+=PAGE;refresh();}}
        });
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));refresh();
    }
    private void gap(LinearLayout row){row.addView(new View(activity),new LinearLayout.LayoutParams(Ui.dp(activity,6),1));}
    View view(){return root;}
    private int columns(){int w=activity.getResources().getConfiguration().screenWidthDp;return gallery?Math.max(2,Math.min(6,w/170)):Math.max(1,Math.min(3,w/340));}
    /** A rotation only rebuilds the page when the column count changes and no single task is open. */
    boolean needsRelayout(){return openJob==null&&columns()!=builtColumns;}
    /** Leaves a single task's images or the selection mode; returns whether back was consumed. */
    boolean back(){
        if(selecting){selecting=false;selected.clear();updateSelection();return true;}
        if(openJob!=null){openJob=null;lastSignature="";refresh();return true;}
        return false;
    }

    // ---------------- loading ----------------
    void refresh(){
        if(fetching||openJob!=null)return;fetching=true;final int request=++version;final int want=limit;
        activity.worker.execute(()->{
            try{
                JSONObject page=client.json("/api/jobs?limit="+want+(gallery?"&status=completed":""));
                activity.runOnUiThread(()->{fetching=false;if(activity.isFinishing()||request!=version||openJob!=null)return;render(page);});
            }catch(Exception e){activity.runOnUiThread(()->{fetching=false;summary.setText("连接失败："+e.getMessage()+"。恢复网络后刷新即可。");});}
        });
    }
    private void render(JSONObject page){
        JSONArray jobs=page.optJSONArray("jobs");JSONObject pagination=page.optJSONObject("pagination");
        hasMore=pagination!=null&&pagination.optBoolean("has_more");
        int running=0,pending=0;
        ArrayList<JSONObject> next=new ArrayList<>();
        for(int i=0;jobs!=null&&i<jobs.length();i++){
            JSONObject job=jobs.optJSONObject(i);if(job==null)continue;String s=job.optString("status");
            if(s.equals("in_progress"))running++;if(s.equals("pending"))pending++;
            if(gallery&&!job.has("preview_output"))continue;
            next.add(job);
        }
        summary.setText((gallery?"":"运行中 "+running+" · 等待 "+pending+" · ")+"共 "+(pagination==null?next.size():pagination.optInt("total"))+" 条记录"+(running>0&&!activity.progressText().isEmpty()?" · "+activity.progressText():""));
        String signature=jobs==null?"":jobs.toString();
        if(signature.equals(lastSignature))return;lastSignature=signature;
        items.clear();items.addAll(next);adapter.notifyDataSetChanged();
        if(items.isEmpty())summary.setText(gallery?"还没有图片。先生成，完成后会出现在这里。":"还没有任务。提交后可以在这里查看。");
    }
    private void showJob(JSONObject job){
        openJob=job;items.clear();adapter.notifyDataSetChanged();summary.setText("正在读取此任务的全部结果…");
        activity.worker.execute(()->{
            try{
                JSONObject full=client.json("/api/jobs/"+Uri.encode(job.optString("id")));
                ArrayList<JSONObject> outputs=outputsOf(full);
                activity.runOnUiThread(()->{
                    if(openJob!=job)return;
                    summary.setText(outputs.isEmpty()?"该任务没有图片或视频输出":"此任务共 "+outputs.size()+" 个结果 · 返回键回到列表");
                    items.clear();for(JSONObject o:outputs){JSONObject wrap=new JSONObject();try{wrap.put("preview_output",o);wrap.put("status","output");}catch(Exception ignored){}items.add(wrap);}
                    adapter.notifyDataSetChanged();
                });
            }catch(Exception e){activity.runOnUiThread(()->summary.setText("读取失败："+e.getMessage()));}
        });
    }
    static ArrayList<JSONObject> outputsOf(JSONObject full){
        ArrayList<JSONObject> list=new ArrayList<>();JSONObject outputs=full.optJSONObject("outputs");if(outputs==null)return list;
        for(Iterator<String> nodes=outputs.keys();nodes.hasNext();){
            JSONObject node=outputs.optJSONObject(nodes.next());if(node==null)continue;
            for(Iterator<String> kinds=node.keys();kinds.hasNext();){
                JSONArray files=node.optJSONArray(kinds.next());
                for(int i=0;files!=null&&i<files.length();i++){JSONObject f=files.optJSONObject(i);if(f!=null&&f.has("filename")&&(MediaRef.byExtension(f.optString("filename"))!=null))list.add(f);}
            }
        }
        return list;
    }

    // ---------------- actions ----------------
    private void tap(JSONObject job){
        if(selecting){toggle(job);return;}
        String status=job.optString("status");
        // Outputs open in a viewer that swipes through everything currently listed here.
        if(status.equals("output")||(gallery&&job.optInt("outputs_count")<=1)){
            ArrayList<JSONObject> shown=new ArrayList<>();int at=0;
            for(JSONObject item:items){JSONObject o=item.optJSONObject("preview_output");if(o==null)continue;if(item==job)at=shown.size();shown.add(o);}
            OutputViewer.show(activity,shown,at);return;
        }
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        if(status.equals("pending")){labels.add("取消这个排队任务");actions.add(()->deleteQueued(job.optString("id")));}
        if(status.equals("in_progress")){labels.add("停止生成");actions.add(activity::interrupt);}
        if(job.optInt("outputs_count")>0){labels.add("查看全部结果（"+job.optInt("outputs_count")+"）");actions.add(()->showJob(job));}
        if(!status.equals("pending")&&!status.equals("in_progress")){
            labels.add("换个种子再来一次");actions.add(()->editorJob(job,"rerun"));
            labels.add("把这次的工作流载入画布");actions.add(()->editorJob(job,"open"));
        }
        JSONObject error=job.optJSONObject("execution_error");
        if(error!=null&&error.has("node_id")){labels.add("在画布上定位出错的节点");actions.add(()->{activity.showPage(MainActivity.PAGE_CANVAS);activity.editor().command("select",NodePanel.args("nodeId",error.opt("node_id")),r->{if(!r.optBoolean("ok"))activity.message("当前画布不是这个任务的工作流，可先“载入画布”");});});}
        if(labels.isEmpty())return;
        new AlertDialog.Builder(activity).setTitle(describe(job)).setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).setNegativeButton("关闭",null).show();
    }
    private void editorJob(JSONObject job,String mode){
        EditorView editor=activity.editor();if(editor==null)return;
        editor.command("loadJob",NodePanel.args("id",job.optString("id"),"mode",mode),r->{
            if(!r.optBoolean("ok")){activity.report(r);return;}
            if(mode.equals("rerun")){activity.message("已重新提交（随机新种子）");lastSignature="";refresh();}
            else{activity.prompt().resetTarget();activity.message("已在新标签中载入这次的工作流");activity.showPage(MainActivity.PAGE_CANVAS);}
        });
    }
    private void deleteQueued(String id){
        activity.worker.execute(()->{
            try{client.post("/queue",NodePanel.args("delete",new JSONArray().put(id)));activity.runOnUiThread(()->{activity.message("已取消");lastSignature="";refresh();});}
            catch(Exception e){activity.runOnUiThread(()->activity.message("取消失败："+e.getMessage()));}
        });
    }
    private void clearQueue(){
        new AlertDialog.Builder(activity).setMessage("取消所有还在排队的任务？正在执行的任务不受影响。").setPositiveButton("全部取消",(d,w)->activity.worker.execute(()->{
            try{client.post("/queue",NodePanel.args("clear",true));activity.runOnUiThread(()->{activity.message("排队已清空");lastSignature="";refresh();});}
            catch(Exception e){activity.runOnUiThread(()->activity.message("操作失败："+e.getMessage()));}
        })).setNegativeButton("取消",null).show();
    }
    private void toggle(JSONObject job){String key=keyOf(job);if(!selected.remove(key))selected.add(key);updateSelection();}
    private static String keyOf(JSONObject job){return job.optString("id")+"|"+job.optJSONObject("preview_output");}
    private void updateSelection(){
        selectionBar.setVisibility(selecting?View.VISIBLE:View.GONE);selectionText.setText("已选 "+selected.size()+" 项（任务的全部结果都会保存）");adapter.notifyDataSetChanged();
    }
    private void saveSelected(){
        ArrayList<JSONObject> chosen=new ArrayList<>();for(JSONObject j:items)if(selected.contains(keyOf(j)))chosen.add(j);
        if(chosen.isEmpty()){activity.message("先点选要保存的图片");return;}
        selecting=false;selected.clear();updateSelection();activity.message("正在保存 "+chosen.size()+" 个任务的结果…");
        activity.worker.execute(()->{
            int saved=0,failed=0;
            for(JSONObject job:chosen){
                ArrayList<JSONObject> outputs=new ArrayList<>();
                try{if(job.has("id")&&job.optInt("outputs_count")>1)outputs=outputsOf(client.json("/api/jobs/"+Uri.encode(job.optString("id"))));}catch(Exception ignored){}
                if(outputs.isEmpty())outputs.add(job.optJSONObject("preview_output"));
                for(JSONObject o:outputs){try{client.saveMedia(activity,pathOf(o),o.optString("filename"));saved++;}catch(Exception e){failed++;}}
            }
            int ok=saved,bad=failed;activity.runOnUiThread(()->activity.message("已保存 "+ok+" 个文件到手机"+(bad>0?"，"+bad+" 个失败":"")));
        });
    }

    // ---------------- rendering ----------------
    private String describe(JSONObject job){
        String s=job.optString("status");
        String label=s.equals("completed")?"已完成":s.equals("failed")?"失败":s.equals("cancelled")?"已停止":s.equals("in_progress")?"正在生成":s.equals("pending")?"排队中":s;
        long start=job.optLong("execution_start_time"),end=job.optLong("execution_end_time"),created=job.optLong("create_time");
        String when=created>0?time.format(new Date(created)):"";
        String took=start>0&&end>start?" · 用时 "+Math.round((end-start)/1000.0)+" 秒":"";
        return label+(when.isEmpty()?"":" · "+when)+took;
    }
    static String pathOf(JSONObject o){return MediaRef.path(o.optString("filename"),o.optString("subfolder",""),o.optString("type","output"));}
    private final class Adapter extends BaseAdapter {
        public int getCount(){return items.size();}
        public Object getItem(int i){return items.get(i);}
        public long getItemId(int i){return i;}
        public View getView(int i,View reuse,ViewGroup parent){
            Cell cell=reuse==null?new Cell():(Cell)reuse.getTag();
            JSONObject job=items.get(i);JSONObject output=job.optJSONObject("preview_output");
            boolean output1=job.optString("status").equals("output");
            cell.title.setText(output1?output.optString("filename"):gallery?time.format(new Date(Math.max(job.optLong("create_time"),1)))+(job.optInt("outputs_count")>1?" · 共 "+job.optInt("outputs_count")+" 个":""):describe(job));
            JSONObject error=job.optJSONObject("execution_error");
            String detail="";
            if(error!=null)detail="出错节点："+error.optString("node_type")+" #"+error.optString("node_id")+"\n"+error.optString("exception_message");
            else if(!gallery&&!output1&&job.optString("status").equals("in_progress"))detail=activity.progressText();
            else if(!gallery&&!output1&&job.optInt("outputs_count")>0)detail=job.optInt("outputs_count")+" 个结果 · 点击查看或再来一次";
            cell.detail.setText(detail);cell.detail.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);
            cell.check.setVisibility(selecting?View.VISIBLE:View.GONE);cell.check.setText(selected.contains(keyOf(job))?"✓ 已选":"点选");
            cell.load(output);
            return cell.root;
        }
    }
    private final class Cell {
        final LinearLayout root;final ImageView image;final TextView title,detail,check;String key;
        Cell(){
            root=Ui.column(activity);Ui.pad(root,8);root.setBackground(Ui.shape(activity,Ui.SURFACE));root.setTag(this);
            FrameLayout frame=new FrameLayout(activity);
            image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackgroundColor(Ui.BG);
            frame.addView(image,new FrameLayout.LayoutParams(-1,Ui.dp(activity,gallery?150:180)));
            check=Ui.text(activity,"",12,Ui.BG);check.setBackgroundColor(Ui.TEAL);check.setPadding(Ui.dp(activity,6),Ui.dp(activity,2),Ui.dp(activity,6),Ui.dp(activity,2));
            frame.addView(check,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END));root.addView(frame);
            title=Ui.text(activity,"",13,Ui.TEXT);title.setMaxLines(2);root.addView(title);
            detail=Ui.text(activity,"",12,Ui.MUTED);detail.setMaxLines(4);root.addView(detail);
        }
        void load(JSONObject output){
            image.setImageDrawable(null);key=null;
            if(output==null||!output.has("filename")){image.setVisibility(View.GONE);return;}
            image.setVisibility(View.VISIBLE);
            if(MediaRef.isVideo(output.optString("filename"))){image.setImageResource(android.R.drawable.ic_media_play);image.setScaleType(ImageView.ScaleType.CENTER);return;}
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            String path=pathOf(output);key=path;Bitmap cached=THUMBS.get(path);if(cached!=null){image.setImageBitmap(cached);return;}
            activity.images.execute(()->{
                try{Bitmap b=decode(client.bytes(path+"&preview=webp",12*1024*1024),420);if(b!=null)THUMBS.put(path,b);
                    activity.runOnUiThread(()->{if(path.equals(key))image.setImageBitmap(b);});}catch(Exception ignored){}
            });
        }
    }

    // ---------------- full-screen viewing ----------------
    static Bitmap decode(byte[] data,int max){
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
        options.inSampleSize=1;while(options.outWidth/options.inSampleSize>max*2||options.outHeight/options.inSampleSize>max*2)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(b==null||Math.max(b.getWidth(),b.getHeight())<=max)return b;
        float s=(float)max/Math.max(b.getWidth(),b.getHeight());Bitmap scaled=Bitmap.createScaledBitmap(b,Math.round(b.getWidth()*s),Math.round(b.getHeight()*s),true);
        if(scaled!=b)b.recycle();return scaled;
    }
    /** Opens one output: images in the swipeable viewer, videos in a player. */
    static void open(MainActivity a,JSONObject output){
        if(output==null)return;
        if(MediaRef.isVideo(output.optString("filename"))){playVideo(a,output.optString("filename"),pathOf(output));return;}
        OutputViewer.show(a,java.util.Collections.singletonList(output),0);
    }
    static ZoomImage viewer(Activity a,String title,Bitmap bitmap,Runnable save,JSONObject shareOutput){
        Dialog dialog=new Dialog(a,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout box=Ui.column(a);box.setBackgroundColor(0xFF000000);Ui.applyInsets(box);
        ZoomImage image=new ZoomImage(a);if(bitmap!=null)image.setImageBitmap(bitmap);image.setContentDescription(title);
        box.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bar=Ui.row(a);Ui.pad(bar,8);
        TextView label=Ui.text(a,title+" · 双指或双击放大",12,Ui.MUTED);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        if(save!=null&&a instanceof MainActivity){
            if(shareOutput!=null)bar.addView(Ui.button(a,"分享",false,()->share((MainActivity)a,shareOutput)));
            bar.addView(Ui.button(a,"保存原图",true,save));
        }
        bar.addView(Ui.button(a,"关闭",false,dialog::dismiss));box.addView(bar);
        dialog.setContentView(box);dialog.show();return image;
    }
    static void playVideo(MainActivity a,String name,String path){
        Dialog dialog=new Dialog(a,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout box=Ui.column(a);box.setBackgroundColor(0xFF000000);Ui.applyInsets(box);
        VideoView video=new VideoView(a);FrameLayout frame=new FrameLayout(a);frame.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        box.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bar=Ui.row(a);Ui.pad(bar,8);bar.addView(Ui.text(a,name,12,Ui.MUTED),new LinearLayout.LayoutParams(0,-2,1));
        JSONObject target=new JSONObject();try{target.put("filename",name);target.put("_path",path);}catch(Exception ignored){}
        bar.addView(Ui.button(a,"保存视频",true,()->a.saveFromServer(path,name)));bar.addView(Ui.button(a,"关闭",false,dialog::dismiss));box.addView(bar);
        MediaController controls=new MediaController(a);controls.setAnchorView(video);video.setMediaController(controls);
        video.setVideoURI(Uri.parse(a.client().server.origin()+path));video.setOnPreparedListener(p->{p.setLooping(true);video.start();});
        video.setOnErrorListener((p,w,e)->{a.message("此视频格式手机无法直接播放，可以保存后用其他播放器打开");return true;});
        dialog.setOnDismissListener(d->video.stopPlayback());dialog.setContentView(box);dialog.show();
    }
    /** Shares the original through a private copy, so sharing does not add files to the gallery. */
    static void share(MainActivity a,JSONObject output){
        if(output==null){a.message("没有可分享的原图");return;}
        String name=output.optString("filename"),path=pathOf(output);a.message("正在准备分享…");
        a.worker.execute(()->{
            try{File file=a.client().cacheForShare(a,path,name);Uri uri=ShareProvider.uriFor(file);
                a.runOnUiThread(()->{Intent send=new Intent(Intent.ACTION_SEND);String type=MediaRef.byExtension(name);send.setType(type==null?"*/*":type);
                    send.putExtra(Intent.EXTRA_STREAM,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);a.startActivity(Intent.createChooser(send,"分享原图"));});
            }catch(Exception e){a.runOnUiThread(()->a.message("分享失败："+e.getMessage()));}
        });
    }
}
