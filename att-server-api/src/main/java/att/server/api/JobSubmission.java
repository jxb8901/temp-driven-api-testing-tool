package att.server.api;

import java.util.List;
import java.util.Map;

/** Public submit DTO. Paths are package-relative logical names; packageRoot/outputDirectory are intentionally absent. */
public final class JobSubmission {
    public String packageId;
    public String config;
    public String environment;
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
    /** Server-issued in-memory Debug draft. */
    public String draftId;
    public Map<String,String> load;
    public List<String> overrides;
    public JobSubmission() { }
}
