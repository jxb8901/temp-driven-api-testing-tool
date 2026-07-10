/* Author: Jeffrey + ChatGPT */
package com.company.apitest.excel;

import com.company.apitest.config.DataColumnConfig;
import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.config.SheetGroupConfig;
import com.company.apitest.config.StageConfig;
import com.company.apitest.config.YamlSupport;
import com.company.apitest.core.StageCaseData;
import com.company.apitest.core.TestCase;
import com.company.apitest.core.ValueNormalizer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
    private final DataFormatter formatter = new DataFormatter();
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
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to load V2 suite " + suitePath + ": " + e.getMessage(), e);
        }
    }

    private void loadGroup(Workbook workbook, Path suitePath, SheetGroupConfig group, List<TestCase> output, Set<String> ids) {
        Sheet sheet = workbook.getSheet(group.sheetName());
        if (sheet == null) throw new IllegalArgumentException("Missing configured sheet '" + group.sheetName() + "' for group '" + group.id() + "'");
        Row header = sheet.getRow(0);
        if (header == null) throw new IllegalArgumentException("Missing header row in sheet: " + group.sheetName());
        Map<String, Integer> columns = columns(header);
        requireColumn(columns, config.caseIdColumn(), "excel.caseId", group);
        requireColumn(columns, config.tagsColumn(), "excel.tags", group);
        for (DataColumnConfig column : config.dataColumns()) requireColumn(columns, column.columnName(), "excel.dataColumns." + column.key(), group);
        for (StageConfig stage : config.stages()) {
            requireColumn(columns, stage.template(), "stages." + stage.key() + ".template", group);
            for (DataColumnConfig column : stage.dataColumns()) requireColumn(columns, column.columnName(), "stages." + stage.key() + ".dataColumns." + column.key(), group);
        }
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || blankRow(row, columns)) continue;
            TestCase testCase = toCase(suitePath, group, row, columns);
            if (!ids.add(testCase.caseId())) throw new IllegalArgumentException("Duplicate full Case ID " + testCase.caseId() + " at " + group.sheetName() + "!" + (rowIndex + 1));
            output.add(testCase);
        }
    }

    private TestCase toCase(Path suitePath, SheetGroupConfig group, Row row, Map<String, Integer> columns) {
        String rowCaseId = value(row, columns.get(config.caseIdColumn()));
        if (rowCaseId.isEmpty()) throw new IllegalArgumentException("Blank Case ID at " + group.sheetName() + "!" + (row.getRowNum() + 1));
        List<String> tags = tags(value(row, columns.get(config.tagsColumn())));
        Map<String, Object> caseData = loadColumns(row, columns, config.dataColumns());
        caseData.put("workbook", suitePath.getFileName().toString());

        Map<String, StageCaseData> stages = new LinkedHashMap<String, StageCaseData>();
        for (StageConfig stage : config.stages()) {
            String cell = value(row, columns.get(stage.template()));
            if (cell.isEmpty()) {
                if (stage.required()) throw new IllegalArgumentException("Required stage '" + stage.key() + "' has blank template at " + group.sheetName() + "!" + (row.getRowNum() + 1));
                continue;
            }
            Map<String, Object> stageValues = yamlMap(cell, "stage template cell " + group.sheetName() + "!" + (row.getRowNum() + 1));
            for (String reserved : new String[]{"key", "status", "startedAt", "durationMs", "TEMPLATE"}) {
                if (stageValues.containsKey(reserved)) throw new IllegalArgumentException("Reserved stage key '" + reserved + "' in " + group.sheetName() + "!" + (row.getRowNum() + 1));
            }
            Object name = stageValues.get("name");
            if (name == null || ValueNormalizer.normalize(String.valueOf(name)).isEmpty()) throw new IllegalArgumentException("Stage template YAML requires non-blank name: " + stage.key());
            Map<String, Object> privateData = loadColumns(row, columns, stage.dataColumns());
            for (Map.Entry<String, Object> entry : privateData.entrySet()) {
                if (stageValues.containsKey(entry.getKey())) throw new IllegalArgumentException("Duplicate stage data key '" + entry.getKey() + "' in stage " + stage.key());
                stageValues.put(entry.getKey(), entry.getValue());
            }
            stages.put(stage.key(), new StageCaseData(stage.key(), ValueNormalizer.normalize(String.valueOf(name)), stageValues));
        }
        return new TestCase(row.getRowNum() + 1, group.id(), group.sheetName(), rowCaseId, tags, caseData, stages, null);
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
        if (!(loaded instanceof Map)) throw new IllegalArgumentException(owner + " must be a YAML map");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
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

    private Map<String, Integer> columns(Row header) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (Cell cell : header) {
            String name = ValueNormalizer.normalize(formatter.formatCellValue(cell));
            if (!name.isEmpty()) {
                if (result.put(name, cell.getColumnIndex()) != null) throw new IllegalArgumentException("Duplicate Excel header: " + name);
            }
        }
        return result;
    }

    private void requireColumn(Map<String, Integer> columns, String name, String field, SheetGroupConfig group) {
        if (name == null || name.trim().isEmpty() || !columns.containsKey(name)) throw new IllegalArgumentException("Missing required column for " + field + " in group " + group.id() + ": " + name);
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
