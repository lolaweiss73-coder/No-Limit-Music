package il.nolimits.music;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;

/** App-owned introduction; never added to the music catalog or playback queue. */
public class IntroActivity extends Activity {
    static final String PREFS="intro", DISABLED="skip_nRfo1NdTN7k", REPLAY="replay";
    private WebView web;
    private TextView status;
    private boolean replay, leaving;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable slow=()->{if(status!=null)status.setText("הטעינה מתעכבת. אפשר להמתין או לדלג.");};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        replay=getIntent().getBooleanExtra(REPLAY,false);
        if(saved==null&&!replay&&getSharedPreferences(PREFS,0).getBoolean(DISABLED,false)){complete();return;}
        getWindow().setStatusBarColor(0xff101018);getWindow().setNavigationBarColor(0xff101018);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101018);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(16),dp(12),dp(16),dp(12));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(16)+i.getSystemWindowInsetLeft(),dp(12)+i.getSystemWindowInsetTop(),dp(16)+i.getSystemWindowInsetRight(),dp(12)+i.getSystemWindowInsetBottom());return i;});
        TextView title=new TextView(this);title.setText("מוזיקה ללא גבולות");title.setTextColor(Color.WHITE);title.setTextSize(25);root.addView(title);
        status=new TextView(this);status.setText("טוענת את קליפ הפתיחה…");status.setTextColor(0xffb2b2c7);status.setTextSize(14);root.addView(status);
        web=new WebView(this);web.setBackgroundColor(Color.BLACK);
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                Uri uri=r.getUrl();
                if(r.isForMainFrame()&&"intro-event".equals(uri.getScheme())){
                    String event=uri.getHost();
                    if("ended".equals(event))complete();
                    else if("playing".equals(event)){handler.removeCallbacks(slow);status.setText("קליפ הפתיחה");}
                    else if("blocked".equals(event)){handler.removeCallbacks(slow);status.setText("לחץ על הנגן כדי להתחיל, או דלג.");}
                    else if("error".equals(event)){handler.removeCallbacks(slow);status.setText("הקליפ לא זמין כרגע. אפשר לדלג ולהמשיך למוזיקה.");}
                    return true;
                }
                // Never launch a browser automatically from the intro player.
                if(r.isForMainFrame()){
                    status.setText("קישור חיצוני אינו נפתח אוטומטית. אפשר לדלג ולהמשיך למוזיקה.");
                    return true;
                }
                return false;
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){
                if(r.isForMainFrame())status.setText("לא ניתן לטעון את הקליפ. אפשר לדלג.");
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        CheckBox skip=new CheckBox(this);skip.setText("אל תנגן את הקליפ הזה שוב בפתיחת האפליקציה");skip.setTextColor(Color.WHITE);skip.setTextSize(15);
        skip.setChecked(getSharedPreferences(PREFS,0).getBoolean(DISABLED,false));
        skip.setOnCheckedChangeListener((b,checked)->getSharedPreferences(PREFS,0).edit().putBoolean(DISABLED,checked).apply());root.addView(skip);
        Button next=new Button(this);next.setText("דלג");next.setAllCaps(false);next.setOnClickListener(v->complete());root.addView(next);
        setContentView(root);
        try(java.io.InputStream in=getAssets().open("intro.html")){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[4096];int count;while((count=in.read(buf))!=-1)out.write(buf,0,count);
            web.loadDataWithBaseURL(Catalog.ORIGIN,new String(out.toByteArray(),StandardCharsets.UTF_8),"text/html","UTF-8",null);
            handler.postDelayed(slow,15000);
        }catch(Exception e){status.setText("לא ניתן לטעון את הקליפ. אפשר לדלג.");}
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void complete(){
        if(leaving)return;leaving=true;handler.removeCallbacksAndMessages(null);
        if(web!=null){web.stopLoading();web.loadUrl("about:blank");web.onPause();}
        if(!replay)startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    @Override public void onBackPressed(){complete();}
    @Override protected void onPause(){if(web!=null){web.evaluateJavascript("window.pauseIntro && window.pauseIntro()",null);web.onPause();}super.onPause();}
    @Override protected void onResume(){super.onResume();if(web!=null){web.onResume();web.evaluateJavascript("window.resumeIntro && window.resumeIntro()",null);}}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(web!=null){((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}super.onDestroy();}
}
