package il.nolimits.music;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Saves only owner-approved lyric files exposed by the separate music sync server. */
final class LyricsCache {
    private static final String ENDPOINT="https://no-limit-music-sync.floot.app/_api/lyrics?youtubeId=";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final long DAY=24L*60*60*1000;
    private static final int LIMIT=220_000;

    static void fetchAsync(Context context,Catalog.Track track){
        if(!track.hosted()||!Catalog.saved(context,track))return;
        Context app=context.getApplicationContext();
        SharedPreferences state=app.getSharedPreferences("lyrics_cache",0);
        if(state.getBoolean(track.id+"_saved",false))return;
        if(System.currentTimeMillis()-state.getLong(track.id+"_attempt",0)<DAY)return;
        state.edit().putLong(track.id+"_attempt",System.currentTimeMillis()).apply();
        IO.execute(()->{
            HttpURLConnection con=null;
            try{
                con=(HttpURLConnection)new URL(ENDPOINT+track.id).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(12000);
                if(con.getResponseCode()==404)return;
                if(con.getResponseCode()!=200)return;
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=con.getInputStream()){
                    byte[] buffer=new byte[8192];
                    int count;
                    while((count=in.read(buffer))>=0){
                        bytes.write(buffer,0,count);
                        if(bytes.size()>LIMIT)throw new IllegalStateException("Lyrics response too large");
                    }
                }
                JSONObject data=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                if(!data.optBoolean("available",false))return;
                String format=data.optString("format","txt");
                if(!format.equals("lrc")&&!format.equals("txt"))return;
                String lyrics=data.optString("text","");
                if(lyrics.isEmpty()||lyrics.length()>200_000)return;
                String filename=Catalog.safeFileName(track.title)+" ["+track.id+"]."+format;
                String folder=Catalog.destinationDirectory(app,track);
                byte[] body=lyrics.getBytes(StandardCharsets.UTF_8);
                if(Build.VERSION.SDK_INT>=29){
                    ContentValues values=new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);
                    values.put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS+"/"+folder+"/");
                    values.put(MediaStore.MediaColumns.IS_PENDING,1);
                    Uri uri=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                    if(uri==null)throw new IllegalStateException("Unable to create lyrics file");
                    boolean completed=false;
                    try(OutputStream out=app.getContentResolver().openOutputStream(uri)){
                        if(out==null)throw new IllegalStateException("Unable to open lyrics file");
                        out.write(body);
                        completed=true;
                    } finally {
                        if(!completed)app.getContentResolver().delete(uri,null,null);
                    }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING,0);
                    app.getContentResolver().update(uri,values,null,null);
                }else{
                    File dir=new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),folder);
                    if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Unable to create lyrics directory");
                    try(OutputStream out=new FileOutputStream(new File(dir,filename))){out.write(body);}
                }
                state.edit().putBoolean(track.id+"_saved",true).apply();
            }catch(Exception e){
                android.util.Log.w("NoLimitLyrics","Lyrics not saved for "+track.id,e);
            }finally{
                if(con!=null)con.disconnect();
            }
        });
    }

    private LyricsCache(){}
}
