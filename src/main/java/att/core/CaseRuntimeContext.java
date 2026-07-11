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
    private String currentStage;
    private Map<String, Object> currentActions;
    private int toolSequence;

    public CaseRuntimeContext(TestCase testCase, Path caseOutputDir, String runId, Path runDirectory, Path caseLog) {
        this.caseOutputDir = caseOutputDir;
        caseNode.put("caseId", testCase.caseId());
        // Short aliases make common expressions readable while preserving the explicit V2 names.
        caseNode.put("id", testCase.caseId());
        caseNode.put("groupId", testCase.groupId());
        caseNode.put("rowCaseId", testCase.rowCaseId());
        caseNode.put("workbook", testCase.caseData().get("workbook"));
        caseNode.put("sheet", testCase.sheetName());
        caseNode.put("rowNumber", testCase.rowNumber());
        caseNode.put("tags", testCase.tags());
        caseNode.put("status", "RUNNING");
        caseNode.put("startedAt", java.time.Instant.now().toString());
        caseNode.putAll(testCase.caseData());
        caseNode.put("STAGES", new LinkedHashMap<String, Object>());
        root.put("CASE", caseNode);
        root.put("ACTIONS", actionsView);
        // TOOL is a transient, uppercase convenience scope for the currently
        // executing tool. Persisted tool results live below ACTIONS.<actionId>.
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

    public int nextToolSequence(String ignored) { return ++toolSequence; }
    public String nextInvocationId(String base) { return base + "_" + String.format("%03d", nextToolSequence(base)); }

    public void addAction(String actionId, Map<String, Object> action) {
        if (currentActions == null) throw new IllegalStateException("No current stage for action: " + actionId);
        if (currentActions.containsKey(actionId)) throw new IllegalArgumentException("Duplicate action id: " + actionId);
        currentActions.put(actionId, action);
        // ACTIONS is a transient convenience view. Keep the persisted CASE
        // shape canonical while exposing the first tool's fields directly for
        // existing same-template expressions such as ${ACTIONS.call.output}.
        Map<String, Object> view = new LinkedHashMap<String, Object>(action);
        Object toolNode = action.get("TOOL");
        if (toolNode instanceof Map && !((Map<?, ?>) toolNode).isEmpty()) {
            Object first = ((Map<?, ?>) toolNode).values().iterator().next();
            if (first instanceof Map) view.putAll((Map<String, Object>) first);
        }
        actionsView.put(actionId, view);
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
        for (String raw : path.split("\\.")) {
            String part = raw;
            int bracket = part.indexOf('[');
            String key = bracket < 0 ? part : part.substring(0, bracket);
            if (!key.isEmpty()) {
                if (current instanceof Map) current = ((Map<String, Object>) current).get(key);
                else if (current instanceof java.util.List && numeric(key)) current = listValue((java.util.List<?>) current, Integer.parseInt(key));
                else return null;
            }
            while (bracket >= 0) {
                int end = part.indexOf(']', bracket);
                if (end < 0 || !(current instanceof java.util.List)) return null;
                String index = part.substring(bracket + 1, end).trim();
                if (!numeric(index)) return null;
                current = listValue((java.util.List<?>) current, Integer.parseInt(index));
                bracket = part.indexOf('[', end + 1);
            }
        }
        return current;
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
