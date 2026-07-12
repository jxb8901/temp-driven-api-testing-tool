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

class ExcelReportWriterTest {
    @TempDir Path tempDir;

    @Test
    void fillsMappedExistingColumnsAndAppendsOnlyMissingColumns() throws Exception {
        Path source = tempDir.resolve("payment.xlsx");
        writeSource(source);

        Map<String, String> columns = new LinkedHashMap<String, String>();
        columns.put("result", "測試結果");
        columns.put("durationMs", "耗時毫秒");
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 1000,
                Paths.get("templates"), Collections.emptyMap(), new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", columns),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"), Collections.singletonList(new SheetGroupConfig("payment", "案例")),
                "案例編號", "", Collections.emptyList(), Collections.emptyList());
        TestResult result = new TestResult("payment.TC001", "付款", ResultStatus.PASS, Duration.ofMillis(37), "", "", null, Collections.emptyList());

        Path output = new ExcelReportWriter(config).write(source, tempDir.resolve("run"), Collections.singletonList(result));

        try (InputStream input = Files.newInputStream(output); Workbook workbook = WorkbookFactory.create(input)) {
            Row header = workbook.getSheet("案例").getRow(0);
            Row row = workbook.getSheet("案例").getRow(1);
            assertEquals(3, header.getLastCellNum());
            assertEquals("測試結果", header.getCell(1).getStringCellValue());
            assertEquals("耗時毫秒", header.getCell(2).getStringCellValue());
            assertEquals("PASS", row.getCell(1).getStringCellValue());
            assertEquals("37", row.getCell(2).getStringCellValue());
        }
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
