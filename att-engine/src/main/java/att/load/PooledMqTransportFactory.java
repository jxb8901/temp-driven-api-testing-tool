package att.load;

import att.config.MqHelperConfig;
import att.exec.MqTransport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Adapts the invocation-scoped MQ transport API to run-scoped bounded physical connections. */
public final class PooledMqTransportFactory implements MqTransport.Factory, AutoCloseable {
    private final MqTransport.Factory delegate;
    private final int defaultMaxSize;
    private final long defaultBorrowTimeoutMs;
    private final ConcurrentHashMap<String, PoolEntry> pools = new ConcurrentHashMap<String, PoolEntry>();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    private static final class PoolEntry {
        final LoadResourcePool<MqTransport.Connection> pool;
        final String logicalId;
        final java.util.concurrent.atomic.AtomicBoolean preparationConnect;
        final long preparationDeadlineNanos;
        PoolEntry(LoadResourcePool<MqTransport.Connection> pool, String logicalId,
                  java.util.concurrent.atomic.AtomicBoolean preparationConnect, long preparationDeadlineNanos) {
            this.pool = pool; this.logicalId = logicalId; this.preparationConnect = preparationConnect; this.preparationDeadlineNanos = preparationDeadlineNanos;
        }
    }

    public PooledMqTransportFactory(MqTransport.Factory delegate, int maxSize, long borrowTimeoutMs) {
        if (delegate == null) throw new IllegalArgumentException("MQ transport factory is required");
        if (maxSize < 1 || borrowTimeoutMs < 0L) throw new IllegalArgumentException("Invalid MQ pool defaults");
        this.delegate = delegate;
        this.defaultMaxSize = maxSize;
        this.defaultBorrowTimeoutMs = borrowTimeoutMs;
    }

    @Override public MqTransport.Connection connect(final MqHelperConfig config) throws Exception {
        if (config == null) throw new IllegalArgumentException("MQ helper configuration is required");
        final PoolEntry entry = poolFor(config, Long.MAX_VALUE, false);
        final LoadResourcePool<MqTransport.Connection> pool = entry.pool;
        final LoadResourcePool<MqTransport.Connection>.Lease lease;
        try {
            lease = pool.borrow();
        } catch (LoadResourcePool.PoolTimeoutException timeout) {
            throw MqTransport.Exception.poolTimeout(timeout);
        }
        return new PooledConnection(lease);
    }

    public LoadResourcePool<MqTransport.Connection> pool(String id) {
        PoolEntry entry = pools.get(id);
        return entry == null ? null : entry.pool;
    }

    /** Connect/authenticate only. No business queue is opened and no message is sent. */
    public int prepareInitialReady(MqHelperConfig config) throws Exception {
        return prepareInitialReady(config, Long.MAX_VALUE);
    }

    public int prepareInitialReady(MqHelperConfig config, long deadlineNanos) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("MQ preparation cancelled");
        boolean bounded = deadlineNanos != Long.MAX_VALUE;
        PoolEntry entry = poolFor(config, deadlineNanos, bounded);
        LoadResourcePool<MqTransport.Connection> pool = entry.pool;
        try {
            if (bounded) pool.ensureMinIdle(Math.max(1, config.poolMinIdle()), deadlineNanos);
            else pool.ensureMinIdle(Math.max(1, config.poolMinIdle()));
        }
        catch (PoolFactoryException wrapped) { throw wrapped.cause; }
        if (bounded && System.nanoTime() >= deadlineNanos)
            throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT");
        entry.preparationConnect.set(false);
        int ready = pool.idle();
        if (ready < Math.max(1, config.poolMinIdle()))
            throw new IllegalStateException("MQ_PREPARATION_IDLE_FLOOR_UNAVAILABLE");
        return ready;
    }

    public Map<String, Map<String, Object>> metrics() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, PoolEntry> entry : pools.entrySet()) {
            LoadResourcePool<MqTransport.Connection> pool = entry.getValue().pool;
            Map<String, Object> values = new LinkedHashMap<String, Object>(pool.metrics());
            values.put("helperId", entry.getKey());
            result.put(entry.getKey(), values);
        }
        return result;
    }

    private PoolEntry poolFor(final MqHelperConfig config, final long deadlineNanos, boolean bounded) throws Exception {
        if (closed.get()) throw new IllegalStateException("MQ resource factory is closed");
        final String poolKey = config.poolKey();
        PoolEntry existing = pools.get(poolKey);
        if (existing != null) return existing;
        int maxSize = config.poolMaxSize() < 1 ? defaultMaxSize : config.poolMaxSize();
        long borrowTimeoutMs = config.poolBorrowTimeoutMs() < 0L ? defaultBorrowTimeoutMs : config.poolBorrowTimeoutMs();
        final java.util.concurrent.atomic.AtomicBoolean preparationConnect = new java.util.concurrent.atomic.AtomicBoolean(bounded);
        LoadResourcePool<MqTransport.Connection> created = new LoadResourcePool<MqTransport.Connection>(maxSize, borrowTimeoutMs,
                () -> {
                    try {
                        if (preparationConnect.get()) {
                            long remaining = deadlineNanos - System.nanoTime();
                            if (remaining <= 0L) throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT");
                            long timeoutMs = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
                            return delegate.connect(config, timeoutMs);
                        }
                        return delegate.connect(config);
                    }
                    catch (Exception error) { throw new PoolFactoryException(error); }
                }, connection -> {
                    try { connection.disconnect(); }
                    catch (Exception failure) {
                        throw new IllegalStateException("MQ_CONNECTION_DISCONNECT_FAILED", failure);
                    }
                });
        PoolEntry candidate = new PoolEntry(created, diagnosticIdentity(config), preparationConnect, deadlineNanos);
        PoolEntry raced = pools.putIfAbsent(poolKey, candidate);
        if (raced != null) {
            created.close();
            return raced;
        }
        if (closed.get()) {
            pools.remove(poolKey, candidate);
            created.close();
            throw new IllegalStateException("MQ resource factory is closed");
        }
        return candidate;
    }

    private static String diagnosticIdentity(MqHelperConfig config) {
        String logical = config.logicalId();
        String instance = config.instanceId();
        if (logical == null || logical.trim().isEmpty()) return config.id();
        if (instance == null || instance.trim().isEmpty() || logical.equals(instance)) return logical;
        return logical + ":" + instance;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        for (PoolEntry entry : pools.values()) {
            try { entry.pool.close(); }
            catch (RuntimeException | Error problem) {
                LoadResourceTeardownException identified=new LoadResourceTeardownException("MQ",entry.logicalId,
                        Math.max(1,entry.pool.closeFailureCount()),problem);
                if (failure == null) failure = identified;
                else failure.addSuppressed(identified);
            }
        }
        pools.clear();
        if (failure != null) throw new IllegalStateException(
                "MQ_RESOURCE_TEARDOWN_FAILED", failure);
    }

    private static final class PoolFactoryException extends RuntimeException {
        private final Exception cause;
        PoolFactoryException(Exception cause) { this.cause = cause; }
    }

    private static final class PooledConnection implements MqTransport.Connection {
        private final LoadResourcePool<MqTransport.Connection>.Lease lease;
        private boolean broken;
        PooledConnection(LoadResourcePool<MqTransport.Connection>.Lease lease) { this.lease = lease; }

        @Override public MqTransport.Queue open(String queue, boolean input, boolean output) throws Exception {
            return open(queue, input, output, false);
        }

        @Override public MqTransport.Queue open(String queue, boolean input, boolean output, boolean bindNotFixed) throws Exception {
            try { return new PooledQueue(lease.value().open(queue, input, output, bindNotFixed), lease); }
            catch (MqTransport.Exception error) { invalidateIfBroken(error); throw error; }
            catch (Exception error) { broken = true; lease.invalidate(); throw error; }
        }

        @Override public void disconnect() {
            if (broken) lease.invalidate(); else lease.close();
        }

        @Override public void close() { disconnect(); }

        private void invalidateIfBroken(MqTransport.Exception error) {
            if (error.connectionFailure()) { broken = true; lease.invalidate(); }
        }
    }

    private static final class PooledQueue implements MqTransport.Queue {
        private final MqTransport.Queue delegate;
        private final LoadResourcePool<MqTransport.Connection>.Lease lease;
        PooledQueue(MqTransport.Queue delegate, LoadResourcePool<MqTransport.Connection>.Lease lease) {
            this.delegate = delegate; this.lease = lease;
        }

        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) throws Exception {
            try { return delegate.put(payload, request); }
            catch (MqTransport.Exception error) { if (error.connectionFailure()) lease.invalidate(); throw error; }
            catch (Exception error) { lease.invalidate(); throw error; }
        }

        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
            try { return delegate.get(request); }
            catch (MqTransport.Exception error) {
                if (error.connectionFailure()) lease.invalidate();
                throw error;
            } catch (Exception error) { lease.invalidate(); throw error; }
        }

        @Override public void close() throws Exception {
            try { delegate.close(); }
            catch (Exception error) { lease.invalidate(); throw error; }
        }

    }
}
