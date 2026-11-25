package com.trackershield.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

/**
 * Tells whether the app making a tracker lookup was on screen at that moment. Needs the
 * "Usage access" permission, which the user turns on in Android settings; without it the
 * answer is "unknown".
 */
final class ForegroundWatcher {
    // UsageEvents.Event.ACTIVITY_RESUMED / ACTIVITY_PAUSED (same values as the older
    // MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND, so this works on every supported version).
    private static final int RESUMED = 1;
    private static final int PAUSED = 2;
    private static final long QUERY_INTERVAL_MS = 1000;
    private static final long ACCESS_CHECK_INTERVAL_MS = 10_000;

    private final Context context;
    private final UsageStatsManager usage;
    private final PowerManager power;
    private String foreground;
    private long queriedUntil;
    private long lastQueryElapsed;
    private boolean access;
    private long accessCheckedElapsed = -ACCESS_CHECK_INTERVAL_MS;

    ForegroundWatcher(Context context) {
        this.context = context.getApplicationContext();
        this.usage = context.getSystemService(UsageStatsManager.class);
        this.power = context.getSystemService(PowerManager.class);
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        int mode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        } else {
            mode = checkOpLegacy(ops, c);
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @SuppressWarnings("deprecation")
    private static int checkOpLegacy(AppOpsManager ops, Context c) {
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
    }

    /**
     * The package on screen right now, "" if the screen is off or nothing is known to be on
     * screen, or null if we can't tell (no usage access).
     */
    synchronized String foregroundPackage() {
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed - accessCheckedElapsed >= ACCESS_CHECK_INTERVAL_MS) {
            access = hasUsageAccess(context);
            accessCheckedElapsed = nowElapsed;
        }
        if (!access || usage == null) return null;
        if (power != null && !power.isInteractive()) return "";
        if (queriedUntil != 0 && nowElapsed - lastQueryElapsed < QUERY_INTERVAL_MS) {
            return foreground == null ? "" : foreground;
        }
        long now = System.currentTimeMillis();
        long from = queriedUntil == 0 ? now - 60 * 60 * 1000 : queriedUntil;
        try {
            UsageEvents events = usage.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                int type = e.getEventType();
                if (type == RESUMED) {
                    foreground = e.getPackageName();
                } else if (type == PAUSED && e.getPackageName().equals(foreground)) {
                    foreground = null;
                }
            }
        } catch (RuntimeException ignored) {
            // Some phones throw here briefly after usage access is changed.
        }
        queriedUntil = now;
        lastQueryElapsed = nowElapsed;
        return foreground == null ? "" : foreground;
    }
}
