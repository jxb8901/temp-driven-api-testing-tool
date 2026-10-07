/* Author: Jeffrey + ChatGPT */
package att.excel;

import att.config.DataColumnConfig;
import att.config.FrameworkConfig;
import att.config.SheetGroupConfig;
import att.config.StageConfig;
import att.config.YamlSupport;
import att.core.StageCaseData;
import att.core.TestCase;
import att.core.IdentifierValidator;
import att.core.ValueNormalizer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads all V2 testcase groups from one workbook. */
public final class ExcelTestSuiteLoader {
    private final DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
    private final FrameworkConfig config;

    public ExcelTestSuiteLoader(FrameworkConfig config) { this.config = config; }

    public List<TestCase> load(Path suitePath) throws IOException {
        if (!config.suiteResolved()) throw new IllegalArgumentException("Suite configuration has not been resolved");
        try (InputStream input = Files.newInputStream(suitePath); Workbook workbook = WorkbookFactory.create(input)) {
            List<TestCase> result = new ArrayList<TestCase>();
            Set<String> ids = new LinkedHashSet<String>();
            for (SheetGroupConfig group : config.sheetGroups()) loadGroup(workbook, suitePath, group, result, ids);
            return result;
        } catch (IOException e) {
            throw e;
        } catch (att.validation.DiagnosticException e) {
            throw e;
        } catch (Exception e) {
            throw caseError("Unable to load testcase workbook", e.getMessage(), suitePath, "workbook",
                    null, null, null, "Verify that the file is a readable .xlsx workbook and matches its sidecar configuration.", e);
        }
    }

    private void loadGroup(Workbook workbook, Path suitePath, SheetGroupConfig group, List<TestCase> output, Set<String> ids) {
        Sheet sheet = workbook.getSheet(group.sheetName());
        if (sheet == null) throw caseError("Configured Sheet does not exist", "groupId=" + group.id() + ", configuredSheet='" + group.sheetName() + "'", suitePath,
                "excel.sheet", group.sheetName(), null, null, "Correct the sidecar sheet mapping or add the configured Sheet to the workbook.", null);
        if (sheet.getLastRowNum() < config.headerRows() - 1) throw caseError("Configured header rows do not exist", "headerRows=" + config.headerRows(), suitePath,
                "excel.headerRows", group.sheetName(), null, null, "Reduce excel.headerRows or add the required header rows.", null);
        Map<String, Integer> columns = ExcelHeaderResolver.columns(sheet, config.headerRows(), formatter);
        requireColumn(columns, config.caseIdColumn(), "excel.caseId", group, suitePath);
        requireColumn(columns, config.tagsColumn(), "excel.tags", group, suitePath);
        for (DataColumnConfig column : config.dataColumns()) requireColumn(columns, column.columnName(), "excel.dataColumns." + column.key(), group, suitePath);
        for (StageConfig stage : config.stages()) {
            requireColumn(columns, stage.template(), "stages." + stage.key() + ".template", group, suitePath);
            for (DataColumnConfig column : stage.dataColumns()) requireColumn(columns, column.columnName(), "stages." + stage.key() + ".dataColumns." + column.key(), group, suitePath);
        }
        Set<Integer> testcaseColumns = testcaseColumns(columns);
        rejectMergedDataCells(sheet, testcaseColumns, suitePath, group);
        for (int rowIndex = config.headerRows(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || blankRow(row, columns)) continue;
            rejectFormulaCells(row, testcaseColumns, suitePath, group);
            TestCase testCase = toCase(suitePath, group, row, columns);
            if (!ids.add(testCase.caseId())) throw caseError("Duplicate full Case ID '" + testCase.caseId() + "'", "The same workbookId.groupId.rowCaseId was already loaded.", suitePath,
                    "excel.caseId", group.sheetName(), rowIndex + 1, columns.get(config.caseIdColumn()) + 1,
                    "Make the row Case ID unique within this workbook and group.", null);
            output.add(testCase);
        }
    }

    private TestCase toCase(Path suitePath, SheetGroupConfig group, Row row, Map<String, Integer> columns) {
        String rowCaseId = value(row, columns.get(config.caseIdColumn()));
        if (rowCaseId.isEmpty()) throw caseError("Case ID is blank", "The configured Case ID cell normalizes to blank.", suitePath,
                "excel.caseId", group.sheetName(), row.getRowNum() + 1, columns.get(config.caseIdColumn()) + 1,
                "Enter a non-blank, path-safe row Case ID in this cell.", null);
        List<String> tags = tags(value(row, columns.get(config.tagsColumn())));
        Map<String, Object> caseData = loadColumns(row, columns, config.dataColumns());
        caseData.put("workbook", suitePath.getFileName().toString());

        Map<String, StageCaseData> stages = new LinkedHashMap<String, StageCaseData>();
        for (StageConfig stage : config.stages()) {
            String cell = value(row, columns.get(stage.template()));
            if (cell.isEmpty()) {
                if (stage.required()) throw stageError("Required stage selector is blank", "stage=" + stage.key(), suitePath,
                        "stages." + stage.key() + ".template", group.sheetName(), row.getRowNum() + 1, columns.get(stage.template()) + 1,
                        "Enter a template symbolic name/path or mark the stage as optional.", null);
                continue;
            }
            Map<String, Object> stageValues;
            try { stageValues = yamlMap(cell, "stage template cell " + group.sheetName() + "!" + (row.getRowNum() + 1)); }
            catch (Exception e) { throw stageError("Invalid stage selector YAML", e.getMessage(), suitePath,
                    "stages." + stage.key() + ".template", group.sheetName(), row.getRowNum() + 1, columns.get(stage.template()) + 1,
                    "Use a scalar template name/path or a YAML map containing a non-blank name.", e); }
            for (String reserved : new String[]{"key", "status", "startedAt", "durationMs", "TEMPLATE"}) {
                if (stageValues.containsKey(reserved)) throw stageError("Stage selector uses reserved key '" + reserved + "'", "stage=" + stage.key(), suitePath,
                        "stages." + stage.key() + ".template", group.sheetName(), row.getRowNum() + 1, columns.get(stage.template()) + 1,
                        "Remove the framework-owned key from the selector map.", null);
            }
            Object name = stageValues.get("name");
            if (name == null || ValueNormalizer.normalize(String.valueOf(name)).isEmpty()) throw stageError("Stage selector requires a non-blank name", "stage=" + stage.key(), suitePath,
                    "stages." + stage.key() + ".template.name", group.sheetName(), row.getRowNum() + 1, columns.get(stage.template()) + 1,
                    "Add name: <template symbolic name or relative path>.", null);
            Map<String, Object> privateData = loadColumns(row, columns, stage.dataColumns());
            for (Map.Entry<String, Object> entry : privateData.entrySet()) {
                if (stageValues.containsKey(entry.getKey())) throw stageError("Duplicate stage data key '" + entry.getKey() + "'", "stage=" + stage.key(), suitePath,
                        "stages." + stage.key() + ".dataColumns." + entry.getKey(), group.sheetName(), row.getRowNum() + 1, null,
                        "Keep the key in either the selector map or the configured stage data column, not both.", null);
                stageValues.put(entry.getKey(), entry.getValue());
            }
            stages.put(stage.key(), new StageCaseData(stage.key(), ValueNormalizer.normalize(String.valueOf(name)), stageValues));
        }
        try { IdentifierValidator.caseId(config.workbookId(), group.id(), rowCaseId); }
        catch (Exception e) { throw caseError("Invalid full Case ID", "workbookId=" + config.workbookId() + ", groupId=" + group.id() + ", rowCaseId='" + rowCaseId + "': " + e.getMessage(), suitePath,
                "excel.caseId", group.sheetName(), row.getRowNum() + 1, columns.get(config.caseIdColumn()) + 1,
                "Use an ID without dots, path separators, control characters, trailing dots/spaces, or reserved platform names.", e); }
        return new TestCase(row.getRowNum() + 1, config.workbookId(), group.id(), group.sheetName(), rowCaseId, tags, caseData, stages, null);
    }

    private Map<String, Object> loadColumns(Row row, Map<String, Integer> columns, List<DataColumnConfig> configured) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (DataColumnConfig column : configured) {
            String text = value(row, columns.get(column.columnName()));
            result.put(column.key(), column.yaml() && !text.isEmpty() ? yaml(text) : text);
        }
        return result;
    }

    private Object yaml(String text) {
        Object loaded = YamlSupport.parser().load(text);
        return loaded == null ? "" : loaded;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yamlMap(String text, String owner) {
        Object loaded = yaml(text);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        // A scalar selector is shorthand for {name: <selector>}. It preserves
        // the ordinary name-first, path-second template resolution behavior.
        if (loaded instanceof String) {
            String selector = ValueNormalizer.normalize((String) loaded);
            if (selector.isEmpty()) throw new IllegalArgumentException(owner + " must not be blank");
            result.put("name", selector);
            return result;
        }
        if (!(loaded instanceof Map)) throw new IllegalArgumentException(owner + " must be a YAML map or scalar template name/path");
        for (Map.Entry<?, ?> e : ((Map<?, ?>) loaded).entrySet()) result.put(String.valueOf(e.getKey()), normalizeYaml(e.getValue()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeYaml(Object value) {
        if (value instanceof String) return ValueNormalizer.normalize((String) value);
        if (value instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) map.put(String.valueOf(e.getKey()), normalizeYaml(e.getValue()));
            return map;
        }
        if (value instanceof Iterable) {
            List<Object> list = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) list.add(normalizeYaml(item));
            return list;
        }
        return value;
    }

    private void requireColumn(Map<String, Integer> columns, String name, String field, SheetGroupConfig group, Path suitePath) {
        if (name == null || name.trim().isEmpty() || !columns.containsKey(name)) throw caseError("Required Excel column is missing",
                "field=" + field + ", expectedHeader='" + name + "', availableHeaders=" + columns.keySet(), suitePath,
                field, group.sheetName(), config.headerRows(), null,
                "Correct the sidecar header mapping or add the case-sensitive header; spaces, tabs, line breaks, and other Unicode whitespace are ignored during matching.", null);
    }

    private Set<Integer> testcaseColumns(Map<String, Integer> columns) {
        Set<Integer> result = new LinkedHashSet<Integer>();
        result.add(columns.get(config.caseIdColumn()));
        result.add(columns.get(config.tagsColumn()));
        for (DataColumnConfig column : config.dataColumns()) result.add(columns.get(column.columnName()));
        for (StageConfig stage : config.stages()) {
            result.add(columns.get(stage.template()));
            for (DataColumnConfig column : stage.dataColumns()) result.add(columns.get(column.columnName()));
        }
        result.remove(null);
        return result;
    }

    private void rejectFormulaCells(Row row, Set<Integer> testcaseColumns, Path suitePath, SheetGroupConfig group) {
        for (Integer column : testcaseColumns) {
            Cell cell = row.getCell(column);
            if (cell != null && cell.getCellType() == CellType.FORMULA) throw caseError(
                    "Formula cells are not supported in testcase data",
                    "Formula values can differ from cached Excel results and cannot produce a reproducible snapshot.", suitePath,
                    "snapshot", group.sheetName(), row.getRowNum() + 1, column + 1,
                    "Replace the formula with a literal value; format identifiers and leading-zero values as Excel Text.", null);
        }
    }

    private void rejectMergedDataCells(Sheet sheet, Set<Integer> testcaseColumns, Path suitePath, SheetGroupConfig group) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress region = sheet.getMergedRegion(i);
            if (region.getLastRow() < config.headerRows()) continue;
            for (Integer column : testcaseColumns) {
                if (column >= region.getFirstColumn() && column <= region.getLastColumn()) throw caseError(
                        "Merged cells are not supported in testcase data",
                        "Merged region " + region.formatAsString() + " intersects an ATT testcase column.", suitePath,
                        "snapshot", group.sheetName(), region.getFirstRow() + 1, column + 1,
                        "Unmerge testcase data cells; merged presentation cells are allowed only within configured header rows.", null);
            }
        }
    }

    private static att.validation.DiagnosticException caseError(String summary, String detail, Path file, String field,
                                                                String sheet, Integer row, Integer column,
                                                                String suggestion, Throwable cause) {
        return new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TESTCASE_INVALID, summary, detail,
                file == null ? null : file.toString(), field, sheet, row, column, null, null, suggestion, cause);
    }

    private static att.validation.DiagnosticException stageError(String summary, String detail, Path file, String field,
                                                                 String sheet, Integer row, Integer column,
                                                                 String suggestion, Throwable cause) {
        return new att.validation.DiagnosticException(att.validation.DiagnosticCodes.STAGE_INVALID, summary, detail,
                file == null ? null : file.toString(), field, sheet, row, column, null, null, suggestion, cause);
    }

    private boolean blankRow(Row row, Map<String, Integer> columns) {
        for (Integer index : columns.values()) if (!value(row, index).isEmpty()) return false;
        return true;
    }

    private String value(Row row, Integer index) {
        if (index == null) return "";
        Cell cell = row.getCell(index);
        return ValueNormalizer.normalize(cell == null ? "" : formatter.formatCellValue(cell));
    }

    private List<String> tags(String value) {
        if (value.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        for (String tag : value.split(",")) {
            String normalized = ValueNormalizer.normalize(tag);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }
}
