/* Author: Jeffrey + ChatGPT */
package att.template;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded process-level UTF-8 payload cache invalidated by canonical path, size and modification time. */
public final class PayloadCache {
    static final int MAX_CACHE_ENTRIES = 512;
    static final long MAX_CACHED_CHARS = 16L * 1024L * 1024L;
    static final int MAX_ENTRY_CHARS = 2 * 1024 * 1024;
    private static final Map<Key, String> CACHE = new LinkedHashMap<Key, String>(16, 0.75f, true);
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong LOADS = new AtomicLong();
    private static long cachedChars;

    private PayloadCache() {}

    public static String readUtf8(Path file) throws Exception {
        Path canonical = file.toRealPath();
        Key key = new Key(canonical, Files.size(canonical), Files.getLastModifiedTime(canonical).toMillis());
        synchronized (CACHE) {
            String cached = CACHE.get(key);
            if (cached != null) { HITS.incrementAndGet(); return cached; }
        }
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(canonical, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) content.append(buffer, 0, count);
        }
        String loaded = content.toString();
        LOADS.incrementAndGet();
        synchronized (CACHE) {
            String concurrent = CACHE.get(key);
            if (concurrent != null) return concurrent;
            removeOlder(canonical);
            if (loaded.length() <= MAX_ENTRY_CHARS) {
                CACHE.put(key, loaded);
                cachedChars += loaded.length();
                evictToBound();
            }
        }
        return loaded;
    }

    public static Stats stats() { return new Stats(LOADS.get(), HITS.get()); }
    static void clearForTests() { synchronized (CACHE) { CACHE.clear(); cachedChars = 0L; } LOADS.set(0); HITS.set(0); }

    private static void removeOlder(Path canonical) {
        Iterator<Map.Entry<Key, String>> entries = CACHE.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, String> entry = entries.next();
            if (entry.getKey().path.equals(canonical)) {
                cachedChars -= entry.getValue().length();
                entries.remove();
            }
        }
    }

    private static void evictToBound() {
        Iterator<Map.Entry<Key, String>> entries = CACHE.entrySet().iterator();
        while ((CACHE.size() > MAX_CACHE_ENTRIES || cachedChars > MAX_CACHED_CHARS) && entries.hasNext()) {
            cachedChars -= entries.next().getValue().length();
            entries.remove();
        }
    }

    public static final class Stats {
        private final long loads; private final long hits;
        private Stats(long loads, long hits) { this.loads = loads; this.hits = hits; }
        public long loads() { return loads; }
        public long hits() { return hits; }
    }

    private static final class Key {
        private final Path path; private final long size; private final long modified;
        private Key(Path path, long size, long modified) { this.path = path; this.size = size; this.modified = modified; }
        @Override public boolean equals(Object value) { if (!(value instanceof Key)) return false; Key other = (Key) value; return size == other.size && modified == other.modified && path.equals(other.path); }
        @Override public int hashCode() { int result = path.hashCode(); result = 31 * result + Long.valueOf(size).hashCode(); return 31 * result + Long.valueOf(modified).hashCode(); }
    }
}
