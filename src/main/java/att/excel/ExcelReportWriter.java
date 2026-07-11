/*
 * Author: Jeffrey + ChatGPT
 */

package att.excel;

import att.config.FrameworkConfig;
import att.config.SheetGroupConfig;
import att.core.TestResult;
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
 * Writes V2 results to every configured testcase group sheet.
 */
public class ExcelReportWriter {
    private final FrameworkConfig config;

    public ExcelReportWriter(FrameworkConfig config) {
        this.config = config;
    }

    public Path write(Path suitePath, Path runDirectory, List<TestResult> results) throws IOException {
        Files.createDirectories(runDirectory);
        String suiteName = suitePath.getFileName().toString().replaceFirst("\\.xlsx$", "");
        Path workbookDirectory = runDirectory.resolve("workbooks");
        Files.createDirectories(workbookDirectory);
        Path reportPath = workbookDirectory.resolve(config.report().fileNamePattern().replace("${suiteName}", suiteName));
        Map<String, TestResult> byCaseId = new LinkedHashMap<String, TestResult>();
        for (TestResult result : results) {
            byCaseId.put(result.caseId(), result);
        }

        try (InputStream input = Files.newInputStream(suitePath); Workbook workbook = WorkbookFactory.create(input); OutputStream output = Files.newOutputStream(reportPath)) {
            for (SheetGroupConfig group : config.sheetGroups()) {
                Sheet sheet = workbook.getSheet(group.sheetName());
                Row header = sheet.getRow(0);
                int start = Math.max(0, header.getLastCellNum());
                int offset = 0;
                for (String headerName : config.report().columns().values()) {
                    header.createCell(start + offset).setCellValue(headerName);
                    offset++;
                }
                int caseColumn = columnIndex(header, config.caseIdColumn());
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    Cell caseCell = row.getCell(caseColumn);
                    if (caseCell == null) continue;
                    TestResult result = byCaseId.get(group.id() + "." + caseCell.toString().trim());
                    if (result != null) writeResult(row, start, result);
                }
            }
            workbook.write(output);
        }
        return reportPath;
    }

    private void writeResult(Row row, int start, TestResult result) {
        int offset = 0;
        for (String field : config.report().columns().keySet()) {
            Cell cell = row.createCell(start + offset);
            String text = value(field, result);
            cell.setCellValue(text);
            if ("reportLink".equals(field)) {
                org.apache.poi.ss.usermodel.Hyperlink link = row.getSheet().getWorkbook().getCreationHelper()
                        .createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.FILE);
                link.setAddress(text);
                cell.setHyperlink(link);
            }
            offset++;
        }
    }

    private String value(String field, TestResult result) {
        if ("result".equals(field)) return result.status().name();
        if ("durationMs".equals(field)) return String.valueOf(result.duration().toMillis());
        if ("actualResult".equals(field)) return result.actual();
        if ("caseLog".equals(field)) return result.caseLogPath() == null ? "" : result.caseLogPath().toString();
        if ("reportLink".equals(field)) return "../report/cases/" + result.caseId().replaceAll("[^A-Za-z0-9._-]", "_") + ".html";
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

}
