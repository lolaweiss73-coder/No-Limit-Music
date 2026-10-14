package il.nolimits.music;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.Collections;

/** Full video playback for the recorded MP4, online or offline. */
public final class VideoPlaybackActivity extends Activity {
    static final String EXTRA_ID = "recorded_video_id";
    static final String EXTRA_TITLE = "recorded_video_title";
    private ExoPlayer player;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String id=getIntent().getStringExtra(EXTRA_ID);
        if (id==null || !id.matches("[A-Za-z0-9_-]{11}")) {
            finish();return;
        }
        getWindow().setStatusBarColor(0xff101018);
        getWindow().setNavigationBarColor(0xff101018);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(0xff101018);
        root.setPadding(12,16,12,12);

        TextView heading=new TextView(this);
        heading.setText(getIntent().getStringExtra(EXTRA_TITLE));
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(19);
        root.addView(heading,new LinearLayout.LayoutParams(-1,-2));

        PlayerView view=new PlayerView(this);
        view.setUseController(true);
        view.setKeepScreenOn(true);
        root.addView(view,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));

        TextView status=new TextView(this);
        status.setText(RecordedVideo.saved(this,id) ?
                "הווידאו מתנגן מהעותק המקומי" : "הווידאו מתנגן מהאתר");
        status.setTextColor(0xffb2b2c7);
        status.setTextSize(14);
        root.addView(status);

        Button back=new Button(this);
        back.setText("חזרה לספרייה");
        back.setAllCaps(false);
        back.setOnClickListener(v->finish());
        root.addView(back);
        setContentView(root);

        DefaultDataSource.Factory base=new DefaultDataSource.Factory(this);
        ResolvingDataSource.Factory secured=new ResolvingDataSource.Factory(
                base,dataSpec -> {
                    Uri uri=dataSpec.uri,server=Uri.parse(CloudUpload.baseUrl(this));
                    if ("https".equalsIgnoreCase(uri.getScheme())
                            && uri.getHost()!=null
                            && uri.getHost().equalsIgnoreCase(server.getHost())
                            && uri.getPort()==server.getPort()
                            && uri.getPath()!=null
                            && uri.getPath().matches("/api/recordings/[A-Za-z0-9_-]{11}\\.mp4")) {
                        String token=CloudUpload.authToken(this);
                        if (!token.isEmpty())
                            return dataSpec.withRequestHeaders(
                                    Collections.singletonMap("Authorization","Bearer "+token));
                    }
                    return dataSpec;
                });
        player=new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(secured))
                .build();
        view.setPlayer(player);
        player.addListener(new Player.Listener(){
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error){
                status.setText("לא ניתן לנגן את הווידאו כרגע. בדוק את חיבור האתר.");
            }
        });
        Uri playback=RecordedVideo.saved(this,id)
                ?RecordedVideo.uri(this,id):CloudUpload.recordedRemoteUri(this,id);
        player.setMediaItem(MediaItem.fromUri(playback));
        player.prepare();
        player.play();
    }

    @Override protected void onPause() {
        if(player!=null) player.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if(player!=null){player.release();player=null;}
        super.onDestroy();
    }
}
