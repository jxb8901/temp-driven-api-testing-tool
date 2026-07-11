package att.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/** Deterministic verifier for the strict JSON-schema subset used by ATT generated artifacts. */
public final class JsonSchemaVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private JsonSchemaVerifier() {}
    public static void verify(Path schemaFile, Object document) throws Exception { JsonNode schema = JSON.readTree(Files.readAllBytes(schemaFile)); JsonNode value = document instanceof JsonNode ? (JsonNode) document : JSON.valueToTree(document); verify(schema, value, "$", schema); }
    public static void verifyJson(Path schemaFile, Path documentFile) throws Exception { verify(schemaFile, JSON.readTree(Files.readAllBytes(documentFile))); }
    public static void verifyJson(Path schemaFile, String document) throws Exception { verify(schemaFile, JSON.readTree(document)); }
    private static void verify(JsonNode schema, JsonNode value, String path, JsonNode root) {
        if (schema.has("$ref")) { String ref=schema.get("$ref").asText(); if(!ref.startsWith("#/$defs/")) fail(path,"unsupported schema reference "+ref); verify(root.path("$defs").path(ref.substring(8)),value,path,root); return; }
        if(schema.has("const")&&!schema.get("const").equals(value)) fail(path,"must equal "+schema.get("const"));
        if(schema.has("enum")){boolean found=false;for(JsonNode item:schema.get("enum"))found|=item.equals(value);if(!found)fail(path,"is outside the allowed enum");}
        if(schema.has("type"))checkType(schema.get("type").asText(),value,path);
        if(value.isObject()){JsonNode required=schema.get("required");if(required!=null)for(JsonNode name:required)if(!value.has(name.asText()))fail(path,"missing required field "+name.asText());JsonNode properties=schema.path("properties");Iterator<Map.Entry<String,JsonNode>> fields=value.fields();while(fields.hasNext()){Map.Entry<String,JsonNode> field=fields.next();if(properties.has(field.getKey()))verify(properties.get(field.getKey()),field.getValue(),path+"."+field.getKey(),root);else if(schema.has("additionalProperties")&&!schema.get("additionalProperties").asBoolean(true))fail(path,"unknown field "+field.getKey());}}
        if(value.isArray()&&schema.has("items"))for(int i=0;i<value.size();i++)verify(schema.get("items"),value.get(i),path+"["+i+"]",root);
    }
    private static void checkType(String type,JsonNode value,String path){boolean valid="object".equals(type)?value.isObject():"array".equals(type)?value.isArray():"string".equals(type)?value.isTextual():"boolean".equals(type)?value.isBoolean():"integer".equals(type)?value.isIntegralNumber():"number".equals(type)?value.isNumber():"null".equals(type)&&value.isNull();if(!valid)fail(path,"must be "+type);}
    private static void fail(String path,String message){throw new IllegalArgumentException("Schema validation failed at "+path+": "+message);}
}
