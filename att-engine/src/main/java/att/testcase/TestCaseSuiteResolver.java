package att.testcase;

import att.validation.Diagnostic;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Resolves a deterministic implicit Suite over bounded native Definitions. */
public final class TestCaseSuiteResolver {
    public static final int MAX_DEFINITIONS = 100;
    public static final int MAX_RESOLVED_CASES = 10000;
    public static final int MAX_SELECTED_CASES = TestCaseDefinitionResolver.MAX_PREVIEW_CASES;
    /** Public explicit selector bound shared with the execution-v2 schema and Server parser. */
    public static final int MAX_QUALIFIED_CASE_IDS = 1000;
    public static final int MAX_TAG_LENGTH = 128;
    private static final Pattern DEFINITION_ID = Pattern.compile("^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*$");
    private static final Pattern LOCAL_CASE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private final TestCaseDefinitionResolver definitions;

    public TestCaseSuiteResolver(Path activeSchemaFile) {
        definitions = new TestCaseDefinitionResolver(activeSchemaFile);
    }

    /**
     * Resolves all or a selected set of cases. Definitions are ordered by case-sensitive ID,
     * records keep source order, and tag filters use the same any-match semantics as legacy RUN.
     */
    public TestCaseSuiteResolutionResult resolve(Path packageRoot, String packageId,
            Path templatesRoot, Path testcasesRoot, List<String> definitionIds,
            List<String> qualifiedCaseIds, List<String> tags, List<String> excludeTags,
            boolean all, BooleanSupplier cancellation) throws Exception {
        List<String> definitionsInRequest = unique(definitionIds, "definitionIds", MAX_DEFINITIONS);
        if (definitionsInRequest.isEmpty()) throw invalid("At least one Definition ID is required");
        for (String id : definitionsInRequest) {
            if (id.length() > 126 || !DEFINITION_ID.matcher(id).matches())
                throw invalid("definitionIds contains an invalid Definition ID");
        }
        List<String> selectedIds = unique(qualifiedCaseIds, "qualifiedCaseIds", MAX_QUALIFIED_CASE_IDS);
        List<String> includedTags = unique(tags, "tags", 100, MAX_TAG_LENGTH);
        List<String> excludedTags = unique(excludeTags, "excludeTags", 100, MAX_TAG_LENGTH);
        if (selectedIds.isEmpty() && includedTags.isEmpty() && !all)
            throw invalid("Select all cases, at least one Qualified Case ID, or at least one tag");
        if (all && !selectedIds.isEmpty()) throw invalid("all and qualifiedCaseIds cannot be combined");

        Set<String> requestedDefinitions = new LinkedHashSet<String>(definitionsInRequest);
        Set<String> requestedCaseIds = new LinkedHashSet<String>();
        for (String id : selectedIds) {
            int separator = id.lastIndexOf('.');
            if (separator < 1 || separator == id.length() - 1)
                throw invalid("qualifiedCaseIds must use DefinitionID.localCaseID form");
            String definitionId = id.substring(0, separator);
            String localCaseId = id.substring(separator + 1);
            if (!DEFINITION_ID.matcher(definitionId).matches() || !LOCAL_CASE_ID.matcher(localCaseId).matches())
                throw invalid("qualifiedCaseIds contains an invalid Qualified Case ID");
            if (!requestedDefinitions.contains(definitionId))
                throw invalid("Every Qualified Case ID must belong to one of definitionIds");
            requestedCaseIds.add(id);
        }

        List<String> orderedDefinitions = new ArrayList<String>(requestedDefinitions);
        Collections.sort(orderedDefinitions, Comparator.naturalOrder());
        List<TestCaseResolutionResult> resolvedDefinitions = new ArrayList<TestCaseResolutionResult>();
        Map<String,ResolvedTestCase> resolvedByQualifiedId = new LinkedHashMap<String,ResolvedTestCase>();
        int resolvedCount = 0;
        for (String definitionId : orderedDefinitions) {
            checkCancelled(cancellation);
            TestCaseResolutionResult result = definitions.resolveByDefinitionId(packageRoot, packageId,
                    templatesRoot, testcasesRoot, definitionId,
                    new TestCaseDefinitionResolver.Options(TestCaseDefinitionResolver.MAX_RECORDS,
                            TestCaseDefinitionResolver.MAX_PREVIEW_CASES, null, cancellation));
            if (result.selectedCases().size() != result.selectedCount())
                throw invalid("Definition '" + definitionId + "' did not produce a complete bounded Case plan");
            resolvedCount = Math.addExact(resolvedCount, result.resolvedCount());
            if (resolvedCount > MAX_RESOLVED_CASES)
                throw invalid("Implicit Suite resolution exceeds " + MAX_RESOLVED_CASES + " Cases");
            resolvedDefinitions.add(result);
            // Suite selectors operate on the complete bounded plan, not the presentation preview.
            for (ResolvedTestCase testCase : result.selectedCases()) {
                if (resolvedByQualifiedId.put(testCase.qualifiedCaseId(), testCase) != null)
                    throw invalid("Implicit Suite contains duplicate Qualified Case ID: " + testCase.qualifiedCaseId());
            }
        }
        if (!requestedCaseIds.isEmpty()) {
            for (String id : requestedCaseIds) if (!resolvedByQualifiedId.containsKey(id))
                throw invalid("Selection contains an unknown Qualified Case ID: " + id);
        }
        Set<String> includeTagSet = new HashSet<String>(includedTags);
        Set<String> excludeTagSet = new HashSet<String>(excludedTags);
        List<ResolvedTestCase> selected = new ArrayList<ResolvedTestCase>();
        for (ResolvedTestCase testCase : resolvedByQualifiedId.values()) {
            if (!requestedCaseIds.isEmpty() && !requestedCaseIds.contains(testCase.qualifiedCaseId())) continue;
            if (!includeTagSet.isEmpty() && Collections.disjoint(testCase.tags(), includeTagSet)) continue;
            if (!excludeTagSet.isEmpty() && !Collections.disjoint(testCase.tags(), excludeTagSet)) continue;
            selected.add(testCase);
        }
        if (selected.size() > MAX_SELECTED_CASES)
            throw invalid("Implicit Suite selection exceeds " + MAX_SELECTED_CASES + " Cases");
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        if (selected.isEmpty()) diagnostics.add(new Diagnostic(DiagnosticCodes.SELECTION_EMPTY,
                Diagnostic.Severity.ERROR, "Case selection is empty", null, "selection", null,
                null, null, null, null, "Select at least one resolved Case before execution."));
        return new TestCaseSuiteResolutionResult(resolvedDefinitions, selected, resolvedCount, diagnostics, true);
    }

    private static List<String> unique(List<String> values, String field, int maximum) {
        return unique(values, field, maximum, 512);
    }

    private static List<String> unique(List<String> values, String field, int maximum, int maxValueLength) {
        if (values == null) return Collections.emptyList();
        if (values.size() > maximum) throw invalid(field + " must not exceed " + maximum + " entries");
        List<String> copy = new ArrayList<String>(values.size());
        Set<String> seen = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty() || value.length() > maxValueLength
                    || value.chars().anyMatch(Character::isISOControl))
                throw invalid(field + " contains an invalid value");
            if (!seen.add(value)) throw invalid(field + " contains duplicate values: " + value);
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static void checkCancelled(BooleanSupplier cancellation) {
        if (Thread.currentThread().isInterrupted() || (cancellation != null && cancellation.getAsBoolean()))
            throw new java.util.concurrent.CancellationException("Implicit Suite resolution was cancelled");
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
