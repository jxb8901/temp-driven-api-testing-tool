/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-case runtime context used by V1.3 stage actions and tool invocations.
 */
public class CaseRuntimeContext {
    private final Map<String, Object> values = new LinkedHashMap<String, Object>();
    private final Map<String, Map<String, Object>> actions = new LinkedHashMap<String, Map<String, Object>>();
    private final Path caseOutputDir;
    private int toolSequence = 0;

    public CaseRuntimeContext(TestCase testCase, Path caseOutputDir, String runId, Path runDirectory, Path caseLog) {
        this.caseOutputDir = caseOutputDir;
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
            return resolveActionPath(path.substring("TOOLS.".length()));
        }
        if (path.startsWith("ACTIONS.")) {
            return resolveActionPath(path.substring("ACTIONS.".length()));
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
        addAction(invocationId, invocation);
    }

    public void addAction(String actionId, Map<String, Object> action) {
        if (actions.containsKey(actionId)) {
            throw new IllegalArgumentException("Duplicate action id: " + actionId);
        }
        actions.put(actionId, action);
    }

    public Path actionOutputDir(String actionId) {
        Path directory = caseOutputDir.resolve(actionId).normalize();
        if (!directory.startsWith(caseOutputDir.normalize())) {
            throw new IllegalArgumentException("Action output directory must stay under case output directory: " + actionId);
        }
        return directory;
    }

    private Object resolveActionPath(String path) {
        String id = null;
        for (String candidate : actions.keySet()) {
            if (path.equals(candidate) || path.startsWith(candidate + ".")) {
                if (id == null || candidate.length() > id.length()) {
                    id = candidate;
                }
            }
        }
        if (id == null) {
            return null;
        }
        Map<String, Object> action = actions.get(id);
        return path.equals(id) ? action : getPath(action, path.substring(id.length() + 1));
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
