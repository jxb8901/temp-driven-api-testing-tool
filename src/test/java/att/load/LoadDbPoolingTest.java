package att.load;

import att.config.DbHelperConfig;
import att.config.FrameworkConfig;
import att.config.ProcessOutputConfig;
import att.core.ResultStatus;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.TemplateAction;
import att.exec.DbHelperExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadDbPoolingTest {
    @TempDir Path tempDir;
    private FakeDriver driver;

    @BeforeEach void registerDriver() throws Exception {
        driver = new FakeDriver();
        DriverManager.registerDriver(driver);
    }

    @AfterEach void unregisterDriver() throws Exception {
        DriverManager.deregisterDriver(driver);
    }

    @Test
    void iterationReusesOneBorrowedConnectionAndReturnsItAfterCaseFinalization() throws Exception {
        String url = "jdbc:att-load:reuse";
        FrameworkConfig config = framework(db("orders", url, 1, 500L, "rollback"));
        LoadTarget target = target(project(), template(twoQueries()));

        try (LoadRunResources resources = new LoadRunResources(tempDir, config)) {
            IterationResult result = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output"))
                    .execute(request("reuse-1"));
            assertEquals(ResultStatus.PASS, result.status());
            assertEquals(1, driver.states.get(url).queryConnectionIds.size());
            assertEquals(1, driver.states.get(url).rollbacks);
            assertEquals(0, resources.dbPool("orders").active());
            assertEquals(1, resources.dbPool("orders").total());
        }
        assertEquals(driver.states.get(url).connections, driver.states.get(url).closes);
    }

    @Test
    void concurrentIterationsTimeoutCanonicallyRecoverAndNeverShareOnePhysicalConnection() throws Exception {
        String url = "jdbc:att-load:concurrent";
        FrameworkConfig config = framework(db("orders", url, 1, 250L, "rollback"));
        LoadTarget target = target(project(), template(singleQuery()));
        driver.blockQueries = true;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (LoadRunResources resources = new LoadRunResources(tempDir, config)) {
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output"));
            Future<IterationResult> first = workers.submit(() -> executor.execute(request("concurrent-1")));
            assertTrue(driver.queryEntered.await(2L, TimeUnit.SECONDS));
            Future<IterationResult> second = workers.submit(() -> executor.execute(request("concurrent-2")));
            IterationResult timedOut = second.get(2L, TimeUnit.SECONDS);
            assertEquals(ResultStatus.ERROR, timedOut.status());
            assertEquals("DB_POOL_TIMEOUT", timedOut.context().resolve("EXEC.ACTIONS.query.output.result.error.type"));
            assertEquals(1, driver.states.get(url).queryConnectionIds.size());

            driver.blockQueries = false;
            driver.releaseQuery.countDown();
            assertEquals(ResultStatus.PASS, first.get(2L, TimeUnit.SECONDS).status());
            assertEquals(0, resources.dbPool("orders").active());

            IterationResult recovered = executor.execute(request("concurrent-3"));
            assertEquals(ResultStatus.PASS, recovered.status());
            assertEquals(1, driver.states.get(url).queryConnectionIds.size());
            assertEquals(0, resources.dbPool("orders").active());
        } finally {
            driver.releaseQuery.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptingAnIterationAbortsAndReturnsItsPooledConnection() throws Exception {
        String url = "jdbc:att-load:cancel";
        FrameworkConfig config = framework(db("orders", url, 1, 500L, "rollback"));
        LoadTarget target = target(project(), template(singleQuery()));
        driver.blockQueries = true;
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (LoadRunResources resources = new LoadRunResources(tempDir, config)) {
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output"));
            Future<IterationResult> running = workers.submit(() -> executor.execute(request("cancel-1")));
            assertTrue(driver.queryEntered.await(2L, TimeUnit.SECONDS));
            running.cancel(true);
            assertTrue(driver.queryFinished.await(2L, TimeUnit.SECONDS));
            workers.shutdown();
            assertTrue(workers.awaitTermination(2L, TimeUnit.SECONDS));
            assertEquals(0, resources.dbPool("orders").active());
        } finally {
            driver.releaseQuery.countDown();
            workers.shutdownNow();
        }
        assertEquals(driver.states.get(url).connections, driver.states.get(url).closes);
    }

    @Test
    void pooledCaseCommitAndRollbackRemainCompatible() throws Exception {
        String commitUrl = "jdbc:att-load:commit";
        String rollbackUrl = "jdbc:att-load:rollback";
        runOneCase(commitUrl, "commit");
        runOneCase(rollbackUrl, "rollback");
        assertEquals(1, driver.states.get(commitUrl).commits);
        assertEquals(0, driver.states.get(commitUrl).rollbacks);
        assertEquals(0, driver.states.get(rollbackUrl).commits);
        assertEquals(1, driver.states.get(rollbackUrl).rollbacks);
    }

    private void runOneCase(String url, String onEnd) throws Exception {
        FrameworkConfig config = framework(db("orders", url, 1, 500L, onEnd));
        LoadTarget target = target(project(), template(singleQuery()));
        try (LoadRunResources resources = new LoadRunResources(tempDir, config)) {
            IterationResult result = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output"))
                    .execute(request("transaction-" + onEnd));
            assertEquals(ResultStatus.PASS, result.status());
            assertEquals(0, resources.dbPool("orders").active());
        }
    }

    private Path project() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("templates"));
        return project;
    }

    private LoadTarget target(Path project, StageTemplate template) throws Exception {
        FlowRegistry flows = new FlowRegistry(project, project.resolve("templates"), false);
        return new LoadTarget("template", "DB", template, flows, project.resolve("templates"), project.resolve("scenario.yaml"));
    }

    private StageTemplate template(List<TemplateAction> actions) throws Exception {
        Path directory = tempDir.resolve("project/templates/DB");
        Files.createDirectories(directory);
        return new StageTemplate("DB", directory, actions, "att-template/v3.0", directory.resolve("template.yaml"));
    }

    private List<TemplateAction> singleQuery() {
        return Collections.singletonList(new TemplateAction("query", map(
                "type", "db", "db", "orders", "query", map("sql", "select ONE")), "att-template/v3.0"));
    }

    private List<TemplateAction> twoQueries() {
        List<TemplateAction> actions = new ArrayList<TemplateAction>();
        actions.addAll(singleQuery());
        actions.add(new TemplateAction("queryAgain", map(
                "type", "db", "db", "orders", "query", map("sql", "select ONE")), "att-template/v3.0"));
        return actions;
    }

    private IterationRequest request(String id) {
        return IterationRequest.closed("load-db", id, 1, "STEADY", java.time.Instant.now(), "VU-1", Collections.emptyMap());
    }

    private FrameworkConfig framework(DbHelperConfig helper) {
        Map<String, DbHelperConfig> helpers = new LinkedHashMap<String, DbHelperConfig>();
        helpers.put(helper.id(), helper);
        return new FrameworkConfig(tempDir.resolve("output"), tempDir.resolve("report"), tempDir.resolve("logs"),
                "SIT", 10000, tempDir.resolve("project/templates"), tempDir.resolve("project/testcase"),
                Collections.emptyMap(), helpers, Collections.emptyMap(), null, null, null, "", "", null, null,
                1, "ignore", "", false, ProcessOutputConfig.defaults());
    }

    private DbHelperConfig db(String id, String url, int maxSize, long timeoutMs, String onEnd) {
        return new DbHelperConfig(id, id, id + " DB", url, "user", "secret", "",
                Collections.<String, String>emptyMap(), false, "driverDefault", 5, "case", onEnd,
                10, 1024, 8192, "full", "masked", maxSize, 0, timeoutMs,
                tempDir.resolve(id + ".yaml"));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private static final class FakeState {
        int connections, commits, rollbacks, closes;
        int nextConnectionId;
        final java.util.Set<Integer> queryConnectionIds = new java.util.LinkedHashSet<Integer>();
        boolean autoCommit, closed;
    }

    private static final class FakeDriver implements Driver {
        final Map<String, FakeState> states = new LinkedHashMap<String, FakeState>();
        volatile boolean blockQueries;
        final CountDownLatch queryEntered = new CountDownLatch(1);
        final CountDownLatch releaseQuery = new CountDownLatch(1);
        final CountDownLatch queryFinished = new CountDownLatch(1);

        @Override public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) return null;
            FakeState state = states.get(url);
            if (state == null) { state = new FakeState(); states.put(url, state); }
            state.connections++;
            final int connectionId = ++state.nextConnectionId;
            state.closed = false;
            final FakeState captured = state;
            return proxy(Connection.class, (proxy, method, args) -> {
                String name = method.getName();
                if ("prepareStatement".equals(name)) return statement(captured, connectionId);
                if ("setAutoCommit".equals(name)) { captured.autoCommit = (Boolean) args[0]; return null; }
                if ("getAutoCommit".equals(name)) return captured.autoCommit;
                if ("setReadOnly".equals(name) || "setTransactionIsolation".equals(name)) return null;
                if ("commit".equals(name)) { captured.commits++; return null; }
                if ("rollback".equals(name)) { captured.rollbacks++; return null; }
                if ("close".equals(name)) { captured.closes++; captured.closed = true; return null; }
                if ("isClosed".equals(name)) return captured.closed;
                if ("isValid".equals(name)) return true;
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement(final FakeState state, final int connectionId) {
            return proxy(PreparedStatement.class, (proxy, method, args) -> {
                String name = method.getName();
                if ("setQueryTimeout".equals(name) || "setMaxRows".equals(name) || "setObject".equals(name)) return null;
                if ("executeQuery".equals(name)) {
                    state.queryConnectionIds.add(connectionId);
                    try {
                        if (blockQueries) {
                            queryEntered.countDown();
                            releaseQuery.await();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("query interrupted", "57014", 57014);
                    } finally { queryFinished.countDown(); }
                    return rows();
                }
                if ("close".equals(name)) return null;
                return defaultValue(method.getReturnType());
            });
        }

        private ResultSet rows() {
            return proxy(ResultSet.class, new InvocationHandler() {
                private boolean returned;
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("next".equals(name)) return !returned && (returned = true);
                    if ("getObject".equals(name)) return "A100";
                    if ("getMetaData".equals(name)) return metadata();
                    if ("close".equals(name)) return null;
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private ResultSetMetaData metadata() {
            return proxy(ResultSetMetaData.class, (proxy, method, args) -> {
                if ("getColumnCount".equals(method.getName())) return 1;
                if ("getColumnLabel".equals(method.getName()) || "getColumnName".equals(method.getName())) return "ID";
                return defaultValue(method.getReturnType());
            });
        }

        @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:att-load:"); }
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
