package att.server;

import att.Version;
import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.PackageConfig;
import att.server.api.source.SourceManagementContracts;
import att.server.api.source.SourceManagementContracts.AffectedPackage;
import att.server.api.source.SourceManagementContracts.AffectedSchedule;
import att.server.api.source.SourceManagementContracts.ApprovalDecision;
import att.server.api.source.SourceManagementContracts.ApprovalPhase;
import att.server.api.source.SourceManagementContracts.ApprovalRequest;
import att.server.api.source.SourceManagementContracts.CandidateValidation;
import att.server.api.source.SourceManagementContracts.Certainty;
import att.server.api.source.SourceManagementContracts.Coverage;
import att.server.api.source.SourceManagementContracts.DependencyKind;
import att.server.api.source.SourceManagementContracts.Domain;
import att.server.api.source.SourceManagementContracts.EffectiveResolution;
import att.server.api.source.SourceManagementContracts.ImpactAcknowledgement;
import att.server.api.source.SourceManagementContracts.ImpactDecision;
import att.server.api.source.SourceManagementContracts.ImpactFreshnessToken;
import att.server.api.source.SourceManagementContracts.ImpactReport;
import att.server.api.source.SourceManagementContracts.ImpactReportProvider;
import att.server.api.source.SourceManagementContracts.Page;
import att.server.api.source.SourceManagementContracts.ProviderState;
import att.server.api.source.SourceManagementContracts.ReferenceCertainty;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import att.server.api.source.SourceManagementContracts.ScheduleAction;
import att.server.api.source.SourceManagementContracts.ValidationState;
import att.validation.SchemaFiles;
import att.validation.SourceCandidateValidator;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded, value-free impact analysis for central Runtime Config Change Sets. */
final class ConfigImpactAnalyzer implements ImpactReportProvider {
    static final int MAX_PACKAGES = 128;
    static final int MAX_ENVIRONMENTS_PER_PACKAGE = 16;
    static final int MAX_COMBINATIONS = 512;
    static final int MAX_SCHEDULES = 2000;
    static final int MAX_AFFECTED_PACKAGES = 256;
    static final int SCHEDULE_PAGE_SIZE = 100;
    static final long MAX_ANALYSIS_MILLIS = 5000L;
    static final long MAX_TREE_BYTES = 512L * 1024L * 1024L;
    static final int MAX_TREE_FILES = 20000;
    private static final String CENTRAL_SCOPE = ChangeSetPublisher.CENTRAL_SCOPE_ID;
    private static final String UNAVAILABLE = "ATT-CONFIG-IMPACT-UNAVAILABLE";
    private static final String ANALYSIS_LIMIT = "ATT-CONFIG-IMPACT-ANALYSIS-LIMIT";
    private static final String IMPACT_STALE = "ATT-CONFIG-IMPACT-STALE";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadPoolExecutor ANALYSIS_EXECUTOR = new ThreadPoolExecutor(0, 2, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadFactory() {
        private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "att-config-impact-" + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }, new ThreadPoolExecutor.AbortPolicy());

    interface Inventory {
        PackageRegistry.Snapshot registry() throws Exception;
        List<LoadSchedule> schedules() throws Exception;
    }

    interface ImpactEngine {
        CandidateValidation validateCentralConfig(Path configFile, Path runtimeRoot,
                                                  RevisionSet revisions, String validationFingerprint) throws Exception;
        CandidateValidation validatePackage(Path packageRoot, Path configFile, Path runtimeRoot, String packageId,
                                            RevisionSet revisions, String validationFingerprint) throws Exception;
        EffectiveResolution resolveEffective(String packageId, String environment, Path packageRoot,
                                            Path configFile, Path runtimeRoot, RevisionSet provenance) throws Exception;
    }

    private final Inventory inventory;
    private final PackageConfigurationPolicy packagePolicy;
    private final String defaultSchedulePolicy;
    private final ImpactEngine engine;
    private final ConfigActivationCoordinator activation;
    private final long maxAnalysisMillis;
    private final Map<String, ChangeSetPublisher.Candidate> candidates = new ConcurrentHashMap<>();
    private final Map<String, ChangeSetPublisher.Candidate> candidatesByFingerprint = new ConcurrentHashMap<>();
    private final Map<String, String> issuedReports = new ConcurrentHashMap<>();

    ConfigImpactAnalyzer(Inventory inventory, PackageConfigurationPolicy packagePolicy,
                         SourceCandidateValidator engine, ConfigActivationCoordinator activation) {
        this(inventory, packagePolicy, "strict", engine, activation);
    }

    ConfigImpactAnalyzer(Inventory inventory, PackageConfigurationPolicy packagePolicy,
                         String defaultSchedulePolicy, SourceCandidateValidator engine,
                         ConfigActivationCoordinator activation) {
        this(inventory, packagePolicy, defaultSchedulePolicy, adapt(engine), activation, MAX_ANALYSIS_MILLIS);
    }

    ConfigImpactAnalyzer(Inventory inventory, PackageConfigurationPolicy packagePolicy,
                         String defaultSchedulePolicy, ImpactEngine engine,
                         ConfigActivationCoordinator activation, long maxAnalysisMillis) {
        this.inventory = java.util.Objects.requireNonNull(inventory);
        this.packagePolicy = java.util.Objects.requireNonNull(packagePolicy);
        if (!Set.of("strict", "pause", "ignore").contains(defaultSchedulePolicy))
            throw new IllegalArgumentException("Default Schedule policy is invalid");
        this.defaultSchedulePolicy = defaultSchedulePolicy;
        this.engine = java.util.Objects.requireNonNull(engine);
        this.activation = java.util.Objects.requireNonNull(activation);
        if (maxAnalysisMillis < 1L || maxAnalysisMillis > MAX_ANALYSIS_MILLIS)
            throw new IllegalArgumentException("Impact analysis time limit is invalid");
        this.maxAnalysisMillis = maxAnalysisMillis;
    }

    private static ImpactEngine adapt(SourceCandidateValidator validator) {
        java.util.Objects.requireNonNull(validator);
        return new ImpactEngine() {
            @Override public CandidateValidation validateCentralConfig(Path configFile, Path runtimeRoot,
                                                                       RevisionSet revisions, String validationFingerprint) throws Exception {
                return validator.validateCentralConfig(configFile, runtimeRoot, revisions, validationFingerprint);
            }
            @Override public CandidateValidation validatePackage(Path packageRoot, Path configFile, Path runtimeRoot,
                                                                 String packageId, RevisionSet revisions,
                                                                 String validationFingerprint) throws Exception {
                return validator.validatePackage(packageRoot, configFile, runtimeRoot, packageId,
                        revisions, validationFingerprint);
            }
            @Override public EffectiveResolution resolveEffective(String packageId, String environment,
                                                                  Path packageRoot, Path configFile, Path runtimeRoot,
                                                                  RevisionSet provenance) throws Exception {
                return validator.resolveEffective(packageId, environment, packageRoot, configFile, runtimeRoot, provenance);
            }
        };
    }

    /** The publisher calls this only after it has re-authorized and revision-fenced the draft. */
    ImpactReport report(ChangeSetPublisher.Candidate candidate, String pageCursor) {
        if (candidate == null || !ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(candidate.domain()))
            return ImpactReport.unavailable(UNAVAILABLE);
        cursorOffset(pageCursor);
        candidates.put(candidate.changeSetId(), candidate);
        try {
            ImpactReport report = bounded(() -> analyze(candidate, pageCursor));
            if (report.freshness != null) {
                candidatesByFingerprint.put(report.freshness.draftFingerprint, candidate);
                if (report.providerState == ProviderState.AVAILABLE)
                    issuedReports.put(candidate.changeSetId(), freshnessFingerprint(report.freshness));
            }
            boundCaches();
            return report;
        } catch (AnalysisLimitException limited) {
            return ImpactReport.unavailable(ANALYSIS_LIMIT);
        } catch (StaleInputException stale) {
            return staleReport();
        } catch (Exception unavailable) {
            return ImpactReport.unavailable(UNAVAILABLE);
        }
    }

    @Override public ImpactReport report(String draftId, String pageCursor) {
        ChangeSetPublisher.Candidate candidate = draftId == null ? null : candidates.get(draftId);
        if (candidate == null) return ImpactReport.unavailable(UNAVAILABLE);
        return report(candidate, pageCursor);
    }

    /** Recomputes at each publisher fence, then routes the decision through the shared provider contract. */
    ApprovalDecision authorizeCandidate(ChangeSetPublisher.Candidate candidate,
                                        Map<String, Object> review) {
        try {
            return bounded(() -> authorizeCandidateBounded(candidate, review));
        } catch (AnalysisLimitException limited) {
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.UNAVAILABLE, null, ANALYSIS_LIMIT);
        } catch (StaleInputException stale) {
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null, IMPACT_STALE);
        } catch (Exception unavailable) {
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.UNAVAILABLE, null, UNAVAILABLE);
        }
    }

    private ApprovalDecision authorizeCandidateBounded(ChangeSetPublisher.Candidate candidate,
                                                       Map<String, Object> review) throws Exception {
        ImpactAcknowledgement acknowledgement = acknowledgement(review);
        String fingerprint = candidateFingerprint(candidate,
                System.nanoTime() + Duration.ofMillis(maxAnalysisMillis).toNanos());
        candidates.put(candidate.changeSetId(), candidate);
        candidatesByFingerprint.put(fingerprint, candidate);
        CandidateValidation validation = candidateValidation(candidate, fingerprint);
        ApprovalRequest request = new ApprovalRequest(validation, candidate.actor, true,
                ApprovalPhase.COMMIT_FENCE, acknowledgement == null ? null : acknowledgement.freshnessFingerprint);
        return authorizeBounded(request, acknowledgement);
    }

    @Override public ApprovalDecision authorize(ApprovalRequest request, ImpactAcknowledgement acknowledgement) {
        try {
            return bounded(() -> authorizeBounded(request, acknowledgement));
        } catch (AnalysisLimitException limited) {
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.UNAVAILABLE, null, ANALYSIS_LIMIT);
        } catch (StaleInputException stale) {
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null, IMPACT_STALE);
        } catch (Exception unavailable) {
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.UNAVAILABLE, null, UNAVAILABLE);
        }
    }

    private ApprovalDecision authorizeBounded(ApprovalRequest request,
                                               ImpactAcknowledgement acknowledgement) throws Exception {
        if (request == null || request.candidate == null)
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.UNAVAILABLE, null, UNAVAILABLE);
        CandidateValidation candidate = request.candidate;
        ChangeSetPublisher.Candidate sourceCandidate = candidatesByFingerprint.get(candidate.validationFingerprint);
        if (sourceCandidate == null)
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.UNAVAILABLE, null, UNAVAILABLE);
        String issuedFingerprint = issuedReports.get(sourceCandidate.changeSetId());
        if (issuedFingerprint == null)
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null, IMPACT_STALE);
        if (acknowledgement == null
                || !java.util.Objects.equals(request.acknowledgementBinding, acknowledgement.freshnessFingerprint)
                || !issuedFingerprint.equals(acknowledgement.freshnessFingerprint)
                || !request.actor.equals(acknowledgement.actor)
                || !acknowledgementDigest(issuedFingerprint, request.actor)
                .equals(acknowledgement.acknowledgementDigest))
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null, IMPACT_STALE);
        long preflightDeadline = System.nanoTime() + Duration.ofMillis(maxAnalysisMillis).toNanos();
        InventorySnapshot current = snapshot(sourceCandidate, candidate.validationFingerprint, preflightDeadline, null);
        if (!issuedFingerprint.equals(freshnessFingerprint(current.freshness)))
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null, IMPACT_STALE);
        ImpactReport report;
        try {
            report = analyze(sourceCandidate, null);
        } catch (StaleInputException stale) {
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null, IMPACT_STALE);
        }
        if (report == null || report.freshness == null || report.providerState != ProviderState.AVAILABLE)
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.UNAVAILABLE, null, UNAVAILABLE);
        if (!candidate.validationFingerprint.equals(report.freshness.draftFingerprint))
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null, "ATT-CONFIG-IMPACT-STALE");
        if (!request.administratorAuthorized)
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.REJECTED, null,
                    "ATT-CONFIG-IMPACT-ADMIN-REQUIRED");
        if (candidate.domain != Domain.CENTRAL_RUNTIME_CONFIG || candidate.state != ValidationState.VALID
                || !candidate.executionEligible)
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.REJECTED, null,
                    "ATT-CONFIG-IMPACT-CANDIDATE-INVALID");
        String freshnessFingerprint = freshnessFingerprint(report.freshness);
        if (!freshnessFingerprint.equals(issuedReports.get(sourceCandidate.changeSetId()))
                || acknowledgement == null
                || !java.util.Objects.equals(request.acknowledgementBinding, acknowledgement.freshnessFingerprint))
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null,
                    "ATT-CONFIG-IMPACT-STALE");
        if (!request.actor.equals(acknowledgement.actor)
                || !freshnessFingerprint.equals(acknowledgement.freshnessFingerprint)
                || !acknowledgementDigest(freshnessFingerprint, request.actor)
                .equals(acknowledgement.acknowledgementDigest)) {
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.STALE, null,
                    "ATT-CONFIG-IMPACT-STALE");
        }
        if (!activation.canActivateOnline())
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.REJECTED, null,
                    "ATT-CONFIG-RUNTIME-GUARD-UNAVAILABLE");
        if (report.decision == ImpactDecision.BLOCKED)
            return new ApprovalDecision(SourceManagementContracts.ApprovalState.REJECTED, null,
                    report.unavailableCode == null ? "ATT-CONFIG-IMPACT-BLOCKED" : report.unavailableCode);
        return new ApprovalDecision(SourceManagementContracts.ApprovalState.APPROVED,
                digest(freshnessFingerprint + "\n" + request.actor + "\n" + report.decision), null);
    }

    static String freshnessFingerprint(ImpactFreshnessToken token) {
        StringBuilder value = new StringBuilder("att-config-impact-ack/v1\n")
                .append(token.draftId).append('\n').append(token.draftFingerprint).append('\n')
                .append(identity(token.baseConfig)).append('\n').append(identity(token.candidateConfig)).append('\n')
                .append(identity(token.packageRegistry)).append('\n').append(identity(token.engineSchema)).append('\n');
        appendIdentities(value, token.packageSources);
        appendIdentities(value, token.manifests);
        appendIdentities(value, token.scheduleSnapshots);
        return digest(value.toString());
    }

    static String acknowledgementDigest(String freshnessFingerprint, String actor) {
        return digest("att-config-impact-acknowledgement/v1\n" + freshnessFingerprint + "\n" + actor);
    }

    private <T> T bounded(Callable<T> operation) throws Exception {
        Future<T> future;
        try {
            FutureTask<T> task = new FutureTask<>(operation);
            ANALYSIS_EXECUTOR.execute(task);
            future = task;
        } catch (RejectedExecutionException saturated) {
            throw new AnalysisLimitException();
        }
        try {
            return future.get(maxAnalysisMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new AnalysisLimitException();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AnalysisLimitException();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof AnalysisLimitException limited) throw limited;
            if (cause instanceof StaleInputException stale) throw stale;
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IOException("Config impact analysis failed");
        }
    }

    private static ImpactReport staleReport() {
        return new ImpactReport(ProviderState.STALE, null, null, null, Collections.emptyList(), null,
                IMPACT_STALE, false);
    }

    private ImpactReport analyze(ChangeSetPublisher.Candidate candidate, String pageCursor) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(maxAnalysisMillis).toNanos();
        Path workspace = privateDirectory();
        try {
            Path baseRoot = Files.createDirectory(workspace.resolve("base"));
            Path candidateRoot = Files.createDirectory(workspace.resolve("candidate"));
            Path packageSnapshots = Files.createDirectory(workspace.resolve("packages"));
            materialize(candidate.centralFiles(), candidate::readCentral, baseRoot, deadline);
            materialize(candidate.files(), candidate::read, candidateRoot, deadline);
            String draftFingerprint = candidateFingerprint(candidate, deadline);
            InventorySnapshot snapshot = snapshot(candidate, draftFingerprint, deadline, packageSnapshots);
            ImpactFreshnessToken freshness = snapshot.freshness;
            try {

            CandidateValidation central = engine.validateCentralConfig(candidateRoot.resolve("config/config.yaml"),
                    candidateRoot, revisions(freshness), draftFingerprint);
            if (!central.executionEligible) {
                return report(freshness, Coverage.PARTIAL, ImpactDecision.BLOCKED,
                        Collections.emptyList(), Collections.emptyList(), "ATT-CONFIG-IMPACT-CANDIDATE-INVALID", false,
                        pageCursor, snapshot.scheduleTruncated);
            }

            CandidateValidation baseCentral = engine.validateCentralConfig(baseRoot.resolve("config/config.yaml"),
                    baseRoot, revisions(freshness), digest(draftFingerprint + "\nbase"));
            if (!baseCentral.executionEligible) {
                return report(freshness, Coverage.PARTIAL, ImpactDecision.BLOCKED,
                        Collections.emptyList(), Collections.emptyList(), "ATT-CONFIG-IMPACT-BASE-UNAVAILABLE", false,
                        pageCursor, snapshot.scheduleTruncated);
            }

            boolean partial = snapshot.partial || !activation.canActivateOnline();
            boolean blocked = !activation.canActivateOnline();
            String blockedCode = blocked ? "ATT-CONFIG-RUNTIME-GUARD-UNAVAILABLE" : null;
            List<AffectedPackage> affected = new ArrayList<>();
            List<AffectedSchedule> affectedSchedules = new ArrayList<>();
            int combinations = 0;
            boolean affectedLimitReached = false;
            for (PackageEntry pkg : snapshot.packages) {
                checkTime(deadline);
                EffectiveDefaults defaults = effectiveDefaults(baseRoot, candidateRoot, pkg.root);
                Set<String> implicitScheduleEnvironments = defaults.environments();
                if (!packagePolicy.allowedEnvironments.isEmpty() && defaults.candidate != null
                        && !packagePolicy.allowedEnvironments.contains(defaults.candidate.toLowerCase(Locale.ROOT))) {
                    partial = true;
                    blocked = true;
                    blockedCode = "ATT-CONFIG-IMPACT-ENVIRONMENT-POLICY";
                    if (!addAffected(affected, pkg.id, defaults.candidate, Certainty.POTENTIALLY_AFFECTED,
                            "The candidate's effective default Environment is outside Server policy.", List.of()))
                        affectedLimitReached = true;
                    addScheduleRows(affectedSchedules, snapshot.schedules, pkg.id, defaults.candidate,
                            implicitScheduleEnvironments, Certainty.POTENTIALLY_AFFECTED,
                            "The candidate's effective default Environment is outside Server policy.");
                }
                Set<String> environments = relevantEnvironments(baseRoot, candidateRoot, pkg.root,
                        snapshot.schedules, pkg.id, implicitScheduleEnvironments);
                if (environments.size() > MAX_ENVIRONMENTS_PER_PACKAGE) {
                    partial = true;
                    blocked = true;
                    blockedCode = "ATT-CONFIG-IMPACT-ENVIRONMENT-LIMIT";
                    environments = new LinkedHashSet<>(new ArrayList<>(environments)
                            .subList(0, MAX_ENVIRONMENTS_PER_PACKAGE));
                }
                CandidateValidation basePackage = null;
                CandidateValidation candidatePackage = null;
                try {
                    basePackage = engine.validatePackage(pkg.root, baseRoot.resolve("config/config.yaml"), baseRoot,
                            pkg.id, revisions(freshness), digest(draftFingerprint + "\nbase\n" + pkg.id));
                    candidatePackage = engine.validatePackage(pkg.root, candidateRoot.resolve("config/config.yaml"), candidateRoot,
                            pkg.id, revisions(freshness), digest(draftFingerprint + "\nnew\n" + pkg.id));
                } catch (Exception unresolved) {
                    partial = true;
                    blocked = true;
                    blockedCode = "ATT-CONFIG-IMPACT-PACKAGE-UNRESOLVED";
                }
                boolean packageUnknown = incomplete(basePackage) || incomplete(candidatePackage);
                boolean packageInvalid = invalid(basePackage) || invalid(candidatePackage);
                if (packageUnknown) partial = true;
                if (packageInvalid) {
                    blocked = true;
                    blockedCode = "ATT-CONFIG-IMPACT-PACKAGE-INVALID";
                }
                ReferenceInventory references = references(basePackage, candidatePackage);
                Set<String> dynamicReasons = unknownReferences(basePackage, candidatePackage);

                List<EnvironmentComparison> comparisons = new ArrayList<>();
                for (String environment : environments) {
                    if (!sameEnvironment(defaults.base, defaults.candidate)
                            && environment.equalsIgnoreCase(defaults.candidate)) {
                        // Compare the actual implicit binding as well as explicit selection of this profile.
                        comparisons.add(new EnvironmentComparison(environment, defaults.base, defaults.candidate));
                        comparisons.add(new EnvironmentComparison(environment, environment, environment));
                    } else comparisons.add(new EnvironmentComparison(environment, environment, environment));
                }
                for (EnvironmentComparison comparison : comparisons) {
                    String environment = comparison.displayEnvironment;
                    checkTime(deadline);
                    if (++combinations > MAX_COMBINATIONS) {
                        partial = true;
                        blocked = true;
                        blockedCode = "ATT-CONFIG-IMPACT-COMBINATION-LIMIT";
                        break;
                    }
                    EffectiveModel before;
                    EffectiveModel after;
                    try {
                        before = resolve(baseRoot, pkg.root, pkg.id, comparison.baseEnvironment, freshness);
                        after = resolve(candidateRoot, pkg.root, pkg.id, comparison.candidateEnvironment, freshness);
                    } catch (Exception unresolved) {
                        partial = true;
                        blocked = true;
                        blockedCode = "ATT-CONFIG-IMPACT-RESOLUTION-UNKNOWN";
                        if (!addAffected(affected, pkg.id, environment, Certainty.POTENTIALLY_AFFECTED,
                                "Effective Config could not be resolved for this Package and Environment.", List.of()))
                            affectedLimitReached = true;
                        addScheduleRows(affectedSchedules, snapshot.schedules, pkg.id, environment,
                                implicitScheduleEnvironments, Certainty.POTENTIALLY_AFFECTED,
                                "Effective Config resolution is incomplete.");
                        continue;
                    }
                    SemanticDelta delta = compare(before, after);
                    boolean unknown = !before.resolution.complete || !after.resolution.complete
                            || packageUnknown || !dynamicReasons.isEmpty();
                    if (unknown) partial = true;

                    Set<String> referencedChangedTools = new TreeSet<>();
                    for (String id : delta.changedTools.keySet()) if (references.tools.contains(id)) referencedChangedTools.add(id);
                    Set<String> referencedChangedHelpers = referencedHelpers(delta, before.config, after.config,
                            references.tools, references.helpers);
                    Certainty certainty;
                    List<String> changedIds = new ArrayList<>();
                    String reason;
                    if (delta.unclassifiedEffectiveChange) {
                        partial = true;
                        blocked = true;
                        blockedCode = "ATT-CONFIG-IMPACT-SEMANTIC-UNKNOWN";
                        certainty = Certainty.POTENTIALLY_AFFECTED;
                        reason = "Effective execution changed in a Config binding this analyzer cannot classify.";
                    } else if (delta.executionRevisionChanged && delta.changedCore) {
                        certainty = Certainty.CONFIRMED_AFFECTED;
                        changedIds.addAll(delta.coreReasons);
                        reason = safeReason(delta.coreReasons, environment);
                    } else if (delta.executionRevisionChanged && !referencedChangedTools.isEmpty()) {
                        certainty = Certainty.CONFIRMED_AFFECTED;
                        for (String id : referencedChangedTools) changedIds.add("tool/" + id);
                        reason = "TOOL_CONTRACT_CHANGED: a statically referenced Tool changed.";
                    } else if (delta.executionRevisionChanged && !referencedChangedHelpers.isEmpty()) {
                        certainty = Certainty.CONFIRMED_AFFECTED;
                        changedIds.addAll(referencedChangedHelpers);
                        reason = helperReason(referencedChangedHelpers, delta);
                    } else if (unknown && delta.hasAnyChange()) {
                        certainty = Certainty.POTENTIALLY_AFFECTED;
                        reason = "A dynamic or incomplete dependency could use the changed Config binding.";
                        changedIds.addAll(delta.safeChangedIds());
                    } else {
                        certainty = Certainty.UNAFFECTED;
                        reason = "Effective Config semantics did not change for this Package and Environment.";
                    }

                    // A currently invalid Package resolution after a Config edit is an online safety blocker.
                    if (packageInvalid && certainty == Certainty.UNAFFECTED) {
                        certainty = Certainty.POTENTIALLY_AFFECTED;
                        reason = "Package validation is incomplete for the candidate Config.";
                    }
                    if (certainty != Certainty.UNAFFECTED) {
                        if (!addAffected(affected, pkg.id, environment, certainty, reason, changedIds))
                            affectedLimitReached = true;
                        addScheduleRows(affectedSchedules, snapshot.schedules, pkg.id, environment,
                                implicitScheduleEnvironments, certainty, reason);
                    }
                }
            }

            if (affectedLimitReached) {
                partial = true;
                blocked = true;
                blockedCode = "ATT-CONFIG-IMPACT-REPORT-LIMIT";
            }
            if (snapshot.scheduleTruncated) {
                partial = true;
                blocked = true;
                blockedCode = "ATT-CONFIG-IMPACT-SCHEDULE-LIMIT";
            }
            if (partial && blockedCode == null) {
                blocked = true;
                blockedCode = "ATT-CONFIG-IMPACT-COVERAGE-PARTIAL";
            }
            ImpactDecision decision = blocked ? ImpactDecision.BLOCKED
                    : affected.isEmpty() ? ImpactDecision.COMPATIBLE : ImpactDecision.REQUIRES_REVIEW;
            return report(freshness, partial ? Coverage.PARTIAL : Coverage.COMPLETE, decision,
                    affected, affectedSchedules, blockedCode, decision == ImpactDecision.REQUIRES_REVIEW,
                    pageCursor, snapshot.scheduleTruncated);
            } finally {
                InventorySnapshot current = snapshot(candidate, draftFingerprint, deadline, null);
                if (!freshnessFingerprint(freshness).equals(freshnessFingerprint(current.freshness)))
                    throw new StaleInputException();
            }
        } finally {
            deleteTree(workspace);
        }
    }

    private ImpactReport report(ImpactFreshnessToken freshness, Coverage coverage, ImpactDecision decision,
                                List<AffectedPackage> affected, List<AffectedSchedule> schedules,
                                String code, boolean acknowledgementRequired, String cursor,
                                boolean schedulesTruncated) {
        List<AffectedSchedule> sorted = new ArrayList<>(schedules);
        sorted.sort(Comparator.comparing((AffectedSchedule item) -> item.scheduleId)
                .thenComparing(item -> String.valueOf(item.packageId))
                .thenComparing(item -> String.valueOf(item.environment)));
        int offset = cursorOffset(cursor);
        int end = Math.min(sorted.size(), offset + SCHEDULE_PAGE_SIZE);
        List<AffectedSchedule> page = offset >= sorted.size() ? Collections.emptyList()
                : sorted.subList(offset, end);
        String next = end < sorted.size() ? Integer.toString(end) : null;
        boolean truncated = schedulesTruncated || end < sorted.size();
        return new ImpactReport(ProviderState.AVAILABLE, freshness, coverage, decision, affected,
                new Page<>(page, next, sorted.size(), truncated), code, acknowledgementRequired);
    }

    private InventorySnapshot snapshot(ChangeSetPublisher.Candidate candidate, String draftFingerprint,
                                       long deadline, Path packageSnapshots) throws Exception {
        PackageRegistry.Snapshot registry = inventory.registry();
        if (registry == null || registry.revision == null || registry.packages == null)
            throw new IOException("Package registry is unavailable");
        List<LoadSchedule> rawSchedules = inventory.schedules();
        if (rawSchedules == null) throw new IOException("Schedule inventory is unavailable");
        boolean partial = registry.packages.size() > MAX_PACKAGES || rawSchedules.size() > MAX_SCHEDULES;
        List<PackageEntry> packages = new ArrayList<>();
        List<RevisionIdentity> sources = new ArrayList<>();
        List<RevisionIdentity> manifests = new ArrayList<>();
        TreeBudget packageBudget = new TreeBudget(deadline);
        TreeBudget copyBudget = new TreeBudget(deadline);
        TreeBudget liveVerifyBudget = new TreeBudget(deadline);
        int count = 0;
        for (Map.Entry<String, Path> entry : new TreeMap<>(registry.packages).entrySet()) {
            if (count++ >= MAX_PACKAGES) break;
            checkTime(deadline);
            Path root = safePackageRoot(entry.getValue());
            Path analysisRoot = root;
            String sourceDigest;
            if (packageSnapshots != null) {
                analysisRoot = packageSnapshots.resolve(String.format(Locale.ROOT, "%03d", count - 1));
                copyPackageSnapshot(root, analysisRoot, copyBudget);
                String liveDigest = boundedTreeDigest(root, liveVerifyBudget);
                sourceDigest = boundedTreeDigest(analysisRoot, packageBudget);
                if (!liveDigest.equals(sourceDigest)) throw new StaleInputException();
            } else {
                sourceDigest = boundedTreeDigest(analysisRoot, packageBudget);
            }
            String manifestDigest = fileDigestOrEmpty(analysisRoot.resolve(PackageConfig.FILE_NAME));
            packages.add(new PackageEntry(entry.getKey(), analysisRoot));
            sources.add(RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE, entry.getKey(), sourceDigest));
            manifests.add(RevisionIdentity.of(RevisionKind.PACKAGE_MANIFEST, entry.getKey(), manifestDigest));
        }
        List<ScheduleEntry> schedules = new ArrayList<>();
        List<RevisionIdentity> scheduleRevisions = new ArrayList<>();
        int scheduleCount = 0;
        for (LoadSchedule schedule : rawSchedules) {
            if (schedule == null || schedule.id == null || schedule.packageId == null) {
                partial = true;
                continue;
            }
            if (scheduleCount++ >= MAX_SCHEDULES) break;
            String revision = scheduleRevision(schedule);
            schedules.add(new ScheduleEntry(schedule.id, schedule.name, schedule.packageId, schedule.environment,
                    schedule.enabled, schedule.packageChangePolicy, revision));
            scheduleRevisions.add(RevisionIdentity.of(RevisionKind.SCHEDULE_SNAPSHOT, schedule.id, revision));
        }
        schedules.sort(Comparator.comparing(item -> item.id));
        String candidateRevision = ChangeSetPublisher.publishedRevision(candidate);
        String schemaDigest = candidate.schemaRevision() == null || !candidate.schemaRevision().matches("[a-f0-9]{64}")
                ? SchemaFiles.registryDigest() : candidate.schemaRevision();
        ImpactFreshnessToken freshness = new ImpactFreshnessToken(candidate.changeSetId(), draftFingerprint,
                RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, CENTRAL_SCOPE, candidate.sourceRevision()),
                RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, CENTRAL_SCOPE, candidateRevision),
                RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION, "package-registry", registry.revision),
                sources, manifests, RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA, "installed-engine", schemaDigest),
                scheduleRevisions);
        return new InventorySnapshot(packages, schedules, freshness, partial,
                rawSchedules.size() > MAX_SCHEDULES);
    }

    private EffectiveModel resolve(Path runtimeRoot, Path packageRoot, String packageId, String environment,
                                   ImpactFreshnessToken freshness) throws Exception {
        Path configPath = runtimeRoot.resolve("config/config.yaml");
        String selected = "default".equalsIgnoreCase(environment) ? null : environment;
        FrameworkConfig config = new FrameworkConfigLoader().loadDiscovery(configPath, runtimeRoot, packageRoot, selected);
        RevisionIdentity source = freshness.packageSources.stream()
                .filter(item -> item.scopeId.equals(packageId)).findFirst().orElseThrow();
        RevisionIdentity packageSource = source.kind == RevisionKind.PACKAGE_SOURCE ? source
                : new RevisionIdentity(RevisionKind.PACKAGE_SOURCE, source.scopeId, source.qualifier, source.revision);
        RevisionSet provenance = new RevisionSet(null, null,
                RevisionIdentity.of(RevisionKind.CHANGE_SET, packageId, freshness.draftFingerprint),
                RevisionIdentity.of(RevisionKind.PACKAGE_MANIFEST, packageId, freshness.manifests.stream()
                        .filter(item -> item.scopeId.equals(packageId)).findFirst().orElseThrow().revision),
                freshness.candidateConfig, null,
                RevisionIdentity.of(RevisionKind.ENVIRONMENT, packageId + ":" + environment,
                        digest(environment + "\n" + freshness.candidateConfig.revision)),
                freshness.engineSchema, null, packageSource, null);
        SourceManagementContracts.EffectiveResolution resolution = engine.resolveEffective(packageId, environment,
                packageRoot, configPath, runtimeRoot, provenance);
        executionRevision(resolution, packageId);
        return new EffectiveModel(config, resolution);
    }

    private SemanticDelta compare(EffectiveModel before, EffectiveModel after) throws Exception {
        RevisionIdentity beforeExecution = executionRevision(before.resolution, before.resolution.packageId);
        RevisionIdentity afterExecution = executionRevision(after.resolution, after.resolution.packageId);
        boolean executionRevisionChanged = !beforeExecution.scopeId.equals(afterExecution.scopeId)
                || !beforeExecution.revision.equals(afterExecution.revision);
        Map<String, String> beforeTools = fingerprints(before.config.tools());
        Map<String, String> afterTools = fingerprints(after.config.tools());
        Map<String, String> beforeHelpers = helperFingerprints(before.config);
        Map<String, String> afterHelpers = helperFingerprints(after.config);
        Map<String, String> changedTools = changed(beforeTools, afterTools);
        Map<String, String> changedHelpers = changed(beforeHelpers, afterHelpers);
        Map<String, String> changedEngineSemantics = changed(before.resolution.semanticFingerprints,
                after.resolution.semanticFingerprints);
        for (Map.Entry<String, String> entry : changedEngineSemantics.entrySet()) {
            String prefix = "descriptor:TOOL:";
            if (!entry.getKey().startsWith(prefix)) continue;
            int idEnd = entry.getKey().indexOf(':', prefix.length());
            if (idEnd > prefix.length()) changedTools.put(entry.getKey().substring(prefix.length(), idEnd), entry.getValue());
        }
        String beforeCore = fingerprintConfigCore(before.config);
        String afterCore = fingerprintConfigCore(after.config);
        boolean changedCore = !beforeCore.equals(afterCore);
        boolean changedProjection = changedCore || !changedTools.isEmpty() || !changedHelpers.isEmpty();
        if (changedProjection && !executionRevisionChanged) throw new StaleInputException();
        boolean unclassifiedEffectiveChange = executionRevisionChanged
                && before.resolution.effectiveFingerprint != null
                && after.resolution.effectiveFingerprint != null
                && !before.resolution.effectiveFingerprint.equals(after.resolution.effectiveFingerprint)
                && !changedProjection;
        List<String> coreReasons = new ArrayList<>();
        if (before.config.timeoutMs() != after.config.timeoutMs()) coreReasons.add("DEFAULT_TIMEOUT_CHANGED");
        if (!fingerprint(before.config.report()).equals(fingerprint(after.config.report())))
            coreReasons.add("REPORT_PRESENTATION_CHANGED");
        if (changedCore && coreReasons.isEmpty()) coreReasons.add("ENV_PROFILE_CHANGED");
        return new SemanticDelta(executionRevisionChanged, unclassifiedEffectiveChange,
                changedCore, coreReasons, changedTools, changedHelpers);
    }

    private static RevisionIdentity executionRevision(EffectiveResolution resolution, String packageId)
            throws IOException {
        if (resolution == null || resolution.revisions == null
                || !packageId.equals(resolution.packageId)
                || resolution.revisions.executionRevisionDigest == null
                || resolution.revisions.executionRevisionDigest.kind != RevisionKind.EXECUTION_REVISION
                || !resolution.revisions.executionRevisionDigest.scopeId
                .equals(resolution.packageId + "@" + resolution.environment))
            throw new IOException("Effective execution revision is unavailable or inconsistent");
        return resolution.revisions.executionRevisionDigest;
    }

    private Set<String> referencedHelpers(SemanticDelta delta, FrameworkConfig before,
                                          FrameworkConfig after, Set<String> toolReferences,
                                          Set<String> helperReferences) {
        Set<String> changed = new TreeSet<>();
        for (String logicalId : delta.changedHelpers.keySet()) {
            String id = logicalId.substring(logicalId.indexOf('/') + 1);
            if (helperReferences.contains(id) || helperReferences.contains(logicalId)) changed.add(logicalId);
        }
        for (String toolId : toolReferences) {
            att.config.ToolConfig oldTool = before.tools().get(toolId);
            att.config.ToolConfig newTool = after.tools().get(toolId);
            if (oldTool != null && !oldTool.sshHelper().isBlank()
                    && delta.changedHelpers.containsKey("sshhelper/" + oldTool.sshHelper()))
                changed.add("sshhelper/" + oldTool.sshHelper());
            if (newTool != null && !newTool.sshHelper().isBlank()
                    && delta.changedHelpers.containsKey("sshhelper/" + newTool.sshHelper()))
                changed.add("sshhelper/" + newTool.sshHelper());
        }
        return changed;
    }

    private static Map<String, String> helperFingerprints(FrameworkConfig config) throws Exception {
        Map<String, String> values = new TreeMap<>();
        addFingerprints(values, "dbhelper", config.dbHelpers());
        addFingerprints(values, "mqhelper", config.mqHelpers());
        addFingerprints(values, "httphelper", config.httpHelpers());
        addFingerprints(values, "sshhelper", config.sshHelpers());
        return values;
    }

    private static Map<String, String> fingerprints(Map<String, ?> values) throws Exception {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) result.put(entry.getKey(), fingerprint(entry.getValue()));
        return result;
    }

    private static void addFingerprints(Map<String, String> target, String prefix, Map<String, ?> values) throws Exception {
        for (Map.Entry<String, ?> entry : values.entrySet())
            target.put(prefix + "/" + entry.getKey(), fingerprint(entry.getValue()));
    }

    private static Map<String, String> changed(Map<String, String> before, Map<String, String> after) {
        Map<String, String> result = new TreeMap<>();
        Set<String> ids = new TreeSet<>(); ids.addAll(before.keySet()); ids.addAll(after.keySet());
        for (String id : ids) if (!java.util.Objects.equals(before.get(id), after.get(id)))
            result.put(id, after.containsKey(id) ? "changed" : "removed");
        return result;
    }

    private static String fingerprintConfigCore(FrameworkConfig config) throws Exception {
        Map<String, Object> values = new TreeMap<>();
        values.put("environment", config.environment());
        values.put("timeoutMs", config.timeoutMs());
        values.put("report", config.report());
        values.put("run", config.run());
        values.put("templatesRoot", config.templatesRoot());
        values.put("testcasesRoot", config.testcasesRoot());
        values.put("sheetGroups", config.sheetGroups());
        values.put("caseIdColumn", config.caseIdColumn());
        values.put("tagsColumn", config.tagsColumn());
        values.put("dataColumns", config.dataColumns());
        values.put("stages", config.stages());
        values.put("headerRows", config.headerRows());
        values.put("xmlNamespaceMode", config.xmlNamespaceMode());
        values.put("caseLogYamlAnchors", config.caseLogYamlAnchors());
        values.put("processOutput", config.processOutput());
        values.put("testdataDescriptors", config.testdataDescriptors());
        return fingerprint(values);
    }

    private static Object normalize(Object value, int depth, Set<Object> visiting) throws Exception {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
            return value;
        if (depth > 24) throw new IOException("Effective Config projection exceeds its depth limit");
        if (value instanceof Enum<?>) return ((Enum<?>) value).name();
        if (value instanceof Path) return ((Path) value).toString().replace('\\', '/');
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            for (Map.Entry<?, ?> item : map.entrySet())
                result.put(String.valueOf(item.getKey()), normalize(item.getValue(), depth + 1, visiting));
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            for (Object item : collection) result.add(normalize(item, depth + 1, visiting));
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++)
                result.add(normalize(Array.get(value, index), depth + 1, visiting));
            return result;
        }
        if (value.getClass().getName().startsWith("att.config.")) {
            if (!visiting.add(value)) return "<cycle>";
            try {
                Map<String, Object> result = new TreeMap<>();
                for (Field field : fields(value.getClass())) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())
                            || field.getName().equals("sourceFile") || field.getName().equals("effectiveConfiguration")
                            || field.getName().equals("helperDiscoveryFailures")) continue;
                    field.setAccessible(true);
                    result.put(field.getName(), normalize(field.get(value), depth + 1, visiting));
                }
                return result;
            } finally { visiting.remove(value); }
        }
        return value.getClass().getName() + ":" + String.valueOf(value);
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
            result.addAll(Arrays.asList(current.getDeclaredFields()));
        result.sort(Comparator.comparing(Field::getName));
        return result;
    }

    private Set<String> relevantEnvironments(Path baseRoot, Path candidateRoot, Path packageRoot,
                                             List<ScheduleEntry> schedules, String packageId,
                                             Set<String> implicitScheduleEnvironments) throws Exception {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(declaredEnvironments(baseRoot.resolve("config/config.yaml")));
        names.addAll(declaredEnvironments(candidateRoot.resolve("config/config.yaml")));
        PackageConfig manifest = PackageConfig.load(packageRoot);
        if (manifest.defaultEnvironment() != null) names.add(manifest.defaultEnvironment());
        names.addAll(implicitScheduleEnvironments);
        for (ScheduleEntry schedule : schedules) if (packageId.equals(schedule.packageId)) {
            if (schedule.environment != null && !schedule.environment.isBlank()) names.add(schedule.environment);
        }
        if (!packagePolicy.allowedEnvironments.isEmpty())
            names.removeIf(value -> !packagePolicy.allowedEnvironments.contains(value.toLowerCase(Locale.ROOT)));
        return names;
    }

    /** Null-environment schedules follow the Package's effective runtime default in each source tree. */
    private static EffectiveDefaults effectiveDefaults(Path baseRoot, Path candidateRoot,
                                                        Path packageRoot) throws Exception {
        FrameworkConfig base = new FrameworkConfigLoader().loadDiscovery(
                baseRoot.resolve("config/config.yaml"), baseRoot, packageRoot, null);
        FrameworkConfig candidate = new FrameworkConfigLoader().loadDiscovery(
                candidateRoot.resolve("config/config.yaml"), candidateRoot, packageRoot, null);
        return new EffectiveDefaults(base.environment(), candidate.environment());
    }

    private static boolean sameEnvironment(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private static Set<String> declaredEnvironments(Path configPath) throws Exception {
        Object parsed = loadYaml(configPath);
        if (!(parsed instanceof Map<?, ?> root)) throw new IOException("Runtime Config is not a mapping");
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Object profiles = root.get("environments");
        if (profiles instanceof Map<?, ?> values) for (Object key : values.keySet()) {
            String name = String.valueOf(key);
            if (!disabled(name) && name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) names.add(name);
        }
        Object selected = root.get("environment");
        if (selected instanceof String value && value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) names.add(value);
        return names;
    }

    private static List<ScheduleEntry> scheduleRows(List<ScheduleEntry> scheduleEntries,
                                                     String packageId, String environment,
                                                     Set<String> implicitScheduleEnvironments) {
        List<ScheduleEntry> rows = new ArrayList<>();
        for (ScheduleEntry entry : scheduleEntries) if (packageId.equals(entry.packageId)
                && (entry.environment == null || entry.environment.isBlank()
                ? implicitScheduleEnvironments.contains(environment) : environment.equalsIgnoreCase(entry.environment))) rows.add(entry);
        return rows;
    }

    private void addScheduleRows(List<AffectedSchedule> output, List<ScheduleEntry> scheduleEntries,
                                 String packageId, String environment, Set<String> implicitScheduleEnvironments,
                                 Certainty certainty,
                                 String reason) {
        for (ScheduleEntry entry : scheduleRows(scheduleEntries, packageId, environment,
                implicitScheduleEnvironments)) {
            String effectivePolicy = entry.policy == null ? defaultSchedulePolicy : entry.policy;
            boolean pause = certainty == Certainty.POTENTIALLY_AFFECTED || !entry.enabled
                    || "pause".equalsIgnoreCase(effectivePolicy);
            ScheduleAction action = "ignore".equalsIgnoreCase(effectivePolicy) && certainty != Certainty.POTENTIALLY_AFFECTED
                    ? ScheduleAction.UNCHANGED
                    : pause ? ScheduleAction.PAUSE_AND_REAPPROVE : ScheduleAction.REVALIDATE;
            String state = entry.enabled ? "enabled" : "paused";
            String policy = entry.policy == null ? "server default (" + defaultSchedulePolicy + ")" : entry.policy;
            int existingIndex = -1;
            for (int index = 0; index < output.size(); index++)
                if (entry.id.equals(output.get(index).scheduleId)) { existingIndex = index; break; }
            String safeReason = "Schedule is " + state + "; saved change policy is " + policy + ". " + reason;
            if (existingIndex < 0) output.add(new AffectedSchedule(entry.id, entry.packageId,
                    entry.environment, action, safeReason));
            else {
                AffectedSchedule existing = output.get(existingIndex);
                ScheduleAction strongest = scheduleRisk(existing.action) >= scheduleRisk(action) ? existing.action : action;
                output.set(existingIndex, new AffectedSchedule(entry.id, entry.packageId, entry.environment,
                        strongest, existing.safeReason.equals(safeReason) ? safeReason
                        : "Schedule is affected by multiple effective Config bindings; saved change policy is " + policy + "."));
            }
        }
    }

    private static int scheduleRisk(ScheduleAction action) {
        return action == ScheduleAction.PAUSE_AND_REAPPROVE ? 2
                : action == ScheduleAction.REVALIDATE ? 1 : 0;
    }

    private static boolean addAffected(List<AffectedPackage> output, String packageId, String environment,
                                       Certainty certainty, String reason, List<String> changedIds) {
        for (int index = 0; index < output.size(); index++) {
            AffectedPackage existing = output.get(index);
            if (!packageId.equals(existing.packageId) || !environment.equalsIgnoreCase(existing.environment)) continue;
            Set<String> mergedIds = new TreeSet<>(existing.changedLogicalIds);
            mergedIds.addAll(changedIds);
            Certainty mergedCertainty = existing.certainty == Certainty.POTENTIALLY_AFFECTED
                    || certainty == Certainty.POTENTIALLY_AFFECTED
                    ? Certainty.POTENTIALLY_AFFECTED : Certainty.CONFIRMED_AFFECTED;
            String mergedReason = existing.safeReason.equals(reason) ? reason
                    : "Multiple effective Config bindings affect this Package and Environment.";
            output.set(index, new AffectedPackage(packageId, environment, mergedCertainty,
                    mergedReason, new ArrayList<>(mergedIds)));
            return true;
        }
        if (output.size() >= MAX_AFFECTED_PACKAGES) return false;
        output.add(new AffectedPackage(packageId, environment, certainty, reason, changedIds));
        return true;
    }

    private static CandidateValidation candidateValidation(ChangeSetPublisher.Candidate candidate,
                                                           String fingerprint) {
        List<SourceManagementContracts.DependencyReference> dependencies = new ArrayList<>();
        RevisionSet revisions = new RevisionSet(
                candidate.registryRevision() == null ? null : RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION,
                        "package-registry", candidate.registryRevision()),
                null,
                RevisionIdentity.of(RevisionKind.CHANGE_SET, candidate.scopeId(), fingerprint), null,
                RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, CENTRAL_SCOPE, candidate.sourceRevision()), null,
                null, candidate.schemaRevision() == null ? null : RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA,
                        "installed-engine", candidate.schemaRevision()), null);
        return new CandidateValidation(Domain.CENTRAL_RUNTIME_CONFIG, CENTRAL_SCOPE, ValidationState.VALID,
                true, fingerprint, revisions, dependencies, Collections.emptyList());
    }

    private static RevisionSet revisions(ImpactFreshnessToken freshness) {
        return new RevisionSet(freshness.packageRegistry, null, null, null, freshness.candidateConfig,
                null, null, freshness.engineSchema, null);
    }

    private static ImpactAcknowledgement acknowledgement(Map<String, Object> review) {
        if (review == null) return null;
        Object raw = review.get("impactAcknowledgement");
        if (!(raw instanceof Map<?, ?> values)) return null;
        try {
            return new ImpactAcknowledgement(string(values.get("freshnessFingerprint")),
                    string(values.get("actor")), string(values.get("acknowledgementDigest")));
        } catch (RuntimeException invalid) { return null; }
    }

    private static String string(Object value) { return value instanceof String ? (String) value : null; }

    private static ReferenceInventory references(CandidateValidation... candidates) {
        Set<String> tools = new TreeSet<>(), helpers = new TreeSet<>();
        if (candidates != null) for (CandidateValidation candidate : candidates)
            if (candidate != null && candidate.dependencies != null)
                for (SourceManagementContracts.DependencyReference dependency : candidate.dependencies) {
                    String originalRelationship = dependency.relationship == null ? "" : dependency.relationship;
                    String relationship = originalRelationship.isEmpty() ? ""
                            : originalRelationship.toLowerCase(Locale.ROOT);
                    if (dependency.kind == DependencyKind.TOOL && dependency.logicalId != null
                            && !"tool-descriptor".equals(relationship)) tools.add(dependency.logicalId);
                    if (!relationship.equals("tool-descriptor")) {
                        addRelationshipReference(originalRelationship, relationship, "->tool:", tools);
                        addRelationshipReference(originalRelationship, relationship, "->helper:", helpers);
                        addRelationshipReference(originalRelationship, relationship, "->dbhelper:", helpers);
                        addRelationshipReference(originalRelationship, relationship, "->mqhelper:", helpers);
                        addRelationshipReference(originalRelationship, relationship, "->httphelper:", helpers);
                        addRelationshipReference(originalRelationship, relationship, "->sshhelper:", helpers);
                    }
                }
        return new ReferenceInventory(tools, helpers);
    }

    private static void addRelationshipReference(String original, String normalized,
                                                 String marker, Set<String> output) {
        int index = normalized.lastIndexOf(marker);
        if (index >= 0 && index + marker.length() < normalized.length())
            output.add(original.substring(index + marker.length()));
    }

    private static Set<String> unknownReferences(CandidateValidation... candidates) {
        Set<String> result = new LinkedHashSet<>();
        if (candidates != null) for (CandidateValidation candidate : candidates)
            if (candidate != null && candidate.dependencies != null)
                for (SourceManagementContracts.DependencyReference dependency : candidate.dependencies)
                    if (dependency.certainty == ReferenceCertainty.UNKNOWN)
                        result.add(dependency.kind + ":" + String.valueOf(dependency.logicalId));
        return result;
    }

    private static boolean incomplete(CandidateValidation validation) {
        return validation != null && validation.state == ValidationState.INCOMPLETE;
    }
    private static boolean invalid(CandidateValidation validation) {
        return validation != null && validation.state == ValidationState.INVALID;
    }

    private static String safeReason(List<String> reasons, String environment) {
        if (reasons.isEmpty()) return "ENV_PROFILE_CHANGED: effective values changed in Environment " + environment + ".";
        return String.join("; ", reasons) + ": effective values changed in Environment " + environment + ".";
    }

    private static String helperReason(Set<String> ids, SemanticDelta delta) {
        boolean removed = ids.stream().anyMatch(id -> "removed".equals(delta.changedHelpers.get(id)));
        return removed ? "HELPER_REMOVED: a referenced Helper is no longer available."
                : "HELPER_ENDPOINT_CHANGED: a referenced Helper definition changed.";
    }

    private static void materialize(Set<String> logicalPaths, Reader reader, Path root, long deadline) throws Exception {
        if (logicalPaths == null || logicalPaths.size() > MAX_TREE_FILES)
            throw new IOException("Config source exceeds the impact scan file limit");
        long total = 0;
        for (String logical : new TreeSet<>(logicalPaths)) {
            checkTime(deadline);
            Path relative = Path.of(logical).normalize();
            if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")
                    || !relative.toString().replace('\\', '/').equals(logical))
                throw new IOException("Config source contains an unsafe logical path");
            byte[] bytes = reader.read(logical);
            if (bytes == null) throw new IOException("Config source closure is incomplete");
            total = Math.addExact(total, bytes.length);
            if (bytes.length > ChangeSetPublisher.MAX_REVISION_FILE_BYTES || total > MAX_TREE_BYTES)
                throw new IOException("Config source exceeds the impact scan byte limit");
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) throw new IOException("Config source path escapes its scan root");
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
    }

    private static String candidateFingerprint(ChangeSetPublisher.Candidate candidate, long deadline) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, candidate.domain()); update(digest, candidate.scopeId()); update(digest, candidate.changeSetId());
        update(digest, candidate.sourceRevision()); update(digest, candidate.registryRevision());
        update(digest, candidate.schemaRevision());
        long total = 0;
        int count = 0;
        for (String path : new TreeSet<>(candidate.files())) {
            checkTime(deadline);
            if (++count > MAX_TREE_FILES) throw new IOException("Candidate Config exceeds the impact scan file limit");
            byte[] bytes = candidate.read(path);
            if (bytes == null) throw new IOException("Candidate Config source is incomplete");
            total += bytes.length;
            if (bytes.length > ChangeSetPublisher.MAX_REVISION_FILE_BYTES || total > MAX_TREE_BYTES)
                throw new IOException("Candidate Config exceeds the impact scan byte limit");
            update(digest, path); digest.update(bytes); digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    private static String boundedTreeDigest(Path root, TreeBudget budget) throws Exception {
        Path canonical = safePackageRoot(root);
        if (Files.isSymbolicLink(canonical) || !Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Package source root is unavailable");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> directories = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(canonical, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) throw new IOException("Package source contains an unsafe directory");
                if (!dir.equals(canonical)) directories.add(canonical.relativize(dir).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(file) || !attrs.isRegularFile())
                    throw new IOException("Package source contains an unsafe file");
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        directories.sort(String::compareTo);
        for (String logical : directories) update(digest, "directory/" + logical);
        files.sort(Comparator.comparing(file -> canonical.relativize(file).toString().replace('\\', '/')));
        for (Path file : files) {
            checkTime(budget.deadline);
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.isSymbolicLink() || !before.isRegularFile() || Files.isSymbolicLink(file))
                throw new IOException("Package source contains an unsafe file");
            budget.add(before.size());
            byte[] bytes = Files.readAllBytes(file);
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameFileObservation(before, after) || bytes.length != before.size()) throw new StaleInputException();
            String logical = canonical.relativize(file).toString().replace('\\', '/');
            update(digest, "file/" + logical); digest.update(bytes); digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    private static void copyPackageSnapshot(Path source, Path destination, TreeBudget budget) throws Exception {
        Path canonical = safePackageRoot(source);
        Files.createDirectories(destination);
        Files.walkFileTree(canonical, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkTime(budget.deadline);
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir))
                    throw new IOException("Package source contains an unsafe directory");
                Path target = destination.resolve(canonical.relativize(dir)).normalize();
                if (!target.startsWith(destination)) throw new IOException("Package source path escapes its snapshot root");
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkTime(budget.deadline);
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(file) || !attrs.isRegularFile())
                    throw new IOException("Package source contains an unsafe file");
                budget.add(attrs.size());
                byte[] bytes = Files.readAllBytes(file);
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!sameFileObservation(attrs, after) || bytes.length != attrs.size()) throw new StaleInputException();
                Path target = destination.resolve(canonical.relativize(file)).normalize();
                if (!target.startsWith(destination)) throw new IOException("Package source path escapes its snapshot root");
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                try {
                    Files.setPosixFilePermissions(target,
                            Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
                } catch (UnsupportedOperationException ignored) { }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean sameFileObservation(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isRegularFile() && !after.isSymbolicLink() && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && java.util.Objects.equals(before.fileKey(), after.fileKey());
    }

    private static Path safePackageRoot(Path root) throws IOException {
        if (root == null || Files.isSymbolicLink(root)) throw new IOException("Package source root is unavailable");
        Path real = root.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Package source root is unavailable");
        return real;
    }

    private static String fileDigestOrEmpty(Path file) throws Exception {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return digest("");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Package Manifest source is unsafe");
        if (Files.size(file) > ChangeSetPublisher.MAX_REVISION_FILE_BYTES)
            throw new IOException("Package Manifest source exceeds the impact scan byte limit");
        return digest(Files.readAllBytes(file));
    }

    private static String scheduleRevision(LoadSchedule schedule) {
        return digest("att-schedule-snapshot/v1\n" + schedule.id + "\n" + schedule.packageId + "\n"
                + String.valueOf(schedule.environment) + "\n" + schedule.definitionVersion + "\n"
                + schedule.snapshotVersion + "\n" + String.valueOf(schedule.expectedRevisionDigest) + "\n"
                + schedule.enabled + "\n" + String.valueOf(schedule.packageChangePolicy) + "\n"
                + String.valueOf(schedule.updatedAt));
    }

    private static String identity(RevisionIdentity value) {
        if (value == null) return "-";
        return value.kind + "\t" + value.scopeId + "\t" + String.valueOf(value.qualifier) + "\t" + value.revision;
    }

    private static void appendIdentities(StringBuilder value, List<RevisionIdentity> identities) {
        List<RevisionIdentity> sorted = new ArrayList<>(identities);
        sorted.sort(Comparator.comparing(ConfigImpactAnalyzer::identity));
        for (RevisionIdentity identity : sorted) value.append(identity(identity)).append('\n');
    }

    private static int cursorOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int value = Integer.parseInt(cursor);
            if (value < 0 || value > 100000) throw new IllegalArgumentException("Invalid impact page cursor");
            return value;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid impact page cursor"); }
    }

    private static Object loadYaml(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > 16L * 1024L * 1024L) throw new IOException("Runtime Config source is unavailable");
        String source = Files.readString(path, StandardCharsets.UTF_8);
        if (source.indexOf('\0') >= 0) throw new IOException("Runtime Config source is invalid");
        return safeYaml().load(source);
    }

    private static boolean disabled(String key) { return key != null && key.startsWith("x-"); }

    private static Yaml safeYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(16 * 1024 * 1024);
        return new Yaml(new SafeConstructor(options));
    }

    private static void checkTime(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
            throw new AnalysisLimitException();
    }

    private static final class AnalysisLimitException extends IOException {
        AnalysisLimitException() { super("Config impact analysis exceeded its time limit"); }
    }
    private static final class StaleInputException extends IOException {
        StaleInputException() { super("Config impact inputs changed during analysis"); }
    }

    private static Path privateDirectory() throws IOException {
        Path directory = Files.createTempDirectory("att-config-impact-");
        try { Files.setPosixFilePermissions(directory, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
        catch (UnsupportedOperationException ignored) { }
        return directory;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(directory); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String fingerprint(Object value) throws Exception {
        return digest(JSON.writeValueAsBytes(normalize(value, 0, new HashSet<>())));
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
    private static void update(MessageDigest digest, String value) {
        if (value == null) value = "";
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }

    private void boundCaches() {
        if (candidates.size() > 128) trim(candidates);
        if (candidatesByFingerprint.size() > 128) trim(candidatesByFingerprint);
        if (issuedReports.size() > 128) trim(issuedReports);
    }
    private static <T> void trim(Map<String, T> values) {
        synchronized (values) {
            int remove = Math.max(0, values.size() - 96);
            for (String key : new ArrayList<>(values.keySet())) {
                if (remove-- <= 0) break;
                values.remove(key);
            }
        }
    }

    @FunctionalInterface private interface Reader { byte[] read(String logicalPath) throws IOException; }
    private record PackageEntry(String id, Path root) { }
    private record EnvironmentComparison(String displayEnvironment, String baseEnvironment,
                                         String candidateEnvironment) { }
    private record EffectiveDefaults(String base, String candidate) {
        Set<String> environments() {
            Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (base != null && !base.isBlank()) result.add(base);
            if (candidate != null && !candidate.isBlank()) result.add(candidate);
            return result;
        }
    }
    private record ScheduleEntry(String id, String name, String packageId, String environment,
                                 boolean enabled, String policy, String revision) { }
    private record InventorySnapshot(List<PackageEntry> packages, List<ScheduleEntry> schedules,
                                     ImpactFreshnessToken freshness, boolean partial,
                                     boolean scheduleTruncated) { }
    private static final class TreeBudget {
        final long deadline;
        long bytes;
        int files;
        TreeBudget(long deadline) { this.deadline = deadline; }
        void add(long size) throws IOException {
            if (++files > MAX_TREE_FILES || size > ChangeSetPublisher.MAX_REVISION_FILE_BYTES)
                throw new IOException("Package source exceeds the impact scan file limit");
            bytes += size;
            if (bytes > MAX_TREE_BYTES) throw new IOException("Package source exceeds the impact scan byte limit");
        }
    }
    private record EffectiveModel(FrameworkConfig config, SourceManagementContracts.EffectiveResolution resolution) { }
    private record ReferenceInventory(Set<String> tools, Set<String> helpers) { }
    private record SemanticDelta(boolean executionRevisionChanged, boolean unclassifiedEffectiveChange,
                                 boolean changedCore, List<String> coreReasons,
                                 Map<String, String> changedTools, Map<String, String> changedHelpers) {
        boolean hasAnyChange() { return changedCore || !changedTools.isEmpty() || !changedHelpers.isEmpty(); }
        List<String> safeChangedIds() {
            List<String> result = new ArrayList<>();
            for (String id : changedTools.keySet()) result.add("tool/" + id);
            result.addAll(changedHelpers.keySet());
            return result;
        }
    }
}
