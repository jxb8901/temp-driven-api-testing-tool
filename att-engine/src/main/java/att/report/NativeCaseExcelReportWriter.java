package att.report;

import att.core.TestResult;
import att.validation.JsonSupport;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Writes the standard XLSX result artifact for native cases without requiring a source workbook. */
public final class NativeCaseExcelReportWriter {
    public static final String OUTPUT_PATH = "workbooks/native-cases.result.xlsx";
    private static final int MAX_CASES = 1000;
    private static final int MAX_CELL_CHARACTERS = 32767;
    private static final List<String> HEADERS = Arrays.asList("Run ID", "Definition ID", "Qualified Case ID", "Local Case ID",
            "Flow ID", "Flow Revision", "Source Revision", "Source Type", "Source Reference", "Source Sheet",
            "Source Row", "Source Record ID", "Source Record Index", "Execution ID", "Status", "Duration (ms)",
            "Expected", "Actual", "Case Log", "Source Provenance (JSON)");

    /** Writes bounded, string-safe report rows and returns the path relative to the run directory. */
    public Path write(Path runDirectory, String runId, List<TestResult> results) throws IOException {
        if (results == null) throw new IllegalArgumentException("Native Case report results are required");
        if (results.size() > MAX_CASES)
            throw new IOException("Native Case XLSX report exceeds the " + MAX_CASES + " row output limit");
        Path normalizedRun = runDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedRun)) throw new IOException("Native Case report run directory must not be a symbolic link");
        Path relative = java.nio.file.Paths.get(OUTPUT_PATH);
        Path output = normalizedRun.resolve(relative).normalize();
        if (!output.startsWith(normalizedRun)) throw new IOException("Unsafe native Case report output path");
        Path workbookDirectory = output.getParent();
        if (Files.isSymbolicLink(workbookDirectory)) throw new IOException("Native Case report directory must not be a symbolic link");
        if (Files.exists(workbookDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(workbookDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native Case report directory is not a directory");
        Files.createDirectories(workbookDirectory);
        if (Files.isSymbolicLink(output)) throw new IOException("Native Case XLSX target must not be a symbolic link");
        if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(output, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native Case XLSX target is not a regular file");

        try (Workbook workbook = new XSSFWorkbook(); OutputStream stream = Files.newOutputStream(output)) {
            Sheet sheet = workbook.createSheet("Cases");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) setText(header.createCell(index), HEADERS.get(index));
            int rowIndex = 1;
            for (TestResult result : results) {
                Row row = sheet.createRow(rowIndex++);
                Map<String, Object> provenance = result.sourceProvenance();
                String sourceType = text(provenance.get("sourceType"));
                String sourceReference = text(provenance.get("sourceReference"));
                String sourceSheet = text(provenance.get("sheet"));
                String caseLog = "";
                if (result.caseLogPath() != null) {
                    Path normalizedLog = result.caseLogPath().toAbsolutePath().normalize();
                    if (!normalizedLog.startsWith(normalizedRun)) throw new IOException("Native Case report cannot include a Case log outside the run directory");
                    caseLog = normalizedRun.relativize(normalizedLog).toString().replace('\\', '/');
                }
                setText(row.createCell(0), runId);
                setText(row.createCell(1), result.definitionId());
                setText(row.createCell(2), result.qualifiedCaseId());
                String qualifiedCaseId = result.qualifiedCaseId();
                String localCaseId = result.definitionId() != null
                        && qualifiedCaseId.startsWith(result.definitionId() + ".")
                        ? qualifiedCaseId.substring(result.definitionId().length() + 1) : "";
                setText(row.createCell(3), localCaseId);
                setText(row.createCell(4), result.flowId());
                setText(row.createCell(5), result.flowRevision());
                setText(row.createCell(6), result.sourceRevision());
                setText(row.createCell(7), sourceType);
                setText(row.createCell(8), sourceReference);
                setText(row.createCell(9), sourceSheet);
                setOptionalNumber(row.createCell(10), provenance.get("row"));
                setText(row.createCell(11), provenance.get("recordId"));
                setOptionalNumber(row.createCell(12), provenance.get("recordIndex"));
                setText(row.createCell(13), result.executionId());
                setText(row.createCell(14), result.status().name());
                row.createCell(15).setCellValue(result.duration().toMillis());
                setText(row.createCell(16), result.expected());
                setText(row.createCell(17), result.actual());
                setText(row.createCell(18), caseLog);
                setText(row.createCell(19), provenance.isEmpty() ? "{}" : JsonSupport.write(provenance));
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, HEADERS.size() - 1));
            workbook.write(stream);
        }
        return output;
    }

    private void setText(Cell cell, String value) {
        String safe = value == null ? "" : value;
        if (safe.length() > MAX_CELL_CHARACTERS) {
            int end = MAX_CELL_CHARACTERS - 14;
            if (end > 0 && Character.isHighSurrogate(safe.charAt(end - 1)) && Character.isLowSurrogate(safe.charAt(end))) end--;
            safe = safe.substring(0, end) + " [truncated]";
        }
        // POI writes this as a string cell even when it starts with a spreadsheet formula marker.
        cell.setCellValue(safe);
    }

    private void setText(Cell cell, Object value) { setText(cell, value == null ? null : String.valueOf(value)); }

    private void setOptionalNumber(Cell cell, Object value) {
        if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue());
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
