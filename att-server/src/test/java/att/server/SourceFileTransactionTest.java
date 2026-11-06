package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceFileTransactionTest {
    private static final String BEFORE_REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AFTER_REVISION = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    @TempDir Path temp;

    @Test void interruptedAndCorruptedCommitRecoversOneReadableCoherentRevision() throws Exception {
        Path root = Files.createDirectories(temp.resolve("package")).toRealPath();
        Path data = Files.createDirectories(temp.resolve("data"));
        Files.createDirectories(root.resolve("templates"));
        Path first = root.resolve("templates/first.yaml");
        Path second = root.resolve("templates/second.yaml");
        byte[] oldFirst = bytes("id: old-first\n");
        byte[] oldSecond = bytes("id: old-second\n");
        Files.write(first, oldFirst); Files.write(second, oldSecond);
        Map<String, SourceFileTransaction.Write> writes = new LinkedHashMap<String, SourceFileTransaction.Write>();
        writes.put("templates/first.yaml", new SourceFileTransaction.Write("templates/first.yaml", bytes("id: new-first\n"), digest(oldFirst)));
        writes.put("templates/second.yaml", new SourceFileTransaction.Write("templates/second.yaml", bytes("id: new-second\n"), digest(oldSecond)));

        SourceFileTransaction interrupted = new SourceFileTransaction(data, new SourcePublicationBarrier(), applied -> {
            if (applied == 1) throw new SourceFileTransaction.SimulatedCrash();
        });
        assertThrows(SourceFileTransaction.SimulatedCrash.class, () -> interrupted.commit(
                ChangeSetPublisher.PACKAGE_SOURCE, "owner", root, "owner", BEFORE_REVISION, AFTER_REVISION,
                writes, () -> { }));
        assertEquals("id: new-first\n", read(first));
        assertEquals("id: old-second\n", read(second));
        Files.write(first, bytes("corrupted partial\n"));
        Path transactionDirectory;
        try (java.util.stream.Stream<Path> entries = Files.list(data.resolve("source-transactions"))) {
            transactionDirectory = entries.findFirst().get();
        }
        Files.write(transactionDirectory.resolve("COMMITTED"), bytes(AFTER_REVISION + "\n"));

        Map<String, Path> roots = new LinkedHashMap<String, Path>();
        roots.put(ChangeSetPublisher.PACKAGE_SOURCE + ":owner", root.toRealPath());
        final String[] recovery = new String[1];
        SourceFileTransaction recovered = new SourceFileTransaction(data, new SourcePublicationBarrier());
        int count = recovered.recover(roots, (actor, domain, scope, outcome, revision) -> recovery[0] = outcome + " " + revision);
        assertEquals(1, count);
        assertEquals("id: old-first\n", read(first));
        assertEquals("id: old-second\n", read(second));
        assertNotNull(recovery[0]);
        assertTrue(recovery[0].startsWith("ROLLED_BACK "), recovery[0]);
    }

    @Test void validatesEveryExpectedFileHashBeforeChangingAnyPublishedFile() throws Exception {
        Path root = Files.createDirectories(temp.resolve("package")).toRealPath();
        Path data = Files.createDirectories(temp.resolve("data"));
        Files.createDirectories(root.resolve("templates"));
        Path first = root.resolve("templates/first.yaml");
        Path second = root.resolve("templates/second.yaml");
        Files.write(first, bytes("id: first\n")); Files.write(second, bytes("id: second\n"));
        Map<String, SourceFileTransaction.Write> writes = new LinkedHashMap<String, SourceFileTransaction.Write>();
        writes.put("templates/first.yaml", new SourceFileTransaction.Write("templates/first.yaml", bytes("id: changed\n"), digest(bytes("id: first\n"))));
        writes.put("templates/second.yaml", new SourceFileTransaction.Write("templates/second.yaml", bytes("id: changed\n"), digest(bytes("id: stale\n"))));
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        assertThrows(SourceFileTransaction.RevisionConflict.class, () -> transaction.commit(
                ChangeSetPublisher.PACKAGE_SOURCE, "owner", root, "owner", BEFORE_REVISION, AFTER_REVISION,
                writes, () -> { }));
        assertEquals("id: first\n", read(first));
        assertEquals("id: second\n", read(second));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static String digest(byte[] bytes) throws Exception {
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte item : hashed) result.append(String.format("%02x", item & 255));
        return result.toString();
    }
}
