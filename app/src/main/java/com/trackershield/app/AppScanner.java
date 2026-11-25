package com.trackershield.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks the installed apps for tracking libraries built into their code. Results are saved,
 * so later scans only look at apps that were installed or updated since.
 */
final class AppScanner {
    /** What was found in one app. */
    static final class Result {
        final String pkg;
        final long version;
        final long updated;
        final List<DexScanner.Sdk> sdks;

        Result(String pkg, long version, long updated, List<DexScanner.Sdk> sdks) {
            this.pkg = pkg;
            this.version = version;
            this.updated = updated;
            this.sdks = Collections.unmodifiableList(sdks);
        }
    }

    interface Listener {
        /** Progress or completion; called on the scanning thread. */
        void onScanChanged();
    }

    private static final String TAG = "TrackerShield";
    private static final String FILE = "sdk_scan.tsv";
    private static final Map<String, Result> results = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static boolean loaded;
    private static volatile boolean running;
    private static volatile int done;
    private static volatile int total;
    private static volatile String current = "";
    private static volatile boolean finishedOnce;

    private AppScanner() {}

    static Result resultFor(Context c, String pkg) {
        load(c);
        return pkg == null ? null : results.get(pkg);
    }

    static boolean isRunning() {
        return running;
    }

    static boolean hasFinished() {
        return finishedOnce;
    }

    static int done() {
        return done;
    }

    static int total() {
        return total;
    }

    static String current() {
        return current;
    }

    static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed() {
        for (Listener l : listeners) l.onScanChanged();
    }

    /** Starts a background scan of new and updated apps (does nothing if one is running). */
    static synchronized void scanInstalledApps(Context c) {
        if (running) return;
        running = true;
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            try {
                scan(app);
            } catch (RuntimeException e) {
                Log.e(TAG, "App scan failed", e);
            } finally {
                running = false;
                finishedOnce = true;
                current = "";
                changed();
            }
        });
    }

    private static void scan(Context c) {
        load(c);
        DexScanner scanner = Settings.dexScanner(c);
        PackageManager pm = c.getPackageManager();
        // Apps on the home screen, plus any other installed app seen contacting trackers
        // (for example a background service with no icon).
        List<ApplicationInfo> apps = new ArrayList<>(Apps.launcherApps(c));
        Set<String> listed = new HashSet<>();
        for (ApplicationInfo ai : apps) listed.add(ai.packageName);
        for (BlockLog.AppSummary seen : BlockLog.get().apps()) {
            if (seen.uid < android.os.Process.FIRST_APPLICATION_UID) continue;
            for (String p : Apps.forUid(c, seen.uid).packages) {
                if (p.equals(c.getPackageName()) || !listed.add(p)) continue;
                try {
                    apps.add(pm.getApplicationInfo(p, 0));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        total = apps.size();
        done = 0;
        changed();
        Set<String> present = new HashSet<>();
        boolean dirty = false;
        for (ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            present.add(pkg);
            current = Apps.labelOf(pm, pkg);
            changed();
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                long version = versionOf(pi);
                Result old = results.get(pkg);
                if (old == null || old.version != version || old.updated != pi.lastUpdateTime) {
                    List<File> apks = new ArrayList<>();
                    apks.add(new File(ai.sourceDir));
                    if (ai.splitSourceDirs != null) {
                        for (String split : ai.splitSourceDirs) apks.add(new File(split));
                    }
                    Set<DexScanner.Sdk> found = new TreeSet<>();
                    try {
                        scanner.scanApks(apks, found);
                    } catch (IOException | RuntimeException e) {
                        Log.w(TAG, "Could not read " + pkg, e);
                    } catch (OutOfMemoryError e) {
                        Log.w(TAG, pkg + " is too big to check", e);  // keep what was found so far
                    }
                    results.put(pkg, new Result(pkg, version, pi.lastUpdateTime, new ArrayList<>(found)));
                    dirty = true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Uninstalled while we were scanning.
            }
            done++;
        }
        if (results.keySet().retainAll(present)) dirty = true;
        if (dirty) save(c);
    }

    @SuppressWarnings("deprecation")
    private static long versionOf(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
    }

    /** A stamp that changes whenever TrackerShield itself is updated (its tracker list may have changed). */
    private static String listStamp(Context c) {
        try {
            return "SDKSCAN 1 " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return "SDKSCAN 1 0";
        }
    }

    private static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return;
        DexScanner scanner = Settings.dexScanner(c);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            if (!listStamp(c).equals(r.readLine())) return;   // list changed: scan everything again
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length != 4) continue;
                List<DexScanner.Sdk> sdks = new ArrayList<>();
                for (String name : p[3].split(";")) {
                    DexScanner.Sdk sdk = scanner.sdkNamed(name);
                    if (sdk != null) sdks.add(sdk);
                }
                try {
                    results.put(p[0], new Result(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), sdks));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read saved scan", e);
        }
    }

    private static void save(Context c) {
        File dir = c.getFilesDir();
        File tmp = new File(dir, FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(listStamp(c) + "\n");
            for (Result res : results.values()) {
                StringBuilder names = new StringBuilder();
                for (DexScanner.Sdk s : res.sdks) {
                    if (names.length() > 0) names.append(';');
                    names.append(s.name);
                }
                w.write(res.pkg + "\t" + res.version + "\t" + res.updated + "\t" + names + "\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not save scan", e);
            return;
        }
        if (!tmp.renameTo(new File(dir, FILE))) Log.w(TAG, "Could not replace scan file");
    }
}
