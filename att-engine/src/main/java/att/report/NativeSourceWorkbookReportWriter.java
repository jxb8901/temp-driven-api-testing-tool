package att.report;

import att.config.ColumnSpecParser;
import att.config.DataColumnConfig;
import att.config.FrameworkConfig;
import att.core.TestResult;
import att.core.ValueNormalizer;
import att.excel.ExcelDataSourceAdapter;
import att.excel.ExcelReportWriter;
import att.resource.PackageResourceResolver;
import att.testcase.CaseSourceProvenance;
import att.testcase.ResolvedTestCase;
import att.testcase.TestCaseDefinition;
import att.testcase.TestCaseResolutionResult;
import att.testcase.TestCaseSuiteResolutionResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates the optional source-workbook-shaped report from the accepted native Excel snapshot. */
public final class NativeSourceWorkbookReportWriter {
    public static final String OUTPUT_PATH = "workbooks/native-cases.source-workbook.xlsx";
    public static final long MAX_OUTPUT_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_CASES = 1000;

    private NativeSourceWorkbookReportWriter() { }

    /**
     * Captures and verifies the source workbook before Engine starts executing any selected Case.
     * The returned Prepared value owns those exact bytes and is the only source used for report
     * generation after execution.
     */
    public static Prepared prepareIfRequested(Path packageRoot,
            TestCaseSuiteResolutionResult resolution) throws Exception {
        if (packageRoot == null || resolution == null)
            throw new IllegalArgumentException("Native source-workbook preparation inputs are required");
        boolean requested = false;
        for (TestCaseResolutionResult definition : resolution.definitions()) {
            Map<String,Object> report = definition.definition().report();
            if (report != null && "sourceWorkbook".equals(String.valueOf(report.get("template")))) requested = true;
        }
        if (!requested) return null;
        if (resolution.definitions().size() != 1)
            return Prepared.fallback("MULTIPLE_DEFINITIONS", "Source-workbook layout is unavailable for a multi-Definition Suite; the standard Native XLSX contains all selected Cases.");

        TestCaseResolutionResult selectedDefinition = resolution.definitions().get(0);
        TestCaseDefinition definition = selectedDefinition.definition();
        if (!"excel".equals(definition.dataSourceType()))
            return Prepared.fallback("NON_EXCEL_SOURCE", "Source-workbook layout requires an Excel Data Source; the standard Native XLSX contains all selected Cases.");
        if (resolution.cases().isEmpty())
            return Prepared.fallback("EMPTY_SELECTION", "Source-workbook layout requires a nonempty Case selection; the standard Native XLSX contains all available results.");
        if (resolution.cases().size() > MAX_CASES)
            return Prepared.fallback("OUTPUT_LIMIT", "Source-workbook layout exceeds the 1000 Case output limit; the standard Native XLSX contains all selected Cases.");

        Map<String,Object> source = definition.dataSourceFields();
        String authoredPath = text(source.get("path"));
        String sheetName = text(source.get("sheet"));
        String caseIdHeader = text(source.get("caseId"));
        if (authoredPath.isEmpty() || sheetName.isEmpty() || caseIdHeader.isEmpty())
            throw new IOException("Native source-workbook report has incomplete Excel Data Source metadata");
        int headerRows = integer(source.get("headerRows"), 1);
        if (headerRows < 1 || headerRows > ExcelDataSourceAdapter.MAX_HEADER_ROWS)
            throw new IOException("Native source-workbook report headerRows is invalid");

        ExcelDataSourceAdapter.WorkbookSnapshot snapshot = ExcelDataSourceAdapter.captureWorkbook(
                new PackageResourceResolver(packageRoot), selectedDefinition.sourceReference(), authoredPath);
        validatePinnedRows(snapshot.bytes(), sheetName, headerRows, caseIdHeader, resolution.cases());
        Set<String> rowIdentities = new LinkedHashSet<String>();
        for (ResolvedTestCase testCase : resolution.cases()) {
            if (!definition.id().equals(testCase.definitionId()))
                return Prepared.fallback("MIXED_DEFINITIONS", "Source-workbook layout requires Cases from one Definition; the standard Native XLSX contains every result.");
            CaseSourceProvenance provenance = testCase.sourceProvenance();
            if (!"excel".equals(provenance.sourceType()))
                return Prepared.fallback("MIXED_SOURCE_TYPES", "Source-workbook layout cannot combine Excel and non-Excel Cases; the standard Native XLSX contains every result.");
            if (!snapshot.sourceReference().equals(provenance.sourceReference())
                    || !sheetName.equals(provenance.sheet()))
                return Prepared.fallback("MIXED_WORKBOOKS_OR_SHEETS", "Source-workbook layout requires one workbook and worksheet; the standard Native XLSX contains every result.");
            Object pinnedDigest = provenance.metadata().get("workbookSha256");
            if (pinnedDigest == null || !snapshot.sha256().equals(String.valueOf(pinnedDigest)))
                throw new IOException("Native Excel source changed after Case resolution; refusing to start RUN");
            if (provenance.row() == null || provenance.recordId() == null
                    || !rowIdentities.add(String.valueOf(provenance.row())))
                throw new IOException("Native source-workbook report has invalid or duplicated pinned row provenance");
        }

        Set<String> dataHeaders = new LinkedHashSet<String>();
        Set<String> sensitiveHeaders = new LinkedHashSet<String>();
        Object dataColumnsValue = source.get("dataColumns");
        if (dataColumnsValue != null) {
            for (DataColumnConfig column : ColumnSpecParser.dataColumns(String.valueOf(dataColumnsValue))) {
                dataHeaders.add(column.columnName());
                if (att.security.SecretProtection.sensitiveName(column.key())
                        || att.security.SecretProtection.sensitiveName(column.columnName()))
                    sensitiveHeaders.add(column.columnName());
            }
        }
        Object tagsHeader = source.get("tags");
        if (tagsHeader != null) dataHeaders.add(String.valueOf(tagsHeader));
        byte[] bytes = snapshot.bytes();
        return Prepared.ready(definition.id(), snapshot.sourceReference(), sheetName, headerRows,
                caseIdHeader, dataHeaders, sensitiveHeaders, resolution.cases().size(), snapshot.sha256(),
                formulaInventorySha256(bytes), bytes);
    }

    /** Validate every selected physical row before any Case can begin execution. */
    private static void validatePinnedRows(byte[] bytes, String sheetName, int headerRows,
            String caseIdHeader, List<ResolvedTestCase> cases) throws IOException {
        DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw new IOException("Configured source workbook sheet is missing");
            Map<String,Integer> columns = att.excel.ExcelHeaderResolver.columns(sheet, headerRows, formatter);
            Integer caseIdColumn = columns.get(caseIdHeader);
            if (caseIdColumn == null) throw new IOException("Configured Case ID header is missing from the source workbook");
            Set<Integer> seenRows = new LinkedHashSet<Integer>();
            for (ResolvedTestCase testCase : cases) {
                CaseSourceProvenance provenance = testCase.sourceProvenance();
                if (provenance.row() == null || provenance.recordId() == null
                        || provenance.row() <= headerRows || !seenRows.add(provenance.row()))
                    throw new IOException("Native source-workbook report has invalid or duplicate pinned row provenance");
                Row row = sheet.getRow(provenance.row() - 1);
                Cell cell = row == null ? null : row.getCell(caseIdColumn.intValue());
                String actual = cell == null ? "" : ValueNormalizer.normalize(formatter.formatCellValue(cell));
                if (!provenance.recordId().equals(actual))
                    throw new IOException("Native Excel source row changed after Case resolution; refusing to start RUN");
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Unable to verify the bounded source workbook Case rows", failure);
        }
    }

    /** Writes the already captured source snapshot to a distinct path after Case execution. */
    public static Result writePrepared(Prepared prepared, Path runDirectory, List<TestResult> results,
            FrameworkConfig config, Collection<String> registeredSecrets) throws Exception {
        if (prepared == null) return null;
        if (prepared.fallbackResult != null) return prepared.fallbackResult;
        if (runDirectory == null || results == null || config == null)
            throw new IllegalArgumentException("Native source-workbook report write inputs are required");
        if (results.size() != prepared.expectedCaseCount)
            return Result.fallback("INCOMPLETE_SELECTION", "Source-workbook layout requires results for every selected Case; the standard Native XLSX contains all available results.");
        for (TestResult result : results) {
            if (!prepared.definitionId.equals(result.definitionId()))
                return Result.fallback("MIXED_DEFINITIONS", "Source-workbook layout requires Cases from one Definition; the standard Native XLSX contains every result.");
            Map<String,Object> provenance = result.sourceProvenance();
            if (!"excel".equals(text(provenance.get("sourceType"))))
                return Result.fallback("MIXED_SOURCE_TYPES", "Source-workbook layout cannot combine Excel and non-Excel Cases; the standard Native XLSX contains every result.");
            if (!prepared.sourceReference.equals(text(provenance.get("sourceReference")))
                    || !prepared.sheet.equals(text(provenance.get("sheet"))))
                return Result.fallback("MIXED_WORKBOOKS_OR_SHEETS", "Source-workbook layout requires one workbook and worksheet; the standard Native XLSX contains every result.");
            if (!prepared.sourceSha256.equals(workbookSha256(provenance)))
                throw new IOException("Native Case result provenance does not match the source snapshot captured before RUN");
        }

        Path reportPath;
        try {
            reportPath = new ExcelReportWriter(config).writeNativeSourceWorkbook(prepared.sourceBytes,
                    runDirectory, prepared.sourceReference, prepared.sheet, prepared.headerRows,
                    prepared.caseIdHeader, prepared.dataHeaders, prepared.sensitiveHeaders,
                    registeredSecrets, results);
        } catch (OutputLimitException tooLarge) {
            return Result.fallback("OUTPUT_LIMIT", "Source-workbook report exceeds the 16 MiB Server artifact limit; the standard Native XLSX contains all selected Cases.");
        } catch (IOException unsupported) {
            return Result.fallback("SOURCE_WORKBOOK_UNAVAILABLE", "Source-workbook layout could not be safely produced; the standard Native XLSX contains all selected Cases.");
        }
        try {
            byte[] reportBytes = Files.readAllBytes(reportPath);
            String outputFormulaSha256 = formulaInventorySha256(reportBytes);
            if (!prepared.formulaSha256.equals(outputFormulaSha256)) {
                Files.deleteIfExists(reportPath);
                return Result.fallback("FORMULA_INVENTORY_CHANGED", "Source-workbook layout changed a source formula; the standard Native XLSX contains all selected Cases.");
            }
            if (reportBytes.length > MAX_OUTPUT_BYTES) {
                Files.deleteIfExists(reportPath);
                return Result.fallback("OUTPUT_LIMIT", "Source-workbook report exceeds the 16 MiB Server artifact limit; the standard Native XLSX contains all selected Cases.");
            }
            return Result.written(prepared.sourceReference, prepared.sheet, prepared.sourceSha256,
                    sha256(reportBytes), prepared.formulaSha256);
        } catch (Exception failure) {
            Files.deleteIfExists(reportPath);
            return Result.fallback("SOURCE_WORKBOOK_UNAVAILABLE", "Source-workbook layout could not be safely verified; the standard Native XLSX contains all selected Cases.");
        }
    }

    /**
     * SHA-256 of the formula inventory in workbook sheet order, then row and column order.
     * Every record contains four length-delimited UTF-8 fields: sheet name, 1-based row, 1-based
     * column, and exact formula text. The same helper is used by Server archive validation.
     */
    public static String formulaInventorySha256(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > ExcelDataSourceAdapter.MAX_WORKBOOK_BYTES)
            throw new IOException("Formula inventory input exceeds the bounded workbook size");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                    org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(sheetIndex);
                    for (Row row : sheet) for (Cell cell : row) {
                        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                            updateLengthDelimited(digest, sheet.getSheetName());
                            updateLengthDelimited(digest, String.valueOf(row.getRowNum() + 1));
                            updateLengthDelimited(digest, String.valueOf(cell.getColumnIndex() + 1));
                            updateLengthDelimited(digest, cell.getCellFormula());
                        }
                    }
                }
            }
            return hex(digest.digest());
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Unable to read the bounded source workbook formula inventory", failure);
        }
    }

    /** Compatibility name shared with Server-side archive validation. */
    public static String formulaSha256(byte[] bytes) throws IOException {
        return formulaInventorySha256(bytes);
    }

    private static void updateLengthDelimited(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String workbookSha256(Map<String,Object> provenance) {
        Object metadata = provenance.get("metadata");
        if (!(metadata instanceof Map<?,?>)) return "";
        Object value = ((Map<?,?>) metadata).get("workbookSha256");
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) throws IOException {
        if (value == null) return fallback;
        if (!(value instanceof Number)) throw new IOException("Native source-workbook report headerRows is invalid");
        double number = ((Number) value).doubleValue();
        if (number != Math.rint(number) || number < 1 || number > Integer.MAX_VALUE)
            throw new IOException("Native source-workbook report headerRows is invalid");
        return (int) number;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    /** A signal that leaves no optional output behind while allowing the standard XLSX to remain complete. */
    public static final class OutputLimitException extends IOException {
        private static final long serialVersionUID = 1L;
        public OutputLimitException() { super("Native source-workbook output exceeds the bounded artifact size"); }
    }

    public static final class Prepared {
        private final String definitionId;
        private final String sourceReference;
        private final String sheet;
        private final int headerRows;
        private final String caseIdHeader;
        private final Set<String> dataHeaders;
        private final Set<String> sensitiveHeaders;
        private final int expectedCaseCount;
        private final String sourceSha256;
        private final String formulaSha256;
        private final byte[] sourceBytes;
        private final Result fallbackResult;

        private Prepared(String definitionId, String sourceReference, String sheet, int headerRows,
                String caseIdHeader, Set<String> dataHeaders, Set<String> sensitiveHeaders,
                int expectedCaseCount, String sourceSha256,
                String formulaSha256, byte[] sourceBytes, Result fallbackResult) {
            this.definitionId = definitionId;
            this.sourceReference = sourceReference;
            this.sheet = sheet;
            this.headerRows = headerRows;
            this.caseIdHeader = caseIdHeader;
            this.dataHeaders = Collections.unmodifiableSet(new LinkedHashSet<String>(dataHeaders));
            this.sensitiveHeaders = Collections.unmodifiableSet(new LinkedHashSet<String>(sensitiveHeaders));
            this.expectedCaseCount = expectedCaseCount;
            this.sourceSha256 = sourceSha256;
            this.formulaSha256 = formulaSha256;
            this.sourceBytes = sourceBytes == null ? null : sourceBytes.clone();
            this.fallbackResult = fallbackResult;
        }

        private static Prepared ready(String definitionId, String sourceReference, String sheet,
                int headerRows, String caseIdHeader, Set<String> dataHeaders, Set<String> sensitiveHeaders,
                int expectedCaseCount,
                String sourceSha256, String formulaSha256, byte[] sourceBytes) {
            return new Prepared(definitionId, sourceReference, sheet, headerRows, caseIdHeader,
                    dataHeaders, sensitiveHeaders, expectedCaseCount, sourceSha256, formulaSha256, sourceBytes, null);
        }

        private static Prepared fallback(String code, String message) {
            return new Prepared(null, null, null, 0, null, Collections.<String>emptySet(),
                    Collections.<String>emptySet(), 0, null, null, null, Result.fallback(code, message));
        }
    }

    public static final class Result {
        private final String status;
        private final String diagnosticCode;
        private final String diagnostic;
        private final String sourceReference;
        private final String sheet;
        private final String sourceSha256;
        private final String outputSha256;
        private final String formulaSha256;

        private Result(String status, String diagnosticCode, String diagnostic, String sourceReference,
                String sheet, String sourceSha256, String outputSha256, String formulaSha256) {
            this.status = status;
            this.diagnosticCode = diagnosticCode;
            this.diagnostic = diagnostic;
            this.sourceReference = sourceReference;
            this.sheet = sheet;
            this.sourceSha256 = sourceSha256;
            this.outputSha256 = outputSha256;
            this.formulaSha256 = formulaSha256;
        }

        private static Result fallback(String code, String message) {
            return new Result("fallback", code, message, null, null, null, null, null);
        }

        private static Result written(String sourceReference, String sheet, String sourceSha256,
                String outputSha256, String formulaSha256) {
            return new Result("written", null, null, sourceReference, sheet, sourceSha256,
                    outputSha256, formulaSha256);
        }

        public boolean written() { return "written".equals(status); }
        public String outputPath() { return written() ? OUTPUT_PATH : null; }

        public Map<String,Object> toMap() {
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            result.put("schemaVersion", "att-native-source-workbook-report/v1");
            result.put("status", status);
            if (diagnosticCode != null) result.put("diagnosticCode", diagnosticCode);
            if (diagnostic != null) result.put("diagnostic", diagnostic);
            if (sourceReference != null) result.put("sourceReference", sourceReference);
            if (sheet != null) result.put("sheet", sheet);
            if (sourceSha256 != null) result.put("sourceSha256", sourceSha256);
            if (outputPath() != null) result.put("outputPath", outputPath());
            if (outputSha256 != null) result.put("outputSha256", outputSha256);
            if (formulaSha256 != null) result.put("formulaSha256", formulaSha256);
            return Collections.unmodifiableMap(result);
        }
    }
}
