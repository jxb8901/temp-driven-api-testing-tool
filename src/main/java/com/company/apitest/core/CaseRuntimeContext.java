/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-case runtime context used by all V1.1 templates and tool invocations.
 */
public class CaseRuntimeContext {
    private final Map<String, Object> values = new LinkedHashMap<String, Object>();
    private final Map<String, List<Map<String, Object>>> tools = new LinkedHashMap<String, List<Map<String, Object>>>();
    private int toolSequence = 0;

    public CaseRuntimeContext(TestCase testCase, Path caseOutputDir) {
        values.putAll(testCase.fixedValues());
        values.putAll(testCase.requestData());
        values.putAll(testCase.expectedPrecheckData());
        values.putAll(testCase.expectedPostcheckData());
        values.put("CaseID", testCase.caseId());
        values.put("caseId", testCase.caseId());
        values.put("PATH.caseOutputDir", caseOutputDir.toString());
    }

    public Object resolve(String path) {
        if (values.containsKey(path)) {
            return values.get(path);
        }
        if (path.startsWith("TOOL.input.")) {
            return getPath(values.get("TOOL.input"), path.substring("TOOL.input.".length()));
        }
        if (path.startsWith("TOOL.output.")) {
            return getPath(values.get("TOOL.output"), path.substring("TOOL.output.".length()));
        }
        if (path.startsWith("TOOLS.")) {
            return resolveToolPath(path.substring("TOOLS.".length()));
        }
        return null;
    }

    public void put(String key, Object value) {
        values.put(key, value);
    }

    public Map<String, Object> values() {
        return values;
    }

    public int nextToolSequence(String toolName) {
        toolSequence++;
        return toolSequence;
    }

    public void addToolInvocation(String toolName, Map<String, Object> invocation) {
        List<Map<String, Object>> invocations = tools.get(toolName);
        if (invocations == null) {
            invocations = new ArrayList<Map<String, Object>>();
            tools.put(toolName, invocations);
        }
        invocations.add(invocation);
    }

    private Object resolveToolPath(String path) {
        int bracket = path.indexOf('[');
        int close = path.indexOf(']');
        if (bracket <= 0 || close <= bracket) {
            return null;
        }
        String tool = path.substring(0, bracket);
        int index = Integer.parseInt(path.substring(bracket + 1, close));
        List<Map<String, Object>> invocations = tools.get(tool);
        if (invocations == null || index < 0 || index >= invocations.size()) {
            return null;
        }
        String remainder = path.substring(close + 1);
        if (remainder.startsWith(".")) {
            remainder = remainder.substring(1);
        }
        return getPath(invocations.get(index), remainder);
    }

    @SuppressWarnings("unchecked")
    public static Object getPath(Object root, String path) {
        if (path == null || path.isEmpty()) {
            return root;
        }
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
