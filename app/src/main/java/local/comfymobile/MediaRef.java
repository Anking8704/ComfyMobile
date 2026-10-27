package local.comfymobile;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

public final class MediaRef {
    private static String encode(String s) {
        try {return URLEncoder.encode(s,"UTF-8").replace("+","%20");}
        catch(UnsupportedEncodingException e) {throw new AssertionError(e);}
    }
    public static String path(String filename,String subfolder,String type) {
        if(!type.equals("output")&&!type.equals("input")&&!type.equals("temp"))throw new IllegalArgumentException("未知图片类型");
        return "/view?filename="+encode(filename)+"&subfolder="+encode(subfolder)+"&type="+encode(type);
    }
    public static String safeName(String value) {
        String name=value.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "");
        return name.isEmpty()?"ComfyMobile.png":name;
    }
}
