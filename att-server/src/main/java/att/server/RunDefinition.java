package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict public RUN contract. Case bindings never pass through the Debug input adapter. */
final class RunDefinition {
    static final String VERSION="att-execution/v1";
    static final String NATIVE_CASE_VERSION="att-execution/v2";
    private static final Pattern NATIVE_DEFINITION_ID=Pattern.compile(
            "^(?!.*(?:^|\\.)(?:[cC][oO][nN]|[pP][rR][nN]|[aA][uU][xX]|[nN][uU][lL]|[cC][oO][mM][1-9]|[lL][pP][tT][1-9])(?:\\.|$))[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*$");
    private static final Pattern NATIVE_CASE_ID=Pattern.compile(
            "^(?!(?:[cC][oO][nN]|[pP][rR][nN]|[aA][uU][xX]|[nN][uU][lL]|[cC][oO][mM][1-9]|[lL][pP][tT][1-9])$)[A-Za-z0-9_-]+$");
    final ObjectNode definition;
    final ObjectNode workerInput;
    final boolean resourceTarget;
    final boolean debug;
    final boolean nativeCaseTarget;
    final boolean nativeSuiteTarget;
    final String nativeDefinitionId;
    final List<String> resolvedCaseIds;
    final List<String> nativeDefinitionIds;
    final List<String> qualifiedCaseIds;
    final List<String> nativeSuiteTags;
    final List<String> nativeSuiteExcludeTags;
    final boolean nativeSuiteAll;

    private RunDefinition(ObjectNode definition,ObjectNode workerInput,boolean resourceTarget,boolean debug,
                          boolean nativeCaseTarget,String nativeDefinitionId,List<String> resolvedCaseIds,
                          boolean nativeSuiteTarget,List<String> nativeDefinitionIds,List<String> qualifiedCaseIds,
                          List<String> nativeSuiteTags,List<String> nativeSuiteExcludeTags,boolean nativeSuiteAll) {
        this.definition=definition;this.workerInput=workerInput;this.resourceTarget=resourceTarget;this.debug=debug;
        this.nativeCaseTarget=nativeCaseTarget;this.nativeDefinitionId=nativeDefinitionId;
        this.resolvedCaseIds=Collections.unmodifiableList(new ArrayList<>(resolvedCaseIds));
        this.nativeSuiteTarget=nativeSuiteTarget;this.nativeDefinitionIds=immutable(nativeDefinitionIds);
        this.qualifiedCaseIds=immutable(qualifiedCaseIds);this.nativeSuiteTags=immutable(nativeSuiteTags);
        this.nativeSuiteExcludeTags=immutable(nativeSuiteExcludeTags);this.nativeSuiteAll=nativeSuiteAll;
    }

    static RunDefinition parse(JsonNode value) {return parse(value,false);}
    /** Only server-retained selections may contain the full resolved 10000-case batch. */
    static RunDefinition parseRetained(JsonNode value) {return parse(value,true);}
    private static RunDefinition parse(JsonNode value,boolean retained) {
        fields(value,"schemaVersion","executionType","packageId","environment","config","target","selection","input","options");
        String schemaVersion=text(value,"schemaVersion");
        boolean nativeVersion=NATIVE_CASE_VERSION.equals(schemaVersion);
        if(!VERSION.equals(schemaVersion)&&!nativeVersion)throw invalid("Unsupported execution schemaVersion");
        if(!"run".equals(text(value,"executionType")))throw invalid("executionType must be run");
        ObjectNode worker=ServerRuntime.JSON.createObjectNode();worker.put("packageId",text(value,"packageId"));
        for(String name:new String[]{"environment","config"})if(value.has(name)) {
            if(!value.get(name).isNull())text(value,name);
            worker.set(name,value.get(name).deepCopy());
        }
        if(value.has("target")==value.has("selection"))throw invalid("Provide exactly one target or selection");
        boolean resource=false,nativeCase=false,nativeSuite=false,nativeSuiteAll=false;
        String nativeDefinitionId=null;List<String> resolvedCaseIds=Collections.emptyList();
        List<String> nativeDefinitionIds=Collections.emptyList(),qualifiedCaseIds=Collections.emptyList();
        List<String> nativeSuiteTags=Collections.emptyList(),nativeSuiteExcludeTags=Collections.emptyList();
        if(value.has("target")) {
            JsonNode target=value.get("target");
            String type=text(target,"type");
            if("nativeCase".equals(type)) {
                if(!nativeVersion)throw invalid("Native Case RUN requires att-execution/v2");
                fields(target,"type","definitionId","selection");
                nativeCase=true;nativeDefinitionId=nativeDefinitionId(text(target,"definitionId"));
                JsonNode selection=target.get("selection");fields(selection,"resolvedCaseIds");
                resolvedCaseIds=nativeCaseIds(selection.get("resolvedCaseIds"));
            } else if("nativeSuite".equals(type)) {
                if(!nativeVersion)throw invalid("Native Suite RUN requires att-execution/v2");
                fields(target,"type","definitionIds","selection");nativeSuite=true;
                nativeDefinitionIds=nativeDefinitionIds(target.get("definitionIds"));
                JsonNode selection=target.get("selection");fields(selection,"all","qualifiedCaseIds","tags","excludeTags");
                nativeSuiteAll=selection.has("all")&&bool(selection,"all");
                if(selection.has("all")&&!selection.get("all").isBoolean())throw invalid("all must be boolean");
                qualifiedCaseIds=textList(selection.get("qualifiedCaseIds"),"qualifiedCaseIds",
                        att.testcase.TestCaseSuiteResolver.MAX_QUALIFIED_CASE_IDS);
                nativeSuiteTags=textList(selection.get("tags"),"tags",100,att.testcase.TestCaseSuiteResolver.MAX_TAG_LENGTH);
                nativeSuiteExcludeTags=textList(selection.get("excludeTags"),"excludeTags",100,att.testcase.TestCaseSuiteResolver.MAX_TAG_LENGTH);
                if(!nativeSuiteAll&&qualifiedCaseIds.isEmpty()&&nativeSuiteTags.isEmpty())
                    throw invalid("Native Suite selection must include all, qualifiedCaseIds, or tags");
                if(nativeSuiteAll&&!qualifiedCaseIds.isEmpty())throw invalid("all and qualifiedCaseIds cannot be combined");
                Set<String> declared=new java.util.HashSet<>(nativeDefinitionIds);
                for(String qualified:qualifiedCaseIds) {
                    int separator=qualified.lastIndexOf('.');
                    if(separator<1||separator==qualified.length()-1||qualified.length()>255)
                        throw invalid("qualifiedCaseIds must use DefinitionID.localCaseID form");
                    String definitionId=qualified.substring(0,separator),caseId=qualified.substring(separator+1);
                    if(!declared.contains(definitionId)||!NATIVE_CASE_ID.matcher(caseId).matches())
                        throw invalid("qualifiedCaseIds must refer to a declared Definition and a valid local Case ID");
                }
                worker.set("nativeSuiteDefinitionIds",ServerRuntime.JSON.valueToTree(nativeDefinitionIds));
                worker.set("nativeSuiteQualifiedCaseIds",ServerRuntime.JSON.valueToTree(qualifiedCaseIds));
                worker.set("nativeSuiteTags",ServerRuntime.JSON.valueToTree(nativeSuiteTags));
                worker.set("nativeSuiteExcludeTags",ServerRuntime.JSON.valueToTree(nativeSuiteExcludeTags));
                worker.put("nativeSuiteAll",nativeSuiteAll);
            } else {
                fields(target,"type","id");String id=text(target,"id");
                if(!Set.of("case","template","flow","tool").contains(type))throw invalid("Unsupported RUN target type");
                resource=!"case".equals(type);
                if(resource)worker.set("target",target.deepCopy());
                else worker.putArray("caseIds").add(id);
            }
        } else {
            JsonNode selection=value.get("selection");
            fields(selection,"suites","suiteDirectory","caseIds","tags","excludeTags","all","rerunFailed");
            if(selection.isEmpty())throw invalid("selection must explicitly select cases");
            if(selection.has("suites")&&selection.has("suiteDirectory"))throw invalid("suites and suiteDirectory are mutually exclusive");
            boolean positive=false;
            for(String name:new String[]{"suites","caseIds","tags","excludeTags"})if(selection.has(name)) {
                JsonNode list=selection.get(name);
                int maximum=retained&&"caseIds".equals(name)?10000:1000;
                if(!list.isArray()||list.isEmpty()||list.size()>maximum)throw invalid(name+" must be a nonempty array of at most "+maximum+" strings");
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
            if(nativeVersion)fields(options,"debug","failFast","runId");
            else fields(options,"debug","failFast");
            for(String name:new String[]{"debug","failFast"})if(options.has(name)&&!options.get(name).isBoolean())throw invalid(name+" must be boolean");
            debug=options.path("debug").asBoolean(false);
            if(options.has("failFast")) {
                if(resource)throw invalid("failFast is a case selection option");
                worker.set("failFast",options.get("failFast"));
            }
            if(options.has("runId")) {
                if(!options.get("runId").isTextual())throw invalid("runId must be a valid v2 RUN ID");
                try {worker.put("runId",att.core.IdentifierValidator.runId(options.get("runId").asText()));}
                catch(IllegalArgumentException invalidRunId) {throw invalid("runId must be a valid v2 RUN ID");}
            }
        }
        if(value.has("input")&&!resource)throw invalid("Workbook and Native Case RUN do not accept input overrides; input is only valid for Template, Flow or Tool");
        if(resource) {
            JsonNode input=value.get("input");
            if(input==null||!input.isObject())throw invalid("Resource RUN requires an input object");
            if(value.has("config")&&!value.get("config").isNull()&&!"config/config.yaml".equals(value.get("config").asText()))
                throw invalid("Resource RUN must use the configuration validated by its input draft");
        }
        worker.put("diagnosticMode",debug);
        return new RunDefinition(((ObjectNode)value).deepCopy(),worker,resource,debug,nativeCase,nativeDefinitionId,resolvedCaseIds,
                nativeSuite,nativeDefinitionIds,qualifiedCaseIds,nativeSuiteTags,nativeSuiteExcludeTags,nativeSuiteAll);
    }

    private static List<String> nativeDefinitionIds(JsonNode value) {
        List<String> ids=textList(value,"definitionIds",100);
        if(ids.isEmpty())throw invalid("definitionIds must be a nonempty array of at most 100 Definition IDs");
        for(String id:ids)nativeDefinitionId(id);
        return ids;
    }

    private static List<String> textList(JsonNode value,String name,int maximum) {
        return textList(value,name,maximum,512);
    }

    private static List<String> textList(JsonNode value,String name,int maximum,int maxValueLength) {
        if(value==null)return Collections.emptyList();
        if(!value.isArray()||value.size()==0||value.size()>maximum)
            throw invalid(name+" must be a nonempty array of at most "+maximum+" strings");
        Set<String> seen=new java.util.HashSet<>();List<String> result=new ArrayList<>(value.size());
        for(JsonNode entry:value) {
            if(!entry.isTextual()||entry.asText().isBlank()||entry.asText().length()>maxValueLength
                    ||entry.asText().chars().anyMatch(Character::isISOControl))throw invalid(name+" contains an invalid value");
            if(!seen.add(entry.asText()))throw invalid(name+" contains duplicate values");
            result.add(entry.asText());
        }
        return result;
    }

    private static boolean bool(JsonNode value,String field) {return value.path(field).asBoolean(false);}
    private static List<String> immutable(List<String> values) {return Collections.unmodifiableList(new ArrayList<>(values));}

    private static String nativeDefinitionId(String value) {
        if(value.length()>126||!NATIVE_DEFINITION_ID.matcher(value).matches())
            throw invalid("definitionId must be a package-scoped native Definition ID");
        return value;
    }

    private static List<String> nativeCaseIds(JsonNode value) {
        if(value==null||!value.isArray()||value.isEmpty()||value.size()>1000)
            throw invalid("resolvedCaseIds must be a nonempty array of at most 1000 strings");
        Set<String> unique=new java.util.HashSet<>();List<String> result=new ArrayList<>(value.size());
        for(JsonNode entry:value) {
            if(!entry.isTextual())throw invalid("resolvedCaseIds contains an invalid value");
            String id=entry.asText();
            if(id.length()>128||!NATIVE_CASE_ID.matcher(id).matches())
                throw invalid("resolvedCaseIds contains an invalid local Case ID");
            if(!unique.add(id))throw invalid("resolvedCaseIds contains duplicate values");
            result.add(id);
        }
        return result;
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
