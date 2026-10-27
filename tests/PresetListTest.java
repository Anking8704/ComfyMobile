package local.comfymobile;
import java.util.Arrays;
import java.util.List;
public class PresetListTest {
    public static void main(String[] args){
        List<String> latest=Arrays.asList("just added","old favorite","another favorite");
        eq(Arrays.asList("just added","edited favorite","another favorite"),PresetList.edit(latest,"old favorite","edited favorite"));
        eq(Arrays.asList("just added","another favorite"),PresetList.edit(latest,"old favorite",null));
        eq(Arrays.asList("just added","old favorite","another favorite","new entry"),PresetList.edit(latest,null,"new entry"));
        eq(Arrays.asList("just added","old favorite","another favorite"),latest);
        try{PresetList.edit(latest,"already removed","replacement");throw new AssertionError("stale entry silently changed");}catch(IllegalStateException expected){}
        System.out.println("PresetList: edits and deletes preserve newly added favorites");
    }
    static void eq(Object a,Object b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
}
