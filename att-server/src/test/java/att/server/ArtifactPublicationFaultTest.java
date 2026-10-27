package att.server;

import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings={"unverified", "ledger-failure", "quarantine-store-failure", "load"})
    void incompleteProofReleasesCapacityOnlyAfterDurableQuarantine(String failure) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(), "Uses a POSIX Worker protocol fixture");
        Path gate=temp.resolve("finish-worker");
        String script="#!/bin/sh\nread request\nwhile [ ! -f '"+gate+"' ]; do sleep 0.02; done\n"
                +"printf '%s\\n' '{\"type\":\"RESULT\",\"status\":\"PASS\",\"exitCode\":0}'\n";
        String command="load".equals(failure)?"load":"run";
        ServerConfig config;
        String first;
        try(ServerRuntime runtime=runtime(ArtifactArchive::build,script)) {
            config=runtime.config;
            var request=ServerRuntime.JSON.createObjectNode().put("packageId","p");
            first=String.valueOf(runtime.submit(command,request,"user-a").get("jobId"));
            Job job=runtime.jobs.get(first);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(job.processFence==null&&System.nanoTime()<deadline)Thread.sleep(10);
            assertNotNull(job.processFence);
            Files.writeString(runtime.outputDirectoryForJob(first).resolve("case.log"),"password=private-canary");
            if(!"ledger-failure".equals(failure))job.processFence.unverified();
            if("ledger-failure".equals(failure))sql(config,"ALTER TABLE job_worker_processes RENAME TO unavailable_worker_processes");
            if("quarantine-store-failure".equals(failure))sql(config,"ALTER TABLE jobs RENAME COLUMN worker_quarantined TO unavailable_quarantine");
            Files.writeString(gate,"");
            if("quarantine-store-failure".equals(failure)) {
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while((!job.terminal()||job.process.isAlive())&&System.nanoTime()<deadline)Thread.sleep(10);
                assertTrue(job.terminal());assertFalse(job.process.isAlive());
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                int attempts=0;
                while(attempts==0&&System.nanoTime()<deadline){attempts=runtime.reconcileWorkerReleases();if(attempts==0)Thread.sleep(10);}
                assertTrue(attempts>0,"Exercise an actual deferred quarantine write while the store is unavailable");
                assertThrows(ServerRuntime.QueueFullException.class,()->runtime.submit(command,request,"user-b"),
                        "A failed quarantine write cannot release capacity or erase the active fence");
                assertTrue(runtime.jobs.containsKey(first));
                sql(config,"ALTER TABLE jobs RENAME COLUMN unavailable_quarantine TO worker_quarantined");
            }
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(runtime.jobs.containsKey(first)&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(runtime.jobs.containsKey(first),"Known-process exit must eventually reclaim the running permit");
            assertTrue(runtime.store.workerQuarantined(first));
            awaitState(runtime,first,"FAILED");
            assertEquals("worker_unverified",runtime.artifactIndex(first).get("reason"));
            assertFalse(Files.exists(runtime.artifactArchivePath(first)));
            assertTrue(Files.isDirectory(runtime.outputDirectoryForJob(first)));
            if("ledger-failure".equals(failure))sql(config,"ALTER TABLE unavailable_worker_processes RENAME TO job_worker_processes");
            String second=String.valueOf(runtime.submit(command,request,"user-b").get("jobId"));
            awaitReady(runtime,second);
            job.finishedAt=Instant.now().minus(java.time.Duration.ofDays(config.jobRetentionDays+1L));
            runtime.store.update(job);runtime.cleanupExpiredJobs();
            assertNotNull(runtime.store.get(first));
        }
        try(ServerRuntime restarted=new ServerRuntime(config,temp.resolve("libs").toString())) {
            assertTrue(restarted.store.workerQuarantined(first));
            assertTrue(restarted.store.workerMayBeAlive(first));
            assertThrows(IllegalArgumentException.class,()->restarted.retryArtifactArchive(first));
            assertEquals(false,restarted.artifactIndex(first).get("retryable"));
            assertEquals("worker_unverified",restarted.artifactIndex(first).get("reason"));
            assertThrows(ServerRuntime.ArchiveUnavailableException.class,()->restarted.openArtifactArchive(first,true));
            restarted.cleanupExpiredJobs();
            assertNotNull(restarted.store.get(first));
            assertTrue(Files.isDirectory(restarted.outputDirectoryForJob(first)));
            assertFalse(Files.exists(restarted.artifactArchivePath(first)));
        }
    }

    private static void sql(ServerConfig config,String sql) throws Exception {
        String url="jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')
                +";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){statement.execute(sql);}
    }

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

    @Test void candidateLimitPublicationPersistsStructuredArchiveFailureDiagnostic() throws Exception {
        ServerRuntime.ArtifactPublisher capped=(output,archive,publisher)->ArtifactArchive.buildForTest(output,archive,publisher,2);
        try(ServerRuntime runtime=runtime(capped)) {
            String id="J0000000000000298";
            Job job=terminalJob(runtime,id);job.exitCode=0;runtime.store.update(job);
            Files.writeString(runtime.outputDirectoryForJob(id).resolve("second.log"),"bounded evidence");
            runtime.scheduleArtifactArchive(id);
            awaitState(runtime,id,"FAILED");

            Map<?,?> index=(Map<?,?>)runtime.artifactIndex(id);
            assertEquals("FAILED",index.get("archiveStatus"));
            Map<?,?> diagnostic=(Map<?,?>)index.get("diagnostic");
            assertEquals("ATT-ARCHIVE-LIMIT-EXCEEDED",diagnostic.get("code"));
            assertEquals("ARCHIVE_FAILED",diagnostic.get("outcome"));
            assertEquals("artifact.inventory.candidates",diagnostic.get("limitName"));
            assertEquals(2L,((Number)diagnostic.get("configuredLimit")).longValue());
            assertEquals(3L,((Number)diagnostic.get("observed")).longValue());
            assertEquals("PASS",runtime.jobRecord(id).get("status"));
            assertEquals(0,runtime.jobRecord(id).get("exitCode"));
            assertFalse(diagnostic.toString().contains(runtime.config.dataDir.toString()));
        }
    }

    @Test void archiveLimitFailureHasItsOwnStructuredStateAndPreservesExecutionOutcome() throws Exception {
        try (ServerRuntime runtime = runtime((output, archive, publisher) -> {
            throw new ArtifactArchive.LimitFailure("total cap", "artifact.archive.totalBytes",
                    ArtifactArchive.MAX_TOTAL_BYTES, ArtifactArchive.MAX_TOTAL_BYTES + 1L);
        })) {
            Job job=terminalJob(runtime,"J0000000000000001");
            job.exitCode=0;runtime.store.update(job);
            runtime.scheduleArtifactArchive(job.id);
            awaitState(runtime,job.id,"FAILED");
            Map<String,Object> execution=runtime.store.get(job.id);
            Map<String,Object> artifacts=runtime.artifactIndex(job.id);
            Map<?,?> diagnostic=(Map<?,?>)artifacts.get("diagnostic");
            assertEquals("PASS",execution.get("status"));
            assertEquals(0,((Number)execution.get("exitCode")).intValue());
            assertEquals("ARCHIVE_FAILED",diagnostic.get("outcome"));
            assertEquals("artifact.archive.totalBytes",diagnostic.get("limitName"));
            assertEquals(ArtifactArchive.MAX_TOTAL_BYTES,((Number)diagnostic.get("configuredLimit")).longValue());
            assertEquals(ArtifactArchive.MAX_TOTAL_BYTES+1L,((Number)diagnostic.get("observed")).longValue());
            assertFalse(diagnostic.toString().contains(runtime.config.dataDir.toString()));
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
                assertThrows(ServerRuntime.NotFoundException.class, () -> restarted.archiveEntry(id, "case.log"),
                        "unapproved producer secrets must be omitted instead of being redacted during ZIP creation");
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

    @Test void restartAndRetentionKeepPrivateOutputForAnObservedChildAfterItsParentExits() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(), "Uses a POSIX process tree fixture");
        Path pidFile = temp.resolve("orphan.pid");
        Process parent = new ProcessBuilder("sh", "-c", "sleep 30 >/dev/null 2>&1 & echo $! > \"$1\"; wait", "fixture", pidFile.toString()).start();
        ProcessHandle child = null;
        String id = "J0000000000000001";
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
            WorkerProcessFence fence = new WorkerProcessFence(parent);
            ServerConfig config;
            try (ServerRuntime runtime = runtime(ArtifactArchive::build)) {
                config = runtime.config;
                Job job = terminalJob(runtime, id);
                job.workerPid = parent.pid();job.workerStartTime = parent.info().startInstant().orElseThrow();
                job.finishedAt = Instant.now().minus(java.time.Duration.ofDays(config.jobRetentionDays + 1L));
                runtime.store.update(job);
                runtime.store.rememberWorkerProcesses(id, fence.identities());
                parent.destroy();
                assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
                assertTrue(child.isAlive());
                assertTrue(runtime.store.workerMayBeAlive(id), "The persisted child must fence a dead parent");
            }
            try (ServerRuntime restarted = new ServerRuntime(config, temp.resolve("libs").toString())) {
                awaitState(restarted, id, "FAILED");
                assertEquals("worker_active", restarted.artifactIndex(id).get("reason"));
                assertNotNull(restarted.store.get(id), "Retention must preserve a Job with a known live descendant");
                assertTrue(Files.isDirectory(restarted.outputDirectoryForJob(id)));
                assertFalse(Files.exists(restarted.artifactArchivePath(id)));
                child.destroyForcibly();
                child.onExit().get(5, TimeUnit.SECONDS);
                awaitReady(restarted, id);
                assertThrows(ServerRuntime.NotFoundException.class, () -> restarted.archiveEntry(id, "case.log"),
                        "unapproved producer secrets must be omitted instead of being redacted during ZIP creation");
                long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (Files.exists(restarted.outputDirectoryForJob(id)) && System.nanoTime() < cleanupDeadline) Thread.sleep(10);
                assertFalse(Files.exists(restarted.outputDirectoryForJob(id)));
                // Deletion must also remove the operational process ledger (FK cascade).
                restarted.store.deleteJob(id);
                assertFalse(restarted.store.workerMayBeAlive(id));
            }
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            parent.destroyForcibly();parent.waitFor(5, TimeUnit.SECONDS);
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
            assertThrows(ServerRuntime.NotFoundException.class, () -> runtime.archiveEntry(job.id, "case.log"),
                    "raw producer output containing credentials must not become a public archive entry");
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
                assertThrows(ServerRuntime.NotFoundException.class, () -> runtime.archiveEntry(job.id, "case.log"));
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

    @Test void acceptedRetryRetentionAndAnOpenViewerCoordinateAcrossTheSameArchive() throws Exception {
        CountDownLatch first = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        CountDownLatch retry = new CountDownLatch(1), releaseRetry = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        try (ServerRuntime runtime = runtime((output, archive, publisher) -> {
            if (attempts.incrementAndGet() == 1) {
                first.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Initial gate expired");
                throw new java.io.IOException("Injected publication failure");
            }
            retry.countDown();
            if (!releaseRetry.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Retry gate expired");
            return ArtifactArchive.build(output, archive, publisher);
        })) {
            try {
                Job job = terminalJob(runtime, "J0000000000000001");
                runtime.scheduleArtifactArchive(job.id);
                assertTrue(first.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 10; i++) runtime.retryArtifactArchive(job.id);
                job.finishedAt = Instant.now().minus(java.time.Duration.ofDays(runtime.config.jobRetentionDays + 1L));
                runtime.store.update(job);
                var stopRetention = new java.util.concurrent.atomic.AtomicBoolean();
                CountDownLatch swept = new CountDownLatch(1);
                var retention = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        while (!stopRetention.get()) {
                            runtime.cleanupExpiredJobs();swept.countDown();Thread.sleep(1);
                        }
                    }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                });
                try {
                    assertTrue(swept.await(5, TimeUnit.SECONDS));
                    releaseFirst.countDown();
                    assertTrue(retry.await(5, TimeUnit.SECONDS), "An accepted retry must survive the retention handoff");
                } finally {stopRetention.set(true);retention.get(5, TimeUnit.SECONDS);}
                assertNotNull(runtime.store.get(job.id));
                assertTrue(Files.isDirectory(runtime.outputDirectoryForJob(job.id)));
                releaseRetry.countDown();
                awaitState(runtime, job.id, "READY");
                byte[] archive = Files.readAllBytes(runtime.artifactArchivePath(job.id));
                try (var view = runtime.openArtifactArchive(job.id, true)) {
                    // Retention uses a nonblocking write-lock attempt: an active
                    // viewer defers this sweep rather than blocking its thread.
                    var expiry = java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {runtime.cleanupExpiredJobs();}
                        catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
                    });
                    expiry.get(5,TimeUnit.SECONDS);
                    assertNotNull(runtime.store.get(job.id), "An active archive reader must fence expiry");
                    assertTrue(new String(runtime.archiveEntry(job.id, "case.log"), java.nio.charset.StandardCharsets.UTF_8).contains("business=unchanged"));
                    assertArrayEquals(archive, Files.readAllBytes(runtime.artifactArchivePath(job.id)));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (runtime.store.get(job.id) != null && System.nanoTime() < deadline) { runtime.cleanupExpiredJobs();Thread.sleep(10); }
                assertNull(runtime.store.get(job.id));
                assertFalse(Files.exists(runtime.artifactArchivePath(job.id)));
                assertEquals(2, attempts.get());
            } finally { releaseFirst.countDown();releaseRetry.countDown(); }
        }
    }

    @Test void cancellationArchivesPartialOutputOnlyAfterTheSurvivingWriterIsStopped() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(), "Uses a POSIX Worker and detached-writer fixture");
        Path writer = Files.writeString(temp.resolve("writer.sh"), "#!/bin/sh\ntrap '' TERM\n"
                + "printf 'password=private-cancel-canary\\nbusiness=partial\\n' >> \"$1/case.log\"\n"
                + "touch \"$1/child.ready\"\n"
                + "while :; do printf 'business=partial\\n' >> \"$1/case.log\"; sleep 0.02; done\n");
        String launcher = "#!/bin/sh\nIFS= read -r request\n"
                + "output=$(printf '%s' \"$request\" | sed 's/.*\"outputDirectory\":\"\\([^\"]*\\)\".*/\\1/')\n"
                + "sh '" + writer + "' \"$output\" >/dev/null 2>&1 &\n"
                + "echo $! > \"$output/child.pid\"\nwait\n";
        var child = new java.util.concurrent.atomic.AtomicReference<ProcessHandle>();
        AtomicInteger publications = new AtomicInteger();
        try (ServerRuntime runtime = runtime((output, archive, publisher) -> {
            assertNotNull(child.get());
            assertFalse(child.get().isAlive(), "A surviving child must not write during publication");
            publications.incrementAndGet();
            return ArtifactArchive.build(output, archive, publisher);
        }, launcher)) {
            try {
                String id = String.valueOf(runtime.submit("run", ServerRuntime.JSON.createObjectNode().put("packageId", "p"), "user-a").get("jobId"));
                Path output = runtime.outputDirectoryForJob(id);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((!Files.exists(output.resolve("child.ready")) || !Files.exists(output.resolve("child.pid")))
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(output.resolve("child.ready")));
                child.set(ProcessHandle.of(Long.parseLong(Files.readString(output.resolve("child.pid")).trim())).orElseThrow());
                assertTrue(child.get().isAlive());
                assertEquals("CANCELLED", runtime.cancel(id, "user-a"));
                awaitReady(runtime, id);
                assertEquals("CANCELLED", runtime.jobRecord(id).get("status"));
                assertThrows(ServerRuntime.NotFoundException.class, () -> runtime.archiveEntry(id, "case.log"),
                        "cancellation must not publish incomplete unapproved Worker secrets");
                long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (Files.exists(output) && System.nanoTime() < cleanupDeadline) Thread.sleep(10);
                assertFalse(Files.exists(output));
                assertEquals(1, publications.get());
                assertFalse(child.get().isAlive());
            } finally { if (child.get() != null && child.get().isAlive()) child.get().destroyForcibly(); }
        }
    }

    @Test void queuedCancellationPublishesItsEmptyArchiveWithoutAReconciliationRetry() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(), "Uses a POSIX blocked Worker fixture");
        Path started = temp.resolve("active.started");
        String script = "#!/bin/sh\nread request\ntouch '" + started + "'\nexec sleep 60\n";
        try (ServerRuntime runtime = runtime(ArtifactArchive::build, script, 1)) {
            String active = String.valueOf(runtime.submit("run", ServerRuntime.JSON.createObjectNode().put("packageId", "p"), "user-a").get("jobId"));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(started) && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(started));
                String queued = String.valueOf(runtime.submit("run", ServerRuntime.JSON.createObjectNode().put("packageId", "p"), "user-b").get("jobId"));
                assertEquals("QUEUED", runtime.jobRecord(queued).get("status"));
                assertEquals("CANCELLED", runtime.cancel(queued, "user-b"));
                // Poll only: a manual schedule/retry here would hide a lost initial publication.
                awaitState(runtime, queued, "READY");
                assertEquals("CANCELLED", runtime.jobRecord(queued).get("status"));
                assertTrue(Files.isRegularFile(runtime.artifactArchivePath(queued)));
                ArtifactArchive.verify(runtime.artifactArchivePath(queued), true);
            } finally {runtime.cancel(active, "user-a");}
        }
    }

    private ServerRuntime runtime(ServerRuntime.ArtifactPublisher publisher) throws Exception {
        return runtime(publisher, "#!/bin/sh\nread request\nprintf '%s\\n' '{\"type\":\"RESULT\",\"status\":\"PASS\",\"exitCode\":0}'\n");
    }

    private ServerRuntime runtime(ServerRuntime.ArtifactPublisher publisher, String script) throws Exception {
        return runtime(publisher, script, 0);
    }

    private ServerRuntime runtime(ServerRuntime.ArtifactPublisher publisher, String script, int queuedLimit) throws Exception {
        Path packages = Files.createDirectories(temp.resolve("packages"));
        Path pkg = Files.createDirectories(packages.resolve("p"));
        Path launcher = temp.resolve("test-worker");
        Files.writeString(launcher, script);
        launcher.toFile().setExecutable(true);
        Path yaml = Files.writeString(temp.resolve("server.yaml"),
                "server:\n  dataDir: '" + temp.resolve("data") + "'\n  javaExecutable: '" + launcher + "'\n"
                        + "workers:\n  maxConcurrent: 1\n  maxConcurrentLoad: 1\n  queuedLimit: " + queuedLimit + "\n  gracefulStopMs: 100\npackages:\n  allowedRoots:\n    - '" + packages + "'\n  entries:\n    p: '" + pkg + "'\n");
        return new ServerRuntime(ServerConfig.load(yaml), Files.createDirectories(temp.resolve("libs")).toString(),
                ServerRuntimeTestSupport.resolvingConfiguration(ProcessBuilder::start), Clock.systemUTC(), publisher);
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
