package nl.iptvspeler.tv;

import android.app.Activity;
import android.media.MediaPlayer;
import android.media.TimedText;
import android.net.Uri;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.Map;

/**
 * De ingebouwde videospeler van de tv (Android MediaPlayer).
 * Speelt films en afleveringen af die de WebView zelf niet kan tonen,
 * zoals MKV, H.265 en Dolby-geluid. Het beeld komt op een SurfaceView
 * onder de doorzichtige WebView, zodat de IBStv-bediening erover blijft.
 *
 * JavaScript gebruikt dit als window.IBSPlayer.
 */
public final class NativePlayer implements SurfaceHolder.Callback {
    private final Activity act;
    private final SurfaceView surface;
    private MediaPlayer mp;
    private boolean surfaceReady;

    // Toestand, gelezen vanuit JavaScript via state().
    private volatile String state = "idle"; // idle, preparing, playing, paused, ended, error
    private volatile String error = "";
    private volatile int width, height;
    private volatile int duration;          // ms
    private volatile boolean buffering;
    private volatile String subText = "";
    private volatile String tracksJson = "[]";
    private volatile int session;

    // Plaats van het beeld (telefoon: venster bovenin). Leeg = hele scherm.
    private float boxX, boxY, boxW, boxH, boxVw;

    private String pendingUrl;
    private int pendingStartMs;
    private int pendingAudio = -1;

    public NativePlayer(Activity act, SurfaceView surface) {
        this.act = act;
        this.surface = surface;
        surface.getHolder().addCallback(this);
    }

    /* ---------- Oppervlak ---------- */

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (mp != null) mp.setDisplay(h);
        if (pendingUrl != null) {
            String u = pendingUrl; pendingUrl = null;
            open(u, pendingStartMs, pendingAudio);
        }
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { fit(); }
    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        if (mp != null) { try { mp.setDisplay(null); } catch (Exception ignored) {} }
    }

    /** Beeld passend in het scherm, met zwarte balken waar nodig. */
    private void fit() {
        View parent = (View) surface.getParent();
        if (parent == null || width <= 0 || height <= 0) return;
        int pw = parent.getWidth(), ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) return;
        float bx = 0, by = 0, bw = pw, bh = ph;
        if (boxW > 0 && boxH > 0 && boxVw > 0) {
            float k = pw / boxVw;              // CSS-pixels naar schermpixels
            bx = boxX * k; by = boxY * k; bw = boxW * k; bh = boxH * k;
        }
        float scale = Math.min(bw / (float) width, bh / (float) height);
        int w = Math.round(width * scale), h = Math.round(height * scale);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = Math.round(bx + (bw - w) / 2f);
        lp.topMargin = Math.round(by + (bh - h) / 2f);
        surface.setLayoutParams(lp);
    }

    /* ---------- Afspelen ---------- */

    private void release() {
        if (mp != null) {
            try { mp.reset(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
    }

    private void open(final String url, final int startMs, final int audioIdx) {
        release();
        final int my = ++session;
        state = "preparing"; error = ""; buffering = true; subText = ""; tracksJson = "[]";
        width = 0; height = 0; duration = 0;
        surface.setVisibility(View.VISIBLE);
        if (!surfaceReady) { pendingUrl = url; pendingStartMs = startMs; pendingAudio = audioIdx; return; }
        try {
            mp = new MediaPlayer();
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", ProxyCore.UA);
            mp.setDataSource(act, Uri.parse(url), headers);
            mp.setDisplay(surface.getHolder());
            mp.setScreenOnWhilePlaying(true);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    if (my != session) return;
                    duration = Math.max(0, p.getDuration());
                    readTracks();
                    if (audioIdx >= 0) selectTrackSafe(audioIdx);
                    if (startMs > 0) p.seekTo(startMs);
                    p.start();
                    state = "playing"; buffering = false;
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(MediaPlayer p, int w, int h) { width = w; height = h; fit(); }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer p) { if (my == session && !"error".equals(state)) state = "ended"; }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer p, int what, int extra) {
                    if (my != session) return true;
                    state = "error"; error = what + "/" + extra; buffering = false;
                    return true;
                }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                public boolean onInfo(MediaPlayer p, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true;
                    else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) buffering = false;
                    return false;
                }
            });
            mp.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                public void onSeekComplete(MediaPlayer p) { buffering = false; }
            });
            mp.setOnTimedTextListener(new MediaPlayer.OnTimedTextListener() {
                public void onTimedText(MediaPlayer p, TimedText t) { subText = t == null || t.getText() == null ? "" : t.getText(); }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            state = "error"; error = String.valueOf(e.getMessage()); buffering = false;
        }
    }

    private void readTracks() {
        StringBuilder sb = new StringBuilder("[");
        try {
            MediaPlayer.TrackInfo[] ti = mp.getTrackInfo();
            for (int i = 0; i < ti.length; i++) {
                int type = ti[i].getTrackType();
                String kind = type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO ? "audio"
                        : type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT ? "text" : null;
                if (kind == null) continue;
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"i\":").append(i).append(",\"kind\":\"").append(kind)
                  .append("\",\"lang\":\"").append(esc(ti[i].getLanguage())).append("\"}");
            }
        } catch (Exception ignored) {}
        tracksJson = sb.append(']').toString();
    }

    private void selectTrackSafe(int i) {
        try { if (mp != null) mp.selectTrack(i); } catch (Exception ignored) {}
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') continue;
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private void ui(Runnable r) { act.runOnUiThread(r); }

    /* ---------- Voor JavaScript ---------- */

    @JavascriptInterface public void play(final String url, final double startSec, final int audioIdx) {
        ui(new Runnable() { public void run() { open(url, (int) Math.round(startSec * 1000), audioIdx); } });
    }
    @JavascriptInterface public void pause() {
        ui(new Runnable() { public void run() {
            if (mp != null && "playing".equals(state)) { try { mp.pause(); state = "paused"; } catch (Exception ignored) {} }
        } });
    }
    @JavascriptInterface public void resume() {
        ui(new Runnable() { public void run() {
            if (mp != null && ("paused".equals(state) || "ended".equals(state))) { try { mp.start(); state = "playing"; } catch (Exception ignored) {} }
        } });
    }
    @JavascriptInterface public void seek(final double sec) {
        ui(new Runnable() { public void run() {
            if (mp == null || "preparing".equals(state)) return;
            try {
                int ms = (int) Math.round(Math.max(0, sec) * 1000);
                if (duration > 0) ms = Math.min(ms, Math.max(0, duration - 1500));
                buffering = true; subText = "";
                mp.seekTo(ms);
                if ("ended".equals(state)) { mp.start(); state = "playing"; }
            } catch (Exception ignored) {}
        } });
    }
    @JavascriptInterface public void selectTrack(final int i) {
        ui(new Runnable() { public void run() { selectTrackSafe(i); } });
    }
    @JavascriptInterface public void deselectTrack(final int i) {
        ui(new Runnable() { public void run() {
            try { if (mp != null) mp.deselectTrack(i); } catch (Exception ignored) {}
            subText = "";
        } });
    }
    @JavascriptInterface public void stop() {
        ui(new Runnable() { public void run() {
            session++; pendingUrl = null;
            release();
            state = "idle"; buffering = false; subText = "";
            surface.setVisibility(View.GONE);
        } });
    }
    /** Telefoon: beeld in een rechthoek van de pagina (CSS-pixels). Breedte 0 = hele scherm. */
    @JavascriptInterface public void layout(final double x, final double y, final double w, final double h, final double vw) {
        ui(new Runnable() { public void run() {
            boxX = (float) x; boxY = (float) y; boxW = (float) w; boxH = (float) h; boxVw = (float) vw;
            fit();
        } });
    }
    @JavascriptInterface public String state() {
        int pos = 0, sel = -1;
        MediaPlayer p = mp;
        if (p != null && !"preparing".equals(state) && !"error".equals(state)) {
            try { pos = p.getCurrentPosition(); } catch (Exception ignored) {}
            try { sel = p.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO); } catch (Throwable ignored) {}
        }
        return "{\"state\":\"" + state + "\",\"t\":" + (pos / 1000.0) + ",\"d\":" + (duration / 1000.0)
                + ",\"w\":" + width + ",\"h\":" + height + ",\"buffering\":" + buffering
                + ",\"audioSel\":" + sel + ",\"tracks\":" + tracksJson
                + ",\"sub\":\"" + esc(subText) + "\",\"error\":\"" + esc(error) + "\"}";
    }

    /** Bij het verlaten van de app. */
    public void onPause() {
        if (mp != null && "playing".equals(state)) { try { mp.pause(); state = "paused"; } catch (Exception ignored) {} }
    }
    public void destroy() { session++; release(); }
}
