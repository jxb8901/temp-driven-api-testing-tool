package att.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    /** Sample only the supplied Worker processes while an execution pool is idle. */
    static Map<String, Object> sampleProcesses(List<Process> processes, int samples, long intervalMs) throws Exception {
        if (processes.isEmpty() || samples < 1 || intervalMs < 0)
            throw new IllegalArgumentException("Worker resource sampling requires processes, samples, and a nonnegative interval");
        List<Long> pids = processes.stream().map(Process::pid).toList();
        List<Long> peakRssBytes = new ArrayList<>(java.util.Collections.nCopies(pids.size(), 0L));
        List<IoCounters> firstIo = new ArrayList<>(java.util.Collections.nCopies(pids.size(), null));
        List<IoCounters> lastIo = new ArrayList<>(java.util.Collections.nCopies(pids.size(), null));
        long peakTotalRssBytes = 0L;
        int completedSamples = 0;
        for (int sample = 0; sample < samples; sample++) {
            if (sample > 0 && intervalMs > 0) Thread.sleep(intervalMs);
            String pidArgument = pids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            Process ps = new ProcessBuilder("ps", "-p", pidArgument, "-o", "pid=,rss=")
                    .redirectErrorStream(true).start();
            Map<Long, Long> rssKb = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length != 2) continue;
                    try { rssKb.put(Long.parseLong(fields[0]), Long.parseLong(fields[1])); }
                    catch (NumberFormatException ignored) { }
                }
            }
            if (!ps.waitFor(2, TimeUnit.SECONDS)) {
                ps.destroyForcibly();
                if (!ps.waitFor(2, TimeUnit.SECONDS)) throw new IllegalStateException("ps sampler did not exit");
            }
            if (ps.exitValue() != 0) throw new IllegalStateException("ps failed while sampling Worker resources");
            long totalRssBytes = 0L;
            for (int index = 0; index < pids.size(); index++) {
                long pid = pids.get(index);
                Long residentKb = rssKb.get(pid);
                if (residentKb == null || !processes.get(index).isAlive())
                    throw new IllegalStateException("An idle Worker exited or became unobservable during resource sampling");
                long residentBytes = Math.multiplyExact(residentKb, 1024L);
                peakRssBytes.set(index, Math.max(peakRssBytes.get(index), residentBytes));
                totalRssBytes = saturatingAdd(totalRssBytes, residentBytes);
                IoCounters counters = readIoCounters(pid);
                if (sample == 0) firstIo.set(index, counters);
                lastIo.set(index, counters);
            }
            peakTotalRssBytes = Math.max(peakTotalRssBytes, totalRssBytes);
            completedSamples++;
        }
        boolean physicalIoAvailable = firstIo.stream().allMatch(value -> value != null)
                && lastIo.stream().allMatch(value -> value != null);
        long readBytes = 0L;
        long writeBytes = 0L;
        if (physicalIoAvailable) {
            for (int index = 0; index < pids.size(); index++) {
                readBytes = saturatingAdd(readBytes, Math.max(0L, lastIo.get(index).readBytes - firstIo.get(index).readBytes));
                writeBytes = saturatingAdd(writeBytes, Math.max(0L, lastIo.get(index).writeBytes - firstIo.get(index).writeBytes));
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("scope", "idle execution Worker processes only");
        result.put("workerCount", processes.size());
        result.put("sampleCount", completedSamples);
        result.put("samplingIntervalMs", intervalMs);
        result.put("peakTotalResidentBytes", peakTotalRssBytes);
        result.put("peakResidentBytesPerWorker", peakRssBytes);
        result.put("physicalIoAvailable", physicalIoAvailable);
        result.put("kernelReadBytesDuringIdle", readBytes);
        result.put("kernelWriteBytesDuringIdle", writeBytes);
        result.put("physicalIoSource", physicalIoAvailable ? "/proc/<pid>/io read_bytes/write_bytes" : "unavailable on this host");
        return result;
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
