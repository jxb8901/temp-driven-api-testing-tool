/* Author: Jeffrey + ChatGPT */
package com.company.apitest.excel;

import com.company.apitest.config.DataColumnConfig;
import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.config.ReportConfig;
import com.company.apitest.config.RunConfig;
import com.company.apitest.config.SheetGroupConfig;
import com.company.apitest.config.StageConfig;
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
        assertEquals("MOBILE", cases.get(0).stage("invoke").values().get("channel"));
        assertEquals("30", String.valueOf(((Map<?, ?>) cases.get(0).stage("invoke").values().get("params")).get("timeout")));
    }

    private FrameworkConfig config() {
        List<DataColumnConfig> data = Arrays.asList(new DataColumnConfig("caseName", "案例名稱", false), new DataColumnConfig("note", "備註", false));
        List<StageConfig> stages = Collections.singletonList(new StageConfig("invoke", "執行模板",
                Collections.singletonList(new DataColumnConfig("params", "執行參數", true)), true, "stop", "normal"));
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), Collections.emptyMap(), new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", new LinkedHashMap<String, String>()),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"), Arrays.asList(new SheetGroupConfig("payment", "支付測試案例集"), new SheetGroupConfig("batch", "批量測試案例集")),
                "案例編號", "標籤", data, stages);
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
                row.createCell(4).setCellValue("name: PAYMENT_INVOKE\nchannel: MOBILE");
                row.createCell(5).setCellValue("timeout: 30");
            }
            workbook.write(output);
        }
    }
}
