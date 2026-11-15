package com.akemi.wristreader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class UiHelper {
    public static class NavEntry {
        public final List<View> views = new ArrayList<>();
        public final int scrollY;
        public NavEntry(LinearLayout box, int scrollY) {
            this.scrollY = scrollY;
            for (int i = 0; i < box.getChildCount(); i++) views.add(box.getChildAt(i));
        }
    }

    public final Activity act;
    public final FrameLayout rootFrame;
    public final ScrollView scrollView;
    public final LinearLayout contentBox;
    public final FrameLayout mediaOverlay;
    private VideoView videoView;
    private volatile int videoGen = 0;
    public float fontScale;
    public final float density;
    public final int screenWidthPx;

    public UiHelper(Activity act, float initialScale) {
        this.act = act;
        this.fontScale = initialScale;
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        this.screenWidthPx = dm.widthPixels;
        this.density = dm.density;

        rootFrame = new FrameLayout(act);
        rootFrame.setBackgroundColor(Color.BLACK);

        scrollView = new ScrollView(act);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setFocusable(true);
        scrollView.setFocusableInTouchMode(true);

        int sidePad = Math.max(dp(20), Math.round(screenWidthPx * 0.115f));
        int topPad = Math.max(dp(34), Math.round(dm.heightPixels * 0.15f));
        int botPad = Math.max(dp(48), Math.round(dm.heightPixels * 0.22f));

        contentBox = new LinearLayout(act);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(sidePad, topPad, sidePad, botPad);
        scrollView.addView(contentBox);

        mediaOverlay = new FrameLayout(act);
        mediaOverlay.setBackgroundColor(Color.BLACK);
        mediaOverlay.setVisibility(View.GONE);

        rootFrame.addView(scrollView);
        rootFrame.addView(mediaOverlay);
        act.setContentView(rootFrame);
        scrollView.requestFocus();
    }

    public int dp(int v) { return Math.round(v * density); }
    public float sp(float v) { return v * fontScale; }

    public void clear() {
        contentBox.removeAllViews();
        scrollView.scrollTo(0, 0);
        scrollView.requestFocus();
    }

    public NavEntry captureState() {
        return new NavEntry(contentBox, scrollView.getScrollY());
    }

    public void restoreState(final NavEntry entry) {
        contentBox.removeAllViews();
        for (View v : entry.views) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            contentBox.addView(v);
        }
        scrollView.requestFocus();
        scrollView.post(() -> scrollView.scrollTo(0, entry.scrollY));
    }

    public void rescaleText(View v, float ratio) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * ratio);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) rescaleText(vg.getChildAt(i), ratio);
        }
    }

    public boolean handleRotary(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            if (mediaOverlay.getVisibility() != View.VISIBLE) {
                float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                        * ViewConfiguration.get(act).getScaledVerticalScrollFactor();
                scrollView.scrollBy(0, Math.round(delta));
                return true;
            }
        }
        return false;
    }

    public boolean isOverlayVisible() { return mediaOverlay.getVisibility() == View.VISIBLE; }

    public void closeOverlay() {
        videoGen++;
        try { if (videoView != null) { videoView.stopPlayback(); videoView = null; } } catch (Exception ignored) {}
        mediaOverlay.removeAllViews();
        mediaOverlay.setVisibility(View.GONE);
        scrollView.requestFocus();
    }

    public void showFullImage(Bitmap bmp) {
        mediaOverlay.removeAllViews();
        ImageView full = new ImageView(act);
        full.setImageBitmap(bmp);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setOnClickListener(v -> closeOverlay());
        mediaOverlay.addView(full, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mediaOverlay.setVisibility(View.VISIBLE);
    }

    public void playVideo(final String rawUrl, final NetHelper net) {
        final int gen = ++videoGen;
        mediaOverlay.removeAllViews();
        mediaOverlay.setVisibility(View.VISIBLE);

        final TextView statusTv = new TextView(act);
        statusTv.setText("Scaricamento video...");
        statusTv.setTextColor(Color.parseColor("#38BDF8"));
        statusTv.setTextSize(sp(13f));
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(dp(24), 0, dp(24), 0);
        mediaOverlay.addView(statusTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        Button closeBtn = chip("✕ Chiudi", "#CC0F172A", "#1E293B", "#475569", "#FFFFFF");
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = dp(22);
        closeBtn.setOnClickListener(v -> closeOverlay());
        mediaOverlay.addView(closeBtn, blp);

        new Thread(() -> {
            Set<String> candidates = new LinkedHashSet<>();
            String clean = rawUrl.split("\\?")[0];
            if (clean.contains("v.redd.it")) {
                candidates.add(clean.replaceAll("(?:DASH|CMAF)_\\d+\\.mp4", "DASH_360.mp4"));
                candidates.add(clean.replaceAll("(?:DASH|CMAF)_\\d+\\.mp4", "CMAF_360.mp4"));
                candidates.add(clean.replaceAll("(?:DASH|CMAF)_\\d+\\.mp4", "DASH_240.mp4"));
                candidates.add(clean.replaceAll("(?:DASH|CMAF)_\\d+\\.mp4", "CMAF_240.mp4"));
            }
            candidates.add(rawUrl);

            byte[] videoBytes = null;
            String lastErr = "Nessun flusso video trovato";
            for (String cand : candidates) {
                if (gen != videoGen) return;
                try {
                    byte[] data = net.getBytes(cand, NetHelper.UA_WEB);
                    if (data != null && data.length > 4096) { videoBytes = data; break; }
                } catch (Exception e) { lastErr = e.getMessage(); }
            }

            if (gen != videoGen) return;
            if (videoBytes == null) {
                final String err = lastErr;
                act.runOnUiThread(() -> { if (gen == videoGen) statusTv.setText("Video non disponibile:\n" + err); });
                return;
            }

            try {
                String ext = clean.toLowerCase().endsWith(".webm") ? ".webm" : ".mp4";
                final File outFile = new File(act.getCacheDir(), "wrist_vid" + ext);
                try (FileOutputStream fos = new FileOutputStream(outFile)) { fos.write(videoBytes); }
                act.runOnUiThread(() -> {
                    if (gen != videoGen) return;
                    mediaOverlay.removeView(statusTv);
                    videoView = new VideoView(act);
                    videoView.setLayoutParams(new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                    MediaController mc = new MediaController(act);
                    mc.setAnchorView(videoView);
                    videoView.setMediaController(mc);
                    videoView.setOnErrorListener((mp, what, extra) -> { closeOverlay(); return true; });
                    mediaOverlay.addView(videoView, 0);
                    videoView.setVideoPath(outFile.getAbsolutePath());
                    videoView.requestFocus();
                    videoView.start();
                });
            } catch (final Exception e) {
                act.runOnUiThread(() -> { if (gen == videoGen) statusTv.setText("Errore file video: " + e.getMessage()); });
            }
        }).start();
    }

    public Drawable roundBox(String fillHex, String strokeHex, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(Color.parseColor(fillHex));
        gd.setCornerRadius(dp(radiusDp));
        if (strokeHex != null) gd.setStroke(dp(1), Color.parseColor(strokeHex));
        return gd;
    }

    public Drawable pillDrawable(String normalHex, String pressedHex, String strokeHex, int radiusDp) {
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, roundBox(pressedHex, strokeHex, radiusDp));
        st.addState(new int[]{}, roundBox(normalHex, strokeHex, radiusDp));
        return st;
    }

    public LinearLayout card() { return makeCard("#12161F", "#232B3B"); }
    public LinearLayout subCard() { return makeCard("#0D1118", "#1A2230"); }

    private LinearLayout makeCard(String fill, String stroke) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundBox(fill, stroke, 18));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);
        return c;
    }

    public Button chip(String text, String normalHex, String pressedHex, String strokeHex, String textHex) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(sp(10.5f));
        b.setSingleLine(true);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.parseColor(textHex));
        b.setBackground(pillDrawable(normalHex, pressedHex, strokeHex, 999));
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    public Button pill(String text, String normalHex, String pressedHex, String strokeHex, String textHex) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(sp(12.5f));
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.parseColor(textHex));
        b.setBackground(pillDrawable(normalHex, pressedHex, strokeHex, 999));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    public void addPill(LinearLayout parent, String text, String normalHex, String pressedHex,
                        String strokeHex, String textHex, View.OnClickListener l) {
        Button b = pill(text, normalHex, pressedHex, strokeHex, textHex);
        b.setOnClickListener(l);
        parent.addView(b);
    }

    public void addNavBar(Runnable onBack, Runnable onFont, Runnable onRefresh) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        Button bBack = chip("←", "#181E29", "#263042", "#2D3748", "#FFFFFF");
        bBack.setOnClickListener(v -> onBack.run());
        row.addView(bBack);

        Button bFont = chip("Aa", "#181E29", "#263042", "#2D3748", "#38BDF8");
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.leftMargin = dp(6);
        flp.rightMargin = dp(6);
        bFont.setLayoutParams(flp);
        bFont.setOnClickListener(v -> onFont.run());
        row.addView(bFont);

        if (onRefresh != null) {
            Button bRef = chip("↻", "#181E29", "#263042", "#2D3748", "#34D399");
            bRef.setOnClickListener(v -> onRefresh.run());
            row.addView(bRef);
        }
        contentBox.addView(row);
    }

    public void addTitle(String text, String hex) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(hex));
        tv.setTextSize(sp(17f));
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(4), 0, dp(4));
        contentBox.addView(tv);
    }

    public void addSub(String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#94A3B8"));
        tv.setTextSize(sp(11f));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dp(8));
        contentBox.addView(tv);
    }

    public void addSection(String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#64748B"));
        tv.setTextSize(sp(10.5f));
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(14), 0, dp(4));
        contentBox.addView(tv);
    }

    public TextView addBody(LinearLayout parent, String text, float baseSp, String hex) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(hex));
        tv.setTextSize(sp(baseSp));
        tv.setLineSpacing(dp(2), 1.0f);
        tv.setPadding(0, dp(4), 0, dp(6));
        parent.addView(tv);
        return tv;
    }
}
