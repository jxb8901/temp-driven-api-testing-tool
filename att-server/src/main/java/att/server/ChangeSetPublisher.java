package att.server;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, domain-separated Draft -> Validate -> Review -> Publish workflow for source files. */
final class ChangeSetPublisher implements AutoCloseable {
    static final String PACKAGE_SOURCE = "PACKAGE_SOURCE";
    static final String CENTRAL_RUNTIME_CONFIG = "CENTRAL_RUNTIME_CONFIG";
    static final String CENTRAL_SCOPE_ID = "central-runtime-config";
    static final int MAX_OPERATIONS = 32;
    static final int MAX_FILES = 64;
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    static final int MAX_CHANGE_BYTES = 8 * 1024 * 1024;
    static final int MAX_DRAFT_BYTES = 64 * 1024 * 1024;
    static final int MAX_REVISION_FILES = 20000;
    static final long MAX_REVISION_BYTES = 512L * 1024L * 1024L;
    static final long MAX_REVISION_FILE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_WORKBOOK_ENTRIES = 512;
    private static final long MAX_WORKBOOK_EXPANDED_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_WORKBOOK_EXPANSION_RATIO = 200L;
    private static final Pattern FILE_EXPRESSION = Pattern.compile("&\\{([^{}\\r\\n]{1,512})\\}");
    private static final Set<String> TEXT_EXTENSIONS = set("yaml", "yml", "xml", "json", "csv", "tsv", "txt", "sql", "properties", "md", "xlsx", "xls");
    private static final Set<String> BINARY_EXTENSIONS = set("xlsx", "xls");

    interface Access {
        Scope resolve(String domain, String scopeId, String actor, boolean systemSettingsWrite) throws Exception;
    }
    interface CandidateValidator { ValidationResult validate(Candidate candidate) throws Exception; }
    interface ConfigPublishGate { GateDecision authorize(Candidate candidate, Map<String, Object> review) throws Exception; }
    interface Audit { void record(String actor, String action, String domain, String scopeId, String detail) throws Exception; }

    static final class Scope {
        final String domain;
        final String scopeId;
        final Path root;
        final Path centralRoot;
        final String registryRevision;
        final String schemaRevision;

        Scope(String domain, String scopeId, Path root, Path centralRoot, String registryRevision, String schemaRevision) {
            this.domain = domain;
            this.scopeId = scopeId;
            this.root = root;
            this.centralRoot = centralRoot;
            this.registryRevision = registryRevision;
            this.schemaRevision = schemaRevision;
        }
    }

    static final class OperationInput {
        final String operation;
        final String path;
        final String targetPath;
        final byte[] content;

        OperationInput(String operation, String path, String targetPath, byte[] content) {
            this.operation = operation;
            this.path = path;
            this.targetPath = targetPath;
            this.content = content == null ? null : content.clone();
        }

        static OperationInput create(String path, byte[] content) { return new OperationInput("create", path, null, content); }
        static OperationInput replace(String path, byte[] content) { return new OperationInput("replace", path, null, content); }
        static OperationInput delete(String path) { return new OperationInput("delete", path, null, null); }
        static OperationInput move(String path, String targetPath) { return new OperationInput("move", path, targetPath, null); }
    }

    static final class ValidationResult {
        final boolean valid;
        final String revision;
        final String code;
        private ValidationResult(boolean valid, String revision, String code) {
            this.valid = valid; this.revision = revision; this.code = code;
        }
        static ValidationResult accepted(String revision) { return new ValidationResult(true, revision, null); }
        static ValidationResult rejected(String code) { return new ValidationResult(false, null, safeCode(code)); }
    }

    static final class GateDecision {
        final boolean allowed;
        final String approvalRevision;
        final String code;
        private GateDecision(boolean allowed, String approvalRevision, String code) {
            this.allowed = allowed; this.approvalRevision = approvalRevision; this.code = code;
        }
        static GateDecision allow(String approvalRevision) { return new GateDecision(true, approvalRevision, null); }
        static GateDecision block(String code) { return new GateDecision(false, null, safeCode(code)); }
    }

    static final class Candidate {
        final Scope scope;
        final String actor;
        private final Map<String, byte[]> changes;
        private final Map<String, String> expectedHashes;
        private final List<Diff> diff;
        private final String sourceRevision;
        private final String registryRevision;
        private final String manifestRevision;
        private final String configRevision;
        private final String schemaRevision;
        private final RevisionState base;

        private Candidate(Draft draft) {
            this.scope = draft.scope;
            this.actor = draft.actor;
            this.changes = cloneChanges(draft.changes);
            this.expectedHashes = new LinkedHashMap<String, String>(draft.expectedHashes);
            this.diff = immutableDiff(draft.diff);
            this.sourceRevision = draft.base.sourceRevision;
            this.registryRevision = draft.base.registryRevision;
            this.manifestRevision = draft.base.manifestRevision;
            this.configRevision = draft.base.configRevision;
            this.schemaRevision = draft.base.schemaRevision;
            this.base = draft.base;
        }

        String domain() { return scope.domain; }
        String scopeId() { return scope.scopeId; }
        Path root() { return scope.root; }
        String sourceRevision() { return sourceRevision; }
        String registryRevision() { return registryRevision; }
        String manifestRevision() { return manifestRevision; }
        String configRevision() { return configRevision; }
        String schemaRevision() { return schemaRevision; }
        List<Map<String, Object>> changedFiles() { return diffView(diff); }
        Set<String> paths() { return Collections.unmodifiableSet(new HashSet<String>(changes.keySet())); }

        byte[] read(String logicalPath) throws IOException {
            String path = normalizePath(logicalPath);
            if (changes.containsKey(path)) {
                byte[] value = changes.get(path);
                return value == null ? null : value.clone();
            }
            Path source = checkedPath(scope.root, path);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Candidate source is unavailable");
            return readBounded(source);
        }
    }

    static final class Denied extends RuntimeException { Denied() { super("Source editing is not authorized"); } }
    static final class Missing extends RuntimeException { Missing() { super("Change Set was not found"); } }
    static final class Conflict extends RuntimeException { Conflict() { super("Source revision changed"); } }
    static final class Capacity extends RuntimeException { Capacity() { super("Change Set capacity is full"); } }
    static final class Busy extends RuntimeException { Busy() { super("Source readers are still using the published revision"); } }
    static final class ValidationRejected extends RuntimeException {
        final String code;
        ValidationRejected(String code) { super("Candidate source validation was rejected"); this.code = safeCode(code); }
    }

    private static final class RevisionState {
        final String sourceRevision, registryRevision, manifestRevision, configRevision, schemaRevision;
        final String packageTreeRevision, centralTreeRevision;
        final ScanIndex packageIndex, centralIndex;
        RevisionState(String sourceRevision, String registryRevision, String manifestRevision, String configRevision,
                      String schemaRevision, String packageTreeRevision, String centralTreeRevision,
                      ScanIndex packageIndex, ScanIndex centralIndex) {
            this.sourceRevision = sourceRevision; this.registryRevision = registryRevision;
            this.manifestRevision = manifestRevision; this.configRevision = configRevision;
            this.schemaRevision = schemaRevision; this.packageTreeRevision = packageTreeRevision;
            this.centralTreeRevision = centralTreeRevision; this.packageIndex = packageIndex; this.centralIndex = centralIndex;
        }
        boolean same(RevisionState other) {
            return other != null && equal(sourceRevision, other.sourceRevision) && equal(registryRevision, other.registryRevision)
                    && equal(manifestRevision, other.manifestRevision) && equal(configRevision, other.configRevision)
                    && equal(schemaRevision, other.schemaRevision);
        }
    }

    private static final class ScanIndex {
        final Map<String, Path> files = new LinkedHashMap<String, Path>();
        final Map<String, String> hashes = new LinkedHashMap<String, String>();
        final Map<String, String> caseNames = new LinkedHashMap<String, String>();
        long bytes;
    }

    private static final class Diff {
        final String operation, path, targetPath, oldHash, newHash;
        final long oldBytes, newBytes;
        final boolean automatic;
        int referenceUpdates;
        Diff(String operation, String path, String targetPath, String oldHash, String newHash,
             long oldBytes, long newBytes, boolean automatic) {
            this.operation = operation; this.path = path; this.targetPath = targetPath; this.oldHash = oldHash;
            this.newHash = newHash; this.oldBytes = oldBytes; this.newBytes = newBytes; this.automatic = automatic;
        }
    }

    private static final class Draft {
        final String id, actor;
        final Scope scope;
        final RevisionState base;
        final Instant expires;
        final Map<String, byte[]> changes;
        final Map<String, String> expectedHashes;
        final List<Diff> diff;
        String validationRevision;
        boolean reviewed;
        boolean publishing;
        Draft(String id, String actor, Scope scope, RevisionState base, Instant expires, Map<String, byte[]> changes,
              Map<String, String> expectedHashes, List<Diff> diff) {
            this.id = id; this.actor = actor; this.scope = scope; this.base = base; this.expires = expires;
            this.changes = cloneChanges(changes); this.expectedHashes = new LinkedHashMap<String, String>(expectedHashes);
            this.diff = immutableDiff(diff);
        }
    }

    private final Clock clock;
    private final int maxDrafts, maxDraftsPerActor, ttlSeconds;
    private final Access access;
    private final CandidateValidator validator;
    private final ConfigPublishGate configGate;
    private final Audit audit;
    private final SourceFileTransaction transaction;
    private final Lock sourceReadLock;
    private final Map<String, Draft> drafts = new LinkedHashMap<String, Draft>();
    private long stagedBytes;

    ChangeSetPublisher(Clock clock, int maxDrafts, int maxDraftsPerActor, int ttlSeconds, Access access,
                       CandidateValidator validator, ConfigPublishGate configGate, Audit audit,
                       SourceFileTransaction transaction) {
        this.clock = clock; this.maxDrafts = maxDrafts; this.maxDraftsPerActor = maxDraftsPerActor;
        this.ttlSeconds = ttlSeconds; this.access = access; this.validator = validator;
        this.configGate = configGate; this.audit = audit; this.transaction = transaction;
        this.sourceReadLock = transaction.readLock();
    }

    static CandidateValidator unavailableValidator() {
        return candidate -> ValidationResult.rejected("ATT-CHANGESET-VALIDATOR-UNAVAILABLE");
    }

    static ConfigPublishGate unavailableConfigGate() {
        return (candidate, review) -> GateDecision.block("ATT-CONFIG-PRECOMMIT-UNAVAILABLE");
    }

    synchronized Map<String, Object> stage(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                            String expectedSourceRevision, String expectedRegistryRevision,
                                            List<OperationInput> operations) throws Exception {
        return stage(domain, scopeId, actor, systemSettingsWrite, expectedSourceRevision, expectedRegistryRevision, operations, null);
    }

    synchronized Map<String, Object> stage(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                            String expectedSourceRevision, String expectedRegistryRevision,
                                            List<OperationInput> operations, String requestId) throws Exception {
        sourceReadLock.lock();
        try {
        Scope scope = authorizedScope(domain, scopeId, actor, systemSettingsWrite);
        expire();
        if (operations == null || operations.isEmpty() || operations.size() > MAX_OPERATIONS)
            throw new IllegalArgumentException("A Change Set must contain between 1 and 32 file operations");
        RevisionState base = state(scope);
        if (expectedSourceRevision != null && !base.sourceRevision.equals(requireDigest(expectedSourceRevision))) throw new Conflict();
        if (expectedRegistryRevision != null && !equal(expectedRegistryRevision, base.registryRevision)) throw new Conflict();
        if (drafts.size() >= maxDrafts || actorDraftCount(actor) >= maxDraftsPerActor) throw new Capacity();

        Normalized normalized = normalizeOperations(scope, base, operations);
        if (stagedBytes + normalized.byteCount > MAX_DRAFT_BYTES) throw new Capacity();
        String id = "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        Draft draft = new Draft(id, actor, scope, base, clock.instant().plusSeconds(ttlSeconds),
                normalized.changes, normalized.expectedHashes, normalized.diff);
        audit.record(actor, "SOURCE_CHANGE_SET_STAGE", domain, scopeId,
                auditDetail(requestId, "changeSetId=" + id + " before=" + base.sourceRevision
                        + " outcome=STAGED paths=" + pathList(draft.diff)));
        drafts.put(id, draft); stagedBytes += normalized.byteCount;
        return view(draft, "draft");
        } finally { sourceReadLock.unlock(); }
    }

    synchronized Map<String, Object> validate(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                               String id) throws Exception {
        return validate(domain, scopeId, actor, systemSettingsWrite, id, null);
    }

    synchronized Map<String, Object> validate(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                               String id, String requestId) throws Exception {
        sourceReadLock.lock();
        try {
        Draft draft = draft(domain, scopeId, actor, systemSettingsWrite, id);
        requireCurrent(draft, systemSettingsWrite);
        Candidate candidate = new Candidate(draft);
        validateFileReferences(candidate, draft.diff);
        try {
            ValidationResult result;
            try { result = validator.validate(candidate); }
            catch (Exception rejected) { throw new ValidationRejected("ATT-CHANGESET-VALIDATION-FAILED"); }
            if (result == null || !result.valid) throw new ValidationRejected(result == null ? "ATT-CHANGESET-VALIDATION-UNAVAILABLE" : result.code);
            String revision = result.revision == null ? validationRevision(candidate) : requireDigest(result.revision);
            audit.record(actor, "SOURCE_CHANGE_SET_VALIDATE", domain, scopeId,
                    auditDetail(requestId, "changeSetId=" + id + " revision=" + revision + " outcome=VALIDATED paths=" + pathList(draft.diff)));
            draft.validationRevision = revision;
            draft.reviewed = false;
            return view(draft, "validated");
        } catch (ValidationRejected rejected) {
            safeAudit(actor, "SOURCE_CHANGE_SET_VALIDATE", domain, scopeId,
                    auditDetail(requestId, "changeSetId=" + id + " outcome=REJECTED code=" + rejected.code));
            throw rejected;
        } catch (Conflict conflict) {
            safeAudit(actor, "SOURCE_CHANGE_SET_VALIDATE", domain, scopeId,
                    auditDetail(requestId, "changeSetId=" + id + " outcome=CONFLICT"));
            throw conflict;
        }
        } finally { sourceReadLock.unlock(); }
    }

    synchronized Map<String, Object> review(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                             String id) throws Exception {
        return review(domain, scopeId, actor, systemSettingsWrite, id, null);
    }

    synchronized Map<String, Object> review(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                             String id, String requestId) throws Exception {
        sourceReadLock.lock();
        try {
        Draft draft = draft(domain, scopeId, actor, systemSettingsWrite, id);
        requireCurrent(draft, systemSettingsWrite);
        if (draft.validationRevision == null) throw new ValidationRejected("ATT-CHANGESET-VALIDATION-REQUIRED");
        validateFileReferences(new Candidate(draft), draft.diff);
        ValidationResult checked;
        try { checked = validator.validate(new Candidate(draft)); }
        catch (Exception rejected) { throw new ValidationRejected("ATT-CHANGESET-VALIDATION-FAILED"); }
        if (checked == null || !checked.valid) throw new ValidationRejected(checked == null
                ? "ATT-CHANGESET-VALIDATION-UNAVAILABLE" : checked.code);
        String checkedRevision = checked.revision == null ? validationRevision(new Candidate(draft)) : requireDigest(checked.revision);
        if (!draft.validationRevision.equals(checkedRevision)) throw new Conflict();
        audit.record(actor, "SOURCE_CHANGE_SET_REVIEW", domain, scopeId,
                auditDetail(requestId, "changeSetId=" + id + " revision=" + draft.base.sourceRevision
                        + " outcome=REVIEWED paths=" + pathList(draft.diff)));
        draft.reviewed = true;
        return view(draft, "reviewed");
        } finally { sourceReadLock.unlock(); }
    }

    Map<String, Object> publish(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                String id) throws Exception {
        return publish(domain, scopeId, actor, systemSettingsWrite, id, null);
    }

    Map<String, Object> publish(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                String id, String requestId) throws Exception {
        final Draft draft;
        synchronized (this) {
            draft = draft(domain, scopeId, actor, systemSettingsWrite, id);
            if (!draft.reviewed || draft.validationRevision == null) throw new ValidationRejected("ATT-CHANGESET-REVIEW-REQUIRED");
            if (draft.publishing) throw new Conflict();
            draft.publishing = true;
        }
        boolean committed = false;
        try {
            String approvalRevision = null;
            String afterRevision;
            Map<String, SourceFileTransaction.Write> writes;
            sourceReadLock.lock();
            try {
                requireCurrent(draft, systemSettingsWrite);
                Candidate candidate = new Candidate(draft);
                validateFileReferences(candidate, draft.diff);
                ValidationResult validated;
                try { validated = validator.validate(candidate); }
                catch (Exception failure) { throw new ValidationRejected("ATT-CHANGESET-VALIDATION-FAILED"); }
                if (validated == null || !validated.valid) throw new ValidationRejected(validated == null
                        ? "ATT-CHANGESET-VALIDATION-UNAVAILABLE" : validated.code);
                String newValidationRevision = validated.revision == null ? validationRevision(candidate) : requireDigest(validated.revision);
                if (!draft.validationRevision.equals(newValidationRevision)) throw new Conflict();

                if (CENTRAL_RUNTIME_CONFIG.equals(domain)) {
                    GateDecision decision;
                    try { decision = configGate.authorize(candidate, view(draft, "reviewed")); }
                    catch (Exception failure) { throw new ValidationRejected("ATT-CONFIG-PRECOMMIT-BLOCKED"); }
                    if (decision == null || !decision.allowed)
                        throw new ValidationRejected(decision == null ? "ATT-CONFIG-PRECOMMIT-UNAVAILABLE" : decision.code);
                    approvalRevision = decision.approvalRevision == null ? null : requireDigest(decision.approvalRevision);
                }

                afterRevision = publishedRevision(candidate);
                writes = new LinkedHashMap<String, SourceFileTransaction.Write>();
                for (Map.Entry<String, byte[]> entry : draft.changes.entrySet())
                    writes.put(entry.getKey(), new SourceFileTransaction.Write(entry.getKey(), entry.getValue(), draft.expectedHashes.get(entry.getKey())));
                audit.record(actor, "SOURCE_CHANGE_SET_PUBLISH_INTENT", domain, scopeId,
                        auditDetail(requestId, "changeSetId=" + id + " before=" + draft.base.sourceRevision
                                + " after=" + afterRevision + " outcome=INTENT gate="
                                + (approvalRevision == null ? "none" : approvalRevision)
                                + " paths=" + pathList(draft.diff)));
            } finally { sourceReadLock.unlock(); }
            try {
                transaction.commit(domain, scopeId, draft.scope.root, actor, draft.base.sourceRevision, afterRevision, writes, () -> {
                    Scope currentScope = authorizedScope(domain, scopeId, actor, systemSettingsWrite);
                    if (!draft.base.same(state(currentScope))) throw new Conflict();
                });
            } catch (SourceFileTransaction.RevisionConflict changed) {
                throw new Conflict();
            } catch (SourceFileTransaction.Busy busy) {
                throw new Busy();
            }
            committed = true;
            boolean auditConfirmed = true;
            try { audit.record(actor, "SOURCE_CHANGE_SET_PUBLISH", domain, scopeId,
                    auditDetail(requestId, "changeSetId=" + id + " before=" + draft.base.sourceRevision
                            + " after=" + afterRevision + " outcome=COMMITTED paths=" + pathList(draft.diff))); }
            catch (Exception ignored) { auditConfirmed = false; }
            synchronized (this) { removeDraft(draft); }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("published", Boolean.TRUE); result.put("domain", domain); result.put("scopeId", scopeId);
            result.put("changeSetId", id); result.put("beforeRevision", draft.base.sourceRevision);
            result.put("revision", afterRevision); result.put("validationRevision", draft.validationRevision);
            result.put("auditConfirmed", Boolean.valueOf(auditConfirmed));
            if (approvalRevision != null) result.put("configGateRevision", approvalRevision);
            result.put("changedFiles", diffView(draft.diff));
            return result;
        } catch (Exception failure) {
            String outcome = failure instanceof Conflict ? "CONFLICT"
                    : failure instanceof ValidationRejected ? "REJECTED" : "FAILED";
            String code = failure instanceof ValidationRejected ? ((ValidationRejected) failure).code : outcome;
            safeAudit(actor, "SOURCE_CHANGE_SET_PUBLISH", domain, scopeId,
                    auditDetail(requestId, "changeSetId=" + id + " outcome=" + outcome
                            + " code=" + code + " paths=" + pathList(draft.diff)));
            throw failure;
        } finally {
            if (!committed) synchronized (this) { draft.publishing = false; }
        }
    }

    synchronized Map<String, Object> discard(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                              String id) throws Exception {
        return discard(domain, scopeId, actor, systemSettingsWrite, id, null);
    }

    synchronized Map<String, Object> discard(String domain, String scopeId, String actor, boolean systemSettingsWrite,
                                              String id, String requestId) throws Exception {
        Draft draft = draft(domain, scopeId, actor, systemSettingsWrite, id);
        if (draft.publishing) throw new Conflict();
        audit.record(actor, "SOURCE_CHANGE_SET_DISCARD", domain, scopeId,
                auditDetail(requestId, "changeSetId=" + id + " before=" + draft.base.sourceRevision
                        + " outcome=DISCARDED paths=" + pathList(draft.diff)));
        removeDraft(draft);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("discarded", Boolean.TRUE); result.put("changeSetId", id);
        return result;
    }

    synchronized void invalidateDrafts() { drafts.clear(); stagedBytes = 0L; }
    @Override public synchronized void close() { invalidateDrafts(); }

    private Normalized normalizeOperations(Scope scope, RevisionState base, List<OperationInput> inputs) throws Exception {
        Map<String, byte[]> changes = new LinkedHashMap<String, byte[]>();
        Map<String, String> expected = new LinkedHashMap<String, String>();
        List<Diff> diffs = new ArrayList<Diff>();
        List<Move> moves = new ArrayList<Move>();
        Set<String> reserved = new HashSet<String>();
        long totalBytes = 0L;
        for (OperationInput input : inputs) {
            if (input == null) throw new IllegalArgumentException("A file operation is invalid");
            String operation = input.operation == null ? "" : input.operation.toLowerCase(Locale.ROOT);
            String path = normalizePath(input.path);
            requireAllowed(scope.domain, path);
            String target = input.targetPath == null ? null : normalizePath(input.targetPath);
            if ("create".equals(operation) || "replace".equals(operation)) {
                if (target != null || input.content == null) throw new IllegalArgumentException("A create or replace operation requires file content");
                if (input.content.length > MAX_FILE_BYTES) throw new IllegalArgumentException("A source file exceeds its size limit");
                validateContentEncoding(path, input.content);
                totalBytes += input.content.length;
            } else if ("delete".equals(operation)) {
                if (target != null || input.content != null) throw new IllegalArgumentException("A delete operation accepts only a path");
            } else if ("move".equals(operation)) {
                if (target == null || input.content != null) throw new IllegalArgumentException("A move operation requires a target path");
                requireAllowed(scope.domain, target);
                if (path.equals(target) || path.equalsIgnoreCase(target)) throw new IllegalArgumentException("A move target must have a different path");
            } else throw new IllegalArgumentException("Unsupported file operation");
            if (!reserved.add(path.toLowerCase(Locale.ROOT)) || (target != null && !reserved.add(target.toLowerCase(Locale.ROOT))))
                throw new IllegalArgumentException("A Change Set cannot modify the same path more than once");

            Path source = checkedPath(scope.root, path);
            String oldHash = PACKAGE_SOURCE.equals(scope.domain)
                    ? base.packageIndex.hashes.get(path) : base.centralIndex.hashes.get(path);
            boolean exists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
            if (exists && (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)))
                throw new IllegalArgumentException("A source path is unsafe");
            if (exists && Files.size(source) > SourceFileTransaction.MAX_FILE_BYTES)
                throw new IllegalArgumentException("A source file exceeds its publication backup limit");
            if ("create".equals(operation)) {
                if (exists || oldHash != null) throw new Conflict();
                checkCaseCollision(scope, path);
                changes.put(path, input.content.clone()); expected.put(path, null);
                diffs.add(new Diff("create", path, null, null, hash(input.content), 0L, input.content.length, false));
            } else if ("replace".equals(operation)) {
                if (!exists || oldHash == null) throw new Conflict();
                changes.put(path, input.content.clone()); expected.put(path, oldHash);
                diffs.add(new Diff("replace", path, null, oldHash, hash(input.content), Files.size(source), input.content.length, false));
            } else if ("delete".equals(operation)) {
                if (!exists || oldHash == null) throw new Conflict();
                changes.put(path, null); expected.put(path, oldHash);
                diffs.add(new Diff("delete", path, null, oldHash, null, Files.size(source), 0L, false));
            } else {
                if (!exists || oldHash == null) throw new Conflict();
                Path destination = checkedPath(scope.root, target);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new Conflict();
                checkCaseCollision(scope, target);
                byte[] bytes = readBounded(source);
                validateContentEncoding(target, bytes);
                changes.put(path, null); changes.put(target, bytes);
                expected.put(path, oldHash); expected.put(target, null);
                diffs.add(new Diff("move", path, target, oldHash, hash(bytes), Files.size(source), bytes.length, false));
                moves.add(new Move(path, target));
                totalBytes += bytes.length;
            }
            if (totalBytes > MAX_CHANGE_BYTES) throw new IllegalArgumentException("A Change Set exceeds its total source limit");
        }
        applyMovedFileReferences(scope, base, changes, expected, diffs, moves);
        if (changes.size() > MAX_FILES) throw new IllegalArgumentException("A Change Set exceeds its file-count limit");
        long backupBytes = 0L;
        for (Diff diff : diffs) {
            backupBytes += diff.oldBytes;
            if (backupBytes > SourceFileTransaction.MAX_BACKUP_BYTES)
                throw new IllegalArgumentException("A Change Set exceeds its publication backup limit");
        }
        long staged = 0L;
        for (byte[] bytes : changes.values()) if (bytes != null) staged += bytes.length;
        if (staged > MAX_CHANGE_BYTES) throw new IllegalArgumentException("A Change Set exceeds its total source limit");
        return new Normalized(changes, expected, diffs, staged);
    }

    private void applyMovedFileReferences(Scope scope, RevisionState base, Map<String, byte[]> changes,
                                          Map<String, String> expected, List<Diff> diffs, List<Move> moves) throws Exception {
        if (!PACKAGE_SOURCE.equals(scope.domain) || moves.isEmpty()) return;
        for (Move move : moves) {
            if (isDescriptor(move.source)) continue;
            List<String> descriptors = descriptorPaths(scope.root, changes);
            for (String descriptor : descriptors) {
                byte[] current = candidateBytes(scope.root, changes, descriptor);
                if (current == null) continue;
                String text = decodeUtf8(current);
                Matcher matcher = FILE_EXPRESSION.matcher(text);
                StringBuffer rewritten = new StringBuffer();
                int count = 0;
                while (matcher.find()) {
                    String locator = matcher.group(1);
                    String resolved = resolveLocator(scope.root, descriptor, locator);
                    if (move.source.equals(resolved)) {
                        matcher.appendReplacement(rewritten, Matcher.quoteReplacement("&{" + move.target + "}"));
                        count++;
                    } else matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(0)));
                }
                matcher.appendTail(rewritten);
                if (count == 0) continue;
                byte[] rewrittenBytes = rewritten.toString().getBytes(StandardCharsets.UTF_8);
                if (rewrittenBytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("A referring descriptor exceeds its size limit");
                if (!changes.containsKey(descriptor)) {
                    String oldHash = base.packageIndex.hashes.get(descriptor);
                    if (oldHash == null) throw new Conflict();
                    expected.put(descriptor, oldHash);
                    Diff diff = new Diff("replace", descriptor, null, oldHash, hash(rewrittenBytes), current.length, rewrittenBytes.length, true);
                    diff.referenceUpdates = count; diffs.add(diff);
                } else {
                    for (Diff diff : diffs) if (descriptor.equals(diff.path)) diff.referenceUpdates += count;
                }
                changes.put(descriptor, rewrittenBytes);
            }
        }
    }

    private void validateFileReferences(Candidate candidate, List<Diff> diffs) throws Exception {
        if (!PACKAGE_SOURCE.equals(candidate.domain())) return;
        Set<String> affected = new HashSet<String>();
        for (Diff diff : diffs) {
            if ("move".equals(diff.operation)) { affected.add(diff.path); affected.add(diff.targetPath); }
            else if ("delete".equals(diff.operation)) affected.add(diff.path);
        }
        if (affected.isEmpty()) return;
        for (String descriptor : descriptorPaths(candidate.root(), candidate.changes)) {
            byte[] bytes = candidate.read(descriptor);
            if (bytes == null) continue;
            String content = decodeUtf8(bytes);
            Matcher matcher = FILE_EXPRESSION.matcher(content);
            while (matcher.find()) {
                String locator = matcher.group(1);
                String resolved = resolveLocator(candidate.root(), descriptor, locator);
                if (resolved.isEmpty()) throw new ValidationRejected("ATT-CHANGESET-FILE-REFERENCE-UNRESOLVED");
                if (!affected.contains(resolved)) continue;
                byte[] target = candidate.read(resolved);
                if (target == null) throw new ValidationRejected("ATT-CHANGESET-DANGLING-FILE-REFERENCE");
            }
        }
    }

    private Scope authorizedScope(String domain, String scopeId, String actor, boolean systemSettingsWrite) throws Exception {
        if (!PACKAGE_SOURCE.equals(domain) && !CENTRAL_RUNTIME_CONFIG.equals(domain)) throw new Denied();
        if (CENTRAL_RUNTIME_CONFIG.equals(domain) && !CENTRAL_SCOPE_ID.equals(scopeId)) throw new Denied();
        if (actor == null || actor.trim().isEmpty()) throw new Denied();
        Scope scope;
        try { scope = access.resolve(domain, scopeId, actor, systemSettingsWrite); }
        catch (Denied denied) { throw denied; }
        catch (Exception failure) { throw new Denied(); }
        if (scope == null || !domain.equals(scope.domain) || !scopeId.equals(scope.scopeId)) throw new Denied();
        try { canonicalRoot(scope.root); } catch (Exception invalid) { throw new Conflict(); }
        if (CENTRAL_RUNTIME_CONFIG.equals(domain) && !systemSettingsWrite) throw new Denied();
        return scope;
    }

    private synchronized Draft draft(String domain, String scopeId, String actor, boolean systemSettingsWrite, String id) throws Exception {
        Scope current = authorizedScope(domain, scopeId, actor, systemSettingsWrite);
        expire();
        Draft draft = drafts.get(id);
        if (draft == null || !draft.scope.domain.equals(domain) || !draft.scope.scopeId.equals(scopeId) || !draft.actor.equals(actor))
            throw new Missing();
        if (!draft.scope.root.equals(canonicalRoot(current.root))) throw new Conflict();
        return draft;
    }

    private void requireCurrent(Draft draft, boolean systemSettingsWrite) throws Exception {
        Scope current = authorizedScope(draft.scope.domain, draft.scope.scopeId, draft.actor, systemSettingsWrite);
        if (!draft.scope.root.equals(canonicalRoot(current.root)) || !draft.base.same(state(current))) throw new Conflict();
    }

    private RevisionState state(Scope scope) throws Exception {
        Path root = canonicalRoot(scope.root);
        ScanIndex primary = scan(root, scope.domain);
        String packageTree = PACKAGE_SOURCE.equals(scope.domain) ? digestIndex(primary) : null;
        String centralTree;
        ScanIndex centralIndex;
        if (PACKAGE_SOURCE.equals(scope.domain)) {
            Path centralRoot = canonicalRoot(scope.centralRoot);
            centralIndex = scan(centralRoot, CENTRAL_RUNTIME_CONFIG);
            centralTree = digestIndex(centralIndex);
        } else {
            centralIndex = primary;
            centralTree = digestIndex(primary);
        }
        String manifest = PACKAGE_SOURCE.equals(scope.domain) ? primary.hashes.getOrDefault("package.yaml", hash(new byte[0])) : null;
        String config = centralIndex.hashes.getOrDefault("config/config.yaml", hash(new byte[0]));
        String schema = scope.schemaRevision == null ? "unavailable" : requireDigest(scope.schemaRevision);
        String registry = PACKAGE_SOURCE.equals(scope.domain) ? requireDigest(scope.registryRevision) : null;
        String source = hash(join(scope.domain, scope.scopeId, packageTree, centralTree, registry, manifest, config, schema));
        return new RevisionState(source, registry, manifest, config, schema, packageTree, centralTree,
                PACKAGE_SOURCE.equals(scope.domain) ? primary : null, centralIndex);
    }

    private static ScanIndex scan(Path root, String domain) throws Exception {
        final ScanIndex index = new ScanIndex();
        final Path canonical = canonicalRoot(root);
        final int[] visited = new int[] { 0 };
        Files.walkFileTree(canonical, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                if (++visited[0] > MAX_REVISION_FILES * 3) throw new IOException("Source tree exceeds its entry limit");
                if (Files.isSymbolicLink(directory) || attrs.isSymbolicLink()) {
                    if (directory.equals(canonical)) throw new IOException("Source root contains an unsafe symlink");
                    String name = logical(canonical.relativize(directory));
                    if (managedPath(domain, name)) throw new IOException("Source tree contains an unsafe symlink");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String name = directory.equals(canonical) ? "" : logical(canonical.relativize(directory));
                if (!name.isEmpty() && excludedDirectory(domain, name)) return FileVisitResult.SKIP_SUBTREE;
                if (!name.isEmpty()) {
                    String key = name.toLowerCase(Locale.ROOT);
                    String prior = index.caseNames.put(key, name);
                    if (prior != null && !prior.equals(name)) throw new IOException("Source tree contains a case collision");
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (++visited[0] > MAX_REVISION_FILES * 3) throw new IOException("Source tree exceeds its entry limit");
                String name = logical(canonical.relativize(file));
                if (!managedPath(domain, name)) return FileVisitResult.CONTINUE;
                if (Files.isSymbolicLink(file) || attrs.isSymbolicLink() || !attrs.isRegularFile())
                    throw new IOException("Source tree contains an unsafe file");
                if (!authorableExtension(name, domain)) return FileVisitResult.CONTINUE;
                if (index.files.size() >= MAX_REVISION_FILES) throw new IOException("Source tree exceeds its file limit");
                long size = attrs.size();
                if (size > MAX_REVISION_FILE_BYTES || index.bytes + size > MAX_REVISION_BYTES)
                    throw new IOException("Source tree exceeds its byte limit");
                String key = name.toLowerCase(Locale.ROOT);
                String prior = index.caseNames.put(key, name);
                if (prior != null && !prior.equals(name)) throw new IOException("Source tree contains a case collision");
                index.files.put(name, file);
                index.hashes.put(name, hashFile(file));
                index.bytes += size;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { throw failure; }
        });
        return index;
    }

    private static boolean managedPath(String domain, String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        if (CENTRAL_RUNTIME_CONFIG.equals(domain)) return normalized.equals("config") || normalized.startsWith("config/");
        return !excludedDirectory(PACKAGE_SOURCE, path) && !"att-server.yaml".equals(normalized);
    }

    private static boolean excludedDirectory(String domain, String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (CENTRAL_RUNTIME_CONFIG.equals(domain) && !lower.equals("config") && !lower.startsWith("config/")) return true;
        for (String part : lower.split("/")) {
            if (set("secrets", "credentials", "private", "auth", "roles", "security", "bootstrap",
                    "schemas", "output", "jobs", "cache", ".git", ".att", "target", "logs", "reports",
                    "results", "artifacts", "work", "server").contains(part)) return true;
            if (!CENTRAL_RUNTIME_CONFIG.equals(domain) && "config".equals(part)) return true;
        }
        return false;
    }

    private static boolean authorableExtension(String path, String domain) {
        String normalized = path.toLowerCase(Locale.ROOT);
        if ("package.yaml".equals(normalized)) return PACKAGE_SOURCE.equals(domain);
        int dot = normalized.lastIndexOf('.');
        if (dot < 0 || dot == normalized.length() - 1) return false;
        String extension = normalized.substring(dot + 1);
        if (!TEXT_EXTENSIONS.contains(extension)) return false;
        if (CENTRAL_RUNTIME_CONFIG.equals(domain)) return centralPath(normalized);
        return true;
    }

    private static boolean centralPath(String path) {
        if ("config/config.yaml".equals(path)) return true;
        return path.startsWith("config/environments/") || path.startsWith("config/tools/")
                || path.startsWith("config/toolgroups/") || path.startsWith("config/tool-groups/")
                || path.startsWith("config/dbhelpers/") || path.startsWith("config/mqhelpers/")
                || path.startsWith("config/httphelpers/") || path.startsWith("config/sshhelpers/");
    }

    private static void requireAllowed(String domain, String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("/att-server.yaml") || "att-server.yaml".equals(normalized)
                || normalized.startsWith("config/") && !CENTRAL_RUNTIME_CONFIG.equals(domain)
                || normalized.startsWith("schemas/") || normalized.startsWith("../")
                || excludedDirectory(domain, path)) throw new IllegalArgumentException("Source path is outside the authoring allowlist");
        int dot = normalized.lastIndexOf('.');
        if (dot < 0 || !TEXT_EXTENSIONS.contains(normalized.substring(dot + 1)))
            throw new IllegalArgumentException("Source file type is outside the authoring allowlist");
        if (CENTRAL_RUNTIME_CONFIG.equals(domain) && !centralPath(normalized))
            throw new IllegalArgumentException("Central Runtime Config path is outside the authoring allowlist");
        if (protectedSourcePath(normalized)) throw new IllegalArgumentException("Protected source paths cannot be authored online");
        if (PACKAGE_SOURCE.equals(domain) && normalized.startsWith("config/"))
            throw new IllegalArgumentException("Package Config files cannot be edited through Package source APIs");
    }

    private static boolean protectedSourcePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".jks")
                || lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".keystore")) return true;
        for (String segment : lower.split("/")) if (set("secrets", "credentials", "private", "auth", "roles",
                "security", "bootstrap").contains(segment)) return true;
        return false;
    }

    private void checkCaseCollision(Scope scope, String path) throws Exception {
        RevisionState current = state(scope);
        ScanIndex index = PACKAGE_SOURCE.equals(scope.domain) ? current.packageIndex : current.centralIndex;
        String[] segments = path.split("/");
        String prefix = "";
        for (String segment : segments) {
            prefix = prefix.isEmpty() ? segment : prefix + "/" + segment;
            String prior = index.caseNames.get(prefix.toLowerCase(Locale.ROOT));
            if (prior != null && !prior.equals(prefix)) throw new IllegalArgumentException("Source path has a case collision");
        }
    }

    private static List<String> descriptorPaths(Path root, Map<String, byte[]> changes) throws Exception {
        Set<String> result = new java.util.TreeSet<String>();
        for (String name : scan(root, PACKAGE_SOURCE).files.keySet()) if (isDescriptor(name)) result.add(name);
        for (Map.Entry<String, byte[]> change : changes.entrySet()) {
            if (isDescriptor(change.getKey())) {
                if (change.getValue() == null) result.remove(change.getKey()); else result.add(change.getKey());
            }
        }
        return new ArrayList<String>(result);
    }

    private static boolean isDescriptor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("templates/") || lower.startsWith("flows/"))) return false;
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static String resolveLocator(Path root, String descriptor, String locator) throws Exception {
        if (locator == null || locator.isEmpty() || locator.trim().isEmpty() || !locator.equals(locator.trim())
                || locator.indexOf("${") >= 0 || locator.indexOf("#{") >= 0 || locator.contains("&{")) return "";
        if (locator.indexOf('\\') >= 0 || locator.startsWith("/") || locator.matches("^[A-Za-z]:.*")) return "";
        Path selected;
        try {
            Path parsed = Paths.get(locator);
            Path base = locator.startsWith("./") || locator.startsWith("../")
                    ? root.resolve(parent(descriptor).replace('/', java.io.File.separatorChar)) : root;
            selected = base.resolve(parsed).normalize();
        } catch (RuntimeException invalid) { return ""; }
        if (!selected.startsWith(root) || selected.equals(root)) return "";
        Path current = root;
        for (Path part : root.relativize(selected)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return "";
        }
        return logical(root.relativize(selected));
    }

    private static byte[] candidateBytes(Path root, Map<String, byte[]> changes, String path) throws IOException {
        if (changes.containsKey(path)) return changes.get(path);
        Path file = checkedPath(root, path);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Candidate source is unavailable");
        return readBounded(file);
    }

    private static byte[] readBounded(Path path) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_FILE_BYTES)
                    throw new IllegalArgumentException("Source file exceeds its size limit");
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static void validateContentEncoding(String path, byte[] bytes) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        if (BINARY_EXTENSIONS.contains(extension)) {
            if ("xlsx".equals(extension)) validateWorkbookArchive(bytes);
            return;
        }
        decodeUtf8(bytes);
    }

    private static void validateWorkbookArchive(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Workbook archive is invalid");
        int entries = 0;
        long expanded = 0L;
        boolean contentTypes = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_WORKBOOK_ENTRIES) throw new IllegalArgumentException("Workbook archive exceeds its entry limit");
                String name = entry.getName();
                if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                        || name.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("Workbook archive contains an unsafe entry");
                for (String segment : name.split("/")) if ("..".equals(segment) || ".".equals(segment))
                    throw new IllegalArgumentException("Workbook archive contains an unsafe entry");
                if ("[Content_Types].xml".equals(name)) contentTypes = true;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > MAX_WORKBOOK_EXPANDED_BYTES || expanded > (long) bytes.length * MAX_WORKBOOK_EXPANSION_RATIO)
                        throw new IllegalArgumentException("Workbook archive exceeds its expanded size limit");
                }
                zip.closeEntry();
            }
        } catch (IOException invalid) { throw new IllegalArgumentException("Workbook archive is invalid or exceeds its limits"); }
        if (entries == 0 || !contentTypes) throw new IllegalArgumentException("Workbook archive is invalid");
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Source text must be valid UTF-8"); }
    }

    private static void validateContentEncodingForMove(String path, byte[] bytes) {
        if (bytes == null) return;
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        if (!BINARY_EXTENSIONS.contains(extension)) decodeUtf8(bytes);
    }

    private void removeDraft(Draft draft) {
        if (drafts.remove(draft.id) != null) {
            for (byte[] bytes : draft.changes.values()) if (bytes != null) stagedBytes = Math.max(0L, stagedBytes - bytes.length);
        }
    }

    private long actorDraftCount(String actor) {
        long count = 0L;
        for (Draft draft : drafts.values()) if (draft.actor.equals(actor)) count++;
        return count;
    }

    private void expire() {
        Instant now = clock.instant();
        List<Draft> expired = new ArrayList<Draft>();
        for (Draft draft : drafts.values()) if (!draft.expires.isAfter(now)) expired.add(draft);
        for (Draft draft : expired) removeDraft(draft);
    }

    private Map<String, Object> view(Draft draft, String state) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("changeSetId", draft.id); result.put("domain", draft.scope.domain); result.put("scopeId", draft.scope.scopeId);
        result.put("state", draft.reviewed ? "reviewed" : draft.validationRevision == null ? state : "validated");
        result.put("baseSourceRevision", draft.base.sourceRevision);
        if (draft.base.registryRevision != null) result.put("registryRevision", draft.base.registryRevision);
        if (draft.base.manifestRevision != null) result.put("manifestRevision", draft.base.manifestRevision);
        result.put("configRevision", draft.base.configRevision); result.put("schemaRevision", draft.base.schemaRevision);
        result.put("expiresAt", draft.expires.toString()); result.put("changedFiles", diffView(draft.diff));
        if (draft.validationRevision != null) result.put("validationRevision", draft.validationRevision);
        return result;
    }

    private static List<Map<String, Object>> diffView(List<Diff> diffs) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Diff diff : diffs) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("operation", diff.operation); item.put("path", diff.path);
            if (diff.targetPath != null) item.put("targetPath", diff.targetPath);
            item.put("oldHash", diff.oldHash); item.put("newHash", diff.newHash);
            item.put("oldBytes", Long.valueOf(diff.oldBytes)); item.put("newBytes", Long.valueOf(diff.newBytes));
            item.put("automatic", Boolean.valueOf(diff.automatic));
            if (diff.referenceUpdates > 0) item.put("updatedFileReferences", Integer.valueOf(diff.referenceUpdates));
            result.add(item);
        }
        return result;
    }

    private static List<Diff> immutableDiff(List<Diff> source) {
        List<Diff> copy = new ArrayList<Diff>();
        for (Diff diff : source) {
            Diff cloned = new Diff(diff.operation, diff.path, diff.targetPath, diff.oldHash, diff.newHash,
                    diff.oldBytes, diff.newBytes, diff.automatic);
            cloned.referenceUpdates = diff.referenceUpdates; copy.add(cloned);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, byte[]> cloneChanges(Map<String, byte[]> source) {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : source.entrySet())
            result.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().clone());
        return result;
    }

    private String validationRevision(Candidate candidate) throws Exception {
        List<String> values = new ArrayList<String>();
        values.add(candidate.sourceRevision()); values.add(candidate.schemaRevision());
        for (String path : new java.util.TreeSet<String>(candidate.changes.keySet())) {
            byte[] bytes = candidate.changes.get(path);
            values.add(path); values.add(bytes == null ? "deleted" : hash(bytes));
        }
        return hash(join(values.toArray(new String[values.size()])));
    }

    private String publishedRevision(Candidate candidate) throws Exception {
        Map<String, String> packageHashes = PACKAGE_SOURCE.equals(candidate.domain())
                ? new LinkedHashMap<String, String>(candidate.base.packageIndex.hashes) : null;
        Map<String, String> centralHashes = new LinkedHashMap<String, String>(candidate.base.centralIndex.hashes);
        Map<String, String> targetHashes = PACKAGE_SOURCE.equals(candidate.domain()) ? packageHashes : centralHashes;
        for (Map.Entry<String, byte[]> entry : candidate.changes.entrySet()) {
            if (entry.getValue() == null) targetHashes.remove(entry.getKey());
            else targetHashes.put(entry.getKey(), hash(entry.getValue()));
        }
        String packageTree = PACKAGE_SOURCE.equals(candidate.domain()) ? digestHashes(packageHashes) : null;
        String centralTree = PACKAGE_SOURCE.equals(candidate.domain())
                ? candidate.base.centralTreeRevision : digestHashes(centralHashes);
        String manifest = PACKAGE_SOURCE.equals(candidate.domain())
                ? packageHashes.getOrDefault("package.yaml", hash(new byte[0])) : null;
        String config = PACKAGE_SOURCE.equals(candidate.domain()) ? candidate.configRevision
                : centralHashes.getOrDefault("config/config.yaml", hash(new byte[0]));
        return hash(join(candidate.domain(), candidate.scopeId(), packageTree, centralTree,
                candidate.registryRevision(), manifest, config, candidate.schemaRevision()));
    }

    private static Path canonicalRoot(Path root) throws IOException {
        if (root == null) throw new IOException("Source root is unavailable");
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Source root is unavailable");
        Path canonical = absolute.toRealPath();
        if (!absolute.equals(canonical)) throw new IOException("Source root is unsafe");
        return canonical;
    }

    private static Path checkedPath(Path root, String logical) throws IOException {
        String path = normalizePath(logical);
        Path target = root.resolve(path.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Source path is unsafe");
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Source path contains an unsafe symlink");
        }
        return target;
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0 || raw.startsWith("/")
                || raw.length() > 512 || raw.matches("^[A-Za-z]:.*") || !raw.equals(raw.trim()))
            throw new IllegalArgumentException("Source path is unsafe");
        String[] parts = raw.split("/", -1);
        for (String part : parts) if (part.isEmpty() || part.length() > 128 || ".".equals(part) || "..".equals(part)
                || part.indexOf(':') >= 0 || hasControl(part)) throw new IllegalArgumentException("Source path is unsafe");
        return String.join("/", parts);
    }

    private static String safeCode(String code) {
        if (code == null || !code.matches("ATT-[A-Z0-9-]{3,80}")) return "ATT-CHANGESET-VALIDATION-FAILED";
        return code;
    }
    private static String auditDetail(String requestId, String detail) {
        String safeRequestId = requestId != null && requestId.matches("[A-Za-z0-9._-]{1,80}") ? requestId : "unavailable";
        return "requestId=" + safeRequestId + " " + detail;
    }
    private static String pathList(List<Diff> diffs) {
        StringBuilder result = new StringBuilder();
        for (Diff diff : diffs) {
            if (result.length() > 0) result.append(',');
            result.append(diff.operation).append(':').append(diff.path);
            if (diff.targetPath != null) result.append("->").append(diff.targetPath);
        }
        return result.toString();
    }
    private void safeAudit(String actor, String action, String domain, String scopeId, String detail) {
        try { audit.record(actor, action, domain, scopeId, detail); } catch (Exception ignored) { }
    }
    private static String requireDigest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("A valid source revision is required");
        return value;
    }
    private static String digestIndex(ScanIndex index) throws Exception {
        return digestHashes(index.hashes);
    }
    private static String digestHashes(Map<String, String> hashes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String path : new java.util.TreeSet<String>(hashes.keySet())) {
            digest.update(path.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            digest.update(hashes.get(path).getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        }
        return hex(digest.digest());
    }
    private static String hash(String value) throws Exception { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] value) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String hashFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 255));
        return result.toString();
    }
    private static String join(String... values) { return String.join("\n", values); }
    private static boolean equal(Object left, Object right) { return left == null ? right == null : left.equals(right); }
    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }
    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private static String logical(Path path) { return path.toString().replace('\\', '/'); }
    private static Set<String> set(String... values) {
        Set<String> result = new HashSet<String>(); Collections.addAll(result, values); return Collections.unmodifiableSet(result);
    }

    private static final class Normalized {
        final Map<String, byte[]> changes;
        final Map<String, String> expectedHashes;
        final List<Diff> diff;
        final long byteCount;
        Normalized(Map<String, byte[]> changes, Map<String, String> expectedHashes, List<Diff> diff, long byteCount) {
            this.changes = changes; this.expectedHashes = expectedHashes; this.diff = diff; this.byteCount = byteCount;
        }
    }
    private static final class Move {
        final String source, target;
        Move(String source, String target) { this.source = source; this.target = target; }
    }
}
