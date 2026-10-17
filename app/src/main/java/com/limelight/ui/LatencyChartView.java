package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Moonlight CM: a small chart for the post-stream latency screen. Lines (several series sharing one
 * axis and one unit) or bars (one series). One y-axis only, recessive grid, 2dp lines, direct labels
 * at the line ends plus the legend above, and touch-and-drag to read the exact values of a second.
 * Missing values (negative) leave a gap rather than a made-up line.
 */
public class LatencyChartView extends View {

    public static final class Series {
        final String name;
        final int color;
        final float[] values;

        public Series(String name, int color, float[] values) {
            this.name = name;
            this.color = color;
            this.values = values;
        }
    }

    // Chart chrome (dark): surface, inks, hairlines.
    static final int SURFACE = 0xFF1A1A19, INK = 0xFFF0EFEC, INK2 = 0xFFC3C2B7, MUTED = 0xFF898781,
            GRID = 0xFF2C2C2A, AXIS = 0xFF383835, TIP = 0xFF262624, RING = 0x1AFFFFFF;

    private float[] rawT = new float[0];
    private final List<Series> rawSeries = new ArrayList<>();
    // What's drawn: the samples averaged into buckets of a few seconds when there are more of them
    // than the chart has room for (about one point per 2dp), so long streams stay readable.
    private float[] t = new float[0];
    private final List<Series> series = new ArrayList<>();
    private int bucket = 1, displayWidth = -1;
    private boolean bars;
    private String unit = "ms";
    private int touchIndex = -1;

    private final float dp;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public LatencyChartView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        grid.setStrokeWidth(Math.max(1f, dp * 0.75f));
        text.setTextSize(11.5f * dp);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        text.setFontFeatureSettings("tnum");
    }

    public void setData(float[] times, List<Series> data, boolean asBars, String unitLabel) {
        rawT = times;
        rawSeries.clear();
        rawSeries.addAll(data);
        displayWidth = -1;
        bars = asBars;
        unit = unitLabel;
        touchIndex = -1;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) ((bars ? 130 : 230) * dp);
        setMeasuredDimension(w, h);
    }

    // ------------------------------------------------------------------ scales

    private float left() { return 38 * dp; }
    private float right() { return getWidth() - (bars ? 10 : 84) * dp; }
    private float top() { return 10 * dp; }
    private float bottom() { return getHeight() - 22 * dp; }

    private float yMax() {
        float max = 0;
        for (Series s : series) for (float v : s.values) if (v > max) max = v;
        float floor = bars ? 1f : 5f;
        return niceStep(Math.max(max * 1.1f, floor) / 4f) * 4f;
    }

    /** 1, 2, 2.5 or 5 times a power of ten. */
    private static float niceStep(float raw) {
        double p = Math.pow(10, Math.floor(Math.log10(raw)));
        double[] m = {1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10};
        for (double k : m) if (k * p >= raw) return (float) (k * p);
        return (float) (10 * p);
    }

    private float xOf(int i) {
        float t0 = t.length > 0 ? t[0] : 0, t1 = t.length > 1 ? t[t.length - 1] : 1;
        float span = Math.max(1f, t1 - t0);
        return left() + (t[i] - t0) / span * (right() - left());
    }

    private float yOf(float v, float yMax) {
        return bottom() - v / yMax * (bottom() - top());
    }

    static String clock(float seconds) {
        int s = Math.round(seconds);
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String num(float v) {
        if (v < 10) return String.format(Locale.US, "%.1f", v);
        return String.format(Locale.US, "%d", Math.round(v));
    }

    // ------------------------------------------------------------------ drawing

    /** Averages the samples into buckets that fit the chart's width (missing values left out). */
    private void ensureDisplay() {
        if (displayWidth == getWidth()) return;
        displayWidth = getWidth();
        int n = rawT.length;
        int room = Math.max(2, (int) ((right() - left()) / (2 * dp)));
        bucket = Math.max(1, (int) Math.ceil(n / (double) room));
        int m = (n + bucket - 1) / bucket;
        t = new float[m];
        for (int b = 0; b < m; b++) t[b] = rawT[b * bucket];
        series.clear();
        for (Series s : rawSeries) {
            float[] v = new float[m];
            for (int b = 0; b < m; b++) {
                double sum = 0;
                int count = 0;
                for (int i = b * bucket; i < Math.min(n, (b + 1) * bucket); i++) {
                    if (s.values[i] >= 0) { sum += s.values[i]; count++; }
                }
                v[b] = count == 0 ? -1 : (float) (sum / count);
            }
            series.add(new Series(s.name, s.color, v));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (rawT.length < 2 || rawSeries.isEmpty()) return;
        ensureDisplay();
        if (t.length < 2) return;
        float yMax = yMax();
        float step = yMax / 4f;

        // Grid and y labels (recessive)
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(MUTED);
        for (int k = 0; k <= 4; k++) {
            float v = step * k, y = yOf(v, yMax);
            grid.setColor(k == 0 ? AXIS : GRID);
            c.drawLine(left(), y, right(), y, grid);
            String label = (step < 1 ? String.format(Locale.US, "%.1f", v) : String.valueOf(Math.round(v)));
            c.drawText(label, left() - 6 * dp, y + 4 * dp, text);
        }

        // Time labels
        float duration = t[t.length - 1] - t[0];
        float[] choices = {5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200};
        float every = choices[choices.length - 1];
        for (float ch : choices) if (duration / ch <= 5) { every = ch; break; }
        text.setTextAlign(Paint.Align.CENTER);
        for (float s = 0; s <= duration + 0.01f; s += every) {
            float x = left() + s / Math.max(1f, duration) * (right() - left());
            c.drawText(clock(s), x, getHeight() - 6 * dp, text);
        }

        if (bars) drawBars(c, yMax);
        else drawLines(c, yMax);

        if (touchIndex >= 0 && touchIndex < t.length) drawReadout(c, yMax);
    }

    private void drawLines(Canvas c, float yMax) {
        for (Series s : series) {
            path.reset();
            boolean pen = false;
            for (int i = 0; i < t.length; i++) {
                float v = s.values[i];
                if (v < 0) { pen = false; continue; }
                float x = xOf(i), y = yOf(Math.min(v, yMax), yMax);
                if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
            }
            line.setColor(s.color);
            c.drawPath(path, line);
        }
        drawDirectLabels(c, yMax);
    }

    /** "Network 12" at the end of each line, in text colour with a dot of the series colour, kept apart. */
    private void drawDirectLabels(Canvas c, float yMax) {
        int n = series.size();
        float[] ys = new float[n];
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) {
            order[k] = k;
            float last = -1;
            float[] v = series.get(k).values;
            for (int i = v.length - 1; i >= 0; i--) if (v[i] >= 0) { last = v[i]; break; }
            ys[k] = last < 0 ? Float.NaN : yOf(Math.min(last, yMax), yMax);
        }
        final float[] yy = ys;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Float.compare(yy[a], yy[b]); }
        });
        float gap = 15 * dp, prev = -1e9f;
        float[] placed = new float[n];
        for (int idx : order) {
            if (Float.isNaN(ys[idx])) continue;
            float y = Math.max(ys[idx], prev + gap);
            placed[idx] = y;
            prev = y;
        }
        // Push back up if the lowest ran past the bottom
        float over = prev - bottom();
        if (over > 0) for (int k = 0; k < n; k++) if (!Float.isNaN(ys[k])) placed[k] -= over;
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(INK2);
        for (int k = 0; k < n; k++) {
            if (Float.isNaN(ys[k])) continue;
            fill.setColor(series.get(k).color);
            c.drawCircle(right() + 9 * dp, placed[k] - 4 * dp, 3.5f * dp, fill);
            c.drawText(series.get(k).name, right() + 16 * dp, placed[k], text);
        }
    }

    private void drawBars(Canvas c, float yMax) {
        Series s = series.get(0);
        float slot = (right() - left()) / t.length;
        float w = Math.max(1f, slot - 2 * dp);
        fill.setColor(s.color);
        for (int i = 0; i < t.length; i++) {
            float v = s.values[i];
            if (v <= 0) continue;
            float x = left() + slot * i + (slot - w) / 2;
            float y = yOf(Math.min(v, yMax), yMax);
            if (w >= 6 * dp) {
                float r = 4 * dp;
                rect.set(x, y, x + w, bottom());
                c.drawRoundRect(rect, r, r, fill);
                c.drawRect(x, Math.min(bottom(), y + r), x + w, bottom(), fill); // square base on the axis
            } else {
                c.drawRect(x, y, x + w, bottom(), fill);
            }
        }
    }

    private void drawReadout(Canvas c, float yMax) {
        int i = touchIndex;
        float x = bars ? left() + (right() - left()) / t.length * (i + 0.5f) : xOf(i);
        grid.setColor(INK2);
        c.drawLine(x, top(), x, bottom(), grid);

        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        float t0 = rawT[0];
        lines.add(bucket == 1 ? clock(t[i] - t0)
                : clock(t[i] - t0) + "–" + clock(Math.min(rawT[rawT.length - 1], t[i] + bucket) - t0) + " average");
        colors.add(0);
        for (Series s : series) {
            float v = s.values[i];
            lines.add(s.name + "  " + (v < 0 ? "–" : num(v) + " " + unit));
            colors.add(s.color);
            if (!bars && v >= 0) {
                float y = yOf(Math.min(v, yMax), yMax);
                fill.setColor(SURFACE);
                c.drawCircle(x, y, 6 * dp, fill);  // 2dp surface ring
                fill.setColor(s.color);
                c.drawCircle(x, y, 4 * dp, fill);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
        float lh = 16 * dp, pad = 8 * dp, w = 0;
        for (String l : lines) w = Math.max(w, text.measureText(l));
        w += pad * 2 + 12 * dp;
        float h = lines.size() * lh + pad;
        float bx = x + 10 * dp;
        if (bx + w > getWidth() - 2 * dp) bx = x - 10 * dp - w;
        float by = top();
        rect.set(bx, by, bx + w, by + h);
        fill.setColor(TIP);
        c.drawRoundRect(rect, 8 * dp, 8 * dp, fill);
        grid.setColor(RING);
        Paint.Style old = grid.getStyle();
        grid.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(rect, 8 * dp, 8 * dp, grid);
        grid.setStyle(old);
        for (int k = 0; k < lines.size(); k++) {
            float ty = by + pad + lh * (k + 1) - 5 * dp;
            if (k == 0) {
                text.setColor(INK2);
                c.drawText(lines.get(k), bx + pad, ty, text);
            } else {
                fill.setColor(colors.get(k));
                c.drawCircle(bx + pad + 4 * dp, ty - 4 * dp, 3.5f * dp, fill);
                text.setColor(INK);
                c.drawText(lines.get(k), bx + pad + 12 * dp, ty, text);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        ensureDisplay();
        if (t.length < 2) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                getParent().requestDisallowInterceptTouchEvent(true);
                float x = Math.max(left(), Math.min(right(), e.getX()));
                int best = 0;
                if (bars) {
                    best = (int) ((x - left()) / ((right() - left()) / t.length));
                } else {
                    float bestD = Float.MAX_VALUE;
                    for (int i = 0; i < t.length; i++) {
                        float d = Math.abs(xOf(i) - x);
                        if (d < bestD) { bestD = d; best = i; }
                    }
                }
                touchIndex = Math.max(0, Math.min(t.length - 1, best));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** Average, 95th percentile and maximum of the values that exist, or null if there are none. */
    public static float[] summary(float[] values) {
        List<Float> v = new ArrayList<>();
        double sum = 0;
        for (float x : values) if (x >= 0) { v.add(x); sum += x; }
        if (v.isEmpty()) return null;
        Collections.sort(v);
        float p95 = v.get(Math.min(v.size() - 1, (int) Math.floor(v.size() * 0.95)));
        return new float[]{(float) (sum / v.size()), p95, v.get(v.size() - 1), v.get(0)};
    }
}
