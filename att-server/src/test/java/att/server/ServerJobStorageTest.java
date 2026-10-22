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

    @Test void loadQueryFiltersBeforePaginationAndHonorsPrincipalAndPins() throws Exception {
        try (ServerRuntime runtime=runtime("load-query",null,Clock.systemUTC())) {
            Instant finished=Instant.parse("2026-01-01T00:00:00Z");
            for(int index=1;index<=6;index++) {
                String id=String.format("J%016d",index);
                seedRow(runtime,id,index%2==0?"load":"run","PASS",finished,false);
            }
            assertEquals(List.of("J0000000000000006","J0000000000000004"),runtime.store.listLoad(2,0,"CI",false).stream().map(row->row.get("jobId")).toList());
            assertEquals("J0000000000000002",runtime.store.listLoad(2,2,"ci",false).get(0).get("jobId"));
            assertTrue(runtime.store.listLoad(10,0,"missing",false).isEmpty());
            runtime.pinJob("J0000000000000002",true,"ci");
            assertEquals("J0000000000000002",runtime.store.listLoad(1,0,null,true).get(0).get("jobId"));
            assertTrue(runtime.store.listLoad(1,1,null,true).isEmpty());
        }
    }

    @Test void pinnedJobsSurviveAgeQuotaAndRestartAndRequireUnpinBeforeManualDelete() throws Exception {
        ServerRuntime runtime=runtime("pins",quota(0),Clock.systemUTC());
        String run="J0000000000000901", load="J0000000000000902", other="J0000000000000903";
        Instant old=Instant.parse("2020-01-01T00:00:00Z");
        ServerConfig config=runtime.config;
        try {
            seed(runtime,run,"run","PASS",old,false,mib(2));
            seed(runtime,load,"load","ERROR",old,false,mib(2));
            seed(runtime,other,"PASS",old,false,mib(1));
            runtime.pinJob(run,true,"operator");runtime.pinJob(load,true,"operator");
            assertTrue(Boolean.TRUE.equals(runtime.store.get(run).get("pinned")));
            assertEquals("operator",runtime.store.get(run).get("pinnedBy"));
            assertEquals(1,runtime.store.list(1,null,true).size());
            assertTrue(runtime.store.list(100,null,true).stream().allMatch(row->Boolean.TRUE.equals(row.get("pinned"))));
            runtime.cleanupExpiredJobs();
            assertNull(runtime.store.get(other));assertNotNull(runtime.store.get(run));
            runtime.cleanupJobStorage();assertEquals(true,storage(runtime).get("quotaUnmet"));
            assertTrue(Files.exists(jobDir(runtime,run).resolve("artifacts.zip")));
            var outcomes=runtime.deleteJobs(List.of(run,load),"operator");
            assertTrue(outcomes.stream().allMatch(row->"PINNED_UNPIN_FIRST".equals(row.get("outcome"))));
        } finally {runtime.close();}
        try(ServerRuntime restarted=new ServerRuntime(config,Files.createDirectory(temp.resolve("pins-restart-libs")).toString(),ProcessBuilder::start)) {
            assertEquals(true,restarted.store.get(run).get("pinned"));
            assertEquals(true,restarted.store.get(load).get("pinned"));
            assertTrue(Files.exists(jobDir(restarted,load).resolve("artifacts.zip")));
            restarted.pinJob(run,false,"second-operator");
            assertEquals("DELETED",restarted.deleteJob(run,"second-operator").get("outcome"));
            assertFalse(Files.exists(jobDir(restarted,run)));assertNull(restarted.store.get(run));
            assertEquals("ALREADY_UNAVAILABLE",restarted.deleteJob(run,"second-operator").get("outcome"));
            assertEquals(true,restarted.store.get(load).get("pinned"));
            assertThrows(ServerRuntime.NotFoundException.class,()->restarted.pinJob(run,true,"operator"));
            assertThrows(IllegalArgumentException.class,()->restarted.deleteJobs(List.of("../outside"),"operator"));
            assertEquals(List.of("false->true","false->true","true->false"),restarted.store.auditOutcomes("JOB_PIN"));
        }
    }

    @Test void everyWorkerRequestSerializationOverridesRestSuppliedLiveOutputPolicy() throws Exception {
        try(ServerRuntime runtime=runtime("inspection-policy",null,Clock.systemUTC())) {
            ObjectNode rest=ServerRuntime.JSON.createObjectNode();rest.put("packageId","p");rest.put("liveOutputMaxEntryChars",1);rest.put("liveOutputMaxEntryLines",1);
            var submit=ServerRuntime.JSON.treeToValue(rest,att.server.api.WorkerRequest.class);
            var runInput=ServerRuntime.JSON.treeToValue(rest,att.server.api.WorkerRequest.class);
            var inspection=runtime.inspectionRequest("p",runtime.config.packages.get("p"),null);
            inspection.liveOutputMaxEntryChars=1;inspection.liveOutputMaxEntryLines=1;
            for(var request:List.of(submit,runInput,inspection)) {
                var serialized=ServerRuntime.JSON.readValue(runtime.workerRequestBytes(request),att.server.api.WorkerRequest.class);
                assertEquals(runtime.config.liveOutput.maxEntryChars,serialized.liveOutputMaxEntryChars);
                assertEquals(runtime.config.liveOutput.maxEntryLines,serialized.liveOutputMaxEntryLines);
            }
        }
    }

    @Test void staleRetentionCandidateCannotDeleteNewPinAndBusyJobsStayIntact() throws Exception {
        try(ServerRuntime runtime=runtime("pin-fences",null,Clock.systemUTC())) {
            String id="J0000000000000904", busy="J0000000000000905";
            seed(runtime,id,"PASS",Instant.parse("2020-01-01T00:00:00Z"),false,1024);
            assertFalse(runtime.store.storageCandidates().isEmpty());
            runtime.pinJob(id,true,"operator");
            assertFalse(runtime.store.markDeleting(id,Instant.now()));
            seed(runtime,busy,"RUNNING",Instant.now(),false,1024);
            assertEquals("NOT_TERMINAL",runtime.deleteJob(busy,"operator").get("outcome"));
            assertTrue(Files.exists(jobDir(runtime,busy)));
            assertThrows(ServerRuntime.JobConflictException.class,()->runtime.pinJob(busy,true,"operator"));
        }
    }

    @Test void pinUsesTheJobRowFenceWhileAnotherJobsArchiveDownloadIsOpen() throws Exception {
        try(ServerRuntime runtime=runtime("pin-during-download",null,Clock.systemUTC())) {
            Instant finished=Instant.parse("2026-01-01T00:00:00Z");String downloading="J0000000000000906",pin="J0000000000000907";
            Path source=Files.createDirectories(jobDir(runtime,downloading).resolve("output"));Files.writeString(source.resolve("report.txt"),"download");
            seedRow(runtime,downloading,"PASS",finished,false);ArtifactArchive.build(source,runtime.artifactArchivePath(downloading),file->runtime.publicArtifact(downloading,file));
            Files.writeString(jobDir(runtime,downloading).resolve("archive-status.json"),"{\"state\":\"READY\",\"reason\":\"\",\"manifestVersion\":1}");
            seed(runtime,pin,"PASS",finished,false,1024);
            try(ServerRuntime.ArtifactRead open=runtime.openArtifactArchive(downloading,true)) {
                Map<String,Object> pinned=runtime.pinJob(pin,true,"release evidence","operator");
                assertEquals(true,pinned.get("pinned"));assertEquals("release evidence",pinned.get("pinReason"));
            }
        }
    }

    @Test void bulkPinReasonIsBoundedSanitizedAndReturnsPerJobOutcomes() throws Exception {
        try(ServerRuntime runtime=runtime("bulk-pin",null,Clock.systemUTC())) {
            Instant finished=Instant.parse("2026-01-01T00:00:00Z");String first="J0000000000000908",second="J0000000000000909",running="J000000000000090A";
            seed(runtime,first,"PASS",finished,false,128);seed(runtime,second,"load","FAIL",finished,false,128);seed(runtime,running,"RUNNING",finished,false,128);
            List<Map<String,Object>> outcomes=runtime.pinJobs(List.of(first,second,running),true," password=pin-secret ","operator");
            assertEquals(List.of("PINNED","PINNED","NOT_ELIGIBLE"),outcomes.stream().map(row->row.get("outcome")).toList());
            assertEquals("password=[REDACTED_SECRET]",runtime.store.get(first).get("pinReason"));
            assertEquals("password=[REDACTED_SECRET]",listed(runtime.store.list(100),first).get(0).get("pinReason"));
            assertEquals("password=[REDACTED_SECRET]",listed(runtime.store.listLoad(100,0),second).get(0).get("pinReason"));
            String activeId="J000000000000090B";Path activeDirectory=Files.createDirectories(jobDir(runtime,activeId));
            Job active=new Job(activeId,"run","p","ci",new att.server.api.WorkerRequest(),new JobEvents(activeDirectory.resolve("events.jsonl"),100));
            active.status="PASS";active.finishedAt=finished;runtime.store.insert(active,"{}");runtime.store.update(active);runtime.store.artifactRecoveryNeeded(activeId,false);runtime.jobs.put(activeId,active);
            assertEquals("active terminal",runtime.pinJob(activeId,true,"active terminal","operator").get("pinReason"));
            assertThrows(IllegalArgumentException.class,()->runtime.pinJob(first,true,"x".repeat(201),"operator"));
            assertThrows(IllegalArgumentException.class,()->runtime.pinJob(first,true,"bad\nreason","operator"));
            assertEquals(List.of("UNPINNED","UNPINNED"),runtime.pinJobs(List.of(first,second),false,null,"operator").stream().map(row->row.get("outcome")).toList());
            assertNull(runtime.store.get(first).get("pinReason"));assertNull(runtime.store.get(second).get("pinReason"));
        }
    }

    @Test void mixedManualDeleteReportsEveryTargetAndRecoveryFinishesDurableDeletion() throws Exception {
        try(ServerRuntime runtime=runtime("manual-bulk",null,Clock.systemUTC())) {
            Instant old=Instant.parse("2020-01-01T00:00:00Z");
            String pinned="J0000000000000911", eligible="J0000000000000912", active="J0000000000000913", pending="J0000000000000914";
            for(String id:List.of(pinned,eligible,pending))seed(runtime,id,"PASS",old,false,1024);
            seed(runtime,active,"RUNNING",old,false,1024);runtime.pinJob(pinned,true,"operator");
            assertTrue(runtime.store.markManualDeleting(pending,"operator"));
            assertFalse(runtime.store.setPinned(pending,true,"operator"));
            var results=runtime.deleteJobs(List.of(pinned,eligible,active,pending,eligible),"operator");
            assertEquals(List.of("PINNED_UNPIN_FIRST","DELETED","NOT_TERMINAL","DELETION_PENDING_RECOVERY"),results.stream().map(row->row.get("outcome")).toList());
            assertTrue(runtime.reconcileDeletingJobs());assertFalse(Files.exists(jobDir(runtime,pending)));assertFalse(runtime.store.jobRowExists(pending));
            assertNotNull(runtime.store.get(pinned));assertNotNull(runtime.store.get(active));
            assertTrue(runtime.store.auditOutcomes("JOB_DELETE").contains("DELETED_AFTER_RECOVERY"));
        }
    }

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

    @Test void hourlyAgeSweepReconcilesPendingManualDeletionWhenStorageQuotaIsDisabled() throws Exception {
        try(ServerRuntime runtime=runtime("disabled-reconcile",null,Clock.systemUTC())) {
            String id="J0000000000000920";Path directory=seed(runtime,id,"PASS",Instant.parse("2020-01-01T00:00:00Z"),false,1024);
            assertTrue(runtime.store.markManualDeleting(id,"operator"));assertTrue(Files.exists(directory));
            runtime.cleanupExpiredJobs();assertFalse(runtime.store.jobRowExists(id));assertFalse(Files.exists(directory));
            assertTrue(runtime.store.auditOutcomes("JOB_DELETE").contains("DELETED_AFTER_RECOVERY"));
        }
    }

    @Test void failedArchiveStillAgesOutWhilePinnedFailedArchiveIsProtected() throws Exception {
        ServerConfig config;
        try(ServerRuntime runtime=runtime("failed-retention",null,Clock.systemUTC(),1,(output,archive,publisher)->{throw new java.io.IOException("forced publication failure");})) {
            config=runtime.config;
            Instant old=Instant.parse("2020-01-01T00:00:00Z");String failed="J0000000000000921",pinned="J0000000000000922";
            for(String id:List.of(failed,pinned)) {
                Path output=Files.createDirectories(jobDir(runtime,id).resolve("output"));Files.writeString(output.resolve("case.log"),"password=pin-secret /private/secret");
                Job job=new Job(id,"run","p","test",new att.server.api.WorkerRequest(),new JobEvents(jobDir(runtime,id).resolve("events.jsonl"),100));
                job.status="ERROR";job.finishedAt=old;runtime.store.insert(job,"{}");runtime.store.update(job);runtime.scheduleArtifactArchive(id);
            }
            long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while((runtime.store.artifactRecoveryJobIds("",64).contains(failed)||runtime.store.artifactRecoveryJobIds("",64).contains(pinned))&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(runtime.store.artifactRecoveryJobIds("",64).contains(failed));assertFalse(runtime.store.artifactRecoveryJobIds("",64).contains(pinned));
            assertEquals("FAILED",runtime.artifactIndex(failed).get("archiveStatus"));assertEquals("FAILED",runtime.artifactIndex(pinned).get("archiveStatus"));
            runtime.pinJob(pinned,true,"retain failed evidence","operator");
        }
        try(ServerRuntime restarted=new ServerRuntime(config,Files.createDirectory(temp.resolve("failed-retention-restart-libs")).toString())) {
            restarted.cleanupExpiredJobs();
            assertNull(restarted.store.get("J0000000000000921"));assertNotNull(restarted.store.get("J0000000000000922"));assertTrue(Files.exists(jobDir(restarted,"J0000000000000922")));
        }
    }

    @Test void quarantinedHistoryDoesNotBlockBoundedAgeDeletion() throws Exception {
        try(ServerRuntime runtime=runtime("quarantine-page",null,Clock.systemUTC(),1)) {
            Instant old=Instant.parse("2020-01-01T00:00:00Z");String ordinary="J0000000000000923";
            seedQuarantinedRows(runtime,502,old);
            seed(runtime,ordinary,"PASS",old,false,1024);
            runtime.cleanupExpiredJobs();
            assertNull(runtime.store.get(ordinary));assertFalse(Files.exists(jobDir(runtime,ordinary)));
            assertTrue(runtime.store.jobRowExists("J0000000000000001"));
        }
    }

    @Test void unreconcilableDeletingJobDoesNotBlockAgeDeletionOrDeleteSymlinkTarget() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"));
        try(ServerRuntime runtime=runtime("deleting-symlink",null,Clock.systemUTC(),1)) {
            Instant old=Instant.parse("2020-01-01T00:00:00Z");String deleting="J0000000000000924",ordinary="J0000000000000925";
            Path source=Files.createDirectories(temp.resolve("deleting-symlink-target"));Files.writeString(source.resolve("keep.txt"),"retain target");
            Path jobDirectory=seed(runtime,deleting,"PASS",old,false,1024);assertTrue(JobDirectoryUsage.deleteJobDirectory(jobDirectory));Files.createSymbolicLink(jobDirectory,source);
            assertTrue(runtime.store.markManualDeleting(deleting,"operator"));
            seed(runtime,ordinary,"PASS",old,false,1024);
            runtime.cleanupExpiredJobs();
            assertFalse(runtime.reconcileDeletingJobs());
            assertNull(runtime.store.get(ordinary));assertFalse(Files.exists(jobDir(runtime,ordinary)));
            assertTrue(runtime.store.jobRowExists(deleting));assertTrue(Files.isSymbolicLink(jobDirectory));
            assertTrue(Files.readString(source.resolve("keep.txt")).contains("retain target"));
        }
    }

    @Test void bulkPinValidatesAllIdsBeforePinningAnyJob() throws Exception {
        try(ServerRuntime runtime=runtime("bulk-pin-atomic",null,Clock.systemUTC())) {
            String valid="J0000000000000926";seed(runtime,valid,"PASS",Instant.parse("2026-01-01T00:00:00Z"),false,128);
            assertThrows(IllegalArgumentException.class,()->runtime.pinJobs(List.of(valid,"../bad"),true,null,"operator"));
            assertThrows(IllegalArgumentException.class,()->runtime.pinJobs(List.of(valid),true,"bad\nreason","operator"));
            assertEquals(false,runtime.store.get(valid).get("pinned"));
            assertEquals(1,runtime.pinJobs(List.of(valid,valid),true,"duplicate","operator").size());
        }
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

    private static void seedQuarantinedRows(ServerRuntime runtime,int count,Instant finished) throws Exception {
        String url="jdbc:h2:file:"+runtime.config.dataDir.resolve("db/att-server").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var insert=connection.prepareStatement(
                "INSERT INTO jobs(job_id,command,package_id,principal,status,created_at,finished_at,att_version,request_summary,artifact_recovery,worker_quarantined) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            connection.setAutoCommit(false);
            for(int i=1;i<=count;i++) {
                insert.setString(1,String.format("J%016X",i));insert.setString(2,"run");insert.setString(3,"p");insert.setString(4,"ci");insert.setString(5,"PASS");
                insert.setString(6,finished.toString());insert.setString(7,finished.toString());insert.setString(8,"test");insert.setString(9,"{}");insert.setBoolean(10,false);insert.setBoolean(11,true);insert.addBatch();
            }
            insert.executeBatch();connection.commit();
        }
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
        return runtime(name,storage,clock,retentionDays,ArtifactArchive::build);
    }

    private ServerRuntime runtime(String name, String storage, Clock clock, int retentionDays, ServerRuntime.ArtifactPublisher publisher) throws Exception {
        Path yaml = temp.resolve(name + "-server.yaml");
        Files.writeString(yaml, serverYaml(name, storage, retentionDays));
        return new ServerRuntime(ServerConfig.load(yaml), Files.createDirectory(temp.resolve(name + "-libs")).toString(), ProcessBuilder::start, clock, publisher);
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
