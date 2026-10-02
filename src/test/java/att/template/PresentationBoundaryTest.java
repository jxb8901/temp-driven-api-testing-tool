package att.template;

import att.core.*;
import att.validation.JsonSchemaVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PresentationBoundaryTest {
    @TempDir Path root;
    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
    @Test void logFormatsTypedDbAndTreeValuesWithoutSeverityOrMutation() throws Exception {
        Map<String, Object> db = map("operation", "query", "rows", Collections.singletonList(map("ID", "A100", "AMOUNT", 7)));
        Map<String, Object> tree = map("items", Arrays.asList(1, true, map("name", "A")));
        TestCase test = new TestCase(1, "g", "s", "C", Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, root, "R", root, root.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", map("db", db, "tree", tree)), "T", root);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("rows", map("type", "log", "value", "${EXEC.INPUT.db}", "format", "sqlplus")),
                new TemplateAction("json", map("type", "log", "value", "${EXEC.INPUT.tree}", "format", "json")),
                new TemplateAction("yaml", map("type", "log", "value", "${EXEC.INPUT.tree}", "format", "yaml")),
                new TemplateAction("note", map("type", "log", "message", "Unexpected response")));
        try (CaseExecutionLog log = new CaseExecutionLog(root.resolve("case.log"))) {
            List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                    .execute("invoke", new StageTemplate("T", root, actions), context, log);
            for (ValidationResult result : results) assertEquals(ResultStatus.PASS, result.status(), result.message());
        }
        assertEquals(new DbTextResultFormatter().format(db), context.resolve("ACTIONS.rows.output.result"));
        assertTrue(String.valueOf(context.resolve("ACTIONS.json.output.result")).contains("items"));
        assertTrue(String.valueOf(context.resolve("ACTIONS.yaml.output.result")).contains("items:"));
        assertNull(context.resolve("ACTIONS.note.output.level"));
        assertEquals(7, ((Map<?, ?>) ((List<?>) db.get("rows")).get(0)).get("AMOUNT"));
        assertEquals(tree, context.resolve("EXEC.INPUT.tree"));
        String log = new String(Files.readAllBytes(root.resolve("case.log")), "UTF-8");
        assertTrue(log.contains("[LOG rows]")); assertTrue(log.contains("A100")); assertTrue(log.contains("Unexpected response"));
    }
    @Test void currentSchemaAndRuntimeRejectLevelWithMigrationGuidance() throws Exception {
        att.TestSchemas.install(root);
        Map<String, Object> action = map("type", "log", "level", "ERROR", "message", "hello");
        JsonSchemaVerifier.SchemaValidationException invalid = assertThrows(JsonSchemaVerifier.SchemaValidationException.class,
                () -> JsonSchemaVerifier.verify(root.resolve("schemas/att-template-v3.6.schema.json"),
                        map("schemaVersion", "att-template/v3.6", "name", "T", "description", "test", "actions", map("note", action))));
        assertTrue(invalid.getMessage().contains("Log.level was removed"), invalid.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new TemplateAction("note", action)).getMessage().contains("delete level"));
    }
}
