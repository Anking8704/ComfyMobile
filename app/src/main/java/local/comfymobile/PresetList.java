package local.comfymobile;
import java.util.ArrayList;
import java.util.List;

public final class PresetList {
    public static List<String> edit(List<String> latest,String original,String replacement){
        ArrayList<String> next=new ArrayList<>(latest);
        if(original==null){if(replacement!=null)next.add(replacement);return next;}
        int index=next.indexOf(original);
        if(index<0)throw new IllegalStateException("收藏已变化，请重新打开词库");
        if(replacement==null)next.remove(index);else next.set(index,replacement);
        return next;
    }
}
