package att.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed Load scenario or single-target Load execution intent. */
public final class LoadRequest extends AttRequest {
    private final Path scenario; private final String debugTargetType, debugTargetId;
    private final String users, arrivalRate, warmup, rampUp, duration, rampDown, thinkTime, maxConcurrent, overloadPolicy;
    private final List<String> overrides;
    public LoadRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                       Path scenario, String debugTargetType, String debugTargetId, String users, String arrivalRate,
                       String warmup, String rampUp, String duration, String rampDown, String thinkTime,
                       String maxConcurrent, String overloadPolicy, List<String> overrides) {
        super(packageRoot, configPath, environment, outputDirectory, runId);
        if (scenario == null && (debugTargetType == null || debugTargetId == null)) throw new IllegalArgumentException("Load requires a scenario or a Debug target");
        this.scenario = scenario; this.debugTargetType = debugTargetType; this.debugTargetId = debugTargetId;
        this.users=users; this.arrivalRate=arrivalRate; this.warmup=warmup; this.rampUp=rampUp; this.duration=duration;
        this.rampDown=rampDown; this.thinkTime=thinkTime; this.maxConcurrent=maxConcurrent; this.overloadPolicy=overloadPolicy;
        this.overrides = Collections.unmodifiableList(new ArrayList<String>(overrides == null ? Collections.<String>emptyList() : overrides));
    }
    public Path scenario() { return scenario; } public String debugTargetType() { return debugTargetType; } public String debugTargetId() { return debugTargetId; }
    public String users() { return users; } public String arrivalRate() { return arrivalRate; } public String warmup() { return warmup; } public String rampUp() { return rampUp; }
    public String duration() { return duration; } public String rampDown() { return rampDown; } public String thinkTime() { return thinkTime; }
    public String maxConcurrent() { return maxConcurrent; } public String overloadPolicy() { return overloadPolicy; } public List<String> overrides() { return overrides; }
}
