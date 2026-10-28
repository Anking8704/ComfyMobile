package local.comfymobile;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

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
    /** Media type from the file's first bytes, falling back to its extension; null when it is neither image nor video. */
    public static String sniff(byte[] head,int length,String filename) {
        if(length>=8 && head[0]==(byte)0x89 && head[1]=='P'&&head[2]=='N'&&head[3]=='G')return "image/png";
        if(length>=3 && head[0]==(byte)0xFF&&head[1]==(byte)0xD8)return "image/jpeg";
        if(length>=12 && head[0]=='R'&&head[1]=='I'&&head[8]=='W'&&head[9]=='E')return "image/webp";
        if(length>=6 && head[0]=='G'&&head[1]=='I'&&head[2]=='F')return "image/gif";
        if(length>=12 && head[4]=='f'&&head[5]=='t'&&head[6]=='y'&&head[7]=='p')return head[8]=='q'&&head[9]=='t'?"video/quicktime":"video/mp4";
        if(length>=4 && head[0]==(byte)0x1A&&head[1]==(byte)0x45&&head[2]==(byte)0xDF&&head[3]==(byte)0xA3)return "video/webm";
        return byExtension(filename);
    }
    public static String byExtension(String filename) {
        String name=filename==null?"":filename.toLowerCase(Locale.ROOT);
        if(name.endsWith(".png"))return "image/png";
        if(name.endsWith(".jpg")||name.endsWith(".jpeg"))return "image/jpeg";
        if(name.endsWith(".webp"))return "image/webp";
        if(name.endsWith(".gif"))return "image/gif";
        if(name.endsWith(".mp4")||name.endsWith(".m4v"))return "video/mp4";
        if(name.endsWith(".webm"))return "video/webm";
        if(name.endsWith(".mov"))return "video/quicktime";
        return null;
    }
    public static boolean isVideo(String filename){String type=byExtension(filename);return type!=null&&type.startsWith("video/");}
}
