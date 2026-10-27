package local.comfymobile;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class Ui {
    static final int BG=Color.rgb(16,21,26), SURFACE=Color.rgb(28,36,44), TEXT=Color.rgb(240,246,250),
        MUTED=Color.rgb(175,189,200), TEAL=Color.rgb(0,213,199), BORDER=Color.rgb(68,86,99);
    static int dp(Context c,int n) {return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(Context c,int color) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,12));d.setStroke(dp(c,1),BORDER);return d;
    }
    static LinearLayout column(Context c) {LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c) {LinearLayout l=new LinearLayout(c);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static TextView text(Context c,String s,int size,int color) {
        TextView v=new TextView(c);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(c,6),0,dp(c,6));return v;
    }
    static TextView title(Context c,String s) {TextView v=text(c,s,23,TEXT);v.setTypeface(null,Typeface.BOLD);return v;}
    static Button button(Context c,String s,boolean primary,Runnable action) {
        Button b=new Button(c);b.setText(s);b.setTextSize(15);b.setAllCaps(false);b.setTextColor(primary?BG:TEXT);
        b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setPadding(dp(c,12),dp(c,4),dp(c,12),dp(c,4));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),shape(c,primary?TEAL:SURFACE),null));
        // Long labels shrink instead of wrapping on narrow phones or with large system font sizes.
        b.setMaxLines(1);b.setAutoSizeTextTypeUniformWithConfiguration(10,15,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        b.setOnClickListener(v->action.run());return b;
    }
    static EditText input(Context c,String hint,boolean multiline) {
        EditText e=new EditText(c);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setHint(hint);e.setTextSize(16);
        e.setSingleLine(!multiline);e.setGravity(Gravity.TOP|Gravity.START);e.setPadding(dp(c,12),dp(c,12),dp(c,12),dp(c,12));
        e.setBackground(shape(c,SURFACE));return e;
    }
    static void add(LinearLayout parent,View view) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(parent.getContext(),10);parent.addView(view,p);
    }
    static void weighted(LinearLayout parent,View child) {parent.addView(child,new LinearLayout.LayoutParams(0,dp(parent.getContext(),48),1));}
    /** Height for a scrolling list inside a dialog: tall on tablets, never taller than about half of a landscape phone. */
    static int listHeight(Context c,int maxDp){return Math.min(dp(c,maxDp),Math.round(c.getResources().getDisplayMetrics().heightPixels*0.5f));}
    static boolean wide(Context c){return c.getResources().getConfiguration().smallestScreenWidthDp>=600;}
    /** Keeps forms readable on tablets by centring content at a maximum width. */
    static View maxWidth(View content,int maxDp){
        android.widget.FrameLayout frame=new android.widget.FrameLayout(content.getContext()){
            @Override protected void onMeasure(int w,int h){
                int limit=dp(getContext(),maxDp),size=View.MeasureSpec.getSize(w);
                android.widget.FrameLayout.LayoutParams p=(android.widget.FrameLayout.LayoutParams)content.getLayoutParams();
                int wanted=Math.min(size,limit);if(p.width!=wanted){p.width=wanted;}
                super.onMeasure(w,h);
            }
        };
        frame.addView(content,new android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL));return frame;
    }
    static ScrollView scroll(Context c,View content) {ScrollView s=new ScrollView(c);s.setFillViewport(true);s.addView(content);return s;}
    static void pad(View v,int n) {int p=dp(v.getContext(),n);v.setPadding(p,p,p,p);}
    static void hideKeyboard(Activity a,View v) {
        ((android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);
    }
    static void applyInsets(View root){
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){
                int types=android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime();
                android.graphics.Insets p=insets.getInsets(types);view.setPadding(p.left,p.top,p.right,p.bottom);
                return new android.view.WindowInsets.Builder(insets).setInsets(types,android.graphics.Insets.NONE).build();
            }
            // Android 10: system-window insets omit the landscape camera cutout, so take the larger of the two.
            android.view.DisplayCutout cut=insets.getDisplayCutout();
            view.setPadding(Math.max(insets.getSystemWindowInsetLeft(),cut==null?0:cut.getSafeInsetLeft()),Math.max(insets.getSystemWindowInsetTop(),cut==null?0:cut.getSafeInsetTop()),
                Math.max(insets.getSystemWindowInsetRight(),cut==null?0:cut.getSafeInsetRight()),Math.max(insets.getSystemWindowInsetBottom(),cut==null?0:cut.getSafeInsetBottom()));
            return insets.replaceSystemWindowInsets(0,0,0,0).consumeDisplayCutout();
        });
    }
}
