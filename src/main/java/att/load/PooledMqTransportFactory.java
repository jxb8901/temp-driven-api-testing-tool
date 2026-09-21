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
    private final Map<String, LoadResourcePool<MqTransport.Connection>> pools = new ConcurrentHashMap<String, LoadResourcePool<MqTransport.Connection>>();

    public PooledMqTransportFactory(MqTransport.Factory delegate, int maxSize, long borrowTimeoutMs) {
        if (delegate == null) throw new IllegalArgumentException("MQ transport factory is required");
        if (maxSize < 1 || borrowTimeoutMs < 0L) throw new IllegalArgumentException("Invalid MQ pool defaults");
        this.delegate = delegate;
        this.defaultMaxSize = maxSize;
        this.defaultBorrowTimeoutMs = borrowTimeoutMs;
    }

    @Override public MqTransport.Connection connect(final MqHelperConfig config) throws Exception {
        if (config == null) throw new IllegalArgumentException("MQ helper configuration is required");
        final LoadResourcePool<MqTransport.Connection> pool = poolFor(config);
        final LoadResourcePool<MqTransport.Connection>.Lease lease;
        try {
            lease = pool.borrow();
        } catch (LoadResourcePool.PoolTimeoutException timeout) {
            throw MqTransport.Exception.poolTimeout(timeout);
        }
        return new PooledConnection(lease);
    }

    public LoadResourcePool<MqTransport.Connection> pool(String id) { return pools.get(id); }

    public Map<String, Map<String, Object>> metrics() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, LoadResourcePool<MqTransport.Connection>> entry : pools.entrySet()) {
            LoadResourcePool<MqTransport.Connection> pool = entry.getValue();
            Map<String, Object> values = new LinkedHashMap<String, Object>(pool.metrics());
            values.put("helperId", entry.getKey());
            result.put(entry.getKey(), values);
        }
        return result;
    }

    private synchronized LoadResourcePool<MqTransport.Connection> poolFor(final MqHelperConfig config) throws Exception {
        LoadResourcePool<MqTransport.Connection> existing = pools.get(config.id());
        if (existing != null) return existing;
        int maxSize = config.poolMaxSize() < 1 ? defaultMaxSize : config.poolMaxSize();
        long borrowTimeoutMs = config.poolBorrowTimeoutMs() < 0L ? defaultBorrowTimeoutMs : config.poolBorrowTimeoutMs();
        LoadResourcePool<MqTransport.Connection> created = new LoadResourcePool<MqTransport.Connection>(maxSize, borrowTimeoutMs,
                () -> {
                    try { return delegate.connect(config); }
                    catch (Exception error) { throw new PoolFactoryException(error); }
                }, connection -> { try { connection.disconnect(); } catch (Exception ignored) { } });
        try {
            created.ensureMinIdle(config.poolMinIdle());
        } catch (PoolFactoryException wrapped) {
            created.close();
            throw wrapped.cause;
        } catch (RuntimeException error) {
            created.close();
            throw error;
        }
        pools.put(config.id(), created);
        return created;
    }

    @Override public synchronized void close() {
        for (LoadResourcePool<MqTransport.Connection> pool : pools.values()) pool.close();
        pools.clear();
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
            try { return new PooledQueue(lease.value().open(queue, input, output), lease); }
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
