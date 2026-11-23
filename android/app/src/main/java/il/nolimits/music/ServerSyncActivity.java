package il.nolimits.music;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * User-initiated synchronization of captured song clips to the user's server.
 * No credential is embedded; upload uses HTTPS plus bearer token.
 * This screen sends only locally prepared clips after the user presses Sync.
 */
public final class ServerSyncActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private EditText address,secret;
    private TextView status;
    private boolean running=false;
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int size){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);
        t.setPadding(dp(4),dp(8),dp(4),dp(8));return t;
    }
    private EditText edit(String label,String value){
        EditText t=new EditText(this);t.setHint(label);t.setText(value);t.setTextColor(Color.WHITE);
        t.setHintTextColor(0xffb9c5d8);t.setTextSize(16);return t;
    }
    private Button button(String label,Runnable r){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;
    }
    private void report(String message){handler.post(()->status.setText(message));}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);layout.setPadding(dp(18),dp(24),dp(18),dp(24));
        layout.setBackgroundColor(0xff10131d);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);
        layout.addView(text("☁️ שרת המדיה שלנו",24));
        layout.addView(text("חבר את השרת ששומר קובצי M4A ו־MP4. ההעלאה מתבצעת רק כשלוחצים על סנכרון.",14));
        address=edit("https://server.example.com/",ServerMediaConfig.base(this));layout.addView(address);
        secret=edit("מפתח גישה לשרת (נשמר מוצפן)","");
        secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(secret);
        layout.addView(button("💾 שמור כתובת ומפתח",this::save));
        layout.addView(button("בדוק חיבור לשרת",this::testServer));
        layout.addView(button("⬆ סנכרן את כל הקליפים לשרת",this::uploadAll));
        layout.addView(button("מחק מפתח גישה שמור",()->{
            ServerMediaConfig.clearToken(this);secret.setText("");report("מפתח השרת נמחק מהמכשיר.");
        }));
        status=text("עדיין לא בוצע סנכרון.",14);layout.addView(status);
        layout.addView(text("לפני העלאה, סיים סשן הקלטה ולחץ ״חתוך לפי סימונים״ במסך ההקלטות. "
                +"כל שיר נשלח בשתי גרסאות כשהן קיימות. קליפים שכבר מאוחסנים בשרת אינם נשלחים שוב. "
                +"גרסת הבדיקה דורשת להשאיר את המסך פתוח במהלך העלאה ארוכה; בהמשך נוסיף העלאה נמשכת ברקע.",12));
        setContentView(scroll);
    }
    private void save(){
        try{
            ServerMediaConfig.save(this,address.getText().toString(),secret.getText().toString());
            secret.setText("");report("הכתובת נשמרה והמפתח מוצפן בטלפון.");
        }catch(Exception e){report("שמירה נכשלה: "+e.getMessage());}
    }
    private JSONObject fetchCatalog(String base,String token)throws Exception{
        HttpURLConnection conn=(HttpURLConnection)new URL(base+"api/catalog").openConnection();
        conn.setRequestProperty("Authorization","Bearer "+token);
        conn.setConnectTimeout(16000);conn.setReadTimeout(30000);
        try{
            if(conn.getResponseCode()!=200)throw new IOException("השרת החזיר "+conn.getResponseCode()+". בדוק כתובת ומפתח.");
            return new JSONObject(read(conn.getInputStream(),2_000_000));
        }finally{conn.disconnect();}
    }
    private String read(InputStream input,int limit)throws IOException {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1){
                if(out.size()+n>limit)throw new IOException("תשובת השרת גדולה מדי");
                out.write(buf,0,n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private void testServer(){
        if(running)return;running=true;report("בודקת את השרת…");
        io.execute(()->{
            try{
                String base=ServerMediaConfig.validate(address.getText().toString());
                HttpURLConnection conn=(HttpURLConnection)new URL(base+"health").openConnection();
                conn.setConnectTimeout(15000);conn.setReadTimeout(15000);
                int statusCode=conn.getResponseCode();conn.disconnect();
                report(statusCode==200?"השרת מגיב. כעת אפשר לסנכרן.":"כתובת השרת החזירה HTTP "+statusCode);
            }catch(Exception e){report("חיבור נכשל: "+e.getMessage());}
            finally{running=false;}
        });
    }
    private static final class Upload {
        File file;String id,kind,title;
        Upload(File f,String i,String k,String t){file=f;id=i;kind=k;title=t;}
    }
    private ArrayList<Upload> localClips(){
        ArrayList<Upload> all=new ArrayList<>();
        File videos=getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        if(videos==null)return all;
        File[] sessions=new File(videos,"Sessions").listFiles(File::isDirectory);
        if(sessions==null)return all;
        Pattern pattern=Pattern.compile("^\\d+-([A-Za-z0-9_-]{11})\\.(m4a|mp4)$");
        for(File session:sessions){
            Map<String,String> names=new HashMap<>();
            File marks=new File(session,"song-marks.jsonl");
            if(marks.isFile()){
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(marks),StandardCharsets.UTF_8))){
                    String line;while((line=reader.readLine())!=null){
                        JSONObject m=new JSONObject(line);
                        String id=m.optString("videoId"),title=m.optString("title");
                        if(id.matches("[A-Za-z0-9_-]{11}")&&!title.isEmpty())names.put(id,title);
                    }
                }catch(Exception ignored){}
            }
            File[] clips=new File(session,"Clips").listFiles(File::isFile);
            if(clips==null)continue;
            for(File file:clips){
                Matcher matcher=pattern.matcher(file.getName());
                if(!matcher.matches()||file.length()<1024)continue;
                String id=matcher.group(1),type=matcher.group(2).equals("m4a")?"audio":"video";
                all.add(new Upload(file,id,type,names.getOrDefault(id,id)));
            }
        }
        all.sort(Comparator.comparing(o->o.id+"-"+o.kind));
        return all;
    }
    private void sendFile(String base,String token,Upload item)throws Exception{
        String title=URLEncoder.encode(item.title,StandardCharsets.UTF_8.name());
        URL url=new URL(base+"api/media/"+item.id+"/"+item.kind+"?title="+title);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setConnectTimeout(20000);connection.setReadTimeout(120000);
        connection.setChunkedStreamingMode(65536);
        connection.setRequestProperty("Authorization","Bearer "+token);
        connection.setRequestProperty("Content-Type","application/octet-stream");
        try{
            try(OutputStream dest=connection.getOutputStream();InputStream in=new FileInputStream(item.file)){
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new IOException("ההעלאה בוטלה");
                    dest.write(buffer,0,n);
                }
            }
            int code=connection.getResponseCode();
            if(code!=201)throw new IOException("HTTP "+code+" בהעלאת "+item.file.getName());
        }finally{connection.disconnect();}
    }
    private void uploadAll(){
        if(running)return;
        save();
        final String base=ServerMediaConfig.base(this);
        final String token;
        try{token=ServerMediaConfig.token(this);}catch(Exception e){report("מפתח השרת לא נקרא: "+e.getMessage());return;}
        if(token.length()<24){report("לא הוגדר מפתח גישה לשרת.");return;}
        ArrayList<Upload> uploads=localClips();
        if(uploads.isEmpty()){report("אין קליפים שנחתכו. חזור להקלטות וחתוך סשן.");return;}
        running=true;
        report("בודקת מה כבר נמצא בשרת…");
        io.execute(()->{
            try{
                JSONObject catalog=fetchCatalog(base,token).optJSONObject("tracks");
                if(catalog==null)catalog=new JSONObject();
                int total=0,skipped=0;
                for(Upload file:uploads){
                    JSONObject existing=catalog.optJSONObject(file.id);
                    if(existing!=null&&existing.optBoolean(file.kind)){skipped++;continue;}
                    report("מעלה "+file.file.getName()+" ("+(total+skipped+1)+"/"+uploads.size()+")");
                    sendFile(base,token,file);
                    total++;
                }
                report("הסנכרון הושלם: "+total+" קבצים הועלו, "+skipped+" כבר היו בשרת.");
            }catch(Exception e){report("הסנכרון הופסק: "+e.getMessage());}
            finally{running=false;}
        });
    }
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
