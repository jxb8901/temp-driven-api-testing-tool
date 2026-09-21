package att.load;

import att.config.DbHelperConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.util.Map;

/** Run/load-scoped HikariCP owner for one ATT DB helper. */
public final class HikariDbPool implements AutoCloseable {
    private final String helperId;
    private final HikariDataSource dataSource;
    public HikariDbPool(DbHelperConfig config) {
        this.helperId = config.id();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("att-load-db-" + config.id());
        hikari.setJdbcUrl(config.url()); hikari.setUsername(config.username()); hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolMaxSize()); hikari.setMinimumIdle(config.poolMinIdle()); hikari.setConnectionTimeout(config.poolConnectionTimeoutMs());
        if (!config.driverClass().isEmpty()) hikari.setDriverClassName(config.driverClass());
        for (Map.Entry<String, String> entry : config.properties().entrySet()) hikari.addDataSourceProperty(entry.getKey(), entry.getValue());
        this.dataSource = new HikariDataSource(hikari);
    }
    public String helperId() { return helperId; }
    public Connection borrow() throws java.sql.SQLException { return dataSource.getConnection(); }
    public int active() { return dataSource.getHikariPoolMXBean() == null ? 0 : dataSource.getHikariPoolMXBean().getActiveConnections(); }
    public int idle() { return dataSource.getHikariPoolMXBean() == null ? 0 : dataSource.getHikariPoolMXBean().getIdleConnections(); }
    public int total() { return dataSource.getHikariPoolMXBean() == null ? 0 : dataSource.getHikariPoolMXBean().getTotalConnections(); }
    public int waiting() { return dataSource.getHikariPoolMXBean() == null ? 0 : dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(); }
    @Override public void close() { dataSource.close(); }
}
