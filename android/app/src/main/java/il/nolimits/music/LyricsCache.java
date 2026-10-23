package il.nolimits.music;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads only lyrics explicitly published by our own licensed LRC endpoint. */
final class LyricsCache {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final String ENDPOINT = "https://no-limit-music-sync.floot.app/_api/lyrics?youtubeId=";
    private LyricsCache() {}

    static void fetchAsync(Context context, String trackId) {
        if(trackId==null || !trackId.matches("[A-Za-z0-9_-]{11}")) return;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            if (PublicMusicStorage.find(app,trackId,"lrc") != null) return;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(ENDPOINT + trackId).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setUseCaches(false);
                int response = connection.getResponseCode();
                if (response == 404) return;
                if (response != 200) throw new IOException("Lyrics HTTP " + response);
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n=input.read(buffer))!=-1) {
                        result.write(buffer,0,n);
                        if (result.size()>260000) throw new IOException("Lyrics too large");
                    }
                }
                JSONObject lyrics = new JSONObject(result.toString(StandardCharsets.UTF_8.name()));
                if (!lyrics.optBoolean("available") ||
                        !trackId.equals(lyrics.optString("youtubeId")) ||
                        !"lrc".equals(lyrics.optString("format"))) return;
                String text = lyrics.optString("text","");
                if (text.trim().isEmpty()) return;
                PublicMusicStorage.saveText(app,trackId,text);
            } catch (Exception e) {
                Log.w("NoLimitLyrics","Lyrics not available for "+trackId,e);
            } finally {
                if(connection!=null) connection.disconnect();
            }
        });
    }
}
