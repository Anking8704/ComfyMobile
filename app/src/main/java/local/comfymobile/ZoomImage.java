package local.comfymobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** An image that fits the view and can be pinched, dragged and double-tapped to zoom, for checking details of a result. */
final class ZoomImage extends ImageView {
    private final Matrix matrix=new Matrix();
    private final float[] values=new float[9];
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private float fitScale=1;
    interface Swipe{void swiped(int direction);}
    private Swipe onSwipe;
    void setOnSwipe(Swipe swipe){onSwipe=swipe;}

    ZoomImage(Context c){
        super(c);setScaleType(ScaleType.MATRIX);setBackgroundColor(0xFF000000);
        scaler=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){zoomBy(d.getScaleFactor(),d.getFocusX(),d.getFocusY());return true;}
        });
        gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){matrix.postTranslate(-dx,-dy);clamp();return true;}
            // A fast horizontal fling at normal zoom moves to the next or previous result.
            @Override public boolean onFling(MotionEvent a,MotionEvent b,float vx,float vy){
                if(onSwipe==null||scale()>fitScale*1.05f||Math.abs(vx)<800||Math.abs(vx)<Math.abs(vy)*1.5f)return false;
                onSwipe.swiped(vx<0?1:-1);return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e){
                float target=scale()>fitScale*1.5f?fitScale:fitScale*3;zoomBy(target/scale(),e.getX(),e.getY());return true;
            }
        });
    }
    @Override public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);post(this::fit);}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);fit();}
    private void fit(){
        Drawable d=getDrawable();if(d==null||getWidth()==0||getHeight()==0)return;
        float iw=d.getIntrinsicWidth(),ih=d.getIntrinsicHeight();if(iw<=0||ih<=0)return;
        fitScale=Math.min(getWidth()/iw,getHeight()/ih);
        matrix.setScale(fitScale,fitScale);matrix.postTranslate((getWidth()-iw*fitScale)/2,(getHeight()-ih*fitScale)/2);setImageMatrix(matrix);
    }
    private float scale(){matrix.getValues(values);return values[Matrix.MSCALE_X];}
    private void zoomBy(float factor,float x,float y){
        float next=Math.max(fitScale,Math.min(fitScale*8,scale()*factor));
        matrix.postScale(next/scale(),next/scale(),x,y);clamp();
    }
    /** Keeps the picture on screen and centred along any axis where it is smaller than the view. */
    private void clamp(){
        Drawable d=getDrawable();if(d==null)return;
        RectF r=new RectF(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight());matrix.mapRect(r);
        float dx=0,dy=0;
        if(r.width()<=getWidth())dx=(getWidth()-r.width())/2-r.left;else if(r.left>0)dx=-r.left;else if(r.right<getWidth())dx=getWidth()-r.right;
        if(r.height()<=getHeight())dy=(getHeight()-r.height())/2-r.top;else if(r.top>0)dy=-r.top;else if(r.bottom<getHeight())dy=getHeight()-r.bottom;
        matrix.postTranslate(dx,dy);setImageMatrix(matrix);
    }
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e){
        scaler.onTouchEvent(e);gestures.onTouchEvent(e);
        getParent().requestDisallowInterceptTouchEvent(scale()>fitScale*1.01f||e.getPointerCount()>1);
        return true;
    }
}
