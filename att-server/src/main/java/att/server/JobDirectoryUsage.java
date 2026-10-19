package att.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measures and deletes directories under {@code dataDir/jobs} without following symbolic links.
 * An incomplete measurement reports {@code bytes < 0} and must not be treated as an empty tree.
 */
final class JobDirectoryUsage {
    static final int MAX_ENTRIES = 200_000;
    static final int MAX_DEPTH = 32;

    static final class Measurement {
        final boolean complete;
        final long bytes;
        final Map<String, Long> jobBytes;

        Measurement(boolean complete, long bytes, Map<String, Long> jobBytes) {
            this.complete = complete;
            this.bytes = bytes;
            this.jobBytes = jobBytes;
        }
    }

    private JobDirectoryUsage() {}

    static Measurement measure(Path jobsRoot) {
        if (jobsRoot == null) return incomplete();
        try {
            if (!Files.exists(jobsRoot, LinkOption.NOFOLLOW_LINKS)) return new Measurement(true, 0L, Map.of());
            var root = Files.readAttributes(jobsRoot, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (root.isSymbolicLink() || !root.isDirectory()) return incomplete();
        } catch (IOException failure) {
            return incomplete();
        }
        Map<String, Long> jobBytes = new LinkedHashMap<>();
        long total = 0L;
        int visited = 0;
        try (var children = Files.newDirectoryStream(jobsRoot)) {
            for (Path child : children) {
                long[] size = new long[]{0L};
                int[] count = new int[]{0};
                boolean[] failed = new boolean[]{false};
                measureTree(child, 1, size, count, failed);
                visited += count[0];
                if (failed[0] || visited > MAX_ENTRIES) return incomplete();
                if (total > Long.MAX_VALUE - size[0]) return incomplete();
                total += size[0];
                Path name = child.getFileName();
                if (name != null) jobBytes.put(name.toString(), size[0]);
            }
        } catch (IOException failure) {
            return incomplete();
        }
        return new Measurement(true, total, jobBytes);
    }

    /** Byte size of one job directory. Returns {@code -1} when the walk cannot be completed safely. */
    static long directoryBytes(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return -1L;
        long[] size = new long[]{0L};
        int[] count = new int[]{0};
        boolean[] failed = new boolean[]{false};
        measureTree(directory, 1, size, count, failed);
        return failed[0] || count[0] > MAX_ENTRIES ? -1L : size[0];
    }

    /**
     * Deletes a directory tree. A symbolic-link root is left untouched and the method returns false
     * so callers do not remove the database row for a path they did not delete.
     */
    static boolean deleteJobDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.isSymbolicLink(directory)) return false;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
        }
        return !Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
    }

    private static void measureTree(Path start, int depth, long[] size, int[] count, boolean[] failed) {
        ArrayDeque<Path> dirs = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        dirs.add(start);
        depths.add(depth);
        while (!dirs.isEmpty()) {
            Path current = dirs.removeFirst();
            int currentDepth = depths.removeFirst();
            count[0]++;
            if (count[0] > MAX_ENTRIES || currentDepth > MAX_DEPTH) { failed[0] = true; return; }
            java.nio.file.attribute.BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException failure) { failed[0] = true; return; }
            long length = Math.max(0L, attributes.size());
            if (size[0] > Long.MAX_VALUE - length) { failed[0] = true; return; }
            size[0] += length;
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) continue;
            try (var stream = Files.newDirectoryStream(current)) {
                for (Path child : stream) { dirs.add(child); depths.add(currentDepth + 1); }
            } catch (IOException failure) { failed[0] = true; return; }
        }
    }

    private static Measurement incomplete() { return new Measurement(false, -1L, Map.of()); }
}
