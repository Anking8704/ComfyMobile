package local.comfymobile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Runs the shared prompt cases; tests/web-shell.test.cjs runs the same file against the iPad page's JavaScript. */
public class PromptTextTest {
    static void eq(String expected,String actual) {if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual);}
    static String unescape(String s){return s.replace("\\n","\n");}
    public static void main(String[] args) throws Exception {
        int cases=0;
        for(String line:Files.readAllLines(Paths.get("tests/prompt-cases.tsv"),StandardCharsets.UTF_8)){
            if(line.startsWith("#")||line.trim().isEmpty())continue;
            String[] c=line.split("\t",-1);String op=c[0],text=unescape(c[1]);int a=Integer.parseInt(c[2]);
            if(op.equals("phrase")){
                int[] span=PromptText.phraseAt(text,a);
                eq(c[6],span==null?"none":String.valueOf(span[0]));eq(c[7],span==null?"none":String.valueOf(span[1]));cases++;continue;
            }
            int b=Integer.parseInt(c[3]);PromptText.Edit e;
            switch(op){
                case "weight":e=PromptText.weight(text,a,b,Double.parseDouble(c[4]));break;
                case "insert":e=PromptText.insert(text,a,b,unescape(c[4]));break;
                case "remove":e=PromptText.remove(text,a,b);break;
                case "swap":{String[] o=c[4].split(",");e=PromptText.swap(text,new int[]{a,b},new int[]{Integer.parseInt(o[0]),Integer.parseInt(o[1])});break;}
                default:throw new AssertionError("unknown op "+op);
            }
            eq(unescape(c[5]),e.text);eq(c[6],String.valueOf(e.start));eq(c[7],String.valueOf(e.end));cases++;
        }
        eq("unchanged",PromptText.weight("unchanged",-1,4,.1).text);
        eq("unchanged",PromptText.weight("unchanged",3,3,.1).text);
        if(PromptText.tags("a, (b, c:1.2)\nd").size()!=3)throw new AssertionError("tags ignore commas inside parentheses");
        System.out.println("PromptText: "+cases+" shared cases passed");
    }
}
