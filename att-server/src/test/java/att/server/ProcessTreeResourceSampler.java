package att.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Best-effort OS RSS sampler for opt-in local benchmarks; measures the test JVM and its descendants. */
final class ProcessTreeResourceSampler implements AutoCloseable {
    private final long rootPid = ProcessHandle.current().pid();
    private final long intervalMs;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "att-benchmark-process-sampler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong peakResidentBytes = new AtomicLong();
    private final AtomicLong sampleCount = new AtomicLong();
    private final AtomicLong peakPhysicalReadBytes = new AtomicLong();
    private final AtomicLong peakPhysicalWriteBytes = new AtomicLong();
    private final AtomicLong firstPhysicalReadBytes = new AtomicLong(-1);
    private final AtomicLong firstPhysicalWriteBytes = new AtomicLong(-1);
    private volatile boolean available;
    private volatile boolean physicalIoAvailable;

    private ProcessTreeResourceSampler(long intervalMs) {
        this.intervalMs = intervalMs;
        sampler.scheduleWithFixedDelay(this::sampleQuietly, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    static ProcessTreeResourceSampler start() {
        return new ProcessTreeResourceSampler(25);
    }

    private void sampleQuietly() {
        try {
            Process process = new ProcessBuilder("ps", "-A", "-o", "pid=,ppid=,rss=")
                    .redirectErrorStream(true).start();
            Map<Long, ProcessRow> rows = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length != 3) continue;
                    try {
                        rows.put(Long.parseLong(fields[0]), new ProcessRow(Long.parseLong(fields[1]), Long.parseLong(fields[2])));
                    } catch (NumberFormatException ignored) { }
                }
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            ProcessRow root = rows.get(rootPid);
            if (root == null) return;
            Set<Long> members = new HashSet<>();
            members.add(rootPid);
            boolean changed;
            do {
                changed = false;
                for (Map.Entry<Long, ProcessRow> row : rows.entrySet())
                    if (!members.contains(row.getKey()) && members.contains(row.getValue().parentPid))
                        changed |= members.add(row.getKey());
            } while (changed);
            long totalKb = 0;
            long readBytes = 0, writeBytes = 0;
            boolean ioCountersFound = false;
            for (Long pid : members) {
                ProcessRow row = rows.get(pid);
                if (row != null) totalKb = Math.min(Long.MAX_VALUE / 1024, totalKb + row.rssKb);
                IoCounters io = readIoCounters(pid);
                if (io != null) {
                    ioCountersFound = true;
                    readBytes = saturatingAdd(readBytes, io.readBytes);
                    writeBytes = saturatingAdd(writeBytes, io.writeBytes);
                }
            }
            peakResidentBytes.accumulateAndGet(totalKb * 1024, Math::max);
            sampleCount.incrementAndGet();
            available = true;
            if (ioCountersFound) {
                physicalIoAvailable = true;
                firstPhysicalReadBytes.compareAndSet(-1, readBytes);
                firstPhysicalWriteBytes.compareAndSet(-1, writeBytes);
                peakPhysicalReadBytes.accumulateAndGet(Math.max(0, readBytes - firstPhysicalReadBytes.get()), Math::max);
                peakPhysicalWriteBytes.accumulateAndGet(Math.max(0, writeBytes - firstPhysicalWriteBytes.get()), Math::max);
            }
        } catch (Exception | LinkageError unavailable) {
            // Process sampling is supplemental evidence and must not fail the measured workload.
        }
    }

    Map<String, Object> snapshot() {
        return Map.of("available", available,
                "scope", "test-process-and-descendants",
                "peakResidentBytes", peakResidentBytes.get(),
                "physicalIoAvailable", physicalIoAvailable,
                "kernelReadBytes", peakPhysicalReadBytes.get(),
                "kernelWriteBytes", peakPhysicalWriteBytes.get(),
                "physicalIoSource", physicalIoAvailable ? "/proc/<pid>/io read_bytes/write_bytes" : "unavailable on this host",
                "sampleCount", sampleCount.get(),
                "samplingIntervalMs", intervalMs);
    }

    @Override public void close() {
        sampleQuietly();
        sampler.shutdownNow();
        try { sampler.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private record ProcessRow(long parentPid, long rssKb) { }

    private static IoCounters readIoCounters(long pid) {
        try {
            long read = -1, write = -1;
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc", Long.toString(pid), "io"))) {
                if (line.startsWith("read_bytes:")) read = Long.parseLong(line.substring("read_bytes:".length()).trim());
                else if (line.startsWith("write_bytes:")) write = Long.parseLong(line.substring("write_bytes:".length()).trim());
            }
            return read < 0 || write < 0 ? null : new IoCounters(read, write);
        } catch (Exception unavailable) { return null; }
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private record IoCounters(long readBytes, long writeBytes) { }
}
