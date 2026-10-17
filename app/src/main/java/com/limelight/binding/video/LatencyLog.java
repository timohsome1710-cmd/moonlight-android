package com.limelight.binding.video;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Moonlight CM: keeps Moonlight's own per-second stream numbers (the same ones the performance
 * overlay shows) so they can be drawn as a graph when the stream ends. Only records while
 * "Show latency graph after streaming" is on; one small sample per second, so it costs nothing.
 */
public final class LatencyLog {

    /** One second of the stream. Times in ms; -1 when Moonlight had no value for that second. */
    public static final class Sample {
        public final float t;             // seconds since the stream started
        public final float networkMs;     // round-trip time to the host
        public final float networkVarMs;  // its variance
        public final float hostMs;        // host processing (capture + encode) per frame, average
        public final float decodeMs;      // decoding on this phone per frame, average
        public final float dropPct;       // frames lost on the network
        public final float fps;           // frames received per second

        Sample(float t, float networkMs, float networkVarMs, float hostMs, float decodeMs, float dropPct, float fps) {
            this.t = t;
            this.networkMs = networkMs;
            this.networkVarMs = networkVarMs;
            this.hostMs = hostMs;
            this.decodeMs = decodeMs;
            this.dropPct = dropPct;
            this.fps = fps;
        }
    }

    private static final int MAX_SAMPLES = 6 * 60 * 60; // six hours
    private static final List<Sample> samples = new ArrayList<>();
    private static long startUptime;
    private static String video = "";
    private static boolean shown = true;

    private LatencyLog() {}

    /** A stream is starting: forget the last one. */
    public static synchronized void begin(String videoDescription) {
        samples.clear();
        startUptime = SystemClock.uptimeMillis();
        video = videoDescription == null ? "" : videoDescription;
        shown = false;
    }

    static synchronized void add(VideoStats window, long rttInfo) {
        if (startUptime == 0 || samples.size() >= MAX_SAMPLES) return;
        float t = (SystemClock.uptimeMillis() - startUptime) / 1000f;
        long now = SystemClock.uptimeMillis();
        float seconds = window.measurementStartTimestamp > 0 ? (now - window.measurementStartTimestamp) / 1000f : 1f;
        float network = (int) (rttInfo >> 32);
        float variance = (int) rttInfo;
        float host = window.framesWithHostProcessingLatency > 0
                ? (float) window.totalHostProcessingLatency / 10f / window.framesWithHostProcessingLatency : -1;
        float decode = window.totalFramesReceived > 0 ? (float) window.decoderTimeMs / window.totalFramesReceived : -1;
        float drops = window.totalFrames > 0 ? window.framesLost * 100f / window.totalFrames : 0;
        float fps = seconds > 0 ? window.totalFramesReceived / seconds : 0;
        samples.add(new Sample(t, network > 0 ? network : -1, variance, host, decode, drops, fps));
    }

    /** The last stream's samples, if there are enough to draw and they haven't been shown yet. */
    public static synchronized List<Sample> takeUnshown() {
        if (shown || samples.size() < 3) return null;
        shown = true;
        return new ArrayList<>(samples);
    }

    public static synchronized boolean hasUnshown() {
        return !shown && samples.size() >= 3;
    }

    public static synchronized List<Sample> last() {
        return new ArrayList<>(samples);
    }

    public static synchronized String video() {
        return video;
    }
}
