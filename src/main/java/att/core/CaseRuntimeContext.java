/* Author: Jeffrey + ChatGPT */
package att.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authoritative V2 CASE tree plus transient ACTIONS/TOOL views. */
public final class CaseRuntimeContext {
    private final Map<String, Object> root = new LinkedHashMap<String, Object>();
    private final Map<String, Object> caseNode = new LinkedHashMap<String, Object>();
    private final Map<String, Object> runNode = new LinkedHashMap<String, Object>();
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
        runNode.put("runId", runId);
        runNode.put("id", runId);
        runNode.put("runDirectory", runDirectory.toString());
        runNode.put("caseLog", caseLog.toString());
        root.put("RUN", runNode);
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
        Resolution resolution = resolution(path);
        return resolution.status == ResolutionStatus.FOUND ? resolution.value : null;
    }

    public Object require(String path) {
        Resolution resolution = resolution(path);
        if (resolution.status == ResolutionStatus.FOUND) return resolution.value;
        java.util.List<String> paths = availablePaths();
        String nearest = nearest(path, paths);
        StringBuilder detail = new StringBuilder();
        if (resolution.status == ResolutionStatus.AMBIGUOUS) {
            detail.append("The shorthand matches more than one readable logical Context path.")
                    .append("\nrequestedPath: ").append(path)
                    .append("\ncurrentNode: <root>")
                    .append("\ncandidates:");
            for (String candidate : resolution.candidates) detail.append("\n  - ").append(candidate);
            detail.append("\ncontextTree:\n").append(indent(contextTree("<root>"), 2));
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_AMBIGUOUS,
                    "Ambiguous Context shorthand '${" + path + "}'", detail.toString(), null, path, null, null, null,
                    null, null, "Use a longer unique suffix or one of the listed canonical Context paths.", null);
        }
        detail.append("No value exists at the requested case-sensitive Context path.")
                .append("\nrequestedPath: ").append(path)
                .append("\ncurrentNode: ").append(resolution.currentNode)
                .append("\nmissingSegment: ").append(resolution.missingSegment)
                .append("\ncontextTree:\n").append(indent(contextTree(resolution.currentNode), 2));
        String suggestion = nearest == null
                ? "Check the Context tree, case-sensitive field name, and whether the stage/action output is available at this point."
                : "Use '${" + nearest + "}' if that is the intended Context variable; Context names are case-sensitive.";
        throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_INVALID,
                "Unknown Context variable '${" + path + "}'", detail.toString(), null, path, null, null, null,
                null, null, suggestion, null);
    }

    public boolean contains(String path) { return resolution(path).status == ResolutionStatus.FOUND; }

    private Resolution resolution(String path) {
        java.util.List<Segment> requested;
        try { requested = parsePath(path); }
        catch (Exception ignored) { return Resolution.missing("<root>", path == null ? "<null>" : path); }
        if (requested.isEmpty()) return Resolution.missing("<root>", "<empty>");
        String first = requested.get(0).key;
        if (first != null && explicitRoots().contains(first)) return traverse(logicalRoot(), requested);

        java.util.Map<String, Object> candidates = canonicalPaths();
        java.util.List<String> matches = new java.util.ArrayList<String>();
        for (String candidate : candidates.keySet()) {
            java.util.List<Segment> segments = parsePath(candidate);
            if (endsWith(segments, requested)) matches.add(candidate);
        }
        java.util.Collections.sort(matches);
        if (matches.size() == 1) return Resolution.found(candidates.get(matches.get(0)), matches.get(0));
        if (matches.size() > 1) return Resolution.ambiguous(matches);
        return partialResolution(requested, candidates);
    }

    private java.util.Set<String> explicitRoots() {
        return new java.util.LinkedHashSet<String>(java.util.Arrays.asList("CASE", "RUN", "ACTIONS", "TOOL", "output"));
    }

    private Map<String, Object> logicalRoot() {
        Map<String, Object> logical = new LinkedHashMap<String, Object>();
        logical.put("CASE", caseNode);
        logical.put("RUN", runNode);
        logical.put("ACTIONS", actionsView);
        if (root.containsKey("output")) logical.put("output", root.get("output"));
        logical.put("TOOL", root.get("TOOL"));
        return logical;
    }

    private Map<String, Object> canonicalRoot() {
        Map<String, Object> canonical = new LinkedHashMap<String, Object>();
        canonical.put("CASE", caseNode);
        canonical.put("RUN", runNode);
        canonical.put("TOOL", root.get("TOOL"));
        return canonical;
    }

    public void put(String key, Object value) {
        if (key.startsWith("CASE.")) putPath(caseNode, key.substring(5), value);
        else if (key.startsWith("RUN.")) putPath(runNode, key.substring(4), value);
        else if (key.startsWith("TOOL.")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) root.get("TOOL");
            putPath(tool, key.substring(5), value);
        } else root.put(key, value);
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
        java.util.List<String> result = new java.util.ArrayList<String>(canonicalPaths().keySet());
        java.util.Collections.sort(result);
        return result;
    }

    private Map<String, Object> canonicalPaths() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        collectCanonical(canonicalRoot(), "", result, new java.util.IdentityHashMap<Object, Boolean>());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void collectCanonical(Object value, String prefix, Map<String, Object> output,
                                         java.util.IdentityHashMap<Object, Boolean> active) {
        if (!prefix.isEmpty()) output.put(prefix, value);
        if (!(value instanceof Map) && !(value instanceof java.util.List)) return;
        if (active.put(value, Boolean.TRUE) != null) return;
        try {
            if (value instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    String path = appendPath(prefix, Segment.key(String.valueOf(entry.getKey())));
                    collectCanonical(entry.getValue(), path, output, active);
                }
            } else {
                java.util.List<?> list = (java.util.List<?>) value;
                for (int index = 0; index < list.size(); index++) {
                    collectCanonical(list.get(index), appendPath(prefix, Segment.index(index)), output, active);
                }
            }
        } finally {
            active.remove(value);
        }
    }

    private Resolution traverse(Object tree, java.util.List<Segment> segments) {
        Object current = tree;
        String currentPath = "<root>";
        for (Segment segment : segments) {
            if (current instanceof Map && segment.key != null) {
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) current;
                if (!map.containsKey(segment.key)) return Resolution.missing(currentPath, segment.display());
                current = map.get(segment.key);
            } else if (current instanceof java.util.List && segment.index != null) {
                java.util.List<?> list = (java.util.List<?>) current;
                if (segment.index.intValue() < 0 || segment.index.intValue() >= list.size()) return Resolution.missing(currentPath, segment.display());
                current = list.get(segment.index.intValue());
            } else return Resolution.missing(currentPath, segment.display());
            currentPath = appendPath("<root>".equals(currentPath) ? "" : currentPath, segment);
        }
        return Resolution.found(current, currentPath);
    }

    private Resolution partialResolution(java.util.List<Segment> requested, Map<String, Object> candidates) {
        int best = 0;
        java.util.LinkedHashSet<String> currentNodes = new java.util.LinkedHashSet<String>();
        for (String candidate : candidates.keySet()) {
            java.util.List<Segment> full = parsePath(candidate);
            for (int start = 0; start < full.size(); start++) {
                int matched = 0;
                while (matched < requested.size() && start + matched < full.size()
                        && full.get(start + matched).equals(requested.get(matched))) matched++;
                if (matched == 0 || matched >= requested.size()) continue;
                String node = renderPath(full.subList(0, start + matched));
                if (matched > best) { best = matched; currentNodes.clear(); }
                if (matched == best) currentNodes.add(node);
            }
        }
        if (best > 0 && currentNodes.size() == 1) return Resolution.missing(currentNodes.iterator().next(), requested.get(best).display());
        return Resolution.missing("<root>", requested.get(0).display());
    }

    private static boolean endsWith(java.util.List<Segment> full, java.util.List<Segment> suffix) {
        if (suffix.size() > full.size()) return false;
        int offset = full.size() - suffix.size();
        for (int index = 0; index < suffix.size(); index++) if (!full.get(offset + index).equals(suffix.get(index))) return false;
        return true;
    }

    private String contextTree(String currentNode) {
        StringBuilder out = new StringBuilder();
        if ("<root>".equals(currentNode)) out.append("<root>: map  <-- currentNode\n");
        java.util.IdentityHashMap<Object, Boolean> active = new java.util.IdentityHashMap<Object, Boolean>();
        for (Map.Entry<String, Object> entry : canonicalRoot().entrySet()) {
            renderTree(out, Segment.key(entry.getKey()), entry.getValue(), "", 0, currentNode, active);
        }
        if (currentStage != null) {
            String target = appendPath(appendPath(appendPath(appendPath("CASE.STAGES", Segment.key(currentStage)), Segment.key("TEMPLATE")), Segment.key("ACTIONS")), null);
            out.append("ACTIONS: alias -> ").append(target);
            if (currentNode != null && currentNode.startsWith("ACTIONS")) out.append("  <-- currentNode");
            out.append('\n');
        }
        if (root.containsKey("output")) {
            String target = currentOutputPath();
            out.append("output: alias");
            if (target != null) out.append(" -> ").append(target);
            if (currentNode != null && currentNode.startsWith("output")) out.append("  <-- currentNode");
            out.append('\n');
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private String currentOutputPath() {
        if (currentStage == null || currentActions == null) return null;
        Object output = root.get("output");
        for (Map.Entry<String, Object> entry : currentActions.entrySet()) {
            if (entry.getValue() instanceof Map && ((Map<String, Object>) entry.getValue()).get("output") == output) {
                String path = appendPath("CASE.STAGES", Segment.key(currentStage));
                path = appendPath(path, Segment.key("TEMPLATE"));
                path = appendPath(path, Segment.key("ACTIONS"));
                path = appendPath(path, Segment.key(entry.getKey()));
                return appendPath(path, Segment.key("output"));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void renderTree(StringBuilder out, Segment segment, Object value, String parent, int depth,
                                   String currentNode, java.util.IdentityHashMap<Object, Boolean> active) {
        String path = appendPath(parent, segment);
        for (int index = 0; index < depth; index++) out.append("  ");
        out.append(segment.display()).append(": ").append(type(value));
        if (path.equals(currentNode)) out.append("  <-- currentNode");
        out.append('\n');
        if (!(value instanceof Map) && !(value instanceof java.util.List)) return;
        if (active.put(value, Boolean.TRUE) != null) return;
        try {
            if (value instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    renderTree(out, Segment.key(String.valueOf(entry.getKey())), entry.getValue(), path, depth + 1, currentNode, active);
                }
            } else {
                java.util.List<?> list = (java.util.List<?>) value;
                for (int index = 0; index < list.size(); index++) renderTree(out, Segment.index(index), list.get(index), path, depth + 1, currentNode, active);
            }
        } finally { active.remove(value); }
    }

    private static String type(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) return "map";
        if (value instanceof java.util.List) return "list";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof java.math.BigDecimal || value instanceof Float || value instanceof Double) return "decimal";
        if (value instanceof Number) return "integer";
        return "string";
    }

    private static String indent(String value, int spaces) {
        String prefix = new String(new char[spaces]).replace('\0', ' ');
        String[] lines = value.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index == lines.length - 1 && lines[index].isEmpty()) break;
            if (index > 0) out.append('\n');
            out.append(prefix).append(lines[index]);
        }
        return out.toString();
    }

    private static java.util.List<Segment> parsePath(String path) {
        if (path == null) throw new IllegalArgumentException("Context path is null");
        java.util.List<Segment> result = new java.util.ArrayList<Segment>();
        int position = 0;
        while (position < path.length()) {
            if (path.charAt(position) == '.') { position++; continue; }
            if (path.charAt(position) == '[') {
                int end = bracketEnd(path, position);
                if (end < 0) throw new IllegalArgumentException("Unclosed bracket");
                String selector = path.substring(position + 1, end).trim();
                if (quoted(selector)) result.add(Segment.key(unquote(selector)));
                else if (numeric(selector)) result.add(Segment.index(Integer.parseInt(selector)));
                else throw new IllegalArgumentException("Invalid selector");
                position = end + 1;
                continue;
            }
            int end = position;
            while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
            String key = path.substring(position, end);
            if (key.isEmpty()) throw new IllegalArgumentException("Empty path segment");
            result.add(numeric(key) && !result.isEmpty() ? Segment.index(Integer.parseInt(key)) : Segment.key(key));
            position = end;
        }
        return result;
    }

    private static String renderPath(java.util.List<Segment> segments) {
        String path = "";
        for (Segment segment : segments) path = appendPath(path, segment);
        return path;
    }

    private static String appendPath(String prefix, Segment segment) {
        if (segment == null) return prefix;
        if (segment.index != null) return prefix + "[" + segment.index + "]";
        if (simpleKey(segment.key)) return prefix.isEmpty() ? segment.key : prefix + "." + segment.key;
        return prefix + "['" + segment.key.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private static boolean simpleKey(String key) { return key != null && key.matches("[A-Za-z_][A-Za-z0-9_-]*"); }

    private enum ResolutionStatus { FOUND, MISSING, AMBIGUOUS }

    private static final class Resolution {
        private final ResolutionStatus status; private final Object value; private final String canonicalPath;
        private final String currentNode; private final String missingSegment; private final java.util.List<String> candidates;
        private Resolution(ResolutionStatus status, Object value, String canonicalPath, String currentNode,
                           String missingSegment, java.util.List<String> candidates) {
            this.status = status; this.value = value; this.canonicalPath = canonicalPath; this.currentNode = currentNode;
            this.missingSegment = missingSegment; this.candidates = candidates;
        }
        private static Resolution found(Object value, String canonicalPath) { return new Resolution(ResolutionStatus.FOUND, value, canonicalPath, null, null, java.util.Collections.<String>emptyList()); }
        private static Resolution missing(String currentNode, String missingSegment) { return new Resolution(ResolutionStatus.MISSING, null, null, currentNode, missingSegment, java.util.Collections.<String>emptyList()); }
        private static Resolution ambiguous(java.util.List<String> candidates) { return new Resolution(ResolutionStatus.AMBIGUOUS, null, null, "<root>", null, new java.util.ArrayList<String>(candidates)); }
    }

    private static final class Segment {
        private final String key; private final Integer index;
        private Segment(String key, Integer index) { this.key = key; this.index = index; }
        private static Segment key(String value) { return new Segment(value, null); }
        private static Segment index(int value) { return new Segment(null, Integer.valueOf(value)); }
        private String display() {
            if (index != null) return "[" + index + "]";
            return simpleKey(key) ? key : "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Segment)) return false;
            Segment value = (Segment) other;
            return java.util.Objects.equals(key, value.key) && java.util.Objects.equals(index, value.index);
        }
        @Override public int hashCode() { return java.util.Objects.hash(key, index); }
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
