package att.validation;

import att.server.api.source.SourceManagementContracts.CandidateValidation;
import att.server.api.source.SourceManagementContracts.DependencyKind;
import att.server.api.source.SourceManagementContracts.DependencyReference;
import att.server.api.source.SourceManagementContracts.Domain;
import att.server.api.source.SourceManagementContracts.EffectiveResolution;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import att.server.api.source.SourceManagementContracts.ValidationState;
import att.config.SuiteConfigResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SourceCandidateValidatorTest {
    private static final String FINGERPRINT = repeat('a');
    @TempDir Path temp;
    private Path runtimeRoot;
    private Path configFile;
    private Path firstPackage;
    private SourceCandidateValidator validator;

    @BeforeEach void setUp() throws Exception {
        runtimeRoot = Files.createDirectories(temp.resolve("runtime"));
        Files.createDirectories(runtimeRoot.resolve("config"));
        att.TestSchemas.install(runtimeRoot);
        configFile = runtimeRoot.resolve("config/config.yaml");
        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\n");
        firstPackage = Files.createDirectories(temp.resolve("packages/first"));
        att.TestSchemas.install(firstPackage);
        Files.createDirectories(firstPackage.resolve("templates"));
        Files.createDirectories(firstPackage.resolve("flows"));
        Files.createDirectories(firstPackage.resolve("testcase"));
        write(firstPackage.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: SIT\ntestdata: {descriptors: [testdata/customers.yaml]}\n");
        write(firstPackage.resolve("testdata/customers.yaml"),
                "schemaVersion: att-testdata/v1.0\nid: customers\nrecords: [{id: first-package}]\n");
        validator = new SourceCandidateValidator();
    }

    @Test void validatesWholePackageAndKeepsSameNamedTestdataReferencesPackageScoped() throws Exception {
        Path secondPackage = Files.createDirectories(temp.resolve("packages/second"));
        att.TestSchemas.install(secondPackage);
        Files.createDirectories(secondPackage.resolve("templates"));
        Files.createDirectories(secondPackage.resolve("flows"));
        Files.createDirectories(secondPackage.resolve("testcase"));
        write(secondPackage.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: SIT\ntestdata: {descriptors: [testdata/customers.yaml]}\n");
        write(secondPackage.resolve("testdata/customers.yaml"),
                "schemaVersion: att-testdata/v1.0\nid: customers\nrecords: [{id: second-package}]\n");

        CandidateValidation first = validator.validatePackage(firstPackage, configFile, runtimeRoot,
                "first", revisions("first"), FINGERPRINT);
        CandidateValidation second = validator.validatePackage(secondPackage, configFile, runtimeRoot,
                "second", revisions("second"), FINGERPRINT);
        assertEquals(Domain.PACKAGE_SOURCE, first.domain);
        assertEquals(ValidationState.VALID, first.state, describe(first));
        assertEquals(ValidationState.VALID, second.state, describe(second));
        assertTrue(first.dependencies.stream().anyMatch(ref -> ref.kind == DependencyKind.TESTDATA
                && "first".equals(ref.packageId) && "customers".equals(ref.logicalId)));
        assertTrue(second.dependencies.stream().anyMatch(ref -> ref.kind == DependencyKind.TESTDATA
                && "second".equals(ref.packageId) && "customers".equals(ref.logicalId)));

        EffectiveResolution resolution = validator.resolveEffective("first", "SIT", firstPackage,
                configFile, runtimeRoot, revisions("first"));
        assertTrue(resolution.complete, resolution.diagnostics.toString());
        assertTrue(resolution.dependencies.stream().anyMatch(ref -> ref.kind == DependencyKind.MANIFEST
                && "first".equals(ref.packageId) && "package.yaml".equals(ref.logicalId)));
        assertTrue(resolution.dependencies.stream().allMatch(ref -> ref.packageId == null || "first".equals(ref.packageId)));
    }

    @Test void rejectsCentralConfigOutsideOnlineHotActivationAllowlist() throws Exception {
        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: SIT\noutputDirectory: /srv/worker-output\nenvironments:\n  SIT: {}\n");
        CandidateValidation result = validator.validateCentralConfig(configFile, runtimeRoot,
                revisions("central"), FINGERPRINT);
        assertEquals(Domain.CENTRAL_RUNTIME_CONFIG, result.domain);
        assertEquals(ValidationState.INVALID, result.state);
        assertTrue(result.diagnostics.stream().anyMatch(item -> "ATT-CONFIG-ONLINE-UNSUPPORTED".equals(item.code)
                && "outputDirectory".equals(item.field)));
    }

    @Test void allowsUnchangedDeploymentOutputDirectoryWhenOtherConfigFieldsChange() throws Exception {
        Path baseline = temp.resolve("baseline/config.yaml");
        write(baseline, "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\noutputDirectory: output\n");
        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\noutputDirectory: output\ntimeoutMs: 2400\n");
        CandidateValidation result = validator.validateCentralConfig(configFile, runtimeRoot, baseline,
                revisions("central"), FINGERPRINT);
        assertEquals(ValidationState.VALID, result.state, describe(result));

        for (String candidate : Arrays.asList(
                "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\noutputDirectory: reports\n",
                "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\n")) {
            write(configFile, candidate);
            CandidateValidation changed = validator.validateCentralConfig(configFile, runtimeRoot, baseline,
                    revisions("central"), FINGERPRINT);
            assertEquals(ValidationState.INVALID, changed.state, describe(changed));
            assertTrue(changed.diagnostics.stream().anyMatch(item -> "ATT-CONFIG-ONLINE-UNSUPPORTED".equals(item.code)
                    && "outputDirectory".equals(item.field)), describe(changed));
        }

        write(baseline, "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\n");
        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\noutputDirectory: output\n");
        CandidateValidation added = validator.validateCentralConfig(configFile, runtimeRoot, baseline,
                revisions("central"), FINGERPRINT);
        assertEquals(ValidationState.INVALID, added.state, describe(added));
    }

    @Test void secretReferenceChangesHaveDistinctSafeSemanticFingerprints() throws Exception {
        Path helperDirectory = Files.createDirectories(runtimeRoot.resolve("config/httphelpers"));
        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                + "httphelpers: [config/httphelpers/payments.yaml]\n");
        Path helper = helperDirectory.resolve("payments.yaml");
        write(helper, "schemaVersion: att-httphelper/v1.1\nid: payments\nbaseUrl: https://api.example.test\n"
                + "auth: {type: bearer, token: secret-ref-primary}\n");
        EffectiveResolution first = validator.resolveEffective("first", "SIT", firstPackage,
                configFile, runtimeRoot, revisions("first"));
        write(helper, "schemaVersion: att-httphelper/v1.1\nid: payments\nbaseUrl: https://api.example.test\n"
                + "auth: {type: bearer, token: secret-ref-secondary}\n");
        EffectiveResolution second = validator.resolveEffective("first", "SIT", firstPackage,
                configFile, runtimeRoot, revisions("first"));
        assertNotEquals(first.effectiveFingerprint, second.effectiveFingerprint);
        String safeProjection = first.semanticFingerprints.toString() + first.dependencies.toString();
        assertFalse(safeProjection.contains("secret-ref-primary"), safeProjection);
        assertTrue(first.complete, first.diagnostics.toString());
    }

    @Test void effectiveIdentityIncludesSameIdHelperSemanticsAndWorkbookParsingRules() throws Exception {
        Path helperDirectory = Files.createDirectories(runtimeRoot.resolve("config/httphelpers"));
        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                + "httphelpers: [config/httphelpers/payments.yaml]\n");
        Path helper = helperDirectory.resolve("payments.yaml");
        write(helper, "schemaVersion: att-httphelper/v1.1\nid: payments\nbaseUrl: https://one.example.test\n"
                + "auth: {type: none}\n");
        String helperIdentity = new att.config.FrameworkConfigLoader()
                .loadDiscovery(configFile, runtimeRoot, firstPackage, null).effectiveConfiguration().identity();
        write(helper, "# display-only comment\nschemaVersion: att-httphelper/v1.1\nid: payments\n"
                + "baseUrl: https://one.example.test\nauth: {type: none}\n");
        String commentIdentity = new att.config.FrameworkConfigLoader()
                .loadDiscovery(configFile, runtimeRoot, firstPackage, null).effectiveConfiguration().identity();
        assertEquals(helperIdentity, commentIdentity);
        write(helper, "schemaVersion: att-httphelper/v1.1\nid: payments\nbaseUrl: https://two.example.test\n"
                + "auth: {type: none}\n");
        String endpointIdentity = new att.config.FrameworkConfigLoader()
                .loadDiscovery(configFile, runtimeRoot, firstPackage, null).effectiveConfiguration().identity();
        assertNotEquals(helperIdentity, endpointIdentity);

        Path workbook = firstPackage.resolve("testcase/orders.xlsx");
        Path sidecar = firstPackage.resolve("testcase/orders.yaml");
        write(sidecar, "schemaVersion: att-sidecar/v2.2\nid: orders\n"
                + "excel: {sheet: Cases, caseId: Case ID, tags: Tags, headerRows: 1}\n"
                + "stages: [{key: invoke, template: Payment}]\n");
        att.config.FrameworkConfig global = new att.config.FrameworkConfigLoader()
                .loadDiscovery(configFile, runtimeRoot, firstPackage, null);
        String workbookIdentity = new SuiteConfigResolver(firstPackage, global).resolve(workbook)
                .effectiveConfiguration().identity();
        write(sidecar, "schemaVersion: att-sidecar/v2.2\nid: orders\n"
                + "excel: {sheet: Cases, caseId: External ID, tags: Tags, headerRows: 2}\n"
                + "stages: [{key: invoke, template: Payment}]\n");
        String changedWorkbookIdentity = new SuiteConfigResolver(firstPackage, global).resolve(workbook)
                .effectiveConfiguration().identity();
        assertNotEquals(workbookIdentity, changedWorkbookIdentity);
    }

    @Test void unchangedTemplateInboundFileReferenceIsValidatedAgainstWholeCandidate() throws Exception {
        Path templateDirectory = Files.createDirectories(firstPackage.resolve("templates/lookup"));
        write(templateDirectory.resolve("template.yaml"), "schemaVersion: att-template/v3.6\nname: Lookup\ndescription: Lookup\nactions:\n"
                + "  read: {type: assign, name: payload, expression: '&{./missing.txt}'}\n");
        CandidateValidation result = validator.validatePackage(firstPackage, configFile, runtimeRoot,
                "first", revisions("first"), FINGERPRINT);
        assertEquals(ValidationState.INVALID, result.state, result.diagnostics.toString());
        assertTrue(result.diagnostics.stream().anyMatch(item -> "ATT-FILE-REFERENCE-INVALID".equals(item.code)
                || "ATT-FILE-REFERENCE-MISSING".equals(item.code)
                || (item.logicalPath != null && item.logicalPath.contains("template.yaml"))), describe(result));
    }

    @Test void effectiveResolutionUsesResolvedManifestAndGlobalDefaultsAndExplicitSelection() throws Exception {
        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: UAT\nenvironments:\n  SIT: {}\n  UAT: {}\n");
        write(firstPackage.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n"
                + "testdata: {descriptors: [testdata/customers.yaml]}\n");
        EffectiveResolution manifestDefault = validator.resolveEffective("first", null, firstPackage,
                configFile, runtimeRoot, revisions("first"));
        EffectiveResolution explicit = validator.resolveEffective("first", "UAT", firstPackage,
                configFile, runtimeRoot, revisions("first"));
        assertEquals("SIT", manifestDefault.environment);
        assertEquals("SIT", manifestDefault.revisions.environment.scopeId);
        assertTrue(manifestDefault.bindings.stream().allMatch(binding -> binding.environment == null
                || "SIT".equals(binding.environment)));
        assertEquals("UAT", explicit.environment);
        assertEquals("UAT", explicit.revisions.environment.scopeId);

        write(firstPackage.resolve("package.yaml"), "schemaVersion: att-package/v1\n"
                + "testdata: {descriptors: [testdata/customers.yaml]}\n");
        EffectiveResolution globalDefault = validator.resolveEffective("first", null, firstPackage,
                configFile, runtimeRoot, revisions("first"));
        assertEquals("UAT", globalDefault.environment);
        assertEquals("UAT", globalDefault.revisions.environment.scopeId);
    }

    @Test void semanticEffectiveFingerprintsIgnoreCommentsAndOverriddenChangesAcrossPackageRoots() throws Exception {
        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: SIT\ntimeoutMs: 2000\nenvironments:\n  SIT: {}\n");
        write(firstPackage.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n"
                + "testdata: {descriptors: [testdata/customers.yaml]}\ndefaults: {timeoutMs: 9000}\n");
        EffectiveResolution original = validator.resolveEffective("first", null, firstPackage,
                configFile, runtimeRoot, revisions("first"));

        write(configFile, "schemaVersion: att-config/v2.13\nenvironment: SIT\ntimeoutMs: 2500\n"
                + "environments:\n  SIT: {}\n# comment only\n");
        EffectiveResolution overridden = validator.resolveEffective("first", null, firstPackage,
                configFile, runtimeRoot, revisions("first"));
        assertEquals(original.effectiveFingerprint, overridden.effectiveFingerprint);
        assertEquals(original.semanticFingerprints.get("timeoutMs"), overridden.semanticFingerprints.get("timeoutMs"));

        Path secondPackage = temp.resolve("packages/copied-package");
        copyTree(firstPackage, secondPackage);
        EffectiveResolution otherRoot = validator.resolveEffective("first", null, secondPackage,
                configFile, runtimeRoot, revisions("first"));
        assertEquals(original.effectiveFingerprint, otherRoot.effectiveFingerprint,
                "temporary candidate roots do not become effective semantics");

        write(secondPackage.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n"
                + "testdata: {descriptors: [testdata/customers.yaml]}\ndefaults: {timeoutMs: 9001}\n");
        EffectiveResolution changed = validator.resolveEffective("first", null, secondPackage,
                configFile, runtimeRoot, revisions("first"));
        assertNotEquals(otherRoot.semanticFingerprints.get("timeoutMs"), changed.semanticFingerprints.get("timeoutMs"));
        assertNotEquals(otherRoot.effectiveFingerprint, changed.effectiveFingerprint);
    }

    @Test void sharedFileDependenciesKeepEveryFlowClosureCallSite() throws Exception {
        Path shared = Files.createDirectories(firstPackage.resolve("templates/shared"));
        write(shared.resolve("request.xml"), "<request>${CASE.caseId}</request>\n");
        Path template = Files.createDirectories(firstPackage.resolve("templates/payment"));
        write(template.resolve("template.yaml"), "schemaVersion: att-template/v3.6\nname: Payment\ndescription: Payment\nactions:\n"
                + "  first: {type: flow, use: payment.first.v1}\n  second: {type: flow, use: payment.first.v1}\n");
        Path firstFlow = Files.createDirectories(firstPackage.resolve("templates/flows/first"));
        write(firstFlow.resolve("flow.yaml"), "schemaVersion: att-flow/v3.6\nid: payment.first.v1\nname: First\ndescription: First\nactions:\n"
                + "  read: {type: log, message: '&{../../shared/request.xml}'}\n"
                + "  repeat: {type: log, message: '&{../../shared/request.xml}'}\n");

        EffectiveResolution result = validator.resolveEffective("first", "SIT", firstPackage,
                configFile, runtimeRoot, revisions("first"));
        java.util.List<DependencyReference> fileRefs = result.dependencies.stream()
                .filter(item -> item.kind == DependencyKind.FILE && "templates/shared/request.xml".equals(item.canonicalPath))
                .collect(java.util.stream.Collectors.toList());
        assertTrue(fileRefs.size() >= 4, fileRefs.stream().map(item -> item.callSite + " -> " + item.canonicalPath)
                .collect(java.util.stream.Collectors.toList()).toString());
        assertTrue(fileRefs.stream().anyMatch(item -> item.callSite != null && item.callSite.contains("flows/first")
                && item.callSite.contains("actions[first]")));
        assertTrue(fileRefs.stream().anyMatch(item -> item.callSite != null && item.callSite.contains("flows/first")
                && item.callSite.contains("actions[second]")));
        assertTrue(fileRefs.stream().map(item -> item.callSite).distinct().count() >= 4,
                fileRefs.stream().map(item -> item.callSite).collect(java.util.stream.Collectors.toList()).toString());
    }

    @Test void loadInventoryLimitsAreInvalidAndMarkCoverageIncomplete() throws Exception {
        Path scenarios = Files.createDirectories(firstPackage.resolve("load/scenarios"));
        for (int index = 0; index <= SourceCandidateValidator.MAX_LOAD_DESCRIPTORS; index++)
            write(scenarios.resolve(String.format("scenario-%04d.yaml", index)), "schemaVersion: att-load/v1\n");
        CandidateValidation loadLimited = validator.validatePackage(firstPackage, configFile, runtimeRoot,
                "first", revisions("first"), FINGERPRINT);
        assertEquals(ValidationState.INVALID, loadLimited.state, describe(loadLimited));
        assertFalse(loadLimited.executionEligible);
        assertFalse(loadLimited.coverageComplete);
        assertTrue(loadLimited.diagnostics.stream().anyMatch(item -> "ATT-LOAD-CANDIDATE-LIMIT".equals(item.code)));

        for (Path path : Files.walk(scenarios).sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))
            Files.deleteIfExists(path);
        Path templates = Files.createDirectories(firstPackage.resolve("load/templates"));
        for (int index = 0; index <= SourceCandidateValidator.MAX_LOAD_TEMPLATE_DESCRIPTORS; index++)
            write(templates.resolve(String.format("template-%04d.yaml", index)), "schemaVersion: att-load-template/v1\n");
        CandidateValidation templateLimited = validator.validatePackage(firstPackage, configFile, runtimeRoot,
                "first", revisions("first"), FINGERPRINT);
        assertEquals(ValidationState.INVALID, templateLimited.state, describe(templateLimited));
        assertFalse(templateLimited.executionEligible);
        assertFalse(templateLimited.coverageComplete);
        assertTrue(templateLimited.diagnostics.stream().anyMatch(item -> "ATT-LOAD-TEMPLATE-LIMIT".equals(item.code)));
    }

    private static RevisionSet revisions(String packageId) {
        return new RevisionSet(RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION, "registry", repeat('b')),
                RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, packageId, repeat('c')),
                RevisionIdentity.of(RevisionKind.CHANGE_SET, packageId, repeat('d')), null,
                RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, "central-runtime-config", repeat('e')),
                null, null, RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA, "installed-engine", repeat('f')), null);
    }

    private static void write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, source.getBytes(StandardCharsets.UTF_8));
    }

    private static void copyTree(Path source, Path destination) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static String describe(CandidateValidation result) {
        StringBuilder text = new StringBuilder(String.valueOf(result.state));
        for (att.server.api.source.SourceManagementContracts.ValidationDiagnostic diagnostic : result.diagnostics)
            text.append("\n").append(diagnostic.code).append(" ").append(diagnostic.logicalPath)
                    .append(" ").append(diagnostic.field).append(" ").append(diagnostic.message);
        return text.toString();
    }

    private static String repeat(char value) {
        char[] result = new char[64]; Arrays.fill(result, value); return new String(result);
    }
}
