package local.comfymobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements EditorView.Host {
    static final int PAGE_CANVAS=0,PAGE_PROMPT=1,PAGE_TASKS=2,PAGE_GALLERY=3,PAGE_SIMPLE=4;
    private static final int[] NAV_PAGES={PAGE_CANVAS,PAGE_SIMPLE,PAGE_PROMPT,PAGE_TASKS,PAGE_GALLERY};
    final ExecutorService worker=Executors.newFixedThreadPool(3);
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
    private SimpleMode simple;
    private ServerAddress server;
    private ComfyClient client;
    private SharedPreferences prefs;
    private int page=0,returnPage=PAGE_CANVAS;
    private boolean active=false,running=false;
    private final SubmissionState submission=new SubmissionState();
    private static final String GENERATE="开始生成",STOP="停止生成";
    private Button orientationButton;
    private int setupLoads=-1,eventEpoch=0,previewSeq=0;
    private boolean setupPending=false,previewPending=false;
    private long lastPreviewAt=0,lastBackAt=0;
    private String exportJson,workflowTitle="";
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("settings",0);
        applyOrientation(prefs.getString("orientation","system"));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
        nav=Ui.row(this);Ui.pad(nav,5);String[] tabs={"画布","简洁","提示词","任务","图库"};
        for(int i=0;i<tabs.length;i++){final int target=NAV_PAGES[i];Ui.weighted(nav,Ui.button(this,tabs[i],false,()->showPage(target)));}root.addView(nav);
        setContentView(root);prompt=new PromptEditor(this);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
        watchNetwork();
        String recent=prefs.getString("server","");
        if(recent.isEmpty())showConnection();
        else{try{attach(ServerAddress.parse(recent));showPage(state==null?prefs.getInt("start_page",PAGE_CANVAS):state.getInt("page",PAGE_CANVAS));}catch(IllegalArgumentException e){showConnection();}}
    }
    EditorView editor(){return editor;}
    PromptEditor prompt(){return prompt;}
    ComfyClient client(){return client;}
    SharedPreferences prefs(){return prefs;}
    int page(){return page;}
    void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    void report(JSONObject r){if(!r.optBoolean("ok"))message(r.optString("error","操作失败"));}

    // ---------------- connection ----------------
    private void showConnection(){
        header.setVisibility(View.VISIBLE);connection.setVisibility(View.VISIBLE);
        page=-1;results=null;simple=null;menu=null;body.removeAllViews();nav.setVisibility(View.GONE);
        LinearLayout content=Ui.column(this);Ui.pad(content,22);
        Ui.add(content,Ui.title(this,"连接家中电脑"));
        Ui.add(content,Ui.text(this,"手机与电脑连接同一 Wi-Fi。\n电脑负责运行模型，手机负责编辑和收图。",16,Ui.MUTED));
        ArrayList<String> saved=savedServers();
        if(!saved.isEmpty()){
            Ui.add(content,Ui.text(this,"最近连接的电脑",14,Ui.TEXT));
            for(String origin:saved)Ui.add(content,Ui.button(this,origin,false,()->tryConnect(origin,"",null,null)));
        }
        Ui.add(content,Ui.text(this,"ComfyUI 服务器地址",14,Ui.TEXT));
        EditText address=Ui.input(this,"http://192.168.1.8:8188",false);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(prefs.getString("server",""));Ui.add(content,address);
        EditText code=Ui.input(this,"访问口令（只有电脑开启了口令才需要填）",false);code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);Ui.add(content,code);
        TextView state=Ui.text(this,"输入电脑的局域网地址，或自动查找",14,Ui.MUTED);Ui.add(content,state);
        final Button[] connect=new Button[1];
        connect[0]=Ui.button(this,"检测并连接",true,()->tryConnect(address.getText().toString(),code.getText().toString(),state,connect[0]));Ui.add(content,connect[0]);
        LinearLayout found=Ui.column(this);
        Ui.add(content,Ui.button(this,"自动查找局域网中的电脑",false,()->{
            if(!onLocalNetwork()){state.setText(noWifiText());return;}
            found.removeAllViews();state.setText("正在查找开放了 ComfyUI 的电脑（约 10 秒）…");
            Discovery.scan(this,saved,origin->runOnUiThread(()->{if(isFinishing())return;Ui.add(found,Ui.button(this,"找到："+origin,false,()->tryConnect(origin,code.getText().toString(),state,null)));}),
                count->runOnUiThread(()->state.setText(count==0?"没有找到。请确认电脑 ComfyUI 已开启局域网监听，并且手机和电脑在同一网段。":"找到 "+count+" 台，点选连接")));
        }));
        Ui.add(content,found);
        Ui.add(content,Ui.text(this,"电脑启动参数\n--listen 0.0.0.0 --port 8188\n\n建议在路由器里给电脑设定固定 IP，地址就不会变化。\n\n在外面使用：电脑和手机都安装 Tailscale 或 ZeroTier 等组网工具后，在这里填电脑的组网地址即可，不需要公网服务器。\n\niPad / 其他平板：电脑安装网页版后，用 Safari 打开 相同地址/comfymobile/ ，再“添加到主屏幕”即可全屏使用。手机浏览器打开同一页面也能下载最新安卓安装包。",14,Ui.MUTED));
        if(editor!=null)Ui.add(content,Ui.button(this,"返回当前工作流",false,()->showPage(PAGE_CANVAS)));
        Ui.add(content,Ui.text(this,"本地开发版 "+Version.NAME+" · Android 10+",12,Ui.MUTED));body.addView(Ui.scroll(this,Ui.maxWidth(content,560)));updateLayout();
    }
    private void tryConnect(String input,String code,TextView state,Button button){
        final ServerAddress candidate;
        try{candidate=ServerAddress.parse(input);}catch(IllegalArgumentException e){if(state!=null)state.setText(e.getMessage());else message(e.getMessage());return;}
        String token=code==null?"":code.trim();
        if(!token.isEmpty()){
            // The computer's optional LAN access code travels as a cookie for both the editor page and the app's own requests.
            android.webkit.CookieManager cookies=android.webkit.CookieManager.getInstance();
            cookies.setCookie(candidate.origin(),"comfymobile_token="+token+"; Max-Age=31536000; Path=/");cookies.flush();
        }
        if(button!=null)button.setEnabled(false);if(state!=null)state.setText("正在检查电脑上的 ComfyUI…");Ui.hideKeyboard(this,root);
        worker.execute(()->{
            try{JSONObject stats=new ComfyClient(candidate).json("/system_stats");if(!stats.has("system")||!stats.has("devices"))throw new IllegalStateException("该地址没有返回 ComfyUI 信息");
                runOnUiThread(()->{if(isFinishing())return;attach(candidate);showPage(prefs.getInt("start_page",PAGE_CANVAS));message("连接成功");});
            }catch(Exception e){runOnUiThread(()->{
                if(button!=null)button.setEnabled(true);
                String text=String.valueOf(e.getMessage()).contains("401")?"这台电脑开启了访问口令，请在上面填写口令后再连接。":onLocalNetwork()?"连接失败："+e.getMessage()+"\n检查 ComfyUI 是否开启局域网监听、地址和端口是否正确，或换用“自动查找”。":noWifiText();
                if(state!=null)state.setText(text);else message(text);
            });}
        });
    }
    private String noWifiText(){return "手机当前没有连接 Wi-Fi（可能在用移动数据），连不到家里的电脑。请连接与电脑相同的 Wi-Fi 后再试。";}
    boolean onLocalNetwork(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps!=null&&(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
    }
    private ArrayList<String> savedServers(){
        ArrayList<String> list=new ArrayList<>();
        try{JSONArray a=new JSONArray(prefs.getString("servers","[]"));for(int i=0;i<a.length();i++)list.add(a.getString(i));}catch(Exception ignored){}
        String current=prefs.getString("server","");if(!current.isEmpty()&&!list.contains(current))list.add(0,current);
        return list;
    }
    private void rememberServer(String origin){
        ArrayList<String> list=savedServers();list.remove(origin);list.add(0,origin);
        JSONArray a=new JSONArray();for(int i=0;i<list.size()&&i<6;i++)a.put(list.get(i));
        prefs.edit().putString("server",origin).putString("servers",a.toString()).apply();
    }
    private void attach(ServerAddress address){
        if(server!=null&&server.origin().equals(address.origin())&&editor!=null)return;
        submission.reset();
        if(editor!=null)editor.destroy();server=address;client=new ComfyClient(server);rememberServer(server.origin());
        editor=new EditorView(this,server,this);prompt.resetTarget();setupLoads=-1;setupPending=false;previewSeq=0;previewPending=false;
        status("正在加载编辑器");
        eventEpoch++;handler.removeCallbacks(eventTick);if(active)handler.post(eventTick);
    }
    /** Retries a failed editor load as soon as a network comes back (for example after the phone rejoins Wi-Fi). */
    private void watchNetwork(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return;
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){handler.postDelayed(()->{if(editor!=null&&editor.failed&&!isFinishing()){editor.retry();if(page==PAGE_CANVAS)showPage(PAGE_CANVAS);}},1500);}
        };
        try{cm.registerDefaultNetworkCallback(networkCallback);}catch(Exception ignored){networkCallback=null;}
    }

    // ---------------- EditorView.Host ----------------
    @Override public void status(String text){
        String line=server==null?text:server.origin()+" · "+text;connection.setText(line);if(menu!=null)menu.setStatus(line);
    }
    @Override public void editorCrashed(EditorView view){
        if(view!=editor)return;
        ServerAddress address=server;
        if(view.getParent()!=null)((ViewGroup)view.getParent()).removeView(view);
        view.destroy();editor=null;server=null;attach(address);
        if(page==PAGE_CANVAS||page==PAGE_SIMPLE)showPage(page);
        message("电脑编辑器的网页进程被系统回收（通常是手机内存不足），已自动重新载入。请检查最近未保存的改动。");
    }
    @Override public void editorFailed(EditorView view){if(view==editor&&page==PAGE_CANVAS)showPage(PAGE_CANVAS);}
    @Override public void editorDownload(String path,String filename){saveFromServer(path,filename);}
    /** Puts a generated result into a LoadImage node, so the next run edits it (outputs are referenced as "name [output]"). */
    void useAsInput(JSONObject output){
        if(editor==null||output==null)return;
        String sub=output.optString("subfolder",""),type=output.optString("type","output");
        String value=(sub.isEmpty()?"":sub+"/")+output.optString("filename")+(type.equals("input")?"":" ["+type+"]");
        editor.command("allFields",new JSONObject(),r->{
            JSONArray all=r.optJSONArray("fields");if(!r.optBoolean("ok")||all==null){report(r);return;}
            ArrayList<JSONObject> loaders=new ArrayList<>();ArrayList<String> labels=new ArrayList<>(),details=new ArrayList<>();
            for(int i=0;i<all.length();i++){JSONObject f=all.optJSONObject(i);
                if(f.optString("name").equals("image")&&f.optString("nodeType").contains("LoadImage")&&!f.isNull("options")){loaders.add(f);labels.add(f.optString("nodeTitle")+" · #"+f.optJSONObject("target").optString("nodeId"));details.add("当前："+f.optString("value"));}}
            if(loaders.isEmpty()){message("当前工作流没有“加载图像”节点");return;}
            java.util.function.IntConsumer write=i->editor.command("widget",NodePanel.args("target",loaders.get(i).optJSONObject("target"),"value",value),w->{
                if(!w.optBoolean("ok")){report(w);return;}
                message("已设为“"+labels.get(i)+"”的输入图");refreshWorkflowTitle();if(simple!=null)simple.reload();
            });
            if(loaders.size()==1)write.accept(0);else Ui.choose(this,"设为哪个节点的输入图？",labels,details,write);
        });
    }
    @Override public void editorLink(Uri url){
        String path=url.getPath()==null?"":url.getPath(),name=url.getQueryParameter("filename");
        if(path.endsWith("/view")&&name!=null){
            JSONObject output=NodePanel.args("filename",name,"subfolder",url.getQueryParameter("subfolder")==null?"":url.getQueryParameter("subfolder"),
                "type",url.getQueryParameter("type")==null?"output":url.getQueryParameter("type"));
            ResultsView.open(this,output);return;
        }
        try{startActivity(new Intent(Intent.ACTION_VIEW,url));}catch(Exception e){message("无法打开链接");}
    }
    @Override public void editorTouched(){if(menu!=null&&menu.isOpen())menu.close();}

    // ---------------- pages ----------------
    void showPage(int index){
        if(editor==null){showConnection();return;}
        if((index==PAGE_PROMPT||index==PAGE_TASKS||index==PAGE_GALLERY)&&(page==PAGE_CANVAS||page==PAGE_SIMPLE))returnPage=page;
        if(index==PAGE_CANVAS||index==PAGE_SIMPLE)prefs.edit().putInt("start_page",index).apply();
        if(index!=PAGE_CANVAS)agentReturnsToSimple=false;
        Ui.hideKeyboard(this,root);page=index;body.removeAllViews();results=null;simple=null;menu=null;workflowPage=null;
        for(int i=0;i<nav.getChildCount();i++)((Button)nav.getChildAt(i)).setTextColor(NAV_PAGES[i]==index?Ui.TEAL:Ui.MUTED);
        if(editor.getParent()!=null)((ViewGroup)editor.getParent()).removeView(editor);
        if(index==PAGE_CANVAS){
            workflowPage=new FrameLayout(this);
            workflowPage.addView(editor,new FrameLayout.LayoutParams(-1,-1));
            if(editor.failed)workflowPage.addView(failedCard(),new FrameLayout.LayoutParams(-1,-1));
            menu=new FloatingMenu(this,workflowPage,prefs);menu.setItems(menuItems());syncMenuState();menu.setAgentOpen(agentOpen);
            menu.setStatus(connection.getText().toString());menu.setTitle(workflowTitle);body.addView(workflowPage);menu.attached();
            if(!prefs.getBoolean("hint_v4",false)){prefs.edit().putBoolean("hint_v4",true).apply();message("双击提示词节点进入编辑；圆形按钮展开功能；做好工作流后可切到“简洁模式”出图");}
        }else if(index==PAGE_PROMPT){
            if(prompt.view().getParent()!=null)((ViewGroup)prompt.view().getParent()).removeView(prompt.view());body.addView(prompt.view());
            prompt.sync(null);
        }else if(index==PAGE_SIMPLE){
            // The editor stays alive off screen; simple mode drives it through the same bridge.
            simple=new SimpleMode(this);body.addView(simple.view());simple.setTitle(workflowTitle);simple.reload();
        }else{results=new ResultsView(this,client,index==PAGE_GALLERY);body.addView(results.view());}
        updateLayout();
    }
    void leaveSubpage(){showPage(returnPage==PAGE_SIMPLE?PAGE_SIMPLE:PAGE_CANVAS);}
    private View failedCard(){
        LinearLayout card=Ui.column(this);Ui.pad(card,20);card.setBackgroundColor(0xF010151A);card.setGravity(Gravity.CENTER);card.setClickable(true);
        Ui.add(card,Ui.title(this,"连不上电脑"));
        Ui.add(card,Ui.text(this,onLocalNetwork()?"请确认电脑开着、ComfyUI 正在运行并开启了局域网监听。网络恢复后会自动重试。":noWifiText(),15,Ui.MUTED));
        Ui.add(card,Ui.button(this,"重试",true,()->{editor.retry();showPage(PAGE_CANVAS);}));
        Ui.add(card,Ui.button(this,"连接设置 / 自动查找",false,this::showConnection));
        return Ui.maxWidth(card,480);
    }
    boolean isLandscape(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    void toggleOrientation(){String mode=isLandscape()?"portrait":"landscape";prefs.edit().putString("orientation",mode).apply();applyOrientation(mode);}
    private void applyOrientation(String mode){
        setRequestedOrientation(mode.equals("landscape")?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:mode.equals("portrait")?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        if((page==PAGE_TASKS||page==PAGE_GALLERY)&&results!=null&&results.needsRelayout())showPage(page);else updateLayout();
        if(prompt!=null)prompt.orientationChanged();if(simple!=null)simple.orientationChanged();
    }
    private void updateLayout(){
        if(header==null||connection==null||nav==null)return;
        boolean canvas=page==PAGE_CANVAS&&editor!=null,bare=page==PAGE_PROMPT||page==PAGE_SIMPLE||canvas;
        if(orientationButton!=null)orientationButton.setText(isLandscape()?"竖屏":"横屏");
        header.setVisibility(bare?View.GONE:View.VISIBLE);connection.setVisibility(bare?View.GONE:View.VISIBLE);
        nav.setVisibility(page<0||bare?View.GONE:View.VISIBLE);
        fullScreen(canvas);
        if(menu!=null){menu.setItems(menuItems());syncMenuState();}
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
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&page==PAGE_CANVAS&&editor!=null)fullScreen(!(agentOpen&&Build.VERSION.SDK_INT<30));}

    // ---------------- canvas menu ----------------
    private FloatingMenu.Item[] menuItems(){
        NodePanel nodes=new NodePanel(this);
        return new FloatingMenu.Item[]{
            new FloatingMenu.Item(GENERATE,true,false,()->generate(false)),
            new FloatingMenu.Item(STOP,false,false,this::interrupt),
            new FloatingMenu.Item("简洁模式",false,false,()->showPage(PAGE_SIMPLE)),
            new FloatingMenu.Item("提示词",false,false,()->showPage(PAGE_PROMPT)),
            new FloatingMenu.Item("AI 助手",false,false,this::openAgent),
            new FloatingMenu.Item("工作流",false,false,this::workflowMenu),
            new FloatingMenu.Item("节点",false,false,nodes::list),
            new FloatingMenu.Item("添加",false,false,nodes::add),
            new FloatingMenu.Item("适应画布",false,false,()->editor.command("fit",new JSONObject(),this::report)),
            new FloatingMenu.Item("撤销",false,true,()->editor.command("undo",new JSONObject(),r->{report(r);refreshWorkflowTitle();})),
            new FloatingMenu.Item("重做",false,true,()->editor.command("redo",new JSONObject(),r->{report(r);refreshWorkflowTitle();})),
            new FloatingMenu.Item("任务",false,false,()->showPage(PAGE_TASKS)),
            new FloatingMenu.Item("图库",false,false,()->showPage(PAGE_GALLERY)),
            new FloatingMenu.Item(isLandscape()?"竖屏":"横屏",false,false,this::toggleOrientation),
            new FloatingMenu.Item("原版菜单",false,false,this::toggleWebMenus),
            new FloatingMenu.Item("更多",false,false,this::more)};
    }
    private void syncMenuState(){if(menu!=null){menu.setEnabled(GENERATE,!submission.pending());menu.setEnabled(STOP,running);}}
    void menuOpened(){refreshWorkflowTitle();}
    void refreshWorkflowTitle(){
        if(editor==null)return;
        editor.command("workflowInfo",new JSONObject(),info->{
            if(!info.optBoolean("ok"))return;
            workflowTitle="工作流："+info.optString("name")+(info.optBoolean("modified")?" ●未保存":"")+(info.isNull("subgraph")?"":" › "+info.optString("subgraph"));
            if(menu!=null)menu.setTitle(workflowTitle);if(simple!=null)simple.setTitle(workflowTitle);
        });
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
    /** Applies touch tweaks and the saved original-menu preference once per editor page load. */
    private void applyEditorSetup(){
        if(editor==null||setupPending||editor.loads==0||setupLoads==editor.loads)return;
        final EditorView source=editor;final int load=source.loads;setupPending=true;
        JSONObject config=NodePanel.args("nativeDownloads",true,"hideInfo",true,"closeAgent",true,"agentButton",prefs.getBoolean("agent_button",false),"toolbar",prefs.getBoolean("canvas_toolbar",true),"selectionToolbox",prefs.getBoolean("selection_toolbox",false),"performance",prefs.getBoolean("performance",false));
        source.command("configure",config,c->{
            if(source!=editor)return;
            if(!c.optBoolean("ok")){setupPending=false;return;}
            source.command("chrome",NodePanel.args("focus",!prefs.getBoolean("web_menus",false)),r->{
                if(source!=editor)return;setupPending=false;
                if(r.optBoolean("ok")){setupLoads=load;refreshWorkflowTitle();if(simple!=null)simple.reload();}
            });
        });
    }
    void generate(boolean front){
        final int epoch=submission.begin();if(epoch<0)return;
        if(prefs.getBoolean("notify",true)&&!prefs.getBoolean("asked_notify",false)){prefs.edit().putBoolean("asked_notify",true).apply();GenerationWatch.askPermission(this);}
        syncMenuState();if(simple!=null)simple.setBusy(true);status("正在提交");
        editor.command("generate",NodePanel.args("batch",prefs.getInt("batch",1),"front",front),r->{
            if(!submission.finish(epoch))return;syncMenuState();if(simple!=null)simple.setBusy(false);
            if(r.optBoolean("ok")){message("已提交给电脑；进度显示在顶部，结果在图库");status("已发送生成请求");running=true;syncMenuState();}
            else{report(r);status("提交未确认，请检查任务");}
        });
    }
    void interrupt(){
        if(client==null)return;
        worker.execute(()->{
            try{client.post("/interrupt",new JSONObject());runOnUiThread(()->message("已请求电脑停止当前生成"));}
            catch(Exception e){runOnUiThread(()->message("停止失败："+e.getMessage()));}
        });
    }
    private void more(){
        String[] items={"每次生成数量："+prefs.getInt("batch",1),"插队生成（排到最前）","导入工作流 JSON","导出工作流 JSON","导出上次导入前备份","查看实时预览","刷新编辑器",
            "画布工具条："+(prefs.getBoolean("canvas_toolbar",true)?"显示":"隐藏"),"选中节点时的工具条："+(prefs.getBoolean("selection_toolbox",false)?"显示":"隐藏（避免挡住双击）"),"AI 助手自带的悬浮按钮："+(prefs.getBoolean("agent_button",false)?"显示":"隐藏（从圆形菜单打开）"),"性能模式（缩小时简化绘制）："+(prefs.getBoolean("performance",false)?"开":"关"),
            "生成时保持亮屏："+(prefs.getBoolean("keep_awake",true)?"开":"关"),"后台完成通知："+(prefs.getBoolean("notify",true)?"开":"关"),"连接设置","屏幕跟随系统"};
        new AlertDialog.Builder(this).setTitle("更多").setItems(items,(d,index)->{
            switch(index){
                case 0:chooseBatch(null);break;
                case 1:generate(true);break;
                case 2:Intent input=new Intent(Intent.ACTION_OPEN_DOCUMENT);input.addCategory(Intent.CATEGORY_OPENABLE);input.setType("*/*");startActivityForResult(input,101);break;
                case 3:editor.command("export",new JSONObject(),r->{if(!r.optBoolean("ok")){report(r);return;}exportJson=r.optString("json");createDocument("ComfyMobile-workflow.json");});break;
                case 4:worker.execute(()->{
                    try(InputStream in=new java.io.FileInputStream(new java.io.File(getFilesDir(),"workflow-before-import.json"));ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        ServerConnection.copy(in,out,20*1024*1024);String backup=out.toString("UTF-8");runOnUiThread(()->{exportJson=backup;createDocument("ComfyMobile-before-import.json");});
                    }catch(Exception e){runOnUiThread(()->message("还没有导入前备份，或备份无法读取"));}
                });break;
                case 5:showLivePreview();break;
                case 6:new AlertDialog.Builder(this).setMessage("刷新会重新加载电脑端编辑器。请先保存尚未保存的工作流。").setPositiveButton("刷新",(dd,w)->{editor.web.reload();prompt.resetTarget();}).setNegativeButton("取消",null).show();break;
                case 7:togglePref("canvas_toolbar",true);break;
                case 8:togglePref("selection_toolbox",false);break;
                case 9:togglePref("agent_button",false);break;
                case 10:togglePref("performance",false);break;
                case 11:togglePref("keep_awake",true);keepAwake(running);break;
                case 12:togglePref("notify",true);if(prefs.getBoolean("notify",true))GenerationWatch.askPermission(this);break;
                case 13:showConnection();break;
                case 14:prefs.edit().putString("orientation","system").apply();applyOrientation("system");break;
            }
        }).setNegativeButton("关闭",null).show();
    }
    void chooseBatch(Runnable after){
        String[] counts={"1","2","3","4","6","8","10"};
        new AlertDialog.Builder(this).setTitle("每次生成数量").setItems(counts,(d,i)->{prefs.edit().putInt("batch",Integer.parseInt(counts[i])).apply();if(after!=null)after.run();}).show();
    }
    private void togglePref(String key,boolean fallback){prefs.edit().putBoolean(key,!prefs.getBoolean(key,fallback)).apply();setupLoads=-1;applyEditorSetup();}
    private void createDocument(String title){Intent output=new Intent(Intent.ACTION_CREATE_DOCUMENT);output.addCategory(Intent.CATEGORY_OPENABLE);output.setType("application/json");output.putExtra(Intent.EXTRA_TITLE,title);startActivityForResult(output,102);}

    // ---------------- workflows stored on the computer ----------------
    void workflowMenu(){
        editor.command("workflowInfo",new JSONObject(),info->{
            if(!info.optBoolean("ok")){report(info);return;}
            ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
            labels.add(info.optBoolean("temporary")?"保存到电脑…（新工作流需要命名）":"保存"+(info.optBoolean("modified")?"（有未保存的修改）":"（已是最新）"));actions.add(()->saveWorkflow(false,false));
            labels.add("另存为…");actions.add(()->saveWorkflow(true,false));
            labels.add("打开电脑上的工作流…");actions.add(this::openWorkflowList);
            labels.add("新建空白工作流");actions.add(()->editor.command("newWorkflow",new JSONObject(),r->{report(r);prompt.resetTarget();refreshWorkflowTitle();if(simple!=null)simple.reload();}));
            JSONArray open=info.optJSONArray("open");
            for(int i=0;open!=null&&i<open.length();i++){
                JSONObject w=open.optJSONObject(i);if(w==null||w.optBoolean("active"))continue;
                String path=w.optString("path");labels.add("切换到已打开："+w.optString("name")+(w.optBoolean("modified")?" ●":""));actions.add(()->openWorkflow(path));
            }
            new AlertDialog.Builder(this).setTitle("当前："+info.optString("name")+(info.optBoolean("modified")?" ●未保存":""))
                .setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).setNegativeButton("关闭",null).show();
        });
    }
    private void saveWorkflow(boolean as,boolean force){
        editor.command("saveWorkflow",NodePanel.args("as",as,"force",force),r->{
            if(r.optBoolean("conflict")){
                new AlertDialog.Builder(this).setTitle("电脑上的版本更新").setMessage(r.optString("error")+"。")
                    .setPositiveButton("仍然覆盖",(d,w)->saveWorkflow(false,true)).setNeutralButton("另存为新文件",(d,w)->saveWorkflow(true,true)).setNegativeButton("取消",null).show();
                return;
            }
            if(!r.optBoolean("ok")){report(r);return;}
            if(page!=PAGE_CANVAS)showPage(PAGE_CANVAS);
            message(r.optBoolean("prompted")?"请在画布弹出的窗口中输入名称并确认":"已保存到电脑");
            handler.postDelayed(this::refreshWorkflowTitle,1500);
        });
    }
    private void openWorkflowList(){
        editor.command("workflows",new JSONObject(),r->{
            if(!r.optBoolean("ok")){report(r);return;}
            JSONArray list=r.optJSONArray("workflows");if(list==null||list.length()==0){message("电脑上还没有保存的工作流");return;}
            ArrayList<String> names=new ArrayList<>(),details=new ArrayList<>();
            java.text.DateFormat format=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT);
            for(int i=0;i<list.length();i++){JSONObject w=list.optJSONObject(i);names.add(w.optString("name"));
                details.add((w.optBoolean("open")?"已打开 · ":"")+(w.isNull("modified")?"":"修改于 "+format.format(new java.util.Date(w.optLong("modified")))));}
            Ui.choose(this,"打开电脑上的工作流",names,details,i->openWorkflow(list.optJSONObject(i).optString("path")));
        });
    }
    void openWorkflow(String path){
        editor.command("openWorkflow",NodePanel.args("path",path),r->{
            report(r);if(!r.optBoolean("ok"))return;
            prompt.resetTarget();refreshWorkflowTitle();if(simple!=null)simple.reload();message("已打开");
        });
    }

    // ---------------- events from the editor ----------------
    private final Runnable eventTick=new Runnable(){public void run(){
        if(!active)return;
        if(editor==null||editor.failed||page<0||page==PAGE_PROMPT){handler.postDelayed(this,300);return;}
        if(page==PAGE_CANVAS||page==PAGE_SIMPLE)applyEditorSetup();
        final int epoch=eventEpoch;final EditorView source=editor;
        source.call("drainEvents",new JSONArray(),r->{
            if(epoch!=eventEpoch||!active)return;
            JSONArray events=r.optJSONArray("events");if(events==null){handler.postDelayed(eventTick,800);return;}
            JSONObject state=r.optJSONObject("state");
            boolean completed=false;JSONObject editRequest=null,pickRequest=null;
            if(state!=null)applyState(state,source);
            for(int i=0;i<events.length();i++){
                JSONObject event=events.optJSONObject(i);if(event==null)continue;JSONObject data=event.optJSONObject("data");
                String type=event.optString("type");
                if(type.equals("execution_start"))setRunning(true);
                if(type.equals("execution_success")){completed=true;notifyProgress("生成完成 · 可在图库查看");}
                if(type.equals("executed"))completed=true;
                if(type.equals("execution_interrupted"))notifyProgress("已停止生成");
                if(type.equals("execution_error")&&data!=null){String node=data.optString("node_type");notifyProgress("执行失败"+(node.isEmpty()?"":"："+node));message("节点执行失败"+(node.isEmpty()?"":"（"+node+"）")+"："+data.optString("exception_message","请查看任务"));}
                if(type.equals("edit_field")&&data!=null){editRequest=data;pickRequest=null;}
                if(type.equals("pick_field")&&data!=null){pickRequest=data;editRequest=null;}
                if(type.equals("download")&&data!=null)handleDownload(data);
            }
            if(completed){if(results!=null)results.refresh();if(simple!=null)simple.completed();refreshWorkflowTitle();}
            if(editRequest!=null&&page==PAGE_CANVAS&&source==editor)openEdit(source,editRequest);
            if(pickRequest!=null&&page==PAGE_CANVAS&&source==editor)pickField(source,pickRequest);
            long idle=SystemClock.uptimeMillis()-source.lastTouchAt;
            // Fast while a finger is on the canvas (double taps) or a job runs; slow when idle to save battery.
            handler.postDelayed(eventTick,page==PAGE_CANVAS&&idle<2500?120:running?400:page==PAGE_CANVAS?900:1200);
        });
    }};
    /** Opens the ComfyUI-AgentLink chat inside the editor page; it lives on the canvas, so simple mode switches there first. */
    void openAgent(){
        if(editor==null)return;
        // Opened from simple mode: closing the chat returns there.
        agentReturnsToSimple=page==PAGE_SIMPLE;
        if(page!=PAGE_CANVAS)showPage(PAGE_CANVAS);
        editor.command("agent",NodePanel.args("action","open"),r->{if(!r.optBoolean("ok"))report(r);else setAgentOpen(true);});
    }
    private boolean agentOpen=false,agentReturnsToSimple=false;
    private void setAgentOpen(boolean open){
        if(open==agentOpen)return;agentOpen=open;
        if(!open&&agentReturnsToSimple&&page==PAGE_CANVAS){agentReturnsToSimple=false;showPage(PAGE_SIMPLE);return;}
        if(menu!=null)menu.setAgentOpen(open);
        // Android 10 ignores adjustResize in immersive mode, so the keyboard would cover the chat box; show the bars while chatting.
        if(Build.VERSION.SDK_INT<30&&page==PAGE_CANVAS)fullScreen(!open);
    }
    private void applyState(JSONObject state,EditorView source){
        if(page==PAGE_CANVAS)setAgentOpen(state.optBoolean("agentOpen"));
        boolean busy=!state.isNull("running")||state.optInt("queueRemaining")>0;
        if(busy!=running)setRunning(busy);
        JSONObject p=state.optJSONObject("progress");
        String node=state.isNull("executingTitle")?null:state.optString("executingTitle");
        if(p!=null&&p.optInt("max")>0){String label=(node==null?"":node+" · ")+"采样 "+p.optInt("value")+" / "+p.optInt("max")+(state.optInt("queueRemaining")>1?" · 排队 "+(state.optInt("queueRemaining")-1):"");notifyProgress(label);}
        if(simple!=null)simple.showProgress(busy,p==null?0:p.optInt("value"),p==null?0:p.optInt("max"),node,state.optInt("queueRemaining"));
        int seq=state.optInt("previewSeq");
        long now=SystemClock.uptimeMillis();
        if(seq!=previewSeq&&!previewPending&&now-lastPreviewAt>900&&(page==PAGE_CANVAS||page==PAGE_SIMPLE)){
            previewPending=true;lastPreviewAt=now;
            source.call("preview",new JSONArray().put(previewSeq),pr->{
                previewPending=false;if(source!=editor||!pr.optBoolean("ok"))return;
                previewSeq=pr.optInt("seq",previewSeq);String data=pr.optString("dataUrl","");if(data.isEmpty())return;
                images.execute(()->{Bitmap b=decodeDataUrl(data,480);runOnUiThread(()->{if(menu!=null)menu.showPreview(b);if(simple!=null)simple.showPreview(b);});});
            });
        }
    }
    private String progressText="";
    String progressText(){return running?progressText:"";}
    private void notifyProgress(String label){progressText=label;status(label);if(menu!=null)menu.showProgress(label);if(simple!=null)simple.progress(label);}
    private void setRunning(boolean busy){running=busy;syncMenuState();keepAwake(busy);if(simple!=null)simple.setRunning(busy);}
    private void keepAwake(boolean busy){
        if(busy&&prefs.getBoolean("keep_awake",true))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    static Bitmap decodeDataUrl(String dataUrl,int max){
        try{byte[] bytes=android.util.Base64.decode(dataUrl.substring(dataUrl.indexOf(',')+1),android.util.Base64.DEFAULT);return ResultsView.decode(bytes,max);}
        catch(Exception e){return null;}
    }
    void showLivePreview(){
        if(editor==null)return;
        editor.call("preview",new JSONArray().put(-1),r->{
            String data=r.optString("dataUrl","");
            if(data.isEmpty()){message("暂时没有实时预览；电脑需要启用预览（启动参数 --preview-method auto），生成时再打开");return;}
            images.execute(()->{Bitmap b=decodeDataUrl(data,2000);runOnUiThread(()->{if(b!=null)ResultsView.viewer(this,"实时预览",b,null,null);});});
        });
    }
    private void pickField(EditorView source,JSONObject request){
        JSONArray fields=request.optJSONArray("fields");if(fields==null||fields.length()==0)return;
        String[] names=new String[fields.length()];
        for(int i=0;i<names.length;i++){JSONObject f=fields.optJSONObject(i);String v=f.optString("value").replace('\n',' ');names[i]=roleLabel(f)+f.optString("name")+(v.isEmpty()?"":"："+(v.length()>40?v.substring(0,40)+"…":v));}
        new AlertDialog.Builder(this).setTitle(request.optString("nodeTitle")+" · 选择要编辑的字段")
            .setItems(names,(d,i)->openEdit(source,fields.optJSONObject(i))).setNegativeButton("取消",null).show();
    }
    static String roleLabel(JSONObject field){
        switch(field.optString("role")){case "positive":return "【正向】";case "negative":return "【反向】";case "mixed":return "【正反向都用】";default:return "";}
    }
    /** Re-reads the field so the prompt page starts from the node's current text, then opens it. */
    void openEdit(EditorView source,JSONObject field){
        source.call("readField",new JSONArray().put(field.optJSONObject("target")),value->{
            if(!active||(page!=PAGE_CANVAS&&page!=PAGE_SIMPLE)||editor!=source)return;
            if(!value.optBoolean("ok")){report(value);return;}
            try{field.put("value",value.optString("value"));}catch(Exception ignored){}
            showPage(PAGE_PROMPT);prompt.openField(field);
        });
    }

    // ---------------- downloads ----------------
    private void handleDownload(JSONObject d){
        String name=d.optString("name","download");
        if(d.has("error")){message("下载失败："+d.optString("error"));return;}
        if(d.has("url")){saveFromServer(d.optString("url"),name);return;}
        String data=d.optString("dataUrl","");if(data.isEmpty())return;
        worker.execute(()->{
            try{byte[] bytes=android.util.Base64.decode(data.substring(data.indexOf(',')+1),android.util.Base64.DEFAULT);
                client.saveData(this,bytes,name,d.optString("mime"));runOnUiThread(()->message("已保存到手机："+name+where(name)));}
            catch(Exception e){runOnUiThread(()->message("保存失败："+e.getMessage()));}
        });
    }
    void saveFromServer(String path,String name){
        if(client==null)return;message("正在保存 "+name);
        worker.execute(()->{
            try{client.saveMedia(this,path,name);runOnUiThread(()->message("已保存到手机："+name+where(name)));}
            catch(Exception e){runOnUiThread(()->message("保存失败："+e.getMessage()));}
        });
    }
    static String where(String name){String type=MediaRef.byExtension(name);return type==null?"（下载/ComfyMobile）":type.startsWith("video")?"（影片/ComfyMobile）":"（相册 Pictures/ComfyMobile）";}

    // ---------------- lifecycle and back ----------------
    private final Runnable tick=new Runnable(){public void run(){
        if(!active)return;
        if(results!=null)results.refresh();handler.postDelayed(this,4000);
    }};
    @Override protected void onResume(){
        super.onResume();active=true;eventEpoch++;handler.removeCallbacks(eventTick);handler.post(eventTick);handler.post(tick);
        if(editor!=null)editor.web.onResume();GenerationWatch.stop(this);
    }
    @Override protected void onPause(){
        super.onPause();active=false;eventEpoch++;handler.removeCallbacks(eventTick);handler.removeCallbacks(tick);prompt.persist();
        // Keeps cookies such as the AI assistant's phone pairing even if the system ends the app right after.
        try{android.webkit.CookieManager.getInstance().flush();}catch(Exception ignored){}
        if(editor!=null)editor.web.onPause();
        if(running&&server!=null&&prefs.getBoolean("notify",true))GenerationWatch.start(this,server.origin());
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("page",Math.max(0,page));prompt.persist();}
    @Override protected void onDestroy(){
        active=false;handler.removeCallbacksAndMessages(null);if(editor!=null)editor.destroy();worker.shutdownNow();images.shutdownNow();
        if(networkCallback!=null){try{((ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}}
        super.onDestroy();
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed(){handleBack();}
    /** Back closes the menu, then web dialogs, then sub pages; leaving the app needs a second press. */
    private void handleBack(){
        if(page==PAGE_CANVAS&&menu!=null&&menu.isOpen()){menu.close();return;}
        if(page==PAGE_PROMPT||page==PAGE_TASKS||page==PAGE_GALLERY){if(simple==null&&results!=null&&results.back())return;leaveSubpage();return;}
        if(page<0&&editor!=null){showPage(PAGE_CANVAS);return;}
        if(page==PAGE_SIMPLE&&simple!=null&&simple.back())return;
        if(page==PAGE_CANVAS&&editor!=null&&!editor.failed){
            editor.command("escape",new JSONObject(),r->{if(!r.optBoolean("closed"))confirmExit();});
            return;
        }
        confirmExit();
    }
    private void confirmExit(){
        long now=SystemClock.uptimeMillis();
        if(now-lastBackAt<2000){finish();return;}
        lastBackAt=now;Toast.makeText(this,"再按一次返回键退出",Toast.LENGTH_SHORT).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==100){if(editor!=null)editor.fileChosen(result,data);return;}
        if(request==SimpleMode.PICK_IMAGE){if(simple!=null)simple.imagePicked(result,data);return;}
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
                        destination.command("import",NodePanel.args("json",json),r->{report(r);prompt.resetTarget();if(r.optBoolean("ok")){message("工作流已导入");refreshWorkflowTitle();}});
                    });
                }catch(Exception e){runOnUiThread(()->message("无法保存导入前备份，已取消导入："+e.getMessage()));}
            });
        });
    }
}
