package com.limelight.binding.video;

import java.util.Locale;

/**
 * Moonlight CM: the stream's latest per-second numbers (the ones the performance overlay shows), for the
 * CouchMode panel's header. Written once a second by the decoder, read by the panel; just a few fields.
 */
public final class LiveStats {
    private static volatile float rttMs = -1, fps = -1, dropPct = -1, decodeMs = -1;
    private static volatile long updatedAt;

    private LiveStats() {}

    static void update(float rtt, float framesPerSecond, float drops, float decode) {
        rttMs = rtt;
        fps = framesPerSecond;
        dropPct = drops;
        decodeMs = decode;
        updatedAt = System.currentTimeMillis();
    }

    /** {"rtt":..,"fps":..,"drop":..,"decode":..}, each -1 when not known (or older than 3 seconds). */
    public static String json() {
        boolean fresh = System.currentTimeMillis() - updatedAt < 3000;
        return String.format(Locale.US, "{\"rtt\":%.1f,\"fps\":%.1f,\"drop\":%.2f,\"decode\":%.2f}",
                fresh ? rttMs : -1f, fresh ? fps : -1f, fresh ? dropPct : -1f, fresh ? decodeMs : -1f);
    }
}
