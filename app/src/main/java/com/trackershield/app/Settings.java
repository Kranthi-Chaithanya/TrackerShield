package com.trackershield.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** The user's choices (saved on the phone) and the shared tracker list. */
final class Settings {
    /** Tracker domains the user let through. */
    static final String KEY_ALLOWED = "allowed_domains";
    /** Extra sites the user blocks. */
    static final String KEY_CUSTOM = "custom_blocked";
    /** Package names of apps that bypass the shield entirely. */
    static final String KEY_SKIPPED_APPS = "skipped_apps";

    private static final String PREFS = "trackershield";
    private static final String TRACKER_FILE = "trackers.txt";
    private static Blocklist blocklist;

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

    /** The tracker list from the app's assets, with the user's own changes applied. */
    static synchronized Blocklist blocklist(Context c) {
        if (blocklist == null) {
            Blocklist b;
            try (InputStream in = c.getAssets().open(TRACKER_FILE)) {
                b = Blocklist.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.e("TrackerShield", "Could not read " + TRACKER_FILE, e);
                b = new Blocklist(new HashMap<String, String>());
            }
            b.setAllowed(getSet(c, KEY_ALLOWED));
            b.setCustom(getSet(c, KEY_CUSTOM));
            blocklist = b;
        }
        return blocklist;
    }
}
