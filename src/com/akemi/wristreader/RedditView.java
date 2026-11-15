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
import java.util.concurrent.ExecutorService;
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
        List<String> list = app.store.getFollowedSubs();
        if (list.isEmpty()) {
            ui.clear();
            ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(), () -> loadFollowed(mode, true));
            ui.addTitle("Nessuna pagina seguita", "#F97316");
            ui.addBody(ui.contentBox, "Torna nella Home, scrivi r/nomepagina nella barra in alto e tocca '+ Segui'.", 13f, "#E2E8F0");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append("+"); sb.append(s); }
        loadFeed(sb.toString(), true, mode, force);
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
            String xml = net.getCached("https://www.reddit.com/search.rss?q=" + qEnc + "&limit=18", NetHelper.UA_FEED, force);
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
                obj.put("title", title);
                obj.put("author", "reddit");
                obj.put("score", -1);
                obj.put("num_comments", -1);
                String perm = href.replaceFirst("^https?://[^/]+", "");
                obj.put("permalink", perm);
                Matcher sm = Pattern.compile("/r/([^/]+)/").matcher(perm);
                if (sm.find()) obj.put("subreddit", sm.group(1));
                obj.put("url", href);
                obj.put("is_self", true);
                pr.posts.add(obj);
            }
        }
        return pr;
    }

    public void loadFeed(final String subOrMulti, final boolean isMulti, final String mode, final boolean force) {
        ui.clear();
        ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(), () -> loadFeed(subOrMulti, isMulti, mode, true));

        String modeLabel = "random".equals(mode) ? "🎲 Casuale"
                : "new".equals(mode) ? "🆕 Nuovi"
                : "rising".equals(mode) ? "🚀 In Ascesa"
                : "day".equals(mode) ? "🔥 Top 24h"
                : "week".equals(mode) ? "📅 Top 7gg"
                : "month".equals(mode) ? "🗓️ Top 30gg"
                : "year".equals(mode) ? "🏅 Top 1 Anno"
                : "all".equals(mode) ? "👑 Top Sempre" : "🔥 Popolari (Hot)";

        ui.addTitle(isMulti ? "Miei Seguiti" : ("r/" + subOrMulti), "#FB923C");
        ui.addSub(modeLabel);

        if (!isMulti) {
            boolean fol = app.store.isSubFollowed(subOrMulti);
            ui.addPill(ui.contentBox, fol ? "✓ Seguito" : "+ Segui r/" + subOrMulti,
                    fol ? "#132A22" : "#15803D", fol ? "#1B3B2F" : "#166534",
                    fol ? "#10B981" : null, fol ? "#34D399" : "#FFFFFF", v -> {
                        app.store.toggleSub(subOrMulti);
                        loadFeed(subOrMulti, false, mode, false);
                    });
        }

        addFilterRow(subOrMulti, isMulti, mode, new String[][]{{"🔥Hot", "hot"}, {"🆕New", "new"}, {"🚀", "rising"}, {"🎲", "random"}});
        addFilterRow(subOrMulti, isMulti, mode, new String[][]{{"24h", "day"}, {"7gg", "week"}, {"30gg", "month"}, {"1A", "year"}, {"All", "all"}});

        final TextView loading = ui.addBody(ui.contentBox, "Caricamento post da Reddit...", 13f, "#E2E8F0");

        pool.execute(() -> {
            try {
                String enc = URLEncoder.encode(subOrMulti, "UTF-8").replace("%2B", "+");
                final PageResult res = fetchPage(enc, isMulti, mode, "", force, loading);
                if ("random".equals(mode)) {
                    Collections.shuffle(res.posts);
                    if (res.posts.size() > 16) res.posts.subList(16, res.posts.size()).clear();
                }
                app.runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (res.posts.isEmpty()) {
                        ui.addBody(ui.contentBox, "Nessun post in questo intervallo.", 13f, "#E2E8F0");
                        return;
                    }
                    appendPostsAndMore(subOrMulti, isMulti, mode, res);
                });
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore Reddit: " + e.getMessage()));
            }
        });
    }

    private void addFilterRow(final String subOrMulti, final boolean isMulti, final String activeMode, String[][] items) {
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
            sb.setOnClickListener(v -> loadFeed(subOrMulti, isMulti, fc, "random".equals(fc)));
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
                ? "/top.json?t=" + mode + "&limit=20&raw_json=1" + qAfter
                : "new".equals(mode) ? "/new.json?limit=20&raw_json=1" + qAfter
                : "rising".equals(mode) ? "/rising.json?limit=20&raw_json=1" + qAfter
                : "random".equals(mode) ? "/top.json?t=week&limit=28&raw_json=1"
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
                    return pr;
                }
            } catch (Exception ignored) {}
        }

        String rssPath = ("day".equals(mode) || "week".equals(mode) || "month".equals(mode) || "year".equals(mode) || "all".equals(mode))
                ? "/top.rss?t=" + mode + "&limit=22"
                : "new".equals(mode) ? "/new.rss?limit=22"
                : "rising".equals(mode) ? "/rising.rss?limit=22"
                : "random".equals(mode) ? "/top.rss?t=week&limit=25" : "/hot.rss?limit=22";

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
            for (int i = 0; i < Math.min(subs.size(), 4); i++) {
                try {
                    String subXml = net.getCached("https://www.reddit.com/r/" + subs.get(i) + rssPath, NetHelper.UA_FEED, force);
                    for (NetHelper.RssEntry it : net.parseRss(subXml)) pr.posts.add(rssEntryToPostJson(it));
                } catch (Exception ignored) {}
            }
            if (!pr.posts.isEmpty()) return pr;
        }

        return fetchRedditViaDuckDuckGo("site:reddit.com/r/" + enc.replace("+", " OR site:reddit.com/r/"));
    }

    public void loadFeed(final String subOrMulti, final boolean isMulti, final String mode, final boolean force) {
        ui.clear();
        ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(), () -> loadFeed(subOrMulti, isMulti, mode, true));

        String modeLabel = "random".equals(mode) ? "🎲 Casuale"
                : "new".equals(mode) ? "🆕 Nuovi"
                : "rising".equals(mode) ? "🚀 In Ascesa"
                : "day".equals(mode) ? "🔥 Top 24h"
                : "week".equals(mode) ? "📅 Top 7gg"
                : "month".equals(mode) ? "🗓️ Top 30gg"
                : "year".equals(mode) ? "🏅 Top 1 Anno"
                : "all".equals(mode) ? "👑 Top Sempre" : "🔥 Popolari (Hot)";

        ui.addTitle(isMulti ? "Miei Seguiti" : ("r/" + subOrMulti), "#FB923C");
        ui.addSub(modeLabel);

        if (!isMulti) {
            boolean fol = app.store.isSubFollowed(subOrMulti);
            ui.addPill(ui.contentBox, fol ? "✓ Seguito" : "+ Segui r/" + subOrMulti,
                    fol ? "#132A22" : "#15803D", fol ? "#1B3B2F" : "#166534",
                    fol ? "#10B981" : null, fol ? "#34D399" : "#FFFFFF", v -> {
                        app.store.toggleSub(subOrMulti);
                        loadFeed(subOrMulti, false, mode, false);
                    });
        }

        addFilterRow(subOrMulti, isMulti, mode, new String[][]{{"🔥Hot", "hot"}, {"🆕New", "new"}, {"🚀", "rising"}, {"🎲", "random"}});
        addFilterRow(subOrMulti, isMulti, mode, new String[][]{{"24h", "day"}, {"7gg", "week"}, {"30gg", "month"}, {"1A", "year"}, {"All", "all"}});

        final TextView loading = ui.addBody(ui.contentBox, "Caricamento post da Reddit...", 13f, "#E2E8F0");

        pool.execute(() -> {
            try {
                String enc = URLEncoder.encode(subOrMulti, "UTF-8").replace("%2B", "+");
                final PageResult res = fetchPage(enc, isMulti, mode, "", force, loading);
                if ("random".equals(mode)) {
                    Collections.shuffle(res.posts);
                    if (res.posts.size() > 16) res.posts.subList(16, res.posts.size()).clear();
                }
                app.runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (res.posts.isEmpty()) {
                        ui.addBody(ui.contentBox, "Nessun post in questo intervallo.", 13f, "#E2E8F0");
                        return;
                    }
                    appendPostsAndMore(subOrMulti, isMulti, mode, res);
                });
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore Reddit: " + e.getMessage()));
            }
        });
    }

    private void addFilterRow(final String subOrMulti, final boolean isMulti, final String activeMode, String[][] items) {
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
            sb.setOnClickListener(v -> loadFeed(subOrMulti, isMulti, fc, "random".equals(fc)));
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
                ? "/top.json?t=" + mode + "&limit=20&raw_json=1" + qAfter
                : "new".equals(mode) ? "/new.json?limit=20&raw_json=1" + qAfter
                : "rising".equals(mode) ? "/rising.json?limit=20&raw_json=1" + qAfter
                : "random".equals(mode) ? "/top.json?t=week&limit=28&raw_json=1"
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
                    return pr;
                }
            } catch (Exception ignored) {}
        }

        String rssPath = ("day".equals(mode) || "week".equals(mode) || "month".equals(mode) || "year".equals(mode) || "all".equals(mode))
                ? "/top.rss?t=" + mode + "&limit=22"
                : "new".equals(mode) ? "/new.rss?limit=22"
                : "rising".equals(mode) ? "/rising.rss?limit=22"
                : "random".equals(mode) ? "/top.rss?t=week&limit=25" : "/hot.rss?limit=22";

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
            for (int i = 0; i < Math.min(subs.size(), 4); i++) {
                try {
                    String subXml = net.getCached("https://www.reddit.com/r/" + subs.get(i) + rssPath, NetHelper.UA_FEED, force);
                    for (NetHelper.RssEntry it : net.parseRss(subXml)) pr.posts.add(rssEntryToPostJson(it));
                } catch (Exception ignored) {}
            }
            if (!pr.posts.isEmpty()) return pr;
        }

        return fetchRedditViaDuckDuckGo("site:reddit.com/r/" + enc.replace("+", " OR site:reddit.com/r/"));
    }
