/* Author: Jeffrey + ChatGPT */
package att.core;

import att.config.FrameworkConfig;
import att.config.DbHelperConfig;
import att.config.ReportConfig;
import att.config.RunConfig;
import att.config.StageConfig;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.config.SuiteConfigResolver;
import att.excel.ExcelTestSuiteLoader;
import att.snapshot.TestcaseSnapshotService;
import att.api.ExecutionEvent;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrameworkEngineTest {
    @TempDir Path projectRoot;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(projectRoot); }

    @Test void dryRunCaseIsNotCountedAsAnExecution() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v3.4\nname: PAYMENT_INVOKE\ndescription: dry-run count\nactions:\n"
                        +"  show: {type: log, message: safe}\n");
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"),
                "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n"
                        +"  caseId: 案例編號\n  tags: 標籤\n  dataColumns: caseName=案例名稱\nstages:\n"
                        +"  - key: invoke\n    template: 執行模板\n    required: true\n");
        writeSnapshot(projectRoot.resolve("testcase/payment.xlsx"));

        RunSummary summary=new FrameworkEngine(projectRoot,globalConfig()).run(
                ExecutionOptionsTestSupport.parse(new String[]{"run","--suite",
                        projectRoot.resolve("testcase/payment.xlsx").toString(),"--run-id","DRY-RUN-COUNT","--dry-run"}));

        assertEquals(1,summary.skipped());
        assertEquals(0,summary.executionCount());
        assertFalse(summary.results().get(0).executionStarted());
    }

    @Test void dryRunErrorsAreNotCountedAsStartedExecutions() {
        TestCase testCase=new TestCase(2,"payments","payment","case",Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(),Collections.<String,StageCaseData>emptyMap(),null);
        TestResult error=FrameworkEngine.error(testCase,"dry-run failure",null,java.time.Duration.ZERO,
                null,"payments.payment.case",false);
        assertEquals(ResultStatus.ERROR,error.status());
        assertFalse(error.executionStarted());
    }

    @Test void relocatingNotStartedCasePreservesExecutionEvidence() {
        TestResult skipped=TestResult.notExecuted("fpp.transfer.third","third",Collections.<String>emptyList(),
                "fpp.transfer","fpp.transfer.third","failFast");
        TestResult relocated=skipped.relocate(Paths.get("/old-run"),Paths.get("/new-run"));
        assertFalse(relocated.executionStarted());
        assertNull(relocated.executionId());
    }

    @Test
    void runsNestedV31FlowThroughFullCaseLifecycle() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v3.4\nname: PAYMENT_INVOKE\ndescription: V3.2 shared Context\nactions:\n"
                        + "  compose: {type: flow, use: common.outer.v1}\n"
                        + "  verify: {type: assert, assert: \"${EXEC.VARS.seedReference} == '${META.SOURCE.caseId}'\"}\n");
        writeText(projectRoot.resolve("templates/flows/inner/flow.yaml"),
                "schemaVersion: att-flow/v3.4\nid: common.inner.v1\nname: Inner\ndescription: Inner\nactions:\n"
                        + "  seed: {type: assign, name: seedReference, expression: '${META.SOURCE.caseId}'}\n");
        writeText(projectRoot.resolve("templates/flows/outer/flow.yaml"),
                "schemaVersion: att-flow/v3.4\nid: common.outer.v1\nname: Outer\ndescription: Outer\nactions:\n"
                        + "  innerFlow: {type: flow, use: common.inner.v1}\n"
                        + "  finish: {type: assign, name: finalReference, expression: '${EXEC.VARS.seedReference}-done'}\n");
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"),
                "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\n  dataColumns: caseName=案例名稱\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
        writeSnapshot(projectRoot.resolve("testcase/payment.xlsx"));
        writeRuntimeSchemas();

        ExecutionOptions options = att.core.ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite",
                projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "V3-FLOW"});
        RunSummary summary = new FrameworkEngine(projectRoot, globalConfig()).run(options);

        assertEquals(1, summary.passed());
        Path caseYaml = summary.results().get(0).caseLogPath().getParent().resolve("case.yaml");
        String evidence = new String(Files.readAllBytes(caseYaml), "UTF-8");
        assertTrue(evidence.contains("id: compose"));
        assertTrue(evidence.contains("id: common.outer.v1"));
        assertTrue(evidence.contains("id: innerFlow"));
        assertTrue(evidence.contains("id: seed"));
        assertTrue(evidence.contains("id: finish"));
        assertFalse(evidence.contains("output.outputs"));

        // Exercise the same package through the complete error/report path.
        writeText(projectRoot.resolve("templates/flows/inner/flow.yaml"),
                "schemaVersion: att-flow/v3.4\nid: common.inner.v1\nname: Inner\ndescription: Inner\nactions:\n"
                        + "  seed: {type: assign, name: seedReference, expression: '#{1 / 0}'}\n");
        RunSummary failed = new FrameworkEngine(projectRoot, globalConfig()).run(att.core.ExecutionOptionsTestSupport.parse(new String[]{
                "run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "V3-FLOW-ERROR"}));
        assertEquals(1, failed.error());
        Path failedRun = projectRoot.resolve("output/V3-FLOW-ERROR");
        Path failedCaseYaml = failed.results().get(0).caseLogPath().getParent().resolve("case.yaml");
        String failedCase = new String(Files.readAllBytes(failedCaseYaml), "UTF-8");
        assertTrue(failedCase.contains("common.inner.v1"));
        assertTrue(failedCase.contains("diagnostic:"));
        assertTrue(failedCase.contains("callChain:"));
        String manifest = new String(Files.readAllBytes(failedRun.resolve("run.yaml")), "UTF-8");
        assertTrue(manifest.contains("diagnostic:"));
        assertTrue(manifest.contains("flow.yaml"));
        String ci = new String(Files.readAllBytes(failedRun.resolve("ci/summary.json")), "UTF-8");
        assertTrue(ci.contains("\"callChain\""));
        Path regenerated = new att.report.ReportRegenerator().regenerate(projectRoot.resolve("output"), "V3-FLOW-ERROR");
        assertTrue(new String(Files.readAllBytes(regenerated), "UTF-8").contains("common.inner.v1"));
    }

    @Test void configuredRunIdentityUsesTheSameEnclosingTimestampAsCaseContext() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v3.4\nname: PAYMENT_INVOKE\ndescription: timestamp identity\nactions:\n"
                        + "  show: {type: log, message: 'run=${EXEC.RUN_STARTED_AT}|case=${EXEC.STARTED_AT}'}\n");
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"),
                "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n"
                        + "  caseId: 案例編號\n  tags: 標籤\n  dataColumns: caseName=案例名稱\nstages:\n"
                        + "  - key: invoke\n    template: 執行模板\n    required: true\n");
        writeSnapshot(projectRoot.resolve("testcase/payment.xlsx"));
        Instant seed = Instant.parse("2026-10-07T01:02:03Z");
        String format = "run-#{str.replace(value=${EXEC.RUN_STARTED_AT}, target=':', replacement='-')}";
        FrameworkConfig config = globalConfig("none", new RunConfig("timestamp", "yyyyMMdd-HHmmss", format, ""));
        ExecutionOptions options = ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite",
                projectRoot.resolve("testcase/payment.xlsx").toString()}).withIdentitySeed(new ExecutionIdentitySeed(seed));

        RunSummary summary = new FrameworkEngine(projectRoot, config).run(options);

        assertEquals("run-2026-10-07T01-02-03Z", summary.runId());
        String caseLog = new String(Files.readAllBytes(summary.results().get(0).caseLogPath()), "UTF-8");
        assertTrue(caseLog.contains("run=" + seed + "|case="), caseLog);
    }

    @Test void ordinaryRunReloadsRenderPayloadBetweenTestcases() throws Exception {
        Path payload = projectRoot.resolve("templates/PAYMENT_INVOKE/payload.txt");
        writeText(payload, "before-edit");
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v3.4\nname: PAYMENT_INVOKE\ndescription: render edit lifecycle\nactions:\n"
                        + "  renderPayload: {type: render, payload: payload.txt}\n"
                        + "  updatePayload: {type: tool, call: \"#{replacePayload()}\"}\n");
        Path workbook = projectRoot.resolve("testcase/render-lifecycle.xlsx");
        writeTwoCaseWorkbook(workbook);
        writeText(projectRoot.resolve("testcase/render-lifecycle.yaml"),
                "schemaVersion: att-sidecar/v2.1\nid: render-lifecycle\n"
                        + "excel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\n"
                        + "stages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");

        Path updater = projectRoot.resolve("tools/replace-payload.sh");
        writeTool(updater, "printf 'after-edit' > '" + payload.toAbsolutePath() + "'\n");
        FrameworkConfig base = globalConfig();
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>(base.tools());
        tools.put("replacePayload", new ToolConfig("replacePayload", "Replace payload", "Test payload edit",
                "./tools/replace-payload.sh", "text", Collections.<String, ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(base.outputDirectory(), base.reportDirectory(), base.logDirectory(),
                base.environment(), base.timeoutMs(), base.templatesRoot(), tools, base.report(), base.run(),
                Collections.<att.config.SheetGroupConfig>emptyList(), "案例編號", "標籤",
                Collections.<att.config.DataColumnConfig>emptyList(), Collections.<StageConfig>emptyList());
        writeSnapshot(workbook, config);
        writeRuntimeSchemas();

        RunSummary summary = new FrameworkEngine(projectRoot, config).run(att.core.ExecutionOptionsTestSupport.parse(new String[]{
                "run", "--suite", workbook.toString(), "--run-id", "RENDER-CASE-LIFETIME"}));

        assertEquals(2, summary.passed());
        String firstCase = new String(Files.readAllBytes(summary.results().get(0).caseLogPath()), "UTF-8");
        String secondCase = new String(Files.readAllBytes(summary.results().get(1).caseLogPath()), "UTF-8");
        assertTrue(firstCase.contains("before-edit"), firstCase);
        assertTrue(secondCase.contains("after-edit"), secondCase);
    }

    @Test void runTestdataSelectionAdvancesAcrossTestcasesAndIsStableWithinEachCase() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v3.4\nname: PAYMENT_INVOKE\ndescription: testdata selection\nactions:\n"
                        + "  check:\n    type: assert\n    assert: \"${EXEC.INPUT.accountId} == '${EXEC.INPUT.stageAccountId}'\"\n");
        Path descriptor = projectRoot.resolve("testdata/accounts.yaml");
        writeText(descriptor, testdataDescriptor("recycle"));
        Path workbook = projectRoot.resolve("testcase/testdata.xlsx");
        writeTestdataWorkbook(workbook);
        writeText(projectRoot.resolve("testcase/testdata.yaml"),
                "schemaVersion: att-sidecar/v2.1\nid: testdata\n"
                        + "excel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\n"
                        + "  dataColumns: accountId=帳戶\n"
                        + "stages:\n  - key: invoke\n    template: 執行模板\n    required: true\n"
                        + "    dataColumns: stageAccountId=階段帳戶\n");
        FrameworkConfig config = withTestdata(descriptor);
        writeSnapshot(workbook, config);
        writeRuntimeSchemas();

        RunSummary recycled = new FrameworkEngine(projectRoot, config).run(att.core.ExecutionOptionsTestSupport.parse(new String[]{
                "run", "--suite", workbook.toString(), "--run-id", "TESTDATA-RECYCLE"}));
        assertEquals(3, recycled.passed());
        int[] expectedIndices = {0, 1, 0};
        for (int index = 0; index < recycled.results().size(); index++) {
            Map<?, ?> evidence = readCaseEvidence(recycled.results().get(index).caseLogPath().getParent().resolve("case.yaml"));
            Map<?, ?> selection = (Map<?, ?>) ((Map<?, ?>) evidence.get("testdataSelections")).get("accounts");
            assertEquals(expectedIndices[index], ((Number) selection.get("index")).intValue());
            Map<?, ?> stage = (Map<?, ?>) ((Map<?, ?>) evidence.get("STAGES")).get("invoke");
            assertEquals(evidence.get("accountId"), stage.get("stageAccountId"));
        }

        writeText(descriptor, testdataDescriptor("error"));
        writeSnapshot(workbook, config);
        RunSummary exhausted = new FrameworkEngine(projectRoot, config).run(att.core.ExecutionOptionsTestSupport.parse(new String[]{
                "run", "--suite", workbook.toString(), "--run-id", "TESTDATA-EXHAUSTED"}));
        assertEquals(2, exhausted.passed());
        assertEquals(1, exhausted.error());
        Map<?, ?> failedEvidence = readCaseEvidence(exhausted.results().get(2).caseLogPath().getParent().resolve("case.yaml"));
        assertTrue(String.valueOf(failedEvidence.get("error")).contains("Testdata selection exhausted"));
    }

    @Test
    void runsV2GroupedCaseThroughTemplateAndTool() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v3.4\nname: PAYMENT_INVOKE\ndescription: test\nactions:\n  callApi:\n    type: tool\n    call: \"#{invokePaymentApi(caseId=${CASE.caseId})}\"\n  check:\n    type: assert\n    description: API status\n    assert: \"${ACTIONS.callApi.output.result.Status} == 'SUCCESS'\"\n    expected: SUCCESS\n    actual: \"${ACTIONS.callApi.output.result.Status}\"\n");
        writeTool(projectRoot.resolve("tools/invoke.sh"), "sleep 1\nprintf '%s\\n' \"$PWD\" > tool-cwd.txt\nprintf '%s\\n%s\\n' \"$ATT_ROOT_DIR\" \"$ATT_CASE_OUTPUT_DIR\" > tool-env.txt\nprintf '<Response><Status>SUCCESS</Status></Response>\\n'\n");
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"),
                "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\n  dataColumns: caseName=案例名稱\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
        writeSnapshot(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("schemas/att-ci-summary-v2.1.schema.json"), "{\"type\":\"object\",\"required\":[\"schemaVersion\",\"inputManifestHash\"]}");
        writeText(projectRoot.resolve("schemas/att-run-v2.1.schema.json"), "{\"type\":\"object\",\"required\":[\"schemaVersion\",\"run\",\"inputs\"]}");
        writeText(projectRoot.resolve("schemas/att-junit-v2.1.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"testsuite\"><xs:complexType mixed=\"true\"><xs:sequence><xs:any minOccurs=\"0\" maxOccurs=\"unbounded\" processContents=\"skip\"/></xs:sequence><xs:anyAttribute processContents=\"skip\"/></xs:complexType></xs:element></xs:schema>");

        ExecutionOptions parsedVerboseOptions = att.core.ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "TEST-V2", "--verbose", "--profile"});
        java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
        java.io.PrintStream eventOutput = new java.io.PrintStream(console, true, "UTF-8");
        java.util.List<att.api.ExecutionEvent> events = new java.util.concurrent.CopyOnWriteArrayList<att.api.ExecutionEvent>();
        final ExecutionOptions verboseOptions = parsedVerboseOptions.withObserver(event -> {
            events.add(event);
            String message = event.message();
            if (event.type() == att.api.ExecutionEvent.Type.CASE_LOG)
                message = "[CASE-LOG case=" + event.caseId() + "] " + message;
            if (message != null) synchronized (eventOutput) { eventOutput.println(message); }
        });
        java.util.concurrent.atomic.AtomicReference<RunSummary> summaryRef = new java.util.concurrent.atomic.AtomicReference<RunSummary>();
        java.util.concurrent.atomic.AtomicReference<Throwable> runError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread running;
        boolean sawLiveActionStart = false;
        boolean runStillActiveAtActionStart = false;
        try {
            running = new Thread(() -> {
                try { summaryRef.set(new FrameworkEngine(projectRoot, globalConfig()).run(verboseOptions)); }
                catch (Throwable error) { runError.set(error); }
            });
            running.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4L);
            while (System.nanoTime() < deadline && running.isAlive()) {
                if (console.toString("UTF-8").contains("type: tool, status: START")) {
                    sawLiveActionStart = true;
                    runStillActiveAtActionStart = running.isAlive();
                    break;
                }
                Thread.sleep(10L);
            }
            running.join(4000L);
        } finally { eventOutput.close(); }
        assertTrue(runError.get() == null, String.valueOf(runError.get()));
        assertTrue(sawLiveActionStart, console.toString("UTF-8"));
        assertTrue(runStillActiveAtActionStart, "Run Action start must be visible while the Tool is still running");
        RunSummary summary = summaryRef.get();
        assertTrue(events.stream().anyMatch(event -> "RUN_STARTED".equals(event.data().get("event")) && "TEST-V2".equals(event.runId())));
        assertTrue(events.stream().anyMatch(event -> "SUITE_STARTED".equals(event.data().get("event"))));
        assertTrue(events.stream().anyMatch(event -> "CASE_STARTED".equals(event.data().get("event")) && "payments.payment.TC001".equals(event.caseId())));
        assertTrue(events.stream().anyMatch(event -> "STAGE_STARTED".equals(event.data().get("event")) && "invoke".equals(event.stage())));
        assertTrue(events.stream().anyMatch(event -> "ACTION_FINISHED".equals(event.data().get("event")) && "callApi".equals(event.action())));
        assertTrue(events.stream().anyMatch(event -> "CASE_LOG_PATH".equals(event.data().get("event"))));
        String verbose = console.toString("UTF-8");
        assertTrue(verbose.contains("[CASE-LOG case=payments.payment.TC001] [ACTION]"));
        assertTrue(verbose.contains("resource: TOOL"));
        assertTrue(verbose.contains("<Response>"));

        ExecutionOptions defaultOptions = att.core.ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "TEST-DEFAULT"});
        java.io.ByteArrayOutputStream defaultConsole = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previous = System.out;
        try {
            System.setOut(new java.io.PrintStream(defaultConsole));
            new FrameworkEngine(projectRoot, globalConfig()).run(defaultOptions);
        } finally {
            System.setOut(previous);
        }
        assertEquals("", defaultConsole.toString("UTF-8"));

        ExecutionOptions noWorkbook = att.core.ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "TEST-NO-WORKBOOK"});
        new FrameworkEngine(projectRoot, globalConfig("none")).run(noWorkbook);
        assertFalse(Files.exists(projectRoot.resolve("output/TEST-NO-WORKBOOK/workbooks")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-NO-WORKBOOK/report/index.html")));

        assertEquals(1, summary.passed());
        assertEquals("API status\nSUCCESS", summary.results().get(0).expected());
        assertEquals("API status", summary.results().get(0).validations().get(1).description());
        assertEquals("SUCCESS", summary.results().get(0).actual());
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/workbooks/payment.result.xlsx")));
        Path caseDirectory = summary.results().get(0).caseLogPath().getParent().toAbsolutePath().normalize();
        assertTrue(Files.exists(caseDirectory.resolve("case.yaml")));
        Path cwdEvidence = caseDirectory.resolve("tool-cwd.txt");
        assertTrue(Files.exists(cwdEvidence));
        assertEquals(caseDirectory.toRealPath().toString() + "\n", new String(Files.readAllBytes(cwdEvidence), "UTF-8"));
        java.util.List<String> environment = Files.readAllLines(caseDirectory.resolve("tool-env.txt"), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(projectRoot.toAbsolutePath().normalize().toString(), environment.get(0));
        assertEquals(caseDirectory.toRealPath(), Paths.get(environment.get(1)).toRealPath());
        String caseYaml = new String(Files.readAllBytes(caseDirectory.resolve("case.yaml")), "UTF-8");
        Map<?, ?> persistedCase = (Map<?, ?>) new org.yaml.snakeyaml.Yaml().load(caseYaml);
        assertEquals(att.core.PathPresentation.displayPath(caseDirectory.toRealPath(), projectRoot),
                String.valueOf(persistedCase.get("outputDirectory")));
        assertFalse(caseYaml.contains(".in-progress"));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/events.jsonl")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/ci/summary.json")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/ci/junit.xml")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/report/junit.html")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/performance.json")));
        String performance = new String(Files.readAllBytes(projectRoot.resolve("output/TEST-V2/performance.json")), "UTF-8");
        assertTrue(performance.contains("\"schemaVersion\":\"att-performance/v2.4.3\""));
        assertTrue(performance.contains("\"caseExecutionMs\""));
        assertFalse(Files.exists(caseDirectory.resolve("process-output")));
        Path caseLogPath;
        try (java.util.stream.Stream<Path> paths = Files.list(caseDirectory)) {
            caseLogPath = paths.filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow(AssertionError::new);
        }
        String caseLog = new String(Files.readAllBytes(caseLogPath), "UTF-8");
        assertTrue(caseLog.contains("[TOOL callApi STDOUT]"));
        assertFalse(Files.exists(projectRoot.resolve("output/TEST-V2/ci/junit.html")));
        String manifest = new String(Files.readAllBytes(projectRoot.resolve("output/TEST-V2/run.yaml")), "UTF-8");
        assertTrue(manifest.contains("schemaVersion: att-run/v2.1"));
        assertTrue(manifest.contains("javaVersion:")); assertTrue(manifest.contains("timezone:")); assertTrue(manifest.contains("sha256:"));
        assertTrue(manifest.contains("kind: testcase-snapshot"));
        assertFalse(manifest.contains(".in-progress"));
        assertFalse(Files.exists(projectRoot.resolve("output/.in-progress")));
        String ci = new String(Files.readAllBytes(projectRoot.resolve("output/TEST-V2/ci/summary.json")), "UTF-8");
        assertTrue(ci.contains("\"inputManifestHash\":\""));
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).run(verboseOptions));
        assertTrue(duplicate.getMessage().contains("Run ID already exists: TEST-V2"), duplicate.getMessage());
        IllegalArgumentException preflight = assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).assertRunIdAvailable(verboseOptions));
        assertTrue(preflight.getMessage().contains("Run ID already exists: TEST-V2"), preflight.getMessage());
    }

    @Test void runWhenUsesPriorFailureIndependentlyFromStopState() throws Exception {
        FrameworkConfig config = new FrameworkConfig(projectRoot.resolve("output"), projectRoot.resolve("report"), projectRoot.resolve("logs"), "SIT", 10000,
                projectRoot.resolve("templates"), Collections.emptyMap(), null, null);
        FrameworkEngine engine = new FrameworkEngine(projectRoot, config);
        java.lang.reflect.Method method = FrameworkEngine.class.getDeclaredMethod("shouldRunStage", StageConfig.class, boolean.class, boolean.class);
        method.setAccessible(true);
        StageConfig failure = new StageConfig("rollback", "Rollback", Collections.emptyList(), false, "continue", "onFailure");
        StageConfig success = new StageConfig("verify", "Verify", Collections.emptyList(), false, "continue", "onSuccess");
        assertTrue((Boolean) method.invoke(engine, failure, true, false));
        assertFalse((Boolean) method.invoke(engine, success, true, false));
    }

    @Test void legacyStageAndNativeFlowGoldenFixturePinsCompatibilityBoundary() throws Exception {
        String inputRef = "$" + "{EXEC.INPUT.business}";
        String variableRef = "$" + "{EXEC.VARS.sourceBusiness}";
        String verifySuccessRef = "$" + "{EXEC.ACTIONS.verify.output.success}";

        writeText(projectRoot.resolve("templates/PREPARE/template.yaml"),
                "schemaVersion: att-template/v3.6\nname: PREPARE\ndescription: Prepare\n"
                        + "actions:\n  seed: {type: assign, name: sourceBusiness, expression: '" + inputRef + "'}\n"
                        + "  prepareLog: {type: log, message: 'prepare:" + inputRef + "'}\n");
        writeText(projectRoot.resolve("templates/INVOKE/template.yaml"),
                "schemaVersion: att-template/v3.6\nname: INVOKE\ndescription: Invoke\n"
                        + "actions:\n  invokeLog: {type: log, message: 'invoke:" + inputRef + "|seed:" + variableRef + "'}\n");
        writeText(projectRoot.resolve("templates/VERIFY/template.yaml"),
                "schemaVersion: att-template/v3.6\nname: VERIFY\ndescription: Verify\n"
                        + "actions:\n  verify: {type: assert, assert: 'false', actual: '" + inputRef + "'}\n");
        writeText(projectRoot.resolve("templates/ROLLBACK/template.yaml"),
                "schemaVersion: att-template/v3.6\nname: ROLLBACK\ndescription: Rollback\n"
                        + "actions:\n  rollbackLog: {type: log, message: 'rollback:" + inputRef + "'}\n");
        writeText(projectRoot.resolve("templates/CLEANUP/template.yaml"),
                "schemaVersion: att-template/v3.6\nname: CLEANUP\ndescription: Cleanup\n"
                        + "actions:\n  cleanupLog: {type: log, message: 'cleanup:" + inputRef + "'}\n");

        Path workbook = projectRoot.resolve("testcase/stage-flow-compat.xlsx");
        Files.createDirectories(workbook.getParent());
        try (Workbook book = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(workbook)) {
            Sheet sheet = book.createSheet("Cases");
            Row header = sheet.createRow(0);
            String[] headers = {"Case ID", "Tags", "Business", "Stage Business", "Prepare Template",
                    "Invoke Template", "Verify Template", "Rollback Template", "Cleanup Template"};
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            Row row = sheet.createRow(1);
            String[] values = {"TC001", "compat", "BASE", "STAGE", "name: PREPARE", "name: INVOKE",
                    "name: VERIFY", "name: ROLLBACK", "name: CLEANUP"};
            for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);
            book.write(output);
        }
        writeText(projectRoot.resolve("testcase/stage-flow-compat.yaml"),
                "schemaVersion: att-sidecar/v2.2\nid: compat\n"
                        + "excel:\n  sheet: Cases\n  caseId: Case ID\n  tags: Tags\n  dataColumns: business=Business\n"
                        + "stages:\n"
                        + "  - {key: prepare, template: Prepare Template, required: true}\n"
                        + "  - key: invoke\n    template: Invoke Template\n    required: true\n"
                        + "    dataColumns: business=Stage Business\n"
                        + "  - {key: verify, template: Verify Template, required: true, onFailure: continue}\n"
                        + "  - {key: rollback, template: Rollback Template, runWhen: onFailure}\n"
                        + "  - {key: cleanup, template: Cleanup Template, runWhen: always}\n");
        writeSnapshot(workbook);

        Path definitionPath = projectRoot.resolve("testcase/native-compat/testcase.yaml");
        writeText(definitionPath,
                "schemaVersion: att-testcase/v1\nid: native.compat\nflow: compat.stage-flow.v1\n"
                        + "inputs: {business: BASE}\n");
        writeText(projectRoot.resolve("templates/flows/compat/stage-flow/v1/flow.yaml"),
                "schemaVersion: att-flow/v3.6\nid: compat.stage-flow.v1\nname: Stage Flow Compatibility\n"
                        + "description: Closest Flow analogue to the five-stage fixture\nactions:\n"
                        + "  seed: {type: assign, name: sourceBusiness, expression: '" + inputRef + "'}\n"
                        + "  prepareLog: {type: log, message: 'prepare:" + inputRef + "'}\n"
                        + "  invokeLog: {type: log, message: 'invoke:" + inputRef + "|seed:" + variableRef + "'}\n"
                        + "  verify: {type: assert, assert: 'false', actual: '" + inputRef + "', onFailure: continue}\n"
                        + "  rollbackLog:\n    type: log\n    message: 'rollback:" + inputRef + "'\n"
                        + "    runWhen: '" + verifySuccessRef + " == false'\n"
                        + "  cleanupLog: {type: log, message: 'cleanup:" + inputRef + "', runWhen: 'true'}\n");

        List<ExecutionEvent> legacyEvents = new ArrayList<ExecutionEvent>();
        RunSummary legacy = new FrameworkEngine(projectRoot, globalConfig()).run(
                ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite", workbook.toString(),
                        "--run-id", "STAGE-FLOW-LEGACY"}).withObserver(legacyEvents::add));
        att.testcase.TestCaseDefinitionResolver resolver = new att.testcase.TestCaseDefinitionResolver(
                att.validation.SchemaFiles.resolve(projectRoot, "att-testcase-v1.schema.json"));
        att.testcase.TestCaseResolutionResult resolution = resolver.resolve(definitionPath, "compat", projectRoot,
                Paths.get("templates"), att.testcase.TestCaseDefinitionResolver.Options.defaults());
        List<ExecutionEvent> flowEvents = new ArrayList<ExecutionEvent>();
        RunSummary flow = new FrameworkEngine(projectRoot, globalConfig()).runNativeCases(resolution,
                "STAGE-FLOW-NATIVE", null, false, false, flowEvents::add);

        assertEquals(1, legacy.failed());
        assertEquals(ResultStatus.FAIL, legacy.results().get(0).status());
        assertEquals(1, flow.failed());
        assertEquals(ResultStatus.FAIL, flow.results().get(0).status());

        Path legacyCaseYaml = legacy.results().get(0).caseLogPath().getParent().resolve("case.yaml");
        Path flowCaseYaml = flow.results().get(0).caseLogPath().getParent().resolve("case.yaml");
        Path legacyCaseLog = legacy.results().get(0).caseLogPath();
        Path flowCaseLog = flow.results().get(0).caseLogPath();
        assertTrue(Files.isRegularFile(legacyCaseYaml));
        assertTrue(Files.isRegularFile(flowCaseYaml));
        assertTrue(Files.isRegularFile(legacyCaseLog));
        assertTrue(Files.isRegularFile(flowCaseLog));
        assertNotEquals(projectRoot.resolve("output/STAGE-FLOW-LEGACY").relativize(legacyCaseLog),
                projectRoot.resolve("output/STAGE-FLOW-NATIVE").relativize(flowCaseLog));

        Map<?, ?> legacyEvidence = readCaseEvidence(legacyCaseYaml);
        Map<?, ?> flowEvidence = readCaseEvidence(flowCaseYaml);
        assertEquals("BASE", legacyEvidence.get("business"));
        assertEquals("BASE", CaseRuntimeContext.getPath(legacyEvidence, "VARS.sourceBusiness"));
        assertEquals("STAGE", CaseRuntimeContext.getPath(legacyEvidence, "STAGES.invoke.business"));
        assertEquals("BASE", CaseRuntimeContext.getPath(legacyEvidence, "STAGES.verify.TEMPLATE.ACTIONS.verify.output.actual"));
        assertEquals("FAIL", CaseRuntimeContext.getPath(legacyEvidence, "STAGES.verify.status"));
        assertEquals("PASS", CaseRuntimeContext.getPath(legacyEvidence, "STAGES.rollback.status"));
        assertEquals("PASS", CaseRuntimeContext.getPath(legacyEvidence, "STAGES.cleanup.status"));
        assertEquals("BASE", flowEvidence.get("business"));
        assertEquals("BASE", CaseRuntimeContext.getPath(flowEvidence, "VARS.sourceBusiness"));
        assertEquals("FAIL", CaseRuntimeContext.getPath(flowEvidence, "STAGES.run.status"));
        assertEquals(Boolean.FALSE, CaseRuntimeContext.getPath(flowEvidence,
                "STAGES.run.TEMPLATE.ACTIONS.run.flow.actions.verify.output.success"));
        assertEquals("PASS", CaseRuntimeContext.getPath(flowEvidence,
                "STAGES.run.TEMPLATE.ACTIONS.run.flow.actions.rollbackLog.output.status"));
        assertEquals("PASS", CaseRuntimeContext.getPath(flowEvidence,
                "STAGES.run.TEMPLATE.ACTIONS.run.flow.actions.cleanupLog.output.status"));

        String legacyLog = new String(Files.readAllBytes(legacyCaseLog), "UTF-8");
        String flowLog = new String(Files.readAllBytes(flowCaseLog), "UTF-8");
        for (String marker : new String[]{"prepare:BASE", "invoke:STAGE|seed:BASE", "rollback:BASE", "cleanup:BASE"})
            assertTrue(legacyLog.contains(marker), "Missing legacy marker " + marker + " in " + legacyLog);
        for (String marker : new String[]{"prepare:BASE", "invoke:BASE", "rollback:BASE", "cleanup:BASE"})
            assertTrue(flowLog.contains(marker), "Missing Flow marker " + marker + " in " + flowLog);

        assertEquals(5, legacyEvents.stream().filter(event -> "STAGE_STARTED".equals(event.data().get("event"))).count());
        assertEquals(6, legacyEvents.stream().filter(event -> "ACTION_FINISHED".equals(event.data().get("event"))).count());
        assertTrue(legacyEvents.stream().anyMatch(event -> "STAGE_FINISHED".equals(event.data().get("event"))
                && "rollback".equals(event.stage()) && "PASS".equals(event.status())));
        assertTrue(legacyEvents.stream().anyMatch(event -> "STAGE_FINISHED".equals(event.data().get("event"))
                && "cleanup".equals(event.stage()) && "PASS".equals(event.status())));
        assertTrue(flowEvents.stream().anyMatch(event -> "ACTION_FINISHED".equals(event.data().get("event"))
                && "compat.stage-flow.v1".equals(event.data().get("flowId")) && "FAIL".equals(event.status())));
        assertEquals(1, flowEvents.stream().filter(event -> "ACTION_FINISHED".equals(event.data().get("event"))).count());
        assertFalse(flowEvents.stream().anyMatch(event -> "STAGE_STARTED".equals(event.data().get("event"))));
    }

    @Test void nativeRunLedgerCapturesExcelWorkbookShaAndDetectsWorkbookOnlyChange() throws Exception {
        Path workbook = projectRoot.resolve("testdata/native-ledger.xlsx");
        Files.createDirectories(workbook.getParent());
        try (Workbook book = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(workbook)) {
            Sheet sheet = book.createSheet("Cases");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Case ID");
            header.createCell(1).setCellValue("Amount");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("alpha");
            row.createCell(1).setCellValue("100");
            book.write(output);
        }
        writeText(projectRoot.resolve("testcase/fpp/transfer/testcase.yaml"),
                "schemaVersion: att-testcase/v1\nid: fpp.transfer.ledger\nflow: payment.transfer.v1\n"
                        + "dataSource:\n  type: excel\n  path: testdata/native-ledger.xlsx\n  sheet: Cases\n"
                        + "  headerRows: 1\n  caseId: Case ID\n  dataColumns: amount=Amount\n"
                        + "inputs:\n  amount: {from: DATA.amount}\n");
        writeText(projectRoot.resolve("templates/flows/payment/transfer/v1/flow.yaml"),
                "schemaVersion: att-flow/v3.6\nid: payment.transfer.v1\nname: Transfer\n"
                        + "description: Native Excel ledger fixture\nactions:\n"
                        + "  show: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n");
        writeRuntimeSchemas();

        att.testcase.TestCaseDefinitionResolver resolver = new att.testcase.TestCaseDefinitionResolver(
                att.validation.SchemaFiles.resolve(projectRoot, "att-testcase-v1.schema.json"));
        att.testcase.TestCaseResolutionResult resolution = resolver.resolve(
                projectRoot.resolve("testcase/fpp/transfer/testcase.yaml"), "payments", projectRoot,
                Paths.get("templates"), att.testcase.TestCaseDefinitionResolver.Options.defaults());
        String expectedWorkbookSha = String.valueOf(resolution.selectedCases().get(0)
                .sourceProvenance().metadata().get("workbookSha256"));
        assertEquals(64, expectedWorkbookSha.length());

        RunSummary summary = new FrameworkEngine(projectRoot, globalConfig()).runNativeCases(resolution,
                "NATIVE-EXCEL-LEDGER", null, false, false, null);
        assertEquals(1, summary.passed());
        @SuppressWarnings("unchecked")
        Map<String,Object> manifest = (Map<String,Object>) new org.yaml.snakeyaml.Yaml().load(
                new String(Files.readAllBytes(projectRoot.resolve("output/NATIVE-EXCEL-LEDGER/run.yaml")), "UTF-8"));
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> inputs = (List<Map<String,Object>>) manifest.get("inputs");
        assertTrue(inputs.stream().anyMatch(item -> "test-case-definition".equals(item.get("kind"))), inputs.toString());
        assertTrue(inputs.stream().anyMatch(item -> "flow".equals(item.get("kind"))), inputs.toString());
        Map<String,Object> workbookInput = inputs.stream()
                .filter(item -> "excel-data-source".equals(item.get("kind"))
                        && "testdata/native-ledger.xlsx".equals(item.get("path")))
                .findFirst().orElseThrow(() -> new AssertionError("Missing excel-data-source input: " + inputs));
        assertEquals(expectedWorkbookSha, workbookInput.get("sha256"));

        try (java.io.InputStream input = Files.newInputStream(workbook); XSSFWorkbook book = new XSSFWorkbook(input)) {
            book.getSheet("Cases").getRow(1).getCell(1).setCellValue("200");
            try (OutputStream output = Files.newOutputStream(workbook)) { book.write(output); }
        }
        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> new FrameworkEngine(projectRoot, globalConfig()).runNativeCases(resolution,
                        "NATIVE-EXCEL-LEDGER-STALE", null, false, false, null));
        assertTrue(stale.getMessage().contains("Native Excel Data Source changed after resolution"),
                stale.getMessage());
        assertTrue(stale.getMessage().contains("testdata/native-ledger.xlsx"), stale.getMessage());
        assertFalse(Files.exists(projectRoot.resolve("output/NATIVE-EXCEL-LEDGER-STALE")));
    }

    @Test void nativeExcelSourceWorkbookReportMapsPinnedRowsPreservesFormulasAndLeavesSourceUntouched() throws Exception {
        Path workbook = writeNativeReportWorkbook("testdata/native-report.xlsx");
        byte[] original = Files.readAllBytes(workbook);
        Path definitionPath = writeNativeReportDefinition("testcase/fpp/report/testcase.yaml",
                "native.report", "testdata/native-report.xlsx", true, false);
        writeNativeReportFlow();
        writeRuntimeSchemas();

        att.testcase.TestCaseDefinitionResolver resolver = new att.testcase.TestCaseDefinitionResolver(
                att.validation.SchemaFiles.resolve(projectRoot, "att-testcase-v1.schema.json"));
        att.testcase.TestCaseResolutionResult resolution = resolver.resolve(definitionPath, "payments", projectRoot,
                Paths.get("templates"), new att.testcase.TestCaseDefinitionResolver.Options(10, 10,
                        Collections.singleton("native.report.selected"), null));
        RunSummary summary = new FrameworkEngine(projectRoot, globalConfig()).runNativeCases(resolution,
                "NATIVE-SOURCE-REPORT", null, false, false, null);

        assertEquals(1, summary.passed());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(workbook),
                "The accepted source workbook must not be changed by report generation");
        Path report = projectRoot.resolve("output/NATIVE-SOURCE-REPORT/workbooks/native-cases.source-workbook.xlsx");
        assertTrue(Files.isRegularFile(report));
        try (java.io.InputStream input = Files.newInputStream(report); XSSFWorkbook result = new XSSFWorkbook(input)) {
            Sheet sheet = result.getSheet("Cases");
            assertEquals("A4&\"-calc\"", sheet.getRow(3).getCell(2).getCellFormula(),
                    "The source formula inventory must survive the result write");
            assertEquals("Test Result", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("PASS", sheet.getRow(3).getCell(3).getStringCellValue(),
                    "The result belongs on the physical row pinned during Excel resolution");
            assertNull(sheet.getRow(1).getCell(3), "Unselected rows must not receive the selected Case result");
        }
        @SuppressWarnings("unchecked")
        Map<String,Object> manifest = (Map<String,Object>) new org.yaml.snakeyaml.Yaml().load(
                new String(Files.readAllBytes(projectRoot.resolve("output/NATIVE-SOURCE-REPORT/run.yaml")), "UTF-8"));
        @SuppressWarnings("unchecked")
        Map<String,Object> run = (Map<String,Object>) manifest.get("run");
        @SuppressWarnings("unchecked")
        Map<String,Object> sourceReport = (Map<String,Object>) run.get("sourceWorkbookReport");
        @SuppressWarnings("unchecked")
        Map<String,Object> outputs = (Map<String,Object>) manifest.get("outputs");
        assertEquals("written", sourceReport.get("status"));
        assertEquals("workbooks/native-cases.source-workbook.xlsx", outputs.get("sourceWorkbookXlsx"));
        byte[] reportBeforeRegeneration = Files.readAllBytes(report);
        new att.report.ReportRegenerator().regenerate(projectRoot.resolve("output"), "NATIVE-SOURCE-REPORT");
        org.junit.jupiter.api.Assertions.assertArrayEquals(reportBeforeRegeneration, Files.readAllBytes(report),
                "Report regeneration must preserve the already generated source-workbook artifact");
    }

    @Test void nativeExcelSourceWorkbookReportRedactsSensitiveColumnsAndRegisteredValues() throws Exception {
        Path workbook = projectRoot.resolve("testdata/native-report-secrets.xlsx");
        Files.createDirectories(workbook.getParent());
        try (Workbook source = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(workbook)) {
            Sheet sheet = source.createSheet("Cases");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Case ID");
            header.createCell(1).setCellValue("Token Value");
            header.createCell(2).setCellValue("Notes");
            header.createCell(3).setCellValue("Password");
            Row selected = sheet.createRow(1);
            selected.createCell(0).setCellValue("secret-case");
            selected.createCell(1).setCellValue("registered-secret-123");
            selected.createCell(2).setCellValue("reuse registered-secret-123 here");
            selected.createCell(3).setCellValue("password-column-value");
            org.apache.poi.ss.usermodel.Drawing<?> casesDrawing = sheet.createDrawingPatriarch();
            org.apache.poi.ss.usermodel.ClientAnchor casesAnchor = source.getCreationHelper().createClientAnchor();
            casesAnchor.setCol1(3);
            casesAnchor.setCol2(4);
            casesAnchor.setRow1(1);
            casesAnchor.setRow2(2);
            org.apache.poi.ss.usermodel.Comment passwordComment = casesDrawing.createCellComment(casesAnchor);
            passwordComment.setString(source.getCreationHelper().createRichTextString("password-comment-canary"));
            selected.getCell(3).setCellComment(passwordComment);

            Sheet privateData = source.createSheet("Private Data");
            privateData.createRow(0).createCell(0).setCellValue("Exported account credentials");
            Row privateHeader = privateData.createRow(1);
            privateHeader.createCell(0).setCellValue("Account");
            privateHeader.createCell(1).setCellValue("Password");
            privateHeader.createCell(2).setCellValue("Token Value");
            Row privateRow = privateData.createRow(2);
            privateRow.createCell(0).setCellValue("user-1");
            privateRow.createCell(1).setCellValue("other-sheet-password");
            privateRow.createCell(2).setCellValue("other-sheet-token");
            privateRow.createCell(3).setCellValue("password");
            org.apache.poi.ss.usermodel.Drawing<?> privateDrawing = privateData.createDrawingPatriarch();
            org.apache.poi.ss.usermodel.ClientAnchor privateAnchor = source.getCreationHelper().createClientAnchor();
            privateAnchor.setCol1(1);
            privateAnchor.setCol2(2);
            privateAnchor.setRow1(2);
            privateAnchor.setRow2(3);
            org.apache.poi.ss.usermodel.Comment privateComment = privateDrawing.createCellComment(privateAnchor);
            privateComment.setString(source.getCreationHelper().createRichTextString("api-key-comment-canary"));
            privateRow.getCell(1).setCellComment(privateComment);
            source.write(output);
        }
        Path definitionPath = projectRoot.resolve("testcase/fpp/report-secrets/testcase.yaml");
        writeText(definitionPath,
                "schemaVersion: att-testcase/v1\nid: native.report.secrets\nflow: payment.transfer.v1\n"
                        + "dataSource:\n  type: excel\n  path: testdata/native-report-secrets.xlsx\n"
                        + "  sheet: Cases\n  caseId: Case ID\n  dataColumns: tokenValue=Token Value,notes=Notes\n"
                        + "inputs:\n  tokenValue: {from: DATA.tokenValue}\n  notes: {from: DATA.notes}\n"
                        + "report: {format: xlsx, template: sourceWorkbook}\n");
        writeText(projectRoot.resolve("templates/flows/payment/transfer/v1/flow.yaml"),
                "schemaVersion: att-flow/v3.6\nid: payment.transfer.v1\nname: Transfer\n"
                        + "description: Secret report fixture\nactions:\n"
                        + "  log: {type: log, message: 'token=${EXEC.INPUT.tokenValue}|notes=${EXEC.INPUT.notes}'}\n");
        writeRuntimeSchemas();
        att.testcase.TestCaseDefinitionResolver resolver = new att.testcase.TestCaseDefinitionResolver(
                att.validation.SchemaFiles.resolve(projectRoot, "att-testcase-v1.schema.json"));
        att.testcase.TestCaseResolutionResult resolution = resolver.resolve(definitionPath, "payments", projectRoot,
                Paths.get("templates"), att.testcase.TestCaseDefinitionResolver.Options.defaults());
        RunSummary summary = new FrameworkEngine(projectRoot, globalConfig()).runNativeCases(resolution,
                "NATIVE-SOURCE-REPORT-SECRETS", null, false, false, null);

        assertEquals(1, summary.passed());
        Path report = projectRoot.resolve("output/NATIVE-SOURCE-REPORT-SECRETS/workbooks/native-cases.source-workbook.xlsx");
        try (java.io.InputStream input = Files.newInputStream(report); XSSFWorkbook result = new XSSFWorkbook(input)) {
            Row row = result.getSheet("Cases").getRow(1);
            assertEquals(att.security.SecretProtection.REDACTED, row.getCell(1).getStringCellValue());
            assertEquals("reuse " + att.security.SecretProtection.REDACTED + " here", row.getCell(2).getStringCellValue());
            assertEquals(att.security.SecretProtection.REDACTED, row.getCell(3).getStringCellValue());
            Row privateData = result.getSheet("Private Data").getRow(2);
            assertEquals(att.security.SecretProtection.REDACTED, privateData.getCell(1).getStringCellValue(),
                    "Sensitive values on a non-configured worksheet must be redacted");
            assertEquals(att.security.SecretProtection.REDACTED, privateData.getCell(2).getStringCellValue(),
                    "Explicitly sensitive Data Column aliases must be redacted on every worksheet");
            assertEquals(att.security.SecretProtection.REDACTED, privateData.getCell(3).getStringCellValue(),
                    "A value that resembles a sensitive header must not be exempted as an unverified header");
            assertTrue(result.getSheet("Cases").getCellComments().isEmpty(),
                    "Comments on the configured source sheet must not carry secrets into the copied workbook");
            assertTrue(result.getSheet("Private Data").getCellComments().isEmpty(),
                    "Comments on other source sheets must also be removed");
        }
        String log = new String(Files.readAllBytes(summary.results().get(0).caseLogPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(log.contains("registered-secret-123"), log);
        assertFalse(log.contains("password-column-value"), log);
    }

    @Test void nativeExcelSourceWorkbookReportFallsBackWhenFormulaCacheCouldRetainRedactedSecret() throws Exception {
        Path workbook = projectRoot.resolve("testdata/native-report-formula-secret.xlsx");
        Files.createDirectories(workbook.getParent());
        try (Workbook source = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(workbook)) {
            Sheet sheet = source.createSheet("Cases");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Case ID");
            header.createCell(1).setCellValue("Amount");
            header.createCell(2).setCellValue("Password");
            header.createCell(3).setCellValue("Derived");
            Row selected = sheet.createRow(1);
            selected.createCell(0).setCellValue("secret-case");
            selected.createCell(1).setCellValue("30");
            selected.createCell(2).setCellValue("cached-password-secret");
            selected.createCell(3).setCellFormula("C2");
            source.getCreationHelper().createFormulaEvaluator().evaluateAll();
            source.write(output);
        }
        Path definitionPath = writeNativeReportDefinition("testcase/fpp/formula-secret/testcase.yaml",
                "native.report.formula.secret", "testdata/native-report-formula-secret.xlsx", true, false);
        writeNativeReportFlow();
        writeRuntimeSchemas();

        att.testcase.TestCaseDefinitionResolver resolver = new att.testcase.TestCaseDefinitionResolver(
                att.validation.SchemaFiles.resolve(projectRoot, "att-testcase-v1.schema.json"));
        att.testcase.TestCaseResolutionResult resolution = resolver.resolve(definitionPath, "payments", projectRoot,
                Paths.get("templates"), att.testcase.TestCaseDefinitionResolver.Options.defaults());
        RunSummary summary = new FrameworkEngine(projectRoot, globalConfig()).runNativeCases(resolution,
                "NATIVE-SOURCE-REPORT-FORMULA-SECRET", null, false, false, null);

        Path runDirectory = projectRoot.resolve("output/NATIVE-SOURCE-REPORT-FORMULA-SECRET");
        assertEquals(1, summary.passed());
        assertTrue(Files.isRegularFile(runDirectory.resolve("workbooks/native-cases.result.xlsx")),
                "The standard Native report remains available when the optional layout cannot be safely redacted");
        assertFalse(Files.exists(runDirectory.resolve("workbooks/native-cases.source-workbook.xlsx")),
                "The optional workbook must not publish a formula cache that can retain a redacted secret");
        @SuppressWarnings("unchecked")
        Map<String,Object> manifest = (Map<String,Object>) new org.yaml.snakeyaml.Yaml().load(
                new String(Files.readAllBytes(runDirectory.resolve("run.yaml")), "UTF-8"));
        @SuppressWarnings("unchecked")
        Map<String,Object> run = (Map<String,Object>) manifest.get("run");
        @SuppressWarnings("unchecked")
        Map<String,Object> sourceReport = (Map<String,Object>) run.get("sourceWorkbookReport");
        assertEquals("fallback", sourceReport.get("status"));
    }

    @Test void nativeExcelSourceWorkbookReportFallsBackForThreadedCommentParts() throws Exception {
        String canary = "threaded-comment-secret-canary";
        Path workbook = writeNativeReportWorkbook("testdata/native-report-threaded-comments.xlsx");
        byte[] withThreadedComment = addThreadedCommentParts(Files.readAllBytes(workbook), canary);
        Files.write(workbook, withThreadedComment);

        Path definitionPath = writeNativeReportDefinition("testcase/fpp/threaded-comments/testcase.yaml",
                "native.report.threaded.comments", "testdata/native-report-threaded-comments.xlsx", true, false);
        writeNativeReportFlow();
        writeRuntimeSchemas();
        att.testcase.TestCaseDefinitionResolver resolver = new att.testcase.TestCaseDefinitionResolver(
                att.validation.SchemaFiles.resolve(projectRoot, "att-testcase-v1.schema.json"));
        att.testcase.TestCaseResolutionResult resolution = resolver.resolve(definitionPath, "payments", projectRoot,
                Paths.get("templates"), att.testcase.TestCaseDefinitionResolver.Options.defaults());
        RunSummary summary = new FrameworkEngine(projectRoot, globalConfig()).runNativeCases(resolution,
                "NATIVE-SOURCE-REPORT-THREADED-COMMENTS", null, false, false, null);

        Path runDirectory = projectRoot.resolve("output/NATIVE-SOURCE-REPORT-THREADED-COMMENTS");
        assertEquals(3, summary.passed());
        assertTrue(Files.isRegularFile(runDirectory.resolve("workbooks/native-cases.result.xlsx")),
                "The standard Native report remains available when threaded comments cannot be safely stripped");
        assertFalse(Files.exists(runDirectory.resolve("workbooks/native-cases.source-workbook.xlsx")),
                "The optional copied workbook must be withheld when unsupported threaded-comment parts are present");
        assertTrue(zipContainsText(Files.readAllBytes(workbook), canary),
                "The source fixture must contain a threaded-comment canary in an OOXML part");
        assertFalse(zipContainsText(Files.readAllBytes(runDirectory.resolve("workbooks/native-cases.result.xlsx")), canary),
                "The standard generated report must not carry the source threaded-comment canary");

        @SuppressWarnings("unchecked")
        Map<String,Object> manifest = (Map<String,Object>) new org.yaml.snakeyaml.Yaml().load(
                new String(Files.readAllBytes(runDirectory.resolve("run.yaml")), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String,Object> run = (Map<String,Object>) manifest.get("run");
        @SuppressWarnings("unchecked")
        Map<String,Object> sourceReport = (Map<String,Object>) run.get("sourceWorkbookReport");
        assertEquals("fallback", sourceReport.get("status"));
        assertEquals("SOURCE_WORKBOOK_UNAVAILABLE", sourceReport.get("diagnosticCode"));
    }

    private static byte[] addThreadedCommentParts(byte[] source, String canary) throws IOException {
        Map<String,byte[]> entries = new LinkedHashMap<String,byte[]>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                int count;
                while ((count = input.read(buffer)) != -1) contents.write(buffer, 0, count);
                entries.put(entry.getName(), contents.toByteArray());
            }
        }

        String contentTypes = new String(entries.get("[Content_Types].xml"), StandardCharsets.UTF_8);
        contentTypes = appendBeforeEnd(contentTypes, "</Types>",
                "<Override PartName=\"/xl/threadedComments/threadedComment1.xml\" "
                        + "ContentType=\"application/vnd.ms-excel.threadedcomments+xml\"/>"
                        + "<Override PartName=\"/xl/persons/person.xml\" "
                        + "ContentType=\"application/vnd.ms-excel.person+xml\"/>");
        entries.put("[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));

        String workbookRelationships = new String(entries.get("xl/_rels/workbook.xml.rels"), StandardCharsets.UTF_8);
        workbookRelationships = appendBeforeEnd(workbookRelationships, "</Relationships>",
                "<Relationship Id=\"rIdThreadedPerson\" "
                        + "Type=\"http://schemas.microsoft.com/office/2017/10/relationships/person\" "
                        + "Target=\"persons/person.xml\"/>");
        entries.put("xl/_rels/workbook.xml.rels", workbookRelationships.getBytes(StandardCharsets.UTF_8));

        entries.put("xl/worksheets/_rels/sheet1.xml.rels", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdThreadedComment\" "
                + "Type=\"http://schemas.microsoft.com/office/2017/10/relationships/threadedComment\" "
                + "Target=\"../threadedComments/threadedComment1.xml\"/></Relationships>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("xl/threadedComments/threadedComment1.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ThreadedComments xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments\">"
                + "<threadedComment ref=\"A2\" dT=\"2025-01-01T00:00:00Z\" "
                + "personId=\"{00000000-0000-0000-0000-000000000001}\" "
                + "id=\"{00000000-0000-0000-0000-000000000002}\"><text>" + canary
                + "</text></threadedComment></ThreadedComments>").getBytes(StandardCharsets.UTF_8));
        entries.put("xl/persons/person.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<personList xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments\">"
                + "<person displayName=\"Fixture User\" id=\"{00000000-0000-0000-0000-000000000001}\" "
                + "userId=\"fixture\" providerId=\"None\"/></personList>")
                .getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String,byte[]> entry : entries.entrySet()) writeZipEntry(zip, entry.getKey(), entry.getValue());
        }
        return output.toByteArray();
    }

    private static String appendBeforeEnd(String xml, String closingTag, String fragment) {
        int closing = xml.lastIndexOf(closingTag);
        if (closing < 0) throw new IllegalArgumentException("Expected OOXML closing tag " + closingTag);
        return xml.substring(0, closing) + fragment + xml.substring(closing);
    }

    private static void writeZipEntry(ZipOutputStream zip, String name, byte[] contents) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents);
        zip.closeEntry();
    }

    private static boolean zipContainsText(byte[] zippedBytes, String expected) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zippedBytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                int count;
                while ((count = zip.read(buffer)) != -1) contents.write(buffer, 0, count);
                if (new String(contents.toByteArray(), StandardCharsets.UTF_8).contains(expected)) return true;
            }
        }
        return false;
    }

    @Test void nativeExcelSourceWorkbookReportRejectsStaleSnapshotBeforeStartingRun() throws Exception {
        Path workbook = writeNativeReportWorkbook("testdata/native-report-stale.xlsx");
        Path definitionPath = writeNativeReportDefinition("testcase/fpp/stale/testcase.yaml",
                "native.report.stale", "testdata/native-report-stale.xlsx", true, false);
        writeNativeReportFlow();
        writeRuntimeSchemas();
        att.testcase.TestCaseDefinitionResolver resolver = new att.testcase.TestCaseDefinitionResolver(
                att.validation.SchemaFiles.resolve(projectRoot, "att-testcase-v1.schema.json"));
        att.testcase.TestCaseResolutionResult resolution = resolver.resolve(definitionPath, "payments", projectRoot,
                Paths.get("templates"), att.testcase.TestCaseDefinitionResolver.Options.defaults());
        try (java.io.InputStream input = Files.newInputStream(workbook); XSSFWorkbook source = new XSSFWorkbook(input)) {
            source.getSheet("Cases").getRow(3).getCell(1).setCellValue("changed-after-resolution");
            try (OutputStream output = Files.newOutputStream(workbook)) { source.write(output); }
        }

        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> new FrameworkEngine(projectRoot, globalConfig()).runNativeCases(resolution,
                        "NATIVE-SOURCE-REPORT-STALE", null, false, false, null));
        assertTrue(stale.getMessage().contains("changed after resolution"), stale.getMessage());
        assertFalse(Files.exists(projectRoot.resolve("output/NATIVE-SOURCE-REPORT-STALE")),
                "A stale accepted workbook must be rejected before Engine creates or executes a run");
    }

    @Test void nativeExcelSourceWorkbookReportFallsBackForMultiDefinitionSuite() throws Exception {
        Path workbook = writeNativeReportWorkbook("testdata/native-report-suite.xlsx");
        writeNativeReportDefinition("testcase/fpp/source/testcase.yaml", "native.report.source",
                "testdata/native-report-suite.xlsx", true, false);
        writeNativeReportDefinition("testcase/fpp/inline/testcase.yaml", "native.report.inline", "", false, true);
        writeNativeReportFlow();
        writeRuntimeSchemas();

        att.testcase.TestCaseSuiteResolver resolver = new att.testcase.TestCaseSuiteResolver(
                att.validation.SchemaFiles.resolve(projectRoot, "att-testcase-v1.schema.json"));
        att.testcase.TestCaseSuiteResolutionResult resolution = resolver.resolve(projectRoot, "payments",
                projectRoot.resolve("templates"), Paths.get("testcase"),
                java.util.Arrays.asList("native.report.source", "native.report.inline"), null,
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, null);
        RunSummary summary = new FrameworkEngine(projectRoot, globalConfig()).runNativeCaseSuite(resolution,
                "NATIVE-SOURCE-REPORT-MULTI", null, false, false, null);

        assertEquals(4, summary.passed());
        Path runDirectory = projectRoot.resolve("output/NATIVE-SOURCE-REPORT-MULTI");
        assertTrue(Files.isRegularFile(runDirectory.resolve("workbooks/native-cases.result.xlsx")));
        assertFalse(Files.exists(runDirectory.resolve("workbooks/native-cases.source-workbook.xlsx")));
        assertTrue(Files.isRegularFile(workbook));
        @SuppressWarnings("unchecked")
        Map<String,Object> manifest = (Map<String,Object>) new org.yaml.snakeyaml.Yaml().load(
                new String(Files.readAllBytes(runDirectory.resolve("run.yaml")), "UTF-8"));
        @SuppressWarnings("unchecked")
        Map<String,Object> run = (Map<String,Object>) manifest.get("run");
        @SuppressWarnings("unchecked")
        Map<String,Object> sourceReport = (Map<String,Object>) run.get("sourceWorkbookReport");
        assertEquals("fallback", sourceReport.get("status"));
        assertEquals("MULTIPLE_DEFINITIONS", sourceReport.get("diagnosticCode"));
    }

    private Path writeNativeReportWorkbook(String relativePath) throws Exception {
        Path workbook = projectRoot.resolve(relativePath);
        Files.createDirectories(workbook.getParent());
        try (Workbook book = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(workbook)) {
            Sheet sheet = book.createSheet("Cases");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Case ID");
            header.createCell(1).setCellValue("Amount");
            header.createCell(2).setCellValue("Calculated");
            Row unselected = sheet.createRow(1);
            unselected.createCell(0).setCellValue("unselected");
            unselected.createCell(1).setCellValue("10");
            unselected.createCell(2).setCellFormula("A2&\"-calc\"");
            Row secondUnselected = sheet.createRow(2);
            secondUnselected.createCell(0).setCellValue("another");
            secondUnselected.createCell(1).setCellValue("20");
            Row selected = sheet.createRow(3);
            selected.createCell(0).setCellValue("selected");
            selected.createCell(1).setCellValue("30");
            selected.createCell(2).setCellFormula("A4&\"-calc\"");
            book.write(output);
        }
        return workbook;
    }

    private Path writeNativeReportDefinition(String relativePath, String definitionId, String workbookPath,
            boolean sourceReport, boolean inline) throws Exception {
        Path path = projectRoot.resolve(relativePath);
        StringBuilder yaml = new StringBuilder("schemaVersion: att-testcase/v1\nid: ").append(definitionId)
                .append("\nflow: payment.transfer.v1\n");
        if (inline) {
            yaml.append("inputs: {amount: 30}\n");
        } else {
            yaml.append("dataSource:\n  type: excel\n  path: ").append(workbookPath)
                    .append("\n  sheet: Cases\n  headerRows: 1\n  caseId: Case ID\n  dataColumns: amount=Amount\n")
                    .append("inputs:\n  amount: {from: DATA.amount}\n");
        }
        if (sourceReport) yaml.append("report: {format: xlsx, template: sourceWorkbook}\n");
        writeText(path, yaml.toString());
        return path;
    }

    private void writeNativeReportFlow() throws Exception {
        writeText(projectRoot.resolve("templates/flows/payment/transfer/v1/flow.yaml"),
                "schemaVersion: att-flow/v3.6\nid: payment.transfer.v1\nname: Transfer\n"
                        + "description: Source workbook report fixture\nactions:\n"
                        + "  log: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n");
    }

    @Test void preservesExecutionErrorInsteadOfDowngradingItToFail() throws Exception {
        FrameworkEngine engine = new FrameworkEngine(projectRoot, globalConfig());
        java.lang.reflect.Method method = FrameworkEngine.class.getDeclaredMethod("aggregate", java.util.List.class);
        method.setAccessible(true);
        java.util.List<ValidationResult> mixed = java.util.Arrays.asList(
                new ValidationResult("s","assert",ResultStatus.FAIL,"","",""),
                new ValidationResult("s","tool",ResultStatus.ERROR,"","","boom"));
        assertEquals(ResultStatus.ERROR, method.invoke(engine, mixed));
    }

    @Test void inputManifestIncludesProjectFilesUsedByCallBackedTools() throws Exception {
        Path sql = projectRoot.resolve("sql/reference.sql");
        writeText(sql, "select 1");
        Path workbook = projectRoot.resolve("testcase/dummy.xlsx");
        writeText(workbook, "not parsed by input manifest collection");
        ToolConfig lookup = new ToolConfig("reference.lookup", "lookup", "reference", "Lookup", "Lookup",
                Collections.<String>emptyList(), "#{db.reference.query(sql=&{sql/reference.sql}, params=[])}", "db",
                Collections.<String>emptyList(), "", Collections.<String,ToolArgumentConfig>emptyMap(), null, null);
        DbHelperConfig helper = new DbHelperConfig("reference", "Reference", "Reference DB", "jdbc:never-connect",
                "", "", "", Collections.<String,String>emptyMap(), false, "driverDefault", 5,
                "case", "rollback", 10, 1024, 4096, "hash", "masked", null);
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"),
                "SIT", 1000, Paths.get("templates"), Paths.get("testcase"),
                Collections.singletonMap(lookup.key(), lookup), Collections.singletonMap(helper.id(), helper),
                null, null, null, "", "", null, null, 1, "ignore", "", false,
                att.config.ProcessOutputConfig.defaults());
        FrameworkEngine engine = new FrameworkEngine(projectRoot, config);
        java.lang.reflect.Method method = FrameworkEngine.class.getDeclaredMethod("inputHashes", ExecutionOptions.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String,Object>> inputs = (java.util.List<Map<String,Object>>) method.invoke(engine,
                att.core.ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite", workbook.toString(), "--run-id", "MANIFEST"}));
        assertTrue(inputs.stream().anyMatch(item -> "tool-sql".equals(item.get("kind"))
                && "sql/reference.sql".equals(item.get("path"))), inputs.toString());
    }

    @Test void failedPlanCreatesNoOutputDirectory() throws Exception {
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"), "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
        writeSnapshot(projectRoot.resolve("testcase/payment.xlsx"));
        Files.createDirectories(projectRoot.resolve("templates"));
        ExecutionOptions options = att.core.ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "PLAN-FAIL"});
        assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).run(options));
        assertFalse(Files.exists(projectRoot.resolve("output")));
    }

    @Test void configuredRunIdentityIsFrozenAndExactRunIdWins() {
        FrameworkConfig config = globalConfig("append-to-copy",
                new RunConfig("timestamp", "yyyyMMdd-HHmmss", "run-${META.SOURCE.type}", ""));
        FrameworkEngine engine = new FrameworkEngine(projectRoot, config);
        ExecutionOptions configured = ExecutionOptionsTestSupport.parse(new String[]{"run", "--all"});
        assertEquals("run-testcase", engine.effectiveRunId(configured));
        ExecutionOptions exact = ExecutionOptionsTestSupport.parse(new String[]{"run", "--all", "--run-id", "literal-override"});
        assertEquals("literal-override", engine.effectiveRunId(exact));
        assertFalse(Files.exists(projectRoot.resolve("output")));
    }

    @Test void staleSnapshotStopsRunBeforeOutputMutation() throws Exception {
        Path workbook = projectRoot.resolve("testcase/payment.xlsx");
        writeWorkbook(workbook);
        writeText(projectRoot.resolve("testcase/payment.yaml"), "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
        Files.createDirectories(projectRoot.resolve("templates"));
        writeSnapshot(workbook);
        Path snapshot = projectRoot.resolve("testcase/payment.xml");
        String xml = new String(Files.readAllBytes(snapshot), "UTF-8");
        writeText(snapshot, xml.replace("PAYMENT_INVOKE", "CHANGED_TEMPLATE"));
        ExecutionOptions options = att.core.ExecutionOptionsTestSupport.parse(new String[]{"run", "--suite", workbook.toString(), "--run-id", "STALE"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).run(options));
        assertTrue(error.getMessage().contains("snapshot is stale"), error.getMessage());
        assertTrue(error.getMessage().contains("payment.TC001.stages.invoke.name changed"), error.getMessage());
        assertFalse(Files.exists(projectRoot.resolve("output")));
    }

    @Test void rejectsDuplicateWorkbookIdsAcrossExcelFilesDuringPlanning() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v3.4\nname: PAYMENT_INVOKE\ndescription: test\nactions:\n  check:\n    type: assert\n    assert: \"true == true\"\n");
        for (String name : java.util.Arrays.asList("one", "two")) {
            writeWorkbook(projectRoot.resolve("testcase/" + name + ".xlsx"));
            writeText(projectRoot.resolve("testcase/" + name + ".yaml"), "schemaVersion: att-sidecar/v2.1\nid: duplicate\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
            writeSnapshot(projectRoot.resolve("testcase/" + name + ".xlsx"));
        }
        ExecutionOptions options = att.core.ExecutionOptionsTestSupport.parse(new String[]{"run", "--all", "--run-id", "DUPLICATE-WORKBOOK"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).run(options));
        assertTrue(error.getMessage().contains("Duplicate workbook id 'duplicate'"), error.getMessage());
        assertFalse(Files.exists(projectRoot.resolve("output")));
    }

    private FrameworkConfig globalConfig() {
        return globalConfig("append-to-copy");
    }

    private FrameworkConfig globalConfig(String reportMode) {
        return globalConfig(reportMode, new RunConfig("timestamp", "yyyyMMdd-HHmmss"));
    }

    private FrameworkConfig globalConfig(String reportMode, RunConfig run) {
        Map<String, ToolArgumentConfig> args = new LinkedHashMap<String, ToolArgumentConfig>();
        args.put("caseId", new ToolArgumentConfig("caseId", "Case ID", "Full V2 Case ID", true, ""));
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("invokePaymentApi", new ToolConfig("invokePaymentApi", "Invoke", "Invoke test API",
                "./tools/invoke.sh ${TOOL.input.caseId}", "xml", args));
        Map<String, String> report = new LinkedHashMap<String, String>();
        report.put("result", "Test Result");
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30000,
                Paths.get("templates"), tools, new ReportConfig(reportMode, "${suiteName}.result.xlsx", report), run);
    }

    private FrameworkConfig withTestdata(Path descriptor) {
        FrameworkConfig base = globalConfig();
        return new FrameworkConfig(base.outputDirectory(), base.reportDirectory(), base.logDirectory(), base.environment(),
                base.timeoutMs(), base.templatesRoot(), base.testcasesRoot(), base.tools(), base.dbHelpers(), base.mqHelpers(),
                base.sshHelpers(), base.httpHelpers(), base.report(), base.run(), base.sheetGroups(), base.caseIdColumn(),
                base.tagsColumn(), base.dataColumns(), base.stages(), base.headerRows(), base.xmlNamespaceMode(),
                base.workbookId(), base.caseLogYamlAnchors(), base.processOutput(), Collections.singletonList(descriptor));
    }

    private String testdataDescriptor(String exhaustion) {
        return "schemaVersion: att-testdata/v1.0\nid: accounts\nrecords:\n  - {id: A-100}\n  - {id: A-200}\n"
                + "selection: {strategy: sequential, exhaustion: " + exhaustion + "}\n";
    }

    private void writeTestdataWorkbook(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("支付測試案例集");
            Row header = sheet.createRow(0);
            String[] columns = {"案例編號", "標籤", "帳戶", "階段帳戶", "執行模板"};
            for (int index = 0; index < columns.length; index++) header.createCell(index).setCellValue(columns[index]);
            for (int index = 1; index <= 3; index++) {
                Row row = sheet.createRow(index);
                row.createCell(0).setCellValue("TC00" + index);
                row.createCell(1).setCellValue("testdata");
                row.createCell(2).setCellValue("@{accounts.id}");
                row.createCell(3).setCellValue("@{accounts.id}");
                row.createCell(4).setCellValue("name: PAYMENT_INVOKE");
            }
            workbook.write(output);
        }
    }

    private Map<?, ?> readCaseEvidence(Path path) throws Exception {
        try (java.io.InputStream input = Files.newInputStream(path)) {
            return (Map<?, ?>) new org.yaml.snakeyaml.Yaml().load(input);
        }
    }

    private void writeWorkbook(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("支付測試案例集");
            Row header = sheet.createRow(0);
            String[] columns = {"案例編號", "案例名稱", "標籤", "執行模板"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("TC001");
            row.createCell(1).setCellValue("付款成功");
            row.createCell(2).setCellValue("smoke");
            row.createCell(3).setCellValue("name: PAYMENT_INVOKE");
            workbook.write(output);
        }
    }

    private void writeTwoCaseWorkbook(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("支付測試案例集");
            Row header = sheet.createRow(0);
            String[] columns = {"案例編號", "案例名稱", "標籤", "執行模板"};
            for (int index = 0; index < columns.length; index++) header.createCell(index).setCellValue(columns[index]);
            for (int index = 1; index <= 2; index++) {
                Row row = sheet.createRow(index);
                row.createCell(0).setCellValue("TC00" + index);
                row.createCell(1).setCellValue("Render lifecycle " + index);
                row.createCell(2).setCellValue("render");
                row.createCell(3).setCellValue("name: PAYMENT_INVOKE");
            }
            workbook.write(output);
        }
    }

    private void writeText(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes("UTF-8"));
    }
    private void writeSnapshot(Path workbook) throws Exception {
        FrameworkConfig suite = new SuiteConfigResolver(projectRoot, globalConfig()).resolve(workbook);
        new TestcaseSnapshotService().write(workbook, suite, new ExcelTestSuiteLoader(suite).load(workbook));
    }
    private void writeSnapshot(Path workbook, FrameworkConfig config) throws Exception {
        FrameworkConfig suite = new SuiteConfigResolver(projectRoot, config).resolve(workbook);
        new TestcaseSnapshotService().write(workbook, suite, new ExcelTestSuiteLoader(suite).load(workbook));
    }
    private void writeRuntimeSchemas() throws Exception {
        writeText(projectRoot.resolve("schemas/att-ci-summary-v2.1.schema.json"), "{\"type\":\"object\",\"required\":[\"schemaVersion\",\"inputManifestHash\"]}");
        writeText(projectRoot.resolve("schemas/att-run-v2.1.schema.json"), "{\"type\":\"object\",\"required\":[\"schemaVersion\",\"run\",\"inputs\"]}");
        writeText(projectRoot.resolve("schemas/att-junit-v2.1.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"testsuite\"><xs:complexType mixed=\"true\"><xs:sequence><xs:any minOccurs=\"0\" maxOccurs=\"unbounded\" processContents=\"skip\"/></xs:sequence><xs:anyAttribute processContents=\"skip\"/></xs:complexType></xs:element></xs:schema>");
    }
    private void writeTool(Path path, String body) throws Exception {
        writeText(path, "#!/usr/bin/env sh\nset -eu\n" + body);
        path.toFile().setExecutable(true);
    }
}
