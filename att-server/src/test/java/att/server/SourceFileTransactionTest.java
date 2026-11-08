package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SourceFileTransactionTest {
    private static final String BEFORE_REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AFTER_REVISION = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String PACKAGE = ChangeSetPublisher.PACKAGE_SOURCE;
    private static final String SCOPE = "owner";
    @TempDir Path temp;

    @Test void interruptedCandidateBuildLeavesTheOldCompleteRevisionActiveAndRecoversWithoutRestore() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier(), applied -> {
            if (applied == 1) throw new SourceFileTransaction.SimulatedCrash();
        });
        Path oldPin = transaction.pin(PACKAGE, SCOPE, root);
        assertThrows(SourceFileTransaction.SimulatedCrash.class, () -> transaction.commit(
                PACKAGE, SCOPE, oldPin, "owner", BEFORE_REVISION, AFTER_REVISION,
                writes("old-a", "old-b", "new-a", "new-b"), () -> { }));

        assertPair(oldPin, "old-a", "old-b");
        assertPair(transaction.pin(PACKAGE, SCOPE, root), "old-a", "old-b");
        AtomicReference<SourceFileTransaction.AuditRecord> recovered = new AtomicReference<>();
        Map<String, Path> trusted = Map.of(PACKAGE + ":" + SCOPE, root);
        assertEquals(1, transaction.recover(trusted, recovered::set));
        assertPair(transaction.pin(PACKAGE, SCOPE, root), "old-a", "old-b");
        assertEquals("ROLLED_BACK", recovered.get().outcome);
        assertEquals(BEFORE_REVISION, recovered.get().beforeRevision);
        assertEquals(AFTER_REVISION, recovered.get().afterRevision);
    }

    @Test void startupRecoveryWaitsForLivePublisherBeforePreJournalCleanupAndCandidateReconciliation() throws Exception {
        for (String pauseAt : List.of("pre-journal", "candidate-delete")) {
            Path scenario = Files.createDirectories(temp.resolve("recovery-race-" + pauseAt));
            Path root = initialTree(scenario.resolve("package"));
            Path data = Files.createDirectories(scenario.resolve("data"));
            new SourceFileTransaction(data, new SourcePublicationBarrier()).pin(PACKAGE, SCOPE, root);

            Path recoveryStarted = scenario.resolve("recovery.started");
            Path allowRecovery = scenario.resolve("recovery.go");
            Path recoveryResult = scenario.resolve("recovery.result");
            Process recovery = startProbe("--recover", data.toString(), root.toString(), recoveryStarted.toString(),
                    allowRecovery.toString(), recoveryResult.toString());
            awaitFile(recoveryStarted, "recovery process did not register its source root");

            Path publisherReady = scenario.resolve("publisher.ready");
            Path allowPublisher = scenario.resolve("publisher.go");
            Path publisherResult = scenario.resolve("publisher.result");
            Process publisher = startProbe("--publish-paused", data.toString(), root.toString(), publisherReady.toString(),
                    allowPublisher.toString(), publisherResult.toString(), pauseAt);
            awaitFile(publisherReady, "publisher did not reach " + pauseAt + " window");
            Files.writeString(allowRecovery, "go", StandardCharsets.UTF_8);
            Thread.sleep(300);
            assertTrue(recovery.isAlive(), "recovery must wait for the live publisher at " + pauseAt);
            assertFalse(Files.exists(recoveryResult), "recovery must not reconcile an in-flight transaction");

            Files.writeString(allowPublisher, "go", StandardCharsets.UTF_8);
            assertProcessSuccess(publisher, "publisher failed at " + pauseAt);
            assertProcessSuccess(recovery, "recovery failed at " + pauseAt);
            assertEquals("published", Files.readString(publisherResult, StandardCharsets.UTF_8));
            assertTrue(Files.readString(recoveryResult, StandardCharsets.UTF_8).startsWith("recovered:"));
            assertPair(new SourceFileTransaction(data, new SourcePublicationBarrier()).pin(PACKAGE, SCOPE, root), "new-a", "new-b");
        }
    }

    @Test void configuredSourceImportsExternalEditsAfterRestartAndExportsPublishedRevisions() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path active = transaction.pin(PACKAGE, SCOPE, root);
        transaction.commit(PACKAGE, SCOPE, active, "owner", BEFORE_REVISION, AFTER_REVISION,
                writes("old-a", "old-b", "published-a", "published-b"), () -> { });
        assertPair(root, "published-a", "published-b");

        Files.writeString(root.resolve("templates/a.yaml"), "deployment-a", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("templates/b.yaml"), "deployment-b", StandardCharsets.UTF_8);
        SourceFileTransaction restarted = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path imported = restarted.pin(PACKAGE, SCOPE, root);
        assertPair(imported, "deployment-a", "deployment-b");
        assertPair(root, "deployment-a", "deployment-b");
    }

    @Test void centralRevisionCapturesOnlyApprovedConfigSourcesAndPreservesUnrelatedInstallationFiles() throws Exception {
        String domain = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG;
        Path root = Files.createDirectories(temp.resolve("att-home-central")).toRealPath();
        Path config = Files.createDirectories(root.resolve("config"));
        Files.writeString(config.resolve("config.yaml"), "schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                + "dbhelpers: [config/custom/dependencies/db-discovery.yaml]\n");
        Files.writeString(config.resolve("source-references.yaml"), "payload: '&{config/tools/echo.sh}'\n");
        Path nestedHelper = Files.createDirectories(config.resolve("custom/dependencies")).resolve("db-discovery.yaml");
        Files.writeString(nestedHelper, "schemaVersion: att-dbhelper/v2.6\nid: orders\nconnection: {url: 'jdbc:unused'}\n");
        Files.writeString(config.resolve("not-referenced.yaml"), "not part of the current Config dependency closure\n");
        Path tools = Files.createDirectories(config.resolve("tools"));
        Files.writeString(tools.resolve("echo.yaml"), "schemaVersion: att-tool/v1\ncommand: [echo]\n");
        Files.writeString(tools.resolve("echo.sh"), "#!/bin/sh\necho helper\n");
        Path unrelatedNodeModules = Files.createDirectories(root.resolve("node_modules/.bin"));
        Files.writeString(unrelatedNodeModules.resolve("yaml"), "mutable build output");
        Path unrelatedOutput = Files.createDirectories(root.resolve("output"));
        Path outputMarker = Files.writeString(unrelatedOutput.resolve("server.log"), "before");
        Path unrelatedConfig = Files.createDirectories(config.resolve("schemas"));
        Files.writeString(unrelatedConfig.resolve("private.json"), "not an authorable central descriptor");

        Path data = Files.createDirectories(temp.resolve("central-revision-data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path firstPin = transaction.pin(domain, "central", root);
        assertTrue(Files.exists(firstPin.resolve("config/config.yaml")));
        assertTrue(Files.exists(firstPin.resolve("config/source-references.yaml")),
                "The source-link index is part of the bounded inspection closure, not an editable Config category");
        assertTrue(Files.exists(firstPin.resolve("config/tools/echo.yaml")));
        assertTrue(Files.exists(firstPin.resolve("config/tools/echo.sh")));
        assertTrue(Files.exists(firstPin.resolve("config/custom/dependencies/db-discovery.yaml")),
                "Direct Helper paths named by Config are part of the immutable candidate closure");
        assertFalse(Files.exists(firstPin.resolve("config/not-referenced.yaml")),
                "Unreferenced Config-root files are not captured");
        assertFalse(ChangeSetPublisher.centralPath("config/source-references.yaml"),
                "Inspection metadata is retained without making it a central authoring path");
        assertFalse(Files.exists(firstPin.resolve("node_modules")));
        assertFalse(Files.exists(firstPin.resolve("output")));
        assertFalse(Files.exists(firstPin.resolve("config/schemas")));

        Files.writeString(unrelatedNodeModules.resolve("yaml"), "changed unrelated build output");
        Files.writeString(outputMarker, "changed unrelated runtime output");
        AtomicBoolean mutate = new AtomicBoolean(true);
        Thread outputWriter = new Thread(() -> {
            int sequence = 0;
            while (mutate.get()) {
                try { Files.writeString(outputMarker, "concurrent runtime output " + sequence++); }
                catch (Exception unavailable) { return; }
            }
        }, "central-unrelated-output-writer-test");
        outputWriter.start();
        Path afterUnrelatedChanges;
        try { afterUnrelatedChanges = transaction.pin(domain, "central", root); }
        finally { mutate.set(false); outputWriter.join(5000); }
        assertFalse(outputWriter.isAlive());
        assertEquals(firstPin, afterUnrelatedChanges, "unrelated ATT_HOME writes must not create Config revisions");

        byte[] replacement = bytes("schemaVersion: att-config/v2.12\nenvironment: UAT\n");
        Map<String, SourceFileTransaction.Write> writes = new LinkedHashMap<>();
        writes.put("config/config.yaml", new SourceFileTransaction.Write("config/config.yaml", replacement,
                digest(bytes("schemaVersion: att-config/v2.12\nenvironment: SIT\ndbhelpers: [config/custom/dependencies/db-discovery.yaml]\n"))));
        transaction.commit(domain, "central", afterUnrelatedChanges, "admin", BEFORE_REVISION,
                AFTER_REVISION, writes, () -> { });

        assertEquals("changed unrelated build output", Files.readString(unrelatedNodeModules.resolve("yaml")));
        assertTrue(Files.readString(outputMarker).startsWith("concurrent runtime output "));
        assertEquals("not an authorable central descriptor", Files.readString(unrelatedConfig.resolve("private.json")));
        assertEquals(replacement.length, Files.size(config.resolve("config.yaml")));
        assertEquals("schemaVersion: att-config/v2.12\nenvironment: UAT\n", Files.readString(config.resolve("config.yaml")));
        assertFalse(Files.exists(transaction.pin(domain, "central", root).resolve("config/custom/dependencies/db-discovery.yaml")),
                "Removed Helper references are retired from the next immutable central revision");
    }

    @Test void customRuntimeConfigPathIsCapturedAtTheStableWorkerPathAndExportedBack() throws Exception {
        String domain = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG;
        Path root = Files.createDirectories(temp.resolve("custom-runtime-root")).toRealPath();
        Path configured = Files.writeString(root.resolve("runtime.yaml"),
                "schemaVersion: att-config/v2.12\nenvironment: SIT\n");
        Files.writeString(Files.createDirectories(root.resolve("config/tools")).resolve("ping.yaml"),
                "schemaVersion: att-tool/v1\ncommand: [echo]\n");
        Files.writeString(Files.createDirectories(root.resolve("node_modules/.bin")).resolve("yaml"), "unrelated");
        Path data = Files.createDirectories(temp.resolve("custom-runtime-data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        transaction.registerRoot(domain, "central-custom", root, configured);
        Path active = transaction.pin(domain, "central-custom", root);
        assertEquals(Files.readString(configured), Files.readString(active.resolve("config/config.yaml")));
        assertTrue(Files.exists(active.resolve("config/tools/ping.yaml")));
        assertFalse(Files.exists(active.resolve("runtime.yaml")));
        assertFalse(Files.exists(active.resolve("node_modules")));

        byte[] replacement = bytes("schemaVersion: att-config/v2.12\nenvironment: UAT\n");
        Map<String, SourceFileTransaction.Write> writes = new LinkedHashMap<>();
        writes.put("config/config.yaml", new SourceFileTransaction.Write("config/config.yaml", replacement,
                digest(bytes("schemaVersion: att-config/v2.12\nenvironment: SIT\n"))));
        transaction.commit(domain, "central-custom", active, "admin", BEFORE_REVISION, AFTER_REVISION, writes, () -> { });
        assertEquals(new String(replacement, StandardCharsets.UTF_8), Files.readString(configured));
        assertFalse(Files.exists(root.resolve("config/config.yaml")), "The stable revision path must map back to the configured filename");
        assertEquals("unrelated", Files.readString(root.resolve("node_modules/.bin/yaml")));
    }

    @Test void executionSnapshotKeepsBothReadsOnTheAcceptedBytesWhenSameOwnerMutatesPinnedRevision() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("execution-snapshot-data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path revision = transaction.pin(PACKAGE, SCOPE, root);
        Path execution = transaction.executionPin(PACKAGE, SCOPE, root);
        assertNotEquals(revision, execution);
        String expectedExecutionDigest = SourceRevisionStore.treeDigest(PACKAGE, execution);

        String firstRead = Files.readString(execution.resolve("templates/a.yaml"), StandardCharsets.UTF_8);
        Path changed = revision.resolve("templates/b.yaml");
        if (!Files.getFileStore(changed).supportsFileAttributeView("posix")) return;
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(changed);
        permissions.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(changed, permissions);
        Files.writeString(changed, "tampered between Worker reads", StandardCharsets.UTF_8);
        String secondRead = Files.readString(execution.resolve("templates/b.yaml"), StandardCharsets.UTF_8);
        assertEquals("old-a", firstRead);
        assertEquals("old-b", secondRead);
        assertEquals(expectedExecutionDigest, SourceRevisionStore.treeDigest(PACKAGE, execution));
        assertThrows(java.io.IOException.class, () -> transaction.executionPin(PACKAGE, SCOPE, root),
                "A corrupted published revision must fail closed for new execution snapshots");
    }

    @Test void pinnedRevisionsAreReadOnlyAndDelayedReadersKeepTheirOriginalTree() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path delayedPin = transaction.pin(PACKAGE, SCOPE, root);
        Path pinnedFile = delayedPin.resolve("templates/a.yaml");
        if (Files.getFileStore(pinnedFile).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(pinnedFile);
            assertFalse(permissions.contains(PosixFilePermission.OWNER_WRITE));
            assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE));
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE));
            assertThrows(java.io.IOException.class, () -> Files.writeString(pinnedFile, "tampered", StandardCharsets.UTF_8));
        }
        assertPair(delayedPin, "old-a", "old-b");

        transaction.commit(PACKAGE, SCOPE, delayedPin, "owner", BEFORE_REVISION, AFTER_REVISION,
                writes("old-a", "old-b", "new-a", "new-b"), () -> { });
        assertPair(delayedPin, "old-a", "old-b");
        assertPair(transaction.pin(PACKAGE, SCOPE, root), "new-a", "new-b");
    }

    @Test void sameOwnerRevisionMutationIsDetectedByTheNextContentVerifiedPin() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("same-owner-tamper-data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path revision = transaction.pin(PACKAGE, SCOPE, root);
        Path file = revision.resolve("templates/a.yaml");
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) return;

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        permissions.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file, permissions);
        Files.writeString(file, "tampered by the owning Server identity", StandardCharsets.UTF_8);
        java.io.IOException rejected = assertThrows(java.io.IOException.class, () -> transaction.pin(PACKAGE, SCOPE, root));
        assertTrue(rejected.getMessage().contains("integrity check"), rejected.getMessage());
    }

    @Test void sameOwnerCentralRevisionMutationIsRejectedAtDelayedUse() throws Exception {
        String domain = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG;
        Path root = Files.createDirectories(temp.resolve("same-owner-central-tamper-home")).toRealPath();
        Path config = Files.createDirectories(root.resolve("config"));
        Files.writeString(config.resolve("config.yaml"), "schemaVersion: att-config/v2.12\nenvironment: SIT\n");
        Path data = Files.createDirectories(temp.resolve("same-owner-central-tamper-data"));
        Path revision = new SourceFileTransaction(data, new SourcePublicationBarrier()).pin(domain, "central", root);
        String expectedDigest = SourceRevisionStore.treeDigest(domain, revision);
        Path pinnedConfig = revision.resolve("config/config.yaml");
        if (!Files.getFileStore(pinnedConfig).supportsFileAttributeView("posix")) return;

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(pinnedConfig);
        permissions.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(pinnedConfig, permissions);
        Files.writeString(pinnedConfig, "tampered central config", StandardCharsets.UTF_8);

        java.io.IOException rejected = assertThrows(java.io.IOException.class,
                () -> ServerRuntime.verifyAcceptedCentralSource(revision, expectedDigest));
        assertTrue(rejected.getMessage().contains("integrity check"), rejected.getMessage());
    }

    @Test void atomicSwitchKeepsDelayedWorkerPinnedAndSeparateProcessReadersCoherent() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path delayedWorkerPin = transaction.pin(PACKAGE, SCOPE, root);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch allowWorkerExit = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread delayedWorker = new Thread(() -> {
            try {
                workerStarted.countDown();
                assertTrue(allowWorkerExit.await(10, TimeUnit.SECONDS));
                assertPair(delayedWorkerPin, "old-a", "old-b");
            } catch (Throwable failure) { workerFailure.set(failure); }
        }, "delayed-source-worker-test");
        delayedWorker.start();
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

        Path done = temp.resolve("reader.done");
        Path result = temp.resolve("reader.result");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process reader = new ProcessBuilder(java.toString(), "-cp", classpath,
                "att.server.SourceRevisionProbe", data.toString(), root.toString(), done.toString(), result.toString())
                .redirectErrorStream(true).start();
        long readerStartDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!Files.exists(result)&&System.nanoTime()<readerStartDeadline)Thread.sleep(10);
        assertTrue(Files.exists(result),"separate reader process did not start");

        AtomicBoolean apiDone=new AtomicBoolean();
        AtomicReference<Throwable> apiFailure=new AtomicReference<>();
        Thread apiReader=new Thread(()->{
            try {
                while(!apiDone.get()) {
                    Path pinned=transaction.pin(PACKAGE,SCOPE,root);
                    String first=Files.readString(pinned.resolve("templates/a.yaml"));
                    String second=Files.readString(pinned.resolve("templates/b.yaml"));
                    boolean initial="old-a".equals(first)&&"old-b".equals(second);
                    boolean published=first.equals(second)&&first.startsWith("pair-");
                    if(!initial&&!published)throw new AssertionError("in-process API read observed a mixed source revision");
                }
            } catch(Throwable failure) { apiFailure.set(failure); }
        },"pinned-source-api-reader-test");
        apiReader.setDaemon(true);apiReader.start();

        String beforeA="old-a",beforeB="old-b";
        for (int index = 1; index <= 20; index++) {
            Path active = transaction.pin(PACKAGE, SCOPE, root);
            String suffix = Integer.toString(index);
            transaction.commit(PACKAGE, SCOPE, active, "owner", BEFORE_REVISION, digest("revision-" + suffix),
                    writes(beforeA,beforeB,"pair-"+suffix,"pair-"+suffix), () -> { });
            beforeA="pair-"+suffix;beforeB=beforeA;
        }
        apiDone.set(true);apiReader.join(5000);
        assertFalse(apiReader.isAlive());
        assertNull(apiFailure.get());
        Files.writeString(done, "done", StandardCharsets.UTF_8);
        assertTrue(reader.waitFor(20, TimeUnit.SECONDS), "separate reader process did not finish");
        String processOutput = new String(reader.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, reader.exitValue(), processOutput);
        assertTrue(Files.exists(result), processOutput);
        assertEquals("coherent", Files.readString(result, StandardCharsets.UTF_8));
        assertPair(transaction.pin(PACKAGE, SCOPE, root), "pair-20", "pair-20");

        allowWorkerExit.countDown();
        delayedWorker.join(5000);
        assertFalse(delayedWorker.isAlive());
        assertNull(workerFailure.get());
        assertPair(delayedWorkerPin, "old-a", "old-b");
    }

    @Test void staleFileHashesAreCheckedBeforeTheActivePointerMoves() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path active = transaction.pin(PACKAGE, SCOPE, root);
        Map<String, SourceFileTransaction.Write> writes = writes("old-a", "old-b", "changed-a", "changed-b");
        writes.put("templates/b.yaml", new SourceFileTransaction.Write("templates/b.yaml", bytes("changed-b"), digest(bytes("stale"))));
        assertThrows(SourceFileTransaction.RevisionConflict.class, () -> transaction.commit(
                PACKAGE, SCOPE, active, "owner", BEFORE_REVISION, AFTER_REVISION, writes, () -> { }));
        assertPair(transaction.pin(PACKAGE, SCOPE, root), "old-a", "old-b");
    }

    @Test void publishedRevisionFilesKeepReaderModesWithoutWritePermissions() throws Exception {
        Path root=initialTree();
        if(!Files.getFileStore(root).supportsFileAttributeView("posix"))return;
        Path source=root.resolve("templates/a.yaml");
        Set<PosixFilePermission> readable=EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(source,readable);
        SourceFileTransaction transaction=new SourceFileTransaction(Files.createDirectories(temp.resolve("data")),new SourcePublicationBarrier());
        Path active=transaction.pin(PACKAGE,SCOPE,root);
        Map<String,SourceFileTransaction.Write> writes=new LinkedHashMap<>();
        writes.put("templates/a.yaml",new SourceFileTransaction.Write("templates/a.yaml",bytes("replacement"),digest(bytes("old-a"))));
        writes.put("templates/new.yaml",new SourceFileTransaction.Write("templates/new.yaml",bytes("new"),null));
        transaction.commit(PACKAGE,SCOPE,active,"owner",BEFORE_REVISION,AFTER_REVISION,writes,()->{});
        Path published=transaction.pin(PACKAGE,SCOPE,root);
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.GROUP_READ),
                Files.getPosixFilePermissions(published.resolve("templates/a.yaml")));
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ),
                Files.getPosixFilePermissions(published.resolve("templates/new.yaml")));
    }

    @Test void ownerChmodMutationIsDetectedBeforeTheRevisionCanBePinnedAgain() throws Exception {
        Path root = initialTree();
        if (!Files.getFileStore(root).supportsFileAttributeView("posix")) return;
        SourceFileTransaction transaction = new SourceFileTransaction(Files.createDirectories(temp.resolve("data")),
                new SourcePublicationBarrier());
        Path pinned = transaction.pin(PACKAGE, SCOPE, root);
        Path file = pinned.resolve("templates/a.yaml");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        permissions.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file, permissions);
        Files.writeString(file, "tamper", StandardCharsets.UTF_8);

        java.io.IOException rejected = assertThrows(java.io.IOException.class,
                () -> transaction.pin(PACKAGE, SCOPE, root));
        assertTrue(rejected.getMessage().contains("integrity check"), rejected.getMessage());
    }

    @Test void sameSizeConfiguredReplacementWithRestoredMtimeCreatesANewRevision() throws Exception {
        Path root = initialTree();
        SourceFileTransaction transaction = new SourceFileTransaction(Files.createDirectories(temp.resolve("data")),
                new SourcePublicationBarrier());
        Path pinnedBefore = transaction.pin(PACKAGE, SCOPE, root);
        Path configuredFile = root.resolve("templates/a.yaml");
        java.nio.file.attribute.FileTime originalTime = Files.getLastModifiedTime(configuredFile);
        Files.writeString(configuredFile, "new-a", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(configuredFile, originalTime);

        Path pinnedAfter = transaction.pin(PACKAGE, SCOPE, root);
        assertNotEquals(pinnedBefore, pinnedAfter);
        assertEquals("new-a", Files.readString(pinnedAfter.resolve("templates/a.yaml"), StandardCharsets.UTF_8));
    }

    @Test void auditFailureKeepsCommittedJournalForIdempotentRecoveryReplay() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("data"));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        Path active = transaction.pin(PACKAGE, SCOPE, root);
        SourceFileTransaction.CommitResult committed = transaction.commitWithAudit(PACKAGE, SCOPE, active, "alice",
                BEFORE_REVISION, AFTER_REVISION, writes("old-a", "old-b", "new-a", "new-b"), () -> { },
                "request-252", List.of("templates/a.yaml", "templates/b.yaml"), "SOURCE_CHANGE_SET_PUBLISH",
                record -> { throw new java.io.IOException("injected durable audit failure"); });
        assertEquals(AFTER_REVISION, committed.revision);
        assertFalse(committed.auditConfirmed);
        assertPair(transaction.pin(PACKAGE, SCOPE, root), "new-a", "new-b");

        Map<String, SourceFileTransaction.AuditRecord> events = new LinkedHashMap<>();
        Map<String, Path> trusted = Map.of(PACKAGE + ":" + SCOPE, root);
        assertEquals(1, transaction.recover(trusted, record -> events.putIfAbsent(record.transactionId, record)));
        assertEquals(0, transaction.recover(trusted, record -> events.putIfAbsent(record.transactionId, record)));
        assertEquals(1, events.size());
        SourceFileTransaction.AuditRecord record = events.values().iterator().next();
        assertEquals("alice", record.actor);
        assertEquals(PACKAGE, record.domain);
        assertEquals(SCOPE, record.scopeId);
        assertEquals(List.of("templates/a.yaml", "templates/b.yaml"), record.resources);
        assertEquals(BEFORE_REVISION, record.beforeRevision);
        assertEquals(AFTER_REVISION, record.afterRevision);
        assertEquals("request-252", record.requestId);
        assertEquals("COMMITTED", record.outcome);
    }

    @Test void crashImmediatelyAfterPointerSwitchRecoversTheCommittedAuditOutcome() throws Exception {
        Path root = initialTree();
        Path data = Files.createDirectories(temp.resolve("data"));
        SourceFileTransaction.FaultInjector afterSwitch = new SourceFileTransaction.FaultInjector() {
            @Override public void afterApply(int appliedCount) { }
            @Override public void afterPointerSwitch() throws java.io.IOException { throw new SourceFileTransaction.SimulatedCrash(); }
        };
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier(), afterSwitch);
        Path active = transaction.pin(PACKAGE, SCOPE, root);
        assertThrows(SourceFileTransaction.SimulatedCrash.class, () -> transaction.commitWithAudit(PACKAGE, SCOPE,
                active, "alice", BEFORE_REVISION, AFTER_REVISION, writes("old-a", "old-b", "new-a", "new-b"),
                () -> { }, "request-after-switch", List.of("templates/a.yaml", "templates/b.yaml"),
                "SOURCE_CHANGE_SET_PUBLISH", record -> fail("audit must be replayed on recovery")));
        assertPair(transaction.pin(PACKAGE, SCOPE, root), "new-a", "new-b");
        AtomicReference<SourceFileTransaction.AuditRecord> recovered = new AtomicReference<>();
        assertEquals(1, transaction.recover(Map.of(PACKAGE + ":" + SCOPE, root), recovered::set));
        assertEquals("COMMITTED", recovered.get().outcome);
        assertEquals("request-after-switch", recovered.get().requestId);
        assertEquals(AFTER_REVISION, recovered.get().afterRevision);
        assertPair(transaction.pin(PACKAGE, SCOPE, root), "new-a", "new-b");
    }

    private Path initialTree() throws Exception {
        return initialTree(temp.resolve("package"));
    }

    private Path initialTree(Path directory) throws Exception {
        Path root = Files.createDirectories(directory).toRealPath();
        Files.createDirectories(root.resolve("templates"));
        Files.write(root.resolve("templates/a.yaml"), bytes("old-a"));
        Files.write(root.resolve("templates/b.yaml"), bytes("old-b"));
        return root;
    }

    private Process startProbe(String... args) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable.toString()); command.add("-cp"); command.add(classpath);
        command.add("att.server.SourceRevisionProbe"); command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static void awaitFile(Path file, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(file), message);
    }

    private static void assertProcessSuccess(Process process, String message) throws Exception {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), message + ": timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), message + ": " + output);
    }

    private static Map<String, SourceFileTransaction.Write> writes(String oldA, String oldB, String newA, String newB) throws Exception {
        Map<String, SourceFileTransaction.Write> writes = new LinkedHashMap<>();
        writes.put("templates/a.yaml", new SourceFileTransaction.Write("templates/a.yaml", bytes(newA), digest(bytes(oldA))));
        writes.put("templates/b.yaml", new SourceFileTransaction.Write("templates/b.yaml", bytes(newB), digest(bytes(oldB))));
        return writes;
    }

    private static void assertPair(Path root, String first, String second) throws Exception {
        assertEquals(first, Files.readString(root.resolve("templates/a.yaml"), StandardCharsets.UTF_8));
        assertEquals(second, Files.readString(root.resolve("templates/b.yaml"), StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String digest(String value) throws Exception { return digest(bytes(value)); }
    private static String digest(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
