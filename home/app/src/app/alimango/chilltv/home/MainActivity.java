package app.alimango.chilltv.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.TextToSpeech;
import android.net.Uri;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Home screen: renders the designed UI from assets in a WebView and launches apps through a JS bridge. */
public class MainActivity extends Activity {
    private static final int REQ_VOICE = 41;
    private WebView web;
    private boolean pageReady;
    // Voice phrases waiting for onResume: the home reset there would otherwise close what the command opened.
    private JSONArray pendingVoice;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private boolean listenOnResume;
    private long lastRms;
    private final Map<String, String> imageCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        web.setBackgroundColor(0xFF1F4F52);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverPendingVoice();
                if (listenOnResume) {
                    listenOnResume = false;
                    startVoice();
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("ChillTV", m.messageLevel() + " " + m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "TV");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        RemoteHooks.current = this;
        RemoteServer.ensureStarted(this);
        web.requestFocus(View.FOCUS_DOWN);
        takeVoiceExtra(getIntent());
        AlarmScheduler.schedule(this);
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                tts.setLanguage(new Locale("en", "PH"));
                tts.setPitch(1.08f);      // the assistant's voice: a touch brighter than the stock one
                tts.setSpeechRate(1.02f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) { js("window.onOrbSpeak && window.onOrbSpeak(true)"); }
                    public void onDone(String id) { js("window.onOrbSpeak && window.onOrbSpeak(false)"); }
                    public void onError(String id) { js("window.onOrbSpeak && window.onOrbSpeak(false)"); }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    /** Test hook: `am start -n app.alimango.chilltv.home/.MainActivity --es voice "open netflix"` runs a command as if spoken. */
    private void takeVoiceExtra(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("listen", false)) {
            // The remote's mic button (AssistActivity): show the orb and listen once the home screen is up.
            listenOnResume = true;
            intent.removeExtra("listen");
            return;
        }
        ArrayList<String> list = intent.getStringArrayListExtra("voiceList");
        if (list != null) {
            // Words heard through the remote's mic button (AssistActivity).
            pendingVoice = new JSONArray(list);
            intent.removeExtra("voiceList");
            return;
        }
        if (intent.getStringExtra("voice") == null) return;
        pendingVoice = new JSONArray().put(intent.getStringExtra("voice"));
        intent.removeExtra("voice");
    }

    private void deliverPendingVoice() {
        if (!pageReady || pendingVoice == null) return;
        JSONArray heard = pendingVoice;
        pendingVoice = null;
        js("window.onVoiceResult && window.onVoiceResult(" + heard + ")");
    }

    /** The assistant listens in-app (no Google screen); the page draws the orb from these callbacks. */
    private void startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 9);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startVoiceIntent();
            return;
        }
        if (tts != null) tts.stop();
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { js("window.onOrb && window.onOrb('listening')"); }
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float rms) {
                    long now = System.currentTimeMillis();
                    if (now - lastRms < 70) return;
                    lastRms = now;
                    js("window.onOrbLevel && window.onOrbLevel(" + rms + ")");
                }
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() { js("window.onOrb && window.onOrb('thinking')"); }
                public void onError(int code) { js("window.onOrbError && window.onOrbError(" + code + ")"); }
                public void onResults(Bundle b) {
                    ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    js("window.onVoiceResult && window.onVoiceResult(" + new JSONArray(said == null ? new ArrayList<String>() : said) + ")");
                }
                public void onPartialResults(Bundle b) {
                    ArrayList<String> part = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (part != null && !part.isEmpty()) js("window.onOrbText && window.onOrbText(" + JSONObject.quote(part.get(0)) + ")");
                }
                public void onEvent(int type, Bundle b) {}
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-PH")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        js("window.onOrb && window.onOrb('starting')");
        recognizer.cancel();
        recognizer.startListening(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 9 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startVoice();
    }

    private void stopVoice() {
        if (recognizer != null) recognizer.cancel();
    }

    /** Fallback when no in-app recognizer exists: Google's own speech prompt. */
    private void startVoiceIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say: open Netflix");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        // Two apps answer this request on the Mi Box (Google and the speech service), which shows a chooser.
        // Go straight to Google's TV voice prompt when it is installed.
        Intent google = new Intent(i).setPackage("com.google.android.katniss");
        if (google.resolveActivity(getPackageManager()) != null) i = google;
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            js("window.onVoiceUnavailable && window.onVoiceUnavailable()");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE) return;
        ArrayList<String> said = (resultCode == RESULT_OK && data != null)
            ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        JSONArray heard = new JSONArray();
        if (said != null) for (String s : said) heard.put(s);
        pendingVoice = heard; // delivered in onResume, which runs next
    }

    @Override
    public boolean onSearchRequested() {
        // The remote's mic / search button on the home screen listens for a launcher command.
        startVoice();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        RemoteHooks.current = this;
        RemoteHooks.resumed = true;
        web.onResume();
        web.resumeTimers();
        js("window.onHome && window.onHome()");
        deliverPendingVoice();
        if (listenOnResume && pageReady) {
            listenOnResume = false;
            startVoice();
        }
    }

    @Override
    protected void onPause() {
        RemoteHooks.resumed = false;
        stopVoice();
        web.onPause();
        web.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        js("window.onHome && window.onHome()");
        takeVoiceExtra(intent);
    }

    @Override
    public void onBackPressed() {
        // A home screen never closes; let the page decide what Back means.
        js("window.onBack && window.onBack()");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // The remote's mic button is wired to Google Assistant by the system and never reaches an app,
        // so the menu / apps button is the voice button on this home screen.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU || e.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS) {
            if (e.getAction() == KeyEvent.ACTION_UP) startVoice();
            return true;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_ASSIST:
                if (e.getAction() == KeyEvent.ACTION_UP) startVoice();
                return true;
        }
        // Route the remote's D-pad straight to the page; the WebView's own key handling
        // drops some remote Up/Down presses.
        String dir = null;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: dir = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = "right"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: dir = "ok"; break;
        }
        if (dir != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && (!"ok".equals(dir) || e.getRepeatCount() == 0)) {
                js("window.onKey && window.onKey('" + dir + "')");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** A D-pad press from the remote control, handled exactly like the TV remote's. */
    void remoteKey(String dir) {
        js("window.onKey && window.onKey('" + dir + "')");
    }

    /** Runs page script on the UI thread (speech callbacks arrive on binder threads and would be dropped). */
    private void js(final String code) {
        if (web == null) return;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) web.evaluateJavascript(code, null);
        else web.post(new Runnable() { public void run() { if (web != null) web.evaluateJavascript(code, null); } });
    }

    private String toDataUrl(Drawable d, int maxW, int maxH) {
        if (d == null) return "";
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : maxW;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : maxH;
        float scale = Math.min(1f, Math.min((float) maxW / w, (float) maxH / h));
        w = Math.max(1, Math.round(w * scale));
        h = Math.max(1, Math.round(h * scale));
        Bitmap bmp;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            bmp = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), w, h, true);
        } else {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    class Bridge {
        @JavascriptInterface
        public boolean has(String pkg) {
            return launchIntent(pkg) != null;
        }

        @JavascriptInterface
        public boolean launch(final String pkg) {
            final Intent i = launchIntent(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            runOnUiThread(new Runnable() { public void run() { startActivity(i); } });
            return true;
        }

        @JavascriptInterface
        public void say(String text) {
            if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant");
        }

        /** Stop recording now and recognize what was heard so far. */
        @JavascriptInterface
        public void finishListening() {
            runOnUiThread(new Runnable() { public void run() { if (recognizer != null) recognizer.stopListening(); } });
        }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(new Runnable() { public void run() { stopVoice(); if (tts != null) tts.stop(); } });
        }

        /** Opens Google Play's search for an app that is not installed. */
        @JavascriptInterface
        public void searchStore(String query) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query)));
            i.setPackage("com.android.vending");
            open(i);
        }

        @JavascriptInterface
        public String getAlarm() {
            return AlarmScheduler.json(MainActivity.this);
        }

        /** {"enabled":bool,"hour":0-23,"minute":0-59,"days":bitmask, 0 = once}; returns the saved alarm with "next". */
        @JavascriptInterface
        public String setAlarm(String json) {
            return AlarmScheduler.save(MainActivity.this, json);
        }

        /** Anything that is not an assistant command goes to Google search, as the mic button did before. */
        @JavascriptInterface
        public void webSearch(String query) {
            Intent i = new Intent("android.search.action.GLOBAL_SEARCH")
                .putExtra(android.app.SearchManager.QUERY, query)
                .setPackage("com.google.android.katniss");
            open(i);
        }

        /** "Ring now" in the alarm panel: rings without touching the saved alarm. */
        @JavascriptInterface
        public void testAlarm() {
            open(new Intent(MainActivity.this, AlarmActivity.class).putExtra("test", true));
        }

        /** City search: bring up the TV keyboard for the focused text box. */
        @JavascriptInterface
        public void showKeyboard() {
            runOnUiThread(new Runnable() {
                public void run() {
                    web.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }

        @JavascriptInterface
        public void listen() {
            runOnUiThread(new Runnable() { public void run() { startVoice(); } });
        }

        @JavascriptInterface
        public void openSettings() {
            final Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(new Runnable() { public void run() { startActivity(i); } });
        }

        /** Launchable apps (TV first, then phone-style launcher entries), excluding this one. */
        @JavascriptInterface
        public String apps() {
            PackageManager pm = getPackageManager();
            Map<String, String> found = new LinkedHashMap<>();
            String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : cats) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
                for (ResolveInfo r : list) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
                    found.put(pkg, String.valueOf(r.loadLabel(pm)));
                }
            }
            JSONArray arr = new JSONArray();
            try {
                for (Map.Entry<String, String> e : found.entrySet()) {
                    arr.put(new JSONObject().put("pkg", e.getKey()).put("label", e.getValue()));
                }
            } catch (Exception ignored) {
            }
            return arr.toString();
        }

        /** The app's TV banner as a data URL, or its icon when it has no banner. */
        @JavascriptInterface
        public String banner(String pkg) {
            String key = "b:" + pkg;
            if (imageCache.containsKey(key)) return imageCache.get(key);
            String url = "";
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                Drawable d = pm.getApplicationBanner(ai);
                if (d == null) {
                    Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (i != null) d = pm.getActivityBanner(i);
                }
                if (d != null) url = toDataUrl(d, 480, 270);
            } catch (Exception ignored) {
            }
            imageCache.put(key, url);
            return url;
        }

        @JavascriptInterface
        public String icon(String pkg) {
            String key = "i:" + pkg;
            if (imageCache.containsKey(key)) return imageCache.get(key);
            String url = "";
            try {
                url = toDataUrl(getPackageManager().getApplicationIcon(pkg), 160, 160);
            } catch (Exception ignored) {
            }
            imageCache.put(key, url);
            return url;
        }

        /** {"connected":bool,"level":0-3} for the Wi-Fi chip. The network name is deliberately never read. */
        @JavascriptInterface
        public String wifi() {
            JSONObject o = new JSONObject();
            try {
                o.put("connected", false);
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo net = cm.getActiveNetworkInfo();
                if (net == null || !net.isConnected()) return o.toString();
                o.put("connected", true);
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo info = wm.getConnectionInfo();
                o.put("level", info == null ? 3 : WifiManager.calculateSignalLevel(info.getRssi(), 4));
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        /** {"devices":n,"storage":["label",…]} for the USB chip; empty when nothing is plugged in. */
        @JavascriptInterface
        public String usb() {
            JSONObject o = new JSONObject();
            try {
                UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
                o.put("devices", um == null ? 0 : um.getDeviceList().size());
                JSONArray drives = new JSONArray();
                if (Build.VERSION.SDK_INT >= 24) {
                    StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
                    for (StorageVolume v : sm.getStorageVolumes()) {
                        if (v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState())) {
                            drives.put(v.getDescription(MainActivity.this));
                        }
                    }
                }
                o.put("storage", drives);
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void openWifi() {
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }

        @JavascriptInterface
        public void openStorage() {
            open(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        }

        private void open(final Intent i) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }
            });
        }
    }
}
