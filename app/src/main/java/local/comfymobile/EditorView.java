package local.comfymobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.function.Consumer;

final class EditorView extends FrameLayout {
    final WebView web;
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ServerAddress server;
    private final Consumer<String> status;
    private String script;
    private int requestId=0;
    private boolean destroyed=false;
    /** Counts finished page loads so per-load setup (such as hiding the original menus) can be re-applied. */
    int loads=0;
    ValueCallback<Uri[]> fileCallback;

    @SuppressLint("SetJavaScriptEnabled")
    EditorView(Activity a,ServerAddress server,Consumer<String> status) {
        super(a);activity=a;this.server=server;this.status=status;
        web=new WebView(a);web.setBackgroundColor(Ui.BG);
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(true);
        web.getSettings().setSupportMultipleWindows(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        addView(web,new FrameLayout.LayoutParams(-1,-1));
        try(InputStream in=a.getAssets().open("editor-adapter.js");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);script=out.toString("UTF-8");
        }catch(Exception e){throw new IllegalStateException("缺少编辑器适配资源",e);}
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req) {
                if(server.owns(req.getUrl().toString()))return false;
                if(req.isForMainFrame()) {
                    String scheme=req.getUrl().getScheme();
                    if("https".equals(scheme)||"http".equals(scheme)) {
                        try{a.startActivity(new Intent(Intent.ACTION_VIEW,req.getUrl()));}catch(Exception e){status.accept("无法打开外部链接");}
                    }
                }return true;
            }
            @Override public void onPageFinished(WebView view,String url) {
                if(!server.owns(url))return;
                web.evaluateJavascript(script,null);loads++;status.accept("编辑器已加载");
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError error) {
                if(req.isForMainFrame())status.accept("连接失败，请检查电脑是否启动、Wi-Fi 和防火墙");
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params) {
                if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;
                Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);
                String[] types=params.getAcceptTypes();picker.setType("*/*");
                if(types.length>0 && !types[0].isEmpty() && !types[0].startsWith("."))picker.putExtra(Intent.EXTRA_MIME_TYPES,types);
                picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.getMode()==FileChooserParams.MODE_OPEN_MULTIPLE);
                try{a.startActivityForResult(picker,100);}catch(Exception e){fileCallback.onReceiveValue(null);fileCallback=null;status.accept("无法打开文件选择器");}
                return true;
            }
        });
        web.loadUrl(server.origin()+"/");
    }

    void call(String method,JSONArray args,Consumer<JSONObject> callback) {
        if(destroyed || !server.owns(web.getUrl()==null?"":web.getUrl())) {callback.accept(error("编辑器未连接"));return;}
        if(!java.util.Arrays.asList("snapshot","fields","readField","writeField","command","drainEvents").contains(method)) {callback.accept(error("不支持的操作"));return;}
        String id="r"+(++requestId),key=JSONObject.quote(id);
        String invoke="(function(){window.__cmReplies=window.__cmReplies||{};Promise.resolve().then(function(){if(!window.ComfyMobileEditor)throw Error('编辑器尚未就绪');return window.ComfyMobileEditor["
            +JSONObject.quote(method)+"].apply(null,"+args.toString()+");}).then(function(r){window.__cmReplies["+key+"]=JSON.stringify(r);},function(e){window.__cmReplies["+key+"]=JSON.stringify({ok:false,error:String(e.message||e)});});})()";
        web.evaluateJavascript(invoke,unused->poll(id,SystemClock.uptimeMillis()+45000,callback));
    }

    void command(String name,JSONObject args,Consumer<JSONObject> cb) {call("command",new JSONArray().put(name).put(args),cb);}

    private void poll(String id,long deadline,Consumer<JSONObject> cb) {
        if(destroyed)return;
        if(!server.owns(web.getUrl()==null?"":web.getUrl())) {cb.accept(error("编辑器页面已改变"));return;}
        String key=JSONObject.quote(id);
        web.evaluateJavascript("(function(){var m=window.__cmReplies||{},r=m["+key+"];delete m["+key+"];return r||null;})()",value->{
            if(destroyed)return;
            try {
                Object decoded=new JSONTokener(value==null?"null":value).nextValue();
                if(decoded instanceof String) {cb.accept(new JSONObject((String)decoded));return;}
            }catch(Exception e){cb.accept(error("编辑器返回了无法识别的数据"));return;}
            if(SystemClock.uptimeMillis()>deadline){cb.accept(error("操作超时；提交结果可能已被电脑接收，请先检查任务，勿直接重复生成"));return;}
            handler.postDelayed(()->poll(id,deadline,cb),120);
        });
    }

    static JSONObject error(String text) {JSONObject r=new JSONObject();try{r.put("ok",false);r.put("error",text);}catch(Exception ignored){}return r;}
    void destroy() {destroyed=true;handler.removeCallbacksAndMessages(null);if(fileCallback!=null)fileCallback.onReceiveValue(null);web.destroy();}
}
