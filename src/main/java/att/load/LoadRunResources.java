package att.load;

import att.config.FrameworkConfig;
import att.exec.DbHelperExecutor;
import att.exec.MqHelperExecutor;
import att.exec.IbmMqClientFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run-scoped owner for resources shared by load iterations. */
public final class LoadRunResources implements AutoCloseable {
    private final FrameworkConfig config;
    private final DbHelperExecutor db;
    private final HikariDbConnectionProvider dbProvider;
    private final MqHelperExecutor mq;
    private final PooledMqTransportFactory mqFactory;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LoadRunResources(Path projectRoot, FrameworkConfig config) {
        this.config = config;
        this.dbProvider = new HikariDbConnectionProvider();
        this.db = new DbHelperExecutor(projectRoot, config, dbProvider);
        this.mqFactory = new PooledMqTransportFactory(new IbmMqClientFactory(), 20, 2000L);
        this.mq = new MqHelperExecutor(projectRoot, config, mqFactory);
    }

    public DbHelperExecutor db() { ensureOpen(); return db; }
    public MqHelperExecutor mq() { ensureOpen(); return mq; }
    public HikariDbPool dbPool(String helperId, att.config.FrameworkConfig config) {
        ensureOpen();
        att.config.DbHelperConfig helper = config.dbHelper(helperId);
        if (helper == null) throw new IllegalArgumentException("Unknown dbhelper instance: " + helperId);
        return dbProvider.pool(helper);
    }
    public HikariDbPool dbPool(String helperId) { return dbPool(helperId, config); }
    public LoadResourcePool<att.exec.MqTransport.Connection> mqPool(String helperId) {
        ensureOpen();
        LoadResourcePool<att.exec.MqTransport.Connection> pool = mqFactory.pool(helperId);
        if (pool == null) throw new IllegalArgumentException("Unknown or unopened mqhelper instance: " + helperId);
        return pool;
    }
    public Map<String, Object> metrics() {
        ensureOpen();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("db", dbProvider.metrics()); result.put("mq", mqFactory.metrics());
        return result;
    }
    public boolean isClosed() { return closed.get(); }
    public void ensureOpen() { if (closed.get()) throw new IllegalStateException("Load run resources are closed"); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) { mqFactory.close(); db.closeAll(); dbProvider.close(); }
    }
}
