package att.exec;

import att.config.DbHelperConfig;

import java.sql.Connection;
import java.sql.SQLException;

/** Optional physical connection owner used by load/run-scoped resource layers. */
public interface DbConnectionProvider extends AutoCloseable {
    Connection open(DbHelperConfig config) throws Exception;
    /** Sanitized, aggregate lifecycle measurements; no query or connection data. */
    default void released(String helperId, long holdNanos) { }
    default void statementFinished(String helperId, long elapsedNanos) { }
    @Override void close();

    /** Provider-neutral classification for bounded pool acquisition timeout. */
    final class PoolTimeoutException extends SQLException {
        public PoolTimeoutException(String message, Throwable cause) { super(message, cause); }
    }
}
