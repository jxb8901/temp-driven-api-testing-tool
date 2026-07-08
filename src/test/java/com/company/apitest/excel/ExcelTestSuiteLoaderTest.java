/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.excel;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.core.TestCase;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Excel suite rows and YAML cells are parsed into test cases.
 */
class ExcelTestSuiteLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsEnabledCaseWithYamlData() throws Exception {
        Path workbook = tempDir.resolve("suite.xlsx");
        writeWorkbook(workbook);

        List<TestCase> cases = new ExcelTestSuiteLoader(config()).load(workbook);

        assertEquals(1, cases.size());
        TestCase testCase = cases.get(0);
        assertTrue(testCase.enabled());
        assertEquals("TC001", testCase.caseId());
        assertEquals("ATM", testCase.requestData().get("channel"));
        assertEquals("POSTED", testCase.expectedPostcheckData().get("expectedLedgerStatus"));
    }

    private FrameworkConfig config() {
        Map<String, String> columns = new LinkedHashMap<String, String>();
        columns.put("caseId", "Case ID");
        columns.put("caseName", "Case Name");
        columns.put("tags", "Tags");
        columns.put("api", "API");
        columns.put("requestTemplate", "Request Template");
        columns.put("postcheckTemplate", "Expected Template");
        columns.put("requestData", "Request Data");
        columns.put("expectedPostcheckData", "Expected Data");
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30, "TestCases", columns, new LinkedHashMap<String, com.company.apitest.config.ToolConfig>());
    }

    private void writeWorkbook(Path path) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("TestCases");
            Row header = sheet.createRow(0);
            String[] columns = {"Enable", "Case ID", "Case Name", "Tags", "API", "Request Template", "Expected Template", "Request Data", "Expected Data"};
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Y");
            row.createCell(1).setCellValue("TC001");
            row.createCell(2).setCellValue("Payment success");
            row.createCell(3).setCellValue("smoke,regression");
            row.createCell(4).setCellValue("PAYMENT");
            row.createCell(5).setCellValue("PAYMENT_TRANSFER");
            row.createCell(6).setCellValue("PAYMENT_SUCCESS");
            row.createCell(7).setCellValue("channel: ATM");
            row.createCell(8).setCellValue("expectedLedgerStatus: POSTED");
            workbook.write(output);
        }
    }
}
