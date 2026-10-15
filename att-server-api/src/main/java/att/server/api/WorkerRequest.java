package att.server.api;

import java.util.List;
import java.util.Map;

/** Versioned wire request. Paths are interpreted relative to packageRoot when not absolute. */
public class WorkerRequest {
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
    public Map<String,Object> inlineLoadScenario;
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
    public Map<String,Object> inlineDebugInput;
    public Map<String,Object> inlineLoadInput;
    public String loadModel;
    public Map<String,Object> loadOverrides;
    public Map<String,Object> workloadExecution;
    public List<String> quickLoadTestdata;
    public Boolean unsafeFailureDetails;
    public Map<String,String> load;
    public List<String> overrides;
    public String inspectionAction;
    public String inspectionType;
    public String inspectionEnvironment;
    public String inspectionOtherEnvironment;
    public String inspectionSection;
    public Boolean inspectionIncludeInherited;
    public Boolean inspectionIncludeAllFields;
    public String inspectionPath;
    public String inspectionOrigin;
    /** Server-issued token for a Configuration-source file reference. */
    public String inspectionReferenceId;
    public String inspectionResourceId;
    public String inspectionTargetId;
    public String draftResourceId;
    public String inspectionQuery;
    public String inspectionSort;
    public String inspectionDirection;
    public Integer inspectionOffset;
    public Integer inspectionLimit;
    public String expectedRevisionDigest;
    public List<String> safeTextSources;
    /** Exact canonical external script files approved by the Server for this package. */
    public List<String> externalTextSources;
    /** Canonical roots against which externalTextSources are independently checked. */
    public List<String> externalSourceRoots;
    public Integer maxSourceBytes;
    public Integer maxResponseBytes;
    public Boolean sourceBrowsingEnabled;
    public String sourceBrowsingPolicy;
    public List<String> sourceDenyGlobs;
    public Boolean redactionEnabled;
    public List<String> sensitivePaths;
    public List<String> sensitiveFieldPatterns;
}
