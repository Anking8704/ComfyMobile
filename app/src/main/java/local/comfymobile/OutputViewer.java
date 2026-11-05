package local.comfymobile;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen viewer for generated outputs. Swipe left or right at normal zoom to move through the list it was
 * opened from; pinch or double tap to zoom. A result can be saved, shared, or used as the input image of the workflow.
 */
final class OutputViewer {
    private final MainActivity activity;
    private final List<JSONObject> outputs;
    private final ZoomImage image;
    private final TextView label,note;
    private final Button play,useInput,share;
    private int index;

    static void show(MainActivity a,List<JSONObject> list,int index){
        ArrayList<JSONObject> outputs=new ArrayList<>();for(JSONObject o:list)if(o!=null&&o.has("filename"))outputs.add(o);
        if(outputs.isEmpty())return;
        new OutputViewer(a,outputs,Math.max(0,Math.min(index,outputs.size()-1)));
    }
    private OutputViewer(MainActivity a,List<JSONObject> outputs,int start){
        activity=a;this.outputs=outputs;
        Dialog dialog=new Dialog(a,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout box=Ui.column(a);box.setBackgroundColor(0xFF000000);Ui.applyInsets(box);
        FrameLayout stage=new FrameLayout(a);
        image=new ZoomImage(a);image.setOnSwipe(direction->move(direction));
        stage.addView(image,new FrameLayout.LayoutParams(-1,-1));
        note=Ui.text(a,"",15,Ui.MUTED);note.setGravity(Gravity.CENTER);stage.addView(note,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        box.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        label=Ui.text(a,"",12,Ui.MUTED);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);label.setPadding(Ui.dp(a,10),0,Ui.dp(a,10),0);
        box.addView(label);
        LinearLayout bar=Ui.row(a);Ui.pad(bar,6);
        play=Ui.button(a,"播放",true,()->ResultsView.playVideo(a,current().optString("filename"),ResultsView.pathOf(current())));
        useInput=Ui.button(a,"设为输入图",false,()->{dialog.dismiss();a.useAsInput(current());});
        share=Ui.button(a,"分享",false,()->ResultsView.share(a,current()));
        Button save=Ui.button(a,"保存",true,()->a.saveFromServer(ResultsView.pathOf(current()),current().optString("filename")));
        Button close=Ui.button(a,"关闭",false,dialog::dismiss);
        for(Button b:new Button[]{play,useInput,share,save,close})bar.addView(b,new LinearLayout.LayoutParams(0,Ui.dp(a,48),1));
        box.addView(bar);
        dialog.setContentView(box);dialog.show();
        load(start);
    }
    private JSONObject current(){return outputs.get(index);}
    private void move(int direction){
        int next=index+direction;
        if(next<0||next>=outputs.size()){activity.message(direction>0?"已经是最后一张":"已经是第一张");return;}
        load(next);
    }
    private void load(int i){
        index=i;JSONObject o=current();String name=o.optString("filename"),path=ResultsView.pathOf(o);boolean video=MediaRef.isVideo(name);
        label.setText((outputs.size()>1?(i+1)+" / "+outputs.size()+" · 左右滑动切换 · ":"")+name);
        play.setVisibility(video?View.VISIBLE:View.GONE);useInput.setVisibility(video?View.GONE:View.VISIBLE);
        image.setImageDrawable(null);image.setTag(path);note.setText(video?"视频结果，点“播放”观看":"正在读取原图…");
        if(video)return;
        activity.worker.execute(()->{
            try{Bitmap b=ResultsView.decode(activity.client().bytes(path,60*1024*1024),2600);
                activity.runOnUiThread(()->{if(path.equals(image.getTag())){image.setImageBitmap(b);note.setText("");}});}
            catch(Exception e){activity.runOnUiThread(()->{if(path.equals(image.getTag()))note.setText("读取原图失败："+e.getMessage());});}
        });
    }
}
