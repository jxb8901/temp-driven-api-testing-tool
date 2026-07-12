package att.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Draft 2020-12 validation backed by the NetworkNT production validator. */
public final class JsonSchemaVerifier {
    private static final ObjectMapper JSON = JsonSupport.mapper();
    private JsonSchemaVerifier() {}
    public static void verify(Path schemaFile, Object document) throws Exception { JsonNode schema = JSON.readTree(Files.readAllBytes(schemaFile)); JsonNode value = document instanceof JsonNode ? (JsonNode) document : JSON.valueToTree(document); verify(schema, value); }
    public static void verifyJson(Path schemaFile, Path documentFile) throws Exception { verify(schemaFile, JSON.readTree(Files.readAllBytes(documentFile))); }
    public static void verifyJson(Path schemaFile, String document) throws Exception { verify(schemaFile, JSON.readTree(document)); }
    public static void compile(Path schemaFile) throws Exception { schema(JSON.readTree(Files.readAllBytes(schemaFile))); }
    private static void verify(JsonNode schema, JsonNode value) {
        com.networknt.schema.JsonSchema validator = schema(schema);
        Set<com.networknt.schema.ValidationMessage> errors = validator.validate(value);
        if (!errors.isEmpty()) { java.util.List<String> messages = new java.util.ArrayList<String>(); for (com.networknt.schema.ValidationMessage error : errors) messages.add(error.toString()); java.util.Collections.sort(messages); throw new IllegalArgumentException("Schema validation failed: " + String.join("; ", messages)); }
    }
    private static com.networknt.schema.JsonSchema schema(JsonNode schema) { com.networknt.schema.SchemaValidatorsConfig config = new com.networknt.schema.SchemaValidatorsConfig(); config.setFailFast(false); config.setTypeLoose(false); return com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schema, config); }
}
