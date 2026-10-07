package att.core;

import java.time.Instant;

/** Immutable timestamp captured once for generating an outer execution identity and Context. */
public final class ExecutionIdentitySeed {
    private final Instant startedAt;

    public ExecutionIdentitySeed(Instant startedAt) {
        if (startedAt == null) throw new IllegalArgumentException("Execution identity start time is required");
        this.startedAt = startedAt;
    }

    public static ExecutionIdentitySeed now() { return new ExecutionIdentitySeed(Instant.now()); }
    public Instant startedAt() { return startedAt; }
}
