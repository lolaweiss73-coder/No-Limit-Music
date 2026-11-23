package il.nolimits.music;

import android.app.*;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import android.view.*;

/** Device-wide default for choosing visual clip vs audio when both exist. */
public final class MediaPreferencesActivity extends Activity {
    @Override public void onCreate(Bundle s){
        super.onCreate(s);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setBackgroundColor(0xff10131d);
        root.setPadding(26,28,26,24);
        TextView title=new TextView(this);title.setText("🎧 העדפת ניגון");
        title.setTextSize(24);title.setTextColor(Color.WHITE);root.addView(title);
        RadioGroup group=new RadioGroup(this);group.setOrientation(RadioGroup.VERTICAL);
        RadioButton video=new RadioButton(this);video.setId(View.generateViewId());
        video.setText("וידאו + שמע כשיש קליפ");video.setTextColor(Color.WHITE);
        RadioButton audio=new RadioButton(this);audio.setId(View.generateViewId());
        audio.setText("שמע בלבד כשקיים עותק שמע");audio.setTextColor(Color.WHITE);
        group.addView(video);group.addView(audio);
        group.check(MediaSourceResolver.preferVideo(this)?video.getId():audio.getId());
        group.setOnCheckedChangeListener((g,id)->MediaSourceResolver.setPreferVideo(this,id==video.getId()));
        root.addView(group);
        TextView note=new TextView(this);
        note.setText("העדפה זו קובעת איזה קובץ ינוגן. אם קיימת גרסה מקומית, האפליקציה מעדיפה אותה. אם השיר עדיין רק ב־YouTube, הוא מתנגן בנגן המוטמע. ההקלטה שומרת וידאו ושמע בנפרד בלי קשר להעדפה הזאת.");
        note.setTextSize(14);note.setTextColor(0xffc3cedc);
        note.setPadding(0,20,0,20);root.addView(note);
        Button done=new Button(this);done.setText("סיים");done.setOnClickListener(v->finish());root.addView(done);
        setContentView(root);
    }
}
