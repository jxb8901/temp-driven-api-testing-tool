package att.validation;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticTest {
    @Test void physicalSourceDoesNotReplaceExcelLocationAndSurvivesWrapping() {
        IllegalArgumentException cause = new IllegalArgumentException("bad token");
        SourceLocation source = new SourceLocation("flows/a/flow.yaml", 12, 9, 12, 10, "        )");
        DiagnosticException inner = new DiagnosticException(DiagnosticCodes.TEMPLATE_INVALID, "Invalid expression", "Expected operand",
                "flows/a/flow.yaml", "actions.invoke.call", "Cases", 7, 3, "Flow A", "invoke", "Supply an operand.", cause)
                .withSource(source).withContext(new DiagnosticContext("cases.xlsx", "TC1", "verify", "a.v1", Arrays.asList("main", "a.v1")));
        DiagnosticException outer = DiagnosticException.wrap(DiagnosticCodes.RUN_FAILED, "Run failed", inner,
                "template.yaml", "actions.flowCall", "Retry").atSource("template.yaml", "actions.flowCall");
        assertSame(source, outer.source());
        assertEquals("flows/a/flow.yaml", outer.file());
        assertEquals("actions.invoke.call", outer.field());
        assertEquals(7, outer.row());
        assertEquals(3, outer.column());
        assertEquals("a.v1", outer.context().toMap().get("flowId"));
        assertNotNull(outer.getCause());
        Map<String, Object> json = outer.toDiagnostic().toMap();
        assertEquals(12, ((Map<?, ?>) json.get("source")).get("line"));
        assertEquals(9, ((Map<?, ?>) json.get("source")).get("column"));
        assertTrue(outer.format().contains("sourceColumn=9"));
        assertTrue(outer.format().contains("row=7, column=3"));
        assertTrue(outer.format().contains("        ^"));
        assertTrue(DiagnosticRenderer.jsonError(outer.toDiagnostic()).contains("\"field\":\"actions.invoke.call\""));
    }

    @Test void semanticContextPathBecomesExactSourceFieldOnlyOnce() {
        DiagnosticException error = new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID, "Missing value", "requestedPath: CASE.x",
                null, "CASE.x", null, null, null, null, null, null, null);
        DiagnosticException located = error.atSource("flow.yaml", "actions.invoke.call").atSource("template.yaml", "actions.outer");
        assertEquals("flow.yaml", located.file());
        assertEquals("actions.invoke.call", located.field());
        assertEquals("requestedPath: CASE.x", located.detail());
    }

    @Test void sourceRangeRejectsInvalidCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new SourceLocation("a", 0, 1, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new SourceLocation("a", 2, 4, 2, 3, null));
    }

    @Test void packageDiagnosticsDoNotExposePhysicalPackageRoot() {
        java.nio.file.Path root = java.nio.file.Paths.get("/tmp/att-package");
        String physical = root.resolve("config/helpers/db.yaml").toString();
        DiagnosticException error = new DiagnosticException(DiagnosticCodes.CONFIG_INVALID, "Invalid helper",
                "Could not read " + physical, physical, "dbhelpers", null, null, null, null, null, null, null)
                .withSource(new SourceLocation(physical, 2, 1, 2, 4, "bad: " + physical))
                .withContext(new DiagnosticContext(physical, "case", null, null, null));

        DiagnosticException safe = error.forPackage(root);
        assertEquals("$ATT_HOME/config/helpers/db.yaml", safe.file());
        assertEquals("$ATT_HOME/config/helpers/db.yaml", safe.source().file());
        assertEquals("$ATT_HOME/config/helpers/db.yaml", safe.context().caseFile());
        assertFalse(safe.getMessage().contains(root.toString()));
        assertFalse(safe.source().excerpt().contains(root.toString()));
    }
}
