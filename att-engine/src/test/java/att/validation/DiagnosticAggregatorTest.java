package att.validation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticAggregatorTest {
    @Test void sameSyntaxFailureAcrossStaticAndCaseValidationIsOneDiagnostic() {
        String syntax = "Expected an expression operand but found operator '/' at expression offset 122";
        SourceLocation source = new SourceLocation("templates/flows/DDO01/flow.yaml", 68, 11, 68, 12, "  call: >-");
        Diagnostic firstCase = diagnostic("Invalid runtime-rendered value in template action: " + syntax + "; Case source: testcase/ddo.xlsx!Sheet:3",
                "templates/flows/DDO01/flow.yaml", "actions.extract.call", "Cases", 3, "DDO.1", source);
        Diagnostic secondCase = diagnostic("Invalid runtime-rendered value in template action: " + syntax + "; Case source: testcase/ddo.xlsx!Sheet:4",
                "templates/flows/DDO01/flow.yaml", "actions.extract.call", "Cases", 4, "DDO.2", source);
        Diagnostic staticPass = new Diagnostic(DiagnosticCodes.TEMPLATE_INVALID, Diagnostic.Severity.ERROR, syntax,
                "templates/flows/DDO01/flow.yaml", "actions.extract.call", null, null, null, "DDO", "extract",
                "Check the template/action field and every referenced Context variable or inline call.",
                "Expected an expression operand but found operator '/' at expression offset 122", syntax, source,
                DiagnosticContext.EMPTY);

        PackageValidator.ValidationSummary summary = new PackageValidator.ValidationSummary(
                "package", 1, 2, 1, 0, Arrays.asList(firstCase, secondCase, staticPass));

        assertEquals(1, summary.diagnostics.size());
        Diagnostic merged = summary.diagnostics.get(0);
        assertEquals(3, merged.occurrences());
        assertEquals(2, merged.affectedCases().size());
        assertEquals(1, summary.errors());
        assertEquals(3, summary.errorOccurrences());
        String rendered = DiagnosticRenderer.validation(merged);
        assertTrue(rendered.contains("occurrences: 3"));
        assertTrue(rendered.contains("row=3"));
        assertTrue(rendered.contains("row=4"));
    }

    @Test void differentSyntaxOffsetsRemainSeparate() {
        String prefix = "Expected an expression operand but found operator '/ at expression offset ";
        Diagnostic one = new Diagnostic(DiagnosticCodes.TEMPLATE_INVALID, Diagnostic.Severity.ERROR,
                prefix + "10", "templates/a/flow.yaml", "actions.one.call", null, null, null, "A", "one", null);
        Diagnostic two = new Diagnostic(DiagnosticCodes.TEMPLATE_INVALID, Diagnostic.Severity.ERROR,
                prefix + "20", "templates/a/flow.yaml", "actions.two.call", null, null, null, "A", "two", null);

        List<Diagnostic> result = DiagnosticAggregator.aggregate(Arrays.asList(one, two));
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).occurrences());
        assertEquals(1, result.get(1).occurrences());
    }

    @Test void nonSyntaxCaseDiagnosticsGroupOnlyWhenTheirStableIdentityMatches() {
        Diagnostic first = new Diagnostic(DiagnosticCodes.CONTEXT_INVALID, Diagnostic.Severity.ERROR,
                "Unknown Context '${CASE.missing}'", "templates/a/template.yaml", "actions.verify.call", "Cases", 3, 1, "A", "verify", null,
                "Unknown Context '${CASE.missing}'", "Use the declared Case field.", null,
                new DiagnosticContext("testcase/a.xlsx", "A.1", null, null, Collections.<String>emptyList()));
        Diagnostic second = new Diagnostic(DiagnosticCodes.CONTEXT_INVALID, Diagnostic.Severity.ERROR,
                "Unknown Context '${CASE.missing}'", "templates/a/template.yaml", "actions.verify.call", "Cases", 4, 1, "A", "verify", null,
                "Unknown Context '${CASE.missing}'", "Use the declared Case field.", null,
                new DiagnosticContext("testcase/a.xlsx", "A.2", null, null, Collections.<String>emptyList()));
        Diagnostic different = new Diagnostic(DiagnosticCodes.CONTEXT_INVALID, Diagnostic.Severity.ERROR,
                "Unknown Context '${CASE.other}'", "templates/a/template.yaml", "actions.verify.call", "Cases", 5, 1, "A", "verify", null);

        List<Diagnostic> result = DiagnosticAggregator.aggregate(Arrays.asList(first, second, different));
        assertEquals(2, result.size());
        Diagnostic merged = result.get(0).occurrences() > 1 ? result.get(0) : result.get(1);
        assertEquals(2, merged.occurrences());
        assertEquals(2, merged.affectedCases().size());
    }

    @Test void unlocatedGenericDiagnosticsAreNotCollapsedByMessageOnly() {
        Diagnostic first = new Diagnostic(DiagnosticCodes.PACKAGE_INVALID, Diagnostic.Severity.ERROR,
                "Package validation failed", null, null, null, null, null, null, null, null);
        Diagnostic second = new Diagnostic(DiagnosticCodes.PACKAGE_INVALID, Diagnostic.Severity.ERROR,
                "Package validation failed", null, null, null, null, null, null, null, null);

        assertEquals(2, DiagnosticAggregator.aggregate(Arrays.asList(first, second)).size());
    }

    private Diagnostic diagnostic(String message, String file, String field, String sheet, int row,
                                  String caseId, SourceLocation source) {
        return new Diagnostic(DiagnosticCodes.TEMPLATE_INVALID, Diagnostic.Severity.ERROR, message,
                file, field, sheet, row, 11, "DDO", "extract", "Check the expression.",
                "Invalid runtime-rendered value in template action", message, source,
                new DiagnosticContext("testcase/ddo.xlsx", caseId, "invoke", null, Collections.<String>emptyList()));
    }
}
