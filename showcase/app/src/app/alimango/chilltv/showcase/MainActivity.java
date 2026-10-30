package app.alimango.chilltv.showcase;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Chill TV Showcase home screen: the designed UI (assets/index.html) in a WebView, launching apps through a JS bridge. */
public class MainActivity extends Activity {
    private WebView web;
    private boolean pageReady;
    private String pendingMode;

    /** The running home screen (the remote control talks to it), and whether background music is wanted. */
    private static volatile MainActivity current;
    static volatile boolean musicOn = true;

    /** Remote app names -> package lists (the same apps as the dock in index.html; first installed wins). */
    static String packagesFor(String app) {
        if (app == null) return null;
        switch (app) {
            case "nf": return "com.netflix.ninja";
            case "hbo": return "com.wbd.stream";
            case "dis": return "com.disneyplus.ph,com.disney.disneyplus";
            case "prime": return "com.amazon.amazonvideo.livingroom";
            case "play": return "com.android.vending";
            default: return null;
        }
    }

    /** Opens the first installed package of a comma-separated list (TV launcher entry first). */
    static String launchFrom(Context c, String pkgs) {
        PackageManager pm = c.getPackageManager();
        for (String p : pkgs.split(",")) {
            String pkg = p.trim();
            if (pkg.isEmpty()) continue;
            try { pm.getPackageInfo(pkg, 0); } catch (PackageManager.NameNotFoundException e) { continue; }
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) continue;
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return pkg;
            } catch (Exception e) {
                Log.w("ChillTV", "launch failed: " + e);
            }
        }
        return "";
    }

    /** Brings the home screen to the front (closing whatever app was open) in "home", "showcase" or "reel" mode. */
    static void showMode(Context c, String mode) {
        Intent i = new Intent(c, MainActivity.class).putExtra("mode", mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        c.startActivity(i);
    }

    private volatile boolean resumed;

    /** Remote direction pad / OK: works while a Chill TV screen is in front (Android 12 cannot press keys in other apps). */
    static boolean remoteKey(String code) {
        final int kc;
        switch (String.valueOf(code)) {
            case "up": kc = android.view.KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": kc = android.view.KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": kc = android.view.KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": kc = android.view.KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "ok": kc = android.view.KeyEvent.KEYCODE_DPAD_CENTER; break;
            default: return false;
        }
        final MainActivity a = current;
        if (a == null || !a.resumed) return false;
        a.runOnUiThread(new Runnable() { public void run() {
            a.web.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, kc));
            a.web.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, kc));
        } });
        return true;
    }

    /** The box's own app list (TV Settings > Apps), as the remote's Apps button. */
    static void openAppList(Context c) {
        Intent apps = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
        java.util.List<android.content.pm.ResolveInfo> rs = c.getPackageManager().queryIntentActivities(apps, 0);
        if (!rs.isEmpty()) apps.setClassName(rs.get(0).activityInfo.packageName, rs.get(0).activityInfo.name);
        apps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(apps); } catch (Exception e) { Log.w("ChillTV", "app list failed: " + e); }
    }

    static void setMusic(boolean on) {
        musicOn = on;
        MainActivity a = current;
        if (a != null) a.js("window.setMusic && window.setMusic(" + on + ")");
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // A waiting-room display: keep the screen on while the home screen is showing.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0F2B2D);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // The page scales its own 1920x1080 stage to the window; no wide viewport (it doubled the size at 2x density).
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "TV");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("ChillTV", m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) { pageReady = true; }
        });
        web.loadUrl("file:///android_asset/index.html");
        current = this;
        RemoteServer.ensureStarted(this);
        TvFinder.ensureStarted(this);   // the PC/Mac remote finds the TV again after its address changes
        setContentView(web);
        web.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        pendingMode = intent.getStringExtra("mode");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (pageReady && pendingMode != null) {
            js("window.remoteMode && window.remoteMode('" + pendingMode.replaceAll("[^a-z]", "") + "')");
            pendingMode = null;
            return;
        }
        if (pageReady) js("window.onHomeResume && window.onHomeResume()");
    }

    @Override
    protected void onPause() {
        resumed = false;
        // Another app (Netflix, Settings…) is opening: stop the background music.
        if (pageReady) js("window.onHomePause && window.onHomePause()");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home never closes: Back is handled by the page (leave Showcase, move focus home).
        js("window.onBack && window.onBack()");
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript(code, null); } });
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w("ChillTV", "launch failed: " + e);
            return false;
        }
    }

    private static String png(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    class Bridge {
        /** {"code":"ABCD2345","address":"192.168.1.20","port":8765} for the Remote icon (see PairingCode). */
        @JavascriptInterface
        public String remotePairing() {
            return "{\"code\":\"" + PairingCode.get(MainActivity.this) + "\",\"address\":\"" + PairingCode.address()
                    + "\",\"port\":" + RemoteServer.PORT + "}";
        }

        @JavascriptInterface
        public boolean has(String pkg) { return installed(pkg); }

        /** The screen in CSS pixels (width,height). This box's WebView reports innerWidth at twice the visible area. */
        @JavascriptInterface
        public String screen() {
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            return (web.getWidth() / m.density) + "," + (web.getHeight() / m.density);
        }

        /** Opens the first installed package of a comma-separated list (TV launcher entry first). */
        @JavascriptInterface
        public String launch(String pkgs) { return launchFrom(MainActivity.this, pkgs); }

        /** Not installed: open its Play Store page so staff can install it. */
        @JavascriptInterface
        public void store(String pkg) {
            if (!start(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)))) {
                start(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(pkg))));
            }
        }

        /** The app's TV banner ("B|data-url") or, without one, its icon ("I|data-url"), for the first installed package. */
        @JavascriptInterface
        public String banner(String pkgs) {
            PackageManager pm = getPackageManager();
            for (String p : pkgs.split(",")) {
                String pkg = p.trim();
                if (pkg.isEmpty() || !installed(pkg)) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    Drawable d = pm.getApplicationBanner(ai);
                    Intent tv = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (d == null && tv != null) d = pm.getActivityBanner(tv);
                    if (d != null) return "B|" + png(d, 640, 360);
                    return "I|" + png(pm.getApplicationIcon(ai), 192, 192);
                } catch (Exception e) {
                    Log.w("ChillTV", "banner failed for " + pkg + ": " + e);
                }
            }
            return "";
        }

        @JavascriptInterface
        public void openSettings() { start(new Intent(Settings.ACTION_SETTINGS)); }

        /** The box's own app grid (Google TV / stock launcher "all apps"), falling back to the app settings list. */
        @JavascriptInterface
        public void allApps() {
            if (!start(new Intent("com.google.android.tvlauncher.action.APPS_VIEW"))
                    && !start(new Intent("android.intent.action.ALL_APPS"))) {
                // Two activities in TV Settings answer this intent, which shows a chooser; open the first one directly.
                Intent apps = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
                java.util.List<android.content.pm.ResolveInfo> rs = getPackageManager().queryIntentActivities(apps, 0);
                if (!rs.isEmpty()) apps.setClassName(rs.get(0).activityInfo.packageName, rs.get(0).activityInfo.name);
                start(apps);
            }
        }
    }
}
