package att.testcase;

import att.validation.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable aggregate selection across Definitions; the Suite is derived and is never an authored resource. */
public final class TestCaseSuiteResolutionResult {
    private final List<TestCaseResolutionResult> definitions;
    private final List<ResolvedTestCase> cases;
    private final int resolvedCount;
    private final List<Diagnostic> diagnostics;
    private final boolean implicitSuite;

    TestCaseSuiteResolutionResult(List<TestCaseResolutionResult> definitions,
            List<ResolvedTestCase> cases, int resolvedCount, List<Diagnostic> diagnostics, boolean implicitSuite) {
        this.definitions = Collections.unmodifiableList(new ArrayList<TestCaseResolutionResult>(definitions));
        this.cases = Collections.unmodifiableList(new ArrayList<ResolvedTestCase>(cases));
        this.resolvedCount = resolvedCount;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics));
        this.implicitSuite = implicitSuite;
    }

    public static TestCaseSuiteResolutionResult fromSingle(TestCaseResolutionResult resolution) {
        if (resolution == null) throw new IllegalArgumentException("A native Case resolution is required");
        return new TestCaseSuiteResolutionResult(Collections.singletonList(resolution), resolution.selectedCases(),
                resolution.resolvedCount(), resolution.diagnostics(), false);
    }

    public List<TestCaseResolutionResult> definitions() { return definitions; }
    public List<ResolvedTestCase> cases() { return cases; }
    public int resolvedCount() { return resolvedCount; }
    public int selectedCount() { return cases.size(); }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public boolean implicitSuite() { return implicitSuite; }
    public boolean emptySelection() { return cases.isEmpty(); }
    /** True only when a Definition's internal selected execution plan is incomplete; preview truncation is separate. */
    public boolean truncated() {
        for (TestCaseResolutionResult result : definitions)
            if (result.selectedCases().size() != result.selectedCount()) return true;
        return false;
    }

    public Map<String,Object> toMap() {
        List<Map<String,Object>> definitionValues = new ArrayList<Map<String,Object>>();
        for (TestCaseResolutionResult result : definitions) {
            Map<String,Object> value = new LinkedHashMap<String,Object>();
            value.put("definitionId", result.definition().id());
            value.put("sourceReference", result.sourceReference());
            value.put("sourceRevision", result.sourceRevision());
            value.put("resolvedCount", result.resolvedCount());
            definitionValues.add(Collections.unmodifiableMap(value));
        }
        List<Map<String,Object>> caseValues = new ArrayList<Map<String,Object>>();
        for (ResolvedTestCase testCase : cases) caseValues.add(testCase.toMap());
        List<Map<String,Object>> diagnosticValues = new ArrayList<Map<String,Object>>();
        for (Diagnostic diagnostic : diagnostics) diagnosticValues.add(diagnostic.toMap());
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("kind", "ImplicitTestCaseSuite");
        result.put("definitions", Collections.unmodifiableList(definitionValues));
        result.put("resolvedCount", resolvedCount);
        result.put("selectedCount", cases.size());
        result.put("cases", Collections.unmodifiableList(caseValues));
        result.put("diagnostics", Collections.unmodifiableList(diagnosticValues));
        return Collections.unmodifiableMap(result);
    }
}
