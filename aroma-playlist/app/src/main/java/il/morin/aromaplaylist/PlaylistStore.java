package il.morin.aromaplaylist;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class PlaylistStore {
    private static final String FILE = "aroma_playlist";
    private PlaylistStore() {}
    static synchronized void status(Context context, String message) {
        prefs(context).edit().putString("status", message).apply();
    }
    static String statusText(Context context) {
        return prefs(context).getString("status", "מוכן להאזנה");
    }
    static synchronized void add(Context context, JSONObject song) throws JSONException {
        String title = song.optString("title", "").trim();
        if (title.isEmpty()) return;
        String artist = song.optString("artist", "").trim();
        String key = song.optString("key", title + ":" + artist);
        long now = System.currentTimeMillis();
        JSONArray list = all(context);
        if (list.length() > 0) {
            JSONObject previous = list.optJSONObject(list.length() - 1);
            if (previous != null && key.equals(previous.optString("key"))
                    && now - previous.optLong("addedAt") < 240000L) {
                status(context, "ממשיך להתנגן: " + title);
                return;
            }
        }
        JSONObject saved = new JSONObject();
        saved.put("title", title);
        saved.put("artist", artist);
        saved.put("key", key);
        saved.put("url", song.optString("url"));
        saved.put("addedAt", now);
        list.put(saved);
        prefs(context).edit().putString("tracks", list.toString()).apply();
        status(context, "זוהה: " + title + " • " + artist);
    }
    static synchronized JSONArray all(Context context) {
        String stored = prefs(context).getString("tracks", "[]");
        try { return new JSONArray(stored); }
        catch (JSONException e) { return new JSONArray(); }
    }
    static synchronized void clear(Context context) {
        prefs(context).edit().putString("tracks", "[]").putString("status", "הרשימה נוקתה").apply();
    }
    static String text(Context context) {
        JSONArray arr = all(context);
        StringBuilder b = new StringBuilder("Aroma Playlist 🎵\n");
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject track = arr.optJSONObject(i);
            if (track == null) continue;
            b.append(i + 1).append(". ").append(track.optString("title"))
             .append(" — ").append(track.optString("artist"))
             .append(" (").append(dateFormat.format(new Date(track.optLong("addedAt")))).append(")\n");
            String url = track.optString("url");
            if (!url.isEmpty()) b.append(url).append("\n");
        }
        return b.toString();
    }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
