/* Author: Jeffrey + ChatGPT */
package att.core;

import att.exec.ActionExecutionResult;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Execution-neutral expression Context.
 *
 * <p>{@code EXEC} and {@code META} are the canonical roots.  The historical
 * {@code CASE}, {@code RUN}, and {@code ACTIONS} roots are generated views of
 * the same maps so old definitions do not get a second mutable copy of the
 * execution state.  {@code output} is held separately as an Action-local
 * binding and is deliberately absent from the canonical tree.</p>
 */
public final class CaseRuntimeContext {
    /** Marker used only by validation/documentation contexts for values whose runtime shape is unknown. */
    private static final Object DEFERRED_VALIDATION_VALUE = new Object();
    /** Transient legacy TOOL/DB views; these are not promoted into EXEC. */
    private final Map<String, Object> root = new LinkedHashMap<String, Object>();
    private final Map<String, Object> execNode = new LinkedHashMap<String, Object>();
    private final Map<String, Object> inputNode = new LinkedHashMap<String, Object>();
    private final Map<String, Object> varsNode = new LinkedHashMap<String, Object>();
    /** Load-only scheduler state published below the canonical EXEC root. */
    private final Map<String, Object> loadNode = new LinkedHashMap<String, Object>();
    private final Map<String, Object> stagesNode = new LinkedHashMap<String, Object>();
    private final Map<String, Object> metaNode = new LinkedHashMap<String, Object>();
    private final Map<String, Object> caseNode = new LinkedHashMap<String, Object>();
    /** Execution result/lifecycle state; deliberately not part of public EXEC. */
    private final Map<String, Object> lifecycleNode = new LinkedHashMap<String, Object>();
    private final Map<String, Object> runNode = new LinkedHashMap<String, Object>();
    /** Current Stage's published Action results; history is retained below CASE.STAGES. */
    private final Map<String, Object> actionsView = new LinkedHashMap<String, Object>();
    private final Map<String, Object> caseDbNode = new LinkedHashMap<String, Object>();
    /** Optional legacy CASE.inputs compatibility view; never part of EXEC.INPUT. */
    private Map<String, Object> legacyInputsView = Collections.emptyMap();
    /**
     * Previous values temporarily hidden by the current Stage's input overlay.
     * The overlay is an adapter view, not a second public Context store.
     */
    private final Map<String, Object> activeStageInputPrevious = new LinkedHashMap<String, Object>();
    private final java.util.Set<String> activeStageInputKeys = new java.util.LinkedHashSet<String>();
    private final java.util.Set<String> activeStageInputHadPrevious = new java.util.LinkedHashSet<String>();
    private final Path caseOutputDir;
    private final Path caseLogPath;
    private final String mode;
    private String currentStage;
    private Map<String, Object> currentActions;
    /** Current Action/attempt-local output; never published as EXEC.OUTPUT. */
    private Map<String, Object> actionOutput;
    /** Canonical Action result sink used while an Action is executing. */
    private final Deque<Map<String, Object>> actionEvidenceSinks = new ArrayDeque<Map<String, Object>>();
    private boolean statusPublished;
    private int toolSequence;
    private int dbSequence;
    private final Map<String, Object> callToolCache = new LinkedHashMap<String, Object>();
    private final java.util.Deque<FlowFrame> flowScopes = new java.util.ArrayDeque<FlowFrame>();

    public CaseRuntimeContext(TestCase testCase, Path caseOutputDir, String runId, Path runDirectory, Path caseLog) {
        this(testCase, caseOutputDir, runId, runDirectory, caseLog, "testcase");
    }

    /** Creates a Context for the normal testcase or standalone debug adapter. */
    public CaseRuntimeContext(TestCase testCase, Path caseOutputDir, String runId, Path runDirectory, Path caseLog,
                              String mode) {
        this(testCase, caseOutputDir, runId, runDirectory, caseLog, mode, null);
    }

    /** Creates a Context with an adapter-provided iteration start timestamp. */
    public CaseRuntimeContext(TestCase testCase, Path caseOutputDir, String runId, Path runDirectory, Path caseLog,
                              String mode, String startedAtOverride) {
        this.caseOutputDir = caseOutputDir.toAbsolutePath().normalize();
        this.caseLogPath = caseLog.toAbsolutePath().normalize();
        this.mode = normalizeMode(mode);
        String startedAt = startedAtOverride == null ? java.time.Instant.now().toString() : startedAtOverride;

        execNode.put("ID", runId);
        execNode.put("MODE", this.mode);
        execNode.put("STARTED_AT", startedAt);
        execNode.put("OUTPUT_DIR", this.caseOutputDir.toString());
        execNode.put("INPUT", inputNode);
        execNode.put("VARS", varsNode);
        execNode.put("ACTIONS", actionsView);
        if ("load".equals(this.mode)) execNode.put("LOAD", loadNode);
        lifecycleNode.put("status", "RUNNING");

        inputNode.putAll(testCase.caseData());

        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("type", "testcase");
        source.put("caseId", testCase.caseId());
        source.put("workbookId", testCase.workbookId());
        source.put("groupId", testCase.groupId());
        source.put("rowCaseId", testCase.rowCaseId());
        source.put("sheet", testCase.sheetName());
        source.put("row", Integer.valueOf(testCase.rowNumber()));
        if (testCase.caseData().get("workbook") != null) source.put("workbook", testCase.caseData().get("workbook"));
        metaNode.put("SOURCE", source);
        metaNode.put("TARGET", mapOf("type", "testcase", "id", testCase.caseId()));

        // The legacy Case view retains framework-owned identity and evidence
        // fields, but business data itself lives only in EXEC.INPUT.
        caseNode.put("caseId", testCase.caseId());
        caseNode.put("workbookId", testCase.workbookId());
        caseNode.put("groupId", testCase.groupId());
        caseNode.put("rowCaseId", testCase.rowCaseId());
        caseNode.put("workbook", testCase.caseData().get("workbook"));
        caseNode.put("sheet", testCase.sheetName());
        caseNode.put("rowNumber", testCase.rowNumber());
        caseNode.put("tags", testCase.tags());
        caseNode.put("status", "RUNNING");
        caseNode.put("startedAt", startedAt);
        caseNode.put("outputDirectory", this.caseOutputDir.toString());
        caseNode.put("VARS", varsNode);
        caseNode.put("DB", caseDbNode);
        caseNode.put("STAGES", stagesNode);
        // TOOL is a reserved transient scope. Persisted tool results live below
        // ACTIONS.<actionId>; later actions must not depend on case-wide latest state.
        root.put("TOOL", new LinkedHashMap<String, Object>());
        // Root DB is invocation-local evidence and is intentionally distinct
        // from the fixed CASE.DB transaction-finalization map above.
        root.put("DB", new LinkedHashMap<String, Object>());
        runNode.put("runId", runId);
        runNode.put("id", runId);
        runNode.put("runDirectory", runDirectory.toString());
        runNode.put("caseLog", caseLog.toString());
    }

    @SuppressWarnings("unchecked")
    public void beginStage(StageCaseData stage, String templateName, Path templatePath) {
        clearActiveStageInput();
        currentStage = stage.key();
        // The current Stage caller/input values are adapted into the one
        // canonical EXEC.INPUT map.  Stage values win over Case-level values
        // for the duration of that Stage; the original values are restored
        // when the Stage ends or another Stage starts.
        applyActiveStageInput(stage.values());
        Map<String, Object> stageNode = new LinkedHashMap<String, Object>();
        stageNode.put("key", stage.key());
        stageNode.put("status", "RUNNING");
        stageNode.put("startedAt", java.time.Instant.now().toString());
        stageNode.putAll(stage.values());
        stageNode.put("key", stage.key());
        stageNode.put("status", "RUNNING");
        Map<String, Object> template = new LinkedHashMap<String, Object>();
        template.put("name", templateName);
        template.put("id", templateName);
        template.put("path", templatePath.toString());
        template.put("status", "RUNNING");
        template.put("startedAt", java.time.Instant.now().toString());
        currentActions = new LinkedHashMap<String, Object>();
        template.put("ACTIONS", currentActions);
        stageNode.put("TEMPLATE", template);
        stagesNode.put(stage.key(), stageNode);
        setComponentMetadata("TEMPLATE", templateMetadata(templateName, templatePath));
        actionsView.clear();
    }

    @SuppressWarnings("unchecked")
    public void finishStage(String status, long durationMs) {
        if (currentStage == null) return;
        Map<String, Object> stages = stagesNode;
        Map<String, Object> stage = (Map<String, Object>) stages.get(currentStage);
        try {
            stage.put("status", status);
            stage.put("durationMs", durationMs);
            Map<String, Object> template = (Map<String, Object>) stage.get("TEMPLATE");
            template.put("status", status);
            template.put("durationMs", durationMs);
        } finally {
            clearActiveStageInput();
            currentStage = null;
            currentActions = null;
            clearActionOutput();
        }
    }

    public boolean hasActiveStage() { return currentStage != null; }

    private void applyActiveStageInput(Map<String, Object> values) {
        if (values == null) return;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (inputNode.containsKey(key)) {
                activeStageInputHadPrevious.add(key);
                activeStageInputPrevious.put(key, inputNode.get(key));
            }
            activeStageInputKeys.add(key);
            inputNode.put(key, entry.getValue());
        }
    }

    private void clearActiveStageInput() {
        for (String key : activeStageInputKeys) {
            if (activeStageInputHadPrevious.contains(key)) inputNode.put(key, activeStageInputPrevious.get(key));
            else inputNode.remove(key);
        }
        activeStageInputKeys.clear();
        activeStageInputHadPrevious.clear();
        activeStageInputPrevious.clear();
    }

    public Object resolve(String path) {
        Resolution resolution = resolution(path);
        return resolution.status == ResolutionStatus.FOUND ? resolution.value : null;
    }

    /** Resolves an optional Context reference without changing strict lookup diagnostics. */
    public Object resolveOptional(String path) {
        Resolution resolution = resolution(requiredReferencePath(path));
        return resolution.status == ResolutionStatus.FOUND ? resolution.value : null;
    }

    public Object require(String path) {
        return requireResolved(path, false);
    }

    /**
     * Resolves ${path?}. Missing maps, list entries, and intermediate nodes are
     * nullable; ambiguous, malformed, and structurally invalid paths remain
     * errors so optional lookup cannot hide authoring mistakes.
     */
    public Object requireOptional(String path) {
        return requireResolved(path, true);
    }

    private Object requireResolved(String path, boolean optional) {
        String lookupPath = optional ? requiredReferencePath(path) : path;
        Resolution resolution = resolution(lookupPath);
        if (resolution.status == ResolutionStatus.FOUND) return resolution.value;
        if (resolution.status == ResolutionStatus.DEFERRED) return null;
        if (optional && (resolution.status == ResolutionStatus.MISSING
                || resolution.status == ResolutionStatus.NULL_INTERMEDIATE)) return null;
        String diagnosticPath = path == null ? lookupPath : path;
        java.util.List<String> paths = availablePaths();
        String nearest = nearest(lookupPath, paths);
        StringBuilder detail = new StringBuilder();
        if (resolution.status == ResolutionStatus.AMBIGUOUS) {
            detail.append("The shorthand matches more than one readable logical Context path.")
                    .append("\nrequestedPath: ").append(diagnosticPath)
                    .append("\ncurrentNode: <root>")
                    .append("\ncandidates:");
            for (String candidate : resolution.candidates) detail.append("\n  - ").append(candidate);
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_AMBIGUOUS,
                    "Ambiguous Context shorthand '${" + diagnosticPath + "}'", detail.toString(), null, diagnosticPath, null, null, null,
                    null, null, "Use a longer unique suffix or one of the listed canonical Context paths.", null);
        }
        if (resolution.status == ResolutionStatus.INVALID_PATH || resolution.status == ResolutionStatus.NULL_INTERMEDIATE) {
            detail.append("The requested Context path cannot be traversed.")
                    .append("\nrequestedPath: ").append(diagnosticPath)
                    .append("\ncurrentNode: ").append(resolution.currentNode)
                    .append("\nreason: ").append(resolution.reason);
            if (resolution.missingSegment != null) detail.append("\nmissingSegment: ").append(resolution.missingSegment);
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_INVALID,
                    "Invalid Context path '${" + diagnosticPath + "}'", detail.toString(), null, diagnosticPath, null, null, null,
                    null, null,
                    "Check the path segment type and use a valid map key or list index.", null);
        }
        detail.append("No value exists at the requested case-sensitive Context path.")
                .append("\nrequestedPath: ").append(diagnosticPath)
                .append("\ncurrentNode: ").append(resolution.currentNode)
                .append("\nmissingSegment: ").append(resolution.missingSegment);
        String suggestion = nearest == null
                ? "Check the case-sensitive field name and whether the stage/action output is available at this point."
                : "Use '${" + nearest + "}' if that is the intended Context variable; Context names are case-sensitive.";
        throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_INVALID,
                "Unknown Context variable '${" + diagnosticPath + "}'", detail.toString(), null, diagnosticPath, null, null,
                null, null, null, suggestion, null);
    }

    public boolean contains(String path) {
        ResolutionStatus status = resolution(requiredReferencePath(path)).status;
        return status == ResolutionStatus.FOUND || status == ResolutionStatus.DEFERRED;
    }

    /**
     * Returns the unique canonical path selected by the rootless resolver.
     *
     * <p>This is intentionally narrower than {@link #resolve(String)}: a
     * migration diagnostic may suggest a replacement only when the resolver
     * has one concrete or validation-deferred candidate. Ambiguous, missing,
     * and structurally invalid references return {@code null}.</p>
     */
    public String uniqueCanonicalPath(String path) {
        Resolution resolved = resolution(requiredReferencePath(path));
        if (resolved.status != ResolutionStatus.FOUND && resolved.status != ResolutionStatus.DEFERRED) return null;
        return resolved.canonicalPath;
    }

    /** True when validation knows the owning value exists but cannot know its runtime shape yet. */
    public boolean isValidationDeferred(String path) {
        return resolution(requiredReferencePath(path)).status == ResolutionStatus.DEFERRED;
    }

    public static boolean isOptionalReference(String path) {
        return path != null && path.endsWith("?");
    }

    /** Removes the trailing optional marker while preserving all other path syntax. */
    public static String requiredReferencePath(String path) {
        if (path == null) return null;
        if (!isOptionalReference(path)) return path;
        String required = path.substring(0, path.length() - 1);
        if (required.isEmpty()) throw new IllegalArgumentException("Optional Context path must contain a path before '?'");
        return required;
    }

    /** Validates path grammar without requiring the referenced value to exist. */
    public static void validateReferencePath(String path) {
        java.util.List<Segment> segments = parsePath(requiredReferencePath(path));
        if (segments.isEmpty()) throw new IllegalArgumentException("Context path must contain at least one segment");
    }

    private Resolution resolution(String path) {
        java.util.List<Segment> requested;
        try { requested = parsePath(path); }
        catch (Exception error) { return Resolution.invalidPath("<root>", error.getMessage()); }
        if (requested.isEmpty()) return Resolution.missing("<root>", "<empty>");
        String first = requested.get(0).key;
        if ("LOAD".equals(first)) return Resolution.missing("<root>", first);
        if (first != null && ContextPathPolicy.isExplicitRoot(first)) return traverse(logicalRoot(), requested);

        java.util.Map<String, Object> candidates = readablePaths();
        java.util.List<String> matches = new java.util.ArrayList<String>();
        for (String candidate : candidates.keySet()) {
            java.util.List<Segment> segments = parsePath(candidate);
            if (endsWith(segments, requested)) matches.add(candidate);
        }
        java.util.Collections.sort(matches);
        if (matches.size() == 1) return candidateResolution(candidates.get(matches.get(0)), matches.get(0));
        if (matches.size() > 1) {
            java.util.List<String> display = new java.util.ArrayList<String>();
            for (String match : matches) display.add(displayPath(match));
            return Resolution.ambiguous(display);
        }
        Resolution deferred = deferredSuffixResolution(requested, candidates);
        if (deferred != null) return deferred;
        return partialResolution(requested, candidates);
    }

    private Resolution candidateResolution(Object value, String canonicalPath) {
        return value == DEFERRED_VALIDATION_VALUE
                ? Resolution.deferred(canonicalPath) : Resolution.found(value, canonicalPath);
    }

    /** Resolves shorthand below a deferred value, for example value.id below CASE.VARS.value. */
    private Resolution deferredSuffixResolution(java.util.List<Segment> requested, Map<String, Object> candidates) {
        java.util.Set<String> matches = new java.util.LinkedHashSet<String>();
        for (Map.Entry<String, Object> candidate : candidates.entrySet()) {
            if (candidate.getValue() != DEFERRED_VALIDATION_VALUE) continue;
            java.util.List<Segment> full = parsePath(candidate.getKey());
            for (int start = 0; start < full.size(); start++) {
                int suffixLength = full.size() - start;
                if (suffixLength > requested.size()) continue;
                boolean match = true;
                for (int index = 0; index < suffixLength; index++) {
                    if (!full.get(start + index).equals(requested.get(index))) { match = false; break; }
                }
                if (!match) continue;
                String path = candidate.getKey();
                for (int index = suffixLength; index < requested.size(); index++) path = appendPath(path, requested.get(index));
                matches.add(path);
            }
        }
        java.util.List<String> ordered = new java.util.ArrayList<String>(matches);
        java.util.Collections.sort(ordered);
        if (ordered.size() == 1) return Resolution.deferred(ordered.get(0));
        if (ordered.size() > 1) {
            java.util.List<String> display = new java.util.ArrayList<String>();
            for (String path : ordered) display.add(displayPath(path));
            return Resolution.ambiguous(display);
        }
        return null;
    }

    private Map<String, Object> logicalRoot() {
        Map<String, Object> logical = new LinkedHashMap<String, Object>();
        logical.put("EXEC", execNode);
        logical.put("META", immutable(metaNode));
        // CASE.STAGES remains persisted result/evidence, but is deliberately
        // absent from the expression view so history cannot be used as a
        // cross-Stage or cross-Flow Context namespace.
        logical.put("CASE", expressionCaseView());
        logical.put("RUN", runNode);
        logical.put("ACTIONS", actionsView);
        if (actionOutput != null) logical.put("output", actionOutput);
        logical.put("TOOL", root.get("TOOL"));
        logical.put("DB", root.get("DB"));
        return logical;
    }

    /** The two canonical expression roots. Transient TOOL/DB scopes are separate. */
    private Map<String, Object> canonicalContextRoot() {
        Map<String, Object> canonical = new LinkedHashMap<String, Object>();
        canonical.put("EXEC", execNode);
        canonical.put("META", immutable(metaNode));
        return canonical;
    }

    private Map<String, Object> transientRoot() {
        Map<String, Object> transientScopes = new LinkedHashMap<String, Object>();
        transientScopes.put("TOOL", root.get("TOOL"));
        transientScopes.put("DB", root.get("DB"));
        return transientScopes;
    }

    /**
     * Publishes a runtime value. New framework code should use canonical
     * {@code EXEC.*} paths; {@code CASE.<businessField>} writes remain a
     * deliberate compatibility adapter into {@code EXEC.INPUT}.
     */
    public void put(String key, Object value) {
        if (key.startsWith("EXEC.")) putExecutionPath(key.substring(5), value, false);
        else if (key.startsWith("META.")) throw new IllegalArgumentException("META is immutable after Context construction");
        else if (key.startsWith("CASE.")) putLegacyCase(key.substring(5), value);
        else if (key.startsWith("RUN.")) putLegacyRun(key.substring(4), value);
        else if (key.startsWith("ACTIONS.")) putPath(actionsView, key.substring(8), value);
        else if (key.startsWith("output.")) {
            if (actionOutput == null) throw new IllegalArgumentException("Action-local output is not visible outside an Action scope");
            putPath(actionOutput, key.substring(7), value);
        }
        else if (key.startsWith("TOOL.")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) root.get("TOOL");
            putPath(tool, key.substring(5), value);
        } else if (key.startsWith("DB.")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> db = (Map<String, Object>) root.get("DB");
            putPath(db, key.substring(3), value);
        } else if ("EXEC".equals(key) || "META".equals(key)) {
            throw new IllegalArgumentException("Framework-owned Context root cannot be overwritten: " + key);
        } else root.put(key, value);
    }

    private void putExecutionPath(String path, Object value, boolean internal) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("EXEC path must contain a field");
        String first = firstSegment(path);
        if (ContextPathPolicy.isUnsupportedExecPath(path)) {
            if (ContextPathPolicy.isUnsupportedStagePath(path)) {
                throw new IllegalArgumentException("EXEC." + first + " is not a canonical Context node in 3.4.2; use EXEC.INPUT for current Stage input or CASE.STAGES for legacy execution evidence");
            }
            throw new IllegalArgumentException("EXEC." + first + " is not a canonical Context node in 3.4.2; use transient/helper compatibility views or META helper identity where applicable");
        }
        if (!internal && ContextPathPolicy.isFrameworkOwnedExecField(first)) {
            throw new IllegalArgumentException("Framework-owned EXEC field cannot be overwritten: EXEC." + first);
        }
        if (!ContextPathPolicy.isCanonicalExecField(first)) {
            throw new IllegalArgumentException("Unknown EXEC field: EXEC." + first
                    + "; the public tree exposes ID, MODE, STARTED_AT, OUTPUT_DIR, INPUT, VARS, ACTIONS, and load-only LOAD");
        }
        if ("INPUT".equals(first)) putPath(inputNode, suffix(path, first), value);
        else if ("VARS".equals(first)) putPath(varsNode, suffix(path, first), value);
        else if ("ACTIONS".equals(first)) putPath(actionsView, suffix(path, first), value);
        else if ("LOAD".equals(first)) {
            String loadPath = suffix(path, first);
            if (!loadPath.isEmpty() && !ContextPathPolicy.isCanonicalLoadField(firstSegment(loadPath)))
                throw new IllegalArgumentException("Unknown EXEC.LOAD field: " + firstSegment(loadPath));
            putPath(loadNode, loadPath, value);
            execNode.put("LOAD", loadNode);
        }
        else putPath(execNode, path, value);
    }

    private void putLegacyCase(String path, Object value) {
        String first = firstSegment(path);
        if ("VARS".equals(first)) {
            putPath(varsNode, suffix(path, first), value);
        } else if ("DB".equals(first)) {
            if (path.equals("DB")) {
                caseDbNode.clear();
                if (value instanceof Map) caseDbNode.putAll((Map<String, Object>) value);
                else throw new IllegalArgumentException("CASE.DB must be a map");
            } else putPath(caseDbNode, suffix(path, first), value);
        } else if ("STAGES".equals(first)) {
            putPath(stagesNode, suffix(path, first), value);
        } else if ("outputDirectory".equals(path)) {
            throw new IllegalArgumentException("Framework-owned CASE.outputDirectory cannot be overwritten");
        } else if ("caseId".equals(path) || "workbookId".equals(path) || "groupId".equals(path)
                || "rowCaseId".equals(path) || "startedAt".equals(path)) {
            throw new IllegalArgumentException("Framework-owned CASE field cannot be overwritten: CASE." + path);
        } else if ("status".equals(path) || "durationMs".equals(path) || "error".equals(path)
                || "errorDiagnostic".equals(path) || "environment".equals(path) || "debugInput".equals(path)) {
            if ("status".equals(path)) statusPublished = true;
            lifecycleNode.put(path, value);
        } else {
            // Legacy business-field writes are retained for existing adapters.
            // They update the canonical input map, but cannot replace any
            // framework-owned identity, lifecycle, VARS, DB, or Stage fields.
            putPath(inputNode, path, value);
        }
    }

    private void putLegacyRun(String path, Object value) {
        if ("id".equals(path) || "runId".equals(path))
            throw new IllegalArgumentException("Framework-owned RUN identity cannot be overwritten");
        putPath(runNode, path, value);
    }

    private static String firstSegment(String path) {
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        int end = dot < 0 ? path.length() : dot;
        if (bracket >= 0 && bracket < end) end = bracket;
        return path.substring(0, end);
    }

    private static String suffix(String path, String first) {
        return path.length() == first.length() ? "" : path.substring(first.length() + 1);
    }

    private Map<String, Object> legacyCaseView() {
        return legacyCaseView(true);
    }

    private Map<String, Object> expressionCaseView() {
        return legacyCaseView(false);
    }

    private Map<String, Object> legacyCaseView(boolean includeStageHistory) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll(inputNode);
        if (!inputNode.containsKey("inputs") && !legacyInputsView.isEmpty()) result.put("inputs", legacyInputsView);
        result.putAll(caseNode);
        if (!includeStageHistory) result.remove("STAGES");
        result.put("status", statusPublished || !inputNode.containsKey("status")
                ? lifecycleNode.get("status") : inputNode.get("status"));
        result.put("startedAt", execNode.get("STARTED_AT"));
        result.put("outputDirectory", execNode.get("OUTPUT_DIR"));
        result.put("durationMs", lifecycleNode.get("durationMs"));
        if (lifecycleNode.containsKey("environment")) result.put("environment", lifecycleNode.get("environment"));
        if (lifecycleNode.containsKey("debugInput")) result.put("debugInput", lifecycleNode.get("debugInput"));
        if (lifecycleNode.containsKey("error")) result.put("error", lifecycleNode.get("error"));
        if (lifecycleNode.containsKey("errorDiagnostic")) result.put("errorDiagnostic", lifecycleNode.get("errorDiagnostic"));
        result.put("VARS", varsNode);
        result.put("DB", caseDbNode);
        if (includeStageHistory) result.put("STAGES", stagesNode);
        return result;
    }

    private static Map<String, Object> mapOf(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key1, value1); result.put(key2, value2); return result;
    }

    private static String normalizeMode(String value) {
        String mode = value == null ? "testcase" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.isEmpty()) mode = "testcase";
        if (!"testcase".equals(mode) && !"debug".equals(mode) && !"load".equals(mode))
            throw new IllegalArgumentException("Unsupported execution Context mode: " + value);
        return mode;
    }

    private Map<String, Object> templateMetadata(String id, Path path) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("path", path.toString()); return result;
    }

    /** Adds only curated component metadata; credentials/config objects never enter META. */
    public void setProject(Path projectRoot) {
        setComponentMetadata("PROJECT", mapOf("root", projectRoot.toAbsolutePath().normalize().toString(), "id", projectRoot.getFileName() == null ? "" : projectRoot.getFileName().toString()));
    }

    public void setTargetMetadata(String type, String id) {
        setComponentMetadata("TARGET", mapOf("type", type, "id", id));
    }

    public void setSourceMetadata(String type, Path source, String caseId) {
        setSourceMetadata(type, source, caseId, null);
    }

    public void setSourceMetadata(String type, Path source, String caseId, String scenario) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("type", type);
        if (caseId != null && !caseId.trim().isEmpty()) values.put("caseId", caseId);
        if (scenario != null && !scenario.trim().isEmpty()) values.put("scenario", scenario);
        if (source != null) values.put("path", source.toAbsolutePath().normalize().toString());
        setComponentMetadata("SOURCE", values);
    }

    /** Publishes load source metadata without mutable per-iteration identity. */
    public void setLoadSourceMetadata(Path source, String scenario) {
        setSourceMetadata("load", source, null, scenario);
    }

    public void setComponentMetadata(String key, Map<String, Object> values) {
        if (key == null || values == null) throw new IllegalArgumentException("META component cannot be null");
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("password") || name.contains("credential") || name.contains("secret") || name.contains("token")) continue;
            if (entry.getValue() instanceof Map || entry.getValue() instanceof Iterable || entry.getValue() == null
                    || entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean)
                safe.put(entry.getKey(), entry.getValue());
        }
        metaNode.put(key, safe);
    }

    public void setToolMetadata(String id) { setComponentMetadata("TOOL", mapOf("id", id, "type", "tool")); }
    public void setDbHelperMetadata(String id) { setComponentMetadata("DBHELPER", mapOf("id", id, "type", "dbhelper")); }
    public void setMqHelperMetadata(String id) { setComponentMetadata("MQHELPER", mapOf("id", id, "type", "mqhelper")); }

    /** Publishes one load iteration and its enclosing load-run identity. */
    public void setLoad(String runId, String model, String iterationId, long iteration, String phase,
                        String startedAt, String userId, String runStartedAt) {
        if (!"load".equals(mode)) throw new IllegalStateException("EXEC.LOAD requires load execution mode");
        loadNode.clear();
        loadNode.put("RUN_ID", runId);
        loadNode.put("MODEL", model);
        loadNode.put("USER_ID", userId);
        loadNode.put("ITERATION_ID", iterationId);
        loadNode.put("ITERATION", Long.valueOf(iteration));
        loadNode.put("PHASE", phase);
        if (runStartedAt != null) loadNode.put("RUN_STARTED_AT", runStartedAt);
        execNode.put("LOAD", loadNode);
    }

    /** Compatibility overload for scheduler adapters that do not expose run start time. */
    public void setLoad(String model, String iterationId, long iteration, String phase,
                        String startedAt, String userId) {
        setLoad("LOAD", model, iterationId, iteration, phase, startedAt, userId, startedAt);
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return Collections.unmodifiableMap(deepImmutable(source, new java.util.IdentityHashMap<Object, Boolean>()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepImmutable(Map<String, Object> source, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(source, Boolean.TRUE) != null) return source;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) result.put(entry.getKey(), immutableValue(entry.getValue(), seen));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object immutableValue(Object value, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (value instanceof Map) return Collections.unmodifiableMap(deepImmutable((Map<String, Object>) value, seen));
        if (value instanceof java.util.List) {
            java.util.List<Object> result = new java.util.ArrayList<Object>();
            for (Object item : (java.util.List<?>) value) result.add(immutableValue(item, seen));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    /** Declares a validation-only value whose existence is known but whose nested runtime shape is not. */
    public void putValidationPlaceholder(String key) {
        if (key != null && key.startsWith("EXEC.")) putExecutionPath(key.substring("EXEC.".length()), DEFERRED_VALIDATION_VALUE, true);
        else put(key, DEFERRED_VALIDATION_VALUE);
    }

    /** Adds a read-only legacy CASE.inputs compatibility view without duplicating EXEC.INPUT. */
    public void setLegacyInputsView(Map<String, Object> inputs) {
        legacyInputsView = inputs == null || inputs.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(inputs));
    }

    @SuppressWarnings("unchecked")
    public void requireCaseVariableAvailable(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_INVALID,
                    "Invalid EXEC.VARS assignment name", "name='" + name + "' must match [A-Za-z_][A-Za-z0-9_]*",
                    null, "name", null, null, null, null, null,
                    "Use a simple case-sensitive identifier such as txnSeq.", null);
        }
        Map<String, Object> variables = varsNode;
        if (variables.containsKey(name)) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.CONTEXT_INVALID,
                    "Duplicate EXEC.VARS assignment '${EXEC.VARS." + name + "}'",
                    "The variable was already assigned earlier in this Test Case.", null, "name",
                    null, null, null, null, null,
                    "Use a unique name; assign does not overwrite scoped variables.", null);
        }
    }

    @SuppressWarnings("unchecked")
    public void assignCaseVariable(String name, Object value) {
        requireCaseVariableAvailable(name);
        Map<String, Object> variables = varsNode;
        variables.put(name, value);
    }

    /** Expression-visible roots, including legacy aliases and current local bindings. */
    public Map<String, Object> values() { return logicalRoot(); }

    /** Canonical execution data, exposed read-only for adapters and diagnostics. */
    public Map<String, Object> executionTree() { return immutable(execNode); }

    /** Curated, secret-safe metadata, exposed read-only. */
    public Map<String, Object> metadataTree() { return immutable(metaNode); }

    /** Persisted legacy Case evidence view; its mutable state is backed by EXEC. */
    public Map<String, Object> caseTree() { return legacyCaseView(); }
    public Path caseOutputDirectory() { return caseOutputDir; }

    public void setActionOutput(Map<String, Object> output) { actionOutput = output; }
    public void clearActionOutput() { actionOutput = null; }

    /**
     * Starts collecting helper-native evidence into the current Action result
     * without making the Action's output visible to its own pre-publication
     * expressions.
     */
    public void beginAction(Map<String, Object> output) {
        if (output == null) throw new IllegalArgumentException("Action output cannot be null");
        actionEvidenceSinks.push(output);
    }

    /** Adds executor-neutral evidence to the active Action result envelope. */
    @SuppressWarnings("unchecked")
    public void recordActionEvidence(Map<String, Object> commonEvidence) {
        if (actionEvidenceSinks.isEmpty() || commonEvidence == null || commonEvidence.isEmpty()) return;
        Map<String, Object> targetOutput = actionEvidenceSinks.peek();
        Map<String, Object> target = (Map<String, Object>) targetOutput.get("evidence");
        if (target == null) {
            target = new LinkedHashMap<String, Object>();
            targetOutput.put("evidence", target);
        }
        ActionExecutionResult.mergeEvidence(target, commonEvidence);
    }

    /** Restores the enclosing Action sink; the published Action remains intact. */
    public void endAction() {
        if (!actionEvidenceSinks.isEmpty()) actionEvidenceSinks.pop();
    }

    public int nextToolSequence(String ignored) { return ++toolSequence; }
    public String nextInvocationId(String base) { return base + "_" + String.format("%03d", nextToolSequence(base)); }
    public String nextDbInvocationId(String instance) { return instance + "_" + String.format("%03d", ++dbSequence); }

    public boolean hasCallToolCache(String key) { return callToolCache.containsKey(key); }
    public Object callToolCache(String key) { return callToolCache.get(key); }
    public void cacheCallTool(String key, Object value) { callToolCache.put(key, value); }

    public void addAction(String actionId, Map<String, Object> action) {
        if (!flowScopes.isEmpty()) {
            FlowFrame frame = flowScopes.peek();
            if (actionsView.containsKey(actionId)) throw new IllegalArgumentException("Duplicate expanded action id: " + actionId);
            frame.actions.put(actionId, action);
            actionsView.put(actionId, actionView(action));
            return;
        }
        if (currentActions == null) throw new IllegalStateException("No current stage for action: " + actionId);
        if (actionsView.containsKey(actionId)) throw new IllegalArgumentException("Duplicate expanded action id: " + actionId);
        currentActions.put(actionId, action);
        Map<String, Object> view = actionView(action);
        actionsView.put(actionId, view);
    }

    private Map<String, Object> actionView(Map<String, Object> action) {
        Map<String, Object> view = new LinkedHashMap<String, Object>(action);
        // Flow internals are retained in the persisted evidence node, but the
        // parent/current Action namespace exposes only the Flow invocation's
        // standard outcome.  This prevents a caller from reaching through a
        // completed Flow into its private Action scope.
        if ("flow".equalsIgnoreCase(String.valueOf(action.get("type")))) view.remove("flow");
        return view;
    }

    @SuppressWarnings("unchecked")
    public void updateAction(String actionId, Map<String, Object> action) {
        if (!flowScopes.isEmpty()) {
            FlowFrame frame = flowScopes.peek();
            if (!frame.actions.containsKey(actionId)) throw new IllegalArgumentException("Unknown Flow action id: " + actionId);
            frame.actions.put(actionId, action); actionsView.put(actionId, actionView(action)); return;
        }
        if (currentActions == null || !currentActions.containsKey(actionId)) throw new IllegalArgumentException("Unknown action id: " + actionId);
        currentActions.put(actionId, action);
        actionsView.put(actionId, actionView(action));
    }

    public Path caseLogDirectory() {
        Path parent = caseLogPath.getParent();
        return parent == null ? caseOutputDir.toAbsolutePath().normalize() : parent;
    }

    public Path actionOutputDir(String actionId) {
        Path directory = currentStage == null ? caseOutputDir : caseOutputDir.resolve(currentStage);
        if (!flowScopes.isEmpty()) {
            java.util.List<FlowFrame> frames = new java.util.ArrayList<FlowFrame>(flowScopes);
            java.util.Collections.reverse(frames);
            directory = directory.resolve("flows");
            for (FlowFrame frame : frames) directory = directory.resolve(frame.invocationId).resolve("actions");
        }
        directory = directory.resolve(actionId);
        directory = directory.normalize();
        if (!directory.startsWith(caseOutputDir.normalize())) throw new IllegalArgumentException("Action output directory escapes case root: " + actionId);
        return directory;
    }

    public String scopedArtifactPath(String actionId, String configuredPath) {
        if (!inFlow()) return configuredPath;
        Path relative = IdentifierValidator.relativePath(configuredPath, "Flow action artifact path");
        Path target = actionOutputDir(actionId).resolve(relative).normalize();
        Path root = caseLogDirectory().toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(root)) throw new IllegalArgumentException("Flow artifact path escapes Case output directory: " + configuredPath);
        return root.relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public void beginFlow(String flowId, String invocationId) {
        Map<String, Object> previous = metaNode.get("FLOW") instanceof Map
                ? new LinkedHashMap<String, Object>((Map<String, Object>) metaNode.get("FLOW")) : null;
        // EXEC.ACTIONS is a scope-local namespace.  Keep the parent map as the
        // owner of its published Actions, but expose only a fresh Flow map while
        // the Flow is executing.  The same internal Action IDs can therefore be
        // reused by repeated or nested Flow invocations without collision.
        FlowFrame frame = new FlowFrame(flowId, invocationId, flowScopes.size() + 1,
                previous, currentActions, new LinkedHashMap<String, Object>(actionsView));
        flowScopes.push(frame);
        actionsView.clear();
        currentActions = frame.actions;
        setComponentMetadata("FLOW", mapOf("id", flowId, "invocationId", invocationId));
    }

    public FlowEvidence finishFlow() {
        if (flowScopes.isEmpty()) throw new IllegalStateException("No active Flow scope");
        FlowFrame frame = flowScopes.pop();
        Map<String, Object> actions = new LinkedHashMap<String, Object>(frame.actions);
        actionsView.clear();
        actionsView.putAll(frame.previousVisibleActions);
        currentActions = frame.previousActions;
        if (frame.previousFlow == null) metaNode.remove("FLOW");
        else metaNode.put("FLOW", frame.previousFlow);
        return new FlowEvidence(frame.flow, actions);
    }

    public boolean inFlow() { return !flowScopes.isEmpty(); }
    public att.validation.DiagnosticContext diagnosticContext() {
        java.util.List<String> chain = new java.util.ArrayList<String>();
        java.util.Iterator<FlowFrame> frames = flowScopes.descendingIterator();
        while (frames.hasNext()) {
            FlowFrame frame = frames.next();
            chain.add(frame.invocationId + " -> " + frame.flow.get("id"));
        }
        Object workbook = caseNode.get("workbook");
        return new att.validation.DiagnosticContext(workbook == null ? null : String.valueOf(workbook),
                String.valueOf(caseNode.get("caseId")), currentStage,
                flowScopes.isEmpty() ? null : String.valueOf(flowScopes.peek().flow.get("id")), chain);
    }
    public String qualifiedActionId(String actionId) {
        if (flowScopes.isEmpty()) return actionId;
        java.util.List<FlowFrame> frames = new java.util.ArrayList<FlowFrame>(flowScopes);
        java.util.Collections.reverse(frames);
        StringBuilder result = new StringBuilder();
        for (FlowFrame frame : frames) { if (result.length() > 0) result.append('.'); result.append(frame.invocationId); }
        if (result.length() > 0) result.append('.');
        return result.append(actionId).toString();
    }

    public static final class FlowEvidence {
        private final Map<String, Object> flow, actions;
        private FlowEvidence(Map<String, Object> flow, Map<String, Object> actions) {
            this.flow = new LinkedHashMap<String, Object>(flow); this.actions = new LinkedHashMap<String, Object>(actions);
        }
        public Map<String, Object> flow() { return flow; }
        public Map<String, Object> actions() { return actions; }
    }

    private static final class FlowFrame {
        private final String invocationId;
        private final Map<String, Object> previousFlow;
        private final Map<String, Object> previousActions;
        private final Map<String, Object> previousVisibleActions;
        private final Map<String, Object> actions = new LinkedHashMap<String, Object>();
        private final Map<String, Object> flow = new LinkedHashMap<String, Object>();
        private FlowFrame(String flowId, String invocationId, int depth, Map<String, Object> previousFlow,
                          Map<String, Object> previousActions,
                          Map<String, Object> previousVisibleActions) {
            this.invocationId = invocationId;
            this.previousFlow = previousFlow;
            this.previousActions = previousActions;
            this.previousVisibleActions = previousVisibleActions;
            flow.put("id", flowId); flow.put("invocationId", invocationId); flow.put("depth", Integer.valueOf(depth));
        }
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
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<String>();
        for (String path : readablePaths().keySet()) paths.add(displayPath(path));
        java.util.List<String> result = new java.util.ArrayList<String>(paths);
        java.util.Collections.sort(result);
        return result;
    }

    private Map<String, Object> canonicalPaths() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        collectCanonical(canonicalContextRoot(), "", result, new java.util.IdentityHashMap<Object, Boolean>());
        return result;
    }

    private Map<String, Object> transientPaths() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        collectCanonical(transientRoot(), "", result, new java.util.IdentityHashMap<Object, Boolean>());
        return result;
    }

    private Map<String, Object> legacyEvidencePaths() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        // Preserve rootless shorthand for the historical CASE.STAGES evidence
        // view without making that history a canonical node or copying any of
        // its mutable state.
        result.put("CASE.STAGES", stagesNode);
        collectLegacyStageShorthand(stagesNode, "CASE.STAGES", result,
                new java.util.IdentityHashMap<Object, Boolean>());
        return result;
    }

    /** All paths available to rootless shorthand resolution, grouped by contract. */
    private Map<String, Object> readablePaths() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll(canonicalPaths());
        result.putAll(transientPaths());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void collectLegacyStageShorthand(Object value, String prefix, Map<String, Object> output,
                                                    java.util.IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, Boolean.TRUE) != null) return;
        try {
            if (!(value instanceof Map) && !(value instanceof java.util.List)) return;
            if (value instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    // Completed Action evidence is already canonical under
                    // EXEC.ACTIONS. Keep the explicit CASE.STAGES history
                    // path, but do not make the same Action look like a
                    // second rootless shorthand candidate.
                    if ("ACTIONS".equals(key) && prefix.endsWith(".TEMPLATE")) continue;
                    String path = appendPath(prefix, Segment.key(key));
                    output.put(path, entry.getValue());
                    collectLegacyStageShorthand(entry.getValue(), path, output, active);
                }
            } else {
                java.util.List<?> list = (java.util.List<?>) value;
                for (int index = 0; index < list.size(); index++) {
                    String path = appendPath(prefix, Segment.index(index));
                    output.put(path, list.get(index));
                    collectLegacyStageShorthand(list.get(index), path, output, active);
                }
            }
        } finally {
            active.remove(value);
        }
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
            if (current == DEFERRED_VALIDATION_VALUE) return Resolution.deferred(currentPath);
            if (current instanceof Map && segment.key != null) {
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) current;
                if (!map.containsKey(segment.key)) return Resolution.missing(currentPath, segment.display());
                current = map.get(segment.key);
            } else if (current instanceof java.util.List && segment.index != null) {
                java.util.List<?> list = (java.util.List<?>) current;
                if (segment.index.intValue() < 0 || segment.index.intValue() >= list.size()) return Resolution.missing(currentPath, segment.display());
                current = list.get(segment.index.intValue());
            } else if (current == null) return Resolution.nullIntermediate(currentPath, segment.display());
            else if (current instanceof java.util.List) return Resolution.invalidPath(currentPath, "expected a numeric list index but found " + segment.display());
            else return Resolution.invalidPath(currentPath, "value is a scalar and cannot contain '" + segment.display() + "'");
            currentPath = appendPath("<root>".equals(currentPath) ? "" : currentPath, segment);
        }
        return current == DEFERRED_VALIDATION_VALUE
                ? Resolution.deferred(currentPath) : Resolution.found(current, currentPath);
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
                String node = displayPath(renderPath(full.subList(0, start + matched)));
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

    private static java.util.List<Segment> parsePath(String path) {
        if (path == null) throw new IllegalArgumentException("Context path is null");
        java.util.List<Segment> result = new java.util.ArrayList<Segment>();
        int position = 0;
        while (position < path.length()) {
            if (path.charAt(position) == '.') {
                if (result.isEmpty() || position + 1 >= path.length() || path.charAt(position + 1) == '.') {
                    throw new IllegalArgumentException("Empty path segment");
                }
                position++;
                continue;
            }
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
            if (key.indexOf('?') >= 0) throw new IllegalArgumentException("Invalid optional marker");
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

    /** Canonical spelling for diagnostics and suggestions. Legacy paths remain explicit. */
    private static String displayPath(String path) {
        return path;
    }

    private static String appendPath(String prefix, Segment segment) {
        if (segment == null) return prefix;
        if (segment.index != null) return prefix + "[" + segment.index + "]";
        if (simpleKey(segment.key)) return prefix.isEmpty() ? segment.key : prefix + "." + segment.key;
        return prefix + "['" + segment.key.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private static boolean simpleKey(String key) { return key != null && key.matches("[A-Za-z_][A-Za-z0-9_-]*"); }

    private enum ResolutionStatus { FOUND, DEFERRED, MISSING, NULL_INTERMEDIATE, INVALID_PATH, AMBIGUOUS }

    private static final class Resolution {
        private final ResolutionStatus status; private final Object value; private final String canonicalPath;
        private final String currentNode; private final String missingSegment; private final String reason; private final java.util.List<String> candidates;
        private Resolution(ResolutionStatus status, Object value, String canonicalPath, String currentNode,
                           String missingSegment, String reason, java.util.List<String> candidates) {
            this.status = status; this.value = value; this.canonicalPath = canonicalPath; this.currentNode = currentNode;
            this.missingSegment = missingSegment; this.reason = reason; this.candidates = candidates;
        }
        private static Resolution found(Object value, String canonicalPath) { return new Resolution(ResolutionStatus.FOUND, value, canonicalPath, null, null, null, java.util.Collections.<String>emptyList()); }
        private static Resolution deferred(String canonicalPath) { return new Resolution(ResolutionStatus.DEFERRED, null, canonicalPath, null, null, null, java.util.Collections.<String>emptyList()); }
        private static Resolution missing(String currentNode, String missingSegment) { return new Resolution(ResolutionStatus.MISSING, null, null, currentNode, missingSegment, null, java.util.Collections.<String>emptyList()); }
        private static Resolution nullIntermediate(String currentNode, String missingSegment) { return new Resolution(ResolutionStatus.NULL_INTERMEDIATE, null, null, currentNode, missingSegment, "value is null", java.util.Collections.<String>emptyList()); }
        private static Resolution invalidPath(String currentNode, String reason) { return new Resolution(ResolutionStatus.INVALID_PATH, null, null, currentNode, null, reason, java.util.Collections.<String>emptyList()); }
        private static Resolution invalidPath(String currentNode, String missingSegment, String reason) { return new Resolution(ResolutionStatus.INVALID_PATH, null, null, currentNode, missingSegment, reason, java.util.Collections.<String>emptyList()); }
        private static Resolution ambiguous(java.util.List<String> candidates) { return new Resolution(ResolutionStatus.AMBIGUOUS, null, null, "<root>", null, null, new java.util.ArrayList<String>(candidates)); }
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
            String legacy = legacySuggestionPath(candidate);
            if (legacy != null) {
                int legacyDistance = levenshtein(requested, legacy);
                if (legacyDistance < current) current = legacyDistance;
            }
            if (current < distance) { distance = current; best = candidate; }
        }
        int threshold = Math.max(2, requested == null ? 2 : requested.length() / 4);
        return distance <= threshold ? best : null;
    }

    /** Matches old explicit roots for typo hints while returning the canonical path. */
    private static String legacySuggestionPath(String canonical) {
        if (canonical == null) return null;
        if (canonical.startsWith("META.SOURCE.")) return "CASE" + canonical.substring("META.SOURCE".length());
        if (canonical.startsWith("EXEC.INPUT.")) return "CASE" + canonical.substring("EXEC.INPUT".length());
        if (canonical.startsWith("EXEC.VARS.")) return "CASE.VARS" + canonical.substring("EXEC.VARS".length());
        if (canonical.startsWith("EXEC.ACTIONS.")) return "ACTIONS" + canonical.substring("EXEC.ACTIONS".length());
        if ("EXEC.ID".equals(canonical)) return "RUN.id";
        if ("EXEC.OUTPUT_DIR".equals(canonical)) return "CASE.outputDirectory";
        return null;
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
