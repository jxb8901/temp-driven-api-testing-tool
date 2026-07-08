/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.excel;

import com.company.apitest.core.TestResult;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the V1.1 execution report workbook consumed by developers after a run.
 */
public class ExcelReportWriter {
    private static final String[] HEADERS = {"Case ID", "Case Name", "Result", "Duration", "Expected", "Actual", "Artifact Directory"};

    public void write(Path reportPath, List<TestResult> results) throws IOException {
        Files.createDirectories(reportPath.getParent());
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(reportPath)) {
            Sheet sheet = workbook.createSheet("Report");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            for (int i = 0; i < results.size(); i++) {
                TestResult result = results.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(value(result.caseId()));
                row.createCell(1).setCellValue(value(result.caseName()));
                row.createCell(2).setCellValue(result.status().name());
                row.createCell(3).setCellValue(String.valueOf(result.duration().toMillis()));
                row.createCell(4).setCellValue(value(result.expected()));
                row.createCell(5).setCellValue(value(result.actual()));
                row.createCell(6).setCellValue(result.outputXml() == null ? "" : result.outputXml().toString());
            }
            workbook.write(output);
        }
    }

    private String value(String text) {
        return text == null ? "" : text;
    }
}
