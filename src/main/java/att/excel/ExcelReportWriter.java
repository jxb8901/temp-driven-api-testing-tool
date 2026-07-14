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
            Map<Short, org.apache.poi.ss.usermodel.CellStyle> wrappedStyles = new LinkedHashMap<Short, org.apache.poi.ss.usermodel.CellStyle>();
            for (SheetGroupConfig group : config.sheetGroups()) {
                Sheet sheet = workbook.getSheet(group.sheetName());
                Row header = sheet.getRow(config.headerRows() - 1);
                if (header == null) throw new IllegalArgumentException("Missing final header row in sheet: " + group.sheetName());
                Map<String, Integer> resultColumns = resolveResultColumns(header);
                int caseColumn = columnIndex(header, config.caseIdColumn());
                for (int rowIndex = config.headerRows(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    Cell caseCell = row.getCell(caseColumn);
                    if (caseCell == null) continue;
                    String prefix = config.workbookId().isEmpty() ? group.id() : config.workbookId() + "." + group.id();
                    TestResult result = byCaseId.get(prefix + "." + caseCell.toString().trim());
                    if (result != null) writeResult(row, resultColumns, result, wrappedStyles);
                }
            }
            workbook.write(output);
        }
        return reportPath;
    }

    private Map<String, Integer> resolveResultColumns(Row header) {
        Map<String, Integer> columns = new LinkedHashMap<String, Integer>();
        int nextColumn = Math.max(0, header.getLastCellNum());
        for (Map.Entry<String, String> mapping : config.report().columns().entrySet()) {
            int column = columnIndex(header, mapping.getValue());
            if (column < 0) {
                column = nextColumn++;
                header.createCell(column).setCellValue(mapping.getValue());
            }
            columns.put(mapping.getKey(), column);
        }
        return columns;
    }

    private void writeResult(Row row, Map<String, Integer> columns, TestResult result, Map<Short, org.apache.poi.ss.usermodel.CellStyle> wrappedStyles) {
        for (Map.Entry<String, Integer> mapping : columns.entrySet()) {
            String field = mapping.getKey();
            Cell cell = row.getCell(mapping.getValue(), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String text = value(field, result);
            cell.setCellValue(text);
            if ("expectedResult".equals(field) || "actualResult".equals(field)) {
                short baseIndex = cell.getCellStyle() == null ? 0 : cell.getCellStyle().getIndex();
                org.apache.poi.ss.usermodel.CellStyle style = wrappedStyles.get(Short.valueOf(baseIndex));
                if (style == null) {
                    style = row.getSheet().getWorkbook().createCellStyle();
                    if (cell.getCellStyle() != null) style.cloneStyleFrom(cell.getCellStyle());
                    style.setWrapText(true);
                    wrappedStyles.put(Short.valueOf(baseIndex), style);
                }
                cell.setCellStyle(style);
            }
            if ("reportLink".equals(field)) {
                org.apache.poi.ss.usermodel.Hyperlink link = row.getSheet().getWorkbook().getCreationHelper()
                        .createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.FILE);
                link.setAddress(text);
                cell.setHyperlink(link);
            }
        }
    }

    private String value(String field, TestResult result) {
        if ("result".equals(field)) return result.status().name();
        if ("durationMs".equals(field)) return String.valueOf(result.duration().toMillis());
        if ("expectedResult".equals(field)) return normalizeLines(result.expected());
        if ("actualResult".equals(field)) return normalizeLines(result.actual());
        if ("caseLog".equals(field)) return result.caseLogPath() == null ? "" : result.caseLogPath().toString();
        if ("reportLink".equals(field)) return "../report/index.html#case-" + att.report.HtmlSupport.id(result.caseId());
        if ("runTime".equals(field)) return java.time.LocalDateTime.now().toString();
        return "";
    }

    private String normalizeLines(String value) { return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n'); }

    private int columnIndex(Row header, String name) {
        for (Cell cell : header) {
            if (name.equals(cell.toString().trim())) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

}
