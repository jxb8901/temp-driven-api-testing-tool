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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void perWorkerInitialConnectionBudgetHasATypedLimitFailure() throws Exception {
        LoadPreparationBudget.requireWithinPerRunLimit(LoadPreparationBudget.PER_RUN_CONNECTIONS);
        LoadResourcePreparationException failure = assertThrows(LoadResourcePreparationException.class,
                () -> LoadPreparationBudget.requireWithinPerRunLimit(LoadPreparationBudget.PER_RUN_CONNECTIONS + 1));
        assertEquals(LoadResourcePreparationException.RESOURCE_LIMIT, failure.diagnosticCode());
    }

    @Test
    void preparationHoldsDistinctValidatedConnectionsWithoutBusinessSql() throws Exception {
        for (int minimum : new int[] {0, 3}) {
            String url = "jdbc:att-load:ready-" + minimum;
            DbHelperConfig helper = new DbHelperConfig("orders", "orders", "DB", url, "user", "secret", "",
                    Collections.<String, String>emptyMap(), false, "driverDefault", 5, "case", "rollback",
                    10, 1024, 8192, "full", "masked", 5, minimum, 1000L, tempDir.resolve("db.yaml"));
            try (HikariDbPool pool = new HikariDbPool(helper)) {
                assertTrue(pool.prepareInitialReady() >= Math.max(1, minimum));
                assertEquals(0, pool.active());
                assertTrue(driver.states.get(url).connections >= Math.max(1, minimum));
                assertEquals(0, driver.states.get(url).queryConnectionIds.size());
                assertEquals(0, driver.states.get(url).commits);
                assertEquals(0, driver.states.get(url).rollbacks);
                assertEquals(minimum, pool.metrics().get("minIdle"));
                assertEquals(5, pool.metrics().get("maxSize"));
                // Business borrow telemetry must not include the readiness probe.
                assertEquals(0, pool.borrowCount());
            }
            assertEquals(driver.states.get(url).connections, driver.states.get(url).closes);
        }
    }

    @Test
    void readinessDriverTimeoutsAreTemporaryAndRuntimePoolGrowthKeepsConfiguredValues() throws Exception {
        String url="jdbc:postgresql:att-load-timeout-properties";
        Map<String,String> properties=new LinkedHashMap<String,String>();
        properties.put("connectTimeout","30");properties.put("loginTimeout","40");
        DbHelperConfig helper=new DbHelperConfig("timeout","timeout","DB",url,"user","secret","",
                properties,false,"driverDefault",5,"case","rollback",10,1024,8192,"full","masked",
                2,0,2000L,tempDir.resolve("timeout-db.yaml"));
        try(HikariDbPool pool=new HikariDbPool(helper,1000L)) {
            assertTrue(pool.prepareInitialReady(System.nanoTime()+TimeUnit.SECONDS.toNanos(5))>=1);
            List<Map<String,String>> seen=driver.connectionProperties.get(url);
            assertNotNull(seen);
            assertEquals("1",seen.get(0).get("connectTimeout"));
            assertEquals("1",seen.get(0).get("loginTimeout"));

            Connection first=pool.borrow();
            Connection second=pool.borrow();
            try {
                assertTrue(seen.size()>=2,"holding the ready lease should force a post-readiness physical pool connection");
                assertEquals("30",seen.get(seen.size()-1).get("connectTimeout"));
                assertEquals("40",seen.get(seen.size()-1).get("loginTimeout"));
            } finally { second.close();first.close(); }
        }
    }

    @Test
    void preparationPermitsStayChargedUntilSlowResourceTeardownCompletes() throws Exception {
        String url="jdbc:att-load:slow-resource-close";
        DbHelperConfig helper=db("orders",url,2,500L,"rollback");
        FrameworkConfig config=framework(helper);
        LoadTarget target=target(project(),template(singleQuery()));
        java.util.concurrent.Semaphore budget=new java.util.concurrent.Semaphore(1,true);
        LoadRunResources first=new LoadRunResources(tempDir,config,new att.exec.IbmMqClientFactory(),5000L,budget);
        new IterationExecutor(tempDir,config,target,first,tempDir.resolve("output-slow-close-first"));
        first.prepareResources();
        assertEquals(0,budget.availablePermits());

        driver.blockCloseUrl=url;
        driver.closeEntered=new CountDownLatch(1);
        driver.releaseClose=new CountDownLatch(1);
        ExecutorService closer=Executors.newSingleThreadExecutor();
        LoadRunResources overlapping=new LoadRunResources(tempDir,config,new att.exec.IbmMqClientFactory(),150L,budget);
        try {
            Future<?> close=closer.submit(first::close);
            assertTrue(driver.closeEntered.await(5,TimeUnit.SECONDS),"close must reach the physical DB session");
            assertEquals(0,budget.availablePermits(),"the active teardown must retain its reservation");
            new IterationExecutor(tempDir,config,target,overlapping,tempDir.resolve("output-slow-close-overlap"));
            assertThrows(LoadResourcePreparationException.class,overlapping::prepareResources,
                    "a new run must not acquire capacity while the old physical session is still open");
            assertEquals(0,budget.availablePermits());

            driver.releaseClose.countDown();
            close.get(5,TimeUnit.SECONDS);
            assertEquals(1,budget.availablePermits(),"the reservation is returned after teardown completes");
        } finally {
            driver.releaseClose.countDown();
            closer.shutdownNow();
            first.close();overlapping.close();
        }
    }

    @Test
    void closedSchedulerEmitsNoIterationUntilDbReadinessAndLoadStart() throws Exception {
        String url = "jdbc:att-load:barrier";
        DbHelperConfig helper = new DbHelperConfig("orders", "orders", "DB", url, "user", "secret", "",
                Collections.<String, String>emptyMap(), false, "driverDefault", 5, "case", "rollback",
                10, 1024, 8192, "full", "masked", 5, 3, 1000L, tempDir.resolve("db.yaml"));
        String unusedUrl = "jdbc:att-load:unused-helper";
        FrameworkConfig config = framework(helper, db("unused", unusedUrl, 2, 500L, "rollback"));
        LoadTarget target = target(project(), template(singleQuery()));
        List<String> timeline = Collections.synchronizedList(new ArrayList<String>());

        try (LoadRunResources resources = new LoadRunResources(tempDir, config)) {
            resources.setPreparationObserver(event -> {
                String name = String.valueOf(event.get("event"));
                timeline.add(name);
                if ("LOAD_RESOURCES_READY".equals(name)) {
                    FakeState state = driver.states.get(url);
                    assertTrue(state.connections >= 3, "minIdle sessions must be ready before the scheduler gate opens");
                    assertEquals(0, state.queryConnectionIds.size(), "readiness must not issue business SQL");
                    assertEquals(0, state.commits);
                    assertEquals(0, state.rollbacks);
                }
            });
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources,
                    tempDir.resolve("output-barrier"));
            LoadScenario scenario = new LoadScenario(tempDir.resolve("scenario.yaml"), "template", "DB",
                    Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                    LoadScenario.Model.CLOSED, 1, 0.0, null, java.time.Duration.ZERO, java.time.Duration.ZERO,
                    java.time.Duration.ofMillis(100), java.time.Duration.ZERO, java.time.Duration.ZERO, 0, "",
                    Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());
            LoadEventListener listener = new LoadEventListener() {
                @Override public void onEvent(LoadEvent event) { timeline.add("ITERATION_EVENT"); }
            };

            new ClosedVuScheduler(scenario, executor, "barrier-run", listener).run();

            int ready = timeline.indexOf("LOAD_RESOURCES_READY");
            int loadStart = timeline.indexOf("LOAD_START");
            int firstIteration = timeline.indexOf("ITERATION_EVENT");
            assertTrue(ready >= 0 && loadStart > ready && firstIteration > loadStart, timeline.toString());
            assertTrue(driver.states.get(url).queryConnectionIds.size() > 0, "business SQL should run after readiness");
            assertTrue(!driver.states.containsKey(unusedUrl), "unreferenced helpers must not be warmed");
            assertEquals(3, resources.dbPool("orders").metrics().get("minIdle"));
            assertEquals(5, resources.dbPool("orders").metrics().get("maxSize"));
        }
    }

    @Test
    void failedDbReadinessDoesNotReleaseSchedulerAndClosesResources() throws Exception {
        String url = "jdbc:att-load:readiness-failure";
        driver.failUrl = url;
        DbHelperConfig helper = db("orders", url, 2, 500L, "rollback");
        FrameworkConfig config = framework(helper);
        LoadTarget target = target(project(), template(singleQuery()));
        List<String> timeline = Collections.synchronizedList(new ArrayList<String>());

        try (LoadRunResources resources = new LoadRunResources(tempDir, config)) {
            resources.setPreparationObserver(event -> timeline.add(String.valueOf(event.get("event"))));
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources,
                    tempDir.resolve("output-readiness-failure"));
            LoadScenario scenario = new LoadScenario(tempDir.resolve("scenario-failure.yaml"), "template", "DB",
                    Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                    LoadScenario.Model.CLOSED, 1, 0.0, null, java.time.Duration.ZERO, java.time.Duration.ZERO,
                    java.time.Duration.ofMillis(100), java.time.Duration.ZERO, java.time.Duration.ZERO, 0, "",
                    Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());
            ClosedVuScheduler scheduler = new ClosedVuScheduler(scenario, executor, "readiness-failure-run",
                    (LoadEventListener) event -> timeline.add("ITERATION_EVENT"));

            assertThrows(LoadResourcePreparationException.class, scheduler::run);
            assertTrue(resources.isClosed());
            assertTrue(timeline.contains("LOAD_PREPARATION_FAILED"), timeline.toString());
            assertTrue(!timeline.contains("LOAD_START"), timeline.toString());
            assertTrue(!timeline.contains("ITERATION_EVENT"), timeline.toString());
        }
    }

    @Test
    void blockedDbConnectionHonorsOverallPreparationDeadlineAndNeverReleasesScheduler() throws Exception {
        String url = "jdbc:att-load:blocked-connect";
        driver.blockConnectUrl = url;
        DbHelperConfig helper = db("orders", url, 2, 500L, "rollback");
        FrameworkConfig config = framework(helper);
        LoadTarget target = target(project(), template(singleQuery()));
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        int permitsBefore = LoadRunResources.availablePreparationPermits();
        long startedNanos = System.nanoTime();

        try (LoadRunResources resources = new LoadRunResources(tempDir, config, new att.exec.IbmMqClientFactory(), 400L)) {
            resources.setPreparationObserver(event -> events.add(new LinkedHashMap<String, Object>(event)));
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources,
                    tempDir.resolve("output-blocked-readiness"));
            LoadScenario scenario = new LoadScenario(tempDir.resolve("scenario-blocked.yaml"), "template", "DB",
                    Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                    LoadScenario.Model.CLOSED, 1, 0.0, null, java.time.Duration.ZERO, java.time.Duration.ZERO,
                    java.time.Duration.ofMillis(100), java.time.Duration.ZERO, java.time.Duration.ZERO, 0, "",
                    Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());
            ClosedVuScheduler scheduler = new ClosedVuScheduler(scenario, executor, "blocked-db-run",
                    (LoadEventListener) event -> events.add(Collections.<String, Object>singletonMap("event", "ITERATION_EVENT")));

            assertThrows(LoadResourcePreparationException.class, scheduler::run);
            assertTrue(resources.isClosed());
        } finally {
            driver.releaseConnect.countDown();
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        assertTrue(driver.connectEntered.await(1L, TimeUnit.SECONDS), "the test must reach the blocking DB connect");
        assertTrue(driver.connectInterrupted, "closing the failed run must interrupt the in-flight JDBC connect");
        assertTrue(elapsedMs < 2_000L, "the overall preparation deadline should bound cancellation and cleanup: " + elapsedMs + " ms");
        long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (LoadRunResources.availablePreparationPermits() != permitsBefore && System.nanoTime() < cleanupDeadline)
            Thread.sleep(10L);
        assertEquals(permitsBefore, LoadRunResources.availablePreparationPermits(),
                "preparation permits return after timeout cleanup finishes");
        assertTrue(events.stream().anyMatch(event -> "LOAD_PREPARATION_FAILED".equals(event.get("event"))), events.toString());
        assertTrue(events.stream().noneMatch(event -> "LOAD_RESOURCES_READY".equals(event.get("event"))), events.toString());
        assertTrue(events.stream().noneMatch(event -> "LOAD_START".equals(event.get("event"))), events.toString());
        assertTrue(events.stream().noneMatch(event -> "ITERATION_EVENT".equals(event.get("event"))), events.toString());
        Map<String, Object> failed = events.stream().filter(event -> "LOAD_PREPARATION_FAILED".equals(event.get("event")))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("PREPARATION_TIMEOUT", failed.get("errorType"));
    }

    @Test
    void dynamicDbActionIsReportedAsSkippedUntilFirstUse() throws Exception {
        String configuredUrl = "jdbc:att-load:dynamic-helper";
        FrameworkConfig config = framework(db("orders", configuredUrl, 2, 500L, "rollback"));
        List<String> timeline = new ArrayList<String>();
        TemplateAction dynamicAction = new TemplateAction("dynamicQuery", map(
                "type", "db", "db", "${EXEC.INPUT.helper}", "query", map("sql", "select ONE")), "att-template/v3.4");
        LoadTarget target = target(project(), template(Collections.singletonList(dynamicAction)));

        try (LoadRunResources resources = new LoadRunResources(tempDir, config)) {
            resources.setPreparationObserver(event -> timeline.add(String.valueOf(event.get("event"))));
            new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output-dynamic"));
            resources.prepareResources();

            assertTrue(timeline.contains("LOAD_RESOURCE_SKIPPED_DYNAMIC"), timeline.toString());
            assertTrue(timeline.contains("LOAD_RESOURCES_READY"), timeline.toString());
            assertTrue(!driver.states.containsKey(configuredUrl), "dynamic references must not warm every configured helper");
        }
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

    @Test
    void closingLoadRunClosesAnActiveDatabasePoolAndRejectsFurtherBorrows() throws Exception {
        String url = "jdbc:att-load:shutdown";
        FrameworkConfig config = framework(db("orders", url, 1, 500L, "rollback"));
        LoadRunResources resources = new LoadRunResources(tempDir, config);
        HikariDbPool pool = resources.dbPool("orders");
        resources.db().beginCase();
        assertTrue(resources.db().execute("orders", "query", "select ONE", "inline",
                Collections.emptyList(), "shutdown-case").success());
        assertEquals(1, pool.active());

        resources.close();

        assertTrue(resources.isClosed());
        assertTrue(driver.states.get(url).closed);
        assertEquals(0, pool.active());
        assertThrows(SQLException.class, pool::borrow);
        assertTrue(driver.states.get(url).closes >= 1);
        int closesAfterFirstShutdown = driver.states.get(url).closes;
        resources.close();
        assertEquals(closesAfterFirstShutdown, driver.states.get(url).closes);
        assertThrows(IllegalStateException.class, resources::db);
    }

    private void runOneCase(String url, String onEnd) throws Exception {
        FrameworkConfig config = framework(db("orders", url, 1, 500L, onEnd));
        LoadTarget target = target(project(), template(singleQuery()));
        try (LoadRunResources resources = new LoadRunResources(tempDir, config)) {
            IterationResult result = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output-" + onEnd))
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
        return new StageTemplate("DB", directory, actions, "att-template/v3.4", directory.resolve("template.yaml"));
    }

    private List<TemplateAction> singleQuery() {
        return Collections.singletonList(new TemplateAction("query", map(
                "type", "db", "db", "orders", "query", map("sql", "select ONE")), "att-template/v3.4"));
    }

    private List<TemplateAction> twoQueries() {
        List<TemplateAction> actions = new ArrayList<TemplateAction>();
        actions.addAll(singleQuery());
        actions.add(new TemplateAction("queryAgain", map(
                "type", "db", "db", "orders", "query", map("sql", "select ONE")), "att-template/v3.4"));
        return actions;
    }

    private IterationRequest request(String id) {
        return IterationRequest.closed("load-db", id, 1, "STEADY", java.time.Instant.now(), "VU-1", Collections.emptyMap());
    }

    private FrameworkConfig framework(DbHelperConfig... configuredHelpers) {
        Map<String, DbHelperConfig> helpers = new LinkedHashMap<String, DbHelperConfig>();
        for (DbHelperConfig helper : configuredHelpers) helpers.put(helper.id(), helper);
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
        final Map<String, List<Map<String, String>>> connectionProperties = new java.util.concurrent.ConcurrentHashMap<String, List<Map<String, String>>>();
        volatile boolean blockQueries;
        volatile String blockConnectUrl;
        volatile boolean connectInterrupted;
        final CountDownLatch queryEntered = new CountDownLatch(1);
        final CountDownLatch releaseQuery = new CountDownLatch(1);
        final CountDownLatch queryFinished = new CountDownLatch(1);
        final CountDownLatch connectEntered = new CountDownLatch(1);
        final CountDownLatch releaseConnect = new CountDownLatch(1);
        volatile String blockCloseUrl;
        volatile CountDownLatch closeEntered = new CountDownLatch(1);
        volatile CountDownLatch releaseClose = new CountDownLatch(1);
        volatile String failUrl;

        @Override public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) return null;
            Map<String, String> snapshot=new LinkedHashMap<String, String>();
            for(String key:new String[]{"connectTimeout","loginTimeout"})snapshot.put(key,info.getProperty(key));
            connectionProperties.computeIfAbsent(url,ignored->new java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()).add(snapshot);
            if (url.equals(failUrl)) throw new SQLException("simulated connection refusal", "08001");
            if (url.equals(blockConnectUrl)) {
                connectEntered.countDown();
                try { releaseConnect.await(); }
                catch (InterruptedException interrupted) {
                    connectInterrupted = true;
                    Thread.currentThread().interrupt();
                    throw new SQLException("simulated JDBC connection interrupted", "08001", interrupted);
                }
            }
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
                if ("close".equals(name)) {
                    if(url.equals(blockCloseUrl)) {
                        closeEntered.countDown();
                        try { releaseClose.await(); }
                        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new SQLException("simulated JDBC close interrupted",interrupted); }
                    }
                    captured.closes++; captured.closed = true; return null;
                }
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

        @Override public boolean acceptsURL(String url) {
            return url != null && (url.startsWith("jdbc:att-load:")||url.startsWith("jdbc:postgresql:att-load-"));
        }
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
