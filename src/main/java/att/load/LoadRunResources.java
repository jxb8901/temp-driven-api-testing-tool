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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
    private final AtomicLong customExecutionIdsTracked = new AtomicLong();
    private final java.util.Set<Path> initializedExecutionNamespaces = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final att.template.SequenceService sequences = new att.template.SequenceService();
    private final att.template.RenderPlanCache renderPlans = new att.template.RenderPlanCache();

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
    /** Validates an isolated run output namespace once, before any iteration writes into it. */
    public synchronized void initializeExecutionNamespace(Path runDirectory) throws IOException {
        ensureOpen();
        Path run = runDirectory.toAbsolutePath().normalize();
        if (initializedExecutionNamespaces.contains(run)) return;
        for (String name : new String[] {"samples", "failures", "executions"}) {
            Path directory = run.resolve(name);
            if (Files.exists(directory)) {
                if (!Files.isDirectory(directory))
                    throw new IllegalArgumentException("Load output namespace is not a directory: " + directory);
                try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                    if (entries.iterator().hasNext())
                        throw new IllegalArgumentException("Load output namespace already contains retained output: " + directory);
                }
            }
        }
        initializedExecutionNamespaces.add(run);
    }

    /** Custom identities use atomic disk reservations so collision state does not grow in the JVM. */
    public void reserveExecutionId(String executionId, IterationRequest request, boolean custom,
                                   Path runDirectory) {
        ensureOpen();
        if (!custom) return; // Default IDs are unique by the run-scoped AtomicLong.
        String runId = request.runId();
        if (executionId.toLowerCase(java.util.Locale.ROOT).matches(
                java.util.regex.Pattern.quote(runId.toLowerCase(java.util.Locale.ROOT)) + "-execution-[1-9][0-9]*"))
            throw new IllegalArgumentException("Custom EXEC.ID uses the reserved default-ID namespace: " + executionId);
        Path run = runDirectory.toAbsolutePath().normalize();
        try { initializeExecutionNamespace(run); }
        catch (IOException failure) { throw new IllegalStateException("Unable to isolate Load output namespace", failure); }
        Path marker = run.resolve(".exec-id-reservations").resolve(identityKey(executionId) + ".id");
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, (request.workloadId() + "/" + request.iterationId()).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            customExecutionIdsTracked.incrementAndGet();
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            throw new IllegalArgumentException("Duplicate custom EXEC.ID '" + executionId + "' in Load run", duplicate);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to reserve custom EXEC.ID '" + executionId + "'", failure);
        }
    }

    private static String identityKey(String executionId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(executionId.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(hash.length * 2);
            for (byte item : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
        result.put("render", renderPlans.stats().toMap());
        Map<String, Object> ids = new LinkedHashMap<String, Object>();
        ids.put("customExecutionIdsTracked", customExecutionIdsTracked.get());
        ids.put("executionIdCollisionTrackingSize", customExecutionIdsTracked.get());
        result.put("executionIds", ids);
        return result;
    }
    /** Samples shared pool gauges between iterations so completed runs retain their observed peaks. */
    public void sampleResourceMetrics() {
        ensureOpen();
        dbProvider.metrics(); mqFactory.metrics(); http.metrics();
    }
    public boolean isClosed() { return closed.get(); }
    public void ensureOpen() { if (closed.get()) throw new IllegalStateException("Load run resources are closed"); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) { http.close(); mqFactory.close(); db.closeAll(); dbProvider.close(); }
    }
}
