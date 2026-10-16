package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Strict public RUN contract. Case bindings never pass through the Debug input adapter. */
final class RunDefinition {
    static final String VERSION="att-execution/v1";
    final ObjectNode definition;
    final ObjectNode workerInput;
    final boolean resourceTarget;
    final boolean debug;

    private RunDefinition(ObjectNode definition,ObjectNode workerInput,boolean resourceTarget,boolean debug) {
        this.definition=definition;this.workerInput=workerInput;this.resourceTarget=resourceTarget;this.debug=debug;
    }

    static RunDefinition parse(JsonNode value) {
        fields(value,"schemaVersion","executionType","packageId","environment","config","target","selection","input","options");
        if(!VERSION.equals(text(value,"schemaVersion")))throw invalid("Unsupported execution schemaVersion");
        if(!"run".equals(text(value,"executionType")))throw invalid("executionType must be run");
        ObjectNode worker=ServerRuntime.JSON.createObjectNode();worker.put("packageId",text(value,"packageId"));
        for(String name:new String[]{"environment","config"})if(value.has(name)) {
            if(!value.get(name).isNull())text(value,name);
            worker.set(name,value.get(name).deepCopy());
        }
        if(value.has("target")==value.has("selection"))throw invalid("Provide exactly one target or selection");
        boolean resource=false;
        if(value.has("target")) {
            JsonNode target=value.get("target");fields(target,"type","id");
            String type=text(target,"type"),id=text(target,"id");
            if(!Set.of("case","template","flow","tool").contains(type))throw invalid("Unsupported RUN target type");
            resource=!"case".equals(type);
            if(resource)worker.set("target",target.deepCopy());
            else worker.putArray("caseIds").add(id);
        } else {
            JsonNode selection=value.get("selection");
            fields(selection,"suites","suiteDirectory","caseIds","tags","excludeTags","all","rerunFailed");
            if(selection.isEmpty())throw invalid("selection must explicitly select cases");
            if(selection.has("suites")&&selection.has("suiteDirectory"))throw invalid("suites and suiteDirectory are mutually exclusive");
            boolean positive=false;
            for(String name:new String[]{"suites","caseIds","tags","excludeTags"})if(selection.has(name)) {
                JsonNode list=selection.get(name);
                if(!list.isArray()||list.isEmpty()||list.size()>1000)throw invalid(name+" must be a nonempty array of at most 1000 strings");
                Set<String> unique=new java.util.HashSet<>();
                for(JsonNode entry:list) {
                    if(!entry.isTextual()||entry.asText().isBlank()||entry.asText().length()>512||entry.asText().chars().anyMatch(Character::isISOControl))throw invalid(name+" contains an invalid value");
                    if(!unique.add(entry.asText()))throw invalid(name+" contains duplicate values");
                }
                worker.set(name,list.deepCopy());if(!"excludeTags".equals(name))positive=true;
            }
            if(selection.has("suiteDirectory")){worker.put("suiteDirectory",text(selection,"suiteDirectory"));positive=true;}
            for(String name:new String[]{"all","rerunFailed"})if(selection.has(name)) {
                if(!selection.get(name).isBoolean())throw invalid(name+" must be boolean");
                worker.set(name,selection.get(name));positive|=selection.get(name).booleanValue();
            }
            if(!positive)throw invalid("selection must include a positive selector");
        }
        JsonNode options=value.get("options");boolean debug=false;
        if(options!=null) {
            fields(options,"debug","failFast");
            for(String name:new String[]{"debug","failFast"})if(options.has(name)&&!options.get(name).isBoolean())throw invalid(name+" must be boolean");
            debug=options.path("debug").asBoolean(false);
            if(options.has("failFast")) {
                if(resource)throw invalid("failFast is a case selection option");
                worker.set("failFast",options.get("failFast"));
            }
        }
        if(value.has("input")&&!resource)throw invalid("Case RUN uses Excel/sidecar bindings; input is only valid for Template, Flow or Tool");
        if(resource) {
            JsonNode input=value.get("input");
            if(input==null||!input.isObject())throw invalid("Resource RUN requires an input object");
            if(value.has("config")&&!value.get("config").isNull()&&!"config/config.yaml".equals(value.get("config").asText()))
                throw invalid("Resource RUN must use the configuration validated by its input draft");
        }
        worker.put("diagnosticMode",debug);
        return new RunDefinition(((ObjectNode)value).deepCopy(),worker,resource,debug);
    }

    private static void fields(JsonNode value,String... allowed) {
        if(value==null||!value.isObject())throw invalid("A JSON object is required");
        Set<String> names=Set.of(allowed);
        value.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw invalid("Unknown RUN field: "+name);});
    }
    private static String text(JsonNode value,String field) {
        JsonNode text=value.get(field);
        if(text==null||!text.isTextual()||text.asText().isBlank()||text.asText().length()>512||text.asText().chars().anyMatch(Character::isISOControl))throw invalid(field+" must be a nonempty string of at most 512 characters");
        return text.asText();
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
