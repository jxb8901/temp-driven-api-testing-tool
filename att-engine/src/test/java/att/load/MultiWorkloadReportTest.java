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
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultiWorkloadReportTest {
    @TempDir Path temp;

    @Test void writesSchemaValidAggregateAndPerWorkloadReport() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize().resolve("packages/default");
        Path scenarioFile = temp.resolve("report-v11.yaml");
        Files.write(scenarioFile, (
                "schemaVersion: att-load/v1.3\n"
                + "workloads:\n"
                + "- id: primary\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {arrivalRate: 10/s, duration: 250ms, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "- id: secondary\n  target: {type: tool, id: sample.getSeq, arguments: {seqLen: 8}}\n"
                + "  load: {arrivalRate: 5/s, duration: 250ms, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "thresholds: {errorRate: \"< 100%\"}\n").getBytes(StandardCharsets.UTF_8));
        LoadScenario scenario = new LoadScenarioLoader(root).load(scenarioFile);
        FrameworkConfig config = runtimeConfig(root);
        LoadScenario first = scenario.forWorkload(scenario.workloads().get(0));
        LoadTarget seedTarget = new LoadTargetResolver(root, config).resolve(first);
        LoadRunResult result;
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            IterationExecutor seed = new IterationExecutor(root, config, seedTarget, resources, temp.resolve("out"));
            result = LoadRunCoordinator.runFrom(scenario, seed, "multi-report", new LoadEvidenceStore(LoadEvidencePolicy.from(scenario)), temp.resolve("out"));
        }
        result = result.withThresholds(new LoadThresholdEvaluator().evaluate(scenario, result.metrics()));
        Path report = new LoadReportWriter().write(temp.resolve("out"), result);
        Path summary = temp.resolve("out/load/multi-report/load-summary.json");
        String jsonText = new String(Files.readAllBytes(summary), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> att.validation.JsonSchemaVerifier.verifyJson(
                att.validation.SchemaFiles.resolve(root, "att-load-summary-v1.2.schema.json"), jsonText));
        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper().readValue(jsonText, Map.class);
        assertTrue(json.containsKey("workloads"));
        @SuppressWarnings("unchecked") Map<String, Object> workloads = (Map<String, Object>) json.get("workloads");
        assertTrue(workloads.containsKey("primary"));
        assertTrue(workloads.containsKey("secondary"));
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("<h2>Workloads</h2>"));
        assertTrue(html.contains("primary"));
        assertTrue(html.contains("secondary"));
        assertTrue(html.contains("Steady rates use actual event timestamps"));
        assertTrue(html.contains("Execution</th><th>Performance</th>"));
        assertTrue(html.contains("id=\"rate-chart\""));
    }

    @Test void executesClosedMixAndReportsPerEntryMetrics() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize().resolve("packages/default");
        Path scenarioFile = temp.resolve("mixed-report.yaml");
        Files.write(scenarioFile, ("schemaVersion: att-load/v1.6\nseed: 73\nworkloads:\n"
                + "- id: checkout\n  inputs: {region: HK}\n  mix:\n"
                + "  - id: date\n    weight: 60\n    target: {type: tool, id: sample.getAcDate}\n"
                + "  - id: sequence\n    weight: 40\n    target: {type: tool, id: sample.getSeq, arguments: {seqLen: 8}}\n"
                + "  load: {users: 8, duration: 1s}\n").getBytes(StandardCharsets.UTF_8));
        LoadScenario scenario = new LoadScenarioLoader(root).load(scenarioFile);
        FrameworkConfig config = runtimeConfig(root);
        LoadScenario firstMix = scenario.forMixEntry(scenario.workload(), scenario.workload().mix().get(0));
        LoadTarget seedTarget = new LoadTargetResolver(root, config).resolve(firstMix);
        LoadRunResult result;
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            IterationExecutor seed = new IterationExecutor(root, config, seedTarget, resources, temp.resolve("mixed-out"));
            result = LoadRunCoordinator.runFrom(scenario, seed, "mixed-report", new LoadEvidenceStore(LoadEvidencePolicy.from(scenario)), temp.resolve("mixed-out"));
        }
        Path report = new LoadReportWriter().write(temp.resolve("mixed-out"), result);
        Path summary = temp.resolve("mixed-out/load/mixed-report/load-summary.json");
        String jsonText = new String(Files.readAllBytes(summary), StandardCharsets.UTF_8);
        att.validation.JsonSchemaVerifier.verifyJson(att.validation.SchemaFiles.resolve(root, "att-load-summary-v1.2.schema.json"), jsonText);
        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper().readValue(jsonText, Map.class);
        @SuppressWarnings("unchecked") Map<String, Object> workloads = (Map<String, Object>) json.get("workloads");
        @SuppressWarnings("unchecked") Map<String, Object> checkout = (Map<String, Object>) workloads.get("checkout");
        @SuppressWarnings("unchecked") Map<String, Object> mixes = (Map<String, Object>) checkout.get("mix");
        assertEquals(2, mixes.size());
        long selected = 0L;
        for (Object value : mixes.values()) {
            @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) value;
            long entrySelected = ((Number) entry.get("selectedCount")).longValue();
            assertTrue(entrySelected > 0L);
            selected += entrySelected;
            assertTrue(entry.containsKey("metrics"));
        }
        assertTrue(selected > 0L);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("checkout mix"));
        assertTrue(html.contains("sequence"));
        assertTrue(html.contains("mixed targets"));
    }

    @Test void exactAggregateWorkloadMixAndPhaseRatesMatchJsonYamlAndHtml() throws Exception {
        Path root=Paths.get("").toAbsolutePath().normalize().resolve("packages/default");
        Path source=temp.resolve("exact-breakdown.yaml");
        Files.write(source,("schemaVersion: att-load/v1.7\nworkloads:\n"
                +"- id: checkout\n  target: {type: template, id: CHECKOUT}\n  load: {users: 1, duration: 1s}\n"
                +"- id: payments\n  mix:\n"
                +"  - {id: http, weight: 50, target: {type: template, id: HTTP}}\n"
                +"  - {id: mq, weight: 50, target: {type: template, id: MQ}}\n"
                +"  load: {users: 1, duration: 1s}\n").getBytes(StandardCharsets.UTF_8));
        LoadScenario scenario=new LoadScenarioLoader(root).load(source);
        LoadScenario checkoutScenario=scenario.forWorkload(scenario.workloads().get(0));
        LoadScenario paymentsScenario=scenario.forWorkload(scenario.workloads().get(1));
        LoadMetrics checkout=collector(ResultStatus.PASS,ResultStatus.FAIL);
        LoadMetrics http=collector(ResultStatus.PASS,ResultStatus.FAIL);
        LoadMetrics mq=collector(ResultStatus.PASS,ResultStatus.ERROR);
        LoadMetrics payments=collector(ResultStatus.PASS,ResultStatus.FAIL,ResultStatus.PASS,ResultStatus.ERROR);
        LoadMetrics aggregate=collector(ResultStatus.PASS,ResultStatus.FAIL,ResultStatus.PASS,ResultStatus.FAIL,ResultStatus.PASS,ResultStatus.ERROR);
        Map<String,Object> mixes=new LinkedHashMap<String,Object>();
        mixes.put("http",mix("template","HTTP",2,http.snapshot()));mixes.put("mq",mix("template","MQ",2,mq.snapshot()));
        Map<String,Object> paymentResources=new LinkedHashMap<String,Object>();paymentResources.put("mix",mixes);
        LoadRunResult checkoutResult=new LoadRunResult("exact-checkout",checkoutScenario,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),checkout.snapshot());
        LoadRunResult paymentsResult=new LoadRunResult("exact-payments",paymentsScenario,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),payments.snapshot(),LoadThresholdSummary.empty(),Collections.<String,Object>emptyMap(),paymentResources);
        Map<String,LoadRunResult> workloads=new LinkedHashMap<String,LoadRunResult>();workloads.put("checkout",checkoutResult);workloads.put("payments",paymentsResult);
        LoadRunResult result=new LoadRunResult("exact",scenario,Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),aggregate.snapshot(),LoadThresholdSummary.empty(),Collections.<String,Object>emptyMap(),Collections.<String,Object>emptyMap(),workloads);
        Path report=new LoadReportWriter().write(temp,result);String html=new String(Files.readAllBytes(report),StandardCharsets.UTF_8);
        assertRateCells(aggregate.snapshot().values(),1.0/3.0,1.0/6.0,.5,3.0);
        assertRateCells(checkout.snapshot().values(),.5,0,.5,1.0);assertRateCells(payments.snapshot().values(),.25,.25,.5,2.0);
        assertRateCells(http.snapshot().values(),.5,0,.5,1.0);assertRateCells(mq.snapshot().values(),0,.5,.5,1.0);
        assertTrue(html.contains("checkout"));assertTrue(html.contains("payments mix"));assertTrue(html.contains("50.00%"));assertTrue(html.contains("1.000/s"));
        assertTrue(html.contains("Thresholds: none configured / Execution: ERROR"));
        assertTrue(html.contains("<h2>Run overview</h2>"));
        assertTrue(html.contains("id=\"load-window\""));
        assertTrue(html.contains("SUT FAIL</span>"));
        assertTrue(html.contains("Runtime ERROR</span>"));
        assertTrue(html.contains("Steady started/s"));
        assertTrue(html.contains("Steady p95"));
        assertTrue(html.contains("<td>http</td>"));
        assertTrue(html.contains("<td>mq</td>"));
        assertTrue(html.contains("event-time starts / configured"));
        assertFalse(html.contains("<pre>null</pre>"));
        @SuppressWarnings("unchecked") Map<String,Object> json=att.validation.JsonSupport.mapper().readValue(temp.resolve("load/exact/load-summary.json").toFile(),Map.class);
        @SuppressWarnings("unchecked") Map<String,Object> yaml=(Map<String,Object>)new org.yaml.snakeyaml.Yaml().load(Files.newInputStream(temp.resolve("load/exact/load-summary.yaml")));
        assertSummaryConsistency(json,yaml,"metrics");
        @SuppressWarnings("unchecked") Map<String,Object> jsonWorkloads=(Map<String,Object>)json.get("workloads");
        @SuppressWarnings("unchecked") Map<String,Object> yamlWorkloads=(Map<String,Object>)yaml.get("workloads");
        for(String id:new String[]{"checkout","payments"})assertNestedMetricConsistency((Map<?,?>)jsonWorkloads.get(id),(Map<?,?>)yamlWorkloads.get(id));
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> phases=(Map<String,Map<String,Object>>)((Map<String,Object>)json.get("metrics")).get("phases");
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> yamlPhases=(Map<String,Map<String,Object>>)((Map<String,Object>)yaml.get("metrics")).get("phases");
        assertRateCells(phases.get("STEADY"),1.0/3.0,1.0/6.0,.5,30.0);
        assertNestedMetricConsistency(phases.get("STEADY"),yamlPhases.get("STEADY"));
    }

    private LoadMetrics collector(att.core.ResultStatus... statuses) {
        LoadScenario base=new LoadScenario(Paths.get("scenario.yaml"),"template","T",Collections.emptyMap(),Collections.emptyMap(),LoadScenario.Model.CLOSED,1,0.0,null,
                java.time.Duration.ZERO,java.time.Duration.ZERO,java.time.Duration.ofSeconds(1),java.time.Duration.ZERO,java.time.Duration.ZERO,0,"",Collections.emptyMap(),Collections.emptyMap());
        LoadMetrics result=LoadMetrics.forScenario(base,1000,1000);for(int i=0;i<statuses.length;i++)result.onEvent(LoadEvent.completed("r","closed","STEADY","i"+i,"u",i,1000,1000,1100,statuses[i]));result.finish(2000);return result;
    }
    private static Map<String,Object> mix(String type,String id,long selected,LoadMetricsSnapshot snapshot) {
        Map<String,Object> result=new LinkedHashMap<String,Object>(),target=new LinkedHashMap<String,Object>();target.put("type",type);target.put("id",id);
        result.put("target",target);result.put("configuredWeight",50);result.put("selectedCount",selected);result.put("selectedPercent",50.0);result.put("metrics",snapshot.values());return result;
    }
    private static void assertRateCells(Map<String,Object> metrics,double sut,double runtime,double total,double successful) {
        assertEquals(sut,((Number)metrics.get("sutErrorRate")).doubleValue(),.000001);assertEquals(runtime,((Number)metrics.get("runtimeErrorRate")).doubleValue(),.000001);
        assertEquals(total,((Number)metrics.get("totalErrorRate")).doubleValue(),.000001);assertEquals(successful,((Number)metrics.get("successfulThroughput")).doubleValue(),.000001);
    }
    private static void assertSummaryConsistency(Map<String,Object> json,Map<String,Object> yaml,String key) {assertNestedMetricConsistency((Map<?,?>)json.get(key),(Map<?,?>)yaml.get(key));}
    private static void assertNestedMetricConsistency(Map<?,?> json,Map<?,?> yaml) {
        Map<?,?> jsonMetrics=json.containsKey("metrics")?(Map<?,?>)json.get("metrics"):json;Map<?,?> yamlMetrics=yaml.containsKey("metrics")?(Map<?,?>)yaml.get("metrics"):yaml;
        assertEquals(((Number)jsonMetrics.get("sutErrorRate")).doubleValue(),((Number)yamlMetrics.get("sutErrorRate")).doubleValue());
        assertEquals(((Number)jsonMetrics.get("runtimeErrorRate")).doubleValue(),((Number)yamlMetrics.get("runtimeErrorRate")).doubleValue());
        assertEquals(((Number)jsonMetrics.get("totalErrorRate")).doubleValue(),((Number)yamlMetrics.get("totalErrorRate")).doubleValue());
        assertEquals(((Number)jsonMetrics.get("successfulThroughput")).doubleValue(),((Number)yamlMetrics.get("successfulThroughput")).doubleValue());
    }
    private static String row(String html,String marker) {
        int start=html.indexOf("<tr><td><b>"+marker+"</b>");
        if(start<0)start=html.indexOf("<tr><td>"+marker+"</td>");
        assertTrue(start>=0,marker);
        int end=html.indexOf("</tr>",start);
        assertTrue(end>start,marker);
        return html.substring(start,end+5);
    }

    private static FrameworkConfig runtimeConfig(Path packageRoot) throws Exception {
        Path home = Paths.get("").toAbsolutePath().normalize();
        return new FrameworkConfigLoader().load(home.resolve("config/config.yaml"), home, packageRoot, "SIT");
    }
}
