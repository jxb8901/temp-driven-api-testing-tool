package att.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Retained, job-local event journal. Client delivery runs independently from the Worker consumer. */
final class JobEvents {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_EVENT_DATA_BYTES = 65536;
    static final int MAX_EVENT_LINE_BYTES = 131072;
    static final long DEFAULT_MAX_JOURNAL_BYTES = 64L * 1024L * 1024L;
    static final long MAX_RETAINED_MEMORY_BYTES = 16L * 1024L * 1024L;
    static final long MIN_STORAGE_HEADROOM_BYTES = 8L * 1024L * 1024L;
    private static final int COMPACT_EVERY_EVENTS = 128;
    private static final int MAX_COMPACTION_TAIL_EVENTS = 32;
    private static final int RETAINED_RECORD_OVERHEAD_BYTES = 512;
    private static final Object[] COMPACTION_LOCKS = new Object[64];

    static {
        for (int i = 0; i < COMPACTION_LOCKS.length; i++) COMPACTION_LOCKS[i] = new Object();
    }

    private final Path file;
    private final int capacity;
    private final long maxJournalBytes;
    private final long maxRetainedMemoryBytes;
    private final Executor compactionExecutor;
    private final Runnable afterSnapshotWrite;
    private final LongSupplier usableSpace;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean compactionScheduled = new AtomicBoolean();
    private final AtomicBoolean compactionAgain = new AtomicBoolean();
    private final AtomicLong compactionAttempts = new AtomicLong();
    private final AtomicLong compactionFailures = new AtomicLong();
    private final NavigableMap<Long, Map<String, Object>> events = new TreeMap<>();
    private final NavigableMap<Long, Long> eventSizes = new TreeMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<Map<String, Object>>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private long resultEventId = -1L;
    private long journalBytes;
    private long retainedBytes;
    private long retainedMemoryCost;
    private long bytesScanned;
    private long recordsRead;
    private long lastScannedId = -1L;
    private long lastCompactionAt;
    private long lastCompactionDurationMs;
    private long nextPersistenceRetryNanos;
    private int consecutivePersistenceFailures;
    private boolean needsNewline;
    private boolean historyDegraded;
    private boolean sequenceUncertain;
    private boolean appendBlocked;
    private boolean repairBlocked;
    private boolean legacyOversized;
    private boolean persistenceDegraded;
    private String degradationReason;

    JobEvents(Path file, int capacity) throws Exception {
        this(file, capacity, DEFAULT_MAX_JOURNAL_BYTES, Runnable::run, () -> { });
    }

    JobEvents(Path file, int capacity, Executor compactionExecutor) throws Exception {
        this(file, capacity, DEFAULT_MAX_JOURNAL_BYTES, compactionExecutor, () -> { });
    }

    JobEvents(Path file, int capacity, Executor compactionExecutor, Runnable afterSnapshotWrite) throws Exception {
        this(file, capacity, DEFAULT_MAX_JOURNAL_BYTES, compactionExecutor, afterSnapshotWrite);
    }

    JobEvents(Path file, int capacity, long maxJournalBytes, Executor compactionExecutor) throws Exception {
        this(file, capacity, maxJournalBytes, MAX_RETAINED_MEMORY_BYTES, compactionExecutor, () -> { },
                fileUsableSpace(file));
    }

    JobEvents(Path file, int capacity, long maxJournalBytes, long maxRetainedMemoryBytes,
              Executor compactionExecutor) throws Exception {
        this(file, capacity, maxJournalBytes, maxRetainedMemoryBytes, compactionExecutor, () -> { },
                fileUsableSpace(file));
    }

    JobEvents(Path file, int capacity, long maxJournalBytes, Executor compactionExecutor,
              Runnable afterSnapshotWrite) throws Exception {
        this(file, capacity, maxJournalBytes, MAX_RETAINED_MEMORY_BYTES, compactionExecutor, afterSnapshotWrite,
                fileUsableSpace(file));
    }

    private static LongSupplier fileUsableSpace(Path file) {
        return () -> {
            try {
                return Files.getFileStore(file.getParent()).getUsableSpace();
            } catch (Exception unavailable) {
                return -1L;
            }
        };
    }

    JobEvents(Path file, int capacity, long maxJournalBytes, Executor compactionExecutor,
              Runnable afterSnapshotWrite, LongSupplier usableSpace) throws Exception {
        this(file, capacity, maxJournalBytes, MAX_RETAINED_MEMORY_BYTES,
                compactionExecutor, afterSnapshotWrite, usableSpace);
    }

    JobEvents(Path file, int capacity, long maxJournalBytes, long maxRetainedMemoryBytes,
              Executor compactionExecutor, Runnable afterSnapshotWrite, LongSupplier usableSpace) throws Exception {
        if (capacity < 1) throw new IllegalArgumentException("Journal event capacity must be positive");
        if (maxJournalBytes < 1) throw new IllegalArgumentException("Journal byte budget must be positive");
        if (maxRetainedMemoryBytes < 1) throw new IllegalArgumentException("Journal memory budget must be positive");
        this.file = file.toAbsolutePath().normalize();
        this.capacity = capacity;
        this.maxJournalBytes = maxJournalBytes;
        this.maxRetainedMemoryBytes = maxRetainedMemoryBytes;
        this.compactionExecutor = compactionExecutor;
        this.afterSnapshotWrite = afterSnapshotWrite;
        this.usableSpace = usableSpace;
        loadBoundedHistory();
    }

    /**
     * Rehydrate at most the configured tail window. A too-large historical file is left untouched:
     * the retained tail remains readable, while writes stop until an operator has preserved and
     * repaired the original evidence.
     */
    private void loadBoundedHistory() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                historyDegraded = true;
                appendBlocked = true;
                degradationReason = "The journal path is not a regular file.";
                return;
            }
            journalBytes = Files.size(file);
            long start = Math.max(0L, journalBytes - maxJournalBytes);
            scanTail(file, start, journalBytes - start);
            if (journalBytes > maxJournalBytes) {
                historyDegraded = true;
                appendBlocked = true;
                legacyOversized = true;
                degradationReason = "The existing journal exceeds its byte budget; only a bounded tail was loaded and the original file was left unchanged.";
            } else if (sequenceUncertain) {
                appendBlocked = true;
                historyDegraded = true;
                degradationReason = "The latest journal sequence could not be verified; appends are disabled to avoid reusing an event ID.";
            } else if (!historyDegraded && (recordsRead > events.size() || journalBytes > retainedBytes)) {
                // A normal, valid journal can be compacted to its configured count/byte window.
                scheduleCompaction();
            }
        } catch (Exception failure) {
            historyDegraded = true;
            appendBlocked = true;
            persistenceDegraded = true;
            degradationReason = "The journal could not be read safely; event history is unavailable for this Job.";
            java.util.logging.Logger.getLogger(JobEvents.class.getName())
                    .warning("A Job event journal could not be read; the Server will keep serving other requests");
        }
    }

    private void scanTail(Path source, long start, long length) throws Exception {
        bytesScanned = length;
        if (length <= 0) return;
        boolean skipPartialFirstLine = start > 0;
        boolean lineTooLong = false;
        long lineBytes = 0L;
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(MAX_EVENT_LINE_BYTES, (int) Math.min(length, 8192L)));
        try (SeekableByteChannel channel = Files.newByteChannel(source, StandardOpenOption.READ)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    int value = buffer.get() & 0xff;
                    if (skipPartialFirstLine) {
                        if (value == '\n') {
                            skipPartialFirstLine = false;
                            historyDegraded = true;
                            sequenceUncertain = true;
                        }
                        continue;
                    }
                    if (value == '\n') {
                        if (lineTooLong) {
                            malformedRecord(true);
                        } else if (lineBytes > 0) {
                            byte[] row = line.toByteArray();
                            int lengthWithoutCr = row.length > 0 && row[row.length - 1] == '\r' ? row.length - 1 : row.length;
                            parseRecord(row, lengthWithoutCr, lineBytes + 1L, true);
                        }
                        line.reset();
                        lineBytes = 0L;
                        lineTooLong = false;
                        continue;
                    }
                    lineBytes++;
                    if (!lineTooLong) {
                        if (lineBytes > MAX_EVENT_LINE_BYTES) {
                            lineTooLong = true;
                            line.reset();
                        } else {
                            line.write(value);
                        }
                    }
                }
                buffer.clear();
            }
            if (skipPartialFirstLine) {
                historyDegraded = true;
                sequenceUncertain = true;
            } else if (lineTooLong) {
                // The original line may begin outside the scanned tail; its ID cannot be trusted.
                malformedRecord(start > 0);
                sequenceUncertain = true;
            } else if (lineBytes > 0) {
                byte[] row = line.toByteArray();
                int lengthWithoutCr = row.length > 0 && row[row.length - 1] == '\r' ? row.length - 1 : row.length;
                boolean parsed = parseRecord(row, lengthWithoutCr, lineBytes + 1L, false);
                needsNewline = parsed;
                if (!parsed) {
                    sequenceUncertain = true;
                    appendBlocked = true;
                    degradationReason = "The final journal record is unterminated and could not be verified; appends are disabled to preserve the original bytes.";
                }
            }
        }
    }

    private boolean parseRecord(byte[] row, int length, long storedBytes, boolean terminated) {
        if (length <= 0) {
            malformedRecord(terminated);
            return false;
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(row, 0, length)).toString();
            @SuppressWarnings("unchecked") Map<String, Object> event = JSON.readValue(json, Map.class);
            Object rawId = event.get("id");
            long id;
            if (rawId instanceof BigInteger bigInteger) id = bigInteger.longValueExact();
            else if (rawId instanceof Byte || rawId instanceof Short || rawId instanceof Integer || rawId instanceof Long)
                id = ((Number) rawId).longValue();
            else throw new IOException("Missing integral event ID");
            if (id <= 0L) throw new IOException("Invalid event ID");
            long prior = sequence.get();
            if (lastScannedId > 0L) {
                if (id <= lastScannedId) {
                    malformedRecord(terminated);
                    return false;
                }
                if (id > lastScannedId + 1L) {
                    historyDegraded = true;
                    repairBlocked = true;
                }
            }
            lastScannedId = id;
            sequence.set(Math.max(prior, id));
            recordsRead++;
            if ("result".equals(event.get("event"))) resultEventId = Math.max(resultEventId, id);
            events.put(id, event);
            long normalizedSize = Math.max(1L, storedBytes);
            eventSizes.put(id, normalizedSize);
            retainedBytes += normalizedSize;
            retainedMemoryCost += normalizedSize + RETAINED_RECORD_OVERHEAD_BYTES;
            trimRetainedEvents();
            if (id >= prior) sequenceUncertain = false;
            return true;
        } catch (Exception malformed) {
            malformedRecord(terminated);
            return false;
        }
    }

    private void malformedRecord(boolean terminated) {
        historyDegraded = true;
        if (terminated) {
            sequenceUncertain = true;
            repairBlocked = true;
        }
        // A later valid monotonic row will clear sequenceUncertain during parseRecord.
    }

    Map<String, Object> append(String type, Map<String, ?> data) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        List<Consumer<Map<String, Object>>> observers;
        boolean requestCompaction;
        Object journalLock = fileLock();
        synchronized (journalLock) {
            synchronized (this) {
                Map<String, Object> payload = boundedPayload(data);
                event.put("event", type);
                event.put("data", payload);
                if (sequence.get() == Long.MAX_VALUE) {
                    sequenceUncertain = true;
                    appendBlocked = true;
                    historyDegraded = true;
                    degradationReason = "The event ID sequence reached its maximum value; no further cursor IDs can be assigned safely.";
                }
                if (sequenceUncertain) {
                    // There is no safe cursor ID after an unparseable tail. Wake observers so they
                    // can receive the separate degraded-history notice, but never invent or reuse
                    // an ID that could collide with an event hidden in the original bytes.
                    event.put("journalPersistence", "DEGRADED");
                    Map<String, Object> notice = diagnostic();
                    if (notice != null) event.put("journalDiagnostic", notice);
                    observers = List.copyOf(listeners);
                    requestCompaction = false;
                } else {
                    long id = sequence.get() + 1L;
                    event.put("id", id);
                    byte[] encoded = JSON.writeValueAsBytes(event);
                    if (encoded.length + 1 > MAX_EVENT_LINE_BYTES) {
                        throw new IOException("Event journal record exceeded its line-size limit");
                    }
                    byte[] line = new byte[encoded.length + 1];
                    System.arraycopy(encoded, 0, line, 0, encoded.length);
                    line[line.length - 1] = '\n';
                    boolean persisted = persistAppend(line);
                    if (!persisted) {
                        historyDegraded = true;
                        Map<String, Object> notice = diagnostic();
                        event.put("journalPersistence", "DEGRADED");
                        if (notice != null) event.put("journalDiagnostic", notice);
                    }
                    sequence.set(id);
                    long recordBytes = line.length;
                    events.put(id, event);
                    eventSizes.put(id, recordBytes);
                    retainedBytes += recordBytes;
                    retainedMemoryCost += recordBytes + RETAINED_RECORD_OVERHEAD_BYTES;
                    if ("result".equals(type)) resultEventId = id;
                    trimRetainedEvents();
                    observers = List.copyOf(listeners);
                    requestCompaction = id % COMPACT_EVERY_EVENTS == 0;
                }
            }
        }
        for (Consumer<Map<String, Object>> listener : observers) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // Observer failures must not stop journal persistence or Worker consumption.
            }
        }
        if (requestCompaction) scheduleCompaction();
        return event;
    }

    /** Writes the complete record or truncates a partial ENOSPC write back to the old boundary. */
    private boolean persistAppend(byte[] line) {
        long now = System.nanoTime();
        if (appendBlocked || (persistenceDegraded && now < nextPersistenceRetryNanos)) return false;
        try {
            long actualSize = Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0L;
            if (actualSize > maxJournalBytes) {
                legacyOversized = true;
                appendBlocked = true;
                historyDegraded = true;
                degradationReason = "The journal exceeds its byte budget; the original file was left unchanged.";
                return false;
            }
            journalBytes = actualSize;
            long delimiterBytes = needsNewline ? 1L : 0L;
            if (journalBytes + delimiterBytes + line.length > maxJournalBytes) {
                if (!compactCurrentUnderFileLock()) throw new IOException("Bounded journal compaction did not complete");
                delimiterBytes = 0L;
            }
            long available = usableSpace.getAsLong();
            if (available >= 0L && available < MIN_STORAGE_HEADROOM_BYTES + line.length) {
                throw new IOException("Journal storage headroom is below the safety threshold");
            }
            long originalSize = Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0L;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.position(originalSize);
                if (delimiterBytes > 0L) {
                    ByteBuffer delimiter = ByteBuffer.wrap(new byte[]{'\n'});
                    while (delimiter.hasRemaining()) channel.write(delimiter);
                }
                ByteBuffer bytes = ByteBuffer.wrap(line);
                while (bytes.hasRemaining()) channel.write(bytes);
            } catch (IOException writeFailure) {
                try (FileChannel repair = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    repair.truncate(originalSize);
                } catch (Exception truncateFailure) {
                    appendBlocked = true;
                    degradationReason = "A failed journal append could not be rolled back safely.";
                }
                throw writeFailure;
            }
            journalBytes = originalSize + delimiterBytes + line.length;
            needsNewline = false;
            persistenceDegraded = false;
            degradationReason = null;
            consecutivePersistenceFailures = 0;
            nextPersistenceRetryNanos = 0L;
            return true;
        } catch (Exception failure) {
            markPersistenceFailure(failure);
            return false;
        }
    }

    private void markPersistenceFailure(Exception failure) {
        persistenceDegraded = true;
        historyDegraded = true;
        if (degradationReason == null || !appendBlocked) {
            degradationReason = failure instanceof java.nio.file.FileSystemException
                    ? "Journal storage is unavailable or out of space."
                    : "The event journal could not be written safely.";
        }
        consecutivePersistenceFailures = Math.min(8, consecutivePersistenceFailures + 1);
        long delaySeconds = Math.min(300L, 5L << Math.min(6, consecutivePersistenceFailures - 1));
        nextPersistenceRetryNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(delaySeconds);
        java.util.logging.Logger.getLogger(JobEvents.class.getName())
                .warning("Event journal persistence degraded; retries are rate-limited and this Job remains available");
    }

    synchronized List<Map<String, Object>> after(long id) {
        return after(id, Integer.MAX_VALUE);
    }

    synchronized List<Map<String, Object>> after(long id, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (limit <= 0) return result;
        for (Map<String, Object> event : events.tailMap(id, false).values()) {
            result.add(event);
            if (result.size() >= limit) break;
        }
        return result;
    }

    synchronized boolean hasMore(long id) {
        return !events.isEmpty() && events.lastKey() > id;
    }

    /** True only after the stream cursor has passed the final result marker. */
    synchronized boolean resultDeliveredThrough(long id) {
        return resultEventId >= 0L && id >= resultEventId;
    }

    long latest() { return sequence.get(); }

    AutoCloseable listen(Consumer<Map<String, Object>> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    boolean isClosed() { return false; }

    synchronized boolean truncatedHistory() {
        return historyDegraded || (!events.isEmpty() && events.firstKey() > 1L);
    }

    synchronized boolean historyDegraded() { return historyDegraded; }

    /** Recover a missing terminal cursor from the separately persisted Job result, without rewriting the journal. */
    synchronized void recoverMissingTerminalResult(Map<String, ?> resultData) throws Exception {
        if (resultEventId >= 0L) return;
        historyDegraded = true;
        appendBlocked = true;
        repairBlocked = true;
        degradationReason = "The terminal result marker is missing from the event journal; the Job result was recovered from metadata when available.";
        if (sequenceUncertain || sequence.get() == Long.MAX_VALUE || resultData == null) return;

        long id = sequence.get() + 1L;
        Map<String, Object> recovered = new LinkedHashMap<>();
        recovered.put("id", id);
        recovered.put("event", "result");
        recovered.put("data", boundedPayload(resultData));
        recovered.put("journalPersistence", "DEGRADED");
        recovered.put("journalDiagnostic", diagnostic());
        byte[] encoded = JSON.writeValueAsBytes(recovered);
        if (encoded.length + 1 > MAX_EVENT_LINE_BYTES) throw new IOException("Recovered terminal result exceeded the journal line-size limit");
        events.put(id, recovered);
        eventSizes.put(id, (long) encoded.length + 1L);
        retainedBytes += encoded.length + 1L;
        retainedMemoryCost += encoded.length + 1L + RETAINED_RECORD_OVERHEAD_BYTES;
        sequence.set(id);
        resultEventId = id;
        trimRetainedEvents();
    }

    synchronized long oldestId() {
        return events.isEmpty() ? -1L : events.firstKey();
    }

    synchronized long cacheWeightBytes() {
        return retainedMemoryCost;
    }

    synchronized Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journalPersistence", persistenceDegraded || appendBlocked ? "DEGRADED" : "HEALTHY");
        result.put("journalBytes", journalBytes);
        result.put("maxJournalBytes", maxJournalBytes);
        result.put("recordsRead", recordsRead);
        result.put("bytesScanned", bytesScanned);
        result.put("retainedEventCount", events.size());
        result.put("retainedBytes", retainedBytes);
        result.put("retainedMemoryBytes", retainedMemoryCost);
        result.put("maxRetainedMemoryBytes", Math.min(maxJournalBytes, maxRetainedMemoryBytes));
        result.put("newestRecordExceedsMemoryShare",retainedMemoryCost>Math.min(maxJournalBytes,maxRetainedMemoryBytes));
        result.put("compactionBacklogBytes", Math.max(0L, journalBytes-retainedBytes));
        result.put("oldestEventId", oldestId());
        result.put("latestEventId", sequence.get());
        result.put("historyTruncated", truncatedHistory());
        result.put("compactionAttempts", compactionAttempts.get());
        result.put("compactionFailures", compactionFailures.get());
        result.put("lastCompactionAt", lastCompactionAt == 0L ? null : Instant.ofEpochMilli(lastCompactionAt).toString());
        result.put("lastCompactionDurationMs", lastCompactionDurationMs);
        result.put("lastCompactionAgeMs", lastCompactionAt == 0L ? null : Math.max(0L,System.currentTimeMillis()-lastCompactionAt));
        long available = usableSpace.getAsLong();
        result.put("storageUsableBytes", available);
        result.put("storageHeadroomLow", available >= 0L && available < MIN_STORAGE_HEADROOM_BYTES);
        return result;
    }

    synchronized Map<String, Object> diagnostic() {
        boolean nearLimit = journalBytes >= Math.ceil(maxJournalBytes * 0.8);
        if (!historyDegraded && !persistenceDegraded && !appendBlocked && !nearLimit) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        String code = legacyOversized ? "ATT-JOURNAL-LIMIT-EXCEEDED"
                : persistenceDegraded || appendBlocked ? "ATT-JOURNAL-PERSISTENCE-DEGRADED"
                : historyDegraded ? "ATT-JOURNAL-HISTORY-TRUNCATED" : "ATT-JOURNAL-STORAGE-NEAR-LIMIT";
        result.put("code", code);
        result.put("severity", "warning");
        result.put("summary", degradationReason != null ? degradationReason
                : nearLimit ? "The event journal is approaching its configured byte limit." : "The event history is incomplete.");
        result.putAll(metrics());
        return result;
    }

    private Object fileLock() {
        int index = Math.floorMod(file.hashCode(), COMPACTION_LOCKS.length);
        return COMPACTION_LOCKS[index];
    }

    private Map<String, Object> boundedPayload(Map<String, ?> data) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        int originalBytes=JSON.writeValueAsBytes(payload).length;
        if (originalBytes > MAX_EVENT_DATA_BYTES) {
            Object console = payload.get("console");
            Map<String,Object> retained=new LinkedHashMap<>();
            for(String key:new String[]{"status","code","message","executionId","caseId","stage","action","executionEventType"})if(payload.get(key) instanceof String)retained.put(key,boundedText(payload.get(key),512));
            for(String key:new String[]{"exitCode","durationMs"})if(payload.get(key) instanceof Number number)retained.put(key,number.longValue());
            List<String> dropped=new ArrayList<>();for(String key:payload.keySet())if(!retained.containsKey(key)&&!"console".equals(key)&&dropped.size()<32)dropped.add(boundedText(key,128));
            payload.clear();
            payload.putAll(retained);
            payload.put("truncated", true);
            payload.put("journalLimit",att.server.api.LimitDiagnostic.create("ATT-JOURNAL-EVENT-TRUNCATED",
                    "Event details were omitted from the retained journal payload", "server.eventJournal.maxEventDataBytes",
                    MAX_EVENT_DATA_BYTES, originalBytes, "server.eventJournal", att.server.api.LimitOutcome.DATA_TRUNCATED,
                    "presentation", "Use bounded event details or open the retained report artifact", null));
            payload.put("summary", "Event payload exceeded the retained event size limit");
            if(!dropped.isEmpty())payload.put("droppedFields",dropped);
            if (console instanceof String text && !text.isEmpty()) {
                payload.put("console", text);
                if (JSON.writeValueAsBytes(payload).length > MAX_EVENT_DATA_BYTES) {
                    payload.put("consoleTruncated", true);
                    int low=0, high=text.length();
                    while(low<high) {
                        int mid=(low+high+1)/2;
                        payload.put("console", consolePrefix(text,mid));
                        if(JSON.writeValueAsBytes(payload).length<=MAX_EVENT_DATA_BYTES)low=mid;
                        else high=mid-1;
                    }
                    payload.put("console",consolePrefix(text,low));
                }
            }
        }
        return payload;
    }
    private static String boundedText(Object value,int limit){String text=String.valueOf(value);if(text.length()<=limit)return text;if(limit>0&&Character.isHighSurrogate(text.charAt(limit-1)))limit--;return text.substring(0,limit);}

    private static String consolePrefix(String text,int length) {
        if(length>0 && Character.isHighSurrogate(text.charAt(length-1)))length--;
        return text.substring(0,length)+" [truncated by journal]";
    }

    private void trimRetainedEvents() {
        long memoryBudget=Math.min(maxJournalBytes,maxRetainedMemoryBytes);
        while (!events.isEmpty() && (events.size() > capacity || (retainedMemoryCost > memoryBudget && events.size() > 1))) {
            Map.Entry<Long, Map<String, Object>> removed = events.pollFirstEntry();
            Long size = eventSizes.remove(removed.getKey());
            long bytes = size == null ? 0L : size;
            retainedBytes = Math.max(0L, retainedBytes - bytes);
            retainedMemoryCost = Math.max(0L, retainedMemoryCost - bytes - RETAINED_RECORD_OVERHEAD_BYTES);
        }
    }

    private void scheduleCompaction() {
        if (!compactionScheduled.compareAndSet(false, true)) {
            compactionAgain.set(true);
            return;
        }
        try {
            compactionExecutor.execute(() -> {
                boolean retryOnNextTrigger = false;
                try {
                    retryOnNextTrigger = !compactSnapshot();
                    if(retryOnNextTrigger)compactionFailures.incrementAndGet();
                } catch (Exception failure) {
                    retryOnNextTrigger = true;
                    compactionFailures.incrementAndGet();
                    java.util.logging.Logger.getLogger(JobEvents.class.getName())
                            .warning("Event journal compaction failed; bounded appends remain enabled");
                } finally {
                    compactionScheduled.set(false);
                    boolean requestedAgain = compactionAgain.getAndSet(false);
                    // A failed catch-up waits for a later append threshold; disk-budget compaction
                    // is synchronous and therefore cannot enter a retry loop.
                    if (!retryOnNextTrigger && requestedAgain) scheduleCompaction();
                }
            });
        } catch (RejectedExecutionException full) {
            compactionScheduled.set(false);
            compactionAgain.set(false);
            compactionFailures.incrementAndGet();
            java.util.logging.Logger.getLogger(JobEvents.class.getName())
                    .warning("Event journal compaction deferred; the byte budget is still enforced");
        }
    }

    /** Snapshot write runs outside the append lock; the final bounded tail and rename are serialized. */
    private boolean compactSnapshot() throws Exception {
        long started = System.nanoTime();
        compactionAttempts.incrementAndGet();
        List<Map<String, Object>> snapshot;
        long snapshotFirstId;
        long snapshotLastId;
        synchronized (this) {
            if(repairBlocked||appendBlocked||legacyOversized||sequenceUncertain)return false;
            if (events.isEmpty()) return true;
            snapshot = new ArrayList<>(events.values());
            snapshotFirstId = events.firstKey();
            snapshotLastId = events.lastKey();
        }

        Path temporary = temporaryPath();
        boolean replaced = false;
        try {
            MessageDigest digest = sha256();
            long written = writeSnapshot(temporary, snapshot, digest);
            afterSnapshotWrite.run();
            synchronized (fileLock()) {
                synchronized (this) {
                    if(repairBlocked||appendBlocked||legacyOversized||sequenceUncertain)return false;
                    if (events.isEmpty() || events.firstKey() > snapshotFirstId) return false;
                    List<Map<String, Object>> tail = new ArrayList<>(events.tailMap(snapshotLastId, false).values());
                    if (tail.size() > MAX_COMPACTION_TAIL_EVENTS || snapshot.size() + tail.size() > capacity) return false;
                    if (!tail.isEmpty() && ((Number) tail.get(0).get("id")).longValue() != snapshotLastId + 1L) return false;
                    for (Map<String, Object> event : tail) {
                        written = writeEvent(temporary, event, digest, written);
                    }
                    if (written > maxJournalBytes) return false;
                    verifyDigest(temporary, digest.digest());
                    atomicReplace(temporary);
                    replaced = true;
                    journalBytes = written;
                    needsNewline = false;
                    lastCompactionAt = System.currentTimeMillis();
                    lastCompactionDurationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    return true;
                }
            }
        } finally {
            if (!replaced) Files.deleteIfExists(temporary);
        }
    }

    /** Hard-cap fallback: the path lock blocks writers, so the latest retained window always fits. */
    private boolean compactCurrentUnderFileLock() throws Exception {
        long started = System.nanoTime();
        compactionAttempts.incrementAndGet();
        List<Map<String, Object>> snapshot;
        synchronized (this) {
            if(repairBlocked||appendBlocked||legacyOversized||sequenceUncertain)return false;
            snapshot = new ArrayList<>(events.values());
        }
        Path temporary = temporaryPath();
        boolean replaced = false;
        try {
            MessageDigest digest = sha256();
            long written = writeSnapshot(temporary, snapshot, digest);
            if (written > maxJournalBytes) throw new IOException("Retained event window exceeds the byte budget");
            verifyDigest(temporary, digest.digest());
            synchronized (this) {
                atomicReplace(temporary);
                replaced = true;
                journalBytes = written;
                needsNewline = false;
                lastCompactionAt = System.currentTimeMillis();
                lastCompactionDurationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            }
            return true;
        } catch (Exception failure) {
            compactionFailures.incrementAndGet();
            throw failure;
        } finally {
            if (!replaced) Files.deleteIfExists(temporary);
        }
    }

    private long writeSnapshot(Path temporary, List<Map<String, Object>> snapshot,
                               MessageDigest digest) throws Exception {
        long available=usableSpace.getAsLong();
        long estimatedBytes=Math.max(1L,Math.min(maxJournalBytes,retainedBytes));
        if(available>=0L&&available<estimatedBytes+MIN_STORAGE_HEADROOM_BYTES)
            throw new IOException("Insufficient storage headroom for a verified journal snapshot");
        Files.deleteIfExists(temporary);
        long written = 0L;
        try (OutputStream raw = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             DigestOutputStream output = new DigestOutputStream(raw, digest)) {
            for (Map<String, Object> event : snapshot) {
                byte[] encoded = JSON.writeValueAsBytes(event);
                if (encoded.length + 1 > MAX_EVENT_LINE_BYTES || written + encoded.length + 1L > maxJournalBytes)
                    throw new IOException("Retained event snapshot exceeds the configured budget");
                output.write(encoded);
                output.write('\n');
                written += encoded.length + 1L;
            }
        }
        return written;
    }

    private long writeEvent(Path temporary, Map<String, Object> event, MessageDigest digest,
                            long written) throws Exception {
        byte[] encoded = JSON.writeValueAsBytes(event);
        if (encoded.length + 1 > MAX_EVENT_LINE_BYTES || written + encoded.length + 1L > maxJournalBytes)
            throw new IOException("Compaction tail exceeds the configured byte budget");
        try (OutputStream raw = Files.newOutputStream(temporary, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
             DigestOutputStream output = new DigestOutputStream(raw, digest)) {
            output.write(encoded);
            output.write('\n');
        }
        return written + encoded.length + 1L;
    }

    private void verifyDigest(Path temporary, byte[] expectedDigest) throws Exception {
        MessageDigest actual = sha256();
        long size = 0L;
        try (InputStream raw = Files.newInputStream(temporary);
             DigestInputStream input = new DigestInputStream(raw, actual)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                size += count;
                if (size > maxJournalBytes) throw new IOException("Compaction verification exceeded the byte budget");
            }
        }
        if (!MessageDigest.isEqual(expectedDigest, actual.digest())) {
            throw new IOException("Compaction verification failed");
        }
    }

    private void atomicReplace(Path temporary) throws IOException {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path temporaryPath() {
        return file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
