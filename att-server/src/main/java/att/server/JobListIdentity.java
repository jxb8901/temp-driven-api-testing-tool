package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Allowlisted immutable list metadata: never includes inputs, arguments, secrets or filesystem paths. */
final class JobListIdentity {
    private JobListIdentity() {}
    static Map<String,Object> from(JsonNode definition) {
        if(definition==null||!definition.isObject())return Map.of();
        if("load".equals(definition.path("executionType").asText()))return load(definition.path("scenario"));
        JsonNode target=definition.path("target");
        Map<String,String> single=target(target);
        if(!single.isEmpty())return identity(single.get("type"),single.get("id"),List.of(single),List.of(single.get("type"),single.get("id")));
        JsonNode cases=definition.path("selection").path("caseIds");
        if(cases.isArray()&&!cases.isEmpty()) {
            if(cases.size()==1)return identity("case",cases.get(0).asText(),List.of(Map.of("type","case","id",cases.get(0).asText())),List.of("case",cases.get(0).asText()));
            List<Map<String,String>> preview=new ArrayList<>();List<String> ids=new ArrayList<>();
            for(JsonNode id:cases){ids.add(id.asText());if(preview.size()<32)preview.add(Map.of("type","case","id",id.asText()));}
            Collections.sort(ids);
            Map<String,Object> out=identity("cases",cases.size()+" selected",preview,ids);out.put("bindingCount",cases.size());return out;
        }
        return Map.of();
    }
    static Map<String,Object> load(JsonNode scenario) {
        JsonNode workloads=scenario.path("workloads");if(!workloads.isArray()||workloads.isEmpty())return Map.of();
        List<Map<String,Object>> summaries=new ArrayList<>();List<Map<String,String>> targets=new ArrayList<>();List<String> signature=new ArrayList<>();int bindings=0,previewBindings=0;
        for(JsonNode workload:workloads) {
            List<Map<String,String>> members=new ArrayList<>();Map<String,String> direct=target(workload.path("target"));if(!direct.isEmpty())members.add(direct);
            JsonNode mix=workload.path("mix");if(mix.isArray())for(JsonNode entry:mix){Map<String,String> t=target(entry.path("target"));if(!t.isEmpty())members.add(t);}
            for(Map<String,String> member:members){bindings++;if(targets.size()<32&&!targets.contains(member))targets.add(member);}
            List<String> normalized=new ArrayList<>();for(var member:members)normalized.add(json(List.of(member.get("type"),member.get("id"))));Collections.sort(normalized);
            signature.add(json(List.of(text(workload.path("id")),normalized)));
            if(summaries.size()<32) {
                Map<String,Object> summary=new LinkedHashMap<>();summary.put("name",text(workload.path("id")));summary.put("mix",mix.isArray());summary.put("targets",members.stream().limit(Math.max(0,32-previewBindings)).toList());previewBindings+=Math.min(members.size(),Math.max(0,32-previewBindings));
                JsonNode rootLoad=scenario.path("load"), workloadLoad=workload.path("load");
                for(String key:List.of("users","arrivalRate","maxConcurrent","duration","warmup","rampUp","rampDown")) {
                    JsonNode value=effectiveLoadValue(rootLoad,workloadLoad,key);if(value!=null&&value.isValueNode()&&!value.isNull()&&value.asText().length()<=128)summary.put(key,value.isNumber()?value.numberValue():value.asText());
                }
                JsonNode durationValue=effectiveLoadValue(rootLoad,workloadLoad,"duration");
                Long total=durationValue==null||durationValue.isNull()?null:duration(durationValue);
                for(String phase:List.of("warmup","rampUp","rampDown")) {
                    JsonNode phaseValue=effectiveLoadValue(rootLoad,workloadLoad,phase);
                    if(total!=null&&phaseValue!=null&&!phaseValue.isNull()) {Long time=duration(phaseValue);try{total=time==null?null:Math.addExact(total,time);}catch(ArithmeticException overflow){total=null;}}
                }
                if(total!=null)summary.put("totalDurationMs",total);
                summary.put("model",nonNull(effectiveLoadValue(rootLoad,workloadLoad,"arrivalRate"))?"arrivalRate":nonNull(effectiveLoadValue(rootLoad,workloadLoad,"users"))?"virtualUsers":"Not available");summaries.add(summary);
            }
        }
        Collections.sort(signature);
        Map<String,Object> out;
        if(workloads.size()==1&&bindings==1&&!targets.isEmpty()) {var t=targets.get(0);out=identity(t.get("type"),t.get("id"),targets,List.of(t.get("type"),t.get("id")));}
        else out=identity("load",workloads.size()+" workloads / "+bindings+" target bindings",targets,signature);
        out.put("workloadCount",workloads.size());out.put("bindingCount",bindings);out.put("workloads",summaries);out.put("truncated",workloads.size()>32||bindings>32);return out;
    }
    /** Complete private search relation, independent of the 32-target display preview.
     * Retained RUN selections are bounded at 10000; LOAD snapshots are size bounded. */
    static Set<Map<String,String>> searchTargets(JsonNode definition) {
        Set<Map<String,String>> out=new LinkedHashSet<>();
        if("load".equals(definition.path("executionType").asText())) {
            for(JsonNode workload:definition.path("scenario").path("workloads")) {
                addTarget(out,workload.path("target"));
                for(JsonNode entry:workload.path("mix"))addTarget(out,entry.path("target"));
            }
        } else {
            addTarget(out,definition.path("target"));
            for(JsonNode id:definition.path("selection").path("caseIds")) {
                String value=text(id);if(!value.isBlank())out.add(Map.of("type","case","id",value));
            }
        }
        return out;
    }
    private static void addTarget(Set<Map<String,String>> out,JsonNode value) {
        Map<String,String> member=target(value);if(!member.isEmpty())out.add(member);
    }
    private static Map<String,Object> identity(String type,String id,List<Map<String,String>> targets,Object signature) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("type",type);out.put("id",id);out.put("targets",targets);
        try {out.put("signature",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(List.of(type,signature)).getBytes(StandardCharsets.UTF_8))));}
        catch(Exception failed){throw new IllegalStateException(failed);}return out;
    }
    private static Map<String,String> target(JsonNode value) {
        String type=value.path("type").asText(""),id=text(value.path("id"));
        return Set.of("flow","template","tool","case").contains(type)&&!id.isBlank()?Map.of("type",type,"id",id):Map.of();
    }
    private static String text(JsonNode node){String value=node.asText("");return value.length()<=512&&!value.chars().anyMatch(Character::isISOControl)?value:"";}
    private static JsonNode effectiveLoadValue(JsonNode root,JsonNode workload,String key){return workload!=null&&workload.isObject()&&workload.has(key)?workload.get(key):root==null?null:root.get(key);}
    private static boolean nonNull(JsonNode value){return value!=null&&!value.isNull();}
    private static String json(Object value){try{return ServerRuntime.JSON.writeValueAsString(value);}catch(Exception failed){throw new IllegalArgumentException(failed);}}
    private static Long duration(JsonNode node) {
        if(node.isNumber())try{return node.decimalValue().longValueExact();}catch(ArithmeticException invalid){return null;}
        String value=node.asText("");var match=java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)").matcher(value);
        if(!match.matches())return null;
        try{return new java.math.BigDecimal(match.group(1)).multiply(java.math.BigDecimal.valueOf(switch(match.group(2)){case "ms"->1;case "s"->1000;case "m"->60000;default->3600000;})).longValueExact();}
        catch(ArithmeticException invalid){return null;}
    }
}
