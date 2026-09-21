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
    public HikariDbPool pool(DbHelperConfig config) { HikariDbPool existing = pools.get(config.id()); if (existing != null) return existing; HikariDbPool created = new HikariDbPool(config); HikariDbPool previous = pools.putIfAbsent(config.id(), created); if (previous != null) { created.close(); return previous; } return created; }
    public Map<String, Map<String, Object>> metrics() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, HikariDbPool> entry : pools.entrySet()) result.put(entry.getKey(), entry.getValue().metrics());
        return result;
    }
    @Override public void close() { for (HikariDbPool pool : pools.values()) pool.close(); pools.clear(); }
}
