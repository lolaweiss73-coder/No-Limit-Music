package il.nolimits.music;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.util.*;

/** One source-of-truth decision for account tracks: local capture, server audio, or YouTube. */
final class MediaSourceResolver {
    private MediaSourceResolver(){}
    static final String PREFS="morin_playback_prefs";
    static boolean preferVideo(Context c){
        return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean("prefer_video",true);
    }
    static void setPreferVideo(Context c,boolean value){
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("prefer_video",value).apply();
    }
    static File local(Context c,String id,String extension){
        if(id==null||!id.matches("[A-Za-z0-9_-]{11}"))return null;
        File movies=c.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if(movies==null)return null;
        File parent=new File(movies,"Sessions"),best=null;
        File[] sessions=parent.listFiles(File::isDirectory);
        if(sessions==null)return null;
        for(File folder:sessions){
            File clips=new File(folder,"Clips");
            File[] files=clips.listFiles(f->f.isFile()&&f.getName().endsWith("-"+id+"."+extension));
            if(files!=null)for(File file:files){
                if(best==null||file.lastModified()>best.lastModified())best=file;
            }
        }
        return best;
    }
    static boolean localVideo(Context c,String id){return local(c,id,"mp4")!=null;}
    static boolean localAudio(Context c,String id){return local(c,id,"m4a")!=null;}
    static String label(Context c,String id,boolean serverAudio){
        if(preferVideo(c)&&localVideo(c,id))return "📼 וידאו מקומי";
        if(localAudio(c,id))return "🎧 שמע מקומי";
        if(serverAudio&&!preferVideo(c))return "☁️ שמע בשרת";
        if(serverAudio)return "☁️ שמע בשרת · ▶ YouTube לווידאו";
        return "▶ YouTube";
    }
    static Intent intentFor(Context c,String id,String title,boolean serverAudio){
        boolean video=preferVideo(c);
        File localVideo=local(c,id,"mp4");
        File localAudio=local(c,id,"m4a");
        if(video&&localVideo!=null)
            return new Intent(c,LocalVideoActivity.class).putExtra("file",localVideo.getAbsolutePath())
                .putExtra("title",title);
        if(localAudio!=null)
            return new Intent(c,HostedTrackActivity.class)
                .putExtra("uri",Uri.fromFile(localAudio).toString()).putExtra("title",title);
        if(serverAudio&&!video)
            return new Intent(c,HostedTrackActivity.class)
                .putExtra("uri",Catalog.ORIGIN+"media/"+id+".mp3").putExtra("title",title);
        return new Intent(c,YouTubePlayerActivity.class)
            .putExtra(YouTubePlayerActivity.EXTRA_ID,id)
            .putExtra(YouTubePlayerActivity.EXTRA_TITLE,title);
    }
}
