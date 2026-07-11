/* Author: Jeffrey + ChatGPT */
package att.excel;

import att.config.DataColumnConfig;
import att.config.FrameworkConfig;
import att.config.ReportConfig;
import att.config.RunConfig;
import att.config.SheetGroupConfig;
import att.config.StageConfig;
import att.core.TestCase;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelTestSuiteLoaderTest {
    @TempDir Path tempDir;

    @Test
    void loadsChineseGroupsYamlStageDataAndBlankMarkers() throws Exception {
        Path workbook = tempDir.resolve("支付.xlsx");
        writeWorkbook(workbook);
        List<TestCase> cases = new ExcelTestSuiteLoader(config()).load(workbook);

        assertEquals(2, cases.size());
        assertEquals("payment.TC001", cases.get(0).caseId());
        assertEquals("batch.TC001", cases.get(1).caseId());
        assertEquals("", cases.get(0).caseData().get("note"));
        assertEquals("PAYMENT_INVOKE", cases.get(0).stage("invoke").templateName());
        assertEquals("PAYMENT_INVOKE", cases.get(1).stage("invoke").templateName());
        assertEquals("PAYMENT_INVOKE", cases.get(1).stage("invoke").values().get("name"));
        assertEquals("MOBILE", cases.get(0).stage("invoke").values().get("channel"));
        assertEquals("30", String.valueOf(((Map<?, ?>) cases.get(0).stage("invoke").values().get("params")).get("timeout")));
    }

    @Test
    void resolvesLastNonEmptyCellFromMultiRowHeader() throws Exception {
        Path workbook = tempDir.resolve("multi-header.xlsx");
        writeMultiRowWorkbook(workbook);
        List<TestCase> cases = new ExcelTestSuiteLoader(config(2, false)).load(workbook);
        assertEquals(1, cases.size());
        assertEquals("payment.TC002", cases.get(0).caseId());
        assertEquals("PAYMENT_INVOKE", cases.get(0).stage("invoke").templateName());
    }

    private FrameworkConfig config() {
        return config(1, true);
    }

    private FrameworkConfig config(int headerRows, boolean includeBatch) {
        List<DataColumnConfig> data = Arrays.asList(new DataColumnConfig("caseName", "案例名稱", false), new DataColumnConfig("note", "備註", false));
        List<StageConfig> stages = Collections.singletonList(new StageConfig("invoke", "執行模板",
                Collections.singletonList(new DataColumnConfig("params", "執行參數", true)), true, "stop", "normal"));
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), Collections.emptyMap(), new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", new LinkedHashMap<String, String>()),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"), includeBatch
                ? Arrays.asList(new SheetGroupConfig("payment", "支付測試案例集"), new SheetGroupConfig("batch", "批量測試案例集"))
                : Collections.singletonList(new SheetGroupConfig("payment", "支付測試案例集")),
                "案例編號", "標籤", data, stages, headerRows);
    }

    private void writeWorkbook(Path path) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            for (String name : Arrays.asList("支付測試案例集", "批量測試案例集")) {
                Sheet sheet = workbook.createSheet(name);
                Row header = sheet.createRow(0);
                String[] columns = {"案例編號", "案例名稱", "標籤", "備註", "執行模板", "執行參數"};
                for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
                Row row = sheet.createRow(1);
                row.createCell(0).setCellValue("TC001");
                row.createCell(1).setCellValue("付款成功");
                row.createCell(2).setCellValue("smoke,付款");
                row.createCell(3).setCellValue("N/A");
                row.createCell(4).setCellValue("支付測試案例集".equals(name) ? "name: PAYMENT_INVOKE\nchannel: MOBILE" : "PAYMENT_INVOKE");
                row.createCell(5).setCellValue("timeout: 30");
            }
            workbook.write(output);
        }
    }

    private void writeMultiRowWorkbook(Path path) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("支付測試案例集");
            Row groupHeader = sheet.createRow(0);
            groupHeader.createCell(0).setCellValue("基本資料");
            groupHeader.createCell(4).setCellValue("執行資訊");
            Row header = sheet.createRow(1);
            String[] columns = {"案例編號", "案例名稱", "標籤", "備註", "執行模板", "執行參數"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            Row row = sheet.createRow(2);
            row.createCell(0).setCellValue("TC002");
            row.createCell(1).setCellValue("多行表頭");
            row.createCell(2).setCellValue("smoke");
            row.createCell(3).setCellValue("");
            row.createCell(4).setCellValue("PAYMENT_INVOKE");
            row.createCell(5).setCellValue("timeout: 30");
            workbook.write(output);
        }
    }
}
