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
    private final String precheckTemplate;
    private final String expectedPrecheckResult;
    private final String requestTemplate;
    private final String postcheckTemplate;
    private final String expectedPostcheckResult;
    private final Map<String, String> fixedValues;
    private final Map<String, Object> requestData;
    private final Map<String, Object> expectedPrecheckData;
    private final Map<String, Object> expectedPostcheckData;
    private final String invalidReason;

    public TestCase(int rowNumber, boolean enabled, String caseId, String caseName, List<String> tags, String api,
                    String precheckTemplate, String expectedPrecheckResult, String requestTemplate,
                    String postcheckTemplate, String expectedPostcheckResult, Map<String, String> fixedValues,
                    Map<String, Object> requestData, Map<String, Object> expectedPrecheckData,
                    Map<String, Object> expectedPostcheckData, String invalidReason) {
        this.rowNumber = rowNumber;
        this.enabled = enabled;
        this.caseId = caseId;
        this.caseName = caseName;
        this.tags = tags;
        this.api = api;
        this.precheckTemplate = precheckTemplate;
        this.expectedPrecheckResult = expectedPrecheckResult;
        this.requestTemplate = requestTemplate;
        this.postcheckTemplate = postcheckTemplate;
        this.expectedPostcheckResult = expectedPostcheckResult;
        this.fixedValues = fixedValues;
        this.requestData = requestData;
        this.expectedPrecheckData = expectedPrecheckData;
        this.expectedPostcheckData = expectedPostcheckData;
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
    public String precheckTemplate() { return precheckTemplate; }
    public String expectedPrecheckResult() { return expectedPrecheckResult; }
    public String requestTemplate() { return requestTemplate; }
    public String postcheckTemplate() { return postcheckTemplate; }
    public String expectedPostcheckResult() { return expectedPostcheckResult; }
    public Map<String, String> fixedValues() { return fixedValues; }
    public Map<String, Object> requestData() { return requestData; }
    public Map<String, Object> expectedPrecheckData() { return expectedPrecheckData; }
    public Map<String, Object> expectedPostcheckData() { return expectedPostcheckData; }
    public String invalidReason() { return invalidReason; }
}
