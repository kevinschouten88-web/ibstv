package nl.iptvspeler.tv;

import android.app.Activity;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Casten naar een Chromecast (of een tv met Chromecast ingebouwd).
 * Zoekt apparaten op het wifi-netwerk en stuurt zender of film naar de
 * standaard-ontvanger van Google. JavaScript gebruikt dit als window.IBSCast.
 */
public final class CastBridge {
    private final Activity act;
    private final CastContext ctx;
    private final MediaRouter router;
    private final MediaRouteSelector selector;
    private volatile String devJson = "[]";
    private volatile String curJson = "";
    private MediaLoadRequestData pending;

    /** Geeft null als casten op dit toestel niet kan (bijvoorbeeld zonder Google Play-services). */
    public static CastBridge create(Activity act) {
        try { return new CastBridge(act); } catch (Throwable t) { return null; }
    }

    private CastBridge(Activity act) {
        this.act = act;
        ctx = CastContext.getSharedInstance(act);
        router = MediaRouter.getInstance(act);
        selector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build();
        ctx.getSessionManager().addSessionManagerListener(sessions, CastSession.class);
        router.addCallback(selector, routes, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
        update();
    }

    /* ---------- Apparaten ---------- */

    private final MediaRouter.Callback routes = new MediaRouter.Callback() {
        @Override public void onRouteAdded(MediaRouter r, MediaRouter.RouteInfo i) { update(); }
        @Override public void onRouteRemoved(MediaRouter r, MediaRouter.RouteInfo i) { update(); }
        @Override public void onRouteChanged(MediaRouter r, MediaRouter.RouteInfo i) { update(); }
        @Override public void onRouteSelected(MediaRouter r, MediaRouter.RouteInfo i, int reason) { update(); }
        @Override public void onRouteUnselected(MediaRouter r, MediaRouter.RouteInfo i, int reason) { update(); }
    };

    private boolean isCast(MediaRouter.RouteInfo r) {
        return r != null && !r.isDefault() && r.isEnabled() && r.matchesSelector(selector);
    }

    private void update() {
        try {
            JSONArray a = new JSONArray();
            for (MediaRouter.RouteInfo r : router.getRoutes()) {
                if (!isCast(r)) continue;
                a.put(new JSONObject().put("id", r.getId()).put("name", String.valueOf(r.getName())).put("kind", "chromecast"));
            }
            devJson = a.toString();
            MediaRouter.RouteInfo s = router.getSelectedRoute();
            CastSession cs = ctx.getSessionManager().getCurrentCastSession();
            if (isCast(s) && cs != null && (cs.isConnected() || cs.isConnecting()))
                curJson = new JSONObject().put("id", s.getId()).put("name", String.valueOf(s.getName())).put("kind", "chromecast").toString();
            else curJson = "";
        } catch (Exception ignored) {}
    }

    /* ---------- Sessies ---------- */

    private final SessionManagerListener<CastSession> sessions = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarted(CastSession s, String id) { update(); sendPending(s); }
        @Override public void onSessionResumed(CastSession s, boolean wasSuspended) { update(); sendPending(s); }
        @Override public void onSessionEnded(CastSession s, int error) { pending = null; update(); }
        @Override public void onSessionStartFailed(CastSession s, int error) { pending = null; update(); }
        @Override public void onSessionStarting(CastSession s) {}
        @Override public void onSessionEnding(CastSession s) {}
        @Override public void onSessionResuming(CastSession s, String id) {}
        @Override public void onSessionResumeFailed(CastSession s, int error) { update(); }
        @Override public void onSessionSuspended(CastSession s, int reason) { update(); }
    };

    private void sendPending(CastSession s) {
        if (pending == null || s == null) return;
        RemoteMediaClient c = s.getRemoteMediaClient();
        if (c == null) return;
        c.load(pending);
        pending = null;
    }

    private static String typeOf(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        int q = u.indexOf('?'); if (q > 0) u = u.substring(0, q);
        if (u.endsWith(".m3u8")) return "application/x-mpegURL";
        if (u.endsWith(".mkv")) return "video/x-matroska";
        if (u.endsWith(".webm")) return "video/webm";
        if (u.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }

    private void ui(Runnable r) { act.runOnUiThread(r); }

    /* ---------- Voor JavaScript ---------- */

    @JavascriptInterface public String devices() { return devJson; }
    @JavascriptInterface public String current() { return curJson; }

    @JavascriptInterface public void connect(final String id) {
        ui(new Runnable() { public void run() {
            for (MediaRouter.RouteInfo r : router.getRoutes()) if (r.getId().equals(id)) { router.selectRoute(r); break; }
            update();
        } });
    }

    @JavascriptInterface public void cast(final String url, final String title, final String img, final boolean live, final double startSec) {
        ui(new Runnable() { public void run() {
            MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
            md.putString(MediaMetadata.KEY_TITLE, title == null ? "" : title);
            if (img != null && img.startsWith("http")) md.addImage(new WebImage(Uri.parse(img)));
            MediaInfo info = new MediaInfo.Builder(url)
                    .setStreamType(live ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(typeOf(url))
                    .setMetadata(md)
                    .build();
            pending = new MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
                    .setCurrentTime(live ? 0 : (long) (startSec * 1000)).build();
            CastSession s = ctx.getSessionManager().getCurrentCastSession();
            if (s != null && s.isConnected()) sendPending(s);
        } });
    }

    @JavascriptInterface public void stop() {
        ui(new Runnable() { public void run() {
            pending = null;
            SessionManager sm = ctx.getSessionManager();
            sm.endCurrentSession(true);
            router.unselect(MediaRouter.UNSELECT_REASON_STOPPED);
            update();
        } });
    }

    /* ---------- Levenscyclus ---------- */

    void resume() {
        router.addCallback(selector, routes, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
        update();
    }

    void release() {
        try {
            router.removeCallback(routes);
            ctx.getSessionManager().removeSessionManagerListener(sessions, CastSession.class);
        } catch (Exception ignored) {}
    }
}
