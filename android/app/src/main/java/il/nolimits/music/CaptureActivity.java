package il.nolimits.music;

import android.app.*;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** User-facing control for a single ongoing capture and local recording library. */
public final class CaptureActivity extends Activity {
    private static final int PROJECTION_REQUEST=8101,EXPORT_REQUEST=8102,AUDIO_REQUEST=8103;
    private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private LinearLayout sessions;
    private TextView status;
    private File chosenFile;
    private final Handler ui=new Handler(Looper.getMainLooper());

    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private TextView text(String s,int size){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);
        t.setPadding(dp(5),dp(8),dp(5),dp(8));return t;
    }
    private Button button(String title,Runnable callback){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->callback.run());return b;
    }
    private void add(LinearLayout l,View v){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);l.addView(v,p);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff10131d);getWindow().setNavigationBarColor(0xff10131d);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setBackgroundColor(0xff10131d);
        root.setPadding(dp(14),dp(16),dp(14),dp(28));scroll.addView(root);
        add(root,text("🎥  הקלטת האזנה רציפה",24));
        add(root,text("אישור אחד לכל סשן. התוצאה היא וידאו עם שמע, לצד קובץ שמע נפרד. אפשר להישאר בהקלטה בזמן מעבר בין שירים.",14));
        add(root,button("▶ התחל סשן הקלטה",this::askProjection));
        add(root,button("■ סיים סשן ועבד את הקבצים",this::stopCapture));
        add(root,button("🎵 עבור לפלייליסטים שלי",()->startActivity(new Intent(this,YouTubeLibraryActivity.class))));
        add(root,button("סמן תחילת שיר עכשיו",()->mark("start","סימון ידני","")));
        add(root,button("סמן סוף שיר עכשיו",()->mark("end","סימון ידני","")));
        status=text("מוכנה",14);add(root,status);
        add(root,text("הסשנים נשמרים מקומית בטלפון. כדי להפוך סימוני התחלה וסיום לקליפים וקובצי M4A, השתמש בכפתור ״חתוך לפי סימונים״. החיתוך נשען על הסימונים, ואינו מנתח פרסומות בעצמו.",12));
        add(root,button("☁️ סנכרון הקליפים לשרת",()->startActivity(new Intent(this,ServerSyncActivity.class))));
        add(root,button("רענן ספריית הקלטות",this::render));
        sessions=new LinearLayout(this);sessions.setOrientation(LinearLayout.VERTICAL);
        sessions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);add(root,sessions);
        setContentView(scroll);render();
    }
    private void askProjection(){
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},AUDIO_REQUEST);
            return;
        }
        if(Build.VERSION.SDK_INT<29){status.setText("הקלטת שמע פנימי דורשת Android 10 ומעלה.");return;}
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        try{startActivityForResult(m.createScreenCaptureIntent(),PROJECTION_REQUEST);}
        catch(Exception e){status.setText("מסך ההרשאה לא נפתח: "+e.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==AUDIO_REQUEST){
            if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)
                askProjection();
            else status.setText("בלי הרשאת שמע, לא ניתן להקליט צליל פנימי.");
        }
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==PROJECTION_REQUEST){
            if(result!=RESULT_OK||data==null){status.setText("המשתמש ביטל את הרשאת ההקלטה.");return;}
            try{
                Intent start=new Intent(this,CaptureService.class).setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE,result)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA,data);
                startForegroundService(start);
                status.setText("התחלתי סשן חדש. אפשר לעבור עכשיו לנגן ולהשמיע שירים.");
                ui.postDelayed(this::render,1800);
            }catch(Exception e){status.setText("אי אפשר להתחיל הקלטה: "+e.getMessage());}
        }else if(req==EXPORT_REQUEST){
            if(result!=RESULT_OK||data==null||chosenFile==null)return;
            Uri out=data.getData();
            if(out==null)return;
            File source=chosenFile;chosenFile=null;
            jobs.execute(()->{
                try(InputStream in=new FileInputStream(source);
                    OutputStream dest=getContentResolver().openOutputStream(out)){
                    if(dest==null)throw new IOException("אי אפשר לפתוח קובץ יעד");
                    byte[] b=new byte[65536];int n;
                    while((n=in.read(b))!=-1)dest.write(b,0,n);
                    ui.post(()->status.setText("הקובץ יוצא ליעד שבחרת."));
                }catch(Exception e){ui.post(()->status.setText("ייצוא נכשל: "+e.getMessage()));}
            });
        }
    }
    private void stopCapture(){
        Intent stop=new Intent(this,CaptureService.class).setAction(CaptureService.ACTION_STOP);
        startService(stop);
        status.setText("מסיימת את הסשן ומכינה את הקבצים. הפעולה יכולה להימשך בהתאם לאורך ההקלטה.");
        ui.postDelayed(this::render,3000);
    }
    private void mark(String kind,String title,String id){
        Intent command=new Intent(this,CaptureService.class).setAction(CaptureService.ACTION_MARK);
        command.putExtra(CaptureService.EXTRA_MARK_KIND,kind)
            .putExtra(CaptureService.EXTRA_MARK_TITLE,title)
            .putExtra(CaptureService.EXTRA_MARK_ID,id);
        startService(command);status.setText("נשלח סימון "+("start".equals(kind)?"תחילת":"סוף")+" השיר.");
    }
    private File rootFolder(){
        File root=getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        return root==null?null:new File(root,"Sessions");
    }
    private void render(){
        if(sessions==null)return;
        sessions.removeAllViews();
        status.setText(getSharedPreferences(CaptureService.PREFS,MODE_PRIVATE).getString("status","לא התחילה הקלטה."));
        File root=rootFolder();
        if(root==null||!root.exists()){add(sessions,text("אין הקלטות עדיין.",14));return;}
        File[] folders=root.listFiles(File::isDirectory);
        if(folders==null||folders.length==0){add(sessions,text("אין הקלטות עדיין.",14));return;}
        Arrays.sort(folders,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File folder:folders){
            add(sessions,text("📁 "+folder.getName(),17));
            File merged=new File(folder,"session-video-audio.mp4");
            File screen=new File(folder,"screen-video.mp4");
            File audio=new File(folder,"playback-audio.m4a");
            File main=merged.exists()?merged:screen;
            if(main.exists())add(sessions,button("💾 ייצוא וידאו  ("+mb(main)+" MB)",()->export(main)));
            if(audio.exists())add(sessions,button("🎧 ייצוא שמע  ("+mb(audio)+" MB)",()->export(audio)));
            if(main.exists())add(sessions,button("✂ חתוך שירים לפי סימונים",()->split(folder)));
            File clips=new File(folder,"Clips");
            File[] files=clips.listFiles(File::isFile);
            if(files!=null){
                Arrays.sort(files,Comparator.comparing(File::getName));
                for(File f:files)add(sessions,button("↗ "+f.getName()+" ("+mb(f)+" MB)",()->export(f)));
            }
        }
    }
    private String mb(File f){return String.format(Locale.US,"%.1f",f.length()/1048576.0);}
    private void split(File folder){
        status.setText("חותכת לפי סימוני תחילת וסוף שירים…");
        jobs.execute(()->{
            try{int count=SessionMediaProcessor.splitMarkedSession(folder);
                ui.post(()->{status.setText("נוצרו "+count+" קטעי וידאו, עם שמע כאשר הוא זמין.");render();});
            }catch(Exception e){ui.post(()->status.setText("אי אפשר לחתוך: "+e.getMessage()));}
        });
    }
    private void export(File file){
        chosenFile=file;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE).setType(file.getName().endsWith(".m4a")?"audio/mp4":"video/mp4");
        intent.putExtra(Intent.EXTRA_TITLE,file.getName());
        try{startActivityForResult(intent,EXPORT_REQUEST);}
        catch(Exception e){status.setText("לא ניתן לפתוח בחירת תיקייה: "+e.getMessage());}
    }
    @Override protected void onDestroy(){jobs.shutdown();super.onDestroy();}
}
