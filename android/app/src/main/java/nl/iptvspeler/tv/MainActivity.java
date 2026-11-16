package nl.iptvspeler.tv;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.util.Rational;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.view.WindowInsets;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * IBStv voor Google TV / Android TV en Android-telefoons.
 * Op een tv: liggend, bediening met de afstandsbediening.
 * Op een telefoon: staand en dwars, bediening met de vinger.
 * De speler zelf is dezelfde als op Windows; deze activity toont hem
 * schermvullend, regelt de verbinding met de IPTV-server en geeft de
 * knoppen van de afstandsbediening door.
 */
public class MainActivity extends Activity {
    static final String ORIGIN = "http://iptv.local/";
    static final String PROXY_BASE = ORIGIN + "proxy?url=";
    private WebView web;
    private NativePlayer player;
    private boolean tv;
    static final String VERSION = "1.3.0";
    private CastBridge cast;
    // Beeld in beeld: speelt er iets, en in welke verhouding?
    private volatile boolean playing;
    private volatile int vidW = 16, vidH = 9;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        tv = isTv();
        Window win = getWindow();
        if (tv) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            win.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            win.setStatusBarColor(Color.rgb(8, 11, 24));
            win.setNavigationBarColor(Color.rgb(8, 11, 24));
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            setRequestedOrientation(browseOrientation());
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + (tv ? " IBStvTV/" : " IBStvPhone/") + VERSION);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (!url.startsWith(ORIGIN)) return null;   // zenderlogo's en posters gewoon laden
                return handle(req);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return true;
                if (url.startsWith(ORIGIN)) return false;
                // Links naar buiten (privacybeleid, supportpagina) in de browser openen
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
                }
                return true;
            }
        });

        // Onder de WebView: het beeld van de ingebouwde speler van de tv.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(tv ? Color.BLACK : Color.rgb(8, 11, 24));
        if (!tv) {
            // Android 15+ tekent apps tot in de randen: ruimte houden voor de statusbalk, navigatiebalk en het toetsenbord.
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @SuppressWarnings("deprecation")
                public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                            in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                    return in.consumeSystemWindowInsets();
                }
            });
        }
        SurfaceView surface = new SurfaceView(this);
        surface.setVisibility(View.GONE);
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        player = new NativePlayer(this, surface);
        web.addJavascriptInterface(player, "IBSPlayer");
        web.addJavascriptInterface(new AppBridge(), "IBSApp");
        if (!tv) {
            cast = CastBridge.create(this);
            if (cast != null) web.addJavascriptInterface(cast, "IBSCast");
        }
        setContentView(root);
        web.requestFocus(View.FOCUS_DOWN);
        if (state == null || web.restoreState(state) == null) web.loadUrl(ORIGIN);
    }

    private boolean isTv() {
        UiModeManager m = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature("android.software.leanback") || pm.hasSystemFeature("android.hardware.type.television");
    }

    /** Bladeren: staand op een telefoon, vrij draaien op een tablet. */
    private int browseOrientation() {
        return getResources().getConfiguration().smallestScreenWidthDp < 600
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
    }

    /** Voor JavaScript als window.IBSApp (alleen nodig op een telefoon). */
    public final class AppBridge {
        @android.webkit.JavascriptInterface public void immersive(final boolean on) {
            if (tv) return;
            runOnUiThread(new Runnable() { public void run() {
                View d = getWindow().getDecorView();
                if (on) {
                    d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    // Kijken: meteen liggend (beide kanten op, met de sensor)
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                } else {
                    setRequestedOrientation(browseOrientation());
                    d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            } });
        }

        /** Speelt er iets? Dan gaat de app bij wegvegen naar beeld in beeld. */
        @android.webkit.JavascriptInterface public void setPlaying(final boolean on, final int w, final int h) {
            if (tv) return;
            playing = on;
            if (w > 0 && h > 0) { vidW = w; vidH = h; }
            runOnUiThread(new Runnable() { public void run() { updatePip(); } });
        }
    }

    /* ---------- Beeld in beeld ---------- */

    private boolean pipSupported() {
        return !tv && Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private PictureInPictureParams pipParams() {
        float r = vidW / (float) vidH;
        int w = vidW, h = vidH;
        if (r > 2.39f) { w = 239; h = 100; } else if (r < 1 / 2.39f) { w = 100; h = 239; }
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder().setAspectRatio(new Rational(w, h));
        if (Build.VERSION.SDK_INT >= 31) { b.setAutoEnterEnabled(playing); b.setSeamlessResizeEnabled(true); }
        return b.build();
    }

    private void updatePip() {
        if (!pipSupported()) return;
        try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) {}
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 8 tot en met 11: zelf naar beeld in beeld gaan (vanaf 12 gaat dat vanzelf)
        if (pipSupported() && playing && Build.VERSION.SDK_INT < 31) {
            try { enterPictureInPictureMode(pipParams()); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        if (web != null) web.evaluateJavascript("window.pipMode && pipMode(" + inPip + ")", null);
    }

    private boolean inPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    /* ---------- Ingebouwde server ---------- */

    private WebResourceResponse handle(WebResourceRequest req) {
        Uri u = req.getUrl();
        String path = u.getPath();
        if (path == null || path.length() == 0 || "/".equals(path)) path = "/index.html";
        try {
            if ("/proxy".equals(path)) return proxy(param(u, "url"), header(req, "Range"));
            if ("/status".equals(path))
                return text(200, "application/json", "{\"ffmpeg\":false,\"conversions\":0,\"max\":0}");
            if ("/convert".equals(path)) return text(501, "text/plain", "Omzetten is niet beschikbaar op tv");
            return asset(path);
        } catch (Exception e) {
            return text(502, "text/plain", "IPTV-server niet bereikbaar: " + e.getMessage());
        }
    }

    private WebResourceResponse proxy(String url, String range) throws Exception {
        if (url == null) return text(400, "text/plain", "Ongeldig adres");
        String low = url.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("http://")) url = "http://" + url.substring(7);
        else if (low.startsWith("https://")) url = "https://" + url.substring(8);
        else return text(400, "text/plain", "Ongeldig adres");
        ProxyCore.Result r = ProxyCore.fetch(url, range, PROXY_BASE);
        int status = r.status;
        if (status >= 300 && status < 400) status = 502;
        return new WebResourceResponse(r.mime, r.charset, status, ProxyCore.reason(status), r.headers, r.body);
    }

    private WebResourceResponse asset(String path) {
        String clean = path.replace("..", "");
        String mime = "application/octet-stream";
        if (clean.endsWith(".html")) mime = "text/html";
        else if (clean.endsWith(".js")) mime = "application/javascript";
        else if (clean.endsWith(".woff2")) mime = "font/woff2";
        else if (clean.endsWith(".png")) mime = "image/png";
        else if (clean.endsWith(".svg")) mime = "image/svg+xml";
        try {
            InputStream in = getAssets().open("www" + clean);
            if (tv && clean.equals("/index.html")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                String html = bo.toString("UTF-8").replaceFirst("<meta name=\"viewport\" content=\"[^\"]*\">",
                        "<meta name=\"viewport\" content=\"width=1440\">");
                in = new ByteArrayInputStream(html.getBytes("UTF-8"));
            }
            Map<String, String> h = new HashMap<String, String>();
            h.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, mime.startsWith("text/") || mime.endsWith("javascript") ? "utf-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return text(404, "text/plain", "Niet gevonden");
        }
    }

    private static WebResourceResponse text(int code, String mime, String body) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "no-store");
        try {
            return new WebResourceResponse(mime, "utf-8", code, ProxyCore.reason(code), h,
                    new ByteArrayInputStream(body.getBytes("UTF-8")));
        } catch (Exception e) {
            return null;
        }
    }

    private static String param(Uri u, String name) throws Exception {
        String q = u.getEncodedQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            String k = i >= 0 ? part.substring(0, i) : part;
            if (k.equals(name)) return URLDecoder.decode(i >= 0 ? part.substring(i + 1) : "", "UTF-8");
        }
        return null;
    }

    private static String header(WebResourceRequest req, String name) {
        Map<String, String> h = req.getRequestHeaders();
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    /* ---------- Afstandsbediening ---------- */

    @Override
    public void onBackPressed() {
        String fn = tv ? "(window.tvBack ? tvBack() : false)" : "(window.appBack ? appBack() : false)";
        web.evaluateJavascript(fn, new ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (!"true".equals(v)) finish();
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String k = null;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP: k = "chup"; break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN: k = "chdown"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: k = "playpause"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: k = "ff"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: k = "rew"; break;
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_INFO: k = "menu"; break;
            case KeyEvent.KEYCODE_GUIDE: k = "guide"; break;
            default: break;
        }
        if (k != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0)
                web.evaluateJavascript("window.tvKey && tvKey('" + k + "')", null);
            return true;
        }
        // De middelste knop van het pijlenkruis als Enter doorgeven.
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            KeyEvent enter = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                    KeyEvent.KEYCODE_ENTER, e.getRepeatCount(), e.getMetaState(),
                    e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
            return super.dispatchKeyEvent(enter);
        }
        return super.dispatchKeyEvent(e);
    }

    /* ---------- Levenscyclus ---------- */

    @Override
    protected void onPause() {
        // In beeld in beeld gewoon doorspelen
        if (!inPip()) {
            web.evaluateJavascript("try{video.pause()}catch(e){}", null);
            if (player != null) player.onPause();
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Venster van beeld in beeld weggeveegd: stoppen met afspelen
        if (web != null && !isChangingConfigurations()) {
            web.evaluateJavascript("try{video.pause()}catch(e){}", null);
            if (player != null) player.onPause();
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (cast != null) cast.resume();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (cast != null) cast.release();
        if (web != null) {
            if (player != null) player.destroy();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
