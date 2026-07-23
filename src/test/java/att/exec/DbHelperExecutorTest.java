/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.DbHelperConfig;
import att.config.FrameworkConfig;
import att.config.ProcessOutputConfig;
import att.config.ToolConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.StageCaseData;
import att.core.TestCase;
import att.core.ValidationResult;
import att.template.StageTemplate;
import att.template.StageTemplateRunner;
import att.template.TemplateAction;
import att.template.UnifiedTemplateEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DbHelperExecutorTest {
    @TempDir Path tempDir;
    private FakeDriver driver;

    @BeforeEach void registerDriver() throws Exception {
        driver = new FakeDriver();
        DriverManager.registerDriver(driver);
    }

    @AfterEach void unregisterDriver() throws Exception {
        DriverManager.deregisterDriver(driver);
    }

    @Test void executesTypedQueryAndUpdateWithInstanceTimeout() throws Exception {
        Map<String, DbHelperConfig> helpers = new LinkedHashMap<String, DbHelperConfig>();
        helpers.put("orders", db("orders", "jdbc:att-test:orders", "case", "rollback", 7, 10));
        helpers.put("audit", db("audit", "jdbc:att-test:audit", "statement", "commit", 11, 10));
        DbHelperExecutor executor = executor(helpers);
        CaseRuntimeContext context = context();
        CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"));
        executor.beginCase();

        DbInvocationResult query = executor.execute("orders", "query",
                "select id, status from orders where id = ?", "inline",
                Collections.<Object>singletonList("A100"), "query-1");
        Map<?, ?> queryResult = (Map<?, ?>) query.result();
        assertTrue(query.success());
        assertEquals("query", queryResult.get("operation"));
        assertEquals(2, queryResult.get("rowCount"));
        assertTrue(queryResult.get("rows") instanceof List);
        assertEquals("A100", ((Map<?, ?>) ((List<?>) queryResult.get("rows")).get(0)).get("ID"));
        assertEquals("PENDING_ROLLBACK", ((Map<?, ?>) queryResult.get("transaction")).get("state"));
        assertEquals(Collections.singletonList("***"), query.evidence().get("parameters"));

        DbInvocationResult update = executor.execute("audit", "update",
                "update audit set status='DONE'", "inline", Collections.emptyList(), "update-1");
        assertTrue(update.success());
        assertEquals(2, ((Map<?, ?>) update.result()).get("affectedRows"));
        assertEquals("AUTO_COMMITTED", ((Map<?, ?>) ((Map<?, ?>) update.result()).get("transaction")).get("state"));

        assertTrue(executor.finishCase(context, log).isEmpty());
        assertEquals("ROLLED_BACK", context.resolve("CASE.DB.orders.state"));
        assertEquals("AUTO_COMMITTED", context.resolve("CASE.DB.audit.state"));
        FakeState orders = driver.states.get("jdbc:att-test:orders");
        FakeState audit = driver.states.get("jdbc:att-test:audit");
        assertEquals(7, orders.lastQueryTimeout);
        assertEquals(Collections.singletonList("A100"), orders.boundValues);
        assertEquals(1, orders.rollbacks);
        assertEquals(11, audit.lastQueryTimeout);
        assertEquals(0, orders.closes);
        executor.close();
        log.close();
        assertEquals(1, orders.closes);
        assertEquals(1, audit.closes);
    }

    @Test void keepsRowsAsAListAndReportsLimitsAndDuplicateLabels() throws Exception {
        Map<String, DbHelperConfig> helpers = new LinkedHashMap<String, DbHelperConfig>();
        helpers.put("limited", db("limited", "jdbc:att-test:limits", "statement", "commit", 5, 1));
        DbHelperExecutor executor = executor(helpers);
        executor.beginCase();

        Map<?, ?> empty = (Map<?, ?>) executor.execute("limited", "query", "select EMPTY", "inline",
                Collections.emptyList(), "empty").result();
        assertTrue(empty.get("rows") instanceof List);
        assertEquals(0, ((List<?>) empty.get("rows")).size());

        Map<?, ?> one = (Map<?, ?>) executor.execute("limited", "query", "select ONE", "inline",
                Collections.emptyList(), "one").result();
        assertTrue(one.get("rows") instanceof List);
        assertEquals(1, ((List<?>) one.get("rows")).size());

        Map<?, ?> tooMany = (Map<?, ?>) executor.execute("limited", "query", "select MANY", "inline",
                Collections.emptyList(), "many").result();
        assertFalse(Boolean.TRUE.equals(tooMany.get("success")));
        assertEquals("LIMIT_EXCEEDED", ((Map<?, ?>) tooMany.get("error")).get("type"));

        Map<?, ?> duplicate = (Map<?, ?>) executor.execute("limited", "query", "select DUP", "inline",
                Collections.emptyList(), "duplicate").result();
        assertFalse(Boolean.TRUE.equals(duplicate.get("success")));
        assertEquals("SQL_ERROR", ((Map<?, ?>) duplicate.get("error")).get("type"));
        assertTrue(String.valueOf(((Map<?, ?>) duplicate.get("error")).get("message")).contains("Duplicate"));
        executor.close();
    }

    @Test void caseSqlErrorBecomesRollbackOnlyAndFinalizesAsRollback() throws Exception {
        Map<String, DbHelperConfig> helpers = Collections.singletonMap("orders",
                db("orders", "jdbc:att-test:rollback-only", "case", "commit", 9, 10));
        DbHelperExecutor executor = executor(helpers);
        CaseRuntimeContext context = context();
        CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("rollback-only.log"));
        executor.beginCase();

        DbInvocationResult failed = executor.execute("orders", "query", "select FAIL", "inline",
                Collections.emptyList(), "failed");
        assertFalse(failed.success());
        assertEquals("SQL_ERROR", ((Map<?, ?>) ((Map<?, ?>) failed.result()).get("error")).get("type"));
        DbInvocationResult blocked = executor.execute("orders", "query", "select ONE", "inline",
                Collections.emptyList(), "blocked");
        assertEquals("ROLLBACK_ONLY", ((Map<?, ?>) ((Map<?, ?>) blocked.result()).get("error")).get("type"));

        assertTrue(executor.finishCase(context, log).isEmpty());
        assertEquals("ROLLED_BACK", context.resolve("CASE.DB.orders.state"));
        executor.close();
        log.close();
    }

    @Test void finalizationFailureIsErrorAndSanitizesCredentials() throws Exception {
        String url = "jdbc:att-test:commit-fail";
        Map<String, DbHelperConfig> helpers = Collections.singletonMap("unsafe",
                db("unsafe", url, "case", "commit", 23, 10));
        DbHelperExecutor executor = executor(helpers);
        CaseRuntimeContext context = context();
        CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("commit-fail.log"));
        executor.beginCase();
        assertTrue(executor.execute("unsafe", "update", "update account set value=1", "inline",
                Collections.emptyList(), "update").success());

        List<ValidationResult> failures = executor.finishCase(context, log);
        assertEquals(1, failures.size());
        assertEquals(ResultStatus.ERROR, failures.get(0).status());
        assertEquals("FINALIZE_ERROR", context.resolve("CASE.DB.unsafe.state"));
        String message = String.valueOf(context.resolve("CASE.DB.unsafe.error.message"));
        assertFalse(message.contains("secret"));
        assertFalse(message.contains(url));
        assertTrue(message.contains("<redacted>"));
        assertTrue(message.contains("<jdbc-url>"));
        assertEquals(1, driver.states.get(url).closes);
        executor.close();
        log.close();
        assertEquals(1, driver.states.get(url).closes);
    }

    @Test void reusesThreadConnectionAcrossCasesAndReconnectsAfterIsolationRollbackFailure() throws Exception {
        String url = "jdbc:att-test:rollback-fail";
        Map<String, DbHelperConfig> helpers = Collections.singletonMap("shared",
                db("shared", url, "case", "commit", 29, 10));
        DbHelperExecutor executor = executor(helpers);
        CaseExecutionLog firstLog = new CaseExecutionLog(tempDir.resolve("first.log"));
        executor.beginCase();
        assertTrue(executor.execute("shared", "update", "update account set value=1", "inline",
                Collections.emptyList(), "first").success());
        assertTrue(executor.finishCase(context(), firstLog).isEmpty());
        firstLog.close();
        FakeState state = driver.states.get(url);
        assertEquals(1, state.connections);
        assertEquals(0, state.closes);

        executor.beginCase();
        assertEquals(2, state.connections);
        assertEquals(1, state.closes);
        CaseRuntimeContext second = context();
        CaseExecutionLog secondLog = new CaseExecutionLog(tempDir.resolve("second.log"));
        assertTrue(executor.execute("shared", "update", "update account set value=2", "inline",
                Collections.emptyList(), "second").success());
        assertTrue(executor.finishCase(second, secondLog).isEmpty());
        assertEquals("COMMITTED", second.resolve("CASE.DB.shared.state"));
        executor.close();
        secondLog.close();
        assertEquals(2, state.closes);
    }

    @Test void rejectsUpdatesForReadOnlyInstance() throws Exception {
        DbHelperConfig readOnly = new DbHelperConfig("readonly", "Read only", "Read only DB",
                "jdbc:att-test:readonly", "user", "secret", "", Collections.<String, String>emptyMap(),
                true, "driverDefault", 30, "case", "rollback", 10, 1024, 8192,
                "full", "masked", null);
        DbHelperExecutor executor = executor(Collections.singletonMap("readonly", readOnly));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> executor.execute(
                "readonly", "update", "update t set v=1", "inline", Collections.emptyList(), "u"));
        assertTrue(error.getMessage().contains("readOnly"));
        executor.close();
    }

    @Test void sqlFilesMustBePackageContainedRegularFiles() throws Exception {
        DbHelperExecutor executor = executor(Collections.<String, DbHelperConfig>emptyMap());
        java.nio.file.Files.createDirectories(tempDir.resolve("sql"));
        java.nio.file.Files.write(tempDir.resolve("sql/query.sql"), "select 1".getBytes("UTF-8"));
        assertEquals(tempDir.resolve("sql/query.sql").toRealPath(), executor.resolveSqlFile("sql/query.sql"));
        assertThrows(IllegalArgumentException.class, () -> executor.resolveSqlFile("../outside.sql"));
        assertThrows(Exception.class, () -> executor.resolveSqlFile("sql/missing.sql"));
        executor.close();
    }

    @Test void dbActionsAndExpressionsPreserveTypedResultsAndWriteStructuredArtifacts() throws Exception {
        Map<String, DbHelperConfig> helpers = Collections.singletonMap("orders",
                db("orders", "jdbc:att-test:actions", "case", "rollback", 12, 10));
        DbHelperExecutor executor = executor(helpers);
        java.nio.file.Files.createDirectories(tempDir.resolve("sql"));
        java.nio.file.Files.write(tempDir.resolve("sql/orders.sql"),
                "select ${CASE.queryToken} where customer_id = ?".getBytes("UTF-8"));
        Map<String, Object> caseData = new LinkedHashMap<String, Object>();
        caseData.put("customerId", 42);
        caseData.put("queryToken", "ONE");
        CaseRuntimeContext context = contextWithData(caseData);
        context.beginStage(new StageCaseData("verify", "DB", Collections.<String, Object>emptyMap()),
                "DB", tempDir);
        CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("actions.log"));
        executor.beginCase();

        List<TemplateAction> actions = new ArrayList<TemplateAction>();
        actions.add(new TemplateAction("query", map("type", "db", "db", "orders",
                "query", map("sqlFile", "sql/orders.sql", "params",
                        Collections.<Object>singletonList("${CASE.customerId}")),
                "saveAs", map("path", "db/orders.json", "format", "json")), "att-template/v2.5"));
        actions.add(new TemplateAction("assign", map("type", "assign", "name", "orders",
                "expression", "#{db.orders.query(sql='select ONE', params=[${CASE.customerId}, 'OPEN'])}"),
                "att-template/v2.5"));
        actions.add(new TemplateAction("scalar", map("type", "assign", "name", "orderId",
                "expression", "#{db.orders.scalar(sql='select SCALAR', params=[])}"),
                "att-template/v2.5"));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null, executor))
                .execute("verify", new StageTemplate("DB", tempDir, actions, "att-template/v2.5"), context, log);
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(ResultStatus.PASS, results.get(1).status(), results.get(1).message());
        assertEquals(3, results.size());
        assertEquals(ResultStatus.PASS, results.get(2).status());
        assertTrue(context.resolve("ACTIONS.query.output.result.rows") instanceof List);
        assertEquals("A100", context.resolve("CASE.VARS.orders.rows[0].ID"));
        assertEquals("A100", context.resolve("CASE.VARS.orderId"));
        Path artifact = java.nio.file.Paths.get(String.valueOf(context.resolve("ACTIONS.query.output.targetFiles[0]")));
        assertTrue(artifact.startsWith(tempDir));
        assertTrue(new String(java.nio.file.Files.readAllBytes(artifact), "UTF-8").contains("\"rows\""));
        assertTrue(context.resolve("ACTIONS.assign.DB.orders") instanceof Map);
        assertTrue(executor.finishCase(context, log).isEmpty());
        assertEquals("ROLLED_BACK", context.resolve("CASE.DB.orders.state"));
        executor.close();
        log.close();
    }

    @Test void callBackedToolsWrapTypedQueriesScalarsAndPrimaryUpdates() throws Exception {
        Map<String, DbHelperConfig> helpers = Collections.singletonMap("orders",
                db("orders", "jdbc:att-test:facade", "case", "rollback", 17, 10));
        Map<String, att.config.ToolArgumentConfig> findArguments = new LinkedHashMap<String, att.config.ToolArgumentConfig>();
        findArguments.put("customerId", new att.config.ToolArgumentConfig("customerId", "Customer", "Customer ID", true, ""));
        findArguments.put("status", new att.config.ToolArgumentConfig("status", "Status", "Order status", true, ""));
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("orders.find", callTool("orders.find", "find", "orders",
                "#{db.orders.query(sql='select ONE', params=[input.customerId, #{upper(input.status)}])}", "case", findArguments));
        tools.put("orders.id", callTool("orders.id", "id", "orders",
                "#{db.orders.scalar(sql='select SCALAR', params=[])}", Collections.<String, att.config.ToolArgumentConfig>emptyMap()));
        tools.put("orders.close", callTool("orders.close", "close", "orders",
                "#{db.orders.update(sql='update orders set status = ?', params=[input.status])}",
                Collections.singletonMap("status", new att.config.ToolArgumentConfig("status", "Status", "Status", true, ""))));
        FrameworkConfig config = frameworkConfig(tools, helpers);
        DbHelperExecutor executor = new DbHelperExecutor(tempDir, config);
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(new ToolInvoker(tempDir, config), executor);
        Map<String, Object> caseData = new LinkedHashMap<String, Object>();
        caseData.put("customerId", 42);
        CaseRuntimeContext context = contextWithData(caseData);
        context.beginStage(new StageCaseData("verify", "DB facade", Collections.<String, Object>emptyMap()),
                "DB facade", tempDir);
        CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("facade.log"));
        executor.beginCase();

        Object query = engine.evaluate("#{orders.find(customerId=${CASE.customerId}, status='open')}", context, log);
        assertTrue(query instanceof Map);
        assertTrue(((Map<?, ?>) query).get("rows") instanceof List);
        assertEquals(17, driver.states.get("jdbc:att-test:facade").lastQueryTimeout);
        assertEquals(java.util.Arrays.<Object>asList(42, "OPEN"), driver.states.get("jdbc:att-test:facade").boundValues);
        ToolInvocationResult cached = engine.executeToolAttempt(
                "#{orders.find(status='open', customerId=${CASE.customerId})}", context, log,
                "cached", null, "");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) contextPath(cached.invocation(),
                "TOOL", "orders", "find", "cache")).get("hit"));
        assertFalse(cached.invocation().containsKey("DB"));
        assertFalse(((Map<?, ?>) contextPath(cached.invocation(), "TOOL", "orders", "find")).containsKey("call"));
        assertEquals(java.util.Arrays.<Object>asList(42, "OPEN"), driver.states.get("jdbc:att-test:facade").boundValues);
        assertThrows(IllegalArgumentException.class,
                () -> engine.evaluate("#{orders.close(status='DONE')}", context, log));

        List<TemplateAction> actions = new ArrayList<TemplateAction>();
        actions.add(new TemplateAction("scalar", map("type", "tool", "call", "#{orders.id()}",
                "saveAs", map("path", "db/order-id.txt", "format", "text")), "att-template/v2.5"));
        actions.add(new TemplateAction("close", map("type", "tool", "call", "#{orders.close(status='DONE')}"),
                "att-template/v2.5"));
        List<ValidationResult> results = new StageTemplateRunner(engine).execute("verify",
                new StageTemplate("DB facade", tempDir, actions, "att-template/v2.5"), context, log);
        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        assertEquals(ResultStatus.PASS, results.get(1).status(), results.get(1).message());
        assertEquals("A100", context.resolve("ACTIONS.scalar.output.result"));
        assertEquals("call", context.resolve("ACTIONS.close.TOOL.orders.close.implementation"));
        assertTrue(context.resolve("ACTIONS.close.DB.orders") instanceof Map);
        assertNull(context.resolve("ACTIONS.close.output.exitCode"));
        assertTrue(executor.finishCase(context, log).isEmpty());
        executor.close();
        log.close();
    }

    @Test void dbScopedToolCacheSurvivesUpdatesTransactionsAndReconnects() throws Exception {
        Map<String, DbHelperConfig> helpers = Collections.singletonMap("reference",
                db("reference", "jdbc:att-test:db-cache-rollback-fail", "case", "commit", 19, 10));
        Map<String, att.config.ToolArgumentConfig> arguments = Collections.singletonMap("id",
                new att.config.ToolArgumentConfig("id", "ID", "ID", true, ""));
        ToolConfig lookup = callTool("reference.lookup", "lookup", "reference",
                "#{db.reference.query(sql='select ONE', params=[input.id])}", "db", arguments);
        FrameworkConfig config = frameworkConfig(Collections.singletonMap(lookup.key(), lookup), helpers);
        DbHelperExecutor executor = new DbHelperExecutor(tempDir, config);
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(new ToolInvoker(tempDir, config), executor);

        CaseRuntimeContext first = context();
        CaseExecutionLog firstLog = new CaseExecutionLog(tempDir.resolve("db-cache-first.log"));
        executor.beginCase();
        ToolInvocationResult miss = engine.executeToolAttempt("#{reference.lookup(id='7')}", first, firstLog,
                "first", null, "");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) contextPath(miss.invocation(),
                "TOOL", "reference", "lookup", "cache")).get("hit"));
        assertTrue(executor.finishCase(first, firstLog).isEmpty());
        firstLog.close();

        CaseRuntimeContext second = context();
        CaseExecutionLog secondLog = new CaseExecutionLog(tempDir.resolve("db-cache-second.log"));
        executor.beginCase();
        ToolInvocationResult hit = engine.executeToolAttempt("#{reference.lookup(id='7')}", second, secondLog,
                "second", null, "");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) contextPath(hit.invocation(),
                "TOOL", "reference", "lookup", "cache")).get("hit"));
        assertEquals(2, driver.states.get("jdbc:att-test:db-cache-rollback-fail").connections);
        assertEquals(Collections.<Object>singletonList("7"), driver.states.get("jdbc:att-test:db-cache-rollback-fail").boundValues);

        assertTrue(executor.execute("reference", "update", "update reference set value=1", "inline",
                Collections.emptyList(), "update").success());
        ToolInvocationResult afterUpdate = engine.executeToolAttempt("#{reference.lookup(id='7')}", second, secondLog,
                "third", null, "");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) contextPath(afterUpdate.invocation(),
                "TOOL", "reference", "lookup", "cache")).get("hit"));
        assertEquals(Collections.<Object>singletonList("7"), driver.states.get("jdbc:att-test:db-cache-rollback-fail").boundValues);
        executor.abortCase();
        ToolInvocationResult afterRollback = engine.executeToolAttempt("#{reference.lookup(id='7')}", second, secondLog,
                "fourth", null, "");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) contextPath(afterRollback.invocation(),
                "TOOL", "reference", "lookup", "cache")).get("hit"));
        assertEquals(1, driver.states.get("jdbc:att-test:db-cache-rollback-fail").commits);
        assertEquals(2, driver.states.get("jdbc:att-test:db-cache-rollback-fail").rollbacks);
        assertEquals(Collections.<Object>singletonList("7"), driver.states.get("jdbc:att-test:db-cache-rollback-fail").boundValues);
        executor.close();
        secondLog.close();
    }

    private DbHelperExecutor executor(Map<String, DbHelperConfig> helpers) {
        return new DbHelperExecutor(tempDir, frameworkConfig(Collections.<String, ToolConfig>emptyMap(), helpers));
    }

    private FrameworkConfig frameworkConfig(Map<String, ToolConfig> tools, Map<String, DbHelperConfig> helpers) {
        return new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000,
                tempDir, tempDir, tools, helpers, null, null,
                null, "", "", null, null, 1, "ignore", "", false, ProcessOutputConfig.defaults());
    }

    private ToolConfig callTool(String key, String localKey, String groupId, String call,
                                Map<String, att.config.ToolArgumentConfig> arguments) {
        return callTool(key, localKey, groupId, call, "", arguments);
    }

    private ToolConfig callTool(String key, String localKey, String groupId, String call, String cache,
                                Map<String, att.config.ToolArgumentConfig> arguments) {
        return new ToolConfig(key, localKey, groupId, key, key, Collections.<String>emptyList(), call,
                cache, Collections.<String>emptyList(), "", arguments, null, null);
    }

    private Object contextPath(Map<String, Object> root, String... path) {
        Object current = root;
        for (String part : path) current = ((Map<?, ?>) current).get(part);
        return current;
    }

    private DbHelperConfig db(String id, String url, String scope, String onEnd, int timeout, int maxRows) {
        return new DbHelperConfig(id, id, id + " DB", url, "user", "secret", "",
                Collections.<String, String>emptyMap(), false, "driverDefault", timeout, scope, onEnd,
                maxRows, 1024, 8192, "full", "masked", null);
    }

    private CaseRuntimeContext context() throws Exception {
        return contextWithData(Collections.<String, Object>emptyMap());
    }

    private CaseRuntimeContext contextWithData(Map<String, Object> data) throws Exception {
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                data, Collections.emptyMap(), null);
        return new CaseRuntimeContext(test, tempDir, "R", tempDir, tempDir.resolve("case.log"));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static final class FakeState {
        int connections, commits, rollbacks, closes, lastQueryTimeout, rollbackFailuresRemaining;
        boolean autoCommit, closed, commitFails;
        final List<Object> boundValues = new ArrayList<Object>();
    }

    private static final class FakeDriver implements Driver {
        final Map<String, FakeState> states = new LinkedHashMap<String, FakeState>();

        @Override public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) return null;
            FakeState state = states.get(url);
            if (state == null) {
                state = new FakeState();
                state.commitFails = url.contains("commit-fail");
                state.rollbackFailuresRemaining = url.contains("rollback-fail") ? 1 : 0;
                states.put(url, state);
            }
            state.connections++;
            state.closed = false;
            final FakeState captured = state;
            return proxy(Connection.class, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("prepareStatement".equals(name)) return statement(captured, String.valueOf(args[0]));
                    if ("setAutoCommit".equals(name)) { captured.autoCommit = (Boolean) args[0]; return null; }
                    if ("getAutoCommit".equals(name)) return captured.autoCommit;
                    if ("setReadOnly".equals(name) || "setTransactionIsolation".equals(name)) return null;
                    if ("commit".equals(name)) {
                        captured.commits++;
                        if (captured.commitFails) throw new SQLException(
                                "commit failed for secret at jdbc:att-test:commit-fail", "08006", 77);
                        return null;
                    }
                    if ("rollback".equals(name)) {
                        captured.rollbacks++;
                        if (captured.rollbackFailuresRemaining > 0) {
                            captured.rollbackFailuresRemaining--;
                            throw new SQLException("isolation rollback failed", "08006", 78);
                        }
                        return null;
                    }
                    if ("close".equals(name)) { captured.closes++; captured.closed = true; return null; }
                    if ("isClosed".equals(name)) return captured.closed;
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private PreparedStatement statement(final FakeState state, final String sql) {
            return proxy(PreparedStatement.class, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("setQueryTimeout".equals(name)) { state.lastQueryTimeout = (Integer) args[0]; return null; }
                    if ("setObject".equals(name)) { state.boundValues.add(args[1]); return null; }
                    if ("setMaxRows".equals(name) || "close".equals(name)) return null;
                    if ("executeQuery".equals(name)) {
                        if (sql.contains("FAIL")) throw new SQLException("expected failure", "42000", 99);
                        return rows(sql);
                    }
                    if ("executeUpdate".equals(name)) {
                        if (sql.contains("FAIL")) throw new SQLException("expected failure", "42000", 99);
                        return 2;
                    }
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private ResultSet rows(final String sql) {
            final Object[][] values = sql.contains("EMPTY") ? new Object[0][0]
                    : sql.contains("SCALAR") ? new Object[][]{{"A100"}}
                    : sql.contains("ONE") || sql.contains("DUP") ? new Object[][]{{"A100", "READY"}}
                    : new Object[][]{{"A100", "READY"}, {"A101", "DONE"}};
            return proxy(ResultSet.class, new InvocationHandler() {
                int row = -1;
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("next".equals(name)) return ++row < values.length;
                    if ("getObject".equals(name)) return values[row][((Integer) args[0]) - 1];
                    if ("getMetaData".equals(name)) return metadata(sql);
                    if ("close".equals(name)) return null;
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private ResultSetMetaData metadata(final String sql) {
            return proxy(ResultSetMetaData.class, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("getColumnCount".equals(method.getName())) return sql.contains("SCALAR") ? 1 : 2;
                    if ("getColumnLabel".equals(method.getName()) || "getColumnName".equals(method.getName())) {
                        return sql.contains("DUP") ? "ID" : ((Integer) args[0]) == 1 ? "ID" : "STATUS";
                    }
                    return defaultValue(method.getReturnType());
                }
            });
        }

        @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:att-test:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
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
}
