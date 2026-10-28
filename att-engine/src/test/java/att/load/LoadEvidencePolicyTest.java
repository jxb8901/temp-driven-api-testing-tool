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

    @Test void perWorkloadSuccessCapsNeverBlockOrReclaimAnotherWorkloadsFirstSuccess() throws Exception {
        LoadEvidenceStore store=new LoadEvidenceStore(perWorkloadPolicy(2,2,
                "busy",Collections.<String,Object>emptyMap(),"slow",Collections.<String,Object>emptyMap()));
        store.registerWorkload("busy");store.registerWorkload("slow");
        assertTrue(store.reserveSuccessEvidence("busy-1","busy"));
        assertTrue(store.reserveSuccessEvidence("busy-2","busy"));
        assertFalse(store.reserveSuccessEvidence("busy-3","busy"));
        store.onEvent(event("busy-1","busy",ResultStatus.PASS));
        store.onEvent(event("busy-2","busy",ResultStatus.PASS));
        // The busy workload is at its own cap. The slow workload's late first success still
        // has its full independent allowance and no retained busy evidence is revoked.
        assertTrue(store.reserveSuccessEvidence("slow-1","slow"));
        store.onEvent(event("slow-1","slow",ResultStatus.PASS));
        assertEquals(3,store.events().size());
        assertTrue(store.events().stream().anyMatch(value->"busy-1".equals(value.iterationId())));
        assertTrue(store.events().stream().anyMatch(value->"busy-2".equals(value.iterationId())));
        assertTrue(store.events().stream().anyMatch(value->"slow-1".equals(value.iterationId())));
        Map<String,Object> report=store.statistics();
        assertEquals(2,row(report,"busy").get("retainedSuccessCount"));
        assertEquals(2,row(report,"busy").get("maxSuccessSamples"));
        assertEquals(1,row(report,"slow").get("retainedSuccessCount"));
        assertEquals(2,row(report,"slow").get("maxSuccessSamples"));
        assertEquals(1L,report.get("skippedSuccessQuotaCount"));
        assertEquals(Boolean.TRUE,report.get("successQuotaExceeded"));
    }

    @Test void perWorkloadSuccessAndFailureCapsAreIndependent() throws Exception {
        LoadEvidenceStore store=new LoadEvidenceStore(perWorkloadPolicy(1,1,
                "payment",Collections.<String,Object>emptyMap(),"queue",Collections.<String,Object>emptyMap()));
        store.registerWorkload("payment");store.registerWorkload("queue");
        assertTrue(store.claimFailureEvidence("payment-failure","payment"));
        store.onEvent(event("payment-failure","payment",ResultStatus.ERROR));
        assertFalse(store.claimFailureEvidence("payment-failure-2","payment"));
        assertFalse(store.retainsFailureEvidence("payment"));
        assertTrue(store.retainsFailureEvidence("queue"),"One workload's failure cap does not affect another");
        assertTrue(store.reserveSuccessEvidence("payment-success","payment"),"Failure exhaustion does not consume success capacity");
        store.onEvent(event("payment-success","payment",ResultStatus.PASS));
        assertTrue(store.claimFailureEvidence("queue-failure","queue"));
        store.onEvent(event("queue-failure","queue",ResultStatus.ERROR));
        assertEquals(3,store.events().size());
        assertEquals(1L,store.statistics().get("skippedFailureQuotaCount"));
        assertEquals(Boolean.TRUE,row(store.statistics(),"payment").get("failureQuotaExceeded"));
        assertEquals(Boolean.FALSE,row(store.statistics(),"queue").get("failureQuotaExceeded"));
    }

    @Test void perWorkloadDefaultsPartialOverridesAndZeroCapsResolveIndependently() {
        LoadEvidencePolicy policy=perWorkloadPolicy(3,4,
                "inherits",Collections.<String,Object>emptyMap(),
                "partial",Collections.<String,Object>singletonMap("maxSuccessSamples",0),
                "override",new LinkedHashMap<String,Object>() {{
                    put("maxSuccessSamples",9);put("maxFailureSamples",0);
                }});
        assertTrue(policy.perWorkloadQuotas());
        assertEquals(3,policy.successCapFor("inherits"));
        assertEquals(4,policy.failureCapFor("inherits"));
        assertEquals(0,policy.successCapFor("partial"));
        assertEquals(4,policy.failureCapFor("partial"),"A partial override inherits the other default");
        assertEquals(9,policy.successCapFor("override"));
        assertEquals(0,policy.failureCapFor("override"));
        assertEquals(3,policy.defaultSuccessCap());
        assertEquals(4,policy.defaultFailureCap());
        assertEquals("perWorkload",policy.toMap().get("quotaMode"));
    }

    @Test void perWorkloadRejectsMixedSyntaxAndOversizedAggregatePreflight() {
        assertThrows(IllegalArgumentException.class,()->perWorkloadPolicy(1,1,
                "bad",Collections.<String,Object>singletonMap("mode","all")));
        Map<String,Object> rootCaps=new LinkedHashMap<String,Object>();
        rootCaps.put("quotaScope","perWorkload");rootCaps.put("maxFailureSamples",1);
        assertThrows(IllegalArgumentException.class,()->LoadEvidencePolicy.validateQuotas(rootCaps));
        assertThrows(IllegalArgumentException.class,()->policy("quotaScope","perWorkload","defaults",
                Collections.<String,Object>singletonMap("mode","all")));
        assertThrows(IllegalArgumentException.class,()->policy("quotaScope","run","defaults",
                Collections.<String,Object>singletonMap("maxSuccessSamples",1)));
        assertThrows(IllegalArgumentException.class,()->perWorkloadPolicy(1,1,
                "legacy-override",Collections.<String,Object>singletonMap("maxSamples",1)));
        assertThrows(IllegalArgumentException.class,()->perWorkloadPolicy(
                LoadEvidencePolicy.MAX_AGGREGATE_CAP,1,"a",Collections.<String,Object>emptyMap(),
                "b",Collections.<String,Object>emptyMap()));
    }

    @Test void perWorkloadReservationsReleaseOnMissingEvidenceDropsAndReclassification() throws Exception {
        LoadEvidenceStore store=new LoadEvidenceStore(perWorkloadPolicy(1,1,
                "one",Collections.<String,Object>emptyMap()));
        store.registerWorkload("one");
        assertTrue(store.reserveSuccessEvidence("missing","one"));
        store.onEvent(LoadEvent.completed("run","closed","STEADY","missing","VU-1",1,0,0,1,
                ResultStatus.PASS,null));
        assertTrue(store.reserveSuccessEvidence("dropped","one"));
        store.onEvent(LoadEvent.dropped("run","arrivalRate","STEADY","dropped",1,0,1));
        assertTrue(store.reserveSuccessEvidence("reclassified","one"));
        assertTrue(store.claimFailureEvidence("reclassified","one"));
        store.onEvent(event("reclassified","one",ResultStatus.FAIL));
        assertTrue(store.reserveSuccessEvidence("actual","one"));
        store.onEvent(event("actual","one",ResultStatus.PASS));
        Map<String,Object> report=store.statistics();
        assertEquals(1,report.get("retainedSuccessCount"));
        assertEquals(0,report.get("reservedSuccessCount"));
        assertEquals(0,report.get("reservedFailureCount"));
        assertEquals(1L,report.get("skippedMissingEvidenceCount"));
    }

    @Test void perWorkloadConcurrentReservationsRespectEachCaps() throws Exception {
        LoadEvidenceStore store=new LoadEvidenceStore(perWorkloadPolicy(7,11,
                "first",Collections.<String,Object>emptyMap(),"second",Collections.<String,Object>emptyMap()));
        store.registerWorkload("first");store.registerWorkload("second");
        ExecutorService workers=Executors.newFixedThreadPool(12);List<Future<?>> tasks=new ArrayList<Future<?>>();
        try {
            for(int i=0;i<800;i++) {
                final int n=i;
                tasks.add(workers.submit(()->{
                    String workload=n%2==0?"first":"second";
                    String id=workload+"-"+n;boolean failure=n%4>=2;
                    boolean admitted=failure?store.claimFailureEvidence(id,workload)
                            :store.reserveSuccessEvidence(id,workload);
                    if(admitted)store.onEvent(event(id,workload,failure?ResultStatus.ERROR:ResultStatus.PASS));
                    else store.releaseEvidence(id);
                    return null;
                }));
            }
            for(Future<?> task:tasks)task.get();
        } finally {workers.shutdownNow();}
        Map<String,Object> report=store.statistics();
        assertEquals(14,report.get("retainedSuccessCount"));
        assertEquals(22,report.get("retainedFailureCount"));
        assertEquals(7,row(report,"first").get("retainedSuccessCount"));
        assertEquals(11,row(report,"first").get("retainedFailureCount"));
        assertEquals(7,row(report,"second").get("retainedSuccessCount"));
        assertEquals(11,row(report,"second").get("retainedFailureCount"));
        assertEquals(0,report.get("reservedSuccessCount"));
        assertEquals(0,report.get("reservedFailureCount"));
    }

    @Test void perWorkloadCoverageReportsTargetAndSamplingIneligibility() throws Exception {
        LoadEvidenceStore store=new LoadEvidenceStore(perWorkloadPolicy(0.0,2,2,
                "payment",Collections.<String,Object>emptyMap()));
        store.registerWorkload("payment","PAYMENT_TEMPLATE");
        store.onEvent(event("pass-1","payment",ResultStatus.PASS));
        store.onEvent(event("pass-2","payment",ResultStatus.PASS));

        Map<String,Object> payment=row(store.statistics(),"payment");
        assertEquals("PAYMENT_TEMPLATE",payment.get("targetId"));
        assertEquals(2L,payment.get("eligibleSuccessCount"));
        assertEquals(2L,payment.get("samplingIneligibleSuccessCount"));
        assertEquals(0,payment.get("retainedSuccessCount"));
        assertEquals(0L,payment.get("skippedSuccessQuotaCount"),
                "a success rejected by the sampling hash is not also reported as a cap skip");
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

    @Test void stableHashSamplingIsWellDistributedAcrossSequentialIdsPrefixesAndPhases() {
        LoadEvidencePolicy sampled=policy("mode","samples","sampleRate",0.10);
        String observedPrefix="load-1793151008298-sub20370-arrival-";
        int selected=0;
        for(int i=1;i<=13824;i++)if(sampled.sampleSuccess(observedPrefix+i))selected++;
        assertTrue(selected>1200&&selected<1560,"10% sample of observed sequential IDs should be close to rate; selected="+selected);
        assertEquals((int)0xf6a5c420L,LoadEvidencePolicy.murmur3_32("foo".getBytes(java.nio.charset.StandardCharsets.UTF_8),0),
                "MurmurHash3 x86 32-bit matches the published seed-0 reference vector");
        assertEquals(sampled.sampleSuccess(observedPrefix+12345),
                policy("mode","samples","sampleRate",0.10).sampleSuccess(observedPrefix+12345),
                "hash selection is stable across independently resolved policies");
        assertEquals(LoadEvidencePolicy.SUCCESS_HASH_VERSION,sampled.toMap().get("successSamplingHash"));
        assertEquals("UTF-8",sampled.toMap().get("successSamplingEncoding"));

        String[] workloads={"payment","queue","billing"};
        String[] phases={"WARMUP","RAMP_UP","STEADY","RAMP_DOWN"};
        for(String workload:workloads)for(String phase:phases) {
            int groupSelected=0;
            for(int i=1;i<=5000;i++) {
                String id="load-run-"+workload+"-"+phase+"-arrival-"+i;
                if(sampled.sampleSuccess(id))groupSelected++;
            }
            assertTrue(groupSelected>400&&groupSelected<600,
                    workload+"/"+phase+" should not have a prefix or phase-specific sampling block: "+groupSelected);
        }
    }

    @Test void boundedCombinedRetentionKeepsAppendOnlySamplesAcrossThePlannedRun() throws Exception {
        LoadEvidenceStore store=new LoadEvidenceStore(policy("mode","samples","sampleRate",1.0,"maxSamples",10));
        store.registerRunTimeline(null,0L,10000L);
        String[] phases={"WARMUP","WARMUP","RAMP_UP","RAMP_UP","STEADY","STEADY","STEADY","RAMP_DOWN","RAMP_DOWN","RAMP_DOWN"};
        for(int window=0;window<10;window++)for(int item=0;item<20;item++) {
            long plannedAt=window*1000L+item;
            String id="run-arrival-"+(window*20+item+1);
            store.reserveSuccessEvidence(id,null,plannedAt);
            store.onEvent(eventAt(id,ResultStatus.PASS,phases[window],plannedAt));
        }
        assertEquals(10,store.events().size());
        assertEquals(10,store.events().stream().map(value->value.scheduledAtEpochMs()/1000L).distinct().count(),
                "each retained sample remains in its original run-time window");
        Map<String,Object> stats=store.statistics();
        assertEquals(200L,stats.get("completedSuccessCount"));
        assertEquals(200L,stats.get("hashQualifiedSuccessCount"));
        assertEquals(0L,stats.get("samplingIneligibleSuccessCount"));
        assertEquals(10,stats.get("retainedSuccessCount"));
        assertEquals(190L,stats.get("skippedSuccessQuotaCount"));
        assertEquals(true,stats.get("combinedQuotaExceeded"));
        assertEquals(LoadEvidencePolicy.RETENTION_STRATEGY,
                ((Map<?,?>)stats.get("retentionCoverage")).get("strategy"));
        assertEquals(true,((Map<?,?>)stats.get("retentionCoverage")).get("appendOnly"));
    }

    @Test void legacyCombinedQuotaDenialsCoverSuccessFailureMixedAndOutcomeReclassification() throws Exception {
        LoadEvidenceStore successes=new LoadEvidenceStore(policy("mode","samples","sampleRate",1.0,"maxSamples",2));
        for(int i=0;i<3;i++) {
            String id="success-"+i;
            boolean reserved=successes.reserveSuccessEvidence(id);
            if(reserved)successes.onEvent(event(id,ResultStatus.PASS));
            else successes.onEvent(event(id,ResultStatus.PASS));
        }
        assertEquals(2,successes.events().size());
        assertEquals(1L,successes.statistics().get("skippedSuccessQuotaCount"));
        assertEquals(true,successes.statistics().get("combinedQuotaExceeded"));

        LoadEvidenceStore failures=new LoadEvidenceStore(policy("mode","metrics","failure","full","maxSamples",2));
        for(int i=0;i<3;i++) {
            String id="failure-"+i;
            if(failures.claimFailureEvidence(id))failures.onEvent(event(id,ResultStatus.FAIL));
            else failures.onEvent(event(id,ResultStatus.FAIL));
        }
        assertEquals(2,failures.events().size());
        assertEquals(1L,failures.statistics().get("skippedFailureQuotaCount"));
        assertEquals(true,failures.statistics().get("combinedQuotaExceeded"));

        LoadEvidenceStore mixed=new LoadEvidenceStore(policy("mode","samples","sampleRate",1.0,"maxSamples",2));
        assertTrue(mixed.reserveSuccessEvidence("retained-success"));
        mixed.onEvent(event("retained-success",ResultStatus.PASS));
        assertTrue(mixed.claimFailureEvidence("retained-failure"));
        mixed.onEvent(event("retained-failure",ResultStatus.FAIL));
        assertFalse(mixed.reserveSuccessEvidence("denied-then-fails"));
        assertFalse(mixed.claimFailureEvidence("denied-then-fails"));
        mixed.onEvent(event("denied-then-fails",ResultStatus.FAIL));
        assertEquals(2,mixed.events().size());
        assertEquals(0L,mixed.statistics().get("skippedSuccessQuotaCount"),"one failed iteration is not double-counted as a skipped success");
        assertEquals(1L,mixed.statistics().get("skippedFailureQuotaCount"));
        assertEquals(0L,mixed.statistics().get("skippedUnclassifiedQuotaCount"));
    }

    private static LoadEvidencePolicy policyStore(Object... values) {
        return LoadEvidencePolicy.from(scenario(values));
    }

    private static LoadEvidencePolicy policy(Object... values) {
        return LoadEvidencePolicy.from(scenario(values));
    }

    @SuppressWarnings("unchecked")
    private static LoadEvidencePolicy perWorkloadPolicy(int successCap, int failureCap, Object... overrides) {
        return perWorkloadPolicy(1.0, successCap, failureCap, overrides);
    }

    @SuppressWarnings("unchecked")
    private static LoadEvidencePolicy perWorkloadPolicy(double sampleRate, int successCap, int failureCap,
                                                        Object... overrides) {
        Map<String,Object> defaults=new LinkedHashMap<String,Object>();
        defaults.put("maxSuccessSamples",successCap);
        defaults.put("maxFailureSamples",failureCap);
        Map<String,Object> evidence=new LinkedHashMap<String,Object>();
        evidence.put("quotaScope","perWorkload");evidence.put("mode","samples");evidence.put("sampleRate",sampleRate);
        evidence.put("defaults",defaults);
        List<LoadWorkload> workloads=new ArrayList<LoadWorkload>();
        for(int i=0;i<overrides.length;i+=2) {
            String id=String.valueOf(overrides[i]);
            Map<String,Object> workloadEvidence=(Map<String,Object>)overrides[i+1];
            workloads.add(new LoadWorkload(id,"template","TARGET",Collections.<String,Object>emptyMap(),
                    Collections.<String,Object>emptyMap(),Collections.<String,Object>emptyMap(),
                    LoadScenario.Model.CLOSED,1,0.0,null,Duration.ZERO,Duration.ZERO,
                    Duration.ofSeconds(1L),Duration.ZERO,ThinkTimePolicy.fixed(Duration.ZERO),0,"drop",
                    Collections.<String,Object>emptyMap(),workloadEvidence));
        }
        return LoadEvidencePolicy.from(new LoadScenario(Paths.get("evidence.yaml"),"att-load/v1.7",
                workloads,1L,Collections.<String,Object>emptyMap(),evidence));
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

    private LoadEvent eventAt(String id, ResultStatus status, String phase, long scheduledAt) throws Exception {
        Path workspace=temp.resolve(id);
        Files.createDirectories(workspace);
        Path caseLog=workspace.resolve("case.log");
        Files.write(caseLog,Collections.singletonList("evidence"));
        return LoadEvent.completed("run","arrivalRate",phase,id,null,1,scheduledAt,scheduledAt,scheduledAt+1L,
                status,new EvidenceRef(workspace,caseLog,null));
    }

    private LoadEvent event(String id, String workload, ResultStatus status) throws Exception {
        return event(id, status).withWorkloadId(workload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> row(Map<String,Object> report, String workload) {
        return (Map<String,Object>)((Map<String,Object>)report.get("workloads")).get(workload);
    }
}
