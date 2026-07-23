/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.DbHelperConfig;
import att.config.FrameworkConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.ValidationResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** First-class V2.5 JDBC executor with one connection per dbhelper instance and execution thread. */
public final class DbHelperExecutor implements AutoCloseable {
    private final Path projectRoot;
    private final Map<String, DbHelperConfig> helpers;
    private final ThreadLocal<Scope> scopes = new ThreadLocal<Scope>();

    public DbHelperExecutor(Path projectRoot, FrameworkConfig config) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.helpers = config == null ? Collections.<String, DbHelperConfig>emptyMap() : config.dbHelpers();
    }

    public DbHelperConfig helper(String id) {
        if (id == null) return null;
        for (Map.Entry<String, DbHelperConfig> entry : helpers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(id)) return entry.getValue();
        }
        return null;
    }

    public boolean hasCached(String instance, String key) {
        ManagedConnection managed = scope().connections.get(canonicalId(instance));
        return managed != null && managed.hasCached(key);
    }

    public Object cached(String instance, String key) {
        ManagedConnection managed = scope().connections.get(canonicalId(instance));
        return managed == null ? null : managed.cache.get(key);
    }

    public void cache(String instance, String key, Object value) {
        DbHelperConfig config = helper(instance);
        if (config == null) throw new IllegalArgumentException("Unknown dbhelper instance: " + instance);
        Scope scope = scope();
        ManagedConnection managed = scope.connections.get(config.id());
        if (managed == null) { managed = new ManagedConnection(config); scope.connections.put(config.id(), managed); }
        managed.cache.put(key, value);
    }

    private String canonicalId(String instance) {
        DbHelperConfig config = helper(instance);
        return config == null ? instance : config.id();
    }

    public Path resolveSqlFile(String configured) throws Exception {
        Path relative = att.core.IdentifierValidator.relativePath(configured, "DB sqlFile");
        Path logical = projectRoot.resolve(relative).normalize();
        Path canonicalRoot = projectRoot.toRealPath();
        Path file = logical.toRealPath();
        if (!file.startsWith(canonicalRoot) || Files.isSymbolicLink(logical) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("DB sqlFile must be a package-contained regular non-symlink file: " + configured);
        }
        return file;
    }

    /** Isolates a new Case from every non-auto-commit connection previously used on this thread. */
    public void beginCase() {
        Scope scope = scope();
        for (ManagedConnection managed : scope.connections.values()) managed.beginCase();
    }

    public DbInvocationResult execute(String instance, String operation, String sql, String source,
                                      List<?> params, String invocationId) {
        DbHelperConfig config = helper(instance);
        if (config == null) throw new IllegalArgumentException("Unknown dbhelper instance: " + instance);
        if (!("query".equals(operation) || "update".equals(operation))) {
            throw new IllegalArgumentException("DB operation must be query or update: " + operation);
        }
        if (sql == null || sql.trim().isEmpty()) throw new IllegalArgumentException("DB SQL must not be blank");
        if ("update".equals(operation) && config.readOnly()) {
            throw new IllegalArgumentException("Dbhelper '" + config.id() + "' is readOnly and cannot execute update Actions");
        }
        List<?> values = params == null ? Collections.emptyList() : params;
        Scope scope = scope();
        ManagedConnection managed = scope.connections.get(config.id());
        if (managed == null) {
            managed = new ManagedConnection(config);
            scope.connections.put(config.id(), managed);
        }
        managed.usedInCase = true;
        Instant started = Instant.now();
        Map<String, Object> result;
        if (managed.rollbackOnly) {
            result = failure(operation, "ROLLBACK_ONLY",
                    "Transaction is rollback-only after an earlier database failure", null, managed);
        } else {
            try {
                result = executeStatement(managed, operation, sql, values);
            } catch (DbFailure failure) {
                managed.failed();
                result = failure(operation, failure.type, safeMessage(failure.cause, config),
                        failure.cause instanceof SQLException ? (SQLException) failure.cause : null, managed);
            } catch (Exception error) {
                managed.failed();
                result = failure(operation, "SQL_ERROR", safeMessage(error, config),
                        error instanceof SQLException ? (SQLException) error : null, managed);
            }
        }
        Map<String, Object> evidence = evidence(config, invocationId, operation, source, sql, values,
                result, Duration.between(started, Instant.now()).toMillis());
        return new DbInvocationResult(result, evidence);
    }

    public List<ValidationResult> finishCase(CaseRuntimeContext context, CaseExecutionLog log) {
        Scope scope = scopes.get();
        if (scope == null) return Collections.emptyList();
        List<ValidationResult> failures = new ArrayList<ValidationResult>();
        Map<String, Object> outcomes = new LinkedHashMap<String, Object>();
        try {
            for (ManagedConnection managed : scope.connections.values()) {
                if (!managed.usedInCase) continue;
                Map<String, Object> outcome = managed.finishCase();
                outcomes.put(managed.config.id(), outcome);
                if (!Boolean.TRUE.equals(outcome.get("success"))) {
                    failures.add(new ValidationResult("db", managed.config.id(), ResultStatus.ERROR,
                            "transaction finalization succeeds", String.valueOf(outcome.get("state")),
                            String.valueOf(outcome.get("error"))));
                }
            }
            context.put("CASE.DB", outcomes);
            if (!outcomes.isEmpty()) {
                try { log.append("DB FINALIZE", outcomes); } catch (Exception ignored) { }
            }
            return failures;
        } finally {
            for (ManagedConnection managed : scope.connections.values()) managed.usedInCase = false;
        }
    }

    public void abortCase() {
        Scope scope = scopes.get();
        if (scope == null) return;
        for (ManagedConnection managed : scope.connections.values()) managed.abortCase();
    }

    @Override public void close() {
        Scope scope = scopes.get();
        if (scope != null) for (ManagedConnection managed : scope.connections.values()) managed.close();
        scopes.remove();
    }

    private Scope scope() {
        Scope scope = scopes.get();
        if (scope == null) { scope = new Scope(); scopes.set(scope); }
        return scope;
    }

    private Map<String, Object> executeStatement(ManagedConnection managed, String operation,
                                                 String sql, List<?> params) throws DbFailure {
        Connection connection;
        try { connection = managed.connection(); }
        catch (Exception error) { throw new DbFailure("CONNECTION_ERROR", error); }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(managed.config.timeoutSeconds());
            if ("query".equals(operation)) statement.setMaxRows(managed.config.maxRows() + 1);
            try {
                for (int index = 0; index < params.size(); index++) statement.setObject(index + 1, params.get(index));
            } catch (SQLException error) { throw new DbFailure("BIND_ERROR", error); }
            Map<String, Object> result;
            try {
                if ("query".equals(operation)) {
                    try (ResultSet rows = statement.executeQuery()) { result = queryResult(rows, managed); }
                } else {
                    int affected = statement.executeUpdate();
                    result = success("update", 0, Collections.emptyList(), Integer.valueOf(affected), managed);
                }
            } catch (SQLTimeoutException error) { throw new DbFailure("TIMEOUT", error); }
            catch (LimitException error) { throw new DbFailure("LIMIT_EXCEEDED", error); }
            catch (SQLException error) { throw new DbFailure("SQL_ERROR", error); }
            try { managed.afterSuccess(); }
            catch (SQLException error) { throw new DbFailure("SQL_ERROR", error); }
            updateTransactionState(result, managed);
            return result;
        } catch (DbFailure failure) {
            throw failure;
        } catch (SQLTimeoutException error) {
            throw new DbFailure("TIMEOUT", error);
        } catch (SQLException error) {
            throw new DbFailure("SQL_ERROR", error);
        }
    }

    private Map<String, Object> queryResult(ResultSet resultSet, ManagedConnection managed) throws SQLException {
        DbHelperConfig config = managed.config;
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<String> labels = new ArrayList<String>();
        Set<String> unique = new LinkedHashSet<String>();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            String label = metadata.getColumnLabel(column);
            if (label == null || label.isEmpty()) label = metadata.getColumnName(column);
            if (!unique.add(label)) throw new SQLException("Duplicate result column label '" + label + "'; add explicit SQL aliases");
            labels.add(label);
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        while (resultSet.next()) {
            if (rows.size() >= config.maxRows()) throw new LimitException("Query exceeded maxRows=" + config.maxRows());
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (int column = 1; column <= labels.size(); column++) {
                try { row.put(labels.get(column - 1), normalize(resultSet.getObject(column), config)); }
                catch (LimitException error) { throw error; }
                catch (Exception error) { throw new SQLException("Unable to convert column '" + labels.get(column - 1) + "'", error); }
            }
            rows.add(row);
            if (jsonBytes(rows) > config.maxBytes()) throw new LimitException("Query exceeded maxBytes=" + config.maxBytes());
        }
        return success("query", rows.size(), rows, null, managed);
    }

    private Object normalize(Object value, DbHelperConfig config) throws Exception {
        if (value == null || value instanceof Boolean || value instanceof Number || value instanceof String) return checked(value, config);
        if (value instanceof byte[]) return checked(Base64.getEncoder().encodeToString((byte[]) value), config);
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            if (blob.length() > config.maxCellBytes()) throw new LimitException("BLOB exceeded maxCellBytes=" + config.maxCellBytes());
            try (InputStream input = blob.getBinaryStream()) { return Base64.getEncoder().encodeToString(read(input, config.maxCellBytes())); }
        }
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            if (clob.length() > config.maxCellBytes()) throw new LimitException("CLOB exceeded maxCellBytes=" + config.maxCellBytes());
            try (Reader reader = clob.getCharacterStream()) { return checked(read(reader, config.maxCellBytes()), config); }
        }
        if (value instanceof java.sql.Date || value instanceof java.sql.Time || value instanceof java.sql.Timestamp
                || value instanceof java.time.temporal.TemporalAccessor) return checked(String.valueOf(value), config);
        if (value instanceof BigDecimal) return value;
        return checked(String.valueOf(value), config);
    }

    private Object checked(Object value, DbHelperConfig config) {
        if (value instanceof String && ((String) value).getBytes(StandardCharsets.UTF_8).length > config.maxCellBytes()) {
            throw new LimitException("Cell exceeded maxCellBytes=" + config.maxCellBytes());
        }
        return value;
    }

    private byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > limit) throw new LimitException("Binary cell exceeded maxCellBytes=" + limit);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String read(Reader reader, int limit) throws Exception {
        StringBuilder output = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            output.append(buffer, 0, read);
            if (output.toString().getBytes(StandardCharsets.UTF_8).length > limit) {
                throw new LimitException("Text cell exceeded maxCellBytes=" + limit);
            }
        }
        return output.toString();
    }

    private int jsonBytes(Object value) {
        try { return att.validation.JsonSupport.write(value).getBytes(StandardCharsets.UTF_8).length; }
        catch (Exception error) { throw new LimitException("Unable to measure DB result: " + error.getMessage()); }
    }

    private Map<String, Object> success(String operation, int rowCount, List<?> rows, Integer affectedRows,
                                        ManagedConnection managed) {
        Map<String, Object> result = base(true, operation, managed);
        result.put("rowCount", rowCount);
        result.put("rows", rows);
        result.put("affectedRows", affectedRows);
        result.put("error", null);
        return result;
    }

    private Map<String, Object> failure(String operation, String type, String message, SQLException sql,
                                        ManagedConnection managed) {
        Map<String, Object> result = base(false, operation, managed);
        result.put("rowCount", 0);
        result.put("rows", Collections.emptyList());
        result.put("affectedRows", null);
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("type", type);
        error.put("message", message);
        error.put("sqlState", sql == null ? null : sql.getSQLState());
        error.put("vendorCode", sql == null ? 0 : sql.getErrorCode());
        result.put("error", error);
        return result;
    }

    private Map<String, Object> base(boolean success, String operation, ManagedConnection managed) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", success);
        result.put("operation", operation);
        Map<String, Object> transaction = new LinkedHashMap<String, Object>();
        transaction.put("scope", managed.config.transactionScope());
        transaction.put("onEnd", managed.config.transactionOnEnd());
        transaction.put("state", managed.state());
        result.put("transaction", transaction);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void updateTransactionState(Map<String, Object> result, ManagedConnection managed) {
        ((Map<String, Object>) result.get("transaction")).put("state", managed.state());
    }

    private Map<String, Object> evidence(DbHelperConfig config, String invocationId, String operation,
                                         String source, String sql, List<?> params, Map<String, Object> result,
                                         long durationMs) {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("id", invocationId);
        evidence.put("type", "db");
        evidence.put("db", config.id());
        evidence.put("operation", operation);
        evidence.put("status", Boolean.TRUE.equals(result.get("success")) ? "PASS" : "ERROR");
        evidence.put("durationMs", durationMs);
        evidence.put("source", source == null ? "inline" : source);
        evidence.put("sql", "hash".equals(config.evidenceSql()) ? sha256(sql) : sql);
        evidence.put("sqlEvidence", config.evidenceSql());
        evidence.put("parameters", parameterEvidence(params, config.evidenceParameters()));
        evidence.put("parameterEvidence", config.evidenceParameters());
        evidence.put("timeoutSeconds", config.timeoutSeconds());
        evidence.put("result", result);
        return evidence;
    }

    private Object parameterEvidence(List<?> params, String mode) {
        List<Object> evidence = new ArrayList<Object>();
        for (Object value : params) {
            if ("values".equals(mode)) evidence.add(value);
            else if ("types".equals(mode)) evidence.add(value == null ? "null" : value.getClass().getName());
            else evidence.add("***");
        }
        return evidence;
    }

    private String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private String safeMessage(Throwable error, DbHelperConfig config) {
        String message = error == null ? "Database operation failed" : error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        if (!config.password().isEmpty()) message = message.replace(config.password(), "<redacted>");
        if (!config.url().isEmpty()) message = message.replace(config.url(), "<jdbc-url>");
        for (Map.Entry<String, String> property : config.properties().entrySet()) {
            String key = property.getKey().toLowerCase(Locale.ROOT);
            if ((key.contains("password") || key.contains("secret") || key.contains("token"))
                    && !property.getValue().isEmpty()) message = message.replace(property.getValue(), "<redacted>");
        }
        return message;
    }

    private static final class Scope {
        final Map<String, ManagedConnection> connections = new LinkedHashMap<String, ManagedConnection>();
    }

    private final class ManagedConnection {
        final DbHelperConfig config;
        Connection connection;
        boolean rollbackOnly;
        boolean usedInCase;
        final Map<String, Object> cache = new LinkedHashMap<String, Object>();

        ManagedConnection(DbHelperConfig config) { this.config = config; }

        void beginCase() {
            rollbackOnly = false;
            usedInCase = false;
            if (connection == null) return;
            try {
                if (connection.isClosed()) { connection = null; return; }
                if (!connection.getAutoCommit()) connection.rollback();
            } catch (Exception isolationFailure) {
                close();
                try { connection(); } catch (Exception reconnectFailure) { close(); }
            }
        }

        Connection connection() throws Exception {
            if (connection != null && !connection.isClosed()) return connection;
            if (!config.driverClass().isEmpty()) Class.forName(config.driverClass());
            Properties properties = new Properties();
            properties.putAll(config.properties());
            if (!config.username().isEmpty()) properties.setProperty("user", config.username());
            if (!config.password().isEmpty()) properties.setProperty("password", config.password());
            connection = DriverManager.getConnection(config.url(), properties);
            boolean autoCommit = "statement".equals(config.transactionScope()) && "commit".equals(config.transactionOnEnd());
            connection.setAutoCommit(autoCommit);
            connection.setReadOnly(config.readOnly());
            int isolation = isolation(config.isolation());
            if (isolation != -1) connection.setTransactionIsolation(isolation);
            return connection;
        }

        void afterSuccess() throws SQLException {
            if ("statement".equals(config.transactionScope()) && "rollback".equals(config.transactionOnEnd())) {
                connection.rollback();
            }
        }

        void failed() {
            if ("case".equals(config.transactionScope())) rollbackOnly = true;
            else if (connection != null && "rollback".equals(config.transactionOnEnd())) {
                try { connection.rollback(); } catch (Exception rollbackFailure) { close(); }
            }
        }

        Map<String, Object> finishCase() {
            Map<String, Object> outcome = new LinkedHashMap<String, Object>();
            outcome.put("scope", config.transactionScope());
            outcome.put("onEnd", config.transactionOnEnd());
            outcome.put("success", true);
            outcome.put("error", null);
            try {
                if (connection == null || connection.isClosed()) {
                    outcome.put("state", "NOT_OPENED");
                } else if (connection.getAutoCommit()) {
                    outcome.put("state", "AUTO_COMMITTED");
                } else if ("statement".equals(config.transactionScope())) {
                    outcome.put("state", "ROLLED_BACK");
                } else if (rollbackOnly || "rollback".equals(config.transactionOnEnd())) {
                    connection.rollback();
                    outcome.put("state", "ROLLED_BACK");
                } else {
                    connection.commit();
                    outcome.put("state", "COMMITTED");
                }
            } catch (Exception error) {
                outcome.put("success", false);
                outcome.put("state", "FINALIZE_ERROR");
                Map<String, Object> detail = new LinkedHashMap<String, Object>();
                detail.put("type", "FINALIZE_ERROR");
                detail.put("message", safeMessage(error, config));
                if (error instanceof SQLException) {
                    detail.put("sqlState", ((SQLException) error).getSQLState());
                    detail.put("vendorCode", ((SQLException) error).getErrorCode());
                }
                outcome.put("error", detail);
                close();
            } finally {
                rollbackOnly = false;
            }
            return outcome;
        }

        void abortCase() {
            try { if (connection != null && !connection.isClosed() && !connection.getAutoCommit()) connection.rollback(); }
            catch (Exception error) { close(); }
            rollbackOnly = false;
            usedInCase = false;
        }

        String state() {
            if (rollbackOnly) return "ROLLBACK_ONLY";
            if ("statement".equals(config.transactionScope())) {
                return "commit".equals(config.transactionOnEnd()) ? "AUTO_COMMITTED" : "ROLLED_BACK";
            }
            return "commit".equals(config.transactionOnEnd()) ? "PENDING_COMMIT" : "PENDING_ROLLBACK";
        }

        void close() {
            if (connection != null) try { connection.close(); } catch (Exception ignored) { }
            connection = null;
        }

        boolean hasCached(String key) {
            return cache.containsKey(key);
        }
    }

    private int isolation(String value) {
        if ("readUncommitted".equals(value)) return Connection.TRANSACTION_READ_UNCOMMITTED;
        if ("readCommitted".equals(value)) return Connection.TRANSACTION_READ_COMMITTED;
        if ("repeatableRead".equals(value)) return Connection.TRANSACTION_REPEATABLE_READ;
        if ("serializable".equals(value)) return Connection.TRANSACTION_SERIALIZABLE;
        return -1;
    }

    private static final class DbFailure extends Exception {
        final String type;
        final Throwable cause;
        DbFailure(String type, Throwable cause) { super(cause); this.type = type; this.cause = cause; }
    }

    private static final class LimitException extends RuntimeException {
        LimitException(String message) { super(message); }
    }
}
