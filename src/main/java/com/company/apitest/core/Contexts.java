/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the two runtime variable maps defined by the design document.
 */
public final class Contexts {
    private Contexts() {
    }

    public static Map<String, Object> requestContext(TestCase testCase) {
        Map<String, Object> context = new LinkedHashMap<>(testCase.fixedValues());
        // Request Data is intentionally the only YAML block available to request XML templates.
        context.putAll(testCase.requestData());
        context.put("api", testCase.api());
        context.put("Case ID", testCase.caseId());
        return context;
    }

    public static Map<String, Object> expectedContext(TestCase testCase) {
        Map<String, Object> context = new LinkedHashMap<>(testCase.fixedValues());
        // Expected data is validation-only and is not exposed to request template rendering.
        context.putAll(testCase.expectedPrecheckData());
        context.putAll(testCase.expectedPostcheckData());
        context.put("api", testCase.api());
        context.put("Case ID", testCase.caseId());
        return context;
    }
}
