package att.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Child JVM used to verify active-revision pinning across process boundaries. */
public final class SourceRevisionProbe {
    private SourceRevisionProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].startsWith("--")) {
            runMode(args);
            return;
        }
        Path data = Path.of(args[0]);
        Path source = Path.of(args[1]);
        Path done = Path.of(args[2]);
        Path result = Path.of(args[3]);
        Files.writeString(result, "running", StandardCharsets.UTF_8);
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(done) && System.nanoTime() < deadline) {
            Path pinned = transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE, "owner", source);
            String a = Files.readString(pinned.resolve("templates/a.yaml"), StandardCharsets.UTF_8);
            String b = Files.readString(pinned.resolve("templates/b.yaml"), StandardCharsets.UTF_8);
            if (!coherent(a, b)) {
                Files.writeString(result, "mixed:" + a + "/" + b, StandardCharsets.UTF_8);
                System.exit(3);
            }
            Thread.yield();
        }
        if (!Files.exists(done)) {
            Files.writeString(result, "timed-out", StandardCharsets.UTF_8);
            System.exit(4);
        }
        Files.writeString(result, "coherent", StandardCharsets.UTF_8);
    }

    private static void runMode(String[] args) throws Exception {
        switch (args[0]) {
            case "--publish-paused" -> publishPaused(args);
            case "--recover" -> recover(args);
            default -> throw new IllegalArgumentException("Unknown source revision probe mode");
        }
    }

    private static void publishPaused(String[] args) throws Exception {
        Path data = Path.of(args[1]);
        Path source = Path.of(args[2]);
        Path ready = Path.of(args[3]);
        Path release = Path.of(args[4]);
        Path result = Path.of(args[5]);
        String pauseAt = args[6];
        SourceFileTransaction.FaultInjector injector = new SourceFileTransaction.FaultInjector() {
            private void pause() throws java.io.IOException {
                Files.writeString(ready, "ready", StandardCharsets.UTF_8);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(release) && System.nanoTime() < deadline) {
                    try { Thread.sleep(10); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Paused publisher was interrupted", interrupted);
                    }
                }
                if (!Files.exists(release)) throw new java.io.IOException("Paused publisher timed out");
            }
            @Override public void beforeJournal() throws java.io.IOException {
                if ("pre-journal".equals(pauseAt)) pause();
            }
            @Override public void afterApply(int appliedCount) throws java.io.IOException {
                if ("candidate-delete".equals(pauseAt) && appliedCount == 1) pause();
            }
        };
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier(), injector);
        Path active = transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE, "owner", source);
        Map<String, SourceFileTransaction.Write> writes = new LinkedHashMap<>();
        writes.put("templates/a.yaml", new SourceFileTransaction.Write("templates/a.yaml", bytes("new-a"), digest(bytes("old-a"))));
        writes.put("templates/b.yaml", new SourceFileTransaction.Write("templates/b.yaml", bytes("new-b"), digest(bytes("old-b"))));
        transaction.commit(ChangeSetPublisher.PACKAGE_SOURCE, "owner", active, "publisher",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", writes, () -> { });
        Files.writeString(result, "published", StandardCharsets.UTF_8);
    }

    private static void recover(String[] args) throws Exception {
        Path data = Path.of(args[1]);
        Path source = Path.of(args[2]);
        Path started = Path.of(args[3]);
        Path proceed = Path.of(args[4]);
        Path result = Path.of(args[5]);
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        // Prime this process's registration to cover recovery after normal server initialization.
        transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE, "owner", source);
        Files.writeString(started, "started", StandardCharsets.UTF_8);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(proceed) && System.nanoTime() < deadline) Thread.sleep(10);
        if (!Files.exists(proceed)) throw new java.io.IOException("Recovery probe timed out before reconciliation");
        int count = transaction.recover(Map.of(ChangeSetPublisher.PACKAGE_SOURCE + ":owner", source), ignored -> { });
        Files.writeString(result, "recovered:" + count, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String digest(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static boolean coherent(String a, String b) {
        if (a.equals(b)) return true;
        return "old-a".equals(a) && "old-b".equals(b)
                || "new-a".equals(a) && "new-b".equals(b);
    }
}
