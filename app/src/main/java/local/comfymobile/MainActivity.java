package local.comfymobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
    private LinearLayout root,nav,header;
    private FrameLayout workflowPage;
    private FloatingMenu menu;
    private FrameLayout body;
    private TextView connection;
    private EditorView editor;
    private PromptEditor prompt;
    private ResultsView results;
    private ServerAddress server;
    private ComfyClient client;
    private SharedPreferences prefs;
    private int page=0;
    private boolean active=false;
    private final SubmissionState submission=new SubmissionState();
    private static final String GENERATE="开始生成";
    private Button orientationButton;
    private int focusLoads=-1;
    private boolean focusPending=false;
    private int eventEpoch=0;
    private String exportJson;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("settings",0);
        applyOrientation(prefs.getString("orientation","system"));
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(attributes);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(EDGE_TO_EDGE);
        root=Ui.column(this);root.setBackgroundColor(Ui.BG);
        Ui.applyInsets(root);
        header=Ui.row(this);Ui.pad(header,10);
        TextView name=Ui.text(this,"ComfyMobile",18,Ui.TEXT);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        orientationButton=Ui.button(this,"横屏",false,this::toggleOrientation);header.addView(orientationButton);
        header.addView(Ui.button(this,"连接设置",false,this::showConnection));root.addView(header);
        connection=Ui.text(this,"电脑算力 · 手机创作",12,Ui.MUTED);connection.setSingleLine(true);connection.setEllipsize(android.text.TextUtils.TruncateAt.END);connection.setPadding(Ui.dp(this,14),0,Ui.dp(this,14),Ui.dp(this,5));root.addView(connection);
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
        header.setVisibility(View.VISIBLE);connection.setVisibility(View.VISIBLE);
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
        Ui.add(content,Ui.text(this,"电脑启动参数\n--listen 0.0.0.0 --port 8188\n\n首次连接可先用手机浏览器打开相同地址。电脑需要保持开机，地址变化后在这里更新。\n\niPad / 其他平板：电脑安装网页版后，用 Safari 打开 相同地址/comfymobile/ ，再“添加到主屏幕”即可全屏使用。",14,Ui.MUTED));
        if(editor!=null)Ui.add(content,Ui.button(this,"返回当前工作流",false,()->showPage(0)));
        Ui.add(content,Ui.text(this,"本地开发版 0.3.0 · Android 10+",12,Ui.MUTED));body.addView(Ui.scroll(this,Ui.maxWidth(content,560)));updateLayout();
    }
    private void attach(ServerAddress address){
        if(server!=null&&server.origin().equals(address.origin())&&editor!=null)return;
        submission.reset();previewData=null;
        if(editor!=null)editor.destroy();server=address;client=new ComfyClient(server);prefs.edit().putString("server",server.origin()).apply();
        editor=new EditorView(this,server,this::status);prompt.resetTarget();focusLoads=-1;focusPending=false;
        status("正在加载编辑器");
        eventEpoch++;handler.removeCallbacks(eventTick);if(active)handler.post(eventTick);
    }
    void showPage(int index){
        if(editor==null){showConnection();return;}
        Ui.hideKeyboard(this,root);page=index;body.removeAllViews();results=null;
        nav.setVisibility(index==1?View.GONE:View.VISIBLE);
        header.setVisibility(index==1?View.GONE:View.VISIBLE);connection.setVisibility(index==1?View.GONE:View.VISIBLE);
        for(int i=0;i<nav.getChildCount();i++)((Button)nav.getChildAt(i)).setTextColor(i==index?Ui.TEAL:Ui.MUTED);
        menu=null;workflowPage=null;
        if(index==0){
            workflowPage=new FrameLayout(this);
            if(editor.getParent()!=null)((ViewGroup)editor.getParent()).removeView(editor);
            workflowPage.addView(editor,new FrameLayout.LayoutParams(-1,-1));
            menu=new FloatingMenu(this,workflowPage,prefs);menu.setItems(menuItems());menu.setEnabled(GENERATE,!submission.pending());
            menu.setStatus(connection.getText().toString());body.addView(workflowPage);menu.attached();
            if(!prefs.getBoolean("hint_v3",false)){prefs.edit().putBoolean("hint_v3",true).apply();message("双击提示词节点进入编辑；圆形按钮展开功能，可拖到任意边");}
        }else if(index==1){
            if(prompt.view().getParent()!=null)((ViewGroup)prompt.view().getParent()).removeView(prompt.view());body.addView(prompt.view());
            // Android's back gesture returns to the workflow after dismissing the IME.
        }else{results=new ResultsView(this,client,index==3);body.addView(results.view());}
        updateLayout();
    }
    boolean isLandscape(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    void toggleOrientation(){String mode=isLandscape()?"portrait":"landscape";prefs.edit().putString("orientation",mode).apply();applyOrientation(mode);}
    private void applyOrientation(String mode){
        setRequestedOrientation(mode.equals("landscape")?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:mode.equals("portrait")?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);if((page==2||page==3)&&results!=null&&results.needsRelayout())showPage(page);else updateLayout();if(prompt!=null)prompt.orientationChanged();}
    private void updateLayout(){
        if(header==null||connection==null||nav==null)return;
        boolean canvas=page==0&&editor!=null,bare=page==1||canvas;
        if(orientationButton!=null)orientationButton.setText(isLandscape()?"竖屏":"横屏");
        header.setVisibility(bare?View.GONE:View.VISIBLE);connection.setVisibility(bare?View.GONE:View.VISIBLE);
        nav.setVisibility(page<0||bare?View.GONE:View.VISIBLE);
        fullScreen(canvas);
        if(menu!=null)menu.setItems(menuItems());
    }
    /** The canvas page hides the system bars; an edge swipe shows them temporarily. Other pages keep normal bars for the IME. */
    private void fullScreen(boolean on){
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController controller=getWindow().getInsetsController();if(controller==null)return;
            if(on){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(WindowInsets.Type.systemBars());}
            else controller.show(WindowInsets.Type.systemBars());
        }else getWindow().getDecorView().setSystemUiVisibility(on?EDGE_TO_EDGE|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN:EDGE_TO_EDGE);
    }
    // No LAYOUT_STABLE: Android 10 must report zero insets once the bars are hidden, or the canvas keeps empty bands.
    private static final int EDGE_TO_EDGE=View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&page==0&&editor!=null)fullScreen(true);}
    private FloatingMenu.Item[] menuItems(){
        NodePanel nodes=new NodePanel(this);
        return new FloatingMenu.Item[]{
            new FloatingMenu.Item(GENERATE,true,false,this::generate),
            new FloatingMenu.Item("提示词",false,false,()->showPage(1)),
            new FloatingMenu.Item("节点",false,false,nodes::list),
            new FloatingMenu.Item("添加",false,false,nodes::add),
            new FloatingMenu.Item("适应画布",false,false,()->editor.command("fit",new JSONObject(),this::report)),
            new FloatingMenu.Item("任务",false,false,()->showPage(2)),
            new FloatingMenu.Item("图库",false,false,()->showPage(3)),
            new FloatingMenu.Item(isLandscape()?"竖屏":"横屏",false,false,this::toggleOrientation),
            new FloatingMenu.Item("原版菜单",false,false,this::toggleWebMenus),
            new FloatingMenu.Item("连接设置",false,false,this::showConnection),
            new FloatingMenu.Item("更多",false,false,this::more)};
    }
    void status(String text){
        String line=server==null?text:server.origin()+" · "+text;connection.setText(line);if(menu!=null)menu.setStatus(line);
    }
    private void toggleWebMenus(){
        editor.command("chrome",new JSONObject(),state->{
            if(!state.optBoolean("ok")){report(state);return;}
            boolean hide=!state.optBoolean("focus");
            editor.command("chrome",NodePanel.args("focus",hide),r->{
                if(!r.optBoolean("ok")){report(r);return;}
                prefs.edit().putBoolean("web_menus",!r.optBoolean("focus")).apply();
                message(r.optBoolean("focus")?"已隐藏原版菜单，画布全屏":"已显示原版菜单；可在这里再次隐藏");
            });
        });
    }
    /** Applies the saved original-menu preference once per editor page load. */
    private void applyWebMenus(){
        if(editor==null||focusPending||focusLoads==editor.loads)return;
        final EditorView source=editor;final int load=source.loads;focusPending=true;
        source.command("chrome",NodePanel.args("focus",!prefs.getBoolean("web_menus",false)),r->{
            if(source!=editor)return;focusPending=false;if(r.optBoolean("ok"))focusLoads=load;
        });
    }
    private void report(JSONObject r){if(!r.optBoolean("ok"))message(r.optString("error"));}
    private void generate(){
        final int epoch=submission.begin();if(epoch<0)return;
        if(menu!=null)menu.setEnabled(GENERATE,false);status("正在提交");
        editor.command("generate",new JSONObject(),r->{if(!submission.finish(epoch))return;if(menu!=null)menu.setEnabled(GENERATE,true);
            if(r.optBoolean("ok")){message("提交操作已完成，请查看任务；节点校验错误请看编辑器提示");status("已发送生成请求");}
            else{report(r);status("提交未确认，请检查任务");}
        });
    }
    private void more(){
        new AlertDialog.Builder(this).setTitle("工作流操作").setItems(new String[]{"导入工作流 JSON","导出工作流 JSON","撤销","查看实时预览","刷新编辑器","导出上次导入前备份","任务","图库","连接设置","屏幕跟随系统"},(d,index)->{
            switch(index){
                case 0:Intent input=new Intent(Intent.ACTION_OPEN_DOCUMENT);input.addCategory(Intent.CATEGORY_OPENABLE);input.setType("*/*");startActivityForResult(input,101);break;
                case 1:editor.command("export",new JSONObject(),r->{if(!r.optBoolean("ok")){report(r);return;}exportJson=r.optString("json");Intent output=new Intent(Intent.ACTION_CREATE_DOCUMENT);output.addCategory(Intent.CATEGORY_OPENABLE);output.setType("application/json");output.putExtra(Intent.EXTRA_TITLE,"ComfyMobile-workflow.json");startActivityForResult(output,102);});break;
                case 2:editor.command("undo",new JSONObject(),this::report);break;
                case 3:showPreview();break;
                case 4:new AlertDialog.Builder(this).setMessage("刷新会重新加载电脑端编辑器。请先保存或导出尚未保存的工作流。").setPositiveButton("刷新",(dd,w)->{editor.web.reload();prompt.resetTarget();}).setNegativeButton("取消",null).show();break;
                case 5:worker.execute(()->{
                    try(InputStream in=new java.io.FileInputStream(new java.io.File(getFilesDir(),"workflow-before-import.json"));ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        ServerConnection.copy(in,out,20*1024*1024);String backup=out.toString("UTF-8");runOnUiThread(()->{exportJson=backup;Intent output=new Intent(Intent.ACTION_CREATE_DOCUMENT);output.addCategory(Intent.CATEGORY_OPENABLE);output.setType("application/json");output.putExtra(Intent.EXTRA_TITLE,"ComfyMobile-before-import.json");startActivityForResult(output,102);});
                    }catch(Exception e){runOnUiThread(()->message("还没有导入前备份，或备份无法读取"));}
                });break;
                case 6:showPage(2);break;
                case 7:showPage(3);break;
                case 8:showConnection();break;
                case 9:prefs.edit().putString("orientation","system").apply();applyOrientation("system");break;
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
    private final Runnable eventTick=new Runnable(){public void run(){
        if(!active)return;
        if(editor==null||page<0||page==1){handler.postDelayed(this,200);return;}
        if(page==0)applyWebMenus();
        final int epoch=eventEpoch;final EditorView source=editor;
        source.call("drainEvents",new JSONArray(),r->{
            if(epoch!=eventEpoch||!active)return;
            JSONArray events=r.optJSONArray("events");if(events==null){handler.postDelayed(eventTick,500);return;}
            boolean completed=false;JSONObject editRequest=null,pickRequest=null;
            for(int i=0;i<events.length();i++){
                JSONObject event=events.optJSONObject(i);if(event==null)continue;JSONObject data=event.optJSONObject("data");
                String type=event.optString("type");
                if(type.equals("progress")&&data!=null){String label="采样进度 "+data.optInt("value")+" / "+data.optInt("max");status(label);if(menu!=null)menu.showProgress(label);}
                if(type.equals("preview")&&data!=null)previewData=data.optString("dataUrl",previewData);
                if(type.equals("execution_success")||type.equals("executed"))completed=true;
                if(type.equals("execution_success")&&menu!=null)menu.showProgress("生成完成 · 可在图库查看");
                if(type.equals("execution_error")){status("节点执行失败，请查看任务");if(menu!=null)menu.showProgress("节点执行失败，请查看任务");}
                if(type.equals("edit_field")&&data!=null){editRequest=data;pickRequest=null;}
                if(type.equals("pick_field")&&data!=null){pickRequest=data;editRequest=null;}
            }
            if(completed&&results!=null)results.refresh();
            if(editRequest!=null&&page==0&&source==editor)openEdit(source,editRequest);
            if(pickRequest!=null&&page==0&&source==editor){
                JSONArray fields=pickRequest.optJSONArray("fields");
                if(fields!=null&&fields.length()>0){
                    String[] names=new String[fields.length()];for(int i=0;i<names.length;i++){JSONObject f=fields.optJSONObject(i);String v=f.optString("value").replace('\n',' ');names[i]=f.optString("name")+(v.isEmpty()?"":"："+(v.length()>40?v.substring(0,40)+"…":v));}
                    new AlertDialog.Builder(MainActivity.this).setTitle(pickRequest.optString("nodeTitle")+" · 选择要编辑的字段")
                        .setItems(names,(d,i)->openEdit(source,fields.optJSONObject(i))).setNegativeButton("取消",null).show();
                }
            }
            handler.postDelayed(eventTick,page==0?160:1000);
        });
    }};
    /** Re-reads the field so the prompt page starts from the node's current text, then opens it. */
    private void openEdit(EditorView source,JSONObject field){
        source.call("readField",new JSONArray().put(field.optJSONObject("target")),value->{
            if(!active||page!=0||editor!=source)return;
            if(!value.optBoolean("ok")){report(value);return;}
            try{field.put("value",value.optString("value"));}catch(Exception ignored){}
            showPage(1);prompt.openField(field);
        });
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(!active)return;
        if(results!=null)results.refresh();handler.postDelayed(this,4000);
    }};
    @Override protected void onResume(){super.onResume();active=true;eventEpoch++;handler.removeCallbacks(eventTick);handler.post(eventTick);handler.post(tick);if(editor!=null)editor.web.onResume();}
    @Override protected void onPause(){super.onPause();active=false;eventEpoch++;handler.removeCallbacks(eventTick);handler.removeCallbacks(tick);prompt.persist();if(editor!=null)editor.web.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("page",Math.max(0,page));prompt.persist();}
    @Override protected void onDestroy(){active=false;handler.removeCallbacksAndMessages(null);if(editor!=null)editor.destroy();worker.shutdownNow();images.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){
        if(page==0&&menu!=null&&menu.isOpen()){menu.close();return;}
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
            new JSONObject(json);runOnUiThread(()->new AlertDialog.Builder(this).setMessage("导入将替换当前画布；会先在手机保存一份导入前备份。").setPositiveButton("导入",(d,w)->importWithBackup(json)).setNegativeButton("取消",null).show());
        }catch(Exception e){runOnUiThread(()->message("导入失败："+e.getMessage()));}});}
        if(request==102&&exportJson!=null){String json=exportJson;exportJson=null;worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IllegalStateException("无法写入文件");out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));runOnUiThread(()->message("工作流已导出"));}catch(Exception e){runOnUiThread(()->message("导出失败："+e.getMessage()));}});}
    }
    private void importWithBackup(String json){
        final EditorView destination=editor;
        destination.command("export",new JSONObject(),before->{
            if(!before.optBoolean("ok")){report(before);return;}
            worker.execute(()->{
                try(OutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"workflow-before-import.json"))){
                    out.write(before.optString("json").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    runOnUiThread(()->{
                        if(editor!=destination){message("连接已改变，已取消导入");return;}
                        destination.command("import",NodePanel.args("json",json),r->{report(r);prompt.resetTarget();if(r.optBoolean("ok"))message("工作流已导入");});
                    });
                }catch(Exception e){runOnUiThread(()->message("无法保存导入前备份，已取消导入："+e.getMessage()));}
            });
        });
    }
}
