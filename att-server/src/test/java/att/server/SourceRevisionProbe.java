package att.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Child JVM used to verify active-revision pinning across process boundaries. */
public final class SourceRevisionProbe {
    private SourceRevisionProbe() { }

    public static void main(String[] args) throws Exception {
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

    private static boolean coherent(String a, String b) {
        if (a.equals(b)) return true;
        return "old-a".equals(a) && "old-b".equals(b)
                || "new-a".equals(a) && "new-b".equals(b);
    }
}
