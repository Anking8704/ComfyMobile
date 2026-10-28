package local.comfymobile;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children out left to right, wrapping onto new lines, for the prompt tag view. */
final class FlowLayout extends ViewGroup {
    private final int gap;
    FlowLayout(Context c){super(c);gap=Ui.dp(c,6);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec),x=0,y=0,row=0;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            if(x>0&&x+child.getMeasuredWidth()>width){x=0;y+=row+gap;row=0;}
            x+=child.getMeasuredWidth()+gap;row=Math.max(row,child.getMeasuredHeight());
        }
        setMeasuredDimension(width,y+row);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int width=r-l,x=0,y=0,row=0;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);int w=child.getMeasuredWidth(),h=child.getMeasuredHeight();
            if(x>0&&x+w>width){x=0;y+=row+gap;row=0;}
            child.layout(x,y,x+w,y+h);x+=w+gap;row=Math.max(row,h);
        }
    }
}
