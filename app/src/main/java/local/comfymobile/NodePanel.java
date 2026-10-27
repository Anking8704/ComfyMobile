package local.comfymobile;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

final class NodePanel {
    private final MainActivity activity;
    NodePanel(MainActivity a){activity=a;}
    private void command(String name,JSONObject args,java.util.function.Consumer<JSONObject> cb){
        activity.editor().command(name,args,result->{if(!result.optBoolean("ok"))activity.message(result.optString("error"));else cb.accept(result);});
    }
    static JSONObject args(Object... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return o;}
    void list(){
        command("nodes",args(),result->{
            JSONArray nodes=result.optJSONArray("nodes"),fields=result.optJSONArray("fields");
            if(nodes==null||nodes.length()==0){activity.message("当前工作流没有节点，点添加创建");return;}
            String[] labels=new String[nodes.length()];for(int i=0;i<labels.length;i++)labels[i]="#"+nodes.optJSONObject(i).optString("id")+" · "+nodes.optJSONObject(i).optString("title");
            new AlertDialog.Builder(activity).setTitle("节点列表 · 点选操作").setItems(labels,(d,index)->details(nodes.optJSONObject(index),nodes,fields)).setNegativeButton("关闭",null).show();
        });
    }
    void add(){
        command("types",args(),result->{
            JSONArray types=result.optJSONArray("types");LinearLayout content=Ui.column(activity);Ui.pad(content,12);
            EditText filter=Ui.input(activity,"搜索节点类型",false);Ui.add(content,filter);LinearLayout list=Ui.column(activity);
            content.addView(Ui.scroll(activity,list),new LinearLayout.LayoutParams(-1,Ui.dp(activity,350)));
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("添加电脑已安装的节点").setView(content).setNegativeButton("取消",null).create();
            Runnable render=()->{
                list.removeAllViews();String query=filter.getText().toString().toLowerCase(java.util.Locale.ROOT);int shown=0;
                for(int i=0;i<types.length()&&shown<80;i++){
                    String type=types.optString(i);if(!type.toLowerCase(java.util.Locale.ROOT).contains(query))continue;shown++;
                    Ui.add(list,Ui.button(activity,type,false,()->command("add",args("type",type),r->{dialog.dismiss();activity.message("已添加节点");})));
                }
                if(shown==80)Ui.add(list,Ui.text(activity,"输入关键词缩小范围",13,Ui.MUTED));
            };
            filter.addTextChangedListener(new PromptEditor.SimpleWatcher(render));render.run();dialog.show();
        });
    }
    private void details(JSONObject node,JSONArray nodes,JSONArray fields){
        new AlertDialog.Builder(activity).setTitle(node.optString("title")+" · #"+node.optString("id"))
            .setItems(new String[]{"定位到画布","编辑参数","连接到其他节点","断开输入连线","复制节点","删除节点"},(d,index)->{
                Object id=node.opt("id");
                switch(index){
                    case 0:command("select",args("nodeId",id),r->{});break;
                    case 1:editParams(node,fields);break;
                    case 2:connect(node,nodes);break;
                    case 3:disconnect(node);break;
                    case 4:command("duplicate",args("nodeId",id),r->activity.message("已复制"));break;
                    case 5:new AlertDialog.Builder(activity).setMessage("删除此节点及其连线？").setPositiveButton("删除",(dd,w)->command("remove",args("nodeId",id),r->activity.message("已删除"))).setNegativeButton("取消",null).show();break;
                }
            }).setNegativeButton("关闭",null).show();
    }
    private void editParams(JSONObject node,JSONArray fields){
        ArrayList<JSONObject> choices=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<fields.length();i++){JSONObject f=fields.optJSONObject(i);if(f!=null&&String.valueOf(f.optJSONObject("target").opt("nodeId")).equals(node.optString("id"))){choices.add(f);labels.add(f.optString("name")+"："+f.optString("value"));}}
        if(choices.isEmpty()){activity.message("没有标准参数；插件专用控件请在画布操作");return;}
        new AlertDialog.Builder(activity).setTitle("修改节点参数").setItems(labels.toArray(new String[0]),(d,i)->{
            JSONObject field=choices.get(i);Object original=field.opt("value");JSONArray options=field.optJSONArray("options");
            if(options!=null){String[] names=new String[options.length()];for(int j=0;j<names.length;j++)names[j]=options.optString(j);
                new AlertDialog.Builder(activity).setTitle(field.optString("name")).setItems(names,(dd,j)->write(field,options.opt(j))).setNegativeButton("取消",null).show();
            }else if(original instanceof Boolean){new AlertDialog.Builder(activity).setTitle(field.optString("name")).setItems(new String[]{"开启","关闭"},(dd,j)->write(field,j==0)).show();}
            else if(original instanceof Number){EditText input=Ui.input(activity,"数值",false);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);input.setText(String.valueOf(original));
                new AlertDialog.Builder(activity).setTitle(field.optString("name")).setView(input).setPositiveButton("保存",(dd,w)->{try{double value=Double.parseDouble(input.getText().toString());if(!Double.isFinite(value))throw new NumberFormatException();write(field,value);}catch(NumberFormatException e){activity.message("请输入有效数值");}}).setNegativeButton("取消",null).show();
            }else{activity.showPage(1);activity.message("在提示词页选择该节点字段，使用全屏编辑");activity.prompt().chooseField();}
        }).setNegativeButton("关闭",null).show();
    }
    private void write(JSONObject field,Object value){command("widget",args("target",field.optJSONObject("target"),"value",value),r->activity.message("参数已更新"));}
    private void connect(JSONObject from,JSONArray nodes){
        JSONArray outputs=from.optJSONArray("outputs");if(outputs==null||outputs.length()==0){activity.message("此节点没有输出端口");return;}
        String[] names=new String[outputs.length()];for(int i=0;i<names.length;i++)names[i]=outputs.optJSONObject(i).optString("name")+" · "+outputs.optJSONObject(i).optString("type");
        new AlertDialog.Builder(activity).setTitle("选择输出端口").setItems(names,(d,output)->{
            ArrayList<JSONObject> targets=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
            for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null||n.optString("id").equals(from.optString("id")))continue;JSONArray inputs=n.optJSONArray("inputs");
                for(int j=0;inputs!=null&&j<inputs.length();j++){JSONObject input=inputs.optJSONObject(j);targets.add(args("node",n.opt("id"),"input",j));labels.add("#"+n.optString("id")+" "+n.optString("title")+" / "+input.optString("name")+" · "+input.optString("type")+(input.optBoolean("linked")?"（替换原连线）":""));}}
            new AlertDialog.Builder(activity).setTitle("选择目标输入端口").setItems(labels.toArray(new String[0]),(dd,index)->{
                JSONObject target=targets.get(index);command("connect",args("from",from.opt("id"),"output",output,"to",target.opt("node"),"input",target.optInt("input")),r->activity.message("连线已建立"));
            }).setNegativeButton("取消",null).show();
        }).setNegativeButton("取消",null).show();
    }
    private void disconnect(JSONObject node){
        JSONArray inputs=node.optJSONArray("inputs");ArrayList<Integer> slots=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(int i=0;inputs!=null&&i<inputs.length();i++){JSONObject input=inputs.optJSONObject(i);if(input.optBoolean("linked")){slots.add(i);labels.add(input.optString("name"));}}
        if(slots.isEmpty()){activity.message("没有已连接的输入端口");return;}
        new AlertDialog.Builder(activity).setTitle("断开哪个输入端口？").setItems(labels.toArray(new String[0]),(d,i)->command("disconnect",args("nodeId",node.opt("id"),"input",slots.get(i)),r->activity.message("已断开"))).setNegativeButton("取消",null).show();
    }
}
