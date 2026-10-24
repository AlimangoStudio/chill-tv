package app.alimango.chilltv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/** What the remote server needs from the home screen: open apps, go home, press the D-pad, show the app list. */
final class RemoteHooks {
    private RemoteHooks() {}

    /** The running home screen and whether it is in front (set by MainActivity). */
    static volatile MainActivity current;
    static volatile boolean resumed;

    /** Remote app names -> package lists (first installed wins, TV build first). */
    static String packagesFor(String app) {
        if (app == null) return null;
        switch (app) {
            case "nf": return "com.netflix.ninja";
            case "hbo": return "com.wbd.stream";
            case "dis": return "com.disneyplus.ph,com.disney.disneyplus";
            case "prime": return "com.amazon.amazonvideo.livingroom";
            case "play": return "com.android.vending";
            case "yt": return "com.google.android.youtube.tv,com.google.android.youtube";
            default: return null;
        }
    }

    /** Opens the first installed package of a comma-separated list. */
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

    /** Brings the home screen to the front, closing whatever app was open. */
    static void goHome(Context c) {
        c.startActivity(new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** Remote D-pad / OK: works while the home screen is in front (Android cannot press keys in other apps). */
    static boolean remoteKey(String code) {
        String dir = String.valueOf(code);
        if (!dir.equals("up") && !dir.equals("down") && !dir.equals("left") && !dir.equals("right") && !dir.equals("ok")) return false;
        MainActivity a = current;
        if (a == null || !resumed) return false;
        a.remoteKey(dir);
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
}
