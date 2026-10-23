package il.nolimits.music;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Uploads completed, user-consented screen videos with audio to the configured
 * authenticated cloud service. A private staging file survives failures so
 * no recording is falsely reported as stored online.
 */
final class CloudUpload {
    static final String PREFS = "cloud_storage_settings";
    static final String ENDPOINT = "cloud_endpoint";
    static final String TOKEN = "cloud_access_token";
    // Offline saving is one tri-state preference; video is the legacy enabled format.
    static final String KEEP_OFFLINE = "keep_recording_offline";
    static final String OFFLINE_MODE = "offline_recording_mode";
    static final String MODE_NO = "none";
    static final String MODE_AUDIO = "audio";
    static final String MODE_VIDEO = "video";
    static final String ACTION_STATUS = "il.nolimits.music.CLOUD_STATUS";
    static final String EXTRA_ID = "cloud_track_id";
    static final String EXTRA_OK = "cloud_success";
    static final String EXTRA_MESSAGE = "cloud_message";
    private static final String LOG = "NoLimitCloud";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String offlineMode(Context c) {
        String mode=prefs(c).getString(OFFLINE_MODE,null);
        if (MODE_NO.equals(mode) || MODE_AUDIO.equals(mode) || MODE_VIDEO.equals(mode))
            return mode;
        return prefs(c).getBoolean(KEEP_OFFLINE,false) ? MODE_VIDEO : MODE_NO;
    }

    static void setOfflineMode(Context c,String mode) {
        if (!MODE_NO.equals(mode) && !MODE_AUDIO.equals(mode) && !MODE_VIDEO.equals(mode))
            throw new IllegalArgumentException("Bad offline mode");
        prefs(c).edit().putString(OFFLINE_MODE,mode).remove(KEEP_OFFLINE).apply();
    }

    static boolean keepOffline(Context c) {
        return !MODE_NO.equals(offlineMode(c));
    }

    static boolean localAvailable(Context c,String id) {
        return RecordedOfflineAudio.saved(c,id)||RecordedVideo.saved(c,id);
    }

    static Uri localPlaybackUri(Context c,String id) {
        // A freshly recorded MP4 can be a pending upload; favor the user's
        // requested offline format when both files happen to exist.
        if (MODE_AUDIO.equals(offlineMode(c)) && RecordedOfflineAudio.saved(c,id))
            return RecordedOfflineAudio.uri(c,id);
        if (RecordedVideo.saved(c,id)) return RecordedVideo.uri(c,id);
        return RecordedOfflineAudio.uri(c,id);
    }

    static String baseUrl(Context c) {
        String address = prefs(c).getString(ENDPOINT, Catalog.ORIGIN);
        if (address == null || !address.startsWith("https://")) return Catalog.ORIGIN;
        return address.endsWith("/") ? address : address + "/";
    }

    static boolean configured(Context c) {
        return !prefs(c).getString(TOKEN, "").isEmpty();
    }

    static boolean uploaded(Context c, String id) {
        return prefs(c).getBoolean("video_uploaded_" + id, false);
    }

    static Uri recordedRemoteUri(Context c, String id) {
        return Uri.parse(baseUrl(c) + "api/recordings/" + id + ".mp4");
    }

    static void enqueue(Context c, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        Context app = c.getApplicationContext();
        WORKER.execute(() -> upload(app, id));
    }

    static void retryPending(Context c) {
        Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            File dir = RecordedVideo.folder(app);
            File[] recordings = dir.listFiles((d,name) -> name.endsWith(".mp4"));
            if (recordings == null) return;
            for (File f : recordings) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
                if (!uploaded(app,id)) upload(app,id);
                else try {
                    applyRetention(app,id);
                } catch (IOException e) {
                    Log.w(LOG,"Could not finalize offline format",e);
                }
            }
        });
    }

    static String authToken(Context c) { return prefs(c).getString(TOKEN, ""); }

    private static void upload(Context c, String id) {
        if (!RecordedVideo.saved(c, id)) return;
        String token = authToken(c);
        if (token.isEmpty()) {
            report(c,id,false,"ממתינה להגדרת הרשאת העלאה לשרת. הקובץ הזמני נשמר עד אז.");
            return;
        }
        HttpURLConnection connection = null;
        try {
            File file = RecordedVideo.song(c,id);
            URL url = new URL(baseUrl(c) + "api/recordings/" + id + ".mp4");
            if (!"https".equalsIgnoreCase(url.getProtocol()))
                throw new IOException("Upload requires HTTPS");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(18000);
            connection.setReadTimeout(90000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "video/mp4");
            connection.setRequestProperty("X-Track-Id", id);
            connection.setFixedLengthStreamingMode(file.length());
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = connection.getOutputStream()) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer,0,n);
                output.flush();
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300)
                throw new IOException("שרת ההקלטות החזיר שגיאה " + status);

            // Mark uploaded only after the server acknowledged a complete write.
            prefs(c).edit().putBoolean("video_uploaded_"+id,true).apply();
            String localResult;
            try {
                localResult=applyRetention(c,id);
            } catch (IOException extractionError) {
                Log.w(LOG,"Cloud upload succeeded but offline processing failed",extractionError);
                // Keep the source MP4 so offline audio can be retried safely.
                localResult="; עיבוד עותק האופליין נכשל, המקור נשמר זמנית";
            }
            if(keepOffline(c)) LyricsCache.fetchAsync(c,id);
            report(c,id,true,"הווידאו והשמע נשמרו באתר" + localResult);
        } catch (Exception e) {
            Log.w(LOG,"Cloud upload failed for "+id,e);
            report(c,id,false,"השמירה באתר טרם הושלמה; הקובץ נשמר זמנית להעלאה חוזרת.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Only delete the staged MP4 after the server has acknowledged upload. */
    private static String applyRetention(Context c,String id) throws IOException {
        String mode=offlineMode(c);
        if (MODE_VIDEO.equals(mode)) {
            File source=RecordedVideo.song(c,id);
            if (!source.isFile()) throw new IOException("No local MP4 staging file");
            PublicMusicStorage.saveFile(c,id,"mp4",source);
            if (!source.delete()) Log.w(LOG,"Public MP4 saved but private copy could not be removed");
            return "; עותק MP4 שמור בתיקיית ההורדות";
        }
        if (MODE_AUDIO.equals(mode)) {
            File video=RecordedVideo.song(c,id);
            if (!RecordedOfflineAudio.saved(c,id)) {
                if (!video.isFile()) throw new IOException("No MP4 available for audio extraction");
                RecordedOfflineAudio.extract(c,id,video);
            }
            if (!RecordedOfflineAudio.saved(c,id))
                throw new IOException("Offline audio was not created");
            File audio=RecordedOfflineAudio.song(c,id);
            if (audio.isFile()) {
                PublicMusicStorage.saveFile(c,id,"m4a",audio);
                if (!audio.delete()) Log.w(LOG,"Public M4A saved but private copy remains");
            } else if (PublicMusicStorage.find(c,id,"m4a")==null) {
                throw new IOException("No audio file available for public saving");
            }
            File video=RecordedVideo.song(c,id);
            if (video.exists() && !video.delete())
                Log.w(LOG,"Unable to remove staged video after exporting M4A");
            return "; עותק M4A שמור בתיקיית ההורדות";
        }
        if (!RecordedVideo.delete(c,id))
            Log.w(LOG,"Unable to remove uploaded MP4 staging file");
        return "; ללא עותק אופליין";
    }

    private static void report(Context c,String id,boolean ok,String text) {
        c.sendBroadcast(new Intent(ACTION_STATUS).setPackage(c.getPackageName())
                .putExtra(EXTRA_ID,id).putExtra(EXTRA_OK,ok)
                .putExtra(EXTRA_MESSAGE,text));
    }
}
