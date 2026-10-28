package local.comfymobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Finds computers on the phone's local network that answer ComfyUI's /system_stats. */
final class Discovery {
    private Discovery(){}
    static void scan(Context context,List<String> known,Consumer<String> found,IntConsumer finished){
        LinkedHashSet<String> hosts=new LinkedHashSet<>();LinkedHashSet<Integer> ports=new LinkedHashSet<>();ports.add(8188);
        for(String origin:known){try{java.net.URI u=java.net.URI.create(origin);if(u.getPort()>0)ports.add(u.getPort());if(u.getHost()!=null)addSubnet(hosts,u.getHost());}catch(Exception ignored){}}
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties link=cm==null?null:cm.getLinkProperties(cm.getActiveNetwork());
        if(link!=null)for(LinkAddress address:link.getLinkAddresses()){
            InetAddress ip=address.getAddress();
            // Large networks (such as /16) are not swept whole: only the phone's own /24 and those of known computers.
            if(ip instanceof Inet4Address&&!ip.isLoopbackAddress())addSubnet(hosts,ip.getHostAddress());
        }
        ExecutorService pool=Executors.newFixedThreadPool(48);AtomicInteger hits=new AtomicInteger();
        for(String host:hosts)for(int port:ports)pool.execute(()->{
            try{
                java.net.Socket probe=new java.net.Socket();probe.connect(new java.net.InetSocketAddress(host,port),350);probe.close();
                String origin="http://"+host+":"+port;
                org.json.JSONObject stats=new ComfyClient(ServerAddress.parse(origin)).json("/system_stats");
                if(stats.has("system")){hits.incrementAndGet();found.accept(origin);}
            }catch(Exception ignored){}
        });
        pool.shutdown();
        new Thread(()->{try{pool.awaitTermination(30,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finished.accept(hits.get());}).start();
    }
    private static void addSubnet(LinkedHashSet<String> hosts,String ip){
        String[] parts=ip.split("\\.");if(parts.length!=4)return;
        String prefix=parts[0]+"."+parts[1]+"."+parts[2]+".";
        ArrayList<String> list=new ArrayList<>();for(int i=1;i<255;i++)list.add(prefix+i);
        list.remove(ip);list.add(0,ip);hosts.addAll(list);
    }
}
