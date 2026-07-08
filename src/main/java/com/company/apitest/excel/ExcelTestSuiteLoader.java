/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.excel;

import com.company.apitest.core.TestCase;
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
    private static final List<String> REQUIRED_COLUMNS = Arrays.asList("Enable", "Case ID", "API", "Request Template", "Expected Template");
    private final DataFormatter formatter = new DataFormatter();

    public List<TestCase> load(Path suitePath) throws IOException {
        try (InputStream input = Files.newInputStream(suitePath); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet("TestCases");
            if (sheet == null) {
                throw new IllegalArgumentException("Missing required sheet: TestCases");
            }
            Row header = sheet.getRow(0);
            if (header == null) {
                throw new IllegalArgumentException("TestCases sheet must contain a header row");
            }
            Map<String, Integer> columns = columns(header);
            // Required columns are suite-level validation errors because the framework cannot interpret rows without them.
            for (String required : REQUIRED_COLUMNS) {
                if (!columns.containsKey(required)) {
                    throw new IllegalArgumentException("Missing required column: " + required);
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
        for (Map.Entry<String, Integer> entry : columns.entrySet()) {
            fixed.put(entry.getKey(), cell(row, entry.getValue()));
        }

        boolean enabled = parseEnabled(fixed.get("Enable"));
        String invalid = null;
        if (enabled) {
            // Enabled rows are validated strictly; disabled rows can remain as draft or negative examples.
            for (String required : REQUIRED_COLUMNS) {
                if (empty(fixed.get(required))) {
                    invalid = "Missing required value: " + required;
                    break;
                }
            }
        }

        Map<String, Object> requestData = Collections.emptyMap();
        Map<String, Object> expectedData = Collections.emptyMap();
        try {
            requestData = parseYamlMap(fixed.get("Request Data"));
            expectedData = parseYamlMap(fixed.get("Expected Data"));
        } catch (RuntimeException e) {
            invalid = "Invalid YAML: " + e.getMessage();
        }

        return new TestCase(
                row.getRowNum() + 1,
                enabled,
                fixed.get("Case ID"),
                fixed.get("Case Name"),
                parseTags(fixed.get("Tags")),
                fixed.get("API"),
                fixed.get("Request Template"),
                fixed.get("Expected Template"),
                fixed,
                requestData,
                expectedData,
                invalid
        );
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
