package att.load;

import att.config.FrameworkConfig;
import att.config.MqHelperConfig;
import att.config.ProcessOutputConfig;
import att.core.ResultStatus;
import att.exec.MqTransport;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.TemplateAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadMqPoolingTest {
    @TempDir Path tempDir;

    @Test
    void concurrentIterationsUseOneExclusiveLeaseAndPublishPoolTimeout() throws Exception {
        BlockingFactory delegate = new BlockingFactory();
        FrameworkConfig config = config(1, 0, 50L);
        LoadTarget target = target();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (LoadRunResources resources = new LoadRunResources(tempDir, config, delegate)) {
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output"));
            Future<IterationResult> first = workers.submit(() -> executor.execute(request("mq-1", "VU-1")));
            assertTrue(delegate.getEntered.await(2L, TimeUnit.SECONDS));
            assertEquals(1, resources.mqPool("broker").active());

            Future<IterationResult> second = workers.submit(() -> executor.execute(request("mq-2", "VU-2")));
            IterationResult timedOut = second.get(2L, TimeUnit.SECONDS);
            assertEquals(ResultStatus.ERROR, timedOut.status());
            assertEquals("MQ_POOL_TIMEOUT", timedOut.context().resolve("EXEC.ACTIONS.request.output.error.type"));
            assertEquals(1, delegate.maxConcurrentGets.get());

            delegate.releaseGet.countDown();
            assertEquals(ResultStatus.PASS, first.get(2L, TimeUnit.SECONDS).status());
            assertEquals(0, resources.mqPool("broker").active());

            IterationResult recovered = executor.execute(request("mq-3", "VU-3"));
            assertEquals(ResultStatus.PASS, recovered.status());
            assertEquals(1, delegate.connections.get());
            assertEquals(1, delegate.maxConcurrentGets.get());
            assertEquals(0, resources.mqPool("broker").active());
            assertEquals(4, delegate.queueCloses.get());
        } finally {
            delegate.releaseGet.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2L, TimeUnit.SECONDS);
        }
        assertEquals(1, delegate.disconnects.get());
    }

    @Test
    void cancellingAnIterationWithAnActiveMqLeaseInvalidatesAndCleansItUp() throws Exception {
        BlockingFactory delegate = new BlockingFactory();
        FrameworkConfig config = config(1, 0, 500L);
        LoadTarget target = target();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (LoadRunResources resources = new LoadRunResources(tempDir, config, delegate)) {
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output"));
            Future<IterationResult> running = workers.submit(() -> executor.execute(request("mq-cancel", "VU-1")));
            assertTrue(delegate.getEntered.await(2L, TimeUnit.SECONDS));
            assertEquals(1, resources.mqPool("broker").active());

            assertTrue(running.cancel(true));
            assertTrue(delegate.getFinished.await(2L, TimeUnit.SECONDS));
            workers.shutdown();
            assertTrue(workers.awaitTermination(2L, TimeUnit.SECONDS));
            assertTrue(awaitActive(resources.mqPool("broker"), 2L), "cancelled MQ iteration leaked its lease");
            assertEquals(1, delegate.disconnects.get());
            assertEquals(2, delegate.queueCloses.get());
        } finally {
            delegate.releaseGet.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void blockedMqHandshakeHonorsOverallPreparationDeadlineAndClosesLateConnection() throws Exception {
        FrameworkConfig config = config(2, 0, 100L);
        LoadTarget target = target();
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger interruptedConnect = new AtomicInteger();
        AtomicInteger disconnectCount = new AtomicInteger();
        MqTransport.Factory slowFactory = helper -> {
            connectEntered.countDown();
            try { releaseConnect.await(); }
            catch (InterruptedException interrupted) {
                interruptedConnect.incrementAndGet();
                boolean released = false;
                while (!released) {
                    try { releaseConnect.await(); released = true; }
                    catch (InterruptedException ignored) { /* emulate a transport that needs its own timeout */ }
                }
            }
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    throw new AssertionError("Readiness must not open a business queue");
                }
                @Override public void disconnect() { disconnectCount.incrementAndGet(); disconnected.countDown(); }
                @Override public void close() { disconnect(); }
            };
        };
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        int permitsBefore = LoadRunResources.availablePreparationPermits();
        long startedNanos = System.nanoTime();

        try (LoadRunResources resources = new LoadRunResources(tempDir, config, slowFactory, 400L)) {
            resources.setPreparationObserver(event -> events.add(new LinkedHashMap<String, Object>(event)));
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("mq-deadline-output"));
            LoadScenario scenario = new LoadScenario(tempDir.resolve("scenario-mq-deadline.yaml"), "template", "MQ",
                    Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(), LoadScenario.Model.CLOSED,
                    1, 0.0, null, Duration.ZERO, Duration.ZERO, Duration.ofMillis(100), Duration.ZERO,
                    Duration.ZERO, 0, "", Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());
            ClosedVuScheduler scheduler = new ClosedVuScheduler(scenario, executor, "blocked-mq-run",
                    (LoadEventListener) event -> events.add(Collections.<String, Object>singletonMap("event", "ITERATION_EVENT")));

            assertThrows(LoadResourcePreparationException.class, scheduler::run);
            assertTrue(resources.isClosed());
        } finally {
            releaseConnect.countDown();
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        assertTrue(connectEntered.await(1L, TimeUnit.SECONDS), "the test must reach the blocking MQ handshake");
        assertTrue(interruptedConnect.get() > 0, "bounded cancellation must interrupt the in-flight MQ connect");
        assertTrue(disconnected.await(2L, TimeUnit.SECONDS), "late MQ connections must be closed after timeout");
        assertEquals(1, disconnectCount.get());
        assertTrue(elapsedMs < 2_000L, "the overall preparation deadline should bound scheduler release: " + elapsedMs + " ms");
        long permitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (LoadRunResources.availablePreparationPermits() != permitsBefore && System.nanoTime() < permitDeadline)
            Thread.sleep(10L);
        assertEquals(permitsBefore, LoadRunResources.availablePreparationPermits(), "preparation permits must be released on timeout");
        assertTrue(events.stream().anyMatch(event -> "LOAD_PREPARATION_FAILED".equals(event.get("event"))), events.toString());
        assertTrue(events.stream().noneMatch(event -> "LOAD_RESOURCES_READY".equals(event.get("event"))), events.toString());
        assertTrue(events.stream().noneMatch(event -> "LOAD_START".equals(event.get("event"))), events.toString());
        assertTrue(events.stream().noneMatch(event -> "ITERATION_EVENT".equals(event.get("event"))), events.toString());
        Map<String, Object> failed = events.stream().filter(event -> "LOAD_PREPARATION_FAILED".equals(event.get("event")))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("PREPARATION_TIMEOUT", failed.get("errorType"));
    }

    @Test
    void timedOutMqConnectKeepsPreparationBudgetUntilLateConnectionTeardownCompletes() throws Exception {
        FrameworkConfig config = config(1, 0, 100L);
        Semaphore budget = new Semaphore(1, true);
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch interruptedConnect = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        CountDownLatch disconnectEntered = new CountDownLatch(1);
        CountDownLatch releaseDisconnect = new CountDownLatch(1);
        AtomicInteger secondConnections = new AtomicInteger();
        MqTransport.Factory slowFactory = helper -> {
            connectEntered.countDown();
            try { releaseConnect.await(); }
            catch (InterruptedException cancelled) {
                interruptedConnect.countDown();
                boolean released = false;
                while (!released) {
                    try { releaseConnect.await(); released = true; }
                    catch (InterruptedException ignored) { /* emulate a transport that ignores cancellation */ }
                }
            }
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    throw new AssertionError("Readiness must not open a business queue");
                }
                @Override public void disconnect() throws Exception {
                    disconnectEntered.countDown();
                    releaseDisconnect.await();
                }
                @Override public void close() throws Exception { disconnect(); }
            };
        };
        LoadRunResources first = new LoadRunResources(tempDir, config, slowFactory, 400L, budget);
        LoadRunResources second = null;
        try {
            IterationExecutor firstExecutor = new IterationExecutor(tempDir, config, target(), first,
                    tempDir.resolve("mq-connect-timeout-first"));
            assertThrows(LoadResourcePreparationException.class,
                    () -> firstExecutor.prepareResources("mq-connect-timeout-first"));
            assertTrue(connectEntered.await(1L, TimeUnit.SECONDS), "the first run must enter its MQ handshake");
            assertTrue(interruptedConnect.await(1L, TimeUnit.SECONDS), "the preparation deadline must cancel the handshake");
            assertEquals(0, budget.availablePermits(), "the first run keeps its reservation while connect is outstanding");

            MqTransport.Factory unusedFactory = helper -> {
                secondConnections.incrementAndGet();
                throw new AssertionError("a second run must wait for the first run's physical session teardown");
            };
            second = new LoadRunResources(tempDir, config, unusedFactory, 150L, budget);
            IterationExecutor secondExecutor = new IterationExecutor(tempDir, config, target(), second,
                    tempDir.resolve("mq-connect-timeout-second"));
            assertThrows(LoadResourcePreparationException.class,
                    () -> secondExecutor.prepareResources("mq-connect-timeout-second"));
            assertEquals(0, secondConnections.get(), "the second run must not connect while the first factory is in flight");
            assertEquals(0, budget.availablePermits());

            releaseConnect.countDown();
            assertTrue(disconnectEntered.await(2L, TimeUnit.SECONDS), "the late MQ session must enter physical teardown");
            assertEquals(0, budget.availablePermits(), "a returned but not yet disconnected session remains charged");
            releaseDisconnect.countDown();
            assertTrue(awaitPermits(budget, 1, 2L), "the reservation returns only after the late session closes");
        } finally {
            releaseConnect.countDown();
            releaseDisconnect.countDown();
            if (second != null) second.close();
            first.close();
        }
        assertEquals(0, secondConnections.get());
    }

    @Test
    void failedMqDisconnectKeepsPreparationBudgetChargedWhileAnotherRunWaits() throws Exception {
        FrameworkConfig config = config(1, 0, 100L);
        Semaphore budget = new Semaphore(1, true);
        CountDownLatch disconnectEntered = new CountDownLatch(1);
        CountDownLatch allowDisconnectFailure = new CountDownLatch(1);
        AtomicInteger disconnectAttempts = new AtomicInteger();
        MqTransport.Factory failingFactory = helper -> new MqTransport.Connection() {
            @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                throw new AssertionError("Readiness must not open a business queue");
            }
            @Override public void disconnect() throws Exception {
                disconnectAttempts.incrementAndGet();
                disconnectEntered.countDown();
                allowDisconnectFailure.await();
                throw new Exception("synthetic MQ disconnect failure");
            }
            @Override public void close() throws Exception { disconnect(); }
        };
        LoadRunResources first = new LoadRunResources(tempDir, config, failingFactory, 3_000L, budget);
        ExecutorService cleanup = Executors.newSingleThreadExecutor();
        LoadRunResources second = null;
        try {
            IterationExecutor firstExecutor = new IterationExecutor(tempDir, config, target(), first,
                    tempDir.resolve("mq-close-failure-first"));
            firstExecutor.prepareResources("mq-close-failure-first");
            assertEquals(0, budget.availablePermits(), "the prepared connection must hold the shared charge");

            Future<RuntimeException> closing = cleanup.submit(() -> {
                try { first.close(); return null; }
                catch (RuntimeException failure) { return failure; }
            });
            assertTrue(disconnectEntered.await(2L, TimeUnit.SECONDS), "owner close must attempt physical MQ disconnect");

            MqTransport.Factory unusedFactory = helper -> {
                throw new AssertionError("a second run must not connect before acquiring the shared budget");
            };
            second = new LoadRunResources(tempDir, config, unusedFactory, 400L, budget);
            IterationExecutor secondExecutor = new IterationExecutor(tempDir, config, target(), second,
                    tempDir.resolve("mq-close-failure-second"));
            LoadRunResources blockedRun = second;
            assertThrows(LoadResourcePreparationException.class,
                    () -> secondExecutor.prepareResources("mq-close-failure-second"));
            assertEquals(0, budget.availablePermits(), "an overlapping run must not reuse an unconfirmed MQ session slot");

            allowDisconnectFailure.countDown();
            RuntimeException teardownFailure = closing.get(2L, TimeUnit.SECONDS);
            assertNotNull(teardownFailure, "failed disconnect must propagate to the resource owner");
            assertTrue(teardownFailure.getMessage().contains("MQ_RESOURCE_TEARDOWN_FAILED"), teardownFailure.toString());
            assertEquals(1, disconnectAttempts.get());
            assertEquals(0, budget.availablePermits(), "failed teardown must quarantine its preparation charge");
            blockedRun.close();
        } finally {
            allowDisconnectFailure.countDown();
            if (second != null) second.close();
            first.close();
            cleanup.shutdownNow();
            cleanup.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    @Test
    void loadMqRequestUsesProductionIterationPathWithoutEagerSuccessWorkspaceAndRetainsCappedFailure() throws Exception {
        BlockingFactory successFactory = new BlockingFactory();
        successFactory.releaseGet.countDown();
        FrameworkConfig config = config(2, 0, 1000L);
        LoadTarget target = target();
        Path outputRoot = tempDir.resolve("load-output");
        try (LoadRunResources resources = new LoadRunResources(tempDir, config, successFactory)) {
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, outputRoot);
            IterationResult success = executor.execute(request("mq-success", "VU-1"));
            assertEquals(ResultStatus.PASS, success.status());
            assertEquals(1, successFactory.putCalls.get());
            assertEquals(1, successFactory.getCalls.get());
            assertFalse(Files.exists(success.outputDirectory()),
                    "a successful load MQ request must not eagerly materialize its workspace");
        }

        BlockingFactory failureFactory = new BlockingFactory();
        failureFactory.failGet = true;
        failureFactory.releaseGet.countDown();
        LoadEvidenceStore evidence = new LoadEvidenceStore(new LoadEvidencePolicy(
                LoadEvidencePolicy.Success.NONE, LoadEvidencePolicy.Failure.FULL, 0.0, 1));
        try (LoadRunResources resources = new LoadRunResources(tempDir, config, failureFactory)) {
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("failure-output"));
            assertTrue(evidence.reserveEvidence("mq-failure"));
            IterationResult failure = executor.execute(request("mq-failure", "VU-1")
                    .withOutputDirectory(outputRoot.resolve("load/mq-run/iterations"))
                    .withEvidenceRetention(false, true));
            assertEquals(ResultStatus.ERROR, failure.status());
            assertNotNull(failure.evidenceRef());
            assertTrue(Files.isRegularFile(failure.outputDirectory().resolve("case.log")));
            evidence.onEvent(LoadEvent.completed("mq-run", "closed", "STEADY", "mq-failure", "VU-1", 1,
                    0L, 0L, 1L, failure.status(), failure.evidenceRef()));
            assertEquals(1, evidence.events().size());
            assertTrue(Files.isDirectory(evidence.events().get(0).evidence().workspace()));
        }
    }

    @Test
    void loadSchedulerExecutesFlowMqRequestWithMetricsAndFailureEvidencePolicies() throws Exception {
        Path project = tempDir.resolve("project");
        att.TestSchemas.install(project);
        Path flowDirectory = project.resolve("templates/flows/load/mq");
        Files.createDirectories(flowDirectory);
        Path payload = flowDirectory.resolve("request.xml");
        Files.write(payload, "<request/>\n".getBytes("UTF-8"));
        Files.write(flowDirectory.resolve("flow.yaml"), (
                "schemaVersion: att-flow/v3.4\n"
                + "id: load.mq.request.v1\nname: Load MQ request\ndescription: scheduler MQ flow\nactions:\n"
                + "  request:\n    type: tool\n    call: \"#{mq.broker.request(payload=&{./request.xml}, waitMs=1000)}\"\n").getBytes("UTF-8"));
        FrameworkConfig base = config(2, 0, 1000L);
        Path helper = project.resolve("config/mqhelpers/mq-routing.yaml"); Files.createDirectories(helper.getParent());
        Files.write(helper,("schemaVersion: att-mqhelper/v1.3\nid: broker\nname: Broker\ndescription: Queue routing\n"
                +"defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: CH}\n"
                +"  message: {requestQueues: [REQUEST.A, REQUEST.B], requestQueueSelection: {strategy: roundRobin}, replyQueue: REPLY.Q}\n"
                +"instances: [{id: primary}]\n").getBytes("UTF-8"));
        Map<String,MqHelperConfig> routed = new att.config.MqHelperConfigLoader().load(Collections.singletonList("config/mqhelpers/mq-routing.yaml"),project);
        FrameworkConfig config = new FrameworkConfig(base.outputDirectory(),base.reportDirectory(),base.logDirectory(),base.environment(),
                base.timeoutMs(),base.templatesRoot(),base.testcasesRoot(),base.tools(),base.dbHelpers(),routed,null,null,
                null,"","",null,null,1,"ignore","",false,ProcessOutputConfig.defaults());
        Path metricsScenarioFile = project.resolve("mq-metrics.yaml");
        LoadTestSupport.writeScenario(metricsScenarioFile, "schemaVersion: att-load/v1.3\n"
                + "workloads:\n  - id: mq\n    target: {type: flow, id: load.mq.request.v1}\n"
                + "    load: {users: 1, duration: 1s}\n    execution: {thinkTime: 10ms}\n"
                + "evidence: {mode: metrics}\n");
        LoadScenario metricsScenario = new LoadScenarioLoader(project).load(metricsScenarioFile);
        LoadScenario metricsWorkload = metricsScenario.forWorkload(metricsScenario.workload());
        LoadTarget metricsTarget = new LoadTargetResolver(project, config).resolve(metricsWorkload);
        Path outputRoot = tempDir.resolve("scheduler-output");
        BlockingFactory metricsFactory = new BlockingFactory();
        metricsFactory.releaseGet.countDown();
        List<LoadEvent> metricEvents = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadEvidenceStore metricsEvidence = new LoadEvidenceStore(LoadEvidencePolicy.from(metricsScenario));
        try (LoadRunResources resources = new LoadRunResources(project, config, metricsFactory)) {
            IterationExecutor executor = new IterationExecutor(project, config, metricsTarget, resources, outputRoot);
            ClosedVuScheduler scheduler = new ClosedVuScheduler(metricsWorkload, executor, "mq-metrics", event -> {
                metricEvents.add(event);
                metricsEvidence.onEvent(event);
            }, LoadSchedulerTiming.system(), metricsEvidence, outputRoot, null);
            scheduler.run();
            assertTrue(metricEvents.stream().anyMatch(LoadEvent::completed));
            assertTrue(metricsFactory.putQueues.contains("REQUEST.A"));assertTrue(metricsFactory.putQueues.contains("REQUEST.B"));
            assertTrue(metricEvents.stream().filter(LoadEvent::completed).allMatch(event -> event.status() == ResultStatus.PASS));
            assertFalse(Files.exists(outputRoot.resolve("load/mq-metrics/iterations")),
                    "metrics mode must keep successful Flow iterations workspace-free");
        }

        Path failureScenarioFile = project.resolve("mq-failures.yaml");
        LoadTestSupport.writeScenario(failureScenarioFile, "schemaVersion: att-load/v1.3\n"
                + "workloads:\n  - id: mq\n    target: {type: flow, id: load.mq.request.v1}\n"
                + "    load: {users: 1, duration: 1s}\n    execution: {thinkTime: 10ms}\n"
                + "evidence: {mode: failures, maxSamples: 1}\n");
        LoadScenario failureScenario = new LoadScenarioLoader(project).load(failureScenarioFile);
        LoadScenario failureWorkload = failureScenario.forWorkload(failureScenario.workload());
        LoadTarget failureTarget = new LoadTargetResolver(project, config).resolve(failureWorkload);
        BlockingFactory failureFactory = new BlockingFactory();
        failureFactory.failGet = true;
        failureFactory.releaseGet.countDown();
        List<LoadEvent> failureEvents = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadEvidenceStore failureEvidence = new LoadEvidenceStore(LoadEvidencePolicy.from(failureScenario));
        try (LoadRunResources resources = new LoadRunResources(project, config, failureFactory)) {
            IterationExecutor executor = new IterationExecutor(project, config, failureTarget, resources, outputRoot);
            ClosedVuScheduler scheduler = new ClosedVuScheduler(failureWorkload, executor, "mq-failures", event -> {
                failureEvents.add(event);
                failureEvidence.onEvent(event);
            }, LoadSchedulerTiming.system(), failureEvidence, outputRoot, null);
            scheduler.run();
            assertTrue(failureEvents.stream().anyMatch(LoadEvent::completed));
            assertEquals(1, failureEvidence.events().size());
            LoadEvent retained = failureEvidence.events().get(0);
            assertEquals(ResultStatus.ERROR, retained.status());
            assertNotNull(retained.evidence());
            assertTrue(Files.isRegularFile(retained.evidence().caseLog()));
            try (java.util.stream.Stream<Path> paths = Files.walk(outputRoot.resolve("load/mq-failures/executions"))) {
                assertEquals(1L, paths.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("mq-failures-"))
                        .count());
            }
        }
    }

    private FrameworkConfig config(int maxSize, int minIdle, long timeoutMs) {
        Map<String, MqHelperConfig> helpers = new LinkedHashMap<String, MqHelperConfig>();
        helpers.put("broker", new MqHelperConfig("broker", "Broker", "test broker", "QM1", "localhost", 1414,
                "DEV.APP.SVRCONN", "user", "secret", 1208, "MQSTR", "asQueue", 1000,
                "metadata", maxSize, minIdle, timeoutMs, tempDir.resolve("mq.yaml")));
        return new FrameworkConfig(tempDir.resolve("output"), tempDir.resolve("report"), tempDir.resolve("logs"),
                "SIT", 10000, tempDir.resolve("project/templates"), tempDir.resolve("project/testcase"),
                Collections.emptyMap(), Collections.emptyMap(), helpers, null, null,
                null, "", "", null, null, 1, "ignore", "", false, ProcessOutputConfig.defaults());
    }

    private LoadTarget target() throws Exception {
        Path project = tempDir.resolve("project");
        Path directory = project.resolve("templates/MQ");
        Files.createDirectories(directory);
        Path payload = directory.resolve("request.xml");
        Files.write(payload, "<request/>\n".getBytes("UTF-8"));
        TemplateAction action = new TemplateAction("request", map(
                "type", "tool",
                "call", "#{mq.broker.request(payload=&{./request.xml}, requestQueue='REQUEST.Q', replyQueue='REPLY.Q', waitMs=1000)}"), "att-template/v3.4");
        StageTemplate template = new StageTemplate("MQ", directory, Collections.singletonList(action),
                "att-template/v3.4", directory.resolve("template.yaml"));
        FlowRegistry flows = new FlowRegistry(project, project.resolve("templates"), false);
        return new LoadTarget("template", "MQ", template, flows, project.resolve("templates"), project.resolve("scenario.yaml"));
    }

    private IterationRequest request(String id, String userId) {
        return IterationRequest.closed("load-mq", id, 1, "STEADY", Instant.now(), userId, Collections.emptyMap());
    }

    private boolean awaitActive(LoadResourcePool<MqTransport.Connection> pool, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (pool.active() != 0 && System.nanoTime() < deadline) Thread.sleep(10L);
        return pool.active() == 0;
    }

    private boolean awaitPermits(Semaphore budget, int expected, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (budget.availablePermits() != expected && System.nanoTime() < deadline) Thread.sleep(10L);
        return budget.availablePermits() == expected;
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private static final class BlockingFactory implements MqTransport.Factory {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicInteger queueCloses = new AtomicInteger();
        final AtomicInteger putCalls = new AtomicInteger();
        final List<String> putQueues = new java.util.concurrent.CopyOnWriteArrayList<String>();
        final AtomicInteger getCalls = new AtomicInteger();
        final AtomicInteger activeGets = new AtomicInteger();
        final AtomicInteger maxConcurrentGets = new AtomicInteger();
        final CountDownLatch getEntered = new CountDownLatch(1);
        final CountDownLatch releaseGet = new CountDownLatch(1);
        final CountDownLatch getFinished = new CountDownLatch(1);
        boolean failGet;

        @Override public MqTransport.Connection connect(MqHelperConfig config) {
            connections.incrementAndGet();
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    return new MqTransport.Queue() {
                        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) {
                            putCalls.incrementAndGet();putQueues.add(queue);
                            return new MqTransport.Message(new byte[]{1}, null, payload);
                        }

                        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
                            getCalls.incrementAndGet();
                            int active = activeGets.incrementAndGet();
                            for (;;) {
                                int previous = maxConcurrentGets.get();
                                if (active <= previous || maxConcurrentGets.compareAndSet(previous, active)) break;
                            }
                            getEntered.countDown();
                            try {
                                releaseGet.await();
                                if (failGet) throw new MqTransport.Exception("MQ request failed", 2, 2059,
                                        "MQRC_Q_MGR_NOT_AVAILABLE", null);
                                return new MqTransport.Message(new byte[]{1}, null, new byte[]{7});
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new MqTransport.Exception("MQ get interrupted", null, 2009,
                                        "MQRC_CONNECTION_BROKEN", interrupted);
                            } finally {
                                activeGets.decrementAndGet();
                                getFinished.countDown();
                            }
                        }

                        @Override public void close() { queueCloses.incrementAndGet(); }
                    };
                }

                @Override public void disconnect() { disconnects.incrementAndGet(); }
                @Override public void close() { disconnect(); }
            };
        }
    }
}
