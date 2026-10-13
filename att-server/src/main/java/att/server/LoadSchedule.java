package att.server;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted definition and public view of one package-backed scheduled Load. */
final class LoadSchedule {
    public String id;
    public String name;
    public String description;
    public List<String> tags=List.of();
    public String packageId;
    public String scenario;
    public String loadModel;
    public String config;
    public String environment;
    public String timezone;
    public String cron;
    public Map<String,String> load=new LinkedHashMap<>();
    public boolean enabled;
    public String createdBy;
    public String createdAt;
    public String updatedBy;
    public String updatedAt;
    public String nextRunAt;

    LoadSchedule copy() {
        LoadSchedule copy=new LoadSchedule();
        copy.id=id;copy.name=name;copy.description=description;copy.packageId=packageId;copy.scenario=scenario;copy.loadModel=loadModel;
        copy.tags=tags==null?List.of():List.copyOf(tags);
        copy.config=config;copy.environment=environment;copy.timezone=timezone;copy.cron=cron;
        copy.load=load==null?new LinkedHashMap<>():new LinkedHashMap<>(load);copy.enabled=enabled;
        copy.createdBy=createdBy;copy.createdAt=createdAt;copy.updatedBy=updatedBy;copy.updatedAt=updatedAt;copy.nextRunAt=nextRunAt;
        return copy;
    }

    Instant nextRunInstant(){return nextRunAt==null?null:Instant.parse(nextRunAt);}
}
