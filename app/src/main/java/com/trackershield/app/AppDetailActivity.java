package com.trackershield.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Everything TrackerShield knows about how one app tracks you. */
public class AppDetailActivity extends Activity implements BlockLog.Listener {
    static final String EXTRA_UID = "uid";
    static final String EXTRA_PKG = "pkg";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DateFormat dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    private final DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
    private boolean renderQueued;
    private int uid;
    private String pkg;
    private LinearLayout content;
    private List<String> access;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uid = getIntent().getIntExtra(EXTRA_UID, BlockLog.UNKNOWN_UID);
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null) pkg = Apps.forUid(this, uid).packageName;
        if (uid == BlockLog.UNKNOWN_UID && pkg != null) uid = Apps.uidOf(this, pkg);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        access = pkg == null ? Collections.<String>emptyList() : Apps.sensitiveAccess(this, pkg);
        BlockLog.get().addListener(this);
        render();
    }

    @Override
    protected void onPause() {
        BlockLog.get().removeListener(this);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onLogChanged() {
        handler.post(() -> {
            if (renderQueued) return;
            renderQueued = true;
            handler.postDelayed(this::render, 1000);
        });
    }

    private void render() {
        renderQueued = false;
        content.removeAllViews();
        Apps.Info info = Apps.forUid(this, uid);
        String label = pkg != null ? Apps.labelOf(getPackageManager(), pkg) : info.label;
        setTitle(label);

        // Header: icon, name, package
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Apps.icon(this, pkg != null ? new Apps.Info(uid, label, pkg, info.packages) : info));
        int size = Ui.dp(this, 56);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
        ilp.rightMargin = Ui.dp(this, 14);
        header.addView(icon, ilp);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, label, 22, true));
        TextView sub = Ui.text(this, pkg != null ? pkg : "System id " + uid, 12, false);
        sub.setTextColor(Ui.GRAY);
        names.addView(sub);
        header.addView(names);
        content.addView(header);

        renderLiveTracking();
        renderBuiltIn();
        renderAccess();
        renderButtons();
    }

    // ---- 1. What it actually did ----

    private void renderLiveTracking() {
        section("Tracking attempts on this phone");
        List<BlockLog.Record> recs = uid == BlockLog.UNKNOWN_UID && pkg != null
                ? Collections.<BlockLog.Record>emptyList() : BlockLog.get().recordsFor(uid);
        if (recs.isEmpty()) {
            para("TrackerShield hasn't seen this app contact any trackers yet. Keep protection on and use the "
                    + "app as normal; anything it tries will show up here.", Ui.GRAY);
            return;
        }
        int attempts = 0, blocked = 0, background = 0, watched = 0;
        long first = Long.MAX_VALUE;
        Map<String, List<BlockLog.Record>> byOwner = new LinkedHashMap<>();
        Map<String, Integer> ownerTotals = new LinkedHashMap<>();
        for (BlockLog.Record r : recs) {
            attempts += r.attempts;
            blocked += r.blocked;
            background += r.background;
            watched += r.watched;
            first = Math.min(first, r.firstSeen);
            List<BlockLog.Record> l = byOwner.get(r.owner);
            if (l == null) {
                l = new ArrayList<>();
                byOwner.put(r.owner, l);
            }
            l.add(r);
            Integer t = ownerTotals.get(r.owner);
            ownerTotals.put(r.owner, (t == null ? 0 : t) + r.attempts);
        }
        int allowed = attempts - blocked;
        para(String.format(Locale.getDefault(),
                "Tried to send data to %d tracking %s %,d times since %s. %,d %s blocked%s.",
                byOwner.size(), byOwner.size() == 1 ? "company" : "companies", attempts,
                dateTime.format(new Date(first)), blocked, blocked == 1 ? "was" : "were",
                allowed > 0 ? String.format(Locale.getDefault(), " and %,d went through because you allowed them", allowed) : ""),
                Ui.RED);
        if (watched > 0) {
            int pct = Math.round(100f * background / watched);
            para(String.format(Locale.getDefault(),
                    "%,d of %,d checked attempts (%d%%) happened while you weren't using the app: in the "
                            + "background or with the screen off.", background, watched, pct),
                    background > 0 ? Ui.RED : Ui.GRAY);
        } else if (!ForegroundWatcher.hasUsageAccess(this)) {
            TextView t = para("Tap here and turn on \"Usage access\" for TrackerShield to see how much of this "
                    + "happens while you aren't using the app.", Ui.GRAY);
            t.setOnClickListener(v -> Ui.openUsageAccessSettings(this));
        }

        List<Map.Entry<String, Integer>> owners = new ArrayList<>(ownerTotals.entrySet());
        Collections.sort(owners, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Blocklist list = Settings.blocklist(this);
        for (Map.Entry<String, Integer> o : owners) {
            TextView ownerHead = Ui.text(this, o.getKey() + "  —  " + o.getValue() + "×", 16, true);
            ownerHead.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
            content.addView(ownerHead);
            for (final BlockLog.Record r : byOwner.get(o.getKey())) {
                boolean isAllowed = list.isAllowed(r.domain);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), 0, Ui.dp(this, 6));
                row.addView(Ui.text(this, r.domain, 15, false));
                String detail = String.format(Locale.getDefault(), "%s · %,d× · last at %s",
                        isAllowed ? "ALLOWED by you" : "BLOCKED", r.attempts, time.format(new Date(r.lastSeen)));
                if (r.watched > 0) {
                    detail += String.format(Locale.getDefault(), " · %,d× in background", r.background);
                }
                TextView d = Ui.text(this, detail + "  (tap to " + (isAllowed ? "block" : "allow") + ")", 13, false);
                d.setTextColor(isAllowed ? Ui.GREEN : Ui.RED);
                row.addView(d);
                row.setOnClickListener(v -> Ui.toggleDomain(this, r.domain, r.owner, this::render));
                row.setBackgroundResource(android.R.drawable.list_selector_background);
                content.addView(row);
            }
        }
    }

    // ---- 2. What it is built to do ----

    private void renderBuiltIn() {
        section("Tracking code built into the app");
        if (pkg == null) {
            para("Not available for parts of Android itself.", Ui.GRAY);
            return;
        }
        AppScanner.Result res = AppScanner.resultFor(this, pkg);
        if (res == null) {
            para(AppScanner.isRunning() || !AppScanner.hasFinished()
                    ? "Not checked yet. Open \"Which apps track me\" and TrackerShield will check every app."
                    : "This is part of Android itself, so its code wasn't checked.", Ui.GRAY);
            return;
        }
        if (res.sdks.isEmpty()) {
            para("No known tracking libraries were found in this app's code.", Ui.GREEN);
            return;
        }
        para(res.sdks.size() + " tracking " + (res.sdks.size() == 1 ? "library is" : "libraries are")
                + " built into this app:", 0xFFE65100);
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (String category : Ui.CATEGORY_MEANING.keySet()) byCategory.put(category, new ArrayList<String>());
        for (DexScanner.Sdk s : res.sdks) {
            List<String> l = byCategory.get(s.category);
            if (l == null) {
                l = new ArrayList<>();
                byCategory.put(s.category, l);
            }
            l.add(s.name);
        }
        for (Map.Entry<String, List<String>> e : byCategory.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            TextView head = Ui.text(this, e.getKey() + ": " + TextUtils.join(", ", e.getValue()), 15, true);
            head.setPadding(0, Ui.dp(this, 8), 0, 0);
            content.addView(head);
            String meaning = Ui.CATEGORY_MEANING.get(e.getKey());
            if (meaning != null) para(meaning, Ui.GRAY);
        }
        para("Built-in code shows what the app is able to do. The section above shows what it actually tried "
                + "on this phone.", Ui.GRAY);
    }

    // ---- 3. What it can reach ----

    private void renderAccess() {
        if (pkg == null) return;
        section("What it can access");
        if (access.isEmpty()) {
            para("You haven't given this app access to anything sensitive.", Ui.GREEN);
        } else {
            for (String a : access) para("•  " + a, 0xFF000000);
            para("Trackers inside an app can use anything the app can access.", Ui.GRAY);
        }
    }

    private void renderButtons() {
        if (pkg == null) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);

        Button settings = new Button(this);
        settings.setText("Change permissions or uninstall");
        settings.setOnClickListener(v -> startActivity(new Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        content.addView(settings, lp);

        final Set<String> skipped = Settings.getSet(this, Settings.KEY_SKIPPED_APPS);
        final boolean isSkipped = skipped.contains(pkg);
        Button skip = new Button(this);
        skip.setText(isSkipped ? "Filter this app again" : "Stop filtering this app");
        skip.setOnClickListener(v -> {
            if (isSkipped) skipped.remove(pkg);
            else skipped.add(pkg);
            Settings.putSet(this, Settings.KEY_SKIPPED_APPS, skipped);
            if (ShieldVpnService.isRunning()) {
                startService(new Intent(this, ShieldVpnService.class).setAction(ShieldVpnService.ACTION_RESTART));
            }
            Ui.toast(this, isSkipped ? "This app is filtered again." : "TrackerShield will leave this app alone.");
            render();
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(lp);
        lp2.topMargin = Ui.dp(this, 4);
        content.addView(skip, lp2);
        if (isSkipped) {
            para("This app is skipped, so its tracking is neither blocked nor recorded.", Ui.RED);
        }
    }

    // ---- Helpers ----

    private void section(String title) {
        TextView t = Ui.text(this, title, 18, true);
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 4));
        content.addView(t);
        View line = new View(this);
        line.setBackgroundColor(0xFFDDDDDD);
        content.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
    }

    private TextView para(String s, int color) {
        TextView t = Ui.text(this, s, 14, false);
        t.setTextColor(color);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        content.addView(t);
        return t;
    }
}
