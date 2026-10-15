package il.nolimits.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One user-authorized MediaProjection and one VirtualDisplay for an entire
 * listening session. The display Surface changes when each video file
 * rotates, but the projection/virtual display are NOT recreated.
 *
 * Every track is identified by a YouTube video ID; completed clips are
 * finalized/uploaded asynchronously, without stopping the listening queue.
 * Existing server tracks are recorded only as temporary data and discarded.
 *
 * This never suppresses Android's system capture consent/indicator and cannot
 * capture DRM-protected video or audio that disallows playback capture.
 */
public final class CaptureSessionService extends Service {
    static final String START = "il.nolimits.music.session.START";
    static final String TRACK = "il.nolimits.music.session.TRACK";
    static final String END_TRACK = "il.nolimits.music.session.END_TRACK";
    static final String STOP = "il.nolimits.music.session.STOP";
    static final String SESSION_READY = "il.nolimits.music.session.SESSION_READY";
    static final String TRACK_READY = "il.nolimits.music.session.TRACK_READY";
    static final String SESSION_FINISHED = "il.nolimits.music.session.FINISHED";
    static final String EXTRA_ID = "session_video_id";
    static final String EXTRA_SKIP = "session_skip_upload";
    static final String EXTRA_CODE = "session_consent_code";
    static final String EXTRA_DATA = "session_consent_data";
    static final String EXTRA_OK = "session_success";
    static final String EXTRA_DETAILS = "session_message";

    private static final String TAG = "NoLimitSession";
    private static final int NOTIFICATION_ID = 2217;
    private static final String CHANNEL = "listening_session";
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService finalizers = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final Object audioLock = new Object();
    private final HashSet<String> recordedIds = new HashSet<>();

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private AudioRecord audio;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private volatile boolean authorized;
    private Surface activeSurface;
    private FileOutputStream pcmStream;
    private Slice current;
    private int width, height, density;
    private volatile boolean started;
    private long audioStartNanos,videoStartNanos;

    private static final class Slice {
        final String id;
        final boolean skip;
        final File video,pcm;
        long audioDelayMs;
        boolean videoStarted;
        Slice(String id,boolean skip,File video,File pcm) {
            this.id=id;this.skip=skip;this.video=video;this.pcm=pcm;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL,"האזנה והקלטה רציפה",
                        NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null)return START_NOT_STICKY;
        final String action=intent.getAction();
        if(STOP.equals(action)) {
            requestStop();
            return START_NOT_STICKY;
        }
        if(START.equals(action)) {
            if(started) return START_NOT_STICKY;
            started=true;
            // Foreground type must be active BEFORE getMediaProjection on API 34+.
            try{
                startForeground(NOTIFICATION_ID,notification("הקלטת המסך פעילה"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }catch(Exception e){
                send(SESSION_FINISHED,null,false,"לא ניתן להפעיל שירות הקלטה");
                stopSelf();
                return START_NOT_STICKY;
            }
            final Intent permission=(Intent)intent.getParcelableExtra(EXTRA_DATA);
            final int resultCode=intent.getIntExtra(EXTRA_CODE,0);
            final String first=intent.getStringExtra(EXTRA_ID);
            final boolean skip=intent.getBooleanExtra(EXTRA_SKIP,false);
            work.execute(()->{
                try {
                    if(permission==null)throw new IOException("Missing Android screen consent");
                    MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
                    projection=manager.getMediaProjection(resultCode,permission);
                    if(projection==null)throw new IOException("No approved projection");
                    projection.registerCallback(new MediaProjection.Callback(){
                        @Override public void onStop(){requestStop();}
                    },new Handler(Looper.getMainLooper()));
                    DisplayMetrics size=getResources().getDisplayMetrics();
                    density=size.densityDpi;
                    double factor=Math.min(1.0,1920.0/Math.max(size.widthPixels,size.heightPixels));
                    width=Math.max(320,((int)(size.widthPixels*factor)/16)*16);
                    height=Math.max(320,((int)(size.heightPixels*factor)/16)*16);
                    initializeAudio();
                    authorized=true;
                    send(SESSION_READY,first,true,
                            "אישור Android התקבל · הקלטה רציפה פעילה");
                    if(valid(first)) begin(first,skip);
                }catch(Exception e){
                    Log.w(TAG,"Session start failed",e);
                    send(SESSION_FINISHED,first,false,
                            "לא ניתן להתחיל הקלטה רציפה: "+e.getClass().getSimpleName());
                    requestStop();
                }
            });
            return START_NOT_STICKY;
        }
        if(TRACK.equals(action)&&started&&!stopping.get()){
            final String id=intent.getStringExtra(EXTRA_ID);
            final boolean skip=intent.getBooleanExtra(EXTRA_SKIP,false);
            if(valid(id)) work.execute(()->{
                if(!stopping.get()&&authorized)begin(id,skip);
            });
        }
        if(END_TRACK.equals(action)&&started&&!stopping.get()){
            final String id=intent.getStringExtra(EXTRA_ID);
            work.execute(()->{
                if(current!=null&&current.id.equals(id))endCurrent();
            });
        }
        return START_NOT_STICKY;
    }

    private static boolean valid(String id){
        return id!=null&&id.matches("[A-Za-z0-9_-]{11}");
    }

    private Notification notification(String message) {
        Intent open=new Intent(this,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent resume=PendingIntent.getActivity(this,2217,open,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,2218,
                new Intent(this,CaptureSessionService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle("מוזיקה ללא גבולות · סשן הקלטה")
                .setContentText(message)
                .setOngoing(true)
                .setContentIntent(resume)
                .addAction(android.R.drawable.ic_media_pause,"הפסק הקלטה",stop)
                .build();
    }

    private void initializeAudio() throws IOException {
        int min=AudioRecord.getMinBufferSize(RecordedAudio.RATE,
                AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<1)throw new IOException("No playback-capture buffer");
        AudioPlaybackCaptureConfiguration cfg=
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUid(getApplicationInfo().uid)
                        .build();
        AudioFormat format=new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setSampleRate(RecordedAudio.RATE).build();
        audio=new AudioRecord.Builder().setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(Math.max(min*4,32768)).build();
        if(audio.getState()!=AudioRecord.STATE_INITIALIZED)
            throw new IOException("Playback capture refused by Android");
        audio.startRecording();
        if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
            throw new IOException("AudioRecord did not start");
        audioStartNanos=System.nanoTime();
        audioRunning=true;
        audioThread=new Thread(this::writeAudio,"NoLimitSessionAudio");
        audioThread.start();
    }

    private void writeAudio(){
        byte[] buffer=new byte[16384];
        try{
            while(audioRunning){
                int n=audio.read(buffer,0,buffer.length);
                if(n>0){
                    synchronized(audioLock){
                        if(pcmStream!=null)pcmStream.write(buffer,0,n);
                    }
                }else if(n==AudioRecord.ERROR_DEAD_OBJECT||
                         n==AudioRecord.ERROR_INVALID_OPERATION){
                    if(audioRunning)Log.w(TAG,"Android stopped providing audio");
                    break;
                }
            }
        }catch(Exception e){Log.w(TAG,"Audio writer error",e);}
    }

    /** Rotate media files only; retain the SAME MediaProjection + VirtualDisplay. */
    private void begin(String id,boolean knownOnServer){
        if(!authorized||stopping.get())return;
        endCurrent();
        boolean skip=knownOnServer||CloudUpload.uploaded(this,id)||recordedIds.contains(id);
        File video=new File(getCacheDir(),"segment-"+System.nanoTime()+"-"+id+".mp4");
        File pcm=new File(getCacheDir(),"segment-"+System.nanoTime()+"-"+id+".pcm");
        Slice slice=new Slice(id,skip,video,pcm);
        try {
            synchronized(audioLock){pcmStream=new FileOutputStream(pcm);}
            MediaRecorder next=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();
            recorder=next;
            next.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            next.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            next.setVideoSize(width,height);
            next.setVideoFrameRate(24);
            next.setVideoEncodingBitRate(2_000_000);
            next.setOutputFile(video.getAbsolutePath());
            next.prepare();
            activeSurface=next.getSurface();
            if(display==null){
                // Android 14+ permits only one createVirtualDisplay per consent.
                display=projection.createVirtualDisplay("NoLimitListeningSession",
                        width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        activeSurface,null,new Handler(Looper.getMainLooper()));
                if(display==null)throw new IOException("Virtual display unavailable");
            }else{
                display.setSurface(activeSurface);
            }
            next.start();
            videoStartNanos=System.nanoTime();
            slice.audioDelayMs=Math.max(0L,(audioStartNanos-videoStartNanos)/1000000L);
            slice.videoStarted=true;
            current=slice;
            send(TRACK_READY,id,true,skip?"מנגן · השיר כבר קיים באתר":
                    "מנגן · הקלטת קליפ חדש");
        }catch(Exception e){
            Log.w(TAG,"Track capture failed",e);
            cleanupRecorder();
            synchronized(audioLock){
                try{if(pcmStream!=null)pcmStream.close();}catch(IOException ignored){}
                pcmStream=null;
            }
            video.delete();pcm.delete();
            send(TRACK_READY,id,false,"הניגון יימשך בלי הקלטה: "+e.getClass().getSimpleName());
        }
    }

    private void endCurrent(){
        Slice completed=current;
        if(completed==null)return;
        current=null;
        synchronized(audioLock){
            try{if(pcmStream!=null){pcmStream.flush();pcmStream.close();}}
            catch(IOException e){Log.w(TAG,"PCM close failed",e);}
            pcmStream=null;
        }
        boolean recorded=completed.videoStarted;
        if(recorder!=null){
            try{recorder.stop();}catch(Exception e){
                recorded=false;
                Log.w(TAG,"Could not finish segment "+completed.id,e);
            }
        }
        cleanupRecorder();
        if(!recorded||completed.skip){
            completed.video.delete();completed.pcm.delete();
            if(recordedIds.add(completed.id)&&completed.skip)
                send(END_TRACK,completed.id,true,"השיר כבר קיים · ללא שמירה כפולה");
            return;
        }
        recordedIds.add(completed.id);
        finalizers.execute(()->{
            try{
                RecordedVideo.Result result=RecordedVideo.finish(
                        this,completed.id,completed.video,completed.pcm,
                        completed.audioDelayMs);
                CloudUpload.enqueue(this,completed.id);
                send(END_TRACK,completed.id,true,
                        "נוצר קליפ ("+(result.durationMs/1000)+" שניות) · העלאה לאתר");
            }catch(Exception e){
                Log.w(TAG,"Clip finalization failed",e);
                send(END_TRACK,completed.id,false,
                        "הקלטת הקליפ לא הושלמה: "+e.getMessage());
            }finally{
                completed.video.delete();
                completed.pcm.delete();
            }
        });
    }

    private void cleanupRecorder(){
        if(display!=null){
            try{display.setSurface(null);}catch(Exception ignored){}
        }
        if(recorder!=null){
            try{recorder.reset();}catch(Exception ignored){}
            recorder.release();
            recorder=null;
        }
        if(activeSurface!=null){activeSurface.release();activeSurface=null;}
    }

    private void requestStop(){
        if(!stopping.compareAndSet(false,true))return;
        work.execute(()->{
            endCurrent();
            authorized=false;
            audioRunning=false;
            if(audio!=null){
                try{audio.stop();}catch(Exception ignored){}
            }
            if(audioThread!=null){
                try{audioThread.join(1500);}catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }
            }
            if(audio!=null){audio.release();audio=null;}
            if(display!=null){display.release();display=null;}
            if(projection!=null){
                try{projection.stop();}catch(Exception ignored){}
                projection=null;
            }
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                    notification("הסשן הסתיים · שומר את הקליפים"));
            finalizers.execute(()->{
                send(SESSION_FINISHED,null,true,
                        "הסשן הסתיים · הקליפים שנקלטו נשלחו לעיבוד");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
            finalizers.shutdown();
            work.shutdown();
        });
    }

    private void send(String event,String id,boolean ok,String details){
        Intent i=new Intent(event).setPackage(getPackageName())
                .putExtra(EXTRA_ID,id).putExtra(EXTRA_OK,ok)
                .putExtra(EXTRA_DETAILS,details);
        sendBroadcast(i);
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
