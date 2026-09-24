/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.DbHelperConfig;
import att.config.FrameworkConfig;
import att.config.ProcessOutputConfig;
import att.config.ToolConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.TestCase;
import att.core.ValidationResult;
import att.template.StageTemplate;
import att.template.StageTemplateRunner;
import att.template.TemplateAction;
import att.template.UnifiedTemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end Action-runner coverage for direct DB timeout/retry semantics from issue #39. */
class DirectDbActionRetryRuntimeTest {
    @TempDir Path tempDir;

    @Test
    void retriesQueryAssertionAndPublishesWinningAttempt() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(Behavior.EMPTY_THEN_ROW);
        RunResult run = run(provider, queryAction("poll", 3, 20, "ASSERTION", null));

        assertEquals(ResultStatus.PASS, run.result.status());
        assertEquals(2, provider.executions);
        List<?> attempts = (List<?>) run.context.resolve("EXEC.ACTIONS.poll.output.attempts");
        assertEquals(2, attempts.size());
        assertEquals("ASSERTION", ((Map<?, ?>) attempts.get(0)).get("retryReason"));
        assertEquals("FAIL", ((Map<?, ?>) attempts.get(0)).get("status"));
        assertEquals(2, run.context.resolve("EXEC.ACTIONS.poll.output.winningAttempt"));
        assertEquals(1, run.context.resolve("EXEC.ACTIONS.poll.output.result.rowCount"));
        assertTrue(provider.elapsedMs >= 15L, "retry interval must occur between attempts");
    }

    @Test
    void retriesTimeoutAndAppliesShorterActionJdbcTimeoutPerAttempt() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(Behavior.TIMEOUT_THEN_ROW);
        RunResult run = run(provider, queryAction("poll", 2, 0, "TIMEOUT", 1500L));

        assertEquals(ResultStatus.PASS, run.result.status());
        assertEquals(2, provider.executions);
        assertEquals(Arrays.asList(2, 2), provider.queryTimeoutSeconds);
        List<?> attempts = (List<?>) run.context.resolve("EXEC.ACTIONS.poll.output.attempts");
        assertEquals("TIMEOUT", ((Map<?, ?>) attempts.get(0)).get("category"));
        assertEquals("TIMEOUT", ((Map<?, ?>) attempts.get(0)).get("retryReason"));
        assertEquals(2, run.context.resolve("EXEC.ACTIONS.poll.output.winningAttempt"));
    }

    @Test
    void assertionRetryExhaustionKeepsFinalAttemptAndResult() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(Behavior.ALWAYS_EMPTY);
        RunResult run = run(provider, queryAction("poll", 2, 0, "ASSERTION", null));

        assertEquals(ResultStatus.FAIL, run.result.status());
        assertEquals(2, provider.executions);
        assertEquals(2, run.context.resolve("EXEC.ACTIONS.poll.output.finalAttempt"));
        assertEquals(0, run.context.resolve("EXEC.ACTIONS.poll.output.result.rowCount"));
        assertEquals(2, ((List<?>) run.context.resolve("EXEC.ACTIONS.poll.output.attempts")).size());
    }

    @Test
    void genericSqlErrorIsTerminalEvenWhenRetryIsConfigured() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(Behavior.SQL_ERROR);
        RunResult run = run(provider, queryAction("poll", 3, 0, "TIMEOUT", 500L));

        assertEquals(ResultStatus.ERROR, run.result.status());
        assertEquals(1, provider.executions);
        assertEquals(1, run.context.resolve("EXEC.ACTIONS.poll.output.finalAttempt"));
        assertEquals("SQL_ERROR", run.context.resolve("EXEC.ACTIONS.poll.output.result.error.type"));
    }

    @Test
    void updateUsesActionTimeoutWithoutRetry() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(Behavior.UPDATE_SUCCESS);
        Map<String, Object> values = map(
                "type", "db", "db", "orders",
                "update", map("sql", "update orders set status='DONE'"),
                "timeoutMs", 400);
        RunResult run = run(provider, new TemplateAction("write", values, "att-template/v3.0"));

        assertEquals(ResultStatus.PASS, run.result.status());
        assertEquals(Collections.singletonList(1), provider.queryTimeoutSeconds);
        assertFalse(run.context.contains("EXEC.ACTIONS.write.output.attempts"));
        assertEquals(1, run.context.resolve("EXEC.ACTIONS.write.output.result.affectedRows"));
    }

    private TemplateAction queryAction(String id, int maxAttempts, int intervalMs, String retryOn, Long timeoutMs) {
        Map<String, Object> values = map(
                "type", "db", "db", "orders",
                "query", map("sql", "select status from orders"),
                "assert", "#{${output.result.rowCount} == 1}",
                "retry", map("maxAttempts", maxAttempts, "intervalMs", intervalMs,
                        "retryOn", Collections.singletonList(retryOn)));
        if (timeoutMs != null) values.put("timeoutMs", timeoutMs);
        return new TemplateAction(id, values, "att-template/v3.0");
    }

    private RunResult run(ScriptedProvider provider, TemplateAction action) throws Exception {
        DbHelperConfig helper = new DbHelperConfig("orders", "Orders", "Orders DB", "jdbc:scripted:orders",
                "", "", "", Collections.<String, String>emptyMap(), false, "driverDefault", 7,
                "statement", "commit", 10, 1024, 8192, "full", "masked", null);
        Map<String, DbHelperConfig> helpers = Collections.singletonMap("orders", helper);
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000,
                tempDir, tempDir, Collections.<String, ToolConfig>emptyMap(), helpers, null, null,
                null, "", "", null, null, 1, "ignore", "", false, ProcessOutputConfig.defaults());
        DbHelperExecutor executor = new DbHelperExecutor(tempDir, config, provider);
        TestCase testCase = new TestCase(2, "default", "Sheet1", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, tempDir, "run-1", tempDir, tempDir.resolve("case.log"));
        StageTemplate template = new StageTemplate("DB_RETRY", tempDir, Collections.singletonList(action), "att-template/v3.0");
        executor.beginCase();
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            long started = System.nanoTime();
            List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null, executor))
                    .execute("main", template, context, log);
            provider.elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertEquals(1, results.size());
            return new RunResult(results.get(0), context);
        } finally {
            executor.close();
        }
    }

    private enum Behavior { EMPTY_THEN_ROW, TIMEOUT_THEN_ROW, ALWAYS_EMPTY, SQL_ERROR, UPDATE_SUCCESS }

    private static final class ScriptedProvider implements DbConnectionProvider {
        private final Behavior behavior;
        private int executions;
        private long elapsedMs;
        private final java.util.List<Integer> queryTimeoutSeconds = new java.util.ArrayList<Integer>();

        private ScriptedProvider(Behavior behavior) { this.behavior = behavior; }

        @Override public Connection open(DbHelperConfig config) {
            return proxy(Connection.class, new InvocationHandler() {
                private boolean autoCommit;
                private boolean closed;
                @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("prepareStatement".equals(name)) return statement(String.valueOf(args[0]));
                    if ("setAutoCommit".equals(name)) { autoCommit = (Boolean) args[0]; return null; }
                    if ("getAutoCommit".equals(name)) return autoCommit;
                    if ("setReadOnly".equals(name) || "setTransactionIsolation".equals(name)) return null;
                    if ("isClosed".equals(name)) return closed;
                    if ("close".equals(name)) { closed = true; return null; }
                    if ("commit".equals(name) || "rollback".equals(name)) return null;
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private PreparedStatement statement(final String sql) {
            return proxy(PreparedStatement.class, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("setQueryTimeout".equals(name)) { queryTimeoutSeconds.add((Integer) args[0]); return null; }
                    if ("setMaxRows".equals(name) || "setObject".equals(name) || "cancel".equals(name) || "close".equals(name)) return null;
                    if ("executeQuery".equals(name)) {
                        executions++;
                        if (behavior == Behavior.SQL_ERROR) throw new SQLException("boom", "42000", 99);
                        if (behavior == Behavior.TIMEOUT_THEN_ROW && executions == 1) throw new SQLTimeoutException("timed out", "57014", 0);
                        boolean row = behavior == Behavior.EMPTY_THEN_ROW ? executions > 1
                                : behavior == Behavior.TIMEOUT_THEN_ROW || behavior == Behavior.UPDATE_SUCCESS;
                        if (behavior == Behavior.ALWAYS_EMPTY) row = false;
                        return rows(row);
                    }
                    if ("executeUpdate".equals(name)) { executions++; return 1; }
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private ResultSet rows(final boolean hasRow) {
            return proxy(ResultSet.class, new InvocationHandler() {
                private boolean consumed;
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("next".equals(name)) { if (hasRow && !consumed) { consumed = true; return true; } return false; }
                    if ("getObject".equals(name)) return "DONE";
                    if ("getMetaData".equals(name)) return metadata();
                    if ("close".equals(name)) return null;
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private ResultSetMetaData metadata() {
            return proxy(ResultSetMetaData.class, (proxy, method, args) -> {
                if ("getColumnCount".equals(method.getName())) return 1;
                if ("getColumnLabel".equals(method.getName()) || "getColumnName".equals(method.getName())) return "STATUS";
                return defaultValue(method.getReturnType());
            });
        }

        @Override public void close() { }
    }

    private static final class RunResult {
        private final ValidationResult result;
        private final CaseRuntimeContext context;
        private RunResult(ValidationResult result, CaseRuntimeContext context) { this.result = result; this.context = context; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
