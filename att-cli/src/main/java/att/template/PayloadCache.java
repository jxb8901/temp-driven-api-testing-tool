/* Author: Jeffrey + ChatGPT */
package att.template;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Process-level UTF-8 payload cache invalidated by canonical path, size and modification time. */
public final class PayloadCache {
    private static final Map<Key, String> CACHE = new LinkedHashMap<Key, String>();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong LOADS = new AtomicLong();

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
        synchronized (CACHE) {
            removeOlder(canonical);
            String previous = CACHE.put(key, loaded);
            if (previous == null) LOADS.incrementAndGet(); else return previous;
        }
        return loaded;
    }

    public static Stats stats() { return new Stats(LOADS.get(), HITS.get()); }
    static void clearForTests() { synchronized (CACHE) { CACHE.clear(); } LOADS.set(0); HITS.set(0); }

    private static void removeOlder(Path canonical) {
        java.util.Iterator<Key> keys = CACHE.keySet().iterator();
        while (keys.hasNext()) if (keys.next().path.equals(canonical)) keys.remove();
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
