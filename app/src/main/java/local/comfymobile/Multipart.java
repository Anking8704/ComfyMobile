package local.comfymobile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A multipart/form-data body with text fields and one file, as ComfyUI's /upload/image expects. */
public final class Multipart {
    public final String boundary;
    public final byte[] body;
    public Multipart(Map<String,String> fields,String fileField,String filename,String mime,byte[] data) {
        boundary="ComfyMobile"+Long.toHexString(System.nanoTime())+Long.toHexString(Double.doubleToLongBits(Math.random()));
        ByteArrayOutputStream out=new ByteArrayOutputStream(data.length+1024);
        for(Map.Entry<String,String> field:fields.entrySet())
            write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\""+quote(field.getKey())+"\"\r\n\r\n"+field.getValue()+"\r\n");
        write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\""+quote(fileField)+"\"; filename=\""+quote(filename)+"\"\r\nContent-Type: "+mime+"\r\n\r\n");
        out.write(data,0,data.length);
        write(out,"\r\n--"+boundary+"--\r\n");
        body=out.toByteArray();
    }
    public String contentType(){return "multipart/form-data; boundary="+boundary;}
    private static String quote(String value){return value.replace("\"","").replace("\r","").replace("\n","");}
    private static void write(ByteArrayOutputStream out,String text){byte[] b=text.getBytes(StandardCharsets.UTF_8);out.write(b,0,b.length);}
}
