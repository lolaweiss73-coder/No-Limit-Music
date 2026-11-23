package il.nolimits.music;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One opt-in MediaProjection session. Capture lasts until Stop or OS revokes it.
 * Creates a screen video and attempts playback audio capture independently.
 * On stop the service merges them into an MP4 and preserves original M4A audio.
 * No server upload, automatic ad detection or DRM bypass.
 */
public final class CaptureService extends Service {
    public static final String ACTION_START="il.nolimits.music.capture.START";
    public static final String ACTION_STOP="il.nolimits.music.capture.STOP";
    public static final String ACTION_MARK="il.nolimits.music.capture.MARK";
    public static final String EXTRA_RESULT_CODE="projectionResultCode";
    public static final String EXTRA_RESULT_DATA="projectionData";
    public static final String EXTRA_MARK_TITLE="markTitle";
    public static final String EXTRA_MARK_ID="markVideoId";
    public static final String EXTRA_MARK_KIND="markKind";
    public static final String PREFS="morin_capture_session";
    private static final String CHANNEL="morin_capture";
    private static final int NOTIFICATION_ID=2422;
    private static final String TAG="MorinCapture";
    private final AtomicBoolean capturing=new AtomicBoolean(false);
    private final AtomicBoolean stopping=new AtomicBoolean(false);
    private MediaProjection projection;
    private MediaRecorder screenRecorder;
    private VirtualDisplay display;
    private AudioRecord playbackAudio;
    private MediaCodec encoder;
    private MediaMuxer audioMuxer;
    private Thread audioThread;
    private File folder, screenFile, audioFile, combinedFile, marksFile;
    private long startAt;
    private volatile boolean audioActive;
    private volatile String audioError="";
    private int audioMuxTrack=-1;

    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel(CHANNEL,"הקלטת MorinMusic",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("הקלטת מסך ושמע בעת האזנה");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }
    @Override public IBinder onBind(Intent i){return null;}
    private Notification notification(String message){
        Intent stop=new Intent(this,CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi=PendingIntent.getService(this,21,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent activity=new Intent(this,CaptureActivity.class);
        PendingIntent view=PendingIntent.getActivity(this,22,activity,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL)
            .setContentTitle("MorinMusic · הקלטה")
            .setContentText(message).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(view).setOngoing(capturing.get())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"סיים הקלטה",stopPi).build())
            .build();
    }
    private void notifyStatus(String message){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification(message));
    }
    private void preferences(String status){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putString("status",status)
            .putString("folder",folder==null?"":folder.getAbsolutePath())
            .putLong("started",startAt).apply();
    }
    @Override public int onStartCommand(Intent command,int flags,int id){
        if(command==null)return START_NOT_STICKY;
        String action=command.getAction();
        if(ACTION_STOP.equals(action)){requestStop();return START_NOT_STICKY;}
        if(ACTION_MARK.equals(action)){mark(command);return START_NOT_STICKY;}
        if(!ACTION_START.equals(action))return START_NOT_STICKY;
        if(capturing.get()||stopping.get())return START_NOT_STICKY;
        int code=command.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED);
        Intent data=command.getParcelableExtra(EXTRA_RESULT_DATA);
        if(code!=Activity.RESULT_OK||data==null){
            stopSelf();return START_NOT_STICKY;
        }
        try{
            // targetSdk 35: startForeground must precede getMediaProjection.
            startForeground(NOTIFICATION_ID,notification("מאתחלת הקלטה"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            startProjection(code,data);
        }catch(Exception e){
            Log.e(TAG,"Start failed",e);
            audioError=e.getMessage();
            preferences("שגיאה בתחילת הקלטה: "+audioError);
            requestStop();
        }
        return START_NOT_STICKY;
    }
    private void startProjection(int code,Intent data) throws Exception{
        MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=manager.getMediaProjection(code,data);
        if(projection==null)throw new IOException("לא התקבלה הרשאת צילום מסך");
        projection.registerCallback(new MediaProjection.Callback(){
            @Override public void onStop(){requestStop();}
        },new Handler(Looper.getMainLooper()));
        File parent=getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        if(parent==null)throw new IOException("האחסון המקומי אינו זמין");
        folder=new File(parent,"Sessions/"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8));
        if(!folder.mkdirs())throw new IOException("אי אפשר ליצור תיקיית הקלטה");
        screenFile=new File(folder,"screen-video.mp4");
        audioFile=new File(folder,"playback-audio.m4a");
        combinedFile=new File(folder,"session-video-audio.mp4");
        marksFile=new File(folder,"song-marks.jsonl");
        DisplayMetrics metrics=getResources().getDisplayMetrics();
        float scale=Math.min(1f,1080f/Math.max(metrics.widthPixels,metrics.heightPixels));
        int width=(Math.max(16,(int)(metrics.widthPixels*scale)) / 16)*16;
        int height=(Math.max(16,(int)(metrics.heightPixels*scale)) / 16)*16;
        screenRecorder=new MediaRecorder();
        screenRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        screenRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        screenRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        screenRecorder.setVideoSize(width,height);
        screenRecorder.setVideoFrameRate(24);
        screenRecorder.setVideoEncodingBitRate(2400000);
        screenRecorder.setOutputFile(screenFile.getAbsolutePath());
        screenRecorder.prepare();
        display=projection.createVirtualDisplay("MorinMusicCapture",width,height,metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,screenRecorder.getSurface(),null,
            new Handler(Looper.getMainLooper()));
        if(display==null)throw new IOException("לא ניתן ליצור הקלטת מסך");
        screenRecorder.start();
        startAt=SystemClock.elapsedRealtime();
        capturing.set(true);
        stopping.set(false);
        preferences("מקליטה");
        notifyStatus("מקליטה ברצף · הקש לסיום");
        try{startAudio();}catch(Exception e){
            audioError="שמע פנימי אינו זמין: "+e.getMessage();
            Log.w(TAG,"Audio capture unavailable",e);
            notifyStatus("מקליטה וידאו; "+audioError);
        }
    }
    private void startAudio() throws Exception {
        if(Build.VERSION.SDK_INT<29)throw new IOException("הקלטת שמע פנימי דורשת Android 10 ומעלה");
        final int rate=48000, channels=AudioFormat.CHANNEL_IN_STEREO;
        AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build();
        AudioFormat fmt=new AudioFormat.Builder().setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channels).build();
        int min=AudioRecord.getMinBufferSize(rate,channels,AudioFormat.ENCODING_PCM_16BIT);
        if(min<0)throw new IOException("תצורת הקלטת שמע אינה נתמכת");
        playbackAudio=new AudioRecord.Builder().setAudioFormat(fmt)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(Math.max(32768,min*2)).build();
        if(playbackAudio.getState()!=AudioRecord.STATE_INITIALIZED)
            throw new IOException("AudioRecord לא אותחל");
        MediaFormat aac=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,2);
        aac.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        aac.setInteger(MediaFormat.KEY_BIT_RATE,160000);
        aac.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);
        encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(aac,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        audioMuxer=new MediaMuxer(audioFile.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        audioMuxTrack=-1;
        audioActive=true;
        playbackAudio.startRecording();
        audioThread=new Thread(()->encodeAudio(rate),"MorinMusic-AAC");
        audioThread.start();
    }
    private void encodeAudio(int rate) {
        long frames=0;
        boolean eosQueued=false,eosOutput=false;
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        try{
            while(!eosOutput){
                if(!eosQueued){
                    int input=encoder.dequeueInputBuffer(12000);
                    if(input>=0){
                        ByteBuffer b=encoder.getInputBuffer(input);
                        if(b==null)throw new IOException("Codec input buffer missing");
                        b.clear();
                        if(audioActive){
                            int size=playbackAudio.read(b,Math.min(16384,b.remaining()),AudioRecord.READ_BLOCKING);
                            if(size>0){
                                long pts=frames*1000000L/rate;
                                frames+=size/4; // stereo, signed 16-bit
                                encoder.queueInputBuffer(input,0,size,pts,0);
                            }else if(size<0)throw new IOException("AudioRecord error "+size);
                            else encoder.queueInputBuffer(input,0,0,frames*1000000L/rate,0);
                        }else{
                            encoder.queueInputBuffer(input,0,0,frames*1000000L/rate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosQueued=true;
                        }
                    }
                }
                int output=encoder.dequeueOutputBuffer(info,12000);
                if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    if(audioMuxTrack!=-1)throw new IOException("Duplicate audio codec format");
                    audioMuxTrack=audioMuxer.addTrack(encoder.getOutputFormat());
                    audioMuxer.start();
                }else if(output>=0){
                    ByteBuffer b=encoder.getOutputBuffer(output);
                    if(b!=null&&info.size>0&&audioMuxTrack>=0){
                        b.position(info.offset);b.limit(info.offset+info.size);
                        audioMuxer.writeSampleData(audioMuxTrack,b,info);
                    }
                    eosOutput=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    encoder.releaseOutputBuffer(output,false);
                }
            }
        }catch(Exception e){
            audioError="הקלטת שמע נכשלה: "+e.getMessage();
            Log.e(TAG,"AAC encoding failed",e);
        }finally{
            try{playbackAudio.stop();}catch(Exception ignored){}
            try{playbackAudio.release();}catch(Exception ignored){}
            playbackAudio=null;
            try{encoder.stop();}catch(Exception ignored){}
            try{encoder.release();}catch(Exception ignored){}
            encoder=null;
            try{if(audioMuxTrack>=0)audioMuxer.stop();}catch(Exception ignored){}
            try{audioMuxer.release();}catch(Exception ignored){}
            audioMuxer=null;
        }
    }
    private synchronized void mark(Intent intent){
        if(!capturing.get()||marksFile==null)return;
        String video=intent.getStringExtra(EXTRA_MARK_ID);
        String title=intent.getStringExtra(EXTRA_MARK_TITLE);
        String kind=intent.getStringExtra(EXTRA_MARK_KIND);
        long delta=SystemClock.elapsedRealtime()-startAt;
        try(FileOutputStream out=new FileOutputStream(marksFile,true)){
            org.json.JSONObject item=new org.json.JSONObject();
            item.put("ms",delta).put("kind",kind==null?"mark":kind)
               .put("videoId",video==null?"":video).put("title",title==null?"":title);
            out.write((item.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception e){Log.e(TAG,"Unable to mark",e);}
    }
    private void requestStop(){
        if(!stopping.compareAndSet(false,true))return;
        capturing.set(false);audioActive=false;
        new Thread(()->finish(),"MorinMusic-Finalize").start();
    }
    private void finish(){
        boolean goodVideo=false;
        try{
            if(audioThread!=null)audioThread.join(5000);
            if(screenRecorder!=null){
                try{screenRecorder.stop();goodVideo=true;}
                catch(Exception e){audioError="וידאו לא הושלם: "+e.getMessage();}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        finally{
            if(display!=null){display.release();display=null;}
            if(screenRecorder!=null){try{screenRecorder.reset();screenRecorder.release();}catch(Exception ignored){}screenRecorder=null;}
            if(projection!=null){try{projection.stop();}catch(Exception ignored){}projection=null;}
        }
        try{
            if(goodVideo&&screenFile!=null&&screenFile.isFile()){
                if(audioFile!=null&&audioFile.isFile()&&audioFile.length()>1000)
                    SessionMediaProcessor.combine(screenFile,audioFile,combinedFile);
                else combinedFile=screenFile;
                preferences("הושלם: "+combinedFile.getName()+(audioError.isEmpty()?"":" · "+audioError));
            }else preferences("ההקלטה הופסקה; לא נוצר וידאו תקין.");
        }catch(Exception e){
            preferences("הווידאו נשמר בנפרד, אך שילוב השמע נכשל: "+e.getMessage());
        }finally{
            notifyStatus("ההקלטה הסתיימה · פתח את הספרייה");
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }
    @Override public void onDestroy(){
        if(capturing.get())requestStop();
        super.onDestroy();
    }
}
