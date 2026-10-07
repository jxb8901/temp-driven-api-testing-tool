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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, outputRoot);
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
                + "  request:\n    type: tool\n    call: \"#{mq.broker.request(payload=&{request.xml}, requestQueue='REQUEST.Q', replyQueue='REPLY.Q', waitMs=1000)}\"\n").getBytes("UTF-8"));
        FrameworkConfig config = config(2, 0, 1000L);
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
                "call", "#{mq.broker.request(payload=&{request.xml}, requestQueue='REQUEST.Q', replyQueue='REPLY.Q', waitMs=1000)}"), "att-template/v3.4");
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
                            putCalls.incrementAndGet();
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
