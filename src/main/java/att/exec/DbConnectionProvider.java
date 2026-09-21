package att.exec;

import att.config.DbHelperConfig;

import java.sql.Connection;

/** Optional physical connection owner used by load/run-scoped resource layers. */
public interface DbConnectionProvider extends AutoCloseable {
    Connection open(DbHelperConfig config) throws Exception;
    @Override void close();
}
