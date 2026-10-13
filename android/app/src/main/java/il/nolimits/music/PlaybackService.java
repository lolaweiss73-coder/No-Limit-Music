package il.nolimits.music;

import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class PlaybackService extends MediaSessionService {
    private MediaSession session;
    @Override public void onCreate() {
        super.onCreate();
        ExoPlayer player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this,player).setSessionActivity(open).build();
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo info) { return session; }
    @Override public void onDestroy() {
        if(session!=null){ session.getPlayer().release(); session.release(); session=null; }
        super.onDestroy();
    }
}
