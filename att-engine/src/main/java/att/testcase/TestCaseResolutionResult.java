package att.testcase;

import att.validation.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable bounded preview result; an empty selection is explicit and is never a passing run. */
public final class TestCaseResolutionResult {
    private final TestCaseDefinition definition;
    private final String sourceReference;
    private final String sourceRevision;
    private final List<ResolvedTestCase> cases;
    private final int resolvedCount;
    private final int selectedCount;
    private final boolean truncated;
    private final List<Diagnostic> diagnostics;

    TestCaseResolutionResult(TestCaseDefinition definition, String sourceReference, String sourceRevision,
                             List<ResolvedTestCase> cases, int resolvedCount, int selectedCount,
                             boolean truncated, List<Diagnostic> diagnostics) {
        this.definition = definition;
        this.sourceReference = sourceReference;
        this.sourceRevision = sourceRevision;
        this.cases = Collections.unmodifiableList(new ArrayList<ResolvedTestCase>(cases));
        this.resolvedCount = resolvedCount;
        this.selectedCount = selectedCount;
        this.truncated = truncated;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics));
    }

    public TestCaseDefinition definition() { return definition; }
    public String sourceReference() { return sourceReference; }
    public String sourceRevision() { return sourceRevision; }
    public List<ResolvedTestCase> cases() { return cases; }
    public int resolvedCount() { return resolvedCount; }
    public int selectedCount() { return selectedCount; }
    public boolean truncated() { return truncated; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public boolean emptySelection() { return selectedCount == 0; }

    public Map<String,Object> toMap() {
        List<Map<String,Object>> values = new ArrayList<Map<String,Object>>();
        for (ResolvedTestCase testCase : cases) values.add(testCase.toMap());
        List<Map<String,Object>> diagnosticValues = new ArrayList<Map<String,Object>>();
        for (Diagnostic diagnostic : diagnostics) diagnosticValues.add(diagnostic.toMap());
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("kind", "TestCaseResolutionPreview");
        result.put("definition", definition.toMap());
        result.put("sourceReference", sourceReference);
        result.put("sourceRevision", sourceRevision);
        result.put("resolvedCount", resolvedCount);
        result.put("selectedCount", selectedCount);
        result.put("previewCount", cases.size());
        result.put("truncated", truncated);
        result.put("cases", Collections.unmodifiableList(values));
        result.put("diagnostics", Collections.unmodifiableList(diagnosticValues));
        return Collections.unmodifiableMap(result);
    }
}
