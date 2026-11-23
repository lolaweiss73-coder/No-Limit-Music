package il.nolimits.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Offline-first show/cast editor.
 * This screen does NOT claim to generate AI dialogue or imitate celebrity voices.
 * Saved shows stay on the device until the user exports/deletes them.
 */
public class StudioActivity extends Activity {
    private static final int BG=0xff10131d, PANEL=0xff202636, WHITE=0xfff7f7fc,
            SOFT=0xffb9c5d8, ACCENT=0xff84dcc6, PINK=0xffeb96bb;
    private static final String PREFS="morin_studio_v1", KEY="shows";
    private static final String[] FORMATS={"תוכנית אירוח","תוכנית רדיו","פאנל","שעשועון","שיחה חופשית"};
    private static final String[] ROLES={"מנחה","אורח/ת","DJ","קומיקאי/ת","מפיק/ה","מומחה/ית"};
    private static final String[][] PRESETS={
        {"איל קיציס","מנחה","הנחיה מאופקת והומור יבש"},
        {"ארז טל","מנחה","קצב, משחקיות וניהול אולפן"},
        {"אברי גלעד","אורח/ת","דעות ברורות ושאלות נוקבות"},
        {"ג'יי לנו","מנחה","מונולוגים, ראיונות והומור"},
        {"ירון אשבל","DJ","נוסטלגיה ולהיטים אהובים"},
        {"יואב קוטנר","מומחה/ית","ידע מוזיקלי, סקרנות וסיפורי רוק"},
        {"אדריאן קרונאואר","DJ","שדרן רדיו פרוע מ'בוקר טוב, וייטנאם'"},
        {"אדוארד גארליק","מפיק/ה","מפיק שמנסה לשמור על הסדר"},
        {"רובין ויליאמס","קומיקאי/ת","אלתור קומי ואנרגיה מתפרצת"},
        {"Weird Al Yankovic","קומיקאי/ת","פארודיות מקוריות ומשחקי מילים"},
        {"Q","אורח/ת","פרובוקציות קוסמיות, ללא כוחות לשינוי האולפן"},
        {"ז'אן-לוק פיקארד","אורח/ת","אתיקה, מנהיגות, איפוק"},
        {"קתרין ג'יינוויי","אורח/ת","פתרון בעיות, סקרנות ומנהיגות"},
        {"הדוקטור מ-Voyager","אורח/ת","רפואה, הומור יבש ואישיות נמרצת"},
        {"לאונרדו מ-Voyager","אורח/ת","אמן וממציא הולוגרפי"},
        {"גנדלף","אורח/ת","חוכמה, סבלנות ומבט היסטורי"},
        {"גימלי","אורח/ת","נאמנות, עקשנות והומור"},
        {"טרי פראצ'ט","אורח/ת","סאטירה, אנושיות ופילוסופיה"},
        {"מורין ג'ונסון לונג","אורח/ת","עצמאות מחשבתית, אירוניה ופתיחות"},
        {"ד\"ר רות וסטהיימר","מומחה/ית","חינוך מיני, אנושיות והומור"},
        {"נתן זהבי","אורח/ת","טון נוקב וביקורת חברתית"}
    };
    private static final class CastMember {
        String name, role, traits;
        CastMember(String n,String r,String t){name=n;role=r;traits=t;}
        JSONObject toJson() throws Exception {
            JSONObject x=new JSONObject();x.put("name",name);x.put("role",role);x.put("traits",traits);return x;
        }
        static CastMember fromJson(JSONObject x){
            return new CastMember(x.optString("name"),x.optString("role","אורח/ת"),x.optString("traits"));
        }
    }
    private final ArrayList<CastMember> cast=new ArrayList<>();
    private String currentId=null;
    private EditText titleInput, topicInput, rulesInput;
    private Spinner formatInput;
    private SeekBar musicInput;
    private TextView musicValue, note, castTitle;
    private LinearLayout castRows;
    private boolean initializing=false;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        build();
        if(state!=null){
            try {restoreDraft(new JSONObject(state.getString("draft","{}")));}catch(Exception ignored){}
        } else {
            titleInput.setText("בוקר טוב, כל היקומים");
            topicInput.setText("האם האנושות ראויה להמשיך להתקיים?");
            rulesInput.setText("Q רשאי להתווכח אך אינו יכול לשנות את המציאות. כל דמות מקבלת תור.");
            cast.add(new CastMember("איל קיציס","מנחה","מאופק, שנון ומחזיק את האולפן"));
            cast.add(new CastMember("Q","אורח/ת","פרובוקטיבי, כוחו מוגבל"));
            cast.add(new CastMember("גנדלף","אורח/ת","חוכמה והומור יבש"));
            cast.add(new CastMember("מורין ג'ונסון לונג","אורח/ת","עצמאית ושנונה"));
        }
        renderCast();
        note.setText("טיוטה מקומית. לחיצה על 'שמור הרכב' שומרת אותה במכשיר.");
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout vertical(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return l;
    }
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(dp(4),dp(6),dp(4),dp(6));return t;
    }
    private Button action(String s,Runnable run){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);
        b.setTextColor(WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));
        b.setMinHeight(dp(44));b.setOnClickListener(v->run.run());return b;
    }
    private EditText edit(String hint,String value){
        EditText e=new EditText(this);e.setSingleLine(false);e.setMaxLines(4);
        e.setTextColor(WHITE);e.setHintTextColor(SOFT);e.setTextSize(16);
        e.setHint(hint);e.setText(value);e.setPadding(dp(12),dp(10),dp(12),dp(10));
        e.setBackground(bg(PANEL,12));return e;
    }
    private void append(LinearLayout l,View v,int marginBottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(marginBottom);l.addView(v,p);
    }
    private Spinner spinner(String[] options,int selected){
        Spinner s=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);
        s.setSelection(Math.max(0,Math.min(selected,options.length-1)));return s;
    }
    private LinearLayout pair(){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return row;
    }
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root=vertical();root.setBackgroundColor(BG);
        root.setPadding(dp(16),dp(18),dp(16),dp(28));scroll.addView(root);
        append(root,label("🎙️  MorinMusic Studio",26,WHITE,true),0);
        append(root,label("בונים תוכנית, בוחרים קאסט ושומרים הרכבים משלך.",14,SOFT,false),8);

        LinearLayout nav=pair();
        nav.addView(action("🎵 נגן המוזיקה",()->startActivity(new Intent(this,MainActivity.class))),
                new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(action("📂 הרכבים שמורים",this::chooseSaved),
                new LinearLayout.LayoutParams(0,-2,1));
        append(root,nav,14);
        LinearLayout integration=pair();
        integration.addView(action("▶️ YouTube שלי",()->startActivity(new Intent(this,YouTubeLibraryActivity.class))),new LinearLayout.LayoutParams(0,-2,1));
        integration.addView(action("🤖 הפעל תוכנית AI",this::startLiveShow),new LinearLayout.LayoutParams(0,-2,1));
        append(root,integration,12);

        append(root,label("שם התוכנית",15,ACCENT,true),0);
        titleInput=edit("לדוגמה: הערב שבו Q איבד את הכוחות","");append(root,titleInput,12);

        append(root,label("סוג התוכנית",15,ACCENT,true),0);
        formatInput=spinner(FORMATS,0);append(root,formatInput,12);

        append(root,label("על מה מדברים היום?",15,ACCENT,true),0);
        topicInput=edit("נושא הדיון או התוכנית","");append(root,topicInput,12);

        append(root,label("כללי העולם והיחסים בין הדמויות",15,ACCENT,true),0);
        rulesInput=edit("לדוגמה: אין כוחות קסם באולפן, אפשר להתווכח בחופשיות","");append(root,rulesInput,12);

        append(root,label("איזון מוזיקה ודיבורים",15,ACCENT,true),0);
        musicValue=label("",13,SOFT,false);append(root,musicValue,0);
        musicInput=new SeekBar(this);musicInput.setMax(100);musicInput.setProgress(30);
        musicInput.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int p,boolean user){
                musicValue.setText("מוזיקה "+p+"%   |   דיבורים "+(100-p)+"%");
            }
            public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}
        });
        append(root,musicInput,16);

        castTitle=label("הקאסט",20,WHITE,true);append(root,castTitle,0);
        LinearLayout addRow=pair();
        addRow.addView(action("⭐ בחר דמות מוכרת",this::choosePreset),
                new LinearLayout.LayoutParams(0,-2,1));
        addRow.addView(action("＋ צור דמות",()->editMember(-1,null)),
                new LinearLayout.LayoutParams(0,-2,1));
        append(root,addRow,10);
        castRows=vertical();append(root,castRows,12);

        LinearLayout saveRow=pair();
        saveRow.addView(action("💾 שמור הרכב",this::saveShow),new LinearLayout.LayoutParams(0,-2,1));
        saveRow.addView(action("🆕 תוכנית חדשה",this::newShow),new LinearLayout.LayoutParams(0,-2,1));
        append(root,saveRow,10);

        LinearLayout tools=pair();
        tools.addView(action("📋 הצג שלד תוכנית",this::showOutline),
                new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(action("🗑 מחק הרכב שמור",this::confirmDelete),
                new LinearLayout.LayoutParams(0,-2,1));
        append(root,tools,10);
        note=label("",13,SOFT,false);append(root,note,10);
        append(root,label("גרסת בדיקה משולבת: ההרכבים נשמרים מקומית; AI חי דורש מפתח OpenRouter אישי, ו־YouTube דורש הגדרת OAuth. קולות הדמויות הם קול סינתטי כללי, ולא חיקוי ידוענים.",12,SOFT,false),8);
        setContentView(scroll);
    }
    private void renderCast(){
        castTitle.setText("הקאסט ("+cast.size()+" משתתפים)");
        castRows.removeAllViews();
        if(cast.isEmpty()){append(castRows,label("אין משתתפים. בחר דמות או צור אחת משלך.",14,SOFT,false),8);return;}
        for(int i=0;i<cast.size();i++){
            final int index=i;CastMember c=cast.get(i);
            LinearLayout card=vertical();card.setBackground(bg(PANEL,14));card.setPadding(dp(10),dp(8),dp(10),dp(8));
            append(card,label(c.name+"  ·  "+c.role,17,WHITE,true),2);
            if(!c.traits.trim().isEmpty())append(card,label(c.traits,13,SOFT,false),2);
            LinearLayout bar=pair();
            bar.addView(action("ערוך",()->editMember(index,c)),new LinearLayout.LayoutParams(0,-2,1));
            bar.addView(action("↑",()->move(index,-1)),new LinearLayout.LayoutParams(0,-2,0.6f));
            bar.addView(action("↓",()->move(index,1)),new LinearLayout.LayoutParams(0,-2,0.6f));
            bar.addView(action("הסר",()->{cast.remove(index);renderCast();}),new LinearLayout.LayoutParams(0,-2,1));
            card.addView(bar);append(castRows,card,8);
        }
    }
    private void move(int i,int d){
        int j=i+d;if(j<0||j>=cast.size())return;
        CastMember c=cast.remove(i);cast.add(j,c);renderCast();
    }
    private void choosePreset(){
        String[] names=new String[PRESETS.length];
        for(int i=0;i<names.length;i++)names[i]=PRESETS[i][0];
        new AlertDialog.Builder(this).setTitle("הוסף השראה לקאסט")
                .setItems(names,(dlg,i)->{
                    String[] p=PRESETS[i];editMember(-1,new CastMember(p[0],p[1],p[2]));
                }).setNegativeButton("ביטול",null).show();
    }
    private void editMember(int index,CastMember original){
        LinearLayout form=vertical();form.setPadding(dp(14),dp(6),dp(14),dp(8));
        EditText n=edit("שם הדמות",original==null?"":original.name);append(form,n,10);
        int chosen=1;
        if(original!=null)for(int i=0;i<ROLES.length;i++)if(ROLES[i].equals(original.role))chosen=i;
        Spinner role=spinner(ROLES,chosen);append(form,role,10);
        EditText traits=edit("אישיות, התנהגות ותפקיד מיוחד",original==null?"":original.traits);append(form,traits,4);
        new AlertDialog.Builder(this).setTitle(index>=0?"ערוך משתתף":"הוסף משתתף")
                .setView(form)
                .setPositiveButton("אישור",(d,w)->{
                    String name=n.getText().toString().trim();
                    if(name.isEmpty()){Toast.makeText(this,"לא נשמר: יש להזין שם.",Toast.LENGTH_LONG).show();return;}
                    CastMember c=new CastMember(name,role.getSelectedItem().toString(),traits.getText().toString().trim());
                    if(index>=0)cast.set(index,c);else cast.add(c);
                    renderCast();
                }).setNegativeButton("ביטול",null).show();
    }
    private JSONObject draft() throws Exception{
        JSONObject x=new JSONObject();
        x.put("id",currentId==null?"":currentId);
        x.put("title",titleInput.getText().toString().trim());
        x.put("topic",topicInput.getText().toString().trim());
        x.put("rules",rulesInput.getText().toString().trim());
        x.put("format",formatInput.getSelectedItemPosition());
        x.put("music",musicInput.getProgress());
        JSONArray a=new JSONArray();for(CastMember c:cast)a.put(c.toJson());x.put("cast",a);
        return x;
    }
    private void restoreDraft(JSONObject x){
        initializing=true;
        String id=x.optString("id");currentId=id.isEmpty()?null:id;
        titleInput.setText(x.optString("title"));topicInput.setText(x.optString("topic"));
        rulesInput.setText(x.optString("rules"));
        formatInput.setSelection(Math.max(0,Math.min(FORMATS.length-1,x.optInt("format",0))));
        musicInput.setProgress(Math.max(0,Math.min(100,x.optInt("music",30))));
        cast.clear();JSONArray a=x.optJSONArray("cast");
        if(a!=null)for(int i=0;i<a.length();i++)if(a.optJSONObject(i)!=null)cast.add(CastMember.fromJson(a.optJSONObject(i)));
        initializing=false;renderCast();
    }
    private JSONArray allShows(){
        String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY,"[]");
        try{return new JSONArray(s);}catch(Exception e){return new JSONArray();}
    }
    private void saveShow(){
        String title=titleInput.getText().toString().trim();
        if(title.isEmpty()){toast("יש לתת שם לתוכנית.");return;}
        if(cast.isEmpty()){toast("יש להוסיף לפחות משתתף אחד.");return;}
        try {
            JSONObject x=draft();if(currentId==null)currentId=UUID.randomUUID().toString();x.put("id",currentId);
            JSONArray src=allShows(),dest=new JSONArray();boolean found=false;
            for(int i=0;i<src.length();i++){
                JSONObject s=src.optJSONObject(i);if(s==null)continue;
                if(currentId.equals(s.optString("id"))){dest.put(x);found=true;}
                else dest.put(s);
            }
            if(!found)dest.put(x);
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY,dest.toString()).apply();
            note.setText("✓ נשמר במכשיר: "+title);
            toast("ההרכב נשמר.");
        }catch(Exception e){toast("שגיאה בשמירה: נסה שוב.");}
    }
    private void chooseSaved(){
        JSONArray src=allShows();
        if(src.length()==0){toast("עדיין לא שמרת הרכבים.");return;}
        String[] names=new String[src.length()];
        for(int i=0;i<names.length;i++){
            JSONObject x=src.optJSONObject(i);names[i]=x==null?"תוכנית":x.optString("title","תוכנית");
        }
        new AlertDialog.Builder(this).setTitle("בחר הרכב שמור")
                .setItems(names,(d,i)->{JSONObject x=src.optJSONObject(i);
                    if(x!=null){restoreDraft(x);note.setText("נטען: "+x.optString("title"));}
                }).setNegativeButton("ביטול",null).show();
    }
    private void newShow(){
        currentId=null;titleInput.setText("");topicInput.setText("");rulesInput.setText("");
        formatInput.setSelection(0);musicInput.setProgress(30);cast.clear();renderCast();
        note.setText("תוכנית חדשה. תן לה שם והתחל להרכיב קאסט.");
    }
    private void confirmDelete(){
        if(currentId==null){toast("התוכנית הזו עדיין אינה שמורה.");return;}
        new AlertDialog.Builder(this).setTitle("למחוק את ההרכב השמור?")
                .setMessage("המחיקה לא תפגע בהרכבים אחרים.")
                .setPositiveButton("מחק",(d,w)->{
                    JSONArray src=allShows(),dest=new JSONArray();
                    for(int i=0;i<src.length();i++){
                        JSONObject x=src.optJSONObject(i);
                        if(x!=null&&!currentId.equals(x.optString("id")))dest.put(x);
                    }
                    getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY,dest.toString()).apply();
                    newShow();toast("ההרכב נמחק.");
                }).setNegativeButton("ביטול",null).show();
    }
    private void startLiveShow(){
        if(cast.isEmpty()){toast("יש לבחור לפחות משתתף אחד.");return;}
        try{startActivity(new Intent(this,LiveShowActivity.class).putExtra("show",draft().toString()));}
        catch(Exception e){toast("לא ניתן לפתוח תוכנית.");}
    }
    private void showOutline(){
        if(cast.isEmpty()){toast("כדי להכין שלד, הוסף משתתפים.");return;}
        String name=titleInput.getText().toString().trim();
        if(name.isEmpty())name="תוכנית ללא שם";
        String topic=topicInput.getText().toString().trim();
        if(topic.isEmpty())topic="נושא לבחירת המנחה";
        String host=cast.get(0).name;
        for(CastMember c:cast)if(c.role.equals("מנחה")){host=c.name;break;}
        StringBuilder s=new StringBuilder();
        s.append("פורמט: ").append(FORMATS[formatInput.getSelectedItemPosition()])
          .append("\nנושא: ").append(topic)
          .append("\n\n1. פתיחה: ").append(host).append(" מציג/ה את המשתתפים ואת הנושא.")
          .append("\n2. סבב ראשון: כל משתתף מגיב מזווית אחרת.")
          .append("\n3. דיון: המנחה מפתח שאלות ומאזן את זמן הדיבור.")
          .append("\n4. המאזין רשאי להתערב ולהציע כיוון חדש.")
          .append("\n5. סיכום וקטע סיום.");
        if(musicInput.getProgress()>0)s.append("\n\nמוזיקה: ").append(musicInput.getProgress()).append("% מהתוכנית.");
        if(!rulesInput.getText().toString().trim().isEmpty())
            s.append("\n\nכללי העולם: ").append(rulesInput.getText().toString().trim());
        s.append("\n\nזוהי תוכנית שלד קבועה, לא שיחה שנוצרה באמצעות AI. מנוע ה-AI והקולות עדיין בפיתוח.");
        new AlertDialog.Builder(this).setTitle(name).setMessage(s.toString())
                .setPositiveButton("סגור",null).show();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);try{out.putString("draft",draft().toString());}catch(Exception ignored){}
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
