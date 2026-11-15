package com.akemi.wristreader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Html;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class RedditView {
    private final MainActivity app;
    private final UiHelper ui;
    private final NetHelper net;
    private final ExecutorService pool;

    private static class PageResult {
        final List<JSONObject> posts = new ArrayList<>();
        String after = "";
    }

    public RedditView(MainActivity app, UiHelper ui, NetHelper net, ExecutorService pool) {
        this.app = app; this.ui = ui; this.net = net; this.pool = pool;
    }

    public void loadFollowed(final String mode, final boolean force) {
        loadFollowed(mode, true, force);
    }

    public void loadFollowed(final String mode, final boolean perSub, final boolean force) {
        List<String> list = app.store.getFollowedSubs();
        if (list.isEmpty()) {
            ui.clear();
            ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(), () -> loadFollowed(mode, perSub, true));
            ui.addTitle("Nessuna pagina seguita", "#F97316");
            ui.addBody(ui.contentBox, "Torna nella Home, scrivi r/nomepagina nella barra in alto e tocca '+ Segui'.", 13f, "#E2E8F0");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append("+"); sb.append(s); }
        loadFeedInternal(sb.toString(), true, perSub, mode, force);
    }

    public void loadFeed(final String subOrMulti, final boolean isMulti, final String mode, final boolean force) {
        loadFeedInternal(subOrMulti, isMulti, isMulti, mode, force);
    }

    public void searchReddit(final String query, final boolean force) {
        ui.clear();
        ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(), () -> searchReddit(query, true));
        ui.addTitle("Reddit: " + query, "#FB923C");

        final String cleanSub = query.trim().replaceFirst("^/?r/", "").replaceAll("[^a-zA-Z0-9_]", "");
        if (!cleanSub.isEmpty() && !query.trim().contains(" ")) {
            ui.addPill(ui.contentBox, "Apri subito r/" + cleanSub + " ↗", "#EA580C", "#C2410C", null, "#FFFFFF",
                    v -> app.navigateTo(() -> loadFeed(cleanSub, false, "hot", false)));
        }

        final TextView loading = ui.addBody(ui.contentBox, "Ricerca community e post...", 13f, "#E2E8F0");

        pool.execute(() -> {
            try {
                String qEnc = URLEncoder.encode(query, "UTF-8");
                final List<JSONObject> subs = new ArrayList<>();
                for (String[] ep : new String[][]{
                        {"https://www.reddit.com/subreddits/search.json?q=" + qEnc + "&limit=6&raw_json=1", NetHelper.UA_WEB},
                        {"https://old.reddit.com/subreddits/search.json?q=" + qEnc + "&limit=6&raw_json=1", NetHelper.UA_REDDIT}}) {
                    try {
                        String sJson = net.getCached(ep[0], ep[1], force).trim();
                        if (sJson.startsWith("{")) {
                            JSONArray ch = new JSONObject(sJson).getJSONObject("data").getJSONArray("children");
                            for (int i = 0; i < ch.length(); i++) subs.add(ch.getJSONObject(i).getJSONObject("data"));
                            if (!subs.isEmpty()) break;
                        }
                    } catch (Exception ignored) {}
                }

                final PageResult pr = fetchSearchJsonOrFallback(qEnc, "", force);

                app.runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (!subs.isEmpty()) {
                        ui.addSection("COMMUNITY TROVATE");
                        for (JSONObject s : subs) {
                            final String sName = s.optString("display_name", "");
                            int members = s.optInt("subscribers", 0);
                            if (!sName.isEmpty()) {
                                ui.addPill(ui.contentBox, "r/" + sName + (members > 0 ? " (" + (members / 1000) + "k)" : ""),
                                        "#141923", "#1E2634", "#EA580C", "#FDBA74",
                                        v -> app.navigateTo(() -> loadFeed(sName, false, "hot", false)));
                            }
                        }
                    }
                    ui.addSection("POST TROVATI");
                    if (pr.posts.isEmpty()) { ui.addBody(ui.contentBox, "Nessun post trovato.", 13f, "#E2E8F0"); return; }
                    appendPostsAndMore(query, true, "search", pr);
                });
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore ricerca Reddit: " + e.getMessage()));
            }
        });
    }

    private PageResult fetchSearchJsonOrFallback(String qEnc, String after, boolean force) throws Exception {
        PageResult pr = new PageResult();
        String qAfter = (after != null && !after.isEmpty()) ? ("&after=" + URLEncoder.encode(after, "UTF-8")) : "";
        String[][] endpoints = {
            {"https://www.reddit.com/search.json?q=" + qEnc + "&sort=relevance&t=year&limit=18&raw_json=1" + qAfter, NetHelper.UA_WEB},
            {"https://old.reddit.com/search.json?q=" + qEnc + "&sort=relevance&t=year&limit=18&raw_json=1" + qAfter, NetHelper.UA_REDDIT},
            {"https://api.reddit.com/search?q=" + qEnc + "&sort=relevance&t=year&limit=18&raw_json=1" + qAfter, NetHelper.UA_FEED}
        };
        for (String[] ep : endpoints) {
            try {
                String pJson = net.getCached(ep[0], ep[1], force).trim();
                if (pJson.startsWith("{")) {
                    JSONObject d = new JSONObject(pJson).getJSONObject("data");
                    pr.after = d.optString("after", "");
                    JSONArray ch = d.getJSONArray("children");
                    for (int i = 0; i < ch.length(); i++) pr.posts.add(ch.getJSONObject(i).getJSONObject("data"));
                    if (!pr.posts.isEmpty()) return pr;
                }
            } catch (Exception ignored) {}
        }
        try {
            String xml = net.getCached("https://www.reddit.com/search/.rss?q=" + qEnc + "&limit=18", NetHelper.UA_FEED, force);
            for (NetHelper.RssEntry it : net.parseRss(xml)) pr.posts.add(rssEntryToPostJson(it));
            if (!pr.posts.isEmpty()) return pr;
        } catch (Exception ignored) {}

        return fetchRedditViaDuckDuckGo("site:reddit.com " + URLDecoder.decode(qEnc, "UTF-8"));
    }

    private PageResult fetchRedditViaDuckDuckGo(String ddgQuery) throws Exception {
        PageResult pr = new PageResult();
        String html = net.getCached("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(ddgQuery, "UTF-8"), NetHelper.UA_WEB, false);
        Matcher m = Pattern.compile("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        while (m.find() && pr.posts.size() < 15) {
            String href = Html.fromHtml(m.group(1), Html.FROM_HTML_MODE_LEGACY).toString();
            if (href.contains("uddg=")) href = URLDecoder.decode(href.replaceAll(".*uddg=([^&]+).*", "$1"), "UTF-8");
            String title = net.stripHtml(m.group(2)).replaceFirst("\\s*-\\s*Reddit$", "");
            if (href.contains("reddit.com/r/") && href.contains("/comments/") && !title.isEmpty()) {
                JSONObject obj = new JSONObject();
                obj.put("title", title); obj.put("author", "reddit");
                obj.put("score", -1); obj.put("num_comments", -1);
                String perm = href.replaceFirst("^https?://[^/]+", "");
                obj.put("permalink", perm);
                Matcher sm = Pattern.compile("/r/([^/]+)/").matcher(perm);
                if (sm.find()) obj.put("subreddit", sm.group(1));
                obj.put("url", href); obj.put("is_self", true);
                pr.posts.add(obj);
            }
        }
        return pr;
    }

    private void loadFeedInternal(final String subOrMulti, final boolean isMulti, final boolean perSub,
                                  final String mode, final boolean force) {
        ui.clear();
        ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(),
                () -> loadFeedInternal(subOrMulti, isMulti, perSub, mode, true));

        String modeLabel = "random".equals(mode) ? "🎲 Casuale"
                : "new".equals(mode) ? "🆕 Nuovi"
                : "rising".equals(mode) ? "🚀 In Ascesa"
                : "day".equals(mode) ? "🔥 Top 24h"
                : "week".equals(mode) ? "📅 Top 7gg"
                : "month".equals(mode) ? "🗓️ Top 30gg"
                : "year".equals(mode) ? "🏅 Top 1 Anno"
                : "all".equals(mode) ? "👑 Top Sempre" : "🔥 Popolari (Hot)";

        ui.addTitle(isMulti ? "Miei Seguiti" : ("r/" + subOrMulti), "#FB923C");
        ui.addSub(isMulti && perSub ? (modeLabel + " • 1 per Sub") : modeLabel);

        if (!isMulti) {
            boolean fol = app.store.isSubFollowed(subOrMulti);
            ui.addPill(ui.contentBox, fol ? "✓ Seguito" : "+ Segui r/" + subOrMulti,
                    fol ? "#132A22" : "#15803D", fol ? "#1B3B2F" : "#166534",
                    fol ? "#10B981" : null, fol ? "#34D399" : "#FFFFFF", v -> {
                        app.store.toggleSub(subOrMulti);
                        loadFeedInternal(subOrMulti, false, false, mode, false);
                    });
        } else {
            LinearLayout modeRow = new LinearLayout(app);
            modeRow.setOrientation(LinearLayout.HORIZONTAL);
            modeRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mrLp.setMargins(0, ui.dp(2), 0, ui.dp(4));
            modeRow.setLayoutParams(mrLp);

            Button bTurn = ui.chip("🔀 1 per Sub", perSub ? "#0284C7" : "#141923", "#0369A1",
                    perSub ? null : "#2A3447", perSub ? "#FFFFFF" : "#94A3B8");
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp1.rightMargin = ui.dp(3);
            bTurn.setLayoutParams(lp1);
            bTurn.setOnClickListener(v -> { if (!perSub) loadFeedInternal(subOrMulti, true, true, mode, false); });
            modeRow.addView(bTurn);

            Button bMix = ui.chip("📊 Unificato", !perSub ? "#0284C7" : "#141923", "#0369A1",
                    !perSub ? null : "#2A3447", !perSub ? "#FFFFFF" : "#94A3B8");
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp2.leftMargin = ui.dp(3);
            bMix.setLayoutParams(lp2);
            bMix.setOnClickListener(v -> { if (perSub) loadFeedInternal(subOrMulti, true, false, mode, false); });
            modeRow.addView(bMix);
            ui.contentBox.addView(modeRow);
        }

        addFilterRow(subOrMulti, isMulti, perSub, mode, new String[][]{{"🔥Hot", "hot"}, {"🆕New", "new"}, {"🚀", "rising"}, {"🎲", "random"}});
        addFilterRow(subOrMulti, isMulti, perSub, mode, new String[][]{{"24h", "day"}, {"7gg", "week"}, {"30gg", "month"}, {"1A", "year"}, {"All", "all"}});

        final TextView loading = ui.addBody(ui.contentBox, "Caricamento post da Reddit...", 13f, "#E2E8F0");

        pool.execute(() -> {
            try {
                final PageResult res;
                if (isMulti && perSub) {
                    res = fetchRoundRobinFollowed(mode, force, loading);
                } else {
                    String enc = URLEncoder.encode(subOrMulti, "UTF-8").replace("%2B", "+");
                    res = fetchPage(enc, isMulti, mode, "", force, loading);
                }
                if ("random".equals(mode) && !(isMulti && perSub)) {
                    Collections.shuffle(res.posts);
                    if (res.posts.size() > 16) res.posts.subList(16, res.posts.size()).clear();
                }
                app.runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (res.posts.isEmpty()) {
                        ui.addBody(ui.contentBox, "Nessun post in questo intervallo (prova 7gg o 30gg).", 13f, "#E2E8F0");
                        return;
                    }
                    appendPostsAndMore(subOrMulti, isMulti, mode, res);
                });
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore Reddit: " + e.getMessage()));
            }
        });
    }

    private PageResult fetchRoundRobinFollowed(final String mode, final boolean force, final TextView loading) throws Exception {
        final List<String> subs = app.store.getFollowedSubs();
        app.runOnUiThread(() -> loading.setText("Raccolta top post da " + subs.size() + " pagine..."));
        ExecutorService subPool = Executors.newFixedThreadPool(Math.min(4, Math.max(1, subs.size())));
        List<Future<List<JSONObject>>> futs = new ArrayList<>();
        for (int i = 0; i < subs.size(); i++) {
            final String s = subs.get(i);
            final int delayMs = i * 160;
            futs.add(subPool.submit((Callable<List<JSONObject>>) () -> {
                if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                try {
                    String enc = URLEncoder.encode(s, "UTF-8");
                    PageResult pr = fetchPage(enc, false, mode, "", force, null);
                    if ("random".equals(mode)) Collections.shuffle(pr.posts);
                    else if ("day".equals(mode) || "week".equals(mode) || "month".equals(mode)) {
                        Collections.sort(pr.posts, (a, b) -> Integer.compare(b.optInt("score", 0), a.optInt("score", 0)));
                    }
                    return pr.posts.size() > 10 ? new ArrayList<>(pr.posts.subList(0, 10)) : pr.posts;
                } catch (Exception e) {
                    return new ArrayList<>();
                }
            }));
        }
        List<List<JSONObject>> perSubLists = new ArrayList<>();
        int maxLen = 0;
        for (Future<List<JSONObject>> f : futs) {
            try {
                List<JSONObject> l = f.get();
                perSubLists.add(l);
                if (l.size() > maxLen) maxLen = l.size();
            } catch (Exception ignored) {}
        }
        subPool.shutdownNow();

        PageResult out = new PageResult();
        for (int rank = 0; rank < maxLen; rank++) {
            for (List<JSONObject> l : perSubLists) {
                if (rank < l.size()) out.posts.add(l.get(rank));
            }
        }
        return out;
    }

    private void addFilterRow(final String subOrMulti, final boolean isMulti, final boolean perSub,
                              final String activeMode, String[][] items) {
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, ui.dp(2), 0, ui.dp(4));
        row.setLayoutParams(rlp);

        for (int i = 0; i < items.length; i++) {
            final String fc = items[i][1];
            boolean act = fc.equals(activeMode);
            Button sb = ui.chip(items[i][0], act ? "#EA580C" : "#141923", act ? "#C2410C" : "#1F2736",
                    act ? null : "#2A3447", act ? "#FFFFFF" : "#94A3B8");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) clp.leftMargin = ui.dp(3);
            sb.setLayoutParams(clp);
            sb.setOnClickListener(v -> loadFeedInternal(subOrMulti, isMulti, perSub, fc, "random".equals(fc)));
            row.addView(sb);
        }
        ui.contentBox.addView(row);
    }

    private void appendPostsAndMore(final String subOrMulti, final boolean isMulti, final String mode, final PageResult res) {
        List<String> origTitles = new ArrayList<>();
        List<TextView> titleViews = new ArrayList<>();
        for (JSONObject p : res.posts) {
            String t = p.optString("title", "(Senza titolo)");
            origTitles.add(t);
            titleViews.add(renderCard(subOrMulti, isMulti, mode, p, t));
        }
        if (app.autoTranslateIt) net.translateTitlesAsync(origTitles, titleViews, app);

        if (res.after != null && !res.after.isEmpty() && !"null".equals(res.after) && !"random".equals(mode)) {
            final Button moreBtn = ui.pill("Carica altri post ↓", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC");
            moreBtn.setOnClickListener(v -> {
                moreBtn.setText("Caricamento...");
                moreBtn.setEnabled(false);
                pool.execute(() -> {
                    try {
                        String enc = URLEncoder.encode(subOrMulti, "UTF-8").replace("%2B", "+");
                        final PageResult next = "search".equals(mode)
                                ? fetchSearchJsonOrFallback(enc, res.after, false)
                                : fetchPage(enc, isMulti, mode, res.after, false, null);
                        app.runOnUiThread(() -> {
                            ui.contentBox.removeView(moreBtn);
                            if (!next.posts.isEmpty()) appendPostsAndMore(subOrMulti, isMulti, mode, next);
                        });
                    } catch (Exception e) {
                        app.runOnUiThread(() -> { moreBtn.setText("Riprova ↓"); moreBtn.setEnabled(true); });
                    }
                });
            });
            ui.contentBox.addView(moreBtn);
        }
    }

    private PageResult fetchPage(String enc, boolean isMulti, String mode, String after, boolean force, TextView loading) throws Exception {
        PageResult pr = new PageResult();
        String qAfter = (after != null && !after.isEmpty()) ? ("&after=" + URLEncoder.encode(after, "UTF-8")) : "";
        String jsonPath = ("day".equals(mode) || "week".equals(mode) || "month".equals(mode) || "year".equals(mode) || "all".equals(mode))
                ? "/top.json?sort=top&t=" + mode + "&limit=20&raw_json=1" + qAfter
                : "new".equals(mode) ? "/new.json?limit=20&raw_json=1" + qAfter
                : "rising".equals(mode) ? "/rising.json?limit=20&raw_json=1" + qAfter
                : "random".equals(mode) ? "/top.json?sort=top&t=week&limit=28&raw_json=1"
                : "/hot.json?limit=18&raw_json=1" + qAfter;

        String[][] jsonEndpoints = {
            {"https://www.reddit.com/r/" + enc + jsonPath, NetHelper.UA_WEB},
            {"https://old.reddit.com/r/" + enc + jsonPath, NetHelper.UA_REDDIT},
            {"https://api.reddit.com/r/" + enc + jsonPath.replace(".json", ""), NetHelper.UA_FEED}
        };

        for (String[] ep : jsonEndpoints) {
            try {
                String rawJson = net.getCached(ep[0], ep[1], force,
                        loading != null ? (msg -> app.runOnUiThread(() -> loading.setText(msg))) : null).trim();
                if (rawJson.startsWith("{")) {
                    JSONObject data = new JSONObject(rawJson).getJSONObject("data");
                    pr.after = data.optString("after", "");
                    JSONArray children = data.getJSONArray("children");
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject p = children.optJSONObject(i).optJSONObject("data");
                        if (p != null) pr.posts.add(p);
                    }
                    if (!pr.posts.isEmpty()) return pr;
                }
            } catch (Exception ignored) {}
        }

        String rssPath = ("day".equals(mode) || "week".equals(mode) || "month".equals(mode) || "year".equals(mode) || "all".equals(mode))
                ? "/top/.rss?sort=top&t=" + mode + "&limit=25"
                : "new".equals(mode) ? "/new/.rss?limit=25"
                : "rising".equals(mode) ? "/rising/.rss?limit=25"
                : "random".equals(mode) ? "/top/.rss?sort=top&t=week&limit=25" : "/.rss?limit=25";

        for (String[] rep : new String[][]{
                {"https://www.reddit.com/r/" + enc + rssPath, NetHelper.UA_FEED},
                {"https://old.reddit.com/r/" + enc + rssPath, NetHelper.UA_WEB}}) {
            try {
                String xml = net.getCached(rep[0], rep[1], force,
                        loading != null ? (msg -> app.runOnUiThread(() -> loading.setText(msg))) : null);
                for (NetHelper.RssEntry it : net.parseRss(xml)) pr.posts.add(rssEntryToPostJson(it));
                if (!pr.posts.isEmpty()) return pr;
            } catch (Exception ignored) {}
        }

        if (isMulti) {
            List<String> subs = app.store.getFollowedSubs();
            for (int i = 0; i < Math.min(subs.size(), 5); i++) {
                try {
                    String subXml = net.getCached("https://www.reddit.com/r/" + subs.get(i) + rssPath, NetHelper.UA_FEED, force);
                    for (NetHelper.RssEntry it : net.parseRss(subXml)) pr.posts.add(rssEntryToPostJson(it));
                } catch (Exception ignored) {}
            }
            if (!pr.posts.isEmpty()) return pr;
        }

        return fetchRedditViaDuckDuckGo("site:reddit.com/r/" + enc.replace("+", " OR site:reddit.com/r/"));
    }

    private JSONObject rssEntryToPostJson(NetHelper.RssEntry it) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("title", it.title);
        obj.put("author", it.author.isEmpty() ? "reddit" : it.author);
        obj.put("score", -1); obj.put("num_comments", -1);
        String perm = it.link.replaceFirst("^https?://[^/]+", "");
        obj.put("permalink", perm);
        Matcher subM = Pattern.compile("/r/([^/]+)/").matcher(perm);
        if (subM.find()) obj.put("subreddit", subM.group(1));
        obj.put("selftext", extractMdText(it.rawHtml));

        Set<String> imgSet = new LinkedHashSet<>(extractHtmlImages(it.rawHtml));
        if (!it.imageUrl.isEmpty()) imgSet.add(it.imageUrl.replace("&amp;", "&"));

        String vidUrl = "", extUrl = "";
        Matcher mHref = Pattern.compile("(?i)<a[^>]+href=[\"']([^\"']+)[\"']").matcher(it.rawHtml);
        while (mHref.find()) {
            String h = mHref.group(1).replace("&amp;", "&");
            if (h.contains("v.redd.it/")) {
                String cleanV = h.split("\\?")[0].replaceAll("/$", "");
                vidUrl = cleanV.endsWith(".mp4") ? cleanV : (cleanV + "/DASH_360.mp4");
            } else if (h.startsWith("http") && !h.contains("reddit.com") && !h.contains("redd.it") && !h.contains("redditmedia.com")) {
                if (extUrl.isEmpty()) extUrl = h;
            }
        }
        JSONArray imgArr = new JSONArray();
        for (String im : imgSet) imgArr.put(im);
        obj.put("_imgs", imgArr); obj.put("_vid", vidUrl);
        obj.put("url", !extUrl.isEmpty() ? extUrl : it.link);
        obj.put("is_self", extUrl.isEmpty());
        return obj;
    }

    private String extractMdText(String rawHtml) {
        if (rawHtml == null || rawHtml.isEmpty()) return "";
        Matcher m = Pattern.compile("(?is)<div class=\"md\">(.*?)</div>").matcher(rawHtml);
        if (m.find()) return net.stripHtml(m.group(1));
        return net.stripHtml(rawHtml).replaceAll("submitted by\\s+/u/\\S+\\s*\\[link\\]\\s*\\[comments\\]", "").trim();
    }

    private List<String> extractHtmlImages(String rawHtml) {
        Set<String> set = new LinkedHashSet<>();
        if (rawHtml == null || rawHtml.isEmpty()) return new ArrayList<>();
        Matcher mImg = Pattern.compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").matcher(rawHtml);
        while (mImg.find()) { String u = mImg.group(1).replace("&amp;", "&"); if (isValidPhotoUrl(u)) set.add(u); }
        Matcher mHref = Pattern.compile("(?i)<a[^>]+href=[\"']([^\"']+)[\"']").matcher(rawHtml);
        while (mHref.find()) { String h = mHref.group(1).replace("&amp;", "&"); if (isValidPhotoUrl(h)) set.add(h); }
        return new ArrayList<>(set);
    }

    private boolean isValidPhotoUrl(String u) {
        if (u == null || !u.startsWith("http")) return false;
        String low = u.toLowerCase();
        if (low.contains("emoji") || low.contains("icon") || low.contains("snoo") || low.contains("award")) return false;
        return low.matches(".*\\.(jpg|jpeg|png|webp|gif)(\\?.*)?$")
                || low.contains("i.redd.it/") || low.contains("preview.redd.it/") || low.contains("i.imgur.com/");
    }

    private String timeAgo(long createdUtc) {
        if (createdUtc <= 0) return "";
        long diff = Math.max(0, (System.currentTimeMillis() / 1000L) - createdUtc);
        if (diff < 3600) return (diff / 60) + "m fa";
        if (diff < 86400) return (diff / 3600) + "h fa";
        return (diff / 86400) + "g fa";
    }

    private TextView renderCard(final String subOrMulti, final boolean isMulti, final String mode,
                                JSONObject post, final String shownTitle) {
        LinearLayout card = ui.card();
        String postSub = post.optString("subreddit", "");
        String author = post.optString("author", "?");
        String flair = post.optString("link_flair_text", "").trim();
        int score = post.optInt("score", -1);
        int numComm = post.optInt("num_comments", -1);
        final String permalink = "https://www.reddit.com" + post.optString("permalink", "");
        String selfText = post.optString("selftext", "");
        final String postUrl = post.optString("url", "");
        String ago = timeAgo(post.optLong("created_utc", 0));

        if (isMulti && !postSub.isEmpty()) {
            TextView badge = new TextView(app);
            badge.setText("r/" + postSub);
            badge.setTextColor(Color.parseColor("#FB923C"));
            badge.setTextSize(ui.sp(10.5f));
            badge.setTypeface(null, Typeface.BOLD);
            badge.setBackground(ui.roundBox("#291711", "#572B1A", 12));
            badge.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.bottomMargin = ui.dp(4);
            badge.setLayoutParams(blp);
            card.addView(badge);
        }

        String metaStr = score >= 0
                ? ("u/" + author + (ago.isEmpty() ? "" : " • " + ago) + "\n▲ " + score + "  •  💬 " + numComm)
                : ("u/" + author);
        ui.addBody(card, metaStr, 10.5f, "#94A3B8");
        if (!flair.isEmpty() && !"null".equals(flair)) ui.addBody(card, "[" + flair + "]", 10f, "#38BDF8");
        final TextView tvTitle = ui.addBody(card, shownTitle, 14f, "#F8FAFC");
        tvTitle.setTypeface(null, Typeface.BOLD);

        String vid = extractVideo(post);
        List<String> imgs = extractImages(post);
        if (imgs.size() == 1) app.addSmartImage(card, imgs.get(0), 340, "🖼 Carica foto");
        else if (imgs.size() > 1) {
            for (int idx = 0; idx < Math.min(imgs.size(), 6); idx++) {
                app.addSmartImage(card, imgs.get(idx), 340, "🖼 Foto " + (idx + 1) + "/" + imgs.size());
            }
        }
        if (vid != null && !vid.isEmpty()) {
            final String fVid = vid;
            ui.addPill(card, "▶ Riproduci Video", "#EA580C", "#C2410C", null, "#FFFFFF", v -> ui.playVideo(fVid, net));
        }
        if (!selfText.isEmpty()) {
            ui.addBody(card, selfText.length() > 140 ? selfText.substring(0, 140) + "..." : selfText, 12f, "#CBD5E1");
        }
        if (!post.optBoolean("is_self", false) && vid == null && imgs.isEmpty()
                && !postUrl.contains("i.redd.it") && !postUrl.contains("reddit.com") && postUrl.startsWith("http")) {
            ui.addPill(card, "Leggi articolo web ↗", "#0284C7", "#0369A1", null, "#FFFFFF",
                    v -> app.navigateTo(() -> app.loadSmartUrl(postUrl, false, shownTitle, selfText)));
        }
        String openLabel = numComm >= 0 ? ("Apri Post & Commenti (" + numComm + ")") : "Apri Post & Commenti";
        ui.addPill(card, openLabel, "#1B2332", "#263145", "#334155", "#38BDF8",
                v -> app.navigateTo(() -> loadComments(permalink, tvTitle.getText().toString(), "best", false)));

        ui.contentBox.addView(card);
        return tvTitle;
    }

    public void loadComments(final String permalink, final String fallbackTitle, final String sort, final boolean force) {
        ui.clear();
        ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(), () -> loadComments(permalink, fallbackTitle, sort, true));

        LinearLayout sortRow = new LinearLayout(app);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER);
        String[][] cSorts = {{"Top", "top"}, {"Migliori", "best"}, {"Nuovi", "new"}, {"Contr.", "controversial"}};
        for (int i = 0; i < cSorts.length; i++) {
            final String cs = cSorts[i][1];
            boolean act = cs.equals(sort);
            Button sb = ui.chip(cSorts[i][0], act ? "#0284C7" : "#141923", act ? "#0369A1" : "#1F2736",
                    act ? null : "#2A3447", act ? "#FFFFFF" : "#94A3B8");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) clp.leftMargin = ui.dp(3);
            sb.setLayoutParams(clp);
            sb.setOnClickListener(v -> loadComments(permalink, fallbackTitle, cs, false));
            sortRow.addView(sb);
        }
        ui.contentBox.addView(sortRow);

        final TextView loading = ui.addBody(ui.contentBox, "Caricamento post e commenti...", 13f, "#E2E8F0");
        final int gen = net.nextTransGen();

        pool.execute(() -> {
            try {
                String cleanPath = permalink.replaceFirst("^https?://[^/]+", "").split("\\?")[0].split("#")[0].replaceAll("/+$", "");
                if (!cleanPath.startsWith("/")) cleanPath = "/" + cleanPath;
                String raw = null;
                String[][] cEndpoints = {
                    {"https://www.reddit.com" + cleanPath + ".json?limit=60&depth=6&sort=" + sort + "&raw_json=1", NetHelper.UA_WEB},
                    {"https://old.reddit.com" + cleanPath + ".json?limit=60&depth=6&sort=" + sort + "&raw_json=1", NetHelper.UA_REDDIT},
                    {"https://api.reddit.com" + cleanPath + "?limit=60&depth=6&sort=" + sort + "&raw_json=1", NetHelper.UA_FEED}
                };
                for (String[] cep : cEndpoints) {
                    try {
                        String candidate = net.getCached(cep[0], cep[1], force).trim();
                        if (candidate.startsWith("[")) { raw = candidate; break; }
                    } catch (Exception ignored) {}
                }

                if (raw != null) {
                    JSONArray arr = new JSONArray(raw);
                    JSONObject postData = arr.getJSONObject(0).getJSONObject("data").getJSONArray("children").getJSONObject(0).getJSONObject("data");
                    JSONArray commentsArr = arr.getJSONObject(1).getJSONObject("data").getJSONArray("children");
                    renderFullPostHeader(postData, fallbackTitle, gen, loading);
                    app.runOnUiThread(() -> {
                        ui.addSection("COMMENTI (" + sort.toUpperCase() + ")");
                        renderTree(commentsArr, 0, gen);
                    });
                } else {
                    List<NetHelper.RssEntry> entries = new ArrayList<>();
                    for (String[] rssEp : new String[][]{
                            {"https://www.reddit.com" + cleanPath + "/.rss?limit=60", NetHelper.UA_FEED},
                            {"https://old.reddit.com" + cleanPath + "/.rss?limit=60", NetHelper.UA_WEB},
                            {"https://www.reddit.com" + cleanPath + ".rss?limit=60", NetHelper.UA_FEED}}) {
                        try {
                            String xml = net.getCached(rssEp[0], rssEp[1], force);
                            entries = net.parseRss(xml);
                            if (!entries.isEmpty()) break;
                        } catch (Exception ignored) {}
                    }
                    if (entries.isEmpty()) throw new Exception("Post temporaneamente limitato da Reddit (riprova tra 5s)");
                    JSONObject postData = rssEntryToPostJson(entries.get(0));
                    renderFullPostHeader(postData, fallbackTitle, gen, loading);

                    final Map<String, NetHelper.RssEntry> byId = new HashMap<>();
                    for (int i = 1; i < entries.size(); i++) if (!entries.get(i).id.isEmpty()) byId.put(entries.get(i).id, entries.get(i));
                    final Map<String, List<NetHelper.RssEntry>> childrenMap = new HashMap<>();
                    final List<NetHelper.RssEntry> roots = new ArrayList<>();
                    for (int i = 1; i < entries.size(); i++) {
                        NetHelper.RssEntry ce = entries.get(i);
                        if (!ce.parentId.isEmpty() && byId.containsKey(ce.parentId)) {
                            childrenMap.computeIfAbsent(ce.parentId, k -> new ArrayList<>()).add(ce);
                        } else roots.add(ce);
                    }
                    final int totalComm = Math.max(0, entries.size() - 1);
                    app.runOnUiThread(() -> {
                        ui.addSection("COMMENTI (" + totalComm + ")");
                        renderRssTree(roots, childrenMap, 0, gen);
                    });
                }
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore post: " + e.getMessage()));
            }
        });
    }

    private void renderRssTree(List<NetHelper.RssEntry> nodes, Map<String, List<NetHelper.RssEntry>> childrenMap, int depth, int gen) {
        if (nodes == null || depth > 6) return;
        for (NetHelper.RssEntry ce : nodes) {
            final String orig = extractMdText(ce.rawHtml);
            List<String> imgs = extractHtmlImages(ce.rawHtml);
            if (!orig.isEmpty() || !imgs.isEmpty()) {
                LinearLayout box = depth == 0 ? ui.card() : ui.subCard();
                box.setPadding(ui.dp(12) + Math.min(depth * ui.dp(8), ui.dp(32)), ui.dp(10), ui.dp(12), ui.dp(10));
                String auth = !ce.author.isEmpty() ? ("u/" + ce.author) : ce.title;
                TextView head = ui.addBody(box, (depth > 0 ? "↳ " : "") + auth, 10.5f, depth > 0 ? "#7DD3FC" : "#38BDF8");
                head.setTypeface(null, Typeface.BOLD);
                if (!orig.isEmpty()) {
                    final TextView tv = ui.addBody(box, orig, 12.5f, "#E2E8F0");
                    if (app.autoTranslateIt) {
                        net.transPool.execute(() -> {
                            if (gen != net.transGen) return;
                            final String tr = net.translateIt(orig);
                            if (gen == net.transGen && !tr.equals(orig)) app.runOnUiThread(() -> tv.setText(tr));
                        });
                    }
                }
                for (String im : imgs) app.addSmartImage(box, im, 280, "🖼 Foto commento");
                ui.contentBox.addView(box);
            }
            if (!ce.id.isEmpty() && childrenMap.containsKey(ce.id)) renderRssTree(childrenMap.get(ce.id), childrenMap, depth + 1, gen);
        }
    }

    private void renderFullPostHeader(JSONObject postData, String fallbackTitle, int gen, TextView loading) {
        final String rawT = postData.optString("title", fallbackTitle);
        final String rawS = postData.optString("selftext", "");
        final String extUrl = postData.optString("url", "");
        final String perm = postData.optString("permalink", "");
        final String sub = postData.optString("subreddit", "reddit");
        final boolean isSelf = postData.optBoolean("is_self", true);
        final List<String> imgs = extractImages(postData);
        final String vid = extractVideo(postData);

        app.runOnUiThread(() -> {
            ui.contentBox.removeView(loading);
            final TextView tvHead = ui.addBody(ui.contentBox, rawT, 16f, "#F8FAFC");
            tvHead.setTypeface(null, Typeface.BOLD);
            tvHead.setGravity(Gravity.CENTER);
            if (app.autoTranslateIt) {
                net.transPool.execute(() -> {
                    if (gen != net.transGen) return;
                    final String trT = net.translateIt(rawT);
                    if (gen == net.transGen && !trT.equals(rawT)) app.runOnUiThread(() -> tvHead.setText(trT));
                });
            }
            int sc = postData.optInt("score", -1);
            ui.addSub(sc >= 0 ? ("u/" + postData.optString("author", "?") + " • ▲ " + sc) : ("u/" + postData.optString("author", "?")));

            final String bmId = "reddit_" + perm;
            LinearLayout actRow = new LinearLayout(app);
            actRow.setOrientation(LinearLayout.HORIZONTAL);
            actRow.setGravity(Gravity.CENTER);
            boolean saved = app.store.isBookmarked(bmId);
            final Button bmBtn = ui.chip(saved ? "★ Salvato" : "☆ Salva",
                    "#141923", "#1E2634", saved ? "#F59E0B" : "#334155", saved ? "#FBBF24" : "#CBD5E1");
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp1.rightMargin = ui.dp(3);
            bmBtn.setLayoutParams(lp1);
            bmBtn.setOnClickListener(v -> {
                app.store.toggleBookmark(bmId, "reddit", tvHead.getText().toString(), "r/" + sub, "https://www.reddit.com" + perm, rawS);
                bmBtn.setText(app.store.isBookmarked(bmId) ? "★ Salvato" : "☆ Salva");
            });
            actRow.addView(bmBtn);

            final Button spkBtn = ui.chip("🔊 Ascolta", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC");
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp2.leftMargin = ui.dp(3);
            spkBtn.setLayoutParams(lp2);
            spkBtn.setOnClickListener(v -> ui.toggleSpeak(tvHead.getText().toString() + ". " + rawS, spkBtn));
            actRow.addView(spkBtn);
            ui.contentBox.addView(actRow);

            for (int k = 0; k < Math.min(imgs.size(), 8); k++) {
                app.addSmartImage(ui.contentBox, imgs.get(k), 420,
                        imgs.size() > 1 ? ("🖼 Foto " + (k + 1) + "/" + imgs.size()) : "🖼 Carica foto");
            }
            if (vid != null && !vid.isEmpty()) {
                ui.addPill(ui.contentBox, "▶ Guarda Video", "#EA580C", "#C2410C", null, "#FFFFFF", v -> ui.playVideo(vid, net));
            }
            if (!rawS.isEmpty()) {
                final TextView tvSelf = ui.addBody(ui.contentBox, rawS, 13.5f, "#E2E8F0");
                if (app.autoTranslateIt) {
                    net.transPool.execute(() -> {
                        if (gen != net.transGen) return;
                        final String trS = net.translateIt(rawS);
                        if (gen == net.transGen && !trS.equals(rawS)) app.runOnUiThread(() -> tvSelf.setText(trS));
                    });
                }
            }
            if (!isSelf && extUrl.startsWith("http") && !extUrl.contains("reddit.com") && !extUrl.contains("i.redd.it")) {
                ui.addPill(ui.contentBox, "Leggi articolo collegato ↗", "#0284C7", "#0369A1", null, "#FFFFFF",
                        v -> app.navigateTo(() -> app.loadSmartUrl(extUrl, false, rawT, rawS)));
            }
        });
    }

    private void renderTree(JSONArray children, int depth, final int gen) {
        if (children == null || depth > 6) return;
        for (int i = 0; i < children.length(); i++) {
            JSONObject item = children.optJSONObject(i);
            if (item == null || !"t1".equals(item.optString("kind"))) continue;
            JSONObject c = item.optJSONObject("data");
            if (c == null) continue;
            final String orig = c.optString("body", "").trim();
            List<String> commentImgs = extractCommentImages(c, orig);
            if (orig.isEmpty() && commentImgs.isEmpty()) continue;

            LinearLayout box = depth == 0 ? ui.card() : ui.subCard();
            box.setPadding(ui.dp(12) + Math.min(depth * ui.dp(8), ui.dp(32)), ui.dp(10), ui.dp(12), ui.dp(10));
            String opTag = c.optBoolean("is_submitter", false) ? " [OP]" : "";
            TextView head = ui.addBody(box, (depth > 0 ? "↳ " : "") + "u/" + c.optString("author", "?") + opTag + " • ▲ " + c.optInt("score", 0),
                    10.5f, c.optBoolean("is_submitter", false) ? "#4ADE80" : (depth > 0 ? "#7DD3FC" : "#38BDF8"));
            head.setTypeface(null, Typeface.BOLD);

            if (!orig.isEmpty()) {
                final TextView tv = ui.addBody(box, orig, 12.5f, "#E2E8F0");
                if (app.autoTranslateIt) {
                    net.transPool.execute(() -> {
                        if (gen != net.transGen) return;
                        final String tr = net.translateIt(orig);
                        if (gen == net.transGen && !tr.equals(orig)) app.runOnUiThread(() -> tv.setText(tr));
                    });
                }
            }
            for (String im : commentImgs) app.addSmartImage(box, im, 280, "🖼 Foto commento");
            ui.contentBox.addView(box);

            Object rep = c.opt("replies");
            if (rep instanceof JSONObject) renderTree(((JSONObject) rep).optJSONObject("data").optJSONArray("children"), depth + 1, gen);
        }
    }

    private List<String> extractCommentImages(JSONObject c, String body) {
        Set<String> set = new LinkedHashSet<>();
        try {
            JSONObject meta = c.optJSONObject("media_metadata");
            if (meta != null) {
                Iterator<String> keys = meta.keys();
                while (keys.hasNext()) {
                    JSONObject mObj = meta.optJSONObject(keys.next());
                    if (mObj == null) continue;
                    String u = mObj.has("s") ? mObj.getJSONObject("s").optString("u", mObj.getJSONObject("s").optString("gif", "")) : "";
                    if (u.isEmpty() && mObj.has("p")) {
                        JSONArray pArr = mObj.getJSONArray("p");
                        if (pArr.length() > 0) u = pArr.getJSONObject(pArr.length() - 1).optString("u", "");
                    }
                    if (!u.isEmpty()) set.add(u.replace("&amp;", "&"));
                }
            }
        } catch (Exception ignored) {}
        String combined = body + " " + Html.fromHtml(c.optString("body_html", ""), Html.FROM_HTML_MODE_LEGACY);
        Matcher m = Pattern.compile("(https?://[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE).matcher(combined);
        while (m.find()) {
            String u = m.group(1).replaceAll("[)\\],.]+$", "").replace("&amp;", "&");
            if (isValidPhotoUrl(u)) set.add(u);
        }
        return new ArrayList<>(set);
    }

    private String extractVideo(JSONObject post) {
        String preVid = post.optString("_vid", "");
        if (!preVid.isEmpty()) return preVid;
        try {
            JSONObject m = post.optJSONObject("secure_media");
            if (m == null) m = post.optJSONObject("media");
            if (m != null && m.has("reddit_video")) {
                String f = m.getJSONObject("reddit_video").optString("fallback_url", "");
                if (!f.isEmpty()) return f.replace("&amp;", "&");
            }
            JSONObject prev = post.optJSONObject("preview");
            if (prev != null && prev.has("reddit_video_preview")) {
                String f = prev.getJSONObject("reddit_video_preview").optString("fallback_url", "");
                if (!f.isEmpty()) return f.replace("&amp;", "&");
            }
            JSONArray cross = post.optJSONArray("crosspost_parent_list");
            if (cross != null && cross.length() > 0) return extractVideo(cross.getJSONObject(0));
        } catch (Exception ignored) {}
        String u = post.optString("url", "");
        return u.endsWith(".mp4") ? u : null;
    }

    private List<String> extractImages(JSONObject post) {
        List<String> list = new ArrayList<>();
        JSONArray preImgs = post.optJSONArray("_imgs");
        if (preImgs != null && preImgs.length() > 0) {
            for (int i = 0; i < preImgs.length(); i++) { String s = preImgs.optString(i, ""); if (!s.isEmpty()) list.add(s); }
            if (!list.isEmpty()) return list;
        }
        try {
            JSONObject meta = post.optJSONObject("media_metadata");
            JSONObject gal = post.optJSONObject("gallery_data");
            if (meta != null && gal != null) {
                JSONArray items = gal.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject mObj = meta.optJSONObject(items.getJSONObject(i).optString("media_id", ""));
                        if (mObj != null) {
                            JSONArray pArr = mObj.optJSONArray("p");
                            String u = (pArr != null && pArr.length() > 0)
                                    ? pArr.getJSONObject(Math.min(2, pArr.length() - 1)).optString("u", "")
                                    : (mObj.has("s") ? mObj.getJSONObject("s").optString("u", "") : "");
                            if (!u.isEmpty()) list.add(u.replace("&amp;", "&"));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        if (!list.isEmpty()) return list;
        String u = post.optString("url", "");
        if (isValidPhotoUrl(u)) { list.add(u.replace("&amp;", "&")); return list; }
        try {
            JSONObject prev = post.optJSONObject("preview");
            if (prev != null) {
                JSONArray imgs = prev.optJSONArray("images");
                if (imgs != null && imgs.length() > 0) {
                    JSONObject img0 = imgs.getJSONObject(0);
                    JSONArray res = img0.optJSONArray("resolutions");
                    String pu = (res != null && res.length() > 0)
                            ? res.getJSONObject(Math.min(2, res.length() - 1)).optString("url", "")
                            : img0.getJSONObject("source").optString("url", "");
                    if (!pu.isEmpty()) { list.add(pu.replace("&amp;", "&")); return list; }
                }
            }
            JSONArray cross = post.optJSONArray("crosspost_parent_list");
            if (cross != null && cross.length() > 0) return extractImages(cross.getJSONObject(0));
        } catch (Exception ignored) {}
        String th = post.optString("thumbnail", "");
        if (th.startsWith("http")) list.add(th);
        return list;
    }
}
