/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import com.company.apitest.config.FrameworkConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the full V1.1 happy path from Excel row to report output.
 */
class FrameworkEngineTest {
    @TempDir
    Path projectRoot;

    @Test
    void runsV11HappyPath() throws Exception {
        writeText(projectRoot.resolve("templates/request/PAYMENT_TRANSFER/template.xml"),
                "<Request><Case>${CaseID}</Case><Seq>#{getSeq(caseId=${CaseID})}</Seq></Request>");
        writeText(projectRoot.resolve("templates/request/PAYMENT_TRANSFER/api.invocation.yaml"),
                "invoke:\n"
                        + "  call: \"#{invokePaymentApi(requestXml=${TOOLS.renderRequestTemplate[0].outputFile})}\"\n");
        writeText(projectRoot.resolve("templates/check/PAYMENT_PRECHECK/template.yaml"),
                "ready:\n"
                        + "  call: \"#{selectCtxn(txn.ref=${Debit Account})}\"\n"
                        + "  expected: \"${TOOL.output.effectRows} >= '${minAccountRows}'\"\n");
        writeText(projectRoot.resolve("templates/check/PAYMENT_POSTCHECK/template.yaml"),
                "status:\n"
                        + "  call: \"#{getAPIResponse(responseXml=${TOOLS.invokePaymentApi[0].outputFile})}\"\n"
                        + "  expected: \"${TOOL.output.Response.Status} == '${expectedStatus}'\"\n");
        writeTool(projectRoot.resolve("tools/get_seq.sh"), "echo 0001 > \"$4\"\n");
        writeTool(projectRoot.resolve("tools/select_ct_txn.sh"), "cat > \"$4\" <<YAML\n" + "effectRows: 1\nYAML\n");
        writeTool(projectRoot.resolve("tools/invoke_payment_api.sh"), "cat > \"$4\" <<XML\n" + "<Response><Status>SUCCESS</Status></Response>\nXML\n");
        writeTool(projectRoot.resolve("tools/cat_api_response.sh"), "response=$(sed -n 's/^responseXml:[[:space:]]*//p' \"$2\" | head -1); cat \"$response\" > \"$4\"\n");
        writeWorkbook(projectRoot.resolve("testcase/payment_regression.xlsx"));

        RunSummary summary = new FrameworkEngine(projectRoot, config()).run(new ExecutionOptions(
                Paths.get("config/config.yaml"),
                Paths.get("testcase/payment_regression.xlsx"),
                new HashSet<String>(),
                new HashSet<String>()
        ));

        assertEquals(1, summary.passed());
        assertTrue(Files.exists(summary.reportPath()));
        assertTrue(Files.exists(projectRoot.resolve("output/TC001/tools/001_selectCtxn/input.yaml")));
        assertTrue(Files.exists(projectRoot.resolve("output/TC001/tools/002_renderRequestTemplate/output.xml")));
        assertTrue(Files.exists(projectRoot.resolve("output/TC001/tools/003_getSeq/output.txt")));
    }

    private FrameworkConfig config() {
        Map<String, String> columns = new LinkedHashMap<String, String>();
        columns.put("caseId", "Case ID");
        columns.put("caseName", "Case Name");
        columns.put("tags", "Tags");
        columns.put("api", "API");
        columns.put("precheckTemplate", "PreCheck Template");
        columns.put("expectedPrecheckData", "Expected PreCheck Data");
        columns.put("requestTemplate", "Request Template");
        columns.put("postcheckTemplate", "PostCheck Template");
        columns.put("expectedPostcheckData", "Expected PostCheck Data");
        columns.put("debitAccount", "Debit Account");
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("getSeq", tool("getSeq", "./tools/get_seq.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}", "txt"));
        tools.put("selectCtxn", tool("selectCtxn", "./tools/select_ct_txn.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}", "yaml"));
        tools.put("invokePaymentApi", tool("invokePaymentApi", "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}", "xml"));
        tools.put("getAPIResponse", tool("getAPIResponse", "./tools/cat_api_response.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}", "xml"));
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30, "TestCases", columns, tools);
    }

    private ToolConfig tool(String key, String command, String output) {
        return new ToolConfig(key, key, command, output, new LinkedHashMap<String, Object>(), new LinkedHashMap<String, String>());
    }

    private void writeWorkbook(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("TestCases");
            String[] columns = {"Case ID", "Case Name", "Tags", "API", "PreCheck Template", "Expected PreCheck Data", "Request Template", "Debit Account", "PostCheck Template", "Expected PostCheck Data"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("TC001");
            row.createCell(1).setCellValue("Payment success");
            row.createCell(2).setCellValue("smoke");
            row.createCell(3).setCellValue("PAYMENT");
            row.createCell(4).setCellValue("PAYMENT_PRECHECK");
            row.createCell(5).setCellValue("minAccountRows: 1");
            row.createCell(6).setCellValue("PAYMENT_TRANSFER");
            row.createCell(7).setCellValue("111111");
            row.createCell(8).setCellValue("PAYMENT_POSTCHECK");
            row.createCell(9).setCellValue("expectedStatus: SUCCESS");
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
