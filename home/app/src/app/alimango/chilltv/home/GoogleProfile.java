package app.alimango.chilltv.home;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The G in the top bar: sign in to Google, then show that person's Google profile picture.
 *
 * The account comes from the TV's own Google sign-in: the family picks it once in Android's account chooser (which
 * also offers "Add account" when the TV has none), then allows the basic-profile permission once. The picture is
 * read from Google's userinfo endpoint and kept on the box as a data URL; it is refreshed once a day. The token and
 * the e-mail address are never logged or shown.
 *
 * Google only hands out the token to a registered app: create an Android OAuth client in your own Google Cloud project
 * with this package name and the SHA-1 of the key you sign with (see home/README.md, "Google picture"). Without it the
 * token request fails (UnregisteredOnApiConsole) and the G simply stays a G.
 */
final class GoogleProfile {
    static final String TYPE = "com.google";
    private static final String SCOPE = "oauth2:https://www.googleapis.com/auth/userinfo.profile";
    private static final long REFRESH_MS = 24L * 60 * 60 * 1000;

    interface Done { void run(String error); }

    private GoogleProfile() {}

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("google", Activity.MODE_PRIVATE); }

    /** Android's chooser: lists the TV's Google accounts and offers "Add account" when there is none. */
    static Intent chooser() {
        return AccountManager.newChooseAccountIntent(null, null, new String[] { TYPE }, null, null, null, null);
    }

    /** The account chosen earlier, if it is still on the TV and still visible to us. */
    static Account chosen(Activity a) {
        String name = prefs(a).getString("account", "");
        if (name.isEmpty()) return null;
        for (Account acc : AccountManager.get(a).getAccountsByType(TYPE)) if (acc.name.equals(name)) return acc;
        return null;
    }

    static void forget(Activity a) { prefs(a).edit().clear().apply(); }

    static boolean due(Activity a) {
        return System.currentTimeMillis() - prefs(a).getLong("avatarAt", 0) > REFRESH_MS;
    }

    /** Fetch the picture for the chosen account. The first time, Google shows its permission screen on the TV. */
    private static volatile boolean busy;   // one fetch at a time (the permission screen pauses and resumes the home)

    static void fetch(final Activity a, final Account acc, final Done done0) {
        if (busy) return;
        busy = true;
        final Done done = new Done() { public void run(String error) { busy = false; done0.run(error); } };
        final AccountManager am = AccountManager.get(a);
        am.getAuthToken(acc, SCOPE, null, a, new android.accounts.AccountManagerCallback<Bundle>() {
            public void run(final AccountManagerFuture<Bundle> f) {
                new Thread(new Runnable() { public void run() { finish(a, am, acc, f, done, true); } }).start();
            }
        }, null);
    }

    private static void finish(Activity a, AccountManager am, Account acc, AccountManagerFuture<Bundle> f,
                               Done done, boolean retry) {
        String token;
        try {
            token = f.getResult().getString(AccountManager.KEY_AUTHTOKEN);
        } catch (Exception e) {
            // e.g. the person said no, or Google refused this app (the message names the reason, never the token)
            Log.w("ChillTV", "google token: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            done.run("token");
            return;
        }
        if (token == null) { done.run("token"); return; }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code == 401 && retry) {           // a cached token that expired: drop it and ask once more
                am.invalidateAuthToken(TYPE, token);
                try {
                    Bundle b = am.getAuthToken(acc, SCOPE, null, a, null, null).getResult();
                    String t2 = b.getString(AccountManager.KEY_AUTHTOKEN);
                    if (t2 != null) { token = t2; c = (HttpURLConnection) new URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection();
                        c.setRequestProperty("Authorization", "Bearer " + token); code = c.getResponseCode(); }
                } catch (Exception ignored) {
                }
            }
            if (code != 200) { Log.w("ChillTV", "google userinfo HTTP " + code); done.run("userinfo"); return; }
            JSONObject info = new JSONObject(read(c.getInputStream()));
            String pic = info.optString("picture", "");
            String first = info.optString("given_name", "");
            String avatar = "";
            if (!pic.isEmpty()) {
                // Google picture URLs end in =s96-c: ask for a sharper 256 px square
                String big = pic.replaceAll("=s\\d+(-c)?$", "=s256-c");
                HttpURLConnection p = (HttpURLConnection) new URL(big).openConnection();
                p.setConnectTimeout(15000);
                p.setReadTimeout(15000);
                if (p.getResponseCode() == 200) {
                    byte[] bytes = readBytes(p.getInputStream());
                    String mime = p.getContentType() != null && p.getContentType().contains("png") ? "image/png" : "image/jpeg";
                    avatar = "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                }
            }
            prefs(a).edit().putString("avatar", avatar).putString("first", first)
                .putLong("avatarAt", System.currentTimeMillis()).apply();
            done.run(null);
        } catch (Exception e) {
            Log.w("ChillTV", "google profile: " + e.getClass().getSimpleName());
            done.run("network");
        }
    }

    private static String read(InputStream in) throws Exception { return new String(readBytes(in), "UTF-8"); }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
