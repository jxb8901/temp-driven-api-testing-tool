package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Safe runnable definition, separate from Worker transport, logs and redacted previews. */
final class JobExecutionSpec {
    static final String VERSION="att-job-execution/v1";
    static final int MAX_BYTES=262144;
    final ObjectNode value;

    JobExecutionSpec(ObjectNode definition,String revision,String sourcePath) {
        value=ServerRuntime.JSON.createObjectNode();value.put("schemaVersion",VERSION);
        value.put("revisionPolicy","reject-package-changes");value.put("revision",revision);
        value.set("definition",definition.deepCopy());
        if(sourcePath!=null)value.put("sourcePath",sourcePath);
        validate();
    }
    private JobExecutionSpec(ObjectNode value){this.value=value;validate();}
    static JobExecutionSpec parse(String json) throws Exception {
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("Execution snapshot is unavailable");
        JsonNode node=ServerRuntime.JSON.readTree(json);
        if(!(node instanceof ObjectNode object))throw new IllegalArgumentException("Invalid execution snapshot");
        return new JobExecutionSpec(object);
    }
    String json() throws Exception {
        String result=ServerRuntime.JSON.writeValueAsString(value);
        if(result.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("Execution snapshot exceeds the retention limit");
        return result;
    }
    ObjectNode definition(){return ((ObjectNode)value.get("definition")).deepCopy();}
    String revision(){return value.path("revision").asText();}
    private void validate() {
        if(!VERSION.equals(value.path("schemaVersion").asText())||!"reject-package-changes".equals(value.path("revisionPolicy").asText())
                ||!value.path("revision").asText().matches("[a-f0-9]{64}")||!value.path("definition").isObject())
            throw new IllegalArgumentException("Unsupported execution snapshot");
        value.fieldNames().forEachRemaining(field->{if(!Set.of("schemaVersion","revisionPolicy","revision","definition","sourcePath").contains(field))throw new IllegalArgumentException("Unknown execution snapshot field");});
        JsonNode definition=value.get("definition");
        if(!Set.of("run","load").contains(definition.path("executionType").asText())||definition.path("packageId").asText().isBlank())
            throw new IllegalArgumentException("Invalid execution identity");
        if("run".equals(definition.path("executionType").asText()))RunDefinition.parseRetained(definition);
        else {
            definition.fieldNames().forEachRemaining(field->{if(!Set.of("executionType","packageId","environment","scenario").contains(field))throw new IllegalArgumentException("Unknown Load snapshot field");});
            if(!definition.path("scenario").isObject())throw new IllegalArgumentException("Load snapshot requires a validated scenario");
        }
        String path=value.path("sourcePath").asText("");
        if(path.length()>1024||path.chars().anyMatch(Character::isISOControl)||path.contains(":")||path.startsWith("/")||path.contains("\\")||java.util.Arrays.asList(path.split("/")).contains(".."))
            throw new IllegalArgumentException("Execution source must be package-relative");
    }
}
