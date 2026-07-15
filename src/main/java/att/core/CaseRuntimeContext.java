/* Author: Jeffrey + ChatGPT */
package att.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authoritative V2 CASE tree plus transient ACTIONS/TOOL views. */
public final class CaseRuntimeContext {
    private final Map<String, Object> root = new LinkedHashMap<String, Object>();
    private final Map<String, Object> caseNode = new LinkedHashMap<String, Object>();
    private final Map<String, Object> actionsView = new LinkedHashMap<String, Object>();
    private final Path caseOutputDir;
    private final Path caseLogPath;
    private String currentStage;
    private Map<String, Object> currentActions;
    private int toolSequence;

    public CaseRuntimeContext(TestCase testCase, Path caseOutputDir, String runId, Path runDirectory, Path caseLog) {
        this.caseOutputDir = caseOutputDir.toAbsolutePath().normalize();
        this.caseLogPath = caseLog.toAbsolutePath().normalize();
        caseNode.put("caseId", testCase.caseId());
        caseNode.put("workbookId", testCase.workbookId());
        caseNode.put("groupId", testCase.groupId());
        caseNode.put("rowCaseId", testCase.rowCaseId());
        caseNode.put("workbook", testCase.caseData().get("workbook"));
        caseNode.put("sheet", testCase.sheetName());
        caseNode.put("rowNumber", testCase.rowNumber());
        caseNode.put("tags", testCase.tags());
        caseNode.put("status", "RUNNING");
        caseNode.put("startedAt", java.time.Instant.now().toString());
        caseNode.putAll(testCase.caseData());
        // Framework-owned runtime metadata must not be replaceable by a
        // same-named workbook column/case-data alias.
        caseNode.put("outputDirectory", this.caseOutputDir.toString());
        caseNode.put("STAGES", new LinkedHashMap<String, Object>());
        root.put("CASE", caseNode);
        root.put("ACTIONS", actionsView);
        // TOOL is a reserved transient scope. Persisted tool results live below
        // ACTIONS.<actionId>; later actions must not depend on case-wide latest state.
        root.put("TOOL", new LinkedHashMap<String, Object>());
        root.put("RUN.runId", runId);
        root.put("RUN.id", runId);
        root.put("RUN.runDirectory", runDirectory.toString());
        root.put("RUN.caseLog", caseLog.toString());
    }

    @SuppressWarnings("unchecked")
    public void beginStage(StageCaseData stage, String templateName, Path templatePath) {
        currentStage = stage.key();
        Map<String, Object> stageNode = new LinkedHashMap<String, Object>();
        stageNode.put("key", stage.key());
        stageNode.put("status", "RUNNING");
        stageNode.put("startedAt", java.time.Instant.now().toString());
        stageNode.putAll(stage.values());
        Map<String, Object> template = new LinkedHashMap<String, Object>();
        template.put("name", templateName);
        template.put("path", templatePath.toString());
        template.put("status", "RUNNING");
        template.put("startedAt", java.time.Instant.now().toString());
        currentActions = new LinkedHashMap<String, Object>();
        template.put("ACTIONS", currentActions);
        stageNode.put("TEMPLATE", template);
        ((Map<String, Object>) caseNode.get("STAGES")).put(stage.key(), stageNode);
        actionsView.clear();
    }

    @SuppressWarnings("unchecked")
    public void finishStage(String status, long durationMs) {
        Map<String, Object> stages = (Map<String, Object>) caseNode.get("STAGES");
        Map<String, Object> stage = (Map<String, Object>) stages.get(currentStage);
        stage.put("status", status);
        stage.put("durationMs", durationMs);
        Map<String, Object> template = (Map<String, Object>) stage.get("TEMPLATE");
        template.put("status", status);
        template.put("durationMs", durationMs);
    }

    public Object resolve(String path) {
        if (root.containsKey(path)) return root.get(path);
        if (path.startsWith("TOOL.")) {
            Object scoped = getPath(root.get("TOOL"), path.substring("TOOL.".length()));
            if (scoped != null) return scoped;
            if (path.startsWith("TOOL.input.")) return getPath(root.get("TOOL.input"), path.substring("TOOL.input.".length()));
            if (path.startsWith("TOOL.output.")) return getPath(root.get("TOOL.output"), path.substring("TOOL.output.".length()));
        }
        Object direct = getPath(root, path);
        return direct == null ? getPath(caseNode, path) : direct;
    }

    public void put(String key, Object value) {
        root.put(key, value);
        if (key.startsWith("CASE.")) putPath(caseNode, key.substring(5), value);
        if (key.startsWith("TOOL.")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) root.get("TOOL");
            putPath(tool, key.substring(5), value);
        }
    }

    public Map<String, Object> values() { return root; }
    public Map<String, Object> caseTree() { return caseNode; }
    public Path caseOutputDirectory() { return caseOutputDir; }

    public void setActionOutput(Map<String, Object> output) { root.put("output", output); }
    public void clearActionOutput() { root.remove("output"); }

    public int nextToolSequence(String ignored) { return ++toolSequence; }
    public String nextInvocationId(String base) { return base + "_" + String.format("%03d", nextToolSequence(base)); }

    public void addAction(String actionId, Map<String, Object> action) {
        if (currentActions == null) throw new IllegalStateException("No current stage for action: " + actionId);
        if (currentActions.containsKey(actionId)) throw new IllegalArgumentException("Duplicate action id: " + actionId);
        currentActions.put(actionId, action);
        Map<String, Object> view = actionView(action);
        actionsView.put(actionId, view);
    }

    private Map<String, Object> actionView(Map<String, Object> action) {
        return new LinkedHashMap<String, Object>(action);
    }

    @SuppressWarnings("unchecked")
    public void updateAction(String actionId, Map<String, Object> action) {
        if (currentActions == null || !currentActions.containsKey(actionId)) throw new IllegalArgumentException("Unknown action id: " + actionId);
        currentActions.put(actionId, action);
        actionsView.put(actionId, actionView(action));
    }

    public Path caseLogDirectory() {
        Path parent = caseLogPath.getParent();
        return parent == null ? caseOutputDir.toAbsolutePath().normalize() : parent;
    }

    public Path actionOutputDir(String actionId) {
        Path directory = currentStage == null ? caseOutputDir.resolve(actionId) : caseOutputDir.resolve(currentStage).resolve(actionId);
        directory = directory.normalize();
        if (!directory.startsWith(caseOutputDir.normalize())) throw new IllegalArgumentException("Action output directory escapes case root: " + actionId);
        return directory;
    }

    @SuppressWarnings("unchecked")
    public static Object getPath(Object root, String path) {
        if (path == null || path.isEmpty()) return root;
        Object current = root;
        int position = 0;
        while (position < path.length()) {
            if (path.charAt(position) == '.') { position++; continue; }
            if (path.charAt(position) == '[') {
                int end = bracketEnd(path, position);
                if (end < 0) return null;
                String selector = path.substring(position + 1, end).trim();
                if (quoted(selector)) {
                    if (!(current instanceof Map)) return null;
                    current = ((Map<String, Object>) current).get(unquote(selector));
                } else if (current instanceof java.util.List && numeric(selector)) {
                    current = listValue((java.util.List<?>) current, Integer.parseInt(selector));
                } else return null;
                position = end + 1;
                continue;
            }
            int end = position;
            while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
            String key = path.substring(position, end);
            if (current instanceof Map) current = ((Map<String, Object>) current).get(key);
            else if (current instanceof java.util.List && numeric(key)) current = listValue((java.util.List<?>) current, Integer.parseInt(key));
            else return null;
            position = end;
        }
        return current;
    }

    private static int bracketEnd(String path, int start) {
        char quote = 0;
        for (int i = start + 1; i < path.length(); i++) {
            char value = path.charAt(i);
            if (quote != 0) {
                if (value == quote && path.charAt(i - 1) != '\\') quote = 0;
            } else if (value == '\'' || value == '"') quote = value;
            else if (value == ']') return i;
        }
        return -1;
    }

    private static boolean quoted(String value) {
        return value.length() >= 2 && ((value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') || (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'));
    }

    private static String unquote(String value) {
        char quote = value.charAt(0);
        return value.substring(1, value.length() - 1).replace("\\" + quote, String.valueOf(quote)).replace("\\\\", "\\");
    }

    private static boolean numeric(String value) { return value != null && value.matches("\\d+"); }
    private static Object listValue(java.util.List<?> values, int index) { return index >= 0 && index < values.size() ? values.get(index) : null; }

    @SuppressWarnings("unchecked")
    private static void putPath(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }
}
