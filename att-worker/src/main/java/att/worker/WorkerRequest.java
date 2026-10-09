package att.worker;

import java.util.List;
import java.util.Map;

/** Versioned wire request. Paths are interpreted relative to packageRoot when not absolute. */
public final class WorkerRequest {
    public String protocolVersion;
    public String jobId;
    public String command;
    public String packageRoot;
    public String config;
    public String environment;
    public String outputDirectory;
    public String runId;
    public String debugId;
    public Map<String,Object> target;
    public String scenario;
    public List<String> suites;
    public String suiteDirectory;
    public List<String> caseIds;
    public List<String> tags;
    public List<String> excludeTags;
    public Boolean all;
    public Boolean rerunFailed;
    public Boolean dryRun;
    public Boolean failFast;
    public String validationScope;
    public String debugInput;
    public Boolean unsafeFailureDetails;
    public Map<String,String> load;
    public List<String> overrides;
}
