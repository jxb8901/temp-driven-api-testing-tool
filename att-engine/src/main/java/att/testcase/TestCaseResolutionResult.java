package att.testcase;

import att.validation.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable resolution outcome for one Definition.
 * <p>
 * {@link #selectedCases()} is the complete bounded selected execution plan for Native RUN.
 * {@link #cases()} / {@link #previewCases()} are the presentation preview and may be truncated.
 * {@link #toMap()} serializes only that preview with a Definition metadata summary; it never embeds
 * unselected raw Data Source records. Full authored Definition content remains available via
 * {@link #definition()} for authoring-only callers.
 */
public final class TestCaseResolutionResult {
    private final TestCaseDefinition definition;
    private final String sourceReference;
    private final String sourceRevision;
    private final List<ResolvedTestCase> selectedCases;
    private final List<ResolvedTestCase> previewCases;
    private final int resolvedCount;
    private final int selectedCount;
    private final boolean truncated;
    private final List<Diagnostic> diagnostics;

    TestCaseResolutionResult(TestCaseDefinition definition, String sourceReference, String sourceRevision,
                             List<ResolvedTestCase> selectedCases, List<ResolvedTestCase> previewCases,
                             int resolvedCount, int selectedCount, boolean truncated,
                             List<Diagnostic> diagnostics) {
        this.definition = definition;
        this.sourceReference = sourceReference;
        this.sourceRevision = sourceRevision;
        this.selectedCases = Collections.unmodifiableList(new ArrayList<ResolvedTestCase>(selectedCases));
        this.previewCases = Collections.unmodifiableList(new ArrayList<ResolvedTestCase>(previewCases));
        this.resolvedCount = resolvedCount;
        this.selectedCount = selectedCount;
        this.truncated = truncated;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics));
    }

    public TestCaseDefinition definition() { return definition; }
    public String sourceReference() { return sourceReference; }
    public String sourceRevision() { return sourceRevision; }

    /** Complete bounded selected plan for execution consumers; never truncated by {@code previewLimit}. */
    public List<ResolvedTestCase> selectedCases() { return selectedCases; }

    /**
     * Bounded presentation preview. Prefer {@link #selectedCases()} for Native RUN.
     * Equivalent to {@link #previewCases()}.
     */
    public List<ResolvedTestCase> cases() { return previewCases; }

    /** Bounded presentation preview; may omit selected cases beyond {@code previewLimit}. */
    public List<ResolvedTestCase> previewCases() { return previewCases; }

    public int resolvedCount() { return resolvedCount; }
    public int selectedCount() { return selectedCount; }
    public boolean truncated() { return truncated; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public boolean emptySelection() { return selectedCount == 0; }

    /** Preview DTO: Definition metadata summary plus preview-authorized cases only. */
    public Map<String,Object> toMap() {
        List<Map<String,Object>> values = new ArrayList<Map<String,Object>>();
        for (ResolvedTestCase testCase : previewCases) values.add(testCase.toMap());
        List<Map<String,Object>> diagnosticValues = new ArrayList<Map<String,Object>>();
        for (Diagnostic diagnostic : diagnostics) diagnosticValues.add(diagnostic.toMap());
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("kind", "TestCaseResolutionPreview");
        result.put("definition", definitionSummary());
        result.put("sourceReference", sourceReference);
        result.put("sourceRevision", sourceRevision);
        result.put("resolvedCount", resolvedCount);
        result.put("selectedCount", selectedCount);
        result.put("previewCount", previewCases.size());
        result.put("truncated", truncated);
        result.put("cases", Collections.unmodifiableList(values));
        result.put("diagnostics", Collections.unmodifiableList(diagnosticValues));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Execution-plan DTO for the complete selected set. Includes the same Definition metadata
     * summary as the preview (no raw unselected Data Source records) and every selected Case.
     */
    public Map<String,Object> toSelectedPlanMap() {
        List<Map<String,Object>> values = new ArrayList<Map<String,Object>>();
        for (ResolvedTestCase testCase : selectedCases) values.add(testCase.toMap());
        List<Map<String,Object>> diagnosticValues = new ArrayList<Map<String,Object>>();
        for (Diagnostic diagnostic : diagnostics) diagnosticValues.add(diagnostic.toMap());
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("kind", "TestCaseSelectedPlan");
        result.put("definition", definitionSummary());
        result.put("sourceReference", sourceReference);
        result.put("sourceRevision", sourceRevision);
        result.put("resolvedCount", resolvedCount);
        result.put("selectedCount", selectedCount);
        result.put("truncated", false);
        result.put("cases", Collections.unmodifiableList(values));
        result.put("diagnostics", Collections.unmodifiableList(diagnosticValues));
        return Collections.unmodifiableMap(result);
    }

    Map<String,Object> definitionSummary() {
        Map<String,Object> summary = new LinkedHashMap<String,Object>();
        summary.put("kind", "TestCaseDefinitionSummary");
        summary.put("schemaVersion", definition.schemaVersion());
        summary.put("id", definition.id());
        if (definition.name() != null) summary.put("name", definition.name());
        if (definition.description() != null) summary.put("description", definition.description());
        summary.put("flow", definition.flowId());
        if (!definition.tags().isEmpty()) summary.put("tags", definition.tags());
        if (definition.caseIdExpression() != null) summary.put("caseIdExpression", definition.caseIdExpression());
        if (definition.report() != null) summary.put("report", definition.report());
        if (definition.dataSourceType() != null) {
            Map<String,Object> source = new LinkedHashMap<String,Object>();
            source.put("type", definition.dataSourceType());
            source.put("recordCount", Integer.valueOf(definition.records().size()));
            summary.put("dataSource", Collections.unmodifiableMap(source));
        } else {
            summary.put("inputCount", Integer.valueOf(
                    definition.directInputs() == null ? 0 : definition.directInputs().size()));
        }
        summary.putAll(definition.extensions());
        return Collections.unmodifiableMap(summary);
    }
}
