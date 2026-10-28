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
    /** Spans [start,end) of the comma or line separated tags, ignoring separators inside parentheses; whitespace is trimmed. */
    public static java.util.List<int[]> tags(String text){
        java.util.ArrayList<int[]> spans=new java.util.ArrayList<>();int depth=0,from=0;
        for(int i=0;i<=text.length();i++){
            char c=i<text.length()?text.charAt(i):',';
            if(c=='('||c=='[')depth++;else if((c==')'||c==']')&&depth>0)depth--;
            if(i==text.length()||((c==','||c=='\n'||c=='，')&&depth==0)){
                int s=from,e=i;while(s<e&&Character.isWhitespace(text.charAt(s)))s++;while(e>s&&Character.isWhitespace(text.charAt(e-1)))e--;
                if(e>s)spans.add(new int[]{s,e});from=i+1;
            }
        }
        return spans;
    }
    /** The tag under the cursor, so a weight can be changed without selecting text precisely; null between tags. */
    public static int[] phraseAt(String text,int cursor){
        for(int[] span:tags(text))if(cursor>=span[0]&&cursor<=span[1])return span;
        return null;
    }
    /** Removes a tag together with the separator that follows it (or precedes it, for the last tag). */
    public static Edit remove(String text,int start,int end){
        int e=end;while(e<text.length()&&(text.charAt(e)==' '))e++;
        if(e<text.length()&&(text.charAt(e)==','||text.charAt(e)=='，')){e++;while(e<text.length()&&text.charAt(e)==' ')e++;return new Edit(text.substring(0,start)+text.substring(e),start,start);}
        int s=start;while(s>0&&text.charAt(s-1)==' ')s--;
        if(s>0&&(text.charAt(s-1)==','||text.charAt(s-1)=='，'))s--;
        return new Edit(text.substring(0,s)+text.substring(end),s,s);
    }
    /** Swaps two non-overlapping spans; the result's start/end mark where the first span's text ended up. */
    public static Edit swap(String text,int[] a,int[] b){
        int[] first=a[0]<b[0]?a:b,second=a[0]<b[0]?b:a;
        String one=text.substring(first[0],first[1]),two=text.substring(second[0],second[1]);
        String result=text.substring(0,first[0])+two+text.substring(first[1],second[0])+one+text.substring(second[1]);
        int moved=a==first?second[0]+(two.length()-one.length()):first[0];
        return new Edit(result,moved,moved+text.substring(a[0],a[1]).length());
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
