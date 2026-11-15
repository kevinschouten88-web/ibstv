package nl.iptvspeler.tv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Haalt verzoeken van de speler op bij de IPTV-server (zonder CORS-problemen),
 * volgt doorverwijzingen en herschrijft HLS-playlists zodat ook de stukjes
 * via de app lopen. Puur Java, zodat het ook buiten Android te testen is.
 */
public final class ProxyCore {
    public static final String UA = "VLC/3.0.20 LibVLC/3.0.20";
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private static SSLSocketFactory trustAll;
    private static final HostnameVerifier ANY_HOST = new HostnameVerifier() {
        public boolean verify(String h, SSLSession s) { return true; }
    };

    public static final class Result {
        public int status;
        public String reason;
        public String mime;
        public String charset;
        public final Map<String, String> headers = new HashMap<String, String>();
        public InputStream body;
    }

    private ProxyCore() {}

    /** Veel IPTV-servers hebben verlopen of zelfondertekende certificaten. */
    private static synchronized SSLSocketFactory trustAllFactory() throws IOException {
        if (trustAll == null) {
            try {
                TrustManager[] tm = new TrustManager[] { new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                } };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tm, new SecureRandom());
                trustAll = ctx.getSocketFactory();
            } catch (Exception e) {
                throw new IOException(e.getMessage());
            }
        }
        return trustAll;
    }

    public static Result fetch(String url, String range, String proxyBase) throws IOException {
        String current = url;
        HttpURLConnection conn = null;
        int code = 0;
        for (int hop = 0; hop < 7; hop++) {
            URL u = new URL(current);
            String proto = u.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) throw new IOException("Ongeldig adres");
            conn = (HttpURLConnection) u.openConnection();
            if (conn instanceof HttpsURLConnection) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(trustAllFactory());
                ((HttpsURLConnection) conn).setHostnameVerifier(ANY_HOST);
            }
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (range != null && range.length() > 0) conn.setRequestProperty("Range", range);
            code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new IOException("Doorverwijzing zonder adres");
                current = new URL(u, loc).toString();
                continue;
            }
            break;
        }
        if (code >= 300 && code < 400) throw new IOException("Te veel doorverwijzingen");

        final HttpURLConnection c = conn;
        InputStream in;
        try {
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        } catch (IOException e) {
            in = c.getErrorStream();
        }
        if (in == null) in = new ByteArrayInputStream(new byte[0]);

        String ctype = c.getContentType();
        String path = new URL(current).getPath().toLowerCase();
        boolean playlist = (ctype != null && ctype.toLowerCase().contains("mpegurl"))
                || path.endsWith(".m3u8") || path.endsWith(".m3u");

        Result r = new Result();
        r.status = code;
        r.reason = reason(code);
        r.headers.put("Cache-Control", "no-store");
        r.headers.put("Access-Control-Allow-Origin", "*");

        if (playlist) {
            byte[] body = readAll(in, 8 * 1024 * 1024);
            c.disconnect();
            String text = new String(body, "UTF-8");
            String mime = mimeOf(ctype, "application/octet-stream");
            if (text.trim().startsWith("#EXTM3U")) {
                body = rewrite(text, current, proxyBase).getBytes("UTF-8");
                mime = "application/vnd.apple.mpegurl";
            }
            r.mime = mime;
            r.charset = "utf-8";
            r.headers.put("Content-Length", String.valueOf(body.length));
            if (r.status == 206) { r.status = 200; r.reason = "OK"; }
            r.body = new ByteArrayInputStream(body);
            return r;
        }

        r.mime = mimeOf(ctype, "application/octet-stream");
        r.charset = charsetOf(ctype);
        copyHeader(c, r, "Content-Length");
        copyHeader(c, r, "Content-Range");
        copyHeader(c, r, "Accept-Ranges");
        r.body = new FilterInputStream(in) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { c.disconnect(); }
            }
        };
        return r;
    }

    static String rewrite(String text, String base, String proxyBase) throws IOException {
        StringBuilder sb = new StringBuilder();
        URL b = new URL(base);
        for (String line : text.split("\r\n|\n|\r")) {
            String s = line.trim();
            if (s.length() == 0) { sb.append('\n'); continue; }
            if (s.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(s);
                StringBuffer out = new StringBuffer();
                while (m.find()) {
                    String abs = new URL(b, m.group(1)).toString();
                    m.appendReplacement(out, Matcher.quoteReplacement("URI=\"" + proxied(abs, proxyBase) + "\""));
                }
                m.appendTail(out);
                sb.append(out);
            } else {
                sb.append(proxied(new URL(b, s).toString(), proxyBase));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String proxied(String url, String proxyBase) throws IOException {
        return proxyBase + URLEncoder.encode(url, "UTF-8");
    }

    private static void copyHeader(HttpURLConnection c, Result r, String name) {
        String v = c.getHeaderField(name);
        if (v != null) r.headers.put(name, v);
    }

    static String mimeOf(String ctype, String def) {
        if (ctype == null || ctype.trim().length() == 0) return def;
        int i = ctype.indexOf(';');
        return (i >= 0 ? ctype.substring(0, i) : ctype).trim();
    }

    static String charsetOf(String ctype) {
        if (ctype == null) return null;
        int i = ctype.toLowerCase().indexOf("charset=");
        return i >= 0 ? ctype.substring(i + 8).replace("\"", "").trim() : null;
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) break;
            }
        } finally {
            in.close();
        }
        return bo.toByteArray();
    }

    public static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Status " + code;
        }
    }
}
