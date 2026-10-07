package att.core;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Emits one concise cancellation record if the JVM shuts down during an interactive command. */
public final class ConsoleCancellationHook implements AutoCloseable {
    private final PrintStream output;
    private final String line;
    private final Runnable observer;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Thread hook;

    public ConsoleCancellationHook(PrintStream output, String line) {
        if (output == null) throw new IllegalArgumentException("Cancellation hook requires an output stream");
        this.output = output;
        this.line = line == null || line.trim().isEmpty() ? "[ATT] CANCELLED" : line;
        this.observer = null;
        this.hook = register();
    }

    public ConsoleCancellationHook(Runnable observer) {
        if (observer == null) throw new IllegalArgumentException("Cancellation observer is required");
        this.output = null; this.line = null; this.observer = observer;
        this.hook = register();
    }

    private Thread register() {
        Thread candidate = new Thread(new Runnable() {
            @Override public void run() {
                if (active.compareAndSet(true, false)) emit();
            }
        }, "att-console-cancellation");
        Thread registered = null;
        try {
            Runtime.getRuntime().addShutdownHook(candidate);
            registered = candidate;
        } catch (IllegalStateException ignored) {
            // Shutdown is already in progress, so there is no opportunity to register.
        } catch (SecurityException ignored) {
            // Cancellation reporting is best-effort and must not change execution semantics.
        }
        return registered;
    }

    @Override public void close() {
        if (!active.compareAndSet(true, false)) return;
        if (hook == null) return;
        try { Runtime.getRuntime().removeShutdownHook(hook); }
        catch (IllegalStateException ignored) { /* Shutdown has started. */ }
        catch (SecurityException ignored) { /* The normal completion path is already taken. */ }
    }

    private void emit() {
        if (observer != null) { observer.run(); return; }
        synchronized (output) {
            output.println(line);
            output.flush();
        }
    }
}
