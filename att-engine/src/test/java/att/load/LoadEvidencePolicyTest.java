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

    @Test void splitQuotasRemainIndependentAndReclassifyPreselectedFailures() throws Exception {
        LoadEvidenceStore store=new LoadEvidenceStore(policy("mode","samples","sampleRate",1.0,"maxSuccessSamples",1,"maxFailureSamples",2));
        assertTrue(store.reserveSuccessEvidence("preselected-failure"));
        store.onEvent(LoadEvent.started("run","closed","STEADY","preselected-failure","VU-1",1,0,0));
        assertTrue(store.claimFailureEvidence("preselected-failure"));
        store.onEvent(event("preselected-failure",ResultStatus.FAIL));
        assertTrue(store.reserveSuccessEvidence("success"),"Failure released its success reservation");
        store.onEvent(event("success",ResultStatus.PASS));
        assertFalse(store.reserveSuccessEvidence("exhausted-success"));
        assertTrue(store.retainsFailureEvidence(),"Success quota does not consume failure capacity");
        assertTrue(store.claimFailureEvidence("non-sampled-failure"));
        store.onEvent(event("non-sampled-failure",ResultStatus.ERROR));
        assertFalse(store.claimFailureEvidence("extra-failure"));
        assertEquals(3,store.events().size());
        assertEquals(1,store.statistics().get("retainedSuccessCount"));assertEquals(2,store.statistics().get("retainedFailureCount"));
        assertEquals(0,store.statistics().get("reservedSuccessCount"));assertEquals(0,store.statistics().get("reservedFailureCount"));
        assertEquals(true,store.statistics().get("failureQuotaExceeded"));assertTrue(store.statistics().containsKey("warning"));
    }

    @Test void exhaustedFailureQuotaDoesNotSuppressSuccessAndMissingEvidenceReleasesSlots() throws Exception {
        LoadEvidenceStore store=new LoadEvidenceStore(policy("mode","samples","sampleRate",1.0,"maxSuccessSamples",1,"maxFailureSamples",0));
        assertFalse(store.claimFailureEvidence("failure"));assertFalse(store.retainsFailureEvidence());
        assertTrue(store.reserveSuccessEvidence("missing"));
        store.onEvent(LoadEvent.completed("run","closed","STEADY","missing","VU-1",1,0,0,1,ResultStatus.PASS,null));
        assertTrue(store.reserveSuccessEvidence("missing-log"));
        store.onEvent(LoadEvent.completed("run","closed","STEADY","missing-log","VU-1",1,0,0,1,ResultStatus.PASS,
                new EvidenceRef(Files.createDirectories(temp.resolve("missing-log")),null,null)));
        assertTrue(store.reserveSuccessEvidence("dropped"));
        store.onEvent(LoadEvent.dropped("run","arrivalRate","STEADY","dropped",1,0,1));
        assertTrue(store.reserveSuccessEvidence("success"));store.onEvent(event("success",ResultStatus.PASS));
        assertEquals(1,store.events().size());assertEquals(2L,store.statistics().get("skippedMissingEvidenceCount"));
        LoadEvidenceStore failuresOnly=new LoadEvidenceStore(policy("mode","samples","sampleRate",0.0,"maxSuccessSamples",0,"maxFailureSamples",1));
        assertFalse(failuresOnly.reserveSuccessEvidence("not-sampled"));
        assertTrue(failuresOnly.claimFailureEvidence("failed"));failuresOnly.onEvent(event("failed",ResultStatus.ERROR));
        assertEquals(1,failuresOnly.events().size());
    }

    @Test void quotaWarningSurvivesAnInFlightReservationThatWasLaterReleased() {
        LoadEvidenceStore store=new LoadEvidenceStore(policy(
                "mode","samples","sampleRate",1.0,"maxSuccessSamples",1,"maxFailureSamples",1));
        assertTrue(store.reserveSuccessEvidence("success-in-flight"));
        assertFalse(store.reserveSuccessEvidence("success-skipped"));
        assertTrue(store.claimFailureEvidence("failure-in-flight"));
        assertFalse(store.claimFailureEvidence("failure-skipped"));
        // Neither in-flight item yielded an evidence artifact, but both quota denials happened.
        store.releaseEvidence("success-in-flight");
        store.releaseEvidence("failure-in-flight");
        Map<String,Object> report=store.statistics();
        assertEquals(0,report.get("retainedSuccessCount"));
        assertEquals(0,report.get("retainedFailureCount"));
        assertEquals(true,report.get("successQuotaExceeded"));
        assertEquals(true,report.get("failureQuotaExceeded"));
        assertEquals(1L,report.get("skippedSuccessQuotaCount"));
        assertEquals(1L,report.get("skippedFailureQuotaCount"));
        assertTrue(report.containsKey("warning"));
    }

    @Test void validatesFiniteSplitQuotasAndDeterministicMigration() {
        LoadEvidencePolicy migrated=policy("mode","all","maxSamples",7,"maxFailureSamples",3);
        assertTrue(migrated.splitQuotas());assertEquals(7,migrated.maxSuccessSamples());assertEquals(3,migrated.maxFailureSamples());
        assertEquals(1000,policy("mode","all","maxFailureSamples",3).maxSuccessSamples(),"Split mode never inherits unbounded all-mode success retention");
        assertThrows(IllegalArgumentException.class,()->policy("maxSuccessSamples",1));
        assertThrows(IllegalArgumentException.class,()->policy("maxSamples",1,"maxSuccessSamples",1,"maxFailureSamples",1));
        for(Object invalid:new Object[]{-1,1.5,1000001,"1",Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->policy("maxFailureSamples",invalid));
    }

    @Test void splitConcurrentReservationsRespectBothLimitsAndReleaseReclassifiedSlots() throws Exception {
        final LoadEvidenceStore store=new LoadEvidenceStore(policy("mode","samples","sampleRate",1.0,"maxSuccessSamples",7,"maxFailureSamples",11));
        ExecutorService workers=Executors.newFixedThreadPool(12);List<Future<?>> tasks=new ArrayList<Future<?>>();
        try {
            for(int i=0;i<500;i++) {
                final int n=i;
                tasks.add(workers.submit(()->{
                    String id="split-"+n;boolean selected=store.reserveSuccessEvidence(id);
                    boolean failure=n%2==0;
                    boolean admitted=failure?store.claimFailureEvidence(id):selected;
                    if(admitted)try{store.onEvent(event(id,failure?ResultStatus.ERROR:ResultStatus.PASS));}catch(Exception error){throw new RuntimeException(error);}
                    else store.releaseEvidence(id);
                }));
            }
            for(Future<?> task:tasks)task.get();
        } finally {workers.shutdownNow();}
        assertEquals(7,store.statistics().get("retainedSuccessCount"));assertEquals(11,store.statistics().get("retainedFailureCount"));
        assertEquals(0,store.statistics().get("reservedSuccessCount"));assertEquals(0,store.statistics().get("reservedFailureCount"));
    }

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

    @Test void unsampledSuccessCannotReserveAFullFailureSlotBeforeOutcome() throws Exception {
        LoadEvidenceStore sampled = new LoadEvidenceStore(new LoadEvidencePolicy(
                LoadEvidencePolicy.Success.SAMPLE, LoadEvidencePolicy.Failure.FULL, 0.0, 1));
        assertFalse(sampled.reserveSuccessEvidence("unsampled-success"));
        assertTrue(sampled.claimFailureEvidence("failure-1"));
        sampled.onEvent(event("failure-1", ResultStatus.FAIL));
        assertEquals(1, sampled.events().size());
        assertEquals("failure-1", sampled.events().get(0).iterationId());
    }

    @Test void failureLogCaptureStopsAtZeroAndExhaustedEvidenceCapacity() throws Exception {
        LoadEvidenceStore zeroCapacity = new LoadEvidenceStore(new LoadEvidencePolicy(
                LoadEvidencePolicy.Success.NONE, LoadEvidencePolicy.Failure.FULL, 0.0, 0));
        assertFalse(zeroCapacity.retainsFailureEvidence(), "zero capacity cannot retain a failure");

        LoadEvidenceStore capped = new LoadEvidenceStore(new LoadEvidencePolicy(
                LoadEvidencePolicy.Success.NONE, LoadEvidencePolicy.Failure.FULL, 0.0, 1));
        assertTrue(capped.retainsFailureEvidence());
        assertTrue(capped.claimFailureEvidence("failure-1"));
        assertFalse(capped.retainsFailureEvidence(), "an in-flight reservation consumes available capacity");
        capped.onEvent(event("failure-1", ResultStatus.FAIL));
        assertFalse(capped.retainsFailureEvidence(), "retained evidence at the cap cannot be extended");
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
