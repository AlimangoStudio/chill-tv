package app.alimango.chilltv.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * The home alarm: one time of day, an on/off switch and the days it repeats (bit 0 = Sunday ... bit 6 = Saturday;
 * 0 = once). Booked as an exact "alarm clock" so it wakes the box from standby; the box waking turns the TV on
 * over HDMI-CEC.
 */
final class AlarmScheduler {
    private static final String PREFS = "alarm";
    private static final int EVERY_DAY = 0x7F;

    private AlarmScheduler() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String json(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", p.getBoolean("enabled", false));
            o.put("hour", p.getInt("hour", 6));
            o.put("minute", p.getInt("minute", 30));
            o.put("days", p.getInt("days", EVERY_DAY));
            o.put("next", p.getBoolean("enabled", false) ? nextTrigger(p) : 0);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    static String save(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            int hour = Math.max(0, Math.min(23, o.optInt("hour", 6)));
            int minute = Math.max(0, Math.min(59, o.optInt("minute", 30)));
            prefs(c).edit()
                .putBoolean("enabled", o.optBoolean("enabled", true))
                .putInt("hour", hour)
                .putInt("minute", minute)
                .putInt("days", o.optInt("days", EVERY_DAY) & EVERY_DAY)
                .apply();
        } catch (Exception ignored) {
        }
        schedule(c);
        return json(c);
    }

    /** After a one-time alarm rings it switches itself off; a repeating one books its next day. */
    static void rang(Context c) {
        if (prefs(c).getInt("days", EVERY_DAY) == 0) prefs(c).edit().putBoolean("enabled", false).apply();
        schedule(c);
    }

    static long nextTrigger(SharedPreferences p) {
        int hour = p.getInt("hour", 6), minute = p.getInt("minute", 30), days = p.getInt("days", EVERY_DAY);
        Calendar now = Calendar.getInstance();
        for (int i = 0; i <= 7; i++) {
            Calendar at = Calendar.getInstance();
            at.add(Calendar.DAY_OF_YEAR, i);
            at.set(Calendar.HOUR_OF_DAY, hour);
            at.set(Calendar.MINUTE, minute);
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            if (!at.after(now)) continue;
            int bit = 1 << (at.get(Calendar.DAY_OF_WEEK) - 1);
            if (days == 0 || (days & bit) != 0) return at.getTimeInMillis();
        }
        return 0;
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent ring = new Intent(c, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 7, ring, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("enabled", false)) return;
        long at = nextTrigger(p);
        if (at == 0) return;
        Intent show = new Intent(c, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(c, 8, show, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
        }
    }
}
