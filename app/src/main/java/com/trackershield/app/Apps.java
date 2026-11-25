package com.trackershield.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Names, icons and permissions of the apps on the phone, looked up by Linux user id (uid). */
final class Apps {
    /** An app (or a group of system packages) that shares one uid. */
    static final class Info {
        final int uid;
        final String label;
        /** Main package, or null for system ids and unknown apps. */
        final String packageName;
        final List<String> packages;

        Info(int uid, String label, String packageName, List<String> packages) {
            this.uid = uid;
            this.label = label;
            this.packageName = packageName;
            this.packages = packages;
        }
    }

    /** Dangerous permissions, in plain words, in the order they are shown. */
    private static final Map<String, String> SENSITIVE = new LinkedHashMap<>();

    static {
        SENSITIVE.put(Manifest.permission.ACCESS_BACKGROUND_LOCATION, "Location, even when not in use");
        SENSITIVE.put(Manifest.permission.ACCESS_FINE_LOCATION, "Precise location");
        SENSITIVE.put(Manifest.permission.ACCESS_COARSE_LOCATION, "Approximate location");
        SENSITIVE.put(Manifest.permission.READ_CONTACTS, "Contacts");
        SENSITIVE.put(Manifest.permission.RECORD_AUDIO, "Microphone");
        SENSITIVE.put(Manifest.permission.CAMERA, "Camera");
        SENSITIVE.put(Manifest.permission.READ_SMS, "Text messages");
        SENSITIVE.put(Manifest.permission.RECEIVE_SMS, "Incoming texts");
        SENSITIVE.put(Manifest.permission.READ_CALL_LOG, "Call history");
        SENSITIVE.put(Manifest.permission.READ_PHONE_NUMBERS, "Your phone number");
        SENSITIVE.put(Manifest.permission.READ_PHONE_STATE, "Phone status and IDs");
        SENSITIVE.put(Manifest.permission.CALL_PHONE, "Make calls");
        SENSITIVE.put(Manifest.permission.READ_CALENDAR, "Calendar");
        SENSITIVE.put(Manifest.permission.READ_EXTERNAL_STORAGE, "Photos and files");
        SENSITIVE.put("android.permission.READ_MEDIA_IMAGES", "Photos");
        SENSITIVE.put("android.permission.READ_MEDIA_VIDEO", "Videos");
        SENSITIVE.put("android.permission.READ_MEDIA_AUDIO", "Music and audio");
        SENSITIVE.put(Manifest.permission.ACTIVITY_RECOGNITION, "Physical activity");
        SENSITIVE.put(Manifest.permission.BODY_SENSORS, "Body sensors");
        SENSITIVE.put("android.permission.BLUETOOTH_SCAN", "Nearby Bluetooth devices");
        SENSITIVE.put("android.permission.NEARBY_WIFI_DEVICES", "Nearby Wi-Fi devices");
    }

    private static final Map<Integer, Info> byUid = new ConcurrentHashMap<>();

    private Apps() {}

    static Info forUid(Context c, int uid) {
        Info cached = byUid.get(uid);
        if (cached != null) return cached;
        Info info = lookUp(c.getApplicationContext(), uid);
        byUid.put(uid, info);
        return info;
    }

    /** Forgets cached names, e.g. after apps were installed or removed. */
    static void refresh() {
        byUid.clear();
    }

    private static Info lookUp(Context c, int uid) {
        if (uid == BlockLog.UNKNOWN_UID) {
            return new Info(uid, "Unknown app", null, Collections.<String>emptyList());
        }
        PackageManager pm = c.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        List<String> list = pkgs == null ? Collections.<String>emptyList() : Arrays.asList(pkgs);
        if (uid == Process.ROOT_UID || uid == Process.SYSTEM_UID) {
            return new Info(uid, "Android system", null, list);
        }
        if (list.isEmpty()) {
            String label = uid < Process.FIRST_APPLICATION_UID ? "Android system service" : "Removed app";
            return new Info(uid, label + " (id " + uid + ")", null, list);
        }
        String main = list.get(0);
        String label = labelOf(pm, main);
        if (list.size() > 1) label += " (+" + (list.size() - 1) + " more)";
        return new Info(uid, label, main, list);
    }

    static String labelOf(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static Drawable icon(Context c, Info info) {
        PackageManager pm = c.getPackageManager();
        if (info.packageName != null) {
            try {
                return pm.getApplicationIcon(info.packageName);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return pm.getDefaultActivityIcon();
    }

    static int uidOf(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return BlockLog.UNKNOWN_UID;
        }
    }

    /** Apps with an icon on the home screen, except this one. */
    static List<ApplicationInfo> launcherApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        List<ApplicationInfo> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (ai.packageName.equals(c.getPackageName()) || !seen.add(ai.packageName)) continue;
            out.add(ai);
        }
        return out;
    }

    /** Sensitive things the app has been allowed to access, in plain words. */
    static List<String> sensitiveAccess(Context c, String pkg) {
        Set<String> out = new LinkedHashSet<>();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions == null || pi.requestedPermissionsFlags == null) return new ArrayList<>();
            Set<String> granted = new HashSet<>();
            for (int i = 0; i < pi.requestedPermissions.length && i < pi.requestedPermissionsFlags.length; i++) {
                if ((pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    granted.add(pi.requestedPermissions[i]);
                }
            }
            for (Map.Entry<String, String> e : SENSITIVE.entrySet()) {
                if (granted.contains(e.getKey())) out.add(e.getValue());
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (out.contains("Location, even when not in use")) {
            out.remove("Precise location");
            out.remove("Approximate location");
        } else if (out.contains("Precise location")) {
            out.remove("Approximate location");
        }
        return new ArrayList<>(out);
    }
}
