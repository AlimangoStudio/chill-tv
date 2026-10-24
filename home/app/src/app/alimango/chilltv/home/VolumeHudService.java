package app.alimango.chilltv.home;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Volume HUD: a glass pill at the top middle of the screen, shown
 * whenever the media volume or mute changes. The box's TV audio routing handles the remote's volume keys before any
 * app sees them, so this watches the volume itself instead. The stock Xiaomi panel still shows on the right: it is
 * SystemUI's enable_volume_ui resource, which only root or a platform-signed overlay can turn off.
 * An accessibility service only because it keeps running and may draw over every app; it reads no screen content
 * and asks for no events.
 */
public class VolumeHudService extends AccessibilityService {
    private static final long SHOW_MS = 2200;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private AudioManager audio;
    private HudView hud;
    private boolean attached;
    private ValueAnimator anim;

    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(EXTRA_STREAM, -1) == AudioManager.STREAM_MUSIC) {
                show();
            }
        }
    };
    private boolean registered;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        hud = new HudView(this);
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED);
        filter.addAction(MUTE_CHANGED);
        registerReceiver(volumeReceiver, filter);
        registered = true;
    }

    @Override
    public void onDestroy() {
        if (registered) {
            unregisterReceiver(volumeReceiver);
            registered = false;
        }
        handler.removeCallbacksAndMessages(null);
        if (attached) {
            try { wm.removeView(hud); } catch (Exception ignored) {}
            attached = false;
        }
        super.onDestroy();
    }

    private void show() {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) || cur == 0;
        hud.set(Math.round(cur * 100f / max), muted);
        if (!attached) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int pillW = Math.round(w * 0.30f), pillH = Math.round(pillW * 0.19f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(pillW, pillH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(getResources().getDisplayMetrics().heightPixels * 0.075f);
            hud.setAlpha(0f);
            hud.setTranslationY(-pillH * 0.35f);
            try {
                wm.addView(hud, lp);
                attached = true;
            } catch (Exception ignored) {
                return;
            }
        }
        animateTo(1f);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() { public void run() { animateTo(0f); } }, SHOW_MS);
    }

    /** Fade and lift in, fade and drop out; removed from the screen when hidden so it costs nothing. */
    private void animateTo(final float target) {
        if (anim != null) anim.cancel();
        final float from = hud.getAlpha();
        final float lift = -hud.getHeight() * 0.35f; // slides down from the top edge
        anim = ValueAnimator.ofFloat(0f, 1f).setDuration(target > from ? 220 : 320);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                float alpha = from + (target - from) * t;
                hud.setAlpha(alpha);
                hud.setTranslationY((1f - alpha) * lift);
                if (target == 0f && t >= 1f && attached) {
                    try { wm.removeView(hud); } catch (Exception ignored) {}
                    attached = false;
                }
            }
        });
        anim.start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    /** The pill: speaker in a circle, "Volume"/"Sound off", the level, and a thin bar, on dark frosted glass. */
    static final class HudView extends View {
        private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG), title = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private float shown = -1f;
        private int pct;
        private boolean muted;
        private ValueAnimator barAnim;

        HudView(Context c) {
            super(c);
            glass.setColor(Color.argb(150, 20, 40, 42));          // translucent, so the picture shows through
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(Color.argb(70, 255, 255, 255));
            chip.setColor(Color.argb(36, 255, 255, 255));
            icon.setStyle(Paint.Style.STROKE);
            icon.setColor(Color.WHITE);
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);
            iconFill.setColor(Color.WHITE);
            title.setColor(Color.WHITE);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            big.setColor(Color.WHITE);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            big.setTextAlign(Paint.Align.RIGHT);
            track.setColor(Color.argb(56, 255, 255, 255));
            fill.setColor(Color.WHITE);
        }

        void set(int percent, boolean isMuted) {
            pct = percent;
            muted = isMuted;
            final float target = muted ? 0 : percent;
            if (shown < 0) { shown = target; invalidate(); return; }
            if (barAnim != null) barAnim.cancel();
            barAnim = ValueAnimator.ofFloat(shown, target).setDuration(200);
            barAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) { shown = (float) a.getAnimatedValue(); invalidate(); }
            });
            barAnim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = h * 0.2f, rad = h / 2f;
            r.set(1, 1, w - 1, h - 1);
            c.drawRoundRect(r, rad, rad, glass);
            edge.setStrokeWidth(Math.max(1f, h * 0.012f));
            c.drawRoundRect(r, rad, rad, edge);

            // speaker chip
            float cs = h - pad * 2, cx = pad + cs / 2f, cy = h / 2f;
            c.drawCircle(cx, cy, cs / 2f, chip);
            float u = cs / 24f * 0.62f, ox = cx - 12 * u, oy = cy - 12 * u; // the design's 24-unit icon
            path.reset();
            path.moveTo(ox + 4 * u, oy + 9.5f * u); path.lineTo(ox + 7.5f * u, oy + 9.5f * u); path.lineTo(ox + 12 * u, oy + 5.5f * u);
            path.lineTo(ox + 12 * u, oy + 18.5f * u); path.lineTo(ox + 7.5f * u, oy + 14.5f * u); path.lineTo(ox + 4 * u, oy + 14.5f * u); path.close();
            c.drawPath(path, iconFill);
            icon.setStrokeWidth(1.8f * u);
            if (muted) {
                c.drawLine(ox + 16 * u, oy + 9.5f * u, ox + 21 * u, oy + 14.5f * u, icon);
                c.drawLine(ox + 21 * u, oy + 9.5f * u, ox + 16 * u, oy + 14.5f * u, icon);
            } else {
                if (pct > 0) { r.set(ox + 12 * u, oy + 8.5f * u, ox + 19 * u, oy + 15.5f * u); c.drawArc(r, -55, 110, false, icon); }
                if (pct >= 50) { r.set(ox + 11 * u, oy + 5 * u, ox + 25 * u, oy + 19 * u); c.drawArc(r, -50, 100, false, icon); }
            }

            // title, level, bar
            float left = pad * 2 + cs, right = w - pad * 1.5f;
            title.setTextSize(h * 0.2f);
            big.setTextSize(h * 0.3f);
            float textY = cy - h * 0.04f;
            c.drawText(muted ? "Sound off" : "Volume", left, textY, title);
            c.drawText(muted ? "Muted" : String.valueOf(pct), right, textY + h * 0.02f, big);
            float barH = h * 0.1f, barY = cy + h * 0.12f;
            r.set(left, barY, right, barY + barH);
            c.drawRoundRect(r, barH / 2, barH / 2, track);
            float fw = (right - left) * Math.max(0, Math.min(100, shown)) / 100f;
            if (fw > 0) {
                r.set(left, barY, left + Math.max(fw, barH), barY + barH);
                c.drawRoundRect(r, barH / 2, barH / 2, fill);
            }
        }
    }
}
