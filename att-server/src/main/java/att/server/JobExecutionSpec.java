package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Safe runnable definition, separate from Worker transport, logs and redacted previews. */
final class JobExecutionSpec {
    static final String VERSION="att-job-execution/v2";
    private static final String LEGACY_VERSION="att-job-execution/v1";
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
    java.util.Map<String,Object> configuration(){return value.path("configuration").isObject()?att.server.api.ConfigurationSnapshot.copy(ServerRuntime.JSON.convertValue(value.get("configuration"),java.util.Map.class)):null;}
    JobExecutionSpec withConfiguration(java.util.Map<String,Object> configuration) {
        if(configuration!=null)value.set("configuration",ServerRuntime.JSON.valueToTree(att.server.api.ConfigurationSnapshot.copy(configuration)));
        return this;
    }
    String revision(){return value.path("revision").asText();}
    private void validate() {
        String version=value.path("schemaVersion").asText();
        boolean currentVersion=VERSION.equals(version),legacyVersion=LEGACY_VERSION.equals(version);
        if((!currentVersion&&!legacyVersion)||!"reject-package-changes".equals(value.path("revisionPolicy").asText())
                ||!value.path("revision").asText().matches("[a-f0-9]{64}")||!value.path("definition").isObject())
            throw new IllegalArgumentException("Unsupported execution snapshot");
        value.fieldNames().forEachRemaining(field->{if(!Set.of("schemaVersion","revisionPolicy","revision","definition","sourcePath","configuration","replay").contains(field))throw new IllegalArgumentException("Unknown execution snapshot field");});
        if(value.has("configuration"))configuration();
        if(value.has("replay"))validateReplay(value.path("replay"),currentVersion);
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
    JobExecutionSpec withReplaySource(String sourceJobId,String sourcePolicy,String originalRevision,String effectiveRevision) {
        if(!"current".equals(sourcePolicy)&&!"strict".equals(sourcePolicy))throw new IllegalArgumentException("Unsupported replay source policy");
        ObjectNode replay=value.putObject("replay");replay.put("sourceJobId",sourceJobId);replay.put("sourcePolicy",sourcePolicy);
        replay.put("originalRevision",originalRevision);replay.put("effectiveRevision",effectiveRevision);
        return this;
    }
    private static void validateReplay(JsonNode replay,boolean required) {
        if(!replay.isObject()||!replay.path("sourceJobId").asText("").matches("J[A-F0-9]{16}")
                ||!Set.of("current","strict").contains(replay.path("sourcePolicy").asText())
                ||!replay.path("originalRevision").asText("").matches("[a-f0-9]{64}")
                ||!replay.path("effectiveRevision").asText("").matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid replay provenance");
        replay.fieldNames().forEachRemaining(field->{if(!Set.of("sourceJobId","sourcePolicy","originalRevision","effectiveRevision").contains(field))throw new IllegalArgumentException("Unknown replay provenance field");});
    }
}
