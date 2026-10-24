package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        LoadRunResult child=new LoadRunResult("errors-runtime-only",scenario,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),snapshot,evaluated,Collections.emptyMap(),Collections.emptyMap());
        Map<String,LoadRunResult> workloads=Collections.singletonMap("runtime-only",child);
        LoadRunResult run=new LoadRunResult("errors",scenario,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),snapshot,evaluated,Collections.emptyMap(),Collections.emptyMap(),workloads);
        assertEquals(ResultStatus.ERROR,run.status());String html=new String(Files.readAllBytes(new LoadReportWriter().write(temp,run)),java.nio.charset.StandardCharsets.UTF_8);
        for(String label:Arrays.asList("SUT Fail %","Runtime Error %","Total Error %","Successful TPS","100.00%","SUT FAIL-only"))assertTrue(html.contains(label),label);
        assertTrue(html.contains("Thresholds: 2 passed / 1 failed / Execution: ERROR"));
        assertTrue(html.contains("<strong>0 (0.00%)</strong>"),"overview SUT failure card");
        assertTrue(html.contains("<strong>100 (100.00%)</strong>"),"overview runtime and total error cards");
        assertTrue(html.contains("<span class=\"muted\">Total ERROR</span>"),"overview total error card");
        assertTrue(html.contains("<b>runtime-only</b>"));
        assertTrue(html.contains("0.00%</span></td><td><span title=\"100 / 100 measured completions\">100.00%</span></td><td><span title=\"100 / 100 measured completions\">100.00%</span>"));
        assertTrue(html.contains("class=\"fail\">ERROR</td>"));
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
        assertEquals(ResultStatus.ERROR,run.status());String html=new String(Files.readAllBytes(new LoadReportWriter().write(temp,run)),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("N/A"));assertTrue(html.contains("Thresholds: none configured / Execution: ERROR"));
    }
    @Test void workloadWithoutSamplesUsesTheExplicitEmptyState() throws Exception {
        LoadScenario scenario=scenario(Collections.emptyMap());
        LoadMetricsSnapshot snapshot=metrics(ResultStatus.PASS).snapshot();
        LoadRunResult child=new LoadRunResult("empty-child",scenario,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),snapshot);
        Map<String,LoadRunResult> workloads=Collections.singletonMap("empty",child);
        LoadRunResult run=new LoadRunResult("empty-samples",scenario,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),snapshot,
                LoadThresholdSummary.empty(),Collections.emptyMap(),Collections.emptyMap(),workloads);
        String html=new String(Files.readAllBytes(new LoadReportWriter().write(temp,run)),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(2,count(html,"No lightweight failure samples were recorded."));
        assertFalse(html.contains("<pre>null</pre>"));
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
    @Test void diagnosticsKeepHttpMeaningWhileRemovingHostPaths() {
        String[][] redacted = {
                {"C:\\Users\\att\\Program Files\\config.yaml", "C:\\Users"},
                {"/home/att/failed request.json", "/home/att"},
                {"/Users/att/failed request.json", "/Users/att"},
                {"'C:\\Program Files\\ATT\\config.yaml'", "C:\\Program Files"},
                {"\"/Users/att/Library/Application Support/att.log\"", "/Users/att/Library"}
        };
        for (String[] input : redacted) {
            String safe = LoadFailureDiagnostic.safe("failure at " + input[0]);
            assertFalse(safe.contains(input[1]), input[0]);
            assertTrue(safe.contains("[path]"), input[0]);
            assertEquals(safe, LoadFailureDiagnostic.safe(safe), input[0]);
        }

        String preserved = LoadFailureDiagnostic.safe(
                "GET /api/v1/payments returned HTTP 500/502 via http://host/x and/or queue DEV.QUEUE.1");
        assertTrue(preserved.contains("GET /api/v1/payments"));
        assertTrue(preserved.contains("HTTP 500/502"));
        assertTrue(preserved.contains("http://host/x"));
        assertTrue(preserved.contains("and/or"));
        assertTrue(preserved.contains("DEV.QUEUE.1"));
        assertEquals(preserved, LoadFailureDiagnostic.safe(preserved));
    }
    @Test void completionErrorTypeIsSanitizedOnceAndRemainsSafeThroughCopies() {
        LoadEvent event=LoadEvent.completion("r","closed","STEADY","i","u",1,1000,1000,1100,
                ResultStatus.ERROR,"password=secret /private/x",null);
        assertFalse(event.errorType().contains("secret"));
        assertFalse(event.errorType().contains("/private/x"));
        String first=event.errorType();
        assertEquals(first,event.withWorkloadIdentity("checkout","flow","PURCHASE").errorType());
        assertEquals("LOAD_TIMEOUT",LoadEvent.completion("r","closed","STEADY","i","u",1,1000,1000,1100,
                ResultStatus.ERROR,"LOAD_TIMEOUT",null).errorType());
    }
    @Test void phaseClassificationsRemainBoundedUnderConcurrentDistinctErrors() throws Exception {
        final LoadMetrics collector=metrics();
        int workers=8, events=300;
        ExecutorService pool=Executors.newFixedThreadPool(workers);
        CountDownLatch ready=new CountDownLatch(workers),start=new CountDownLatch(1);
        List<Future<?>> futures=new ArrayList<Future<?>>();
        for(int worker=0;worker<workers;worker++) {
            final int offset=worker;
            futures.add(pool.submit(new Runnable() {
                @Override public void run() {
                    ready.countDown();
                    try { start.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    for(int index=offset;index<events;index+=workers)
                        collector.onEvent(LoadEvent.completed("r","closed","STEADY","i"+index,"u",index,1000,1000,1100,
                                ResultStatus.ERROR,"TYPE_"+index,null));
                }
            }));
        }
        ready.await();start.countDown();
        for(Future<?> future:futures)future.get();
        pool.shutdownNow();
        Map<?,?> classifications=(Map<?,?>)collector.snapshot().phases().get("STEADY").get("errorClassifications");
        long total=0L;for(Object value:classifications.values())total+=((Number)value).longValue();
        assertEquals(events,total);
        assertTrue(classifications.size()<=257,classifications.keySet().toString());
        assertEquals(44L,((Number)classifications.get("OTHER")).longValue());
    }
    @Test void newAndLegacyErrorThresholdsKeepStrictAndInclusiveBoundaries() {
        LoadMetricsSnapshot half=metrics(ResultStatus.PASS,ResultStatus.ERROR).snapshot();
        assertThreshold(half,"runtimeErrorRate","< 50%",false);assertThreshold(half,"runtimeErrorRate","<= 50%",true);
        assertThreshold(half,"totalErrorRate","< 50%",false);assertThreshold(half,"totalErrorRate","<= 50%",true);
        LoadMetricsSnapshot legacyHalf=metrics(ResultStatus.PASS,ResultStatus.FAIL).snapshot();
        assertThreshold(legacyHalf,"errorRate","< 50%",false);assertThreshold(legacyHalf,"errorRate","<= 50%",true);
        assertThreshold(half,"errorRate","< 1%",true);
    }
    private void assertThreshold(LoadMetricsSnapshot snapshot,String name,String expression,boolean expected) {
        Map<String,Object> configured=new LinkedHashMap<String,Object>();configured.put(name,expression);
        LoadThresholdSummary result=new LoadThresholdEvaluator().evaluate(scenario(configured),snapshot);
        assertEquals(expected,result.results().get(0).passed(),name+" "+expression);
    }
    private static int count(String value,String needle) {
        int count=0,index=0;while((index=value.indexOf(needle,index))>=0){count++;index+=needle.length();}return count;
    }
}
