package com.morin.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** Public YouTube playlist index with official iframe playback and locally saved URL bookmarks. */
public class PublicPlaylistsActivity extends Activity {
    private static final String ORIGIN = "https://avi-music-player.lolaweiss73.chatgpt.site/";
    private static final int NAVY = 0xff132136;
    private JSONArray catalog = new JSONArray();
    private LinearLayout root;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scrolling = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(22));
        root.setBackgroundColor(NAVY);
        scrolling.addView(root);
        setContentView(scrolling);
        try (InputStream in = getAssets().open("playlists.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > 3_000_000) throw new Exception("Catalogue too large");
            }
            catalog = new JSONArray(out.toString("UTF-8"));
        } catch (Exception e) {
            Toast.makeText(this, "לא הצלחתי לטעון את הפלייליסטים", Toast.LENGTH_LONG).show();
        }
        int tracks = 0;
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject playlist = catalog.optJSONObject(i);
            if (playlist != null) {
                JSONArray t = playlist.optJSONArray("tracks");
                if (t != null) tracks += t.length();
            }
        }
        label("🎶 MORIN MUSIC", 26, Color.WHITE);
        label("הפלייליסטים הציבוריים שלך · " + catalog.length() + " רשימות ו־" + tracks + " שירים", 15, 0xffd2e5fc);
        addButton("➕ הוספת פלייליסט ציבורי מקישור YouTube", this::addPlaylistUrl);
        addButton("📚 הפלייליסטים שהוספתי", this::showSavedPlaylists);
        addButton("🔍 חיפוש פלייליסטים ב־YouTube", this::searchPlaylists);
        label("לחץ על רשימה כדי לבחור שיר. ניגון קליפים נעשה בנגן YouTube מוטמע; פרסומות ומגבלות רקע אינן בשליטת האפליקציה.", 12, 0xffbdcfe7);
        label("הפלייליסטים הקיימים", 21, Color.WHITE);
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject p = catalog.optJSONObject(i);
            if (p == null) continue;
            JSONArray t = p.optJSONArray("tracks");
            if (t == null || t.length() == 0) continue;
            String title = p.optString("title", "פלייליסט");
            addButton("🎵 " + title + " · " + t.length() + " שירים", () -> chooseTrack(p));
        }
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private void label(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(dp(3), dp(7), dp(3), dp(7));
        root.addView(v);
    }
    private void addButton(String s, Runnable action) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff315f91));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(3), 0, dp(3));
        root.addView(b, params);
        b.setOnClickListener(v -> action.run());
    }
    private static boolean validVideo(String id) { return id != null && id.matches("[a-zA-Z0-9_-]{11}"); }
    private static boolean validList(String id) { return id != null && id.matches("[a-zA-Z0-9_-]{10,100}"); }

    private void chooseTrack(JSONObject playlist) {
        JSONArray items = playlist.optJSONArray("tracks");
        if (items == null) return;
        ArrayList<JSONObject> playable = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject song = items.optJSONObject(i);
            if (song == null || !validVideo(song.optString("youtubeId"))) continue;
            playable.add(song);
            boolean hosted = ("media/" + song.optString("youtubeId") + ".mp3").equals(song.optString("src", ""));
            names.add((hosted ? "🔊 " : "🎬 ") + song.optString("title", "ללא שם"));
        }
        if (playable.isEmpty()) return;
        new AlertDialog.Builder(this)
            .setTitle(playlist.optString("title", "פלייליסט"))
            .setItems(names.toArray(new String[0]), (d, which) -> showSong(playable.get(which)))
            .setNegativeButton("סגור", null)
            .show();
    }
    private void showSong(JSONObject track) {
        String id = track.optString("youtubeId");
        String name = track.optString("title", "קליפ");
        boolean hosted = ("media/" + id + ".mp3").equals(track.optString("src", ""));
        if (!hosted) { showPlayer("video", id, name); return; }
        new AlertDialog.Builder(this).setTitle(name)
            .setItems(new String[]{"🎬 צפה ב־YouTube בתוך האפליקציה", "⬇ הורד MP3 מהשרת להאזנה אופליין"},
                (d, option) -> {
                    if (option == 0) showPlayer("video", id, name);
                    else downloadAudio(id, name);
                }).show();
    }
    private void downloadAudio(String id, String name) {
        if (!validVideo(id)) return;
        try {
            String safe = name.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            if (safe.length() > 65) safe = safe.substring(0, 65);
            if (safe.isEmpty()) safe = id;
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(ORIGIN + "media/" + id + ".mp3"))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MorinMusic/" + safe + "-" + id + ".mp3");
            ((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(this, "מתחיל הורדה ל־Downloads/MorinMusic", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "ההורדה נכשלה: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void showPlayer(String kind, String id, String name) {
        boolean list = "playlist".equals(kind);
        if (list ? !validList(id) : !validVideo(id)) return;
        String url = list ? "https://www.youtube.com/playlist?list=" + id : "https://www.youtube.com/watch?v=" + id;
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        String src = list
            ? "https://www.youtube.com/embed/videoseries?list=" + id + "&playsinline=1"
            : "https://www.youtube.com/embed/" + id + "?playsinline=1";
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>"
            + "<meta name='referrer' content='strict-origin-when-cross-origin'></head>"
            + "<body style='background:black;margin:0'><iframe style='width:100%;height:330px;border:0' "
            + "allow='autoplay; encrypted-media; picture-in-picture' allowfullscreen src='" + src + "'></iframe></body></html>";
        web.loadDataWithBaseURL(ORIGIN, html, "text/html", "UTF-8", null);
        new AlertDialog.Builder(this).setTitle(name).setView(web)
            .setPositiveButton("פתח ב־YouTube", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
            .setNegativeButton("סגור", null)
            .setOnDismissListener(d -> { web.stopLoading(); web.destroy(); })
            .show();
    }
    private JSONArray saved() {
        try { return new JSONArray(getSharedPreferences("links", 0).getString("playlists", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void addPlaylistUrl() {
        EditText input = new EditText(this);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setSingleLine(true);
        input.setHint("https://www.youtube.com/playlist?list=PL...");
        new AlertDialog.Builder(this).setTitle("הוסף פלייליסט ציבורי")
            .setMessage("הדבק קישור של רשימת YouTube ציבורית. לא נדרשת גישה לחשבון Google.")
            .setView(input)
            .setPositiveButton("הוסף", (d, w) -> {
                String text = input.getText().toString().trim();
                String id = null;
                try {
                    Uri u = Uri.parse(text);
                    String h = u.getHost();
                    if (("https".equals(u.getScheme()) || "http".equals(u.getScheme())) &&
                        h != null && (h.equals("youtube.com") || h.endsWith(".youtube.com") || h.equals("youtu.be"))) {
                        id = u.getQueryParameter("list");
                    }
                } catch (Exception ignored) { }
                if (!validList(id)) { Toast.makeText(this, "קישור לא תקין. צריך לכלול list=", Toast.LENGTH_LONG).show(); return; }
                JSONArray saved = saved();
                for (int i = 0; i < saved.length(); i++) {
                    JSONObject existing = saved.optJSONObject(i);
                    if (existing != null && id.equals(existing.optString("id"))) {
                        showPlayer("playlist", id, "הפלייליסט שלי"); return;
                    }
                }
                try {
                    JSONObject entry = new JSONObject().put("id", id).put("name", "פלייליסט " + (saved.length() + 1));
                    saved.put(entry);
                    getSharedPreferences("links", 0).edit().putString("playlists", saved.toString()).apply();
                    showPlayer("playlist", id, "פלייליסט נוסף");
                } catch (Exception e) { Toast.makeText(this, "לא הצלחתי לשמור את הקישור", Toast.LENGTH_SHORT).show(); }
            }).setNegativeButton("ביטול", null).show();
    }
    private void showSavedPlaylists() {
        JSONArray saved = saved();
        if (saved.length() == 0) {
            new AlertDialog.Builder(this).setMessage("עדיין לא הוספת פלייליסט ציבורי. לחץ על 'הוסף פלייליסט' והדבק קישור.")
                .setPositiveButton("סגור", null).show();
            return;
        }
        String[] labels = new String[saved.length()];
        for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i);
            labels[i] = (o == null ? "פלייליסט" : o.optString("name", "פלייליסט")) + " · " + (o == null ? "" : o.optString("id"));
        }
        new AlertDialog.Builder(this).setTitle("הפלייליסטים שהוספתי")
            .setItems(labels, (d, which) -> {
                JSONObject o = saved.optJSONObject(which);
                if (o != null) showPlayer("playlist", o.optString("id"), labels[which]);
            }).setNegativeButton("סגור", null)
            .setNeutralButton("נקה רשימה", (d, w) -> new AlertDialog.Builder(this)
                .setMessage("למחוק את קישורי הפלייליסטים שהוספת?")
                .setPositiveButton("מחק", (d2, w2) -> getSharedPreferences("links", 0).edit().remove("playlists").apply())
                .setNegativeButton("ביטול", null).show())
            .show();
    }
    private void searchPlaylists() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("זמר, סגנון או שם רשימה");
        new AlertDialog.Builder(this).setTitle("חיפוש ב־YouTube").setView(input)
            .setPositiveButton("חפש", (d, w) -> {
                String q = input.getText().toString().trim();
                if (!q.isEmpty())
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q) + "&sp=EgIQAw%253D%253D")));
            }).setNegativeButton("ביטול", null).show();
    }
}
