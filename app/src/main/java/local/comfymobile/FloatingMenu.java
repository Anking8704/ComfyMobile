package local.comfymobile;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.graphics.Bitmap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;

/** Full-screen canvas overlay: a small draggable handle that expands into the workflow actions. */
final class FloatingMenu {
    static final class Item {
        final String label;final boolean primary,keepOpen;final Runnable action;
        Item(String label,boolean primary,boolean keepOpen,Runnable action){this.label=label;this.primary=primary;this.keepOpen=keepOpen;this.action=action;}
    }
    private static final float IDLE=0.72f;
    private final MainActivity activity;
    private final FrameLayout host;
    private final SharedPreferences prefs;
    private final Button handle;
    private final LinearLayout sheet,grid;
    private final TextView status,progress,title;
    private final LinearLayout chip;
    private final ImageView thumb;
    private final ArrayList<Item> items=new ArrayList<>();
    private final HashMap<String,Button> buttons=new HashMap<>();
    private final HashMap<String,Boolean> disabled=new HashMap<>();
    private final Runnable hideProgress;

    FloatingMenu(MainActivity a,FrameLayout host,SharedPreferences prefs){
        activity=a;this.host=host;this.prefs=prefs;
        chip=Ui.row(a);chip.setPadding(Ui.dp(a,6),Ui.dp(a,4),Ui.dp(a,12),Ui.dp(a,4));
        GradientDrawable pill=new GradientDrawable();pill.setColor(0xCC10151A);pill.setCornerRadius(Ui.dp(a,16));pill.setStroke(Ui.dp(a,1),Ui.BORDER);chip.setBackground(pill);
        thumb=new ImageView(a);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);thumb.setVisibility(View.GONE);thumb.setContentDescription("实时预览");
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(Ui.dp(a,44),Ui.dp(a,44));tp.rightMargin=Ui.dp(a,8);chip.addView(thumb,tp);
        progress=Ui.text(a,"",12,Ui.TEXT);progress.setPadding(0,0,0,0);chip.addView(progress);
        chip.setVisibility(View.GONE);chip.setOnClickListener(v->a.showLivePreview());
        FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);pp.topMargin=Ui.dp(a,8);host.addView(chip,pp);
        hideProgress=()->{chip.setVisibility(View.GONE);thumb.setVisibility(View.GONE);};

        sheet=Ui.column(a);Ui.pad(sheet,8);sheet.setBackground(Ui.shape(a,0xF21C242C));sheet.setElevation(Ui.dp(a,8));sheet.setVisibility(View.GONE);
        sheet.setClickable(true);
        title=Ui.text(a,"",14,Ui.TEXT);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);title.setPadding(Ui.dp(a,6),0,Ui.dp(a,6),0);
        title.setVisibility(View.GONE);sheet.addView(title);
        LinearLayout top=Ui.row(a);
        status=Ui.text(a,"",12,Ui.MUTED);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(Ui.dp(a,6),0,Ui.dp(a,6),0);top.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        Button close=Ui.button(a,"收起",false,this::close);top.addView(close,new LinearLayout.LayoutParams(-2,Ui.dp(a,40)));
        sheet.addView(top);grid=Ui.column(a);sheet.addView(grid);
        host.addView(sheet,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));

        handle=new Button(a);handle.setText("≡");handle.setTextSize(22);handle.setTextColor(Ui.TEXT);handle.setPadding(0,0,0,0);
        handle.setContentDescription("展开功能菜单");handle.setAlpha(IDLE);handle.setElevation(Ui.dp(a,6));
        GradientDrawable round=new GradientDrawable();round.setShape(GradientDrawable.OVAL);round.setColor(0xE61C242C);round.setStroke(Ui.dp(a,2),Ui.TEAL);handle.setBackground(round);
        int size=Ui.dp(a,52);host.addView(handle,new FrameLayout.LayoutParams(size,size,Gravity.TOP|Gravity.START));
        handle.setOnClickListener(v->open());
        handle.setOnTouchListener(new View.OnTouchListener(){
            float startX,startY,dx,dy;boolean dragging;final int slop=ViewConfiguration.get(a).getScaledTouchSlop();
            @Override public boolean onTouch(View v,MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:startX=e.getRawX();startY=e.getRawY();dx=v.getX()-startX;dy=v.getY()-startY;dragging=false;v.setAlpha(1f);return true;
                    case MotionEvent.ACTION_MOVE:
                        if(!dragging&&Math.hypot(e.getRawX()-startX,e.getRawY()-startY)>slop)dragging=true;
                        if(dragging){v.setX(clamp(e.getRawX()+dx,0,host.getWidth()-v.getWidth()));v.setY(clamp(e.getRawY()+dy,0,host.getHeight()-v.getHeight()));}
                        return true;
                    case MotionEvent.ACTION_UP:v.setAlpha(IDLE);if(dragging)snap();else v.performClick();return true;
                    case MotionEvent.ACTION_CANCEL:v.setAlpha(IDLE);if(dragging)snap();return true;
                    default:return false;
                }
            }
        });
        host.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)host.post(()->{place();layoutGrid();});});
    }

    void setItems(Item... list){items.clear();java.util.Collections.addAll(items,list);layoutGrid();}
    void setStatus(String text){status.setText(text);}
    /** The open workflow's name (and unsaved marker) shown above the actions. */
    void setTitle(String text){title.setText(text);title.setVisibility(text==null||text.isEmpty()?View.GONE:View.VISIBLE);}
    void showPreview(Bitmap bitmap){if(bitmap==null)return;thumb.setImageBitmap(bitmap);thumb.setVisibility(View.VISIBLE);chip.setVisibility(View.VISIBLE);chip.removeCallbacks(hideProgress);chip.postDelayed(hideProgress,8000);}
    void setEnabled(String label,boolean enabled){disabled.put(label,!enabled);Button b=buttons.get(label);if(b!=null)b.setEnabled(enabled);}
    boolean isOpen(){return sheet.getVisibility()==View.VISIBLE;}
    void open(){layoutGrid();sheet.setVisibility(View.VISIBLE);handle.setVisibility(View.GONE);activity.menuOpened();}
    void close(){sheet.setVisibility(View.GONE);handle.setVisibility(View.VISIBLE);}
    /** Shows sampling progress over the canvas while the menu is collapsed; it fades out once updates stop. */
    void showProgress(String text){
        progress.setText(text);chip.setVisibility(View.VISIBLE);status.setText(text);
        chip.removeCallbacks(hideProgress);chip.postDelayed(hideProgress,8000);
    }

    private void layoutGrid(){
        grid.removeAllViews();buttons.clear();
        int width=activity.getResources().getConfiguration().screenWidthDp;
        int sheetWidth=Math.min(width-16,560);
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)sheet.getLayoutParams();
        lp.width=Ui.dp(activity,sheetWidth);int m=Ui.dp(activity,8);lp.setMargins(m,m,m,m);sheet.setLayoutParams(lp);
        int columns=Math.max(3,Math.min(6,(sheetWidth-16)/92));
        LinearLayout row=null;
        for(int i=0;i<items.size();i++){
            if(i%columns==0){row=Ui.row(activity);grid.addView(row,new LinearLayout.LayoutParams(-1,-2));}
            Item item=items.get(i);
            Button b=Ui.button(activity,item.label,item.primary,()->{if(!item.keepOpen)close();item.action.run();});
            Boolean off=disabled.get(item.label);if(off!=null&&off)b.setEnabled(false);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1);int g=Ui.dp(activity,3);p.setMargins(g,g,g,g);
            row.addView(b,p);buttons.put(item.label,b);
        }
        if(row!=null)for(int i=items.size()%columns;i>0&&i<columns;i++)row.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
    }
    private void place(){
        if(host.getWidth()==0||handle.getWidth()==0)return;
        int m=Ui.dp(activity,12);float range=Math.max(0,host.getHeight()-handle.getHeight()-2*m);
        boolean right=prefs.getBoolean("menu_right",true);float ratio=prefs.getFloat("menu_y",0.82f);
        handle.setX(right?host.getWidth()-handle.getWidth()-m:m);handle.setY(m+range*clamp(ratio,0,1));
    }
    private void snap(){
        int m=Ui.dp(activity,12);float range=Math.max(1,host.getHeight()-handle.getHeight()-2*m);
        boolean right=handle.getX()+handle.getWidth()/2f>host.getWidth()/2f;
        prefs.edit().putBoolean("menu_right",right).putFloat("menu_y",clamp((handle.getY()-m)/range,0,1)).apply();place();
    }
    void attached(){handle.post(this::place);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
