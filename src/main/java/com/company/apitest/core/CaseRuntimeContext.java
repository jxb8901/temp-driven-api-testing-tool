/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-case runtime context used by V1.2 stage actions and tool invocations.
 */
public class CaseRuntimeContext {
    private final Map<String, Object> values = new LinkedHashMap<String, Object>();
    private final Map<String, Map<String, Object>> tools = new LinkedHashMap<String, Map<String, Object>>();
    private int toolSequence = 0;

    public CaseRuntimeContext(TestCase testCase, Path caseOutputDir, String runId, Path runDirectory, Path caseLog) {
        values.putAll(testCase.fixedValues());
        values.putAll(testCase.caseData());
        values.putAll(testCase.expectedPrecheckData());
        values.putAll(testCase.expectedPostcheckData());
        values.put("CaseID", testCase.caseId());
        values.put("caseId", testCase.caseId());
        values.put("PATH.caseOutputDir", caseOutputDir.toString());
        values.put("RUN.runId", runId);
        values.put("RUN.runDirectory", runDirectory.toString());
        values.put("RUN.caseLog", caseLog.toString());
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
        return getPath(values, path);
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

    public String nextInvocationId(String base) {
        int sequence = nextToolSequence(base);
        return base + "_" + String.format("%03d", sequence);
    }

    public void addToolInvocation(String invocationId, Map<String, Object> invocation) {
        if (tools.containsKey(invocationId)) {
            throw new IllegalArgumentException("Duplicate invocation id: " + invocationId);
        }
        tools.put(invocationId, invocation);
    }

    private Object resolveToolPath(String path) {
        int dot = path.indexOf('.');
        String id = dot < 0 ? path : path.substring(0, dot);
        Map<String, Object> invocation = tools.get(id);
        if (invocation == null) {
            return null;
        }
        return dot < 0 ? invocation : getPath(invocation, path.substring(dot + 1));
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
