package att.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Retains observed descendants even after the Worker exits and they are reparented. */
final class WorkerProcessFence {
    record ProcessIdentity(long pid, Instant start) { }
    private static final int MAX_PROCESSES = 1024;
    private final Process root;
    private final Map<Long, Identity> observed = new LinkedHashMap<>();
    private volatile boolean incomplete;

    private record Identity(ProcessHandle handle, Instant start) {
        boolean alive() {
            try {
                if (!handle.isAlive()) return false;
                Instant actual = handle.info().startInstant().orElse(null);
                return start == null || actual == null || start.equals(actual);
            } catch (RuntimeException unavailable) { return true; }
        }
        boolean canSignal() {
            try { return start != null && start.equals(handle.info().startInstant().orElse(null)); }
            catch (RuntimeException unavailable) { return false; }
        }
    }

    WorkerProcessFence(Process root) {
        this.root = root;
        observe();
    }

    synchronized void observe() {
        try {
            if (root.isAlive()) remember(root.toHandle());
            List<Identity> parents = new ArrayList<>(observed.values());
            for (Identity parent : parents) {
                if (!parent.alive()) continue;
                try (var children = parent.handle.descendants()) {
                    var iterator = children.iterator();
                    while (iterator.hasNext()) {
                        remember(iterator.next());
                        if (incomplete) return;
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            incomplete = true;
        }
    }

    private void remember(ProcessHandle handle) {
        Instant start = handle.info().startInstant().orElse(null);
        Identity previous = observed.get(handle.pid());
        if (previous != null) {
            // A PID can be recycled within a long Job. A newly observed owned
            // process replaces the dead identity, never an unrelated PID lookup.
            if (start != null && !start.equals(previous.start)) observed.put(handle.pid(), new Identity(handle, start));
            return;
        }
        if (observed.size() >= MAX_PROCESSES) { incomplete = true; return; }
        observed.put(handle.pid(), new Identity(handle, start));
    }

    synchronized boolean stopped() {
        return !incomplete && observedStopped();
    }

    synchronized boolean observedStopped() {
        try {return !root.isAlive() && observed.values().stream().noneMatch(Identity::alive);}
        catch(RuntimeException unavailable){incomplete=true;return false;}
    }

    boolean verified() { return !incomplete; }

    synchronized List<ProcessIdentity> identities() {
        return observed.values().stream().map(identity -> new ProcessIdentity(identity.handle.pid(), identity.start)).toList();
    }

    void unverified() { incomplete = true; }

    void stop(long gracefulMillis) throws InterruptedException {
        observe();
        signal(false);
        if (!await(gracefulMillis)) {
            // Do not take a new root-only snapshot here: the parent may already
            // have exited, hiding a child that ignored the graceful signal.
            observe();
            force();
            await(Math.max(1000, Math.min(gracefulMillis, 30_000)));
        }
    }

    void force() { signal(true); }

    private synchronized void signal(boolean force) {
        List<Identity> targets = new ArrayList<>(observed.values());
        java.util.Collections.reverse(targets);
        for (Identity target : targets) {
            if ((target.handle.pid() == root.pid() && root.isAlive()) || !target.alive() || !target.canSignal()) continue;
            try { if (force) target.handle.destroyForcibly(); else target.handle.destroy(); }
            catch (RuntimeException unavailable) { incomplete = true; }
        }
        try { if (root.isAlive()) { if (force) root.destroyForcibly(); else root.destroy(); } }
        catch (RuntimeException unavailable) { incomplete = true; }
    }

    private boolean await(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!observedStopped()) {
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(10);
        }
        return true;
    }

    void whenStopped(Runnable action) {
        List<CompletableFuture<?>> exits = new ArrayList<>();
        synchronized (this) {
            if (incomplete) return; // An incomplete enumeration cannot authorize publication.
            // Descendant exit monitoring may allocate additional JVM process reapers.
            Thread caller=Thread.currentThread();ClassLoader previous=caller.getContextClassLoader();
            caller.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            try {
                exits.add(root.onExit());
                for (Identity identity : observed.values()) if (identity.alive()) exits.add(identity.handle.onExit());
            } finally {caller.setContextClassLoader(previous);}
        }
        // Completion may run synchronously; never call Job/store code under the fence monitor.
        CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new)).thenRun(action);
    }
}
