package com.trackershield.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything the shield has seen: which app (by Linux user id) tried to reach which tracker
 * domain, how often, how many attempts were blocked, and how many happened while the app
 * wasn't on screen. Shared by the VPN service and the screens, and saved to a file.
 * Plain Java, so it can be tested off the phone.
 */
final class BlockLog {
    /** Used when Android can't tell which app made a lookup. */
    static final int UNKNOWN_UID = -1;

    /** Background state of one attempt. */
    static final int BG_UNKNOWN = -1;
    static final int BG_IN_USE = 0;
    static final int BG_BACKGROUND = 1;

    /** One app's attempts to reach one tracker domain. */
    static final class Record {
        final int uid;
        final String domain;
        String owner;
        int attempts;
        int blocked;
        /** Attempts made while the app wasn't on screen (or the screen was off). */
        int background;
        /** Attempts where we could tell whether the app was on screen. */
        int watched;
        long firstSeen;
        long lastSeen;

        Record(int uid, String domain, String owner) {
            this.uid = uid;
            this.domain = domain;
            this.owner = owner;
        }

        Record copy() {
            Record r = new Record(uid, domain, owner);
            r.attempts = attempts;
            r.blocked = blocked;
            r.background = background;
            r.watched = watched;
            r.firstSeen = firstSeen;
            r.lastSeen = lastSeen;
            return r;
        }
    }

    /** A tracker domain with all apps added together (rows on the main screen). */
    static final class Entry {
        final String domain;
        String owner;
        int count;
        long lastSeen;
        /** Apps that tried to reach it, most recent first. */
        final Set<Integer> uids = new LinkedHashSet<>();

        Entry(String domain, String owner) {
            this.domain = domain;
            this.owner = owner;
        }
    }

    /** One app's totals. */
    static final class AppSummary {
        final int uid;
        int attempts;
        int blocked;
        int background;
        int watched;
        long lastSeen;
        final Set<String> owners = new TreeSet<>();
        final Set<String> domains = new TreeSet<>();

        AppSummary(int uid) {
            this.uid = uid;
        }
    }

    interface Listener {
        /** Called on whatever thread changed the log, possibly many times a second. */
        void onLogChanged();
    }

    private static final int MAX_RECORDS = 5000;
    private static final int MAX_ENTRIES = 300;
    private static final String FILE_HEADER = "TRACKERSHIELD-LOG 2";
    private static final BlockLog INSTANCE = new BlockLog();

    static BlockLog get() {
        return INSTANCE;
    }

    // Keyed by "uid domain"; re-inserted on every update, so iteration runs oldest to newest.
    private final LinkedHashMap<String, Record> records = new LinkedHashMap<>();
    private final Map<String, Integer> blockedByOwner = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private long blocked;
    private long checked;
    private long since;
    private Record latest;
    private boolean dirty;

    /** A lookup for an ordinary (non-tracker) domain. */
    void recordChecked() {
        synchronized (this) {
            checked++;
            dirty = true;
        }
        changed();
    }

    /**
     * A lookup for a tracker domain by app {@code uid}. {@code wasBlocked} is false if the user
     * allowed it; {@code bgState} is one of the BG_ constants.
     */
    void recordTracker(int uid, String domain, String owner, boolean wasBlocked, int bgState, long now) {
        synchronized (this) {
            if (since == 0) since = now;
            checked++;
            if (wasBlocked) {
                blocked++;
                Integer n = blockedByOwner.get(owner);
                blockedByOwner.put(owner, n == null ? 1 : n + 1);
            }
            String key = uid + " " + domain;
            Record r = records.remove(key);   // re-inserting moves it to the newest end
            if (r == null) {
                r = new Record(uid, domain, owner);
                r.firstSeen = now;
            }
            r.owner = owner;
            r.attempts++;
            if (wasBlocked) r.blocked++;
            if (bgState != BG_UNKNOWN) r.watched++;
            if (bgState == BG_BACKGROUND) r.background++;
            r.lastSeen = now;
            records.put(key, r);
            latest = r;
            trim();
            dirty = true;
        }
        changed();
    }

    private void trim() {
        Iterator<Record> oldest = records.values().iterator();
        while (records.size() > MAX_RECORDS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** Tracker domains, all apps together, newest first (at most 300). */
    synchronized List<Entry> snapshot() {
        LinkedHashMap<String, Entry> byDomain = new LinkedHashMap<>();
        List<Record> newestFirst = new ArrayList<>(records.values());
        Collections.reverse(newestFirst);
        for (Record r : newestFirst) {
            Entry e = byDomain.get(r.domain);
            if (e == null) {
                if (byDomain.size() >= MAX_ENTRIES) continue;
                e = new Entry(r.domain, r.owner);
                e.lastSeen = r.lastSeen;
                byDomain.put(r.domain, e);
            }
            e.count += r.attempts;
            e.uids.add(r.uid);
        }
        return new ArrayList<>(byDomain.values());
    }

    /** Per-app totals, the app with the most tracker attempts first. */
    synchronized List<AppSummary> apps() {
        Map<Integer, AppSummary> byUid = new HashMap<>();
        for (Record r : records.values()) {
            AppSummary a = byUid.get(r.uid);
            if (a == null) {
                a = new AppSummary(r.uid);
                byUid.put(r.uid, a);
            }
            a.attempts += r.attempts;
            a.blocked += r.blocked;
            a.background += r.background;
            a.watched += r.watched;
            a.lastSeen = Math.max(a.lastSeen, r.lastSeen);
            a.owners.add(r.owner);
            a.domains.add(r.domain);
        }
        List<AppSummary> list = new ArrayList<>(byUid.values());
        Collections.sort(list, (x, y) -> {
            int c = Integer.compare(y.attempts, x.attempts);
            return c != 0 ? c : Long.compare(y.lastSeen, x.lastSeen);
        });
        return list;
    }

    /** One app's records, most attempts first. */
    synchronized List<Record> recordsFor(int uid) {
        List<Record> list = new ArrayList<>();
        for (Record r : records.values()) {
            if (r.uid == uid) list.add(r.copy());
        }
        Collections.sort(list, (x, y) -> {
            int c = Integer.compare(y.attempts, x.attempts);
            return c != 0 ? c : x.domain.compareTo(y.domain);
        });
        return list;
    }

    /** The most recent tracker attempt, or null. */
    synchronized Record latest() {
        return latest == null ? null : latest.copy();
    }

    synchronized long blocked() {
        return blocked;
    }

    synchronized long checked() {
        return checked;
    }

    /** When the first tracker attempt was recorded (0 if none). */
    synchronized long since() {
        return since;
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
            records.clear();
            blockedByOwner.clear();
            blocked = 0;
            checked = 0;
            since = 0;
            latest = null;
            dirty = true;
        }
        changed();
    }

    /** True once after each change, so the caller knows to save. */
    synchronized boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    // ---- Saving and loading ----

    /** Writes everything as tab-separated lines. */
    synchronized void writeTo(Writer w) throws IOException {
        w.write(FILE_HEADER + "\n");
        w.write("totals\t" + blocked + "\t" + checked + "\t" + since + "\n");
        for (Map.Entry<String, Integer> e : blockedByOwner.entrySet()) {
            w.write("owner\t" + clean(e.getKey()) + "\t" + e.getValue() + "\n");
        }
        for (Record r : records.values()) {
            w.write("rec\t" + r.uid + "\t" + clean(r.domain) + "\t" + clean(r.owner) + "\t" + r.attempts + "\t"
                    + r.blocked + "\t" + r.background + "\t" + r.watched + "\t" + r.firstSeen + "\t" + r.lastSeen + "\n");
        }
    }

    /** Replaces the current contents with what {@link #writeTo} saved. Bad lines are skipped. */
    void readFrom(Reader reader) throws IOException {
        BufferedReader in = new BufferedReader(reader);
        if (!FILE_HEADER.equals(in.readLine())) return;
        synchronized (this) {
            records.clear();
            blockedByOwner.clear();
            blocked = checked = since = 0;
            latest = null;
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\t", -1);
                try {
                    if (f[0].equals("totals") && f.length == 4) {
                        blocked = Long.parseLong(f[1]);
                        checked = Long.parseLong(f[2]);
                        since = Long.parseLong(f[3]);
                    } else if (f[0].equals("owner") && f.length == 3) {
                        blockedByOwner.put(f[1], Integer.parseInt(f[2]));
                    } else if (f[0].equals("rec") && f.length == 10) {
                        Record r = new Record(Integer.parseInt(f[1]), f[2], f[3]);
                        r.attempts = Integer.parseInt(f[4]);
                        r.blocked = Integer.parseInt(f[5]);
                        r.background = Integer.parseInt(f[6]);
                        r.watched = Integer.parseInt(f[7]);
                        r.firstSeen = Long.parseLong(f[8]);
                        r.lastSeen = Long.parseLong(f[9]);
                        records.put(r.uid + " " + r.domain, r);
                        latest = r;
                    }
                } catch (NumberFormatException ignored) {
                    // skip a damaged line
                }
            }
            trim();
            dirty = false;
        }
        changed();
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ---- Listeners ----

    void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Tells the screens to redraw (also used when protection turns on or off). */
    void changed() {
        for (Listener l : listeners) l.onLogChanged();
    }
}
