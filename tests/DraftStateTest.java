package local.comfymobile;
public class DraftStateTest {
    public static void main(String[] args){
        DraftState restored=new DraftState("手机上尚未写回的文字");
        if(!restored.dirty("手机上尚未写回的文字"))throw new AssertionError("Restored draft can be overwritten silently");
        DraftState fresh=new DraftState("");if(fresh.dirty(""))throw new AssertionError("empty draft");
        restored.bind("节点原文字");if(!restored.dirty("手机上尚未写回的文字"))throw new AssertionError("keep draft binding");
        restored.saved("手机上尚未写回的文字");if(restored.dirty("手机上尚未写回的文字"))throw new AssertionError("saved draft");
        if(!restored.dirty("再次修改"))throw new AssertionError("modified draft");
        System.out.println("DraftState: restore, bind and save passed");
    }
}
