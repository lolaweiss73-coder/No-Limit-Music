package il.nolimits.music;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.File;

/** Preview a locally produced MP4; does not invoke YouTube. */
public final class LocalVideoActivity extends Activity {
    private VideoView video;
    @Override public void onCreate(Bundle s){
        super.onCreate(s);
        String path=getIntent().getStringExtra("file");
        if(path==null||!new File(path).isFile()){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);root.setPadding(12,12,12,12);
        TextView title=new TextView(this);title.setText(getIntent().getStringExtra("title"));
        title.setTextColor(Color.WHITE);title.setTextSize(18);root.addView(title);
        video=new VideoView(this);
        video.setVideoURI(Uri.fromFile(new File(path)));
        MediaController controls=new MediaController(this);
        controls.setAnchorView(video);video.setMediaController(controls);
        root.addView(video,new LinearLayout.LayoutParams(-1,0,1));
        Button back=new Button(this);back.setText("סגור קליפ");
        back.setOnClickListener(v->finish());root.addView(back);
        setContentView(root);
        video.setOnPreparedListener(mp->{mp.setLooping(false);video.start();});
        video.setOnErrorListener((mp,what,extra)->{
            title.setText("שגיאה בפענוח הקליפ המקומי.");return true;
        });
    }
    @Override public void onPause(){if(video!=null)video.pause();super.onPause();}
    @Override public void onDestroy(){if(video!=null)video.stopPlayback();super.onDestroy();}
}
