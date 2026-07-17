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
        Path schema=Paths.get("schemas/att-template-v2.3.schema.json");
        String valid="{\"schemaVersion\":\"att-template/v2.3\",\"description\":\"x\",\"actions\":{\"call\":{\"type\":\"tool\",\"call\":\"#{send()}\",\"saveAs\":\"${CASE.caseId}.json\",\"overwrite\":false,\"assert\":\"${output.result} != null\",\"timeoutMs\":1,\"retry\":{\"retryOn\":[\"EXIT_CODE\"]}}}}";
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(schema,valid));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,valid.replace("EXIT_CODE","OUTPUT_PARSE")));
    }

    @Test void v23TemplateSchemaEnforcesCanonicalRenderAndAssertFields() throws Exception {
        Path schema=Paths.get("schemas/att-template-v2.3.schema.json");
        String render="{\"schemaVersion\":\"att-template/v2.3\",\"description\":\"x\",\"actions\":{\"render\":{\"type\":\"render\",\"payload\":\"data/*.json\",\"renderAs\":\"json\"}}}";
        String assertion="{\"schemaVersion\":\"att-template/v2.3\",\"description\":\"x\",\"actions\":{\"check\":{\"type\":\"assert\",\"assert\":\"true\",\"expected\":\"yes\",\"actual\":\"yes\"}}}";
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(schema,render));
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(schema,assertion));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,render.replace("\"renderAs\":\"json\"","\"dataType\":\"json\"")));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,render.replace(",\"renderAs\":\"json\"","")));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,render.replace("\"renderAs\":\"json\"","\"renderAs\":\"binary\"")));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,render.replace("\"renderAs\":\"json\"","\"saveAs\":\"out.json\"")));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,assertion.replace("\"assert\":\"true\"","\"expression\":\"true\"")));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,assertion.replace("\"actual\"","\"acture\"")));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,assertion.replace("\"actual\"","\"actural\"")));
    }

    @Test void v23TemplateSchemaAcceptsOnlyCanonicalAssignContract() throws Exception {
        Path schema=Paths.get("schemas/att-template-v2.3.schema.json");
        String assign="{\"schemaVersion\":\"att-template/v2.3\",\"description\":\"x\",\"actions\":{\"seq\":{\"type\":\"assign\",\"name\":\"txnSeq\",\"expression\":\"ATT#{sysdate('yyyyMMdd')}\",\"assert\":\"${output.result} != ''\"}}}";
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(schema,assign));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,assign.replace("\"txnSeq\"","\"txn.seq\"")));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,assign.replace(",\"expression\":\"ATT#{sysdate('yyyyMMdd')}\"","")));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(schema,assign.replace("\"assert\":\"${output.result} != ''\"","\"call\":\"#{send()}\"")));
    }

    @Test void productionV22SchemasAcceptArgvGroupsAndRejectMalformedSsh() throws Exception {
        Path config = Paths.get("schemas/att-config-v2.2.schema.json");
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(config, "{\"schemaVersion\":\"att-config/v2.2\",\"toolGroups\":[\"config/tools/db.yaml\"],\"tools\":{\"echo\":{\"name\":\"Echo\",\"description\":\"Echo\",\"command\":[\"echo\",\"${value}\"],\"arguments\":{\"value\":{\"name\":\"Value\",\"description\":\"Value\",\"required\":false,\"argName\":\"--value\"}}}}}"));
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(config, "{\"schemaVersion\":\"att-config/v2.2\",\"tools\":{\"echo\":{\"name\":\"Echo\",\"description\":\"Echo\",\"command\":[\"echo\",\"${value}\"],\"arguments\":{\"value\":{\"name\":\"Value\",\"description\":\"Value\",\"required\":false,\"argName\":\"\"}}}}}"));
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(config, "{\"schemaVersion\":\"att-config/v2.2\",\"tools\":{\"capture\":{\"name\":\"Capture\",\"description\":\"Capture\",\"command\":[\"capture\",\"${keywords}\",\"${types}\"],\"arguments\":{\"keywords\":{\"name\":\"Keywords\",\"description\":\"Keywords\",\"required\":true,\"delimit\":\",\",\"argName\":\"--keyword\",\"argNameMode\":\"repeat\"},\"types\":{\"name\":\"Types\",\"description\":\"Types\",\"required\":true,\"delimit\":\"|\",\"argName\":\"--types\",\"argNameMode\":\"once\"}}}}}"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(config, "{\"schemaVersion\":\"att-config/v2.2\",\"tools\":{\"echo\":{\"name\":\"Echo\",\"description\":\"Echo\",\"command\":[\"echo\"],\"arguments\":{\"value\":{\"name\":\"Value\",\"description\":\"Value\",\"required\":false,\"argName\":\"--bad name\"}}}}}"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(config, "{\"schemaVersion\":\"att-config/v2.2\",\"tools\":{\"echo\":{\"name\":\"Echo\",\"description\":\"Echo\",\"command\":[\"echo\",\"${value}\"],\"arguments\":{\"value\":{\"name\":\"Value\",\"description\":\"Value\",\"required\":false,\"argName\":\"--value\",\"argNameMode\":\"sometimes\"}}}}}"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaVerifier.verifyJson(config, "{\"schemaVersion\":\"att-config/v2.2\",\"ssh\":{\"host\":\"x\",\"user\":\"u\",\"password\":\"secret\"}}"));
        Path group = Paths.get("schemas/att-tool-group-v2.2.schema.json");
        assertDoesNotThrow(() -> JsonSchemaVerifier.verifyJson(group, "{\"schemaVersion\":\"att-tool-group/v2.2\",\"id\":\"db\",\"name\":\"DB\",\"description\":\"DB tools\",\"script\":[\"dispatch\"],\"tools\":{\"select\":{\"name\":\"Select\",\"description\":\"Select\",\"command\":[\"query\",\"${id}\"],\"arguments\":{\"id\":{\"name\":\"ID\",\"description\":\"ID\",\"required\":true,\"argName\":\"--id\"}}}}}"));
    }
}
