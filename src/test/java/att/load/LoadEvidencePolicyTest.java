package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class LoadEvidencePolicyTest {
    @TempDir Path temp;

    @Test void publicModesResolveToDistinctPolicies() {
        LoadEvidencePolicy metrics = policy("mode", "metrics");
        assertEquals(LoadEvidencePolicy.Success.NONE, metrics.success());
        assertEquals(LoadEvidencePolicy.Failure.NONE, metrics.failure());
        assertEquals(0.0, metrics.sampleRate());

        LoadEvidencePolicy failures = policy("mode", "failures");
        assertEquals(LoadEvidencePolicy.Success.NONE, failures.success());
        assertEquals(LoadEvidencePolicy.Failure.FULL, failures.failure());
        assertEquals(1000, failures.maxSamples());

        LoadEvidencePolicy samples = policy("mode", "samples");
        assertEquals(LoadEvidencePolicy.Success.SAMPLE, samples.success());
        assertEquals(LoadEvidencePolicy.Failure.FULL, samples.failure());
        assertEquals(0.01, samples.sampleRate());
        assertEquals(1000, samples.maxSamples());

        LoadEvidencePolicy all = policy("mode", "all");
        assertEquals(LoadEvidencePolicy.Success.FULL, all.success());
        assertEquals(LoadEvidencePolicy.Failure.FULL, all.failure());
        assertEquals(0.0, all.sampleRate(), "sampleRate must not affect full-success retention");
        assertEquals(Integer.MAX_VALUE, all.maxSamples(), "all has no implicit retention cap");
    }

    @Test void omittedEvidenceKeepsFailuresDefaultAndExplicitFieldsOverrideMode() {
        LoadEvidencePolicy omitted = policy();
        assertEquals(LoadEvidencePolicy.Success.NONE, omitted.success());
        assertEquals(LoadEvidencePolicy.Failure.FULL, omitted.failure());

        LoadEvidencePolicy explicit = policy("mode", "all", "success", "sample", "failure", "none",
                "sampleRate", Double.valueOf(0.25), "maxSamples", Integer.valueOf(7));
        assertEquals(LoadEvidencePolicy.Success.SAMPLE, explicit.success());
        assertEquals(LoadEvidencePolicy.Failure.NONE, explicit.failure());
        assertEquals(0.25, explicit.sampleRate());
        assertEquals(7, explicit.maxSamples());

        LoadEvidencePolicy full = policy("mode", "failures", "success", "full");
        assertEquals(LoadEvidencePolicy.Success.FULL, full.success());
        assertEquals(Integer.MAX_VALUE, full.maxSamples(), "full success is uncapped unless maxSamples is explicit");
    }

    @Test void explicitMaxSamplesCapsAllRetainedCompletedEvidenceTogether() throws Exception {
        LoadEvidenceStore store = new LoadEvidenceStore(new LoadEvidencePolicy(
                LoadEvidencePolicy.Success.FULL, LoadEvidencePolicy.Failure.FULL, 0.0, 2));
        assertTrue(store.reserveEvidence("success-1"));
        store.onEvent(event("success-1", ResultStatus.PASS));
        assertTrue(store.reserveEvidence("failure-1"));
        store.onEvent(event("failure-1", ResultStatus.FAIL));
        assertFalse(store.reserveEvidence("success-2"));
        store.onEvent(event("success-2", ResultStatus.PASS));
        store.onEvent(LoadEvent.dropped("run", "arrivalRate", "STEADY", "drop-1", 4, 0L, 1L));

        assertEquals(2, store.events().size());
        assertEquals("success-1", store.events().get(0).iterationId());
        assertEquals("failure-1", store.events().get(1).iterationId());
    }

    @Test void allRetainsEveryCompletedSuccessAndFailureButNeverDroppedArrivals() throws Exception {
        LoadEvidenceStore store = new LoadEvidenceStore(policyStore("mode", "all"));
        assertTrue(store.reserveEvidence("success-1"));
        assertTrue(store.reserveEvidence("failure-1"));
        assertTrue(store.reserveEvidence("success-2"));
        store.onEvent(event("success-1", ResultStatus.PASS));
        store.onEvent(event("failure-1", ResultStatus.ERROR));
        store.onEvent(event("success-2", ResultStatus.PASS));
        store.onEvent(LoadEvent.dropped("run", "arrivalRate", "STEADY", "drop-1", 4, 0L, 1L));

        assertEquals(3, store.events().size());
        assertEquals("failure-1", store.events().get(1).iterationId());
        assertFalse(store.events().stream().anyMatch(event -> event.dropped()));
    }

    @Test void sampleRateOnlyControlsSampledSuccesses() throws Exception {
        LoadEvidenceStore sampled = new LoadEvidenceStore(policyStore("mode", "samples", "sampleRate", 0.0));
        assertTrue(sampled.reserveEvidence("failure-1"));
        sampled.onEvent(event("success-1", ResultStatus.PASS));
        sampled.onEvent(event("failure-1", ResultStatus.FAIL));

        assertEquals(1, sampled.events().size());
        assertEquals("failure-1", sampled.events().get(0).iterationId());
    }

    private static LoadEvidencePolicy policyStore(Object... values) {
        return LoadEvidencePolicy.from(scenario(values));
    }

    private static LoadEvidencePolicy policy(Object... values) {
        return LoadEvidencePolicy.from(scenario(values));
    }

    private static LoadScenario scenario(Object... values) {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) evidence.put(String.valueOf(values[i]), values[i + 1]);
        return new LoadScenario(Paths.get("evidence.yaml"), "template", "EVIDENCE_TEMPLATE",
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                LoadScenario.Model.CLOSED, 1, 0.0, null, Duration.ZERO, Duration.ZERO,
                Duration.ofSeconds(1L), Duration.ZERO, Duration.ZERO, 0, "drop",
                Collections.<String, Object>emptyMap(), evidence);
    }

    @Test void concurrentReservationsBoundRetainedEvidenceAndNeverKeepMissingWorkspaces() throws Exception {
        final LoadEvidenceStore store = new LoadEvidenceStore(new LoadEvidencePolicy(
                LoadEvidencePolicy.Success.FULL, LoadEvidencePolicy.Failure.FULL, 0.0, 8));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 64; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    try {
                        String id = "concurrent-" + index;
                        if (store.reserveEvidence(id)) store.onEvent(event(id, index % 2 == 0 ? ResultStatus.PASS : ResultStatus.FAIL));
                        else store.onEvent(LoadEvent.completed("run", "closed", "STEADY", id, "VU-1", index,
                                0L, 0L, 1L, ResultStatus.PASS));
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                }));
            }
            for (Future<?> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertTrue(store.events().size() <= 8);
        assertTrue(store.events().stream().allMatch(value -> value.evidence() != null
                && Files.isDirectory(value.evidence().workspace())
                && Files.isRegularFile(value.evidence().caseLog())));
    }

    private LoadEvent event(String id, ResultStatus status) throws Exception {
        Path workspace = temp.resolve(id);
        Files.createDirectories(workspace);
        Path caseLog = workspace.resolve("case.log");
        Files.write(caseLog, Collections.singletonList("evidence"));
        return LoadEvent.completed("run", "closed", "STEADY", id, "VU-1", 1,
                0L, 0L, 1L, status, new EvidenceRef(workspace, caseLog, null));
    }
}
