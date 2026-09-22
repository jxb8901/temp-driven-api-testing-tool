package att.load;

import att.config.DbHelperConfig;
import att.exec.DbConnectionProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Run/load-scoped HikariCP owner for one ATT DB helper. */
public final class HikariDbPool implements AutoCloseable {
    private final String helperId;
    private final HikariDataSource dataSource;
    private final int maxSize;
    private final int minIdle;
    private final long connectionTimeoutMs;
    private final AtomicLong borrowCount = new AtomicLong();
    private final AtomicLong borrowTimeouts = new AtomicLong();
    private final AtomicLong borrowFailures = new AtomicLong();
    private final AtomicLong borrowWaitNanos = new AtomicLong();
    public HikariDbPool(DbHelperConfig config) {
        this.helperId = config.id();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("att-load-db-" + config.id());
        hikari.setJdbcUrl(config.url()); hikari.setUsername(config.username()); hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolMaxSize()); hikari.setMinimumIdle(config.poolMinIdle()); hikari.setConnectionTimeout(config.poolConnectionTimeoutMs());
        if (!config.driverClass().isEmpty()) hikari.setDriverClassName(config.driverClass());
        for (Map.Entry<String, String> entry : config.properties().entrySet()) hikari.addDataSourceProperty(entry.getKey(), entry.getValue());
        this.dataSource = new HikariDataSource(hikari);
        this.maxSize = config.poolMaxSize(); this.minIdle = config.poolMinIdle(); this.connectionTimeoutMs = config.poolConnectionTimeoutMs();
    }
    public String helperId() { return helperId; }
    public Connection borrow() throws SQLException {
        long started = System.nanoTime();
        try {
            Connection connection = dataSource.getConnection();
            borrowCount.incrementAndGet();
            return connection;
        } catch (SQLException error) {
            if (isPoolTimeout(error)) {
                borrowTimeouts.incrementAndGet();
                throw new DbConnectionProvider.PoolTimeoutException("DB_POOL_TIMEOUT", error);
            }
            borrowFailures.incrementAndGet();
            throw error;
        } finally {
            borrowWaitNanos.addAndGet(System.nanoTime() - started);
        }
    }
    public int active() { return dataSource.getHikariPoolMXBean() == null ? 0 : dataSource.getHikariPoolMXBean().getActiveConnections(); }
    public int idle() { return dataSource.getHikariPoolMXBean() == null ? 0 : dataSource.getHikariPoolMXBean().getIdleConnections(); }
    public int total() { return dataSource.getHikariPoolMXBean() == null ? 0 : dataSource.getHikariPoolMXBean().getTotalConnections(); }
    public int waiting() { return dataSource.getHikariPoolMXBean() == null ? 0 : dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(); }
    public long borrowCount() { return borrowCount.get(); }
    public long borrowTimeouts() { return borrowTimeouts.get(); }
    public long borrowFailures() { return borrowFailures.get(); }
    public long totalBorrowWaitMs() { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(borrowWaitNanos.get()); }
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("helperId", helperId); result.put("maxSize", maxSize); result.put("minIdle", minIdle);
        result.put("connectionTimeoutMs", connectionTimeoutMs); result.put("active", active()); result.put("idle", idle());
        result.put("total", total()); result.put("waiting", waiting()); result.put("borrowCount", borrowCount());
        result.put("borrowTimeouts", borrowTimeouts()); result.put("borrowFailures", borrowFailures());
        result.put("totalBorrowWaitMs", totalBorrowWaitMs());
        return result;
    }
    private boolean isPoolTimeout(SQLException error) {
        String message = error.getMessage();
        if (message == null) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("connection is not available") && normalized.contains("timed out");
    }
    @Override public void close() { dataSource.close(); }
}
