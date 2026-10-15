package il.nolimits.music;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Continuous mixed playlist playback: hosted MP3 / saved cloud MP4 use
 * Media3, and uncached YouTube videos use their official iframe player.
 * One approved CaptureSessionService handles the entire listening queue.
 */
public final class YouTubePlayerActivity extends Activity {
    static final String EXTRA_ID = "video_id";
    static final String EXTRA_TITLE = "video_title";
    static final String EXTRA_QUEUE_JSON = "session_queue_json";
    static final String EXTRA_QUEUE_INDEX = "session_queue_index";

    private static final int PERMISSION_AUDIO=4091;
    private static final int PERMISSION_PROJECTION=4092;
    private final ArrayList<Catalog.Track> queue=new ArrayList<>();
    private WebView web;
    private ExoPlayer audioPlayer;
    private PlayerView mediaView;
    private TextView status,title,progress;
    private Button stopRecording;
    private int index;
    private String trackId;
    private boolean webpageReady;
    private boolean requestSent;
    private boolean sessionActive;
    private boolean serviceStarted;
    private boolean waitingForTrack;
    private boolean currentIsWeb;
    private boolean acceptEnd;
    private boolean receiverRegistered;
    private boolean finishing;

    private final BroadcastReceiver sessionEvents=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){
            String action=intent.getAction();
            if(CloudUpload.ACTION_STATUS.equals(action)){
                if(trackId!=null&&trackId.equals(intent.getStringExtra(CloudUpload.EXTRA_ID)))
                    status.setText(intent.getStringExtra(CloudUpload.EXTRA_MESSAGE));
                return;
            }
            if(CaptureSessionService.SESSION_READY.equals(action)){
                sessionActive=intent.getBooleanExtra(CaptureSessionService.EXTRA_OK,false);
                stopRecording.setVisibility(sessionActive?View.VISIBLE:View.GONE);
                if(sessionActive) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                status.setText(intent.getStringExtra(CaptureSessionService.EXTRA_DETAILS));
                return;
            }
            if(CaptureSessionService.SESSION_FINISHED.equals(action)){
                boolean expected=finishing||!sessionActive;
                sessionActive=false;
                serviceStarted=false;
                stopRecording.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if(!expected)status.setText(intent.getStringExtra(CaptureSessionService.EXTRA_DETAILS));
                if(waitingForTrack){waitingForTrack=false;playCurrent();}
                return;
            }
            String id=intent.getStringExtra(CaptureSessionService.EXTRA_ID);
            if(CaptureSessionService.TRACK_READY.equals(action)){
                if(trackId==null||!trackId.equals(id))return;
                waitingForTrack=false;
                status.setText(intent.getStringExtra(CaptureSessionService.EXTRA_DETAILS));
                playCurrent();
            }else if(CaptureSessionService.END_TRACK.equals(action)){
                if(trackId!=null&&trackId.equals(id))
                    status.setText(intent.getStringExtra(CaptureSessionService.EXTRA_DETAILS));
            }
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff101018);
        getWindow().setNavigationBarColor(0xff101018);

        try{
            JSONArray arr=new JSONArray(getIntent().getStringExtra(EXTRA_QUEUE_JSON));
            for(int i=0;i<arr.length();i++){
                JSONObject item=arr.getJSONObject(i);
                Catalog.Track track=new Catalog.Track(item);
                if(track.id.matches("[A-Za-z0-9_-]{11}"))queue.add(track);
            }
        }catch(Exception ignored){}
        if(queue.isEmpty()){
            String id=getIntent().getStringExtra(EXTRA_ID);
            if(id==null||!id.matches("[A-Za-z0-9_-]{11}")){finish();return;}
            try {
                queue.add(new Catalog.Track(new JSONObject().put("youtubeId",id)
                        .put("title",getIntent().getStringExtra(EXTRA_TITLE))));
            }catch(Exception e){finish();return;}
        }
        index=Math.max(0,Math.min(queue.size()-1,
                getIntent().getIntExtra(EXTRA_QUEUE_INDEX,0)));

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(0xff101018);
        root.setPadding(dp(12),dp(12),dp(12),dp(12));
        title=new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setMaxLines(2);
        root.addView(title);
        progress=new TextView(this);
        progress.setTextColor(0xffee88af);
        progress.setTextSize(13);
        root.addView(progress);
        status=new TextView(this);
        status.setTextColor(0xffb2b2c7);
        status.setTextSize(14);
        status.setPadding(0,dp(8),0,dp(8));
        root.addView(status);
        web=new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings options=web.getSettings();
        options.setJavaScriptEnabled(true);
        options.setDomStorageEnabled(true);
        options.setMediaPlaybackRequiresUserGesture(false);
        options.setAllowFileAccess(false);
        options.setAllowContentAccess(false);
        options.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                if(!request.isForMainFrame())return false;
                Uri uri=request.getUrl();
                if("music-player".equals(uri.getScheme())){
                    String action=uri.getHost();
                    if("ready".equals(action)){
                        webpageReady=true;
                        beginListening();
                    }else if("playing".equals(action)){
                        if(currentIsWeb){
                            acceptEnd=true;
                            status.setText(sessionActive?"מנגן · סשן הקלטה פעיל":"מנגן");
                        }
                    }else if("ended".equals(action)){
                        if(currentIsWeb&&acceptEnd)next();
                    }else if("error".equals(action)){
                        status.setText("YouTube לא מאפשר לנגן את הקליפ הזה בתוך האפליקציה");
                        if(currentIsWeb&&acceptEnd)next();
                    }else if("blocked".equals(action)){
                        status.setText("הניגון האוטומטי נחסם. לחץ על 'נגן / השהה'.");
                    }
                    return true;
                }
                status.setText("הקישור החיצוני נחסם. אפשר לבחור שיר אחר.");
                return true;
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest request,WebResourceError error){
                if(request.isForMainFrame())status.setText("לא ניתן לטעון YouTube. בדוק את החיבור.");
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));

        DefaultDataSource.Factory base=new DefaultDataSource.Factory(this);
        ResolvingDataSource.Factory secured=new ResolvingDataSource.Factory(base,dataSpec->{
            Uri uri=dataSpec.uri,server=Uri.parse(CloudUpload.baseUrl(this));
            if("https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null
                    &&uri.getHost().equalsIgnoreCase(server.getHost())
                    &&uri.getPort()==server.getPort()&&uri.getPath()!=null
                    &&uri.getPath().startsWith("/api/recordings/")){
                String token=CloudUpload.authToken(this);
                if(!token.isEmpty())
                    return dataSpec.withRequestHeaders(
                            Collections.singletonMap("Authorization","Bearer "+token));
            }
            return dataSpec;
        });
        audioPlayer=new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(secured)).build();
        audioPlayer.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_ENDED&&!currentIsWeb&&acceptEnd)next();
            }
            @Override public void onPlayerError(PlaybackException exception){
                status.setText("הניגון של השיר נכשל; עוברת לשיר הבא");
                if(!currentIsWeb)next();
            }
        });
        mediaView=new PlayerView(this);
        mediaView.setPlayer(audioPlayer);
        mediaView.setUseController(true);
        root.addView(mediaView,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button previous=button("הקודם",v->previous());
        controls.addView(previous,new LinearLayout.LayoutParams(0,dp(52),1));
        Button pause=button("נגן / השהה",v->toggle());
        controls.addView(pause,new LinearLayout.LayoutParams(0,dp(52),2));
        Button following=button("הבא",v->next());
        controls.addView(following,new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(controls);

        stopRecording=button("הפסק הקלטה · המשך מוזיקה",v->stopRecording());
        stopRecording.setVisibility(View.GONE);
        root.addView(stopRecording);

        Button close=button("חזרה לספרייה",v->{stopSession();finish();});
        root.addView(close);
        TextView caption=new TextView(this);
        caption.setText("האישור להקלטה ניתן פעם אחת בתחילת סשן ההאזנה. "
                +"שירים עוברים אוטומטית; ניתן לעצור הקלטה בלי לעצור מוזיקה.");
        caption.setTextColor(0xffa9a9c3);
        caption.setTextSize(12);
        root.addView(caption);
        setContentView(root);

        IntentFilter filter=new IntentFilter();
        filter.addAction(CaptureSessionService.SESSION_READY);
        filter.addAction(CaptureSessionService.TRACK_READY);
        filter.addAction(CaptureSessionService.END_TRACK);
        filter.addAction(CaptureSessionService.SESSION_FINISHED);
        filter.addAction(CloudUpload.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)
            registerReceiver(sessionEvents,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(sessionEvents,filter);
        receiverRegistered=true;

        try(InputStream input=getAssets().open("youtube_player.html")){
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            byte[] bytes=new byte[4096];
            int n;while((n=input.read(bytes))>=0)output.write(bytes,0,n);
            String html=new String(output.toByteArray(),StandardCharsets.UTF_8)
                    .replace("__VIDEO_ID__",queue.get(index).id);
            web.loadDataWithBaseURL(Catalog.ORIGIN,html,"text/html","UTF-8",null);
        }catch(Exception e){
            status.setText("לא ניתן לטעון נגן YouTube");
            webpageReady=true;
            beginListening();
        }
    }

    private Button button(String label,View.OnClickListener action){
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(label);b.setOnClickListener(action);
        return b;
    }

    private void beginListening(){
        if(requestSent||finishing)return;
        requestSent=true;
        progress.setText((index+1)+" / "+queue.size());
        if(Build.VERSION.SDK_INT<29||
                (!CloudUpload.configured(this)&&!CloudUpload.keepOffline(this))){
            status.setText("ניגון רציף · שרת הקלטות עדיין אינו מוגדר");
            playCurrent();
            return;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},PERMISSION_AUDIO);
        else requestProjection();
    }

    private void requestProjection(){
        MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        Intent request=Build.VERSION.SDK_INT>=34
                ?manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                :manager.createScreenCaptureIntent();
        startActivityForResult(request,PERMISSION_PROJECTION);
    }

    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants){
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==PERMISSION_AUDIO){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)
                requestProjection();
            else{
                status.setText("לכידת שמע לא אושרה; מוזיקה תתנגן כרגיל");
                playCurrent();
            }
        }
    }

    @Override protected void onActivityResult(int code,int resultCode,Intent data){
        super.onActivityResult(code,resultCode,data);
        if(code!=PERMISSION_PROJECTION)return;
        if(resultCode!=RESULT_OK||data==null){
            status.setText("ההקלטה לא אושרה; מוזיקה תתנגן כרגיל");
            playCurrent();return;
        }
        Catalog.Track first=queue.get(index);
        Intent cmd=new Intent(this,CaptureSessionService.class)
                .setAction(CaptureSessionService.START)
                .putExtra(CaptureSessionService.EXTRA_ID,first.id)
                .putExtra(CaptureSessionService.EXTRA_SKIP,alreadySaved(first))
                .putExtra(CaptureSessionService.EXTRA_CODE,resultCode)
                .putExtra(CaptureSessionService.EXTRA_DATA,data);
        waitingForTrack=true;
        serviceStarted=true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("מתחילה סשן הקלטה אחד לכל ההאזנה…");
        startForegroundService(cmd);
    }

    private boolean alreadySaved(Catalog.Track t){
        return t.hosted()||t.cloud()||CloudUpload.uploaded(this,t.id)
                ||CloudUpload.localAvailable(this,t.id);
    }

    private void prepareTrack(){
        if(queue.isEmpty())return;
        Catalog.Track track=queue.get(index);
        trackId=track.id;
        title.setText(track.title);
        progress.setText((index+1)+" / "+queue.size());
        acceptEnd=false;
        if(serviceStarted&&sessionActive){
            waitingForTrack=true;
            status.setText("עוברת לשיר הבא בלי בקשת אישור הקלטה…");
            startService(new Intent(this,CaptureSessionService.class)
                    .setAction(CaptureSessionService.TRACK)
                    .putExtra(CaptureSessionService.EXTRA_ID,track.id)
                    .putExtra(CaptureSessionService.EXTRA_SKIP,alreadySaved(track)));
        }else playCurrent();
    }

    private void playCurrent(){
        if(finishing||queue.isEmpty())return;
        Catalog.Track track=queue.get(index);
        trackId=track.id;
        title.setText(track.title);
        progress.setText((index+1)+" / "+queue.size());
        boolean localAudio=RecordedOfflineAudio.saved(this,track.id)
                &&CloudUpload.MODE_AUDIO.equals(CloudUpload.offlineMode(this));
        boolean localVideo=RecordedVideo.saved(this,track.id);
        boolean recorded=CloudUpload.uploaded(this,track.id)||track.cloud();
        Uri uri=null;
        boolean isVideo=false;
        if(localAudio)uri=RecordedOfflineAudio.uri(this,track.id);
        else if(localVideo){uri=RecordedVideo.uri(this,track.id);isVideo=true;}
        else if(recorded){uri=CloudUpload.recordedRemoteUri(this,track.id);isVideo=true;}
        else if(track.hosted()||Catalog.saved(this,track)){uri=Catalog.uri(this,track);}
        currentIsWeb=(uri==null);
        acceptEnd=false;
        if(currentIsWeb){
            audioPlayer.stop();
            mediaView.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            web.evaluateJavascript("window.playVideoId&&window.playVideoId('"+track.id+"')",null);
        }else{
            web.evaluateJavascript("window.pausePlayback&&window.pausePlayback()",null);
            web.setVisibility(View.GONE);
            mediaView.setVisibility(View.VISIBLE);
            mediaView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
            audioPlayer.setMediaItem(MediaItem.fromUri(uri));
            audioPlayer.prepare();
            audioPlayer.play();
            acceptEnd=true;
            if(!isVideo)status.setText("מנגן שמע מהספרייה שלנו");
        }
    }

    private void advance(int delta){
        if(queue.isEmpty()||finishing)return;
        if(delta>0&&index>=queue.size()-1){
            status.setText("הפלייליסט הסתיים");
            stopSession();
            return;
        }
        int next=Math.max(0,Math.min(queue.size()-1,index+delta));
        if(next==index)return;
        acceptEnd=false;
        audioPlayer.stop();
        web.evaluateJavascript("window.pausePlayback&&window.pausePlayback()",null);
        index=next;
        prepareTrack();
    }

    private void next(){advance(1);}
    private void previous(){advance(-1);}

    private void toggle(){
        if(currentIsWeb)
            web.evaluateJavascript("window.togglePlayback&&window.togglePlayback()",null);
        else if(audioPlayer.isPlaying())audioPlayer.pause();
        else audioPlayer.play();
    }

    private void stopRecording(){
        if(!serviceStarted)return;
        sessionActive=false;
        serviceStarted=false;
        waitingForTrack=false;
        stopRecording.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startService(new Intent(this,CaptureSessionService.class)
                .setAction(CaptureSessionService.STOP));
        status.setText("ההקלטה נעצרה, המוזיקה ממשיכה להתנגן");
    }

    private void stopSession(){
        if(serviceStarted)stopRecording();
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override protected void onPause(){
        if(web!=null)web.onPause();
        super.onPause();
    }

    @Override protected void onResume(){
        super.onResume();
        if(web!=null)web.onResume();
    }

    @Override protected void onDestroy(){
        finishing=true;
        stopSession();
        if(receiverRegistered){unregisterReceiver(sessionEvents);receiverRegistered=false;}
        if(audioPlayer!=null){audioPlayer.release();audioPlayer=null;}
        if(web!=null){
            web.stopLoading();
            web.loadUrl("about:blank");
            ViewGroup parent=(ViewGroup)web.getParent();
            if(parent!=null)parent.removeView(web);
            web.destroy();web=null;
        }
        super.onDestroy();
    }
}
