package il.nolimits.music;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * AI talk-show test runtime with swappable LLM transport and original Android TTS voice.
 * OpenRouter free router requires a personally supplied API key and has rate limits.
 * Real people/characters are fictional, illustrative simulations; not endorsed impersonations.
 * Network sends show prompt and recent chat context ONLY after user chooses to start.
 */
public final class LiveShowActivity extends Activity {
    private static final int BG=0xff10131d, WHITE=0xfff7f7fc, SUB=0xffbac6d9;
    private static final String PREFS="morin_live_show_v1", KEY_CIPHER="api_key_encrypted";
    private static final String KEY_IV="api_key_iv", KEY_ALIAS="morinmusic_openrouter_aes";
    private static final int REQUEST_SPEECH=7742;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ArrayList<JSONObject> history=new ArrayList<>();
    private TextView transcript,status;
    private EditText message;
    private ScrollView scroll;
    private TextToSpeech tts;
    private boolean readAloud=false,busy=false,closed=false;
    private String showJson="{}",model="openrouter/free";
    private String title="",topic="",format="",rules="",castPrompt="";
    private interface AIProvider{String complete(JSONArray messages)throws Exception;}
    private AIProvider provider;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        showJson=getIntent().getStringExtra("show");
        if(showJson==null||showJson.trim().isEmpty())showJson="{}";
        try{JSONObject show=new JSONObject(showJson);
            title=show.optString("title","תוכנית אירוח");
            topic=show.optString("topic","שיחת ערב פתוחה");
            rules=show.optString("rules","");
            format=new String[]{"תוכנית אירוח","תוכנית רדיו","פאנל","שעשועון","שיחה חופשית"}[Math.min(4,Math.max(0,show.optInt("format",0)))];
            JSONArray cast=show.optJSONArray("cast");StringBuilder sb=new StringBuilder();
            if(cast!=null)for(int i=0;i<cast.length();i++){
                JSONObject c=cast.optJSONObject(i);if(c==null)continue;
                sb.append("\n").append(c.optString("name")).append(" | ")
                  .append(c.optString("role")).append(" | ").append(c.optString("traits"));
            }
            castPrompt=sb.toString();
        }catch(Exception ignored){}
        provider=this::callOpenRouter;
        build();
        tts=new TextToSpeech(this,s->{
            if(s==TextToSpeech.SUCCESS){
                int ok=tts.setLanguage(new Locale("he","IL"));
                runOnUiThread(()->status.setText(ok<0
                  ?"מנוע הדיבור במכשיר אינו תומך בעברית. ניתן להמשיך בצ'אט כתוב."
                  :"מוכן. הדמויות הן הדמיות בטקסט, עם קול סינתטי כללי בלבד."));
            }
        });
        if(state!=null){
            transcript.setText(state.getString("transcript",""));
            String h=state.getString("history","[]");
            try{JSONArray a=new JSONArray(h);for(int i=0;i<a.length();i++)history.add(a.getJSONObject(i));}
            catch(Exception ignored){}
        }else append("🎙️ "+title+"\nנושא: "+topic+"\n\nגרסת AI ניסיונית. אין חיקוי קולי של ידוענים. כדי להתחיל צריך מפתח API לשירות חינמי עם מגבלות שימוש.");
    }
    private int dp(int v){return (int)(getResources().getDisplayMetrics().density*v+.5f);}
    private TextView label(String s,int size){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(WHITE);t.setTextSize(size);
        t.setPadding(dp(5),dp(8),dp(5),dp(8));return t;
    }
    private EditText edit(String hint){
        EditText e=new EditText(this);e.setTextSize(15);e.setTextColor(WHITE);e.setHintTextColor(SUB);e.setHint(hint);
        e.setPadding(dp(9),dp(8),dp(9),dp(8));return e;
    }
    private Button button(String name,Runnable f){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextSize(12);b.setOnClickListener(v->f.run());return b;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return l;}
    private void add(LinearLayout l,View v){l.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setBackgroundColor(BG);
        root.setPadding(dp(12),dp(14),dp(12),dp(12));
        add(root,label("🎙️ "+(title.isEmpty()?"תוכנית אירוח חיה":title),22));
        LinearLayout options=row();
        options.addView(button("🔑 הגדר AI",this::settings),new LinearLayout.LayoutParams(0,-2,1));
        options.addView(button("▶ התחל",()->send("פתחו את התוכנית במונולוג קצר של המנחה והציגו את המשתתפים.",false)),
                new LinearLayout.LayoutParams(0,-2,1));
        options.addView(button("🔈 קול כבוי/פועל",()->{readAloud=!readAloud;status.setText(readAloud?"הקראת טקסט בקול כללי פעילה":"הקראה כבויה");}),
                new LinearLayout.LayoutParams(0,-2,1));
        add(root,options);
        status=label("כדי להתחיל, הוסף מפתח OpenRouter להגדרות.",12);
        status.setTextColor(SUB);add(root,status);
        scroll=new ScrollView(this);
        transcript=label("",16);transcript.setTextIsSelectable(true);
        scroll.addView(transcript);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        message=edit("שאל את הדמויות, היכנס לוויכוח או בחר שיר…");
        message.setSingleLine(false);message.setMaxLines(4);add(root,message);
        LinearLayout bottom=row();
        bottom.addView(button("שלח",()->{String s=message.getText().toString().trim();if(!s.isEmpty())send(s,true);}),
                new LinearLayout.LayoutParams(0,-2,1));
        bottom.addView(button("🎤 דבר",this::recognizeSpeech),new LinearLayout.LayoutParams(0,-2,1));
        bottom.addView(button("🛑 עצור קול",()->{if(tts!=null)tts.stop();}),
                new LinearLayout.LayoutParams(0,-2,1));
        add(root,bottom);
        add(root,label("AI חיצוני: המידע שתשלח בתוכנית עובר לספק המודל. השימוש ב־openrouter/free מוגבל לפי הספק ואינו מובטח לזמינות תמידית.",11));
        setContentView(root);
    }
    private void append(String s){
        transcript.append(s+"\n\n");
        scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void settings(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16),dp(5),dp(16),dp(5));
        TextView text=label("מפתח OpenRouter אישי (לא נשלח ל־GitHub). נשמר מוצפן במכשיר באמצעות Android Keystore.",13);
        form.addView(text);
        EditText key=edit("sk-or-v1-…");key.setSingleLine(true);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(key);
        TextView modelText=label("מזהה מודל OpenRouter. מודל חינמי מוצע: openrouter/free",13);
        form.addView(modelText);
        EditText modelInput=edit("openrouter/free");
        modelInput.setText(model);form.addView(modelInput);
        new AlertDialog.Builder(this).setTitle("הגדרות מנוע AI")
            .setView(form)
            .setPositiveButton("שמור",(d,w)->{
                try{
                    if(!key.getText().toString().trim().isEmpty())encryptKey(key.getText().toString().trim());
                    model=modelInput.getText().toString().trim();
                    if(model.isEmpty())model="openrouter/free";
                    status.setText("ההגדרות נשמרו. אפשר להתחיל תוכנית.");
                }catch(Exception ex){status.setText("לא ניתן לשמור מפתח: "+ex.getMessage());}
            })
            .setNeutralButton("מחק מפתח",(d,w)->{
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(KEY_CIPHER).remove(KEY_IV).apply();
                status.setText("מפתח OpenRouter נמחק.");
            })
            .setNegativeButton("ביטול",null).show();
    }
    private javax.crypto.SecretKey aesKey() throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(KEY_ALIAS))return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY_ALIAS,null)).getSecretKey();
        javax.crypto.KeyGenerator kg=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return kg.generateKey();
    }
    private void encryptKey(String raw) throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,aesKey());
        String ciphertext=Base64.encodeToString(c.doFinal(raw.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        String iv=Base64.encodeToString(c.getIV(),Base64.NO_WRAP);
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putString(KEY_CIPHER,ciphertext).putString(KEY_IV,iv).apply();
    }
    private String decryptKey() throws Exception{
        android.content.SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        String ciphertext=p.getString(KEY_CIPHER,null),iv=p.getString(KEY_IV,null);
        if(ciphertext==null||iv==null)return "";
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,aesKey(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(ciphertext,Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    private String systemPrompt(){
        return "אתה מנוע לסימולציית תוכנית אירוח/רדיו בדיונית בעברית. "
            +"שם: "+title+". פורמט: "+format+". נושא: "+topic+". "
            +"חוקי העולם: "+rules+". צוות דמויות עם תכונות ותפקידים: "+castPrompt+". "
            +"בכל תגובה צור 2–5 משפטים קצרים של דמויות שונות, בשורות שמתחילות בשם הדמות ונקודתיים. "
            +"המנחה מוביל, הדמויות שומרות על אישיות, לא מתווכחות כולן בכל תור, ויכולות לדבר זו עם זו. "
            +"המאזין רשאי להתפרץ ולשנות נושא. אל תמציא עובדות: סמן השערות. "
            +"אנשים אמיתיים ודמויות מוכרות הם הדמיות ספרותיות, ולא קולותיהם האמיתיים או דבריהם. "
            +"אל תציג דמות כתיעוד אמיתי של האדם; אל תטען שהקולות אותנטיים. "
            +"אם אין מספיק פרטים תאלתר ברוח התכונות אך אל תייחס דברים שקריים לאדם אמיתי כאילו התרחשו באמת. "
            +"השתמש בעברית טבעית, חדה, מצחיקה לפי ההקשר. אל תוסיף כותרות טכניות.";
    }
    private void send(String input,boolean userTyped){
        if(busy){status.setText("התוכנית עדיין מגיבה…");return;}
        String key;
        try{key=decryptKey();}catch(Exception e){status.setText("המפתח לא זמין, הגדר אותו מחדש.");return;}
        if(key.isEmpty()){settings();return;}
        busy=true;
        if(userTyped){append("👤 אתה: "+input);message.setText("");}
        status.setText("הדמויות חושבות על התשובה…");
        JSONArray messages=new JSONArray();
        try{
            messages.put(new JSONObject().put("role","system").put("content",systemPrompt()));
            for(int i=Math.max(0,history.size()-14);i<history.size();i++)messages.put(history.get(i));
            messages.put(new JSONObject().put("role","user").put("content",input));
        }catch(Exception ignored){}
        io.execute(()->{
            try{
                String reply=provider.complete(messages);
                if(reply==null||reply.trim().isEmpty())throw new IOException("המודל החזיר תשובה ריקה.");
                JSONObject in=new JSONObject().put("role","user").put("content",input);
                JSONObject out=new JSONObject().put("role","assistant").put("content",reply);
                runOnUiThread(()->{
                    if(closed)return;
                    history.add(in);history.add(out);
                    append(reply);
                    status.setText("אפשר לשאול, להגיב או לשנות נושא.");
                    if(readAloud&&tts!=null)tts.speak(reply,TextToSpeech.QUEUE_FLUSH,null,"morin_show");
                    busy=false;
                });
            }catch(Exception e){runOnUiThread(()->{
                if(closed)return;
                status.setText("שגיאת AI: "+e.getMessage());busy=false;
            });}
        });
    }
    private String callOpenRouter(JSONArray messages) throws Exception{
        String key=decryptKey();if(key.isEmpty())throw new IOException("לא הוגדר מפתח OpenRouter.");
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        c.setRequestMethod("POST");c.setConnectTimeout(18000);c.setReadTimeout(70000);c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+key);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("X-Title","MorinMusic Studio");
        JSONObject body=new JSONObject().put("model",model).put("messages",messages).put("max_tokens",750);
        try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        int status=c.getResponseCode();
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        InputStream source=status<400?c.getInputStream():c.getErrorStream();
        if(source!=null)try(InputStream input=source){
            byte[] buf=new byte[4096];int count;while((count=input.read(buf))!=-1)raw.write(buf,0,count);
        }
        c.disconnect();
        String text=new String(raw.toByteArray(),StandardCharsets.UTF_8);
        if(status>=400)throw new IOException("OpenRouter "+status+" "+text.substring(0,Math.min(170,text.length())));
        JSONObject response=new JSONObject(text);
        return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","");
    }
    private void recognizeSpeech(){
        try{
            Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"he-IL");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT,"דבר אל צוות התוכנית");
            startActivityForResult(i,REQUEST_SPEECH);
        }catch(Exception e){status.setText("זיהוי דיבור אינו מותקן במכשיר. אפשר להקליד.");}
    }
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==REQUEST_SPEECH&&res==RESULT_OK&&data!=null){
            ArrayList<String> lines=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(lines!=null&&!lines.isEmpty())message.setText(lines.get(0));
        }
    }
    @Override protected void onSaveInstanceState(Bundle s){
        super.onSaveInstanceState(s);s.putString("transcript",transcript.getText().toString());
        JSONArray h=new JSONArray();for(JSONObject m:history)h.put(m);s.putString("history",h.toString());
    }
    @Override protected void onDestroy(){
        closed=true;if(tts!=null){tts.stop();tts.shutdown();}io.shutdownNow();super.onDestroy();
    }
}
