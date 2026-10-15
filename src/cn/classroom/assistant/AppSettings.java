package cn.classroom.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

final class AppSettings {
    static final String API="https://this-is-my.world/classroom-api";
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("settings",Context.MODE_PRIVATE);}
    static void migrate(Context c){
        // Public builds never read a bundled provider credential. Remove the old local preset.
        prefs(c).edit().remove("key").remove("iv").remove("model").remove("personalPresetImported").apply();
    }
    static String installId(Context c){
        String value=prefs(c).getString("installId","");
        if(value.isEmpty()){value=UUID.randomUUID().toString();prefs(c).edit().putString("installId",value).apply();}
        return value;
    }
    static String token(Context c){return prefs(c).getString("sessionToken","");}
    static long expires(Context c){return prefs(c).getLong("sessionExpires",0);}
    static void token(Context c,String value,long expires){prefs(c).edit().putString("sessionToken",value).putLong("sessionExpires",expires).apply();}
    static int seconds(Context c){return prefs(c).getInt("seconds",90);}
    static boolean phoneMic(Context c){return prefs(c).getBoolean("phoneMic",true);}
    static void save(Context c,int seconds,boolean phone){prefs(c).edit().putInt("seconds",seconds).putBoolean("phoneMic",phone).apply();}
    static boolean introductionSeen(Context c){return prefs(c).getBoolean("introductionSeen030",false);}
    static void markIntroductionSeen(Context c){prefs(c).edit().putBoolean("introductionSeen030",true).apply();}
}
