package local.comfymobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class ServerConnection {
    private final ServerAddress server;
    public ServerConnection(ServerAddress server){this.server=server;}
    public HttpURLConnection open(String path,String cookie)throws IOException{
        URL url=server.resolve(path).toURL();
        for(int i=0;i<4;i++){
            if(!server.owns(url.toString()))throw new IOException("已拒绝跳转到其他服务器");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setConnectTimeout(8000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);
            if(cookie!=null)c.setRequestProperty("Cookie",cookie);
            c.setRequestProperty("Accept","application/json,image/*;q=0.9,*/*;q=0.8");
            final int status;
            try{status=c.getResponseCode();}catch(IOException e){c.disconnect();throw e;}
            if(status>=300&&status<400){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("服务器跳转缺少地址");url=new URL(url,location);continue;}
            if(status<200||status>=300){c.disconnect();throw new IOException("服务器返回 HTTP "+status);}
            return c;
        }throw new IOException("服务器跳转次数过多");
    }
    public byte[] bytes(String path,int max,String cookie)throws IOException{
        HttpURLConnection c=open(path,cookie);
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            copy(in,out,max);return out.toByteArray();
        }finally{c.disconnect();}
    }
    public static void copy(InputStream in,OutputStream out,int max)throws IOException{
        byte[] buffer=new byte[32768];int n,total=0;
        while((n=in.read(buffer))!=-1){total+=n;if(total>max)throw new IOException("文件超过当前传输上限，请从完整编辑器下载");out.write(buffer,0,n);}
    }
}
