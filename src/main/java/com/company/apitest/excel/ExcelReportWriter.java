/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.excel;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.core.TestResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes V1.2 results by copying the source workbook and appending result columns.
 */
public class ExcelReportWriter {
    private final FrameworkConfig config;

    public ExcelReportWriter(FrameworkConfig config) {
        this.config = config;
    }

    public Path write(Path suitePath, Path runDirectory, List<TestResult> results) throws IOException {
        Files.createDirectories(runDirectory);
        String suiteName = suitePath.getFileName().toString().replaceFirst("\\.xlsx$", "");
        Path reportPath = runDirectory.resolve(config.report().fileNamePattern().replace("${suiteName}", suiteName));
        Map<String, TestResult> byCaseId = new LinkedHashMap<String, TestResult>();
        for (TestResult result : results) {
            byCaseId.put(result.caseId(), result);
        }

        try (InputStream input = Files.newInputStream(suitePath); Workbook workbook = WorkbookFactory.create(input); OutputStream output = Files.newOutputStream(reportPath)) {
            Sheet sheet = workbook.getSheet(config.testcaseSheet());
            Row header = sheet.getRow(0);
            int start = resultColumnStart(header);
            int offset = 0;
            for (String headerName : config.report().columns().values()) {
                header.createCell(start + offset).setCellValue(headerName);
                offset++;
            }
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                Cell caseCell = row.getCell(columnIndex(header, config.testcaseColumns().get("caseId")));
                if (caseCell == null) {
                    continue;
                }
                TestResult result = byCaseId.get(caseCell.toString().trim());
                if (result == null) {
                    continue;
                }
                writeResult(row, start, result);
            }
            workbook.write(output);
        }
        return reportPath;
    }

    private void writeResult(Row row, int start, TestResult result) {
        int offset = 0;
        for (String field : config.report().columns().keySet()) {
            row.createCell(start + offset).setCellValue(value(field, result));
            offset++;
        }
    }

    private String value(String field, TestResult result) {
        if ("result".equals(field)) return result.status().name();
        if ("durationMs".equals(field)) return String.valueOf(result.duration().toMillis());
        if ("actualResult".equals(field)) return result.actual();
        if ("caseLog".equals(field)) return result.caseLogPath() == null ? "" : result.caseLogPath().toString();
        if ("runTime".equals(field)) return java.time.LocalDateTime.now().toString();
        return "";
    }

    private int columnIndex(Row header, String name) {
        for (Cell cell : header) {
            if (name.equals(cell.toString().trim())) {
                return cell.getColumnIndex();
            }
        }
        return 0;
    }

    private int resultColumnStart(Row header) {
        int maxConfiguredColumn = -1;
        for (String configuredHeader : config.testcaseColumns().values()) {
            int index = columnIndex(header, configuredHeader);
            if (index > maxConfiguredColumn) {
                maxConfiguredColumn = index;
            }
        }
        return maxConfiguredColumn + 1;
    }
}
