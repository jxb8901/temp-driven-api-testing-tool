/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.excel;

import com.company.apitest.core.TestCase;
import com.company.apitest.config.FrameworkConfig;
import org.apache.poi.ss.usermodel.*;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads the TestCases worksheet and converts each populated row into an executable test case.
 */
public class ExcelTestSuiteLoader {
    private final DataFormatter formatter = new DataFormatter();
    private final FrameworkConfig config;

    public ExcelTestSuiteLoader(FrameworkConfig config) {
        this.config = config;
    }

    public List<TestCase> load(Path suitePath) throws IOException {
        try (InputStream input = Files.newInputStream(suitePath); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(config.testcaseSheet());
            if (sheet == null) {
                throw new IllegalArgumentException("Missing required sheet: " + config.testcaseSheet());
            }
            Row header = sheet.getRow(0);
            if (header == null) {
                throw new IllegalArgumentException("TestCases sheet must contain a header row");
            }
            Map<String, Integer> columns = columns(header);
            for (String requiredKey : requiredKeys()) {
                String columnName = config.testcaseColumns().get(requiredKey);
                if (columnName == null || !columns.containsKey(columnName)) {
                    throw new IllegalArgumentException("Missing required configured column: " + requiredKey + " -> " + columnName);
                }
            }

            List<TestCase> cases = new ArrayList<TestCase>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || blank(row, columns)) {
                    continue;
                }
                cases.add(toTestCase(row, columns));
            }
            return cases;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to load suite " + suitePath + ": " + e.getMessage(), e);
        }
    }

    private Map<String, Integer> columns(Row header) {
        Map<String, Integer> columns = new LinkedHashMap<String, Integer>();
        for (Cell cell : header) {
            String name = formatter.formatCellValue(cell).trim();
            if (!name.isEmpty()) {
                columns.put(name, cell.getColumnIndex());
            }
        }
        return columns;
    }

    private TestCase toTestCase(Row row, Map<String, Integer> columns) {
        Map<String, String> fixed = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> configured : config.testcaseColumns().entrySet()) {
            Integer index = columns.get(configured.getValue());
            if (index != null) {
                fixed.put(configured.getValue(), cell(row, index));
                fixed.put(configured.getKey(), cell(row, index));
            }
        }

        boolean enabled = true;
        String invalid = null;
        if (enabled) {
            for (String requiredKey : requiredKeys()) {
                if (empty(fixed.get(requiredKey))) {
                    invalid = "Missing required value: " + requiredKey;
                    break;
                }
            }
        }

        Map<String, Object> requestData = Collections.emptyMap();
        Map<String, Object> expectedPrecheckData = Collections.emptyMap();
        Map<String, Object> expectedPostcheckData = Collections.emptyMap();
        try {
            requestData = parseYamlMap(firstPresent(fixed, "data", "requestData"));
            expectedPrecheckData = parseYamlMap(fixed.get("expectedPrecheckData"));
            expectedPostcheckData = parseYamlMap(fixed.get("expectedPostcheckData"));
        } catch (RuntimeException e) {
            invalid = "Invalid YAML: " + e.getMessage();
        }

        return new TestCase(
                row.getRowNum() + 1,
                enabled,
                fixed.get("caseId"),
                fixed.get("caseName"),
                parseTags(fixed.get("tags")),
                fixed.get("api"),
                fixed.get("precheckTemplate"),
                fixed.get("expectedPrecheckResult"),
                fixed.get("requestTemplate"),
                fixed.get("postcheckTemplate"),
                fixed.get("expectedPostcheckResult"),
                fixed,
                requestData,
                expectedPrecheckData,
                expectedPostcheckData,
                invalid
        );
    }

    private List<String> requiredKeys() {
        List<String> required = new ArrayList<String>();
        required.add("caseId");
        return required;
    }

    private String firstPresent(Map<String, String> values, String first, String second) {
        String value = values.get(first);
        return empty(value) ? values.get(second) : value;
    }

    private boolean blank(Row row, Map<String, Integer> columns) {
        for (Integer index : columns.values()) {
            if (!empty(cell(row, index))) {
                return false;
            }
        }
        return true;
    }

    private String cell(Row row, int index) {
        Cell cell = row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private boolean parseEnabled(String value) {
        if (empty(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "y".equals(normalized) || "yes".equals(normalized) || "true".equals(normalized) || "1".equals(normalized);
    }

    private List<String> parseTags(String tags) {
        if (empty(tags)) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        for (String tag : tags.split(",")) {
            if (!tag.trim().isEmpty()) {
                values.add(tag.trim());
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYamlMap(String yamlText) {
        if (empty(yamlText)) {
            return Collections.emptyMap();
        }
        Object loaded = new Yaml().load(yamlText);
        if (loaded == null) {
            return Collections.emptyMap();
        }
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("YAML value must be a map");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
