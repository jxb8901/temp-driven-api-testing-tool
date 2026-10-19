package att.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

    @Test void hidingDeletingLoadJobsOmitsThemFromGenericAndLoadLists() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        ServerRuntime runtime = runtime("load-list", quota(0), clock);
        try {
            String deleting = "J0000000000000051";
            String visible = "J0000000000000052";
            seed(runtime, deleting, "load", "PASS", Instant.parse("2026-01-01T00:00:00Z"), false, mib(1));
            seed(runtime, visible, "load", "PASS", Instant.parse("2026-01-02T00:00:00Z"), false, mib(1));
            assertTrue(runtime.store.markDeleting(deleting, clock.instant()));
            assertNull(runtime.store.get(deleting));
            assertTrue(listed(runtime.store.list(100), deleting).isEmpty());
            assertTrue(listed(runtime.store.listLoad(100, 0), deleting).isEmpty());
            assertEquals(1, listed(runtime.store.list(100), visible).size());
            assertEquals(1, listed(runtime.store.listLoad(100, 0), visible).size());
        } finally { runtime.close(); }
    }

    @Test void reconcileAfterCrashAuditsEachDeletionOnce() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        Path yaml = temp.resolve("reconcile-audit-server.yaml");
        Files.writeString(yaml, serverYaml("reconcile-audit", quota(0), 30));
        String libs = Files.createDirectories(temp.resolve("reconcile-audit-libs")).toString();
        String pending = "J0000000000000061";
        String alreadyAudited = "J0000000000000062";
        long pendingBytes;
        long auditedBytes;
        ServerRuntime runtime = new ServerRuntime(ServerConfig.load(yaml), libs, ProcessBuilder::start, clock);
        try {
            Path pendingDirectory = seed(runtime, pending, "load", "PASS", Instant.parse("2026-01-01T00:00:00Z"), false, mib(2.2));
            Path auditedDirectory = seed(runtime, alreadyAudited, "PASS", Instant.parse("2026-01-02T00:00:00Z"), false, mib(1.1));
            pendingBytes = JobDirectoryUsage.directoryBytes(pendingDirectory);
            auditedBytes = JobDirectoryUsage.directoryBytes(auditedDirectory);
            assertTrue(pendingBytes > 0 && auditedBytes > 0);
            assertTrue(runtime.store.markDeleting(pending, clock.instant(), pendingBytes));
            assertTrue(runtime.store.markDeleting(alreadyAudited, clock.instant(), auditedBytes));
            runtime.store.auditStorageEviction(alreadyAudited, "DELETED:" + auditedBytes);
            assertTrue(Files.exists(pendingDirectory));
            assertTrue(Files.exists(auditedDirectory));
        } finally { runtime.close(); }

        ServerRuntime restarted = new ServerRuntime(ServerConfig.load(yaml), libs, ProcessBuilder::start, clock);
        try {
            assertFalse(Files.exists(runtime.config.dataDir.resolve("jobs").resolve(pending)));
            assertFalse(Files.exists(runtime.config.dataDir.resolve("jobs").resolve(alreadyAudited)));
            assertFalse(restarted.store.jobRowExists(pending));
            assertFalse(restarted.store.jobRowExists(alreadyAudited));
            List<String> outcomes = restarted.store.auditOutcomes("JOB_STORAGE_EVICT");
            assertEquals(List.of("DELETED:" + auditedBytes, "DELETED:" + pendingBytes), outcomes);
            assertTrue(restarted.reconcileDeletingJobs());
            restarted.store.auditStorageEviction(pending, "DELETED:" + pendingBytes);
            restarted.store.auditStorageEviction(alreadyAudited, "DELETED:0");
            assertEquals(outcomes, restarted.store.auditOutcomes("JOB_STORAGE_EVICT"));
        } finally { restarted.close(); }
    }

    @Test void ageExpiryDoesNotApplyTheSizeMinimum() throws Exception {
        Clock clock = Clock.systemUTC();
        ServerRuntime runtime = runtime("mixed-retention", quota(72), clock, 1);
        try {
            String recent = "J0000000000000071";
            String older = "J0000000000000072";
            Instant now = clock.instant();
            seed(runtime, recent, "PASS", now.minus(Duration.ofHours(25)), false, mib(1.6));
            seed(runtime, older, "PASS", now.minus(Duration.ofDays(10)), false, mib(1.6));
            runtime.cleanupJobStorage();
            assertNotNull(runtime.store.get(recent));
            assertTrue(Files.exists(jobDir(runtime, recent)));
            assertNull(runtime.store.get(older));
            assertFalse(Files.exists(jobDir(runtime, older)));
            assertEquals("ok", storage(runtime).get("state"));
            assertEquals(1, runtime.store.auditOutcomes("JOB_STORAGE_EVICT").stream().filter(outcome -> outcome.startsWith("DELETED:")).count());
            runtime.cleanupExpiredJobs();
            assertNull(runtime.store.get(recent));
            assertFalse(Files.exists(jobDir(runtime, recent)));
            assertEquals(1, runtime.store.auditOutcomes("JOB_STORAGE_EVICT").stream().filter(outcome -> outcome.startsWith("DELETED:")).count());
        } finally { runtime.close(); }
    }

    @Test void sweepBudgetDoesNotClaimQuotaUnmetWhileEligibleJobsRemain() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        ServerRuntime runtime = runtime("partial-sweep", quota(0), clock);
        try {
            runtime.storageCandidateBatchLimit = 1;
            runtime.store.storageCandidatePage = 2;
            Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
            String first = "J0000000000000001";
            String second = "J0000000000000002";
            String kept = "J0000000000000003";
            seed(runtime, first, "PASS", oldest.plusSeconds(1), false, mib(1.2));
            seed(runtime, second, "PASS", oldest.plusSeconds(2), false, mib(1.2));
            seed(runtime, kept, "PASS", oldest.plusSeconds(3), false, mib(3.1));
            runtime.cleanupJobStorage();
            assertNull(runtime.store.get(first));
            assertNull(runtime.store.get(second));
            assertFalse(Files.exists(jobDir(runtime, first)));
            assertFalse(Files.exists(jobDir(runtime, second)));
            assertNotNull(runtime.store.get(kept));
            assertTrue(Files.exists(jobDir(runtime, kept)));
            Map<String, Object> partial = storage(runtime);
            assertEquals("partial", partial.get("state"));
            assertEquals(Boolean.FALSE, partial.get("quotaUnmet"));
            assertTrue(runtime.store.auditOutcomes("JOB_STORAGE_EVICT").contains("SWEEP_PARTIAL"));
            assertFalse(runtime.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
            runtime.cleanupJobStorage();
            assertNull(runtime.store.get(kept));
            assertFalse(Files.exists(jobDir(runtime, kept)));
            assertEquals("ok", storage(runtime).get("state"));
            assertEquals(Boolean.FALSE, storage(runtime).get("quotaUnmet"));
            assertFalse(runtime.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
        } finally { runtime.close(); }
    }

    @Test void protectedPrefixCannotStarveAnEligibleJobBeyondTheSweepBudget() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        ServerRuntime stable = runtime("protected-prefix", quota(0), clock);
        try {
            stable.storageCandidateBatchLimit = 1;
            stable.store.storageCandidatePage = 2;
            Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
            String quarantined = "J0000000000000081";
            String recovering = "J0000000000000082";
            String eligible = "J0000000000000083";
            seed(stable, quarantined, "PASS", oldest.plusSeconds(1), false, mib(1.2));
            stable.store.quarantineWorker(quarantined);
            seed(stable, recovering, "PASS", oldest.plusSeconds(2), true, mib(1.2));
            seed(stable, eligible, "PASS", oldest.plusSeconds(3), false, mib(1.2));
            stable.cleanupJobStorage();
            assertNull(stable.store.get(eligible));
            assertFalse(Files.exists(jobDir(stable, eligible)));
            assertNotNull(stable.store.get(quarantined));
            assertNotNull(stable.store.get(recovering));
            assertTrue(Files.exists(jobDir(stable, quarantined)));
            assertTrue(Files.exists(jobDir(stable, recovering)));
            assertEquals("quota_unmet", storage(stable).get("state"));
            assertEquals(Boolean.TRUE, storage(stable).get("quotaUnmet"));
            assertTrue(stable.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
            assertFalse(storage(stable).containsKey("candidateAfterJobId"));
        } finally { stable.close(); }

        ServerRuntime resumed = runtime("scan-cursor", quota(0), clock);
        try {
            resumed.storageCandidateBatchLimit = 1;
            resumed.store.storageCandidatePage = 2;
            Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
            String firstLink = "J0000000000000091";
            String secondLink = "J0000000000000092";
            String eligible = "J0000000000000093";
            Path outside = Files.createDirectory(temp.resolve("cursor-outside"));
            Files.writeString(outside.resolve("secret.txt"), "do-not-delete");
            linkJob(resumed, firstLink, outside, oldest.plusSeconds(1));
            linkJob(resumed, secondLink, outside, oldest.plusSeconds(2));
            seed(resumed, eligible, "PASS", oldest.plusSeconds(3), false, mib(3.1));
            resumed.cleanupJobStorage();
            assertNotNull(resumed.store.get(eligible));
            assertTrue(Files.exists(jobDir(resumed, eligible)));
            assertEquals("partial", storage(resumed).get("state"));
            assertEquals(Boolean.FALSE, storage(resumed).get("quotaUnmet"));
            assertTrue(resumed.store.auditOutcomes("JOB_STORAGE_EVICT").contains("SWEEP_PARTIAL"));
            assertFalse(resumed.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
            String status = Files.readString(resumed.config.dataDir.resolve("job-storage-status.json"));
            assertTrue(status.contains("\"candidateAfterJobId\":\"" + secondLink + "\""));
            assertFalse(storage(resumed).containsKey("candidateAfterJobId"));
            resumed.cleanupJobStorage();
            assertNull(resumed.store.get(eligible));
            assertFalse(Files.exists(jobDir(resumed, eligible)));
            assertTrue(Files.isSymbolicLink(jobDir(resumed, firstLink)));
            assertTrue(Files.isSymbolicLink(jobDir(resumed, secondLink)));
            assertEquals("do-not-delete", Files.readString(outside.resolve("secret.txt")));
            assertFalse(resumed.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
        } finally { resumed.close(); }

        ServerRuntime wrapped = runtime("scan-wrap", quota(0), clock);
        try {
            String id = "J0000000000000094";
            Instant finished = Instant.parse("2026-01-01T00:00:04Z");
            seed(wrapped, id, "PASS", finished, false, mib(3.1));
            wrapped.storageScanAfterFinished = finished.toString();
            wrapped.storageScanAfterId = id;
            wrapped.cleanupJobStorage();
            assertNull(wrapped.store.get(id));
            assertFalse(Files.exists(jobDir(wrapped, id)));
            assertFalse(wrapped.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
        } finally { wrapped.close(); }
    }

    @Test void resumedSweepThatFinishesTheProtectedTailAtTheBudgetReportsQuotaUnmet() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        Path yaml = temp.resolve("protected-tail-server.yaml");
        Files.writeString(yaml, serverYaml("protected-tail", quota(0), 3650));
        String libs = Files.createDirectories(temp.resolve("protected-tail-libs")).toString();
        String weight = "J00000000000000A1";
        String[] links = {"J00000000000000A2", "J00000000000000A3", "J00000000000000A4", "J00000000000000A5"};
        Path outside = Files.createDirectory(temp.resolve("protected-tail-outside"));
        Files.writeString(outside.resolve("secret.txt"), "do-not-delete");
        ServerRuntime runtime = new ServerRuntime(ServerConfig.load(yaml), libs, ProcessBuilder::start, clock);
        try {
            runtime.storageCandidateBatchLimit = 1;
            runtime.store.storageCandidatePage = 2;
            Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
            seed(runtime, weight, "PASS", oldest, false, mib(3.1));
            runtime.store.quarantineWorker(weight);
            for (int i = 0; i < links.length; i++) linkJob(runtime, links[i], outside, oldest.plusSeconds(i + 1));
            runtime.cleanupJobStorage();
            assertEquals("partial", storage(runtime).get("state"));
            assertEquals(Boolean.FALSE, storage(runtime).get("quotaUnmet"));
            assertTrue(runtime.store.auditOutcomes("JOB_STORAGE_EVICT").contains("SWEEP_PARTIAL"));
            assertFalse(runtime.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
            String status = Files.readString(runtime.config.dataDir.resolve("job-storage-status.json"));
            assertTrue(status.contains("\"candidateAfterJobId\":\"" + links[1] + "\""));
            assertTrue(status.contains("\"candidateCycleCoveredHead\":true"));
            assertFalse(storage(runtime).containsKey("candidateCycleCoveredHead"));
            assertNotNull(runtime.store.get(weight));
            for (String id : links) assertTrue(Files.isSymbolicLink(jobDir(runtime, id)), id);
        } finally { runtime.close(); }

        ServerRuntime.skipInitialStorageSweep = true;
        try {
            ServerRuntime restarted = new ServerRuntime(ServerConfig.load(yaml), libs, ProcessBuilder::start, clock);
            try {
                restarted.storageCandidateBatchLimit = 1;
                restarted.store.storageCandidatePage = 2;
                restarted.cleanupJobStorage();
                assertEquals("quota_unmet", storage(restarted).get("state"));
                assertEquals(Boolean.TRUE, storage(restarted).get("quotaUnmet"));
                assertTrue(restarted.store.auditOutcomes("JOB_STORAGE_EVICT").contains("QUOTA_UNMET"));
                String status = Files.readString(restarted.config.dataDir.resolve("job-storage-status.json"));
                assertFalse(status.contains("candidateAfterJobId"));
                assertFalse(status.contains("candidateCycleCoveredHead"));
                assertNotNull(restarted.store.get(weight));
                assertTrue(Files.exists(jobDir(restarted, weight)));
                for (String id : links) assertTrue(Files.isSymbolicLink(jobDir(restarted, id)), id);
                assertEquals("do-not-delete", Files.readString(outside.resolve("secret.txt")));
            } finally { restarted.close(); }
        } finally { ServerRuntime.skipInitialStorageSweep = false; }
    }

    private void linkJob(ServerRuntime runtime, String id, Path outside, Instant finished) throws Exception {
        Path link = jobDir(runtime, id);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        seedRow(runtime, id, "PASS", finished, false);
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

    private static List<Map<String, Object>> listed(List<Map<String, Object>> rows, String id) {
        return rows.stream().filter(row -> id.equals(row.get("jobId"))).toList();
    }

    private Path seed(ServerRuntime runtime, String id, String status, Instant finished, boolean recovery, long bytes) throws Exception {
        return seed(runtime, id, "run", status, finished, recovery, bytes);
    }

    private Path seed(ServerRuntime runtime, String id, String command, String status, Instant finished, boolean recovery, long bytes) throws Exception {
        Path directory = seedRow(runtime, id, command, status, finished, recovery);
        Files.createDirectories(directory.resolve("output"));
        writeBytes(directory.resolve("artifacts.zip"), bytes);
        return directory;
    }

    private Path seedRow(ServerRuntime runtime, String id, String status, Instant finished, boolean recovery) throws Exception {
        return seedRow(runtime, id, "run", status, finished, recovery);
    }

    private Path seedRow(ServerRuntime runtime, String id, String command, String status, Instant finished, boolean recovery) throws Exception {
        Path directory = Files.createDirectories(jobDir(runtime, id));
        Job job = new Job(id, command, "p", "ci", new att.server.api.WorkerRequest(), new JobEvents(directory.resolve("events.jsonl"), 100));
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
        return runtime(name, storage, clock, 30);
    }

    private ServerRuntime runtime(String name, String storage, Clock clock, int retentionDays) throws Exception {
        Path yaml = temp.resolve(name + "-server.yaml");
        Files.writeString(yaml, serverYaml(name, storage, retentionDays));
        return new ServerRuntime(ServerConfig.load(yaml), Files.createDirectory(temp.resolve(name + "-libs")).toString(), ProcessBuilder::start, clock);
    }

    private String serverYaml(String name, String storage) throws Exception {
        return serverYaml(name, storage, 30);
    }

    private String serverYaml(String name, String storage, int retentionDays) throws Exception {
        Path allowed = Files.createDirectories(temp.resolve(name + "-packages"));
        Path pkg = Files.createDirectories(allowed.resolve("p"));
        Path data = temp.resolve(name + "-data");
        return "server:\n  dataDir: " + data + "\n  javaExecutable: " + Path.of(System.getProperty("java.home"), "bin", "java") + "\n  jobRetentionDays: " + retentionDays + "\n"
                + (storage == null ? "" : storage)
                + "workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - " + allowed + "\n  entries:\n    p: " + pkg + "\n";
    }
}
