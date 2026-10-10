package att.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Retained, job-local event journal. Client delivery runs independently from the Worker consumer. */
final class JobEvents {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_EVENT_DATA_BYTES = 65536;
    private static final int COMPACT_EVERY_EVENTS = 128;
    private static final int MAX_COMPACTION_TAIL_EVENTS = 32;
    private static final Object[] COMPACTION_LOCKS = new Object[64];
    private static final ThreadPoolExecutor COMPACTIONS = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1024), runnable -> {
                Thread thread = new Thread(runnable, "att-server-event-compactor");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static {
        for (int i = 0; i < COMPACTION_LOCKS.length; i++) COMPACTION_LOCKS[i] = new Object();
    }

    private final Path file;
    private final int capacity;
    private final Executor compactionExecutor;
    private final Runnable afterSnapshotWrite;
    private final AtomicLong sequence = new AtomicLong();
    private long resultEventId = -1L;
    private final AtomicBoolean compactionScheduled = new AtomicBoolean();
    private final AtomicBoolean compactionAgain = new AtomicBoolean();
    private final NavigableMap<Long, Map<String, Object>> events = new TreeMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<Map<String, Object>>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    JobEvents(Path file, int capacity) throws Exception {
        this(file, capacity, COMPACTIONS, () -> { });
    }

    JobEvents(Path file, int capacity, Executor compactionExecutor) throws Exception {
        this(file, capacity, compactionExecutor, () -> { });
    }

    JobEvents(Path file, int capacity, Executor compactionExecutor, Runnable afterSnapshotWrite) throws Exception {
        this.file = file;
        this.capacity = capacity;
        this.compactionExecutor = compactionExecutor;
        this.afterSnapshotWrite = afterSnapshotWrite;
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file)) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        @SuppressWarnings("unchecked") Map<String, Object> event = JSON.readValue(line, Map.class);
                        sequence.set(Math.max(sequence.get(), ((Number) event.get("id")).longValue()));
                        long id = ((Number) event.get("id")).longValue();
                        events.put(id, event);
                        if ("result".equals(event.get("event"))) resultEventId = id;
                    } catch (Exception ignored) {
                        // Ignore an incomplete or malformed final line and keep the valid journal prefix.
                    }
                }
            }
            if (events.size() > capacity) {
                trimRetainedEvents();
                scheduleCompaction();
            }
        }
    }

    Map<String, Object> append(String type, Map<String, ?> data) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        boolean scheduleCompaction;
        synchronized (this) {
            Map<String, Object> payload = boundedPayload(data);
            long id = sequence.incrementAndGet();
            event.put("id", id);
            event.put("event", type);
            event.put("data", payload);
            Files.writeString(file, JSON.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            events.put(id, event);
            if ("result".equals(type)) resultEventId = id;
            trimRetainedEvents();
            for (Consumer<Map<String, Object>> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException ignored) {
                    // Observer failures must not stop durable journal writes or Worker consumption.
                }
            }
            scheduleCompaction = id % COMPACT_EVERY_EVENTS == 0;
        }
        if (scheduleCompaction) scheduleCompaction();
        return event;
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

    private Map<String, Object> boundedPayload(Map<String, ?> data) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        if (JSON.writeValueAsBytes(payload).length > MAX_EVENT_DATA_BYTES) {
            payload.clear();
            payload.put("truncated", true);
            payload.put("summary", "Event payload exceeded the retained event size limit");
        }
        return payload;
    }

    private void trimRetainedEvents() {
        while (events.size() > capacity) events.pollFirstEntry();
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
                } catch (Exception failure) {
                    retryOnNextTrigger = true;
                    java.util.logging.Logger.getLogger(JobEvents.class.getName())
                            .fine("Event journal compaction will be retried after a later event batch");
                } finally {
                    compactionScheduled.set(false);
                    boolean requestedAgain = compactionAgain.getAndSet(false);
                    // A failed catch-up must wait for a later append threshold. Immediate retries
                    // can repeatedly serialize the same oversized tail while producers are active.
                    if (!retryOnNextTrigger && requestedAgain) {
                        scheduleCompaction();
                    }
                }
            });
        } catch (RejectedExecutionException full) {
            compactionScheduled.set(false);
            compactionAgain.set(false);
        }
    }

    /** Writes a snapshot outside the append lock, then atomically catches up with a short tail. */
    private boolean compactSnapshot() throws Exception {
        int lockIndex = Math.floorMod(file.toAbsolutePath().normalize().hashCode(), COMPACTION_LOCKS.length);
        synchronized (COMPACTION_LOCKS[lockIndex]) {
            return compactSnapshotUnderFileLock();
        }
    }

    private boolean compactSnapshotUnderFileLock() throws Exception {
        List<Map<String, Object>> snapshot;
        long snapshotLastId;
        synchronized (this) {
            if (events.isEmpty()) return true;
            snapshot = new ArrayList<>(events.values());
            snapshotLastId = events.lastKey();
        }

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        boolean replaced = false;
        try {
            StringBuilder snapshotText = new StringBuilder();
            for (Map<String, Object> event : snapshot) snapshotText.append(JSON.writeValueAsString(event)).append('\n');
            Files.writeString(temporary, snapshotText, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            afterSnapshotWrite.run();

            synchronized (this) {
                List<Map<String, Object>> tail = new ArrayList<>();
                tail.addAll(events.tailMap(snapshotLastId, false).values());
                if (tail.size() > MAX_COMPACTION_TAIL_EVENTS) return false;
                if (snapshot.size() + tail.size() > capacity) return false;
                if (!tail.isEmpty()
                        && ((Number) tail.get(0).get("id")).longValue() != snapshotLastId + 1L) return false;

                if (!tail.isEmpty()) {
                    StringBuilder tailText = new StringBuilder();
                    for (Map<String, Object> event : tail) tailText.append(JSON.writeValueAsString(event)).append('\n');
                    Files.writeString(temporary, tailText, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
                replaced = true;
            }
            return true;
        } finally {
            if (!replaced) Files.deleteIfExists(temporary);
        }
    }
}
