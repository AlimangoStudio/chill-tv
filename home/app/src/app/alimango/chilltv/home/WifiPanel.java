package app.alimango.chilltv.home;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Wi-Fi drawer: join a network from the home screen, password typed with the TV keyboard.
 *
 * build.ps1 targets API 34 (Google Play Protect blocks installs of apps built for old Android versions). From
 * Android 10 an app can no longer add or switch networks itself (addNetwork / enableNetwork fail), so on those TVs
 * the drawer hands the typed network to Android's own "Save this network?" dialog (Android 11+, ACTION_WIFI_ADD_NETWORKS)
 * or opens Wi-Fi settings; on Android 9 and older it still joins directly. Android also lists nearby networks only
 * when the app holds the location permission and Location is on (a scan can reveal where the TV is).
 * With Location off (the privacy-friendly default: on many boxes Location only stays on together with Google's
 * Location Accuracy) the drawer joins networks by typed name + password, and switching between networks the TV
 * already saved is left to Android's Wi-Fi settings.
 * Passwords go straight from the page to Android's saved networks; they are never logged or kept here.
 */
@SuppressWarnings("deprecation")
final class WifiPanel {
    private WifiPanel() {}

    static WifiManager wm(Context c) { return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE); }

    static boolean hasPermission(Context c) {
        return Build.VERSION.SDK_INT < 23
            || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean locationOn(Context c) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    /** "TheName" -> TheName; Android's "<unknown ssid>" (no permission) -> "". */
    static String clean(String ssid) {
        if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID)) return "";
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) return ssid.substring(1, ssid.length() - 1);
        return ssid;
    }

    /** {"enabled":bool,"connected":bool,"ssid":"…"|"","level":0-3} for the icon in the top bar. */
    static JSONObject status(Context c) {
        JSONObject o = new JSONObject();
        try {
            WifiManager w = wm(c);
            o.put("enabled", w != null && w.isWifiEnabled());
            o.put("connected", false);
            o.put("ssid", "");
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo net = cm.getActiveNetworkInfo();
            if (net == null || !net.isConnected()) return o;
            o.put("connected", true);
            o.put("wired", net.getType() == ConnectivityManager.TYPE_ETHERNET);
            WifiInfo info = w == null ? null : w.getConnectionInfo();
            o.put("level", info == null ? 3 : WifiManager.calculateSignalLevel(info.getRssi(), 4));
            if (info != null && net.getType() == ConnectivityManager.TYPE_WIFI) {
                o.put("ssid", clean(info.getSSID()));      // "" while Location is off
            }
            // With Location off Android hides the network's name and id, but not the TV's own address on it: a changed
            // address after a join means the TV moved to the new network (see the Wi-Fi drawer's wWatch).
            android.net.DhcpInfo dh = w == null ? null : w.getDhcpInfo();
            if (dh != null && dh.ipAddress != 0) o.put("addr", Integer.toHexString(dh.ipAddress) + "/" + Integer.toHexString(dh.gateway));
        } catch (Exception ignored) {
        }
        return o;
    }

    static String security(String caps) {
        if (caps == null) return "open";
        if (caps.contains("EAP")) return "eap";
        if (caps.contains("PSK")) return "psk";          // WPA/WPA2, and WPA2/WPA3 transition networks
        if (caps.contains("SAE")) return "sae";          // WPA3 only
        if (caps.contains("WEP")) return "wep";
        if (caps.contains("OWE")) return "owe";
        return "open";
    }

    /** Saved networks by name (Android hands them to apps that target API 28 and hold the location permission). */
    private static Map<String, Integer> saved(WifiManager w) {
        Map<String, Integer> m = new HashMap<>();
        try {
            List<WifiConfiguration> list = w.getConfiguredNetworks();
            if (list != null) for (WifiConfiguration cfg : list) m.put(clean(cfg.SSID), cfg.networkId);
        } catch (Exception ignored) {
        }
        return m;
    }

    /** Nearby networks, strongest first, one row per name. */
    static JSONObject networks(Context c) {
        JSONObject o = status(c);
        try {
            WifiManager w = wm(c);
            boolean perm = hasPermission(c), loc = locationOn(c);
            // Location off: no list, join by typing the name.
            o.put("needLocation", !loc);
            o.put("needPermission", loc && !perm);
            JSONArray list = new JSONArray();
            if (w != null && perm && loc && w.isWifiEnabled()) {
                String current = o.optString("ssid", "");
                Map<String, Integer> known = saved(w);
                Map<String, ScanResult> best = new HashMap<>();
                List<ScanResult> results = w.getScanResults();
                if (results != null) for (ScanResult r : results) {
                    if (r.SSID == null || r.SSID.isEmpty()) continue;         // hidden networks: use Wi-Fi settings
                    ScanResult b = best.get(r.SSID);
                    if (b == null || r.level > b.level) best.put(r.SSID, r);
                }
                java.util.ArrayList<ScanResult> sorted = new java.util.ArrayList<>(best.values());
                java.util.Collections.sort(sorted, new java.util.Comparator<ScanResult>() {
                    public int compare(ScanResult a, ScanResult b) { return b.level - a.level; }
                });
                for (ScanResult r : sorted) {
                    JSONObject n = new JSONObject();
                    n.put("ssid", r.SSID);
                    n.put("level", WifiManager.calculateSignalLevel(r.level, 4));
                    n.put("sec", security(r.capabilities));
                    n.put("saved", known.containsKey(r.SSID));
                    n.put("current", r.SSID.equals(current));
                    list.put(n);
                }
            }
            o.put("list", list);
        } catch (Exception ignored) {
        }
        return o;
    }

    static void scan(Context c) {
        try { wm(c).startScan(); } catch (Exception ignored) { }
    }

    static boolean setEnabled(Context c, boolean on) {
        try { return wm(c).setWifiEnabled(on); } catch (Exception e) { return false; }
    }

    /**
     * Join a network. Empty password on a saved network = switch to it with the saved password.
     * {"ok":true,"id":n,"added":bool} or {"ok":false,"why":"saved"|"add"|"enable"|"type"|"system"|"settings"}.
     */
    static JSONObject connect(Context c, String ssid, String sec, String password) {
        JSONObject o = new JSONObject();
        try {
            WifiManager w = wm(c);
            o.put("ok", false);
            if ("eap".equals(sec)) { o.put("why", "type"); return o; }
            boolean open = "open".equals(sec) || "owe".equals(sec);
            if (Build.VERSION.SDK_INT >= 29) {
                if (!open && (password == null || password.isEmpty())) { o.put("why", "saved"); return o; }
                o.put("why", askSystem(c, ssid, sec, password) ? "system" : "settings");
                return o;
            }
            Integer known = saved(w).get(ssid);
            if (known != null && (password == null || password.isEmpty())) {
                // Android lets an app switch only to networks it saved itself; otherwise ask for the password.
                if (w.enableNetwork(known, true)) { w.reconnect(); o.put("ok", true); o.put("id", known); o.put("added", false); }
                else o.put("why", "saved");
                return o;
            }
            if (!open && (password == null || password.isEmpty())) { o.put("why", "saved"); return o; }
            WifiConfiguration cfg = new WifiConfiguration();
            cfg.SSID = "\"" + ssid + "\"";
            if (open) {
                if ("owe".equals(sec) && Build.VERSION.SDK_INT >= 30) cfg.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE);
                else cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else if ("wep".equals(sec)) {
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                cfg.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                cfg.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                boolean hex = password.matches("[0-9A-Fa-f]{10}|[0-9A-Fa-f]{26}");
                cfg.wepKeys[0] = hex ? password : "\"" + password + "\"";
                cfg.wepTxKeyIndex = 0;
            } else {
                if ("sae".equals(sec) && Build.VERSION.SDK_INT >= 30) cfg.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                else cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                cfg.preSharedKey = "\"" + password + "\"";
            }
            int id = w.addNetwork(cfg);
            if (id == -1) { o.put("why", "add"); return o; }
            if (!w.enableNetwork(id, true)) { w.removeNetwork(id); o.put("why", "enable"); return o; }
            w.reconnect();
            o.put("ok", true); o.put("id", id); o.put("added", true);
        } catch (Exception e) {
            try { o.put("why", "add"); } catch (Exception ignored) { }
        }
        return o;
    }

    /** Android 11+: Android's own "Save this network?" dialog, then it connects. False = open Wi-Fi settings instead. */
    private static boolean askSystem(Context c, String ssid, String sec, String password) {
        Intent settings = new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder().setSsid(ssid);
                if ("sae".equals(sec)) b.setWpa3Passphrase(password);
                else if ("owe".equals(sec)) b.setIsEnhancedOpen(true);
                else if (!"open".equals(sec) && !"wep".equals(sec)) b.setWpa2Passphrase(password);
                if (!"wep".equals(sec)) {   // WEP cannot be suggested; Wi-Fi settings handles it
                    ArrayList<WifiNetworkSuggestion> list = new ArrayList<>();
                    list.add(b.build());
                    Intent add = new Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                            .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, list)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (add.resolveActivity(c.getPackageManager()) != null) { c.startActivity(add); return true; }
                }
            } catch (Exception ignored) { }
        }
        try { c.startActivity(settings); } catch (Exception ignored) { }
        return false;
    }

    /** Remove a network this app added (after a wrong password), so the TV falls back to its previous one. */
    static void forget(Context c, int id) {
        try { WifiManager w = wm(c); w.removeNetwork(id); w.reconnect(); } catch (Exception ignored) { }
    }
}
