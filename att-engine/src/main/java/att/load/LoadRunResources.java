package att.load;

import att.config.FrameworkConfig;
import att.exec.DbHelperExecutor;
import att.exec.MqHelperExecutor;
import att.exec.IbmMqClientFactory;
import att.exec.MqTransport;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Run-scoped owner for resources shared by load iterations. */
public final class LoadRunResources implements AutoCloseable {
    private final FrameworkConfig config;
    private final DbHelperExecutor db;
    private final HikariDbConnectionProvider dbProvider;
    private final MqHelperExecutor mq;
    private final att.exec.HttpHelperExecutor http;
    private final PooledMqTransportFactory mqFactory;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong executionSequence = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicReference<String> identityRun = new java.util.concurrent.atomic.AtomicReference<String>();
    private final AtomicLong lastResourceMetricSampleNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong resourceMetricSamples = new AtomicLong();
    private final java.util.Set<Path> initializedExecutionNamespaces = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final att.template.SequenceService sequences = new att.template.SequenceService();
    private final att.template.RenderPlanCache renderPlans = new att.template.RenderPlanCache();
    private final java.util.concurrent.CopyOnWriteArrayList<att.template.CompiledExecutionPlan> executionPlans =
            new java.util.concurrent.CopyOnWriteArrayList<att.template.CompiledExecutionPlan>();

    public LoadRunResources(Path projectRoot, FrameworkConfig config) {
        this(projectRoot, config, new IbmMqClientFactory());
    }

    /** Creates load resources with an injectable MQ transport boundary for integration tests. */
    LoadRunResources(Path projectRoot, FrameworkConfig config, MqTransport.Factory mqTransportFactory) {
        this.config = config;
        this.dbProvider = new HikariDbConnectionProvider();
        this.db = new DbHelperExecutor(projectRoot, config, dbProvider);
        this.mqFactory = new PooledMqTransportFactory(mqTransportFactory, 20, 2000L);
        this.mq = new MqHelperExecutor(projectRoot, config, mqFactory);
        this.http = new att.exec.HttpHelperExecutor(projectRoot, config);
    }

    public DbHelperExecutor db() { ensureOpen(); return db; }
    public MqHelperExecutor mq() { ensureOpen(); return mq; }
    public att.exec.HttpHelperExecutor http() { ensureOpen(); return http; }
    public att.template.SequenceService sequences() { ensureOpen(); return sequences; }
    public att.template.RenderPlanCache renderPlans() { ensureOpen(); return renderPlans; }
    public void registerExecutionPlan(att.template.CompiledExecutionPlan plan) {
        ensureOpen();
        if (plan == null) throw new IllegalArgumentException("Load execution plan is required");
        executionPlans.addIfAbsent(plan);
    }
    /** Resolves Render sources before the workload start gate, shared by all targets in this Load run. */
    public void prepareRenderPlans(LoadTarget target) throws Exception {
        ensureOpen();
        renderPlans.freeze(target.template(), target.flows() == null ? null : target.flows().freezeFor(target.template()));
    }
    public String nextDefaultExecutionId(String runId) {
        return nextExecutionId(runId);
    }
    public String nextExecutionId(String runId) {
        ensureOpen();
        String owner = identityRun.get();
        if (owner == null) {
            LoadExecutionIdPattern.requireSafe(runId);
            LoadExecutionIdPattern.requireSafe(runId + "-execution-" + Long.MAX_VALUE);
            identityRun.compareAndSet(null, runId);
            owner = identityRun.get();
        }
        if (!owner.equals(runId)) throw new IllegalArgumentException("Load resources belong to a different run identity");
        long sequence;
        do {
            sequence = executionSequence.get();
            if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Load execution identity sequence exhausted");
        } while (!executionSequence.compareAndSet(sequence, sequence + 1));
        return runId + "-execution-" + (sequence + 1);
    }
    /** Validates an isolated run output namespace once, before any iteration writes into it. */
    public void initializeExecutionNamespace(Path runDirectory) throws IOException {
        ensureOpen();
        Path run = runDirectory.toAbsolutePath().normalize();
        LoadExecutionIdPattern.requireSafe(run.getFileName().toString() + "-execution-" + Long.MAX_VALUE);
        if (Files.isSymbolicLink(run) || Files.isSymbolicLink(run.getParent()))
            throw new IllegalArgumentException("Load output namespace cannot follow symlinks: " + run);
        // Schedulers initialize before starting workers. Standalone executor
        // callers retain a lock-free fast path after that one-time validation.
        if (initializedExecutionNamespaces.contains(run)) return;
        synchronized (initializedExecutionNamespaces) {
            if (initializedExecutionNamespaces.contains(run)) return;
            for (String name : new String[] {"samples", "failures", "executions", "report", ".exec-id-reservations"}) {
                Path directory = run.resolve(name);
                if (Files.exists(directory)) {
                    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory))
                        throw new IllegalArgumentException("Load output namespace is not a directory: " + directory);
                    try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                        if (entries.iterator().hasNext())
                            throw new IllegalArgumentException("Load output namespace already contains retained output: " + directory);
                    }
                }
            }
            for (String name : new String[] {"load-summary.json", "load-summary.yaml"})
                if (Files.exists(run.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("Load output namespace contains a retained summary: " + run);
            Files.createDirectories(run);
            // One durable claim per Run, never per iteration. A restarted/second owner must use a new Run ID.
            Files.write(run.resolve(".execution-owner"), new byte[] {1},
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            initializedExecutionNamespaces.add(run);
        }
    }

    public HikariDbPool dbPool(String helperId, att.config.FrameworkConfig config) {
        ensureOpen();
        att.config.DbHelperConfig helper = config.dbHelper(helperId);
        if (helper == null) throw new IllegalArgumentException("Unknown dbhelper instance: " + helperId);
        return dbProvider.pool(helper);
    }
    public HikariDbPool dbPool(String helperId) { return dbPool(helperId, config); }
    public LoadResourcePool<att.exec.MqTransport.Connection> mqPool(String helperId) {
        ensureOpen();
        LoadResourcePool<att.exec.MqTransport.Connection> pool = mqFactory.pool(helperId);
        if (pool == null) throw new IllegalArgumentException("Unknown or unopened mqhelper instance: " + helperId);
        return pool;
    }
    public Map<String, Object> metrics() {
        ensureOpen();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("db", dbProvider.metrics()); result.put("mq", mqFactory.metrics());
        result.put("http", http.metrics());
        result.put("resourceMetricSamples", resourceMetricSamples.get());
        result.put("render", renderPlans.stats().toMap());
        Map<String, Object> ids = new LinkedHashMap<String, Object>();
        ids.put("generatedExecutionIds", executionSequence.get());
        ids.put("executionIdCollisionTrackingSize", 0L); // compatibility metric: no collision registry
        result.put("executionIds", ids);
        long actionPlans = 0L, evaluations = 0L;
        for (att.template.CompiledExecutionPlan plan : executionPlans) {
            actionPlans += plan.actionCount(); evaluations += plan.evaluations();
        }
        Map<String, Object> execution = new LinkedHashMap<String, Object>();
        execution.put("executionPlansCompiled", executionPlans.size());
        execution.put("actionPlansCompiled", actionPlans);
        execution.put("actionEvaluations", evaluations);
        result.put("execution", java.util.Collections.unmodifiableMap(execution));
        return result;
    }
    /** Rate-limited, allocation-light observation of shared HTTP pool peaks. */
    public void sampleResourceMetrics() {
        ensureOpen();
        sampleResourceMetricsAt(System.nanoTime());
    }
    boolean sampleResourceMetricsAt(long nowNanos) {
        ensureOpen();
        while (true) {
            long previous = lastResourceMetricSampleNanos.get();
            if (previous != Long.MIN_VALUE && nowNanos - previous < GeneratorTelemetry.SAMPLE_INTERVAL_NANOS)
                return false;
            if (lastResourceMetricSampleNanos.compareAndSet(previous, nowNanos)) {
                http.observeMetrics();
                resourceMetricSamples.incrementAndGet();
                return true;
            }
        }
    }
    long resourceMetricSamples() { return resourceMetricSamples.get(); }
    public boolean isClosed() { return closed.get(); }
    public void ensureOpen() { if (closed.get()) throw new IllegalStateException("Load run resources are closed"); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) { http.close(); mqFactory.close(); db.closeAll(); dbProvider.close(); }
    }
}
