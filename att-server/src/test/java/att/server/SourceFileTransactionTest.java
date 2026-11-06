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

    @Test void replacementPreservesExistingPosixPermissionsAndCreatesUseOwnerOnlyPermissions() throws Exception {
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
        assertEquals(readable,Files.getPosixFilePermissions(published.resolve("templates/a.yaml")));
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(published.resolve("templates/new.yaml")));
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
        Path root = Files.createDirectories(temp.resolve("package")).toRealPath();
        Files.createDirectories(root.resolve("templates"));
        Files.write(root.resolve("templates/a.yaml"), bytes("old-a"));
        Files.write(root.resolve("templates/b.yaml"), bytes("old-b"));
        return root;
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
