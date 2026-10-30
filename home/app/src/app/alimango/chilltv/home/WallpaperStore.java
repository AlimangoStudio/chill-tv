package app.alimango.chilltv.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * The home screen's one wallpaper: the bundled one (assets) or a video/picture the family copied from a USB stick
 * (Settings -> Wallpaper). The chosen file is copied into the app's own storage, so the stick can come out.
 */
final class WallpaperStore {
    static final long MAX_BYTES = 400L * 1024 * 1024;   // a 1080p loop is ~15-60 MB; refuse whole films
    private static final String[] VIDEO = {".mp4", ".m4v", ".webm", ".mkv"};
    private static final String[] PICTURE = {".jpg", ".jpeg", ".png", ".webp"};
    private static final int MAX_FILES = 80, MAX_DEPTH = 3;

    private final Context ctx;
    private final SharedPreferences prefs;

    WallpaperStore(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("wallpaper", Context.MODE_PRIVATE);
    }

    static String kind(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : VIDEO) if (n.endsWith(e)) return "video";
        for (String e : PICTURE) if (n.endsWith(e)) return "picture";
        return null;
    }

    /** USB sticks and SD cards: everything under /storage except the TV's own storage. */
    static File[] removableRoots() {
        File[] all = new File("/storage").listFiles();
        if (all == null) return new File[0];
        java.util.List<File> out = new java.util.ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (n.equals("emulated") || n.equals("self") || !f.isDirectory() || !f.canRead()) continue;
            out.add(f);
        }
        return out.toArray(new File[0]);
    }

    /** Videos and pictures on the sticks (a few folders deep), newest first. */
    JSONArray list() throws Exception {
        java.util.List<File> found = new java.util.ArrayList<>();
        for (File root : removableRoots()) walk(root, 0, found);
        java.util.Collections.sort(found, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        JSONArray arr = new JSONArray();
        for (File f : found) {
            arr.put(new JSONObject().put("name", f.getName()).put("path", f.getAbsolutePath())
                    .put("kind", kind(f.getName())).put("mb", Math.max(1, f.length() / (1024 * 1024))));
        }
        return arr;
    }

    private static void walk(File dir, int depth, java.util.List<File> found) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (found.size() >= MAX_FILES) return;
            if (f.getName().startsWith(".")) continue;   // macOS ._ files, hidden folders
            if (f.isDirectory()) { if (depth < MAX_DEPTH) walk(f, depth + 1, found); }
            else if (kind(f.getName()) != null && f.length() > 0) found.add(f);
        }
    }

    /** Only files on a removable volume may be imported (never the TV's own storage or another app's files). */
    static boolean onUsb(File f) throws Exception {
        String p = f.getCanonicalPath();
        for (File root : removableRoots()) if (p.startsWith(root.getCanonicalPath() + File.separator)) return true;
        return false;
    }

    /** Copy the chosen file in; returns null when done, or a short reason for the screen. */
    String importFrom(String path) {
        try {
            File src = new File(path);
            String kind = kind(src.getName());
            if (kind == null) return "That file type can't be a wallpaper.";
            if (!src.isFile() || !onUsb(src)) return "Pick a file on the USB stick.";
            if (src.length() > MAX_BYTES) return "That file is too big (more than 400 MB).";
            String ext = src.getName().substring(src.getName().lastIndexOf('.')).toLowerCase(Locale.ROOT);
            File dest = new File(ctx.getFilesDir(), "wallpaper-" + System.currentTimeMillis() + ext);
            File tmp = new File(dest.getPath() + ".part");
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            if (tmp.length() != src.length() || !tmp.renameTo(dest)) { tmp.delete(); return "Copying stopped. Try again."; }
            String old = prefs.getString("file", null);
            prefs.edit().putString("file", dest.getName()).putString("kind", kind).putString("name", src.getName()).apply();
            if (old != null && !old.equals(dest.getName())) new File(ctx.getFilesDir(), old).delete();
            return null;
        } catch (Exception e) {
            return "Copying stopped. Try again.";
        }
    }

    /** {"url","kind","name"} for the imported wallpaper, or null for the bundled one. */
    JSONObject current() throws Exception {
        String file = prefs.getString("file", null);
        if (file == null) return null;
        File f = new File(ctx.getFilesDir(), file);
        if (!f.isFile()) return null;
        return new JSONObject().put("url", "file://" + f.getAbsolutePath()).put("kind", prefs.getString("kind", "picture"))
                .put("name", prefs.getString("name", ""));
    }

    void reset() {
        String file = prefs.getString("file", null);
        if (file != null) new File(ctx.getFilesDir(), file).delete();
        prefs.edit().clear().apply();
    }
}
