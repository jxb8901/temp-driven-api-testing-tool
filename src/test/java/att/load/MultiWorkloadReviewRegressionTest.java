package att.load;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MultiWorkloadReviewRegressionTest {
    @TempDir Path temp;

    @Test void singleWorkloadV11UsesCoordinatorAndPropagatesWorkloadThresholdFailure() throws Exception {
        LoadScenario scenario = load("single-v11.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- id: only\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {users: 1, duration: 120ms}\n"
                + "  thresholds: {minThroughput: \">= 999999/s\"}\n");
        Path root = projectRoot();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        LoadTarget target = new LoadTargetResolver(root, config).resolve(scenario);
        LoadRunResult result;
        Path output = temp.resolve("single-out");
        LoadEvidenceStore evidence = new LoadEvidenceStore(LoadEvidencePolicy.from(scenario));
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            IterationExecutor iterations = new IterationExecutor(root, config, target, resources, output);
            LoadScheduler scheduler = new ClosedVuScheduler(scenario, iterations, "single-v11", evidence, output);
            try { result = scheduler.run(); } finally { scheduler.close(); }
        }
        result = result.withThresholds(new LoadThresholdEvaluator().evaluate(scenario, result.metrics()));
        assertEquals(1, result.workloads().size());
        assertEquals(ResultStatus.FAIL, result.workloads().get("only").status());
        assertEquals(ResultStatus.FAIL, result.status());
        assertEquals(1, result.exitCode());
        Path report = new LoadReportWriter().write(output, result);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("minThroughput"));
        assertTrue(html.contains("999999"));
    }

    @Test void coordinatedGateExcludesDelayedWorkerStartupFromRunEnvelope() throws Exception {
        LoadScenario scenario = load("barrier.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- id: first\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {arrivalRate: 20/s, duration: 150ms, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "- id: second\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {arrivalRate: 20/s, duration: 150ms, maxConcurrent: 2, overloadPolicy: drop}\n");
        LoadScenario first = scenario.forWorkload(scenario.workloads().get(0));
        LoadScenario second = scenario.forWorkload(scenario.workloads().get(1));
        LoadSchedulerStartGate gate = new LoadSchedulerStartGate(2);
        List<LoadEvent> firstEvents = Collections.synchronizedList(new ArrayList<LoadEvent>());
        List<LoadEvent> secondEvents = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadIterationRunner runner = request -> new IterationResult(request.iterationId(), ResultStatus.PASS,
                Duration.ofMillis(1L), null, Collections.emptyList(), null, null);
        LoadSchedulerTiming timing = LoadSchedulerTiming.system();
        FixedArrivalRateScheduler one = new FixedArrivalRateScheduler(first, runner, "barrier-run",
                firstEvents::add, timing, null, null, null, gate);
        FixedArrivalRateScheduler two = new FixedArrivalRateScheduler(second, runner, "barrier-run",
                secondEvents::add, timing, null, null, null, gate);
        ExecutorService control = Executors.newFixedThreadPool(2);
        try {
            Future<LoadRunResult> oneFuture = control.submit(one::run);
            Thread.sleep(120L);
            assertTrue(firstEvents.isEmpty(), "first workload must remain behind the start barrier");
            Future<LoadRunResult> twoFuture = control.submit(two::run);
            assertTrue(gate.awaitReady(2L, TimeUnit.SECONDS));
            long t0 = System.currentTimeMillis();
            gate.release(t0);
            LoadRunResult oneResult = oneFuture.get(3L, TimeUnit.SECONDS);
            LoadRunResult twoResult = twoFuture.get(3L, TimeUnit.SECONDS);
            assertEquals(Instant.ofEpochMilli(t0), oneResult.startedAt());
            assertEquals(Instant.ofEpochMilli(t0), twoResult.startedAt());
            assertEquals(0L, oneResult.metrics().longValue("dropped"));
            assertEquals(0L, twoResult.metrics().longValue("dropped"));
            assertTrue(firstEvents.stream().allMatch(event -> event.scheduledAtEpochMs() >= t0));
            assertTrue(secondEvents.stream().allMatch(event -> event.scheduledAtEpochMs() >= t0));
        } finally {
            one.close();
            two.close();
            control.shutdownNow();
            control.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void validatedScenarioDataRemainsDeeplyImmutableForV11AndLegacyV10() throws Exception {
        LoadScenario v11 = load("immutable-v11.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- id: immutable\n  target:\n    type: tool\n    id: sample.getSeq\n    arguments:\n      values: [a, b]\n"
                + "  inputs:\n    payload:\n      flags: [A, B]\n"
                + "  load: {users: 1, duration: 100ms}\n");
        Map payload = (Map) v11.inputs().get("payload");
        List flags = (List) payload.get("flags");
        List arguments = (List) v11.targetArguments().get("values");
        assertThrows(UnsupportedOperationException.class, () -> payload.put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> flags.add("C"));
        assertThrows(UnsupportedOperationException.class, () -> arguments.add("c"));

        LoadScenario v10 = load("immutable-v10.yaml",
                "schemaVersion: att-load/v1.0\n"
                + "target: {type: tool, id: sample.getAcDate}\n"
                + "inputs:\n  payload:\n    flags: [A, B]\n"
                + "load: {users: 1, duration: 100ms}\n");
        Map legacyPayload = (Map) v10.inputs().get("payload");
        List legacyFlags = (List) legacyPayload.get("flags");
        assertThrows(UnsupportedOperationException.class, () -> legacyPayload.put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> legacyFlags.add("C"));
    }

    @Test void retainedV11EvidenceIncludesWorkloadAndTargetIdentity() throws Exception {
        LoadScenario scenario = load("evidence-identity.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- id: account-date\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {users: 1, duration: 100ms}\n  execution: {thinkTime: 1ms}\n"
                + "evidence: {success: sample, sampleRate: 1.0, maxSamples: 5}\n");
        LoadScenario workload = scenario.forWorkload(scenario.workloads().get(0));
        AtomicLong elapsed = new AtomicLong(500L);
        LoadSchedulerTiming timing = LoadSchedulerTiming.anchored(elapsed::get, 1_700_000_000_000L, 500L,
                millis -> elapsed.addAndGet(millis));
        LoadEvidenceStore evidence = new LoadEvidenceStore(LoadEvidencePolicy.from(scenario));
        LoadIterationRunner runner = request -> new IterationResult(request.iterationId(), ResultStatus.PASS,
                Duration.ofMillis(1L), null, Collections.emptyList(), null, null);
        Path output = temp.resolve("identity-output");
        ClosedVuScheduler scheduler = new ClosedVuScheduler(workload, runner, "evidence-identity",
                evidence::onEvent, timing, evidence, output, null);
        scheduler.run();

        Path evidenceDirectory = temp.resolve("retained-evidence");
        Map<String, Object> index = evidence.write(evidenceDirectory);
        Path eventFile;
        try (java.util.stream.Stream<Path> files = Files.list(evidenceDirectory.resolve("samples/account-date"))) {
            eventFile = files.findFirst().orElseThrow(() -> new AssertionError("expected retained sample"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> event = att.validation.JsonSupport.mapper().readValue(eventFile.toFile(), Map.class);
        assertEquals("account-date", event.get("workloadId"));
        assertEquals("tool", event.get("targetType"));
        assertEquals("sample.getAcDate", event.get("targetId"));
        assertEquals("VU-1", event.get("userId"));
        assertTrue(String.valueOf(event.get("iterationId")).contains("account-date"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) index.get("items")).get(0);
        assertEquals("sample.getAcDate", item.get("targetId"));
    }

    private LoadScenario load(String name, String content) throws Exception {
        Path file = temp.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return new LoadScenarioLoader(projectRoot()).load(file);
    }

    private static Path projectRoot() { return Paths.get("").toAbsolutePath().normalize(); }
}
