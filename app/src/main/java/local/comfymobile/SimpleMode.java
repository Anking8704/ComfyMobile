package local.comfymobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * "Simple mode": a finished workflow reduced to its key controls (prompts, seed, steps, size, model, input image)
 * plus a big generate button and the latest result. Which controls appear is the workflow's own App-mode
 * selection (saved in the workflow file), or an automatic guess when none was chosen.
 */
final class SimpleMode {
    static final int PICK_IMAGE=120;
    private static final HashMap<String,String> NAMES=new HashMap<>();
    static {
        String[] pairs={"seed","种子","noise_seed","种子","control_after_generate","生成后种子","steps","步数","cfg","CFG 引导","sampler_name","采样器","scheduler","调度器",
            "denoise","重绘幅度","width","宽度","height","高度","batch_size","一次出图张数","ckpt_name","大模型","vae_name","VAE","clip_name","CLIP 模型","clip_name1","CLIP 模型 1","clip_name2","CLIP 模型 2","type","CLIP 类型","model_name","模型","strength_clip","LoRA CLIP 强度","upscale_model_name","放大模型","control_net_name","ControlNet","unet_name","模型","lora_name","LoRA","strength_model","LoRA 强度","image","输入图片","text","提示词"};
        for(int i=0;i<pairs.length;i+=2)NAMES.put(pairs[i],pairs[i+1]);
    }
    private static final HashMap<String,String> SEED_MODES=new HashMap<>();
    static {SEED_MODES.put("fixed","固定");SEED_MODES.put("increment","递增");SEED_MODES.put("decrement","递减");SEED_MODES.put("randomize","每次随机");}
    private static final int[][] SIZES={{1024,1024},{832,1216},{1216,832},{768,1344},{1344,768},{512,768},{768,512}};
    // What the AI assistant is doing, by tool, for the one-line status under its reply.
    private static final HashMap<String,String> AGENT_STEPS=new HashMap<>();
    static {
        String[] pairs={"read_canvas","读取工作流","edit_canvas","修改参数","patch_canvas","修改工作流","undo_canvas","撤销修改","run_canvas","提交生成",
            "get_job","等待出图","get_image","查看结果","list_models","查找模型","search_character_loras","查找角色 LoRA","describe_image","识别参考图","get_node_schema","查看节点选项"};
        for(int i=0;i<pairs.length;i+=2)AGENT_STEPS.put(pairs[i],pairs[i+1]);
    }
    // After these the workflow on the phone has new values, so the controls are read again.
    private static final HashSet<String> AGENT_EDITS=new HashSet<>(Arrays.asList("edit_canvas","patch_canvas","undo_canvas"));

    private final MainActivity activity;
    private final LinearLayout root,fields;
    private final TextView title,status,resultLabel,count,stage,steps;
    private final LinearLayout progressPanel;
    private final android.widget.ProgressBar bar;
    private final ImageView result;
    private final Button generate,stop,agentSend;
    private final android.widget.EditText agentInput;
    private final TextView agentReply,agentStep;
    private boolean agentBusy=false;
    private JSONArray current=new JSONArray(),presets=new JSONArray();
    private String source="auto";
    private int attempts=0;
    private JSONObject latest;
    // Static: rotating while the camera is open rebuilds this page, and the result must still reach the upload.
    private static PhotoPicker picker;
    private static JSONObject imageTarget;

    SimpleMode(MainActivity a){
        activity=a;root=Ui.column(a);Ui.pad(root,8);
        LinearLayout top=Ui.row(a);
        top.addView(Ui.button(a,"画布",false,()->a.showPage(MainActivity.PAGE_CANVAS)),new LinearLayout.LayoutParams(-2,Ui.dp(a,44)));
        title=Ui.text(a,"简洁模式",14,Ui.TEXT);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setPadding(Ui.dp(a,10),0,Ui.dp(a,6),0);title.setOnClickListener(v->a.workflowMenu());
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams ai=new LinearLayout.LayoutParams(-2,Ui.dp(a,44));ai.rightMargin=Ui.dp(a,6);
        top.addView(Ui.button(a,"AI 助手",false,a::openAgent),ai);
        top.addView(Ui.button(a,"⋯",false,this::options),new LinearLayout.LayoutParams(Ui.dp(a,52),Ui.dp(a,44)));
        root.addView(top);
        status=Ui.text(a,"",12,Ui.MUTED);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(status);

        // Progress panel: which node runs, a bar for its steps, and the queue; shown only while the computer works.
        progressPanel=Ui.column(a);progressPanel.setBackground(Ui.shape(a,0xFF14302E));Ui.pad(progressPanel,10);progressPanel.setVisibility(View.GONE);
        stage=Ui.text(a,"正在准备…",15,Ui.TEXT);stage.setPadding(0,0,0,Ui.dp(a,4));stage.setSingleLine(true);stage.setEllipsize(android.text.TextUtils.TruncateAt.END);progressPanel.addView(stage);
        bar=new android.widget.ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setIndeterminate(true);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.TEAL));bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.TEAL));
        progressPanel.addView(bar,new LinearLayout.LayoutParams(-1,Ui.dp(a,10)));
        steps=Ui.text(a,"",13,Ui.MUTED);progressPanel.addView(steps);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.bottomMargin=Ui.dp(a,8);root.addView(progressPanel,pp);
        FrameLayout box=new FrameLayout(a);box.setBackground(Ui.shape(a,Ui.SURFACE));
        result=new ImageView(a);result.setScaleType(ImageView.ScaleType.FIT_CENTER);result.setContentDescription("最新结果");
        box.addView(result,new FrameLayout.LayoutParams(-1,-1));
        resultLabel=Ui.text(a,"生成后，结果显示在这里",13,Ui.MUTED);resultLabel.setGravity(Gravity.CENTER);
        box.addView(resultLabel,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        box.setOnClickListener(v->openResult());
        fields=Ui.column(a);
        LinearLayout content;
        if(wide()){
            content=Ui.row(a);content.setGravity(Gravity.TOP);
            content.addView(Ui.scroll(a,fields),new LinearLayout.LayoutParams(0,-1,1));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.leftMargin=Ui.dp(a,8);content.addView(box,p);
        }else{
            content=Ui.column(a);
            int h=Math.round(a.getResources().getDisplayMetrics().heightPixels*0.34f);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,h);p.bottomMargin=Ui.dp(a,8);content.addView(box,p);
            content.addView(Ui.scroll(a,fields),new LinearLayout.LayoutParams(-1,0,1));
        }
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        // AI bar: say what to change; the AI edits this workflow and generates. Its reply opens the full chat.
        LinearLayout agentBox=Ui.column(a);agentBox.setPadding(0,Ui.dp(a,6),0,0);
        agentReply=Ui.text(a,"",13,Ui.TEXT);agentReply.setMaxLines(4);agentReply.setEllipsize(android.text.TextUtils.TruncateAt.END);
        agentReply.setBackground(Ui.shape(a,Ui.SURFACE));Ui.pad(agentReply,8);agentReply.setVisibility(View.GONE);agentReply.setOnClickListener(v->a.openAgent());
        agentBox.addView(agentReply);
        agentStep=Ui.text(a,"",12,Ui.MUTED);agentStep.setSingleLine(true);agentStep.setEllipsize(android.text.TextUtils.TruncateAt.END);agentStep.setVisibility(View.GONE);
        agentBox.addView(agentStep);
        LinearLayout ask=Ui.row(a);
        agentInput=Ui.input(a,"让 AI 改：例如“换成夜景，竖图”",false);agentInput.setTextSize(15);Ui.pad(agentInput,10);
        agentInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        agentInput.setOnEditorActionListener((v,action,e)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND){agentSend();return true;}return false;});
        ask.addView(agentInput,new LinearLayout.LayoutParams(0,Ui.dp(a,46),1));
        agentSend=Ui.button(a,"发送",false,this::agentSend);
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(-2,Ui.dp(a,46));sendParams.leftMargin=Ui.dp(a,6);ask.addView(agentSend,sendParams);
        agentBox.addView(ask);
        root.addView(agentBox);

        LinearLayout bottom=Ui.row(a);bottom.setPadding(0,Ui.dp(a,6),0,0);
        bottom.addView(Ui.button(a,"−",false,()->batch(-1)),new LinearLayout.LayoutParams(Ui.dp(a,44),Ui.dp(a,52)));
        count=Ui.text(a,"",14,Ui.TEXT);count.setGravity(Gravity.CENTER);count.setOnClickListener(v->a.chooseBatch(this::showBatch));
        bottom.addView(count,new LinearLayout.LayoutParams(Ui.dp(a,52),-2));
        bottom.addView(Ui.button(a,"+",false,()->batch(1)),new LinearLayout.LayoutParams(Ui.dp(a,44),Ui.dp(a,52)));
        generate=Ui.button(a,"开始生成",true,()->a.generate(false));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(0,Ui.dp(a,52),1);gp.leftMargin=Ui.dp(a,8);bottom.addView(generate,gp);
        stop=Ui.button(a,"停止",false,a::interrupt);stop.setEnabled(false);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,Ui.dp(a,52));sp.leftMargin=Ui.dp(a,8);bottom.addView(stop,sp);
        root.addView(bottom);
        showBatch();
    }
    View view(){return root;}
    private boolean wide(){return activity.isLandscape()||Ui.wide(activity);}
    void setTitle(String text){title.setText(text==null||text.isEmpty()?"简洁模式":text.replace("工作流：",""));}
    void progress(String label){status.setText(label);}
    /** Live progress from the editor: the running node, its sampling steps and how many jobs wait behind it. */
    void showProgress(boolean running,int value,int max,String node,int queued){
        if(!running){progressPanel.setVisibility(View.GONE);return;}
        progressPanel.setVisibility(View.VISIBLE);
        stage.setText(node==null||node.isEmpty()?"正在生成…":"正在执行："+node);
        if(max>0){bar.setIndeterminate(false);bar.setMax(max);bar.setProgress(Math.min(value,max));}else bar.setIndeterminate(true);
        String text=max>0?"第 "+value+" / "+max+" 步 · "+Math.round(100f*value/max)+"%":"等待电脑开始采样";
        if(queued>1)text+=" · 后面还有 "+(queued-1)+" 个任务";
        steps.setText(text);
    }
    void setBusy(boolean busy){generate.setEnabled(!busy);}
    void setRunning(boolean running){stop.setEnabled(running);if(running){resultLabel.setText("正在生成…");showProgress(true,0,0,null,0);}else progressPanel.setVisibility(View.GONE);}
    void showPreview(Bitmap bitmap){if(bitmap==null)return;result.setImageBitmap(bitmap);resultLabel.setText("");latest=null;}
    void orientationChanged(){activity.showPage(MainActivity.PAGE_SIMPLE);}
    boolean back(){return false;}
    private void batch(int delta){
        int n=Math.max(1,Math.min(100,activity.prefs().getInt("batch",1)+delta));activity.prefs().edit().putInt("batch",n).apply();showBatch();
    }
    private void showBatch(){count.setText(activity.prefs().getInt("batch",1)+" 次");}

    // ---------------- fields ----------------
    void reload(){
        EditorView editor=activity.editor();if(editor==null)return;
        editor.command("simple",new JSONObject(),r->{
            if(!r.optBoolean("ok")){
                status.setText("正在等待电脑编辑器加载…");
                if(++attempts<40)root.postDelayed(this::reload,1200);
                return;
            }
            attempts=0;source=r.optString("source");current=r.optJSONArray("fields");if(current==null)current=new JSONArray();
            render();loadLatest();loadPresets();
        });
    }
    private void render(){
        fields.removeAllViews();
        if(current.length()==0){Ui.add(fields,Ui.text(activity,"这个工作流里没有识别到常用参数。点右上角“⋯ → 选择显示的参数”自己挑选。",14,Ui.MUTED));return;}
        Ui.add(fields,presetRow());
        Ui.add(fields,Ui.text(activity,source.equals("workflow")?"显示的是此工作流保存的参数选择":"自动识别的关键参数 · 可在“⋯”中自定义",12,Ui.MUTED));
        JSONObject width=null,height=null;
        for(int i=0;i<current.length();i++){
            JSONObject f=current.optJSONObject(i);if(f==null)continue;
            Ui.add(fields,control(f));
            if(f.optString("name").equals("width"))width=f;
            if(f.optString("name").equals("height"))height=f;
            if(width!=null&&height!=null&&width.optJSONObject("target").optString("nodeId").equals(height.optJSONObject("target").optString("nodeId"))){Ui.add(fields,sizePresets(width,height));width=height=null;}
        }
    }
    private String labelOf(JSONObject f){
        String name=f.optString("name"),nice=NAMES.containsKey(name)?NAMES.get(name):f.optString("label",name);
        return MainActivity.roleLabel(f)+nice;
    }
    private View control(JSONObject f){
        LinearLayout card=Ui.column(activity);Ui.pad(card,10);card.setBackground(Ui.shape(activity,Ui.SURFACE));
        LinearLayout head=Ui.row(activity);
        TextView label=Ui.text(activity,labelOf(f),15,Ui.TEXT);label.setPadding(0,0,0,0);head.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView node=Ui.text(activity,f.optString("nodeTitle"),11,Ui.MUTED);node.setPadding(Ui.dp(activity,6),0,0,0);node.setSingleLine(true);node.setMaxWidth(Ui.dp(activity,160));
        node.setEllipsize(android.text.TextUtils.TruncateAt.END);head.addView(node);card.addView(head);
        Object value=f.opt("value");JSONArray options=f.optJSONArray("options");
        if(f.optString("name").equals("image")&&f.optString("nodeType").contains("LoadImage"))imageControl(card,f);
        else if(options!=null)comboControl(card,f,options);
        else if(value instanceof Boolean){
            Switch toggle=new Switch(activity);toggle.setChecked((Boolean)value);toggle.setText(((Boolean)value)?"开":"关");toggle.setTextColor(Ui.TEXT);
            toggle.setOnCheckedChangeListener((b,on)->{toggle.setText(on?"开":"关");write(f,on);});card.addView(toggle);
        }else if(value instanceof Number)numberControl(card,f,((Number)value).doubleValue());
        else{
            String text=f.optString("value");
            TextView preview=Ui.text(activity,text.isEmpty()?"（空）点此编辑":text,14,text.isEmpty()?Ui.MUTED:Ui.TEXT);preview.setMaxLines(4);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(preview);
            card.setOnClickListener(v->activity.openEdit(activity.editor(),f));
            card.addView(Ui.text(activity,"点击进入提示词编辑（词库、权重、收藏）",11,Ui.MUTED));
        }
        return card;
    }
    private void comboControl(LinearLayout card,JSONObject f,JSONArray options){
        boolean seedMode=f.optString("name").equals("control_after_generate");
        String shown=f.optString("value");if(seedMode&&SEED_MODES.containsKey(shown))shown=SEED_MODES.get(shown);
        Button pick=Ui.button(activity,shown,false,null);
        pick.setOnClickListener(v->{
            ArrayList<String> labels=new ArrayList<>();
            for(int i=0;i<options.length();i++){String o=options.optString(i);labels.add(seedMode&&SEED_MODES.containsKey(o)?SEED_MODES.get(o):o);}
            Ui.choose(activity,labelOf(f),labels,null,i->write(f,options.opt(i)));
        });
        card.addView(pick,new LinearLayout.LayoutParams(-1,Ui.dp(activity,46)));
    }
    private void numberControl(LinearLayout card,JSONObject f,double value){
        JSONObject n=f.optJSONObject("number");if(n==null)n=new JSONObject();
        boolean integer=n.optInt("precision",-1)==0;
        double step=n.optDouble("step",Double.NaN);if(!(step>0))step=integer?1:0.1;if(integer)step=Math.max(1,Math.round(step));
        final double stepSize=step;final JSONObject limits=n;
        LinearLayout row=Ui.row(activity);
        Button minus=Ui.button(activity,"−",false,()->write(f,clamp(value-stepSize,limits,integer)));
        Button shown=Ui.button(activity,format(value,integer,stepSize),false,()->typeNumber(f,value,limits,integer));
        Button plus=Ui.button(activity,"+",false,()->write(f,clamp(value+stepSize,limits,integer)));
        row.addView(minus,new LinearLayout.LayoutParams(Ui.dp(activity,52),Ui.dp(activity,46)));
        LinearLayout.LayoutParams mid=new LinearLayout.LayoutParams(0,Ui.dp(activity,46),1);mid.leftMargin=mid.rightMargin=Ui.dp(activity,6);row.addView(shown,mid);
        row.addView(plus,new LinearLayout.LayoutParams(Ui.dp(activity,52),Ui.dp(activity,46)));
        if(f.optString("name").contains("seed")){
            LinearLayout.LayoutParams r=new LinearLayout.LayoutParams(-2,Ui.dp(activity,46));r.leftMargin=Ui.dp(activity,6);
            row.addView(Ui.button(activity,"随机",false,()->write(f,clamp(Math.floor(Math.random()*Math.min(limits.optDouble("max",1e15),1e15)),limits,true))),r);
        }
        card.addView(row);
    }
    private static String format(double v,boolean integer,double step){
        if(integer)return String.valueOf((long)Math.round(v));
        int decimals=step>=1?0:step>=0.1?1:step>=0.01?2:3;
        return String.format(Locale.ROOT,"%."+decimals+"f",v);
    }
    private static Object clamp(double v,JSONObject limits,boolean integer){
        if(limits.has("min")&&!limits.isNull("min"))v=Math.max(v,limits.optDouble("min"));
        if(limits.has("max")&&!limits.isNull("max"))v=Math.min(v,limits.optDouble("max"));
        if(integer)return (long)Math.round(v);
        return Math.round(v*1000)/1000.0;
    }
    private void typeNumber(JSONObject f,double value,JSONObject limits,boolean integer){
        android.widget.EditText input=Ui.input(activity,"数值",false);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|(integer?0:android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(integer?String.valueOf((long)value):String.valueOf(value));input.setSelectAllOnFocus(true);
        String range=(limits.isNull("min")?"":"最小 "+limits.opt("min"))+(limits.isNull("max")?"":"  最大 "+limits.opt("max"));
        new AlertDialog.Builder(activity).setTitle(labelOf(f)).setMessage(range.trim().isEmpty()?null:range.trim()).setView(input).setPositiveButton("确定",(d,w)->{
            try{double v=Double.parseDouble(input.getText().toString().trim());if(!Double.isFinite(v))throw new NumberFormatException();write(f,integer?(Object)(long)Math.round(v):(Object)v);}
            catch(NumberFormatException e){activity.message("请输入有效数字");}
        }).setNegativeButton("取消",null).show();
    }
    private View sizePresets(JSONObject width,JSONObject height){
        LinearLayout box=Ui.column(activity);
        box.addView(Ui.text(activity,"常用尺寸",12,Ui.MUTED));
        android.widget.HorizontalScrollView scroller=new android.widget.HorizontalScrollView(activity);LinearLayout row=Ui.row(activity);scroller.addView(row);
        for(int[] s:SIZES){
            Button b=Ui.button(activity,s[0]+"×"+s[1],false,()->write(width,(long)s[0],()->write(height,(long)s[1],null)));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,Ui.dp(activity,44));p.rightMargin=Ui.dp(activity,6);row.addView(b,p);
        }
        box.addView(scroller);return box;
    }
    private void imageControl(LinearLayout card,JSONObject f){
        ImageView thumb=new ImageView(activity);thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);thumb.setBackgroundColor(Ui.BG);
        card.addView(thumb,new LinearLayout.LayoutParams(-1,Ui.dp(activity,160)));
        String name=f.optString("value");TextView caption=Ui.text(activity,name,12,Ui.MUTED);card.addView(caption);
        LinearLayout row=Ui.row(activity);
        Ui.weighted(row,Ui.button(activity,"相册 / 拍照",true,()->pickImage(f)));
        JSONArray options=f.optJSONArray("options");
        if(options!=null&&options.length()>0){View gap=new View(activity);row.addView(gap,new LinearLayout.LayoutParams(Ui.dp(activity,8),1));
            Ui.weighted(row,Ui.button(activity,"电脑上的图片",false,()->{ArrayList<String> l=new ArrayList<>();for(int i=0;i<options.length();i++)l.add(options.optString(i));Ui.choose(activity,"选择输入图片",l,null,i->write(f,options.optString(i)));}));}
        card.addView(row);
        if(name.isEmpty())return;
        // "name.png [output]" refers to a generated result used as input (see MainActivity.useAsInput).
        String plain=name,kind="input";java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(.*) \\[(output|temp|input)\\]$").matcher(name);
        if(m.matches()){plain=m.group(1);kind=m.group(2);}
        String sub=plain.contains("/")?plain.substring(0,plain.lastIndexOf('/')):"",file=plain.substring(plain.lastIndexOf('/')+1);final String type=kind;
        activity.images.execute(()->{
            try{Bitmap b=ResultsView.decode(activity.client().bytes(MediaRef.path(file,sub,type)+"&preview=webp",12*1024*1024),600);
                activity.runOnUiThread(()->thumb.setImageBitmap(b));}catch(Exception ignored){}
        });
    }
    private void pickImage(JSONObject f){
        imageTarget=f;if(picker!=null)picker.discard();
        picker=new PhotoPicker(activity,new String[]{"image/*"},false);
        try{activity.startActivityForResult(picker.intent,PICK_IMAGE);}catch(Exception e){activity.message("无法打开相册");}
    }
    void imagePicked(int resultCode,Intent data){
        Uri[] uris=picker==null?null:picker.result(resultCode,data);picker=null;
        JSONObject target=imageTarget;imageTarget=null;
        if(uris==null||uris.length==0||target==null)return;
        Uri uri=uris[0];status.setText("正在把图片传到电脑…");
        activity.worker.execute(()->{
            try{
                String display="ComfyMobile-"+System.currentTimeMillis()+".png";
                try(android.database.Cursor c=activity.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&c.getString(0)!=null)display=c.getString(0);}catch(Exception ignored){}
                String name=activity.client().uploadImage(activity,uri,display);
                activity.runOnUiThread(()->{status.setText("图片已上传："+name);write(target,name);});
            }catch(Exception e){activity.runOnUiThread(()->{status.setText("");activity.message("上传失败："+e.getMessage());});}
        });
    }
    private void write(JSONObject f,Object value){write(f,value,null);}
    private void write(JSONObject f,Object value,Runnable then){
        EditorView editor=activity.editor();if(editor==null)return;
        editor.command("widget",NodePanel.args("target",f.optJSONObject("target"),"value",value),r->{
            if(!r.optBoolean("ok")){activity.report(r);reload();return;}
            try{f.put("value",r.opt("value"));}catch(Exception ignored){}
            if(then!=null)then.run();else{render();activity.refreshWorkflowTitle();}
        });
    }

    // ---------------- options ----------------
    private void options(){
        String[] items={"选择显示的参数…","恢复自动识别","提示词编辑页","任务","图库","工作流（保存 / 打开）","刷新参数","参数预设…"};
        new AlertDialog.Builder(activity).setItems(items,(d,i)->{
            switch(i){
                case 0:customize();break;
                case 1:save(new JSONArray());break;
                case 2:activity.showPage(MainActivity.PAGE_PROMPT);break;
                case 3:activity.showPage(MainActivity.PAGE_TASKS);break;
                case 4:activity.showPage(MainActivity.PAGE_GALLERY);break;
                case 5:activity.workflowMenu();break;
                case 6:reload();break;
                case 7:managePresets();break;
            }
        }).show();
    }

    // ---------------- presets: named parameter sets saved in the workflow ----------------
    private void loadPresets(){
        activity.editor().command("presets",new JSONObject(),r->{if(r.optBoolean("ok")){presets=r.optJSONArray("presets");if(presets==null)presets=new JSONArray();render();}});
    }
    private View presetRow(){
        android.widget.HorizontalScrollView scroller=new android.widget.HorizontalScrollView(activity);scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row=Ui.row(activity);scroller.addView(row);
        for(int i=0;i<presets.length();i++){
            String name=presets.optJSONObject(i).optString("name");
            Button b=Ui.button(activity,name,false,()->applyPreset(name));
            b.setOnLongClickListener(v->{managePresets();return true;});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,Ui.dp(activity,44));p.rightMargin=Ui.dp(activity,6);row.addView(b,p);
        }
        row.addView(Ui.button(activity,"＋ 存为预设",false,this::savePreset),new LinearLayout.LayoutParams(-2,Ui.dp(activity,44)));
        return scroller;
    }
    private void savePreset(){
        android.widget.EditText input=Ui.input(activity,"例如：竖图人像",false);
        new AlertDialog.Builder(activity).setTitle("把当前参数存为预设").setMessage("保存尺寸、步数、采样器、模型等当前值；提示词和输入图片不放进预设。同名预设会被覆盖。保存工作流后，其他设备也能用。")
            .setView(input).setPositiveButton("保存",(d,w)->{
                JSONArray targets=new JSONArray();for(int i=0;i<current.length();i++)targets.put(current.optJSONObject(i).optJSONObject("target"));
                activity.editor().command("savePreset",NodePanel.args("name",input.getText().toString().trim(),"targets",targets),r->{
                    if(!r.optBoolean("ok")){activity.report(r);return;}
                    presets=r.optJSONArray("presets");render();activity.refreshWorkflowTitle();activity.message("预设已保存");
                });
            }).setNegativeButton("取消",null).show();
    }
    private void applyPreset(String name){
        activity.editor().command("applyPreset",NodePanel.args("name",name),r->{
            if(!r.optBoolean("ok")){activity.report(r);return;}
            activity.message("已应用“"+name+"”"+(r.optInt("skipped")>0?"，"+r.optInt("skipped")+" 项因节点已变化而跳过":""));
            activity.refreshWorkflowTitle();reload();
        });
    }
    private void managePresets(){
        if(presets.length()==0){savePreset();return;}
        String[] names=new String[presets.length()];for(int i=0;i<names.length;i++)names[i]=presets.optJSONObject(i).optString("name")+"（"+presets.optJSONObject(i).optInt("count")+" 项）";
        new AlertDialog.Builder(activity).setTitle("参数预设").setItems(names,(d,i)->{
            String name=presets.optJSONObject(i).optString("name");
            new AlertDialog.Builder(activity).setTitle(name).setItems(new String[]{"应用","删除"},(dd,w)->{
                if(w==0)applyPreset(name);
                else activity.editor().command("deletePreset",NodePanel.args("name",name),r->{if(r.optBoolean("ok")){presets=r.optJSONArray("presets");render();activity.refreshWorkflowTitle();}});
            }).show();
        }).setPositiveButton("存为新预设",(d,w)->savePreset()).setNegativeButton("关闭",null).show();
    }
    private void customize(){
        activity.editor().command("allFields",new JSONObject(),r->{
            JSONArray all=r.optJSONArray("fields");if(!r.optBoolean("ok")||all==null){activity.report(r);return;}
            String[] labels=new String[all.length()];boolean[] checked=new boolean[all.length()];
            for(int i=0;i<all.length();i++){
                JSONObject f=all.optJSONObject(i);labels[i]=f.optString("nodeTitle")+" · "+labelOf(f);
                for(int j=0;j<current.length();j++)if(same(f,current.optJSONObject(j)))checked[i]=true;
            }
            new AlertDialog.Builder(activity).setTitle("在简洁模式显示哪些参数").setMultiChoiceItems(labels,checked,(d,i,on)->checked[i]=on)
                .setPositiveButton("保存",(d,w)->{
                    JSONArray entries=new JSONArray();
                    for(int i=0;i<all.length();i++)if(checked[i]){JSONObject t=all.optJSONObject(i).optJSONObject("target");
                        entries.put(NodePanel.args("graphId",t.optString("graphId"),"nodeId",t.opt("nodeId"),"name",t.optString("widgetName")));}
                    save(entries);
                    activity.message("已保存到工作流；保存工作流文件后，电脑和 iPad 也会使用这个选择");
                }).setNegativeButton("取消",null).show();
        });
    }
    private static boolean same(JSONObject a,JSONObject b){
        if(a==null||b==null)return false;JSONObject x=a.optJSONObject("target"),y=b.optJSONObject("target");
        return x!=null&&y!=null&&x.optString("graphId").equals(y.optString("graphId"))&&x.optString("nodeId").equals(y.optString("nodeId"))&&x.optString("widgetName").equals(y.optString("widgetName"));
    }
    private void save(JSONArray entries){
        activity.editor().command("setSimple",NodePanel.args("entries",entries),r->{
            if(!r.optBoolean("ok")){activity.report(r);return;}
            source=r.optString("source");current=r.optJSONArray("fields");if(current==null)current=new JSONArray();render();activity.refreshWorkflowTitle();
        });
    }

    // ---------------- AI assistant ----------------
    private void agentSend(){
        EditorView editor=activity.editor();if(editor==null)return;
        if(agentBusy){editor.command("agent",NodePanel.args("action","stop"),r->{if(!r.optBoolean("ok"))activity.report(r);});return;}
        String text=agentInput.getText().toString().trim();
        if(text.isEmpty()){activity.message("先写下想让 AI 做什么");return;}
        agentSend.setEnabled(false);
        editor.command("agent",NodePanel.args("action","send","text",text),r->{
            agentSend.setEnabled(true);
            if(!r.optBoolean("ok")){activity.report(r);return;}
            agentInput.setText("");Ui.hideKeyboard(activity,agentInput);activity.askNotifyOnce();
            setAgentBusy(true);showAgentStep("AI 正在思考…");
        });
    }
    /** Busy state from the page: the send button stops the AI while it works. */
    void setAgentBusy(boolean busy){
        if(busy==agentBusy)return;agentBusy=busy;agentSend.setText(busy?"停止":"发送");
        if(!busy)agentStep.setVisibility(View.GONE);
    }
    /** One chat event from AgentLink (see the adapter's "agent" events). */
    void agentEvent(JSONObject e){
        String name=e.optString("name");
        switch(e.optString("type")){
            case "tool_call":showAgentStep("AI："+(AGENT_STEPS.containsKey(name)?AGENT_STEPS.get(name):"处理中")+"…");break;
            case "tool_result":if(!e.optBoolean("is_error")&&AGENT_EDITS.contains(name))reload();break;
            case "text":showAgentReply(e.optString("text"));break;
            case "error":showAgentReply("出错了："+e.optString("message"));break;
            case "done":if(e.optBoolean("stopped"))showAgentStep("已停止");else agentStep.setVisibility(View.GONE);break;
        }
    }
    private void showAgentStep(String text){agentStep.setText(text);agentStep.setVisibility(View.VISIBLE);}
    private void showAgentReply(String text){
        if(text==null||text.trim().isEmpty())return;
        agentReply.setText(text.trim()+"\n（点此打开完整对话）");agentReply.setVisibility(View.VISIBLE);
    }

    // ---------------- latest result ----------------
    void completed(){status.setText("生成完成");loadLatest();}
    private void loadLatest(){
        ComfyClient client=activity.client();if(client==null)return;
        activity.worker.execute(()->{
            try{
                JSONArray jobs=client.json("/api/jobs?limit=1&status=completed").optJSONArray("jobs");
                JSONObject job=jobs==null?null:jobs.optJSONObject(0),output=job==null?null:job.optJSONObject("preview_output");
                if(output==null||!output.has("filename"))return;
                String path=MediaRef.path(output.optString("filename"),output.optString("subfolder"),output.optString("type","output"));
                boolean video=MediaRef.isVideo(output.optString("filename"));
                Bitmap b=video?null:ResultsView.decode(client.bytes(path+"&preview=webp",20*1024*1024),1400);
                activity.runOnUiThread(()->{
                    latest=output;
                    if(b!=null){result.setImageBitmap(b);resultLabel.setText("");}
                    else{result.setImageDrawable(null);resultLabel.setText("最新结果是视频，点击播放");}
                });
            }catch(Exception ignored){}
        });
    }
    private void openResult(){
        if(latest==null){activity.showLivePreview();return;}
        ResultsView.open(activity,latest);
    }
}
