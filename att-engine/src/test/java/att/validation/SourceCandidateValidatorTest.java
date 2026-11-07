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

    @Test void effectiveResolutionRetainsEveryCallerForOneCanonicalFile() throws Exception {
        Path shared=firstPackage.resolve("files/shared.txt");write(shared,"one immutable File value\n");
        for(String templateId:Arrays.asList("one","two")) {
            Path directory=Files.createDirectories(firstPackage.resolve("templates").resolve(templateId));
            write(directory.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: "+templateId
                    +"\ndescription: Shared File caller\nactions:\n  read: {type: log, message: '&{../../files/shared.txt}'}\n");
        }
        Path flowDirectory=Files.createDirectories(firstPackage.resolve("templates/flows/shared"));
        write(flowDirectory.resolve("flow.yaml"),"schemaVersion: att-flow/v3.6\nid: common.shared.v1\nname: Shared\n"
                +"description: Two Flow action callers\nactions:\n"
                +"  first: {type: log, message: '&{../../../files/shared.txt}'}\n"
                +"  second: {type: log, message: '&{../../../files/shared.txt}'}\n");
        Path flowCaller=Files.createDirectories(firstPackage.resolve("templates/flow-caller"));
        write(flowCaller.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FlowCaller\ndescription: Flow caller\n"
                +"actions:\n  invoke: {type: flow, use: common.shared.v1}\n");
        EffectiveResolution resolution=validator.resolveEffective("first","SIT",firstPackage,
                configFile,runtimeRoot,revisions("first"));
        assertTrue(resolution.complete,resolution.diagnostics.toString());
        java.util.List<DependencyReference> callers=resolution.dependencies.stream().filter(ref->ref.kind==DependencyKind.FILE
                &&"files/shared.txt".equals(ref.canonicalPath)).collect(java.util.stream.Collectors.toList());
        assertEquals(5,callers.size(),callers.stream().map(ref->ref.canonicalPath+"|"+ref.callSite)
                .collect(java.util.stream.Collectors.toList()).toString());
        assertEquals(1,callers.stream().map(ref->ref.sourceFingerprint).distinct().count());
        assertEquals(5,callers.stream().map(ref->ref.callSite).distinct().count());
        assertTrue(callers.stream().anyMatch(ref->ref.callSite.contains("templates/one")));
        assertTrue(callers.stream().anyMatch(ref->ref.callSite.contains("templates/two")));
        assertTrue(callers.stream().anyMatch(ref->ref.callSite.contains("templates/flow-caller")));
        assertTrue(callers.stream().anyMatch(ref->ref.callSite.contains("flow.yaml#action:first")));
        assertTrue(callers.stream().anyMatch(ref->ref.callSite.contains("flow.yaml#action:second")));

        byte[] compiled=validator.compileFileExpressionSnapshot(firstPackage,configFile,runtimeRoot,"SIT");
        att.template.FileExpressionResolver.FileExpressionSnapshot restored=
                att.template.FileExpressionResolver.FileExpressionSnapshot.restore(firstPackage,compiled);
        java.util.List<att.template.FileExpressionResolver.CompiledFileDependency> plans=restored.dependencies();
        assertEquals(3,plans.size(),plans.toString());
        assertEquals(3,plans.stream().map(att.template.FileExpressionResolver.CompiledFileDependency::sourceDirectory)
                .distinct().count());
    }

    @Test void effectiveResolutionCapturesSelectedLoadTestdataOverlayAndDescriptorIdentity() throws Exception {
        Files.createDirectories(firstPackage.resolve("load"));
        write(firstPackage.resolve("testdata/scenario.yaml"),
                "schemaVersion: att-testdata/v1.0\nid: scenarioAccounts\nrecords: [{id: selected-overlay}]\n");
        write(firstPackage.resolve("load/scenario.yaml"),
                "schemaVersion: att-load/v1.9\ntestdata: [testdata/scenario.yaml]\nworkloads:\n"
                + "  - id: selected-overlay\n    target: {type: template, id: Lookup}\n"
                + "    inputs: {accountId: '@{scenarioAccounts.id}'}\n    load: {users: 1, duration: 1s}\n");

        EffectiveResolution resolution=validator.resolveEffective("first","SIT",firstPackage,
                configFile,runtimeRoot,revisions("first"),"load/scenario.yaml",null);

        assertTrue(resolution.complete,resolution.diagnostics.toString());
        assertTrue(resolution.dependencies.stream().anyMatch(ref->ref.kind==DependencyKind.LOAD_OVERLAY
                &&"testdata/scenario.yaml".equals(ref.canonicalPath)
                &&ref.sourceFingerprint!=null&&ref.sourceFingerprint.matches("[a-f0-9]{64}")));
        assertTrue(resolution.dependencies.stream().anyMatch(ref->ref.kind==DependencyKind.TESTDATA
                &&"scenarioAccounts".equals(ref.logicalId)
                &&"testdata/scenario.yaml".equals(ref.canonicalPath)
                &&ref.sourceFingerprint!=null&&ref.sourceFingerprint.matches("[a-f0-9]{64}")));
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
