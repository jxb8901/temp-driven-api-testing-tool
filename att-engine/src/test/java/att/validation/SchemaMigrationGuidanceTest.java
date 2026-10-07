package att.validation;

import att.config.FrameworkConfigLoader;
import att.flow.FlowRegistry;
import att.template.StageTemplateLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationGuidanceTest {
    @TempDir Path root;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(root); }

    @Test void currentAndHistoricalSchemasHaveSingleLocations() throws Exception {
        Path packageRoot = Paths.get("").toAbsolutePath();
        assertEquals(packageRoot.resolve("schemas/history/att-flow-v3.1.schema.json"),
                SchemaFiles.resolve(packageRoot, "att-flow-v3.1.schema.json"));
        assertEquals(packageRoot.resolve("schemas/history/att-flow-v3.0.schema.json"),
                SchemaFiles.resolve(packageRoot, "att-flow-v3.0.schema.json"));
        assertEquals(packageRoot.resolve("schemas/history/att-flow-v3.5.schema.json"),
                SchemaFiles.resolve(packageRoot, "att-flow-v3.5.schema.json"));
        assertEquals(packageRoot.resolve("schemas/history/att-flow-v3.4.schema.json"),
                SchemaFiles.resolve(packageRoot, "att-flow-v3.4.schema.json"));
        assertEquals(packageRoot.resolve("schemas/history/att-flow-v3.3.schema.json"),
                SchemaFiles.resolve(packageRoot, "att-flow-v3.3.schema.json"));
        assertEquals(packageRoot.resolve("schemas/history/att-template-v3.3.schema.json"),
                SchemaFiles.resolve(packageRoot, "att-template-v3.3.schema.json"));
        assertFalse(Files.exists(packageRoot.resolve("schemas/att-flow-v3.0.schema.json")));
        assertFalse(Files.exists(packageRoot.resolve("schemas/att-flow-v3.3.schema.json")));
        assertTrue(Files.exists(packageRoot.resolve("schemas/history/att-flow-v3.1.schema.json")));
        assertTrue(Files.exists(packageRoot.resolve("schemas/history/att-flow-v3.3.schema.json")));
        String catalog = new String(Files.readAllBytes(packageRoot.resolve("schemas/catalog.yaml")), StandardCharsets.UTF_8);
        assertTrue(catalog.contains("history/att-flow-v3.0.schema.json"));
        assertTrue(catalog.contains("globalConfig: att-config-v2.12.schema.json"));
    }

    @Test void olderConfigWithNewFieldHasProvenMigrationAndSource() throws Exception {
        Path file = root.resolve("config/config.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, ("schemaVersion: att-config/v2.7\n"
                + "httphelpers: [config/httphelpers/api.yaml]\n").getBytes(StandardCharsets.UTF_8));
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> new FrameworkConfigLoader().load(file, root));
        assertTrue(error.detail().contains("att-config/v2.7"));
        assertTrue(error.detail().contains("att-config/v2.12"));
        assertTrue(error.detail().contains("Upgrade schemaVersion"));
        assertTrue(error.detail().contains("Schema validation failed"));
        assertEquals(file.toString(), error.file());
        assertFalse(error.schemaViolations().isEmpty());
        assertNotNull(error.source());
    }

    @Test void unrelatedInvalidFieldsDoNotClaimVersionBumpIsEnough() throws Exception {
        Path oldSchema = root.resolve("schemas/history/att-config-v2.8.schema.json");
        Path currentSchema = root.resolve("schemas/att-config-v2.12.schema.json");
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("schemaVersion", "att-config/v2.8");
        descriptor.put("timeoutMs", -1);
        SchemaMigrationGuidance.MigrationException error = assertThrows(SchemaMigrationGuidance.MigrationException.class,
                () -> SchemaMigrationGuidance.verify(oldSchema, currentSchema, descriptor,
                        "att-config/v2.8", "att-config/v2.12"));
        assertTrue(error.getMessage().contains("timeoutMs"));
        assertFalse(error.getMessage().contains("Upgrade schemaVersion"));
        assertTrue(error.getMessage().contains("version change alone is not sufficient"));
        assertNotNull(JsonSchemaVerifier.SchemaValidationException.find(error));
    }

    @Test void flowResultUnderOldVersionSuggestsNewVersionWithoutDroppingViolation() throws Exception {
        Path file = root.resolve("templates/flows/sample/flow.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, ("schemaVersion: att-flow/v3.0\nid: sample.flow.v1\nname: Sample\n"
                + "description: Sample flow\nactions:\n"
                + "  fetch: {type: tool, call: \"#{upper('ok')}\", result: {format: text}}\n")
                .getBytes(StandardCharsets.UTF_8));
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(error.detail().contains("att-flow/v3.0"));
        assertTrue(error.detail().contains("att-flow/v3.6"));
        assertFalse(error.detail().contains("Upgrade schemaVersion"));
        assertTrue(error.detail().contains("version change alone is not sufficient"));
        assertTrue(error.detail().contains("result"));
        assertTrue(error.field().contains("result"), error.detail());
        assertFalse(error.schemaViolations().isEmpty());
        assertNotNull(error.source());
        assertEquals(6, error.source().line());
    }

    @Test void templateResultUnderOldVersionSuggestsCurrentSchema() throws Exception {
        Path file = root.resolve("templates/sample/template.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, ("schemaVersion: att-template/v3.0\nname: sample\ndescription: sample template\n"
                + "actions:\n  fetch: {type: tool, call: \"#{upper('ok')}\", result: {format: text}}\n")
                .getBytes(StandardCharsets.UTF_8));
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> new StageTemplateLoader(root, Paths.get("templates")).load("sample"));
        assertTrue(error.detail().contains("att-template/v3.0"));
        assertTrue(error.detail().contains("att-template/v3.6"));
        assertFalse(error.detail().contains("Upgrade schemaVersion"));
        assertTrue(error.detail().contains("version change alone is not sufficient"));
        assertTrue(error.field().contains("result"), error.detail());
        assertFalse(error.schemaViolations().isEmpty());
        assertEquals(file.toRealPath().toString(), error.file());
        assertEquals(5, error.source().line());
    }

    @Test void flowRenderMigrationDoesNotSuggestRemovedResultPersistenceFields() throws Exception {
        Path file = root.resolve("templates/flows/render-migration/flow.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, ("schemaVersion: att-flow/v3.0\nid: render.migration.v1\nname: Render migration\n"
                + "description: Render migration\nactions:\n"
                + "  render: {type: render, payload: request.xml, renderAs: file}\n")
                .getBytes(StandardCharsets.UTF_8));
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(error.suggestion().contains("String"), error.suggestion());
        assertTrue(error.suggestion().contains("exact rendered"), error.suggestion());
        assertFalse(error.suggestion().contains("result:"), error.suggestion());
        assertFalse(error.suggestion().contains("path:"), error.suggestion());
    }

    @Test void missingRegisteredHistoricalSchemaFailsWithoutFallbackAndCatalogScanFindsUnusedGaps() throws Exception {
        Path historical = root.resolve("schemas/history/att-flow-v3.0.schema.json");
        Files.delete(historical);
        DiagnosticException pointOfUse = assertThrows(DiagnosticException.class,
                () -> SchemaFiles.resolve(root, "att-flow-v3.0.schema.json"));
        assertEquals(DiagnosticCodes.PACKAGE_INVALID, pointOfUse.code());
        assertTrue(pointOfUse.detail().contains("schemas/history/att-flow-v3.0.schema.json"));
        assertTrue(pointOfUse.getMessage().contains("will not skip validation")
                || pointOfUse.detail().contains("will not skip validation"));
        DiagnosticException proactive = assertThrows(DiagnosticException.class, () -> SchemaFiles.validateCatalog(root));
        assertEquals(DiagnosticCodes.PACKAGE_INVALID, proactive.code());
        assertTrue(proactive.detail().contains("att-flow-v3.0.schema.json"));
    }
}
