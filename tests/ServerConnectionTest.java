package local.comfymobile;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
public class ServerConnectionTest {
    public static void main(String[] args)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ok",e->{byte[] bytes="电脑已连接".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.createContext("/redirect",e->{e.getResponseHeaders().set("Location","/ok");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/foreign",e->{e.getResponseHeaders().set("Location","http://127.0.0.1:1/secret");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/loop",e->{e.getResponseHeaders().set("Location","/loop");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/fail",e->{e.sendResponseHeaders(503,-1);e.close();});
        server.start();
        try{
            ServerConnection c=new ServerConnection(ServerAddress.parse("127.0.0.1:"+server.getAddress().getPort()));
            String value=new String(c.bytes("/redirect",1000,null),StandardCharsets.UTF_8);
            if(!value.equals("电脑已连接"))throw new AssertionError(value);
            for(String path:new String[]{"/foreign","/loop","/fail"}){
                try{c.bytes(path,1000,null);throw new AssertionError("Accepted "+path);}catch(IOException expected){}
            }
            try{c.bytes("/ok",1,null);throw new AssertionError("Ignored limit");}catch(IOException expected){}
            System.out.println("ServerConnection: same-origin, redirects, errors and transfer limits passed");
        }finally{server.stop(0);}
    }
}
