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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameworkEngineTest {
    @TempDir Path projectRoot;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(projectRoot); }

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

        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run", "--suite",
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
        RunSummary failed = new FrameworkEngine(projectRoot, globalConfig()).run(ExecutionOptions.parse(new String[]{
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

        RunSummary summary = new FrameworkEngine(projectRoot, config).run(ExecutionOptions.parse(new String[]{
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
        Path descriptor = projectRoot.resolve("config/testdata/accounts.yaml");
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

        RunSummary recycled = new FrameworkEngine(projectRoot, config).run(ExecutionOptions.parse(new String[]{
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
        RunSummary exhausted = new FrameworkEngine(projectRoot, config).run(ExecutionOptions.parse(new String[]{
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

        ExecutionOptions parsedVerboseOptions = ExecutionOptions.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "TEST-V2", "--verbose", "--profile"});
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

        ExecutionOptions defaultOptions = ExecutionOptions.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "TEST-DEFAULT"});
        java.io.ByteArrayOutputStream defaultConsole = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previous = System.out;
        try {
            System.setOut(new java.io.PrintStream(defaultConsole));
            new FrameworkEngine(projectRoot, globalConfig()).run(defaultOptions);
        } finally {
            System.setOut(previous);
        }
        assertEquals("", defaultConsole.toString("UTF-8"));

        ExecutionOptions noWorkbook = ExecutionOptions.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "TEST-NO-WORKBOOK"});
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
                ExecutionOptions.parse(new String[]{"run", "--suite", workbook.toString(), "--run-id", "MANIFEST"}));
        assertTrue(inputs.stream().anyMatch(item -> "tool-sql".equals(item.get("kind"))
                && "sql/reference.sql".equals(item.get("path"))), inputs.toString());
    }

    @Test void failedPlanCreatesNoOutputDirectory() throws Exception {
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"), "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
        writeSnapshot(projectRoot.resolve("testcase/payment.xlsx"));
        Files.createDirectories(projectRoot.resolve("templates"));
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "PLAN-FAIL"});
        assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).run(options));
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
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run", "--suite", workbook.toString(), "--run-id", "STALE"});
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
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run", "--all", "--run-id", "DUPLICATE-WORKBOOK"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).run(options));
        assertTrue(error.getMessage().contains("Duplicate workbook id 'duplicate'"), error.getMessage());
        assertFalse(Files.exists(projectRoot.resolve("output")));
    }

    private FrameworkConfig globalConfig() {
        return globalConfig("append-to-copy");
    }

    private FrameworkConfig globalConfig(String reportMode) {
        Map<String, ToolArgumentConfig> args = new LinkedHashMap<String, ToolArgumentConfig>();
        args.put("caseId", new ToolArgumentConfig("caseId", "Case ID", "Full V2 Case ID", true, ""));
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("invokePaymentApi", new ToolConfig("invokePaymentApi", "Invoke", "Invoke test API",
                "./tools/invoke.sh ${TOOL.input.caseId}", "xml", args));
        Map<String, String> report = new LinkedHashMap<String, String>();
        report.put("result", "Test Result");
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30000,
                Paths.get("templates"), tools, new ReportConfig(reportMode, "${suiteName}.result.xlsx", report), new RunConfig("timestamp", "yyyyMMdd-HHmmss"));
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
