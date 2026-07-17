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
        caseNode.put("VARS", new LinkedHashMap<String, Object>());
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
        Lookup lookup = lookup(path);
        return lookup.found ? lookup.value : null;
    }

    public Object require(String path) {
        Lookup lookup = lookup(path);
        if (lookup.found) return lookup.value;
        java.util.List<String> paths = availablePaths();
        String nearest = nearest(path, paths);
        String parent = parent(path);
        java.util.List<String> siblings = new java.util.ArrayList<String>();
        for (String candidate : paths) if (parent.isEmpty() || candidate.startsWith(parent + ".")) {
            String remainder = parent.isEmpty() ? candidate : candidate.substring(parent.length() + 1);
            if (!remainder.contains(".")) siblings.add(remainder);
        }
        java.util.Collections.sort(siblings);
        if (siblings.size() > 12) siblings = new java.util.ArrayList<String>(siblings.subList(0, 12));
        String detail = "No value exists at the case-sensitive path '" + path + "'"
                + (siblings.isEmpty() ? "" : ". Available fields under '" + (parent.isEmpty() ? "<root>" : parent) + "': " + String.join(", ", siblings));
        String suggestion = nearest == null
                ? "Check the uppercase scope (CASE/ACTIONS/TOOL/RUN), stage/action IDs, and camelCase field name."
                : "Use '${" + nearest + "}' if that is the intended Context variable; Context names are case-sensitive.";
        throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_INVALID,
                "Unknown Context variable '${" + path + "}'", detail, null, path, null, null, null,
                null, null, suggestion, null);
    }

    public boolean contains(String path) { return lookup(path).found; }

    private Lookup lookup(String path) {
        if (root.containsKey(path)) return Lookup.found(root.get(path));
        if (path.startsWith("TOOL.")) {
            Lookup scoped = findPath(root.get("TOOL"), path.substring("TOOL.".length()));
            if (scoped.found) return scoped;
            if (path.startsWith("TOOL.input.")) {
                Lookup input = findPath(root.get("TOOL.input"), path.substring("TOOL.input.".length()));
                if (input.found) return input;
            }
            if (path.startsWith("TOOL.output.")) {
                Lookup output = findPath(root.get("TOOL.output"), path.substring("TOOL.output.".length()));
                if (output.found) return output;
            }
        }
        Lookup direct = findPath(root, path);
        return direct.found ? direct : findPath(caseNode, path);
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

    @SuppressWarnings("unchecked")
    public void requireCaseVariableAvailable(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_INVALID,
                    "Invalid CASE.VARS assignment name", "name='" + name + "' must match [A-Za-z_][A-Za-z0-9_]*",
                    null, "name", null, null, null, null, null,
                    "Use a simple case-sensitive identifier such as txnSeq.", null);
        }
        Map<String, Object> variables = (Map<String, Object>) caseNode.get("VARS");
        if (variables.containsKey(name)) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_INVALID,
                    "Duplicate CASE.VARS assignment '${CASE.VARS." + name + "}'",
                    "The variable was already assigned earlier in this Test Case.", null, "name",
                    null, null, null, null, null,
                    "Use a unique name; assign does not overwrite Case-scoped variables.", null);
        }
    }

    @SuppressWarnings("unchecked")
    public void assignCaseVariable(String name, Object value) {
        requireCaseVariableAvailable(name);
        Map<String, Object> variables = (Map<String, Object>) caseNode.get("VARS");
        variables.put(name, value);
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
        Lookup lookup = findPath(root, path);
        return lookup.found ? lookup.value : null;
    }

    @SuppressWarnings("unchecked")
    private static Lookup findPath(Object root, String path) {
        if (path == null || path.isEmpty()) return Lookup.found(root);
        Object current = root;
        int position = 0;
        while (position < path.length()) {
            if (path.charAt(position) == '.') { position++; continue; }
            if (path.charAt(position) == '[') {
                int end = bracketEnd(path, position);
                if (end < 0) return Lookup.missing();
                String selector = path.substring(position + 1, end).trim();
                if (quoted(selector)) {
                    if (!(current instanceof Map)) return Lookup.missing();
                    String key = unquote(selector);
                    if (!((Map<String, Object>) current).containsKey(key)) return Lookup.missing();
                    current = ((Map<String, Object>) current).get(key);
                } else if (current instanceof java.util.List && numeric(selector)) {
                    int index = Integer.parseInt(selector);
                    if (index < 0 || index >= ((java.util.List<?>) current).size()) return Lookup.missing();
                    current = ((java.util.List<?>) current).get(index);
                } else return Lookup.missing();
                position = end + 1;
                continue;
            }
            int end = position;
            while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
            String key = path.substring(position, end);
            if (current instanceof Map) {
                if (!((Map<String, Object>) current).containsKey(key)) return Lookup.missing();
                current = ((Map<String, Object>) current).get(key);
            } else if (current instanceof java.util.List && numeric(key)) {
                int index = Integer.parseInt(key);
                if (index < 0 || index >= ((java.util.List<?>) current).size()) return Lookup.missing();
                current = ((java.util.List<?>) current).get(index);
            } else return Lookup.missing();
            position = end;
        }
        return Lookup.found(current);
    }

    private java.util.List<String> availablePaths() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<String>();
        collectPaths(root, "", result, 0);
        return new java.util.ArrayList<String>(result);
    }

    @SuppressWarnings("unchecked")
    private static void collectPaths(Object value, String prefix, java.util.Set<String> output, int depth) {
        if (depth > 8 || !(value instanceof Map)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            output.add(path);
            collectPaths(entry.getValue(), path, output, depth + 1);
        }
    }

    private static String parent(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    private static String nearest(String requested, java.util.List<String> candidates) {
        String best = null; int distance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int current = levenshtein(requested, candidate);
            if (current < distance) { distance = current; best = candidate; }
        }
        int threshold = Math.max(2, requested == null ? 2 : requested.length() / 4);
        return distance <= threshold ? best : null;
    }

    private static int levenshtein(String left, String right) {
        if (left == null) left = ""; if (right == null) right = "";
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static final class Lookup {
        private final boolean found; private final Object value;
        private Lookup(boolean found, Object value) { this.found = found; this.value = value; }
        private static Lookup found(Object value) { return new Lookup(true, value); }
        private static Lookup missing() { return new Lookup(false, null); }
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
