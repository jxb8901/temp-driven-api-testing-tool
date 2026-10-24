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
    private final AtomicLong holdNanos = new AtomicLong(), holdCount = new AtomicLong();
    private final AtomicLong statementNanos = new AtomicLong(), statementCount = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger activePeak = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger waitingPeak = new java.util.concurrent.atomic.AtomicInteger();
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
    /** Holds distinct leases concurrently, validates through JDBC, then returns
     * them all. This temporary floor never changes Hikari's configured minIdle. */
    public int prepareInitialReady() throws SQLException, InterruptedException {
        int target = Math.max(1, minIdle);
        java.util.List<Connection> held = new java.util.ArrayList<Connection>(target);
        try {
            for (int index = 0; index < target; index++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DB preparation cancelled");
                Connection connection = dataSource.getConnection();
                held.add(connection);
                int seconds = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (connectionTimeoutMs + 999L) / 1000L));
                if (!connection.isValid(seconds)) throw new SQLException("DB_PREPARATION_INVALID_CONNECTION");
            }
        } finally {
            SQLException failure = null;
            for (Connection connection : held) {
                try { connection.close(); } catch (SQLException error) { failure = error; }
            }
            if (failure != null) throw failure;
        }
        int ready = idle();
        if (ready < target) throw new SQLException("DB_PREPARATION_IDLE_FLOOR_UNAVAILABLE");
        return ready;
    }
    public Connection borrow() throws SQLException {
        long started = System.nanoTime();
        waitingPeak.accumulateAndGet(waiting(), Math::max);
        try {
            Connection connection = dataSource.getConnection();
            borrowCount.incrementAndGet();
            activePeak.accumulateAndGet(active(), Math::max);
            return connection;
        } catch (SQLException error) {
            if (isPoolTimeout(error)) {
                borrowTimeouts.incrementAndGet();
                throw new DbConnectionProvider.PoolTimeoutException("DB_POOL_TIMEOUT", error);
            }
            borrowFailures.incrementAndGet();
            throw error;
        } finally {
            waitingPeak.accumulateAndGet(waiting(), Math::max);
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
    void recordHold(long nanos) { holdNanos.addAndGet(Math.max(0, nanos)); holdCount.incrementAndGet(); }
    void recordStatement(long nanos) { statementNanos.addAndGet(Math.max(0, nanos)); statementCount.incrementAndGet(); }
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("helperId", helperId); result.put("maxSize", maxSize); result.put("minIdle", minIdle);
        result.put("connectionTimeoutMs", connectionTimeoutMs); result.put("active", active()); result.put("idle", idle());
        result.put("total", total()); result.put("waiting", waiting()); result.put("borrowCount", borrowCount());
        result.put("borrowTimeouts", borrowTimeouts()); result.put("borrowFailures", borrowFailures());
        result.put("totalBorrowWaitMs", totalBorrowWaitMs());
        result.put("totalConnectionHoldMs", holdNanos.get() / 1_000_000.0);
        result.put("releasedConnections", holdCount.get());
        result.put("totalStatementMs", statementNanos.get() / 1_000_000.0);
        result.put("statementCount", statementCount.get());
        result.put("activePeak", activePeak.get());
        result.put("observedWaitingPeak", waitingPeak.get());
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
