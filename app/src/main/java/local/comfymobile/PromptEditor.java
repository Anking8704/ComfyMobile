package local.comfymobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

final class PromptEditor {
    private final MainActivity activity;
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final EditText text;
    private final TextView targetLabel;
    private final Button rotate;
    private PromptLibrary builtin;
    private final ArrayDeque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private JSONObject target;
    private String last="", fieldName="未选择字段";
    private final DraftState draftState;
    private boolean replacing=false;
    private int selection=0,selectionEnd=0;
    private long lastTypedAt=0;
    private boolean syncing=false;
    private static final String LIBRARY_FILE="/userdata/comfymobile%2Flibrary.json";
    private static final String[] SYNCED={"favorites","templates","history"};

    PromptEditor(MainActivity a) {
        activity=a;prefs=a.getSharedPreferences("prompts",0);root=Ui.column(a);Ui.pad(root,8);
        LinearLayout top=Ui.row(a);
        top.addView(Ui.button(a,"返回",false,a::leaveSubpage),new LinearLayout.LayoutParams(Ui.dp(a,64),Ui.dp(a,48)));
        targetLabel=Ui.text(a,"选择节点文字字段",13,Ui.MUTED);Ui.weighted(top,targetLabel);
        targetLabel.setSingleLine(true);targetLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        targetLabel.setOnClickListener(v->chooseField());targetLabel.setContentDescription("选择节点文字字段");
        rotate=Ui.button(a,"横屏",false,a::toggleOrientation);top.addView(rotate,new LinearLayout.LayoutParams(Ui.dp(a,64),Ui.dp(a,48)));
        Ui.add(root,top);
        text=Ui.input(a,"先选择工作流中的文字字段，或在这里准备草稿",true);
        text.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        text.setTextSize(17);text.setSelectAllOnFocus(false);text.setGravity(Gravity.TOP|Gravity.START);
        root.addView(text,new LinearLayout.LayoutParams(-1,0,1));
        String draft=prefs.getString("draft","");draftState=new DraftState(draft);text.setText(draft);last=draft;
        if(!draft.isEmpty()){fieldName=prefs.getString("draft_label","未绑定字段");targetLabel.setText("手机草稿 · 上次："+fieldName);}
        text.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
            @Override public void afterTextChanged(Editable e){
                if(!replacing && !last.equals(e.toString())){
                    // Typing within a second is one undo step, so undo does not go back one character at a time.
                    long now=android.os.SystemClock.uptimeMillis();
                    if(now-lastTypedAt>1000||undo.isEmpty()){undo.push(last);while(undo.size()>80)undo.removeLast();}
                    lastTypedAt=now;redo.clear();
                }
                last=e.toString();persist();
            }
        });
        LinearLayout compact=Ui.row(a);
        Ui.weighted(compact,Ui.button(a,"英文词库",false,()->library("英文词库","favorites",false)));
        Ui.weighted(compact,Ui.button(a,"标签",false,this::tagEditor));
        Ui.weighted(compact,Ui.button(a,"工具",false,()->new AlertDialog.Builder(a).setItems(new String[]{"选择节点字段","英文模板","历史","撤销","重做","权重 −（光标所在词）","权重 +（光标所在词）"},(d,i)->{switch(i){case 0:chooseField();break;case 1:library("英文模板","templates",true);break;case 2:library("历史","history",true);break;case 3:history(false);break;case 4:history(true);break;case 5:weight(-.1);break;case 6:weight(.1);break;}}).show()));
        Ui.weighted(compact,Ui.button(a,"保存到节点",true,this::save));Ui.add(root,compact);
        try{builtin=new PromptLibrary(a);}catch(Exception e){a.message("内置词库读取失败，仍可使用自己的收藏和模板");}
        orientationChanged();
    }

    View view(){return root;}
    boolean dirty(){return draftState.dirty(text.getText().toString());}
    void focus(){
        text.requestFocus();
        Runnable show=()->text.post(()->{
            if(!text.isShown()||!text.hasFocus()||!text.hasWindowFocus())return;
            ((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(text,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        if(text.hasWindowFocus())show.run();
        else text.getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener(){
            public void onWindowFocusChanged(boolean focused){if(focused){if(text.getViewTreeObserver().isAlive())text.getViewTreeObserver().removeOnWindowFocusChangeListener(this);show.run();}}
        });
    }
    void orientationChanged(){rotate.setText(activity.isLandscape()?"竖屏":"横屏");}
    void persist(){prefs.edit().putString("draft",text.getText().toString()).putString("draft_label",fieldName).apply();}
    void resetTarget(){target=null;targetLabel.setText("连接已更换 · 草稿已保留，请重新选择字段");}

    void chooseField(){
        if(activity.editor()==null){activity.message("请先连接电脑");return;}
        Ui.hideKeyboard(activity,text);
        activity.editor().call("fields",new JSONArray(),r->{
            if(!r.optBoolean("ok")){activity.message(r.optString("error"));return;}
            JSONArray fields=r.optJSONArray("fields");
            if(fields==null||fields.length()==0){activity.message("当前工作流没有可识别的标准文字字段；特殊插件请用原编辑器");return;}
            LinearLayout content=Ui.column(activity);Ui.pad(content,14);
            EditText search=Ui.input(activity,"搜索节点或字段",false);Ui.add(content,search);
            LinearLayout list=Ui.column(activity);
            ScrollView scroll=Ui.scroll(activity,list);content.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.listHeight(activity,420)));
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("选择实际文字字段").setView(content).setNegativeButton("取消",null).create();
            Runnable render=()->{
                list.removeAllViews();String filter=search.getText().toString().toLowerCase(java.util.Locale.ROOT);
                for(int i=0;i<fields.length();i++){
                    JSONObject f=fields.optJSONObject(i);if(f==null)continue;
                    String label=f.optString("nodeTitle")+" · #"+f.optJSONObject("target").optString("nodeId")+" / "+f.optString("name");
                    if(!label.toLowerCase(java.util.Locale.ROOT).contains(filter))continue;
                    Ui.add(list,Ui.button(activity,label,false,()->{
                        dialog.dismiss();openField(f);
                    }));
                }
            };
            search.addTextChangedListener(new SimpleWatcher(render));render.run();dialog.show();
        });
    }

    void openField(JSONObject field){
        JSONObject destination=field.optJSONObject("target");if(destination==null)return;
        if(target!=null&&target.optString("token").equals(destination.optString("token"))&&target.optString("session").equals(destination.optString("session"))&&dirty()){focus();return;}
        String label=MainActivity.roleLabel(field)+field.optString("nodeTitle")+" · #"+destination.optString("nodeId")+" / "+field.optString("name");
        Runnable bind=()->{target=destination;fieldName=label;targetLabel.setText(label);targetLabel.setContentDescription(label);draftState.bind(field.optString("value"));persist();focus();};
        Runnable load=()->{replace(field.optString("value"));bind.run();undo.clear();redo.clear();};
        if(dirty()&&!text.getText().toString().equals(field.optString("value")))new AlertDialog.Builder(activity).setMessage("保留手机草稿并绑定此字段，还是载入节点当前文字？")
            .setPositiveButton("保留草稿并绑定",(d,w)->bind.run()).setNeutralButton("载入节点文字",(d,w)->load.run()).setNegativeButton("取消",null).show();
        else load.run();
    }

    private void replace(String value){replacing=true;text.setText(value);text.setSelection(text.length());last=value;replacing=false;persist();}
    private void history(boolean forward){
        ArrayDeque<String> from=forward?redo:undo,to=forward?undo:redo;
        if(from.isEmpty())return;to.push(text.getText().toString());replace(from.pop());
    }
    private void weight(double delta){
        int start=text.getSelectionStart(),end=text.getSelectionEnd();
        if(start==end){
            int[] span=PromptText.phraseAt(text.getText().toString(),start);
            if(span==null){activity.message("把光标放在要调整的词上，或长按选中词句");return;}
            start=span[0];end=span[1];
        }
        PromptText.Edit edit=PromptText.weight(text.getText().toString(),Math.min(start,end),Math.max(start,end),delta);
        String old=text.getText().toString();undo.push(old);redo.clear();replace(edit.text);text.setSelection(edit.start,edit.end);
    }
    private void insert(String value,boolean replaceAll){
        undo.push(text.getText().toString());redo.clear();
        if(replaceAll)replace(value);
        else{PromptText.Edit edit=PromptText.insert(text.getText().toString(),selection,selectionEnd,value);replace(edit.text);text.setSelection(edit.start,edit.end);}
        focus();
    }
    private JSONArray items(String key){
        try{
            JSONArray values=new JSONArray(prefs.getString(key,"[]"));boolean changed=false;
            if(!key.equals("history"))for(int i=0;i<values.length();i++){String old=values.optString(i),next=PresetMigration.translate(old);if(!old.equals(next)){values.put(i,next);changed=true;}}
            if(changed)prefs.edit().putString(key,values.toString()).apply();return values;
        }catch(Exception e){return new JSONArray();}
    }
    private void remember(String key,String value){
        JSONArray old=items(key),next=new JSONArray().put(value);
        for(int i=0;i<old.length()&&next.length()<60;i++)if(!value.equals(old.optString(i)))next.put(old.optString(i));
        prefs.edit().putString(key,next.toString()).apply();sync(null);
    }
    private void library(String title,String key,boolean replaceAll){
        selection=Math.max(0,Math.min(text.getSelectionStart(),text.getSelectionEnd()));selectionEnd=Math.max(selection,Math.max(text.getSelectionStart(),text.getSelectionEnd()));Ui.hideKeyboard(activity,text);
        JSONArray values=items(key);java.util.ArrayList<PromptLibrary.Entry> entries=new java.util.ArrayList<>();
        if(builtin!=null){if(key.equals("favorites"))entries.addAll(builtin.terms);else if(key.equals("templates"))entries.addAll(builtin.templates);}
        for(int i=0;i<values.length();i++){
            String item=values.optString(i);int split=item.indexOf(" / ");
            entries.add(new PromptLibrary.Entry(split>=0?item.substring(0,split):item,split>=0?item.substring(split+3):item,"","我的收藏","","","",i));
        }
        Dialog dialog=new Dialog(activity);LinearLayout outer=Ui.column(activity);outer.setBackgroundColor(Ui.BG);Ui.applyInsets(outer);
        LinearLayout content=Ui.column(activity);Ui.pad(content,8);outer.addView(content,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout heading=Ui.row(activity);heading.addView(Ui.title(activity,title),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(Ui.button(activity,"关闭",false,dialog::dismiss));Ui.add(content,heading);
        EditText search=Ui.input(activity,"中英文搜索，例如 光线 / lighting",false);Ui.add(content,search);
        java.util.LinkedHashSet<String> categoryNames=new java.util.LinkedHashSet<>();categoryNames.add("全部分类");for(PromptLibrary.Entry entry:entries)categoryNames.add(entry.category);
        java.util.ArrayList<String> categories=new java.util.ArrayList<>(categoryNames);Spinner category=new Spinner(activity);
        ArrayAdapter<String> categoryAdapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,categories);category.setAdapter(categoryAdapter);Ui.add(content,category);
        java.util.ArrayList<PromptLibrary.Entry> visible=new java.util.ArrayList<>();
        ArrayAdapter<PromptLibrary.Entry> adapter=new ArrayAdapter<PromptLibrary.Entry>(activity,android.R.layout.simple_list_item_2,android.R.id.text1,visible){
            @Override public View getView(int position,View reusable,android.view.ViewGroup parent){
                View row=super.getView(position,reusable,parent);PromptLibrary.Entry entry=getItem(position);
                TextView label=row.findViewById(android.R.id.text1),english=row.findViewById(android.R.id.text2);
                label.setText(entry.category+" / "+entry.label+(!entry.translation.isEmpty()&&!entry.translation.equals(entry.label)?"\n"+entry.translation:""));label.setTextColor(Ui.TEXT);label.setTextSize(15);label.setMaxLines(4);
                english.setText(entry.text);english.setTextColor(Ui.MUTED);english.setTextSize(14);english.setMaxLines(3);return row;
            }
        };
        ListView list=new ListView(activity);list.setAdapter(adapter);list.setItemsCanFocus(false);list.setFocusable(false);content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView count=Ui.text(activity,"",12,Ui.MUTED);Ui.add(content,count);
        Runnable render=()->{
            String query=search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT),group=categories.get(Math.max(0,category.getSelectedItemPosition()));
            visible.clear();for(PromptLibrary.Entry entry:entries){String corpus=(entry.label+" "+entry.text+" "+entry.translation+" "+entry.category+" "+entry.keywords).toLowerCase(java.util.Locale.ROOT);
                if((group.equals("全部分类")||group.equals(entry.category))&&(query.isEmpty()||corpus.contains(query)))visible.add(entry);}
            adapter.notifyDataSetChanged();count.setText(visible.size()+" 项 · 点选应用英文，长按查看说明或编辑收藏");
        };
        search.addTextChangedListener(new SimpleWatcher(render));category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){render.run();}public void onNothingSelected(AdapterView<?> parent){}
        });
        list.setOnItemClickListener((parent,view,index,id)->{
            PromptLibrary.Entry entry=visible.get(index);dialog.dismiss();
            if(replaceAll&&dirty())new AlertDialog.Builder(activity).setMessage("用此英文模板替换当前草稿？").setPositiveButton("替换",(d,w)->insert(entry.text,true)).setNegativeButton("取消",null).show();
            else insert(entry.text,replaceAll);
        });
        list.setOnItemLongClickListener((parent,view,index,id)->{
            PromptLibrary.Entry entry=visible.get(index);
            if(entry.savedIndex>=0){dialog.dismiss();editLibraryItem(key,entry.savedIndex,values);}
            else new AlertDialog.Builder(activity).setTitle(entry.label).setMessage("中文：\n"+entry.translation+"\n\n英文（点击应用时插入）：\n"+entry.text+(entry.note.isEmpty()?"":"\n\n"+entry.note)+(entry.negative.isEmpty()?"":"\n\n可选负面词（不会自动写入其他节点）：\n"+entry.negative))
                .setPositiveButton("加入我的收藏",(d,w)->{remember(key,entry.label+" / "+entry.text);activity.message("已收藏");})
                .setNeutralButton(entry.negative.isEmpty()?null:"写入反向字段…",(d,w)->{dialog.dismiss();writeNegative(entry.negative);}).setNegativeButton("关闭",null).show();
            return true;
        });
        LinearLayout actions=Ui.row(activity);
        Ui.weighted(actions,Ui.button(activity,"收藏当前文字",false,()->{
            String value=text.getText().toString();if(value.trim().isEmpty()){activity.message("先输入文字");return;}
            EditText name=Ui.input(activity,"分类或模板名称",false);new AlertDialog.Builder(activity).setTitle("保存我的收藏").setView(name).setPositiveButton("保存",(d,w)->{remember(key,name.getText()+" / "+value);activity.message("已保存，下次打开可见");}).setNegativeButton("取消",null).show();
        }));
        Ui.weighted(actions,Ui.button(activity,"新增词句",false,()->{dialog.dismiss();editLibraryItem(key,-1,values);}));Ui.add(content,actions);
        dialog.setContentView(outer);dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);if(android.os.Build.VERSION.SDK_INT>=30)dialog.getWindow().setDecorFitsSystemWindows(false);}
        render.run();
    }
    private void editLibraryItem(String key,int index,JSONArray values){
        String original=index>=0?values.optString(index):null;
        EditText input=Ui.input(activity,"分类 / 词句内容",true);if(original!=null)input.setText(original);
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle("编辑收藏").setView(input)
            .setPositiveButton("保存",(d,w)->{
                String value=input.getText().toString().trim();if(value.isEmpty())return;
                updateSavedItem(key,original,value);
            }).setNegativeButton("取消",null);
        if(original!=null)dialog.setNeutralButton("删除",(d,w)->updateSavedItem(key,original,null));dialog.show();
    }
    private void updateSavedItem(String key,String original,String replacement){
        JSONArray current=items(key);java.util.ArrayList<String> latest=new java.util.ArrayList<>();for(int i=0;i<current.length();i++)latest.add(current.optString(i));
        try{
            java.util.List<String> edited=PresetList.edit(latest,original,replacement);JSONArray next=new JSONArray();for(String value:edited)next.put(value);
            prefs.edit().putString(key,next.toString()).apply();sync(null);
        }catch(IllegalStateException e){activity.message(e.getMessage());}
    }
    private void save(){
        persist();if(target==null){activity.message("请先选择工作流中的目标字段，草稿已经保存在手机");return;}
        if(activity.editor()==null){activity.message("请先连接电脑，草稿已经保留");return;}
        final String value=text.getText().toString();
        activity.editor().call("writeField",new JSONArray().put(target).put(value),r->{
            if(!r.optBoolean("ok")){activity.message(r.optString("error"));return;}
            draftState.saved(value);remember("history",value);activity.message("已写回节点；工作流可在编辑器中保存或导出");
        });
    }
    /** Writes a template's suggested negative prompt into a negative field the user picks; nothing is written without that choice. */
    private void writeNegative(String negative){
        if(activity.editor()==null){activity.message("请先连接电脑");return;}
        activity.editor().call("fields",new JSONArray(),r->{
            JSONArray all=r.optJSONArray("fields");if(!r.optBoolean("ok")||all==null||all.length()==0){activity.message("没有可写入的文字字段");return;}
            java.util.ArrayList<JSONObject> list=new java.util.ArrayList<>();
            for(int i=0;i<all.length();i++)if("negative".equals(all.optJSONObject(i).optString("role")))list.add(all.optJSONObject(i));
            if(list.isEmpty())for(int i=0;i<all.length();i++)list.add(all.optJSONObject(i));
            java.util.ArrayList<String> labels=new java.util.ArrayList<>(),details=new java.util.ArrayList<>();
            for(JSONObject f:list){labels.add(MainActivity.roleLabel(f)+f.optString("nodeTitle")+" / "+f.optString("name"));String v=f.optString("value");details.add(v.length()>60?v.substring(0,60)+"…":v);}
            Ui.choose(activity,"写入哪个反向字段？",labels,details,i->{
                JSONObject f=list.get(i);
                new AlertDialog.Builder(activity).setMessage("用模板的反向词替换该字段当前内容？\n\n"+negative).setPositiveButton("替换",(d,w)->
                    activity.editor().call("writeField",new JSONArray().put(f.optJSONObject("target")).put(negative),res->activity.message(res.optBoolean("ok")?"已写入反向字段":res.optString("error"))))
                    .setNegativeButton("取消",null).show();
            });
        });
    }
    /** Shows the prompt as tags: tap one to raise or lower its weight, move it or delete it. */
    private void tagEditor(){
        Dialog dialog=new Dialog(activity);LinearLayout box=Ui.column(activity);Ui.pad(box,12);box.setBackgroundColor(Ui.BG);
        box.addView(Ui.title(activity,"标签视图"));
        box.addView(Ui.text(activity,"点标签：调权重、左右移动、删除。改动直接作用于草稿，可撤销。",13,Ui.MUTED));
        FlowLayout flow=new FlowLayout(activity);
        android.widget.ScrollView scroll=Ui.scroll(activity,flow);box.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.listHeight(activity,520)));
        box.addView(Ui.button(activity,"完成",true,dialog::dismiss));
        Runnable[] render=new Runnable[1];
        render[0]=()->{
            flow.removeAllViews();String value=text.getText().toString();java.util.List<int[]> spans=PromptText.tags(value);
            if(spans.isEmpty())flow.addView(Ui.text(activity,"还没有内容",14,Ui.MUTED));
            for(int i=0;i<spans.size();i++){
                int index=i;int[] span=spans.get(i);
                Button chip=Ui.button(activity,value.substring(span[0],span[1]),false,()->{
                    String current=text.getText().toString();java.util.List<int[]> now=PromptText.tags(current);if(index>=now.size())return;int[] at=now.get(index);
                    new AlertDialog.Builder(activity).setTitle(current.substring(at[0],at[1])).setItems(new String[]{"权重 +0.1","权重 −0.1","向左移","向右移","删除"},(d,w)->{
                        PromptText.Edit edit=null;
                        if(w==0)edit=PromptText.weight(current,at[0],at[1],.1);
                        if(w==1)edit=PromptText.weight(current,at[0],at[1],-.1);
                        if(w==2&&index>0)edit=PromptText.swap(current,at,now.get(index-1));
                        if(w==3&&index<now.size()-1)edit=PromptText.swap(current,at,now.get(index+1));
                        if(w==4)edit=PromptText.remove(current,at[0],at[1]);
                        if(edit==null)return;undo.push(current);redo.clear();replace(edit.text);render[0].run();
                    }).show();
                });
                chip.setAllCaps(false);chip.setMaxLines(2);flow.addView(chip);
            }
        };
        render[0].run();dialog.setContentView(box);dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-2);
    }
    /** Keeps favorites, templates and history the same on every phone and iPad that uses this computer. */
    void sync(Runnable after){
        ComfyClient client=activity.client();if(client==null||syncing)return;syncing=true;
        java.util.HashMap<String,java.util.List<String>> start=new java.util.HashMap<>();for(String key:SYNCED)start.put(key,list(items(key)));
        activity.worker.execute(()->{
            JSONObject next=new JSONObject();boolean ok=false;
            try{
                JSONObject remote;
                try{remote=client.json(LIBRARY_FILE);}catch(java.io.IOException e){if(String.valueOf(e.getMessage()).contains("404"))remote=new JSONObject();else throw e;}
                for(String key:SYNCED){
                    java.util.List<String> merged=LibraryMerge.merge(list(remote.optJSONArray(key)),start.get(key),list(prefs.getString("synced_"+key,"[]")),60);
                    next.put(key,new JSONArray(merged));
                }
                if(!next.toString().equals(remote.toString()))client.post(LIBRARY_FILE+"?overwrite=true",next);
                ok=true;
            }catch(Exception ignored){}
            boolean done=ok;
            activity.runOnUiThread(()->{
                syncing=false;if(!done)return;
                SharedPreferences.Editor edit=prefs.edit();boolean changedMeanwhile=false;
                for(String key:SYNCED){
                    if(!list(items(key)).equals(start.get(key))){changedMeanwhile=true;continue;}
                    JSONArray merged=next.optJSONArray(key);edit.putString(key,merged.toString()).putString("synced_"+key,merged.toString());
                }
                edit.apply();if(changedMeanwhile)sync(after);else if(after!=null)after.run();
            });
        });
    }
    private static java.util.List<String> list(JSONArray a){java.util.ArrayList<String> l=new java.util.ArrayList<>();for(int i=0;a!=null&&i<a.length();i++)l.add(a.optString(i));return l;}
    private static java.util.List<String> list(String json){try{return list(new JSONArray(json));}catch(Exception e){return new java.util.ArrayList<>();}}
    static class SimpleWatcher implements TextWatcher {
        private final Runnable changed;SimpleWatcher(Runnable r){changed=r;}
        public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){}
        public void afterTextChanged(Editable e){changed.run();}
    }
}
