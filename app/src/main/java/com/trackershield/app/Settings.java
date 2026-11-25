package com.trackershield.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The user's choices, the saved tracking history, and the shared tracker lists. */
final class Settings {
    /** Tracker domains the user let through. */
    static final String KEY_ALLOWED = "allowed_domains";
    /** Extra sites the user blocks. */
    static final String KEY_CUSTOM = "custom_blocked";
    /** Package names of apps that bypass the shield entirely. */
    static final String KEY_SKIPPED_APPS = "skipped_apps";

    private static final String TAG = "TrackerShield";
    private static final String PREFS = "trackershield";
    private static final String TRACKER_FILE = "trackers.txt";
    private static final String SDK_FILE = "sdk_signatures.txt";
    private static final String HISTORY_FILE = "history.tsv";
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor();
    private static Blocklist blocklist;
    private static DexScanner dexScanner;
    private static boolean historyLoaded;

    private Settings() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A copy you can change and pass back to {@link #putSet}. */
    static Set<String> getSet(Context c, String key) {
        return new TreeSet<>(prefs(c).getStringSet(key, Collections.<String>emptySet()));
    }

    static void putSet(Context c, String key, Set<String> value) {
        prefs(c).edit().putStringSet(key, new HashSet<>(value)).apply();
        Blocklist b = blocklist(c);
        if (KEY_ALLOWED.equals(key)) b.setAllowed(value);
        if (KEY_CUSTOM.equals(key)) b.setCustom(value);
    }

    /** The tracker domain list from the app's assets, with the user's own changes applied. */
    static synchronized Blocklist blocklist(Context c) {
        if (blocklist == null) {
            Blocklist b;
            try (InputStream in = c.getAssets().open(TRACKER_FILE)) {
                b = Blocklist.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.e(TAG, "Could not read " + TRACKER_FILE, e);
                b = new Blocklist(new HashMap<String, String>());
            }
            b.setAllowed(getSet(c, KEY_ALLOWED));
            b.setCustom(getSet(c, KEY_CUSTOM));
            blocklist = b;
        }
        return blocklist;
    }

    /** The list of tracking libraries to look for inside apps. */
    static synchronized DexScanner dexScanner(Context c) {
        if (dexScanner == null) {
            try (InputStream in = c.getAssets().open(SDK_FILE)) {
                dexScanner = DexScanner.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.e(TAG, "Could not read " + SDK_FILE, e);
                try {
                    dexScanner = DexScanner.parse(new java.io.StringReader(""));
                } catch (IOException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        return dexScanner;
    }

    // ---- Tracking history (survives restarts) ----

    /** Loads the saved history once per app start. */
    static synchronized void loadHistory(Context c) {
        if (historyLoaded) return;
        historyLoaded = true;
        File f = new File(c.getFilesDir(), HISTORY_FILE);
        if (!f.exists()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            BlockLog.get().readFrom(r);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read saved history", e);
        }
    }

    /** Saves the history in the background if it changed since the last save. */
    static void saveHistoryIfChanged(Context c) {
        if (!BlockLog.get().takeDirty()) return;
        final StringWriter text = new StringWriter();
        try {
            BlockLog.get().writeTo(text);
        } catch (IOException impossible) {
            return;
        }
        final File dir = c.getApplicationContext().getFilesDir();
        SAVER.execute(() -> {
            File tmp = new File(dir, HISTORY_FILE + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(text.toString());
            } catch (IOException e) {
                Log.w(TAG, "Could not save history", e);
                return;
            }
            if (!tmp.renameTo(new File(dir, HISTORY_FILE))) Log.w(TAG, "Could not replace history file");
        });
    }
}
