package com.trackershield.app;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the shield has seen since the app started: one row per tracker domain (newest first)
 * and running totals. Shared by the VPN service and the screen. Plain Java.
 */
final class BlockLog {
    /** One tracker domain and how often apps tried to reach it. */
    static final class Entry {
        final String domain;
        String owner;
        long lastSeen;
        int count;

        Entry(String domain, String owner) {
            this.domain = domain;
            this.owner = owner;
        }

        Entry copy() {
            Entry e = new Entry(domain, owner);
            e.lastSeen = lastSeen;
            e.count = count;
            return e;
        }
    }

    interface Listener {
        /** Called on whatever thread changed the log, possibly many times a second. */
        void onLogChanged();
    }

    private static final int MAX_ENTRIES = 300;
    private static final BlockLog INSTANCE = new BlockLog();

    static BlockLog get() {
        return INSTANCE;
    }

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(); // oldest first
    private final Map<String, Integer> blockedByOwner = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private long blocked;
    private long checked;

    /** A lookup for an ordinary (non-tracker) domain. */
    void recordChecked() {
        synchronized (this) {
            checked++;
        }
        changed();
    }

    /** A lookup for a tracker domain; {@code wasBlocked} is false if the user allowed it. */
    void recordTracker(String domain, String owner, boolean wasBlocked, long now) {
        synchronized (this) {
            checked++;
            if (wasBlocked) {
                blocked++;
                Integer n = blockedByOwner.get(owner);
                blockedByOwner.put(owner, n == null ? 1 : n + 1);
            }
            Entry e = entries.remove(domain);  // re-inserting moves it to the newest end
            if (e == null) e = new Entry(domain, owner);
            e.owner = owner;
            e.count++;
            e.lastSeen = now;
            entries.put(domain, e);
            if (entries.size() > MAX_ENTRIES) {
                Iterator<String> oldest = entries.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
        changed();
    }

    /** Newest first. */
    synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) out.add(e.copy());
        Collections.reverse(out);
        return out;
    }

    synchronized long blocked() {
        return blocked;
    }

    synchronized long checked() {
        return checked;
    }

    /** The companies whose trackers were blocked most often, biggest first. */
    synchronized List<Map.Entry<String, Integer>> topOwners(int max) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : blockedByOwner.entrySet()) {
            list.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        }
        Collections.sort(list, (a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }

    void clear() {
        synchronized (this) {
            entries.clear();
            blockedByOwner.clear();
            blocked = 0;
            checked = 0;
        }
        changed();
    }

    void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Tells the screen to redraw (also used when protection turns on or off). */
    void changed() {
        for (Listener l : listeners) l.onLogChanged();
    }
}
