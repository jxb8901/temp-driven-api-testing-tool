package att.server.api;

import java.util.List;
import java.util.Map;

/** Versioned wire request. Paths are interpreted relative to packageRoot when not absolute. */
public class WorkerRequest {
    /** Protocol used for ordinary Worker requests. */
    public static final String PROTOCOL_V1 = "att-worker/v1";
    /** Fail-closed protocol required when the Server supplies an accepted source snapshot. */
    public static final String SOURCE_SNAPSHOT_PROTOCOL = "att-worker/v2";
    /** Internal one-shot prewarm envelope. The Server overwrites caller values. */
    public Map<String,String> executionEnvironment;
    /** Trusted Server policy, snapshotted for each new Job. */
    public Integer liveOutputMaxEntryChars;
    public Integer liveOutputMaxEntryLines;
    public String protocolVersion;
    public String jobId;
    public String command;
    /** Optional RUN diagnostic detail; null preserves legacy event behavior. */
    public Boolean diagnosticMode;
    public String packageRoot;
    public String config;
    /** Internal immutable Package plus central Config source bundle used for accepted Jobs. */
    public String sourceSnapshotArchivePath;
    /** SHA-256 of the complete source snapshot archive; Worker verifies before extraction. */
    public String sourceSnapshotArchiveSha256;
    /** Server-materialized immutable source tree, mounted read-only for the entire Worker process tree. */
    public String sourceSnapshotDirectory;
    /** Config path relative to the Config root inside sourceSnapshotArchivePath. */
    public String sourceSnapshotConfigPath;
    public String environment;
    /** Internal Server-approved value-free configuration snapshot. */
    public Map<String,Object> configurationProvenance;
    public String registeredPackageId;
    /** Internal identity for the trusted Package Registry snapshot used by this inspection. */
    public String packageRegistryRevision;
    public String outputDirectory;
    public String runId;
    public String debugId;
    public Map<String,Object> target;
    public String scenario;
    public Map<String,Object> inlineLoadScenario;
    public List<String> suites;
    public String suiteDirectory;
    public List<String> caseIds;
    /** Server-validated native RUN selector; never accepted directly from public /jobs/run requests. */
    public String nativeCaseDefinitionId;
    /** Definition-local Case IDs selected by a validated att-execution/v2 draft. */
    public List<String> nativeResolvedCaseIds;
    /** Exact package-relative testcase.yaml frozen by draft validation and included in the accepted source closure. */
    public String nativeCaseSourcePath;
    public List<String> tags;
    public List<String> excludeTags;
    public Boolean all;
    public Boolean rerunFailed;
    public Boolean dryRun;
    public Boolean failFast;
    public String validationScope;
    /** Server policy filter for installation-wide, profile-aware static validation. */
    public List<String> validationAllowedEnvironments;
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
    /** Internal, bounded private Manifest candidate for source validation; never an execution overlay. */
    public String packageManifestSource;
    public String inspectionAction;
    public String inspectionType;
    public String inspectionEnvironment;
    public String inspectionOtherEnvironment;
    public String inspectionSection;
    public Boolean inspectionIncludeInherited;
    public Boolean inspectionIncludeAllFields;
    public Boolean inspectionSharedConfiguration;
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
    /** Bounded editor-owned Test Case YAML used only by the read-only native-case preview inspection. */
    public String nativeCasePreviewSource;
    /** Internal Server-authorized lossless source read; never populated from an untrusted public body. */
    public String authoringSourceKind;
    public String authoringResourceType;
    /** Closed selector for an indexed Testcase-authored source, such as its workbook sidecar. */
    public String authoringSourceSelector;
    public Integer inspectionOffset;
    public Integer inspectionLimit;
    /** Canonical execution-bound revision identity. */
    public String expectedExecutionRevisionDigest;
    /** @deprecated Legacy alias for expectedExecutionRevisionDigest. */
    @Deprecated
    public String expectedRevisionDigest;
    /** Server-computed identity for the matching Local Runtime's embedded schemas. */
    public String expectedSchemaRegistryDigest;
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

    /**
     * Resolves old and new wire fields to one execution identity. Keeping the legacy field populated
     * lets this request remain readable by older Worker binaries during rolling upgrades.
     */
    public void normalizeExpectedExecutionRevisionDigest() {
        if (expectedExecutionRevisionDigest != null && expectedRevisionDigest != null
                && !expectedExecutionRevisionDigest.equals(expectedRevisionDigest))
            throw new IllegalArgumentException("Worker execution revision fields disagree");
        String revision = expectedExecutionRevisionDigest != null
                ? expectedExecutionRevisionDigest : expectedRevisionDigest;
        expectedExecutionRevisionDigest = revision;
        expectedRevisionDigest = revision;
    }

    public void setExpectedExecutionRevisionDigest(String revision) {
        expectedExecutionRevisionDigest = revision;
        expectedRevisionDigest = revision;
    }
}
