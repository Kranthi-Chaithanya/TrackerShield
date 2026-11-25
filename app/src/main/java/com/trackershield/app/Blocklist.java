package com.trackershield.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The tracker list, plus the user's own changes: extra sites they block, and tracker
 * domains they let through because an app needed them. Plain Java, safe to share between threads.
 */
final class Blocklist {
    static final String CUSTOM_OWNER = "Blocked by you";

    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private final Map<String, String> builtIn;
    private volatile Set<String> custom = Collections.emptySet();
    private volatile Set<String> allowed = Collections.emptySet();

    Blocklist(Map<String, String> builtIn) {
        this.builtIn = builtIn;
    }

    /** Reads lines of the form {@code domain|Company}. Blank lines and # comments are skipped. */
    static Blocklist parse(Reader reader) throws IOException {
        Map<String, String> map = new HashMap<>();
        BufferedReader r = new BufferedReader(reader);
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int bar = line.indexOf('|');
            String domain = normalizeDomain(bar < 0 ? line : line.substring(0, bar));
            if (domain == null) continue;
            String owner = bar < 0 ? "" : line.substring(bar + 1).trim();
            map.put(domain, owner.isEmpty() ? "Tracker" : owner);
        }
        return new Blocklist(map);
    }

    int size() {
        return builtIn.size();
    }

    /**
     * Who runs the tracker behind {@code name} (also matching sub-domains), or null if the name
     * isn't on the tracker list or the user's block list. Ignores the allow list.
     */
    String trackerOwner(String name) {
        Set<String> c = custom;
        for (String s = name; s != null; s = parent(s)) {
            String owner = builtIn.get(s);
            if (owner != null) return owner;
            if (c.contains(s)) return CUSTOM_OWNER;
        }
        return null;
    }

    /** True if the user let {@code name} (or a domain above it) through. */
    boolean isAllowed(String name) {
        Set<String> a = allowed;
        if (a.isEmpty()) return false;
        for (String s = name; s != null; s = parent(s)) {
            if (a.contains(s)) return true;
        }
        return false;
    }

    void setAllowed(Collection<String> domains) {
        allowed = Collections.unmodifiableSet(new HashSet<>(domains));
    }

    void setCustom(Collection<String> domains) {
        custom = Collections.unmodifiableSet(new HashSet<>(domains));
    }

    private static String parent(String s) {
        int dot = s.indexOf('.');
        return dot < 0 ? null : s.substring(dot + 1);
    }

    /**
     * Turns what a person might type ("https://Ads.Example.com/page", "*.example.com") into a
     * bare lower-case domain, or returns null if it isn't one.
     */
    static String normalizeDomain(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        for (char stop : new char[] {'/', '?', '#'}) {
            int i = s.indexOf(stop);
            if (i >= 0) s = s.substring(0, i);
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        if (s.startsWith("*.")) s = s.substring(2);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return DOMAIN.matcher(s).matches() ? s : null;
    }
}
