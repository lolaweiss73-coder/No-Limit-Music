package il.morin.aromaplaylist;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private TextView statusView;
    private TextView listView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 2000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(22, 36, 49));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(24, 28, 24, 16);
        root.setBackgroundColor(Color.rgb(249, 251, 252));

        TextView title = new TextView(this);
        title.setText("🎵 Aroma Playlist");
        title.setTextSize(26f);
        title.setTextColor(Color.rgb(20, 45, 61));
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("מזהה שירים ברצף ושומרת את סדר ההשמעה");
        sub.setTextSize(15f);
        sub.setPadding(0, 4, 0, 20);
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextSize(17f);
        statusView.setTextColor(Color.rgb(24, 100, 82));
        statusView.setPadding(0, 0, 0, 16);
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("התחל האזנה");
        Button stop = button("עצור");
        buttons.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons);
        start.setOnClickListener(v -> startListening());
        stop.setOnClickListener(v -> stopListening());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button share = button("שתף רשימה");
        Button clear = button("נקה רשימה");
        actions.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        share.setOnClickListener(v -> sharePlaylist());
        clear.setOnClickListener(v -> {
            PlaylistStore.clear(this);
            refresh();
        });

        ScrollView scroller = new ScrollView(this);
        listView = new TextView(this);
        listView.setTextSize(17f);
        listView.setTextColor(Color.rgb(30, 43, 50));
        listView.setLineSpacing(8f, 1.12f);
        listView.setPadding(0, 18, 0, 8);
        scroller.addView(listView);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refresh();
    }
    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }
    @Override protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }
    private void refresh() {
        statusView.setText(PlaylistStore.statusText(this));
        JSONArray tracks = PlaylistStore.all(this);
        if (tracks.length() == 0) { listView.setText("עדיין לא זוהו שירים. אפשר להתחיל להאזין."); return; }
        StringBuilder b = new StringBuilder();
        for (int i = tracks.length() - 1; i >= 0; i--) {
            JSONObject song = tracks.optJSONObject(i);
            if (song == null) continue;
            b.append(i + 1).append(". ").append(song.optString("title"))
             .append("\n    ").append(song.optString("artist")).append("\n\n");
        }
        listView.setText(b.toString());
    }
    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 42);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
            return;
        }
        Intent intent = new Intent(this, ListenService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        PlaylistStore.status(this, "האזנה התחילה, אפשר לכבות את המסך");
        refresh();
    }
    private void stopListening() {
        stopService(new Intent(this, ListenService.class));
        PlaylistStore.status(this, "ההאזנה נעצרה");
        refresh();
    }
    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            PlaylistStore.status(this, "נדרשת הרשאת מיקרופון כדי לזהות שירים");
            refresh();
        }
    }
    private void sharePlaylist() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Aroma Playlist");
        send.putExtra(Intent.EXTRA_TEXT, PlaylistStore.text(this));
        startActivity(Intent.createChooser(send, "ייצוא הפלייליסט"));
    }
}
