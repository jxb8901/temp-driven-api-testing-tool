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

    @Test void currentAndHistoricalSchemasHaveSingleLocations() throws Exception {
        Path packageRoot = Paths.get("").toAbsolutePath();
        assertEquals(packageRoot.resolve("schemas/att-flow-v3.1.schema.json"),
                SchemaFiles.resolve(packageRoot, "att-flow-v3.1.schema.json"));
        assertEquals(packageRoot.resolve("schemas/history/att-flow-v3.0.schema.json"),
                SchemaFiles.resolve(packageRoot, "att-flow-v3.0.schema.json"));
        assertFalse(Files.exists(packageRoot.resolve("schemas/att-flow-v3.0.schema.json")));
        assertFalse(Files.exists(packageRoot.resolve("schemas/history/att-flow-v3.1.schema.json")));
        String catalog = new String(Files.readAllBytes(packageRoot.resolve("schemas/catalog.yaml")), StandardCharsets.UTF_8);
        assertTrue(catalog.contains("history/att-flow-v3.0.schema.json"));
        assertTrue(catalog.contains("globalConfig: att-config-v2.8.schema.json"));
    }

    @Test void olderConfigWithNewFieldHasProvenMigrationAndSource() throws Exception {
        Path file = root.resolve("config/config.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, ("schemaVersion: att-config/v2.7\n"
                + "httphelpers: [config/httphelpers/api.yaml]\n").getBytes(StandardCharsets.UTF_8));
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> new FrameworkConfigLoader().load(file, root));
        assertTrue(error.detail().contains("att-config/v2.7"));
        assertTrue(error.detail().contains("att-config/v2.8"));
        assertTrue(error.detail().contains("Upgrade schemaVersion"));
        assertTrue(error.detail().contains("Schema validation failed"));
        assertEquals(file.toString(), error.file());
        assertFalse(error.schemaViolations().isEmpty());
        assertNotNull(error.source());
    }

    @Test void unrelatedInvalidFieldsDoNotClaimVersionBumpIsEnough() throws Exception {
        Path oldSchema = Paths.get("schemas/history/att-config-v2.7.schema.json");
        Path currentSchema = Paths.get("schemas/att-config-v2.8.schema.json");
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("schemaVersion", "att-config/v2.7");
        descriptor.put("timeoutMs", -1);
        SchemaMigrationGuidance.MigrationException error = assertThrows(SchemaMigrationGuidance.MigrationException.class,
                () -> SchemaMigrationGuidance.verify(oldSchema, currentSchema, descriptor,
                        "att-config/v2.7", "att-config/v2.8"));
        assertTrue(error.getMessage().contains("timeoutMs"));
        assertFalse(error.getMessage().contains("Upgrade schemaVersion"));
        assertTrue(error.getMessage().contains("version change alone is not sufficient"));
        assertNotNull(JsonSchemaVerifier.SchemaValidationException.find(error));
    }

    @Test void flowResultUnderOldVersionSuggestsNewVersionWithoutDroppingViolation() throws Exception {
        Path schemas = root.resolve("schemas");
        Files.createDirectories(schemas);
        for (String name : new String[]{"att-flow-v3.0.schema.json", "att-flow-v3.1.schema.json",
                "att-template-v3.0.schema.json", "att-template-v3.1.schema.json"})
            Files.copy(Paths.get("schemas", name.contains("v3.0") ? "history" : "", name), schemas.resolve(name));
        Path file = root.resolve("templates/flows/sample/flow.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, ("schemaVersion: att-flow/v3.0\nid: sample.flow.v1\nname: Sample\n"
                + "description: Sample flow\nactions:\n"
                + "  fetch: {type: tool, call: \"#{upper('ok')}\", result: {format: text}}\n")
                .getBytes(StandardCharsets.UTF_8));
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(error.detail().contains("att-flow/v3.0"));
        assertTrue(error.detail().contains("att-flow/v3.1"));
        assertTrue(error.detail().contains("Upgrade schemaVersion"));
        assertTrue(error.detail().contains("result"));
        assertTrue(error.field().contains("result"), error.detail());
        assertFalse(error.schemaViolations().isEmpty());
        assertNotNull(error.source());
        assertEquals(6, error.source().line());
    }

    @Test void templateResultUnderOldVersionSuggestsCurrentSchema() throws Exception {
        Path current = root.resolve("schemas/att-template-v3.1.schema.json");
        Path historical = root.resolve("schemas/history/att-template-v3.0.schema.json");
        Files.createDirectories(historical.getParent());
        Files.copy(Paths.get("schemas/att-template-v3.1.schema.json"), current);
        Files.copy(Paths.get("schemas/history/att-template-v3.0.schema.json"), historical);
        Path file = root.resolve("templates/sample/template.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, ("schemaVersion: att-template/v3.0\nname: sample\ndescription: sample template\n"
                + "actions:\n  fetch: {type: tool, call: \"#{upper('ok')}\", result: {format: text}}\n")
                .getBytes(StandardCharsets.UTF_8));
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> new StageTemplateLoader(root, Paths.get("templates")).load("sample"));
        assertTrue(error.detail().contains("att-template/v3.0"));
        assertTrue(error.detail().contains("att-template/v3.1"));
        assertTrue(error.detail().contains("Upgrade schemaVersion"));
        assertTrue(error.field().contains("result"), error.detail());
        assertFalse(error.schemaViolations().isEmpty());
        assertEquals(file.toRealPath().toString(), error.file());
        assertEquals(5, error.source().line());
    }
}
