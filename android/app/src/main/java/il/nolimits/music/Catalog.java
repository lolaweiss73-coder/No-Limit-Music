package il.nolimits.music;

import android.content.Context;
import android.app.DownloadManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.Manifest;
import android.content.pm.PackageManager;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Catalog {
    static final String ORIGIN="https://avi-music-player.lolaweiss73.chatgpt.site/";
    static final String SYNC_LIBRARY="https://no-limit-music-sync.floot.app/_api/library";
    static final class Track {
        final String id,title,src;
        Track(JSONObject o){ id=o.optString("youtubeId"); title=o.optString("title",id); src=o.optString("src",""); }
        boolean hosted(){return src.equals("media/"+id+".mp3") && id.matches("[A-Za-z0-9_-]{11}");}
        boolean cloud(){return src.equals("cloud/"+id+".mp4") && id.matches("[A-Za-z0-9_-]{11}");}
        boolean serverAudio(){return hosted()||cloud();}
    }
    static final class Playlist {
        String id,title; final ArrayList<Track> tracks=new ArrayList<>();
    }
    static ArrayList<Playlist> parse(String json) throws Exception {
        // The live sync service wraps the playlist array in a JSON object.
        // The original static website still exposes a bare array.
        JSONArray a=json.trim().startsWith("{")
                ? new JSONObject(json).getJSONArray("playlists") : new JSONArray(json);
        ArrayList<Playlist> result=new ArrayList<>();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.getJSONObject(i); Playlist p=new Playlist();p.title=o.getString("title");p.id=o.optString("id",p.title);
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
    // Always fetch the complete YouTube catalog first, even when a private
    // recording service is configured. Cloud recordings enrich track sources,
    // but must never replace YouTube playlist membership, ordering or titles.
    static ArrayList<Playlist> refresh(Context c, boolean forceSync) throws Exception {
        final File cached=new File(c.getFilesDir(),"catalog.json");
        String body;
        try {
            body=fetchCatalogJson(c,SYNC_LIBRARY,false,forceSync);
        } catch(Exception syncError) {
            // An outage must never replace already-synced playlists with an
            // older static snapshot (which may resurrect deleted songs).
            if(cached.isFile())throw syncError;
            try {
                body=fetchCatalogJson(c,ORIGIN+"playlists.json",false,false);
            } catch(Exception staticError) {
                syncError.addSuppressed(staticError);
                throw syncError;
            }
        }

        JSONArray playlistsJson=body.trim().startsWith("{")
                ? new JSONObject(body).getJSONArray("playlists") : new JSONArray(body);

        // Keep previously uploaded media playable when a cloud server is set.
        // A server outage cannot block the public library synchronization.
        if(CloudUpload.configured(c)) {
            try {
                ArrayList<Playlist> cloud=parse(fetchCatalogJson(
                        c,CloudUpload.baseUrl(c)+"api/library.json",true,false));
                HashMap<String,String> cloudFiles=new HashMap<>();
                for(Playlist p:cloud)for(Track track:p.tracks)
                    if(track.cloud())cloudFiles.put(track.id,track.src);

                for(int i=0;i<playlistsJson.length();i++){
                    JSONArray tracks=playlistsJson.getJSONObject(i).getJSONArray("tracks");
                    for(int j=0;j<tracks.length();j++){
                        JSONObject track=tracks.getJSONObject(j);
                        String id=track.optString("youtubeId","");
                        String src=track.optString("src","");
                        // Prefer server-hosted audio already in the catalog.
                        if(!src.equals("media/"+id+".mp3")&&cloudFiles.containsKey(id))
                            track.put("src",cloudFiles.get(id));
                    }
                }
            }catch(Exception cloudError){
                android.util.Log.w("NoLimitCatalog",
                        "Cloud media metadata unavailable; keeping live playlist catalog",
                        cloudError);
            }
        }
        String complete=playlistsJson.toString();
        ArrayList<Playlist> parsed=parse(complete);
        android.util.AtomicFile file=new android.util.AtomicFile(cached);
        FileOutputStream out=null;
        try {
            out=file.startWrite();
            out.write(complete.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch(IOException e){
            if(out!=null)file.failWrite(out);
            throw e;
        }
        return parsed;
    }
    private static String fetchCatalogJson(
            Context c,String uri,boolean withAuth,boolean forceSync) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(uri).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(forceSync?90000:20000);
        connection.setUseCaches(!forceSync);
        if(forceSync){
            connection.setRequestProperty("Cache-Control","no-cache, no-store, max-age=0");
            connection.setRequestProperty("Pragma","no-cache");
        }
        if(withAuth)connection.setRequestProperty("Authorization","Bearer "+CloudUpload.authToken(c));
        try {
            if(connection.getResponseCode()!=200)
                throw new IOException("HTTP "+connection.getResponseCode()+" from playlist source");
            return read(connection.getInputStream());
        } finally {connection.disconnect();}
    }
    static ArrayList<Playlist> refresh(Context c) throws Exception {return refresh(c,false);}
    static File local(Context c,Track t){return new File(c.getExternalFilesDir("music"),t.id+".mp3");}
    static long downloadId(Context c,Track t){return c.getSharedPreferences("downloads",0).getLong(t.id,-1);}
    static int downloadStatus(Context c,Track t){
        long id=downloadId(c,t);if(id<0)return -1;
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        try(Cursor cur=dm.query(new DownloadManager.Query().setFilterById(id))){
            return cur!=null&&cur.moveToFirst()?cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)):-1;
        }catch(Exception e){return -1;}
    }
    static Uri downloadedUri(Context c, Track t) {
        Uri publicUri = PublicMusicStorage.find(c,t.id,"mp3");
        if(publicUri != null) return publicUri;
        if(downloadStatus(c,t)==DownloadManager.STATUS_SUCCESSFUL) {
            DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri=dm.getUriForDownloadedFile(downloadId(c,t));
            if(uri!=null) {
                PublicMusicStorage.downloaded(c,t.id,"mp3",uri);
                return uri;
            }
        }
        return null;
    }
    static boolean saved(Context c,Track t){
        return downloadedUri(c,t)!=null || (local(c,t).isFile() && downloadStatus(c,t)==DownloadManager.STATUS_SUCCESSFUL);
    }
    static Uri uri(Context c,Track t){
        Uri publicFile=downloadedUri(c,t);
        return publicFile!=null?publicFile:local(c,t).isFile()?Uri.fromFile(local(c,t)):
                t.cloud()?CloudUpload.recordedRemoteUri(c,t.id):Uri.parse(ORIGIN+t.src);
    }
    static boolean removeDownload(Context c,Track t) {
        boolean ok=PublicMusicStorage.remove(c,t.id,"mp3");
        long old=downloadId(c,t);
        if(old>=0) ((DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE)).remove(old);
        File legacy=local(c,t);
        if(legacy.exists()&&!legacy.delete()) ok=false;
        if(ok) c.getSharedPreferences("downloads",0).edit().remove(t.id).apply();
        return ok;
    }
    static void download(Context c,Track t){
        if(!t.hosted())throw new IllegalArgumentException("Not a server track");
        if(saved(c,t))return;
        int status=downloadStatus(c,t);
        if(status==DownloadManager.STATUS_PENDING||status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PAUSED)return;
        if(Build.VERSION.SDK_INT<29 &&
                c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Storage access permission needed on Android 8/9");
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        long previous=downloadId(c,t);if(previous>=0)dm.remove(previous);
        File partial=local(c,t);if(partial.exists()&&!partial.delete())throw new IllegalStateException("Cannot remove partial download");
        DownloadManager.Request req=new DownloadManager.Request(Uri.parse(ORIGIN+t.src))
            .setTitle(t.title).setDescription("מוזיקה ללא גבולות")
            .setMimeType("audio/mpeg").setAllowedOverMetered(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "NoLimitMusic/"+PublicMusicStorage.cleanName(PublicMusicStorage.album(c,t.id))
                            +"/"+t.id+".mp3");
        long id=dm.enqueue(req);c.getSharedPreferences("downloads",0).edit().putLong(t.id,id).apply();
    }
}
