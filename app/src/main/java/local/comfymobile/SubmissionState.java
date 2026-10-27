package local.comfymobile;

public final class SubmissionState {
    private int epoch=0;
    private boolean pending=false;
    public boolean pending(){return pending;}
    public int begin(){if(pending)return -1;pending=true;return ++epoch;}
    public boolean finish(int request){if(request!=epoch)return false;pending=false;return true;}
    public void reset(){epoch++;pending=false;}
}
