package att.testcase;

import att.api.NativeSuiteRetryLineage;
import att.core.IdentifierValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/** Resolves a local Native Suite retry only from a hash-verified latest-run pointer. */
public final class NativeSuiteRetryResolver {
    private NativeSuiteRetryResolver() { }

    /** Stable per-Package/Definition pointer name so unrelated RUNs cannot hide retry history. */
    public static String latestPointerFileName(String packageId,List<String> definitionIds) throws Exception {
        if(packageId==null||packageId.trim().isEmpty()||definitionIds==null||definitionIds.isEmpty())
            throw new IllegalArgumentException("Native Suite retry pointer identity is required");
        List<String> sorted=new ArrayList<String>(definitionIds);sorted.sort(Comparator.naturalOrder());
        StringBuilder identity=new StringBuilder().append(packageId.length()).append(':').append(packageId);
        for(String id:sorted) {
            if(id==null||id.trim().isEmpty())throw new IllegalArgumentException("Native Suite retry Definition IDs are required");
            identity.append('\n').append(id.length()).append(':').append(id);
        }
        return "latest-native-suite-"+sha256(identity.toString().getBytes(StandardCharsets.UTF_8))+".yaml";
    }

    public static NativeSuiteRetryLineage resolve(Path outputRoot, String packageId,
            List<String> definitionIds, List<ResolvedTestCase> currentCases) throws Exception {
        Path root=IdentifierValidator.canonicalPath(outputRoot,"Native Suite output root");
        Path latest=root.resolve(latestPointerFileName(packageId,definitionIds));
        // Accept an existing workspace created before the dedicated pointer was introduced,
        // but never fall back from a present (and possibly invalid) scoped pointer.
        if(!Files.exists(latest,LinkOption.NOFOLLOW_LINKS))latest=root.resolve("latest-run.yaml");
        if(Files.isSymbolicLink(latest)||!Files.isRegularFile(latest,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(latest)>65536)
            throw unavailable("No hash-verified latest RUN is available");
        Object latestValue=new Yaml().load(new String(Files.readAllBytes(latest),StandardCharsets.UTF_8));
        if(!(latestValue instanceof Map<?,?>)||!"att-latest-run/v2.1".equals(((Map<?,?>)latestValue).get("schemaVersion")))
            throw unavailable("The latest RUN pointer is invalid");
        Map<?,?> pointer=(Map<?,?>)latestValue;
        String sourceRunId=string(pointer,"runId");
        IdentifierValidator.runId(sourceRunId);
        String directoryText=string(pointer,"runDirectory");
        Path directoryPath;
        try { directoryPath=Paths.get(directoryText); }
        catch (RuntimeException invalidPath) { throw unavailable("The latest RUN pointer has an invalid Run directory"); }
        if(directoryPath.isAbsolute())throw unavailable("The latest RUN pointer has an absolute Run directory");
        Path pointedDirectory=root.resolve(directoryPath).normalize();
        if(!root.equals(pointedDirectory.getParent())||!sourceRunId.equals(String.valueOf(pointedDirectory.getFileName())))
            throw unavailable("The latest RUN pointer does not name its direct Run directory");
        Path runDirectory=IdentifierValidator.strictExistingChild(root,sourceRunId,"Latest RUN directory");
        if(!runDirectory.toAbsolutePath().normalize().equals(pointedDirectory.toAbsolutePath().normalize()))
            throw unavailable("The latest RUN pointer resolves outside its direct Run directory");
        Path manifestPath=runDirectory.resolve("run.yaml");
        if(Files.isSymbolicLink(manifestPath)||!Files.isRegularFile(manifestPath,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(manifestPath)>8L*1024L*1024L)
            throw unavailable("The latest Native Suite RUN manifest is unavailable");
        byte[] manifestBytes=Files.readAllBytes(manifestPath);
        String digest=sha256(manifestBytes);
        if(!digest.equals(pointer.get("manifestSha256")))
            throw unavailable("The latest RUN manifest hash does not match its pointer");
        Object loaded=new Yaml().load(new String(manifestBytes,StandardCharsets.UTF_8));
        if(!(loaded instanceof Map<?,?>)||!(((Map<?,?>)loaded).get("run") instanceof Map<?,?>)
                ||!(((Map<?,?>)loaded).get("cases") instanceof List<?>))
            throw unavailable("The latest RUN manifest is invalid");
        Map<?,?> manifest=(Map<?,?>)loaded;Map<?,?> run=(Map<?,?>)manifest.get("run");
        List<?> cases=(List<?>)manifest.get("cases");
        if(!"COMPLETE".equals(run.get("state"))||!"nativeSuite".equals(run.get("targetType"))
                ||!packageId.equals(run.get("packageId"))||!sourceRunId.equals(run.get("id")))
            throw unavailable("The latest completed RUN is not an eligible Native Suite RUN for this Package");
        List<String> sourceDefinitions=strings(run.get("definitionIds"));
        List<String> expectedDefinitions=new ArrayList<String>(definitionIds);
        expectedDefinitions.sort(Comparator.naturalOrder());
        if(!expectedDefinitions.equals(sourceDefinitions))
            throw unavailable("The latest completed Native Suite RUN used different Definition IDs");
        int sourceSelectedCount=integer(run.get("selectedCaseCount"),"selectedCaseCount");
        if(!(run.get("caseSources") instanceof List<?>))
            throw unavailable("The latest Native Suite RUN does not retain Case source revisions required for safe retry");
        List<?> caseSources=(List<?>)run.get("caseSources");
        if(sourceSelectedCount!=cases.size()||caseSources.size()!=sourceSelectedCount)
            throw unavailable("The latest Native Suite RUN has inconsistent selected Case counts");
        verifyCurrentRevisions(run,definitionIds,currentCases);
        Map<String,Map<?,?>> currentById=new LinkedHashMap<String,Map<?,?>>();
        for(ResolvedTestCase testCase:currentCases) currentById.put(testCase.qualifiedCaseId(),currentIdentity(testCase));
        Map<String,Map<?,?>> sourceById=new LinkedHashMap<String,Map<?,?>>();
        for(Object value:caseSources) {
            if(!(value instanceof Map<?,?>))throw unavailable("The latest Native Suite RUN has duplicate or invalid Case source identities");
            Map<?,?> row=(Map<?,?>)value;Object rawId=row.get("qualifiedCaseId");
            if(!(rawId instanceof String)||sourceById.put((String)rawId,row)!=null)
                throw unavailable("The latest Native Suite RUN has duplicate or invalid Case source identities");
        }
        for(Map.Entry<String,Map<?,?>> source:sourceById.entrySet()) {
            Map<?,?> current=currentById.get(source.getKey());
            if(current==null||!sameIdentity(source.getValue(),current))
                throw unavailable("Pinned Case source changed since the latest RUN: " + source.getKey());
        }
        String sourceRevisionDigest=string(run,"sourceIdentityDigest");
        if(!sourceRevisionDigest.matches("[a-f0-9]{64}")
                ||!sourceRevisionDigest.equals(sourceIdentityDigest(sourceDefinitions,caseSources,currentById)))
            throw unavailable("Pinned Definition or Flow revisions changed since the latest RUN");
        Set<String> seen=new LinkedHashSet<String>();List<String> failed=new ArrayList<String>();
        for(Object value:cases) {
            if(!(value instanceof Map<?,?>))throw unavailable("The latest Native Suite RUN has an invalid Case result row");
            Map<?,?> row=(Map<?,?>)value;Object rawId=row.get("qualifiedCaseId");
            if(!(rawId instanceof String)||!seen.add((String)rawId))
                throw unavailable("The latest Native Suite RUN has duplicate or invalid Qualified Case IDs");
            String id=(String)rawId;Map<?,?> sourceIdentity=sourceById.get(id),currentIdentity=currentById.get(id);
            if(sourceIdentity==null||currentIdentity==null||!sameIdentity(sourceIdentity,currentIdentity))
                throw unavailable("Pinned Case source changed since the latest RUN: " + id);
            if(!(row.get("executed") instanceof Boolean)||!(row.get("status") instanceof String))
                throw unavailable("The latest Native Suite RUN has incomplete Case execution status");
            if(Boolean.TRUE.equals(row.get("executed"))
                    &&Arrays.asList("FAIL","ERROR","INVALID").contains(row.get("status"))) failed.add(id);
        }
        if(!seen.equals(sourceById.keySet()))
            throw unavailable("The latest Native Suite RUN result IDs do not match its retained source identities");
        if(failed.isEmpty())throw unavailable("The latest eligible completed Native Suite RUN has no failed executed Cases");
        if(failed.size()>TestCaseSuiteResolver.MAX_SELECTED_CASES)
            throw unavailable("The latest Native Suite RUN exceeds the retry selection limit");
        int attempt=1;
        Object prior=run.get("retryLineage");
        if(prior instanceof Map<?,?>)attempt=Math.addExact(integer(((Map<?,?>)prior).get("retryAttemptNumber"),"retryAttemptNumber"),1);
        return new NativeSuiteRetryLineage(null,sourceRunId,digest,sourceRevisionDigest,sourceSelectedCount,attempt,failed);
    }

    private static void verifyCurrentRevisions(Map<?,?> run,List<String> definitionIds,
            List<ResolvedTestCase> currentCases) {
        Object raw=run.get("definitions");
        if(!(raw instanceof List<?>))throw unavailable("The latest Native Suite RUN does not retain Definition revisions");
        List<?> sourceDefinitions=(List<?>)raw;
        Map<String,Map<?,?>> sourceById=new LinkedHashMap<String,Map<?,?>>();
        for(Object value:sourceDefinitions)if(value instanceof Map<?,?>) {
            Map<?,?> row=(Map<?,?>)value;Object id=row.get("definitionId");if(id instanceof String)sourceById.put((String)id,row);
        }
        Map<String,ResolvedTestCase> currentById=new LinkedHashMap<String,ResolvedTestCase>();
        for(ResolvedTestCase testCase:currentCases)currentById.putIfAbsent(testCase.definitionId(),testCase);
        if(sourceById.size()!=definitionIds.size())throw unavailable("The latest Native Suite RUN has incomplete Definition revision evidence");
        for(String id:definitionIds) {
            Map<?,?> source=sourceById.get(id);ResolvedTestCase current=currentById.get(id);
            if(source==null||current==null||!current.sourceReference().equals(source.get("sourceReference"))
                    ||!current.sourceRevision().equals(source.get("sourceRevision")))
                throw unavailable("Pinned Definition source changed since the latest RUN: " + id);
        }
    }

    /** Canonical per-Case identity frozen into Native Suite RUN manifests and compared on retry. */
    public static Map<String,Object> caseSourceIdentity(ResolvedTestCase testCase) {
        if(testCase==null)throw new IllegalArgumentException("Resolved Case is required");
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("definitionId",testCase.definitionId());result.put("qualifiedCaseId",testCase.qualifiedCaseId());
        result.put("sourceReference",testCase.sourceReference());result.put("sourceRevision",testCase.sourceRevision());
        result.put("flowId",testCase.flowId());result.put("flowRevision",testCase.flowRevision());
        if(testCase.sourceRecordId()!=null)result.put("sourceRecordId",testCase.sourceRecordId());
        if(testCase.recordIndex()!=null)result.put("recordIndex",testCase.recordIndex());
        CaseSourceProvenance provenance=testCase.sourceProvenance();
        result.put("dataSourceType",provenance.sourceType());
        result.put("dataSourceReference",provenance.sourceReference());
        Object workbookSha=provenance.metadata().get("workbookSha256");
        if(workbookSha instanceof String&&((String)workbookSha).matches("[a-f0-9]{64}"))
            result.put("workbookSha256",workbookSha);
        result.put("resolvedInputDigest",resolvedInputDigest(testCase));
        List<Map<String,Object>> dependencies=new ArrayList<Map<String,Object>>();
        for(ResolvedTestCase.FlowDependency dependency:testCase.dependencies())dependencies.add(dependency.toMap());
        result.put("dependencies",dependencies);return result;
    }

    private static Map<String,Object> currentIdentity(ResolvedTestCase testCase) {
        return caseSourceIdentity(testCase);
    }

    private static String resolvedInputDigest(ResolvedTestCase testCase) {
        try {
            Map<String,Object> payload=new LinkedHashMap<String,Object>();
            payload.put("data",testCase.data());
            payload.put("inputs",testCase.inputs());
            return sha256(new Yaml().dump(payload).getBytes(StandardCharsets.UTF_8));
        } catch(Exception failure) {
            throw new IllegalStateException("Unable to digest resolved Case inputs for " + testCase.qualifiedCaseId(), failure);
        }
    }
    private static String sourceIdentityDigest(List<String> definitionIds,List<?> sourceCaseRows,
            Map<String,Map<?,?>> currentById) throws Exception {
        Map<String,Map<?,?>> currentByDefinition=new LinkedHashMap<String,Map<?,?>>();
        for(Map.Entry<String,Map<?,?>> entry:currentById.entrySet())
            currentByDefinition.putIfAbsent(String.valueOf(entry.getValue().get("definitionId")),entry.getValue());
        List<Map<String,Object>> definitions=new ArrayList<Map<String,Object>>();
        for(String id:definitionIds) {
            Map<?,?> identity=currentByDefinition.get(id);
            if(identity==null)throw unavailable("Current Definition identity is unavailable: " + id);
            Map<String,Object> definition=new LinkedHashMap<String,Object>();definition.put("definitionId",id);
            definition.put("sourceReference",identity.get("sourceReference"));definition.put("sourceRevision",identity.get("sourceRevision"));
            definitions.add(definition);
        }
        List<Map<String,Object>> caseSources=new ArrayList<Map<String,Object>>();
        for(Object raw:sourceCaseRows) {
            if(!(raw instanceof Map<?,?>))
                throw unavailable("The latest Native Suite RUN has an invalid Case source identity");
            Map<?,?> source=(Map<?,?>)raw;Object rawId=source.get("qualifiedCaseId");
            if(!(rawId instanceof String))throw unavailable("The latest Native Suite RUN has an invalid Case source identity");
            String id=(String)rawId;
            Map<?,?> current=currentById.get(id);if(current==null)throw unavailable("Current Case source is unavailable: " + id);
            @SuppressWarnings("unchecked") Map<String,Object> typed=(Map<String,Object>)current;
            caseSources.add(typed);
        }
        List<Object> identity=new ArrayList<Object>();identity.add(definitions);identity.add(caseSources);
        byte[] bytes=new Yaml().dump(identity).getBytes(StandardCharsets.UTF_8);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder(64);
        for(byte value:hash)result.append(String.format(java.util.Locale.ROOT,"%02x",value&0xff));return result.toString();
    }
    private static boolean sameIdentity(Map<?,?> source,Map<?,?> current) {
        for(String key:Arrays.asList("definitionId","qualifiedCaseId","sourceReference","sourceRevision",
                "flowId","flowRevision","sourceRecordId","recordIndex","dataSourceType","dataSourceReference",
                "workbookSha256","resolvedInputDigest","dependencies"))
            if(!java.util.Objects.deepEquals(source.get(key),current.get(key)))return false;
        return true;
    }
    private static List<String> strings(Object value) {
        if(!(value instanceof List<?>))throw unavailable("The latest Native Suite RUN has invalid Definition IDs");
        List<?> list=(List<?>)value;
        List<String> result=new ArrayList<String>();for(Object item:list) {
            if(!(item instanceof String))throw unavailable("The latest Native Suite RUN has invalid Definition IDs");
            result.add((String)item);
        }return result;
    }
    private static int integer(Object value,String field) {
        if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue()||((Number)value).intValue()<0)
            throw unavailable("The latest Native Suite RUN has an invalid " + field);
        return ((Number)value).intValue();
    }
    private static String string(Map<?,?> value,String field) {
        Object item=value.get(field);if(!(item instanceof String)||((String)item).trim().isEmpty())
            throw unavailable("The latest RUN pointer has an invalid " + field);return (String)item;
    }
    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder(64);
        for(byte value:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",value&0xff));return result.toString();
    }
    private static IllegalArgumentException unavailable(String detail) {
        return new IllegalArgumentException("--rerun-failed requires the latest eligible completed Native Suite RUN: " + detail);
    }
}
