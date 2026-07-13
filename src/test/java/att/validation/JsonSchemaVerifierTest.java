package att.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaVerifierTest {
    @TempDir Path tempDir;
    @Test void enforcesDraft202012CompositionAndConstraints() throws Exception {
        Path schema=tempDir.resolve("schema.json");
        Files.write(schema, ("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"required\":[\"id\",\"mode\"],\"properties\":{\"id\":{\"type\":\"integer\",\"minimum\":2},\"mode\":{\"oneOf\":[{\"const\":\"A\"},{\"const\":\"B\"}]},\"tags\":{\"type\":\"array\",\"uniqueItems\":true}},\"additionalProperties\":false}").getBytes("UTF-8"));
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(schema,"{\"id\":2,\"mode\":\"A\",\"tags\":[\"x\"]}"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,"{\"id\":1,\"mode\":\"C\",\"tags\":[\"x\",\"x\"]}"));
    }
    @Test void jsonWriterEscapesEveryControlCharacter() throws Exception {
        String json=JsonSupport.write(java.util.Collections.singletonMap("value", "a\t\b\f\u0001z"));
        assertEquals("a\t\b\f\u0001z", JsonSupport.mapper().readTree(json).get("value").asText());
    }
    @Test void productionTemplateSchemaAcceptsToolTimeoutAndRejectsParseRetry() throws Exception {
        Path schema=Paths.get("schemas/att-template-v2.1.schema.json");
        String valid="{\"schemaVersion\":\"att-template/v2.1\",\"description\":\"x\",\"actions\":{\"call\":{\"type\":\"tool\",\"call\":\"#{send()}\",\"saveAs\":\"${CASE.caseId}.json\",\"overwrite\":false,\"assert\":\"${ACTIONS.call.output} != null\",\"timeoutMs\":1,\"retry\":{\"retryOn\":[\"EXIT_CODE\"]}}}}";
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(schema,valid));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,valid.replace("EXIT_CODE","OUTPUT_PARSE")));
    }
}
