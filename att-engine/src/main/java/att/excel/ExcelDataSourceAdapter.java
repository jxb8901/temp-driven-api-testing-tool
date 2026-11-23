package att.excel;

import att.config.ColumnSpecParser;
import att.config.DataColumnConfig;
import att.config.YamlSupport;
import att.core.IdentifierValidator;
import att.core.ValueNormalizer;
import att.resource.PackageResourceResolver;
import att.testcase.CaseSourceProvenance;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Read-only, bounded adapter from one native Test Case Excel Data Source to resolved source rows. */
public final class ExcelDataSourceAdapter {
    public static final long MAX_WORKBOOK_BYTES = 32L * 1024L * 1024L;
    public static final int MAX_WORKBOOK_ZIP_ENTRIES = 256;
    public static final long MAX_WORKBOOK_UNCOMPRESSED_ENTRY_BYTES = 32L * 1024L * 1024L;
    public static final long MAX_WORKBOOK_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L;
    public static final int MAX_HEADER_ROWS = 86400;
    /** Maximum rectangular header cells visited while projecting a native Excel source. */
    public static final long MAX_HEADER_CELLS = 1000000L;
    public static final int MAX_WORKSHEET_ROWS = 100000;
    private static final int MAX_YAML_VALUE_DEPTH = 64;
    private static final int MAX_YAML_VALUE_NODES = 100000;

    private static final Pattern CASE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern INPUT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]{0,127}$");
    private static final String SUGGESTION = "Correct the Excel Data Source mapping and retry Resolution; keep identifiers as literal Excel Text values.";

    private ExcelDataSourceAdapter() { }

    /**
     * Loads one bounded XLSX snapshot. The workbook bytes are captured once and both parsed and
     * fingerprinted from that immutable byte array so provenance always names the consumed source.
     */
    public static List<Record> load(PackageResourceResolver resources, String definitionReference,
            String authoredPath, String sheetName, int headerRows, String caseIdHeader, String tagsHeader,
            String dataColumnSpec, int maxRecords, BooleanSupplier cancelled) throws Exception {
        if (resources == null) throw new IllegalArgumentException("Package resources are required");
        String workbookReference = authoredPath == null ? "<excel>" : authoredPath;
        Path workbookPath = resolveWorkbookPath(resources, definitionReference, authoredPath);
        workbookReference = resources.fromInternalPath(workbookPath, PackageResourceResolver.Kind.FILE).logicalName();
        byte[] bytes;
        try { bytes = readBounded(workbookPath, workbookReference); }
        catch (DiagnosticException failure) { throw failure; }
        catch (Exception failure) {
            throw invalid(workbookReference, "dataSource.path", "Unable to read Excel Data Source workbook",
                    null, null, null, failure);
        }
        String workbookRevision = sha256(bytes);
        preflightWorkbookArchive(bytes, workbookReference);

        List<DataColumnConfig> dataColumns;
        try {
            dataColumns = ColumnSpecParser.dataColumns(dataColumnSpec);
            for (DataColumnConfig column : dataColumns) {
                if (!INPUT_NAME.matcher(column.key()).matches())
                    throw new IllegalArgumentException("Data column alias is not a valid att-testcase/v1 input name: " + column.key());
            }
        } catch (RuntimeException failure) {
            throw invalid(definitionReference, "dataSource.dataColumns",
                    "Excel Data Source dataColumns is invalid: " + safeMessage(failure), null, null, null, failure);
        }
        if (headerRows < 1 || headerRows > MAX_HEADER_ROWS)
            throw invalid(definitionReference, "dataSource.headerRows",
                    "Excel Data Source headerRows must be between 1 and " + MAX_HEADER_ROWS, null, null, null, null);
        if (maxRecords < 1)
            throw invalid(definitionReference, "dataSource", "Excel Data Source record limit must be positive", null, null, null, null);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw invalid(workbookReference, "dataSource.sheet",
                    "Configured Excel sheet does not exist: " + sheetName, sheetName, null, null, null);
            int lastRowIndex = sheet.getLastRowNum();
            if ((long) lastRowIndex + 1L > MAX_WORKSHEET_ROWS)
                throw invalid(workbookReference, "dataSource", "Excel worksheet exceeds the "
                        + MAX_WORKSHEET_ROWS + " physical row limit", sheetName,
                        Integer.valueOf(MAX_WORKSHEET_ROWS + 1), null, null);
            if (lastRowIndex < headerRows - 1)
                throw invalid(workbookReference, "dataSource.headerRows",
                        "Configured Excel header rows do not exist", sheetName, null, null, null);

            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Map<String,Integer> columns;
            int maxHeaderColumns = ExcelHeaderResolver.maxColumns(sheet, headerRows);
            long headerCells = (long) headerRows * (long) maxHeaderColumns;
            if (headerCells > MAX_HEADER_CELLS)
                throw invalid(workbookReference, "dataSource.headerRows",
                        "Excel header range exceeds the " + MAX_HEADER_CELLS
                                + " cell scan budget (" + headerRows + " rows by " + maxHeaderColumns + " columns)",
                        sheetName, Integer.valueOf(headerRows), Integer.valueOf(maxHeaderColumns), null);
            try { columns = ExcelHeaderResolver.columns(sheet, headerRows, formatter, maxHeaderColumns); }
            catch (ExcelHeaderResolver.DuplicateHeaderException failure) {
                String detail = "Excel headers are invalid: " + safeMessage(failure)
                        + " (first occurrence at row " + failure.firstRow() + ", column " + failure.firstColumn()
                        + "; duplicate at row " + failure.duplicateRow() + ", column " + failure.duplicateColumn() + ")";
                throw invalid(workbookReference, "dataSource", detail, sheetName,
                        Integer.valueOf(failure.duplicateRow()), Integer.valueOf(failure.duplicateColumn()), failure);
            }
            catch (RuntimeException failure) {
                throw invalid(workbookReference, "dataSource", "Excel headers are invalid: " + safeMessage(failure),
                        sheetName, null, null, failure);
            }
            Integer caseIdColumn = requireColumn(columns, caseIdHeader, "dataSource.caseId", workbookReference, sheetName, headerRows);
            Integer tagsColumn = tagsHeader == null ? null
                    : requireColumn(columns, tagsHeader, "dataSource.tags", workbookReference, sheetName, headerRows);
            Map<String,Integer> dataColumnIndices = new LinkedHashMap<String,Integer>();
            for (DataColumnConfig column : dataColumns)
                dataColumnIndices.put(column.key(), requireColumn(columns, column.columnName(),
                        "dataSource.dataColumns." + column.key(), workbookReference, sheetName, headerRows));

            Set<Integer> configuredColumns = new LinkedHashSet<Integer>();
            configuredColumns.add(caseIdColumn);
            if (tagsColumn != null) configuredColumns.add(tagsColumn);
            configuredColumns.addAll(dataColumnIndices.values());
            rejectFormulaHeaders(sheet, configuredColumns, headerRows, workbookReference, sheetName);
            rejectMergedDataCells(sheet, configuredColumns, headerRows, workbookReference, sheetName);

            Map<String,Object> provenanceMetadata = new LinkedHashMap<String,Object>();
            provenanceMetadata.put("workbookSha256", workbookRevision);
            provenanceMetadata.put("headerRows", Integer.valueOf(headerRows));
            provenanceMetadata.put("columns", columnMetadata(caseIdColumn, tagsColumn, dataColumnIndices));

            List<Record> records = new ArrayList<Record>();
            Iterator<Row> rowIterator = sheet.rowIterator();
            while (rowIterator.hasNext()) {
                checkCancelled(cancelled);
                Row row = rowIterator.next();
                int rowIndex = row.getRowNum();
                if (rowIndex < headerRows) continue;
                rejectFormulaCells(row, configuredColumns, workbookReference, sheetName);
                rejectErrorCells(row, configuredColumns, workbookReference, sheetName);
                if (blankRow(row, configuredColumns, formatter)) continue;
                if (records.size() >= maxRecords)
                    throw invalid(workbookReference, "dataSource", "Excel Data Source exceeds the configured record limit",
                            sheetName, Integer.valueOf(rowIndex + 1), null, null);

                String recordId = caseId(row, caseIdColumn, workbookReference, sheetName);
                List<String> tags = tagsColumn == null ? Collections.<String>emptyList()
                        : tags(value(row, tagsColumn, formatter));
                Map<String,Object> data = new LinkedHashMap<String,Object>();
                for (DataColumnConfig column : dataColumns) {
                    Integer columnIndex = dataColumnIndices.get(column.key());
                    String cellText = value(row, columnIndex, formatter);
                    Object cellValue = cellText;
                    if (column.yaml() && !cellText.isEmpty()) {
                        try {
                            cellValue = normalizeYaml(YamlSupport.parse(cellText));
                            // Match native JSON-compatible values and reject date/other Java objects here,
                            // while the originating workbook row and cell are still available.
                            validateYamlValue(cellValue);
                        } catch (RuntimeException failure) {
                            throw invalid(workbookReference, "dataSource.dataColumns." + column.key(),
                                    "Excel (yaml) cell does not contain a supported safe YAML value: " + safeMessage(failure),
                                    sheetName, Integer.valueOf(rowIndex + 1), Integer.valueOf(columnIndex.intValue() + 1), failure);
                        }
                    }
                    data.put(column.key(), cellValue);
                }

                int recordIndex = records.size();
                CaseSourceProvenance provenance = new CaseSourceProvenance("excel", workbookReference,
                        sheetName, Integer.valueOf(rowIndex + 1), Integer.valueOf(recordIndex), recordId,
                        provenanceMetadata);
                records.add(new Record(recordId, tags, data, recordIndex, provenance));
            }
            if (records.isEmpty()) throw invalid(workbookReference, "dataSource", "Excel Data Source has no nonblank Case rows",
                    sheetName, null, null, null);
            return Collections.unmodifiableList(records);
        } catch (DiagnosticException failure) {
            throw failure;
        } catch (java.util.concurrent.CancellationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(workbookReference, "dataSource", "Unable to read Excel Data Source: " + safeMessage(failure),
                    sheetName, null, null, failure);
        }
    }

    /** Resolves an Excel Data Source workbook using the same containment and symlink rules as the adapter. */
    public static Path resolveWorkbookPath(PackageResourceResolver resources, String definitionReference, String authoredPath) {
        if (authoredPath == null || authoredPath.trim().isEmpty())
            throw invalid(definitionReference, "dataSource.path", "Excel Data Source path is required", null, null, null, null);
        try {
            Path authored = java.nio.file.Paths.get(authoredPath.replace('/', java.io.File.separatorChar));
            if (authored.isAbsolute() || authoredPath.indexOf('\\') >= 0 || authoredPath.matches("^[A-Za-z]:.*"))
                throw new IllegalArgumentException("Excel Data Source path must be package-relative");
            Path logical = resources.packageRoot().resolve(authored).normalize();
            if (!logical.startsWith(resources.packageRoot()))
                throw new IllegalArgumentException("Excel Data Source path escapes the Package");
            Path cursor = resources.packageRoot();
            for (Path segment : resources.packageRoot().relativize(logical)) {
                cursor = cursor.resolve(segment);
                if (Files.isSymbolicLink(cursor))
                    throw new IllegalArgumentException("Excel Data Source path contains a symbolic link");
            }
            PackageResourceResolver.PackageResource resource = resources.resolvePackageRelative(authoredPath,
                    PackageResourceResolver.Kind.FILE);
            if (!resource.canonicalPath().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                throw new IllegalArgumentException("Excel Data Source must be an .xlsx workbook");
            return resource.canonicalPath();
        } catch (DiagnosticException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid(definitionReference, "dataSource.path", "Excel Data Source path is unavailable or unsafe",
                    null, null, null, failure);
        }
    }

    private static byte[] readBounded(Path path, String workbookReference) throws Exception {
        long size = Files.size(path);
        if (size > MAX_WORKBOOK_BYTES)
            throw invalid(workbookReference, "dataSource.path", "Excel Data Source exceeds the 32 MiB workbook limit",
                    null, null, null, null);
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(size, MAX_WORKBOOK_BYTES))) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0L;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_WORKBOOK_BYTES)
                    throw invalid(workbookReference, "dataSource.path", "Excel Data Source exceeds the 32 MiB workbook limit",
                            null, null, null, null);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /** Checks expanded XLSX ZIP content before POI materializes its workbook object graph. */
    private static void preflightWorkbookArchive(byte[] bytes, String workbookReference) {
        int entries = 0;
        long totalExpanded = 0L;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > MAX_WORKBOOK_ZIP_ENTRIES)
                    throw invalid(workbookReference, "dataSource.path", "Excel workbook exceeds the "
                            + MAX_WORKBOOK_ZIP_ENTRIES + " ZIP entry limit", null, null, null, null);
                long entryExpanded = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    entryExpanded += read;
                    totalExpanded += read;
                    if (entryExpanded > MAX_WORKBOOK_UNCOMPRESSED_ENTRY_BYTES)
                        throw invalid(workbookReference, "dataSource.path", "Excel workbook ZIP entry exceeds the "
                                + "32 MiB uncompressed entry limit", null, null, null, null);
                    if (totalExpanded > MAX_WORKBOOK_UNCOMPRESSED_BYTES)
                        throw invalid(workbookReference, "dataSource.path", "Excel workbook exceeds the "
                                + "64 MiB total uncompressed ZIP limit", null, null, null, null);
                }
                input.closeEntry();
            }
            if (entries == 0)
                throw invalid(workbookReference, "dataSource.path", "Excel Data Source is not a valid XLSX ZIP archive",
                        null, null, null, null);
        } catch (DiagnosticException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(workbookReference, "dataSource.path", "Excel Data Source is not a valid or supported XLSX archive",
                    null, null, null, failure);
        }
    }

    private static Integer requireColumn(Map<String,Integer> columns, String name, String field,
            String workbookReference, String sheet, int headerRows) {
        if (name == null || name.trim().isEmpty() || !columns.containsKey(name))
            throw invalid(workbookReference, field, "Configured Excel header is missing: " + name,
                    sheet, Integer.valueOf(headerRows), null, null);
        return columns.get(name);
    }

    private static void rejectFormulaHeaders(Sheet sheet, Set<Integer> columns, int headerRows,
            String file, String sheetName) {
        for (int rowIndex = 0; rowIndex < headerRows; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (Integer column : columns) {
                Cell cell = row.getCell(column.intValue());
                if (cell != null && cell.getCellType() == CellType.FORMULA)
                    throw invalid(file, "dataSource", "Formula cells are not supported in configured Excel headers",
                            sheetName, Integer.valueOf(rowIndex + 1), Integer.valueOf(column.intValue() + 1), null);
                if (cell != null && cell.getCellType() == CellType.ERROR)
                    throw invalid(file, "dataSource", "Excel error cells are not supported in configured headers",
                            sheetName, Integer.valueOf(rowIndex + 1), Integer.valueOf(column.intValue() + 1), null);
            }
        }
    }

    private static void rejectMergedDataCells(Sheet sheet, Set<Integer> columns, int headerRows,
            String file, String sheetName) {
        for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            if (region.getLastRow() < headerRows) continue;
            for (Integer column : columns) if (column.intValue() >= region.getFirstColumn()
                    && column.intValue() <= region.getLastColumn())
                throw invalid(file, "dataSource", "Merged Excel cells intersect a configured Data Source column: " + region.formatAsString(),
                        sheetName, Integer.valueOf(region.getFirstRow() + 1), Integer.valueOf(column.intValue() + 1), null);
        }
    }

    private static void rejectFormulaCells(Row row, Set<Integer> columns, String file, String sheetName) {
        for (Integer column : columns) {
            Cell cell = row.getCell(column.intValue());
            if (cell != null && cell.getCellType() == CellType.FORMULA)
                throw invalid(file, "dataSource", "Formula cells are not supported in configured Excel Data Source columns",
                        sheetName, Integer.valueOf(row.getRowNum() + 1), Integer.valueOf(column.intValue() + 1), null);
        }
    }

    private static void rejectErrorCells(Row row, Set<Integer> columns, String file, String sheetName) {
        for (Integer column : columns) {
            Cell cell = row.getCell(column.intValue());
            if (cell != null && cell.getCellType() == CellType.ERROR)
                throw invalid(file, "dataSource", "Excel error cells are not supported in configured Data Source columns",
                        sheetName, Integer.valueOf(row.getRowNum() + 1), Integer.valueOf(column.intValue() + 1), null);
        }
    }

    private static boolean blankRow(Row row, Set<Integer> columns, DataFormatter formatter) {
        for (Integer column : columns)
            if (!value(row, column, formatter).isEmpty()) return false;
        return true;
    }

    private static String caseId(Row row, Integer column, String file, String sheet) {
        Cell cell = row.getCell(column.intValue());
        if (cell == null || cell.getCellType() == CellType.BLANK)
            throw invalid(file, "dataSource.caseId", "Excel Case ID cell is blank", sheet,
                    Integer.valueOf(row.getRowNum() + 1), Integer.valueOf(column.intValue() + 1), null);
        if (cell.getCellType() != CellType.STRING)
            throw invalid(file, "dataSource.caseId", "Excel Case ID must be a literal string cell",
                    sheet, Integer.valueOf(row.getRowNum() + 1), Integer.valueOf(column.intValue() + 1), null);
        String value = cell.getStringCellValue();
        if (ValueNormalizer.normalize(value).isEmpty())
            throw invalid(file, "dataSource.caseId", "Excel Case ID cell is blank after blank-marker normalization",
                    sheet, Integer.valueOf(row.getRowNum() + 1), Integer.valueOf(column.intValue() + 1), null);
        if (!value.equals(value.trim()) || !CASE_ID.matcher(value).matches())
            throw invalid(file, "dataSource.caseId", "Excel Case ID must match [A-Za-z0-9_-]{1,128} without surrounding whitespace",
                    sheet, Integer.valueOf(row.getRowNum() + 1), Integer.valueOf(column.intValue() + 1), null);
        try { IdentifierValidator.caseId("excel", value); }
        catch (RuntimeException failure) {
            throw invalid(file, "dataSource.caseId", "Excel Case ID is not a path-safe identifier",
                    sheet, Integer.valueOf(row.getRowNum() + 1), Integer.valueOf(column.intValue() + 1), failure);
        }
        return value;
    }

    private static String value(Row row, Integer column, DataFormatter formatter) {
        if (column == null) return "";
        Cell cell = row.getCell(column.intValue());
        return ValueNormalizer.normalize(cell == null ? "" : formatter.formatCellValue(cell));
    }

    private static List<String> tags(String text) {
        if (text.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        for (String value : text.split(",")) {
            String normalized = ValueNormalizer.normalize(value);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    private static Map<String,Object> columnMetadata(Integer caseIdColumn, Integer tagsColumn,
            Map<String,Integer> dataColumns) {
        Map<String,Object> columns = new LinkedHashMap<String,Object>();
        columns.put("caseId", Integer.valueOf(caseIdColumn.intValue() + 1));
        if (tagsColumn != null) columns.put("tags", Integer.valueOf(tagsColumn.intValue() + 1));
        Map<String,Object> data = new LinkedHashMap<String,Object>();
        for (Map.Entry<String,Integer> entry : dataColumns.entrySet())
            data.put(entry.getKey(), Integer.valueOf(entry.getValue().intValue() + 1));
        columns.put("data", data);
        return columns;
    }

    private static Object normalizeYaml(Object value) {
        return normalizeYaml(value, "$", 0, new YamlValueBudget(), new IdentityHashMap<Object,Boolean>());
    }

    private static Object normalizeYaml(Object value, String field, int depth, YamlValueBudget budget,
            IdentityHashMap<Object,Boolean> active) {
        budget.consume(field, depth);
        if (value == null || value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) return value;
        if (value instanceof Float) {
            float number = ((Float) value).floatValue();
            if (Float.isInfinite(number) || Float.isNaN(number))
                throw new IllegalArgumentException(field + " contains a non-finite number");
            return value;
        }
        if (value instanceof Double) {
            double number = ((Double) value).doubleValue();
            if (Double.isInfinite(number) || Double.isNaN(number))
                throw new IllegalArgumentException(field + " contains a non-finite number");
            return value;
        }
        if (value instanceof String) return ValueNormalizer.normalize((String) value);
        if (active.put(value, Boolean.TRUE) != null)
            throw new IllegalArgumentException(field + " contains a recursive YAML alias");
        try {
            if (value instanceof Map<?,?>) {
                Map<String,Object> result = new LinkedHashMap<String,Object>();
                for (Map.Entry<?,?> entry : ((Map<?,?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String))
                        throw new IllegalArgumentException(field + " mapping keys must be strings");
                    String key = (String) entry.getKey();
                    result.put(key, normalizeYaml(entry.getValue(), field + "." + key, depth + 1, budget, active));
                }
                return result;
            }
            if (value instanceof Iterable<?>) {
                List<Object> result = new ArrayList<Object>();
                int index = 0;
                for (Object item : (Iterable<?>) value)
                    result.add(normalizeYaml(item, field + "[" + index++ + "]", depth + 1, budget, active));
                return result;
            }
        } finally {
            active.remove(value);
        }
        throw new IllegalArgumentException(field + " contains an unsupported YAML value type "
                + value.getClass().getSimpleName());
    }

    private static void validateYamlValue(Object value) {
        validateYamlValue(value, "$", 0, new YamlValueBudget(), new IdentityHashMap<Object,Boolean>());
    }

    private static void validateYamlValue(Object value, String field, int depth, YamlValueBudget budget,
            IdentityHashMap<Object,Boolean> active) {
        budget.consume(field, depth);
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) return;
        if (value instanceof Float) {
            float number = ((Float) value).floatValue();
            if (Float.isInfinite(number) || Float.isNaN(number))
                throw new IllegalArgumentException(field + " contains a non-finite number");
            return;
        }
        if (value instanceof Double) {
            double number = ((Double) value).doubleValue();
            if (Double.isInfinite(number) || Double.isNaN(number))
                throw new IllegalArgumentException(field + " contains a non-finite number");
            return;
        }
        if (active.put(value, Boolean.TRUE) != null)
            throw new IllegalArgumentException(field + " contains a recursive YAML alias");
        try {
            if (value instanceof Map<?,?>) {
                for (Map.Entry<?,?> entry : ((Map<?,?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String))
                        throw new IllegalArgumentException(field + " mapping keys must be strings");
                    validateYamlValue(entry.getValue(), field + "." + entry.getKey(), depth + 1, budget, active);
                }
                return;
            }
            if (value instanceof Iterable<?>) {
                int index = 0;
                for (Object item : (Iterable<?>) value)
                    validateYamlValue(item, field + "[" + index++ + "]", depth + 1, budget, active);
                return;
            }
        } finally {
            active.remove(value);
        }
        throw new IllegalArgumentException(field + " contains an unsupported YAML value type "
                + value.getClass().getSimpleName());
    }

    /** Keeps YAML cells within the same value-graph limits as native Test Case inputs. */
    private static final class YamlValueBudget {
        private int remaining = MAX_YAML_VALUE_NODES;

        void consume(String field, int depth) {
            if (depth > MAX_YAML_VALUE_DEPTH)
                throw new IllegalArgumentException(field + " exceeds the maximum nesting depth of "
                        + MAX_YAML_VALUE_DEPTH);
            if (--remaining < 0)
                throw new IllegalArgumentException(field + " exceeds the maximum value count of "
                        + MAX_YAML_VALUE_NODES);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean()))
            throw new java.util.concurrent.CancellationException("Test Case Resolution was cancelled");
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.trim().isEmpty() ? failure == null ? "invalid value" : failure.getClass().getSimpleName() : message;
    }

    private static DiagnosticException invalid(String file, String field, String detail, String sheet,
            Integer row, Integer column, Throwable cause) {
        return new DiagnosticException(DiagnosticCodes.TESTCASE_INVALID, "Excel Data Source is invalid", detail,
                file, field, sheet, row, column, null, null, SUGGESTION, cause);
    }

    public static final class Record {
        private final String recordId;
        private final List<String> tags;
        private final Map<String,Object> data;
        private final int index;
        private final CaseSourceProvenance provenance;

        private Record(String recordId, List<String> tags, Map<String,Object> data, int index,
                CaseSourceProvenance provenance) {
            this.recordId = recordId;
            this.tags = Collections.unmodifiableList(new ArrayList<String>(tags));
            this.data = Collections.unmodifiableMap(new LinkedHashMap<String,Object>(data));
            this.index = index;
            this.provenance = provenance;
        }

        public String recordId() { return recordId; }
        public List<String> tags() { return tags; }
        public Map<String,Object> data() { return data; }
        public int index() { return index; }
        public CaseSourceProvenance provenance() { return provenance; }
    }
}
