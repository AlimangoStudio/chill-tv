package app.alimango.chilltv.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;

/** Full-screen ringing alarm: wakes the box (and the TV over HDMI-CEC), rings, and stops on any remote button. */
public class AlarmActivity extends Activity {
    private static final long AUTO_STOP_MS = 15 * 60 * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private PowerManager.WakeLock wake;
    private WebView web;
    private float volume = 0.15f;
    private boolean stopped;
    private boolean sawDown;
    private long createdAt;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        createdAt = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
            | PowerManager.ON_AFTER_RELEASE, "chilltv:alarm");
        wake.acquire(AUTO_STOP_MS + 60_000L);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E1420);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/clock.html#alarm");

        if (!getIntent().getBooleanExtra("test", false)) AlarmScheduler.rang(this);
        startSound();
        handler.postDelayed(new Runnable() { public void run() { stopAlarm(); } }, AUTO_STOP_MS);
    }

    /**
     * Sounds to try, in order. The box's "default alarm" can point at a media entry that no longer exists
     * (it does on this Mi Box), so each candidate is actually prepared before it is trusted.
     */
    private java.util.List<Uri> alarmSounds() {
        java.util.List<Uri> list = new java.util.ArrayList<>();
        Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (u != null) list.add(u);
        for (String f : new String[]{"/product/media/audio/alarms/Alarm_Classic.ogg", "/system/media/audio/alarms/Alarm_Classic.ogg",
                "/product/media/audio/alarms/Alarm_Beep_02.ogg"}) {
            if (new File(f).canRead()) list.add(Uri.fromFile(new File(f)));
        }
        Uri ring = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE);
        if (ring != null) list.add(ring);
        return list;
    }

    private void startSound() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        // An alarm nobody can hear is useless: lift a muted/low alarm stream to 80%.
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max * 0.8f) {
            am.setStreamVolume(AudioManager.STREAM_ALARM, Math.round(max * 0.8f), 0);
        }
        for (Uri u : alarmSounds()) {
            MediaPlayer p = new MediaPlayer();
            try {
                p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
                if ("file".equals(u.getScheme())) p.setDataSource(u.getPath()); else p.setDataSource(this, u);
                p.setLooping(true);
                p.setVolume(volume, volume);
                p.prepare();
                p.start();
                player = p;
                android.util.Log.i("ChillTV", "alarm sound: " + u);
                rampUp();
                return;
            } catch (Exception e) {
                android.util.Log.w("ChillTV", "alarm sound failed: " + u + " " + e);
                p.release();
            }
        }
    }

    /** Gentle start, full volume after about 30 seconds. */
    private void rampUp() {
        handler.postDelayed(new Runnable() {
            public void run() {
                if (stopped || player == null) return;
                volume = Math.min(1f, volume + 0.06f);
                player.setVolume(volume, volume);
                if (volume < 1f) handler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Any button on the remote stops the alarm, but only a real press made while it rings: waking the box
        // (and the TV over HDMI-CEC) sends key events of its own, which once stopped the alarm after one second.
        if (e.getKeyCode() == KeyEvent.KEYCODE_WAKEUP) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (System.currentTimeMillis() - createdAt > 1500) sawDown = true;
        } else if (e.getAction() == KeyEvent.ACTION_UP && sawDown) {
            stopAlarm();
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (sawDown) stopAlarm();
    }

    private void stopAlarm() {
        if (stopped) return;
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        if (wake != null && wake.isHeld()) wake.release();
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        if (wake != null && wake.isHeld()) wake.release();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
