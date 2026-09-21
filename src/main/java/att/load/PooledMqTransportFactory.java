package att.load;

import att.config.MqHelperConfig;
import att.exec.MqTransport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Adapts the invocation-scoped MQ transport API to run-scoped bounded physical connections. */
public final class PooledMqTransportFactory implements MqTransport.Factory, AutoCloseable {
    private final MqTransport.Factory delegate; private final int maxSize; private final long borrowTimeoutMs;
    private final Map<String, LoadResourcePool<MqTransport.Connection>> pools = new ConcurrentHashMap<String, LoadResourcePool<MqTransport.Connection>>();
    public PooledMqTransportFactory(MqTransport.Factory delegate, int maxSize, long borrowTimeoutMs) { this.delegate = delegate; this.maxSize = maxSize; this.borrowTimeoutMs = borrowTimeoutMs; }
    @Override public MqTransport.Connection connect(final MqHelperConfig config) throws Exception {
        final LoadResourcePool<MqTransport.Connection> pool = pools.computeIfAbsent(config.id(), key -> new LoadResourcePool<MqTransport.Connection>(config.poolMaxSize(), config.poolBorrowTimeoutMs(), () -> {
            try { return delegate.connect(config); } catch (Exception error) { throw new PoolFactoryException(error); }
        }, connection -> { try { connection.disconnect(); } catch (Exception ignored) { } }));
        final LoadResourcePool<MqTransport.Connection>.Lease lease;
        try { lease = pool.borrow(); } catch (PoolFactoryException wrapped) { throw wrapped.cause; } catch (LoadResourcePool.PoolTimeoutException timeout) { throw new MqTransport.Exception("MQ_POOL_TIMEOUT", null, null, "MQ_POOL_TIMEOUT", timeout); }
        return new PooledConnection(lease);
    }
    public LoadResourcePool<MqTransport.Connection> pool(String id) { return pools.get(id); }
    @Override public void close() { for (LoadResourcePool<MqTransport.Connection> pool : pools.values()) pool.close(); pools.clear(); }
    private static final class PoolFactoryException extends RuntimeException { private final Exception cause; PoolFactoryException(Exception cause) { this.cause = cause; } }
    private static final class PooledConnection implements MqTransport.Connection {
        private final LoadResourcePool<MqTransport.Connection>.Lease lease; private boolean broken;
        PooledConnection(LoadResourcePool<MqTransport.Connection>.Lease lease) { this.lease = lease; }
        @Override public MqTransport.Queue open(String queue, boolean input, boolean output) throws Exception { try { return new PooledQueue(lease.value().open(queue, input, output), lease); } catch (Exception error) { broken = true; lease.invalidate(); throw error; } }
        @Override public void disconnect() { if (broken) lease.invalidate(); else lease.close(); }
        @Override public void close() { disconnect(); }
    }
    private static final class PooledQueue implements MqTransport.Queue {
        private final MqTransport.Queue delegate; private final LoadResourcePool<MqTransport.Connection>.Lease lease; PooledQueue(MqTransport.Queue delegate, LoadResourcePool<MqTransport.Connection>.Lease lease) { this.delegate = delegate; this.lease = lease; }
        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) throws Exception { try { return delegate.put(payload, request); } catch (Exception error) { lease.invalidate(); throw error; } }
        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception { try { return delegate.get(request); } catch (Exception error) { lease.invalidate(); throw error; } }
        @Override public void close() throws Exception { delegate.close(); }
    }
}
