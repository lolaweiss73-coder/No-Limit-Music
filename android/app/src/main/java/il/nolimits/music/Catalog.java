package il.nolimits.music;

import android.content.Context;
import android.app.DownloadManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Catalog {
    static final String ORIGIN="https://avi-music-player.lolaweiss73.chatgpt.site/";
    static final class Track {
        final String id,title,src;
        Track(JSONObject o){ id=o.optString("youtubeId"); title=o.optString("title",id); src=o.optString("src",""); }
        boolean hosted(){return src.equals("media/"+id+".mp3") && id.matches("[A-Za-z0-9_-]{11}");}
    }
    static final class Playlist {
        String title; final ArrayList<Track> tracks=new ArrayList<>();
    }
    static ArrayList<Playlist> parse(String json) throws Exception {
        JSONArray a=new JSONArray(json); ArrayList<Playlist> result=new ArrayList<>();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.getJSONObject(i); Playlist p=new Playlist();p.title=o.getString("title");
            JSONArray ts=o.getJSONArray("tracks");
            for(int j=0;j<ts.length();j++){Track t=new Track(ts.getJSONObject(j));if(t.id.matches("[A-Za-z0-9_-]{11}"))p.tracks.add(t);}
            result.add(p);
        }
        if(result.isEmpty())throw new IOException("Empty catalog");
        return result;
    }
    static String read(InputStream in) throws IOException {try(in){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>5_000_000)throw new IOException("Catalog too large");}return out.toString(StandardCharsets.UTF_8.name());}}
    static ArrayList<Playlist> load(Context c) throws Exception {
        File cache=new File(c.getFilesDir(),"catalog.json");
        if(cache.exists())try{return parse(read(new FileInputStream(cache)));}catch(Exception ignored){}
        return parse(read(c.getAssets().open("playlists.json")));
    }
    static ArrayList<Playlist> refresh(Context c) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(ORIGIN+"playlists.json").openConnection();
        connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setUseCaches(false);
        try{
            if(connection.getResponseCode()!=200)throw new IOException("HTTP "+connection.getResponseCode());
            String body=read(connection.getInputStream());ArrayList<Playlist> parsed=parse(body);
            android.util.AtomicFile file=new android.util.AtomicFile(new File(c.getFilesDir(),"catalog.json"));
            FileOutputStream out=null;
            try{out=file.startWrite();out.write(body.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
            catch(IOException e){if(out!=null)file.failWrite(out);throw e;}
            return parsed;
        } finally {connection.disconnect();}
    }
    // Public Downloads are retained even if the Android app is uninstalled.
    private static final String DOWNLOAD_ROOT="NoLimitMusic";
    static String safeFileName(String name){
        String clean=(name==null?"":name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_")
            .replaceAll("\\s+"," ").trim().replaceAll("^[. ]+|[. ]+$","");
        if(clean.isEmpty())clean="Playlist";
        if(clean.length()>65)clean=clean.substring(0,65).trim();
        return clean.equals(".")||clean.equals("..")?"Playlist":clean;
    }
    static String destinationDirectory(Context c,Track t){
        String folder=c.getSharedPreferences("downloads",0).getString(t.id+"_playlist","Unknown");
        return DOWNLOAD_ROOT+"/"+safeFileName(folder);
    }
    static File local(Context c,Track t){
        String relative=c.getSharedPreferences("downloads",0).getString(t.id+"_path",null);
        if(relative!=null && relative.startsWith(DOWNLOAD_ROOT+"/") && !relative.contains("../"))
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),relative);
        // Migration path for songs saved with previous builds; never remove them.
        return new File(c.getExternalFilesDir("music"),t.id+".mp3");
    }
    static long downloadId(Context c,Track t){return c.getSharedPreferences("downloads",0).getLong(t.id,-1);}
    static int downloadStatus(Context c,Track t){
        long id=downloadId(c,t);if(id<0)return -1;
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        try(Cursor cur=dm.query(new DownloadManager.Query().setFilterById(id))){
            return cur!=null&&cur.moveToFirst()?cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)):-1;
        }catch(Exception e){return -1;}
    }
    static boolean saved(Context c,Track t){
        if(downloadStatus(c,t)!=DownloadManager.STATUS_SUCCESSFUL)return false;
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        return local(c,t).isFile() || dm.getUriForDownloadedFile(downloadId(c,t))!=null;
    }
    static Uri uri(Context c,Track t){
        if(saved(c,t)){
            DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri downloaded=dm.getUriForDownloadedFile(downloadId(c,t));
            if(downloaded!=null)return downloaded;
            return Uri.fromFile(local(c,t));
        }
        return Uri.parse(ORIGIN+t.src);
    }
    static void download(Context c,Track t,String playlistName){
        if(!t.hosted())throw new IllegalArgumentException("Not a server track");
        if(saved(c,t))return;
        int status=downloadStatus(c,t);
        if(status==DownloadManager.STATUS_PENDING||status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PAUSED)return;
        if(Build.VERSION.SDK_INT<29 && c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Downloads storage permission required on Android 8/9");
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        long previous=downloadId(c,t);if(previous>=0)dm.remove(previous);
        File partial=local(c,t);if(partial.exists()&&!partial.delete())throw new IllegalStateException("Cannot remove partial download");
        String folder=safeFileName(playlistName);
        String relative=DOWNLOAD_ROOT+"/"+folder+"/"+safeFileName(t.title)+" ["+t.id+"].mp3";
        DownloadManager.Request req=new DownloadManager.Request(Uri.parse(ORIGIN+t.src))
            .setTitle(t.title).setDescription("מוזיקה ללא גבולות")
            .setMimeType("audio/mpeg").setAllowedOverMetered(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,relative);
        long id=dm.enqueue(req);
        c.getSharedPreferences("downloads",0).edit()
            .putLong(t.id,id).putString(t.id+"_path",relative)
            .putString(t.id+"_playlist",folder).apply();
    }
}
