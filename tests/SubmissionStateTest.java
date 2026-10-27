package local.comfymobile;
public class SubmissionStateTest {
    public static void main(String[] args){
        SubmissionState s=new SubmissionState();int old=s.begin();
        if(old<0||!s.pending()||s.begin()!=-1)throw new AssertionError("single flight");
        s.reset();if(s.pending())throw new AssertionError("server switch did not reset");
        int current=s.begin();if(current==old)throw new AssertionError("request epoch reused");
        if(s.finish(old)||!s.pending())throw new AssertionError("old result affected current request");
        if(!s.finish(current)||s.pending())throw new AssertionError("current completion");
        System.out.println("SubmissionState: duplicate, cancellation and stale callbacks passed");
    }
}
