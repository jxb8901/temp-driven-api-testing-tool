/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import java.util.List;
import java.util.Map;

/**
 * Represents one row from the Excel TestCases sheet after parsing and validation.
 */
public class TestCase {
    private final int rowNumber;
    private final boolean enabled;
    private final String caseId;
    private final String caseName;
    private final List<String> tags;
    private final String api;
    private final String requestTemplate;
    private final String expectedTemplate;
    private final Map<String, String> fixedValues;
    private final Map<String, Object> requestData;
    private final Map<String, Object> expectedData;
    private final String invalidReason;

    public TestCase(int rowNumber, boolean enabled, String caseId, String caseName, List<String> tags, String api, String requestTemplate, String expectedTemplate, Map<String, String> fixedValues, Map<String, Object> requestData, Map<String, Object> expectedData, String invalidReason) {
        this.rowNumber = rowNumber;
        this.enabled = enabled;
        this.caseId = caseId;
        this.caseName = caseName;
        this.tags = tags;
        this.api = api;
        this.requestTemplate = requestTemplate;
        this.expectedTemplate = expectedTemplate;
        this.fixedValues = fixedValues;
        this.requestData = requestData;
        this.expectedData = expectedData;
        this.invalidReason = invalidReason;
    }

    public boolean valid() {
        return invalidReason == null || invalidReason.trim().isEmpty();
    }

    public int rowNumber() { return rowNumber; }
    public boolean enabled() { return enabled; }
    public String caseId() { return caseId; }
    public String caseName() { return caseName; }
    public List<String> tags() { return tags; }
    public String api() { return api; }
    public String requestTemplate() { return requestTemplate; }
    public String expectedTemplate() { return expectedTemplate; }
    public Map<String, String> fixedValues() { return fixedValues; }
    public Map<String, Object> requestData() { return requestData; }
    public Map<String, Object> expectedData() { return expectedData; }
    public String invalidReason() { return invalidReason; }
}
