package local.comfymobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    final ExecutorService worker=Executors.newFixedThreadPool(2);
    final ExecutorService images=Executors.newFixedThreadPool(2);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout root,nav;
    private FrameLayout body;
    private TextView connection,progress;
    private EditorView editor;
    private PromptEditor prompt;
    private ResultsView results;
    private ServerAddress server;
    private ComfyClient client;
    private SharedPreferences prefs;
    private int page=0;
    private boolean active=false,generating=false;
    private String exportJson;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("settings",0);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root=Ui.column(this);root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets p=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());view.setPadding(p.left,p.top,p.right,p.bottom);return WindowInsets.CONSUMED;}
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();
        });
        LinearLayout header=Ui.row(this);Ui.pad(header,10);
        TextView name=Ui.text(this,"ComfyMobile",18,Ui.TEXT);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(Ui.button(this,"连接设置",false,this::showConnection));root.addView(header);
        connection=Ui.text(this,"电脑算力 · 手机创作",12,Ui.MUTED);connection.setPadding(Ui.dp(this,14),0,Ui.dp(this,14),Ui.dp(this,5));root.addView(connection);
        body=new FrameLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        nav=Ui.row(this);Ui.pad(nav,5);String[] tabs={"工作流","提示词","任务","图库"};
        for(int i=0;i<tabs.length;i++){final int target=i;Ui.weighted(nav,Ui.button(this,tabs[i],false,()->showPage(target)));}root.addView(nav);
        setContentView(root);prompt=new PromptEditor(this);
        String recent=prefs.getString("server","");
        if(recent.isEmpty())showConnection();else{try{attach(ServerAddress.parse(recent));showPage(state==null?0:state.getInt("page",0));}catch(IllegalArgumentException e){showConnection();}}
    }
    EditorView editor(){return editor;}
    PromptEditor prompt(){return prompt;}
    void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}

    private void showConnection(){
        page=-1;results=null;body.removeAllViews();nav.setVisibility(View.GONE);
        LinearLayout content=Ui.column(this);Ui.pad(content,22);
        Ui.add(content,Ui.title(this,"连接家中电脑"));
        Ui.add(content,Ui.text(this,"手机与电脑连接同一 Wi-Fi。\n电脑负责运行模型，手机负责编辑和收图。",16,Ui.MUTED));
        Ui.add(content,Ui.text(this,"ComfyUI 服务器地址",14,Ui.TEXT));
        EditText address=Ui.input(this,"http://192.168.1.8:8188",false);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(prefs.getString("server",""));Ui.add(content,address);
        TextView state=Ui.text(this,"输入电脑的局域网地址",14,Ui.MUTED);Ui.add(content,state);
        final Button[] connect=new Button[1];connect[0]=Ui.button(this,"检测并连接",true,()->{
            final ServerAddress candidate;try{candidate=ServerAddress.parse(address.getText().toString());}catch(IllegalArgumentException e){state.setText(e.getMessage());return;}
            connect[0].setEnabled(false);state.setText("正在检查电脑上的 ComfyUI…");Ui.hideKeyboard(this,address);
            worker.execute(()->{
                try{JSONObject stats=new ComfyClient(candidate).json("/system_stats");if(!stats.has("system")||!stats.has("devices"))throw new IllegalStateException("该地址没有返回 ComfyUI 信息");
                    runOnUiThread(()->{if(isFinishing())return;attach(candidate);showPage(0);message("连接成功");});
                }catch(Exception e){runOnUiThread(()->{connect[0].setEnabled(true);state.setText("连接失败："+e.getMessage()+"\n检查 ComfyUI 是否开启局域网监听、地址和端口是否正确。");});}
            });
        });Ui.add(content,connect[0]);
        Ui.add(content,Ui.text(this,"电脑启动参数\n--listen 0.0.0.0 --port 8188\n\n首次连接可先用手机浏览器打开相同地址。电脑需要保持开机，地址变化后在这里更新。",14,Ui.MUTED));
        if(editor!=null)Ui.add(content,Ui.button(this,"返回当前工作流",false,()->showPage(0)));
        Ui.add(content,Ui.text(this,"本地开发版 0.1.0 · Android 10+",12,Ui.MUTED));body.addView(Ui.scroll(this,content));
    }
    private void attach(ServerAddress address){
        if(server!=null&&server.origin().equals(address.origin())&&editor!=null)return;
        if(editor!=null)editor.destroy();server=address;client=new ComfyClient(server);prefs.edit().putString("server",server.origin()).apply();
        editor=new EditorView(this,server,text->{connection.setText(server.origin()+" · "+text);});prompt.resetTarget();
        connection.setText(server.origin()+" · 正在加载编辑器");
    }
    void showPage(int index){
        if(editor==null){showConnection();return;}
        Ui.hideKeyboard(this,root);page=index;body.removeAllViews();results=null;
        nav.setVisibility(index==1?View.GONE:View.VISIBLE);
        for(int i=0;i<nav.getChildCount();i++)((Button)nav.getChildAt(i)).setTextColor(i==index?Ui.TEAL:Ui.MUTED);
        if(index==0){
            LinearLayout pageView=Ui.column(this);
            if(editor.getParent()!=null)((ViewGroup)editor.getParent()).removeView(editor);
            pageView.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
            progress=Ui.text(this,"双指缩放画布 · 节点面板可精确连线",12,Ui.MUTED);Ui.add(pageView,progress);
            LinearLayout tools=Ui.row(this);Ui.pad(tools,5);NodePanel nodes=new NodePanel(this);
            Ui.weighted(tools,Ui.button(this,"节点",false,nodes::list));Ui.weighted(tools,Ui.button(this,"添加",false,nodes::add));
            Ui.weighted(tools,Ui.button(this,"适应",false,()->editor.command("fit",new JSONObject(),this::report)));
            Ui.weighted(tools,Ui.button(this,"更多",false,this::more));Ui.add(pageView,tools);
            Button generate=Ui.button(this,"开始生成",true,this::generate);generate.setEnabled(!generating);Ui.add(pageView,generate);body.addView(pageView);
        }else if(index==1){
            if(prompt.view().getParent()!=null)((ViewGroup)prompt.view().getParent()).removeView(prompt.view());body.addView(prompt.view());
            // Android's back gesture returns to the workflow after dismissing the IME.
        }else{results=new ResultsView(this,client,index==3);body.addView(results.view());}
    }
    private void report(JSONObject r){if(!r.optBoolean("ok"))message(r.optString("error"));}
    private void generate(){
        if(generating)return;generating=true;connection.setText(server.origin()+" · 正在提交");
        editor.command("generate",new JSONObject(),r->{generating=false;
            if(r.optBoolean("ok")){message("提交操作已完成，请查看任务；节点校验错误请看编辑器提示");connection.setText(server.origin()+" · 已发送生成请求");}
            else{report(r);connection.setText(server.origin()+" · 提交未确认，请检查任务");}
        });
    }
    private void more(){
        new AlertDialog.Builder(this).setTitle("工作流操作").setItems(new String[]{"导入工作流 JSON","导出工作流 JSON","撤销","查看实时预览","刷新编辑器"},(d,index)->{
            switch(index){
                case 0:Intent input=new Intent(Intent.ACTION_OPEN_DOCUMENT);input.addCategory(Intent.CATEGORY_OPENABLE);input.setType("*/*");startActivityForResult(input,101);break;
                case 1:editor.command("export",new JSONObject(),r->{if(!r.optBoolean("ok")){report(r);return;}exportJson=r.optString("json");Intent output=new Intent(Intent.ACTION_CREATE_DOCUMENT);output.addCategory(Intent.CATEGORY_OPENABLE);output.setType("application/json");output.putExtra(Intent.EXTRA_TITLE,"ComfyMobile-workflow.json");startActivityForResult(output,102);});break;
                case 2:editor.command("undo",new JSONObject(),this::report);break;
                case 3:showPreview();break;
                case 4:new AlertDialog.Builder(this).setMessage("刷新会重新加载电脑端编辑器。请先保存或导出尚未保存的工作流。").setPositiveButton("刷新",(dd,w)->{editor.web.reload();prompt.resetTarget();}).setNegativeButton("取消",null).show();break;
            }
        }).setNegativeButton("关闭",null).show();
    }
    private String previewData;
    private void showPreview(){
        if(previewData==null){message("暂时没有实时预览；电脑需要启用预览，生成时再打开这里");return;}
        try{byte[] bytes=android.util.Base64.decode(previewData.substring(previewData.indexOf(',')+1),android.util.Base64.DEFAULT);
            ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setContentDescription("当前生成预览");image.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length));
            new AlertDialog.Builder(this).setTitle("最近生成预览").setView(image).setPositiveButton("关闭",null).show();
        }catch(Exception e){message("预览暂不可用");}
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(!active)return;
        if(editor!=null&&page>=0)editor.call("drainEvents",new JSONArray(),r->{
            JSONArray events=r.optJSONArray("events");if(events==null)return;
            boolean completed=false;
            for(int i=0;i<events.length();i++){
                JSONObject event=events.optJSONObject(i);if(event==null)continue;JSONObject data=event.optJSONObject("data");
                String type=event.optString("type");
                if(type.equals("progress")&&data!=null){String label="采样进度 "+data.optInt("value")+" / "+data.optInt("max");connection.setText(server.origin()+" · "+label);if(progress!=null)progress.setText(label);}
                if(type.equals("preview")&&data!=null)previewData=data.optString("dataUrl",previewData);
                if(type.equals("execution_success")||type.equals("executed"))completed=true;
                if(type.equals("execution_error"))connection.setText(server.origin()+" · 节点执行失败，请查看任务");
            }
            if(completed&&results!=null)results.refresh();
        });
        if(results!=null)results.refresh();handler.postDelayed(this,4000);
    }};
    @Override protected void onResume(){super.onResume();active=true;handler.post(tick);if(editor!=null)editor.web.onResume();}
    @Override protected void onPause(){super.onPause();active=false;handler.removeCallbacks(tick);prompt.persist();if(editor!=null)editor.web.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("page",Math.max(0,page));prompt.persist();}
    @Override protected void onDestroy(){active=false;handler.removeCallbacksAndMessages(null);if(editor!=null)editor.destroy();worker.shutdownNow();images.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){
        if(page!=0&&editor!=null){showPage(0);return;}
        if(editor!=null&&editor.web.canGoBack()){editor.web.goBack();return;}
        super.onBackPressed();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==100){if(editor!=null&&editor.fileCallback!=null){Uri[] uris=null;
            if(result==RESULT_OK&&data!=null){if(data.getClipData()!=null){uris=new Uri[data.getClipData().getItemCount()];for(int i=0;i<uris.length;i++)uris[i]=data.getClipData().getItemAt(i).getUri();}else if(data.getData()!=null)uris=new Uri[]{data.getData()};}
            editor.fileCallback.onReceiveValue(uris);editor.fileCallback=null;}return;}
        if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==101){worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IllegalStateException("文件无法读取");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>20*1024*1024)throw new IllegalStateException("工作流 JSON 过大");out.write(buffer,0,n);}String json=out.toString("UTF-8");
            new JSONObject(json);runOnUiThread(()->new AlertDialog.Builder(this).setMessage("导入将替换当前画布，建议先导出保存现有工作流。").setPositiveButton("导入",(d,w)->editor.command("import",NodePanel.args("json",json),r->{report(r);if(r.optBoolean("ok")){prompt.resetTarget();message("工作流已导入");}})).setNegativeButton("取消",null).show());
        }catch(Exception e){runOnUiThread(()->message("导入失败："+e.getMessage()));}});}
        if(request==102&&exportJson!=null){String json=exportJson;exportJson=null;worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IllegalStateException("无法写入文件");out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));runOnUiThread(()->message("工作流已导出"));}catch(Exception e){runOnUiThread(()->message("导出失败："+e.getMessage()));}});}
    }
}
