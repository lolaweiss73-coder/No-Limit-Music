package il.nolimits.music;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/**
 * User-authorized YouTube playlists via Google Identity Services, no embedded credentials.
 * A Cloud project with the YouTube Data API and an Android OAuth client registered for
 * the preview APK applicationId + SHA-1 is required before account authorization works.
 * No YouTube audiovisual content is downloaded or cached here.
 */
public final class YouTubeLibraryActivity extends Activity {
    private static final String YT_CACHE="morin_yt_metadata_v1";
    private static final String SCOPE_READ="https://www.googleapis.com/auth/youtube.readonly";
    private static final String SCOPE_WRITE="https://www.googleapis.com/auth/youtube";
    private static final int AUTH_REQUEST=7741;
    private static final int BG=0xff10131d, PANEL=0xff222a3a, TEXT=0xfff7f7fc, MUTED=0xffb9c5d8;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ExecutorService importPool=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private AuthorizationClient google;
    private String token=null;
    private boolean canWrite=false;
    private boolean writingRequested=false;
    private TextView status;
    private LinearLayout rows;
    private String currentPlaylistId=null,currentPlaylistTitle="";
    private final ArrayList<Playlist> playlists=new ArrayList<>();
    private final ArrayList<Track> tracks=new ArrayList<>();
    private final Set<String> serverAudioIds=Collections.synchronizedSet(new HashSet<>());
    private static class Playlist {
        String id,title,privacy;
        Playlist(String a,String b,String c){id=a;title=b;privacy=c;}
    }
    private static class Track {
        String id,title,videoId;int position;
        Track(String a,String b,String c,int p){id=a;title=b;videoId=c;position=p;}
    }
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private Button button(String s,Runnable r){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);
        b.setOnClickListener(v->r.run());return b;
    }
    private TextView txt(String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        t.setPadding(dp(6),dp(7),dp(6),dp(7));return t;
    }
    private LinearLayout line(){LinearLayout l=new LinearLayout(this);l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void add(LinearLayout l,View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);l.addView(v,p);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void report(String s){main.post(()->status.setText(s));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        google=Identity.getAuthorizationClient(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=column();root.setPadding(dp(14),dp(14),dp(14),dp(28));root.setBackgroundColor(BG);
        scroll.addView(root);
        add(root,txt("▶️  הפלייליסטים שלי ב־YouTube",24,TEXT));
        add(root,txt("חיבור ישיר לחשבון Google בהרשאתך. השירים מתנגנים בנגן המוטמע הרשמי, עם כל מגבלות YouTube.",13,MUTED));
        LinearLayout top=line();
        top.addView(button("התחבר וקרא",()->authorize(false)),new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("אפשר עריכה",()->authorize(true)),new LinearLayout.LayoutParams(0,-2,1));
        add(root,top);
        LinearLayout nav=line();
        nav.addView(button("רענן",()->{if(token==null)authorize(canWrite);else loadPlaylists();}),new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(button("חזור לרשימות",()->{currentPlaylistId=null;render();}),new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(button("נתק חשבון",this::confirmDisconnect),new LinearLayout.LayoutParams(0,-2,1));
        add(root,button("☁️ רענן את כל השירים מכל הפלייליסטים",this::syncAllPlaylistTracks));
        add(root,nav);
        LinearLayout mediaNav=line();
        mediaNav.addView(button("🎥 הקלטה",()->startActivity(new Intent(this,CaptureActivity.class))),new LinearLayout.LayoutParams(0,-2,1));
        mediaNav.addView(button("⚙ שמע / וידאו",()->startActivity(new Intent(this,MediaPreferencesActivity.class))),new LinearLayout.LayoutParams(0,-2,1));
        add(root,mediaNav);
        status=txt("עדיין לא התחברת ל־Google.",13,MUTED);add(root,status);
        rows=column();add(root,rows);
        add(root,txt("נדרשת הגדרה חד־פעמית של Google Cloud: YouTube Data API v3, מסך הסכמה ו־OAuth client מסוג Android עבור חבילת גרסת הבדיקה וחתימת APK. הרשאת קריאה אינה מאפשרת שינויים. אין הורדה, הסרת פרסומות או עקיפת מגבלות רקע.",12,MUTED));
        add(root,button("תנאי השירות של YouTube",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/t/terms")))));
        setContentView(scroll);
        render();
        loadHostedIndex();
    }
    private void authorize(boolean write){
        writingRequested=write;
        String scope=write?SCOPE_WRITE:SCOPE_READ;
        report("פותחת הרשאת Google "+(write?"לעריכת הפלייליסטים":"לקריאת הפלייליסטים")+"…");
        AuthorizationRequest request=AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(scope))).build();
        google.authorize(request).addOnSuccessListener(this,this::handleAuth)
                .addOnFailureListener(this,e->report("Google לא אישרה חיבור: "+e.getMessage()
                        +"\nבדוק ש־OAuth Android ו־YouTube Data API מוגדרים לפרויקט."));
    }
    private void handleAuth(AuthorizationResult r){
        if(r.hasResolution()){
            try{
                startIntentSenderForResult(r.getPendingIntent().getIntentSender(),
                        AUTH_REQUEST,null,0,0,0);
            }catch(Exception e){report("אי אפשר לפתוח את מסך ההרשאה: "+e.getMessage());}
            return;
        }
        String t=r.getAccessToken();
        if(t==null||t.isEmpty()){report("Google לא החזירה הרשאת גישה.");return;}
        token=t;
        canWrite=r.getGrantedScopes().contains(SCOPE_WRITE);
        report(canWrite?"מחובר עם הרשאת עריכה.":"מחובר לקריאה בלבד. בחר 'אפשר עריכה' רק אם ברצונך לשנות רשימות.");
        loadPlaylists();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=AUTH_REQUEST)return;
        if(result!=RESULT_OK||data==null){report("ההתחברות בוטלה.");return;}
        try{handleAuth(google.getAuthorizationResultFromIntent(data));}
        catch(Exception e){report("הרשאת Google נכשלה: "+e.getMessage());}
    }
    private void confirmDisconnect(){
        new AlertDialog.Builder(this).setTitle("לנתק את YouTube?")
            .setMessage("הרשאת Google תבוטל ונתוני הרשימות שהוצגו יוסרו מזיכרון האפליקציה.")
            .setPositiveButton("נתק",(d,w)->{
                google.revokeAccess(RevokeAccessRequest.builder().setScopes(Arrays.asList(new Scope(SCOPE_READ),new Scope(SCOPE_WRITE))).build()).addOnCompleteListener(task->{
                    token=null;canWrite=false;currentPlaylistId=null;playlists.clear();tracks.clear();
                    getSharedPreferences(YT_CACHE,MODE_PRIVATE).edit().clear().apply();
                    render();report(task.isSuccessful()?"החשבון נותק.":"הנתונים נוקו. ייתכן שנדרש להסיר את הגישה גם בהגדרות חשבון Google.");
                });
            }).setNegativeButton("ביטול",null).show();
    }
    private JSONObject api(String method,String path,JSONObject payload) throws Exception{
        String access=token;
        if(access==null)throw new IOException("צריך להתחבר ל־Google");
        URL u=new URL("https://www.googleapis.com/youtube/v3/"+path);
        HttpsURLConnection c=(HttpsURLConnection)u.openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(25000);
        c.setRequestProperty("Authorization","Bearer "+access);
        c.setRequestProperty("Accept","application/json");
        if(payload!=null){
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=c.getOutputStream()){out.write(body);}
        }
        int code=c.getResponseCode();
        InputStream stream=code<400?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        if(stream!=null)try(InputStream in=stream){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);}
        c.disconnect();
        String data=new String(b.toByteArray(),StandardCharsets.UTF_8);
        if(code>=400){
            String message=data;
            try{message=new JSONObject(data).getJSONObject("error").optString("message",data);}catch(Exception ignored){}
            throw new IOException("YouTube "+code+": "+message);
        }
        return data.isEmpty()?new JSONObject():new JSONObject(data);
    }
    private String enc(String s){return Uri.encode(s);}
    private void network(String loading,Work job,Runnable success){
        report(loading);executor.execute(()->{
            try{job.run();main.post(()->{success.run();});}
            catch(Exception e){main.post(()->{
                String m=e.getMessage();
                report("שגיאת YouTube: "+m);
                if(m!=null&&m.contains("401"))toast("תוקף ההרשאה פג. התחבר שוב כדי להמשיך.");
            });}
        });
    }
    private interface Work{void run() throws Exception;}
    private void loadPlaylists(){
        if(token==null){authorize(false);return;}
        network("מייבאת את הפלייליסטים בחשבון שלך…",()->{
            ArrayList<Playlist> result=new ArrayList<>();String page="";
            do{
                JSONObject o=api("GET","playlists?part=snippet,status&mine=true&maxResults=50"+
                        (page.isEmpty()?"":"&pageToken="+enc(page)),null);
                JSONArray list=o.optJSONArray("items");
                if(list!=null)for(int i=0;i<list.length();i++){
                    JSONObject p=list.getJSONObject(i);
                    result.add(new Playlist(p.optString("id"),
                            p.optJSONObject("snippet")==null?"ללא שם":p.getJSONObject("snippet").optString("title"),
                            p.optJSONObject("status")==null?"":p.getJSONObject("status").optString("privacyStatus")));
                }
                page=o.optString("nextPageToken");
                if(result.size()>5000)throw new IOException("יותר מ־5000 רשימות, נדרש יבוא בשלבים.");
            }while(!page.isEmpty());
            synchronized(playlists){playlists.clear();playlists.addAll(result);}
        },()->{cachePlaylistMetadata();currentPlaylistId=null;render();syncAllPlaylistTracks();report("יובאו "+playlists.size()+" רשימות מהחשבון שלך. מתחילה לייבא גם את השירים. "+(canWrite?"ניתן לערוך רשימות שבבעלותך.":"לצפייה בלבד."));});
    }
    private void loadTracks(){
        String selectedId=currentPlaylistId;
        if(selectedId==null)return;
        network("קוראת את כל השירים ב־"+currentPlaylistTitle+"…",()->{
            ArrayList<Track> result=new ArrayList<>();String page="";
            do{
                JSONObject o=api("GET","playlistItems?part=snippet,contentDetails&playlistId="+enc(selectedId)+
                        "&maxResults=50"+(page.isEmpty()?"":"&pageToken="+enc(page)),null);
                JSONArray list=o.optJSONArray("items");
                if(list!=null)for(int i=0;i<list.length();i++){
                    JSONObject j=list.getJSONObject(i),snippet=j.optJSONObject("snippet");
                    if(snippet==null)continue;
                    JSONObject resource=snippet.optJSONObject("resourceId");
                    String video=resource==null?"":resource.optString("videoId");
                    result.add(new Track(j.optString("id"),snippet.optString("title"),
                            video,snippet.optInt("position",result.size())));
                }
                page=o.optString("nextPageToken");
                if(result.size()>10000)throw new IOException("הרשימה גדולה מדי לגרסת הבדיקה.");
            }while(!page.isEmpty());
            synchronized(tracks){tracks.clear();tracks.addAll(result);}
        },()->{if(selectedId.equals(currentPlaylistId)){cacheTrackMetadata(selectedId);render();report("נטענו "+tracks.size()+" שירים מתוך YouTube.");}});
    }
    /** Imports song metadata from every owned playlist, without downloading media. */
    private void syncAllPlaylistTracks(){
        if(token==null){report("כדי לסנכרן שירים צריך להתחבר ל־Google.");return;}
        final ArrayList<Playlist> snapshot=new ArrayList<>(playlists);
        if(snapshot.isEmpty()){report("אין פלייליסטים שבבעלות החשבון.");return;}
        importPool.execute(()->{
            int imported=0,tracksImported=0,failed=0;
            for(Playlist playlist:snapshot){
                if(Thread.currentThread().isInterrupted()||token==null)break;
                try{
                    JSONArray cached=new JSONArray();String page="";
                    do{
                        JSONObject response=api("GET",
                            "playlistItems?part=snippet&playlistId="+enc(playlist.id)+"&maxResults=50"+
                            (page.isEmpty()?"":"&pageToken="+enc(page)),null);
                        JSONArray items=response.optJSONArray("items");
                        if(items!=null)for(int i=0;i<items.length();i++){
                            JSONObject item=items.optJSONObject(i);
                            if(item==null)continue;
                            JSONObject snippet=item.optJSONObject("snippet");
                            if(snippet==null)continue;
                            JSONObject resource=snippet.optJSONObject("resourceId");
                            String video=resource==null?"":resource.optString("videoId");
                            if(video.matches("[A-Za-z0-9_-]{11}"))
                                cached.put(new JSONObject().put("id",video).put("title",snippet.optString("title")));
                        }
                        page=response.optString("nextPageToken");
                        if(cached.length()>10000)throw new IOException("פלייליסט גדול מדי");
                    }while(!page.isEmpty());
                    if(token==null)break;
                    getSharedPreferences(YT_CACHE,MODE_PRIVATE).edit()
                        .putString("tracks_"+playlist.id,cached.toString()).apply();
                    imported++;
                    tracksImported+=cached.length();
                }catch(Exception e){
                    failed++;
                }
                final int n=imported,f=failed,total=tracksImported;
                report("ייבוא ספרייה: "+(n+f)+"/"+snapshot.size()+" פלייליסטים, "+total+" שירים.");
            }
            final int n=imported,f=failed,total=tracksImported;
            if(token!=null)report("ייבוא ראשוני הסתיים: "+n+" פלייליסטים, "+total+
                " שירים זמינים להמלצות DJ"+(f>0?". "+f+" רשימות לא נטענו, ייתכן בגלל הגבלות או מכסת API.":"."));
        });
    }
    private void cachePlaylistMetadata(){
        try{JSONArray a=new JSONArray();for(Playlist p:playlists){
            a.put(new JSONObject().put("id",p.id).put("title",p.title));
        }getSharedPreferences(YT_CACHE,MODE_PRIVATE).edit().putString("playlists",a.toString()).apply();
        }catch(Exception ignored){}
    }
    private void cacheTrackMetadata(String playlist){
        try{JSONArray a=new JSONArray();for(Track t:tracks){
            if(t.videoId!=null&&t.videoId.matches("[A-Za-z0-9_-]{11}"))
                a.put(new JSONObject().put("id",t.videoId).put("title",t.title));
        }getSharedPreferences(YT_CACHE,MODE_PRIVATE).edit().putString("tracks_"+playlist,a.toString()).apply();
        }catch(Exception ignored){}
    }
    /** Read our own server catalog to label songs accurately. Network failure leaves YouTube usable. */
    private void loadHostedIndex(){
        importPool.execute(()->{
            try{
                ArrayList<Catalog.Playlist> catalog=Catalog.refresh(this);
                HashSet<String> found=new HashSet<>();
                for(Catalog.Playlist p:catalog)for(Catalog.Track t:p.tracks)
                    if(t.hosted())found.add(t.id);
                serverAudioIds.clear();serverAudioIds.addAll(found);
                main.post(this::render);
            }catch(Exception e){android.util.Log.i("MorinMusic","Server catalog not available: "+e.getMessage());}
        });
    }
    private void openTrack(Track t){
        if(!t.videoId.matches("[A-Za-z0-9_-]{11}")){toast("הסרטון אינו זמין.");return;}
        startActivity(MediaSourceResolver.intentFor(this,t.videoId,t.title,serverAudioIds.contains(t.videoId)));
    }
    @Override protected void onResume(){super.onResume();if(rows!=null&&token!=null)render();}
    private void render(){
        if(rows==null)return;rows.removeAllViews();
        if(token==null){
            add(rows,txt("כדי לראות את הרשימות האישיות שלך לחץ על ״התחבר וקרא״.",16,TEXT));return;
        }
        if(currentPlaylistId==null){
            if(canWrite)add(rows,button("＋ צור פלייליסט חדש",this::createPlaylist));
            if(playlists.isEmpty())add(rows,txt("לא נמצאו רשימות או שעדיין לא הסתיים הייבוא.",15,MUTED));
            for(Playlist p:playlists){
                add(rows,button("🎵 "+p.title+"  ("+p.privacy+")",()->{
                    currentPlaylistId=p.id;currentPlaylistTitle=p.title;tracks.clear();render();loadTracks();
                }));
            }
        }else{
            add(rows,txt(currentPlaylistTitle+"  ·  "+tracks.size()+" שירים",20,TEXT));
            if(canWrite){
                LinearLayout menu=line();
                menu.addView(button("＋ הוסף שיר",this::addTrack),new LinearLayout.LayoutParams(0,-2,1));
                menu.addView(button("✎ שנה שם",this::renamePlaylist),new LinearLayout.LayoutParams(0,-2,1));
                add(rows,menu);
                add(rows,button("מחק את הפלייליסט כולו (עם אישור)",this::deletePlaylist));
            }
            if(tracks.isEmpty())add(rows,txt("רשימה ריקה או עדיין נטענת…",14,MUTED));
            for(int i=0;i<tracks.size();i++){
                final int ix=i;Track t=tracks.get(i);
                LinearLayout item=column();
                add(item,txt((i+1)+". "+t.title+"  ·  "+MediaSourceResolver.label(this,t.videoId,serverAudioIds.contains(t.videoId)),15,TEXT));
                LinearLayout controls=line();
                controls.addView(button("▶ נגן",()->openTrack(t)),new LinearLayout.LayoutParams(0,-2,1));
                if(canWrite){
                    controls.addView(button("↑",()->reorder(t,Math.max(0,ix-1))),new LinearLayout.LayoutParams(0,-2,.55f));
                    controls.addView(button("↓",()->reorder(t,Math.min(tracks.size()-1,ix+1))),new LinearLayout.LayoutParams(0,-2,.55f));
                    controls.addView(button("הסר",()->deleteTrack(t)),new LinearLayout.LayoutParams(0,-2,1));
                }
                add(item,controls);
                item.setBackgroundColor(PANEL);item.setPadding(dp(6),dp(3),dp(6),dp(3));
                add(rows,item);
            }
        }
    }
    private void requireWrite(Runnable yes){
        if(canWrite&&token!=null){yes.run();return;}
        new AlertDialog.Builder(this).setMessage("עריכת YouTube דורשת הרשאת כתיבה. לחץ ״אפשר עריכה״ והענק את ההרשאה ב־Google.")
            .setPositiveButton("אפשר עריכה",(d,w)->authorize(true)).setNegativeButton("ביטול",null).show();
    }
    private void ask(String heading,String hint,java.util.function.Consumer<String> action){
        EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);
        new AlertDialog.Builder(this).setTitle(heading).setView(e)
            .setPositiveButton("אישור",(d,w)->{
                String s=e.getText().toString().trim();if(!s.isEmpty())action.accept(s);
                else toast("יש להזין ערך.");
            }).setNegativeButton("ביטול",null).show();
    }
    private void createPlaylist(){
        requireWrite(()->ask("שם פלייליסט חדש","שם הרשימה",name->network("יוצרת רשימה…",()->{
            JSONObject body=new JSONObject();
            body.put("snippet",new JSONObject().put("title",name));
            body.put("status",new JSONObject().put("privacyStatus","private"));
            api("POST","playlists?part=snippet,status",body);
        },()->loadPlaylists())));
    }
    private void renamePlaylist(){
        requireWrite(()->ask("שם חדש לרשימה","שם הרשימה",name->network("מעדכנת את שם הרשימה…",()->{
            JSONObject body=new JSONObject().put("id",currentPlaylistId)
                .put("snippet",new JSONObject().put("title",name));
            api("PUT","playlists?part=snippet",body);
        },()->{currentPlaylistTitle=name;loadPlaylists();})));
    }
    private void deletePlaylist(){
        requireWrite(()->new AlertDialog.Builder(this).setTitle("מחיקה לצמיתות ב־YouTube")
            .setMessage("באמת למחוק את הפלייליסט ״"+currentPlaylistTitle+"״? אי אפשר לבטל פעולה זו.")
            .setPositiveButton("מחק",(d,w)->network("מוחקת פלייליסט…",()->{
                api("DELETE","playlists?id="+enc(currentPlaylistId),null);
            },()->{currentPlaylistId=null;loadPlaylists();}))
            .setNegativeButton("ביטול",null).show());
    }
    private String videoId(String raw){
        String s=raw.trim();
        if(s.matches("[A-Za-z0-9_-]{11}"))return s;
        try{
            Uri u=Uri.parse(s);
            String id=u.getQueryParameter("v");
            if(id==null&&u.getHost()!=null&&u.getHost().endsWith("youtu.be"))
                id=u.getLastPathSegment();
            if(id==null&&u.getPathSegments()!=null&&u.getPathSegments().size()>=2&&
               ("shorts".equals(u.getPathSegments().get(0))||"embed".equals(u.getPathSegments().get(0))))
                id=u.getPathSegments().get(1);
            if(id!=null&&id.matches("[A-Za-z0-9_-]{11}"))return id;
        }catch(Exception ignored){}
        return null;
    }
    private void addTrack(){
        requireWrite(()->ask("הוסף סרטון לפלייליסט","קישור YouTube או מזהה סרטון",raw->{
            String id=videoId(raw);
            if(id==null){toast("לא זוהה מזהה סרטון תקין.");return;}
            String playlist=currentPlaylistId;
            network("מוסיפה סרטון ב־YouTube…",()->{
                JSONObject snippet=new JSONObject().put("playlistId",playlist)
                    .put("resourceId",new JSONObject().put("kind","youtube#video").put("videoId",id));
                api("POST","playlistItems?part=snippet",new JSONObject().put("snippet",snippet));
            },this::loadTracks);
        }));
    }
    private void deleteTrack(Track t){
        requireWrite(()->new AlertDialog.Builder(this).setTitle("להסיר מהפלייליסט?")
            .setMessage(t.title+"\nהסרטון עצמו לא יימחק מ־YouTube.")
            .setPositiveButton("הסר",(d,w)->network("מסירה שיר…",()->{
                api("DELETE","playlistItems?id="+enc(t.id),null);
            },this::loadTracks))
            .setNegativeButton("ביטול",null).show());
    }
    private void reorder(Track t,int to){
        if(t.position==to)return;
        requireWrite(()->network("משנה סדר ברשימה…",()->{
            JSONObject snippet=new JSONObject().put("playlistId",currentPlaylistId)
                    .put("position",to)
                    .put("resourceId",new JSONObject().put("kind","youtube#video").put("videoId",t.videoId));
            api("PUT","playlistItems?part=snippet",
                new JSONObject().put("id",t.id).put("snippet",snippet));
        },this::loadTracks));
    }
    @Override protected void onDestroy(){executor.shutdownNow();importPool.shutdownNow();super.onDestroy();}
}
