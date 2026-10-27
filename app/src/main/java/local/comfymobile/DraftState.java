package local.comfymobile;

public final class DraftState {
    private String saved="";
    private boolean restored;
    public DraftState(String recoveredText){restored=!recoveredText.isEmpty();}
    public boolean dirty(String current){return restored || !saved.equals(current);}
    public void bind(String nodeText){restored=false;saved=nodeText;}
    public void saved(String text){restored=false;saved=text;}
}
