package local.comfymobile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs the shared merge cases; tests/web-shell.test.cjs runs the same file against the iPad page. */
public class LibraryMergeTest {
    static List<String> list(String cell){return cell.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(cell.split("\\|")));}
    public static void main(String[] args) throws Exception {
        int cases=0;
        for(String line:Files.readAllLines(Paths.get("tests/merge-cases.tsv"),StandardCharsets.UTF_8)){
            if(line.startsWith("#")||line.trim().isEmpty())continue;
            String[] c=line.split("\t",-1);
            List<String> got=LibraryMerge.merge(list(c[0]),list(c[1]),list(c[2]),60);
            if(!got.equals(list(c[3])))throw new AssertionError(line+" -> "+got);
            cases++;
        }
        if(LibraryMerge.merge(list("a|b|c"),list(""),list(""),2).size()!=2)throw new AssertionError("limit");
        System.out.println("LibraryMerge: "+cases+" shared cases passed");
    }
}
