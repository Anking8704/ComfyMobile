package local.comfymobile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Merges the favorites list kept on the computer with this device's copy, by content.
 * An entry this device saw at the last sync but which is now gone from the computer was deleted elsewhere and stays deleted;
 * an entry this device has that it had not synced yet is new here and is kept. The same rule runs in the iPad page.
 */
public final class LibraryMerge {
    private LibraryMerge(){}
    public static List<String> merge(List<String> server,List<String> local,Collection<String> lastSynced,int limit){
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String item:local)if(!server.contains(item)&&!lastSynced.contains(item))result.add(item);
        for(String item:server){
            boolean deletedHere=lastSynced.contains(item)&&!local.contains(item);
            if(!deletedHere)result.add(item);
        }
        ArrayList<String> list=new ArrayList<>(result);
        return list.size()>limit?new ArrayList<>(list.subList(0,limit)):list;
    }
}
