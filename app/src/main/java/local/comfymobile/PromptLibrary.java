package local.comfymobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

final class PromptLibrary {
    static final class Entry {
        final String label,text,translation,category,note,negative,keywords;
        final int savedIndex;
        Entry(String label,String text,String translation,String category,String note,String negative,String keywords,int savedIndex){
            this.label=label;this.text=text;this.translation=translation;this.category=category;this.note=note;this.negative=negative;this.keywords=keywords;this.savedIndex=savedIndex;
        }
    }
    final List<Entry> terms=new ArrayList<>(),templates=new ArrayList<>();
    PromptLibrary(Context context)throws Exception{
        JSONObject data;
        try(InputStream in=context.getAssets().open("prompt-library.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            ServerConnection.copy(in,out,2*1024*1024);data=new JSONObject(out.toString("UTF-8"));
        }
        JSONArray categories=data.getJSONArray("categories");
        for(int i=0;i<categories.length();i++){
            JSONObject category=categories.getJSONObject(i);JSONArray items=category.getJSONArray("items");
            for(int j=0;j<items.length();j++){
                JSONObject item=items.getJSONObject(j);terms.add(new Entry(item.getString("label"),item.getString("text"),item.optString("translation",item.getString("label")),category.getString("label"),item.optString("note"),"",item.optString("keywords"),-1));
            }
        }
        JSONArray items=data.getJSONArray("templates");
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);templates.add(new Entry(item.getString("label"),item.getString("text"),item.optString("translation",item.getString("label")),"内置模板",item.optString("note"),item.optString("negative"),item.optString("category"),-1));
        }
    }
}
