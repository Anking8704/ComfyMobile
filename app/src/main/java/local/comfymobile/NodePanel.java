package local.comfymobile;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;

final class NodePanel {
    private final MainActivity activity;
    NodePanel(MainActivity a){activity=a;}
    private void command(String name,JSONObject args,java.util.function.Consumer<JSONObject> cb){
        activity.editor().command(name,args,result->{if(!result.optBoolean("ok"))activity.message(result.optString("error"));else cb.accept(result);});
    }
    static JSONObject args(Object... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return o;}
    private static String modeLabel(String mode){return mode.equals("bypass")?"【已旁路】":mode.equals("mute")?"【已静音】":"";}
    void list(){
        command("nodes",args(),result->{
            JSONArray nodes=result.optJSONArray("nodes"),fields=result.optJSONArray("fields");
            if(nodes==null||nodes.length()==0){activity.message("当前工作流没有节点，点添加创建");return;}
            ArrayList<String> labels=new ArrayList<>(),details=new ArrayList<>();
            for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);
                labels.add(modeLabel(n.optString("mode"))+"#"+n.optString("id")+" · "+n.optString("title"));details.add(n.optString("type")+(n.optBoolean("subgraph")?" · 子图":""));}
            String where=result.isNull("graph")?"":"（子图："+result.optString("graph")+"）";
            Ui.choose(activity,"节点列表"+where,labels,details,i->details(nodes.optJSONObject(i),nodes,fields));
        });
    }
    void add(){
        command("types",args(),result->{
            JSONArray types=result.optJSONArray("types");if(types==null)return;
            ArrayList<String> labels=new ArrayList<>(),details=new ArrayList<>();
            for(int i=0;i<types.length();i++){JSONObject t=types.optJSONObject(i);labels.add(t.optString("title"));details.add(t.optString("category")+" · "+t.optString("type"));}
            Ui.choose(activity,"添加电脑已安装的节点（共 "+labels.size()+" 种）",labels,details,i->command("add",args("type",types.optJSONObject(i).optString("type")),r->activity.message("已添加到画布中央")));
        });
    }
    private void details(JSONObject node,JSONArray nodes,JSONArray fields){
        Object id=node.opt("id");String mode=node.optString("mode");
        String[] items={"定位到画布","编辑参数","连接到其他节点","断开输入连线",mode.equals("bypass")?"取消旁路":"旁路（跳过此节点）",mode.equals("mute")?"取消静音":"静音（不执行此节点）","复制节点","删除节点"};
        new AlertDialog.Builder(activity).setTitle(node.optString("title")+" · #"+node.optString("id")).setItems(items,(d,index)->{
            switch(index){
                case 0:command("select",args("nodeId",id),r->{});break;
                case 1:editParams(node,fields);break;
                case 2:connect(node,nodes);break;
                case 3:disconnect(node);break;
                case 4:command("mode",args("nodeId",id,"mode","bypass"),r->activity.message(r.optString("mode").equals("bypass")?"已旁路":"已恢复"));break;
                case 5:command("mode",args("nodeId",id,"mode","mute"),r->activity.message(r.optString("mode").equals("mute")?"已静音":"已恢复"));break;
                case 6:command("duplicate",args("nodeId",id),r->activity.message("已复制"));break;
                case 7:new AlertDialog.Builder(activity).setMessage("删除此节点及其连线？可用“撤销”恢复。").setPositiveButton("删除",(dd,w)->command("remove",args("nodeId",id),r->activity.message("已删除"))).setNegativeButton("取消",null).show();break;
            }
        }).setNegativeButton("关闭",null).show();
    }
    private void editParams(JSONObject node,JSONArray fields){
        ArrayList<JSONObject> choices=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(int i=0;fields!=null&&i<fields.length();i++){JSONObject f=fields.optJSONObject(i);
            if(f!=null&&String.valueOf(f.optJSONObject("target").opt("nodeId")).equals(node.optString("id"))){
                String v=f.optString("value").replace('\n',' ');choices.add(f);
                labels.add(MainActivity.roleLabel(f)+f.optString("label",f.optString("name"))+(f.optBoolean("linked")?"（已由连线提供，不能在这里改）":"："+(v.length()>40?v.substring(0,40)+"…":v)));}}
        if(choices.isEmpty()){activity.message("没有标准参数；插件专用控件请在画布操作");return;}
        new AlertDialog.Builder(activity).setTitle("修改节点参数").setItems(labels.toArray(new String[0]),(d,i)->edit(choices.get(i))).setNegativeButton("关闭",null).show();
    }
    private void edit(JSONObject field){
        if(field.optBoolean("linked")){activity.message("这个参数由连线提供，请修改上游节点或断开连线");return;}
        Object original=field.opt("value");JSONArray options=field.optJSONArray("options");String title=field.optString("label",field.optString("name"));
        if(options!=null){ArrayList<String> names=new ArrayList<>();for(int j=0;j<options.length();j++)names.add(options.optString(j));
            Ui.choose(activity,title+"（当前："+field.optString("value")+"）",names,null,j->write(field,options.opt(j)));}
        else if(original instanceof Boolean){new AlertDialog.Builder(activity).setTitle(title).setItems(new String[]{"开启","关闭"},(dd,j)->write(field,j==0)).show();}
        else if(original instanceof Number){
            JSONObject n=field.optJSONObject("number");if(n==null)n=new JSONObject();boolean integer=n.optInt("precision",-1)==0;
            EditText input=Ui.input(activity,"数值",false);input.setInputType(InputType.TYPE_CLASS_NUMBER|(integer?0:InputType.TYPE_NUMBER_FLAG_DECIMAL)|InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setText(integer?String.valueOf(((Number)original).longValue()):String.valueOf(original));input.setSelectAllOnFocus(true);
            String range=(n.isNull("min")?"":"最小 "+n.opt("min"))+(n.isNull("max")?"":"  最大 "+n.opt("max"))+(integer?"  只接受整数":"");
            new AlertDialog.Builder(activity).setTitle(title).setMessage(range.trim().isEmpty()?null:range.trim()).setView(input).setPositiveButton("保存",(dd,w)->{
                try{double value=Double.parseDouble(input.getText().toString().trim());if(!Double.isFinite(value))throw new NumberFormatException();
                    write(field,integer?(Object)Math.round(value):(Object)value);}
                catch(NumberFormatException e){activity.message("请输入有效数字");}
            }).setNegativeButton("取消",null).show();
        }else activity.openEdit(activity.editor(),field);
    }
    private void write(JSONObject field,Object value){command("widget",args("target",field.optJSONObject("target"),"value",value),r->{activity.message("参数已更新");activity.refreshWorkflowTitle();});}
    /** Same rule as the editor: equal types, a wildcard on either side, or overlapping comma-separated lists. */
    static boolean compatible(String output,String input){
        if(output.equals("*")||input.equals("*")||output.isEmpty()||input.isEmpty())return true;
        ArrayList<String> a=new ArrayList<>(Arrays.asList(output.toUpperCase().split(",")));
        for(String t:input.toUpperCase().split(","))if(a.contains(t.trim())||a.contains(t))return true;
        return false;
    }
    private void connect(JSONObject from,JSONArray nodes){
        JSONArray outputs=from.optJSONArray("outputs");if(outputs==null||outputs.length()==0){activity.message("此节点没有输出端口");return;}
        String[] names=new String[outputs.length()];for(int i=0;i<names.length;i++)names[i]=outputs.optJSONObject(i).optString("name")+" · "+outputs.optJSONObject(i).optString("type");
        new AlertDialog.Builder(activity).setTitle("选择输出端口").setItems(names,(d,output)->{
            String type=outputs.optJSONObject(output).optString("type");
            ArrayList<JSONObject> targets=new ArrayList<>();ArrayList<String> labels=new ArrayList<>(),details=new ArrayList<>();
            for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null||n.optString("id").equals(from.optString("id")))continue;JSONArray inputs=n.optJSONArray("inputs");
                for(int j=0;inputs!=null&&j<inputs.length();j++){JSONObject input=inputs.optJSONObject(j);if(!compatible(type,input.optString("type")))continue;
                    targets.add(args("node",n.opt("id"),"input",j));labels.add("#"+n.optString("id")+" "+n.optString("title")+" / "+input.optString("name"));
                    details.add(input.optString("type")+(input.optBoolean("linked")?" · 已有连线，会被替换":""));}}
            if(targets.isEmpty()){activity.message("没有能接收 "+type+" 的输入端口");return;}
            Ui.choose(activity,"连接到（只列出类型兼容的端口）",labels,details,index->{
                JSONObject target=targets.get(index);command("connect",args("from",from.opt("id"),"output",output,"to",target.opt("node"),"input",target.optInt("input")),r->activity.message("连线已建立"));
            });
        }).setNegativeButton("取消",null).show();
    }
    private void disconnect(JSONObject node){
        JSONArray inputs=node.optJSONArray("inputs");ArrayList<Integer> slots=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(int i=0;inputs!=null&&i<inputs.length();i++){JSONObject input=inputs.optJSONObject(i);if(input.optBoolean("linked")){slots.add(i);labels.add(input.optString("name"));}}
        if(slots.isEmpty()){activity.message("没有已连接的输入端口");return;}
        new AlertDialog.Builder(activity).setTitle("断开哪个输入端口？").setItems(labels.toArray(new String[0]),(d,i)->command("disconnect",args("nodeId",node.opt("id"),"input",slots.get(i)),r->activity.message("已断开"))).setNegativeButton("取消",null).show();
    }
}
