package com.limelight;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.binding.video.LatencyLog;
import com.limelight.ui.LatencyChartView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Moonlight CM: shown when a stream ends (Settings → "Show latency graph after streaming"). Draws
 * Moonlight's own per-second numbers from that stream: network round trip, the host's processing
 * time and this phone's decoding time (one chart, all in ms), and frames dropped (its own chart).
 */
public class LatencyGraphActivity extends Activity {

    // Series colours, validated as a set against the dark chart surface (colour-blind safe in this order).
    private static final int NETWORK = 0xFF3987E5, HOST = 0xFFD95926, DECODE = 0xFF199E70, DROPS = 0xFFE66767;
    private static final int PAGE = 0xFF0D0D0D, INK = 0xFFF0EFEC;

    private float dp;
    private LinearLayout table;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(PAGE);
        getWindow().setNavigationBarColor(PAGE);

        List<LatencyLog.Sample> samples = LatencyLog.takeUnshown();
        if (samples == null) samples = LatencyLog.last();
        if (samples.size() < 3) { finish(); return; }

        int n = samples.size();
        float[] t = new float[n], network = new float[n], host = new float[n], decode = new float[n], drops = new float[n];
        float fpsSum = 0;
        for (int i = 0; i < n; i++) {
            LatencyLog.Sample s = samples.get(i);
            t[i] = s.t; network[i] = s.networkMs; host[i] = s.hostMs; decode[i] = s.decodeMs; drops[i] = s.dropPct;
            fpsSum += s.fps;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = px(18);
        root.setPadding(pad, px(20), pad, px(24));
        scroll.addView(root);

        root.addView(text("Stream latency", 24, INK, true));
        String video = LatencyLog.video();
        String sub = clock(t[n - 1] - t[0]) + " streamed"
                + String.format(Locale.US, " · %d fps average", Math.round(fpsSum / n))
                + (video.isEmpty() ? "" : " · " + video);
        TextView subView = text(sub, 14, 0xFFC3C2B7, false);
        subView.setPadding(0, px(4), 0, px(14));
        root.addView(subView);

        // Headline numbers
        float[] sn = LatencyChartView.summary(network), sh = LatencyChartView.summary(host),
                sd = LatencyChartView.summary(decode);
        float totalDrops = 0;
        for (float d : drops) totalDrops += d;
        float avgDrops = totalDrops / n;
        LinearLayout row1 = new LinearLayout(this), row2 = new LinearLayout(this);
        row1.addView(tile("Network", sn, "round trip"), weight());
        row1.addView(tile("Host", sh, "capture + encode"), weight());
        row2.addView(tile("Decode", sd, "on this phone"), weight());
        row2.addView(tile("Dropped", new float[]{avgDrops, -1, -1, -1}, "frames, network", "%"), weight());
        root.addView(row1, matchWrap());
        LinearLayout.LayoutParams r2 = matchWrap();
        r2.topMargin = px(8);
        root.addView(row2, r2);

        // Latency chart: three series, one axis, one unit
        LinearLayout card = card(root, "Latency over the stream (ms)");
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.addView(legendItem("Network", NETWORK));
        legend.addView(legendItem("Host", HOST));
        legend.addView(legendItem("Decode", DECODE));
        card.addView(legend);
        LatencyChartView lines = new LatencyChartView(this);
        List<LatencyChartView.Series> ls = new ArrayList<>();
        ls.add(new LatencyChartView.Series("Network", NETWORK, network));
        ls.add(new LatencyChartView.Series("Host", HOST, host));
        ls.add(new LatencyChartView.Series("Decode", DECODE, decode));
        lines.setData(t, ls, false, "ms");
        lines.setContentDescription(describe("Network", sn) + " " + describe("Host", sh) + " " + describe("Decode", sd));
        card.addView(lines, matchWrap());
        card.addView(note("Touch and drag to read a second. Gaps: Moonlight had no number for that second."));

        // Drops: their own chart (a different unit), or a sentence when there were none
        LinearLayout dropCard = card(root, "Frames dropped by the network (%)");
        if (totalDrops <= 0) {
            dropCard.addView(text("None. Every frame arrived.", 15, INK, false));
        } else {
            LatencyChartView bars = new LatencyChartView(this);
            List<LatencyChartView.Series> bs = new ArrayList<>();
            bs.add(new LatencyChartView.Series("Dropped", DROPS, drops));
            bars.setData(t, bs, true, "%");
            bars.setContentDescription(String.format(Locale.US, "Frames dropped: %.1f%% on average.", avgDrops));
            dropCard.addView(bars, matchWrap());
        }

        // The numbers as a table, for reading without the charts
        final Button toggle = button("Show the numbers", false);
        LinearLayout.LayoutParams tp = matchWrap();
        tp.topMargin = px(14);
        root.addView(toggle, tp);
        table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setVisibility(View.GONE);
        table.setPadding(px(14), px(10), px(14), px(10));
        table.setBackground(rounded(0xFF1A1A19, 14));
        LinearLayout.LayoutParams tbl = matchWrap();
        tbl.topMargin = px(8);
        root.addView(table, tbl);
        table.addView(tableRow("", "Average", "95%", "Worst", true));
        table.addView(tableRow("Network ms", sn));
        table.addView(tableRow("Host ms", sh));
        table.addView(tableRow("Decode ms", sd));
        float[] sdrop = LatencyChartView.summary(drops);
        table.addView(tableRow("Dropped %", sdrop));
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = table.getVisibility() != View.VISIBLE;
                table.setVisibility(show ? View.VISIBLE : View.GONE);
                toggle.setText(show ? "Hide the numbers" : "Show the numbers");
            }
        });

        root.addView(note("Network: time for a message to reach the laptop and come back. Host: the laptop's time to "
                + "capture and encode each frame. Decode: this phone's time to decode each frame. "
                + "Turn this screen off in Settings → \"Show latency graph after streaming\"."));

        Button done = button("Done", true);
        LinearLayout.LayoutParams dl = matchWrap();
        dl.topMargin = px(16);
        root.addView(done, dl);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        setContentView(scroll);
    }

    // ------------------------------------------------------------------ pieces

    private static String clock(float s) {
        int sec = Math.round(s);
        if (sec >= 3600) return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private static String fmt(float v) {
        if (v < 0) return "–";
        return v < 10 ? String.format(Locale.US, "%.1f", v) : String.valueOf(Math.round(v));
    }

    private static String describe(String name, float[] s) {
        return s == null ? name + ": no data." : name + ": " + fmt(s[0]) + " ms on average, worst " + fmt(s[2]) + " ms.";
    }

    private View tile(String label, float[] s, String what) { return tile(label, s, what, "ms"); }

    private View tile(String label, float[] s, String what, String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(14), px(12), px(14), px(12));
        box.setBackground(rounded(0xFF1A1A19, 14));
        box.addView(text(label, 13, 0xFFC3C2B7, false));
        LinearLayout valueRow = new LinearLayout(this);
        valueRow.setGravity(Gravity.BOTTOM);
        valueRow.addView(text(s == null ? "–" : fmt(s[0]), 28, INK, true));
        TextView u = text(" " + unit, 14, 0xFFC3C2B7, false);
        u.setPadding(0, 0, 0, px(4));
        valueRow.addView(u);
        box.addView(valueRow);
        String extra = s == null ? what : (s[1] >= 0 ? what + " · 95% under " + fmt(s[1]) : what);
        box.addView(text(extra, 12.5f, 0xFF898781, false));
        return box;
    }

    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(14), px(14), px(14), px(12));
        c.setBackground(rounded(0xFF1A1A19, 16));
        TextView h = text(title, 15.5f, INK, true);
        h.setPadding(0, 0, 0, px(8));
        c.addView(h);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = px(14);
        root.addView(c, lp);
        return c;
    }

    private View legendItem(String name, int color) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, 0, px(16), px(6));
        View swatch = new View(this);
        swatch.setBackground(rounded(color, 2));
        item.addView(swatch, new LinearLayout.LayoutParams(px(16), px(3)));
        TextView t = text(name, 13.5f, 0xFFC3C2B7, false);
        t.setPadding(px(6), 0, 0, 0);
        item.addView(t);
        return item;
    }

    private View tableRow(String name, float[] s) {
        return tableRow(name, s == null ? "–" : fmt(s[0]), s == null ? "–" : fmt(s[1]), s == null ? "–" : fmt(s[2]), false);
    }

    private View tableRow(String a, String b, String c, String d, boolean head) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, px(5), 0, px(5));
        int color = head ? 0xFF898781 : INK;
        r.addView(cell(a, color, Gravity.START, head), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        r.addView(cell(b, color, Gravity.END, head), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(cell(c, color, Gravity.END, head), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(cell(d, color, Gravity.END, head), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private TextView cell(String s, int color, int gravity, boolean head) {
        TextView t = text(s, 14, color, false);
        t.setGravity(gravity);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 13, 0xFF898781, false);
        t.setPadding(px(2), px(10), px(2), 0);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        b.setTextColor(primary ? PAGE : INK);
        b.setBackground(rounded(primary ? 0xFFF0EFEC : 0xFF262624, 24));
        return b;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(px(4));
        lp.setMarginStart(px(4));
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int px(float v) { return (int) (v * dp + 0.5f); }
}
