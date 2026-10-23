package il.nolimits.music;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.text.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG=0xff101018,CARD=0xff20202d,PINK=0xffee769a,MUTED=0xffb2b2c7;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private ArrayList<Catalog.Playlist> playlists=new ArrayList<>();
    private ArrayList<Catalog.Track> visible=new ArrayList<>();
    private MediaController player;
    private ListenableFuture<MediaController> future;
    private LinearLayout rows;
    private TextView subtitle,now,time;
    private Button play,shuffle,repeat;
    private SeekBar seek;
    private EditText search;
    private int selected=-1; private boolean hostedOnly=false,offlineOnly=false,dragging=false;
    private String query="";private boolean destroyed=false;
    private final Runnable ticker=new Runnable(){public void run(){updatePlayer();handler.postDelayed(this,700);}};
    private static final String CACHE_SETTINGS = "playback_cache_settings";
    private static final String AUTO_CACHE = "save_on_first_play";
    private final BroadcastReceiver downloads=new BroadcastReceiver(){
        public void onReceive(Context c,Intent i){
            long downloadId=i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1L);
            if(downloadId>=0) for(Catalog.Playlist p:playlists){
                for(Catalog.Track t:p.tracks){
                    if(Catalog.downloadId(MainActivity.this,t)==downloadId){
                        if(Catalog.saved(MainActivity.this,t)){
                            AudioTrim.inspectAsync(MainActivity.this,t);
                            LyricsCache.fetchAsync(MainActivity.this,t);
                        }
                        render();
                        return;
                    }
                }
            }
            render();
        }
    };
    private String playlistFor(Catalog.Track t){
        if(selected>=0&&selected<playlists.size())
            for(Catalog.Track item:playlists.get(selected).tracks)
                if(item.id.equals(t.id))return playlists.get(selected).title;
        for(Catalog.Playlist p:playlists)
            for(Catalog.Track item:p.tracks)
                if(item.id.equals(t.id))return p.title;
        return "כל השירים";
    }
    private boolean autoCacheEnabled(){
        return getSharedPreferences(CACHE_SETTINGS,0).getBoolean(AUTO_CACHE,true);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(state!=null){selected=state.getInt("selected",-1);query=state.getString("query","");hostedOnly=state.getBoolean("hosted",false);offlineOnly=state.getBoolean("offline",false);}
        buildUi();
        try{playlists=Catalog.load(this);}catch(Exception e){toast("לא הצלחתי לטעון את הספרייה. נסה רענון.");}
        render();
        SessionToken token=new SessionToken(this,new ComponentName(this,PlaybackService.class));
        future=new MediaController.Builder(this,token).buildAsync();
        future.addListener(()->{try{
            if(destroyed)return;player=future.get();
            player.addListener(new Player.Listener(){
                @Override public void onEvents(Player p,Player.Events e){updatePlayer();}
                @Override public void onMediaItemTransition(MediaItem item,int reason){
                    if(item==null||!autoCacheEnabled())return;
                    for(Catalog.Playlist list:playlists)for(Catalog.Track t:list.tracks){
                        if(t.id.equals(item.mediaId)){
                            if(t.hosted()&&!Catalog.saved(MainActivity.this,t))
                                try{Catalog.download(MainActivity.this,t,list.title);}catch(Exception ignored){}
                            return;
                        }
                    }
                }
                @Override public void onPlayerError(PlaybackException e){toast("השיר לא זמין כרגע. בדוק חיבור או עבור לשיר הבא.");}
            });updatePlayer();
        }catch(Exception e){toast("הנגן לא התחבר. סגור ופתח שוב את האפליקציה.");}},command->handler.post(command));
        if(Build.VERSION.SDK_INT>=33)registerReceiver(downloads,new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),Context.RECEIVER_EXPORTED);
        else registerReceiver(downloads,new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        if(Build.VERSION.SDK_INT<29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},62);
        refresh(false);
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout line(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(4),dp(5),dp(4),dp(5));return t;}
    private GradientDrawable rounded(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(16));return d;}
    private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setMinHeight(dp(48));b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD));b.setOnClickListener(v->r.run());return b;}
    private void buildUi(){
        LinearLayout root=column();root.setBackgroundColor(BG);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setPadding(dp(16),dp(10),dp(16),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(16)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(16)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());return insets;});
        TextView title=text("מוזיקה ללא גבולות",27,Color.WHITE);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        LinearLayout heading=line();subtitle=text("הספרייה שלך",14,MUTED);heading.addView(subtitle,new LinearLayout.LayoutParams(0,-2,1));heading.addView(button("הגדרות",this::showSettings));root.addView(heading);
        LinearLayout navigation=line();navigation.addView(button("הפלייליסטים",()->{selected=-1;query="";search.setText("");render();}),new LinearLayout.LayoutParams(0,dp(52),1));
        navigation.addView(button("כל השירים",()->{selected=-2;render();}),new LinearLayout.LayoutParams(0,dp(52),1));
        navigation.addView(button("רענון",()->refresh(true)),new LinearLayout.LayoutParams(0,dp(52),1));root.addView(navigation);
        search=new EditText(this);search.setSingleLine(true);search.setTextColor(Color.WHITE);search.setHintTextColor(MUTED);search.setTextSize(16);search.setHint("חפש שיר או פלייליסט");search.setText(query);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();render();}public void afterTextChanged(Editable e){}});root.addView(search);
        LinearLayout filters=line();CheckBox direct=new CheckBox(this);direct.setText("רק שירים מהשרת (ללא פרסומות)");direct.setTextSize(14);direct.setTextColor(Color.WHITE);direct.setChecked(hostedOnly);direct.setOnCheckedChangeListener((b,v)->{hostedOnly=v;render();});filters.addView(direct);
        CheckBox offline=new CheckBox(this);offline.setText("שמורים במכשיר");offline.setTextSize(14);offline.setTextColor(Color.WHITE);offline.setChecked(offlineOnly);offline.setOnCheckedChangeListener((b,v)->{offlineOnly=v;selected=-2;render();});filters.addView(offline);root.addView(filters);
        ScrollView scroll=new ScrollView(this);rows=column();scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout dock=column();dock.setPadding(dp(12),dp(8),dp(12),dp(8));dock.setBackground(rounded(CARD));
        now=text("בחר שיר. תן למוזיקה להמשיך.",17,Color.WHITE);now.setMaxLines(2);now.setTypeface(null,Typeface.BOLD);dock.addView(now);
        seek=new SeekBar(this);seek.setMax(1000);seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int v,boolean user){}public void onStartTrackingTouch(SeekBar s){dragging=true;}public void onStopTrackingTouch(SeekBar s){if(player!=null&&player.getDuration()>0)player.seekTo(player.getDuration()*s.getProgress()/1000);dragging=false;}});dock.addView(seek);
        time=text("0:00 / 0:00",12,MUTED);time.setGravity(Gravity.CENTER);dock.addView(time);
        LinearLayout controls=line();controls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        controls.addView(button("הקודם",()->{if(player!=null)player.seekToPreviousMediaItem();}),new LinearLayout.LayoutParams(0,dp(50),1));
        play=button("נגן",()->{if(player==null)return;if(player.isPlaying())player.pause();else {if(player.getPlaybackState()==Player.STATE_IDLE)player.prepare();player.play();}});play.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffab3d64));controls.addView(play,new LinearLayout.LayoutParams(0,dp(50),1));
        controls.addView(button("הבא",()->{if(player!=null)player.seekToNextMediaItem();}),new LinearLayout.LayoutParams(0,dp(50),1));dock.addView(controls);
        LinearLayout modes=line();shuffle=button("ערבוב כבוי",()->{if(player!=null)player.setShuffleModeEnabled(!player.getShuffleModeEnabled());});repeat=button("חזרה כבויה",()->{if(player!=null)player.setRepeatMode((player.getRepeatMode()+1)%3);});modes.addView(shuffle,new LinearLayout.LayoutParams(0,dp(48),1));modes.addView(repeat,new LinearLayout.LayoutParams(0,dp(48),1));dock.addView(modes);root.addView(dock);setContentView(root);
    }
    private void showSettings(){
        boolean disabled=getSharedPreferences(IntroActivity.PREFS,0).getBoolean(IntroActivity.DISABLED,false);
        new AlertDialog.Builder(this).setTitle("הגדרות · מוזיקה ללא גבולות")
            .setItems(new String[]{"הפעל שוב את קליפ הפתיחה",
                    "אפס את ההעדפה והחזר את הקליפ לפתיחה הבאה",
                    disabled?"מצב הקליפ: כבוי בפתיחה":"מצב הקליפ: פעיל בפתיחה",
                    autoCacheEnabled()?"שמירה אוטומטית בניגון ראשון: פעילה (לחץ לכיבוי)"
                            :"שמירה אוטומטית בניגון ראשון: כבויה (לחץ להפעלה)"},(d,which)->{
                if(which==0){if(player!=null)player.pause();startActivity(new Intent(this,IntroActivity.class).putExtra(IntroActivity.REPLAY,true));}
                else if(which==1){getSharedPreferences(IntroActivity.PREFS,0).edit().remove(IntroActivity.DISABLED).apply();toast("קליפ הפתיחה יופעל בפתיחה הבאה של האפליקציה.");}
                else if(which==3){
                    boolean next=!autoCacheEnabled();
                    getSharedPreferences(CACHE_SETTINGS,0).edit().putBoolean(AUTO_CACHE,next).apply();
                    toast(next?"שמירת שירים מהשרת בניגון ראשון הופעלה.":"שמירה אוטומטית כובתה.");
                }
            }).setNegativeButton("סגור",null).show();
    }
    private ArrayList<Catalog.Track> all(){LinkedHashMap<String,Catalog.Track> m=new LinkedHashMap<>();for(Catalog.Playlist p:playlists)for(Catalog.Track t:p.tracks){if(!m.containsKey(t.id)||t.hosted())m.put(t.id,t);}return new ArrayList<>(m.values());}
    private boolean matches(Catalog.Track t){return (!hostedOnly||t.hosted())&&(!offlineOnly||Catalog.saved(this,t))&&t.title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));}
    private void render(){
        if(rows==null||destroyed)return;rows.removeAllViews();visible.clear();
        if(selected>=playlists.size())selected=-1;
        if(selected==-1&&query.trim().isEmpty()&&!offlineOnly){
            subtitle.setText(playlists.size()+" פלייליסטים · הספרייה מתעדכנת מהאתר");
            for(int i=0;i<playlists.size();i++){
                Catalog.Playlist p=playlists.get(i);final int index=i;long playable=p.tracks.stream().filter(Catalog.Track::hosted).count();
                LinearLayout box=column();box.setPadding(dp(14),dp(12),dp(14),dp(12));box.setBackground(rounded(CARD));
                TextView name=text(p.title,18,Color.WHITE);name.setTypeface(null,Typeface.BOLD);box.addView(name);box.addView(text(p.tracks.size()+" שירים · "+playable+" ללא פרסומות",14,PINK));
                box.setOnClickListener(v->{selected=index;render();});box.setFocusable(true);box.setContentDescription(p.title);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);rows.addView(box,lp);
            }return;
        }
        ArrayList<Catalog.Track> base=selected>=0?playlists.get(selected).tracks:all();for(Catalog.Track t:base)if(matches(t))visible.add(t);
        subtitle.setText((selected>=0?playlists.get(selected).title:"כל השירים")+" · "+visible.size()+" שירים");
        if(!hostedOnly&&!offlineOnly)rows.addView(text("YouTube מתנגן בתוך האפליקציה; ייתכנו פרסומות ומגבלות השמעה ברקע.",14,MUTED));
        rows.addView(button("נגן שיר ראשון",()->{for(Catalog.Track t:visible){if(t.hosted()||Catalog.saved(this,t))start(t);else openYouTube(t);return;}toast("אין שירים זמינים ברשימה הזאת.");}));
        if(visible.isEmpty())rows.addView(text("אין שירים בתצוגה הזאת. נסה לשנות את החיפוש או המסננים.",16,MUTED));
        for(Catalog.Track t:visible){
            boolean saved=Catalog.saved(this,t);LinearLayout row=line();row.setPadding(dp(6),dp(9),dp(6),dp(9));
            LinearLayout info=column();TextView name=text(t.title,16,Color.WHITE);name.setMaxLines(2);info.addView(name);
            info.addView(text(saved?"במכשיר · זמין ללא אינטרנט"
                    +(AudioTrim.get(this,t)!=null&&AudioTrim.get(this,t).changed()?" · קצוות שקטים הוסרו":"")
                    :t.hosted()?"בשרת · נשמר בניגון הראשון":"YouTube · נגן מובנה (ללא שמירה אוטומטית)",
                    13,saved?0xff83d7b2:MUTED));
            info.setOnClickListener(v->{if(t.hosted()||saved)start(t);else openYouTube(t);});info.setFocusable(true);row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            if(t.hosted())row.addView(button(saved?"מחק":"שמור",()->{if(Catalog.saved(this,t)){
                new AlertDialog.Builder(this).setTitle("מחיקת העותק מהמכשיר?").setMessage(t.title).setPositiveButton("מחק",(d,w)->{if(player!=null&&player.getCurrentMediaItem()!=null&&player.getCurrentMediaItem().mediaId.equals(t.id)){toast("עצור ועבור לשיר אחר לפני מחיקת השיר המתנגן.");return;}((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).remove(Catalog.downloadId(this,t));Catalog.local(this,t).delete();getSharedPreferences("downloads",0).edit().remove(t.id).remove(t.id+"_path").remove(t.id+"_playlist").apply();render();}).setNegativeButton("ביטול",null).show();
            }else try{Catalog.download(this,t,playlistFor(t));toast("ההורדה מופיעה בהתראות. השיר יישמר בתיקיית ההורדות.");}catch(Exception e){toast("לא ניתן להתחיל הורדה. נסה שוב.");}}));
            else row.addView(button("נגן",()->openYouTube(t)));
            rows.addView(row);View rule=new View(this);rule.setBackgroundColor(CARD);rows.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));
        }
    }
    private void start(Catalog.Track chosen){
        if(player==null){toast("הנגן מתחבר, נסה בעוד רגע.");return;}
        ArrayList<MediaItem> queue=new ArrayList<>();int start=0;
        for(Catalog.Track t:visible)if(t.hosted()||Catalog.saved(this,t)){
            if(t.id.equals(chosen.id))start=queue.size();
            boolean local=Catalog.saved(this,t);
            MediaItem.Builder item=new MediaItem.Builder()
                    .setMediaId(t.id).setUri(Catalog.uri(this,t))
                    .setMediaMetadata(new MediaMetadata.Builder()
                            .setTitle(t.title).setArtist("מוזיקה ללא גבולות").build());
            if(local){
                AudioTrim.Bounds trim=AudioTrim.get(this,t);
                if(trim==null) AudioTrim.inspectAsync(this,t);
                else if(trim.changed()){
                    MediaItem.ClippingConfiguration.Builder clipping =
                            new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(trim.startMs);
                    if(trim.endMs>=0)clipping.setEndPositionMs(trim.endMs);
                    item.setClippingConfiguration(clipping.build());
                }
            }
            queue.add(item.build());
        }
        if(queue.isEmpty())return;
        player.setMediaItems(queue,start,0);
        player.prepare();
        player.play();
        // Fetch only audio already hosted on our server, not YouTube playback.
        // The current playback streams normally while DownloadManager makes a
        // local copy. Future playback resolves to the local file.
        if(chosen.hosted()&&!Catalog.saved(this,chosen)&&autoCacheEnabled()){
            try{Catalog.download(this,chosen,playlistFor(chosen));}
            catch(Exception e){toast("לא ניתן לשמור את השיר אוטומטית כרגע.");}
        }
    }
    private void openYouTube(Catalog.Track t){
        if(player!=null)player.pause();
        Intent intent=new Intent(this,YouTubePlayerActivity.class)
            .putExtra(YouTubePlayerActivity.EXTRA_ID,t.id)
            .putExtra(YouTubePlayerActivity.EXTRA_TITLE,t.title);
        startActivity(intent);
    }
    private void refresh(boolean announce){
        if(announce)toast("מעדכנת את הספרייה…");
        io.execute(()->{try{ArrayList<Catalog.Playlist> next=Catalog.refresh(this);runOnUiThread(()->{if(destroyed)return;playlists=next;render();if(announce)toast("הספרייה מעודכנת.");});}catch(Exception e){if(announce)runOnUiThread(()->toast("אין חיבור כרגע. הספרייה האחרונה נשארה זמינה."));}});
    }
    private String clock(long ms){long s=Math.max(0,ms/1000);return String.format(Locale.US,"%d:%02d",s/60,s%60);}
    private void updatePlayer(){if(player==null||destroyed)return;MediaItem item=player.getCurrentMediaItem();if(item!=null)now.setText(player.getMediaMetadata().title);play.setText(player.isPlaying()?"השהה":"נגן");long duration=player.getDuration(),position=player.getCurrentPosition();if(!dragging)seek.setProgress(duration>0?(int)(position*1000/duration):0);time.setText(clock(position)+" / "+clock(duration));shuffle.setText(player.getShuffleModeEnabled()?"ערבוב פעיל":"ערבוב כבוי");repeat.setText(switch(player.getRepeatMode()){case Player.REPEAT_MODE_ONE->"חזרה על שיר";case Player.REPEAT_MODE_ALL->"חזרה על הכול";default->"חזרה כבויה";});}
    private void toast(String s){if(!destroyed)Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.post(ticker);render();}
    @Override protected void onPause(){handler.removeCallbacks(ticker);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("selected",selected);out.putString("query",query);out.putBoolean("hosted",hostedOnly);out.putBoolean("offline",offlineOnly);super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(selected!=-1||!query.isEmpty()){selected=-1;query="";search.setText("");render();}else super.onBackPressed();}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacks(ticker);unregisterReceiver(downloads);if(future!=null)MediaController.releaseFuture(future);io.shutdownNow();super.onDestroy();}
}
