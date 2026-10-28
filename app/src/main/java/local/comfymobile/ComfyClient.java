package local.comfymobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

final class ComfyClient {
    final ServerAddress server;
    private final ServerConnection connection;
    ComfyClient(ServerAddress server){this.server=server;connection=new ServerConnection(server);}

    private String cookie(){try{return CookieManager.getInstance().getCookie(server.origin());}catch(Exception e){return null;}}
    private HttpURLConnection open(String path) throws IOException {return connection.open(path,cookie());}
    byte[] bytes(String path,int max) throws IOException {return connection.bytes(path,max,cookie());}
    JSONObject json(String path) throws Exception{return new JSONObject(new String(bytes(path,12*1024*1024),StandardCharsets.UTF_8));}
    JSONObject post(String path,JSONObject body) throws Exception{
        byte[] reply=connection.post(path,"application/json",body.toString().getBytes(StandardCharsets.UTF_8),4*1024*1024,cookie());
        String text=new String(reply,StandardCharsets.UTF_8).trim();
        return text.startsWith("{")?new JSONObject(text):new JSONObject();
    }
    /** Uploads a picture to ComfyUI's input folder; returns the name a LoadImage widget refers to. */
    String uploadImage(Context context,Uri uri,String filename) throws Exception{
        byte[] data;
        try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("图片无法读取");ServerConnection.copy(in,out,60*1024*1024);data=out.toByteArray();
        }
        String mime=MediaRef.sniff(data,Math.min(16,data.length),filename);
        if(mime==null||!mime.startsWith("image/"))throw new IOException("请选择 PNG、JPEG、WebP 或 GIF 图片");
        LinkedHashMap<String,String> fields=new LinkedHashMap<>();fields.put("type","input");fields.put("overwrite","false");
        Multipart body=new Multipart(fields,"image",MediaRef.safeName(filename),mime,data);
        JSONObject reply=new JSONObject(new String(connection.post("/upload/image",body.contentType(),body.body,1024*1024,cookie()),StandardCharsets.UTF_8));
        String name=reply.getString("name"),sub=reply.optString("subfolder","");
        return sub.isEmpty()?name:sub+"/"+name;
    }

    /** Saves an output to the phone: pictures to Pictures/ComfyMobile, videos to Movies/ComfyMobile, other files to Download/ComfyMobile. */
    Uri saveMedia(Context context,String path,String filename) throws Exception {
        HttpURLConnection c=open(path);
        try(InputStream in=c.getInputStream()){return store(context,in,filename,null);}
        finally{c.disconnect();}
    }
    Uri saveData(Context context,byte[] data,String filename,String mime) throws Exception{
        return store(context,new ByteArrayInputStream(data),filename,mime);
    }
    private Uri store(Context context,InputStream source,String filename,String declared) throws Exception{
        PushbackInputStream buffered=new PushbackInputStream(source,16);
        byte[] head=new byte[16];int length=0,n;
        while(length<head.length&&(n=buffered.read(head,length,head.length-length))>0)length+=n;
        buffered.unread(head,0,length);
        String name=MediaRef.safeName(filename),mime=MediaRef.sniff(head,length,name);
        Uri collection;ContentValues values=new ContentValues();
        if(mime!=null&&mime.startsWith("image/")){collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/ComfyMobile");}
        else if(mime!=null){collection=MediaStore.Video.Media.EXTERNAL_CONTENT_URI;values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/ComfyMobile");}
        else{mime=declared!=null&&!declared.isEmpty()?declared:"application/octet-stream";collection=MediaStore.Downloads.EXTERNAL_CONTENT_URI;values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/ComfyMobile");}
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(collection,values);
        if(uri==null)throw new IOException("无法在手机上创建文件");
        try{
            try(OutputStream out=context.getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IOException("无法写入手机存储");ServerConnection.copy(buffered,out,500*1024*1024);
            }
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(uri,values,null,null);return uri;
        }catch(Exception e){context.getContentResolver().delete(uri,null,null);throw e;}
    }
    /** Downloads an output into the app's private share folder, so sharing does not add a copy to the gallery. */
    File cacheForShare(Context context,String path,String filename) throws Exception{
        File dir=new File(context.getCacheDir(),"share");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法准备分享文件");
        File[] old=dir.listFiles();if(old!=null)for(File f:old)if(System.currentTimeMillis()-f.lastModified()>3600_000L)f.delete();
        File file=new File(dir,MediaRef.safeName(filename));
        HttpURLConnection c=open(path);
        try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(file)){ServerConnection.copy(in,out,500*1024*1024);}
        finally{c.disconnect();}
        return file;
    }
}
