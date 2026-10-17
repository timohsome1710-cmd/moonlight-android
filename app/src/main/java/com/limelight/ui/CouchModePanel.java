package com.limelight.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.video.LiveStats;

import java.net.URL;

/**
 * Moonlight CM: CouchMode's side panel during a stream, like a phone's game assistant. A small handle
 * sits on the left edge; tapping it (or swiping right from it) slides the panel in over the stream.
 * The panel is CouchMode's own /panel page (tools that switch on and off, and tabs for sound,
 * resolution, Legion mode, Bluetooth and power), loaded from the PC Moonlight is streaming from, so it
 * works at home and through Tailscale alike. Tapping beside it, or Back, slides it out.
 *
 * CouchMode's link carries a secret token: Moonlight CM gets it once from the CouchMode app on this
 * phone (which asks the user first) or from the clipboard, and keeps it in its own settings.
 */
public class CouchModePanel {

    public static final int REQUEST_LINK = 0xC0C0;
    private static final String PREFS = "couchmode_panel";
    private static final String COUCHMODE_APP = "com.couchmode.remote";
    private static final String SHARE_ACTION = "com.couchmode.remote.action.SHARE_LINK";
    private static final int ACCENT = 0xFFF5A524, PANEL = 0xFF18191B, INK = 0xFFF3F3F0, DIM = 0xFF8F9196, RAISE = 0xFF242528;

    private final Activity activity;
    private final String streamHost;
    private final Runnable onClosed;
    private final SharedPreferences prefs;
    private final float dp;

    private FrameLayout root, drawer;
    private View handle, scrim;
    private WebView web;
    private LinearLayout message;
    private boolean open, pageLoaded;
    private String loadedUrl;

    /** onClosed runs after the panel slides out (to give the stream its input focus back). */
    public CouchModePanel(Activity activity, String streamHost, Runnable onClosed) {
        this.activity = activity;
        this.streamHost = streamHost;
        this.onClosed = onClosed;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    public boolean isOpen() { return open; }

    /** Adds the handle (and the hidden panel) on top of the stream. */
    @SuppressLint("ClickableViewAccessibility")
    public void attach(ViewGroup content) {
        root = new FrameLayout(activity);

        scrim = new View(activity);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { close(); }
        });
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new FrameLayout(activity);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true); // touches on the panel never reach the stream
        root.addView(drawer, new FrameLayout.LayoutParams(drawerWidth(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        // The handle: a short orange bar on the left edge, inside a larger invisible touch area.
        FrameLayout touch = new FrameLayout(activity);
        View bar = new View(activity);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(3 * dp);
        bar.setBackground(g);
        bar.setAlpha(0.75f);
        touch.addView(bar, new FrameLayout.LayoutParams(px(5), px(56), Gravity.START | Gravity.CENTER_VERTICAL));
        touch.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downX = e.getRawX(); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (e.getRawX() - downX > 24 * dp) { open(); return true; }
                        return true;
                    case MotionEvent.ACTION_UP: if (!open) open(); return true;
                }
                return true;
            }
        });
        handle = touch;
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(px(22), px(96), Gravity.START | Gravity.TOP);
        root.addView(handle, hp);
        content.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Place the handle at 62% of the height, below where phones put their own game-assistant handle.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int h = b - t;
                handle.setTranslationY(Math.max(0, h * 0.62f - px(48)));
                ViewGroup.LayoutParams lp = drawer.getLayoutParams();
                int w = drawerWidth();
                if (lp.width != w) { lp.width = w; drawer.setLayoutParams(lp); }
            }
        });
    }

    private int drawerWidth() {
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        return Math.min(px(440), (int) (screen * 0.5f));
    }

    // ------------------------------------------------------------------ open / close

    public void open() {
        if (open) return;
        open = true;
        handle.setVisibility(View.GONE);
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).setListener(null).start();
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-drawerWidth());
        drawer.animate().translationX(0).setDuration(220).setListener(null).start();
        showContent();
    }

    public void close() {
        if (!open) return;
        open = false;
        if (web != null) {
            web.evaluateJavascript("window.cmPanel && cmPanel.hidden()", null);
            web.onPause();
        }
        scrim.animate().alpha(0f).setDuration(160).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { scrim.setVisibility(View.GONE); }
        }).start();
        drawer.animate().translationX(-drawerWidth()).setDuration(200).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                drawer.setVisibility(View.GONE);
                handle.setVisibility(View.VISIBLE);
                if (onClosed != null) onClosed.run();
            }
        }).start();
    }

    public void destroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
    }

    // ------------------------------------------------------------------ content

    private String panelUrl() {
        String token = prefs.getString("token", null);
        int port = prefs.getInt("port", 8642);
        if (token == null || token.isEmpty() || streamHost == null) return null;
        String h = streamHost.contains(":") && !streamHost.startsWith("[") ? "[" + streamHost + "]" : streamHost;
        return "http://" + h + ":" + port + "/" + token + "/panel";
    }

    private void showContent() {
        String url = panelUrl();
        if (url == null) { showSetup(null); return; }
        if (web == null) createWeb();
        if (message != null) message.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.onResume();
        if (!url.equals(loadedUrl)) {
            loadedUrl = url;
            pageLoaded = false;
            web.loadUrl(url);
        } else if (pageLoaded) {
            web.evaluateJavascript("window.cmPanel && cmPanel.shown()", null);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWeb() {
        web = new WebView(activity);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);            // keep controller and keyboard input on the stream
        web.setFocusableInTouchMode(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "MoonlightCM");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { pageLoaded = true; }

            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    loadedUrl = null;
                    showSetup("Couldn't open CouchMode on " + streamHost + ". Is CouchMode 1.22 or newer running on the laptop?");
                }
            }

            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, android.webkit.WebResourceResponse resp) {
                if (req.isForMainFrame() && resp.getStatusCode() == 404) {
                    loadedUrl = null;
                    showSetup("CouchMode didn't accept the saved link (its token changed, or CouchMode is older than 1.22). Get the link again.");
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String path = req.getUrl().getPath();
                return path == null || !path.endsWith("/panel"); // stay on the panel
            }
        });
        drawer.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** What the panel page can ask of Moonlight CM. */
    private final class Bridge {
        @JavascriptInterface public String stats() { return LiveStats.json(); }

        @JavascriptInterface public void close() {
            activity.runOnUiThread(new Runnable() { @Override public void run() { CouchModePanel.this.close(); } });
        }
    }

    /** First time (no link yet), or when the link stopped working. */
    private void showSetup(final String problem) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web != null) web.setVisibility(View.GONE);
                if (message == null) {
                    message = new LinearLayout(activity);
                    message.setOrientation(LinearLayout.VERTICAL);
                    message.setGravity(Gravity.CENTER_VERTICAL);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(PANEL);
                    bg.setCornerRadius(28 * dp);
                    message.setBackground(bg);
                    message.setPadding(px(20), px(16), px(20), px(16));
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    lp.setMargins(px(10), px(10), px(10), px(10));
                    drawer.addView(message, lp);
                }
                message.removeAllViews();
                message.setVisibility(View.VISIBLE);
                message.addView(text("CouchMode panel", 19, INK, true));
                TextView t = text(problem != null ? problem
                        : "Control the laptop without leaving the stream: screen, sound, resolution, Legion mode, power. "
                          + "Moonlight CM needs CouchMode's link once.", 14, DIM, false);
                t.setPadding(0, px(6), 0, px(12));
                message.addView(t);
                message.addView(button("Get it from the CouchMode app", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { requestLinkFromApp(); }
                }));
                message.addView(button("Paste a copied CouchMode link", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { pasteLink(); }
                }));
                if (problem != null && prefs.getString("token", null) != null) {
                    message.addView(button("Try again", false, new View.OnClickListener() {
                        @Override public void onClick(View v) { showContent(); }
                    }));
                }
                message.addView(button("Close", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { close(); }
                }));
            }
        });
    }

    private void requestLinkFromApp() {
        Intent i = new Intent(SHARE_ACTION).setPackage(COUCHMODE_APP);
        try {
            activity.startActivityForResult(i, REQUEST_LINK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "Install or update the CouchMode app (1.7 or newer), or paste the link instead.", Toast.LENGTH_LONG).show();
        }
    }

    /** Called from the activity's onActivityResult. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_LINK) return false;
        if (resultCode == Activity.RESULT_OK && data != null) {
            String token = data.getStringExtra("token");
            int port = data.getIntExtra("port", 8642);
            if (token == null) token = tokenOf(data.getStringExtra("url"));
            if (token != null) {
                save(token, port);
                if (open) showContent();
                return true;
            }
        }
        Toast.makeText(activity, "No link from the CouchMode app.", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void pasteLink() {
        try {
            ClipboardManager cb = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cb == null ? null : cb.getPrimaryClip();
            String text = clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null ? null : clip.getItemAt(0).getText().toString().trim();
            String token = tokenOf(text);
            int port = portOf(text);
            if (token == null) {
                Toast.makeText(activity, "Copy the CouchMode link first (laptop: tray icon → Phone remote → Copy).", Toast.LENGTH_LONG).show();
                return;
            }
            save(token, port);
            showContent();
        } catch (Exception e) {
            LimeLog.warning("CouchMode link paste failed: " + e);
        }
    }

    private void save(String token, int port) {
        prefs.edit().putString("token", token).putInt("port", port > 0 ? port : 8642).apply();
        loadedUrl = null;
    }

    private static String tokenOf(String link) {
        if (link == null || !link.startsWith("http")) return null;
        try {
            for (String seg : new URL(link).getPath().split("/")) if (!seg.isEmpty()) return seg;
        } catch (Exception ignored) { }
        return null;
    }

    private static int portOf(String link) {
        try { int p = new URL(link).getPort(); return p > 0 ? p : 8642; } catch (Exception e) { return 8642; }
    }

    // ------------------------------------------------------------------ small helpers

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        return t;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        b.setTextColor(primary ? 0xFF141210 : INK);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : RAISE);
        g.setCornerRadius(24 * dp);
        b.setBackground(g);
        b.setFocusable(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(46));
        lp.topMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int px(float v) { return (int) (v * dp + 0.5f); }
}
