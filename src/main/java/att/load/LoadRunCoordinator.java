package att.load;

import att.config.FrameworkConfig;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Coordinates independent workload schedulers under one load-run lifecycle. */
public final class LoadRunCoordinator implements AutoCloseable {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final LoadScenario scenario;
    private final Map<String, LoadTarget> targets;
    private final LoadRunResources resources;
    private final Path outputRoot;
    private final String runId;
    private final LoadEvidenceStore evidenceStore;
    private final Map<String, LoadScheduler> schedulers = new LinkedHashMap<String, LoadScheduler>();
    private final Map<String, LoadMetrics> mixCollectors = new java.util.concurrent.ConcurrentHashMap<String, LoadMetrics>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> mixSelections = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>();
    private volatile ExecutorService coordinatorWorkers;
    private volatile ThreadPoolExecutor sharedIterationWorkers;

    /** Resolves and validates every workload target before any scheduler is started. */
    public static LoadRunResult runFrom(LoadScenario scenario, IterationExecutor seedExecutor, String runId,
                                 LoadEvidenceStore evidenceStore, Path outputRoot) throws Exception {
        Path root = seedExecutor.projectRoot();
        FrameworkConfig config = seedExecutor.config();
        return runFrom(root, config, scenario, seedExecutor.resources(), runId, evidenceStore,
                outputRoot == null ? seedExecutor.outputRoot() : outputRoot);
    }

    /** Resolves every workload target before scheduling without requiring a synthetic seed target. */
    public static LoadRunResult runFrom(Path projectRoot, FrameworkConfig config, LoadScenario scenario,
                                        LoadRunResources resources, String runId,
                                        LoadEvidenceStore evidenceStore, Path outputRoot) throws Exception {
        Path root = projectRoot.toAbsolutePath().normalize();
        Map<String, LoadTarget> targets = new LinkedHashMap<String, LoadTarget>();
        LoadTargetResolver resolver = new LoadTargetResolver(root, config);
        LoadTargetValidator validator = new LoadTargetValidator(root, config);
        for (LoadWorkload workload : scenario.workloads()) {
            if (workload.mixed()) {
                for (LoadMixEntry entry : workload.mix()) {
                    LoadScenario child = scenario.forMixEntry(workload, entry);
                    LoadTarget target = resolver.resolve(child);
                    validator.validate(child, target);
                    targets.put(targetKey(workload.id(), entry.id()), target);
                }
            } else {
                LoadScenario child = scenario.forWorkload(workload);
                LoadTarget target = resolver.resolve(child);
                validator.validate(child, target);
                targets.put(workload.id(), target);
            }
        }
        try (LoadRunCoordinator coordinator = new LoadRunCoordinator(root, config, scenario, targets,
                resources, outputRoot == null ? root.resolve(config.outputDirectory()) : outputRoot,
                runId, evidenceStore)) {
            return coordinator.run();
        }
    }

    public LoadRunCoordinator(Path projectRoot, FrameworkConfig config, LoadScenario scenario,
                              Map<String, LoadTarget> targets, LoadRunResources resources,
                              Path outputRoot, String runId, LoadEvidenceStore evidenceStore) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
        this.scenario = scenario;
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<String, LoadTarget>(targets));
        this.resources = resources;
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.runId = runId;
        this.evidenceStore = evidenceStore;
        int expectedTargets = 0; for (LoadWorkload workload : scenario.workloads()) expectedTargets += workload.mixed() ? workload.mix().size() : 1;
        if (targets.size() != expectedTargets)
            throw new IllegalArgumentException("Every workload must have exactly one validated target before scheduling");
    }

    public LoadRunResult run() throws Exception {
        resources.initializeExecutionNamespace(outputRoot.resolve("load").resolve(runId));
        final LoadSchedulerTiming timing = LoadSchedulerTiming.system();
        final long rateWindow = LoadPhase.totalMs(scenario);
        final LoadMetrics[] aggregateHolder = new LoadMetrics[1];
        final Consumer<LoadEvent> aggregateListener = new Consumer<LoadEvent>() {
            @Override public void accept(LoadEvent event) {
                LoadMetrics aggregate = aggregateHolder[0];
                if (aggregate == null) throw new IllegalStateException("Workload emitted before coordinated start release");
                aggregate.onEvent(event);
                if (event.mixId() != null) {
                    String key = targetKey(event.workloadId(), event.mixId());
                    LoadMetrics mixMetrics = mixCollectors.get(key);
                    if (mixMetrics != null) mixMetrics.onEvent(event);
                    if (event.started()) mixSelections.get(key).incrementAndGet();
                }
                if (evidenceStore != null) evidenceStore.onEvent(event);
            }
        };
        final LoadSchedulerStartGate startGate = new LoadSchedulerStartGate(scenario.workloads().size());
        sharedIterationWorkers = createSharedIterationWorkers();
        final att.testdata.TestdataRegistry testdataRegistry = new att.testdata.TestdataRegistry(
                projectRoot, config.testdataDescriptors(), scenario.testdataDescriptors());
        final Map<String, att.testdata.TestdataInputResolver> testdataResolvers =
                new LinkedHashMap<String, att.testdata.TestdataInputResolver>();
        coordinatorWorkers = Executors.newFixedThreadPool(scenario.workloads().size(), new NamedFactory("att-load-workload"));
        Map<String, Future<LoadRunResult>> futures = new LinkedHashMap<String, Future<LoadRunResult>>();
        try {
            for (LoadWorkload workload : scenario.workloads()) {
                LoadScenario child = scenario.forWorkload(workload);
                LoadIterationRunner iterations;
                if (workload.mixed()) {
                    Map<String, IterationExecutor> dispatch = new LinkedHashMap<String, IterationExecutor>();
                    att.testdata.TestdataInputResolver sharedSelections = null;
                    for (LoadMixEntry entry : workload.mix()) {
                        String key = targetKey(workload.id(), entry.id());
                        LoadTarget target = targets.get(key);
                        if (target == null) throw new IllegalArgumentException("Missing validated target for mix entry '" + entry.id() + "'");
                        att.testdata.TestdataInputResolver resolver;
                        if (sharedSelections == null) {
                            resolver = new att.testdata.TestdataInputResolver(testdataRegistry, workload.testdata(), workload.id(),
                                    workload.model().wireName(), scenario.seed(), Math.max(1, workload.users()), true,
                                    target.compiledTestdataMapping());
                            sharedSelections = resolver;
                        } else resolver = sharedSelections.forLoadMapping(workload.testdata(), target.compiledTestdataMapping());
                        testdataResolvers.put(key, resolver);
                        dispatch.put(entry.id(), new IterationExecutor(projectRoot, config, target, resources, outputRoot, resolver));
                    }
                    iterations = new MixIterationRunner(dispatch);
                } else {
                    LoadTarget target = targets.get(workload.id());
                    if (target == null) throw new IllegalArgumentException("Missing validated target for workload '" + workload.id() + "'");
                    att.testdata.TestdataInputResolver testdataResolver = new att.testdata.TestdataInputResolver(
                            testdataRegistry, workload.testdata(), workload.id(), workload.model().wireName(),
                            scenario.seed(), Math.max(1, workload.users()), true, target.compiledTestdataMapping());
                    testdataResolvers.put(workload.id(), testdataResolver);
                    iterations = new IterationExecutor(projectRoot, config, target, resources, outputRoot, testdataResolver);
                }
                LoadScheduler scheduler = child.model() == LoadScenario.Model.CLOSED
                        ? new ClosedVuScheduler(child, iterations, runId, aggregateListener, timing, evidenceStore, outputRoot, startGate)
                        : new FixedArrivalRateScheduler(child, iterations, runId, aggregateListener, timing, null,
                                evidenceStore, outputRoot, startGate);
                if (scheduler instanceof ClosedVuScheduler)
                    ((ClosedVuScheduler) scheduler).useSharedWorkers(sharedIterationWorkers);
                else ((FixedArrivalRateScheduler) scheduler).useSharedWorkers(sharedIterationWorkers);
                schedulers.put(workload.id(), scheduler);
            }
            for (Map.Entry<String, LoadScheduler> entry : schedulers.entrySet())
                futures.put(entry.getKey(), coordinatorWorkers.submit(() -> entry.getValue().run()));

            startGate.awaitReady();
            final long startedAt = timing.now();
            final Instant startInstant = LoadSchedulerSupport.instant(startedAt);
            final LoadMetrics aggregate = new LoadMetrics(scenario.model().wireName(), startedAt, rateWindow,
                    scenario.model() == LoadScenario.Model.CLOSED ? scenario.configuredUsers() : 0,
                    scenario.model() == LoadScenario.Model.ARRIVAL_RATE ? scenario.configuredArrivalRatePerSecond() : 0.0,
                    scenario.model() == LoadScenario.Model.ARRIVAL_RATE ? scenario.configuredMaxConcurrent() : 0);
            aggregateHolder[0] = aggregate;
            for (LoadWorkload workload : scenario.workloads()) for (LoadMixEntry entry : workload.mix()) {
                String key = targetKey(workload.id(), entry.id());
                mixCollectors.put(key, new LoadMetrics("closed", startedAt, rateWindow, workload.users(), 0.0, 0));
                mixSelections.put(key, new java.util.concurrent.atomic.AtomicLong());
            }
            startGate.release(startedAt);

            Map<String, LoadRunResult> workloadResults = new LinkedHashMap<String, LoadRunResult>();
            LoadThresholdEvaluator evaluator = new LoadThresholdEvaluator();
            for (LoadWorkload workload : scenario.workloads()) {
                LoadRunResult childResult = futures.get(workload.id()).get();
                aggregate.mergeSchedulerTelemetry(childResult.metrics().values());
                LoadScenario child = scenario.forWorkload(workload);
                childResult = childResult.withThresholds(evaluator.evaluate(child, childResult.metrics()));
                if (workload.mixed()) {
                    Map<String, Object> mixSummary = new LinkedHashMap<String, Object>();
                    long workloadSelected = 0L;
                    for (LoadMixEntry entry : workload.mix()) workloadSelected += mixSelections.get(targetKey(workload.id(), entry.id())).get();
                    for (LoadMixEntry entry : workload.mix()) {
                        String key = targetKey(workload.id(), entry.id());
                        LoadMetrics mix = mixCollectors.get(key); mix.finish(timing.now());
                        Map<String, Object> item = new LinkedHashMap<String, Object>();
                        item.put("configuredWeight", entry.weight());
                        long selected = mixSelections.get(key).get();
                        item.put("selectedCount", selected);
                        item.put("selectedPercent", workloadSelected == 0L ? 0.0 : 100.0 * selected / workloadSelected);
                        item.put("metrics", mix.snapshot().toMap());
                        item.put("target", mapOf("type", entry.targetType(), "id", entry.targetId()));
                        mixSummary.put(entry.id(), item);
                    }
                    childResult = childResult.withResources(Collections.<String, Object>singletonMap("mix", mixSummary));
                }
                workloadResults.put(workload.id(), childResult);
            }
            long endedAt = timing.now();
            aggregate.finish(endedAt);
            LoadRunResult result = new LoadRunResult(runId, scenario, startInstant, LoadSchedulerSupport.instant(endedAt), aggregate.snapshot(),
                    LoadThresholdSummary.empty(), Collections.<String, Object>emptyMap(),
                    resources.metrics(), workloadResults);
            Map<String, Object> testdata = new LinkedHashMap<String, Object>();
            Map<String, Object> perWorkload = new LinkedHashMap<String, Object>();
            long mappings = 0L, requests = 0L, evaluations = 0L, cacheHits = 0L;
            Map<String, Long> sizes = new LinkedHashMap<String, Long>();
            java.util.Set<String> cacheSizeWorkloads = new java.util.HashSet<String>();
            for (Map.Entry<String, att.testdata.TestdataInputResolver> entry : testdataResolvers.entrySet()) {
                Map<String, Object> snapshot = entry.getValue().telemetry();
                perWorkload.put(entry.getKey(), snapshot);
                mappings += ((Number) snapshot.get("mappingEvaluations")).longValue();
                requests += ((Number) snapshot.get("selectionRequests")).longValue();
                evaluations += ((Number) snapshot.get("selectionEvaluations")).longValue();
                cacheHits += ((Number) snapshot.get("selectionCacheHits")).longValue();
                String workloadKey = entry.getKey().contains("::") ? entry.getKey().substring(0, entry.getKey().indexOf("::")) : entry.getKey();
                if (cacheSizeWorkloads.add(workloadKey)) {
                    @SuppressWarnings("unchecked") Map<String, Object> scopeSizes = (Map<String, Object>) snapshot.get("selectionCacheSizesByScope");
                    for (Map.Entry<String, Object> size : scopeSizes.entrySet())
                        sizes.put(size.getKey(), sizes.getOrDefault(size.getKey(), 0L) + ((Number) size.getValue()).longValue());
                }
            }
            testdata.put("mappingEvaluations", mappings); testdata.put("selectionRequests", requests);
            testdata.put("selectionEvaluations", evaluations); testdata.put("selectionCacheHits", cacheHits);
            testdata.put("selectionCacheSizesByScope", sizes); testdata.put("workloads", perWorkload);
            Map<String, Object> generator = new LinkedHashMap<String, Object>();
            generator.put("testdata", testdata);
            Map<String, Object> resourceSnapshot = new LinkedHashMap<String, Object>();
            resourceSnapshot.put("generator", generator);
            return result.withResources(resourceSnapshot);
        } catch (Exception failure) {
            cancelAll();
            throw failure;
        } finally {
            shutdownCoordinator();
        }
    }

    private static String targetKey(String workloadId, String mixId) { return workloadId + "::" + mixId; }
    private static Map<String, Object> mapOf(String key, Object value, String key2, Object value2) {
        Map<String, Object> map = new LinkedHashMap<String, Object>(); map.put(key, value); map.put(key2, value2); return map;
    }

    private void cancelAll() {
        for (LoadScheduler scheduler : schedulers.values()) {
            try { scheduler.cancel(); } catch (Exception ignored) { }
        }
    }

    @Override public void close() {
        cancelAll();
        for (LoadScheduler scheduler : schedulers.values()) {
            try { scheduler.close(); } catch (Exception ignored) { }
        }
        shutdownCoordinator();
    }

    private void shutdownCoordinator() {
        ExecutorService workers = coordinatorWorkers;
        if (workers != null) {
            workers.shutdownNow();
            try { workers.awaitTermination(1L, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { coordinatorWorkers = null; }
        }
        ThreadPoolExecutor iterations = sharedIterationWorkers;
        if (iterations != null) {
            iterations.shutdownNow();
            try { iterations.awaitTermination(1L, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { sharedIterationWorkers = null; }
        }
    }

    private ThreadPoolExecutor createSharedIterationWorkers() {
        long slots = 0L;
        for (LoadWorkload workload : scenario.workloads())
            slots += workload.model() == LoadScenario.Model.CLOSED ? workload.users() : workload.maxConcurrent();
        int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, slots));
        // IterationExecutor is synchronous and may block on target I/O. Grow
        // workers on demand to the configured aggregate concurrency ceiling.
        return LoadWorkerPool.create(LoadWorkerPool.coreSize(capacity), capacity,
                new NamedFactory("att-load-worker"));
    }

    private static final class NamedFactory implements ThreadFactory {
        private final String prefix; private final AtomicLong index = new AtomicLong();
        NamedFactory(String prefix) { this.prefix = prefix; }
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true); return thread;
        }
    }
}
