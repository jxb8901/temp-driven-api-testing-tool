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
    private static final int PREPARATION_PER_RUN_LIMIT = LoadPreparationBudget.PER_RUN_CONNECTIONS;
    private static final java.util.concurrent.Semaphore PREPARATION_PROCESS_LIMIT =
            new java.util.concurrent.Semaphore(LoadPreparationBudget.WORKER_JVM_CONNECTIONS, true);
    private static final long PREPARATION_DEADLINE_MS = 30_000L;
    private static final java.util.concurrent.ThreadPoolExecutor PREPARATION_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            0, PREPARATION_PER_RUN_LIMIT, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<Runnable>(), runnable -> {
                Thread thread = new Thread(runnable, "att-load-resource-preparation");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static final java.util.concurrent.ThreadPoolExecutor PREPARATION_CLEANUP_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            0, LoadPreparationBudget.WORKER_JVM_CONNECTIONS, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<Runnable>(), runnable -> {
                Thread thread = new Thread(runnable, "att-load-resource-cleanup");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final FrameworkConfig config;
    private final java.util.concurrent.Semaphore preparationProcessBudget;
    private final long preparationDeadlineMs;
    private final DbHelperExecutor db;
    private final HikariDbConnectionProvider dbProvider;
    private final MqHelperExecutor mq;
    private final att.exec.HttpHelperExecutor http;
    private final PooledMqTransportFactory mqFactory;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong executionSequence = new AtomicLong();
    private static final class ExecutionIdentity {
        final String runId, prefix;
        ExecutionIdentity(String runId) {
            this.runId = runId;
            this.prefix = LoadExecutionIdPattern.executionPrefix(runId);
            LoadExecutionIdPattern.requireSafe(prefix + "-execution-" + Long.MAX_VALUE);
        }
    }
    private final java.util.concurrent.atomic.AtomicReference<ExecutionIdentity> identityRun = new java.util.concurrent.atomic.AtomicReference<ExecutionIdentity>();
    private final AtomicLong lastResourceMetricSampleNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong resourceMetricSamples = new AtomicLong();
    private final java.util.Set<Path> initializedExecutionNamespaces = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final att.template.SequenceService sequences = new att.template.SequenceService();
    private final att.template.RenderPlanCache renderPlans = new att.template.RenderPlanCache();
    private final java.util.concurrent.CopyOnWriteArrayList<att.template.CompiledExecutionPlan> executionPlans =
            new java.util.concurrent.CopyOnWriteArrayList<att.template.CompiledExecutionPlan>();
    private final Map<String, Object> preparation = new LinkedHashMap<String, Object>();
    private volatile java.util.function.Consumer<Map<String, Object>> preparationObserver;
    private volatile String preparationRunId;
    private int preparationPermits;
    private boolean schedulerStarted;

    public LoadRunResources(Path projectRoot, FrameworkConfig config) {
        this(projectRoot, config, new IbmMqClientFactory());
    }

    /** Creates load resources with an injectable MQ transport boundary for integration tests. */
    LoadRunResources(Path projectRoot, FrameworkConfig config, MqTransport.Factory mqTransportFactory) {
        this(projectRoot, config, mqTransportFactory, PREPARATION_DEADLINE_MS);
    }

    /** Test seam for exercising the same overall readiness deadline with short, deterministic budgets. */
    LoadRunResources(Path projectRoot, FrameworkConfig config, MqTransport.Factory mqTransportFactory, long preparationDeadlineMs) {
        this(projectRoot, config, mqTransportFactory, preparationDeadlineMs, PREPARATION_PROCESS_LIMIT);
    }

    /** Test seam for sharing a small preparation budget across overlapping runs. */
    LoadRunResources(Path projectRoot, FrameworkConfig config, MqTransport.Factory mqTransportFactory,
                     long preparationDeadlineMs, java.util.concurrent.Semaphore preparationProcessBudget) {
        if (preparationDeadlineMs < 1L) throw new IllegalArgumentException("LOAD preparation deadline must be positive");
        if (preparationProcessBudget == null) throw new IllegalArgumentException("LOAD preparation budget is required");
        this.config = config;
        this.preparationProcessBudget = preparationProcessBudget;
        this.preparationDeadlineMs = preparationDeadlineMs;
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
    public void setPreparationObserver(java.util.function.Consumer<Map<String, Object>> observer) {
        this.preparationObserver = observer;
    }
    public synchronized void markSchedulerStart(String runId, String phase, String model) {
        if (!"RESOURCES_READY".equals(preparation.get("status")) || schedulerStarted) return;
        schedulerStarted = true;
        String line = "[LOAD] START runId=" + safeId(runId) + " phase=" + safeId(phase);
        Map<String, Object> event = new LinkedHashMap<String, Object>(); event.put("event", "LOAD_START");
        event.put("status", "STARTED"); event.put("runId", safeId(runId)); event.put("phase", safeId(phase));
        event.put("model", safeId(model));
        emitPreparation(event, line);
    }

    /** LOAD-only barrier: construct referenced pools/client state after validation and before scheduler release. */
    public void prepareResources() throws Exception { prepareResources(null); }

    public synchronized void prepareResources(String runId) throws Exception {
        ensureOpen();
        if (runId != null && !runId.trim().isEmpty()) preparationRunId = safeId(runId);
        if ("RESOURCES_READY".equals(preparation.get("status"))) return;
        if ("PREPARATION_FAILED".equals(preparation.get("status")))
            throw new LoadResourcePreparationException("LOAD_RESOURCE_PREPARATION_FAILED: readiness was already attempted", null);
        long started = System.nanoTime();
        long deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(preparationDeadlineMs);
        long cleanupReserveMs = Math.min(1_000L, Math.max(50L, preparationDeadlineMs / 10L));
        long operationDeadline = deadline - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(cleanupReserveMs);
        long discoveryStarted = started;
        Map<String, att.config.DbHelperConfig> dbs = new java.util.TreeMap<String, att.config.DbHelperConfig>();
        Map<String, att.config.MqHelperConfig> mqs = new java.util.TreeMap<String, att.config.MqHelperConfig>();
        Map<String, att.config.HttpHelperConfig> https = new java.util.TreeMap<String, att.config.HttpHelperConfig>();
        java.util.Set<String> dynamicKinds = new java.util.TreeSet<String>();
        java.util.List<String> renderSources = renderPlans.dependencySources();
        for (att.template.CompiledExecutionPlan plan : executionPlans) {
            att.template.CompiledExecutionPlan.DependencyInventory inventory = plan.resourceDependencyInventory(renderSources);
            dynamicKinds.addAll(inventory.dynamicKinds());
            for (att.template.CompiledExecutionPlan.TargetBinding binding : inventory.bindings()) {
                if (binding.db() != null) dbs.put(binding.db().id(), binding.db());
                if (binding.mq() != null) mqs.put(binding.mq().poolKey(), binding.mq());
                if (binding.http() != null) https.put(binding.http().id(), binding.http());
            }
        }
        long discoveryMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - discoveryStarted);
        int required = 0;
        for (att.config.DbHelperConfig helper : dbs.values()) required = Math.addExact(required, Math.max(1, helper.poolMinIdle()));
        for (att.config.MqHelperConfig helper : mqs.values()) required = Math.addExact(required, Math.max(1, helper.poolMinIdle()));
        int totalHelpers = dbs.size() + mqs.size() + https.size();
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        Map<String, Object> preparing = new LinkedHashMap<String, Object>();
        preparing.put("event", "LOAD_PREPARING_RESOURCES"); preparing.put("status", "PREPARING_RESOURCES");
        preparing.put("requiredDbPools", dbs.size()); preparing.put("requiredMqPools", mqs.size());
        preparing.put("requiredHttpClients", https.size()); preparing.put("required", totalHelpers);
        preparing.put("initialConnections", required); preparing.put("discoveryMs", discoveryMs);
        emitPreparation(preparing, "[LOAD] PREPARING_RESOURCES requiredDbPools=" + dbs.size() + " requiredMqPools=" + mqs.size()
                + " requiredHttpClients=" + https.size() + " initialConnections=" + required + " discoveryMs=" + discoveryMs);
        for (String kind : dynamicKinds) {
            Map<String, Object> row = new LinkedHashMap<String, Object>(); row.put("kind", kind); row.put("helper", "dynamic");
            row.put("status", "SKIPPED_DYNAMIC"); row.put("fallback", "lazy-on-first-use"); rows.add(row);
            Map<String, Object> event = new LinkedHashMap<String, Object>(); event.put("event", "LOAD_RESOURCE_SKIPPED_DYNAMIC");
            event.put("kind", kind); event.put("helper", "dynamic"); event.put("status", "SKIPPED_DYNAMIC");
            event.put("fallback", "lazy-on-first-use");
            emitPreparation(event, "[LOAD][" + kind + "] helper=dynamic status=SKIPPED_DYNAMIC fallback=lazy-on-first-use");
        }
        try {
            LoadPreparationBudget.requireWithinPerRunLimit(required);
            long remainingNanos = operationDeadline - System.nanoTime();
            if (remainingNanos <= 0L || !preparationProcessBudget.tryAcquire(required, remainingNanos, java.util.concurrent.TimeUnit.NANOSECONDS))
                throw new java.util.concurrent.TimeoutException("LOAD_RESOURCE_PREPARATION_BUDGET_TIMEOUT");
            preparationPermits = required;
            for (att.config.DbHelperConfig helper : dbs.values()) {
                long before = System.nanoTime(); int target = Math.max(1, helper.poolMinIdle());
                try {
                    checkPreparationDeadline(operationDeadline);
                    int actual = runBoundedPreparation(() -> {
                        long connectionBudgetMs = remainingPreparationMillis(operationDeadline);
                        if (connectionBudgetMs < 250L) throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT");
                        return dbPool(helper.id(), config, connectionBudgetMs).prepareInitialReady(operationDeadline);
                    }, operationDeadline);
                    checkPreparationDeadline(operationDeadline);
                    Map<String, Object> row = preparationRow("DB", helper.id(), target, actual, helper.poolMaxSize(), helper.poolMinIdle(), before);
                    row.put("sizingSource", helper.poolMinIdle() == 0 ? "min-ready" : "configured"); rows.add(row);
                    Map<String, Object> event = new LinkedHashMap<String, Object>(row); event.put("event", "LOAD_RESOURCE_READY");
                    emitPreparation(event, "[LOAD][DB] helper=" + safeId(helper.id()) + " status=READY maxSize=" + helper.poolMaxSize()
                            + " configuredMinIdle=" + helper.poolMinIdle() + " initialReadyTarget=" + target + " actualReady=" + actual
                            + " sizingSource=" + row.get("sizingSource") + " preparationMs=" + row.get("preparationMs"));
                } catch (Exception failure) {
                    Map<String, Object> row = failedPreparationRow("DB", helper.id(), target, helper.poolMaxSize(), helper.poolMinIdle(), before, failure);
                    rows.add(row); Map<String, Object> event = new LinkedHashMap<String, Object>(row); event.put("event", "LOAD_RESOURCE_FAILED");
                    emitPreparation(event, "[LOAD][DB] helper=" + safeId(helper.id()) + " status=FAILED maxSize=" + helper.poolMaxSize()
                            + " configuredMinIdle=" + helper.poolMinIdle() + " initialReadyTarget=" + target + " actualReady=0 errorType="
                            + row.get("failureCategory") + " preparationMs=" + row.get("preparationMs"));
                    throw failure;
                }
            }
            for (att.config.MqHelperConfig helper : mqs.values()) {
                long before = System.nanoTime(); int target = Math.max(1, helper.poolMinIdle());
                String identity = helper.logicalId() + "::" + helper.instanceId();
                try {
                    checkPreparationDeadline(operationDeadline);
                    int actual = runBoundedPreparation(() -> mqFactory.prepareInitialReady(helper, operationDeadline), operationDeadline);
                    checkPreparationDeadline(operationDeadline);
                    Map<String, Object> row = preparationRow("MQ", identity, target, actual, helper.poolMaxSize(), helper.poolMinIdle(), before);
                    row.put("logicalHelper", safeId(helper.logicalId())); row.put("instance", safeId(helper.instanceId()));
                    row.put("sizingSource", helper.poolMinIdle() == 0 ? "min-ready" : "configured"); rows.add(row);
                    Map<String, Object> event = new LinkedHashMap<String, Object>(row); event.put("event", "LOAD_RESOURCE_READY");
                    emitPreparation(event, "[LOAD][MQ] helper=" + safeId(helper.logicalId()) + " instance=" + safeId(helper.instanceId())
                            + " status=READY maxSize=" + helper.poolMaxSize() + " configuredMinIdle=" + helper.poolMinIdle()
                            + " initialReadyTarget=" + target + " actualReady=" + actual + " sizingSource=" + row.get("sizingSource")
                            + " preparationMs=" + row.get("preparationMs"));
                } catch (Exception failure) {
                    Map<String, Object> row = failedPreparationRow("MQ", identity, target, helper.poolMaxSize(), helper.poolMinIdle(), before, failure);
                    row.put("logicalHelper", safeId(helper.logicalId())); row.put("instance", safeId(helper.instanceId())); rows.add(row);
                    Map<String, Object> event = new LinkedHashMap<String, Object>(row); event.put("event", "LOAD_RESOURCE_FAILED");
                    emitPreparation(event, "[LOAD][MQ] helper=" + safeId(helper.logicalId()) + " instance=" + safeId(helper.instanceId())
                            + " status=FAILED maxSize=" + helper.poolMaxSize() + " configuredMinIdle=" + helper.poolMinIdle()
                            + " initialReadyTarget=" + target + " actualReady=0 errorType=" + row.get("failureCategory")
                            + " preparationMs=" + row.get("preparationMs"));
                    throw failure;
                }
            }
            for (att.config.HttpHelperConfig helper : https.values()) {
                long before = System.nanoTime();
                try {
                    checkPreparationDeadline(operationDeadline);
                    runBoundedPreparation(() -> { http.prepareClient(helper); return Boolean.TRUE; }, operationDeadline);
                    checkPreparationDeadline(operationDeadline);
                    Map<String, Object> row = preparationRow("HTTP", helper.id(), 0, 0, 0, 0, before);
                    row.put("clientReady", true); row.put("tlsConfigReady", true); row.put("physicalConnections", "not-prewarmed"); rows.add(row);
                    Map<String, Object> event = new LinkedHashMap<String, Object>(row); event.put("event", "LOAD_RESOURCE_READY");
                    emitPreparation(event, "[LOAD][HTTP] helper=" + safeId(helper.id()) + " status=READY clientReady=true tlsConfigReady=true physicalConnections=not-prewarmed preparationMs=" + row.get("preparationMs"));
                } catch (Exception failure) {
                    Map<String, Object> row = failedPreparationRow("HTTP", helper.id(), 0, 0, 0, before, failure);
                    row.put("clientReady", false); row.put("tlsConfigReady", false); row.put("physicalConnections", "not-prewarmed"); rows.add(row);
                    Map<String, Object> event = new LinkedHashMap<String, Object>(row); event.put("event", "LOAD_RESOURCE_FAILED");
                    emitPreparation(event, "[LOAD][HTTP] helper=" + safeId(helper.id()) + " status=FAILED clientReady=false tlsConfigReady=false physicalConnections=not-prewarmed errorType="
                            + row.get("failureCategory") + " preparationMs=" + row.get("preparationMs"));
                    throw failure;
                }
            }
            preparation.put("status", "RESOURCES_READY"); preparation.put("required", totalHelpers); preparation.put("prepared", totalHelpers);
            preparation.put("failed", 0); preparation.put("skippedDynamic", dynamicKinds.size()); preparation.put("initialConnections", required);
            preparation.put("perRunConnectionBudget", PREPARATION_PER_RUN_LIMIT);
            preparation.put("processConnectionBudget", LoadPreparationBudget.WORKER_JVM_CONNECTIONS);
            preparation.put("processConnectionBudgetScope", "worker-jvm");
            checkPreparationDeadline(operationDeadline);
            preparation.put("preparationDeadlineMs", preparationDeadlineMs); preparation.put("parallelism", 1);
            preparation.put("discoveryMs", discoveryMs);
            preparation.put("preparationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); preparation.put("helpers", rows);
            Map<String, Object> ready = new LinkedHashMap<String, Object>(); ready.put("event", "LOAD_RESOURCES_READY");
            ready.put("status", "RESOURCES_READY"); ready.put("required", totalHelpers); ready.put("prepared", totalHelpers);
            ready.put("failed", 0); ready.put("skipped", dynamicKinds.size()); ready.put("preparationMs", preparation.get("preparationMs"));
            emitPreparation(ready, "[LOAD] RESOURCES_READY required=" + totalHelpers + " prepared=" + totalHelpers
                    + " failed=0 dynamicSkipped=" + dynamicKinds.size() + " preparationMs=" + preparation.get("preparationMs"));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            preparation.put("status", "PREPARATION_FAILED"); preparation.put("required", totalHelpers);
            int prepared = 0, failed = 0, skipped = 0;
            for (Map<String, Object> row : rows) {
                if ("READY".equals(row.get("status"))) prepared++;
                else if ("FAILED".equals(row.get("status"))) failed++;
                else if ("SKIPPED_DYNAMIC".equals(row.get("status"))) skipped++;
            }
            preparation.put("prepared", prepared); preparation.put("failed", failed); preparation.put("skippedDynamic", skipped);
            preparation.put("initialConnections", required); preparation.put("perRunConnectionBudget", PREPARATION_PER_RUN_LIMIT);
            preparation.put("processConnectionBudget", LoadPreparationBudget.WORKER_JVM_CONNECTIONS);
            preparation.put("processConnectionBudgetScope", "worker-jvm");
            preparation.put("preparationDeadlineMs", preparationDeadlineMs); preparation.put("parallelism", 1);
            preparation.put("discoveryMs", discoveryMs);
            preparation.put("preparationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            String failureCode = preparationCode(failure, "SYSTEM");
            for (Map<String, Object> row : rows) if ("FAILED".equals(row.get("status"))) {
                failureCode = String.valueOf(row.get("failureCategory")); break;
            }
            preparation.put("helpers", rows); preparation.put("failureCode", failureCode);
            Map<String, Object> failedEvent = new LinkedHashMap<String, Object>(); failedEvent.put("event", "LOAD_PREPARATION_FAILED");
            failedEvent.put("status", "PREPARATION_FAILED"); failedEvent.put("required", totalHelpers);
            failedEvent.put("prepared", prepared); failedEvent.put("failed", failed); failedEvent.put("skipped", skipped);
            failedEvent.put("preparationMs", preparation.get("preparationMs")); failedEvent.put("errorType", preparation.get("failureCode"));
            emitPreparation(failedEvent, "[LOAD] PREPARATION_FAILED required=" + totalHelpers + " prepared=" + prepared
                    + " failed=" + failed + " skipped=" + skipped + " preparationMs=" + preparation.get("preparationMs")
                    + " errorType=" + preparation.get("failureCode"));
            closeWithinPreparationDeadline(deadline);
            throw new LoadResourcePreparationException("LOAD_RESOURCE_PREPARATION_FAILED: a referenced helper could not reach its bounded readiness target; verify helper credentials and connectivity (" + failureCode + ")",
                    terminalPreparationCode(failureCode), failure);
        }
    }

    private static void checkPreparationDeadline(long deadline) throws java.util.concurrent.TimeoutException {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("LOAD preparation cancelled");
        if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT");
    }
    private static long remainingPreparationMillis(long deadline) throws java.util.concurrent.TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT");
        return Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
    }
    private static <T> T runBoundedPreparation(java.util.concurrent.Callable<T> preparation, long deadline) throws Exception {
        checkPreparationDeadline(deadline);
        final java.util.concurrent.Future<T> future;
        try { future = PREPARATION_EXECUTOR.submit(preparation); }
        catch (java.util.concurrent.RejectedExecutionException saturated) {
            throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT: bounded preparation workers are saturated");
        }
        try {
            return future.get(remainingPreparationMillis(deadline), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("LOAD preparation task failed", cause);
        }
    }
    private static String safeId(String id) {
        String safe = LoadFailureDiagnostic.safe(id);
        return safe == null ? "unknown" : safe.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
    private static String preparationCode(Throwable failure, String kind) {
        if (failure instanceof LoadResourcePreparationException) {
            String code = ((LoadResourcePreparationException) failure).diagnosticCode();
            if (LoadResourcePreparationException.RESOURCE_LIMIT.equals(code)) return "PREPARATION_LIMIT_EXCEEDED";
            if (LoadResourcePreparationException.DEADLINE.equals(code)) return "PREPARATION_TIMEOUT";
        }
        if (failure instanceof java.util.concurrent.TimeoutException) return "PREPARATION_TIMEOUT";
        if (failure instanceof java.util.concurrent.CancellationException || failure instanceof InterruptedException) return "PREPARATION_CANCELLED";
        if ("DB".equals(kind)) return "DB_CONNECT_ERROR";
        if ("MQ".equals(kind)) return "MQ_CONNECT_ERROR";
        if ("HTTP".equals(kind)) return "HTTP_CLIENT_CONFIGURATION_ERROR";
        return "PREPARATION_ERROR";
    }

    private static String terminalPreparationCode(String failureCode) {
        if ("PREPARATION_LIMIT_EXCEEDED".equals(failureCode)) return LoadResourcePreparationException.RESOURCE_LIMIT;
        if ("PREPARATION_TIMEOUT".equals(failureCode) || "PREPARATION_BUDGET_TIMEOUT".equals(failureCode))
            return LoadResourcePreparationException.DEADLINE;
        return LoadResourcePreparationException.FAILED;
    }
    private static Map<String, Object> preparationRow(String kind, String id, int target, int actual, int max, int min, long before) {
        Map<String, Object> row = new LinkedHashMap<String, Object>(); row.put("kind", kind); row.put("helper", safeId(id)); row.put("status", "READY");
        row.put("initialReadyTarget", target); row.put("actualReady", actual);
        if (!"HTTP".equals(kind)) { row.put("maxSize", max); row.put("configuredMinIdle", min); }
        row.put("preparationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)); return row;
    }
    private static Map<String, Object> failedPreparationRow(String kind, String id, int target, int max, int min, long before, Throwable failure) {
        Map<String, Object> row = new LinkedHashMap<String, Object>(); row.put("kind", kind); row.put("helper", safeId(id));
        row.put("status", "FAILED"); row.put("initialReadyTarget", target); row.put("actualReady", 0);
        if (!"HTTP".equals(kind)) { row.put("maxSize", max); row.put("configuredMinIdle", min); }
        row.put("failureCategory", preparationCode(failure, kind));
        row.put("preparationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)); return row;
    }
    private void emitPreparation(Map<String, Object> attributes, String line) {
        Map<String, Object> event = new LinkedHashMap<String, Object>(attributes);
        String eventRunId = event.get("runId") == null ? preparationRunId : safeId(String.valueOf(event.get("runId")));
        if (eventRunId != null) event.put("runId", eventRunId);
        String contextualLine = line;
        if (eventRunId != null && !line.contains("runId=")) contextualLine += " runId=" + eventRunId;
        java.util.function.Consumer<Map<String, Object>> observer = preparationObserver;
        if (observer == null) { System.out.println(contextualLine); return; }
        event.put("console", contextualLine);
        try { observer.accept(java.util.Collections.unmodifiableMap(event)); }
        catch (RuntimeException ignored) { /* A disconnected live-output observer cannot change readiness. */ }
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
        ExecutionIdentity owner = identityRun.get();
        if (owner == null) {
            identityRun.compareAndSet(null, new ExecutionIdentity(runId));
            owner = identityRun.get();
        }
        if (!owner.runId.equals(runId)) throw new IllegalArgumentException("Load resources belong to a different run identity");
        long sequence;
        do {
            sequence = executionSequence.get();
            if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Load execution identity sequence exhausted");
        } while (!executionSequence.compareAndSet(sequence, sequence + 1));
        return owner.prefix + "-execution-" + (sequence + 1);
    }
    /** Validates an isolated run output namespace once, before any iteration writes into it. */
    public void initializeExecutionNamespace(Path runDirectory) throws IOException {
        ensureOpen();
        Path run = runDirectory.toAbsolutePath().normalize();
        new ExecutionIdentity(run.getFileName().toString());
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
    private HikariDbPool dbPool(String helperId, att.config.FrameworkConfig config, long connectionTimeoutCapMs) {
        ensureOpen();
        att.config.DbHelperConfig helper = config.dbHelper(helperId);
        if (helper == null) throw new IllegalArgumentException("Unknown dbhelper instance: " + helperId);
        return dbProvider.pool(helper, connectionTimeoutCapMs);
    }
    public HikariDbPool dbPool(String helperId) { return dbPool(helperId, config); }
    public LoadResourcePool<att.exec.MqTransport.Connection> mqPool(String helperId) {
        ensureOpen();
        LoadResourcePool<att.exec.MqTransport.Connection> pool = mqFactory.pool(helperId);
        if (pool == null) throw new IllegalArgumentException("Unknown or unopened mqhelper instance: " + helperId);
        return pool;
    }
    public synchronized Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (closed.get()) {
            if (!preparation.isEmpty()) result.put("preparation", new LinkedHashMap<String, Object>(preparation));
            return java.util.Collections.unmodifiableMap(result);
        }
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
        if (!preparation.isEmpty()) result.put("preparation", new LinkedHashMap<String, Object>(preparation));
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
    static int availablePreparationPermits() { return PREPARATION_PROCESS_LIMIT.availablePermits(); }
    public void ensureOpen() { if (closed.get()) throw new IllegalStateException("Load run resources are closed"); }

    private void closeWithinPreparationDeadline(long deadline) {
        if (!beginClose()) return;
        java.util.concurrent.Future<?> cleanup;
        try { cleanup = PREPARATION_CLEANUP_EXECUTOR.submit(this::closeOwnedResourcesAndReleasePreparationBudget); }
        catch (java.util.concurrent.RejectedExecutionException saturated) {
            closeOwnedResourcesAndReleasePreparationBudget();
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0L) cleanup.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException cancelled) {
            interrupted = true;
        } catch (java.util.concurrent.TimeoutException timedOut) {
            // Cleanup continues in the background. Its budget remains charged until every owner closes.
        } catch (java.util.concurrent.ExecutionException ignored) {
            // A failed close keeps the budget charged because teardown was not confirmed.
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public void close() {
        if (!beginClose()) return;
        closeOwnedResourcesAndReleasePreparationBudget();
    }

    private boolean beginClose() {
        return closed.compareAndSet(false, true);
    }

    private void closeOwnedResourcesAndReleasePreparationBudget() {
        closeOwnedResources();
        synchronized (this) {
            if (preparationPermits > 0) {
                preparationProcessBudget.release(preparationPermits);
                preparationPermits = 0;
            }
        }
    }

    private void closeOwnedResources() {
        Throwable failure = null;
        for (Runnable close : new Runnable[] {http::close, mqFactory::close, db::closeAll, dbProvider::close}) {
            try { close.run(); }
            catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
    }
}
