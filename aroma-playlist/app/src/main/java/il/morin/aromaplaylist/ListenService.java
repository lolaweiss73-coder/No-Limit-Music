package il.morin.aromaplaylist;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ListenService extends Service {
    private static final int RATE = 16000;
    private static final int WINDOW_BYTES = RATE * 2 * 10;
    private static final int ID = 194;
    private static final String CHANNEL = "aroma_listening";
    private volatile boolean listening;
    private volatile AudioRecord recorder;
    private Thread audioThread;
    private final ExecutorService recognizeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean recognitionBusy = new AtomicBoolean(false);

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (listening) return START_NOT_STICKY;
        createNotificationChannel();
        try { startForeground(ID, notification()); }
        catch (Exception e) {
            PlaylistStore.status(this, "שגיאה בהפעלת התראת ההאזנה: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        listening = true;
        audioThread = new Thread(this::audioLoop, "Aroma-Audio");
        audioThread.start();
        return START_NOT_STICKY;
    }

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("הרשאת מיקרופון חסרה");
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("מיקרופון אינו תומך ב־16kHz");
            AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, RATE * 2));
            recorder = ar;
            if (ar.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("לא ניתן להפעיל את המיקרופון");
            ar.startRecording();
            byte[] buffer = new byte[Math.max(4096, min)];
            while (listening) {
                ByteArrayOutputStream captured = new ByteArrayOutputStream(WINDOW_BYTES);
                while (listening && captured.size() < WINDOW_BYTES) {
                    int count = ar.read(buffer, 0,
                            Math.min(buffer.length, WINDOW_BYTES - captured.size()));
                    if (count > 0) captured.write(buffer, 0, count);
                    else if (count < 0) throw new IllegalStateException("AudioRecord: " + count);
                }
                if (!listening) break;
                if (recognitionBusy.compareAndSet(false, true)) {
                    File sample = File.createTempFile("aroma_", ".pcm", getCacheDir());
                    try (FileOutputStream stream = new FileOutputStream(sample)) {
                        captured.writeTo(stream);
                    }
                    recognizeExecutor.execute(() -> recognize(sample));
                } else {
                    PlaylistStore.status(this, "ממשיך להאזין, הזיהוי הקודם בעיבוד…");
                }
            }
        } catch (Exception error) {
            Log.e("AromaPlaylist", "Audio loop failed", error);
            PlaylistStore.status(this, "שגיאת הקלטה: " + error.getMessage());
        } finally {
            AudioRecord ar = recorder;
            recorder = null;
            if (ar != null) {
                try { if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) ar.stop(); }
                catch (Exception ignored) {}
                ar.release();
            }
            listening = false;
            stopSelf();
        }
    }
    private void recognize(File sample) {
        try {
            PyObject worker = Python.getInstance().getModule("recognizer");
            String output = worker.callAttr("recognize_pcm_file", sample.getAbsolutePath()).toString();
            JSONObject song = new JSONObject(output);
            String status = song.optString("status");
            if ("match".equals(status)) PlaylistStore.add(this, song);
            else if ("no_match".equals(status)) PlaylistStore.status(this, "מאזינה… עדיין אין זיהוי");
            else PlaylistStore.status(this, "זיהוי נכשל: " + song.optString("message", "שגיאה לא ידועה"));
        } catch (Exception error) {
            Log.e("AromaPlaylist", "Recognition failed", error);
            PlaylistStore.status(this, "שגיאת זיהוי: " + error.getMessage());
        } finally {
            if (!sample.delete()) Log.w("AromaPlaylist", "Could not delete temporary sample");
            recognitionBusy.set(false);
        }
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "זיהוי שירים", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("מראה כאשר האפליקציה מאזינה למוזיקה");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }
    }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("Aroma Playlist מאזינה")
                .setContentText("זיהוי שירים ברצף. פתח כדי לעצור.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true).setContentIntent(pi).build();
    }
    @Override public void onDestroy() {
        listening = false;
        AudioRecord ar = recorder;
        if (ar != null) try { ar.stop(); } catch (Exception ignored) {}
        recognizeExecutor.shutdownNow();
        super.onDestroy();
    }
}
