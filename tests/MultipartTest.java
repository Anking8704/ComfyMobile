package local.comfymobile;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

public class MultipartTest {
    public static void main(String[] args) throws Exception {
        LinkedHashMap<String,String> fields=new LinkedHashMap<>();fields.put("type","input");
        byte[] png={(byte)0x89,'P','N','G',13,10,26,10,0,0};
        Multipart m=new Multipart(fields,"image","a\"b.png","image/png",png);
        String text=new String(m.body,StandardCharsets.ISO_8859_1);
        if(!text.contains("name=\"type\"\r\n\r\ninput\r\n")||!text.contains("filename=\"ab.png\"")||!text.endsWith("--"+m.boundary+"--\r\n"))throw new AssertionError(text);
        if(!"image/png".equals(MediaRef.sniff(png,png.length,"x.bin")))throw new AssertionError("png sniff");
        byte[] mp4={0,0,0,24,'f','t','y','p','i','s','o','m'};
        if(!"video/mp4".equals(MediaRef.sniff(mp4,mp4.length,"x")))throw new AssertionError("mp4 sniff");
        if(MediaRef.sniff(new byte[]{'{','}'},2,"workflow.json")!=null)throw new AssertionError("json is not media");
        if(!MediaRef.isVideo("clip.webm")||MediaRef.isVideo("a.png"))throw new AssertionError("video ext");

        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/echo",e->{ByteArrayOutputStream out=new ByteArrayOutputStream();e.getRequestBody().transferTo(out);
            byte[] reply=("{\"method\":\""+e.getRequestMethod()+"\",\"length\":"+out.size()+"}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,reply.length);e.getResponseBody().write(reply);e.close();});
        server.createContext("/moved",e->{e.getResponseHeaders().set("Location","/echo");e.sendResponseHeaders(307,-1);e.close();});
        server.start();
        try{
            ServerConnection c=new ServerConnection(ServerAddress.parse("127.0.0.1:"+server.getAddress().getPort()));
            String reply=new String(c.post("/echo",m.contentType(),m.body,1000,null),StandardCharsets.UTF_8);
            if(!reply.equals("{\"method\":\"POST\",\"length\":"+m.body.length+"}"))throw new AssertionError(reply);
            try{c.post("/moved","application/json","{}".getBytes(StandardCharsets.UTF_8),1000,null);throw new AssertionError("followed POST redirect");}catch(IOException expected){}
        }finally{server.stop(0);}
        System.out.println("Multipart: upload body, media sniffing and same-origin POST passed");
    }
}
