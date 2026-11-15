package com.akemi.wristreader;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class FourChanView {
    private final MainActivity app;
    private final UiHelper ui;
    private final NetHelper net;
    private final ExecutorService pool;

    public FourChanView(MainActivity app, UiHelper ui, NetHelper net, ExecutorService pool) {
        this.app = app; this.ui = ui; this.net = net; this.pool = pool;
    }

    public void loadBoard(final String rawBoard, final String sortMode, final boolean force) {
        final String board = rawBoard.trim().replaceAll("^/+|/+$", "").toLowerCase(Locale.US);
        ui.clear();
        ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(), () -> loadBoard(board, sortMode, true));

        String modeLabel = "replies".equals(sortMode) ? "💬 Più Risposte"
                : "images".equals(sortMode) ? "🖼 Più Immagini"
                : "random".equals(sortMode) ? "🎲 Casuale" : "🔥 Bump Order";

        ui.addTitle("/" + board + "/", "#4ADE80");
        ui.addSub(modeLabel);

        LinearLayout actionRow = new LinearLayout(app);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.setMargins(0, ui.dp(2), 0, ui.dp(6));
        actionRow.setLayoutParams(arLp);

        boolean fol = app.store.isBoardFollowed(board);
        Button fBtn = ui.chip(fol ? "✓ Seguita" : "+ Segui",
                fol ? "#132A22" : "#15803D", fol ? "#1B3B2F" : "#166534",
                fol ? "#10B981" : null, fol ? "#34D399" : "#FFFFFF");
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp.rightMargin = ui.dp(3);
        fBtn.setLayoutParams(flp);
        fBtn.setOnClickListener(v -> { app.store.toggleBoard(board); loadBoard(board, sortMode, false); });
        actionRow.addView(fBtn);

        Button trToggle = ui.chip(app.autoTranslateIt ? "🌐 IT: ON" : "🌐 Traduci IT",
                app.autoTranslateIt ? "#0284C7" : "#172033", "#0369A1",
                app.autoTranslateIt ? null : "#0284C7", "#FFFFFF");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(3);
        trToggle.setLayoutParams(tlp);
        trToggle.setOnClickListener(v -> { app.toggleAutoTranslate(); loadBoard(board, sortMode, false); });
        actionRow.addView(trToggle);
        ui.contentBox.addView(actionRow);

        LinearLayout sortRow = new LinearLayout(app);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.setMargins(0, ui.dp(2), 0, ui.dp(8));
        sortRow.setLayoutParams(srLp);

        String[][] filters = {{"Bump", "bump"}, {"💬", "replies"}, {"🖼", "images"}, {"🎲", "random"}};
        for (int i = 0; i < filters.length; i++) {
            final String fc = filters[i][1];
            boolean act = fc.equals(sortMode);
            Button sb = ui.chip(filters[i][0], act ? "#16A34A" : "#141923", act ? "#15803D" : "#1F2736",
                    act ? null : "#2A3447", act ? "#FFFFFF" : "#94A3B8");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) clp.leftMargin = ui.dp(4);
            sb.setLayoutParams(clp);
            sb.setOnClickListener(v -> loadBoard(board, fc, false));
            sortRow.addView(sb);
        }
        ui.contentBox.addView(sortRow);

        final TextView loading = ui.addBody(ui.contentBox, "Caricamento /" + board + "/...", 13f, "#E2E8F0");

        pool.execute(() -> {
            try {
                String jsonStr = net.getCached("https://a.4cdn.org/" + board + "/catalog.json", NetHelper.UA_WEB, force,
                        msg -> app.runOnUiThread(() -> loading.setText(msg)));
                JSONArray pages = new JSONArray(jsonStr);
                final List<JSONObject> threads = new ArrayList<>();
                for (int p = 0; p < pages.length(); p++) {
                    JSONArray tArr = pages.getJSONObject(p).optJSONArray("threads");
                    if (tArr == null) continue;
                    for (int t = 0; t < tArr.length(); t++) {
                        JSONObject th = tArr.optJSONObject(t);
                        if (th != null && th.optInt("sticky", 0) == 0) threads.add(th);
                    }
                }
                if ("replies".equals(sortMode)) {
                    Collections.sort(threads, (a, b) -> Integer.compare(b.optInt("replies"), a.optInt("replies")));
                } else if ("images".equals(sortMode)) {
                    Collections.sort(threads, (a, b) -> Integer.compare(b.optInt("images"), a.optInt("images")));
                } else if ("random".equals(sortMode)) {
                    Collections.shuffle(threads);
                }

                app.runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (threads.isEmpty()) { ui.addBody(ui.contentBox, "Nessun thread trovato.", 13f, "#E2E8F0"); return; }
                    renderCatalogSlice(board, threads, 0);
                });
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore 4chan: " + e.getMessage()));
            }
        });
    }

    private void renderCatalogSlice(final String board, final List<JSONObject> all, final int offset) {
        int end = Math.min(offset + 18, all.size());
        List<String> origTexts = new ArrayList<>();
        List<TextView> textViews = new ArrayList<>();

        for (int i = offset; i < end; i++) {
            JSONObject th = all.get(i);
            LinearLayout card = ui.card();
            final long no = th.optLong("no");
            int rep = th.optInt("replies", 0);
            int imgs = th.optInt("images", 0);
            final String sub = cleanHtmlText(th.optString("sub", ""));
            final String com = cleanHtmlText(th.optString("com", ""));
            final String prev = com.length() > 180 ? com.substring(0, 180) + "..." : com;

            ui.addBody(card, "No." + no + "  •  💬 " + rep + "  •  🖼 " + imgs, 10.5f, "#94A3B8");
            final TextView tvSub = !sub.isEmpty() ? ui.addBody(card, sub, 14f, "#4ADE80") : null;
            if (tvSub != null) { tvSub.setTypeface(null, Typeface.BOLD); origTexts.add(sub); textViews.add(tvSub); }
            final TextView tvCom = !prev.isEmpty() ? ui.addBody(card, format4chanSpans(prev, null), 12.5f, "#E2E8F0") : null;
            if (tvCom != null) { origTexts.add(prev); textViews.add(tvCom); }

            long tim = th.optLong("tim", 0);
            if (tim > 0) app.addSmartImage(card, "https://i.4cdn.org/" + board + "/" + tim + "s.jpg", 280, "🖼 Anteprima OP");

            final String titleForNav = !sub.isEmpty() ? sub : ("Thread #" + no);
            ui.addPill(card, "Apri Thread (" + (rep + 1) + " post)", "#1B2332", "#263145", "#334155", "#4ADE80",
                    v -> app.navigateTo(() -> loadThread(board, no, titleForNav, false)));
            ui.contentBox.addView(card);
        }

        if (app.autoTranslateIt && !origTexts.isEmpty()) net.translateTitlesAsync(origTexts, textViews, app);

        if (end < all.size()) {
            final Button moreBtn = ui.pill("Carica altri thread ↓ (" + (all.size() - end) + ")", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC");
            moreBtn.setOnClickListener(v -> { ui.contentBox.removeView(moreBtn); renderCatalogSlice(board, all, end); });
            ui.contentBox.addView(moreBtn);
        }
    }

    public void loadThread(final String board, final long threadNo, final String fallbackSub, final boolean force) {
        ui.clear();
        ui.addNavBar(app::onBackPressed, () -> app.cycleFontInPlace(), () -> loadThread(board, threadNo, fallbackSub, true));
        final TextView loading = ui.addBody(ui.contentBox, "Caricamento thread /" + board + "/#" + threadNo + "...", 13f, "#E2E8F0");
        final int gen = net.nextTransGen();

        pool.execute(() -> {
            try {
                String jsonStr = net.getCached("https://a.4cdn.org/" + board + "/thread/" + threadNo + ".json", NetHelper.UA_WEB, force,
                        msg -> app.runOnUiThread(() -> loading.setText(msg)));
                final JSONArray posts = new JSONObject(jsonStr).getJSONArray("posts");
                final Map<Long, JSONObject> postMap = new HashMap<>();
                for (int i = 0; i < posts.length(); i++) {
                    JSONObject p = posts.optJSONObject(i);
                    if (p != null) postMap.put(p.optLong("no"), p);
                }

                app.runOnUiThread(() -> {
                    ui.contentBox.removeView(loading);
                    if (posts.length() == 0) { ui.addBody(ui.contentBox, "Thread vuoto o archiviato.", 13f, "#E2E8F0"); return; }

                    JSONObject op = posts.optJSONObject(0);
                    final String opSub = cleanHtmlText(op != null ? op.optString("sub", fallbackSub) : fallbackSub);
                    final String displayTitle = opSub.isEmpty() ? ("/" + board + "/ #" + threadNo) : opSub;

                    final TextView tvTitle = ui.addBody(ui.contentBox, displayTitle, 17f, "#4ADE80");
                    tvTitle.setTypeface(null, Typeface.BOLD);
                    tvTitle.setGravity(Gravity.CENTER);
                    if (app.autoTranslateIt && !opSub.isEmpty()) {
                        net.transPool.execute(() -> {
                            if (gen != net.transGen) return;
                            final String trT = net.translateIt(opSub);
                            if (gen == net.transGen && !trT.equals(opSub)) app.runOnUiThread(() -> tvTitle.setText(trT));
                        });
                    }
                    ui.addSub(posts.length() + " post nel thread");

                    ui.addPill(ui.contentBox, app.autoTranslateIt ? "🌐 Traduzione IT: ATTIVA" : "🌐 Traduci tutto in IT",
                            app.autoTranslateIt ? "#0284C7" : "#172033", "#0369A1", "#0284C7", "#FFFFFF", v -> {
                                app.toggleAutoTranslate();
                                loadThread(board, threadNo, fallbackSub, false);
                            });

                    final String bmId = "4chan_" + board + "_" + threadNo;
                    boolean saved = app.store.isBookmarked(bmId);
                    final Button bmBtn = ui.pill(saved ? "★ Salvato offline" : "☆ Salva thread offline",
                            "#141923", "#1E2634", saved ? "#F59E0B" : "#334155", saved ? "#FBBF24" : "#CBD5E1");
                    bmBtn.setOnClickListener(v -> {
                        StringBuilder dump = new StringBuilder();
                        for (int k = 0; k < Math.min(posts.length(), 40); k++) {
                            JSONObject p = posts.optJSONObject(k);
                            if (p != null) dump.append("No.").append(p.optLong("no")).append(": ")
                                    .append(cleanHtmlText(p.optString("com", ""))).append("\n\n");
                        }
                        app.store.toggleBookmark(bmId, "4chan", tvTitle.getText().toString(),
                                "/" + board + "/", "https://boards.4chan.org/" + board + "/thread/" + threadNo, dump.toString());
                        boolean now = app.store.isBookmarked(bmId);
                        bmBtn.setText(now ? "★ Salvato offline" : "☆ Salva thread offline");
                    });
                    ui.contentBox.addView(bmBtn);

                    renderThreadSlice(board, posts, 0, postMap);
                });
            } catch (Throwable e) {
                app.runOnUiThread(() -> loading.setText("Errore thread: " + e.getMessage()));
            }
        });
    }

    private void renderThreadSlice(final String board, final JSONArray posts, final int offset, final Map<Long, JSONObject> postMap) {
        int end = Math.min(offset + 25, posts.length());
        List<String> origTexts = new ArrayList<>();
        List<TextView> textViews = new ArrayList<>();

        for (int i = offset; i < end; i++) {
            JSONObject pObj = posts.optJSONObject(i);
            if (pObj != null) renderPostCard(board, pObj, i == 0, postMap, origTexts, textViews);
        }

        if (app.autoTranslateIt && !origTexts.isEmpty()) {
            net.translateTitlesAsync(origTexts, textViews, app);
        }

        if (end < posts.length()) {
            final Button moreBtn = ui.pill("Carica altri post ↓ (" + (posts.length() - end) + ")", "#14263C", "#1E3A5F", "#2563EB", "#7DD3FC");
            moreBtn.setOnClickListener(v -> {
                ui.contentBox.removeView(moreBtn);
                renderThreadSlice(board, posts, end, postMap);
            });
            ui.contentBox.addView(moreBtn);
        }
    }

    private void renderPostCard(String board, JSONObject p, boolean isOp, final Map<Long, JSONObject> postMap,
                                List<String> origTexts, List<TextView> textViews) {
        LinearLayout box = isOp ? ui.card() : ui.subCard();
        long no = p.optLong("no");
        String name = p.optString("name", "Anonymous");
        long timeSec = p.optLong("time", 0);
        String timeStr = timeSec > 0 ? new SimpleDateFormat("HH:mm dd/MM", Locale.ITALY).format(new Date(timeSec * 1000L)) : "";

        TextView head = ui.addBody(box, (isOp ? "[OP] " : "") + name + " • No." + no + (timeStr.isEmpty() ? "" : " • " + timeStr),
                10.5f, isOp ? "#4ADE80" : "#38BDF8");
        head.setTypeface(null, Typeface.BOLD);

        long tim = p.optLong("tim", 0);
        String ext = p.optString("ext", "");
        if (tim > 0 && !ext.isEmpty()) {
            final String mediaUrl = "https://i.4cdn.org/" + board + "/" + tim + ext;
            if (".webm".equalsIgnoreCase(ext) || ".mp4".equalsIgnoreCase(ext)) {
                ui.addPill(box, "▶ Riproduci Video (" + ext.toUpperCase() + ")", "#EA580C", "#C2410C", null, "#FFFFFF",
                        v -> ui.playVideo(mediaUrl, net));
            } else {
                int w = p.optInt("w", 0), h = p.optInt("h", 0);
                String lbl = w > 0 ? ("🖼 Foto (" + w + "x" + h + ")") : "🖼 Carica foto";
                app.addSmartImage(box, mediaUrl, 360, lbl);
            }
        }

        final String comText = cleanHtmlText(p.optString("com", ""));
        if (!comText.isEmpty()) {
            final TextView tv = ui.addBody(box, format4chanSpans(comText, postMap), 12.5f, "#E2E8F0");
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            tv.setFocusable(false);
            if (comText.length() <= 220) {
                origTexts.add(comText);
                textViews.add(tv);
            } else if (app.autoTranslateIt) {
                net.transPool.execute(() -> {
                    final String tr = net.translateIt(comText);
                    if (!tr.equals(comText)) app.runOnUiThread(() -> tv.setText(format4chanSpans(tr, postMap)));
                });
            }
        }
        ui.contentBox.addView(box);
    }

    private String cleanHtmlText(String html) {
        if (html == null || html.isEmpty()) return "";
        String br = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n");
        return Html.fromHtml(br, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private CharSequence format4chanSpans(String text, final Map<Long, JSONObject> postMap) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(text);
        String[] lines = text.split("\n");
        int pos = 0;
        for (String line : lines) {
            if (line.startsWith(">") && !line.startsWith(">>")) {
                ssb.setSpan(new ForegroundColorSpan(Color.parseColor("#4ADE80")),
                        pos, pos + line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            pos += line.length() + 1;
        }
        Matcher m = Pattern.compile(">>(\\d+)").matcher(text);
        while (m.find()) {
            final long targetNo = Long.parseLong(m.group(1));
            if (postMap != null && postMap.containsKey(targetNo)) {
                ssb.setSpan(new ClickableSpan() {
                    @Override public void onClick(View widget) {
                        JSONObject qp = postMap.get(targetNo);
                        String qTxt = qp != null ? cleanHtmlText(qp.optString("com", "(Solo immagine)")) : "";
                        ui.showPopupCard("Post citato No." + targetNo, format4chanSpans(qTxt, null));
                    }
                    @Override public void updateDrawState(TextPaint ds) {
                        ds.setColor(Color.parseColor("#38BDF8"));
                        ds.setUnderlineText(true);
                    }
                }, m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                ssb.setSpan(new ForegroundColorSpan(Color.parseColor("#38BDF8")),
                        m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return ssb;
    }
}
