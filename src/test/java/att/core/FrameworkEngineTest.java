/* Author: Jeffrey + ChatGPT */
package att.core;

import att.config.FrameworkConfig;
import att.config.ReportConfig;
import att.config.RunConfig;
import att.config.StageConfig;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
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

    @Test
    void runsV2GroupedCaseThroughTemplateAndTool() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v2.1\nname: PAYMENT_INVOKE\ndescription: test\nactions:\n  callApi:\n    type: tool\n    call: \"#{invokePaymentApi(caseId=${CASE.caseId})}\"\n  check:\n    type: assert\n    expression: \"${ACTIONS.callApi.output.Status} == 'SUCCESS'\"\n");
        writeTool(projectRoot.resolve("tools/invoke.sh"), "printf '<Response><Status>SUCCESS</Status></Response>\\n'\n");
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"),
                "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\n  dataColumns: caseName=案例名稱\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
        writeText(projectRoot.resolve("schemas/att-ci-summary-v2.1.schema.json"), "{\"type\":\"object\",\"required\":[\"schemaVersion\",\"inputManifestHash\"]}");
        writeText(projectRoot.resolve("schemas/att-run-v2.1.schema.json"), "{\"type\":\"object\",\"required\":[\"schemaVersion\",\"run\",\"inputs\"]}");
        writeText(projectRoot.resolve("schemas/att-junit-v2.1.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"testsuite\"><xs:complexType mixed=\"true\"><xs:sequence><xs:any minOccurs=\"0\" maxOccurs=\"unbounded\" processContents=\"skip\"/></xs:sequence><xs:anyAttribute processContents=\"skip\"/></xs:complexType></xs:element></xs:schema>");

        ExecutionOptions verboseOptions = ExecutionOptions.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "TEST-V2", "--verbose"});
        java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previous = System.out;
        RunSummary summary;
        try {
            System.setOut(new java.io.PrintStream(console));
            summary = new FrameworkEngine(projectRoot, globalConfig()).run(verboseOptions);
        } finally {
            System.setOut(previous);
        }
        String verbose = console.toString("UTF-8");
        assertTrue(verbose.contains("[RUN] id=TEST-V2"));
        assertTrue(verbose.contains("[SUITE]"));
        assertTrue(verbose.contains("[CASE] id=payments.payment.TC001 status=START"));
        assertTrue(verbose.contains("[STAGE] case=payments.payment.TC001 stage=invoke"));
        assertTrue(verbose.contains("[ACTION] case=payments.payment.TC001 stage=invoke action=callApi status=PASS"));
        assertFalse(verbose.contains("<Response>"));

        assertEquals(1, summary.passed());
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/workbooks/payment.result.xlsx")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/payments.payment.TC001/payments.payment.TC001.TEST.V2.001.log")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/payments.payment.TC001/case.yaml")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/events.jsonl")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/ci/summary.json")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/ci/junit.xml")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/report/junit.html")));
        assertFalse(Files.exists(projectRoot.resolve("output/TEST-V2/ci/junit.html")));
        String manifest = new String(Files.readAllBytes(projectRoot.resolve("output/TEST-V2/run.yaml")), "UTF-8");
        assertTrue(manifest.contains("schemaVersion: att-run/v2.1"));
        assertTrue(manifest.contains("javaVersion:")); assertTrue(manifest.contains("timezone:")); assertTrue(manifest.contains("sha256:"));
        assertFalse(manifest.contains(".in-progress"));
        try (java.util.stream.Stream<Path> pending = Files.list(projectRoot.resolve("output/.in-progress"))) { assertEquals(0, pending.count()); }
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

    @Test void failedPlanCreatesNoOutputOrInProgressDirectory() throws Exception {
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"), "schemaVersion: att-sidecar/v2.1\nid: payments\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
        Files.createDirectories(projectRoot.resolve("templates"));
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run", "--suite", projectRoot.resolve("testcase/payment.xlsx").toString(), "--run-id", "PLAN-FAIL"});
        assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).run(options));
        assertFalse(Files.exists(projectRoot.resolve("output")));
    }

    @Test void allocatesSequentialRunIdWhenCompletionNameHasCollided() throws Exception {
        Files.createDirectories(projectRoot.resolve("output/RUN-1"));
        Files.createDirectories(projectRoot.resolve("output/RUN-1-2"));
        FrameworkEngine engine = new FrameworkEngine(projectRoot, globalConfig());
        java.lang.reflect.Method method = FrameworkEngine.class.getDeclaredMethod("uniqueCompletionRunId", Path.class, String.class);
        method.setAccessible(true);
        assertEquals("RUN-1-3", method.invoke(engine, projectRoot.resolve("output"), "RUN-1"));
    }

    @Test void rejectsDuplicateWorkbookIdsAcrossExcelFilesDuringPlanning() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "schemaVersion: att-template/v2.1\nname: PAYMENT_INVOKE\ndescription: test\nactions:\n  check:\n    type: assert\n    expression: \"true == true\"\n");
        for (String name : java.util.Arrays.asList("one", "two")) {
            writeWorkbook(projectRoot.resolve("testcase/" + name + ".xlsx"));
            writeText(projectRoot.resolve("testcase/" + name + ".yaml"), "schemaVersion: att-sidecar/v2.1\nid: duplicate\nexcel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");
        }
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run", "--all", "--run-id", "DUPLICATE-WORKBOOK"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new FrameworkEngine(projectRoot, globalConfig()).run(options));
        assertTrue(error.getMessage().contains("Duplicate workbook id 'duplicate'"), error.getMessage());
        assertFalse(Files.exists(projectRoot.resolve("output")));
    }

    private FrameworkConfig globalConfig() {
        Map<String, ToolArgumentConfig> args = new LinkedHashMap<String, ToolArgumentConfig>();
        args.put("caseId", new ToolArgumentConfig("caseId", "Case ID", "Full V2 Case ID", true, ""));
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("invokePaymentApi", new ToolConfig("invokePaymentApi", "Invoke", "Invoke test API",
                "./tools/invoke.sh ${TOOL.input.caseId}", "xml", args));
        Map<String, String> report = new LinkedHashMap<String, String>();
        report.put("result", "Test Result");
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30000,
                Paths.get("templates"), tools, new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", report), new RunConfig("timestamp", "yyyyMMdd-HHmmss"));
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

    private void writeText(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes("UTF-8"));
    }
    private void writeTool(Path path, String body) throws Exception {
        writeText(path, "#!/usr/bin/env sh\nset -eu\n" + body);
        path.toFile().setExecutable(true);
    }
}
