package il.nolimits.music;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** In-app YouTube IFrame player. YouTube decides availability, ads and playback policy. */
public final class YouTubePlayerActivity extends Activity {
    static final String EXTRA_ID = "video_id";
    static final String EXTRA_TITLE = "video_title";

    private WebView web;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String videoId = getIntent().getStringExtra(EXTRA_ID);
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            finish();
            return;
        }

        getWindow().setStatusBarColor(0xff101018);
        getWindow().setNavigationBarColor(0xff101018);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(0xff101018);
        int padding = dp(12);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra(EXTRA_TITLE));
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setMaxLines(2);
        root.addView(title);

        status = new TextView(this);
        status.setText("טוען את הנגן המובנה של YouTube…");
        status.setTextColor(0xffb2b2c7);
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if ("music-player".equals(uri.getScheme())) {
                    String event = uri.getHost();
                    if ("playing".equals(event)) status.setText("מנגן בתוך האפליקציה");
                    else if ("ended".equals(event)) status.setText("השיר הסתיים");
                    else if ("blocked".equals(event)) status.setText("הניגון האוטומטי נחסם; לחץ על נגן.");
                    else if ("error".equals(event)) status.setText("השיר אינו זמין בנגן המובנה. ייתכן שבעליו חסם הטמעה.");
                    else if ("ready".equals(event)) status.setText("הנגן מוכן");
                    return true;
                }
                // Never open an external browser as a side effect of selecting a track.
                status.setText("קישור חיצוני נחסם. חזור לספרייה כדי לבחור שיר.");
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) status.setText("לא ניתן לטעון את הנגן. בדוק חיבור לאינטרנט.");
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button toggle = new Button(this);
        toggle.setText("נגן / השהה");
        toggle.setAllCaps(false);
        toggle.setOnClickListener(v -> {
            if (web != null) web.evaluateJavascript("window.togglePlayback && window.togglePlayback()", null);
        });
        root.addView(toggle);

        Button close = new Button(this);
        close.setText("חזרה לשירים");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        root.addView(close);

        TextView note = new TextView(this);
        note.setText("YouTube עשויה להציג פרסומות. השמעה ברקע וזמינות ההטמעה תלויות ב-YouTube.");
        note.setTextColor(0xffb2b2c7);
        note.setTextSize(12);
        root.addView(note);

        setContentView(root);
        try (InputStream in = getAssets().open("youtube_player.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int count;
            while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
            String html = new String(out.toByteArray(), StandardCharsets.UTF_8)
                    .replace("__VIDEO_ID__", videoId);
            web.loadDataWithBaseURL(Catalog.ORIGIN, html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            status.setText("לא ניתן לטעון את הנגן.");
        }
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause() {
        if (web != null) {
            web.evaluateJavascript("window.pausePlayback && window.pausePlayback()", null);
            web.onPause();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
