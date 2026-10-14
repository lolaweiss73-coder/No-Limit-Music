package il.nolimits.music;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.os.Build;
import android.widget.Toast;
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

    private static final int PERMISSION_AUDIO = 4091;
    private static final int PERMISSION_PROJECTION = 4092;
    private WebView web;
    private TextView status;
    private Button capture;
    private String trackId;
    private boolean capturing;
    private boolean restartPending;
    private boolean screenResumed;
    private boolean captureRequested;
    private boolean receiverRegistered;
    private final BroadcastReceiver captureEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (CloudUpload.ACTION_STATUS.equals(action)) {
                if (trackId.equals(intent.getStringExtra(CloudUpload.EXTRA_ID)))
                    status.setText(intent.getStringExtra(CloudUpload.EXTRA_MESSAGE));
                return;
            }
            if (!trackId.equals(intent.getStringExtra(CaptureService.EXTRA_ID))) return;
            String message = intent.getStringExtra(CaptureService.EXTRA_DETAILS);
            if (CaptureService.READY.equals(action)) {
                capturing = true;
                capture.setVisibility(View.VISIBLE);
                capture.setText("עצור ושמור");
                status.setText(message);
                restartPending = true;
                restartForCapture();
            } else if (CaptureService.FINISHED.equals(action)) {
                capturing = false;
                capture.setVisibility(View.GONE);
                status.setText(message);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String videoId = getIntent().getStringExtra(EXTRA_ID);
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            finish();
            return;
        }

        trackId = videoId;
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
                    else if ("ended".equals(event)) { status.setText("השיר הסתיים"); if (capturing) stopCapture(); }
                    else if ("blocked".equals(event)) status.setText("הניגון האוטומטי נחסם; לחץ על נגן.");
                    else if ("error".equals(event)) status.setText("השיר אינו זמין בנגן המובנה. ייתכן שבעליו חסם הטמעה.");
                    else if ("ready".equals(event)) {
                        status.setText("הנגן מוכן · מכינה הקלטה אוטומטית");
                        if (!captureRequested) {
                            captureRequested = true;
                            if (RecordedVideo.saved(YouTubePlayerActivity.this, trackId) && !CloudUpload.uploaded(YouTubePlayerActivity.this, trackId)) {
                                status.setText("קיימת הקלטה מקומית שממתינה להעלאה.");
                                CloudUpload.enqueue(YouTubePlayerActivity.this, trackId);
                            } else if (CloudUpload.uploaded(YouTubePlayerActivity.this, trackId)) {
                                status.setText("הווידאו כבר שמור באתר.");
                            } else if (!CloudUpload.configured(YouTubePlayerActivity.this)
                                    && !CloudUpload.keepOffline(YouTubePlayerActivity.this)) {
                                status.setText("שרת ההקלטות עוד לא חובר. ניתן להאזין כרגיל; לא נוצרת הקלטה שתיתקע במכשיר.");
                            } else requestAudioPermission();
                        }
                    }
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

        capture = new Button(this);
        capture.setAllCaps(false);
        capture.setText("עצור ושמור");
        capture.setVisibility(View.GONE);
        // The user never needs to press Record. This button is only an
        // emergency/manual stop once Android has approved and started capture.
        capture.setOnClickListener(v -> { if (capturing) stopCapture(); });
        root.addView(capture);
        IntentFilter filter = new IntentFilter();
        filter.addAction(CaptureService.READY);
        filter.addAction(CaptureService.FINISHED);
        filter.addAction(CloudUpload.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(captureEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(captureEvents, filter);
        }
        receiverRegistered = true;

        Button close = new Button(this);
        close.setText("חזרה לשירים");
        close.setAllCaps(false);
        close.setOnClickListener(v -> { if (capturing) stopCapture(); finish(); });
        root.addView(close);

        TextView note = new TextView(this);
        note.setText("ההקלטה מתחילה אוטומטית לאחר אישור Android ושומרת וידאו עם שמע באתר. בהגדרות אפשר לבחור: ללא אופליין, וידאו או שמע בלבד.");
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


    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT < 29) {
            status.setText("הקלטת שמע פנימי דורשת Android 10 ומעלה.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_AUDIO);
        } else {
            requestProjection();
        }
    }

    private void requestProjection() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        // Android 14+: choose full-display capture rather than a single app,
        // respecting the user's standard system consent dialog.
        Intent request;
        if (Build.VERSION.SDK_INT >= 34) {
            request = manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            request = manager.createScreenCaptureIntent();
        }
        startActivityForResult(request, PERMISSION_PROJECTION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == PERMISSION_AUDIO) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
                requestProjection();
            else status.setText("לא אושרה הרשאה להקלטת שמע פנימי.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PERMISSION_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                status.setText("הקלטת המסך בוטלה.");
                return;
            }
            Intent service = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.START)
                    .putExtra(CaptureService.EXTRA_ID, trackId)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data);
            status.setText("התקבל אישור Android; מתחילה הקלטה אוטומטית…");
            status.setText("מפעיל צילום מסך ולכידת שמע...");
            startForegroundService(service);
        }
    }

    private void restartForCapture() {
        if (!restartPending || web == null || !screenResumed) return;
        restartPending = false;
        web.onResume();
        web.postDelayed(() -> {
            if (web != null && !isFinishing()) web.evaluateJavascript(
                    "window.restartPlayback && window.restartPlayback()", null);
        }, 150);
    }

    private void stopCapture() {
        capturing = false;
        capture.setVisibility(View.GONE);
        Intent service = new Intent(this, CaptureService.class).setAction(CaptureService.STOP);
        startService(service);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause() {
        screenResumed = false;
        if (web != null) {
            web.evaluateJavascript("window.pausePlayback && window.pausePlayback()", null);
            web.onPause();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        screenResumed = true;
        if (web != null) web.onResume();
        if (restartPending) restartForCapture();
    }

    @Override protected void onDestroy() {
        if (capturing) stopCapture();
        if (receiverRegistered) { unregisterReceiver(captureEvents); receiverRegistered = false; }
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
