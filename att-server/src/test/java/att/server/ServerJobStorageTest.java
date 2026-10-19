package att.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerJobStorageTest {
    @TempDir Path temp;

    @Test void sizeLimitsStayDisabledUntilConfigured() throws Exception {
        ServerRuntime runtime = runtime("disabled", null, Clock.systemUTC());
        try {
            String id = "J0000000000000001";
            seed(runtime, id, "PASS", Instant.parse("2020-01-01T00:00:00Z"), false, 3L * 1024 * 1024);
            runtime.cleanupJobStorage();
            assertNotNull(runtime.store.get(id));
            assertTrue(Files.exists(runtime.config.dataDir.resolve("jobs").resolve(id)));
            Map<String, Object> storage = storage(runtime);
            assertEquals(Boolean.FALSE, storage.get("enabled"));
            assertEquals("disabled", storage.get("state"));
            assertNull(storage.get("totalBytes"));
        } finally { runtime.close(); }
    }

    @Test void rejectsUnsupportedSingleJobLimitAndUnknownKeys() throws Exception {
        Path yaml = temp.resolve("invalid-storage.yaml");
        Files.writeString(yaml, serverYaml("invalid", "  jobStorage:\n    enabled: true\n    maxSingleJobSizeMb: 10\n"));
        IllegalArgumentException single = assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(yaml));
        assertTrue(single.getMessage().contains("maxSingleJobSizeMb"));
        Files.writeString(yaml, serverYaml("invalid", "  jobStorage:\n    enabled: true\n    vacuum: true\n"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(yaml));
        Files.writeString(yaml, serverYaml("invalid", "  jobStorage:\n    enabled: true\n    maxTotalSizeMb: 1\n    cleanupTargetSizeMb: 1\n"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(yaml));
    }

    @Test void evictsOldestTerminalJobUntilTargetAndBreaksTiesByJobId() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        ServerRuntime runtime = runtime("oldest", quota(0), clock);
        try {
            Instant finished = Instant.parse("2026-01-01T00:00:00Z");
            String higher = "J000000000000000B";
            String lower = "J000000000000000A";
            seed(runtime, higher, "PASS", finished, false, mib(1.6));
            seed(runtime, lower, "PASS", finished, false, mib(1.6));
            runtime.cleanupJobStorage();
            assertNull(runtime.store.get(lower));
            assertFalse(Files.exists(jobDir(runtime, lower)));
            assertNotNull(runtime.store.get(higher));
            assertTrue(Files.exists(jobDir(runtime, higher).resolve("artifacts.zip")));
            Map<String, Object> storage = storage(runtime);
            assertEquals("ok", storage.get("state"));
            assertEquals(Boolean.FALSE, storage.get("quotaUnmet"));
            assertTrue(runtime.store.auditOutcomes("JOB_STORAGE_EVICT").stream().anyMatch(outcome -> outcome.startsWith("DELETED:")));
        } finally { runtime.close(); }
    }

    @Test void keepsRecentRunningQuarantinedRecoveringAndInUseJobs() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        ServerRuntime runtime = runtime("protected", quota(24), clock);
        try {
            String recent = "J0000000000000011";
            String running = "J0000000000000012";
            String quarantined = "J0000000000000013";
            String recovering = "J0000000000000014";
            String active = "J0000000000000015";
            String locked = "J0000000000000016";
            seed(runtime, recent, "PASS", clock.instant(), false, mib(2.2));
            seed(runtime, running, "RUNNING", Instant.parse("2026-01-01T00:00:00Z"), false, mib(2.2));
            seed(runtime, quarantined, "PASS", Instant.parse("2026-01-01T00:00:00Z"), false, mib(2.2));
            runtime.store.quarantineWorker(quarantined);
            seed(runtime, recovering, "PASS", Instant.parse("2026-01-01T00:00:00Z"), true, mib(2.2));
            seed(runtime, active, "PASS", Instant.parse("2026-01-01T00:00:00Z"), false, mib(2.2));
            Job resident = new Job(active, "run", "p", "ci", new att.server.api.WorkerRequest(), new JobEvents(jobDir(runtime, active).resolve("events.jsonl"), 100));
            runtime.jobs.put(active, resident);
            seed(runtime, locked, "PASS", Instant.parse("2026-01-01T00:00:00Z"), false, mib(2.2));
            var field = ServerRuntime.class.getDeclaredField("artifactLifecycle");
            field.setAccessible(true);
            var lock = (java.util.concurrent.locks.ReentrantReadWriteLock) field.get(runtime);
            lock.readLock().lock();
            try { runtime.cleanupJobStorage(); }
            finally { lock.readLock().unlock(); }
            for (String id : new String[]{recent, running, quarantined, recovering, active, locked}) {
                assertNotNull(runtime.store.get(id), id);
                assertTrue(Files.exists(jobDir(runtime, id)), id);
            }
            assertEquals("deferred", storage(runtime).get("state"));
            runtime.jobs.remove(active);
            runtime.cleanupJobStorage();
            assertNull(runtime.store.get(active));
            assertNotNull(runtime.store.get(recent));
            assertEquals("quota_unmet", storage(runtime).get("state"));
            assertTrue(runtime.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
        } finally { runtime.close(); }
    }

    @Test void deletesTheWholeJobAndHidesItBeforeTheFilesAreGone() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        ServerRuntime runtime = runtime("whole", quota(0), clock);
        try {
            String id = "J0000000000000021";
            Path directory = seed(runtime, id, "FAIL", Instant.parse("2026-01-01T00:00:00Z"), false, mib(2.2));
            Files.writeString(directory.resolve("artifacts.zip"), "this is not a zip");
            Files.writeString(directory.resolve("output").resolve("report.txt"), "keep-until-delete");
            assertTrue(runtime.store.markDeleting(id, clock.instant()));
            assertNull(runtime.store.get(id));
            assertTrue(runtime.store.list(100).stream().noneMatch(row -> id.equals(row.get("jobId"))));
            assertTrue(Files.exists(directory));
            assertTrue(runtime.reconcileDeletingJobs());
            assertFalse(Files.exists(directory));
            assertFalse(runtime.store.jobRowExists(id));
        } finally { runtime.close(); }
    }

    @Test void doesNotFollowSymlinksOrDeleteWhenMeasurementIsIncomplete() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        ServerRuntime runtime = runtime("unsafe", quota(0), clock);
        try {
            Path outside = Files.createDirectory(temp.resolve("outside-target"));
            Files.writeString(outside.resolve("secret.txt"), "do-not-delete");
            String linkId = "J0000000000000031";
            Path link = jobDir(runtime, linkId);
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, outside);
            seedRow(runtime, linkId, "PASS", Instant.parse("2026-01-01T00:00:00Z"), false);
            String real = "J0000000000000032";
            seed(runtime, real, "PASS", Instant.parse("2026-01-02T00:00:00Z"), false, mib(3.1));
            runtime.cleanupJobStorage();
            assertTrue(Files.exists(outside.resolve("secret.txt")));
            assertTrue(Files.isSymbolicLink(link));
            assertNull(runtime.store.get(real));

            String deep = "J0000000000000033";
            Path nested = seed(runtime, deep, "PASS", Instant.parse("2026-01-01T00:00:00Z"), false, mib(2.2));
            Path cursor = nested;
            for (int i = 0; i < 40; i++) cursor = Files.createDirectory(cursor.resolve("d" + i));
            runtime.cleanupJobStorage();
            assertEquals("measurement_failed", storage(runtime).get("state"));
            assertNotNull(runtime.store.get(deep));
            assertTrue(Files.exists(nested.resolve("artifacts.zip")));
            assertNull(storage(runtime).get("totalBytes"));
        } finally { runtime.close(); }
    }

    @Test void projectsPublishedConsoleLinesWithoutReplayingLoadIterations() throws Exception {
        ServerRuntime runtime = runtime("console", null, Clock.systemUTC());
        try {
            ObjectNode projected = (ObjectNode) runtime.publicEventData(Map.of(
                    "executionEventType", "LOG", "message", "\u001B[31m/var/lib/att/secret.txt password=super-secret"),
                    runtime.config.dataDir.resolve("missing-output"), runtime.config.dataDir.resolve("missing-package"));
            String console = projected.path("console").asText();
            assertFalse(console.contains("super-secret"));
            assertFalse(console.contains("/var/lib/att"));
            assertFalse(console.contains("\u001B"));
            assertTrue(console.contains("[REDACTED_SECRET]"));
            ObjectNode iteration = (ObjectNode) runtime.publicEventData(Map.of("phase", "STEADY", "sequence", 9, "status", "PASS"),
                    runtime.config.dataDir.resolve("missing-output"), runtime.config.dataDir.resolve("missing-package"));
            assertFalse(iteration.has("console"));
            String id = "J0000000000000041";
            Path directory = Files.createDirectories(jobDir(runtime, id));
            Job job = new Job(id, "load", "p", "ci", new att.server.api.WorkerRequest(), new JobEvents(directory.resolve("events.jsonl"), 100));
            runtime.store.insert(job, "{}");
            runtime.finish(job, "PASS", 0, "[LOAD] COMPLETE runId=load-1 status=PASS");
            String journal = Files.readString(directory.resolve("events.jsonl"));
            assertTrue(journal.contains("[LOAD] status=PASS jobId=" + id));
            assertTrue(journal.contains("[LOAD] COMPLETE runId=load-1 status=PASS"));
        } finally { runtime.close(); }
    }

    private Map<String, Object> storage(ServerRuntime runtime) throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> storage = (Map<String, Object>) runtime.counts().get("jobStorage");
        return storage;
    }

    private Path seed(ServerRuntime runtime, String id, String status, Instant finished, boolean recovery, long bytes) throws Exception {
        Path directory = seedRow(runtime, id, status, finished, recovery);
        Files.createDirectories(directory.resolve("output"));
        writeBytes(directory.resolve("artifacts.zip"), bytes);
        return directory;
    }

    private Path seedRow(ServerRuntime runtime, String id, String status, Instant finished, boolean recovery) throws Exception {
        Path directory = Files.createDirectories(jobDir(runtime, id));
        Job job = new Job(id, "run", "p", "ci", new att.server.api.WorkerRequest(), new JobEvents(directory.resolve("events.jsonl"), 100));
        job.status = status;
        job.finishedAt = finished;
        runtime.store.insert(job, "{}");
        runtime.store.update(job);
        runtime.store.artifactRecoveryNeeded(id, recovery);
        return directory;
    }

    private static Path jobDir(ServerRuntime runtime, String id) { return runtime.config.dataDir.resolve("jobs").resolve(id); }

    private static long mib(double value) { return (long) (value * 1024 * 1024); }

    private static void writeBytes(Path file, long size) throws Exception {
        Files.createDirectories(file.getParent());
        byte[] chunk = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(file)) {
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(chunk.length, left);
                out.write(chunk, 0, n);
                left -= n;
            }
        }
    }

    private String quota(int minRetentionHours) {
        return "  jobStorage:\n    enabled: true\n    maxTotalSizeMb: 3\n    cleanupTargetSizeMb: 2\n    minRetentionHours: " + minRetentionHours + "\n    cleanupIntervalMinutes: 60\n";
    }

    private ServerRuntime runtime(String name, String storage, Clock clock) throws Exception {
        Path yaml = temp.resolve(name + "-server.yaml");
        Files.writeString(yaml, serverYaml(name, storage));
        return new ServerRuntime(ServerConfig.load(yaml), Files.createDirectory(temp.resolve(name + "-libs")).toString(), ProcessBuilder::start, clock);
    }

    private String serverYaml(String name, String storage) throws Exception {
        Path allowed = Files.createDirectories(temp.resolve(name + "-packages"));
        Path pkg = Files.createDirectories(allowed.resolve("p"));
        Path data = temp.resolve(name + "-data");
        return "server:\n  dataDir: " + data + "\n  javaExecutable: " + Path.of(System.getProperty("java.home"), "bin", "java") + "\n  jobRetentionDays: 30\n"
                + (storage == null ? "" : storage)
                + "workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - " + allowed + "\n  entries:\n    p: " + pkg + "\n";
    }
}
