package att.load;

import att.config.DbHelperConfig;
import att.exec.DbConnectionProvider;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lazily creates one Hikari pool per DB helper within one load run. */
public final class HikariDbConnectionProvider implements DbConnectionProvider {
    private final Map<String, HikariDbPool> pools = new ConcurrentHashMap<String, HikariDbPool>();
    @Override public Connection open(DbHelperConfig config) throws Exception { return pool(config).borrow(); }
    @Override public void released(String helperId, long holdNanos) {
        HikariDbPool pool = pools.get(helperId);
        if (pool != null) pool.recordHold(holdNanos);
    }
    @Override public void statementFinished(String helperId, long elapsedNanos) {
        HikariDbPool pool = pools.get(helperId);
        if (pool != null) pool.recordStatement(elapsedNanos);
    }
    public HikariDbPool pool(DbHelperConfig config) {
        // Construct under per-key ownership: losing a putIfAbsent race used to
        // initialize and discard a second physical pool.
        return pools.computeIfAbsent(config.id(), ignored -> new HikariDbPool(config));
    }
    public HikariDbPool pool(DbHelperConfig config, long preparationConnectTimeoutMs) {
        return pools.computeIfAbsent(config.id(), ignored -> new HikariDbPool(config, preparationConnectTimeoutMs));
    }
    public Map<String, Map<String, Object>> metrics() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, HikariDbPool> entry : pools.entrySet()) result.put(entry.getKey(), entry.getValue().metrics());
        return result;
    }
    @Override public void close() { for (HikariDbPool pool : pools.values()) pool.close(); pools.clear(); }
}
