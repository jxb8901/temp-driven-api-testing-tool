/*
 * Author: Jeffrey + ChatGPT
 */

package att.excel;

import att.config.FrameworkConfig;
import att.config.SheetGroupConfig;
import att.core.IdentifierValidator;
import att.core.TestResult;
import att.core.ValueNormalizer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes V2 results to every configured testcase group sheet.
 */
public class ExcelReportWriter {
    private final FrameworkConfig config;
    private final DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);

    public ExcelReportWriter(FrameworkConfig config) {
        this.config = config;
    }

    public Path write(Path suitePath, Path runDirectory, List<TestResult> results) throws IOException {
        Files.createDirectories(runDirectory);
        String suiteName = suitePath.getFileName().toString().replaceFirst("\\.xlsx$", "");
        Path workbookDirectory = runDirectory.resolve("workbooks");
        Files.createDirectories(workbookDirectory);
        Path reportPath = workbookDirectory.resolve(reportFileName(suiteName));
        Map<String, TestResult> byCaseId = new LinkedHashMap<String, TestResult>();
        for (TestResult result : results) {
            byCaseId.put(result.caseId(), result);
        }

        try (InputStream input = Files.newInputStream(suitePath); Workbook workbook = WorkbookFactory.create(input); OutputStream output = Files.newOutputStream(reportPath)) {
            Map<Short, org.apache.poi.ss.usermodel.CellStyle> wrappedStyles = new LinkedHashMap<Short, org.apache.poi.ss.usermodel.CellStyle>();
            for (SheetGroupConfig group : config.sheetGroups()) {
                Sheet sheet = workbook.getSheet(group.sheetName());
                if (sheet == null) throw new IllegalArgumentException("Configured Sheet does not exist while writing result workbook: " + group.sheetName());
                Row header = sheet.getRow(config.headerRows() - 1);
                if (header == null) throw new IllegalArgumentException("Missing final header row in sheet: " + group.sheetName());
                Map<String, Integer> sourceColumns = ExcelHeaderResolver.columns(sheet, config.headerRows(), formatter);
                Integer caseColumn = sourceColumns.get(config.caseIdColumn());
                if (caseColumn == null) throw new IllegalArgumentException("Configured Case ID header '" + config.caseIdColumn()
                        + "' was not found in Sheet '" + group.sheetName() + "' across the first " + config.headerRows()
                        + " header row(s); available headers=" + sourceColumns.keySet());
                Map<String, Integer> resultColumns = resolveResultColumns(header, sourceColumns,
                        ExcelHeaderResolver.maxColumns(sheet, config.headerRows()));
                for (int rowIndex = config.headerRows(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    Cell caseCell = row.getCell(caseColumn.intValue());
                    if (caseCell == null) continue;
                    String rowCaseId = ValueNormalizer.normalize(formatter.formatCellValue(caseCell));
                    if (rowCaseId.isEmpty()) continue;
                    String fullCaseId = config.workbookId().isEmpty()
                            ? IdentifierValidator.caseId(group.id(), rowCaseId)
                            : IdentifierValidator.caseId(config.workbookId(), group.id(), rowCaseId);
                    TestResult result = byCaseId.get(fullCaseId);
                    if (result != null) writeResult(row, resultColumns, result, wrappedStyles);
                }
            }
            workbook.write(output);
        }
        return reportPath;
    }

    /**
     * Copies a bounded native Excel Data Source workbook to a distinct run artifact and writes
     * configured result columns only to the physical rows pinned by Case provenance.
     */
    public Path writeNativeSourceWorkbook(byte[] sourceWorkbookBytes, Path runDirectory,
            String sourceReference, String sheetName, int headerRows, String caseIdHeader,
            Set<String> dataHeaders, Set<String> sensitiveHeaders, Collection<String> registeredSecrets,
            List<TestResult> results) throws IOException {
        if (sourceWorkbookBytes == null || sourceWorkbookBytes.length == 0
                || sourceWorkbookBytes.length > att.excel.ExcelDataSourceAdapter.MAX_WORKBOOK_BYTES)
            throw new IOException("Native source-workbook report input exceeds its bounded size");
        if (results == null || results.size() > 1000)
            throw new IOException("Native source-workbook report exceeds the 1000 Case output limit");
        if (headerRows < 1 || headerRows > att.excel.ExcelDataSourceAdapter.MAX_HEADER_ROWS)
            throw new IOException("Native source-workbook report headerRows is invalid");

        Path normalizedRun = runDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedRun))
            throw new IOException("Native source-workbook report run directory must not be a symbolic link");
        Path workbookDirectory = normalizedRun.resolve("workbooks");
        if (Files.isSymbolicLink(workbookDirectory))
            throw new IOException("Native source-workbook report directory must not be a symbolic link");
        if (Files.exists(workbookDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(workbookDirectory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native source-workbook report directory is not a directory");
        Files.createDirectories(workbookDirectory);
        Path output = normalizedRun.resolve(att.report.NativeSourceWorkbookReportWriter.OUTPUT_PATH).normalize();
        if (!output.startsWith(normalizedRun) || Files.isSymbolicLink(output)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native source-workbook report destination is unsafe or already exists");
        Path temporary = Files.createTempFile(workbookDirectory, ".native-source-report-", ".xlsx.tmp");
        boolean published = false;
        try {
            rejectThreadedCommentParts(sourceWorkbookBytes);
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(sourceWorkbookBytes));
                 OutputStream stream = Files.newOutputStream(temporary)) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) throw new IOException("Configured source workbook sheet is missing");
                Row header = sheet.getRow(headerRows - 1);
                if (header == null) throw new IOException("Configured source workbook header row is missing");
                Map<String, Integer> sourceColumns = ExcelHeaderResolver.columns(sheet, headerRows, formatter);
                Integer caseColumn = sourceColumns.get(caseIdHeader);
                if (caseColumn == null) throw new IOException("Configured Case ID header is missing from the source workbook");

                Set<String> reservedHeaders = new LinkedHashSet<String>();
                reservedHeaders.add(ExcelHeaderResolver.matchKey(caseIdHeader));
                if (dataHeaders != null) for (String value : dataHeaders)
                    if (value != null && !value.trim().isEmpty())
                        reservedHeaders.add(ExcelHeaderResolver.matchKey(value));
                if (sensitiveHeaders != null) for (String value : sensitiveHeaders)
                    if (value != null && !value.trim().isEmpty())
                        reservedHeaders.add(ExcelHeaderResolver.matchKey(value));
                for (String sourceHeader : sourceColumns.keySet())
                    if (att.security.SecretProtection.sensitiveName(sourceHeader))
                        reservedHeaders.add(ExcelHeaderResolver.matchKey(sourceHeader));
                for (Map.Entry<String, String> mapping : config.report().columns().entrySet()) {
                    String resultHeader = mapping.getValue();
                    if (resultHeader == null || resultHeader.trim().isEmpty()
                            || reservedHeaders.contains(ExcelHeaderResolver.matchKey(resultHeader)))
                        throw new IOException("A configured result header would overwrite a source-data column");
                }
                Map<String, Integer> resultColumns = resolveResultColumns(header, sourceColumns,
                        ExcelHeaderResolver.maxColumns(sheet, headerRows));
                for (Integer resultColumn : resultColumns.values()) {
                    Cell resultHeader = header.getCell(resultColumn.intValue());
                    if (resultHeader != null && resultHeader.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                        throw new IOException("Native source-workbook report cannot overwrite a formula header");
                }
                for (int rowIndex = headerRows; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    for (Integer resultColumn : resultColumns.values()) {
                        Cell existing = row.getCell(resultColumn.intValue());
                        if (existing != null && existing.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                            throw new IOException("Native source-workbook report cannot overwrite a formula result column");
                        if (existing != null) existing.setBlank();
                    }
                }

                Map<Integer, TestResult> resultsByRow = new LinkedHashMap<Integer, TestResult>();
                for (TestResult result : results) {
                    Map<String, Object> provenance = result.sourceProvenance();
                    if (!"excel".equals(text(provenance.get("sourceType")))
                            || !sourceReference.equals(text(provenance.get("sourceReference")))
                            || !sheetName.equals(text(provenance.get("sheet"))))
                        throw new IOException("Native source-workbook report Case provenance does not match its source");
                    Object rowValue = provenance.get("row");
                    if (!(rowValue instanceof Number))
                        throw new IOException("Native source-workbook report Case has no physical source row");
                    int physicalRow = ((Number) rowValue).intValue();
                    if (physicalRow <= headerRows || physicalRow > 1048576
                            || resultsByRow.put(Integer.valueOf(physicalRow), result) != null)
                        throw new IOException("Native source-workbook report Case rows are invalid or duplicated");
                    Row sourceRow = sheet.getRow(physicalRow - 1);
                    Cell sourceCaseId = sourceRow == null ? null : sourceRow.getCell(caseColumn.intValue());
                    String recordId = text(provenance.get("recordId"));
                    if (sourceCaseId == null || recordId.isEmpty()
                            || !recordId.equals(ValueNormalizer.normalize(formatter.formatCellValue(sourceCaseId))))
                        throw new IOException("Native source-workbook report Case row no longer matches its pinned provenance");
                }

                removeCopiedSourceComments(workbook);
                redactCopiedSource(workbook, sheet, headerRows, sourceColumns, sensitiveHeaders, registeredSecrets);
                Map<Short, org.apache.poi.ss.usermodel.CellStyle> wrappedStyles =
                        new LinkedHashMap<Short, org.apache.poi.ss.usermodel.CellStyle>();
                for (Map.Entry<Integer, TestResult> item : resultsByRow.entrySet()) {
                    Row row = sheet.getRow(item.getKey().intValue() - 1);
                    writeNativeSourceResult(row, resultColumns, item.getValue(), normalizedRun,
                            registeredSecrets, wrappedStyles);
                }
                workbook.write(stream);
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Unable to create the native source-workbook report", failure);
            }
            if (Files.size(temporary) > att.report.NativeSourceWorkbookReportWriter.MAX_OUTPUT_BYTES)
                throw new att.report.NativeSourceWorkbookReportWriter.OutputLimitException();
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, output);
            }
            published = true;
            return output;
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    private void writeNativeSourceResult(Row row, Map<String, Integer> columns, TestResult result,
            Path runDirectory, Collection<String> registeredSecrets,
            Map<Short, org.apache.poi.ss.usermodel.CellStyle> wrappedStyles) throws IOException {
        for (Map.Entry<String, Integer> mapping : columns.entrySet()) {
            String field = mapping.getKey();
            Cell cell = row.getCell(mapping.getValue(), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String value;
            if ("result".equals(field)) value = result.status().name();
            else if ("durationMs".equals(field)) value = String.valueOf(result.duration().toMillis());
            else if ("expectedResult".equals(field)) value = normalizeLines(result.expected());
            else if ("actualResult".equals(field)) value = normalizeLines(result.actual());
            else if ("caseLog".equals(field)) {
                if (result.caseLogPath() == null) value = "";
                else {
                    Path log = result.caseLogPath().toAbsolutePath().normalize();
                    if (!log.startsWith(runDirectory))
                        throw new IOException("Native source-workbook report Case log is outside the run directory");
                    value = runDirectory.relativize(log).toString().replace('\\', '/');
                }
            } else if ("execId".equals(field)) value = result.executionId();
            else if ("reportLink".equals(field)) value = "../report/index.html#case-" + att.report.HtmlSupport.id(result.caseId());
            else if ("runTime".equals(field)) value = java.time.LocalDateTime.now().toString();
            else value = "";
            value = att.security.SecretProtection.text(value, registeredSecrets);
            cell.setCellValue(safeResultText(value));
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
        }
    }

    /** Redact source values in every sheet before the copied workbook can be published. */
    private void redactCopiedSource(Workbook workbook, Sheet configuredSheet, int configuredHeaderRows,
            Map<String,Integer> configuredColumns, Set<String> configuredSensitiveHeaders,
            Collection<String> registeredSecrets) throws IOException {
        Set<String> explicitSensitive = new LinkedHashSet<String>();
        if (configuredSensitiveHeaders != null) for (String header : configuredSensitiveHeaders)
            if (header != null && !header.trim().isEmpty())
                explicitSensitive.add(ExcelHeaderResolver.matchKey(header));

        Map<Sheet, Set<Integer>> sensitiveColumnsBySheet = new LinkedHashMap<Sheet, Set<Integer>>();
        boolean hasFormula = false;
        boolean wouldRedact = false;
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            boolean configured = sheet == configuredSheet;
            Set<Integer> sensitiveColumns = new LinkedHashSet<Integer>();
            if (configured) {
                for (Map.Entry<String,Integer> header : configuredColumns.entrySet())
                    if (isSensitiveSourceHeader(header.getKey(), explicitSensitive))
                        sensitiveColumns.add(header.getValue());
                for (int rowIndex = 0; rowIndex < configuredHeaderRows; rowIndex++) {
                    Row headerRow = sheet.getRow(rowIndex);
                    if (headerRow == null) continue;
                    for (Cell cell : headerRow) {
                        String header = formatter.formatCellValue(cell);
                        if (isSensitiveSourceHeader(header, explicitSensitive))
                            sensitiveColumns.add(Integer.valueOf(cell.getColumnIndex()));
                    }
                }
            } else {
                // Other sheets have no verified header row. Treat every sensitive-looking cell
                // as potentially sensitive data and redact the whole column when a matching label
                // or value appears; exempting the first match could leak a data value such as
                // "password" when the actual header is ordinary text.
                for (Row row : sheet) for (Cell cell : row) {
                    String header = formatter.formatCellValue(cell);
                    if (!isSensitiveSourceHeader(header, explicitSensitive)) continue;
                    sensitiveColumns.add(Integer.valueOf(cell.getColumnIndex()));
                }
            }
            sensitiveColumnsBySheet.put(sheet, sensitiveColumns);

            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                for (Cell cell : row) {
                    boolean sensitiveColumn = sensitiveColumns.contains(Integer.valueOf(cell.getColumnIndex()));
                    boolean dataCell = sensitiveColumn && (configured
                            ? rowIndex >= configuredHeaderRows
                            : true);
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                        String formula = cell.getCellFormula();
                        hasFormula = true;
                        if ((dataCell && cell.getCachedFormulaResultType() != org.apache.poi.ss.usermodel.CellType.BLANK)
                                || !att.security.SecretProtection.text(formula, registeredSecrets).equals(formula))
                            wouldRedact = true;
                        continue;
                    }
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) continue;
                    if (dataCell) {
                        wouldRedact = true;
                        continue;
                    }
                    String original = formatter.formatCellValue(cell);
                    if (!original.equals(att.security.SecretProtection.text(original, registeredSecrets)))
                        wouldRedact = true;
                }
            }
        }

        // OOXML stores formula results separately from formula text. POI preserves those cached
        // values when source cells are redacted, so a dependent formula can still expose the old
        // secret. Fall back to the standard report instead of publishing a potentially stale cache.
        if (hasFormula && wouldRedact)
            throw new IOException("Native source-workbook report cannot safely redact formula cached values");

        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            boolean configured = sheet == configuredSheet;
            Set<Integer> sensitiveColumns = sensitiveColumnsBySheet.get(sheet);
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                for (Cell cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                        String formula = cell.getCellFormula();
                        if (!att.security.SecretProtection.text(formula, registeredSecrets).equals(formula))
                            throw new IOException("Native source-workbook report cannot safely redact a protected formula");
                        continue;
                    }
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) continue;
                    boolean sensitiveColumn = sensitiveColumns.contains(Integer.valueOf(cell.getColumnIndex()));
                    boolean dataCell = sensitiveColumn && (configured
                            ? rowIndex >= configuredHeaderRows
                            : true);
                    if (dataCell) {
                        cell.setCellValue(att.security.SecretProtection.REDACTED);
                        continue;
                    }
                    String original = formatter.formatCellValue(cell);
                    String protectedValue = att.security.SecretProtection.text(original, registeredSecrets);
                    if (!original.equals(protectedValue)) cell.setCellValue(protectedValue);
                }
            }
        }
    }

    /**
     * Cell comments and notes are unstructured source text and can contain credentials even when
     * the associated cell value is redacted. The optional copied workbook is downloadable, so
     * remove every comment on every sheet before publishing it.
     */
    private static void removeCopiedSourceComments(Workbook workbook) {
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            Set<CellAddress> addresses = new LinkedHashSet<CellAddress>(sheet.getCellComments().keySet());
            for (CellAddress address : addresses) {
                Row row = sheet.getRow(address.getRow());
                if (row == null) row = sheet.createRow(address.getRow());
                Cell cell = row.getCell(address.getColumn());
                if (cell == null) cell = row.createCell(address.getColumn());
                cell.removeCellComment();
            }
        }
    }

    /**
     * POI 5.2.5 exposes legacy comments through Sheet.getCellComments(), but does not model
     * modern threaded-comment or person OOXML parts. Reject those source workbooks before POI
     * rewrites them, so opaque comment payloads cannot survive into the downloadable report.
     */
    private static void rejectThreadedCommentParts(byte[] sourceWorkbookBytes) throws IOException {
        try (OPCPackage packageFile = OPCPackage.open(new ByteArrayInputStream(sourceWorkbookBytes))) {
            for (PackagePart part : packageFile.getParts()) {
                if (part.isRelationshipPart()) continue;
                String name = part.getPartName().getName().toLowerCase(java.util.Locale.ROOT);
                String contentType = part.getContentType().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("/threadedcomments/") || name.endsWith("/persons/person.xml")
                        || contentType.contains("threadedcomments")
                        || contentType.contains("vnd.ms-excel.person+xml")) {
                    throw new IOException("Native source-workbook report cannot safely remove threaded comments");
                }
                for (PackageRelationship relationship : part.getRelationships()) {
                    String type = relationship.getRelationshipType().toLowerCase(java.util.Locale.ROOT);
                    if (type.contains("threadedcomment"))
                        throw new IOException("Native source-workbook report cannot safely remove threaded comments");
                }
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Native source-workbook report cannot verify threaded-comment parts", failure);
        }
    }

    private boolean isSensitiveSourceHeader(String value, Set<String> explicitSensitive) {
        return att.security.SecretProtection.sensitiveName(value)
                || (value != null && explicitSensitive.contains(ExcelHeaderResolver.matchKey(value)));
    }

    private String safeResultText(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() > 32767) {
            int end = 32767 - 14;
            if (Character.isHighSurrogate(safe.charAt(end - 1)) && Character.isLowSurrogate(safe.charAt(end))) end--;
            safe = safe.substring(0, end) + " [truncated]";
        }
        return safe;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private String reportFileName(String suiteName) throws IOException {
        Map<String, Object> scope = new LinkedHashMap<String, Object>();
        scope.put("suiteName", suiteName);
        try {
            return new att.template.UnifiedTemplateEngine(null).renderScoped(config.report().fileNamePattern(), scope);
        } catch (Exception e) {
            throw new IOException("Unable to render report.fileNamePattern: " + e.getMessage(), e);
        }
    }

    private Map<String, Integer> resolveResultColumns(Row header, Map<String, Integer> sourceColumns, int firstUnusedColumn) {
        Map<String, Integer> columns = new LinkedHashMap<String, Integer>();
        int nextColumn = Math.max(firstUnusedColumn, Math.max(0, header.getLastCellNum()));
        for (Map.Entry<String, String> mapping : config.report().columns().entrySet()) {
            Integer existing = sourceColumns.get(mapping.getValue());
            int column;
            if (existing == null) {
                column = nextColumn++;
                header.createCell(column).setCellValue(mapping.getValue());
                sourceColumns.put(mapping.getValue(), Integer.valueOf(column));
            } else column = existing.intValue();
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
        if ("execId".equals(field)) return result.executionId();
        if ("reportLink".equals(field)) return "../report/index.html#case-" + att.report.HtmlSupport.id(result.caseId());
        if ("runTime".equals(field)) return java.time.LocalDateTime.now().toString();
        return "";
    }

    private String normalizeLines(String value) { return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n'); }

}
