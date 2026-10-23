package att.server;

import att.config.DbHelperConfig;
import att.config.FrameworkConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.TestCase;
import att.exec.DbConnectionProvider;
import att.exec.DbHelperExecutor;
import att.load.HikariDbPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in fixed-arrival benchmark for pooled DB statement connection occupancy. */
class DbOccupancyPerformanceBenchmark {
    @TempDir Path temp;

    @Test void writesOptInFixedDemandDatabaseOccupancyBenchmark() throws Exception {
        assumeTrue(Boolean.getBoolean("att.server.benchmark.dbOccupancy"), "opt-in local benchmark");
        int arrivals = integerProperty("att.server.benchmark.dbOccupancy.arrivals", 250, 10);
        int arrivalRate = integerProperty("att.server.benchmark.dbOccupancy.arrivalRate", 55, 1);
        int tailMs = integerProperty("att.server.benchmark.dbOccupancy.nonDbTailMs", 20, 0);
        long timeoutMs = integerProperty("att.server.benchmark.dbOccupancy.connectionTimeoutMs", 2000, 250);
        Path output = Paths.get(System.getProperty("att.server.benchmark.dbOccupancy.output",
                "target/db-occupancy-benchmark.json")).toAbsolutePath().normalize();
        benchmark(temp.resolve("measurements"), output, arrivals, arrivalRate, tailMs, timeoutMs, new int[]{1, 4});
        System.out.println("DB occupancy benchmark written to " + output);
    }

    private Map<String, Object> benchmark(Path root, Path output, int arrivals, int arrivalRate,
                                          int tailMs, long timeoutMs, int[] poolSizes) throws Exception {
        Files.createDirectories(root);
        List<Map<String, Object>> scenarios = new ArrayList<Map<String, Object>>();
        for (String scope : Arrays.asList("statement", "case")) {
            for (String onEnd : Arrays.asList("commit", "rollback")) {
                for (int poolSize : poolSizes) {
                    scenarios.add(measure(root.resolve(scope + "-" + onEnd + "-pool-" + poolSize),
                            scope, onEnd, poolSize, arrivals, arrivalRate, tailMs, timeoutMs));
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", "att-db-occupancy-benchmark/v1");
        Map<String, Object> environment = new LinkedHashMap<String, Object>();
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("javaVm", System.getProperty("java.vm.name"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        environment.put("commit", System.getProperty("att.benchmark.commit", "unknown"));
        report.put("environment", environment);
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("scope", Arrays.asList("statement", "case"));
        settings.put("onEnd", Arrays.asList("commit", "rollback"));
        settings.put("poolSizes", Arrays.stream(poolSizes).boxed().toList());
        settings.put("arrivalsPerScenario", arrivals);
        settings.put("offeredArrivalRatePerSecond", arrivalRate);
        settings.put("nonDatabaseTailMs", tailMs);
        settings.put("poolConnectionTimeoutMs", timeoutMs);
        settings.put("operationMix", "query SELECT 1; fixed non-DB tail; INSERT one unique row; finalize Case");
        settings.put("warmup", "Hikari pool connections opened and SELECT 1 executed before offered arrivals");
        report.put("settings", settings);
        report.put("scenarios", scenarios);
        Files.createDirectories(output.getParent());
        Files.writeString(output, ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return report;
    }

    private Map<String, Object> measure(Path root, String scope, String onEnd, int poolSize, int arrivals,
                                        int arrivalRate, int tailMs, long timeoutMs) throws Exception {
        Files.createDirectories(root);
        String helperId = "db";
        DbHelperConfig db = new DbHelperConfig(helperId, "benchmark", "fixed-demand benchmark",
                "jdbc:h2:mem:db_occupancy_" + scope + "_" + onEnd + "_" + poolSize + ";DB_CLOSE_DELAY=-1",
                "sa", "", "org.h2.Driver", Collections.<String, String>emptyMap(), false,
                "driverDefault", 5, scope, onEnd, 10, 1024, 8192, "hash", "masked",
                poolSize, poolSize, timeoutMs, null);
        HikariDbPool pool = new HikariDbPool(db);
        try {
            initialize(pool, poolSize);
            JdbcMetricsProvider provider = new JdbcMetricsProvider(pool);
            FrameworkConfig config = config(root, db);
            int workersCount = Math.max(16, poolSize * 4);
            ExecutorService workers = Executors.newFixedThreadPool(workersCount);
            AtomicInteger activePeak = new AtomicInteger();
            AtomicInteger waitingPeak = new AtomicInteger();
            ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "db-occupancy-metrics"); thread.setDaemon(true); return thread;
            });
            sampler.scheduleAtFixedRate(() -> {
                activePeak.accumulateAndGet(pool.active(), Math::max);
                waitingPeak.accumulateAndGet(pool.waiting(), Math::max);
            }, 0, 1, TimeUnit.MILLISECONDS);

            AtomicInteger failures = new AtomicInteger();
            List<Long> caseNanos = new CopyOnWriteArrayList<Long>();
            long interval = TimeUnit.SECONDS.toNanos(1) / arrivalRate;
            long cpuBefore = processCpuTime();
            long heapBefore = usedHeap();
            long offeredAt = System.nanoTime();
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int index = 0; index < arrivals; index++) {
                long scheduledArrival = offeredAt + index * interval;
                parkUntil(scheduledArrival);
                final int requestId = index;
                futures.add(workers.submit(new Callable<Void>() {
                    @Override public Void call() {
                        try {
                            executeCase(root, requestId, config, provider, helperId, tailMs);
                        } catch (Throwable error) {
                            failures.incrementAndGet();
                        } finally {
                            caseNanos.add(System.nanoTime() - scheduledArrival);
                        }
                        return null;
                    }
                }));
            }
            long arrivalEnded = System.nanoTime();
            for (Future<?> future : futures) future.get(2, TimeUnit.MINUTES);
            long finishedAt = System.nanoTime();
            workers.shutdown(); workers.awaitTermination(10, TimeUnit.SECONDS);
            sampler.shutdownNow(); sampler.awaitTermination(5, TimeUnit.SECONDS);

            long committedRows;
            try (Connection connection = pool.borrow(); PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM audit"); ResultSet result = statement.executeQuery()) {
                result.next(); committedRows = result.getLong(1);
            }
            long expectedRows = "commit".equals(onEnd) ? arrivals : 0L;
            if (committedRows != expectedRows) throw new AssertionError("Unexpected benchmark fixture state: " + scope + "/" + onEnd + " expected=" + expectedRows + " actual=" + committedRows);
            if (failures.get() != 0) throw new AssertionError("DB occupancy benchmark had " + failures.get() + " failed cases for " + scope + "/" + onEnd + "/pool-" + poolSize);

            double offeredDurationSeconds = Math.max(0.001, (arrivalEnded - offeredAt) / 1_000_000_000.0);
            double completedDurationSeconds = Math.max(0.001, (finishedAt - offeredAt) / 1_000_000_000.0);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("scope", scope); result.put("onEnd", onEnd); result.put("poolSize", poolSize);
            result.put("arrivals", arrivals); result.put("arrivalsPerSecondMeasured", arrivals / offeredDurationSeconds);
            result.put("completedCasesPerSecond", arrivals / completedDurationSeconds);
            result.put("completionWallMs", millis(finishedAt - offeredAt));
            result.put("completedCases", arrivals); result.put("failedCases", failures.get());
            result.put("committedRows", committedRows);
            result.put("caseLatencyP50Ms", percentile(caseNanos, 0.50));
            result.put("caseLatencyP95Ms", percentile(caseNanos, 0.95));
            result.put("connectionAcquireWaitP50Ms", percentile(provider.acquireWaitNanos, 0.50));
            result.put("connectionAcquireWaitP95Ms", percentile(provider.acquireWaitNanos, 0.95));
            result.put("connectionHoldP50Ms", percentile(provider.connectionHoldNanos, 0.50));
            result.put("connectionHoldP95Ms", percentile(provider.connectionHoldNanos, 0.95));
            result.put("jdbcExecuteP50Ms", percentile(provider.jdbcExecuteNanos, 0.50));
            result.put("jdbcExecuteP95Ms", percentile(provider.jdbcExecuteNanos, 0.95));
            result.put("borrowCount", provider.borrowCount.get());
            result.put("borrowTimeouts", provider.borrowTimeouts.get());
            result.put("activePeakSampled", activePeak.get()); result.put("waitingPeakSampled", waitingPeak.get());
            result.put("processCpuMs", cpuDeltaMs(cpuBefore));
            result.put("heapUsedBeforeBytes", heapBefore); result.put("heapUsedAfterBytes", usedHeap());
            result.put("rawCaseLatencyMs", millisList(caseNanos));
            result.put("rawConnectionAcquireWaitMs", millisList(provider.acquireWaitNanos));
            result.put("rawConnectionHoldMs", millisList(provider.connectionHoldNanos));
            result.put("rawJdbcExecuteMs", millisList(provider.jdbcExecuteNanos));
            return result;
        } finally {
            pool.close();
        }
    }

    private void executeCase(Path root, int requestId, FrameworkConfig config,
                             JdbcMetricsProvider provider, String helperId, int tailMs) throws Exception {
        Path logPath = root.resolve("case-" + requestId + ".log");
        TestCase test = new TestCase(2, "benchmark", "db", "case-" + requestId,
                Collections.<String>emptyList(), Collections.<String, Object>emptyMap(),
                Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, root, "RUN", root, logPath);
        DbHelperExecutor executor = new DbHelperExecutor(root, config, provider);
        boolean finalized = false;
        try (CaseExecutionLog log = CaseExecutionLog.discarding(logPath)) {
            executor.beginCase();
            requireSuccess(executor.execute(helperId, "query", "SELECT 1 AS V", "benchmark",
                    Collections.emptyList(), "query-" + requestId));
            if (tailMs > 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(tailMs));
            requireSuccess(executor.execute(helperId, "update", "INSERT INTO audit(request_id) VALUES (" + requestId + ")",
                    "benchmark", Collections.emptyList(), "update-" + requestId));
            if (!executor.finishCase(context, log).isEmpty()) throw new IllegalStateException("DB case finalization failed");
            finalized = true;
        } finally {
            if (!finalized) executor.abortCase();
            executor.close();
        }
    }

    private void initialize(HikariDbPool pool, int poolSize) throws Exception {
        List<Connection> warm = new ArrayList<Connection>();
        try {
            for (int index = 0; index < poolSize; index++) warm.add(pool.borrow());
            try (PreparedStatement statement = warm.get(0).prepareStatement("CREATE TABLE audit(request_id BIGINT PRIMARY KEY)")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = warm.get(0).prepareStatement("SELECT 1")) { statement.executeQuery().close(); }
        } finally {
            for (Connection connection : warm) connection.close();
        }
    }

    private FrameworkConfig config(Path root, DbHelperConfig db) {
        return new FrameworkConfig(root, root, root, "BENCHMARK", 10000, root, root,
                Collections.emptyMap(), Collections.singletonMap(db.id(), db), null, null,
                null, "", "", null, null, 1, "ignore", "", false,
                att.config.ProcessOutputConfig.defaults());
    }

    private static void requireSuccess(att.exec.DbInvocationResult result) {
        if (!result.success()) throw new IllegalStateException("DB operation failed");
    }

    private static void parkUntil(long deadline) {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
    }

    private static int integerProperty(String name, int fallback, int minimum) {
        int parsed = Integer.getInteger(name, fallback);
        if (parsed < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum);
        return parsed;
    }

    private static List<Double> millisList(List<Long> values) {
        List<Double> result = new ArrayList<Double>(values.size());
        for (Long value : values) result.add(value / 1_000_000.0);
        return result;
    }

    private static double percentile(List<Long> raw, double fraction) {
        if (raw.isEmpty()) return 0.0;
        long[] values = new long[raw.size()];
        for (int index = 0; index < raw.size(); index++) values[index] = raw.get(index);
        Arrays.sort(values);
        return values[Math.min(values.length - 1, (int) Math.ceil(fraction * values.length) - 1)] / 1_000_000.0;
    }

    private static double millis(long nanos) { return nanos / 1_000_000.0; }
    private static long usedHeap() { Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
    private static long processCpuTime() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) return -1L;
        long total = 0L;
        for (long id : bean.getAllThreadIds()) {
            long time = bean.getThreadCpuTime(id);
            if (time > 0L) total += time;
        }
        return total;
    }
    private static double cpuDeltaMs(long before) { long after = processCpuTime(); return before < 0 || after < 0 ? -1.0 : (after - before) / 1_000_000.0; }

    private static final class JdbcMetricsProvider implements DbConnectionProvider {
        private final HikariDbPool pool;
        final AtomicLong borrowCount = new AtomicLong();
        final AtomicLong borrowTimeouts = new AtomicLong();
        final List<Long> acquireWaitNanos = new CopyOnWriteArrayList<Long>();
        final List<Long> connectionHoldNanos = new CopyOnWriteArrayList<Long>();
        final List<Long> jdbcExecuteNanos = new CopyOnWriteArrayList<Long>();
        JdbcMetricsProvider(HikariDbPool pool) { this.pool = pool; }

        @Override public Connection open(DbHelperConfig config) throws Exception {
            long acquireStartedAt = System.nanoTime();
            Connection raw;
            try {
                raw = pool.borrow(); borrowCount.incrementAndGet();
            } catch (Exception error) {
                if (error instanceof DbConnectionProvider.PoolTimeoutException) borrowTimeouts.incrementAndGet();
                throw error;
            } finally {
                acquireWaitNanos.add(System.nanoTime() - acquireStartedAt);
            }
            final long acquiredAt = System.nanoTime();
            AtomicInteger closed = new AtomicInteger();
            InvocationHandler handler = (proxy, method, args) -> {
                if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                    if (closed.compareAndSet(0, 1)) {
                        try { raw.close(); }
                        finally { connectionHoldNanos.add(System.nanoTime() - acquiredAt); }
                    }
                    return null;
                }
                Object returned = invoke(raw, method, args);
                if (returned instanceof PreparedStatement) return trackedStatement((PreparedStatement) returned);
                return returned;
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement trackedStatement(PreparedStatement raw) {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith("execute")) {
                    long before = System.nanoTime();
                    try { return invoke(raw, method, args); }
                    finally { jdbcExecuteNanos.add(System.nanoTime() - before); }
                }
                return invoke(raw, method, args);
            };
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }

        @Override public void close() { /* The scenario owns and closes the shared pool. */ }
    }
}
