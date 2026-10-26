package att.load;

import att.validation.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Bounded, versioned LOAD metrics checkpoints. Checkpoints are recovery evidence, never a completed report. */
final class LoadMetricsCheckpointStore {
    static final String SCHEMA = "att-load-metrics-checkpoint/v1";
    static final long INTERVAL_MS = TimeUnit.MINUTES.toMillis(5L);
    static final int MAX_CHECKPOINT_BYTES = 16 * 1024 * 1024;
    private static final int RETAINED_GENERATIONS = 2;
    private static final ScheduledExecutorService WRITERS = Executors.newScheduledThreadPool(2, new ThreadFactory() {
        private final AtomicLong sequence = new AtomicLong();
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "att-load-metrics-checkpoint-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = JsonSupport.mapper();

    private final Path outputRoot;
    private final Path relativeDirectory;
    private final String runId;
    private final String scope;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private volatile long lastCheckpointAt;
    private volatile String lastFailureCode;
    private volatile ScheduledFuture<?> scheduled;

    LoadMetricsCheckpointStore(Path directory, String runId, String scope) {
        Path normalized = directory.toAbsolutePath().normalize();
        this.outputRoot = normalized.getParent().getParent().getParent().getParent();
        if (outputRoot == null) throw new IllegalArgumentException("LOAD checkpoint path has no output root.");
        this.relativeDirectory = outputRoot.relativize(normalized);
        this.runId = runId;
        this.scope = scope;
    }

    void start(Supplier<LoadMetricsSnapshot> snapshotSupplier) {
        scheduled = WRITERS.scheduleAtFixedRate(() -> checkpoint(snapshotSupplier, false),
                INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    void finish(Supplier<LoadMetricsSnapshot> snapshotSupplier) {
        ScheduledFuture<?> task = scheduled;
        if (task != null) task.cancel(false);
        checkpoint(snapshotSupplier, true);
    }

    private void checkpoint(Supplier<LoadMetricsSnapshot> snapshotSupplier, boolean finalized) {
        LoadMetricsSnapshot snapshot;
        try { snapshot = snapshotSupplier.get(); }
        catch (RuntimeException failure) {
            writeFailures.incrementAndGet();
            lastFailureCode = "LOAD_CHECKPOINT_SNAPSHOT_FAILED";
            return;
        }
        checkpoint(snapshot, finalized);
    }

    private synchronized void checkpoint(LoadMetricsSnapshot snapshot, boolean finalized) {
        if (snapshot == null) return;
        long next = sequence.incrementAndGet();
        Path temporary = null;
        try {
            Path directory = ensurePrivateDirectory();
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("metrics", snapshot.toMap());
            byte[] payloadBytes = JSON.writeValueAsBytes(payload);
            Map<String, Object> checkpoint = new LinkedHashMap<String, Object>();
            checkpoint.put("schemaVersion", SCHEMA);
            checkpoint.put("state", finalized ? "FINAL_CHECKPOINT" : "CHECKPOINT");
            checkpoint.put("runId", runId);
            checkpoint.put("scope", scope);
            checkpoint.put("sequence", next);
            checkpoint.put("capturedAt", Instant.now().toString());
            checkpoint.put("finalized", finalized);
            checkpoint.put("payload", payload);
            checkpoint.put("integritySha256", sha256(payloadBytes));
            byte[] bytes = JSON.writeValueAsBytes(checkpoint);
            if (bytes.length > MAX_CHECKPOINT_BYTES) throw new IOException("LOAD metrics checkpoint exceeded its 16 MiB size limit.");
            String fileName = fileName(next);
            Path target = directory.resolve(fileName);
            temporary = Files.createTempFile(directory, ".checkpoint-", ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            moveAtomically(temporary, target);
            temporary = null;
            lastCheckpointAt = System.currentTimeMillis();
            lastFailureCode = null;
            retainLatestGenerations();
        } catch (Exception failure) {
            writeFailures.incrementAndGet();
            lastFailureCode = "LOAD_CHECKPOINT_WRITE_FAILED";
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", Boolean.TRUE);
        result.put("intervalMs", INTERVAL_MS);
        result.put("sequence", sequence.get());
        result.put("lastCheckpointAtEpochMs", lastCheckpointAt);
        result.put("writeFailures", writeFailures.get());
        if (lastFailureCode != null) result.put("lastFailureCode", lastFailureCode);
        result.put("retainedGenerations", RETAINED_GENERATIONS);
        return result;
    }

    private void retainLatestGenerations() throws IOException {
        Path directory = ensurePrivateDirectory();
        List<Path> generations = new ArrayList<Path>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{20}\\.json"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed()).forEach(generations::add);
        }
        for (int index = RETAINED_GENERATIONS; index < generations.size(); index++)
            Files.deleteIfExists(generations.get(index));
    }

    private Path ensurePrivateDirectory() throws IOException {
        Path canonicalRoot = outputRoot.toRealPath();
        if (!Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("LOAD checkpoint output root is not a regular directory.");
        Path current = canonicalRoot;
        for (Path component : relativeDirectory) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("LOAD checkpoint path contains a non-private directory component.");
            } else {
                Files.createDirectory(current);
            }
        }
        if (!current.toRealPath().startsWith(canonicalRoot))
            throw new IOException("LOAD checkpoint path escaped its private output root.");
        return current;
    }

    static Map<String, Object> readLatest(Path directory, String runId, String scope) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return null;
        List<Path> generations = new ArrayList<Path>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{20}\\.json"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed()).forEach(generations::add);
        }
        for (Path path : generations) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > MAX_CHECKPOINT_BYTES) continue;
            try {
                JsonNode checkpoint = JSON.readTree(path.toFile());
                long checkpointSequence = checkpoint.path("sequence").asLong(-1L);
                String state = checkpoint.path("state").asText();
                if (!SCHEMA.equals(checkpoint.path("schemaVersion").asText())
                        || !("CHECKPOINT".equals(state) || "FINAL_CHECKPOINT".equals(state))
                        || !runId.equals(checkpoint.path("runId").asText())
                        || !scope.equals(checkpoint.path("scope").asText())
                        || checkpointSequence < 1L
                        || !path.getFileName().toString().equals(fileName(checkpointSequence))
                        || checkpoint.path("finalized").asBoolean() != "FINAL_CHECKPOINT".equals(state)
                        || !checkpoint.path("payload").path("metrics").isObject()) continue;
                String expected = checkpoint.path("integritySha256").asText("");
                String actual = sha256(JSON.writeValueAsBytes(checkpoint.path("payload")));
                if (!expected.equals(actual)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> parsed = JSON.convertValue(checkpoint, Map.class);
                return parsed;
            } catch (Exception invalid) {
                // A truncated newest generation does not hide the previous verified generation.
            }
        }
        return null;
    }

    private static String fileName(long sequence) { return String.format(java.util.Locale.ROOT, "checkpoint-%020d.json", sequence); }
    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
    private static void moveAtomically(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
