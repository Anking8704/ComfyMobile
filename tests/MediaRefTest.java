package local.comfymobile;
public class MediaRefTest {
    public static void main(String[] args) {
        ServerAddress s=ServerAddress.parse("192.168.1.8:8188");
        String url=MediaRef.path("月光 #1.png","人物/今天","output");
        if(!url.contains("%E6%9C%88")||!url.contains("%23")||!url.contains("%2F"))throw new AssertionError(url);
        if(!s.owns(s.resolve(url).toString()))throw new AssertionError("origin");
        if(!MediaRef.safeName("../../test.png").equals("test.png"))throw new AssertionError("name");
        if(!MediaRef.safeName("a\\b.png").equals("b.png"))throw new AssertionError("windows name");
        try {MediaRef.path("x","","../input");throw new AssertionError("type");}catch(IllegalArgumentException expected){}
        System.out.println("MediaRef: all cases passed");
    }
}
