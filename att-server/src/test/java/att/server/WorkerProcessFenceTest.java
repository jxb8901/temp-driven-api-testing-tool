package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProcessFenceTest {
    @TempDir Path temp;

    @Test void incompleteProofStillStopsKnownProcessesWithoutAuthorizingPublication() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));
        Process parent = new ProcessBuilder("sh", "-c", "exec sleep 30").start();
        try {
            WorkerProcessFence fence = new WorkerProcessFence(parent);
            fence.unverified();
            long started = System.nanoTime();
            fence.stop(10_000);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000,
                    "Unknown containment is not a reason to wait after all known processes exited");
            assertFalse(parent.isAlive());
            assertFalse(fence.stopped(), "A partial proof must never authorize publication");
            assertTrue(fence.observedStopped(), "Known-process exit permits durable quarantine, not publication");
            assertFalse(fence.verified());
            CountDownLatch callback = new CountDownLatch(1);
            fence.whenStopped(callback::countDown);
            assertFalse(callback.await(100, TimeUnit.MILLISECONDS));
        } finally {parent.destroyForcibly();parent.waitFor(5, TimeUnit.SECONDS);}
    }

    @Test void retainsAndForcesAChildThatOutlivesItsParentAndIgnoresTermination() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));
        Path script = Files.writeString(temp.resolve("tree.sh"), "#!/bin/sh\n"
                + "if [ \"$1\" = child ]; then\n  trap '' TERM\n  echo $$ > \"$2\"\n  while :; do sleep 0.02; done\nfi\n"
                + "sh \"$0\" child \"$1\" >/dev/null 2>&1 &\nwait\n");
        Path pidFile = temp.resolve("child.pid");
        Process parent = new ProcessBuilder("sh", script.toString(), pidFile.toString()).start();
        ProcessHandle child = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile));
            child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
            WorkerProcessFence fence = new WorkerProcessFence(parent);
            parent.destroy();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            assertTrue(child.isAlive(), "The child must survive the parent for this regression to be meaningful");
            assertFalse(fence.stopped());
            CountDownLatch stopped = new CountDownLatch(1);
            fence.whenStopped(stopped::countDown);
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS));
            fence.stop(100);
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            assertTrue(fence.stopped());
            assertFalse(child.isAlive());
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            parent.destroyForcibly();
            parent.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
