package local.comfymobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class ComfyClient {
    final ServerAddress server;
    private final ServerConnection connection;
    ComfyClient(ServerAddress server){this.server=server;connection=new ServerConnection(server);}

    private HttpURLConnection open(String path) throws IOException {
        return connection.open(path,CookieManager.getInstance().getCookie(server.origin()));
    }
    byte[] bytes(String path,int max) throws IOException {
        return connection.bytes(path,max,CookieManager.getInstance().getCookie(server.origin()));
    }
    JSONObject json(String path) throws Exception{return new JSONObject(new String(bytes(path,12*1024*1024),StandardCharsets.UTF_8));}

    Uri saveImage(Context context,String path,String filename) throws Exception {
        HttpURLConnection c=open(path);Uri uri=null;
        try(InputStream in=c.getInputStream()){
            java.io.PushbackInputStream buffered=new java.io.PushbackInputStream(in,16);
            byte[] head=new byte[16];int length=0,n;
            while(length<head.length&&(n=buffered.read(head,length,head.length-length))>0)length+=n;
            buffered.unread(head,0,length);
            String mime;
            if(length>=8 && head[0]==(byte)0x89 && head[1]=='P'&&head[2]=='N'&&head[3]=='G')mime="image/png";
            else if(length>=3 && head[0]==(byte)0xFF&&head[1]==(byte)0xD8) mime="image/jpeg";
            else if(length>=12 && head[0]=='R'&&head[1]=='I'&&head[8]=='W'&&head[9]=='E')mime="image/webp";
            else if(length>=6 && head[0]=='G'&&head[1]=='I'&&head[2]=='F')mime="image/gif";
            else throw new IOException("服务器返回的内容不是支持的 PNG/JPEG/WebP/GIF 图片");
            ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,MediaRef.safeName(filename));
            values.put(MediaStore.Images.Media.MIME_TYPE,mime);values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ComfyMobile");values.put(MediaStore.Images.Media.IS_PENDING,1);
            uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new IOException("无法创建相册图片");
            try(OutputStream out=context.getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IOException("无法写入相册");ServerConnection.copy(buffered,out,150*1024*1024);
            }
            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);context.getContentResolver().update(uri,values,null,null);return uri;
        }catch(Exception e){if(uri!=null)context.getContentResolver().delete(uri,null,null);throw e;}
        finally{c.disconnect();}
    }
}
