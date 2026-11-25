package com.trackershield.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Small helpers shared by the screens. */
final class Ui {
    static final int GREEN = 0xFF2E7D32;
    static final int RED = 0xFFC62828;
    static final int GRAY = 0xFF616161;

    /** What each kind of built-in tracker does, in plain words. */
    static final Map<String, String> CATEGORY_MEANING = new LinkedHashMap<>();

    static {
        CATEGORY_MEANING.put("Session recording", "Can record your taps, swipes and screens in the app.");
        CATEGORY_MEANING.put("Location", "Collects your location, often to sell to data companies.");
        CATEGORY_MEANING.put("Advertising ID", "Reads your phone's advertising ID, which links what you do across apps.");
        CATEGORY_MEANING.put("Advertising", "Shows ads and builds an advertising profile of you.");
        CATEGORY_MEANING.put("Attribution", "Tracks which ad made you install or open the app.");
        CATEGORY_MEANING.put("Profiling", "Builds a marketing profile of you and targets messages at you.");
        CATEGORY_MEANING.put("Analytics", "Records what you do in the app and sends it to the company.");
        CATEGORY_MEANING.put("Crash reporting", "Sends error reports with details about your phone.");
    }

    private Ui() {}

    static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, CharSequence s, int sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.BLACK);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    /** Lets a blocked tracker through (after asking), or blocks an allowed one again. */
    static void toggleDomain(final Activity a, final String domain, String owner, final Runnable after) {
        Blocklist list = Settings.blocklist(a);
        if (list.isAllowed(domain)) {
            Set<String> allowed = Settings.getSet(a, Settings.KEY_ALLOWED);
            for (Iterator<String> it = allowed.iterator(); it.hasNext(); ) {
                String d = it.next();
                if (domain.equals(d) || domain.endsWith("." + d)) it.remove();
            }
            Settings.putSet(a, Settings.KEY_ALLOWED, allowed);
            toast(a, "Blocking " + domain + " again. Apps may take a few minutes to notice.");
            after.run();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Allow " + domain + "?")
                .setMessage("Apps will be able to send data to " + owner + " through this address again.\n\n"
                        + "Only do this if an app stopped working properly. You can block it again by tapping it.")
                .setPositiveButton("Allow", (d, w) -> {
                    Set<String> allowed = Settings.getSet(a, Settings.KEY_ALLOWED);
                    allowed.add(domain);
                    Settings.putSet(a, Settings.KEY_ALLOWED, allowed);
                    toast(a, "Allowed " + domain);
                    after.run();
                })
                .setNegativeButton("Keep blocked", null)
                .show();
    }

    /** Opens Android's "Usage access" settings so the user can allow TrackerShield. */
    static void openUsageAccessSettings(Activity a) {
        try {
            a.startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (RuntimeException e) {
            a.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
        }
    }
}
