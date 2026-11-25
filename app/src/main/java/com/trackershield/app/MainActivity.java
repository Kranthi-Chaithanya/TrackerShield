package com.trackershield.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The app's only screen: on/off switch, counters, and the list of tracker lookups. */
public class MainActivity extends Activity implements BlockLog.Listener {
    private static final int REQ_VPN = 1;
    private static final int REQ_NOTIFICATIONS = 2;
    private static final int MENU_RESET = 1;
    private static final int MENU_CLEAR = 2;
    private static final int MENU_HELP = 3;
    private static final int GREEN = 0xFF2E7D32;
    private static final int RED = 0xFFC62828;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean refreshQueued;
    private Blocklist blocklist;
    private TextView status;
    private TextView warning;
    private String privateDnsServer;
    private TextView stats;
    private TextView topOwners;
    private TextView emptyText;
    private Button toggle;
    private LogAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        blocklist = Settings.blocklist(this);
        setContentView(buildLayout());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        BlockLog.get().addListener(this);
        privateDnsServer = findPrivateDnsServer();
        refresh();
    }

    @Override
    protected void onPause() {
        BlockLog.get().removeListener(this);
        super.onPause();
    }

    @Override
    public void onLogChanged() {
        // Can arrive from any thread, many times a second: redraw at most a few times a second.
        handler.post(() -> {
            if (refreshQueued) return;
            refreshQueued = true;
            handler.postDelayed(this::refresh, 400);
        });
    }

    // ---- Screen ----

    /**
     * One scrolling page: the controls are the list's header, so on small screens the
     * tracker list isn't squeezed into the bottom of the screen.
     */
    private View buildLayout() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, dp(8));

        TextView intro = text("Stops the apps on this phone from sending your data to known tracking and "
                + "advertising companies. Works for every app, no root needed.", 14, false);
        intro.setTextColor(Color.DKGRAY);
        root.addView(intro);

        status = text("", 20, true);
        status.setPadding(0, dp(16), 0, dp(8));
        root.addView(status);

        warning = text("", 14, false);
        warning.setTextColor(RED);
        warning.setBackgroundColor(0xFFFFEBEE);
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        warning.setVisibility(View.GONE);
        warning.setOnClickListener(v -> openVpnOrNetworkSettings());
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        warningParams.bottomMargin = dp(8);
        root.addView(warning, warningParams);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stats = text("", 15, false);
        stats.setPadding(0, dp(12), 0, 0);
        root.addView(stats);

        topOwners = text("", 13, false);
        topOwners.setTextColor(Color.DKGRAY);
        topOwners.setPadding(0, dp(4), 0, 0);
        root.addView(topOwners);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        Button skipApps = new Button(this);
        skipApps.setText("Apps to skip");
        skipApps.setOnClickListener(v -> showSkipAppsDialog());
        Button blockSite = new Button(this);
        blockSite.setText("Block a site");
        blockSite.setOnClickListener(v -> showBlockSiteDialog());
        row.addView(skipApps, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(blockSite, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        TextView header = text("Trackers your apps tried to reach (newest first). Tap one to allow or block it.", 13, true);
        header.setPadding(0, dp(16), 0, dp(4));
        root.addView(header);

        emptyText = text("Nothing yet. Turn protection on, then use your apps as normal.", 14, false);
        emptyText.setTextColor(Color.GRAY);
        emptyText.setPadding(0, dp(8), 0, 0);
        root.addView(emptyText);

        ListView list = new ListView(this);
        list.setPadding(pad, pad, pad, pad);
        list.setClipToPadding(false);
        list.addHeaderView(root, null, false);
        adapter = new LogAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);  // accounts for the header row
            if (item instanceof BlockLog.Entry) onEntryTapped((BlockLog.Entry) item);
        });
        return list;
    }

    private void refresh() {
        refreshQueued = false;
        boolean on = ShieldVpnService.isRunning();
        status.setText(on ? "●  Protection is ON" : "●  Protection is OFF");
        status.setTextColor(on ? GREEN : RED);
        toggle.setText(on ? "Turn protection off" : "Turn protection on");
        showWarnings();

        BlockLog log = BlockLog.get();
        stats.setText(String.format(Locale.getDefault(),
                "Tracker lookups blocked: %,d\nAll lookups checked: %,d\nTracker domains on the list: %,d",
                log.blocked(), log.checked(), blocklist.size()));

        List<Map.Entry<String, Integer>> owners = log.topOwners(5);
        if (owners.isEmpty()) {
            topOwners.setVisibility(View.GONE);
        } else {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : owners) parts.add(e.getKey() + " (" + e.getValue() + ")");
            topOwners.setText("Most blocked: " + TextUtils.join(" · ", parts));
            topOwners.setVisibility(View.VISIBLE);
        }
        List<BlockLog.Entry> items = log.snapshot();
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.setItems(items);
    }

    /** Points out phone settings that stop the shield from working. */
    private void showWarnings() {
        String message = null;
        if (ShieldVpnService.isLockdownOn()) {
            message = "\"Block connections without VPN\" is turned on for TrackerShield, so apps can't reach "
                    + "the internet. Tap here, open TrackerShield's settings (the gear icon) and turn that option off. "
                    + "Keep \"Always-on VPN\" on.";
        } else if (privateDnsServer != null) {
            message = "Private DNS is set to \"" + privateDnsServer + "\", so apps send their lookups straight "
                    + "there and TrackerShield can't filter them. Tap here, find Private DNS (under Network & "
                    + "internet or Connections), and choose \"Automatic\" or \"Off\".";
        }
        warning.setText(message == null ? "" : message);
        warning.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    /** The private DNS server the phone is locked to, or null when set to Automatic/Off. */
    private String findPrivateDnsServer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        // This app is left out of its own VPN, so this is the real Wi-Fi or mobile network.
        Network network = cm.getActiveNetwork();
        LinkProperties lp = network == null ? null : cm.getLinkProperties(network);
        return lp == null ? null : lp.getPrivateDnsServerName();
    }

    private void openVpnOrNetworkSettings() {
        String action = ShieldVpnService.isLockdownOn()
                ? android.provider.Settings.ACTION_VPN_SETTINGS
                : android.provider.Settings.ACTION_WIRELESS_SETTINGS;
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
        }
    }

    // ---- Actions ----

    private void onToggle() {
        if (ShieldVpnService.isRunning()) {
            startService(new Intent(this, ShieldVpnService.class).setAction(ShieldVpnService.ACTION_STOP));
            return;
        }
        Intent consent = VpnService.prepare(this);
        if (consent == null) {
            startShield();
            return;
        }
        try {
            startActivityForResult(consent, REQ_VPN);
        } catch (ActivityNotFoundException e) {
            toast("This phone does not allow VPN apps, so TrackerShield can't run here.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK) {
            startShield();
        } else {
            toast("TrackerShield needs the VPN permission to check apps' lookups. "
                    + "Nothing is sent to any outside VPN server.");
        }
    }

    private void startShield() {
        startService(new Intent(this, ShieldVpnService.class).setAction(ShieldVpnService.ACTION_START));
    }

    private void onEntryTapped(BlockLog.Entry entry) {
        final String domain = entry.domain;
        if (blocklist.isAllowed(domain)) {
            Set<String> allowed = Settings.getSet(this, Settings.KEY_ALLOWED);
            for (Iterator<String> it = allowed.iterator(); it.hasNext(); ) {
                String a = it.next();
                if (domain.equals(a) || domain.endsWith("." + a)) it.remove();
            }
            Settings.putSet(this, Settings.KEY_ALLOWED, allowed);
            toast("Blocking " + domain + " again. Apps may take a few minutes to notice.");
            adapter.notifyDataSetChanged();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Allow " + domain + "?")
                .setMessage("Apps will be able to send data to " + entry.owner + " through this address again.\n\n"
                        + "Only do this if an app stopped working properly. You can block it again by tapping it.")
                .setPositiveButton("Allow", (d, w) -> {
                    Set<String> allowed = Settings.getSet(this, Settings.KEY_ALLOWED);
                    allowed.add(domain);
                    Settings.putSet(this, Settings.KEY_ALLOWED, allowed);
                    toast("Allowed " + domain);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("Keep blocked", null)
                .show();
    }

    private void showSkipAppsDialog() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<String[]> apps = new ArrayList<>(); // {label, package}
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new String[] {ri.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));

        Set<String> skipped = Settings.getSet(this, Settings.KEY_SKIPPED_APPS);
        String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i)[0];
            checked[i] = skipped.contains(apps.get(i)[1]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Don't filter these apps")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    Set<String> out = new HashSet<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) out.add(apps.get(i)[1]);
                    }
                    Settings.putSet(this, Settings.KEY_SKIPPED_APPS, out);
                    if (ShieldVpnService.isRunning()) {
                        startService(new Intent(this, ShieldVpnService.class)
                                .setAction(ShieldVpnService.ACTION_RESTART));
                    }
                    toast(out.isEmpty() ? "Every app is filtered."
                            : out.size() + " app(s) will not be filtered.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showBlockSiteDialog() {
        final EditText input = new EditText(this);
        input.setHint("for example: tracker.example.com");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine();
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input);

        final Set<String> custom = Settings.getSet(this, Settings.KEY_CUSTOM);
        String message = "Block this address and everything under it, in every app.";
        if (!custom.isEmpty()) message += "\n\nYou already block: " + TextUtils.join(", ", custom);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Block a site")
                .setMessage(message)
                .setView(wrap)
                .setPositiveButton("Block", (d, w) -> {
                    String domain = Blocklist.normalizeDomain(input.getText().toString());
                    if (domain == null) {
                        toast("That doesn't look like a web address.");
                        return;
                    }
                    Set<String> c = Settings.getSet(this, Settings.KEY_CUSTOM);
                    c.add(domain);
                    Settings.putSet(this, Settings.KEY_CUSTOM, c);
                    Set<String> allowed = Settings.getSet(this, Settings.KEY_ALLOWED);
                    if (allowed.remove(domain)) Settings.putSet(this, Settings.KEY_ALLOWED, allowed);
                    toast("Blocking " + domain);
                })
                .setNegativeButton("Cancel", null);
        if (!custom.isEmpty()) {
            builder.setNeutralButton("Unblock all of mine", (d, w) -> {
                Settings.putSet(this, Settings.KEY_CUSTOM, Collections.<String>emptySet());
                toast("Removed your own blocks.");
            });
        }
        builder.show();
    }

    // ---- Menu ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_HELP, Menu.NONE, "How it works");
        menu.add(Menu.NONE, MENU_RESET, Menu.NONE, "Block everything I allowed");
        menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, "Clear list and counters");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_RESET:
                Settings.putSet(this, Settings.KEY_ALLOWED, Collections.<String>emptySet());
                toast("Every tracker on the list is blocked again.");
                adapter.notifyDataSetChanged();
                return true;
            case MENU_CLEAR:
                BlockLog.get().clear();
                return true;
            case MENU_HELP:
                new AlertDialog.Builder(this)
                        .setTitle("How TrackerShield works")
                        .setMessage("Before an app can send data to a server, it has to look up that server's "
                                + "address. TrackerShield sees these lookups (through a VPN that stays on your "
                                + "phone) and answers \"no such address\" for " + blocklist.size()
                                + " known tracking and advertising domains, so the data never leaves.\n\n"
                                + "Everything else works normally and is not slowed down: only the lookups pass "
                                + "through TrackerShield, and nothing is sent to us or to any VPN company.\n\n"
                                + "If an app misbehaves, tap its tracker in the list to allow it, or add the app "
                                + "under \"Apps to skip\".\n\n"
                                + "Limits: an app that uses its own private lookup system or hard-coded addresses "
                                + "can get around this, and it can't run at the same time as another VPN app.")
                        .setPositiveButton("OK", null)
                        .show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ---- Helpers ----

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** Rows for the tracker list. Shows whether each domain is blocked right now. */
    private final class LogAdapter extends BaseAdapter {
        private List<BlockLog.Entry> items = Collections.emptyList();
        private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);

        void setItems(List<BlockLog.Entry> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public BlockLog.Entry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            TextView line1 = v.findViewById(android.R.id.text1);
            TextView line2 = v.findViewById(android.R.id.text2);
            BlockLog.Entry e = items.get(position);
            boolean allowed = blocklist.isAllowed(e.domain);
            line1.setText(e.domain);
            line2.setText(String.format(Locale.getDefault(), "%s · %s · %d× · last at %s",
                    allowed ? "ALLOWED by you" : "BLOCKED", e.owner, e.count,
                    timeFormat.format(new Date(e.lastSeen))));
            line2.setTextColor(allowed ? GREEN : RED);
            return v;
        }
    }
}
