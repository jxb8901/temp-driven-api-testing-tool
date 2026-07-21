/* Author: Jeffrey + ChatGPT */
package att.excel;

import att.config.FrameworkConfig;
import att.config.ReportConfig;
import att.config.RunConfig;
import att.config.SheetGroupConfig;
import att.core.ResultStatus;
import att.core.TestResult;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelReportWriterTest {
    @TempDir Path tempDir;

    @Test
    void fillsMappedExistingColumnsAndAppendsOnlyMissingColumns() throws Exception {
        Path source = tempDir.resolve("payment.xlsx");
        writeSource(source);

        Map<String, String> columns = new LinkedHashMap<String, String>();
        columns.put("result", "測試結果");
        columns.put("durationMs", "耗時毫秒");
        columns.put("expectedResult", "預期結果");
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 1000,
                Paths.get("templates"), Collections.emptyMap(), new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", columns),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"), Collections.singletonList(new SheetGroupConfig("payment", "案例")),
                "案例編號", "", Collections.emptyList(), Collections.emptyList());
        TestResult result = new TestResult("payment.TC001", "付款", ResultStatus.PASS, Duration.ofMillis(37), "line1\r\nline2", "", null, Collections.emptyList());

        Path output = new ExcelReportWriter(config).write(source, tempDir.resolve("run"), Collections.singletonList(result));

        try (InputStream input = Files.newInputStream(output); Workbook workbook = WorkbookFactory.create(input)) {
            Row header = workbook.getSheet("案例").getRow(0);
            Row row = workbook.getSheet("案例").getRow(1);
            assertEquals(4, header.getLastCellNum());
            assertEquals("測試結果", header.getCell(1).getStringCellValue());
            assertEquals("耗時毫秒", header.getCell(2).getStringCellValue());
            assertEquals("PASS", row.getCell(1).getStringCellValue());
            assertEquals("37", row.getCell(2).getStringCellValue());
            assertEquals("預期結果", header.getCell(3).getStringCellValue());
            assertEquals("line1\nline2", row.getCell(3).getStringCellValue());
            assertTrue(row.getCell(3).getCellStyle().getWrapText());
        }
    }

    @Test
    void rendersReportFileNameWithUnifiedBuiltIns() throws Exception {
        Path source = tempDir.resolve("payment.xlsx");
        writeSource(source);
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 1000,
                Paths.get("templates"), Collections.emptyMap(), new ReportConfig("append-to-copy", "#{upper(suiteName)}.result.xlsx", Collections.<String,String>emptyMap()),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"), Collections.singletonList(new SheetGroupConfig("payment", "案例")),
                "案例編號", "", Collections.emptyList(), Collections.emptyList());

        Path output = new ExcelReportWriter(config).write(source, tempDir.resolve("run-expression"), Collections.<TestResult>emptyList());

        assertEquals("PAYMENT.result.xlsx", output.getFileName().toString());
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    void usesTheSameLogicalMultiRowHeadersAsTheTestcaseLoader() throws Exception {
        Path source = tempDir.resolve("multi-header.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(source)) {
            Sheet sheet = workbook.createSheet("案例");
            Row firstHeader = sheet.createRow(0);
            firstHeader.createCell(0).setCellValue("案 例\n編號");
            firstHeader.createCell(1).setCellValue("測試\u00a0結果");
            sheet.createRow(1).createCell(1).setCellValue("");
            Row row = sheet.createRow(2);
            row.createCell(0).setCellValue("TC001");
            row.createCell(1).setCellValue("舊結果");
            workbook.write(output);
        }
        Map<String,String> columns = new LinkedHashMap<String,String>();
        columns.put("result", "測試結果");
        columns.put("durationMs", "耗時毫秒");
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 1000,
                Paths.get("templates"), Collections.emptyMap(), new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", columns),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"), Collections.singletonList(new SheetGroupConfig("payment", "案例")),
                "案例編號", "", Collections.emptyList(), Collections.emptyList(), 2);
        TestResult result = new TestResult("payment.TC001", "付款", ResultStatus.PASS, Duration.ofMillis(37), "", "", null, Collections.emptyList());

        Path output = new ExcelReportWriter(config).write(source, tempDir.resolve("run-multi-header"), Collections.singletonList(result));

        try (InputStream input = Files.newInputStream(output); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet("案例");
            assertEquals("PASS", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("耗時毫秒", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("37", sheet.getRow(2).getCell(2).getStringCellValue());
        }
    }

    @Test
    void reportsAReadableErrorInsteadOfPassingNegativeCellIndexToPoi() throws Exception {
        Path source = tempDir.resolve("missing-case-header.xlsx");
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(source)) {
            Sheet sheet = workbook.createSheet("案例");
            sheet.createRow(0).createCell(0).setCellValue("其他欄位");
            sheet.createRow(1).createCell(0).setCellValue("TC001");
            workbook.write(output);
        }
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 1000,
                Paths.get("templates"), Collections.emptyMap(), new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", Collections.<String,String>emptyMap()),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"), Collections.singletonList(new SheetGroupConfig("payment", "案例")),
                "案例編號", "", Collections.emptyList(), Collections.emptyList());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExcelReportWriter(config).write(source, tempDir.resolve("run-missing-header"), Collections.<TestResult>emptyList()));

        assertTrue(error.getMessage().contains("案例編號"));
        assertTrue(error.getMessage().contains("案例"));
        assertTrue(error.getMessage().contains("available headers"));
    }

    private void writeSource(Path path) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("案例");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("案例編號");
            header.createCell(1).setCellValue("測試結果");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("TC001");
            row.createCell(1).setCellValue("舊結果");
            workbook.write(output);
        }
    }
}
