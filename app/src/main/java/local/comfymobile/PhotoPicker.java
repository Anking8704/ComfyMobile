package local.comfymobile;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

/** A file chooser that also offers "take a photo" when images are accepted; photos go to Pictures/ComfyMobile. */
final class PhotoPicker {
    final Intent intent;
    private final Activity activity;
    private Uri camera;

    PhotoPicker(Activity a,String[] types,boolean multiple){
        activity=a;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType("*/*");
        if(types.length>0&&!types[0].isEmpty()&&!types[0].startsWith("."))picker.putExtra(Intent.EXTRA_MIME_TYPES,types);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multiple);
        intent=Intent.createChooser(picker,"选择文件");
        boolean images=types.length==0;for(String t:types)if(t.isEmpty()||t.startsWith("image")||t.equals("*/*"))images=true;
        Intent capture=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if(!images||capture.resolveActivity(a.getPackageManager())==null)return;
        try{
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,"ComfyMobile-"+System.currentTimeMillis()+".jpg");
            values.put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/ComfyMobile");
            camera=a.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(camera==null)return;
            capture.putExtra(MediaStore.EXTRA_OUTPUT,camera);capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_INITIAL_INTENTS,new Intent[]{capture});
        }catch(Exception e){camera=null;}
    }
    /** The chosen files, or the photo the camera wrote; an unused camera placeholder is removed. */
    Uri[] result(int resultCode,Intent data){
        Uri[] uris=null;
        if(resultCode==Activity.RESULT_OK&&data!=null&&data.getClipData()!=null){uris=new Uri[data.getClipData().getItemCount()];for(int i=0;i<uris.length;i++)uris[i]=data.getClipData().getItemAt(i).getUri();}
        else if(resultCode==Activity.RESULT_OK&&data!=null&&data.getData()!=null)uris=new Uri[]{data.getData()};
        else if(resultCode==Activity.RESULT_OK&&camera!=null&&hasContent(camera)){Uri photo=camera;camera=null;return new Uri[]{photo};}
        discard();return uris;
    }
    private boolean hasContent(Uri uri){
        try(Cursor c=activity.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.SIZE},null,null,null)){return c!=null&&c.moveToFirst()&&c.getLong(0)>0;}
        catch(Exception e){return false;}
    }
    void discard(){if(camera!=null){try{activity.getContentResolver().delete(camera,null,null);}catch(Exception ignored){}camera=null;}}
}
