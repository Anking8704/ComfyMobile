package local.comfymobile;
public class PromptTextTest {
    static void eq(String expected,String actual) {if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual);}
    public static void main(String[] args) {
        eq("前缀 (柔和光线:1.1) 后缀",PromptText.weight("前缀 柔和光线 后缀",3,7,.1).text);
        eq("(moonlight:1.3)",PromptText.weight("(moonlight:1.2)",0,15,.1).text);
        eq("(银白长发:0.9)",PromptText.weight("银白长发",0,4,-.1).text);
        eq("unchanged",PromptText.weight("unchanged",-1,4,.1).text);
        eq("unchanged",PromptText.weight("unchanged",3,3,.1).text);
        eq("(a:1.1), b",PromptText.weight("a, b",0,1,.1).text);
        eq("portrait, soft lighting",PromptText.insert("portrait",8,8,"soft lighting").text);
        eq("soft lighting, portrait",PromptText.insert("portrait",0,0,"soft lighting").text);
        eq("portrait, soft lighting",PromptText.insert("portrait, ",10,10,"soft lighting").text);
        eq("a soft lighting portrait",PromptText.insert("a dark portrait",2,6,"soft lighting").text);
        System.out.println("PromptText: all cases passed");
    }
}
