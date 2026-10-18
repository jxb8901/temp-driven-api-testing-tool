package att.server;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted definition and public view of one package-backed scheduled job. */
final class LoadSchedule {
    public String schemaVersion="att-schedule/v2";
    public Map<String,Object> runInput;
    public String id;
    public String name;
    public String description;
    public List<String> tags=List.of();
    public String packageId;
    /** Missing legacy values deserialize as Load to preserve existing stored schedules. */
    public String jobType="load";
    public String scenario;
    /** Server-validated immutable scenario snapshot for schedules created from a UI draft. */
    public Map<String,Object> inlineScenario;
    /** Marks a Server-validated package-file snapshot; this does not depend on browser Inline Load policy. */
    public boolean catalogSnapshot;
    /** Server-validated immutable Debug input snapshot; secret defaults use path-bound keep markers. */
    public Map<String,Object> inlineDebugInput;
    public String debugTargetType;
    public String debugTargetId;
    public String debugResourceId;
    public String expectedRevisionDigest;
    public String loadModel;
    public String config;
    public String environment;
    public String timezone;
    public String cron;
    /** ISO-8601 instant for a single firing; mutually exclusive with cron. */
    public String runAt;
    public Map<String,String> load=new LinkedHashMap<>();
    public int maxConcurrentRuns=1;
    public boolean enabled;
    public String createdBy;
    /** User identity used for automatic occurrences; old records without it are migrated on manual trigger. */
    public String executionPrincipal;
    /** Non-null when an active USER explicitly adopted a legacy ADMIN-created schedule. */
    public String executionIdentityMigratedFrom;
    public String executionIdentityMigratedBy;
    public String executionIdentityMigratedAt;
    public String createdAt;
    public String updatedBy;
    public String updatedAt;
    public String nextRunAt;

    LoadSchedule copy() {
        LoadSchedule copy=new LoadSchedule();
        copy.schemaVersion=schemaVersion;copy.runInput=runInput==null?null:ServerRuntime.JSON.convertValue(runInput,Map.class);
        copy.id=id;copy.name=name;copy.description=description;copy.packageId=packageId;copy.jobType=jobType==null?"load":jobType;copy.scenario=scenario;
        copy.inlineScenario=inlineScenario==null?null:new LinkedHashMap<>(inlineScenario);copy.catalogSnapshot=catalogSnapshot;copy.loadModel=loadModel;
        copy.inlineDebugInput=inlineDebugInput==null?null:new LinkedHashMap<>(inlineDebugInput);
        copy.debugTargetType=debugTargetType;copy.debugTargetId=debugTargetId;copy.debugResourceId=debugResourceId;
        copy.expectedRevisionDigest=expectedRevisionDigest;
        copy.tags=tags==null?List.of():List.copyOf(tags);
        copy.config=config;copy.environment=environment;copy.timezone=timezone;copy.cron=cron;copy.runAt=runAt;
        copy.load=load==null?new LinkedHashMap<>():new LinkedHashMap<>(load);copy.maxConcurrentRuns=maxConcurrentRuns;copy.enabled=enabled;
        copy.createdBy=createdBy;copy.executionPrincipal=executionPrincipal;
        copy.executionIdentityMigratedFrom=executionIdentityMigratedFrom;copy.executionIdentityMigratedBy=executionIdentityMigratedBy;
        copy.executionIdentityMigratedAt=executionIdentityMigratedAt;
        copy.createdAt=createdAt;copy.updatedBy=updatedBy;copy.updatedAt=updatedAt;copy.nextRunAt=nextRunAt;
        return copy;
    }

    Instant nextRunInstant(){return nextRunAt==null?null:Instant.parse(nextRunAt);}
    static boolean sameInstant(String left,String right) {
        if(left==null||right==null)return left==null&&right==null;
        try{return Instant.parse(left).equals(Instant.parse(right));}
        catch(DateTimeParseException invalid){return left.equals(right);}
    }
    String effectiveJobType(){
        if(schemaVersion!=null&&!"att-schedule/v2".equals(schemaVersion))throw new IllegalArgumentException("Unsupported schedule schemaVersion");
        if(jobType==null||"load".equals(jobType))return "load";
        if("run".equals(jobType)||"debug".equals(jobType))return jobType;
        throw new IllegalArgumentException("Unsupported scheduled execution type");
    }
}
