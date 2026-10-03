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
    private final java.util.concurrent.ConcurrentMap<String, String> executionIds = new java.util.concurrent.ConcurrentHashMap<String, String>();
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
        long sequence = executionSequence.incrementAndGet();
        if (sequence <= 0L) throw new IllegalStateException("Load execution identity sequence exhausted");
        return runId + "-execution-" + sequence;
    }
    /** Atomically reserves a generated identity; collisions fail instead of silently changing the ID. */
    public void reserveExecutionId(String executionId, IterationRequest request) {
        ensureOpen();
        String owner = request.workloadId() + "/" + request.iterationId();
        String existing = executionIds.putIfAbsent(executionId.toLowerCase(java.util.Locale.ROOT), owner);
        if (existing != null) throw new IllegalArgumentException("Duplicate EXEC.ID '" + executionId + "' in Load run; it is already assigned to " + existing);
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
        result.put("render", renderPlans.stats().toMap());
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
    public boolean isClosed() { return closed.get(); }
    public void ensureOpen() { if (closed.get()) throw new IllegalStateException("Load run resources are closed"); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) { http.close(); mqFactory.close(); db.closeAll(); dbProvider.close(); }
    }
}
