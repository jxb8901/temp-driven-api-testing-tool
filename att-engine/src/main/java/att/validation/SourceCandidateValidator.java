package att.validation;

import att.Version;
import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.PackageConfig;
import att.config.SchemaSupport;
import att.config.SuiteConfigResolver;
import att.config.YamlSupport;
import att.core.ExecutionOptions;
import att.core.StageCaseData;
import att.core.TestCase;
import att.excel.ExcelTestSuiteLoader;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.load.LoadScenario;
import att.load.LoadScenarioLoader;
import att.resource.PackageResourceInspector;
import att.resource.PackageResourceResolver;
import att.server.api.AttValidationReport;
import att.server.api.source.SourceManagementContracts;
import att.server.api.source.SourceManagementContracts.CandidateValidation;
import att.server.api.source.SourceManagementContracts.DependencyKind;
import att.server.api.source.SourceManagementContracts.DependencyReference;
import att.server.api.source.SourceManagementContracts.Domain;
import att.server.api.source.SourceManagementContracts.EffectiveResolution;
import att.server.api.source.SourceManagementContracts.ReferenceCertainty;
import att.server.api.source.SourceManagementContracts.ResolutionOrigin;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import att.server.api.source.SourceManagementContracts.Severity;
import att.server.api.source.SourceManagementContracts.ValidationDiagnostic;
import att.server.api.source.SourceManagementContracts.ValidationState;
import att.snapshot.TestcaseSnapshotService;
import att.template.FileExpressionResolver;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.testdata.TestdataDescriptor;
import att.testdata.TestdataRegistry;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Static validation and production Engine resolution for complete staged source candidates. */
public final class SourceCandidateValidator {
    public static final int MAX_PACKAGE_ENVIRONMENTS = 16;
    public static final int MAX_LOAD_DESCRIPTORS = 512;
    public static final int MAX_LOAD_TEMPLATE_DESCRIPTORS = 256;
    public static final int MAX_DIAGNOSTICS = 256;
    public static final int MAX_VALIDATION_REPORT_BYTES = 256 * 1024;

    private static final Set<String> ONLINE_CONFIG_FIELDS = set("schemaVersion", "environment", "timeoutMs",
            "templates", "testcase", "run", "execution", "report", "caseLog", "xml", "toolGroups",
            "dbhelpers", "mqhelpers", "sshhelpers", "httphelpers", "testdata", "environments", "ssh",
            "tools", "packagePolicy");

    public CandidateValidation validatePackage(Path packageRoot, Path configFile, Path runtimeRoot,
                                               String packageId, RevisionSet revisions,
                                               String validationFingerprint) throws Exception {
        Path pkg = canonicalDirectory(packageRoot, "Package candidate");
        Path config = canonicalFile(configFile, "Runtime Config candidate");
        Path runtime = canonicalDirectory(runtimeRoot, "Runtime Config root");
        if (packageId == null || packageId.trim().isEmpty()) throw new IllegalArgumentException("Registered Package ID is required");
        if (!config.startsWith(runtime)) throw new IllegalArgumentException("Runtime Config path is outside its source root");

        List<ValidationDiagnostic> diagnostics = new ArrayList<ValidationDiagnostic>();
        List<DependencyReference> dependencies = new ArrayList<DependencyReference>();
        boolean complete = true;
        boolean invalid = false;

        AttValidationReport configReport = RuntimeConfigurationValidator.validate(config, runtime, pkg,
                null, "SOURCE-CANDIDATE", true, true, null);
        configReport.limitOutputBytes(MAX_VALIDATION_REPORT_BYTES);
        diagnostics.addAll(reportDiagnostics(configReport.toMap(), MAX_DIAGNOSTICS));
        complete &= configReport.isComplete();
        invalid |= configReport.hasFailures();

        List<String> environments = declaredEnvironments(config, pkg);
        if (environments.size() > MAX_PACKAGE_ENVIRONMENTS) {
            complete = false;
            diagnostics.add(diagnostic("ATT-SOURCE-ENVIRONMENT-LIMIT", Severity.ERROR, "config/config.yaml",
                    "environments", null, "Package validation exceeded the Environment coverage limit.",
                    "Reduce the Environment set or validate a smaller candidate."));
            environments = new ArrayList<String>(environments.subList(0, MAX_PACKAGE_ENVIRONMENTS));
        }

        for (String environment : environments) {
            try {
                FrameworkConfig effective = new FrameworkConfigLoader().loadDiscovery(config, runtime, pkg, environment);
                PackageValidator.ValidationSummary summary = packageSummary(pkg, config, effective);
                diagnostics.addAll(packageDiagnostics(summary, MAX_DIAGNOSTICS - diagnostics.size()));
                invalid |= !summary.valid();
                if (diagnostics.size() >= MAX_DIAGNOSTICS) {
                    complete = false;
                    break;
                }
                collectEffectiveBindings(packageId, environment, runtime, effective, dependencies);
                complete &= validateLoadCandidates(pkg, packageId, environment, effective, dependencies, diagnostics);
                collectFileDependencies(pkg, packageId, environment, effective, dependencies);
                collectPackageResourceDependencies(pkg, config, effective, packageId, environment, dependencies);
                if (diagnostics.size() >= MAX_DIAGNOSTICS) {
                    complete = false;
                    break;
                }
            } catch (Exception failure) {
                invalid = true;
                diagnostics.add(diagnostic("ATT-SOURCE-CANDIDATE-ENVIRONMENT-INVALID", Severity.ERROR,
                        "config/config.yaml", "environments." + environment, null,
                        "The Package candidate could not be resolved for Environment " + environment + ".",
                        "Correct the Package Manifest, Environment profile, or referenced descriptors."));
            }
        }

        collectManifestDependencies(pkg, packageId, dependencies);
        List<DependencyReference> candidateDependencies = deduplicate(dependencies);
        if (hasUnknown(candidateDependencies)) {
            complete = false;
            diagnostics.add(diagnostic("ATT-SOURCE-DEPENDENCY-UNKNOWN", Severity.WARNING, null, "dependencies", null,
                    "One or more Package dependencies could not be resolved to an immutable source.",
                    "Correct the unavailable descriptor reference and validate the whole candidate again."));
        }
        RevisionSet candidateRevisions = revisions;
        try (PackageResourceInspector inspector = new PackageResourceInspector(pkg, config,
                environments.isEmpty() ? null : environments.get(0), Collections.<String>emptyList(), 65536, 262144)) {
            String packageSourceDigest = inspector.packageSourceRevisionDigest();
            candidateRevisions = withPackageSourceRevision(revisions, packageId, packageSourceDigest);
        } catch (Exception unavailable) {
            complete = false;
            diagnostics.add(diagnostic("ATT-PACKAGE-SOURCE-REVISION-UNAVAILABLE", Severity.ERROR,
                    null, "packageSourceRevision", null,
                    "The Package source identity could not be computed from the complete candidate.",
                    "Correct unavailable Package files and validate the whole candidate again."));
        }
        invalid |= hasErrors(diagnostics);
        ValidationState state = invalid ? ValidationState.INVALID : complete ? ValidationState.VALID : ValidationState.INCOMPLETE;
        return new CandidateValidation(Domain.PACKAGE_SOURCE, packageId, state, complete,
                state == ValidationState.VALID, validationFingerprint, candidateRevisions,
                candidateDependencies, boundedDiagnostics(diagnostics));
    }

    public CandidateValidation validateCentralConfig(Path configFile, Path runtimeRoot,
                                                     RevisionSet revisions,
                                                     String validationFingerprint) throws Exception {
        return validateCentralConfig(configFile, runtimeRoot, null, revisions, validationFingerprint);
    }

    /**
     * Validates an online Config candidate against its trusted, revision-bound base. The
     * baseline is used only for deployment-controlled fields that must remain unchanged.
     */
    public CandidateValidation validateCentralConfig(Path configFile, Path runtimeRoot,
                                                     Path baselineConfigFile, RevisionSet revisions,
                                                     String validationFingerprint) throws Exception {
        Path config = canonicalFile(configFile, "Runtime Config candidate");
        Path runtime = canonicalDirectory(runtimeRoot, "Runtime Config root");
        if (!config.startsWith(runtime)) throw new IllegalArgumentException("Runtime Config path is outside its source root");
        List<ValidationDiagnostic> diagnostics = new ArrayList<ValidationDiagnostic>();
        List<DependencyReference> dependencies = new ArrayList<DependencyReference>();
        Path baseline = baselineConfigFile == null ? null : canonicalFile(baselineConfigFile, "Runtime Config baseline");
        validateOnlineEligibility(config, baseline, diagnostics);
        AttValidationReport report = RuntimeConfigurationValidator.validate(config, runtime, null,
                null, "SOURCE-CANDIDATE", true, true, null);
        report.limitOutputBytes(MAX_VALIDATION_REPORT_BYTES);
        diagnostics.addAll(reportDiagnostics(report.toMap(), MAX_DIAGNOSTICS - diagnostics.size()));
        collectConfigBindings(runtime, config, dependencies);
        if (hasUnknown(dependencies)) diagnostics.add(diagnostic("ATT-SOURCE-DEPENDENCY-UNKNOWN", Severity.WARNING,
                null, "dependencies", null, "One or more Runtime Config dependencies could not be resolved.",
                "Correct the unavailable descriptor reference and validate the whole candidate again."));
        boolean invalid = hasErrors(diagnostics) || report.hasFailures();
        ValidationState state = invalid ? ValidationState.INVALID
                : report.isComplete() && !hasUnknown(dependencies) ? ValidationState.VALID : ValidationState.INCOMPLETE;
        boolean coverageComplete = report.isComplete() && !hasUnknown(dependencies);
        return new CandidateValidation(Domain.CENTRAL_RUNTIME_CONFIG, "central-runtime-config", state,
                coverageComplete, state == ValidationState.VALID, validationFingerprint, revisions,
                deduplicate(dependencies), boundedDiagnostics(diagnostics));
    }

    /** Resolves the effective candidate with the same Engine/Manifest precedence used by execution. */
    public EffectiveResolution resolveEffective(String packageId, String environment,
                                                Path packageRoot, Path configFile, Path runtimeRoot,
                                                RevisionSet provenance) throws Exception {
        Path pkg = canonicalDirectory(packageRoot, "Package source");
        Path config = canonicalFile(configFile, "Runtime Config source");
        Path runtime = canonicalDirectory(runtimeRoot, "Runtime Config root");
        String requested = environment == null || environment.trim().isEmpty() || "default".equalsIgnoreCase(environment.trim())
                ? null : environment.trim();
        FrameworkConfig effective = new FrameworkConfigLoader().loadDiscovery(config, runtime, pkg,
                requested);
        String selected = effective.environment() == null || effective.environment().trim().isEmpty()
                ? "default" : effective.environment().trim();
        Map<String, ResolutionOrigin> origins = new LinkedHashMap<String, ResolutionOrigin>();
        if (effective.effectiveConfiguration() != null) {
            for (Map.Entry<String, String> entry : effective.effectiveConfiguration().origins().entrySet())
                origins.put(entry.getKey(), origin(entry.getValue()));
        }
        List<DependencyReference> bindings = new ArrayList<DependencyReference>();
        collectEffectiveBindings(packageId, selected, runtime, effective, bindings);
        collectManifestDependencies(pkg, packageId, bindings);
        collectFileDependencies(pkg, packageId, selected, effective, bindings);
        collectPackageResourceDependencies(pkg, config, effective, packageId, selected, bindings);
        PackageConfig manifest = PackageConfig.load(pkg);
        String schemaDigest = SchemaFiles.registryDigest();
        Map<String, String> semanticFingerprints = semanticFingerprints(pkg, runtime, effective, origins, bindings);
        String effectiveDigest = digestFingerprints(selected, semanticFingerprints);
        String envFingerprint = digest(selected + "\n" + effectiveDigest);
        RevisionSet resolvedRevisions = withResolvedRevisions(provenance, packageId, selected,
                manifest.digest(), effectiveDigest, envFingerprint, schemaDigest, fileDigest(config));
        Map<String, String> sourceDigests;
        try (PackageResourceInspector inspector = new PackageResourceInspector(pkg, config, selected,
                Collections.<String>emptyList(), 65536, 262144)) {
            sourceDigests = inspector.sourceRevisionDigests(null);
        }
        RevisionIdentity packageSource = RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE,
                packageId, sourceDigests.get("packageSourceRevision"));
        RevisionIdentity businessSource = RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE,
                packageId, sourceDigests.get("packageSourceRevision"));
        RevisionIdentity executionRevision = RevisionIdentity.of(RevisionKind.EXECUTION_REVISION,
                packageId + "@" + selected, sourceDigests.get("executionRevisionDigest"));
        resolvedRevisions = withExecutionRevisions(resolvedRevisions, businessSource, packageSource, executionRevision);
        List<ValidationDiagnostic> diagnostics = new ArrayList<ValidationDiagnostic>();
        boolean complete = effective.helperDiscoveryFailures().isEmpty();
        List<DependencyReference> resolvedDependencies = deduplicate(bindings);
        complete &= !hasUnknown(resolvedDependencies);
        if (!complete) diagnostics.add(diagnostic("ATT-EFFECTIVE-DEPENDENCY-UNKNOWN", Severity.WARNING,
                null, "helpers", null, "One or more optional shared dependencies could not be inspected.",
                "Review the helper descriptor diagnostics before relying on a complete impact result."));
        return new EffectiveResolution(packageId, selected, effectiveDigest, complete, resolvedRevisions,
                origins, semanticFingerprints, bindings, resolvedDependencies, diagnostics);
    }

    /** Builds an explicitly requested Testcase XML snapshot from the candidate's native authoring unit. */
    public byte[] generateSnapshot(Path packageRoot, Path configFile, Path runtimeRoot,
                                   String workbookPath) throws Exception {
        Path pkg = canonicalDirectory(packageRoot, "Package candidate");
        Path config = canonicalFile(configFile, "Runtime Config candidate");
        Path runtime = canonicalDirectory(runtimeRoot, "Runtime Config root");
        if (!config.startsWith(runtime)) throw new IllegalArgumentException("Runtime Config path is outside its source root");
        if (workbookPath == null || workbookPath.trim().isEmpty()) throw new IllegalArgumentException("Workbook path is required");
        Path workbook = pkg.resolve(workbookPath).normalize();
        if (!workbook.startsWith(pkg) || !Files.isRegularFile(workbook, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(workbook)) throw new IllegalArgumentException("Workbook is unavailable inside the Package candidate");
        FrameworkConfig effective = new FrameworkConfigLoader().loadDiscovery(config, runtime, pkg, null);
        FrameworkConfig suite = new SuiteConfigResolver(pkg, effective).resolve(workbook);
        List<TestCase> cases = new ExcelTestSuiteLoader(suite).load(workbook);
        TestcaseSnapshotService snapshots = new TestcaseSnapshotService();
        return snapshots.serialize(snapshots.build(suite, cases)).getBytes(StandardCharsets.UTF_8);
    }

    private static PackageValidator.ValidationSummary packageSummary(Path pkg, Path configFile,
                                                                    FrameworkConfig effective) throws Exception {
        ExecutionOptions options = ExecutionOptions.forApi("validate", configFile, effective.environment(),
                Collections.<Path>emptyList(), null, Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), "", false,
                false, false, false, null, "package", null, null, null,
                false, null, null, null, null, null, null, null, null, null, null,
                Collections.<String>emptyList());
        return new PackageValidator(pkg, effective, configFile).validate(options);
    }

    private static void validateOnlineEligibility(Path config, Path baseline,
                                                 List<ValidationDiagnostic> diagnostics) throws Exception {
        Object rootValue = YamlSupport.load(config);
        if (!(rootValue instanceof Map)) {
            diagnostics.add(diagnostic("ATT-CONFIG-ONLINE-UNSUPPORTED", Severity.ERROR, "config/config.yaml",
                    null, null, "Runtime Config must be a mapping for online publication.",
                    "Correct the candidate and validate it again."));
            return;
        }
        Map<?, ?> root = (Map<?, ?>) rootValue;
        Map<?, ?> baselineRoot = Collections.emptyMap();
        if (baseline != null && Files.isRegularFile(baseline, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(baseline)) {
            Object baselineValue = YamlSupport.load(baseline);
            if (baselineValue instanceof Map) baselineRoot = (Map<?, ?>) baselineValue;
        }
        boolean outputDirectoryChanged = baseline == null
                ? root.containsKey("outputDirectory")
                : root.containsKey("outputDirectory") != baselineRoot.containsKey("outputDirectory")
                    || root.containsKey("outputDirectory")
                        && !java.util.Objects.equals(canonical(root.get("outputDirectory")),
                                canonical(baselineRoot.get("outputDirectory")));
        for (Object keyValue : root.keySet()) {
            boolean unchangedDeploymentOutput = "outputDirectory".equals(keyValue) && !outputDirectoryChanged;
            if (!(keyValue instanceof String)
                    || !ONLINE_CONFIG_FIELDS.contains(keyValue) && !unchangedDeploymentOutput) {
                diagnostics.add(diagnostic("ATT-CONFIG-ONLINE-UNSUPPORTED", Severity.ERROR, "config/config.yaml",
                        String.valueOf(keyValue), null, "The candidate contains a field that cannot be hot-activated online.",
                        "Remove the unsupported field and apply deployment-controlled changes offline."));
            }
        }
        if (outputDirectoryChanged) {
            diagnostics.add(diagnostic("ATT-CONFIG-ONLINE-UNSUPPORTED", Severity.ERROR, "config/config.yaml",
                    "outputDirectory", null, "Changing the Worker output root is not supported by online Config publication.",
                    "Keep the configured output root and manage output changes through deployment configuration."));
        }
        if (!Version.CONFIG_SCHEMA.equals(root.get("schemaVersion"))) {
            diagnostics.add(diagnostic("ATT-CONFIG-ONLINE-SCHEMA-UNSUPPORTED", Severity.ERROR, "config/config.yaml",
                    "schemaVersion", null, "Online publication requires the currently installed Runtime Config schema.",
                    "Migrate the candidate with the installed Engine before publishing it online."));
        }
    }

    private static List<String> declaredEnvironments(Path config, Path packageRoot) throws Exception {
        Object value = YamlSupport.load(config);
        Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (value instanceof Map) {
            Map<?, ?> root = (Map<?, ?>) value;
            Object profiles = root.get("environments");
            if (profiles instanceof Map) for (Object name : ((Map<?, ?>) profiles).keySet())
                if (name instanceof String && !SchemaSupport.isDisabledKey(name)) names.add((String) name);
            Object selected = root.get("environment");
            if (selected instanceof String) names.add((String) selected);
        }
        PackageConfig manifest = PackageConfig.load(packageRoot);
        if (manifest.defaultEnvironment() != null) names.add(manifest.defaultEnvironment());
        if (names.isEmpty()) names.add("default");
        List<String> result = new ArrayList<String>(names);
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private static boolean validateLoadCandidates(Path packageRoot, String packageId, String environment,
                                                  FrameworkConfig effective,
                                                  List<DependencyReference> dependencies,
                                                  List<ValidationDiagnostic> diagnostics) throws Exception {
        Path loadRoot = packageRoot.resolve("load").normalize();
        if (!Files.exists(loadRoot, LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.isSymbolicLink(loadRoot) || !Files.isDirectory(loadRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Package load root is not a safe directory");
        List<Path> yamlFiles = new ArrayList<Path>();
        try (Stream<Path> files = Files.walk(loadRoot, 32)) {
            files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    .filter(SourceCandidateValidator::isYaml).sorted().forEach(yamlFiles::add);
        }
        if (yamlFiles.size() > MAX_LOAD_DESCRIPTORS) {
            diagnostics.add(diagnostic("ATT-LOAD-CANDIDATE-LIMIT", Severity.ERROR, "load/", null,
                    null, "LOAD candidate exceeds the descriptor validation limit.",
                    "Split or reduce the LOAD descriptor set."));
            return false;
        }
        LoadScenarioLoader loader = new LoadScenarioLoader(packageRoot);
        List<Path> templates = new ArrayList<Path>();
        for (Path file : yamlFiles) {
            String logical = logicalPath(packageRoot, file);
            if (logical.startsWith("load/templates/") || logical.startsWith("load/bindings/")) {
                if (logical.startsWith("load/templates/")) templates.add(file);
                continue;
            }
            LoadScenario scenario = loader.load(file);
            if (scenario.policyOnly() || !scenario.hasWorkloads()) {
                dependencies.add(new DependencyReference(DependencyKind.LOAD_OVERLAY, packageId, environment,
                        logical, logical, fileDigest(file), "policy-only-not-runnable", null, ReferenceCertainty.KNOWN));
                continue;
            }
            att.load.LoadRunCoordinator.resolveAndValidateTargets(packageRoot, effective, scenario);
            dependencies.add(new DependencyReference(DependencyKind.PACKAGE_RESOURCE, packageId, environment,
                    logical, logical, fileDigest(file), "load-scenario", null, ReferenceCertainty.KNOWN));
            for (Path overlay : scenario.testdataDescriptors()) {
                String overlayPath = logicalPath(packageRoot, overlay);
                dependencies.add(new DependencyReference(DependencyKind.LOAD_OVERLAY, packageId, environment,
                        null, overlayPath, fileDigest(overlay), "testdata-whole-descriptor-replacement", logical,
                        ReferenceCertainty.KNOWN));
            }
            for (String id : att.testdata.TestdataSyntax.references(scenario.toMap()))
                dependencies.add(new DependencyReference(DependencyKind.TESTDATA, packageId, environment,
                        id, null, null, "load-input", logical, ReferenceCertainty.KNOWN));
        }
        if (templates.size() > MAX_LOAD_TEMPLATE_DESCRIPTORS) {
            diagnostics.add(diagnostic("ATT-LOAD-TEMPLATE-LIMIT", Severity.ERROR, "load/templates/", null,
                    null, "Quick LOAD Template candidate exceeds the validation limit.",
                    "Reduce the Quick LOAD Template catalog."));
            return false;
        }
        att.load.LoadTemplateCatalog catalog = new att.load.LoadTemplateCatalog(packageRoot);
        for (Path template : templates) {
            String logical = logicalPath(packageRoot, template);
            catalog.load(logical);
            dependencies.add(new DependencyReference(DependencyKind.LOAD_OVERLAY, packageId, environment,
                    logical, logical, fileDigest(template), "quick-load-policy-template-not-runnable", null,
                    ReferenceCertainty.KNOWN));
        }
        // This validates both model policy branches without creating a Run-capable target.
        loader.loadQuickLoadPolicy("virtualUsers");
        loader.loadQuickLoadPolicy("arrivalRate");
        return true;
    }

    private static void collectEffectiveBindings(String packageId, String environment, Path runtimeRoot,
                                                FrameworkConfig config,
                                                List<DependencyReference> output) throws Exception {
        for (att.config.ToolConfig tool : config.tools().values())
            addConfigBinding(output, packageId, environment, runtimeRoot, tool.sourceFile(), tool.key(), "tool-descriptor");
        for (att.config.DbHelperConfig helper : config.dbHelpers().values())
            addConfigBinding(output, packageId, environment, runtimeRoot, helper.sourceFile(), helper.id(), "db-helper");
        for (att.config.MqHelperConfig helper : config.mqHelpers().values())
            addConfigBinding(output, packageId, environment, runtimeRoot, helper.sourceFile(), helper.id(), "mq-helper");
        for (att.config.HttpHelperConfig helper : config.httpHelpers().values())
            addLocatedHelperBinding(output, packageId, environment, runtimeRoot, helper.id(), "config/httphelpers", "http-helper");
        for (att.config.SshHelperConfig helper : config.sshHelpers().values())
            addLocatedHelperBinding(output, packageId, environment, runtimeRoot, helper.id(), "config/sshhelpers", "ssh-helper");
    }

    private static void addLocatedHelperBinding(List<DependencyReference> output, String packageId,
                                                String environment, Path runtimeRoot, String id,
                                                String directory, String relationship) throws Exception {
        Path root = runtimeRoot.resolve(directory).normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            output.add(new DependencyReference(DependencyKind.HELPER, packageId, environment, id,
                    null, null, relationship, null, ReferenceCertainty.UNKNOWN));
            return;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Runtime helper descriptor root is unsafe");
        try (Stream<Path> paths = Files.walk(root, 16)) {
            for (Path file : (Iterable<Path>) paths.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(candidate) && isYaml(candidate))::iterator) {
                Object value = YamlSupport.load(file);
                if (value instanceof Map && id.equals(text(((Map<?, ?>) value).get("id")))) {
                    output.add(new DependencyReference(DependencyKind.HELPER, packageId, environment, id,
                            logicalPath(runtimeRoot, file), fileDigest(file), relationship, null, ReferenceCertainty.KNOWN));
                    return;
                }
            }
        }
        output.add(new DependencyReference(DependencyKind.HELPER, packageId, environment, id,
                null, null, relationship, null, ReferenceCertainty.UNKNOWN));
    }

    private static void addConfigBinding(List<DependencyReference> output, String packageId, String environment,
                                         Path runtimeRoot, Path sourceFile, String id, String relationship) throws Exception {
        String logical = sourceFile == null ? null : logicalPath(runtimeRoot, sourceFile);
        String fingerprint = sourceFile == null ? null : fileDigest(sourceFile);
        DependencyKind kind = relationship.endsWith("descriptor") ? DependencyKind.TOOL : DependencyKind.HELPER;
        output.add(new DependencyReference(kind, packageId, environment, id, logical, fingerprint,
                relationship, null, sourceFile == null ? ReferenceCertainty.UNKNOWN : ReferenceCertainty.KNOWN));
    }

    private static void collectConfigBindings(Path runtimeRoot, Path configFile,
                                             List<DependencyReference> output) throws Exception {
        Map<?, ?> root = objectMap(YamlSupport.load(configFile));
        Object environment = root.get("environment");
        if (environment instanceof String) output.add(new DependencyReference(DependencyKind.ENVIRONMENT_BINDING,
                null, (String) environment, null, "config/config.yaml", fileDigest(configFile),
                "global-default", null, ReferenceCertainty.KNOWN));
        for (String directory : Arrays.asList("config/tools", "config/toolgroups", "config/tool-groups",
                "config/dbhelpers", "config/mqhelpers", "config/httphelpers", "config/sshhelpers",
                "config/environments")) {
            Path path = runtimeRoot.resolve(directory);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Runtime Config descriptor root is unsafe");
            try (Stream<Path> files = Files.walk(path, 16)) {
                for (Path file : (Iterable<Path>) files.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(candidate) && isYaml(candidate))::iterator) {
                    output.add(new DependencyReference(DependencyKind.ENVIRONMENT_BINDING, null, null,
                            file.getFileName().toString(), logicalPath(runtimeRoot, file), fileDigest(file),
                            "candidate-config-descriptor", null, ReferenceCertainty.KNOWN));
                }
            }
        }
        output.add(new DependencyReference(DependencyKind.ENGINE_SCHEMA, null, null,
                Version.CONFIG_SCHEMA, null, SchemaFiles.registryDigest(), "installed-engine-schema", null,
                ReferenceCertainty.KNOWN));
    }

    private static void collectManifestDependencies(Path packageRoot, String packageId,
                                                    List<DependencyReference> output) throws Exception {
        PackageConfig manifest = PackageConfig.load(packageRoot);
        if (!manifest.present()) return;
        Path file = packageRoot.resolve(PackageConfig.FILE_NAME);
        output.add(new DependencyReference(DependencyKind.MANIFEST, packageId, null,
                "package.yaml", "package.yaml", fileDigest(file), "package-manifest", null,
                ReferenceCertainty.KNOWN));
        int layer = 0;
        for (List<Path> paths : manifest.descriptorLayers()) {
            for (Path descriptor : paths) {
                TestdataDescriptor parsed = new TestdataRegistry(packageRoot, Collections.singletonList(descriptor),
                        Collections.<Path>emptyList()).validateAll().values().iterator().next();
                output.add(new DependencyReference(DependencyKind.TESTDATA, packageId, null,
                        parsed.id(), logicalPath(packageRoot, descriptor), fileDigest(descriptor),
                        "manifest-layer-" + layer, null, ReferenceCertainty.KNOWN));
            }
            layer++;
        }
    }

    private static void collectFileDependencies(Path packageRoot, String packageId, String environment,
                                               FrameworkConfig config,
                                               List<DependencyReference> output) throws Exception {
        StageTemplateLoader templates = new StageTemplateLoader(packageRoot, config.templatesRoot(), false);
        FlowRegistry flows = new FlowRegistry(packageRoot, config.templatesRoot(), false);
        FileExpressionResolver resolver = new FileExpressionResolver(packageRoot);
        Map<String, DependencyReference> found = new LinkedHashMap<String, DependencyReference>();
        for (String reference : templates.paths()) collectFilePlans(packageRoot, packageId, environment,
                resolver.snapshotFor(templates.load(reference), flows), found);
        for (String flowId : flows.ids()) {
            FlowDefinition flow = flows.get(flowId);
            if (flow == null) continue;
            StageTemplate body = new StageTemplate(flow.name(), flow.directory(), flow.actions(),
                    flow.templateSchemaVersion(), flow.directory().resolve("flow.yaml"));
            collectFilePlans(packageRoot, packageId, environment, resolver.snapshotFor(body, flows), found);
        }
        output.addAll(found.values());
    }

    private static void collectFilePlans(Path packageRoot, String packageId, String environment,
                                         FileExpressionResolver.FileExpressionSnapshot snapshot,
                                         Map<String, DependencyReference> output) throws Exception {
        if (snapshot == null || snapshot.projectRoot() == null || !snapshot.projectRoot().equals(packageRoot))
            throw new IllegalArgumentException("Compiled File-expression snapshot does not belong to this Package");
        for (FileExpressionResolver.CompiledFileReference reference : snapshot.references()) {
            String logical = reference.logicalPath();
            String callSite = boundedCallSite(reference.callSite() + " -> &{" + reference.authoredPath() + "}");
            String fingerprint = fileDigest(packageRoot.resolve(logical));
            String key = logical + "\n" + fingerprint + "\n" + callSite;
            output.putIfAbsent(key, new DependencyReference(DependencyKind.FILE, packageId, environment,
                    reference.authoredPath(), logical, fingerprint, "canonical-file-expression-plan", callSite,
                    ReferenceCertainty.KNOWN));
        }
    }

    private static String boundedCallSite(String value) throws Exception {
        if (value.length() <= 512) return value;
        return value.substring(0, 440) + "#" + digest(value).substring(0, 64);
    }

    private static void collectPackageResourceDependencies(Path packageRoot, Path configFile,
                                                           FrameworkConfig effectiveConfig,
                                                           String packageId, String environment,
                                                           List<DependencyReference> output) throws Exception {
        try (PackageResourceInspector inspector = new PackageResourceInspector(packageRoot, configFile, environment,
                Collections.<String>emptySet(), 64 * 1024, 1024 * 1024, false,
                false, "safe-package-files", Collections.<String>emptyList(), true,
                Collections.<String>emptyList(), Collections.<String>emptyList())) {
            for (String type : Arrays.asList("case", "template", "flow", "tool")) {
                int offset = 0;
                while (true) {
                    Map<String, Object> page = inspector.inspect("list", type, null, null, offset, 100);
                    Object raw = page.get("items");
                    if (!(raw instanceof List)) break;
                    List<?> items = (List<?>) raw;
                    for (Object item : items) if (item instanceof Map) {
                        Map<?, ?> resource = (Map<?, ?>) item;
                        String resourceId = text(resource.get("resourceId"));
                        String logicalId = text(resource.get("logicalId"));
                        Object refs = resource.get("references");
                        if (!(refs instanceof List)) continue;
                        for (Object ref : (List<?>) refs) if (ref instanceof Map) {
                            Map<?, ?> value = (Map<?, ?>) ref;
                            String refType = text(value.get("type"));
                            String refId = text(value.get("logicalId"));
                            output.add(new DependencyReference("tool".equalsIgnoreCase(refType) ? DependencyKind.TOOL : DependencyKind.PACKAGE_RESOURCE,
                                    packageId, environment, refId, null, null,
                                    resourceId + "->" + refType + ":" + refId, logicalId, ReferenceCertainty.KNOWN));
                        }
                    }
                    if (items.size() < 100) break;
                    offset += items.size();
                    if (offset > 20000) throw new IllegalArgumentException("Package dependency inventory exceeds its limit");
                }
            }
        }
        collectTestcaseDataReferences(packageRoot, effectiveConfig, packageId, environment, output);
    }

    private static void collectTestcaseDataReferences(Path packageRoot, FrameworkConfig config,
                                                      String packageId, String environment,
                                                      List<DependencyReference> output) throws Exception {
        Path configured = config.testcasesRoot().isAbsolute() ? config.testcasesRoot()
                : packageRoot.resolve(config.testcasesRoot()).normalize();
        if (!Files.exists(configured, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(configured) || !Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Package Testcase root is unsafe");
        List<Path> workbooks = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(configured, 32)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .sorted().forEach(workbooks::add);
        }
        if (workbooks.size() > 500) throw new IllegalArgumentException("Package Testcase workbook count exceeds the validation limit");
        TestdataRegistry registry = new TestdataRegistry(packageRoot, config.testdataDescriptors(), Collections.<Path>emptyList());
        SuiteConfigResolver resolver = new SuiteConfigResolver(packageRoot, config);
        for (Path workbook : workbooks) {
            FrameworkConfig suiteConfig = resolver.resolve(workbook);
            for (TestCase testCase : new ExcelTestSuiteLoader(suiteConfig).load(workbook)) {
                String callSite = logicalPath(packageRoot, workbook) + ":" + testCase.sheetName() + ":" + testCase.rowNumber();
                addTestdataUses(packageRoot, registry, packageId, environment, testCase.caseData(), callSite + ":inputs", output);
                for (StageCaseData stage : testCase.stages().values())
                    addTestdataUses(packageRoot, registry, packageId, environment, stage.values(),
                            callSite + ":stage:" + stage.key(), output);
            }
        }
    }

    private static void addTestdataUses(Path packageRoot, TestdataRegistry registry, String packageId, String environment,
                                        Object value, String callSite,
                                        List<DependencyReference> output) throws Exception {
        for (String id : att.testdata.TestdataSyntax.references(value)) {
            try {
                TestdataDescriptor descriptor = registry.resolve(id);
                output.add(new DependencyReference(DependencyKind.TESTDATA, packageId, environment,
                        id, descriptor.source() == null ? null : logicalPath(packageRoot, descriptor.source()),
                        descriptor.source() == null ? null : fileDigest(descriptor.source()),
                        "testcase-input", callSite, ReferenceCertainty.KNOWN));
            } catch (Exception unavailable) {
                output.add(new DependencyReference(DependencyKind.TESTDATA, packageId, environment,
                        id, null, null, "testcase-input-unresolved", callSite, ReferenceCertainty.UNKNOWN));
            }
        }
    }

    private static List<ValidationDiagnostic> packageDiagnostics(PackageValidator.ValidationSummary summary, int remaining) {
        List<ValidationDiagnostic> result = new ArrayList<ValidationDiagnostic>();
        if (remaining <= 0) return result;
        for (Diagnostic diagnostic : summary.diagnostics) {
            if (result.size() >= remaining) break;
            result.add(diagnostic(diagnostic.code(), severity(diagnostic.severity().name()),
                    diagnostic.file(), diagnostic.field(), diagnostic.row(),
                    diagnostic.summary(), diagnostic.suggestion()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<ValidationDiagnostic> reportDiagnostics(Map<String, Object> report, int remaining) {
        List<ValidationDiagnostic> result = new ArrayList<ValidationDiagnostic>();
        Object values = report.get("diagnostics");
        if (!(values instanceof List) || remaining <= 0) return result;
        for (Object value : (List<?>) values) {
            if (result.size() >= remaining || !(value instanceof Map)) break;
            Map<String, Object> item = (Map<String, Object>) value;
            result.add(diagnostic(text(item.get("code")), severity(text(item.get("severity"))),
                    text(item.get("file")), text(item.get("field")), integer(item.get("line")),
                    text(item.get("message")), text(item.get("remediation"))));
        }
        return result;
    }

    private static ValidationDiagnostic diagnostic(String code, Severity severity, String path, String field,
                                                   Integer line, String message, String remedy) {
        String logical = path;
        if (logical != null && logical.startsWith("/")) logical = basenameOrRelative(logical);
        return new ValidationDiagnostic(code == null ? "ATT-SOURCE-VALIDATION" : code,
                severity == null ? Severity.ERROR : severity, logical, field, line,
                message == null ? "Source candidate validation failed." : message,
                remedy == null ? "Correct the candidate and validate it again." : remedy);
    }

    private static Severity severity(String value) {
        if ("WARNING".equalsIgnoreCase(value)) return Severity.WARNING;
        if ("INFO".equalsIgnoreCase(value)) return Severity.INFO;
        return Severity.ERROR;
    }

    private static boolean hasErrors(List<ValidationDiagnostic> values) {
        for (ValidationDiagnostic value : values) if (value.severity == Severity.ERROR) return true;
        return false;
    }

    private static boolean hasUnknown(List<DependencyReference> values) {
        for (DependencyReference value : values) if (value.certainty == ReferenceCertainty.UNKNOWN) return true;
        return false;
    }

    private static List<ValidationDiagnostic> boundedDiagnostics(List<ValidationDiagnostic> values) {
        if (values.size() <= MAX_DIAGNOSTICS) return Collections.unmodifiableList(new ArrayList<ValidationDiagnostic>(values));
        return Collections.unmodifiableList(new ArrayList<ValidationDiagnostic>(values.subList(0, MAX_DIAGNOSTICS)));
    }

    private static List<DependencyReference> deduplicate(List<DependencyReference> values) {
        Map<String, DependencyReference> unique = new LinkedHashMap<String, DependencyReference>();
        for (DependencyReference value : values) {
            String key = value.packageId + "\n" + value.environment + "\n" + value.kind + "\n" + value.logicalId
                    + "\n" + value.canonicalPath + "\n" + value.relationship + "\n" + value.callSite;
            unique.putIfAbsent(key, value);
        }
        return Collections.unmodifiableList(new ArrayList<DependencyReference>(unique.values()));
    }

    /** Value-free, root-independent semantic projection for #335 and replay identity comparisons. */
    private static Map<String, String> semanticFingerprints(Path packageRoot, Path runtimeRoot,
                                                              FrameworkConfig effective,
                                                              Map<String, ResolutionOrigin> origins,
                                                              List<DependencyReference> dependencies) throws Exception {
        Map<String, String> result = new TreeMap<String, String>();
        putSemantic(result, "environment", effective.environment());
        putSemantic(result, "timeoutMs", Integer.valueOf(effective.timeoutMs()));
        putSemantic(result, "resources.templatesRoot", portablePath(packageRoot, effective.templatesRoot()));
        putSemantic(result, "resources.testcaseRoot", portablePath(packageRoot, effective.testcasesRoot()));
        putSemantic(result, "report.mode", effective.report().mode());
        putSemantic(result, "report.fileNamePattern", effective.report().fileNamePattern());
        putSemantic(result, "report.columns", effective.report().columns());
        putSemantic(result, "report.junit.caseLogEmbedThresholdBytes", effective.report().junitCaseLogEmbedThresholdBytes());
        putSemantic(result, "report.html.caseLogInlineLimitBytes", effective.report().htmlCaseLogInlineLimitBytes());
        putSemantic(result, "run.defaultMode", effective.run().defaultMode());
        putSemantic(result, "run.timestampFormat", effective.run().timestampFormat());
        putSemantic(result, "run.runIdFormat", effective.run().runIdFormat());
        putSemantic(result, "run.debugIdFormat", effective.run().debugIdFormat());
        putSemantic(result, "caseIdColumn", effective.caseIdColumn());
        putSemantic(result, "tagsColumn", effective.tagsColumn());
        putSemantic(result, "headerRows", Integer.valueOf(effective.headerRows()));
        putSemantic(result, "xml.namespaceMode", effective.xmlNamespaceMode());
        putSemantic(result, "caseLog.yamlAnchors", Boolean.valueOf(effective.caseLogYamlAnchors()));
        putSemantic(result, "processOutput.memoryLimitBytes", effective.processOutput().memoryLimitBytes());
        putSemantic(result, "processOutput.artifactLimitBytes", effective.processOutput().artifactLimitBytes());
        List<String> testdataPaths = new ArrayList<String>();
        for (Path descriptor : effective.testdataDescriptors()) testdataPaths.add(portablePath(packageRoot, descriptor));
        Collections.sort(testdataPaths);
        putSemantic(result, "testdata.layers", testdataPaths);
        putSemantic(result, "tools.ids", new TreeSet<String>(effective.tools().keySet()));
        putSemantic(result, "dbhelpers.ids", new TreeSet<String>(effective.dbHelpers().keySet()));
        putSemantic(result, "mqhelpers.ids", new TreeSet<String>(effective.mqHelpers().keySet()));
        putSemantic(result, "sshhelpers.ids", new TreeSet<String>(effective.sshHelpers().keySet()));
        putSemantic(result, "httphelpers.ids", new TreeSet<String>(effective.httpHelpers().keySet()));
        for (Map.Entry<String, ResolutionOrigin> entry : origins.entrySet())
            putSemantic(result, "origin:" + entry.getKey(), entry.getValue().name());

        for (DependencyReference dependency : dependencies) {
            if (dependency.canonicalPath == null) continue;
            if (dependency.kind != DependencyKind.TOOL && dependency.kind != DependencyKind.HELPER
                    && dependency.kind != DependencyKind.TESTDATA && dependency.kind != DependencyKind.LOAD_OVERLAY
                    && dependency.kind != DependencyKind.FILE) continue;
            Path sourceRoot = dependency.kind == DependencyKind.TOOL || dependency.kind == DependencyKind.HELPER
                    ? runtimeRoot : packageRoot;
            Path source = sourceRoot.resolve(dependency.canonicalPath).normalize();
            if (!source.startsWith(sourceRoot)) throw new IOException("Candidate semantic dependency escapes its source root");
            String sourceSemantic = dependency.kind == DependencyKind.FILE ? fileDigest(source)
                    : isYaml(source) ? canonicalYaml(YamlSupport.load(source)) : fileDigest(source);
            String key = "descriptor:" + dependency.kind.name() + ":" + safeKey(dependency.logicalId)
                    + ":" + safeKey(dependency.relationship) + ":" + safeKey(dependency.callSite);
            putSemantic(result, key, sourceSemantic);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String digestFingerprints(String environment, Map<String, String> fingerprints) throws Exception {
        StringBuilder canonical = new StringBuilder("environment=").append(environment).append('\n');
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(fingerprints).entrySet())
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        return digest(canonical.toString());
    }

    private static void putSemantic(Map<String, String> target, String key, Object value) throws Exception {
        target.put(key, digest(canonical(value)));
    }

    private static String canonicalYaml(Object value) throws Exception {
        return canonical(value);
    }

    private static String canonical(Object value) throws Exception {
        if (value == null) return "null";
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (secretKey(key)) sorted.put(key, entry.getValue() == null ? null
                        : "<protected:" + digest(canonical(entry.getValue())) + ">");
                else if (!"description".equalsIgnoreCase(key)) sorted.put(key, entry.getValue());
            }
            StringBuilder result = new StringBuilder("{");
            for (Map.Entry<String, Object> entry : sorted.entrySet())
                result.append(canonical(entry.getKey())).append(':').append(canonical(entry.getValue())).append(';');
            return result.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : (Iterable<?>) value) result.append(canonical(item)).append(';');
            return result.append(']').toString();
        }
        if (value instanceof Path) return ((Path) value).normalize().toString().replace('\\', '/');
        if (value instanceof Number) return new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        String text = String.valueOf(value);
        return text.length() + ":" + text;
    }

    private static boolean secretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("credential") || normalized.contains("privatekey")
                || normalized.contains("apikey") || normalized.contains("authorization");
    }

    private static String portablePath(Path root, Path path) {
        Path normalized = path.normalize();
        Path boundary = root.toAbsolutePath().normalize();
        if (normalized.isAbsolute() && normalized.startsWith(boundary))
            return boundary.relativize(normalized).toString().replace('\\', '/');
        return normalized.toString().replace('\\', '/');
    }

    private static String safeKey(String value) {
        return value == null ? "" : value.replace('\\', '/').replace('\n', '_').replace('\r', '_');
    }

    private static ResolutionOrigin origin(String value) {
        if ("package".equalsIgnoreCase(value)) return ResolutionOrigin.PACKAGE_MANIFEST;
        if ("request".equalsIgnoreCase(value) || "context".equalsIgnoreCase(value)) return ResolutionOrigin.REQUEST;
        if ("environment".equalsIgnoreCase(value)) return ResolutionOrigin.ENVIRONMENT_PROFILE;
        if ("global".equalsIgnoreCase(value)) return ResolutionOrigin.GLOBAL_CONFIG;
        if ("runtime-default".equalsIgnoreCase(value)) return ResolutionOrigin.RUNTIME_DEFAULT;
        return ResolutionOrigin.UNKNOWN;
    }

    private static RevisionSet withResolvedRevisions(RevisionSet source, String packageId, String environment,
                                                     String manifestDigest, String effectiveDigest,
                                                     String environmentDigest, String schemaDigest,
                                                     String configDigest) {
        RevisionIdentity registration = source.packageRegistration;
        RevisionIdentity business = source.businessSource;
        RevisionIdentity packageSource = source.packageSourceRevision;
        RevisionIdentity execution = source.executionRevisionDigest;
        RevisionIdentity changeSet = source.changeSet;
        RevisionIdentity manifest = source.manifest != null ? source.manifest
                : RevisionIdentity.of(RevisionKind.PACKAGE_MANIFEST, packageId, manifestDigest);
        RevisionIdentity config = source.configSource != null ? source.configSource
                : RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, "central-runtime-config", configDigest);
        RevisionIdentity resolved = RevisionIdentity.of(RevisionKind.EFFECTIVE_CONFIG, packageId + "@" + environment, effectiveDigest);
        RevisionIdentity env = new RevisionIdentity(RevisionKind.ENVIRONMENT, environment, packageId, environmentDigest);
        RevisionIdentity schema = source.engineSchema != null ? source.engineSchema
                : RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA, "installed-engine", schemaDigest);
        return new RevisionSet(registration, business, changeSet, manifest, config, resolved,
                env, schema, source.scheduleSnapshot, packageSource, execution);
    }

    private static RevisionSet withExecutionRevisions(RevisionSet source, RevisionIdentity businessSource,
                                                      RevisionIdentity packageSource, RevisionIdentity execution) {
        return new RevisionSet(source.packageRegistration, businessSource, source.changeSet, source.manifest,
                source.configSource, source.effectiveConfig, source.environment, source.engineSchema,
                source.scheduleSnapshot, packageSource, execution);
    }

    private static RevisionSet withPackageSourceRevision(RevisionSet source, String packageId, String digest) {
        RevisionIdentity packageSource = RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE, packageId, digest);
        RevisionIdentity businessSource = RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, packageId, digest);
        return new RevisionSet(source.packageRegistration, businessSource, source.changeSet, source.manifest,
                source.configSource, source.effectiveConfig, source.environment, source.engineSchema,
                source.scheduleSnapshot, packageSource, source.executionRevisionDigest);
    }

    private static Map<?, ?> objectMap(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Runtime Config must be a YAML mapping");
        return (Map<?, ?>) value;
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static String text(Object value) { return value instanceof String ? (String) value : null; }
    private static Integer integer(Object value) { return value instanceof Number ? ((Number) value).intValue() : null; }

    private static Path canonicalDirectory(Path path, String name) throws IOException {
        if (path == null || Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException(name + " is unavailable");
        return path.toRealPath();
    }

    private static Path canonicalFile(Path path, String name) throws IOException {
        if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(path)) throw new IOException(name + " is unavailable");
        return path.toRealPath();
    }

    private static String logicalPath(Path root, Path file) throws IOException {
        Path canonicalRoot = root.toRealPath();
        Path canonicalFile = file.toRealPath();
        if (!canonicalFile.startsWith(canonicalRoot)) throw new IOException("Candidate dependency escapes its source root");
        return canonicalRoot.relativize(canonicalFile).toString().replace('\\', '/');
    }

    private static String logicalPath(Path root, String relative) {
        if (relative == null || relative.trim().isEmpty()) return null;
        return relative.replace('\\', '/');
    }

    private static String basenameOrRelative(String value) {
        String normalized = value.replace('\\', '/');
        int config = normalized.indexOf("/config/");
        if (config >= 0) return normalized.substring(config + 1);
        int templates = normalized.indexOf("/templates/");
        if (templates >= 0) return normalized.substring(templates + 1);
        int testcase = normalized.indexOf("/testcase/");
        if (testcase >= 0) return normalized.substring(testcase + 1);
        int load = normalized.indexOf("/load/");
        if (load >= 0) return normalized.substring(load + 1);
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static String fileDigest(Path path) throws Exception {
        if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Candidate dependency is unavailable");
        if (Files.size(path) > 64L * 1024L * 1024L) throw new IOException("Candidate dependency exceeds its validation limit");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte item : hash.digest()) result.append(String.format("%02x", item & 255));
        return result.toString();
    }

    private static String digest(String value) throws Exception { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(value)) result.append(String.format("%02x", item & 255));
        return result.toString();
    }

    private static Set<String> set(String... values) { return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values))); }
}
