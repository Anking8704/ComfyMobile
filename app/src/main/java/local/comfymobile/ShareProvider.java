package local.comfymobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only access for share targets to files in cache/share; nothing else in the app is exposed. */
public final class ShareProvider extends ContentProvider {
    static final String AUTHORITY="local.comfymobile.share";
    static Uri uriFor(File file){return Uri.parse("content://"+AUTHORITY+"/"+Uri.encode(file.getName()));}
    private File fileFor(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(name==null||getContext()==null)throw new FileNotFoundException();
        try{
            File dir=new File(getContext().getCacheDir(),"share").getCanonicalFile(),file=new File(dir,MediaRef.safeName(name)).getCanonicalFile();
            if(!dir.equals(file.getParentFile())||!file.isFile())throw new FileNotFoundException();
            return file;
        }catch(IOException e){throw new FileNotFoundException();}
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){String type=MediaRef.byExtension(uri.getLastPathSegment());return type==null?"application/octet-stream":type;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("只读");
        return ParcelFileDescriptor.open(fileFor(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        File file;try{file=fileFor(uri);}catch(FileNotFoundException e){return null;}
        MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
        cursor.addRow(new Object[]{file.getName(),file.length()});return cursor;
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
