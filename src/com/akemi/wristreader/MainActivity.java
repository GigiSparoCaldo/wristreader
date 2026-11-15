package com.akemi.wristreader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Html;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_VOICE = 901;
    private UiHelper ui;
    private NetHelper net;
    public StorageHelper store;
    private RedditView reddit;
    private FourChanView fourChan;
    private EditText homeInput;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final List<UiHelper.NavEntry> backStack = new ArrayList<>();
    private long lastBackPressMs = 0L;
    private float touchStartX = 0f, touchStartY = 0f;
    private boolean swipeTriggered = false;

    public boolean imagesOnTap = true;
    public boolean autoTranslateIt = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new StorageHelper(this);
        SharedPreferences p = getSharedPreferences("wrist_prefs", MODE_PRIVATE);
        imagesOnTap = p.getBoolean("images_on_tap", true);
        autoTranslateIt = p.getBoolean("auto_translate_it", true);
        float scale = p.getFloat("font_scale", 1.0f);

        ui = new UiHelper(this, scale);
        net = new NetHelper();
        reddit = new RedditView(this, ui, net, pool);
        fourChan = new FourChanView(this, ui, net, pool);
        showHome();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ui != null) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touchStartX = ev.getX(); touchStartY = ev.getY(); swipeTriggered = false;
            } else if (action == MotionEvent.ACTION_MOVE && !swipeTriggered) {
                float dx = ev.getX() - touchStartX, dy = ev.getY() - touchStartY;
                if (Math.abs(dx) > ui.dp(62) && Math.abs(dx) > Math.abs(dy) * 1.65f) {
                    swipeTriggered = true;
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    onBackPressed();
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP && swipeTriggered) {
                swipeTriggered = false; return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ui != null && ui.handleRotary(ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (ui != null && ui.isOverlayVisible()) { ui.closeOverlay(); return; }
        if (!backStack.isEmpty()) {
            UiHelper.NavEntry prev = backStack.remove(backStack.size() - 1);
            ui.restoreState(prev);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressMs > 2500L) {
            lastBackPressMs = now;
            Toast.makeText(this, "Premi ancora Indietro per chiudere", Toast.LENGTH_SHORT).show();
            return;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (ui != null) { ui.closeOverlay(); ui.shutdownTts(); }
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty() && homeInput != null) {
                homeInput.setText(matches.get(0));
                handleQuery(matches.get(0));
            }
        }
    }

    public void navigateTo(Runnable next) {
        if (backStack.size() >= 8) backStack.remove(0);
        backStack.add(ui.captureState());
        next.run();
    }

    public void cycleFontInPlace() {
        float oldScale = ui.fontScale;
        if (ui.fontScale < 0.95f) ui.fontScale = 1.0f;
        else if (ui.fontScale < 1.15f) ui.fontScale = 1.20f;
        else if (ui.fontScale < 1.35f) ui.fontScale = 1.40f;
        else ui.fontScale = 0.85f;
        getSharedPreferences("wrist_prefs", MODE_PRIVATE).edit().putFloat("font_scale", ui.fontScale).apply();
        ui.rescaleText(ui.contentBox, ui.fontScale / oldScale);
    }

    public void toggleAutoTranslate() {
        autoTranslateIt = !autoTranslateIt;
        getSharedPreferences("wrist_prefs", MODE_PRIVATE).edit().putBoolean("auto_translate_it", autoTranslateIt).apply();
    }

    private String fontLabel() {
        if (ui.fontScale < 0.95f) return "Piccolo (S)";
        if (ui.fontScale < 1.15f) return "Normale (M)";
        if (ui.fontScale < 1.35f) return "Grande (L)";
        return "Extra (XL)";
    }

    private void handleQuery(String raw) {
        String q = raw.trim();
        if (q.isEmpty()) return;
        if (q.matches("^/?[a-z0-9]{1,6}/?$") && !q.contains(".")) {
            navigateTo(() -> fourChan.loadBoard(q, "bump", false));
        } else if (q.contains("4chan.org/") || q.contains("4channel.org/")) {
            Matcher tm = Pattern.compile("4chan(?:nel)?\\.org/([^/]+)/thread/(\\d+)").matcher(q);
            if (tm.find()) {
                final String b = tm.group(1); final long no = Long.parseLong(tm.group(2));
                navigateTo(() -> fourChan.loadThread(b, no, "", false));
            } else {
                String b = q.replaceAll(".*4chan(?:nel)?\\.org/([^/]+).*", "$1");
                navigateTo(() -> fourChan.loadBoard(b, "bump", false));
            }
        } else if (q.startsWith("r/") || q.startsWith("/r/")) {
            String sub = q.replaceFirst("^/?r/", "").trim();
            navigateTo(() -> reddit.loadFeed(sub, false, "hot", false));
        } else if (q.contains("reddit.com/r/")) {
            if (q.endsWith(".rss") || q.contains(".rss?")) navigateTo(() -> loadSmartUrl(q, false));
            else if (q.contains("/comments/")) navigateTo(() -> reddit.loadComments(q, "Post Reddit", "best", false));
            else {
                String sub = q.replaceAll(".*reddit\\.com/r/([^/]+).*", "$1");
                navigateTo(() -> reddit.loadFeed(sub, false, "hot", false));
            }
        } else if (q.startsWith("http://") || q.startsWith("https://") || (!q.contains(" ") && q.contains("."))) {
            navigateTo(() -> loadSmartUrl(q.startsWith("http") ? q : "https://" + q, false));
        } else {
            navigateTo(() -> loadSearch(q, false));
        }
    }

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Parla ora...");
            startActivityForResult(intent, REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "Dettatura vocale non presente", Toast.LENGTH_SHORT).show();
        }
    }

    private void showHome() {
        ui.clear();
        ui.addTitle("WristReader", "#38BDF8");
        ui.addSub("Reddit • 4chan • News • Web");

        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.setMargins(0, ui.dp(4), 0, ui.dp(8));
        barRow.setLayoutParams(brLp);

        homeInput = new EditText(this);
        homeInput.setHint("r/sub, /g/, link...");
        homeInput.setHintTextColor(Color.parseColor("#64748B"));
        homeInput.setTextColor(Color.WHITE);
        homeInput.setTextSize(ui.sp(12.5f));
        homeInput.setGravity(Gravity.CENTER);
        homeInput.setBackground(ui.roundBox("#141923", "#2A3447", 24));
        homeInput.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        homeInput.setSingleLine(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        homeInput.setLayoutParams(ilp);
        barRow.addView(homeInput);

        Button micBtn = ui.chip("🎤", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC");
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = ui.dp(5);
        micBtn.setLayoutParams(mlp);
        micBtn.setOnClickListener(v -> startVoiceInput());
        barRow.addView(micBtn);
        ui.contentBox.addView(barRow);

        ui.addPill(ui.contentBox, "Apri / Cerca Web", "#0284C7", "#0369A1", null, "#FFFFFF",
                v -> handleQuery(homeInput.getText().toString()));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.setMargins(0, ui.dp(2), 0, ui.dp(6));
        searchRow.setLayoutParams(srLp);

        Button bRedS = ui.chip("🔍 Reddit", "#291711", "#431F12", "#EA580C", "#FDBA74");
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = ui.dp(3);
        bRedS.setLayoutParams(lp1);
        bRedS.setOnClickListener(v -> {
            String q = homeInput.getText().toString().trim();
            if (!q.isEmpty()) navigateTo(() -> reddit.searchReddit(q, false));
        });
        searchRow.addView(bRedS);

        Button bNewsS = ui.chip("📰 G.News", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = ui.dp(3);
        bNewsS.setLayoutParams(lp2);
        bNewsS.setOnClickListener(v -> {
            String q = homeInput.getText().toString().trim();
            if (q.isEmpty()) navigateTo(() -> loadRss("https://news.google.com/rss?hl=it&gl=IT&ceid=IT:it", "Google News Italia", false));
            else {
                try {
                    String gUrl = "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=it&gl=IT&ceid=IT:it";
                    navigateTo(() -> loadRss(gUrl, "News: " + q, false));
                } catch (Exception ignored) {}
            }
        });
        searchRow.addView(bNewsS);
        ui.contentBox.addView(searchRow);

        ui.addSection("IL MIO FEED REDDIT");
        ui.addPill(ui.contentBox, "🎲 Casuale (Miei Seguiti)", "#EA580C", "#C2410C", null, "#FFFFFF",
                v -> navigateTo(() -> reddit.loadFollowed("random", false)));

        LinearLayout rRow1 = new LinearLayout(this);
        rRow1.setOrientation(LinearLayout.HORIZONTAL);
        rRow1.setGravity(Gravity.CENTER);
        String[][] m1 = {{"🔥Hot", "hot"}, {"🆕New", "new"}, {"🚀Ascesa", "rising"}};
        for (int i = 0; i < m1.length; i++) {
            final String mc = m1[i][1];
            Button b = ui.chip(m1[i][0], "#26150E", "#431F12", "#9A3412", "#FDBA74");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) clp.leftMargin = ui.dp(4);
            b.setLayoutParams(clp);
            b.setOnClickListener(v -> navigateTo(() -> reddit.loadFollowed(mc, false)));
            rRow1.addView(b);
        }
        ui.contentBox.addView(rRow1);

        LinearLayout rRow2 = new LinearLayout(this);
        rRow2.setOrientation(LinearLayout.HORIZONTAL);
        rRow2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.setMargins(0, ui.dp(4), 0, ui.dp(4));
        rRow2.setLayoutParams(r2Lp);
        String[][] m2 = {{"24h", "day"}, {"7gg", "week"}, {"30gg", "month"}, {"1A", "year"}, {"All", "all"}};
        for (int i = 0; i < m2.length; i++) {
            final String mc = m2[i][1];
            Button b = ui.chip(m2[i][0], "#26150E", "#431F12", "#9A3412", "#FDBA74");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) clp.leftMargin = ui.dp(3);
            b.setLayoutParams(clp);
            b.setOnClickListener(v -> navigateTo(() -> reddit.loadFollowed(mc, false)));
            rRow2.addView(b);
        }
        ui.contentBox.addView(rRow2);

        ui.addSection("4CHAN BOARDS");
        for (final String b : store.getFollowedBoards()) {
            ui.addPill(ui.contentBox, "/" + b + "/", "#111F18", "#182E23", "#15803D", "#4ADE80",
                    v -> navigateTo(() -> fourChan.loadBoard(b, "bump", false)));
        }

        ui.addSection("SALVATI & IMPOSTAZIONI");
        int bmCount = store.getBookmarks().length();
        ui.addPill(ui.contentBox, "⭐ Salvati Offline (" + bmCount + ")", "#1F1A10", "#2E2616", "#D97706", "#FBBF24",
                v -> navigateTo(this::showBookmarks));
        ui.addPill(ui.contentBox, "🔠 Testo: " + fontLabel(), "#141923", "#222B3A", "#334155", "#E2E8F0", v -> {
            cycleFontInPlace();
            showHome();
        });
        ui.addPill(ui.contentBox, autoTranslateIt ? "🌐 Traduzione IT: ATTIVA" : "🌐 Traduzione IT: SPENTA",
                "#141923", "#222B3A", autoTranslateIt ? "#0284C7" : "#334155", autoTranslateIt ? "#38BDF8" : "#94A3B8", v -> {
                    toggleAutoTranslate();
                    showHome();
                });
        ui.addPill(ui.contentBox, imagesOnTap ? "🖼 Foto: Solo al tocco" : "🖼 Foto: Automatiche",
                "#141923", "#222B3A", imagesOnTap ? "#059669" : "#334155", imagesOnTap ? "#34D399" : "#94A3B8", v -> {
                    imagesOnTap = !imagesOnTap;
                    getSharedPreferences("wrist_prefs", MODE_PRIVATE).edit().putBoolean("images_on_tap", imagesOnTap).apply();
                    showHome();
                });

        ui.addSection("PAGINE REDDIT SEGUITE");
        for (final String s : store.getFollowedSubs()) {
            ui.addPill(ui.contentBox, "r/" + s, "#141923", "#1E2634", "#263042", "#F8FAFC",
                    v -> navigateTo(() -> reddit.loadFeed(s, false, "hot", false)));
        }

        ui.addSection("NOTIZIE & RSS");
        JSONArray customRss = store.getFollowedRss();
        for (int i = 0; i < customRss.length(); i++) {
            JSONObject ro = customRss.optJSONObject(i);
            if (ro == null) continue;
            final String rName = ro.optString("name", "Feed RSS");
            final String rUrl = ro.optString("url", "");
            if (!rUrl.isEmpty()) {
                ui.addPill(ui.contentBox, "📡 " + rName, "#14263C", "#1E3A5F", "#0284C7", "#7DD3FC",
                        v -> navigateTo(() -> loadRss(rUrl, rName, false)));
            }
        }
        ui.addPill(ui.contentBox, "Google News Italia", "#141923", "#1E2634", "#263042", "#F8FAFC",
                v -> navigateTo(() -> loadRss("https://news.google.com/rss?hl=it&gl=IT&ceid=IT:it", "Google News Italia", false)));
        ui.addPill(ui.contentBox, "Google News Tech", "#141923", "#1E2634", "#263042", "#F8FAFC",
                v -> navigateTo(() -> loadRss("https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=it&gl=IT&ceid=IT:it", "Google News Tech", false)));
        ui.addPill(ui.contentBox, "ANSA Ultima Ora", "#141923", "#1E2634", "#263042", "#F8FAFC",
                v -> navigateTo(() -> loadRss("https://www.ansa.it/sito/notizie/topnews/topnews_rss.xml", "ANSA Top News", false)));
        ui.addPill(ui.contentBox, "Il Post", "#141923", "#1E2634", "#263042", "#F8FAFC",
                v -> navigateTo(() -> loadRss("https://www.ilpost.it/feed/", "Il Post", false)));
    }

    private void showBookmarks() {
        ui.clear();
        ui.addNavBar(this::onBackPressed, this::cycleFontInPlace, null);
        ui.addTitle("⭐ Salvati Offline", "#FBBF24");
        JSONArray arr = store.getBookmarks();
        if (arr.length() == 0) {
            ui.addBody(ui.contentBox, "Nessun contenuto salvato. Usa il tasto '☆ Salva offline' dentro post, thread o articoli.", 13f, "#E2E8F0");
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            LinearLayout card = ui.card();
            ui.addBody(card, o.optString("sub", "") + " • " + o.optString("type", "").toUpperCase(), 10.5f, "#FBBF24");
            TextView tv = ui.addBody(card, o.optString("title", "(Salvato)"), 13.5f, "#F8FAFC");
            tv.setTypeface(null, Typeface.BOLD);
            ui.addPill(card, "Leggi Offline", "#0284C7", "#0369A1", null, "#FFFFFF", v -> navigateTo(() -> {
                ui.clear();
                ui.addNavBar(this::onBackPressed, this::cycleFontInPlace, null);
                final String bTitle = o.optString("title", "");
                final String bBody = o.optString("body", "");
                ui.addTitle(bTitle, "#38BDF8");
                ui.addSub(o.optString("sub", ""));
                final Button spk = ui.pill("🔊 Ascolta", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC");
                spk.setOnClickListener(sv -> ui.toggleSpeak(bTitle + ". " + bBody, spk));
                ui.contentBox.addView(spk);
                ui.addBody(ui.contentBox, bBody, 13.5f, "#E2E8F0");
            }));
            ui.addPill(card, "Rimuovi", "#291711", "#431F12", "#9A3412", "#FCA5A5", v -> {
                store.toggleBookmark(o.optString("id"), "", "", "", "", "");
                showBookmarks();
            });
            ui.contentBox.addView(card);
        }
    }

    public void loadSmartUrl(final String rawUrl, final boolean force) { loadSmartUrl(rawUrl, force, "", ""); }

    public void loadSmartUrl(final String rawUrl, final boolean force, final String fbTitle, final String fbSummary) {
        ui.clear();
        ui.addNavBar(this::onBackPressed, this::cycleFontInPlace, () -> loadSmartUrl(rawUrl, true, fbTitle, fbSummary));
        final TextView loading = ui.addBody(ui.contentBox, "Sblocco link articolo...", 13f, "#E2E8F0");
        pool.execute(() -> {
            try {
                String target = net.unwrapUrl(rawUrl);
                if (target != null && target.contains("news.google.com") && !fbTitle.isEmpty()) {
                    String ddg = net.getCached("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(fbTitle, "UTF-8"), NetHelper.UA_WEB, false);
                    Matcher dm = Pattern.compile("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"").matcher(ddg);
                    while (dm.find()) {
                        String h = Html.fromHtml(dm.group(1), Html.FROM_HTML_MODE_LEGACY).toString();
                        if (h.contains("uddg=")) h = URLDecoder.decode(h.replaceAll(".*uddg=([^&]+).*", "$1"), "UTF-8");
                        if (h.startsWith("http") && !h.contains("google.com")) { target = h; break; }
                    }
                }
                final String finalTarget = target;
                String body = net.getCached(finalTarget, NetHelper.UA_WEB, force, msg -> runOnUiThread(() -> loading.setText(msg)));
                if (body.contains("combinando fonti di notizie") && !fbTitle.isEmpty()) {
                    String ddg = net.getCached("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(fbTitle, "UTF-8"), NetHelper.UA_WEB, false);
                    Matcher dm = Pattern.compile("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"").matcher(ddg);
                    while (dm.find()) {
                        String h = Html.fromHtml(dm.group(1), Html.FROM_HTML_MODE_LEGACY).toString();
                        if (h.contains("uddg=")) h = URLDecoder.decode(h.replaceAll(".*uddg=([^&]+).*", "$1"), "UTF-8");
                        if (h.startsWith("http") && !h.contains("google.com")) { body = net.getCached(h, NetHelper.UA_WEB, force); break; }
                    }
                }
                final String finalBody = body;
                String head = finalBody.substring(0, Math.min(finalBody.length(), 600)).toLowerCase();
                final boolean isRss = head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:rdf");
                runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (isRss) renderRss(finalTarget, "Feed RSS", finalBody);
                    else renderHtml(finalTarget, finalBody, fbTitle, fbSummary);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (!fbTitle.isEmpty()) ui.addTitle(fbTitle, "#38BDF8");
                    if (!fbSummary.isEmpty()) ui.addBody(ui.contentBox, fbSummary, 13.5f, "#E2E8F0");
                    ui.addSub("Sito non apribile (" + e.getMessage() + ")");
                });
            }
        });
    }

    private void loadRss(final String url, final String title, final boolean force) {
        ui.clear();
        ui.addNavBar(this::onBackPressed, this::cycleFontInPlace, () -> loadRss(url, title, true));
        ui.addTitle(title, "#38BDF8");

        boolean fol = store.isRssFollowed(url);
        final Button followRssBtn = ui.pill(fol ? "✓ Feed RSS Seguito" : "+ Segui Feed RSS",
                fol ? "#132A22" : "#15803D", fol ? "#1B3B2F" : "#166534",
                fol ? "#10B981" : null, fol ? "#34D399" : "#FFFFFF");
        followRssBtn.setOnClickListener(v -> {
            store.toggleRss(title, url);
            boolean now = store.isRssFollowed(url);
            followRssBtn.setText(now ? "✓ Feed RSS Seguito" : "+ Segui Feed RSS");
        });
        ui.contentBox.addView(followRssBtn);

        final TextView loading = ui.addBody(ui.contentBox, "Avvio richiesta RSS...", 13f, "#E2E8F0");
        pool.execute(() -> {
            try {
                String xml;
                try { xml = net.getCached(url, NetHelper.UA_WEB, force, msg -> runOnUiThread(() -> loading.setText(msg))); }
                catch (Exception firstErr) {
                    String host = new URL(url).getHost().replaceFirst("^www\\.", "");
                    String gFallback = "https://news.google.com/rss/search?q=site:" + URLEncoder.encode(host, "UTF-8") + "&hl=it&gl=IT&ceid=IT:it";
                    xml = net.getCached(gFallback, NetHelper.UA_WEB, force, msg -> runOnUiThread(() -> loading.setText(msg)));
                }
                final String finalXml = xml;
                runOnUiThread(() -> { ui.contentBox.removeView(loading); renderRss(url, title, finalXml); });
            } catch (Throwable e) {
                runOnUiThread(() -> loading.setText("Errore RSS: " + e.getMessage()));
            }
        });
    }

    private void renderRss(final String feedUrl, final String feedTitle, String xml) {
        try {
            List<NetHelper.RssEntry> entries = net.parseRss(xml);
            if (entries.isEmpty()) { ui.addBody(ui.contentBox, "Nessun articolo trovato.", 13f, "#E2E8F0"); return; }
            for (final NetHelper.RssEntry it : entries) {
                LinearLayout card = ui.card();
                if (!it.pubDate.isEmpty()) ui.addBody(card, it.pubDate, 10f, "#94A3B8");
                TextView tvT = ui.addBody(card, it.title.isEmpty() ? "(Articolo)" : it.title, 14f, "#F8FAFC");
                tvT.setTypeface(null, Typeface.BOLD);
                if (!it.imageUrl.isEmpty()) addSmartImage(card, it.imageUrl, 300, "🖼 Carica foto");
                if (!it.summary.isEmpty()) ui.addBody(card, it.summary.length() > 180 ? it.summary.substring(0, 180) + "..." : it.summary, 12f, "#CBD5E1");
                if (!it.link.isEmpty()) {
                    ui.addPill(card, "Leggi Articolo", "#0284C7", "#0369A1", null, "#FFFFFF",
                            v -> navigateTo(() -> loadSmartUrl(it.link, false, it.title, it.summary)));
                }
                ui.contentBox.addView(card);
            }
        } catch (Throwable e) {
            ui.addBody(ui.contentBox, "Errore XML: " + e.getMessage(), 13f, "#E2E8F0");
        }
    }

    private void loadSearch(final String query, final boolean force) {
        ui.clear();
        ui.addNavBar(this::onBackPressed, this::cycleFontInPlace, () -> loadSearch(query, true));
        ui.addTitle("Ricerca: " + query, "#38BDF8");
        final TextView loading = ui.addBody(ui.contentBox, "Ricerca sul web...", 13f, "#E2E8F0");
        pool.execute(() -> {
            try {
                String html = net.getCached("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"),
                        NetHelper.UA_WEB, force, msg -> runOnUiThread(() -> loading.setText(msg)));
                Matcher m = Pattern.compile("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
                final List<String[]> res = new ArrayList<>();
                while (m.find() && res.size() < 12) {
                    String href = Html.fromHtml(m.group(1), Html.FROM_HTML_MODE_LEGACY).toString();
                    if (href.contains("uddg=")) href = URLDecoder.decode(href.replaceAll(".*uddg=([^&]+).*", "$1"), "UTF-8");
                    String title = net.stripHtml(m.group(2));
                    if (href.startsWith("http") && !title.isEmpty()) res.add(new String[]{title, href});
                }
                runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (res.isEmpty()) { ui.addBody(ui.contentBox, "Nessun risultato.", 13f, "#E2E8F0"); return; }
                    for (final String[] r : res) {
                        ui.addPill(ui.contentBox, r[0], "#141923", "#1E2634", "#263042", "#F8FAFC",
                                v -> navigateTo(() -> loadSmartUrl(r[1], false, r[0], "")));
                    }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> loading.setText("Errore ricerca: " + e.getMessage()));
            }
        });
    }

    private void renderHtml(final String pageUrl, String html, String fbTitle, String fbSummary) {
        String title = net.extractTag(html, "title");
        if (title.isEmpty() || title.equalsIgnoreCase("Google News")) title = fbTitle;
        if (!title.isEmpty()) ui.addTitle(title, "#38BDF8");

        final String bmId = "web_" + pageUrl;
        final String finalTitle = title;
        final StringBuilder collected = new StringBuilder();

        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER);
        boolean saved = store.isBookmarked(bmId);
        final Button bmBtn = ui.chip(saved ? "★ Salvato" : "☆ Salva",
                "#141923", "#1E2634", saved ? "#F59E0B" : "#334155", saved ? "#FBBF24" : "#CBD5E1");
        LinearLayout.LayoutParams bLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bLp1.rightMargin = ui.dp(3);
        bmBtn.setLayoutParams(bLp1);
        bmBtn.setOnClickListener(v -> {
            store.toggleBookmark(bmId, "web", finalTitle, pageUrl, pageUrl, collected.toString());
            bmBtn.setText(store.isBookmarked(bmId) ? "★ Salvato" : "☆ Salva");
        });
        actRow.addView(bmBtn);

        final Button spkBtn = ui.chip("🔊 Ascolta", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC");
        LinearLayout.LayoutParams bLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bLp2.leftMargin = ui.dp(3);
        spkBtn.setLayoutParams(bLp2);
        spkBtn.setOnClickListener(v -> ui.toggleSpeak(finalTitle + ". " + collected.toString(), spkBtn));
        actRow.addView(spkBtn);
        ui.contentBox.addView(actRow);

        String ogImg = "";
        Matcher mOgImg = Pattern.compile("(?is)<meta[^>]+(?:property|name)=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']").matcher(html);
        if (mOgImg.find()) ogImg = net.resolveUrl(pageUrl, mOgImg.group(1));
        if (ogImg != null && !ogImg.isEmpty()) addSmartImage(ui.contentBox, ogImg, 360, "🖼 Foto articolo");

        String cleaned = html.replaceAll("(?is)<script.*?</script>", " ").replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<nav.*?</nav>", " ").replaceAll("(?is)<footer.*?</footer>", " ").replaceAll("(?is)<!--.*?-->", " ");
        Matcher m = Pattern.compile("(?is)<(p|h1|h2|h3|li|blockquote)\\b[^>]*>(.*?)</\\1>|<img\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*>|<(?:video|source)\\b[^>]*\\bsrc=[\"']([^\"']+\\.mp4[^\"']*)[\"'][^>]*>").matcher(cleaned);

        int count = 0, imgs = (ogImg != null && !ogImg.isEmpty()) ? 1 : 0;
        while (m.find() && count < 90) {
            if (m.group(1) != null) {
                String tag = m.group(1).toLowerCase(), txt = net.stripHtml(m.group(2)).trim();
                if (txt.length() >= 25 || tag.startsWith("h")) {
                    if (tag.startsWith("h")) ui.addSection(txt); else ui.addBody(ui.contentBox, txt, 13.5f, "#E2E8F0");
                    collected.append(txt).append("\n\n");
                    count++;
                }
            } else if (m.group(3) != null && imgs < 10) {
                String src = net.resolveUrl(pageUrl, m.group(3));
                if (src != null && !src.equals(ogImg) && !src.endsWith(".svg") && !src.contains("pixel") && !src.contains("icon")) {
                    addSmartImage(ui.contentBox, src, 360, "🖼 Carica foto"); imgs++; count++;
                }
            } else if (m.group(4) != null) {
                final String vs = net.resolveUrl(pageUrl, m.group(4));
                if (vs != null) { ui.addPill(ui.contentBox, "▶ Riproduci Video", "#EA580C", "#C2410C", null, "#FFFFFF", v -> ui.playVideo(vs, net)); count++; }
            }
        }
        if (count == 0 && !fbSummary.isEmpty()) {
            ui.addBody(ui.contentBox, fbSummary, 13.5f, "#E2E8F0");
            collected.append(fbSummary);
        }
    }

    public void addSmartImage(final LinearLayout container, final String imgUrl, final int maxH, final String label) {
        Bitmap cached = ui.bmpCache.get(imgUrl);
        if (!imagesOnTap || cached != null) {
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true); iv.setMaxHeight(maxH); iv.setPadding(0, ui.dp(4), 0, ui.dp(6));
            container.addView(iv);
            if (cached != null) { iv.setImageBitmap(cached); iv.setOnClickListener(v -> ui.showFullImage(cached)); }
            else loadImageAsync(imgUrl, iv);
            return;
        }
        final Button btn = ui.pill(label, "#0E1726", "#16243B", "#1D4ED8", "#60A5FA");
        btn.setOnClickListener(v -> {
            btn.setText("Caricamento..."); btn.setEnabled(false);
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true); iv.setMaxHeight(maxH); iv.setPadding(0, ui.dp(4), 0, ui.dp(6));
            int idx = container.indexOfChild(btn);
            if (idx >= 0) { container.removeView(btn); container.addView(iv, idx); } else container.addView(iv);
            loadImageAsync(imgUrl, iv);
        });
        container.addView(btn);
    }

    private void loadImageAsync(final String imgUrl, final ImageView target) {
        pool.execute(() -> {
            try {
                byte[] b = net.getBytes(imgUrl, NetHelper.UA_WEB);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(b, 0, b.length, o);
                int maxW = Math.max(300, ui.screenWidthPx - ui.dp(48)), sample = 1;
                while (o.outWidth / sample > maxW) sample *= 2;
                o.inJustDecodeBounds = false; o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                if (bmp != null) {
                    ui.bmpCache.put(imgUrl, bmp);
                    runOnUiThread(() -> { target.setImageBitmap(bmp); target.setOnClickListener(v -> ui.showFullImage(bmp)); });
                }
            } catch (Exception ignored) {}
        });
    }
}
