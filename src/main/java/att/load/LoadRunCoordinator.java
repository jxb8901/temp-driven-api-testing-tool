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
    private volatile ExecutorService coordinatorWorkers;

    /** Resolves and validates every workload target before any scheduler is started. */
    public static LoadRunResult runFrom(LoadScenario scenario, IterationExecutor seedExecutor, String runId,
                                 LoadEvidenceStore evidenceStore, Path outputRoot) throws Exception {
        Path root = seedExecutor.projectRoot();
        FrameworkConfig config = seedExecutor.config();
        Map<String, LoadTarget> targets = new LinkedHashMap<String, LoadTarget>();
        LoadTargetResolver resolver = new LoadTargetResolver(root, config);
        LoadTargetValidator validator = new LoadTargetValidator(root, config);
        for (LoadWorkload workload : scenario.workloads()) {
            LoadScenario child = scenario.forWorkload(workload);
            LoadTarget target = resolver.resolve(child);
            validator.validate(child, target);
            targets.put(workload.id(), target);
        }
        try (LoadRunCoordinator coordinator = new LoadRunCoordinator(root, config, scenario, targets,
                seedExecutor.resources(), outputRoot == null ? seedExecutor.outputRoot() : outputRoot,
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
        if (targets.size() != scenario.workloads().size())
            throw new IllegalArgumentException("Every workload must have exactly one validated target before scheduling");
    }

    public LoadRunResult run() throws Exception {
        final LoadSchedulerTiming timing = LoadSchedulerTiming.system();
        final long rateWindow = LoadPhase.totalMs(scenario);
        final LoadMetrics[] aggregateHolder = new LoadMetrics[1];
        final Consumer<LoadEvent> aggregateListener = new Consumer<LoadEvent>() {
            @Override public void accept(LoadEvent event) {
                LoadMetrics aggregate = aggregateHolder[0];
                if (aggregate == null) throw new IllegalStateException("Workload emitted before coordinated start release");
                aggregate.onEvent(event);
                if (evidenceStore != null) evidenceStore.onEvent(event);
            }
        };
        final LoadSchedulerStartGate startGate = new LoadSchedulerStartGate(scenario.workloads().size());
        coordinatorWorkers = Executors.newFixedThreadPool(scenario.workloads().size(), new NamedFactory("att-load-workload"));
        Map<String, Future<LoadRunResult>> futures = new LinkedHashMap<String, Future<LoadRunResult>>();
        try {
            for (LoadWorkload workload : scenario.workloads()) {
                LoadScenario child = scenario.forWorkload(workload);
                LoadTarget target = targets.get(workload.id());
                if (target == null) throw new IllegalArgumentException("Missing validated target for workload '" + workload.id() + "'");
                IterationExecutor iterations = new IterationExecutor(projectRoot, config, target, resources, outputRoot);
                LoadScheduler scheduler = child.model() == LoadScenario.Model.CLOSED
                        ? new ClosedVuScheduler(child, iterations, runId, aggregateListener, timing, evidenceStore, outputRoot, startGate)
                        : new FixedArrivalRateScheduler(child, iterations, runId, aggregateListener, timing, null,
                                evidenceStore, outputRoot, startGate);
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
            startGate.release(startedAt);

            Map<String, LoadRunResult> workloadResults = new LinkedHashMap<String, LoadRunResult>();
            LoadThresholdEvaluator evaluator = new LoadThresholdEvaluator();
            for (LoadWorkload workload : scenario.workloads()) {
                LoadRunResult childResult = futures.get(workload.id()).get();
                LoadScenario child = scenario.forWorkload(workload);
                childResult = childResult.withThresholds(evaluator.evaluate(child, childResult.metrics()));
                workloadResults.put(workload.id(), childResult);
            }
            long endedAt = timing.now();
            aggregate.finish(endedAt);
            return new LoadRunResult(runId, scenario, startInstant, LoadSchedulerSupport.instant(endedAt), aggregate.snapshot(),
                    LoadThresholdSummary.empty(), Collections.<String, Object>emptyMap(),
                    Collections.<String, Object>emptyMap(), workloadResults);
        } catch (Exception failure) {
            cancelAll();
            throw failure;
        } finally {
            shutdownCoordinator();
        }
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
        if (workers == null) return;
        workers.shutdownNow();
        try { workers.awaitTermination(1L, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { coordinatorWorkers = null; }
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
