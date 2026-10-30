package app.alimango.chilltv.home;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Lets the PC and Mac remotes find this TV after it gets a new address (another Wi-Fi, a router restart). The remote
 * broadcasts "CHILLTV-FIND <id>" on UDP port 8766; the TV answers "CHILLTV-HERE <id> home" only when <id> matches its
 * own pairing code, and the remote takes the TV's address from the answer. <id> is a one-way digest of the code
 * (findId), so the code itself never goes over the network and other people's TVs stay silent. The thread blocks in
 * receive() between packets, so it costs no CPU while nobody is looking for the TV.
 */
final class TvFinder extends Thread {
    static final int PORT = 8766;
    private static TvFinder running;

    private final Context app;
    private final String id;

    private TvFinder(Context app) {
        super("ChillTV-finder");
        setDaemon(true);
        this.app = app.getApplicationContext();
        this.id = findId(PairingCode.get(app));
    }

    static synchronized void ensureStarted(Context c) {
        if (running != null && running.isAlive()) return;
        running = new TvFinder(c);
        running.start();
    }

    /** First 16 hex digits of SHA-256("chilltv-find:" + code), the code written as on the TV screen (no dash, capitals). */
    static String findId(String code) {
        try {
            String norm = code.trim().replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("chilltv-find:" + norm).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void run() {
        // Some Wi-Fi chips drop broadcast packets unless an app holds this lock; on Ethernet it does nothing.
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { lock = wm.createMulticastLock("ChillTV-finder"); lock.setReferenceCounted(false); lock.acquire(); }
        } catch (Exception ignored) { }
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(PORT));
            byte[] buf = new byte[128];
            while (!isInterrupted()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).trim();
                if (id.isEmpty() || !msg.equals("CHILLTV-FIND " + id)) continue;   // not for this TV: stay silent
                byte[] out = ("CHILLTV-HERE " + id + " home").getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
            }
        } catch (Exception e) {
            Log.w("ChillTV", "finder stopped: " + e);
        } finally {
            if (lock != null) try { lock.release(); } catch (Exception ignored) { }
        }
    }
}
