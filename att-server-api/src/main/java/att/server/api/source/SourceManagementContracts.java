package att.server.api.source;

import att.security.SecretProtection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned, value-free contracts shared by Source publication, runtime guards, and clients. */
public final class SourceManagementContracts {
    public static final String CONTRACT_VERSION = "att-source-management/v1";

    private SourceManagementContracts() { }

    public enum Domain { PACKAGE_SOURCE, CENTRAL_RUNTIME_CONFIG }

    /** Manifest metadata is deliberately separate from the six managed Package resource categories. */
    public enum PackageCategory { TESTCASE, TEMPLATE, FLOW, LOAD, TESTDATA, FILE }

    public enum RevisionKind {
        PACKAGE_REGISTRATION, PACKAGE_SOURCE, BUSINESS_SOURCE, CHANGE_SET, PACKAGE_MANIFEST,
        CONFIG_SOURCE, EFFECTIVE_CONFIG, ENVIRONMENT, ENGINE_SCHEMA, EXECUTION_REVISION, SCHEDULE_SNAPSHOT
    }

    /** A typed identity. The value is an opaque revision token or digest, never authored source. */
    public static final class RevisionIdentity {
        public final String schemaVersion = "att-source-revision/v1";
        public final RevisionKind kind;
        public final String scopeId;
        public final String qualifier;
        public final String revision;

        public RevisionIdentity(RevisionKind kind, String scopeId, String qualifier, String revision) {
            if (kind == null) throw new IllegalArgumentException("revision kind is required");
            this.kind = kind;
            this.scopeId = requiredToken(scopeId, "scopeId", 160);
            this.qualifier = optionalToken(qualifier, "qualifier", 160);
            this.revision = requiredToken(revision, "revision", 160);
        }

        public static RevisionIdentity of(RevisionKind kind, String scopeId, String revision) {
            return new RevisionIdentity(kind, scopeId, null, revision);
        }
    }

    /** Keeps independently changing revisions distinct; absent identities remain absent. */
    public static final class RevisionSet {
        public final String schemaVersion = "att-source-revision-set/v1";
        public final RevisionIdentity packageRegistration;
        /** Canonical Package-owned content identity. */
        public final RevisionIdentity packageSourceRevision;
        /** @deprecated Compatibility alias for packageSourceRevision. */
        @Deprecated
        public final RevisionIdentity businessSource;
        /** Execution-bound identity; never use it as the Package source edit revision. */
        public final RevisionIdentity executionRevisionDigest;
        public final RevisionIdentity changeSet;
        public final RevisionIdentity manifest;
        public final RevisionIdentity configSource;
        public final RevisionIdentity effectiveConfig;
        public final RevisionIdentity environment;
        public final RevisionIdentity engineSchema;
        public final RevisionIdentity scheduleSnapshot;

        public RevisionSet(RevisionIdentity packageRegistration, RevisionIdentity businessSource,
                           RevisionIdentity changeSet, RevisionIdentity manifest,
                           RevisionIdentity configSource, RevisionIdentity effectiveConfig,
                           RevisionIdentity environment, RevisionIdentity engineSchema,
                           RevisionIdentity scheduleSnapshot) {
            this(packageRegistration, businessSource, changeSet, manifest, configSource, effectiveConfig,
                    environment, engineSchema, scheduleSnapshot, null, null);
        }

        public RevisionSet(RevisionIdentity packageRegistration, RevisionIdentity businessSource,
                           RevisionIdentity changeSet, RevisionIdentity manifest,
                           RevisionIdentity configSource, RevisionIdentity effectiveConfig,
                           RevisionIdentity environment, RevisionIdentity engineSchema,
                           RevisionIdentity scheduleSnapshot, RevisionIdentity packageSourceRevision,
                           RevisionIdentity executionRevisionDigest) {
            this.packageRegistration = requireKind(packageRegistration, RevisionKind.PACKAGE_REGISTRATION, "packageRegistration");
            RevisionIdentity legacyBusiness = requireKind(businessSource, RevisionKind.BUSINESS_SOURCE, "businessSource");
            RevisionIdentity packageSource = requireKind(packageSourceRevision, RevisionKind.PACKAGE_SOURCE, "packageSourceRevision");
            if (packageSource == null && legacyBusiness != null)
                packageSource = new RevisionIdentity(RevisionKind.PACKAGE_SOURCE, legacyBusiness.scopeId,
                        legacyBusiness.qualifier, legacyBusiness.revision);
            if (legacyBusiness == null && packageSource != null)
                legacyBusiness = new RevisionIdentity(RevisionKind.BUSINESS_SOURCE, packageSource.scopeId,
                        packageSource.qualifier, packageSource.revision);
            if (legacyBusiness != null && packageSource != null
                    && (!legacyBusiness.scopeId.equals(packageSource.scopeId)
                    || !java.util.Objects.equals(legacyBusiness.qualifier, packageSource.qualifier)
                    || !legacyBusiness.revision.equals(packageSource.revision)))
                throw new IllegalArgumentException("businessSource must match packageSourceRevision");
            this.packageSourceRevision = packageSource;
            this.businessSource = legacyBusiness;
            this.executionRevisionDigest = requireKind(executionRevisionDigest, RevisionKind.EXECUTION_REVISION,
                    "executionRevisionDigest");
            this.changeSet = requireKind(changeSet, RevisionKind.CHANGE_SET, "changeSet");
            this.manifest = requireKind(manifest, RevisionKind.PACKAGE_MANIFEST, "manifest");
            this.configSource = requireKind(configSource, RevisionKind.CONFIG_SOURCE, "configSource");
            this.effectiveConfig = requireKind(effectiveConfig, RevisionKind.EFFECTIVE_CONFIG, "effectiveConfig");
            this.environment = requireKind(environment, RevisionKind.ENVIRONMENT, "environment");
            this.engineSchema = requireKind(engineSchema, RevisionKind.ENGINE_SCHEMA, "engineSchema");
            this.scheduleSnapshot = requireKind(scheduleSnapshot, RevisionKind.SCHEDULE_SNAPSHOT, "scheduleSnapshot");
        }

        public static RevisionSet empty() {
            return new RevisionSet(null, null, null, null, null, null, null, null, null);
        }

        public List<RevisionIdentity> present() {
            List<RevisionIdentity> result = new ArrayList<RevisionIdentity>();
            for (RevisionIdentity value : Arrays.asList(packageRegistration, packageSourceRevision, businessSource, changeSet,
                    manifest, configSource, effectiveConfig, environment, engineSchema, executionRevisionDigest,
                    scheduleSnapshot))
                if (value != null) result.add(value);
            return Collections.unmodifiableList(result);
        }
    }

    public enum DependencyKind {
        PACKAGE_RESOURCE, MANIFEST, LOAD_OVERLAY, TESTDATA, FILE, TOOL, HELPER,
        ENVIRONMENT_BINDING, ENGINE_SCHEMA, SCHEDULE
    }

    public enum ReferenceCertainty { KNOWN, UNKNOWN }

    /** Logical dependency metadata only; never contains descriptor values, credentials, or file contents. */
    public static final class DependencyReference {
        public final String schemaVersion = "att-source-dependency/v1";
        public final DependencyKind kind;
        public final String packageId;
        public final String environment;
        public final String logicalId;
        public final String canonicalPath;
        public final String sourceFingerprint;
        public final String relationship;
        public final String callSite;
        public final ReferenceCertainty certainty;

        public DependencyReference(DependencyKind kind, String packageId, String environment,
                                   String logicalId, String canonicalPath, String sourceFingerprint,
                                   String relationship, String callSite, ReferenceCertainty certainty) {
            if (kind == null || certainty == null) throw new IllegalArgumentException("dependency kind and certainty are required");
            this.kind = kind;
            this.packageId = optionalToken(packageId, "packageId", 160);
            this.environment = optionalToken(environment, "environment", 64);
            this.logicalId = optionalToken(logicalId, "logicalId", 512);
            this.canonicalPath = safeLogicalPath(canonicalPath);
            this.sourceFingerprint = optionalDigest(sourceFingerprint, "sourceFingerprint");
            this.relationship = optionalToken(relationship, "relationship", 160);
            this.callSite = optionalToken(callSite, "callSite", 512);
            this.certainty = certainty;
        }
    }

    public enum ValidationState { VALID, INVALID, INCOMPLETE }
    public enum Severity { ERROR, WARNING, INFO }

    /** Safe diagnostic fields suitable for a client or audit record. */
    public static final class ValidationDiagnostic {
        public final String code;
        public final Severity severity;
        public final String logicalPath;
        public final String field;
        public final Integer line;
        public final String message;
        public final String remedy;

        public ValidationDiagnostic(String code, Severity severity, String logicalPath, String field,
                                    Integer line, String message, String remedy) {
            if (severity == null) throw new IllegalArgumentException("diagnostic severity is required");
            this.code = safeCode(code);
            this.severity = severity;
            this.logicalPath = safeLogicalPath(logicalPath);
            this.field = optionalToken(field, "field", 512);
            this.line = line == null || line.intValue() < 1 ? null : line;
            this.message = safeText(message, 512);
            this.remedy = safeText(remedy, 512);
        }
    }

    /** Result of whole-candidate validation, including independently typed revision provenance. */
    public static final class CandidateValidation {
        public final String schemaVersion = "att-source-candidate-validation/v1";
        public final Domain domain;
        public final String scopeId;
        public final ValidationState state;
        /** False when bounds or unresolved dependencies prevented whole-candidate coverage. */
        public final boolean coverageComplete;
        public final boolean executionEligible;
        public final String validationFingerprint;
        public final RevisionSet revisions;
        public final List<DependencyReference> dependencies;
        public final List<ValidationDiagnostic> diagnostics;

        public CandidateValidation(Domain domain, String scopeId, ValidationState state,
                                   boolean executionEligible, String validationFingerprint,
                                   RevisionSet revisions, List<DependencyReference> dependencies,
                                   List<ValidationDiagnostic> diagnostics) {
            this(domain, scopeId, state, state != ValidationState.INCOMPLETE, executionEligible,
                    validationFingerprint, revisions, dependencies, diagnostics);
        }

        public CandidateValidation(Domain domain, String scopeId, ValidationState state,
                                   boolean coverageComplete, boolean executionEligible,
                                   String validationFingerprint, RevisionSet revisions,
                                   List<DependencyReference> dependencies,
                                   List<ValidationDiagnostic> diagnostics) {
            if (domain == null || state == null || revisions == null)
                throw new IllegalArgumentException("candidate validation domain, state, and revisions are required");
            this.domain = domain;
            this.scopeId = requiredToken(scopeId, "scopeId", 160);
            this.state = state;
            this.coverageComplete = coverageComplete;
            this.executionEligible = executionEligible && state == ValidationState.VALID;
            this.validationFingerprint = optionalDigest(validationFingerprint, "validationFingerprint");
            this.revisions = revisions;
            this.dependencies = immutable(dependencies);
            this.diagnostics = immutable(diagnostics);
        }
    }

    public enum ResolutionOrigin { REQUEST, PACKAGE_MANIFEST, ENVIRONMENT_PROFILE, GLOBAL_CONFIG, RUNTIME_DEFAULT, UNKNOWN }

    /** Safe effective Config projection consumed by #335. No endpoint or credential values are exposed. */
    public static final class EffectiveResolution {
        public final String schemaVersion = "att-effective-resolution/v1";
        public final String packageId;
        public final String environment;
        public final String effectiveFingerprint;
        public final boolean complete;
        public final RevisionSet revisions;
        public final Map<String, ResolutionOrigin> origins;
        /** Hashes of normalized effective fields and descriptor semantics; never contains resolved values. */
        public final Map<String, String> semanticFingerprints;
        public final List<DependencyReference> bindings;
        public final List<DependencyReference> dependencies;
        public final List<ValidationDiagnostic> diagnostics;

        public EffectiveResolution(String packageId, String environment, String effectiveFingerprint,
                                   boolean complete, RevisionSet revisions,
                                   Map<String, ResolutionOrigin> origins,
                                   List<DependencyReference> bindings,
                                   List<DependencyReference> dependencies,
                                   List<ValidationDiagnostic> diagnostics) {
            this(packageId, environment, effectiveFingerprint, complete, revisions, origins,
                    Collections.<String, String>emptyMap(), bindings, dependencies, diagnostics);
        }

        public EffectiveResolution(String packageId, String environment, String effectiveFingerprint,
                                   boolean complete, RevisionSet revisions,
                                   Map<String, ResolutionOrigin> origins,
                                   Map<String, String> semanticFingerprints,
                                   List<DependencyReference> bindings,
                                   List<DependencyReference> dependencies,
                                   List<ValidationDiagnostic> diagnostics) {
            this.packageId = requiredToken(packageId, "packageId", 160);
            this.environment = requiredToken(environment, "environment", 64);
            this.effectiveFingerprint = optionalDigest(effectiveFingerprint, "effectiveFingerprint");
            this.complete = complete;
            this.revisions = revisions == null ? RevisionSet.empty() : revisions;
            this.origins = immutableMap(origins);
            this.semanticFingerprints = immutableStringMap(semanticFingerprints);
            this.bindings = immutable(bindings);
            this.dependencies = immutable(dependencies);
            this.diagnostics = immutable(diagnostics);
        }
    }

    /** Exact immutable bytes paired with their identity. Digests alone cannot construct this value. */
    public static final class VersionedSourceBytes {
        public final String schemaVersion = "att-versioned-source-bytes/v1";
        public final RevisionIdentity sourceRevision;
        public final String logicalPath;
        public final String sha256;
        private final byte[] bytes;

        public VersionedSourceBytes(RevisionIdentity sourceRevision, String logicalPath, String sha256, byte[] bytes) {
            if (sourceRevision == null || bytes == null) throw new IllegalArgumentException("source identity and bytes are required");
            this.sourceRevision = sourceRevision;
            this.logicalPath = safeLogicalPath(logicalPath);
            this.sha256 = requiredDigest(sha256, "sha256");
            if (!this.sha256.equals(digest(bytes))) throw new IllegalArgumentException("sha256 does not identify the supplied source bytes");
            this.bytes = bytes.clone();
        }

        public byte[] bytes() { return bytes.clone(); }
        public int length() { return bytes.length; }
    }

    public static final class SourceLeaseRequest {
        public final String packageId;
        public final String environment;
        public final RevisionSet expectedRevisions;
        public final List<String> logicalPaths;
        public final String ownerReference;

        public SourceLeaseRequest(String packageId, String environment, RevisionSet expectedRevisions,
                                  List<String> logicalPaths, String ownerReference) {
            this.packageId = optionalToken(packageId, "packageId", 160);
            this.environment = optionalToken(environment, "environment", 64);
            this.expectedRevisions = expectedRevisions == null ? RevisionSet.empty() : expectedRevisions;
            List<String> paths = new ArrayList<String>();
            if (logicalPaths != null) for (String path : logicalPaths) paths.add(requiredLogicalPath(path));
            this.logicalPaths = Collections.unmodifiableList(paths);
            this.ownerReference = requiredToken(ownerReference, "ownerReference", 160);
        }
    }

    public interface SourceLease extends AutoCloseable {
        String leaseId();
        RevisionSet revisions();
        VersionedSourceBytes read(String logicalPath) throws IOException;
        SourceLease retain(String ownerReference) throws IOException;
        @Override void close();
    }

    /** A source revision fence used when the draft is reviewed, committed, or accepted for execution. */
    public interface ImmutableSourceClosureStore {
        SourceLease acquire(SourceLeaseRequest request) throws IOException, StaleSourceException;
        void release(String leaseId, String ownerReference) throws IOException;
        void retire(RevisionIdentity revision) throws IOException;
    }

    public static final class StaleSourceException extends IOException {
        public final String code = "ATT-SOURCE-REVISION-STALE";
        public StaleSourceException() { super("The requested source revision is stale"); }
    }

    public enum ApprovalPhase { VALIDATE, REVIEW, PRECOMMIT, COMMIT_FENCE }
    public enum ApprovalState { APPROVED, REJECTED, STALE, UNAVAILABLE }

    /** Authorization and dependency freshness are evaluated again at COMMIT_FENCE. */
    public static final class ApprovalRequest {
        public final CandidateValidation candidate;
        public final String actor;
        public final boolean administratorAuthorized;
        public final ApprovalPhase phase;
        public final String acknowledgementBinding;

        public ApprovalRequest(CandidateValidation candidate, String actor, boolean administratorAuthorized,
                              ApprovalPhase phase, String acknowledgementBinding) {
            if (candidate == null || phase == null) throw new IllegalArgumentException("candidate and phase are required");
            this.candidate = candidate;
            this.actor = requiredToken(actor, "actor", 160);
            this.administratorAuthorized = administratorAuthorized;
            this.phase = phase;
            this.acknowledgementBinding = optionalDigest(acknowledgementBinding, "acknowledgementBinding");
        }
    }

    public static final class ApprovalDecision {
        public final ApprovalState state;
        public final String approvalRevision;
        public final String code;

        public ApprovalDecision(ApprovalState state, String approvalRevision, String code) {
            if (state == null) throw new IllegalArgumentException("approval state is required");
            this.state = state;
            this.approvalRevision = optionalDigest(approvalRevision, "approvalRevision");
            this.code = code == null ? null : safeCode(code);
            if (state == ApprovalState.APPROVED && this.approvalRevision == null)
                throw new IllegalArgumentException("an approved decision must be revision-bound");
        }
    }

    public interface PreCommitApprovalHook {
        ApprovalDecision evaluate(ApprovalRequest request) throws Exception;
    }

    public enum ActivationState { ACTIVE, PENDING, FAILED, UNAVAILABLE }

    /** Published and active revisions are separate because activation can fail after source commit. */
    public static final class ActivationStatus {
        public final String schemaVersion = "att-source-activation-status/v1";
        public final String scopeId;
        public final RevisionIdentity publishedRevision;
        public final RevisionIdentity activeRevision;
        public final ActivationState state;
        public final String failureCode;

        public ActivationStatus(String scopeId, RevisionIdentity publishedRevision,
                                RevisionIdentity activeRevision, ActivationState state, String failureCode) {
            this.scopeId = requiredToken(scopeId, "scopeId", 160);
            this.publishedRevision = publishedRevision;
            this.activeRevision = activeRevision;
            if (state == null) throw new IllegalArgumentException("activation state is required");
            this.state = state;
            this.failureCode = failureCode == null ? null : safeCode(failureCode);
        }
    }

    public static final class PublicationEvent {
        public final String schemaVersion = "att-source-publication-event/v1";
        public final Domain domain;
        public final String scopeId;
        public final String requestId;
        public final RevisionSet before;
        public final RevisionSet after;
        public final List<String> changedLogicalPaths;

        public PublicationEvent(Domain domain, String scopeId, String requestId, RevisionSet before,
                                RevisionSet after, List<String> changedLogicalPaths) {
            if (domain == null) throw new IllegalArgumentException("publication domain is required");
            this.domain = domain;
            this.scopeId = requiredToken(scopeId, "scopeId", 160);
            this.requestId = optionalToken(requestId, "requestId", 160);
            this.before = before == null ? RevisionSet.empty() : before;
            this.after = after == null ? RevisionSet.empty() : after;
            List<String> paths = new ArrayList<String>();
            if (changedLogicalPaths != null) for (String path : changedLogicalPaths) paths.add(requiredLogicalPath(path));
            this.changedLogicalPaths = Collections.unmodifiableList(paths);
        }
    }

    public static final class InvalidationScope {
        public final String packageId;
        public final String environment;
        public final List<String> resourceKinds;

        public InvalidationScope(String packageId, String environment, List<String> resourceKinds) {
            this.packageId = optionalToken(packageId, "packageId", 160);
            this.environment = optionalToken(environment, "environment", 64);
            List<String> values = new ArrayList<String>();
            if (resourceKinds != null) for (String value : resourceKinds) values.add(requiredToken(value, "resourceKind", 64));
            this.resourceKinds = Collections.unmodifiableList(values);
        }
    }

    public interface PublicationLifecycleHook {
        void committed(PublicationEvent event) throws Exception;
        void invalidate(InvalidationScope scope);
        ActivationStatus activationStatus(String scopeId);
    }

    public enum Capability { SAFE_INSPECTION, READ_AUTHORIZED_SOURCE, EDIT_PACKAGE_SOURCE, EDIT_CENTRAL_CONFIG }
    public enum SourceCachePolicy { NO_STORE, SAFE_INSPECTION_CACHE }

    /** Separate authority context for Package owners and central ADMIN clients. */
    public static final class AuthorityContext {
        public final String schemaVersion = "att-source-authority/v1";
        public final Domain domain;
        public final String packageId;
        public final Set<Capability> capabilities;
        public final SourceCachePolicy cachePolicy;
        public final boolean privilegedSource;

        public AuthorityContext(Domain domain, String packageId, Set<Capability> capabilities,
                                SourceCachePolicy cachePolicy, boolean privilegedSource) {
            if (domain == null || cachePolicy == null) throw new IllegalArgumentException("authority domain and cache policy are required");
            if (domain == Domain.PACKAGE_SOURCE && (packageId == null || packageId.trim().isEmpty()))
                throw new IllegalArgumentException("Package authority requires a Package ID");
            if (domain == Domain.CENTRAL_RUNTIME_CONFIG && packageId != null)
                throw new IllegalArgumentException("Central Config authority is not Package-scoped");
            Set<Capability> copy = capabilities == null ? Collections.<Capability>emptySet() : new LinkedHashSet<Capability>(capabilities);
            if (privilegedSource && (!copy.contains(Capability.READ_AUTHORIZED_SOURCE) || cachePolicy != SourceCachePolicy.NO_STORE))
                throw new IllegalArgumentException("Privileged source requires authorized read capability and no-store policy");
            if (domain == Domain.CENTRAL_RUNTIME_CONFIG && copy.contains(Capability.EDIT_PACKAGE_SOURCE)
                    || domain == Domain.PACKAGE_SOURCE && copy.contains(Capability.EDIT_CENTRAL_CONFIG))
                throw new IllegalArgumentException("Authority capability does not match its source domain");
            this.domain = domain;
            this.packageId = optionalToken(packageId, "packageId", 160);
            this.capabilities = Collections.unmodifiableSet(copy);
            this.cachePolicy = cachePolicy;
            this.privilegedSource = privilegedSource;
        }
    }

    public enum DraftState {
        DRAFT, VALIDATED, REVIEWED, PUBLISHED, CONFLICTED, REJECTED;
        @com.fasterxml.jackson.annotation.JsonValue
        public String wireValue() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** Wire DTO for Package and central source drafts; source values are represented only by ETags. */
    public static final class ChangeSetView {
        public final String schemaVersion = "att-source-change-set/v1";
        public final String changeSetId;
        public final Domain domain;
        public final String scopeId;
        public final DraftState state;
        public final RevisionSet revisions;
        public final String etag;
        public final boolean noStore;
        public final List<PackageCategory> categories;
        public final boolean manifestMetadataChanged;
        public final List<DependencyReference> inboundReferences;
        public final List<ValidationDiagnostic> diagnostics;
        public final String baseSourceRevision;
        public final String registryRevision;
        public final String manifestRevision;
        public final String configRevision;
        public final String schemaRevision;
        public final String expiresAt;
        public final List<Map<String, Object>> changedFiles;
        public final String validationRevision;
        public final String requestId;
        public final Boolean snapshotGenerated;
        public final String snapshotPath;
        public final String snapshotSha256;

        public ChangeSetView(String changeSetId, Domain domain, String scopeId, DraftState state,
                             RevisionSet revisions, String etag, boolean noStore,
                             List<PackageCategory> categories, boolean manifestMetadataChanged,
                             List<DependencyReference> inboundReferences,
                             List<ValidationDiagnostic> diagnostics) {
            this(changeSetId, domain, scopeId, state, revisions, etag, noStore, categories,
                    manifestMetadataChanged, inboundReferences, diagnostics, null, null, null, null,
                    null, null, Collections.<Map<String, Object>>emptyList(), null, null, null, null, null);
        }

        public ChangeSetView(String changeSetId, Domain domain, String scopeId, DraftState state,
                             RevisionSet revisions, String etag, boolean noStore,
                             List<PackageCategory> categories, boolean manifestMetadataChanged,
                             List<DependencyReference> inboundReferences,
                             List<ValidationDiagnostic> diagnostics,
                             String baseSourceRevision, String registryRevision, String manifestRevision,
                             String configRevision, String schemaRevision, String expiresAt,
                             List<Map<String, Object>> changedFiles, String validationRevision,
                             String requestId, Boolean snapshotGenerated, String snapshotPath,
                             String snapshotSha256) {
            this.changeSetId = requiredToken(changeSetId, "changeSetId", 160);
            if (domain == null || state == null) throw new IllegalArgumentException("change set domain and state are required");
            this.domain = domain;
            this.scopeId = requiredToken(scopeId, "scopeId", 160);
            this.state = state;
            this.revisions = revisions == null ? RevisionSet.empty() : revisions;
            this.etag = optionalDigest(etag, "etag");
            this.noStore = noStore;
            this.categories = immutable(categories);
            this.manifestMetadataChanged = manifestMetadataChanged;
            this.inboundReferences = immutable(inboundReferences);
            this.diagnostics = immutable(diagnostics);
            this.baseSourceRevision = optionalToken(baseSourceRevision, "baseSourceRevision", 160);
            this.registryRevision = optionalToken(registryRevision, "registryRevision", 160);
            this.manifestRevision = optionalToken(manifestRevision, "manifestRevision", 160);
            this.configRevision = optionalToken(configRevision, "configRevision", 160);
            this.schemaRevision = optionalToken(schemaRevision, "schemaRevision", 160);
            this.expiresAt = optionalToken(expiresAt, "expiresAt", 64);
            this.changedFiles = immutableObjectMaps(changedFiles);
            this.validationRevision = optionalToken(validationRevision, "validationRevision", 160);
            this.requestId = optionalToken(requestId, "requestId", 160);
            this.snapshotGenerated = snapshotGenerated;
            this.snapshotPath = safeLogicalPath(snapshotPath);
            this.snapshotSha256 = optionalDigest(snapshotSha256, "snapshotSha256");
        }
    }

    public static final class PrivilegedSourceView {
        public final String schemaVersion = "att-authorized-source/v1";
        public final String logicalPath;
        public final String source;
        public final String etag;
        public final boolean noStore = true;
        public PrivilegedSourceView(String logicalPath, String source, String etag) {
            this.logicalPath = requiredLogicalPath(logicalPath);
            if (source == null) throw new IllegalArgumentException("source is required");
            this.source = source;
            this.etag = requiredDigest(etag, "etag");
        }
    }

    public static final class SafeSourceView {
        public final String schemaVersion = "att-safe-source-inspection/v1";
        public final String logicalPath;
        public final String preview;
        public final boolean redacted;
        public final boolean truncated;
        public final boolean noStore = false;
        public SafeSourceView(String logicalPath, String preview, boolean redacted, boolean truncated) {
            this.logicalPath = requiredLogicalPath(logicalPath);
            this.preview = safeText(preview, 4096);
            this.redacted = redacted;
            this.truncated = truncated;
        }
    }

    public enum ApiFailureStatus { FORBIDDEN_403, CONFLICT_409, TOO_LARGE_413, INVALID_422 }

    public static final class ApiFailure {
        public final String schemaVersion = "att-source-api-failure/v1";
        public final ApiFailureStatus status;
        public final String code;
        public final String requestId;
        public final String message;
        public final RevisionSet currentRevisions;
        public ApiFailure(ApiFailureStatus status, String code, String requestId,
                          String message, RevisionSet currentRevisions) {
            if (status == null) throw new IllegalArgumentException("failure status is required");
            this.status = status;
            this.code = safeCode(code);
            this.requestId = optionalToken(requestId, "requestId", 160);
            this.message = safeText(message, 512);
            this.currentRevisions = currentRevisions == null ? RevisionSet.empty() : currentRevisions;
        }
    }

    public static final class Page<T> {
        public final String schemaVersion = "att-source-page/v1";
        public final List<T> items;
        public final String nextCursor;
        public final int totalEstimate;
        public final boolean truncated;
        public Page(List<T> items, String nextCursor, int totalEstimate, boolean truncated) {
            if (totalEstimate < 0) throw new IllegalArgumentException("totalEstimate cannot be negative");
            this.items = immutable(items);
            this.nextCursor = optionalToken(nextCursor, "nextCursor", 512);
            this.totalEstimate = totalEstimate;
            this.truncated = truncated;
        }
    }

    public enum Coverage { COMPLETE, PARTIAL }
    public enum Certainty { CONFIRMED_AFFECTED, POTENTIALLY_AFFECTED, UNAFFECTED }
    public enum ImpactDecision { COMPATIBLE, REQUIRES_REVIEW, BLOCKED }
    public enum ScheduleAction { UNCHANGED, REVALIDATE, PAUSE_AND_REAPPROVE }
    public enum ProviderState { AVAILABLE, UNAVAILABLE, STALE }

    /** Freshness binds an impact result to every independently changing decision input. */
    public static final class ImpactFreshnessToken {
        public final String schemaVersion = "att-config-impact-freshness/v1";
        public final String draftId;
        public final String draftFingerprint;
        public final RevisionIdentity baseConfig;
        public final RevisionIdentity candidateConfig;
        public final RevisionIdentity packageRegistry;
        public final List<RevisionIdentity> packageSources;
        public final List<RevisionIdentity> manifests;
        public final RevisionIdentity engineSchema;
        public final List<RevisionIdentity> scheduleSnapshots;

        public ImpactFreshnessToken(String draftId, String draftFingerprint,
                                    RevisionIdentity baseConfig, RevisionIdentity candidateConfig,
                                    RevisionIdentity packageRegistry, List<RevisionIdentity> packageSources,
                                    List<RevisionIdentity> manifests, RevisionIdentity engineSchema,
                                    List<RevisionIdentity> scheduleSnapshots) {
            this.draftId = requiredToken(draftId, "draftId", 160);
            this.draftFingerprint = requiredDigest(draftFingerprint, "draftFingerprint");
            this.baseConfig = requireKind(baseConfig, RevisionKind.CONFIG_SOURCE, "baseConfig");
            this.candidateConfig = requireKind(candidateConfig, RevisionKind.CONFIG_SOURCE, "candidateConfig");
            this.packageRegistry = requireKind(packageRegistry, RevisionKind.PACKAGE_REGISTRATION, "packageRegistry");
            this.packageSources = identities(packageSources, RevisionKind.PACKAGE_SOURCE, RevisionKind.BUSINESS_SOURCE);
            this.manifests = identities(manifests, RevisionKind.PACKAGE_MANIFEST);
            this.engineSchema = requireKind(engineSchema, RevisionKind.ENGINE_SCHEMA, "engineSchema");
            this.scheduleSnapshots = identities(scheduleSnapshots, RevisionKind.SCHEDULE_SNAPSHOT);
        }
    }

    public static final class AffectedPackage {
        public final String packageId;
        public final String environment;
        public final Certainty certainty;
        public final String safeReason;
        public final List<String> changedLogicalIds;
        public AffectedPackage(String packageId, String environment, Certainty certainty,
                               String safeReason, List<String> changedLogicalIds) {
            this.packageId = requiredToken(packageId, "packageId", 160);
            this.environment = requiredToken(environment, "environment", 64);
            if (certainty == null) throw new IllegalArgumentException("certainty is required");
            this.certainty = certainty;
            this.safeReason = safeText(safeReason, 512);
            this.changedLogicalIds = safeTokens(changedLogicalIds, 160);
        }
    }

    public static final class AffectedSchedule {
        public final String scheduleId;
        public final String packageId;
        public final String environment;
        public final ScheduleAction action;
        public final String safeReason;
        public AffectedSchedule(String scheduleId, String packageId, String environment,
                                ScheduleAction action, String safeReason) {
            this.scheduleId = requiredToken(scheduleId, "scheduleId", 160);
            this.packageId = optionalToken(packageId, "packageId", 160);
            this.environment = optionalToken(environment, "environment", 64);
            if (action == null) throw new IllegalArgumentException("schedule action is required");
            this.action = action;
            this.safeReason = safeText(safeReason, 512);
        }
    }

    /** Reports may be unavailable while #335 is not installed; callers must not infer approval. */
    public static final class ImpactReport {
        public final String schemaVersion = "att-config-impact-report/v1";
        public final ProviderState providerState;
        public final ImpactFreshnessToken freshness;
        public final Coverage coverage;
        public final ImpactDecision decision;
        public final List<AffectedPackage> affectedPackages;
        public final Page<AffectedSchedule> schedules;
        public final String unavailableCode;
        public final boolean acknowledgementRequired;

        public ImpactReport(ProviderState providerState, ImpactFreshnessToken freshness,
                            Coverage coverage, ImpactDecision decision,
                            List<AffectedPackage> affectedPackages, Page<AffectedSchedule> schedules,
                            String unavailableCode, boolean acknowledgementRequired) {
            if (providerState == null) throw new IllegalArgumentException("providerState is required");
            if (providerState == ProviderState.AVAILABLE && (freshness == null || coverage == null || decision == null))
                throw new IllegalArgumentException("available impact report requires freshness, coverage, and decision");
            if (providerState != ProviderState.AVAILABLE && decision == ImpactDecision.COMPATIBLE)
                throw new IllegalArgumentException("unavailable impact cannot be represented as compatible");
            this.providerState = providerState;
            this.freshness = freshness;
            this.coverage = coverage;
            this.decision = decision;
            this.affectedPackages = immutable(affectedPackages);
            this.schedules = schedules == null ? new Page<AffectedSchedule>(Collections.<AffectedSchedule>emptyList(), null, 0, false) : schedules;
            this.unavailableCode = unavailableCode == null ? null : safeCode(unavailableCode);
            this.acknowledgementRequired = acknowledgementRequired;
        }

        public static ImpactReport unavailable(String code) {
            return new ImpactReport(ProviderState.UNAVAILABLE, null, null, null,
                    Collections.<AffectedPackage>emptyList(), null, code, false);
        }
    }

    public static final class ImpactAcknowledgement {
        public final String freshnessFingerprint;
        public final String actor;
        public final String acknowledgementDigest;
        public ImpactAcknowledgement(String freshnessFingerprint, String actor, String acknowledgementDigest) {
            this.freshnessFingerprint = requiredDigest(freshnessFingerprint, "freshnessFingerprint");
            this.actor = requiredToken(actor, "actor", 160);
            this.acknowledgementDigest = requiredDigest(acknowledgementDigest, "acknowledgementDigest");
        }
    }

    public interface ImpactReportProvider {
        ImpactReport report(String draftId, String pageCursor) throws Exception;
        ApprovalDecision authorize(ApprovalRequest request, ImpactAcknowledgement acknowledgement) throws Exception;
    }

    private static RevisionIdentity requireKind(RevisionIdentity value, RevisionKind expected, String field) {
        if (value != null && value.kind != expected) throw new IllegalArgumentException(field + " must be " + expected);
        return value;
    }

    private static List<RevisionIdentity> identities(List<RevisionIdentity> values, RevisionKind expected) {
        List<RevisionIdentity> result = new ArrayList<RevisionIdentity>();
        if (values != null) for (RevisionIdentity value : values) result.add(requireKind(value, expected, "identity"));
        return Collections.unmodifiableList(result);
    }

    private static List<RevisionIdentity> identities(List<RevisionIdentity> values, RevisionKind expected,
                                                      RevisionKind legacyAlias) {
        List<RevisionIdentity> result = new ArrayList<RevisionIdentity>();
        if (values != null) for (RevisionIdentity value : values) {
            if (value == null) {
                result.add(null);
                continue;
            }
            if (value.kind != expected && value.kind != legacyAlias)
                throw new IllegalArgumentException("identity must be " + expected + " or " + legacyAlias);
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    private static Map<String, ResolutionOrigin> immutableMap(Map<String, ResolutionOrigin> values) {
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        Map<String, ResolutionOrigin> copy = new LinkedHashMap<String, ResolutionOrigin>();
        for (Map.Entry<String, ResolutionOrigin> entry : values.entrySet()) {
            String key = requiredToken(entry.getKey(), "origin path", 512);
            if (entry.getValue() == null) throw new IllegalArgumentException("origin value is required");
            copy.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> immutableStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        Map<String, String> copy = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : values.entrySet())
            copy.put(requiredToken(entry.getKey(), "semantic field", 512),
                    requiredDigest(entry.getValue(), "semantic fingerprint"));
        return Collections.unmodifiableMap(copy);
    }

    private static List<Map<String, Object>> immutableObjectMaps(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> value : values) {
            if (value == null) throw new IllegalArgumentException("changed file metadata is required");
            result.add(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(value)));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> safeTokens(List<String> values, int max) {
        List<String> result = new ArrayList<String>();
        if (values != null) for (String value : values) result.add(requiredToken(value, "value", max));
        return Collections.unmodifiableList(result);
    }

    private static String requiredToken(String value, String field, int max) {
        String checked = optionalToken(value, field, max);
        if (checked == null) throw new IllegalArgumentException(field + " is required");
        return checked;
    }

    private static String optionalToken(String value, String field, int max) {
        if (value == null) return null;
        String checked = value.trim();
        if (checked.isEmpty() || checked.length() > max || checked.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field + " is invalid");
        return checked;
    }

    private static String requiredDigest(String value, String field) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        return value;
    }

    private static String digest(byte[] value) {
        try {
            byte[] hashed = java.security.MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(hashed.length * 2);
            for (byte item : hashed) result.append(String.format("%02x", item & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String optionalDigest(String value, String field) {
        return value == null ? null : requiredDigest(value, field);
    }

    private static String safeCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("diagnostic code is invalid");
        return value;
    }

    private static String safeLogicalPath(String value) {
        if (value == null) return null;
        return requiredLogicalPath(value);
    }

    private static String requiredLogicalPath(String value) {
        String path = requiredToken(value, "logicalPath", 1024).replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.contains("//"))
            throw new IllegalArgumentException("logicalPath must be canonical and package-relative");
        for (String segment : path.split("/")) if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
            throw new IllegalArgumentException("logicalPath must be canonical and package-relative");
        return path;
    }

    private static String safeText(String value, int max) {
        if (value == null) return null;
        String safe = att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(SecretProtection.text(value));
        if (safe.length() > max) safe = safe.substring(0, max);
        return safe;
    }
}
