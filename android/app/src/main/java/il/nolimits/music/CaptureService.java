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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-approved projection: temporary whole-screen video + eligible internal
 * playback audio. The private temporary video is deleted after extracting WAV.
 * Never bypasses Android playback-capture policy or protected surfaces.
 */
public final class CaptureService extends Service {
    static final String START = "il.nolimits.music.capture.START";
    static final String STOP = "il.nolimits.music.capture.STOP";
    static final String READY = "il.nolimits.music.capture.READY";
    static final String FINISHED = "il.nolimits.music.capture.FINISHED";
    static final String EXTRA_ID = "capture_track_id";
    static final String EXTRA_CODE = "capture_consent_code";
    static final String EXTRA_DATA = "capture_consent_data";
    static final String EXTRA_OK = "capture_succeeded";
    static final String EXTRA_DETAILS = "capture_details";
    private static final int NOTIFICATION_ID = 2201;
    private static final String CHANNEL = "screen_capture";
    private static final String TAG = "NoLimitCapture";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder screenRecorder;
    private AudioRecord audioRecord;
    private File temporaryVideo, temporaryAudio;
    private FileOutputStream pcmStream;
    private Thread audioThread;
    private String songId;
    private volatile boolean recording;
    private volatile String audioFailure;
    private volatile boolean initialized;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                "הקלטת מסך ושמע", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (STOP.equals(action)) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (!START.equals(action) || initialized) return START_NOT_STICKY;

        initialized = true;
        songId = intent.getStringExtra(EXTRA_ID);
        try {
            if (songId == null || !songId.matches("[A-Za-z0-9_-]{11}"))
                throw new IllegalArgumentException("Invalid track id");

            // Android 14+: start the mediaProjection foreground service BEFORE
            // passing the one-use consent intent into getMediaProjection().
            startForeground(NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

            Intent consent = (Intent) intent.getParcelableExtra(EXTRA_DATA);
            if (consent == null) throw new IllegalArgumentException("Missing capture permission");
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), consent);
            if (projection == null) throw new SecurityException("Capture was not authorized");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { requestStop(); }
            }, new Handler(Looper.getMainLooper()));

            initializeScreenVideo();
            initializePlaybackAudio();

            recording = true;
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Android could not start playback capture");
            audioThread = new Thread(this::writeCapturedAudio, "NoLimitPCM");
            audioThread.start();
            sendEvent(READY, true, "ההקלטה פועלת. הנגן יחזור לתחילת השיר.");
        } catch (Exception ex) {
            Log.e(TAG, "Capture start failed", ex);
            audioFailure = "לא ניתן להתחיל הקלטה: " + ex.getClass().getSimpleName();
            requestStop();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        Intent stop = new Intent(this, CaptureService.class).setAction(STOP);
        PendingIntent pendingStop = PendingIntent.getService(this, 2201, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(this, 2202, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle("מוזיקה ללא גבולות · הקלטה פעילה")
                .setContentText("מצלם את המסך ולוכד שמע פנימי שמותר ללכוד")
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "עצור ושמור", pendingStop)
                .build();
    }

    private void initializeScreenVideo() throws IOException {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        double scale = Math.min(1.0, 1920.0 / Math.max(width, height));
        int videoWidth = Math.max(320, ((int) (width * scale) / 16) * 16);
        int videoHeight = Math.max(320, ((int) (height * scale) / 16) * 16);
        temporaryVideo = new File(getCacheDir(), "screen-" + songId + ".mp4");
        if (temporaryVideo.exists() && !temporaryVideo.delete())
            throw new IOException("Cannot replace temporary video");

        screenRecorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        screenRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        screenRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        screenRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        screenRecorder.setVideoSize(videoWidth, videoHeight);
        screenRecorder.setVideoFrameRate(24);
        screenRecorder.setVideoEncodingBitRate(2_000_000);
        screenRecorder.setOutputFile(temporaryVideo.getAbsolutePath());
        screenRecorder.prepare();
        virtualDisplay = projection.createVirtualDisplay("NoLimitCapture",
                videoWidth, videoHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenRecorder.getSurface(), null, new Handler(Looper.getMainLooper()));
        if (virtualDisplay == null) throw new IOException("Cannot start screen capture");
        screenRecorder.start();
    }

    private void initializePlaybackAudio() throws IOException {
        int min = AudioRecord.getMinBufferSize(RecordedAudio.RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("Playback recording format is unavailable");
        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RecordedAudio.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 4, 16384))
                .setAudioPlaybackCaptureConfig(config)
                .build();
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED)
            throw new IOException("Internal audio capture could not initialize");
        temporaryAudio = new File(getCacheDir(), "internal-" + songId + ".pcm");
        if (temporaryAudio.exists() && !temporaryAudio.delete())
            throw new IOException("Cannot replace temporary sound");
        pcmStream = new FileOutputStream(temporaryAudio);
    }

    private void writeCapturedAudio() {
        byte[] buffer = new byte[32768];
        try {
            while (recording && !Thread.currentThread().isInterrupted()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) pcmStream.write(buffer, 0, read);
                else if (read == AudioRecord.ERROR_DEAD_OBJECT
                        || read == AudioRecord.ERROR_INVALID_OPERATION) {
                    if (recording) audioFailure = "מערכת ההקלטה הפסיקה לספק שמע";
                    break;
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Failed writing captured PCM", ex);
            audioFailure = "שגיאה בזמן שמירת השמע";
        } finally {
            if (recording) requestStop();
        }
    }

    private void requestStop() {
        if (!stopping.compareAndSet(false, true)) return;
        recording = false;
        new Thread(this::finishCapture, "NoLimitCaptureFinish").start();
    }

    private void finishCapture() {
        try {
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
            }
            if (audioThread != null) {
                try { audioThread.join(2500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (pcmStream != null) {
                try { pcmStream.flush(); pcmStream.close(); } catch (Exception ignored) {}
                pcmStream = null;
            }

            if (screenRecorder != null) {
                try { screenRecorder.stop(); } catch (Exception ignored) {}
            }
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (screenRecorder != null) {
                try { screenRecorder.reset(); screenRecorder.release(); } catch (Exception ignored) {}
                screenRecorder = null;
            }
            if (projection != null) {
                try { projection.stop(); } catch (Exception ignored) {}
                projection = null;
            }
            if (audioRecord != null) { audioRecord.release(); audioRecord = null; }

            if (audioFailure != null) throw new IOException(audioFailure);
            if (temporaryAudio == null || !temporaryAudio.isFile())
                throw new IOException("לא התקבלה הקלטת שמע");

            RecordedAudio.Result result = RecordedAudio.finish(this, songId, temporaryAudio);
            // The file is only a private upload staging copy by default.
            // Keep it offline permanently only when the user opted in.
            sendEvent(FINISHED, true, "ההקלטה הסתיימה (" + result.durationMs / 1000
                    + " שניות). מעבירה את השיר לאתר…");
            CloudUpload.enqueue(this, songId);
        } catch (Exception ex) {
            Log.w(TAG, "Capture did not produce usable audio", ex);
            sendEvent(FINISHED, false, ex.getMessage() == null ? "לא ניתן לשמור את ההקלטה" : ex.getMessage());
        } finally {
            if (temporaryVideo != null) temporaryVideo.delete(); // do not retain screen/chat contents
            if (temporaryAudio != null) temporaryAudio.delete();
            if (virtualDisplay != null) virtualDisplay.release();
            if (screenRecorder != null) {
                try { screenRecorder.release(); } catch (Exception ignored) {}
            }
            if (audioRecord != null) {
                try { audioRecord.release(); } catch (Exception ignored) {}
            }
            if (projection != null) {
                try { projection.stop(); } catch (Exception ignored) {}
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void sendEvent(String action, boolean ok, String details) {
        Intent result = new Intent(action).setPackage(getPackageName())
                .putExtra(EXTRA_ID, songId)
                .putExtra(EXTRA_OK, ok)
                .putExtra(EXTRA_DETAILS, details);
        sendBroadcast(result);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
