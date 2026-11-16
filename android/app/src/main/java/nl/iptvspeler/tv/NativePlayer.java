package nl.iptvspeler.tv;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.util.List;

/**
 * De ingebouwde speler van Android (Media3 / ExoPlayer).
 * Herkent zelf het formaat aan de inhoud (HLS, MPEG-TS, MKV, MP4, ...), dus niet alleen aan de naam.
 * Geluid dat het toestel zelf niet kan (AC3, E-AC3, DTS, TrueHD) wordt door FFmpeg gedecodeerd.
 * Het beeld komt op een SurfaceView onder de doorzichtige WebView.
 * JavaScript gebruikt dit als window.IBSPlayer (zelfde functies als eerst).
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NativePlayer {
    private final Activity act;
    private final SurfaceView surface;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer exo;

    // Toestand, gelezen vanuit JavaScript via state().
    private volatile String state = "idle"; // idle, preparing, playing, paused, ended, error
    private volatile String error = "";
    private volatile int width, height;
    private volatile long durationMs, positionMs;
    private volatile boolean buffering;
    private volatile String subText = "";
    private volatile String tracksJson = "[]";
    private volatile int audioSel = -1;
    private volatile int session;
    private boolean started;
    private int pendingAudio = -1;

    // Plaats van het beeld (telefoon: venster bovenin). Leeg = hele scherm.
    private float boxX, boxY, boxW, boxH, boxVw;

    public NativePlayer(Activity act, SurfaceView surface) {
        this.act = act;
        this.surface = surface;
        main.post(tick);
    }

    /** Positie bijhouden (ExoPlayer mag alleen op de hoofdthread gevraagd worden). */
    private final Runnable tick = new Runnable() {
        public void run() {
            if (exo != null) {
                positionMs = Math.max(0, exo.getCurrentPosition());
                long d = exo.getDuration();
                durationMs = d == C.TIME_UNSET || exo.isCurrentMediaItemLive() ? 0 : Math.max(0, d);
            }
            main.postDelayed(this, 250);
        }
    };

    /* ---------- Beeld ---------- */

    private void fit() {
        View parent = (View) surface.getParent();
        if (parent == null || width <= 0 || height <= 0) return;
        int pw = parent.getWidth(), ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) return;
        float bx = 0, by = 0, bw = pw, bh = ph;
        if (boxW > 0 && boxH > 0 && boxVw > 0) {
            float k = pw / boxVw;
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
        if (exo != null) {
            try { exo.release(); } catch (Exception ignored) {}
            exo = null;
        }
    }

    private void open(String url, long startMs, int audioIdx) {
        release();
        final int my = ++session;
        state = "preparing"; error = ""; buffering = true; subText = ""; tracksJson = "[]";
        width = 0; height = 0; durationMs = 0; positionMs = 0; audioSel = -1; started = false; pendingAudio = audioIdx;
        surface.setVisibility(View.VISIBLE);
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(ProxyCore.UA)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000);
            // Formaat aan de inhoud herkennen; ook losse .ts-streams zonder keyframe-markering
            DefaultExtractorsFactory ex = new DefaultExtractorsFactory()
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                    .setConstantBitrateSeekingEnabled(true);
            DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(http, ex);
            DefaultRenderersFactory rf = new DefaultRenderersFactory(act)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .setEnableDecoderFallback(true);
            exo = new ExoPlayer.Builder(act, rf).setMediaSourceFactory(msf).build();
            exo.setVideoSurfaceView(surface);
            exo.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int s) {
                    if (my != session) return;
                    if (s == Player.STATE_BUFFERING) buffering = true;
                    else if (s == Player.STATE_READY) {
                        buffering = false;
                        if (!started) { started = true; if (pendingAudio >= 0) selectTrackSafe(pendingAudio); }
                        state = exo.getPlayWhenReady() ? "playing" : "paused";
                    } else if (s == Player.STATE_ENDED) { buffering = false; state = "ended"; }
                }
                @Override public void onPlayWhenReadyChanged(boolean pwr, int reason) {
                    if (my != session || exo == null) return;
                    if (exo.getPlaybackState() == Player.STATE_READY) state = pwr ? "playing" : "paused";
                }
                @Override public void onPlayerError(PlaybackException e) {
                    if (my != session) return;
                    // Live stream die even hapert: opnieuw aanhaken in plaats van stoppen
                    if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && exo != null) {
                        exo.seekToDefaultPosition(); exo.prepare(); return;
                    }
                    state = "error"; error = e.getErrorCodeName(); buffering = false;
                }
                @Override public void onVideoSizeChanged(VideoSize v) {
                    if (v.width > 0 && v.height > 0) {
                        width = Math.round(v.width * (v.pixelWidthHeightRatio > 0 ? v.pixelWidthHeightRatio : 1f));
                        height = v.height; fit();
                    }
                }
                @Override public void onTracksChanged(Tracks t) { if (my == session) readTracks(t); }
                @Override public void onCues(CueGroup g) {
                    StringBuilder sb = new StringBuilder();
                    for (Cue c : g.cues) if (c.text != null) { if (sb.length() > 0) sb.append('\n'); sb.append(c.text); }
                    subText = sb.toString();
                }
            });
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)), startMs > 0 ? startMs : C.TIME_UNSET);
            exo.setPlayWhenReady(true);
            exo.prepare();
        } catch (Exception e) {
            state = "error"; error = String.valueOf(e.getMessage()); buffering = false;
        }
    }

    /* ---------- Geluid en ondertitels ----------
       Nummer van een spoor: groep * 1000 + spoor. Ondertitels krijgen er 100000 bij. */

    private void readTracks(Tracks t) {
        StringBuilder sb = new StringBuilder("[");
        int sel = -1;
        List<Tracks.Group> groups = t.getGroups();
        for (int g = 0; g < groups.size(); g++) {
            Tracks.Group grp = groups.get(g);
            int type = grp.getType();
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < grp.length; i++) {
                if (!grp.isTrackSupported(i)) continue;
                Format f = grp.getTrackFormat(i);
                int id = g * 1000 + i + (type == C.TRACK_TYPE_TEXT ? 100000 : 0);
                String name = f.label != null ? f.label : (f.language != null ? f.language : "");
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 2 && f.label == null) name = name + " " + (f.channelCount == 6 ? "5.1" : f.channelCount + " kanalen");
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"i\":").append(id).append(",\"kind\":\"").append(type == C.TRACK_TYPE_AUDIO ? "audio" : "text")
                  .append("\",\"lang\":\"").append(esc(name.trim())).append("\"}");
                if (type == C.TRACK_TYPE_AUDIO && grp.isTrackSelected(i)) sel = id;
            }
        }
        tracksJson = sb.append(']').toString();
        audioSel = sel;
        // Wel beeld in de stream, maar geen enkel videospoor dat dit toestel kan tonen? Dan als fout melden,
        // zodat de app een ander formaat probeert in plaats van alleen geluid te spelen.
        boolean hasVideo = false, videoOk = false;
        for (Tracks.Group grp : groups) if (grp.getType() == C.TRACK_TYPE_VIDEO) { hasVideo = true; if (grp.isSupported()) videoOk = true; }
        if (hasVideo && !videoOk) { state = "error"; error = "VIDEO_UNSUPPORTED"; buffering = false; if (exo != null) exo.stop(); }
    }

    private void selectTrackSafe(int id) {
        if (exo == null) return;
        try {
            boolean text = id >= 100000;
            int raw = text ? id - 100000 : id, g = raw / 1000, i = raw % 1000;
            List<Tracks.Group> groups = exo.getCurrentTracks().getGroups();
            if (g >= groups.size()) return;
            Tracks.Group grp = groups.get(g);
            exo.setTrackSelectionParameters(exo.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(grp.getType(), false)
                    .setOverrideForType(new TrackSelectionOverride(grp.getMediaTrackGroup(), i))
                    .build());
        } catch (Exception ignored) {}
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
        ui(new Runnable() { public void run() { open(url, Math.round(startSec * 1000), audioIdx); } });
    }
    @JavascriptInterface public void pause() {
        ui(new Runnable() { public void run() { if (exo != null) { exo.setPlayWhenReady(false); state = "paused"; } } });
    }
    @JavascriptInterface public void resume() {
        ui(new Runnable() { public void run() {
            if (exo == null) return;
            if ("ended".equals(state)) exo.seekTo(0);
            if (exo.isCurrentMediaItemLive()) exo.seekToDefaultPosition();   // live na pauze: terug naar nu
            exo.setPlayWhenReady(true); state = "playing";
        } });
    }
    @JavascriptInterface public void seek(final double sec) {
        ui(new Runnable() { public void run() {
            if (exo == null || "preparing".equals(state)) return;
            long ms = Math.round(Math.max(0, sec) * 1000);
            if (durationMs > 0) ms = Math.min(ms, Math.max(0, durationMs - 1500));
            buffering = true; subText = "";
            exo.seekTo(ms);
            if ("ended".equals(state)) { exo.setPlayWhenReady(true); state = "playing"; }
        } });
    }
    @JavascriptInterface public void selectTrack(final int i) {
        ui(new Runnable() { public void run() { selectTrackSafe(i); } });
    }
    @JavascriptInterface public void deselectTrack(final int i) {
        ui(new Runnable() { public void run() {
            if (exo != null) exo.setTrackSelectionParameters(exo.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            subText = "";
        } });
    }
    @JavascriptInterface public void stop() {
        ui(new Runnable() { public void run() {
            session++;
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
        boolean live = "idle".equals(state) ? false : durationMs == 0;
        return "{\"state\":\"" + state + "\",\"t\":" + (positionMs / 1000.0) + ",\"d\":" + (durationMs / 1000.0)
                + ",\"w\":" + width + ",\"h\":" + height + ",\"buffering\":" + buffering
                + ",\"audioSel\":" + audioSel + ",\"tracks\":" + tracksJson + ",\"live\":" + live
                + ",\"sub\":\"" + esc(subText) + "\",\"error\":\"" + esc(error) + "\"}";
    }

    /** Bij het verlaten van de app. */
    public void onPause() {
        ui(new Runnable() { public void run() {
            if (exo != null && exo.getPlayWhenReady()) { exo.setPlayWhenReady(false); state = "paused"; }
        } });
    }
    public void destroy() { session++; main.removeCallbacks(tick); release(); }
}
