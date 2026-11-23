package il.nolimits.music;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import android.view.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import com.google.common.util.concurrent.ListenableFuture;

/** Stream server/local audio through the existing background-capable MediaSessionService. */
public final class HostedTrackActivity extends Activity {
    private ListenableFuture<MediaController> future;
    private MediaController controller;
    private TextView status;
    private Button play;
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setBackgroundColor(0xff10131d);
        root.setPadding(22,32,22,20);
        TextView title=new TextView(this);title.setText(getIntent().getStringExtra("title"));
        title.setTextSize(22);title.setTextColor(Color.WHITE);root.addView(title);
        status=new TextView(this);status.setText("מתחברת לנגן השמע…");
        status.setTextColor(Color.WHITE);root.addView(status);
        play=new Button(this);play.setText("השהה");play.setAllCaps(false);
        play.setOnClickListener(v->{if(controller!=null){if(controller.isPlaying())controller.pause();else controller.play();update();}});
        root.addView(play);
        Button stop=new Button(this);stop.setText("עצור שיר");stop.setAllCaps(false);
        stop.setOnClickListener(v->{if(controller!=null)controller.stop();finish();});root.addView(stop);
        Button back=new Button(this);back.setText("חזור, המשך ברקע");back.setAllCaps(false);
        back.setOnClickListener(v->finish());root.addView(back);
        setContentView(root);
        String uri=getIntent().getStringExtra("uri");
        if(uri==null||!(uri.startsWith("https://")||uri.startsWith("file:"))){
            status.setText("הכתובת של קובץ השמע אינה תקינה.");return;
        }
        SessionToken token=new SessionToken(this,new ComponentName(this,PlaybackService.class));
        future=new MediaController.Builder(this,token).buildAsync();
        future.addListener(()->{try{
            if(isFinishing()||isDestroyed())return;
            controller=future.get();
            MediaItem item=new MediaItem.Builder().setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder()
                    .setTitle(getIntent().getStringExtra("title")).setArtist("MorinMusic").build()).build();
            controller.setMediaItem(item);controller.prepare();controller.play();
            controller.addListener(new Player.Listener(){
                @Override public void onEvents(Player p,Player.Events e){update();}
                @Override public void onPlayerError(PlaybackException e){
                    runOnUiThread(()->status.setText("קובץ השמע אינו זמין: "+e.getMessage()));}
            });
            update();
        }catch(Exception e){status.setText("נכשל חיבור לנגן: "+e.getMessage());}},
            runOnUiThreadExecutor());
    }
    private java.util.concurrent.Executor runOnUiThreadExecutor(){return this::runOnUiThread;}
    private void update(){
        if(controller==null)return;
        play.setText(controller.isPlaying()?"השהה":"נגן");
        status.setText(controller.isPlaying()?"מתנגן דרך הנגן שלנו, כולל ברקע.":"מושהה");
    }
    @Override public void onDestroy(){
        if(future!=null)MediaController.releaseFuture(future);
        super.onDestroy();
    }
}
