package com.limelight.utils;

import com.limelight.LimeLog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Moonlight CM: asks CouchMode on the PC (if it runs there) where the stream's sound should play, so
 * "Play audio on PC" follows CouchMode's Laptop / Auto / Phone choice instead of a fixed setting:
 *   laptop → Play audio on PC on (and this phone can stay silent);
 *   phone  → off (Sunshine keeps the PC silent and sends the sound here);
 *   auto   → on at home, off away (connected through a Tailscale address).
 * CouchMode answers at http://&lt;pc&gt;:8642/couchmode/sound without a token; if it doesn't answer
 * quickly, nothing changes and the normal setting is used.
 */
public final class CouchModeSound {

    public static final int UNKNOWN = 0, LAPTOP = 1, PHONE = 2;
    private static final int COUCHMODE_PORT = 8642;

    private CouchModeSound() {}

    /**
     * Asks CouchMode, waiting at most about timeoutMs. Safe to call from the UI thread (the request runs
     * on its own). Also tells CouchMode where this phone connects from and its own "Play audio on PC"
     * (used when CouchMode has no choice), so CouchMode knows what this stream uses even when it's a
     * resumed one (Sunshine runs no command then).
     */
    public static int ask(final String host, final int timeoutMs, final boolean ownHostAudio) {
        if (host == null || host.isEmpty()) return UNKNOWN;
        final AtomicInteger answer = new AtomicInteger(UNKNOWN);
        Thread t = new Thread(new Runnable() {
            @Override public void run() { answer.set(fetch(host, timeoutMs, ownHostAudio)); }
        }, "CouchMode sound");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs + 100);
        } catch (InterruptedException ignored) { }
        return answer.get();
    }

    private static int fetch(String host, int timeoutMs, boolean ownHostAudio) {
        HttpURLConnection c = null;
        try {
            String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host; // IPv6
            URL u = new URL("http://" + h + ":" + COUCHMODE_PORT + "/couchmode/sound?client=moonlight-cm"
                    + "&tailscale=" + (isTailscale(host) ? "1" : "0") + "&fallback=" + (ownHostAudio ? "1" : "0"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return UNKNOWN;
            JSONObject j = new JSONObject(read(c.getInputStream()));
            if (!"CouchMode".equals(j.optString("app"))) return UNKNOWN;
            String stream = j.optString("stream", "");
            int result = UNKNOWN;
            if ("laptop".equals(stream)) result = LAPTOP;
            else if ("phone".equals(stream)) result = PHONE;
            else if ("auto".equals(stream)) result = isTailscale(host) ? PHONE : LAPTOP;
            LimeLog.info("CouchMode sound choice: " + (stream.isEmpty() ? "(none)" : stream) + " -> "
                    + (result == LAPTOP ? "PC" : result == PHONE ? "phone" : "unchanged"));
            return result;
        } catch (Exception e) {
            LimeLog.info("CouchMode didn't answer: " + e);
            return UNKNOWN;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** A Tailscale address (100.64.0.0/10, fd7a:115c:a1e0::/48) or a .ts.net name: away from home. */
    public static boolean isTailscale(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (h.endsWith(".ts.net") || h.startsWith("fd7a:115c:a1e0:")) return true;
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n, total = 0;
        while ((n = in.read(buf)) > 0 && total < 16384) { out.write(buf, 0, n); total += n; }
        in.close();
        return out.toString("UTF-8");
    }
}
