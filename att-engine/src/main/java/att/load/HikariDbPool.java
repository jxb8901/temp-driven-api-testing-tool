package att.load;

import att.config.DbHelperConfig;
import att.exec.DbConnectionProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.DriverDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicLong;

/** Run/load-scoped HikariCP owner for one ATT DB helper. */
public final class HikariDbPool implements AutoCloseable {
    private final String helperId;
    private final HikariDataSource dataSource;
    private final PreparationDataSource connectionDataSource;
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
        this(config, 0L);
    }
    public HikariDbPool(DbHelperConfig config, long preparationConnectTimeoutMs) {
        this.helperId = config.id();
        Map<String, String> runtimeValues = new LinkedHashMap<String, String>(config.properties());
        Map<String, String> preparationValues = new LinkedHashMap<String, String>(runtimeValues);
        applyDriverConnectTimeout(config.url(), preparationValues, preparationConnectTimeoutMs);
        Properties runtimeProperties = new Properties();
        runtimeProperties.putAll(runtimeValues);
        Properties preparationProperties = new Properties();
        preparationProperties.putAll(preparationValues);
        String driverClass = config.driverClass().isEmpty() ? null : config.driverClass();
        DataSource runtimeDataSource = new DriverDataSource(config.url(), driverClass, runtimeProperties,
                config.username(), config.password());
        DataSource preparationDataSource = preparationConnectTimeoutMs > 0L
                ? new DriverDataSource(config.url(), driverClass, preparationProperties, config.username(), config.password())
                : runtimeDataSource;
        this.connectionDataSource = new PreparationDataSource(preparationDataSource, runtimeDataSource);
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("att-load-db-" + config.id());
        hikari.setDataSource(connectionDataSource); hikari.setUsername(config.username()); hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolMaxSize()); hikari.setMinimumIdle(config.poolMinIdle()); hikari.setConnectionTimeout(config.poolConnectionTimeoutMs());
        this.dataSource = new HikariDataSource(hikari);
        this.maxSize = config.poolMaxSize(); this.minIdle = config.poolMinIdle(); this.connectionTimeoutMs = config.poolConnectionTimeoutMs();
    }
    public String helperId() { return helperId; }
    /** Holds distinct leases concurrently, validates through JDBC, then returns
     * them all. This temporary floor never changes Hikari's configured minIdle. */
    public int prepareInitialReady() throws SQLException, InterruptedException {
        return prepareInitialReady(Long.MAX_VALUE);
    }
    public int prepareInitialReady(long deadlineNanos) throws SQLException, InterruptedException {
        int target = Math.max(1, minIdle);
        java.util.List<Connection> held = new java.util.ArrayList<Connection>(target);
        long originalTimeoutMs = dataSource.getHikariConfigMXBean().getConnectionTimeout();
        try {
            for (int index = 0; index < target; index++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DB preparation cancelled");
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) throw new SQLException("LOAD_PREPARATION_TIMEOUT");
                long remainingMs = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                long boundedTimeoutMs = Math.min(originalTimeoutMs, remainingMs);
                if (boundedTimeoutMs < 250L) throw new SQLException("LOAD_PREPARATION_TIMEOUT");
                dataSource.getHikariConfigMXBean().setConnectionTimeout(boundedTimeoutMs);
                Connection connection = dataSource.getConnection();
                held.add(connection);
                remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) throw new SQLException("LOAD_PREPARATION_TIMEOUT");
                long validationBudgetMs = Math.min(connectionTimeoutMs,
                        Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                int seconds = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (validationBudgetMs + 999L) / 1000L));
                if (!connection.isValid(seconds)) throw new SQLException("DB_PREPARATION_INVALID_CONNECTION");
            }
        } finally {
            try {
                SQLException failure = null;
                for (Connection connection : held) {
                    try { connection.close(); } catch (SQLException error) { failure = error; }
                }
                dataSource.getHikariConfigMXBean().setConnectionTimeout(originalTimeoutMs);
                if (failure != null) throw failure;
            } finally {
                connectionDataSource.useRuntimeProperties();
            }
        }
        int ready = idle();
        if (ready < target) throw new SQLException("DB_PREPARATION_IDLE_FLOOR_UNAVAILABLE");
        return ready;
    }
    private static void applyDriverConnectTimeout(String url, Map<String, String> properties, long timeoutCapMs) {
        if (url == null || timeoutCapMs <= 0L) return;
        String normalized = url.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("jdbc:postgresql:")) {
            long seconds = Math.max(1L, (timeoutCapMs + 999L) / 1000L);
            properties.put("connectTimeout", boundedNumber(properties.get("connectTimeout"), seconds));
            properties.put("loginTimeout", boundedNumber(properties.get("loginTimeout"), seconds));
        } else if (normalized.startsWith("jdbc:mysql:") || normalized.startsWith("jdbc:mariadb:")) {
            properties.put("connectTimeout", boundedNumber(properties.get("connectTimeout"), timeoutCapMs));
        } else if (normalized.startsWith("jdbc:oracle:")) {
            long seconds = Math.max(1L, (timeoutCapMs + 999L) / 1000L);
            properties.put("oracle.net.CONNECT_TIMEOUT", boundedNumber(properties.get("oracle.net.CONNECT_TIMEOUT"), timeoutCapMs));
            properties.put("oracle.net.OUTBOUND_CONNECT_TIMEOUT", boundedNumber(properties.get("oracle.net.OUTBOUND_CONNECT_TIMEOUT"), seconds));
            properties.put("oracle.jdbc.loginTimeout", boundedNumber(properties.get("oracle.jdbc.loginTimeout"), seconds));
        }
    }
    private static String boundedNumber(String configured, long cap) {
        if (configured != null) {
            try { cap = Math.min(cap, Long.parseLong(configured.trim())); }
            catch (NumberFormatException ignored) { /* use the bounded preparation setting */ }
        }
        return Long.toString(Math.max(1L, cap));
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

    /** Uses bounded driver settings only until the initial readiness barrier completes. */
    private static final class PreparationDataSource implements DataSource {
        private final DataSource preparation;
        private final DataSource runtime;
        private volatile DataSource active;

        private PreparationDataSource(DataSource preparation, DataSource runtime) {
            this.preparation = preparation;
            this.runtime = runtime;
            this.active = preparation;
        }

        private void useRuntimeProperties() { active = runtime; }
        @Override public Connection getConnection() throws SQLException { return active.getConnection(); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return active.getConnection(username, password);
        }
        @Override public PrintWriter getLogWriter() throws SQLException { return active.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) throws SQLException { preparation.setLogWriter(out); runtime.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { preparation.setLoginTimeout(seconds); runtime.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return active.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return active.getParentLogger(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            return active.unwrap(iface);
        }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || active.isWrapperFor(iface);
        }
    }
}
