package com.ahusan.webapps;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** Opens one website full-screen, with a remote-controlled mouse cursor and an options bar. */
public class WebAppActivity extends Activity {

    static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    static final String JS_PLAY_PAUSE = "(function(){var v=document.querySelector('video');"
            + "if(v){if(v.paused)v.play();else v.pause();}})()";

    private FrameLayout root;
    private WebView web;
    private CursorView cursor;
    private ProgressBar progress;
    private LinearLayout bar;
    private TextView barTitle;
    private Button bCursor, bDesktop;

    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    private Store.App app;
    private boolean temporary;
    private String mobileUA;

    private float cx, cy;
    private long touchDownTime;
    private boolean backLongDone;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideCursor = () -> {
        if (cursor != null) cursor.setVisibility(View.INVISIBLE);
    };

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        app = Store.find(this, getIntent().getStringExtra("id"));
        if (app == null) {
            temporary = true;
            app = new Store.App();
            app.url = getIntent().getStringExtra("url");
            if (app.url == null) {
                finish();
                return;
            }
            app.name = Store.defaultName(app.url);
            app.desktop = getIntent().getBooleanExtra("desktop", false);
            app.cursor = getIntent().getBooleanExtra("cursor", true);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(MainActivity.ACCENT));
        progress.setIndeterminate(false);
        root.addView(progress, new FrameLayout.LayoutParams(-1, dp(6), Gravity.TOP));

        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));

        buildBar();

        setContentView(root);
        setupWeb();
        web.loadUrl(app.url);

        root.post(() -> {
            cx = root.getWidth() / 2f;
            cy = root.getHeight() / 2f;
            cursor.moveTo(cx, cy);
            updateCursorVisibility();
        });

        Toast.makeText(this, "Hold BACK (or press MENU) for options", Toast.LENGTH_LONG).show();
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Block pop-up windows; links with target=_blank open in this same view.
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);

        // Look like normal Chrome instead of an embedded WebView (some sites refuse WebViews).
        mobileUA = s.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+(\\.\\d+)* ", "");
        applyUserAgent();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(r.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                barTitle.setText(Store.host(url));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = v;
                customCb = cb;
                root.addView(v, new FrameLayout.LayoutParams(-1, -1));
                showBar(false);
                updateCursorVisibility();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) {
                // Allow protected (DRM) video; refuse camera/mic.
                for (String res : r.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                        r.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                        return;
                    }
                }
                r.deny();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
    }

    private boolean handleUrl(String url) {
        Uri u = Uri.parse(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                || scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) {
            return false;
        }
        try {
            if (scheme.equals("intent")) {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fallback = i.getStringExtra("browser_fallback_url");
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    if (fallback != null) web.loadUrl(fallback);
                }
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            }
        } catch (Exception ignored) {
            // No app can open it (market://, tel:, etc. on a TV) — just stay on the page.
        }
        return true;
    }

    private void applyUserAgent() {
        web.getSettings().setUserAgentString(app.desktop ? DESKTOP_UA : mobileUA);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        View v = customView;
        WebChromeClient.CustomViewCallback cb = customCb;
        customView = null;
        customCb = null;
        root.removeView(v);
        if (cb != null) cb.onCustomViewHidden();
        updateCursorVisibility();
        web.requestFocus();
    }

    private void js(String code) {
        web.evaluateJavascript(code, null);
    }

    // ---------- Options bar ----------

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE10141B);
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(18), dp(18), dp(18), dp(18)});
        bar.setBackground(bg);

        barTitle = new TextView(this);
        barTitle.setTextColor(0xFF8B95A5);
        barTitle.setTextSize(13);
        barTitle.setPadding(dp(6), 0, 0, dp(8));
        barTitle.setText(app.name);
        bar.addView(barTitle);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.addView(button("◀  Back", () -> {
            if (web.canGoBack()) web.goBack();
        }));
        row.addView(button("Forward  ▶", () -> {
            if (web.canGoForward()) web.goForward();
        }));
        row.addView(button("⟳  Reload", () -> {
            web.reload();
            showBar(false);
        }));
        row.addView(button("⌂  Home", () -> {
            web.loadUrl(app.url);
            showBar(false);
        }));
        bCursor = button("", () -> {
            app.cursor = !app.cursor;
            saveApp();
            updateLabels();
        });
        row.addView(bCursor);
        bDesktop = button("", () -> {
            app.desktop = !app.desktop;
            saveApp();
            updateLabels();
            applyUserAgent();
            web.reload();
            showBar(false);
        });
        row.addView(bDesktop);
        row.addView(button("Zoom +", () -> web.zoomIn()));
        row.addView(button("Zoom −", () -> web.zoomOut()));
        row.addView(button("✕  Exit", this::finish));
        updateLabels();

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        bar.addView(hs);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(bar, lp);
        bar.setVisibility(View.GONE);
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setFocusable(true);
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0xFF1F2733);
        normal.setCornerRadius(dp(12));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(MainActivity.ACCENT);
        focused.setCornerRadius(dp(12));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, normal);
        b.setBackground(sl);

        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateLabels() {
        bCursor.setText(app.cursor ? "Cursor: ON" : "Cursor: OFF");
        bDesktop.setText(app.desktop ? "Use mobile site" : "Use desktop site");
    }

    private void saveApp() {
        if (!temporary) Store.update(this, app);
    }

    private boolean barShown() {
        return bar.getVisibility() == View.VISIBLE;
    }

    private void showBar(boolean show) {
        if (show == barShown()) return;
        bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            LinearLayout row = (LinearLayout) ((HorizontalScrollView) bar.getChildAt(1)).getChildAt(0);
            row.getChildAt(0).requestFocus();
        } else {
            web.requestFocus();
        }
        updateCursorVisibility();
    }

    private void toggleBar() {
        if (customView != null) return;
        showBar(!barShown());
    }

    // ---------- Remote control ----------

    private void updateCursorVisibility() {
        boolean on = app.cursor && customView == null && !barShown();
        cursor.setVisibility(on ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideCursor);
        if (on) ui.postDelayed(hideCursor, 6000);
    }

    private void pokeCursor() {
        if (cursor.getVisibility() != View.VISIBLE) cursor.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideCursor);
        ui.postDelayed(hideCursor, 6000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;

        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            handleBack(e);
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO
                || k == KeyEvent.KEYCODE_GUIDE || k == KeyEvent.KEYCODE_SETTINGS) {
            if (up) toggleBar();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY
                || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down && e.getRepeatCount() == 0) js(JS_PLAY_PAUSE);
            return true;
        }
        if (k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (down) seek(k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 10 : -10);
            return true;
        }

        // Full-screen video: OK = play/pause, left/right = seek 10s.
        if (customView != null) {
            if (isOk(k)) {
                if (down && e.getRepeatCount() == 0) js(JS_PLAY_PAUSE);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) seek(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (barShown()) return super.dispatchKeyEvent(e);

        if (k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_PAGE_UP) {
            if (down) scrollAt(0, -0.8f);
            return true;
        }
        if (k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (down) scrollAt(0, 0.8f);
            return true;
        }

        if (app.cursor && handleCursorKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    private void seek(int seconds) {
        js("(function(){var v=document.querySelector('video');if(v)v.currentTime=Math.max(0,v.currentTime+("
                + seconds + "));})()");
    }

    private void handleBack(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                backLongDone = false;
            } else if (!backLongDone && (e.isLongPress() || e.getEventTime() - e.getDownTime() >= 600)) {
                backLongDone = true;
                if (customView == null) showBar(true);
            }
            return;
        }
        if (e.getAction() != KeyEvent.ACTION_UP || backLongDone || e.isCanceled()) return;

        if (customView != null) {
            exitFullscreen();
        } else if (barShown()) {
            showBar(false);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    private boolean handleCursorKey(KeyEvent e) {
        int k = e.getKeyCode();
        int action = e.getAction();

        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                pokeCursor();
                float step = dp(9) + Math.min(e.getRepeatCount(), 25) * dp(2.2f);
                float dx = 0, dy = 0;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) dx = -step;
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) dx = step;
                if (k == KeyEvent.KEYCODE_DPAD_UP) dy = -step;
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) dy = step;
                moveCursor(dx, dy, e.getRepeatCount());
            }
            return true;
        }

        if (isOk(k)) {
            pokeCursor();
            // Press on key-down, release on key-up: a short press is a tap, holding OK is a long-press.
            if (action == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                touchDownTime = SystemClock.uptimeMillis();
                sendTouch(MotionEvent.ACTION_DOWN, touchDownTime);
                cursor.setDown(true);
            } else if (action == KeyEvent.ACTION_UP) {
                sendTouch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
                cursor.setDown(false);
            }
            return true;
        }
        return false;
    }

    private void moveCursor(float dx, float dy, int repeat) {
        float w = root.getWidth(), h = root.getHeight();
        float nx = cx + dx, ny = cy + dy;
        float edge = 0.10f + Math.min(repeat, 20) * 0.01f;

        // Pushing against an edge scrolls the page (or the scrollable box under the cursor).
        if (nx < 0) {
            nx = 0;
            scrollAt(-edge, 0);
        } else if (nx > w - 1) {
            nx = w - 1;
            scrollAt(edge, 0);
        }
        if (ny < 0) {
            ny = 0;
            scrollAt(0, -edge);
        } else if (ny > h - 1) {
            ny = h - 1;
            scrollAt(0, edge);
        }
        cx = nx;
        cy = ny;
        cursor.moveTo(cx, cy);
    }

    private void sendTouch(int action, long eventTime) {
        MotionEvent ev = MotionEvent.obtain(touchDownTime, eventTime, action, cx, cy, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(ev);
        ev.recycle();
    }

    /** Scrolls by a fraction of the viewport, preferring the scrollable element under the cursor. */
    private void scrollAt(float fdx, float fdy) {
        float w = Math.max(1, root.getWidth()), h = Math.max(1, root.getHeight());
        js("(function(fx,fy,dx,dy){var W=innerWidth,H=innerHeight;dx*=W;dy*=H;"
                + "var e=document.elementFromPoint(fx*W,fy*H);"
                + "while(e&&e!==document.body&&e!==document.documentElement){"
                + "var s=getComputedStyle(e);"
                + "if((dy&&e.scrollHeight>e.clientHeight+1&&/(auto|scroll|overlay)/.test(s.overflowY))"
                + "||(dx&&e.scrollWidth>e.clientWidth+1&&/(auto|scroll|overlay)/.test(s.overflowX))){"
                + "var t=e.scrollTop,l=e.scrollLeft;e.scrollBy(dx,dy);"
                + "if(e.scrollTop!==t||e.scrollLeft!==l)return;}"
                + "e=e.parentElement;}"
                + "window.scrollBy(dx,dy);})("
                + (cx / w) + "," + (cy / h) + "," + fdx + "," + fdy + ")");
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
