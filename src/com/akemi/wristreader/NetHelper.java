package com.akemi.wristreader;

import android.text.Html;
import android.util.Base64;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class NetHelper {
    public static final String UA_REDDIT = "android:com.akemi.wristreader:v1.3 (by /u/akemi_watch)";
    public static final String UA_WEB = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36";

    public static class RssEntry {
        public String title = "", link = "", summary = "", pubDate = "", imageUrl = "";
    }

    public static class HttpRes {
        public String body = "", finalUrl = "";
    }

    private final LruCache<String, String> textCache = new LruCache<>(35);
    private final LruCache<String, String> transCache = new LruCache<>(250);

    public String getCached(String urlStr, String ua, boolean force) throws Exception {
        String key = ua + "|" + urlStr;
        if (!force) {
            String c = textCache.get(key);
            if (c != null) return c;
        }
        String res = getWithUrl(urlStr, ua).body;
        textCache.put(key, res);
        return res;
    }

    public HttpRes getWithUrl(String urlStr, String ua) throws Exception {
        String cur = urlStr;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(9000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "application/rss+xml,application/atom+xml,application/xml,application/json,text/html,*/*");
            c.setRequestProperty("Cookie", "CONSENT=YES+cb.20260101-00-p0.it+FX+111");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) break;
                cur = resolveUrl(cur, loc);
                continue;
            }
            if (code >= 400) { c.disconnect(); throw new Exception("HTTP " + code); }
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                HttpRes rx = new HttpRes();
                rx.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                rx.finalUrl = cur;
                return rx;
            } finally { c.disconnect(); }
        }
        throw new Exception("Troppi redirect");
    }

    public String post(String urlStr, String formBody, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(9000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        try (OutputStream os = c.getOutputStream()) { os.write(formBody.getBytes(StandardCharsets.UTF_8)); }
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    public byte[] getBytes(String urlStr, String ua) throws Exception {
        String cur = urlStr;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(9000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", ua);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) break;
                cur = resolveUrl(cur, loc);
                continue;
            }
            if (code >= 400) { c.disconnect(); throw new Exception("HTTP " + code); }
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
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

    public String translateIt(String text) {
        if (text == null || text.trim().isEmpty()) return text;
        String cached = transCache.get(text);
        if (cached != null) return cached;
        try {
            String trimmed = text.length() > 1400 ? text.substring(0, 1400) + "..." : text;
            String resp = post("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=it&dt=t",
                    "q=" + URLEncoder.encode(trimmed, "UTF-8"), UA_WEB);
            JSONArray root = new JSONArray(resp);
            if ("it".equalsIgnoreCase(root.optString(2, ""))) {
                transCache.put(text, text);
                return text;
            }
            JSONArray segs = root.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segs.length(); i++) {
                JSONArray seg = segs.optJSONArray(i);
                if (seg != null && !seg.isNull(0)) sb.append(seg.getString(0));
            }
            String tr = sb.toString().trim();
            if (!tr.isEmpty()) { transCache.put(text, tr); return tr; }
        } catch (Exception ignored) {}
        return text;
    }

    public List<String> translateBatchIt(List<String> items, boolean enabled) {
        List<String> res = new ArrayList<>(items);
        if (!enabled || items.isEmpty()) return res;
        List<Integer> missing = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            String orig = items.get(i);
            String c = transCache.get(orig);
            if (c != null) res.set(i, c);
            else if (!orig.trim().isEmpty()) {
                if (missing.size() > 0) joined.append("\n###\n");
                joined.append(orig.replace("\n", " "));
                missing.add(i);
            }
        }
        if (missing.isEmpty()) return res;
        try {
            String[] parts = translateIt(joined.toString()).split("\\s*###\\s*");
            if (parts.length == missing.size()) {
                for (int k = 0; k < missing.size(); k++) {
                    int idx = missing.get(k);
                    String tr = parts[k].trim();
                    if (!tr.isEmpty()) { transCache.put(items.get(idx), tr); res.set(idx, tr); }
                }
                return res;
            }
        } catch (Exception ignored) {}
        for (int idx : missing) res.set(idx, translateIt(items.get(idx)));
        return res;
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
            if (mSg.find() && mTs.find()) {
                String inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"IT:it\",null,1,null,null,null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"" + id + "\"," + mTs.group(1) + ",\"" + mSg.group(1) + "\"]";
                JSONArray r1 = new JSONArray().put("Fbv4je").put(inner).put(JSONObject.NULL).put("generic");
                String req = new JSONArray().put(new JSONArray().put(r1)).toString();
                String batch = post("https://news.google.com/_/DotsSplashUi/data/batchexecute?rpcids=Fbv4je",
                        "f.req=" + URLEncoder.encode(req, "UTF-8"), UA_WEB);
                Matcher mUrl = Pattern.compile("\\[\"garturlres\",\"(https?:\\\\?/\\\\?/[^\"\\\\]+(?:\\\\/[^\"\\\\]+)*)\"").matcher(batch);
                if (mUrl.find()) return mUrl.group(1).replace("\\/", "/").replace("\\u003d", "=").replace("\\u0026", "&");
            }
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
            return (u.startsWith("http") && u.contains(".")) ? u : null;
        } catch (Exception ignored) { return null; }
    }

    public List<RssEntry> parseRss(String xml) throws Exception {
        List<RssEntry> list = new ArrayList<>();
        int first = xml.indexOf('<');
        if (first > 0) xml = xml.substring(first);
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        XmlPullParser xpp = f.newPullParser();
        xpp.setInput(new StringReader(xml));
        RssEntry cur = null;
        int ev = xpp.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT && list.size() < 25) {
            String tag = xpp.getName() != null ? xpp.getName().toLowerCase() : "";
            if (ev == XmlPullParser.START_TAG) {
                if ("item".equals(tag) || "entry".equals(tag)) cur = new RssEntry();
                else if (cur != null) {
                    if ("title".equals(tag)) cur.title = stripHtml(safeText(xpp));
                    else if ("link".equals(tag)) {
                        String href = xpp.getAttributeValue(null, "href");
                        if (href != null && !href.isEmpty()) cur.link = href.trim();
                        else { String t = safeText(xpp).trim(); if (!t.isEmpty()) cur.link = t; }
                    } else if ("description".equals(tag) || "summary".equals(tag) || "content".equals(tag) || "content:encoded".equals(tag)) {
                        String raw = safeText(xpp);
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
