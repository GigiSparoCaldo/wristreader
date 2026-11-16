package com.akemi.wristreader;

import android.app.Activity;
import android.text.Html;
import android.util.Base64;
import android.util.LruCache;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class NetHelper {
    public static final String UA_REDDIT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    public static final String UA_FEED = "Mozilla/5.0 (compatible; WristReader/2.3; +https://github.com/GigiSparoCaldo/wristreader)";
    public static final String UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public interface StatusCallback { void onStatus(String msg); }
    public interface TextFormatter { CharSequence format(String text); }

    public static class RssEntry {
        public String title = "", link = "", summary = "", pubDate = "", imageUrl = "", author = "", rawHtml = "", id = "", parentId = "";
    }

    public static class HttpRes {
        public String body = "", finalUrl = "";
    }

    private static class SniSslSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory base = createCompatFactory();
        private final String sniHost;

        SniSslSocketFactory(String sniHost) { this.sniHost = sniHost; }

        private static SSLSocketFactory createCompatFactory() {
            try {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((KeyStore) null);
                X509TrustManager sysTm = null;
                for (TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager) { sysTm = (X509TrustManager) tm; break; }
                }
                final X509TrustManager delegate = sysTm;
                TrustManager[] tmArr = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
                            if (delegate != null) {
                                try { delegate.checkServerTrusted(c, a); } catch (CertificateException ignored) {}
                            }
                        }
                        @Override public X509Certificate[] getAcceptedIssuers() {
                            return delegate != null ? delegate.getAcceptedIssuers() : new X509Certificate[0];
                        }
                    }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, tmArr, new SecureRandom());
                return sc.getSocketFactory();
            } catch (Exception e) { return (SSLSocketFactory) SSLSocketFactory.getDefault(); }
        }

        private Socket applySni(Socket socket) {
            if (socket instanceof SSLSocket && sniHost != null) return new SniSSLSocket((SSLSocket) socket, sniHost);
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return applySni(base.createSocket()); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return applySni(base.createSocket(s, sniHost, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException { return applySni(base.createSocket(sniHost, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress lh, int lp) throws IOException {
            return applySni(base.createSocket(sniHost, port, lh, lp));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return applySni(base.createSocket(new Socket(host, port), sniHost, port, true));
        }
        @Override public Socket createSocket(InetAddress addr, int port, InetAddress la, int lp) throws IOException {
            return applySni(base.createSocket(new Socket(addr, port, la, lp), sniHost, port, true));
        }
    }

    private final LruCache<String, String> textCache = new LruCache<>(45);
    private final LruCache<String, String> transCache = new LruCache<>(300);
    private final ConcurrentHashMap<String, String> dnsCache = new ConcurrentHashMap<>();
    private final ExecutorService dnsPool = Executors.newCachedThreadPool();
    public final ExecutorService transPool = Executors.newFixedThreadPool(2);
    public volatile int transGen = 0;
    public volatile String redditCookie = "";

    public int nextTransGen() { return ++transGen; }
    private boolean isIpLiteral(String s) { return s != null && s.matches("^\\d{1,3}(\\.\\d{1,3}){3}$"); }

    private String replaceUrlHost(URL u, String ip) {
        String file = u.getFile();
        return u.getProtocol() + "://" + ip + (u.getPort() != -1 ? ":" + u.getPort() : "") + ((file == null || file.isEmpty()) ? "/" : file);
    }

    private String queryDoh(String dohUrl) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(dohUrl).openConnection();
            c.setConnectTimeout(3500); c.setReadTimeout(3500);
            c.setRequestProperty("Accept", "application/dns-json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[2048]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                JSONArray ans = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).optJSONArray("Answer");
                if (ans != null) {
                    for (int i = 0; i < ans.length(); i++) {
                        JSONObject rec = ans.optJSONObject(i);
                        if (rec != null && rec.optInt("type") == 1 && isIpLiteral(rec.optString("data", "").trim())) {
                            return rec.optString("data", "").trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {} finally { if (c != null) c.disconnect(); }
        return null;
    }

    private String resolveHostFast(final String host, StatusCallback cb) {
        String cached = dnsCache.get(host);
        if (cached != null) return cached;
        if (cb != null) cb.onStatus("Risoluzione DNS " + host + "...");
        Future<String> fut = dnsPool.submit(() -> InetAddress.getByName(host).getHostAddress());
        try {
            String ip = fut.get(2500, TimeUnit.MILLISECONDS);
            if (ip != null && !ip.isEmpty()) { dnsCache.put(host, ip); return ip; }
        } catch (Exception ignored) { fut.cancel(true); }
        if (cb != null) cb.onStatus("DNS alternativo (DoH)...");
        String dohIp = queryDoh("https://8.8.8.8/resolve?name=" + host + "&type=A");
        if (dohIp == null) dohIp = queryDoh("https://1.1.1.1/dns-query?name=" + host + "&type=A");
        if (dohIp != null) { dnsCache.put(host, dohIp); return dohIp; }
        return null;
    }

    private HttpURLConnection openConnection(String urlStr, String ua, StatusCallback cb) throws Exception {
        URL u = new URL(urlStr);
        String host = u.getHost(), targetUrl = urlStr;
        boolean useIp = false;
        if (host != null && !host.isEmpty() && !isIpLiteral(host)) {
            String ip = resolveHostFast(host, cb);
            if (ip != null && !ip.equals(host)) { targetUrl = replaceUrlHost(u, ip); useIp = true; }
        }
        if (cb != null) cb.onStatus("Connessione a " + host + "...");
        HttpURLConnection c = (HttpURLConnection) new URL(targetUrl).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(6500); c.setReadTimeout(9000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7");
        if (useIp) c.setRequestProperty("Host", host);
        if (host != null && host.contains("4cdn.org")) c.setRequestProperty("Referer", "https://boards.4chan.org/");
        if (c instanceof HttpsURLConnection && host != null && !host.isEmpty()) {
            HttpsURLConnection hc = (HttpsURLConnection) c;
            hc.setSSLSocketFactory(new SniSslSocketFactory(host));
            hc.setHostnameVerifier((h, s) -> true);
        }
        return c;
    }

    public String getCached(String urlStr, String ua, boolean force) throws Exception { return getCached(urlStr, ua, force, null); }

    public String getCached(String urlStr, String ua, boolean force, StatusCallback cb) throws Exception {
        String key = ua + "|" + urlStr;
        if (!force) { String c = textCache.get(key); if (c != null) return c; }
        String res = getWithUrl(urlStr, ua, cb).body;
        textCache.put(key, res);
        return res;
    }

    public HttpRes getWithUrl(String urlStr, String ua) throws Exception { return getWithUrl(urlStr, ua, null); }

    public HttpRes getWithUrl(String urlStr, String ua, StatusCallback cb) throws Exception {
        String cur = urlStr;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = openConnection(cur, ua, cb);
            if (cur.contains(".json")) c.setRequestProperty("Accept", "application/json");
            else c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            if (cur.contains("google.com")) c.setRequestProperty("Cookie", "CONSENT=YES+cb.20260101-00-p0.it+FX+111");
            else if (cur.contains("reddit.com") && redditCookie != null && !redditCookie.isEmpty()) c.setRequestProperty("Cookie", redditCookie);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location"); c.disconnect();
                if (loc == null) break;
                cur = resolveUrl(cur, loc); continue;
            }
            if (code >= 400) { c.disconnect(); throw new Exception("HTTP " + code); }
            if (cb != null) cb.onStatus("Scaricamento dati...");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                HttpRes rx = new HttpRes();
                rx.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                rx.finalUrl = cur;
                return rx;
            } finally { c.disconnect(); }
        }
        throw new Exception("Troppi redirect");
    }

    public String post(String urlStr, String formBody, String ua) throws Exception { return post(urlStr, formBody, ua, 6500); }

    public String post(String urlStr, String formBody, String ua, int timeoutMs) throws Exception {
        HttpURLConnection c = openConnection(urlStr, ua, null);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        try (OutputStream os = c.getOutputStream()) { os.write(formBody.getBytes(StandardCharsets.UTF_8)); }
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    public byte[] getBytes(String urlStr, String ua) throws Exception {
        String cur = urlStr;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = openConnection(cur, ua, null);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location"); c.disconnect();
                if (loc == null) break;
                cur = resolveUrl(cur, loc); continue;
            }
            if (code >= 400) { c.disconnect(); throw new Exception("HTTP " + code); }
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            } finally { c.disconnect(); }
        }
        throw new Exception("Troppi redirect");
    }

    public String resolveUrl(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return null; }
    }

    public String extractTag(String html, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(html);
        return m.find() ? stripHtml(m.group(1)).trim() : "";
    }

    public String stripHtml(String s) {
        String noTags = s.replaceAll("(?is)<[^>]+>", " ");
        return Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    public String translateIt(String text) { return translateRaw(text, 1500, 5500); }

    private String translateRaw(String text, int maxLen, int timeoutMs) {
        if (text == null || text.trim().isEmpty()) return text;
        String cached = transCache.get(text);
        if (cached != null) return cached;
        String prefix = "", bodyToTr = text;
        Matcher qm = Pattern.compile("^((?:>>\\d+\\s*)+)(.*)$", Pattern.DOTALL).matcher(text);
        if (qm.matches()) { prefix = qm.group(1); bodyToTr = qm.group(2).trim(); }
        if (bodyToTr.isEmpty()) return text;

        String trimmed = bodyToTr.length() > maxLen ? bodyToTr.substring(0, maxLen) : bodyToTr;
        try {
            String encQ = URLEncoder.encode(trimmed, "UTF-8");
            String resp = post("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=it&dt=t",
                    "q=" + encQ, UA_WEB, timeoutMs);
            JSONArray root = new JSONArray(resp);
            if ("it".equalsIgnoreCase(root.optString(2, ""))) { transCache.put(text, text); return text; }
            JSONArray segs = root.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segs.length(); i++) {
                JSONArray seg = segs.optJSONArray(i);
                if (seg != null && !seg.isNull(0)) sb.append(seg.getString(0));
            }
            String tr = sb.toString().trim();
            if (!tr.isEmpty()) { String full = (prefix + tr).trim(); transCache.put(text, full); return full; }
        } catch (Exception ignored) {}

        try {
            String encQ = URLEncoder.encode(trimmed.length() > 900 ? trimmed.substring(0, 900) : trimmed, "UTF-8");
            String resp2 = getWithUrl("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=it&q=" + encQ, UA_WEB).body;
            JSONArray arr = new JSONArray(resp2);
            String tr2 = "";
            if (arr.length() > 0) {
                Object first = arr.get(0);
                if (first instanceof String) tr2 = (String) first;
                else if (first instanceof JSONArray) tr2 = ((JSONArray) first).optString(0, "");
            }
            tr2 = tr2.trim();
            if (!tr2.isEmpty()) { String full = (prefix + tr2).trim(); transCache.put(text, full); return full; }
        } catch (Exception ignored) {}
        return text;
    }

    public void translateTitlesAsync(final List<String> titles, final List<TextView> views, final Activity act) {
        translateTitlesAsync(titles, views, act, null);
    }

    public void translateTitlesAsync(final List<String> titles, final List<TextView> views, final Activity act, final TextFormatter fmt) {
        final int gen = nextTransGen();
        if (titles.isEmpty()) return;
        transPool.execute(() -> {
            if (gen != transGen) return;
            final List<Integer> missing = new ArrayList<>();
            final StringBuilder joined = new StringBuilder();
            for (int i = 0; i < titles.size(); i++) {
                final String orig = titles.get(i);
                final TextView tv = views.get(i);
                String c = transCache.get(orig);
                if (c != null) {
                    if (!c.equals(orig)) {
                        final CharSequence outSeq = fmt != null ? fmt.format(c) : c;
                        act.runOnUiThread(() -> tv.setText(outSeq));
                    }
                } else if (!orig.trim().isEmpty()) {
                    if (missing.size() > 0) joined.append("\n[[99]]\n");
                    String clean = orig.replace("\n", " ").replace("[[99]]", "-");
                    if (clean.length() > 240) clean = clean.substring(0, 240);
                    joined.append(clean);
                    missing.add(i);
                }
            }
            if (missing.isEmpty() || gen != transGen) return;
            String batchOut = translateRaw(joined.toString(), 4500, 6000);
            if (gen != transGen) return;
            final String[] parts = batchOut.split("\\s*\\[\\[\\s*99\\s*\\]\\]\\s*");
            if (parts.length == missing.size() && !batchOut.equals(joined.toString())) {
                act.runOnUiThread(() -> {
                    if (gen != transGen) return;
                    for (int k = 0; k < missing.size(); k++) {
                        int idx = missing.get(k);
                        String tr = parts[k].trim();
                        if (!tr.isEmpty()) {
                            transCache.put(titles.get(idx), tr);
                            views.get(idx).setText(fmt != null ? fmt.format(tr) : tr);
                        }
                    }
                });
            } else {
                for (int k = 0; k < missing.size(); k++) {
                    if (gen != transGen) return;
                    final int idx = missing.get(k);
                    final String orig = titles.get(idx);
                    final String tr = translateRaw(orig, 900, 5000);
                    if (gen == transGen && !tr.equals(orig)) {
                        final CharSequence outSeq = fmt != null ? fmt.format(tr) : tr;
                        act.runOnUiThread(() -> views.get(idx).setText(outSeq));
                    }
                }
            }
        });
    }

    public String unwrapUrl(String url) {
        try {
            if (url == null) return null;
            if (url.contains("google.com/url?")) {
                Matcher m = Pattern.compile("[?&](?:url|q)=([^&]+)").matcher(url);
                if (m.find()) return URLDecoder.decode(m.group(1), "UTF-8");
            }
            if (!url.contains("news.google.com/")) return url;
            Matcher idM = Pattern.compile("/(?:rss/)?(?:articles|read)/([^?&#/]+)").matcher(url);
            if (!idM.find()) return url;
            String id = idM.group(1);
            String b64 = decodeGNewsB64(id);
            if (b64 != null) return b64;
            HttpRes resp = getWithUrl("https://news.google.com/rss/articles/" + id, UA_WEB);
            if (resp.finalUrl != null && !resp.finalUrl.contains("news.google.com") && !resp.finalUrl.contains("consent.google.com")) {
                return resp.finalUrl;
            }
            Matcher mAu = Pattern.compile("data-n-au=[\"'](https?://[^\"']+)[\"']").matcher(resp.body);
            if (mAu.find()) return Html.fromHtml(mAu.group(1), Html.FROM_HTML_MODE_LEGACY).toString();
            Matcher mSg = Pattern.compile("data-n-a-sg=[\"']([^\"']+)[\"']").matcher(resp.body);
            Matcher mTs = Pattern.compile("data-n-a-ts=[\"']([^\"']+)[\"']").matcher(resp.body);
            if (!mSg.find() || !mTs.find()) {
                resp = getWithUrl("https://news.google.com/articles/" + id + "?hl=it&gl=IT&ceid=IT:it", UA_WEB);
                mSg = Pattern.compile("data-n-a-sg=[\"']([^\"']+)[\"']").matcher(resp.body);
                mTs = Pattern.compile("data-n-a-ts=[\"']([^\"']+)[\"']").matcher(resp.body);
                if (!mSg.find() || !mTs.find()) return url;
            }
            String inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"IT:it\",null,1,null,null,null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"" + id + "\"," + mTs.group(1) + ",\"" + mSg.group(1) + "\"]";
            JSONArray r1 = new JSONArray().put("Fbv4je").put(inner).put(JSONObject.NULL).put("generic");
            String req = new JSONArray().put(new JSONArray().put(r1)).toString();
            String batch = post("https://news.google.com/_/DotsSplashUi/data/batchexecute?rpcids=Fbv4je",
                    "f.req=" + URLEncoder.encode(req, "UTF-8"), UA_WEB, 6500);
            String unescaped = batch.replace("\\\"", "\"").replace("\\/", "/").replace("\\u003d", "=").replace("\\u0026", "&");
            Matcher mUrl = Pattern.compile("\\[\"garturlres\",\"(https?://[^\"]+)\"").matcher(unescaped);
            if (mUrl.find()) return mUrl.group(1);
        } catch (Exception ignored) {}
        return url;
    }

    private String decodeGNewsB64(String id) {
        try {
            String p = id;
            while (p.length() % 4 != 0) p += "=";
            String latin = new String(Base64.decode(p, Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.ISO_8859_1);
            int idx = latin.indexOf("https://");
            if (idx < 0) idx = latin.indexOf("http://");
            if (idx < 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = idx; i < latin.length(); i++) {
                char ch = latin.charAt(i);
                if (ch >= 0x21 && ch <= 0x7E && ch != '"' && ch != '<' && ch != '>') sb.append(ch);
                else break;
            }
            String u = sb.toString();
            return (u.startsWith("http") && u.contains(".") && !u.contains("news.google.com")) ? u : null;
        } catch (Exception ignored) { return null; }
    }

    public List<RssEntry> parseRss(String xml) throws Exception {
        List<RssEntry> list = new ArrayList<>();
        String trimmedLow = xml.trim().toLowerCase();
        if (trimmedLow.startsWith("<!doctype html") || trimmedLow.startsWith("<html")) {
            throw new Exception("Limite richieste (attendi 10s)");
        }
        int first = xml.indexOf('<');
        if (first > 0) xml = xml.substring(first);
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        XmlPullParser xpp = f.newPullParser();
        xpp.setInput(new StringReader(xml));
        RssEntry cur = null;
        int ev = xpp.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT && list.size() < 60) {
            String tag = xpp.getName() != null ? xpp.getName().toLowerCase() : "";
            if (ev == XmlPullParser.START_TAG) {
                if ("item".equals(tag) || "entry".equals(tag)) cur = new RssEntry();
                else if (cur != null) {
                    if ("title".equals(tag)) cur.title = stripHtml(safeText(xpp));
                    else if ("id".equals(tag) && cur.id.isEmpty()) cur.id = safeText(xpp).trim();
                    else if (tag.endsWith("in-reply-to")) {
                        String ref = xpp.getAttributeValue(null, "ref");
                        if (ref != null && !ref.isEmpty()) cur.parentId = ref.trim();
                    } else if ("name".equals(tag) && cur.author.isEmpty()) cur.author = safeText(xpp).replaceFirst("^/?u/", "").trim();
                    else if ("link".equals(tag)) {
                        String href = xpp.getAttributeValue(null, "href");
                        if (href != null && !href.isEmpty()) cur.link = href.trim();
                        else { String t = safeText(xpp).trim(); if (!t.isEmpty()) cur.link = t; }
                    } else if ("description".equals(tag) || "summary".equals(tag) || "content".equals(tag) || "content:encoded".equals(tag)) {
                        String raw = safeText(xpp);
                        if (raw.length() > cur.rawHtml.length()) cur.rawHtml = raw;
                        if (cur.imageUrl.isEmpty()) {
                            Matcher m = Pattern.compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").matcher(raw);
                            if (m.find()) cur.imageUrl = m.group(1);
                        }
                        String cl = stripHtml(raw);
                        if (cl.length() > cur.summary.length()) cur.summary = cl;
                    } else if ("pubdate".equals(tag) || "updated".equals(tag) || "published".equals(tag)) {
                        if (cur.pubDate.isEmpty()) cur.pubDate = safeText(xpp).trim();
                    } else if ("enclosure".equals(tag) || "media:content".equals(tag) || "media:thumbnail".equals(tag)) {
                        String u = xpp.getAttributeValue(null, "url");
                        if (u != null && !u.isEmpty() && cur.imageUrl.isEmpty()) cur.imageUrl = u;
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && ("item".equals(tag) || "entry".equals(tag)) && cur != null) {
                list.add(cur); cur = null;
            }
            ev = xpp.next();
        }
        return list;
    }

    private String safeText(XmlPullParser xpp) {
        try { return xpp.nextText(); } catch (Exception e) { return ""; }
    }
}
