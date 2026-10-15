package att.server;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted definition and public view of one package-backed scheduled job. */
final class LoadSchedule {
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
    public Map<String,String> load=new LinkedHashMap<>();
    public int maxConcurrentRuns=1;
    public boolean enabled;
    public String createdBy;
    public String createdAt;
    public String updatedBy;
    public String updatedAt;
    public String nextRunAt;

    LoadSchedule copy() {
        LoadSchedule copy=new LoadSchedule();
        copy.id=id;copy.name=name;copy.description=description;copy.packageId=packageId;copy.jobType=jobType==null?"load":jobType;copy.scenario=scenario;
        copy.inlineScenario=inlineScenario==null?null:new LinkedHashMap<>(inlineScenario);copy.loadModel=loadModel;
        copy.inlineDebugInput=inlineDebugInput==null?null:new LinkedHashMap<>(inlineDebugInput);
        copy.debugTargetType=debugTargetType;copy.debugTargetId=debugTargetId;copy.debugResourceId=debugResourceId;
        copy.expectedRevisionDigest=expectedRevisionDigest;
        copy.tags=tags==null?List.of():List.copyOf(tags);
        copy.config=config;copy.environment=environment;copy.timezone=timezone;copy.cron=cron;
        copy.load=load==null?new LinkedHashMap<>():new LinkedHashMap<>(load);copy.maxConcurrentRuns=maxConcurrentRuns;copy.enabled=enabled;
        copy.createdBy=createdBy;copy.createdAt=createdAt;copy.updatedBy=updatedBy;copy.updatedAt=updatedAt;copy.nextRunAt=nextRunAt;
        return copy;
    }

    Instant nextRunInstant(){return nextRunAt==null?null:Instant.parse(nextRunAt);}
    String effectiveJobType(){return "debug".equals(jobType)?"debug":"load";}
}
