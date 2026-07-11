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

class FrameworkEngineTest {
    @TempDir Path projectRoot;

    @Test
    void runsV2GroupedCaseThroughTemplateAndTool() throws Exception {
        writeText(projectRoot.resolve("templates/PAYMENT_INVOKE/template.yaml"),
                "name: PAYMENT_INVOKE\nactions:\n  callApi:\n    type: tool\n    call: \"#{invokePaymentApi(caseId=${CASE.caseId})}\"\n  check:\n    type: assert\n    expression: \"${ACTIONS.callApi.output.Response.Status} == 'SUCCESS'\"\n");
        writeTool(projectRoot.resolve("tools/invoke.sh"), "cat > \"$4\" <<XML\n<Response><Status>SUCCESS</Status></Response>\nXML\n");
        writeWorkbook(projectRoot.resolve("testcase/payment.xlsx"));
        writeText(projectRoot.resolve("testcase/payment.yaml"),
                "excel:\n  sheet: payment=支付測試案例集\n  caseId: 案例編號\n  tags: 標籤\n  dataColumns: caseName=案例名稱\nstages:\n  - key: invoke\n    template: 執行模板\n    required: true\n");

        RunSummary summary = new FrameworkEngine(projectRoot, globalConfig()).run(new ExecutionOptions(
                Paths.get("config/config.yaml"), Paths.get("testcase/payment.xlsx"), null,
                new HashSet<String>(), new HashSet<String>(), new HashSet<String>(), "TEST-V2", false, false, false, null));

        assertEquals(1, summary.passed());
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/workbooks/payment.result.xlsx")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/payment.TC001/payment.TC001.TEST.V2.001.log")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/payment.TC001/case.yaml")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V2/events.jsonl")));
    }

    @Test void runWhenUsesPriorFailureIndependentlyFromStopState() throws Exception {
        FrameworkConfig config = new FrameworkConfig(projectRoot.resolve("output"), projectRoot.resolve("report"), projectRoot.resolve("logs"), "SIT", 10,
                projectRoot.resolve("templates"), Collections.emptyMap(), null, null);
        FrameworkEngine engine = new FrameworkEngine(projectRoot, config);
        java.lang.reflect.Method method = FrameworkEngine.class.getDeclaredMethod("shouldRunStage", StageConfig.class, boolean.class, boolean.class);
        method.setAccessible(true);
        StageConfig failure = new StageConfig("rollback", "Rollback", Collections.emptyList(), false, "continue", "onFailure");
        StageConfig success = new StageConfig("verify", "Verify", Collections.emptyList(), false, "continue", "onSuccess");
        assertTrue((Boolean) method.invoke(engine, failure, true, false));
        assertFalse((Boolean) method.invoke(engine, success, true, false));
    }

    private FrameworkConfig globalConfig() {
        Map<String, ToolArgumentConfig> args = new LinkedHashMap<String, ToolArgumentConfig>();
        args.put("caseId", new ToolArgumentConfig("caseId", "Case ID", "Full V2 Case ID", true, ""));
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("invokePaymentApi", new ToolConfig("invokePaymentApi", "Invoke", "Invoke test API",
                "./tools/invoke.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}", "xml", args));
        Map<String, String> report = new LinkedHashMap<String, String>();
        report.put("result", "Test Result");
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
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
