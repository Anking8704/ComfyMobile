package local.comfymobile;

import android.app.AlertDialog;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

final class PromptEditor {
    private final MainActivity activity;
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final EditText text;
    private final TextView targetLabel, counter;
    private final ArrayDeque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private JSONObject target;
    private String last="", saved="", fieldName="未选择字段";
    private boolean replacing=false;
    private int selection=0;

    PromptEditor(MainActivity a) {
        activity=a;prefs=a.getSharedPreferences("prompts",0);root=Ui.column(a);Ui.pad(root,14);
        Ui.add(root,Ui.title(a,"提示词编辑"));
        targetLabel=Ui.text(a,"未绑定字段 · 草稿仅存手机",13,Ui.MUTED);Ui.add(root,targetLabel);
        Ui.add(root,Ui.button(a,"选择节点文字字段",false,this::chooseField));
        text=Ui.input(a,"先选择工作流中的文字字段，或在这里准备草稿",true);
        text.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        text.setTextSize(17);text.setSelectAllOnFocus(false);text.setGravity(Gravity.TOP|Gravity.START);
        root.addView(text,new LinearLayout.LayoutParams(-1,0,1));
        String draft=prefs.getString("draft","");text.setText(draft);last=draft;saved=draft;
        counter=Ui.text(a,"草稿保存在手机",12,Ui.MUTED);Ui.add(root,counter);
        text.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
            @Override public void afterTextChanged(Editable e){
                if(!replacing && !last.equals(e.toString())){undo.push(last);while(undo.size()>80)undo.removeLast();redo.clear();}
                last=e.toString();counter.setText(e.length()+" 字 · 草稿仅存手机");persist();
            }
        });
        LinearLayout tools=Ui.row(a);
        Ui.weighted(tools,Ui.button(a,"撤销",false,()->history(false)));
        Ui.weighted(tools,Ui.button(a,"重做",false,()->history(true)));
        Ui.weighted(tools,Ui.button(a,"权重 −",false,()->weight(-.1)));
        Ui.weighted(tools,Ui.button(a,"权重 +",false,()->weight(.1)));
        Ui.add(root,tools);
        LinearLayout library=Ui.row(a);
        Ui.weighted(library,Ui.button(a,"常用词",false,()->library("常用词","favorites",false)));
        Ui.weighted(library,Ui.button(a,"模板",false,()->library("模板","templates",true)));
        Ui.weighted(library,Ui.button(a,"历史",false,()->library("历史","history",true)));
        Ui.add(root,library);
        Ui.add(root,Ui.button(a,"保存到节点",true,this::save));
    }

    View view(){return root;}
    boolean dirty(){return !saved.equals(text.getText().toString());}
    void focus(){text.requestFocus();}
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
            ScrollView scroll=Ui.scroll(activity,list);content.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(activity,330)));
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("选择实际文字字段").setView(content).setNegativeButton("取消",null).create();
            Runnable render=()->{
                list.removeAllViews();String filter=search.getText().toString().toLowerCase(java.util.Locale.ROOT);
                for(int i=0;i<fields.length();i++){
                    JSONObject f=fields.optJSONObject(i);if(f==null)continue;
                    String label=f.optString("nodeTitle")+" · #"+f.optJSONObject("target").optString("nodeId")+" / "+f.optString("name");
                    if(!label.toLowerCase(java.util.Locale.ROOT).contains(filter))continue;
                    Ui.add(list,Ui.button(activity,label,false,()->{
                        Runnable load=()->{target=f.optJSONObject("target");fieldName=label;targetLabel.setText(label);replace(f.optString("value"));saved=text.getText().toString();undo.clear();redo.clear();dialog.dismiss();focus();};
                        if(dirty())new AlertDialog.Builder(activity).setMessage("手机草稿将被所选字段内容替换。先收藏或复制可保留草稿。").setPositiveButton("载入字段",(d,w)->load.run()).setNegativeButton("取消",null).show();
                        else load.run();
                    }));
                }
            };
            search.addTextChangedListener(new SimpleWatcher(render));render.run();dialog.show();
        });
    }

    private void replace(String value){replacing=true;text.setText(value);text.setSelection(text.length());last=value;replacing=false;persist();}
    private void history(boolean forward){
        ArrayDeque<String> from=forward?redo:undo,to=forward?undo:redo;
        if(from.isEmpty())return;to.push(text.getText().toString());replace(from.pop());
    }
    private void weight(double delta){
        int start=text.getSelectionStart(),end=text.getSelectionEnd();
        if(start==end){activity.message("先长按选择需要调整权重的词句");return;}
        PromptText.Edit edit=PromptText.weight(text.getText().toString(),Math.min(start,end),Math.max(start,end),delta);
        String old=text.getText().toString();undo.push(old);redo.clear();replace(edit.text);text.setSelection(edit.start,edit.end);
    }
    private void insert(String value,boolean replaceAll){
        if(replaceAll){undo.push(text.getText().toString());redo.clear();replace(value);return;}
        int start=Math.max(0,Math.min(selection,text.length()));text.getText().insert(start,value);text.setSelection(start+value.length());
    }
    private JSONArray items(String key){
        try{
            String fallback=key.equals("favorites")?"[\"画质 / 高细节\",\"光影 / 柔和光线\",\"光影 / 电影光影\"]":key.equals("templates")?"[\"人像 / 银白长发，白色礼裙，月光花园，柔和光线\"]":"[]";
            return new JSONArray(prefs.getString(key,fallback));
        }catch(Exception e){return new JSONArray();}
    }
    private void remember(String key,String value){
        JSONArray old=items(key),next=new JSONArray().put(value);
        for(int i=0;i<old.length()&&next.length()<60;i++)if(!value.equals(old.optString(i)))next.put(old.optString(i));
        prefs.edit().putString(key,next.toString()).apply();
    }
    private void library(String title,String key,boolean replaceAll){
        selection=Math.max(0,text.getSelectionStart());Ui.hideKeyboard(activity,text);
        JSONArray values=items(key);String[] labels=new String[values.length()];for(int i=0;i<labels.length;i++)labels[i]=values.optString(i);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title+" · 长按可编辑")
            .setItems(labels,(d,index)->{
                String item=labels[index],value=item.contains(" / ")?item.substring(item.indexOf(" / ")+3):item;
                if(replaceAll && dirty())new AlertDialog.Builder(activity).setMessage("用选中内容替换当前草稿？").setPositiveButton("替换",(dd,w)->insert(value,true)).setNegativeButton("取消",null).show();
                else insert(value,replaceAll);
            }).setPositiveButton("收藏当前文字",(d,w)->{
                String value=text.getText().toString();if(value.trim().isEmpty()){activity.message("先输入文字");return;}
                EditText category=Ui.input(activity,"分类或模板名称",false);
                new AlertDialog.Builder(activity).setTitle("保存到"+title).setView(category).setPositiveButton("保存",(dd,ww)->remember(key,category.getText()+" / "+value)).setNegativeButton("取消",null).show();
            }).setNeutralButton("新增词句",(d,w)->editLibraryItem(key,-1,values)).setNegativeButton("关闭",null).create();
        dialog.setOnShowListener(d->dialog.getListView().setOnItemLongClickListener((parent,view,index,id)->{dialog.dismiss();editLibraryItem(key,index,values);return true;}));dialog.show();
    }
    private void editLibraryItem(String key,int index,JSONArray values){
        EditText input=Ui.input(activity,"分类 / 词句内容",true);if(index>=0)input.setText(values.optString(index));
        new AlertDialog.Builder(activity).setTitle("编辑收藏").setView(input)
            .setPositiveButton("保存",(d,w)->{
                String value=input.getText().toString().trim();if(value.isEmpty())return;
                JSONArray next=new JSONArray();for(int i=0;i<values.length();i++)next.put(i==index?value:values.optString(i));if(index<0)next.put(value);
                prefs.edit().putString(key,next.toString()).apply();
            }).setNeutralButton("删除",(d,w)->{JSONArray next=new JSONArray();for(int i=0;i<values.length();i++)if(i!=index)next.put(values.optString(i));prefs.edit().putString(key,next.toString()).apply();})
            .setNegativeButton("取消",null).show();
    }
    private void save(){
        persist();if(target==null){activity.message("请先选择工作流中的目标字段，草稿已经保存在手机");return;}
        if(activity.editor()==null){activity.message("请先连接电脑，草稿已经保留");return;}
        final String value=text.getText().toString();
        activity.editor().call("writeField",new JSONArray().put(target).put(value),r->{
            if(!r.optBoolean("ok")){activity.message(r.optString("error"));return;}
            saved=value;remember("history",value);activity.message("已写回节点；工作流可在编辑器中保存或导出");
        });
    }
    static class SimpleWatcher implements TextWatcher {
        private final Runnable changed;SimpleWatcher(Runnable r){changed=r;}
        public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){}
        public void afterTextChanged(Editable e){changed.run();}
    }
}
