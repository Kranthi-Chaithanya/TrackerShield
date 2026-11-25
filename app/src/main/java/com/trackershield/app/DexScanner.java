package com.trackershield.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds tracking libraries (SDKs) built into an app by reading the class names in its
 * compiled code (the classes*.dex files inside the APK). An app that contains
 * "com.appsflyer.*" classes has the AppsFlyer tracker built in, whether or not it has used
 * it yet. This is the same idea the Exodus Privacy project uses. Plain Java.
 */
final class DexScanner {
    /** A tracking library. */
    static final class Sdk implements Comparable<Sdk> {
        final String name;
        final String category;

        Sdk(String name, String category) {
            this.name = name;
            this.category = category;
        }

        @Override
        public int compareTo(Sdk o) {
            int c = name.compareToIgnoreCase(o.name);
            return c != 0 ? c : category.compareTo(o.category);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Sdk && ((Sdk) o).name.equals(name) && ((Sdk) o).category.equals(category);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + category.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final Pattern DEX_ENTRY = Pattern.compile("classes\\d*\\.dex");
    private static final long MAX_DEX_BYTES = 32L * 1024 * 1024;

    /** Package prefix in dex form ("com/appsflyer/") to library. */
    private final Map<String, Sdk> byPrefix;
    private final Map<String, Sdk> byName;
    private final int maxDepth;

    private DexScanner(Map<String, Sdk> byPrefix, Map<String, Sdk> byName, int maxDepth) {
        this.byPrefix = byPrefix;
        this.byName = byName;
        this.maxDepth = maxDepth;
    }

    /** Reads lines of the form {@code com.appsflyer.|AppsFlyer|Attribution}. */
    static DexScanner parse(Reader reader) throws IOException {
        Map<String, Sdk> byPrefix = new HashMap<>();
        Map<String, Sdk> byName = new HashMap<>();
        int depth = 1;
        BufferedReader r = new BufferedReader(reader);
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\|");
            if (f.length != 3) continue;
            String prefix = f[0].trim();
            if (!prefix.endsWith(".") || prefix.length() < 3) continue;
            Sdk sdk = byName.get(f[1].trim());
            if (sdk == null) {
                sdk = new Sdk(f[1].trim(), f[2].trim());
                byName.put(sdk.name, sdk);
            }
            String key = prefix.replace('.', '/');
            byPrefix.put(key, sdk);
            int segments = 0;
            for (int i = 0; i < key.length(); i++) if (key.charAt(i) == '/') segments++;
            depth = Math.max(depth, segments);
        }
        return new DexScanner(byPrefix, byName, depth);
    }

    int size() {
        return byName.size();
    }

    /** The library with this name, or null (used when reading saved scan results). */
    Sdk sdkNamed(String name) {
        return byName.get(name);
    }

    /** Scans every classes*.dex in the given APK files (base APK plus any split APKs). */
    void scanApks(Collection<File> apks, Set<Sdk> out) throws IOException {
        byte[] buf = new byte[1 << 20];
        for (File apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory() || !DEX_ENTRY.matcher(e.getName()).matches()) continue;
                    if (e.getSize() > MAX_DEX_BYTES) continue;
                    byte[] dex = readAll(zip.getInputStream(e), e.getSize(), buf);
                    if (dex != null) scanDex(dex, dex.length, out);
                }
            }
        }
    }

    private static byte[] readAll(InputStream in, long size, byte[] buf) throws IOException {
        try (InputStream s = in) {
            if (size > 0) {   // usual case: size known, read straight into one array
                byte[] data = new byte[(int) size];
                int off = 0;
                int n;
                while (off < data.length && (n = s.read(data, off, data.length - off)) > 0) off += n;
                return off == data.length ? data : null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            int n;
            long total = 0;
            while ((n = s.read(buf)) > 0) {
                total += n;
                if (total > MAX_DEX_BYTES) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * Looks at the names of all classes a dex file defines or uses and adds matching
     * libraries to {@code out}. Malformed files are read as far as they make sense.
     */
    void scanDex(byte[] d, int len, Set<Sdk> out) {
        if (len < 0x70 || d[0] != 'd' || d[1] != 'e' || d[2] != 'x' || d[3] != '\n') return;
        long stringIdsSize = u32(d, 0x38);
        long stringIdsOff = u32(d, 0x3C);
        long typeIdsSize = u32(d, 0x40);
        long typeIdsOff = u32(d, 0x44);
        if (typeIdsOff + typeIdsSize * 4 > len || stringIdsOff + stringIdsSize * 4 > len) return;

        for (int t = 0; t < typeIdsSize; t++) {
            long stringIdx = u32(d, (int) (typeIdsOff + 4L * t));
            if (stringIdx >= stringIdsSize) continue;
            long dataOff = u32(d, (int) (stringIdsOff + 4 * stringIdx));
            if (dataOff >= len) continue;
            int p = (int) dataOff;
            // Skip the ULEB128 length (in UTF-16 units); the bytes that follow end with a 0.
            int guard = 0;
            while (p < len && (d[p] & 0x80) != 0 && guard++ < 5) p++;
            p++;
            if (p >= len || d[p] != 'L') continue;   // only class types: "Lcom/appsflyer/AppsFlyerLib;"
            matchDescriptor(d, p, len, out);
        }
    }

    /** Adds the library with the longest matching package prefix, if any. */
    private void matchDescriptor(byte[] d, int start, int len, Set<Sdk> out) {
        int depth = 0;
        Sdk best = null;
        for (int i = start + 1; i < len; i++) {
            byte b = d[i];
            if (b == 0 || b == ';') break;
            if (b == '/') {
                depth++;
                Sdk sdk = byPrefix.get(new String(d, start + 1, i - start, StandardCharsets.ISO_8859_1));
                if (sdk != null) best = sdk;   // a longer prefix is more specific
                if (depth >= maxDepth) break;
            }
        }
        if (best != null) out.add(best);
    }

    private static long u32(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) return Long.MAX_VALUE / 8;
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }
}
