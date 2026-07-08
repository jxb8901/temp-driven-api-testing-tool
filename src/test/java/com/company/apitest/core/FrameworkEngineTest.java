/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.config.ReportConfig;
import com.company.apitest.config.RunConfig;
import com.company.apitest.config.StageConfig;
import com.company.apitest.config.ToolConfig;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the V1.2 stage/action runtime from Excel row to run report output.
 */
class FrameworkEngineTest {
    @TempDir
    Path projectRoot;

    @Test
    void runsV12StageFlow() throws Exception {
        writeText(projectRoot.resolve("templates/stage/PREPARE/template.yaml"),
                "actions:\n"
                        + "  getSeq:\n"
                        + "    type: tool\n"
                        + "    call: \"#{getSeq(caseId=${CaseID})}\"\n");
        writeText(projectRoot.resolve("templates/stage/INVOKE/template.yaml"),
                "actions:\n"
                        + "  renderRequest:\n"
                        + "    type: render\n"
                        + "    payload: payload.xml\n"
                        + "  invokeApi:\n"
                        + "    type: tool\n"
                        + "    call: \"#{invokePaymentApi(requestXml=${TOOLS.renderRequest.output})}\"\n");
        writeText(projectRoot.resolve("templates/stage/INVOKE/payload.xml"),
                "<Request><Case>${CaseID}</Case><Seq>${TOOLS.getSeq.output}</Seq></Request>");
        writeText(projectRoot.resolve("templates/stage/VERIFY/template.yaml"),
                "actions:\n"
                        + "  checkStatus:\n"
                        + "    type: assert\n"
                        + "    expression: \"${TOOLS.invokeApi.output.Response.Status} == '${expected.status}'\"\n");
        writeTool(projectRoot.resolve("tools/get_seq.sh"), "echo 0001 > \"$4\"\n");
        writeTool(projectRoot.resolve("tools/invoke_payment_api.sh"),
                "cat > \"$4\" <<XML\n<Response><Status>SUCCESS</Status></Response>\nXML\n");
        writeWorkbook(projectRoot.resolve("testcase/payment_regression.xlsx"));

        RunSummary summary = new FrameworkEngine(projectRoot, config()).run(new ExecutionOptions(
                Paths.get("config/config.yaml"),
                Paths.get("testcase/payment_regression.xlsx"),
                null,
                new HashSet<String>(),
                new HashSet<String>(),
                new HashSet<String>(),
                "TEST-V12-UNIT",
                false,
                false,
                false,
                null
        ));

        assertEquals(1, summary.passed());
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V12-UNIT/payment_regression.result.xlsx")));
        assertTrue(Files.exists(projectRoot.resolve("output/TEST-V12-UNIT/run.yaml")));
        assertTrue(Files.exists(projectRoot.resolve("output/latest-run.yaml")));
        Path caseLog = projectRoot.resolve("output/TEST-V12-UNIT/TC001/TC001.TEST.V12.UNIT.001.log");
        assertTrue(Files.exists(caseLog));
        assertTrue(new String(Files.readAllBytes(caseLog), "UTF-8").contains("ACTION invokeApi"));
    }

    private FrameworkConfig config() {
        Map<String, String> columns = new LinkedHashMap<String, String>();
        columns.put("caseId", "Case ID");
        columns.put("caseName", "Case Name");
        columns.put("tags", "Tags");
        columns.put("api", "API");
        columns.put("stagePre", "Pre Stage Template");
        columns.put("stageMain", "Main Stage Template");
        columns.put("stagePost", "Post Stage Template");
        columns.put("data", "Case Data");

        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("getSeq", tool("getSeq", "./tools/get_seq.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}", "txt"));
        tools.put("invokePaymentApi", tool("invokePaymentApi", "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}", "xml"));

        Map<String, String> reportColumns = new LinkedHashMap<String, String>();
        reportColumns.put("result", "Test Result");
        reportColumns.put("actualResult", "Actual Result");
        reportColumns.put("caseLog", "Case Log");

        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                "TestCases", columns,
                Arrays.asList(new StageConfig("stagePre", "Pre", false, "stop", "normal"),
                        new StageConfig("stageMain", "Main", true, "stop", "normal"),
                        new StageConfig("stagePost", "Post", false, "stop", "normal")),
                Paths.get("templates/stage"), tools, new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", reportColumns),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"));
    }

    private ToolConfig tool(String key, String command, String output) {
        return new ToolConfig(key, key, command, output, new LinkedHashMap<String, Object>(), new LinkedHashMap<String, String>());
    }

    private void writeWorkbook(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("TestCases");
            String[] columns = {"Case ID", "Case Name", "Tags", "API", "Pre Stage Template", "Main Stage Template", "Post Stage Template", "Case Data"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("TC001");
            row.createCell(1).setCellValue("Payment success");
            row.createCell(2).setCellValue("smoke");
            row.createCell(3).setCellValue("PAYMENT");
            row.createCell(4).setCellValue("PREPARE");
            row.createCell(5).setCellValue("INVOKE");
            row.createCell(6).setCellValue("VERIFY");
            row.createCell(7).setCellValue("expected:\n  status: SUCCESS");
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
