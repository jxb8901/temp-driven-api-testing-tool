package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Bounded, typed replay edits. The normal package validators remain authoritative. */
final class JobReplayOverrides {
    static final int MAX_BYTES=32768;
    private JobReplayOverrides() {}

    static ObjectNode apply(ObjectNode source,JsonNode overrides) {
        ObjectNode result=source.deepCopy();
        if(overrides==null||overrides.isNull())return result;
        if(!overrides.isObject()||overrides.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalArgumentException("Replay overrides must be a JSON object no larger than 32 KiB");
        boolean load="load".equals(source.path("executionType").asText());
        allowFields(overrides,load
                ?Set.of("environment","load","workloads","execution","testdata","thresholds","evidence","seed")
                :Set.of("environment","options","target","selection","input"));
        if(overrides.has("environment")) {
            JsonNode environment=overrides.get("environment");
            if(!environment.isTextual()||environment.asText().length()>128)throw new IllegalArgumentException("Replay environment must be text no longer than 128 characters");
            if(environment.asText().isBlank())result.remove("environment");else result.set("environment",environment.deepCopy());
        }
        if(load)applyLoad(result,overrides);else applyRun(result,overrides);
        return result;
    }

    private static void applyLoad(ObjectNode definition,JsonNode overrides) {
        JsonNode scenarioNode=definition.get("scenario");
        if(!(scenarioNode instanceof ObjectNode))throw new IllegalArgumentException("Retained Load scenario is unavailable");
        ObjectNode scenario=(ObjectNode)scenarioNode;
        if(overrides.has("load")) {
            JsonNode load=overrides.get("load");requireObject(load,"load overrides must be an object");
            allowFields(load,Set.of("warmup","rampUp","duration","rampDown"));
            ObjectNode target=scenario.with("load");merge(target,load);
        }
        if(overrides.has("workloads")) {
            JsonNode patches=overrides.get("workloads");
            if(!patches.isArray()||patches.size()>100)throw new IllegalArgumentException("workloads override must be an array of at most 100 workload edits");
            JsonNode existing=scenario.path("workloads");
            if(!existing.isArray())throw new IllegalArgumentException("Retained Load workloads are unavailable");
            Set<String> seen=new HashSet<>();
            for(JsonNode patch:patches) {
                requireObject(patch,"each workload override must be an object");
                String id=patch.path("id").asText("");
                if(id.isBlank()||!seen.add(id))throw new IllegalArgumentException("Each workload edit must name a unique retained workload ID");
                allowFields(patch,Set.of("id","load","execution","target","mix","inputs","vars","testdata","thresholds","evidence"));
                ObjectNode workload=findWorkload(existing,id);
                if(workload==null)throw new IllegalArgumentException("Workload identity changed; select an existing workload ID");
                if(patch.has("load")) {
                    JsonNode load=patch.get("load");requireObject(load,"workload load override must be an object");
                    allowFields(load,Set.of("users","arrivalRate","maxConcurrent","overloadPolicy"));
                    boolean arrival=workload.path("load").has("arrivalRate");
                    if(arrival&&load.has("users")||!arrival&&load.has("arrivalRate"))
                        throw new IllegalArgumentException("Replay cannot convert between Arrival Rate and Virtual Users models");
                    merge(workload.with("load"),load);
                }
                for(String field:Set.of("execution","target","mix","inputs","vars","testdata","thresholds","evidence"))
                    if(patch.has(field))workload.set(field,patch.get(field).deepCopy());
            }
        }
        for(String field:Set.of("execution","testdata","thresholds","evidence","seed"))
            if(overrides.has(field))scenario.set(field,overrides.get(field).deepCopy());
        JsonNode workloads=scenario.path("workloads");
        if(workloads.isArray()) {
            Boolean arrivalModel=null;
            for(JsonNode workload:workloads) {
                boolean arrival=workload.path("load").has("arrivalRate");
                if(arrivalModel!=null&&arrivalModel.booleanValue()!=arrival)throw new IllegalArgumentException("All replay workloads must keep the original LOAD model");
                arrivalModel=Boolean.valueOf(arrival);
            }
        }
    }

    private static void applyRun(ObjectNode definition,JsonNode overrides) {
        if(overrides.has("options")) {
            JsonNode options=overrides.get("options");requireObject(options,"RUN options must be an object");
            allowFields(options,Set.of("debug","failFast"));merge(definition.with("options"),options);
        }
        if(overrides.has("target")) {
            JsonNode target=overrides.get("target");requireObject(target,"RUN target must be an object");
            allowFields(target,Set.of("type","id"));definition.set("target",target.deepCopy());definition.remove("selection");definition.remove("input");
        }
        if(overrides.has("selection")) {
            JsonNode selection=overrides.get("selection");requireObject(selection,"RUN selection must be an object");
            allowFields(selection,Set.of("caseIds","suites","suiteDirectory","tags","excludeTags","all","rerunFailed"));
            definition.set("selection",selection.deepCopy());definition.remove("target");definition.remove("input");
        }
        if(overrides.has("input")) {
            JsonNode input=overrides.get("input");requireObject(input,"RUN input must be an object");
            if(!definition.path("target").isObject())throw new IllegalArgumentException("RUN input edits require a retained resource target");
            definition.set("input",input.deepCopy());
        }
    }

    private static ObjectNode findWorkload(JsonNode rows,String id) {
        for(JsonNode row:rows)if(row.isObject()&&id.equals(row.path("id").asText()))return (ObjectNode)row;
        return null;
    }
    private static void merge(ObjectNode target,JsonNode source) {source.fields().forEachRemaining(entry->target.set(entry.getKey(),entry.getValue().deepCopy()));}
    private static void requireObject(JsonNode node,String message) {if(node==null||!node.isObject())throw new IllegalArgumentException(message);}
    private static void allowFields(JsonNode node,Set<String> allowed) {
        node.fieldNames().forEachRemaining(field->{if(!allowed.contains(field))throw new IllegalArgumentException("Unsupported replay override field: "+field);});
    }
}
