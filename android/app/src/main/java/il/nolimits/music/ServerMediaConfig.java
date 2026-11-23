package il.nolimits.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** No credentials in GitHub, the APK or logs; the server token is AES-GCM encrypted at rest. */
final class ServerMediaConfig {
    private static final String PREFERENCES="morin_media_server_v1";
    private static final String ALIAS="morin_server_upload_token";
    private static final String DEFAULT="https://avi-music-player.lolaweiss73.chatgpt.site/";
    private ServerMediaConfig(){}
    static String base(Context c){
        return c.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).getString("base",DEFAULT);
    }
    static String validate(String entered){
        String url=entered==null?"":entered.trim();
        if(!url.startsWith("https://"))throw new IllegalArgumentException("נדרשת כתובת HTTPS");
        android.net.Uri uri=android.net.Uri.parse(url);
        if(uri.getHost()==null||uri.getHost().isEmpty()||uri.getUserInfo()!=null)
            throw new IllegalArgumentException("כתובת שרת אינה תקינה");
        if(uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("כתובת בסיס ללא פרמטרים");
        return url.endsWith("/")?url:url+"/";
    }
    private static SecretKey key()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
        KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    static void save(Context c,String entered,String secret)throws Exception{
        String base=validate(entered);
        SharedPreferences.Editor editor=c.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit();
        editor.putString("base",base);
        if(secret!=null&&!secret.trim().isEmpty()){
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key());
            editor.putString("token",Base64.encodeToString(
                cipher.doFinal(secret.trim().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
            editor.putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));
        }
        editor.apply();
    }
    static String token(Context c)throws Exception{
        SharedPreferences p=c.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
        String data=p.getString("token",null),iv=p.getString("iv",null);
        if(data==null||iv==null)return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    static void clearToken(Context c){
        c.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit()
            .remove("token").remove("iv").apply();
    }
}
