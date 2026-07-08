/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies request and expected contexts stay isolated.
 */
class ContextsTest {
    @Test
    void requestContextDoesNotIncludeExpectedData() {
        Map<String, String> fixed = new HashMap<String, String>();
        fixed.put("Case ID", "TC001");
        fixed.put("Amount", "100");

        Map<String, Object> requestData = new HashMap<String, Object>();
        requestData.put("channel", "ATM");

        Map<String, Object> expectedData = new HashMap<String, Object>();
        expectedData.put("expectedLedgerStatus", "POSTED");

        TestCase testCase = new TestCase(2, true, "TC001", "Payment", Arrays.asList("smoke"), "PAYMENT",
                "PAYMENT_TRANSFER", "PAYMENT_SUCCESS", fixed, requestData, expectedData, null);

        assertTrue(Contexts.requestContext(testCase).containsKey("channel"));
        assertFalse(Contexts.requestContext(testCase).containsKey("expectedLedgerStatus"));
        assertTrue(Contexts.expectedContext(testCase).containsKey("expectedLedgerStatus"));
    }
}
