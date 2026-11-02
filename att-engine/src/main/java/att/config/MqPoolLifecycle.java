package att.config;

/** Optional MQ pool idle-health settings. Defaults add no network operation on borrow. */
public final class MqPoolLifecycle {
    public static final MqPoolLifecycle DISABLED = new MqPoolLifecycle(false, 60_000L, 200L, 0L, 0L, 0);

    private final boolean staleIdleValidation;
    private final long staleIdleThresholdMs;
    private final long validationTimeoutMs;
    private final long maxIdleMs;
    private final long maxLifetimeMs;
    private final int heartbeatIntervalSeconds;

    public MqPoolLifecycle(boolean staleIdleValidation, long staleIdleThresholdMs, long validationTimeoutMs,
                           long maxIdleMs, long maxLifetimeMs, int heartbeatIntervalSeconds) {
        if (staleIdleThresholdMs < 0L || maxIdleMs < 0L || maxLifetimeMs < 0L)
            throw new IllegalArgumentException("MQ pool lifecycle durations must not be negative");
        if (validationTimeoutMs < 1L) throw new IllegalArgumentException("MQ pool validation timeout must be positive");
        if (heartbeatIntervalSeconds < 0) throw new IllegalArgumentException("MQ heartbeat interval must not be negative");
        this.staleIdleValidation = staleIdleValidation;
        this.staleIdleThresholdMs = staleIdleThresholdMs;
        this.validationTimeoutMs = validationTimeoutMs;
        this.maxIdleMs = maxIdleMs;
        this.maxLifetimeMs = maxLifetimeMs;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public boolean staleIdleValidation() { return staleIdleValidation; }
    public long staleIdleThresholdMs() { return staleIdleThresholdMs; }
    public long validationTimeoutMs() { return validationTimeoutMs; }
    public long maxIdleMs() { return maxIdleMs; }
    public long maxLifetimeMs() { return maxLifetimeMs; }
    /** Zero leaves the channel HBINT unchanged and does not set a client heartbeat property. */
    public int heartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public boolean active() { return staleIdleValidation || maxIdleMs > 0L || maxLifetimeMs > 0L; }
}
