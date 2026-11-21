package att.validation;

import att.config.YamlSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestCaseDefinitionSchemaTest {
    private static final String SCHEMA_VERSION = "att-testcase/v1";

    @Test void validatesProposedExamplesWithoutRegisteringTheContractAtRuntime() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path proposed = root.resolve("schemas/proposed");
        Path schema = proposed.resolve("att-testcase-v1.schema.json");
        String proposedCatalog = read(proposed.resolve("catalog.yaml"));
        String sourceCatalog = read(root.resolve("schemas/catalog.yaml"));
        String engineCatalog = read(root.resolve("att-engine/src/main/resources/att-schemas/catalog.yaml"));

        assertTrue(proposedCatalog.contains("status: proposed"));
        assertTrue(proposedCatalog.contains(SCHEMA_VERSION + ": att-testcase-v1.schema.json"));
        assertFalse(sourceCatalog.contains(SCHEMA_VERSION));
        assertFalse(engineCatalog.contains(SCHEMA_VERSION));
        assertThrows(DiagnosticException.class, () -> SchemaFiles.resolveVersion(root, SCHEMA_VERSION));

        assertValid(root, schema, "inline-single.yaml");
        assertValid(root, schema, "inline-multi.yaml");
        assertValid(root, schema, "excel.yaml");

        assertInvalid(root, schema, "invalid-ambiguous-case-id.yaml");
        assertInvalid(root, schema, "invalid-runtime-case-id-expression.yaml");
        assertInvalid(root, schema, "invalid-inline-actions.yaml");
        assertInvalid(root, schema, "invalid-inline-steps.yaml");
        assertInvalid(root, schema, "invalid-empty-records.yaml");
        assertInvalid(root, schema, "invalid-flow-ref.yaml");
        assertInvalid(root, schema, "invalid-unsupported-data-source-type.yaml");
        assertInvalid(root, schema, "invalid-data-columns-map.yaml");
        assertInvalid(root, schema, "invalid-runtime-input-root.yaml");
        assertInvalid(root, schema, "invalid-inline-report-template.yaml");
        assertInvalid(root, schema, "invalid-reserved-case-id-lowercase.yaml");
        assertInvalid(root, schema, "invalid-reserved-case-id-mixed-case.yaml");
        assertInvalid(root, schema, "invalid-reserved-definition-id-lowercase.yaml");
        assertInvalid(root, schema, "invalid-reserved-definition-id-mixed-case.yaml");
    }

    @Test void recordsDuplicateIdsAsFutureResolverSemantics() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path schema = root.resolve("schemas/proposed/att-testcase-v1.schema.json");
        Path fixture = root.resolve("docs/adr/examples/att-testcase-v1/resolution-invalid-duplicate-ids.yaml");
        Object parsed = YamlSupport.load(fixture);

        // JSON Schema checks each ID's syntax. Cross-record uniqueness belongs to the future Resolver.
        assertDoesNotThrow(() -> JsonSchemaVerifier.verify(schema, parsed));
        Map<?, ?> definition = (Map<?, ?>) parsed;
        Map<?, ?> source = (Map<?, ?>) definition.get("dataSource");
        List<?> records = (List<?>) source.get("records");
        Map<?, ?> first = (Map<?, ?>) records.get(0);
        Map<?, ?> second = (Map<?, ?>) records.get(1);
        String firstRecordId = (String) first.get("recordId");
        String secondRecordId = (String) second.get("recordId");
        assertEquals("CaseA", firstRecordId);
        assertEquals("casea", secondRecordId);
        assertNotEquals(firstRecordId, secondRecordId);
        assertEquals(firstRecordId.toLowerCase(Locale.ROOT), secondRecordId.toLowerCase(Locale.ROOT));

        String firstResolvedId = (String) ((Map<?, ?>) first.get("data")).get("caseKey");
        String secondResolvedId = (String) ((Map<?, ?>) second.get("data")).get("caseKey");
        assertEquals("CaseA", firstResolvedId);
        assertEquals("casea", secondResolvedId);
        assertNotEquals(firstResolvedId, secondResolvedId);
        assertEquals(firstResolvedId.toLowerCase(Locale.ROOT), secondResolvedId.toLowerCase(Locale.ROOT));
    }

    private static void assertValid(Path root, Path schema, String fixture) throws Exception {
        Path document = root.resolve("docs/adr/examples/att-testcase-v1").resolve(fixture);
        JsonSchemaVerifier.verify(schema, YamlSupport.load(document));
    }

    private static void assertInvalid(Path root, Path schema, String fixture) {
        Path document = root.resolve("docs/adr/examples/att-testcase-v1").resolve(fixture);
        assertThrows(IllegalArgumentException.class, () ->
                JsonSchemaVerifier.verify(schema, YamlSupport.load(document)), fixture);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
