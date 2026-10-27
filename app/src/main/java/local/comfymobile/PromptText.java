package local.comfymobile;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptText {
    private static final Pattern WEIGHT = Pattern.compile("^\\((.+):([0-9]+(?:\\.[0-9]+)?)\\)$", Pattern.DOTALL);
    public static final class Edit {
        public final String text;
        public final int start, end;
        Edit(String text,int start,int end) {this.text=text;this.start=start;this.end=end;}
    }
    public static Edit weight(String text,int start,int end,double delta) {
        if(start<0 || end<=start || end>text.length()) return new Edit(text,Math.max(0,start),Math.max(0,end));
        String phrase=text.substring(start,end);
        Matcher match=WEIGHT.matcher(phrase);
        double previous=1;
        if(match.matches()) {phrase=match.group(1);previous=Double.parseDouble(match.group(2));}
        String replacement="("+phrase+":"+String.format(Locale.ROOT,"%.1f",Math.max(.1,previous+delta))+")";
        return new Edit(text.substring(0,start)+replacement+text.substring(end),start,start+replacement.length());
    }
    public static Edit insert(String text,int start,int end,String snippet){
        start=Math.max(0,Math.min(start,text.length()));end=Math.max(start,Math.min(end,text.length()));
        String left=text.substring(0,start),right=text.substring(end);
        String before="",after="";
        if(start==end){
            if(!left.isEmpty()&&!Character.isWhitespace(left.charAt(left.length()-1))&&",;([".indexOf(left.charAt(left.length()-1))<0)before=", ";
            if(!right.isEmpty()&&!Character.isWhitespace(right.charAt(0))&&",;)]".indexOf(right.charAt(0))<0)after=", ";
        }
        String replacement=before+snippet+after;int caret=start+before.length()+snippet.length();
        return new Edit(left+replacement+right,caret,caret);
    }
}
