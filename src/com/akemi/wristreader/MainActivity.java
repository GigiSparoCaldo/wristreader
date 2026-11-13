package com.akemi.wristreader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Html;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private UiHelper ui;
    private NetHelper net;
    private RedditView reddit;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final List<Runnable> backStack = new ArrayList<>();

    public boolean imagesOnTap = true;
    public boolean autoTranslateIt = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = getSharedPreferences("wrist_prefs", MODE_PRIVATE);
        imagesOnTap = p.getBoolean("images_on_tap", true);
        autoTranslateIt = p.getBoolean("auto_translate_it", true);
        float scale = p.getFloat("font_scale", 1.0f);

        ui = new UiHelper(this, scale);
        net = new NetHelper();
        reddit = new RedditView(this, ui, net, pool);
        showHome();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ui != null && ui.handleRotary(ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (ui != null && ui.isOverlayVisible()) { ui.closeOverlay(); return; }
        if (!backStack.isEmpty()) { backStack.remove(backStack.size() - 1).run(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (ui != null) ui.closeOverlay();
        pool.shutdownNow();
        super.onDestroy();
    }

    public void navigateTo(Runnable restore, Runnable next) {
        if (restore != null) backStack.add(restore);
        next.run();
    }

    public void cycleFont() {
        if (ui.fontScale < 0.95f) ui.fontScale = 1.0f;
        else if (ui.fontScale < 1.15f) ui.fontScale = 1.20f;
        else if (ui.fontScale < 1.35f) ui.fontScale = 1.40f;
        else ui.fontScale = 0.85f;
        getSharedPreferences("wrist_prefs", MODE_PRIVATE).edit().putFloat("font_scale", ui.fontScale).apply();
    }

    private String fontLabel() {
        if (ui.fontScale < 0.95f) return "Piccolo (S)";
        if (ui.fontScale < 1.15f) return "Normale (M)";
        if (ui.fontScale < 1.35f) return "Grande (L)";
        return "Extra (XL)";
    }

    public List<String> getFollowed() {
        String raw = getSharedPreferences("wrist_prefs", MODE_PRIVATE).getString("followed_subs", "italy,WearOS,technology,androiddev");
        List<String> list = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String s : raw.split(",")) {
                String c = s.trim().replaceFirst("^/?r/", "");
                if (!c.isEmpty() && !list.contains(c)) list.add(c);
            }
        }
        return list;
    }

    public boolean isFollowed(String sub) {
        String t = sub.trim().replaceFirst("^/?r/", "");
        for (String s : getFollowed()) if (s.equalsIgnoreCase(t)) return true;
        return false;
    }

    public void toggleFollow(String sub) {
        String t = sub.trim().replaceFirst("^/?r/", "");
        Set<String> set = new LinkedHashSet<>(getFollowed());
        String found = null;
        for (String s : set) if (s.equalsIgnoreCase(t)) { found = s; break; }
        if (found != null) set.remove(found); else set.add(t);
        StringBuilder sb = new StringBuilder();
        for (String s : set) { if (sb.length() > 0) sb.append(","); sb.append(s); }
        getSharedPreferences("wrist_prefs", MODE_PRIVATE).edit().putString("followed_subs", sb.toString()).apply();
    }

    private void showHome() {
        ui.clear();
        ui.addTitle("WristReader", "#38BDF8");
        ui.addSub("Reddit • Google News • Web");

        final EditText input = new EditText(this);
        input.setHint("r/pagina, link o cerca...");
        input.setHintTextColor(Color.parseColor("#64748B"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(ui.sp(13f));
        input.setGravity(Gravity.CENTER);
        input.setBackground(ui.roundBox("#141923", "#2A3447", 24));
        input.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        input.setSingleLine(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(0, ui.dp(4), 0, ui.dp(8));
        input.setLayoutParams(ilp);
        ui.contentBox.addView(input);

        ui.addPill(ui.contentBox, "Apri / Cerca", "#0284C7", "#0369A1", null, "#FFFFFF", v -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) return;
            if (q.startsWith("r/") || q.startsWith("/r/")) {
                String sub = q.replaceFirst("^/?r/", "").trim();
                navigateTo(this::showHome, () -> reddit.loadFeed(sub, false, "hot", false));
            } else if (q.contains("reddit.com/r/")) {
                if (q.endsWith(".rss") || q.contains(".rss?")) navigateTo(this::showHome, () -> loadSmartUrl(q, false));
                else if (q.contains("/comments/")) navigateTo(this::showHome, () -> reddit.loadComments(q, "Post Reddit", false));
                else {
                    String sub = q.replaceAll(".*reddit\\.com/r/([^/]+).*", "$1");
                    navigateTo(this::showHome, () -> reddit.loadFeed(sub, false, "hot", false));
                }
            } else if (q.startsWith("http://") || q.startsWith("https://") || (!q.contains(" ") && q.contains("."))) {
                navigateTo(this::showHome, () -> loadSmartUrl(q.startsWith("http") ? q : "https://" + q, false));
            } else {
                navigateTo(this::showHome, () -> loadSearch(q, false));
            }
        });

        ui.addPill(ui.contentBox, "Cerca su Google News", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC", v -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) {
                navigateTo(this::showHome, () -> loadRss("https://news.google.com/rss?hl=it&gl=IT&ceid=IT:it", "Google News Italia", false));
            } else {
                try {
                    String gUrl = "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=it&gl=IT&ceid=IT:it";
                    navigateTo(this::showHome, () -> loadRss(gUrl, "News: " + q, false));
                } catch (Exception ignored) {}
            }
        });

        ui.addSection("IL MIO FEED REDDIT");
        ui.addPill(ui.contentBox, "🎲 Casuale (Miei Seguiti)", "#EA580C", "#C2410C", null, "#FFFFFF", v ->
                navigateTo(this::showHome, () -> reddit.loadFollowed("random", false)));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.setMargins(0, ui.dp(4), 0, ui.dp(4));
        topRow.setLayoutParams(trLp);

        String[][] periods = {{"🔥 24h", "day"}, {"📅 7gg", "week"}, {"🗓️ 30gg", "month"}};
        for (int i = 0; i < periods.length; i++) {
            final String pc = periods[i][1];
            Button pb = ui.chip(periods[i][0], "#26150E", "#431F12", "#9A3412", "#FDBA74");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) clp.leftMargin = ui.dp(4);
            pb.setLayoutParams(clp);
            pb.setOnClickListener(v -> navigateTo(this::showHome, () -> reddit.loadFollowed(pc, false)));
            topRow.addView(pb);
        }
        ui.contentBox.addView(topRow);

        ui.addSection("ASPETTO & RISPARMIO");
        ui.addPill(ui.contentBox, "🔠 Testo: " + fontLabel(), "#141923", "#222B3A", "#334155", "#E2E8F0", v -> { cycleFont(); showHome(); });
        ui.addPill(ui.contentBox, autoTranslateIt ? "🌐 Traduzione IT: ATTIVA" : "🌐 Traduzione IT: SPENTA",
                "#141923", "#222B3A", autoTranslateIt ? "#0284C7" : "#334155", autoTranslateIt ? "#38BDF8" : "#94A3B8", v -> {
                    autoTranslateIt = !autoTranslateIt;
                    getSharedPreferences("wrist_prefs", MODE_PRIVATE).edit().putBoolean("auto_translate_it", autoTranslateIt).apply();
                    showHome();
                });
        ui.addPill(ui.contentBox, imagesOnTap ? "🖼 Foto: Solo al tocco" : "🖼 Foto: Automatiche",
                "#141923", "#222B3A", imagesOnTap ? "#059669" : "#334155", imagesOnTap ? "#34D399" : "#94A3B8", v -> {
                    imagesOnTap = !imagesOnTap;
                    getSharedPreferences("wrist_prefs", MODE_PRIVATE).edit().putBoolean("images_on_tap", imagesOnTap).apply();
                    showHome();
                });

        ui.addSection("PAGINE SEGUITE");
        List<String> fol = getFollowed();
        if (fol.isEmpty()) ui.addSub("Scrivi r/nome nella barra sopra per seguire.");
        else for (final String s : fol) {
            ui.addPill(ui.contentBox, "r/" + s, "#141923", "#1E2634", "#263042", "#F8FAFC", v ->
                    navigateTo(this::showHome, () -> reddit.loadFeed(s, false, "hot", false)));
        }

        ui.addSection("NOTIZIE & RSS");
        ui.addPill(ui.contentBox, "Google News Italia", "#141923", "#1E2634", "#263042", "#F8FAFC", v ->
                navigateTo(this::showHome, () -> loadRss("https://news.google.com/rss?hl=it&gl=IT&ceid=IT:it", "Google News Italia", false)));
        ui.addPill(ui.contentBox, "Google News Tech", "#141923", "#1E2634", "#263042", "#F8FAFC", v ->
                navigateTo(this::showHome, () -> loadRss("https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=it&gl=IT&ceid=IT:it", "Google News Tech", false)));
        ui.addPill(ui.contentBox, "ANSA Ultima Ora", "#141923", "#1E2634", "#263042", "#F8FAFC", v ->
                navigateTo(this::showHome, () -> loadRss("https://www.ansa.it/sito/notizie/topnews/topnews_rss.xml", "ANSA Top News", false)));
        ui.addPill(ui.contentBox, "Il Post", "#141923", "#1E2634", "#263042", "#F8FAFC", v ->
                navigateTo(this::showHome, () -> loadRss("https://www.ilpost.it/feed/", "Il Post", false)));
    }

    public void loadSmartUrl(final String rawUrl, final boolean force) {
        ui.clear();
        ui.addNavBar(this::onBackPressed, () -> { cycleFont(); loadSmartUrl(rawUrl, false); }, () -> loadSmartUrl(rawUrl, true));
        final TextView loading = ui.addBody(ui.contentBox, "Apertura pagina...", 13f, "#E2E8F0");
        pool.execute(() -> {
            try {
                final String target = net.unwrapUrl(rawUrl);
                String body = net.getCached(target, NetHelper.UA_WEB, force);
                String head = body.substring(0, Math.min(body.length(), 600)).toLowerCase();
                boolean isRss = head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:rdf");
                runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (isRss) renderRss(target, "Feed RSS", body);
                    else renderHtml(target, body);
                });
            } catch (Exception e) {
                runOnUiThread(() -> loading.setText("Errore caricamento: " + e.getMessage()));
            }
        });
    }

    private void loadRss(final String url, final String title, final boolean force) {
        ui.clear();
        ui.addNavBar(this::onBackPressed, () -> { cycleFont(); loadRss(url, title, false); }, () -> loadRss(url, title, true));
        ui.addTitle(title, "#38BDF8");
        final TextView loading = ui.addBody(ui.contentBox, "Scaricamento notizie...", 13f, "#E2E8F0");
        pool.execute(() -> {
            try {
                String xml = net.getCached(url, NetHelper.UA_WEB, force);
                runOnUiThread(() -> { ui.contentBox.removeView(loading); renderRss(url, title, xml); });
            } catch (Exception e) {
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
                if (!it.imageUrl.isEmpty()) addSmartImage(card, it.imageUrl, 300);
                if (!it.summary.isEmpty()) ui.addBody(card, it.summary.length() > 180 ? it.summary.substring(0, 180) + "..." : it.summary, 12f, "#CBD5E1");
                if (!it.link.isEmpty()) {
                    ui.addPill(card, "Leggi Articolo", "#0284C7", "#0369A1", null, "#FFFFFF", v ->
                            navigateTo(() -> loadRss(feedUrl, feedTitle, false), () -> loadSmartUrl(it.link, false)));
                }
                ui.contentBox.addView(card);
            }
        } catch (Exception e) {
            ui.addBody(ui.contentBox, "Errore XML: " + e.getMessage(), 13f, "#E2E8F0");
        }
    }

    private void loadSearch(final String query, final boolean force) {
        ui.clear();
        ui.addNavBar(this::onBackPressed, () -> { cycleFont(); loadSearch(query, false); }, () -> loadSearch(query, true));
        ui.addTitle("Ricerca: " + query, "#38BDF8");
        final TextView loading = ui.addBody(ui.contentBox, "Ricerca sul web...", 13f, "#E2E8F0");
        pool.execute(() -> {
            try {
                String html = net.getCached("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"), NetHelper.UA_WEB, force);
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
                        ui.addPill(ui.contentBox, r[0], "#141923", "#1E2634", "#263042", "#F8FAFC", v ->
                                navigateTo(() -> loadSearch(query, false), () -> loadSmartUrl(r[1], false)));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> loading.setText("Errore ricerca: " + e.getMessage()));
            }
        });
    }

    private void renderHtml(final String pageUrl, String html) {
        String title = net.extractTag(html, "title");
        String cleaned = html.replaceAll("(?is)<script.*?</script>", " ").replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<nav.*?</nav>", " ").replaceAll("(?is)<footer.*?</footer>", " ").replaceAll("(?is)<!--.*?-->", " ");
        Matcher m = Pattern.compile("(?is)<(p|h1|h2|h3|li)\\b[^>]*>(.*?)</\\1>|<img\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*>|<(?:video|source)\\b[^>]*\\bsrc=[\"']([^\"']+\\.mp4[^\"']*)[\"'][^>]*>").matcher(cleaned);
        if (!title.isEmpty()) ui.addTitle(title, "#38BDF8");
        int count = 0, imgs = 0;
        while (m.find() && count < 90) {
            if (m.group(1) != null) {
                String tag = m.group(1).toLowerCase(), txt = net.stripHtml(m.group(2)).trim();
                if (txt.length() >= 25 || tag.startsWith("h")) {
                    if (tag.startsWith("h")) ui.addSection(txt); else ui.addBody(ui.contentBox, txt, 13.5f, "#E2E8F0");
                    count++;
                }
            } else if (m.group(3) != null && imgs < 12) {
                String src = net.resolveUrl(pageUrl, m.group(3));
                if (src != null && !src.endsWith(".svg") && !src.contains("pixel") && !src.contains("icon")) {
                    addSmartImage(ui.contentBox, src, 380); imgs++; count++;
                }
            } else if (m.group(4) != null) {
                final String vs = net.resolveUrl(pageUrl, m.group(4));
                if (vs != null) { ui.addPill(ui.contentBox, "▶ Riproduci Video", "#EA580C", "#C2410C", null, "#FFFFFF", v -> ui.playVideo(vs)); count++; }
            }
        }
        Matcher lm = Pattern.compile("(?is)<a\\b[^>]*\\bhref=[\"']([^\"'#]+)[\"'][^>]*>(.*?)</a>").matcher(cleaned);
        List<String[]> links = new ArrayList<>();
        while (lm.find() && links.size() < 15) {
            String href = net.resolveUrl(pageUrl, lm.group(1)), ltxt = net.stripHtml(lm.group(2)).trim();
            if (href != null && href.startsWith("http") && ltxt.length() >= 24 && ltxt.length() <= 140) links.add(new String[]{ltxt, href});
        }
        if (!links.isEmpty()) {
            ui.addSection("ARTICOLI / LINK");
            for (final String[] lk : links) {
                ui.addPill(ui.contentBox, lk[0], "#141923", "#1E2634", "#263042", "#F8FAFC", v ->
                        navigateTo(() -> loadSmartUrl(pageUrl, false), () -> loadSmartUrl(lk[1], false)));
            }
        }
    }

    public void addSmartImage(final LinearLayout container, final String imgUrl, final int maxH) {
        if (!imagesOnTap) {
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true); iv.setMaxHeight(maxH); iv.setPadding(0, ui.dp(4), 0, ui.dp(6));
            container.addView(iv); loadImageAsync(imgUrl, iv); return;
        }
        final Button btn = ui.pill("🖼 Carica foto", "#0E1726", "#16243B", "#1D4ED8", "#60A5FA");
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
                final Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                if (bmp != null) runOnUiThread(() -> { target.setImageBitmap(bmp); target.setOnClickListener(v -> ui.showFullImage(bmp)); });
            } catch (Exception ignored) {}
        });
    }
}
