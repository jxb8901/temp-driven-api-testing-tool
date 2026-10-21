package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LoadFailureReportingTest {
    @TempDir Path temp;
    private LoadScenario scenario(Map<String,Object> thresholds) {
        return new LoadScenario(Paths.get("scenario.yaml"),"template","T",Collections.emptyMap(),Collections.emptyMap(),
                LoadScenario.Model.CLOSED,1,0.0,null,Duration.ZERO,Duration.ZERO,Duration.ofSeconds(1),Duration.ZERO,Duration.ZERO,
                0,"",thresholds,Collections.emptyMap());
    }
    private LoadMetrics metrics(ResultStatus... statuses) {
        LoadMetrics collector=LoadMetrics.forScenario(scenario(Collections.emptyMap()),1000,1000);
        for(int i=0;i<statuses.length;i++)collector.onEvent(LoadEvent.completed("r","closed","STEADY","i"+i,"u",i,1000,1000,1100,statuses[i]));
        collector.finish(2000);return collector;
    }
    @Test void allRuntimeErrorsKeepLegacyThresholdMeaningAndShowHonestReport() throws Exception {
        ResultStatus[] errors=new ResultStatus[100];Arrays.fill(errors,ResultStatus.ERROR);
        LoadMetricsSnapshot snapshot=metrics(errors).snapshot();
        assertEquals(0,snapshot.doubleValue("sutErrorRate"));assertEquals(1,snapshot.doubleValue("runtimeErrorRate"));assertEquals(1,snapshot.doubleValue("totalErrorRate"));
        assertEquals(100,snapshot.doubleValue("completedThroughput"));assertEquals(0,snapshot.doubleValue("successfulThroughput"));
        Map<String,Object> thresholds=new LinkedHashMap<>();thresholds.put("errorRate","< 1%");thresholds.put("totalErrorRate","< 1%");thresholds.put("runtimeErrorRate","<= 100%");
        LoadScenario scenario=scenario(thresholds);LoadThresholdSummary evaluated=new LoadThresholdEvaluator().evaluate(scenario,snapshot);
        assertTrue(evaluated.results().get(0).passed());assertFalse(evaluated.results().get(1).passed());assertTrue(evaluated.results().get(2).passed());
        LoadRunResult run=new LoadRunResult("errors",scenario,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),snapshot,evaluated,Collections.emptyMap(),Collections.emptyMap());
        assertEquals(ResultStatus.ERROR,run.status());String html=new String(Files.readAllBytes(new LoadReportWriter().write(temp,run)),java.nio.charset.StandardCharsets.UTF_8);
        for(String label:Arrays.asList("SUT Fail %","Runtime Error %","Total Error %","Successful TPS","100.00%","Execution: ERROR","SUT FAIL-only"))assertTrue(html.contains(label),label);
        Map<?,?> json=att.validation.JsonSupport.mapper().readValue(temp.resolve("load/errors/load-summary.json").toFile(),Map.class);
        Map<?,?> yaml=new org.yaml.snakeyaml.Yaml().load(Files.newInputStream(temp.resolve("load/errors/load-summary.yaml")));
        assertEquals(((Number)((Map<?,?>)json.get("metrics")).get("totalErrorRate")).doubleValue(),((Number)((Map<?,?>)yaml.get("metrics")).get("totalErrorRate")).doubleValue());
    }
    @Test void mixedStatusesWarmupDropsAndPhaseBucketRatesRemainConsistent() {
        LoadMetrics collector=metrics(ResultStatus.PASS,ResultStatus.FAIL,ResultStatus.ERROR,ResultStatus.INVALID);
        collector.onEvent(LoadEvent.completed("r","closed","WARMUP","warm","u",5,900,900,950,ResultStatus.ERROR));
        collector.onEvent(LoadEvent.dropped("r","closed","STEADY","drop",6,1000,1000));
        LoadMetricsSnapshot snapshot=collector.snapshot();
        assertEquals(.25,snapshot.doubleValue("sutErrorRate"));assertEquals(.5,snapshot.doubleValue("runtimeErrorRate"));assertEquals(.75,snapshot.doubleValue("totalErrorRate"));
        assertEquals(3,snapshot.longValue("runtimeError"));assertEquals(2,snapshot.longValue("measuredRuntimeError"));
        assertEquals(.75,((Number)snapshot.phases().get("STEADY").get("totalErrorRate")).doubleValue());
        assertEquals(.75,((Number)snapshot.buckets().get("1000").get("totalErrorRate")).doubleValue());
        assertEquals(2,((Number)snapshot.buckets().get("1000").get("runtimeError")).longValue());
        assertEquals(1L,((Map<?,?>)snapshot.phases().get("STEADY").get("errorClassifications")).get("INVALID"));
        assertEquals(1,snapshot.longValue("measuredSuccess"));assertEquals(1,snapshot.longValue("measuredDropped"));
    }
    @Test void warmupOnlyErrorsStillFailExecutionButMeasuredRatesAreZeroAndUiSaysNotApplicable() throws Exception {
        LoadMetrics collector=metrics();collector.onEvent(LoadEvent.completed("r","closed","WARMUP","warm","u",1,900,900,950,ResultStatus.ERROR));
        LoadMetricsSnapshot snapshot=collector.snapshot();assertEquals(0,snapshot.longValue("measuredCompleted"));assertEquals(0,snapshot.doubleValue("totalErrorRate"));
        LoadRunResult run=new LoadRunResult("warm",scenario(Collections.emptyMap()),Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),snapshot);
        assertEquals(ResultStatus.ERROR,run.status());assertTrue(new String(Files.readAllBytes(new LoadReportWriter().write(temp,run)),java.nio.charset.StandardCharsets.UTF_8).contains("N/A"));
    }
    @Test void failedEvidenceIoHasADistinctReasonAndPreservesIterationStatus() throws Exception {
        Path file=Files.write(temp.resolve("not-directory"),new byte[]{1});
        att.core.CaseExecutionLog log=att.core.CaseExecutionLog.bounded(temp.resolve("logical/case.log"),false,4096);
        IterationResult result=new IterationResult("io", "io", ResultStatus.FAIL,Duration.ofMillis(1),null,
                Collections.emptyList(),file.resolve("out"),file.resolve("evidence"),null,false,log,null);
        result=result.materializeEvidence();assertEquals(ResultStatus.FAIL,result.status());
        assertEquals("EVIDENCE_IO_ERROR",result.evidenceFailureReason());
        assertEquals("EVIDENCE_IO_ERROR",LoadFailureDiagnostic.result(result).get("evidenceState"));
    }
    @Test void failureSamplesAreBoundedAndSanitizedWithoutDependingOnCaseLogs() {
        LoadMetrics collector=metrics();
        for(int i=0;i<150;i++)collector.onEvent(LoadEvent.completed("r","closed","STEADY","i"+i,"u",i,1000,1000,1100,ResultStatus.ERROR,"LOAD_ITERATION_EXCEPTION",null)
                .withFailureDiagnostic(LoadFailureDiagnostic.exception(new IllegalStateException("MQ failed password=secret at /private/secret/config.yaml")))
                .withWorkloadId("payments").withMixIdentity("mq","flow","request"));
        List<?> samples=(List<?>)collector.snapshot().value("failureDiagnostics");assertEquals(100,samples.size());
        String text=att.validation.JsonSupport.write(samples);
        assertTrue(text.contains("payments"));assertTrue(text.contains("mq"));assertTrue(text.contains("IllegalStateException"));assertTrue(text.contains("SCHEDULER_EXCEPTION_NO_CASE_LOG"));
        assertFalse(text.contains("password=secret"));assertFalse(text.contains("/private/secret"));
        assertEquals(150,collector.snapshot().longValue("measuredRuntimeError"));
    }
}
