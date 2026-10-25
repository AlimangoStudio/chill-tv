package app.alimango.chilltv.showcase;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * The remote's pairing code. A key built into the APK (res/values/remote.xml, set by build.ps1) wins, so self-built
 * TVs keep their key. The downloadable APK has no key (CHANGE-ME), so every TV makes its own random code on first start
 * and shows it in Settings; a public APK must never carry one shared key.
 */
final class PairingCode {
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O or 1/I/L to misread on a TV
    private static final int LENGTH = 8;

    private PairingCode() {
    }

    static synchronized String get(Context c) {
        String built = c.getString(R.string.remote_key).trim();
        if (!built.isEmpty() && !built.equals("CHANGE-ME")) return built;
        SharedPreferences prefs = c.getApplicationContext().getSharedPreferences("chilltv_remote", Context.MODE_PRIVATE);
        String code = prefs.getString("pairing_code", null);
        if (code == null || code.length() != LENGTH) {
            SecureRandom random = new SecureRandom();
            StringBuilder b = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = b.toString();
            prefs.edit().putString("pairing_code", code).apply();
        }
        return code;
    }

    /** The TV's address on the local network, for the remote's "TV address" field ("" when offline). */
    static String address() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // No network yet: the Settings line says so.
        }
        return "";
    }
}
