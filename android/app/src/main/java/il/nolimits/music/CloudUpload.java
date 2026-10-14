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
 * Uploads completed, user-consented audio recordings to the configured
 * authenticated cloud service. A private staging file survives failures so
 * no recording is falsely reported as stored online.
 */
final class CloudUpload {
    static final String PREFS = "cloud_storage_settings";
    static final String ENDPOINT = "cloud_endpoint";
    static final String TOKEN = "cloud_access_token";
    static final String KEEP_OFFLINE = "keep_recording_offline";
    static final String ACTION_STATUS = "il.nolimits.music.CLOUD_STATUS";
    static final String EXTRA_ID = "cloud_track_id";
    static final String EXTRA_OK = "cloud_success";
    static final String EXTRA_MESSAGE = "cloud_message";
    private static final String LOG = "NoLimitCloud";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean keepOffline(Context c) {
        return prefs(c).getBoolean(KEEP_OFFLINE, false);
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
        return prefs(c).getBoolean("uploaded_" + id, false);
    }

    static Uri recordedRemoteUri(Context c, String id) {
        return Uri.parse(baseUrl(c) + "api/recordings/" + id);
    }

    static void enqueue(Context c, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        Context app = c.getApplicationContext();
        WORKER.execute(() -> upload(app, id));
    }

    static void retryPending(Context c) {
        Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            File dir = RecordedAudio.folder(app);
            File[] recordings = dir.listFiles((d,name) -> name.endsWith(".wav"));
            if (recordings == null) return;
            for (File f : recordings) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
                if (!uploaded(app,id)) upload(app,id);
                else if (!keepOffline(app)) RecordedAudio.delete(app,id);
            }
        });
    }

    static String authToken(Context c) { return prefs(c).getString(TOKEN, ""); }

    private static void upload(Context c, String id) {
        if (!RecordedAudio.saved(c, id)) return;
        String token = authToken(c);
        if (token.isEmpty()) {
            report(c,id,false,"ממתינה להגדרת הרשאת העלאה לשרת. הקובץ הזמני נשמר עד אז.");
            return;
        }
        HttpURLConnection connection = null;
        try {
            File file = RecordedAudio.song(c,id);
            URL url = new URL(baseUrl(c) + "api/recordings/" + id);
            if (!"https".equalsIgnoreCase(url.getProtocol()))
                throw new IOException("Upload requires HTTPS");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(18000);
            connection.setReadTimeout(90000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "audio/wav");
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
            prefs(c).edit().putBoolean("uploaded_"+id,true).apply();
            if (!keepOffline(c)) RecordedAudio.delete(c,id);
            report(c,id,true,"השיר נשמר באתר" + (keepOffline(c)?" וגם במכשיר":""));
        } catch (Exception e) {
            Log.w(LOG,"Cloud upload failed for "+id,e);
            report(c,id,false,"השמירה באתר טרם הושלמה; הקובץ נשמר זמנית להעלאה חוזרת.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void report(Context c,String id,boolean ok,String text) {
        c.sendBroadcast(new Intent(ACTION_STATUS).setPackage(c.getPackageName())
                .putExtra(EXTRA_ID,id).putExtra(EXTRA_OK,ok)
                .putExtra(EXTRA_MESSAGE,text));
    }
}
