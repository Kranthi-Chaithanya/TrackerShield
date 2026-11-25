package com.trackershield.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ranks the apps on the phone by how much they track you. */
public class AppsActivity extends Activity implements BlockLog.Listener, AppScanner.Listener {
    /** Everything known about one app. */
    private static final class Row {
        int uid;
        String pkg;
        String label;
        BlockLog.AppSummary live;
        /** Null if the app hasn't been checked yet. */
        List<DexScanner.Sdk> sdks;
        List<String> access = Collections.emptyList();

        int attempts() {
            return live == null ? 0 : live.attempts;
        }

        int sdkCount() {
            return sdks == null ? 0 : sdks.size();
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, List<String>> accessCache = new HashMap<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Map<String, String> labelCache = new HashMap<>();
    private boolean refreshQueued;
    private List<ApplicationInfo> launcherApps = Collections.emptyList();
    private TextView protectionPrompt;
    private TextView usagePrompt;
    private TextView scanStatus;
    private TextView summary;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Which apps track me");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        int pad = Ui.dp(this, 16);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, 0, 0, Ui.dp(this, 8));

        TextView intro = Ui.text(this, "Apps are ranked by how often they tried to send your data to tracking "
                + "companies. Tap an app to see exactly which companies, what tracking code is built into it, "
                + "and what it can access.", 14, false);
        intro.setTextColor(Ui.GRAY);
        header.addView(intro);

        protectionPrompt = prompt("Protection is off, so new tracking attempts aren't being recorded. "
                + "Tap here and turn it on.");
        protectionPrompt.setOnClickListener(v -> finish());
        header.addView(protectionPrompt);

        usagePrompt = prompt("See which apps track you while you're NOT using them: tap here and turn on "
                + "\"Usage access\" for TrackerShield.");
        usagePrompt.setOnClickListener(v -> Ui.openUsageAccessSettings(this));
        header.addView(usagePrompt);

        scanStatus = Ui.text(this, "", 13, false);
        scanStatus.setTextColor(Ui.GRAY);
        scanStatus.setPadding(0, Ui.dp(this, 12), 0, 0);
        header.addView(scanStatus);

        summary = Ui.text(this, "", 15, true);
        summary.setPadding(0, Ui.dp(this, 8), 0, 0);
        header.addView(summary);

        ListView list = new ListView(this);
        list.setPadding(pad, pad, pad, pad);
        list.setClipToPadding(false);
        list.addHeaderView(header, null, false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (!(item instanceof Row)) return;
            Row r = (Row) item;
            startActivity(new Intent(this, AppDetailActivity.class)
                    .putExtra(AppDetailActivity.EXTRA_UID, r.uid)
                    .putExtra(AppDetailActivity.EXTRA_PKG, r.pkg));
        });
        setContentView(list);
    }

    private TextView prompt(String s) {
        TextView t = Ui.text(this, s, 14, false);
        t.setTextColor(Ui.RED);
        t.setBackgroundColor(0xFFFFEBEE);
        int p = Ui.dp(this, 12);
        t.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        t.setLayoutParams(lp);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Apps.refresh();
        accessCache.clear();
        launcherApps = Apps.launcherApps(this);
        BlockLog.get().addListener(this);
        AppScanner.addListener(this);
        AppScanner.scanInstalledApps(this);
        refresh();
    }

    @Override
    protected void onPause() {
        BlockLog.get().removeListener(this);
        AppScanner.removeListener(this);
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
        queueRefresh();
    }

    @Override
    public void onScanChanged() {
        queueRefresh();
    }

    private void queueRefresh() {
        handler.post(() -> {
            if (refreshQueued) return;
            refreshQueued = true;
            handler.postDelayed(this::refresh, 500);
        });
    }

    private void refresh() {
        refreshQueued = false;
        protectionPrompt.setVisibility(ShieldVpnService.isRunning() ? View.GONE : View.VISIBLE);
        usagePrompt.setVisibility(ForegroundWatcher.hasUsageAccess(this) ? View.GONE : View.VISIBLE);

        if (AppScanner.isRunning()) {
            String now = AppScanner.current();
            scanStatus.setText(String.format(Locale.getDefault(),
                    "Checking apps for built-in tracking code: %d of %d%s…", AppScanner.done(),
                    AppScanner.total(), now.isEmpty() ? "" : " (" + now + ")"));
        } else if (AppScanner.hasFinished()) {
            scanStatus.setText(String.format(Locale.getDefault(),
                    "Finished checking %d apps for built-in tracking code.", AppScanner.total()));
        } else {
            scanStatus.setText("");
        }

        List<Row> rows = buildRows();
        int tracking = 0;
        int withSdks = 0;
        for (Row r : rows) {
            if (r.attempts() > 0) tracking++;
            if (r.sdkCount() > 0) withSdks++;
        }
        String tried = tracking == 1 ? "1 app tried" : tracking + " apps tried";
        String built = withSdks == 1 ? "1 app has" : withSdks + " apps have";
        summary.setText(rows.isEmpty() ? "Nothing found yet. Keep protection on and use your apps."
                : tried + " to contact trackers. " + built + " tracking code built in.");
        adapter.setRows(rows);
    }

    private List<Row> buildRows() {
        PackageManager pm = getPackageManager();
        Map<Integer, Row> byUid = new HashMap<>();
        for (BlockLog.AppSummary a : BlockLog.get().apps()) {
            Row r = new Row();
            r.uid = a.uid;
            r.live = a;
            Apps.Info info = Apps.forUid(this, a.uid);
            r.pkg = info.packageName;
            r.label = info.label;
            byUid.put(a.uid, r);
        }
        for (ApplicationInfo ai : launcherApps) {
            Row r = byUid.get(ai.uid);
            if (r == null) {
                r = new Row();
                r.uid = ai.uid;
                String label = labelCache.get(ai.packageName);
                if (label == null) {
                    label = Apps.labelOf(pm, ai.packageName);
                    labelCache.put(ai.packageName, label);
                }
                r.label = label;
                byUid.put(ai.uid, r);
            }
            r.pkg = ai.packageName;
        }
        List<Row> rows = new ArrayList<>();
        for (Row r : byUid.values()) {
            if (r.pkg != null) {
                AppScanner.Result res = AppScanner.resultFor(this, r.pkg);
                r.sdks = res == null ? null : res.sdks;
                List<String> access = accessCache.get(r.pkg);
                if (access == null) {
                    access = Apps.sensitiveAccess(this, r.pkg);
                    accessCache.put(r.pkg, access);
                }
                r.access = access;
            }
            if (r.attempts() > 0 || r.sdkCount() > 0) rows.add(r);
        }
        Collections.sort(rows, (a, b) -> {
            int c = Integer.compare(b.attempts(), a.attempts());
            if (c != 0) return c;
            c = Integer.compare(b.sdkCount(), a.sdkCount());
            return c != 0 ? c : a.label.compareToIgnoreCase(b.label);
        });
        return rows;
    }

    private Drawable iconFor(Row r) {
        String key = r.pkg != null ? r.pkg : "uid:" + r.uid;
        Drawable d = iconCache.get(key);
        if (d == null) {
            d = Apps.icon(this, Apps.forUid(this, r.uid));
            if (r.pkg != null) {
                try {
                    d = getPackageManager().getApplicationIcon(r.pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            iconCache.put(key, d);
        }
        return d;
    }

    private final class RowAdapter extends BaseAdapter {
        private List<Row> rows = Collections.emptyList();

        void setRows(List<Row> list) {
            rows = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                h = new Holder();
                LinearLayout row = new LinearLayout(AppsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = Ui.dp(AppsActivity.this, 10);
                row.setPadding(0, p, 0, p);
                h.icon = new ImageView(AppsActivity.this);
                int size = Ui.dp(AppsActivity.this, 44);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
                ilp.rightMargin = Ui.dp(AppsActivity.this, 14);
                row.addView(h.icon, ilp);
                LinearLayout texts = new LinearLayout(AppsActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                h.title = Ui.text(AppsActivity.this, "", 17, true);
                h.live = Ui.text(AppsActivity.this, "", 14, false);
                h.sdks = Ui.text(AppsActivity.this, "", 13, false);
                h.access = Ui.text(AppsActivity.this, "", 13, false);
                h.access.setTextColor(Ui.GRAY);
                texts.addView(h.title);
                texts.addView(h.live);
                texts.addView(h.sdks);
                texts.addView(h.access);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }
            Row r = rows.get(position);
            h.icon.setImageDrawable(iconFor(r));
            h.title.setText(r.label);

            if (r.live != null) {
                String s = String.format(Locale.getDefault(), "Tried to contact trackers %,d× (%d %s)",
                        r.live.attempts, r.live.owners.size(), r.live.owners.size() == 1 ? "company" : "companies");
                if (r.live.watched > 0 && r.live.background > 0) {
                    s += String.format(Locale.getDefault(), " · %,d× while you weren't using it", r.live.background);
                }
                h.live.setText(s);
                h.live.setTextColor(Ui.RED);
            } else {
                h.live.setText("No tracking attempts seen yet");
                h.live.setTextColor(Ui.GRAY);
            }

            if (r.sdks == null) {
                // Parts of Android itself aren't checked; say "not yet" only while a check may still come.
                boolean pending = AppScanner.isRunning() || !AppScanner.hasFinished();
                h.sdks.setText(r.pkg != null && pending ? "Not checked for built-in tracking code yet" : "");
                h.sdks.setTextColor(Ui.GRAY);
            } else if (r.sdks.isEmpty()) {
                h.sdks.setText("No known tracking code built in");
                h.sdks.setTextColor(Ui.GRAY);
            } else {
                List<String> names = new ArrayList<>();
                for (DexScanner.Sdk s : r.sdks) if (names.size() < 4) names.add(s.name);
                String more = r.sdks.size() > 4 ? " +" + (r.sdks.size() - 4) + " more" : "";
                h.sdks.setText("Built-in trackers (" + r.sdks.size() + "): " + TextUtils.join(", ", names) + more);
                h.sdks.setTextColor(0xFFE65100);
            }

            h.access.setText(r.access.isEmpty() ? "" : "Can access: " + TextUtils.join(", ", r.access));
            h.access.setVisibility(r.access.isEmpty() ? View.GONE : View.VISIBLE);
            h.sdks.setVisibility(h.sdks.getText().length() == 0 ? View.GONE : View.VISIBLE);
            return convertView;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView title;
        TextView live;
        TextView sdks;
        TextView access;
    }
}
