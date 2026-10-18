package att.server;

import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactPublicationFaultTest {
    @TempDir Path temp;

    @Test void terminalResultDoesNotBypassTheActiveExecutionFence() throws Exception {
        AtomicInteger publications = new AtomicInteger();
        try (ServerRuntime runtime = runtime((output, archive, publisher) -> {
            publications.incrementAndGet();
            return ArtifactArchive.build(output, archive, publisher);
        })) {
            Job job = terminalJob(runtime, "J0000000000000001");
            runtime.jobs.put(job.id, job);
            runtime.scheduleArtifactArchive(job.id);
            Thread.sleep(200);
            assertEquals(0, publications.get(), "A terminal result is not execution cleanup completion");
            assertFalse(Files.exists(runtime.artifactArchivePath(job.id)));
            assertTrue(Files.exists(runtime.outputDirectoryForJob(job.id)));
            runtime.jobs.remove(job.id, job);
            awaitReady(runtime, job.id);
            assertEquals(1, publications.get());
        }
    }

    @Test void restartDoesNotPublishTerminalOutputWhileThePersistedWorkerIsAlive() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(), "Uses a POSIX process fixture");
        Process writer = new ProcessBuilder("sh", "-c", "exec sleep 30").start();
        ServerConfig config;
        String id = "J0000000000000001";
        try {
            try (ServerRuntime runtime = runtime(ArtifactArchive::build)) {
                config = runtime.config;
                Job job = terminalJob(runtime, id);
                job.workerPid = writer.pid();
                job.workerStartTime = writer.info().startInstant().orElseThrow();
                job.finishedAt = Instant.now().minus(java.time.Duration.ofDays(runtime.config.jobRetentionDays + 1L));
                runtime.store.update(job);
                assertTrue(runtime.store.workerMayBeAlive(id));
                Instant actualStart = job.workerStartTime;
                job.workerStartTime = Instant.EPOCH;
                runtime.store.update(job);
                assertFalse(runtime.store.workerMayBeAlive(id), "A reused PID is not the original Worker identity");
                job.workerStartTime = null;
                runtime.store.update(job);
                assertTrue(runtime.store.workerMayBeAlive(id), "Missing identity must fail closed while the PID is alive");
                job.workerStartTime = actualStart;
                runtime.store.update(job);
            }
            try (ServerRuntime restarted = new ServerRuntime(config, temp.resolve("libs").toString())) {
                awaitState(restarted, id, "FAILED");
                assertEquals("worker_active", restarted.artifactIndex(id).get("reason"));
                assertNotNull(restarted.store.get(id), "Startup retention must not delete an expired Job with an active writer");
                assertFalse(Files.exists(restarted.artifactArchivePath(id)));
                assertTrue(Files.readString(restarted.outputDirectoryForJob(id).resolve("case.log")).contains("private-canary"));
                writer.destroyForcibly();
                assertTrue(writer.waitFor(5, TimeUnit.SECONDS));
                awaitReady(restarted, id);
                assertFalse(new String(restarted.archiveEntry(id, "case.log"), java.nio.charset.StandardCharsets.UTF_8).contains("private-canary"));
            }
        } finally {
            writer.destroyForcibly();
            writer.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void slowPublicationDoesNotHoldExecutionAdmissionOrDelayTerminalResults() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(), "Uses a POSIX Worker protocol fixture");
        CountDownLatch archiving = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        try (ServerRuntime runtime = runtime((output, archive, publisher) -> {
            if (publications.incrementAndGet() == 1) {
                archiving.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Test publication gate expired");
            }
            return ArtifactArchive.build(output, archive, publisher);
        })) {
            try {
                var request = ServerRuntime.JSON.createObjectNode().put("packageId", "p");
                String first = String.valueOf(runtime.submit("run", request, "user-a").get("jobId"));
                assertTrue(archiving.await(5, TimeUnit.SECONDS));
                assertEquals("PASS", runtime.jobRecord(first).get("status"));
                assertEquals("ARCHIVING", runtime.artifactIndex(first).get("archiveStatus"));
                assertTrue(Files.isDirectory(runtime.outputDirectoryForJob(first)));
                // maxConcurrent=1 and queuedLimit=0: this acceptance proves that
                // the first execution released its admission lease before ZIP work.
                String second = null;
                long admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do {
                    try { second = String.valueOf(runtime.submit("run", request, "user-b").get("jobId")); }
                    catch (ServerRuntime.QueueFullException finishing) { Thread.sleep(10); }
                } while (second == null && System.nanoTime() < admissionDeadline);
                assertNotNull(second, "Admission must become available while publication is still blocked");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!runtime.terminal(second) && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals("PASS", runtime.jobRecord(second).get("status"));
                assertEquals("ARCHIVING", runtime.artifactIndex(first).get("archiveStatus"));
                release.countDown();
                awaitReady(runtime, first);
                awaitReady(runtime, second);
            } finally { release.countDown(); }
        }
    }

    @Test void simulatedDiskFailurePreservesPrivateSourceAndRetryPublishesOnlyApprovedBytes() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (ServerRuntime runtime = runtime((output, archive, publisher) -> {
            if (attempts.incrementAndGet() == 1) throw new java.nio.file.FileSystemException(output.toString(), null, "disk-full-private-canary");
            return ArtifactArchive.build(output, archive, publisher);
        })) {
            Job job = terminalJob(runtime, "J0000000000000001");
            runtime.scheduleArtifactArchive(job.id);
            awaitState(runtime, job.id, "FAILED");
            Map<String, Object> state = runtime.artifactIndex(job.id);
            assertEquals("publication_failed", state.get("reason"));
            assertEquals(true, state.get("retryable"));
            assertFalse(state.toString().contains("private-canary"));
            assertFalse(state.toString().contains(temp.toString()));
            assertEquals("PASS", runtime.jobRecord(job.id).get("status"));
            assertTrue(Files.readString(runtime.outputDirectoryForJob(job.id).resolve("case.log")).contains("private-canary"));
            assertThrows(ServerRuntime.ArchiveUnavailableException.class, () -> runtime.archiveEntry(job.id, "case.log"));
            awaitReady(runtime, job.id);
            assertEquals(2, attempts.get());
            assertEquals("password=[REDACTED_SECRET]\nbusiness=unchanged\n",
                    new String(runtime.archiveEntry(job.id, "case.log"), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void retriesAcceptedDuringPublicationAreCoalescedAndNotLostOnFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        try (ServerRuntime runtime = runtime((output, archive, publisher) -> {
            if (attempts.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Publication gate expired");
                throw new java.io.IOException("Injected publication failure");
            }
            return ArtifactArchive.build(output, archive, publisher);
        })) {
            try {
                Job job = terminalJob(runtime, "J0000000000000001");
                runtime.scheduleArtifactArchive(job.id);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 10; i++) runtime.retryArtifactArchive(job.id);
                release.countDown();
                awaitState(runtime, job.id, "READY");
                assertEquals(2, attempts.get(), "Concurrent retry requests must become one follow-up publication");
            } finally { release.countDown(); }
        }
    }

    @Test void failedLooseOutputDeletionIsRetriedWithoutRebuildingTheVerifiedZip() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileAttributeView(temp,
                java.nio.file.attribute.PosixFileAttributeView.class) != null, "Requires POSIX directory permissions");
        AtomicInteger publications = new AtomicInteger();
        try (ServerRuntime runtime = runtime((output, archive, publisher) -> {
            publications.incrementAndGet();
            return ArtifactArchive.build(output, archive, publisher);
        })) {
            Job job = terminalJob(runtime, "J0000000000000001");
            Path output = runtime.outputDirectoryForJob(job.id);
            var original = Files.getPosixFilePermissions(output);
            CountDownLatch cleanupFailed = new CountDownLatch(1);
            var logger = java.util.logging.Logger.getLogger(ServerRuntime.class.getName());
            var handler = new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord record) {
                    if (("Artifact cleanup will be retried for Job " + job.id).equals(record.getMessage())) cleanupFailed.countDown();
                }
                @Override public void flush() { }
                @Override public void close() { }
            };
            logger.addHandler(handler);
            try {
                Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
                org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(output), "Privileged users bypass this failure fixture");
                runtime.scheduleArtifactArchive(job.id);
                assertTrue(cleanupFailed.await(10, TimeUnit.SECONDS), "Observe the actual failed deletion, not only READY before cleanup");
                assertEquals("READY", runtime.artifactIndex(job.id).get("archiveStatus"));
                byte[] archive = Files.readAllBytes(runtime.artifactArchivePath(job.id));
                assertTrue(Files.readString(output.resolve("case.log")).contains("private-canary"));
                assertFalse(new String(runtime.archiveEntry(job.id, "case.log"), java.nio.charset.StandardCharsets.UTF_8).contains("private-canary"));
                Files.setPosixFilePermissions(output, original);
                runtime.retryArtifactArchive(job.id);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (Files.exists(output) && System.nanoTime() < deadline) Thread.sleep(10);
                assertFalse(Files.exists(output));
                assertEquals(1, publications.get(), "Cleanup recovery must reuse the existing verified archive");
                assertArrayEquals(archive, Files.readAllBytes(runtime.artifactArchivePath(job.id)));
            } finally {
                logger.removeHandler(handler);
                if (Files.exists(output)) Files.setPosixFilePermissions(output, original);
            }
        }
    }

    private ServerRuntime runtime(ServerRuntime.ArtifactPublisher publisher) throws Exception {
        Path packages = Files.createDirectories(temp.resolve("packages"));
        Path pkg = Files.createDirectories(packages.resolve("p"));
        Path launcher = temp.resolve("test-worker");
        Files.writeString(launcher, "#!/bin/sh\nread request\nprintf '%s\\n' '{\"type\":\"RESULT\",\"status\":\"PASS\",\"exitCode\":0}'\n");
        launcher.toFile().setExecutable(true);
        Path yaml = Files.writeString(temp.resolve("server.yaml"),
                "server:\n  dataDir: '" + temp.resolve("data") + "'\n  javaExecutable: '" + launcher + "'\n"
                        + "workers:\n  maxConcurrent: 1\n  queuedLimit: 0\npackages:\n  allowedRoots:\n    - '" + packages + "'\n  entries:\n    p: '" + pkg + "'\n");
        return new ServerRuntime(ServerConfig.load(yaml), Files.createDirectories(temp.resolve("libs")).toString(),
                ProcessBuilder::start, Clock.systemUTC(), publisher);
    }

    private Job terminalJob(ServerRuntime runtime, String id) throws Exception {
        Path output = Files.createDirectories(runtime.outputDirectoryForJob(id));
        Files.writeString(output.resolve("case.log"), "password=private-canary\nbusiness=unchanged\n");
        Job job = new Job(id, "run", "p", "user-a", new WorkerRequest(), new JobEvents(output.getParent().resolve("events.jsonl"), 100));
        runtime.store.insert(job, "{}");
        job.status = "PASS"; job.finishedAt = Instant.now(); runtime.store.update(job);
        return job;
    }

    private static void awaitReady(ServerRuntime runtime, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if ("READY".equals(runtime.artifactIndex(id).get("archiveStatus"))) return;
            runtime.scheduleArtifactArchive(id);
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Archive did not become READY: " + runtime.artifactIndex(id));
    }

    private static void awaitState(ServerRuntime runtime, String id, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (state.equals(runtime.artifactIndex(id).get("archiveStatus"))) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Archive did not become " + state + ": " + runtime.artifactIndex(id));
    }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
}
