package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

class ConfigImpactPublicationFenceTest {
    @TempDir Path temp;

    @Test void registryAndScheduleWritersStayBlockedUntilThePublishFenceCloses() throws Exception {
        ReentrantReadWriteLock registry = new ReentrantReadWriteLock(true);
        Object scheduleStore = new Object();
        CountDownLatch insidePublish = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        CountDownLatch registryWriterStarted = new CountDownLatch(1);
        CountDownLatch scheduleWriterStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Boolean> publication = executor.submit(() -> ConfigImpactPublicationFence.runInventory(
                    registry.readLock(), scheduleStore, () -> {
                        insidePublish.countDown();
                        assertTrue(releasePublish.await(2, TimeUnit.SECONDS));
                        return true;
                    }));
            assertTrue(insidePublish.await(1, TimeUnit.SECONDS));
            Future<Boolean> registryMutation = executor.submit(() -> {
                registryWriterStarted.countDown();
                registry.writeLock().lock();
                try { return true; } finally { registry.writeLock().unlock(); }
            });
            Future<Boolean> scheduleMutation = executor.submit(() -> {
                scheduleWriterStarted.countDown();
                synchronized (scheduleStore) { return true; }
            });
            assertTrue(registryWriterStarted.await(1, TimeUnit.SECONDS));
            assertTrue(scheduleWriterStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(registryMutation.isDone(), "Registry changes must wait until pointer publication finishes");
            assertFalse(scheduleMutation.isDone(), "Schedule changes must wait until pointer publication finishes");

            releasePublish.countDown();
            assertTrue(publication.get(1, TimeUnit.SECONDS));
            assertTrue(registryMutation.get(1, TimeUnit.SECONDS));
            assertTrue(scheduleMutation.get(1, TimeUnit.SECONDS));
        } finally {
            releasePublish.countDown();
            executor.shutdownNow();
        }
    }

    @Test void packageSourceWriterStaysBlockedAcrossCentralPublicationFence() throws Exception {
        Path packageRoot = Files.createDirectories(temp.resolve("packages/probe"));
        Path centralRoot = Files.createDirectories(temp.resolve("runtime"));
        Path centralConfig = Files.createDirectories(centralRoot.resolve("config"));
        Path data = Files.createDirectories(temp.resolve("data"));
        byte[] before = "schemaVersion: att-package/v1\nmetadata: {name: before}\n".getBytes(StandardCharsets.UTF_8);
        byte[] after = "schemaVersion: att-package/v1\nmetadata: {name: after}\n".getBytes(StandardCharsets.UTF_8);
        byte[] centralBefore = "schemaVersion: att-config/v2.13\nname: before\n".getBytes(StandardCharsets.UTF_8);
        byte[] centralAfter = "schemaVersion: att-config/v2.13\nname: after\n".getBytes(StandardCharsets.UTF_8);
        Files.write(packageRoot.resolve("package.yaml"), before);
        Files.write(centralConfig.resolve("config.yaml"), centralBefore);
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        transaction.registerRoot(ChangeSetPublisher.PACKAGE_SOURCE, "probe", packageRoot);
        transaction.registerRoot(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                ChangeSetPublisher.CENTRAL_SCOPE_ID, centralRoot);
        ReentrantReadWriteLock registry = new ReentrantReadWriteLock(true);
        Object scheduleStore = new Object();
        CountDownLatch finalGate = new CountDownLatch(1);
        CountDownLatch releaseFinalGate = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger gateChecks = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> publication = executor.submit(() -> ConfigImpactPublicationFence.runPublication(
                    registry.readLock(), () -> Map.of("probe", packageRoot), transaction, scheduleStore, () ->
                            transaction.commit(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                                    ChangeSetPublisher.CENTRAL_SCOPE_ID, centralRoot, "admin", digest(centralBefore),
                                    digest(centralAfter), Map.of("config/config.yaml", new SourceFileTransaction.Write(
                                            "config/config.yaml", centralAfter, digest(centralBefore))), () -> {
                                        if (gateChecks.incrementAndGet() == 2) {
                                            finalGate.countDown();
                                            assertTrue(releaseFinalGate.await(2, TimeUnit.SECONDS));
                                        }
                                    })));
            assertTrue(finalGate.await(1, TimeUnit.SECONDS), "central transaction should reach its last pre-switch check");
            Future<String> packageMutation = executor.submit(() -> {
                writerStarted.countDown();
                return transaction.commit(ChangeSetPublisher.PACKAGE_SOURCE, "probe", packageRoot, "admin",
                        digest(before), digest(after), Map.of("package.yaml", new SourceFileTransaction.Write(
                                "package.yaml", after, digest(before))), () -> { });
            });
            assertTrue(writerStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(packageMutation.isDone(), "Package pointer switches must wait after the final Config check");

            releaseFinalGate.countDown();
            assertEquals(digest(centralAfter), publication.get(1, TimeUnit.SECONDS));
            assertEquals(digest(after), packageMutation.get(1, TimeUnit.SECONDS));
            Path activeCentral = transaction.pin(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, centralRoot);
            String publishedCentral = Files.readString(activeCentral.resolve("config/config.yaml"));
            assertTrue(publishedCentral.contains("name: after"));
            assertFalse(publishedCentral.contains("name: before"));
        } finally {
            releaseFinalGate.countDown();
            executor.shutdownNow();
        }
    }

    @Test void exclusiveConfigPublicationBlocksAdmissionsUntilActivationFenceCompletes() throws Exception {
        ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
        Object scheduleStore = new Object();
        SourceFileTransaction transaction = new SourceFileTransaction(
                Files.createDirectories(temp.resolve("admission-fence-data")),
                new SourcePublicationBarrier());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch admissionStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> publication = executor.submit(() -> ConfigImpactPublicationFence.runPublication(
                    lifecycle.writeLock(), Map::of, transaction, scheduleStore, () -> {
                        entered.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return true;
                    }));
            assertTrue(entered.await(1, TimeUnit.SECONDS), "publication must enter exclusive activation fence");
            Future<Boolean> admission = executor.submit(() -> {
                admissionStarted.countDown();
                lifecycle.readLock().lock();
                try { return true; }
                finally { lifecycle.readLock().unlock(); }
            });
            assertTrue(admissionStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(admission.isDone(),
                    "Job admission and Worker startup must not use old/new source during activation");
            release.countDown();
            assertTrue(publication.get(1, TimeUnit.SECONDS));
            assertTrue(admission.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
