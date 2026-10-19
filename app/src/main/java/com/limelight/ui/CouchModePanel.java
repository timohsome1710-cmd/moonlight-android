package com.limelight.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;

/**
 * Moonlight CM: CouchMode's side panel during a stream, laid out like a phone's game assistant.
 * A slim handle sits on the left edge (the system back gesture is switched off over it); dragging it
 * pulls the panel out under the finger, a tap or a fling opens it, and a swipe back to the left (or
 * a tap beside it, or Back) puts it away.
 *
 * The panel page ships inside this app (assets/couchmode_panel.html) and is loaded with the laptop's
 * CouchMode address as its origin, so it talks to CouchMode directly (same origin) at home and
 * through Tailscale alike, and a new look only needs a new Moonlight CM, not a laptop update.
 *
 * CouchMode's link carries a secret token: Moonlight CM gets it once from the CouchMode app on this
 * phone (which asks the user first) or from the clipboard, and keeps it in its own settings.
 */
public class CouchModePanel {

    public static final int REQUEST_LINK = 0xC0C0;
    private static final String PREFS = "couchmode_panel";
    private static final String COUCHMODE_APP = "com.couchmode.remote";
    private static final String SHARE_ACTION = "com.couchmode.remote.action.SHARE_LINK";
    private static final String PAGE_ASSET = "couchmode_panel.html";
    private static final int ACCENT = 0xFFF5A524, PANEL = 0xF71B1B1D, INK = 0xFFF2F2EF, DIM = 0xFF9B9CA1, RAISE = 0xFF2C2C2F;
    private static final int SCRIM = 0x55000000;
    private static final long HANDLE_DIM_DELAY = 3500;

    private final Activity activity;
    private final String streamHost;
    private final Runnable onClosed;
    private final SharedPreferences prefs;
    private final float dp;
    private final int touchSlop;
    private final float flingVelocity;

    private FrameLayout root, handle;
    private DrawerFrame drawer;
    private View handleBar, scrim;
    private WebView web;
    private LinearLayout message;
    private String pageHtml;

    /** open: the panel is out or sliding out. dragging: a finger is moving it. */
    private boolean open, dragging, pageLoaded, pageShown;
    private String loadedUrl;
    private int animSeq;
    private SoundListener soundListener;
    /** The page holds the touch (its volume slider is being dragged): no swipe-to-close meanwhile. */
    private volatile boolean pageHoldsTouch;
    /** The Keyboard tile was tapped: bring up the phone keyboard once the panel is out of the way. */
    private boolean keyboardAfterClose;

    /** Told where CouchMode plays the stream's sound now ("laptop" or "phone"), as the panel learns it. */
    public interface SoundListener { void onSound(String effective); }

    public void setSoundListener(SoundListener l) { soundListener = l; }

    /** onClosed runs after the panel slides away (to give the stream its input focus back). */
    public CouchModePanel(Activity activity, String streamHost, Runnable onClosed) {
        this.activity = activity;
        this.streamHost = streamHost;
        this.onClosed = onClosed;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.dp = activity.getResources().getDisplayMetrics().density;
        ViewConfiguration vc = ViewConfiguration.get(activity);
        this.touchSlop = vc.getScaledTouchSlop();
        this.flingVelocity = 450 * dp; // px per second
    }

    /** True while the panel is out, sliding, or held by a finger (Back then belongs to the panel). */
    public boolean isOpen() { return open || dragging; }

    /** Adds the handle (and the hidden panel) on top of the stream. */
    @SuppressLint("ClickableViewAccessibility")
    public void attach(ViewGroup content) {
        root = new FrameLayout(activity);

        scrim = new View(activity);
        scrim.setBackgroundColor(SCRIM);
        scrim.setVisibility(View.GONE);
        scrim.setOnTouchListener(new View.OnTouchListener() {
            final SwipeBack swipe = new SwipeBack();
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: swipe.down(e); return true;
                    case MotionEvent.ACTION_MOVE: swipe.move(e); return true;
                    case MotionEvent.ACTION_UP:
                        if (!swipe.end(e, false)) close(); // a tap beside the panel
                        return true;
                    case MotionEvent.ACTION_CANCEL: swipe.end(e, true); return true;
                }
                return true;
            }
        });
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new DrawerFrame(activity);
        drawer.setVisibility(View.INVISIBLE);
        drawer.setClickable(true); // touches on the panel never reach the stream
        root.addView(drawer, new FrameLayout.LayoutParams(drawerWidth(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        // The handle: a slim bar on the left edge inside a wider touch strip. The system back gesture
        // is excluded over the strip, so pulling it never takes the phone "back" out of the stream.
        handle = new FrameLayout(activity);
        handleBar = new View(activity);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(2.5f * dp);
        handleBar.setBackground(g);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(px(4), px(44), Gravity.START | Gravity.CENTER_VERTICAL);
        bp.leftMargin = px(3);
        handle.addView(handleBar, bp);
        handle.setOnTouchListener(new EdgePull());
        root.addView(handle, new FrameLayout.LayoutParams(px(30), px(112), Gravity.START | Gravity.TOP));
        content.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                // The handle sits at about 62% of the height, below where phones put their own game assistant.
                FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) handle.getLayoutParams();
                int top = Math.max(0, (int) ((b - t) * 0.62f) - hp.height / 2);
                if (hp.topMargin != top) { hp.topMargin = top; handle.setLayoutParams(hp); }
                ViewGroup.LayoutParams lp = drawer.getLayoutParams();
                int w = drawerWidth();
                if (lp.width != w) {
                    lp.width = w;
                    drawer.setLayoutParams(lp);
                    if (!isOpen()) drawer.setTranslationX(-w);
                }
            }
        });
        handle.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, r - l, b - t)));
                }
            }
        });

        drawer.setTranslationX(-drawerWidth());
        dimHandleLater();
        // Load the page now (it stays hidden and quiet), so the first pull shows it straight away.
        if (panelUrl() != null) {
            createWeb();
            loadPage(panelUrl());
        }
    }

    private int drawerWidth() {
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        return Math.min(px(440), (int) (screen * 0.5f));
    }

    // ------------------------------------------------------------------ handle

    private final Runnable dimHandle = new Runnable() {
        @Override public void run() { handleBar.animate().alpha(0.35f).setDuration(400).start(); }
    };

    private void dimHandleLater() {
        handleBar.removeCallbacks(dimHandle);
        handleBar.animate().cancel();
        handleBar.setAlpha(0.9f);
        handleBar.postDelayed(dimHandle, HANDLE_DIM_DELAY);
    }

    /** Pulling the handle: the panel follows the finger; a tap or a fling to the right opens it. */
    private final class EdgePull implements View.OnTouchListener {
        private float downX, downY;
        private boolean pulling;
        private VelocityTracker vt;

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); pulling = false;
                    vt = VelocityTracker.obtain(); track(vt, e);
                    handleBar.removeCallbacks(dimHandle);
                    handleBar.animate().cancel();
                    handleBar.setAlpha(1f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    track(vt, e);
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!pulling && dx > touchSlop && dx > Math.abs(dy)) {
                        pulling = true;
                        dragging = true;
                        haptic();
                        prepareToShow();
                        handleBar.setAlpha(0f);
                    }
                    if (pulling) moveTo(-drawerWidth() + (dx - touchSlop));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    track(vt, e);
                    if (pulling) {
                        vt.computeCurrentVelocity(1000);
                        settle(shouldEndOpen(vt.getXVelocity()));
                    } else if (Math.abs(e.getRawY() - downY) < touchSlop * 2) {
                        haptic();
                        open();
                    } else {
                        dimHandleLater();
                    }
                    recycle();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (pulling) settle(drawer.getTranslationX() > -drawerWidth() / 2f);
                    else dimHandleLater();
                    recycle();
                    return true;
            }
            return true;
        }

        private void recycle() {
            pulling = false;
            if (vt != null) { vt.recycle(); vt = null; }
        }
    }

    /** A swipe to the left on the panel or beside it: the panel follows and slides away on release. */
    private final class SwipeBack {
        private float downX, downY;
        private boolean active;
        private VelocityTracker vt;

        void down(MotionEvent e) {
            downX = e.getRawX(); downY = e.getRawY(); active = false;
            pageHoldsTouch = false; // the page says so again if this touch lands on its slider
            if (vt != null) vt.recycle();
            vt = VelocityTracker.obtain(); track(vt, e);
        }

        /** Returns true once this is a sideways swipe the panel follows. */
        boolean move(MotionEvent e) {
            if (vt == null) return false;
            track(vt, e);
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            if (!active && open && !pageHoldsTouch && dx < -touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                active = true;
                dragging = true;
                drawer.animate().cancel();
                animSeq++;
            }
            if (active) moveTo(Math.min(0, dx + touchSlop));
            return active;
        }

        /** Returns true if this was a swipe (so a tap can be told apart). */
        boolean end(MotionEvent e, boolean cancelled) {
            boolean was = active;
            if (vt != null) {
                if (!cancelled) track(vt, e);
                if (active) {
                    vt.computeCurrentVelocity(1000);
                    settle(!cancelled && shouldEndOpen(vt.getXVelocity()));
                }
                vt.recycle();
                vt = null;
            }
            active = false;
            return was;
        }
    }

    /** The panel's own frame: it lets taps and scrolling through to the page, but takes over a swipe to the left. */
    private final class DrawerFrame extends FrameLayout {
        private final SwipeBack swipe = new SwipeBack();

        DrawerFrame(Context c) { super(c); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: swipe.down(e); return false;
                case MotionEvent.ACTION_MOVE: return swipe.move(e);
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: swipe.end(e, true); return false;
            }
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: if (swipe.move(e)) return true; break;
                case MotionEvent.ACTION_UP: if (swipe.end(e, false)) return true; break;
                case MotionEvent.ACTION_CANCEL: if (swipe.end(e, true)) return true; break;
            }
            return super.onTouchEvent(e);
        }
    }

    /** Velocity tracking in screen coordinates (the panel itself moves under the finger). */
    private static void track(VelocityTracker vt, MotionEvent e) {
        if (vt == null) return;
        MotionEvent copy = MotionEvent.obtain(e);
        copy.setLocation(e.getRawX(), e.getRawY());
        vt.addMovement(copy);
        copy.recycle();
    }

    private boolean shouldEndOpen(float vx) {
        if (vx > flingVelocity) return true;
        if (vx < -flingVelocity) return false;
        return drawer.getTranslationX() > -drawerWidth() / 2f;
    }

    private void haptic() {
        handle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ------------------------------------------------------------------ open / close

    public void open() {
        if (open && !dragging) return;
        prepareToShow();
        settle(true);
    }

    public void close() {
        if (!isOpen()) return;
        settle(false);
    }

    /** Back: the page goes back a step first (a sub-page, a question), then the panel closes. */
    public void back() {
        if (!isOpen()) return;
        if (web != null && pageLoaded && web.getVisibility() == View.VISIBLE) {
            web.evaluateJavascript("(window.cmPanel && cmPanel.back) ? cmPanel.back() : false", new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) { if (!"true".equals(v)) close(); }
            });
        } else {
            close();
        }
    }

    /** Makes the panel visible just off the left edge, ready to slide in. */
    private void prepareToShow() {
        keyboardAfterClose = false;
        drawer.animate().cancel();
        animSeq++;
        if (drawer.getVisibility() != View.VISIBLE) {
            drawer.setTranslationX(-drawerWidth());
            drawer.setVisibility(View.VISIBLE);
            scrim.setAlpha(0f);
            scrim.setVisibility(View.VISIBLE);
            showContent();
        }
    }

    private void moveTo(float tx) {
        int w = drawerWidth();
        tx = Math.max(-w, Math.min(0, tx));
        drawer.setTranslationX(tx);
        scrim.setAlpha(1f + tx / w);
    }

    private void settle(final boolean toOpen) {
        dragging = false;
        open = toOpen;
        final int w = drawerWidth();
        float target = toOpen ? 0 : -w;
        float distance = Math.abs(target - drawer.getTranslationX());
        long duration = Math.max(90, Math.min(240, (long) (240 * distance / w)));
        final int seq = ++animSeq;
        drawer.animate().cancel();
        drawer.animate()
                .translationX(target)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .setUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) { scrim.setAlpha(1f + drawer.getTranslationX() / w); }
                })
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        if (seq != animSeq) return; // replaced by a newer move
                        if (toOpen) opened(); else closed();
                    }
                })
                .start();
    }

    private void opened() {
        handle.setVisibility(View.INVISIBLE);
        scrim.setAlpha(1f);
    }

    private void closed() {
        drawer.setVisibility(View.INVISIBLE);
        scrim.setVisibility(View.GONE);
        handle.setVisibility(View.VISIBLE);
        dimHandleLater();
        pageHidden();
        if (onClosed != null) onClosed.run(); // the stream view has the focus again
        if (keyboardAfterClose) {
            keyboardAfterClose = false;
            showKeyboardSoon();
        }
    }

    /**
     * The phone keyboard, as the three-finger tap brings it up: typing goes to the laptop. A moment after
     * the stream view gets its focus back, so the keyboard attaches to the stream and not to the panel.
     */
    private void showKeyboardSoon() {
        root.postDelayed(new Runnable() {
            @Override public void run() {
                if (activity instanceof GameGestures && !activity.isFinishing()) ((GameGestures) activity).toggleKeyboard();
            }
        }, 120);
    }

    private void keyboardTapped() {
        if (isOpen()) {
            keyboardAfterClose = true;
            close();
        } else {
            showKeyboardSoon();
        }
    }

    public void destroy() {
        if (handleBar != null) handleBar.removeCallbacks(dimHandle);
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
        if (!url.equals(loadedUrl)) loadPage(url);
        pageShownIfReady();
    }

    private void loadPage(String url) {
        loadedUrl = url;
        pageLoaded = false;
        pageShown = true; // the page starts out polling
        String html = pageHtml();
        if (html != null) web.loadDataWithBaseURL(url, html, "text/html", "utf-8", null);
        else web.loadUrl(url); // the laptop's own copy (CouchMode 1.22 or newer)
    }

    private void pageShownIfReady() {
        if (web == null || !pageLoaded || pageShown) return;
        pageShown = true;
        web.onResume();
        web.evaluateJavascript("window.cmPanel && cmPanel.shown()", null);
    }

    private void pageHidden() {
        if (web == null || !pageLoaded || !pageShown) return;
        pageShown = false;
        web.evaluateJavascript("window.cmPanel && cmPanel.hidden()", null);
        web.onPause();
    }

    private String pageHtml() {
        if (pageHtml != null) return pageHtml;
        try (InputStream in = activity.getAssets().open(PAGE_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            pageHtml = out.toString("UTF-8");
        } catch (Exception e) {
            LimeLog.warning("CouchMode panel page missing: " + e);
        }
        return pageHtml;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWeb() {
        web = new WebView(activity);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);            // keep controller and keyboard input on the stream
        web.setFocusableInTouchMode(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);                 // the panel is laid out to fit; the phone's font size would overflow it
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "MoonlightCM");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageLoaded = true;
                if (!isOpen()) pageHidden(); // loaded ahead of time: stay quiet until the panel comes out
            }

            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, android.webkit.WebResourceResponse resp) {
                if (req.isForMainFrame() && resp.getStatusCode() == 404) linkRejected();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return true; // the panel never navigates away
            }
        });
        drawer.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void linkRejected() {
        loadedUrl = null;
        showSetup("CouchMode didn't accept the saved link (its token changed). Get the link again.");
    }

    /** What the panel page can ask of Moonlight CM. */
    private final class Bridge {
        @JavascriptInterface public String stats() { return LiveStats.json(); }

        @JavascriptInterface public void close() {
            activity.runOnUiThread(new Runnable() { @Override public void run() { CouchModePanel.this.close(); } });
        }

        @JavascriptInterface public void holdTouch(boolean hold) { pageHoldsTouch = hold; }

        @JavascriptInterface public void keyboard() {
            activity.runOnUiThread(new Runnable() { @Override public void run() { keyboardTapped(); } });
        }

        @JavascriptInterface public void setSound(final String effective) {
            activity.runOnUiThread(new Runnable() {
                @Override public void run() { if (soundListener != null && effective != null) soundListener.onSound(effective); }
            });
        }

        @JavascriptInterface public void linkRejected() {
            activity.runOnUiThread(new Runnable() { @Override public void run() { CouchModePanel.this.linkRejected(); } });
        }
    }

    /** First time (no link yet), or when the link stopped working. */
    private void showSetup(final String problem) {
        if (web != null) web.setVisibility(View.GONE);
        if (message == null) {
            message = new LinearLayout(activity);
            message.setOrientation(LinearLayout.VERTICAL);
            message.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(PANEL);
            bg.setCornerRadius(24 * dp);
            message.setBackground(bg);
            message.setPadding(px(18), px(14), px(18), px(14));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(px(10), px(10), px(10), px(10));
            drawer.addView(message, lp);
        }
        message.removeAllViews();
        message.setVisibility(View.VISIBLE);
        message.addView(text("CouchMode panel", 17, INK, true));
        TextView t = text(problem != null ? problem
                : "Control the laptop without leaving the stream: screen, sound, resolution, Legion mode, power. "
                  + "Moonlight CM needs CouchMode's link once.", 13, DIM, false);
        t.setPadding(0, px(6), 0, px(10));
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
                if (isOpen()) showContent();
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
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        b.setTextColor(primary ? 0xFF17130C : INK);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : RAISE);
        g.setCornerRadius(22 * dp);
        b.setBackground(g);
        b.setFocusable(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(42));
        lp.topMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int px(float v) { return (int) (v * dp + 0.5f); }
}
