package com.akemi.wristreader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    public RedditView(MainActivity app, UiHelper ui, NetHelper net, ExecutorService pool) {
        this.app = app;
        this.ui = ui;
        this.net = net;
        this.pool = pool;
    }

    public void loadFollowed(final String mode, final boolean force) {
        List<String> list = app.getFollowed();
        if (list.isEmpty()) {
            ui.clear();
            ui.addNavBar(app::onBackPressed, () -> { app.cycleFont(); loadFollowed(mode, false); }, () -> loadFollowed(mode, true));
            ui.addTitle("Nessuna pagina seguita", "#F97316");
            ui.addBody(ui.contentBox, "Torna nella Home, scrivi r/nomepagina nella barra in alto e tocca '+ Segui'.", 13f, "#E2E8F0");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append("+"); sb.append(s); }
        loadFeed(sb.toString(), true, mode, force);
    }

    public void loadFeed(final String subOrMulti, final boolean isMulti, final String mode, final boolean force) {
        ui.clear();
        ui.addNavBar(app::onBackPressed,
                () -> { app.cycleFont(); loadFeed(subOrMulti, isMulti, mode, false); },
                () -> loadFeed(subOrMulti, isMulti, mode, true));

        String modeLabel = "random".equals(mode) ? "🎲 Casuale"
                : "day".equals(mode) ? "🔥 Top 24h"
                : "week".equals(mode) ? "📅 Top 7gg"
                : "month".equals(mode) ? "🗓️ Top 30gg" : "🔥 Popolari";

        ui.addTitle(isMulti ? "Miei Seguiti" : ("r/" + subOrMulti), "#FB923C");
        ui.addSub(modeLabel);

        if (!isMulti) {
            boolean fol = app.isFollowed(subOrMulti);
            ui.addPill(ui.contentBox, fol ? "✓ Seguito" : "+ Segui r/" + subOrMulti,
                    fol ? "#132A22" : "#15803D", fol ? "#1B3B2F" : "#166534",
                    fol ? "#10B981" : null, fol ? "#34D399" : "#FFFFFF", v -> {
                        app.toggleFollow(subOrMulti);
                        loadFeed(subOrMulti, false, mode, false);
                    });
        }

        LinearLayout sortRow = new LinearLayout(app);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.setMargins(0, ui.dp(4), 0, ui.dp(8));
        sortRow.setLayoutParams(srLp);

        String[][] filters = {{"🎲", "random"}, {"24h", "day"}, {"7gg", "week"}, {"30gg", "month"}};
        for (int i = 0; i < filters.length; i++) {
            final String fc = filters[i][1];
            boolean act = fc.equals(mode);
            Button sb = ui.chip(filters[i][0], act ? "#EA580C" : "#141923", act ? "#C2410C" : "#1F2736",
                    act ? null : "#2A3447", act ? "#FFFFFF" : "#94A3B8");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) clp.leftMargin = ui.dp(4);
            sb.setLayoutParams(clp);
            sb.setOnClickListener(v -> loadFeed(subOrMulti, isMulti, fc, "random".equals(fc)));
            sortRow.addView(sb);
        }
        ui.contentBox.addView(sortRow);

        final TextView loading = ui.addBody(ui.contentBox, "Caricamento post da Reddit...", 13f, "#E2E8F0");

        pool.execute(() -> {
            try {
                String enc = URLEncoder.encode(subOrMulti, "UTF-8").replace("%2B", "+");
                String api = ("day".equals(mode) || "week".equals(mode) || "month".equals(mode))
                        ? "https://www.reddit.com/r/" + enc + "/top.json?t=" + mode + "&limit=20&raw_json=1"
                        : "random".equals(mode)
                        ? "https://www.reddit.com/r/" + enc + "/top.json?t=week&limit=28&raw_json=1"
                        : "https://www.reddit.com/r/" + enc + "/hot.json?limit=18&raw_json=1";

                String rawJson = net.getCached(api, NetHelper.UA_REDDIT, force);
                JSONArray children = new JSONObject(rawJson).getJSONObject("data").getJSONArray("children");
                final List<JSONObject> posts = new ArrayList<>();
                for (int i = 0; i < children.length(); i++) {
                    JSONObject p = children.optJSONObject(i).optJSONObject("data");
                    if (p != null) posts.add(p);
                }
                if ("random".equals(mode)) {
                    Collections.shuffle(posts);
                    if (posts.size() > 16) posts.subList(16, posts.size()).clear();
                }

                app.runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (posts.isEmpty()) {
                        ui.addBody(ui.contentBox, "Nessun post trovato.", 13f, "#E2E8F0");
                        return;
                    }
                    List<String> origTitles = new ArrayList<>();
                    List<TextView> titleViews = new ArrayList<>();
                    for (JSONObject p : posts) {
                        String t = p.optString("title", "(Senza titolo)");
                        origTitles.add(t);
                        titleViews.add(renderCard(subOrMulti, isMulti, mode, p, t));
                    }
                    if (app.autoTranslateIt) {
                        net.translateTitlesAsync(origTitles, titleViews, app);
                    }
                });
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore Reddit (" + e.getClass().getSimpleName() + "): " + e.getMessage() + "\nVerifica BtBridge."));
            }
        });
    }

    private TextView renderCard(final String subOrMulti, final boolean isMulti, final String mode,
                                JSONObject post, final String shownTitle) {
        LinearLayout card = ui.card();
        String postSub = post.optString("subreddit", "");
        String author = post.optString("author", "?");
        int score = post.optInt("score", 0);
        int numComm = post.optInt("num_comments", 0);
        final String permalink = "https://www.reddit.com" + post.optString("permalink", "");
        String selfText = post.optString("selftext", "");
        final String postUrl = post.optString("url", "");

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

        ui.addBody(card, "u/" + author + "  •  ▲ " + score + "  •  💬 " + numComm, 10.5f, "#94A3B8");
        final TextView tvTitle = ui.addBody(card, shownTitle, 14f, "#F8FAFC");
        tvTitle.setTypeface(null, Typeface.BOLD);

        if (!app.autoTranslateIt) {
            final Button trBtn = ui.pill("🌐 Traduci in IT", "#172033", "#1E293B", "#0284C7", "#38BDF8");
            trBtn.setOnClickListener(v -> {
                trBtn.setText("..."); trBtn.setEnabled(false);
                net.transPool.execute(() -> {
                    final String tr = net.translateIt(shownTitle);
                    app.runOnUiThread(() -> { tvTitle.setText(tr); card.removeView(trBtn); });
                });
            });
            card.addView(trBtn);
        }

        String vid = extractVideo(post);
        String img = extractImage(post);
        if (img != null && !img.isEmpty()) app.addSmartImage(card, img, 340);
        if (vid != null && !vid.isEmpty()) {
            final String fVid = vid;
            ui.addPill(card, "▶ Riproduci Video", "#EA580C", "#C2410C", null, "#FFFFFF", v -> ui.playVideo(fVid));
        }
        if (!selfText.isEmpty()) {
            ui.addBody(card, selfText.length() > 140 ? selfText.substring(0, 140) + "..." : selfText, 12f, "#CBD5E1");
        }
        if (!post.optBoolean("is_self", false) && vid == null
                && !postUrl.contains("i.redd.it") && !postUrl.contains("reddit.com/gallery") && postUrl.startsWith("http")) {
            ui.addPill(card, "Leggi articolo web ↗", "#0284C7", "#0369A1", null, "#FFFFFF", v ->
                    app.navigateTo(() -> loadFeed(subOrMulti, isMulti, mode, false), () -> app.loadSmartUrl(postUrl, false)));
        }
        ui.addPill(card, "Commenti (" + numComm + ")", "#1B2332", "#263145", "#334155", "#38BDF8", v ->
                app.navigateTo(() -> loadFeed(subOrMulti, isMulti, mode, false), () -> loadComments(permalink, tvTitle.getText().toString(), false)));

        ui.contentBox.addView(card);
        return tvTitle;
    }

    public void loadComments(final String permalink, final String fallbackTitle, final boolean force) {
        ui.clear();
        ui.addNavBar(app::onBackPressed,
                () -> { app.cycleFont(); loadComments(permalink, fallbackTitle, false); },
                () -> loadComments(permalink, fallbackTitle, true));
        final TextView loading = ui.addBody(ui.contentBox, "Caricamento commenti...", 13f, "#E2E8F0");
        final int gen = net.nextTransGen();

        pool.execute(() -> {
            try {
                String clean = permalink.split("\\?")[0].replaceAll("/$", "");
                JSONArray arr = new JSONArray(net.getCached(clean + ".json?limit=30&depth=4&raw_json=1", NetHelper.UA_REDDIT, force));
                JSONObject postData = arr.getJSONObject(0).getJSONObject("data").getJSONArray("children").getJSONObject(0).getJSONObject("data");
                JSONArray commentsArr = arr.getJSONObject(1).getJSONObject("data").getJSONArray("children");

                final String rawT = postData.optString("title", fallbackTitle);
                final String rawS = postData.optString("selftext", "");

                app.runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    ui.addTitle(rawT, "#F8FAFC");
                    ui.addSub("u/" + postData.optString("author", "?") + " • ▲ " + postData.optInt("score", 0));
                    String img = extractImage(postData);
                    if (img != null && !img.isEmpty()) app.addSmartImage(ui.contentBox, img, 420);
                    String vid = extractVideo(postData);
                    if (vid != null && !vid.isEmpty()) {
                        ui.addPill(ui.contentBox, "▶ Guarda Video", "#EA580C", "#C2410C", null, "#FFFFFF", v -> ui.playVideo(vid));
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
                    ui.addSection("COMMENTI");
                    renderTree(commentsArr, 0, gen);
                });
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore commenti: " + e.getMessage()));
            }
        });
    }

    private void renderTree(JSONArray children, int depth, final int gen) {
        if (children == null || depth > 4) return;
        for (int i = 0; i < children.length(); i++) {
            JSONObject item = children.optJSONObject(i);
            if (item == null || !"t1".equals(item.optString("kind"))) continue;
            JSONObject c = item.optJSONObject("data");
            if (c == null) continue;
            final String orig = c.optString("body", "").trim();
            if (orig.isEmpty()) continue;

            LinearLayout box = depth == 0 ? ui.card() : ui.subCard();
            box.setPadding(ui.dp(12) + Math.min(depth * ui.dp(8), ui.dp(28)), ui.dp(10), ui.dp(12), ui.dp(10));

            TextView head = ui.addBody(box, (depth > 0 ? "↳ " : "") + "u/" + c.optString("author", "?") + " • ▲ " + c.optInt("score", 0), 10.5f, "#38BDF8");
            head.setTypeface(null, Typeface.BOLD);
            final TextView tv = ui.addBody(box, orig, 12.5f, "#E2E8F0");

            if (app.autoTranslateIt) {
                net.transPool.execute(() -> {
                    if (gen != net.transGen) return;
                    final String tr = net.translateIt(orig);
                    if (gen == net.transGen && !tr.equals(orig)) app.runOnUiThread(() -> tv.setText(tr));
                });
            } else {
                final Button trBtn = ui.pill("🌐 Traduci", "#172033", "#1E293B", "#0284C7", "#38BDF8");
                trBtn.setOnClickListener(v -> {
                    trBtn.setText("..."); trBtn.setEnabled(false);
                    net.transPool.execute(() -> {
                        final String tr = net.translateIt(orig);
                        app.runOnUiThread(() -> { tv.setText(tr); box.removeView(trBtn); });
                    });
                });
                box.addView(trBtn);
            }
            Matcher mImg = Pattern.compile("(https?://\\S+\\.(?:jpg|jpeg|png|webp))", Pattern.CASE_INSENSITIVE).matcher(orig);
            if (mImg.find()) app.addSmartImage(box, mImg.group(1), 260);
            ui.contentBox.addView(box);

            Object rep = c.opt("replies");
            if (rep instanceof JSONObject) {
                renderTree(((JSONObject) rep).optJSONObject("data").optJSONArray("children"), depth + 1, gen);
            }
        }
    }

    private String extractVideo(JSONObject post) {
        try {
            JSONObject m = post.optJSONObject("secure_media");
            if (m == null) m = post.optJSONObject("media");
            if (m != null && m.has("reddit_video")) {
                String f = m.getJSONObject("reddit_video").optString("fallback_url", "");
                if (!f.isEmpty()) return f;
            }
            JSONArray cross = post.optJSONArray("crosspost_parent_list");
            if (cross != null && cross.length() > 0) return extractVideo(cross.getJSONObject(0));
        } catch (Exception ignored) {}
        String u = post.optString("url", "");
        return u.endsWith(".mp4") ? u : null;
    }

    private String extractImage(JSONObject post) {
        String u = post.optString("url", "");
        if (u.matches(".*\\.(jpg|jpeg|png|webp)(\\?.*)?$")) return u;
        try {
            JSONObject prev = post.optJSONObject("preview");
            if (prev != null) {
                JSONArray imgs = prev.optJSONArray("images");
                if (imgs != null && imgs.length() > 0) {
                    JSONObject img0 = imgs.getJSONObject(0);
                    JSONArray res = img0.optJSONArray("resolutions");
                    if (res != null && res.length() > 0) return res.getJSONObject(Math.min(2, res.length() - 1)).optString("url", "");
                    return img0.getJSONObject("source").optString("url", "");
                }
            }
        } catch (Exception ignored) {}
        String th = post.optString("thumbnail", "");
        return th.startsWith("http") ? th : null;
    }
}
