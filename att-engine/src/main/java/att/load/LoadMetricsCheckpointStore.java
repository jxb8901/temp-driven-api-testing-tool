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
    private final long intervalMs;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean finishInvoked = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long lastCheckpointAt;
    private volatile String lastFailureCode;
    private volatile ScheduledFuture<?> scheduled;
    private boolean finalCheckpointWritten;

    LoadMetricsCheckpointStore(Path directory, String runId, String scope) {
        this(directory, runId, scope, INTERVAL_MS);
    }

    LoadMetricsCheckpointStore(Path directory, String runId, String scope, long intervalMs) {
        if (intervalMs < 1L) throw new IllegalArgumentException("LOAD checkpoint interval must be positive.");
        Path normalized = directory.toAbsolutePath().normalize();
        this.outputRoot = normalized.getParent().getParent().getParent().getParent();
        if (outputRoot == null) throw new IllegalArgumentException("LOAD checkpoint path has no output root.");
        this.relativeDirectory = outputRoot.relativize(normalized);
        this.runId = runId;
        this.scope = scope;
        this.intervalMs = intervalMs;
    }

    void start(Supplier<LoadMetricsSnapshot> snapshotSupplier) {
        scheduled = WRITERS.scheduleAtFixedRate(() -> checkpoint(snapshotSupplier, false),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    void finish(Supplier<LoadMetricsSnapshot> snapshotSupplier) {
        if (!finishInvoked.compareAndSet(false, true)) return;
        ScheduledFuture<?> task = scheduled;
        if (task != null) task.cancel(false);
        checkpoint(snapshotSupplier, true);
    }

    private synchronized void checkpoint(Supplier<LoadMetricsSnapshot> snapshotSupplier, boolean finalized) {
        if (snapshotSupplier == null || finalCheckpointWritten || (!finalized && finishInvoked.get())) return;
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
        if (snapshot == null || finalCheckpointWritten) return;
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
            if (finalized) finalCheckpointWritten = true;
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
        result.put("intervalMs", intervalMs);
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
                byte[] checkpointBytes = Files.readAllBytes(path);
                JsonNode checkpoint = JSON.readTree(checkpointBytes);
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
                String actual = sha256(payloadBytes(checkpointBytes));
                if (!expected.equals(actual)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> parsed = JSON.convertValue(checkpoint, Map.class);
                return parsed;
            } catch (Exception invalid) {
                // A truncated newest generation does not hide the previous verified generation.
            }
        }
        return null;
    }

    /** Extract the exact UTF-8 JSON value hashed by the writer, without numeric reformatting. */
    private static byte[] payloadBytes(byte[] document) throws IOException {
        int valueStart = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < document.length; index++) {
            int current = document[index] & 0xff;
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current != '"') continue;
            byte[] key = new byte[] {'"','p','a','y','l','o','a','d','"'};
            boolean matches = index + key.length <= document.length;
            for (int offset = 0; matches && offset < key.length; offset++)
                matches = document[index + offset] == key[offset];
            if (!matches) { inString = true; continue; }
            int delimiter = index + key.length;
            while (delimiter < document.length && Character.isWhitespace((char) (document[delimiter] & 0xff))) delimiter++;
            if (delimiter >= document.length || document[delimiter] != ':') { inString = true; continue; }
            valueStart = delimiter + 1;
            while (valueStart < document.length && Character.isWhitespace((char) (document[valueStart] & 0xff))) valueStart++;
            break;
        }
        if (valueStart < 0 || valueStart >= document.length || document[valueStart] != '{')
            throw new IOException("LOAD checkpoint payload is missing or not an object.");
        int depth = 0;
        inString = false;
        escaped = false;
        for (int index = valueStart; index < document.length; index++) {
            int current = document[index] & 0xff;
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') inString = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return java.util.Arrays.copyOfRange(document, valueStart, index + 1);
        }
        throw new IOException("LOAD checkpoint payload is truncated.");
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
