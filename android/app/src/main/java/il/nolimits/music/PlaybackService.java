package il.nolimits.music;

import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import android.net.Uri;
import java.util.Collections;
import java.util.Map;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class PlaybackService extends MediaSessionService {
    private MediaSession session;
    @Override public void onCreate() {
        super.onCreate();
        // Authorization is attached only to the recording endpoint, never to
        // unrelated media hosts or YouTube embeds.
        DefaultDataSource.Factory baseSource = new DefaultDataSource.Factory(this);
        ResolvingDataSource.Factory securedSource = new ResolvingDataSource.Factory(
                baseSource, dataSpec -> {
                    Uri uri = dataSpec.uri;
                    Uri base = Uri.parse(CloudUpload.baseUrl(this));
                    if ("https".equalsIgnoreCase(uri.getScheme())
                            && uri.getHost() != null
                            && uri.getHost().equalsIgnoreCase(base.getHost())
                            && uri.getPort() == base.getPort()
                            && uri.getPath() != null
                            && uri.getPath().startsWith("/api/recordings/")) {
                        String token = CloudUpload.authToken(this);
                        if (!token.isEmpty()) {
                            return dataSpec.withRequestHeaders(
                                    Collections.singletonMap("Authorization","Bearer " + token));
                        }
                    }
                    return dataSpec;
                });
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(securedSource))
                .build();
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
