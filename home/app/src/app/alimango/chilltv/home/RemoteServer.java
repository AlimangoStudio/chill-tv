package app.alimango.chilltv.home;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Remote control for the TV (the Mac dock app, or the page at "/"). Plain HTTP on port 8765, reached over the
 * local network or a private VPN such as Tailscale. Every request must carry the key from res/values/remote.xml (?k=...). The thread blocks
 * in accept() between requests, so it costs no CPU while nobody is using the remote.
 */
final class RemoteServer extends Thread {
    static final int PORT = 8765;
    private static RemoteServer running;

    private final Context app;
    private final byte[] key;

    private RemoteServer(Context app) {
        super("ChillTV-remote");
        setDaemon(true);
        this.app = app.getApplicationContext();
        this.key = app.getString(R.string.remote_key).getBytes(StandardCharsets.UTF_8);
    }

    static synchronized void ensureStarted(Context c) {
        if (running != null && running.isAlive()) return;
        running = new RemoteServer(c);
        running.start();
    }

    @Override
    public void run() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            while (!isInterrupted()) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    handle(s);
                } catch (Exception e) {
                    Log.w("ChillTV", "remote request failed: " + e);
                }
            }
        } catch (Exception e) {
            Log.w("ChillTV", "remote server stopped: " + e);
        }
    }

    private void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null) return;
        String[] parts = line.split(" ");
        if (parts.length < 2) { reply(s, 400, "text/plain", "bad request"); return; }
        String method = parts[0], target = parts[1];
        while ((line = in.readLine()) != null && !line.isEmpty()) { /* headers not needed */ }

        if (method.equals("OPTIONS")) { reply(s, 204, "text/plain", ""); return; }
        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
        Map<String, String> p = params(query);
        if (!keyOk(p.get("k"))) { reply(s, 403, "text/plain", "forbidden"); return; }

        switch (path) {
            case "/":
                reply(s, 200, "text/html; charset=utf-8", asset("remote.html"));
                return;
            case "/api/status":
                reply(s, 200, "application/json", status());
                return;
            case "/api/open": {
                String pkgs = RemoteHooks.packagesFor(p.get("app"));
                if (pkgs == null) { reply(s, 404, "application/json", "{\"ok\":false,\"error\":\"unknown app\"}"); return; }
                String opened = RemoteHooks.launchFrom(app, pkgs);
                reply(s, 200, "application/json", "{\"ok\":" + !opened.isEmpty() + ",\"opened\":\"" + opened + "\"}");
                return;
            }
            case "/api/home":
                RemoteHooks.goHome(app);
                reply(s, 200, "application/json", "{\"ok\":true}");
                return;
            case "/api/showcase":
            case "/api/reel":
                reply(s, 200, "application/json", "{\"ok\":false,\"note\":\"Reel and Idle are on Chill TV Showcase\"}");
                return;
            case "/api/vol": {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                String d = String.valueOf(p.get("d"));
                int dir = d.equals("up") ? AudioManager.ADJUST_RAISE : d.equals("down") ? AudioManager.ADJUST_LOWER
                        : d.equals("mute") ? AudioManager.ADJUST_TOGGLE_MUTE : AudioManager.ADJUST_SAME;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                reply(s, 200, "application/json", status());
                return;
            }
            case "/api/apps":
                RemoteHooks.openAppList(app);
                reply(s, 200, "application/json", "{\"ok\":true}");
                return;
            case "/api/media": {
                // Media keys go to whatever is playing (Netflix, Prime Video...): allowed for any app on Android 12.
                String c = String.valueOf(p.get("c"));
                int kc = c.equals("playpause") ? android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        : c.equals("rew") ? android.view.KeyEvent.KEYCODE_MEDIA_REWIND
                        : c.equals("ff") ? android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        : c.equals("next") ? android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        : c.equals("prev") ? android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS : 0;
                if (kc == 0) { reply(s, 404, "application/json", "{\"ok\":false}"); return; }
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, kc));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, kc));
                reply(s, 200, "application/json", "{\"ok\":true}");
                return;
            }
            case "/api/key": {
                String code = String.valueOf(p.get("code"));
                boolean ok;
                String note = "";
                if (code.equals("back")) {
                    ok = VolumeHudService.back();
                    if (!ok) note = "Back needs the Chill TV volume service switched on (Settings > Accessibility)";
                } else if (code.equals("home")) {
                    RemoteHooks.goHome(app); ok = true;
                } else {
                    ok = RemoteHooks.remoteKey(code);
                    if (!ok) note = "the direction pad works on the Chill TV screens only";
                }
                reply(s, 200, "application/json", "{\"ok\":" + ok + ",\"note\":\"" + note + "\"}");
                return;
            }
            case "/api/music":
                reply(s, 200, "application/json", "{\"ok\":false,\"note\":\"Background music is on Chill TV Showcase\"}");
                return;
            default:
                reply(s, 404, "text/plain", "not found");
        }
    }

    private String status() {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        return "{\"ok\":true,\"volume\":" + am.getStreamVolume(AudioManager.STREAM_MUSIC)
                + ",\"max\":" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + ",\"muted\":" + am.isStreamMute(AudioManager.STREAM_MUSIC)
                + ",\"music\":false,\"kind\":\"home\"}";
    }

    private boolean keyOk(String given) {
        // An unset key (the CHANGE-ME placeholder) locks the remote instead of letting anyone in.
        if (given == null || new String(key, StandardCharsets.UTF_8).equals("CHANGE-ME")) return false;
        return MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> params(String query) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
            String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
            m.put(k, v);
        }
        return m;
    }

    private String asset(String name) throws Exception {
        try (InputStream is = app.getAssets().open(name)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static void reply(Socket s, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 204 ? "No Content" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }
}
