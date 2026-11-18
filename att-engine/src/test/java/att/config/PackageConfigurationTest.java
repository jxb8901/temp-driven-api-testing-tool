package att.config;

import att.testdata.TestdataRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackageConfigurationTest {
    @TempDir Path root;
    private static final String METRIC_SOURCE = "schemaVersion: att-sut-metrics-metric-source/v1\n"
            + "id: host-source\nname: Host metrics\nconnection: {kind: DATA_SOURCE, id: prometheus}\n"
            + "fetch: {id: host-fetch, mode: prometheus-range, query: up, parameters: {}, sampling: range, step: 15s}\n"
            + "mapping: {labels: {retain: [instance]}, outputs: [{id: up, title: Up, unit: count, kind: gauge, metricName: up}]}\n"
            + "limits: {maxRows: 100, maxSeries: 10, maxSamples: 1000, maxArtifactBytes: 8192}\n";
    private static final String METRIC_GROUP = "schemaVersion: att-sut-metrics-group/v1\n"
            + "id: runtime\ntitle: Runtime\norder: 10\nmatch: {metricIds: [up]}\ngroupBy: [instance]\ndisplay: auto\n";
    private static final String METRIC_PROFILE = "schemaVersion: att-sut-metrics-profile/v2\n"
            + "id: default-profile\nname: Default\nmetricSourceIds: [host-source]\ngroupIds: [runtime]\n"
            + "allowedBindings: {environments: [], targets: [], tenants: [], parameters: {}}\n";
    private static final String LEGACY_COMPOSITE_PROFILE = "schemaVersion: att-sut-metrics-profile/v1\n"
            + "id: legacy-composite\nkind: composite\ncategory: app\n"
            + "includes: [{profile: legacy-base, source: prometheus, targets: [{id: host}]}]\n";
    private Path write(Path file, String text) throws Exception { Files.createDirectories(file.getParent()); Files.write(file, text.getBytes("UTF-8")); return file; }
    private Path global(String extra) throws Exception { return write(root.resolve("config/config.yaml"), "schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}, UAT: {}}\n" + extra); }
    private FrameworkConfig load(Path config, Path pkg, String env) throws Exception { return new FrameworkConfigLoader().load(config, root, pkg, env); }

    @Test void independentlyResolvesPackageAndExplicitEnvironmentWithoutChangingCentralProfiles() throws Exception {
        Path config = global("testdata: [testdata/default-only.yaml]\n");
        Path a = root.resolve("packages/a"), b = root.resolve("packages/b");
        write(a.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: uat\n");
        write(b.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n");
        assertEquals("UAT", load(config, a, null).environment());
        assertEquals("SIT", load(config, b, null).environment());
        assertEquals("SIT", load(config, a, "sit").environment());
        assertEquals("package", load(config, a, null).effectiveConfiguration().environmentOrigin());
        assertEquals("request", load(config, a, "sit").effectiveConfiguration().environmentOrigin());
        assertTrue(load(config, a, null).testdataDescriptors().isEmpty());
        assertTrue(load(config, root.resolve("packages/no-manifest"), null).testdataDescriptors().isEmpty());
        assertTrue(new String(Files.readAllBytes(config), "UTF-8").contains("environment: SIT"));
    }

    @Test void omittedGlobalFieldsRetainRuntimeDefaultOriginAndSafeAuthoredPath() throws Exception {
        Path config=global("timeoutMs: 11000\n");Path pkg=root.resolve("packages/a");
        write(pkg.resolve("package.yaml"),"schemaVersion: att-package/v1\n");
        FrameworkConfig effective=load(config,pkg,null);
        assertEquals("global",effective.effectiveConfiguration().origins().get("timeoutMs"));
        assertEquals("runtime-default",effective.effectiveConfiguration().origins().get("report.columns"));
        java.util.Map<?,?> paths=(java.util.Map<?,?>)effective.effectiveConfiguration().metadata("SIT").get("authoredPaths");
        assertEquals("",paths.get("report.columns"));assertEquals("config/config.yaml",paths.get("timeoutMs"));
    }

    @Test void trustedContextSelectsEnvironmentWithoutInferringPackageId() throws Exception {
        Path config=global(""); Path pkg=root.resolve("packages/folder-name");
        write(pkg.resolve("package.yaml"),"schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n");
        FrameworkConfigLoader loader=new FrameworkConfigLoader();
        PackageContext context=new PackageContext(pkg,"registered-id","uat");
        FrameworkConfig selected=loader.loadContext(config,root,context,null);
        assertEquals("UAT",selected.environment());
        java.util.Map<String,Object> evidence=selected.effectiveConfiguration().metadata(selected.environment());
        assertEquals("context",evidence.get("environmentOrigin"));
        assertEquals("registered-id",evidence.get("packageId"));
        assertEquals("Context.environment",((java.util.Map<?,?>)evidence.get("authoredPaths")).get("environment"));
        assertThrows(UnsupportedOperationException.class,()->evidence.put("environment","SIT"));
        FrameworkConfig explicit=loader.loadContext(config,root,context,"sit");
        assertEquals("SIT",explicit.environment());
        assertEquals("request",explicit.effectiveConfiguration().environmentOrigin());
        FrameworkConfig anonymous=loader.loadContext(config,root,new PackageContext(pkg,null,null),null);
        assertEquals("package",anonymous.effectiveConfiguration().environmentOrigin());
        assertFalse(anonymous.effectiveConfiguration().metadata("SIT").containsKey("packageId"));
    }

    @Test void environmentListsReplaceBaseAndExplicitEmptyClearsRegistry() throws Exception {
        Path config = global(""); Path pkg = root.resolve("packages/a");
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\ntestdata:\n  descriptors: [testdata/base.yaml]\n  environments:\n    UAT: []\n");
        assertEquals(Collections.singletonList(pkg.resolve("testdata/base.yaml")), load(config, pkg, "SIT").testdataDescriptors());
        assertTrue(load(config, pkg, "UAT").testdataDescriptors().isEmpty());
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\ntestdata: {environments: {SIT: [], sit: []}}\n");
        assertThrows(IllegalArgumentException.class, () -> load(config, pkg, null));
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\ntestdata: {environments: {PROD: []}}\n");
        assertThrows(IllegalArgumentException.class, () -> load(config, pkg, null));
    }

    @Test void allowlistedDefaultsReplaceColumnsAndCannotChangePolicyOrOutput() throws Exception {
        Path config = global("timeoutMs: 10000\npackagePolicy: {maxTimeoutMs: 16000}\nreport: {columns: {result: Global, durationMs: Time}}\n");
        Path pkg = root.resolve("packages/a");
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\nresources: {templatesRoot: business/templates, testcaseRoot: business/cases}\ndefaults:\n  timeoutMs: 15000\n  report: {columns: {result: Package}}\n  xml: {namespaceMode: preserve}\n");
        FrameworkConfig effective = load(config, pkg, null);
        assertEquals(15000, effective.timeoutMs());
        assertEquals("business/templates", effective.templatesRoot().toString());
        assertEquals(Collections.singletonMap("result", "Package"), effective.report().columns());
        assertEquals("preserve", effective.xmlNamespaceMode());
        assertEquals("output", effective.outputDirectory().toString());
        assertThrows(UnsupportedOperationException.class, () -> effective.report().columns().put("result", "changed"));
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaults: {timeoutMs: 16001}\n");
        assertThrows(IllegalArgumentException.class, () -> load(config, pkg, null));
    }

    @Test void manifestRejectsProtectedUnknownExtensionAndAliasFields() throws Exception {
        for (String key : Arrays.asList("tools", "toolGroups", "dbhelpers", "mqhelpers", "httphelpers", "sshhelpers", "environments", "security", "auth", "server", "schemas", "id", "x-tools", "outputDirectory")) {
            assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(root, "schemaVersion: att-package/v1\n" + key + ": {}\n"), key);
        }
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(root, "schemaVersion: att-package/v1\nmetadata: &x {name: Test}\n"));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(root, "schemaVersion: att-package/v1\nresources: {templatesRoot: ../other}\n"));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(root, "schemaVersion: att-package/v1\nresources: {templatesRoot: /private/tmp}\n"));
        Files.createSymbolicLink(root.resolve("escape"), root.getParent());
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(root, "schemaVersion: att-package/v1\nresources: {templatesRoot: escape/templates}\n"));
    }

    @Test void packageV2LoadsOnlyExplicitValidatedMetricInventoriesAndRoundTripsTheirPaths() throws Exception {
        Path pkg = root.resolve("packages/v2");
        write(pkg.resolve("load/sut-metrics/sources/host.yaml"), METRIC_SOURCE);
        write(pkg.resolve("load/sut-metrics/groups/runtime.yaml"), METRIC_GROUP);
        write(pkg.resolve("load/sut-metrics/profiles/default.yaml"), METRIC_PROFILE);
        write(pkg.resolve("load/sut-metrics/sources/unlisted.yaml"), METRIC_SOURCE.replace("host-source", "unlisted-source"));

        PackageConfig config = PackageConfig.parse(pkg,
                "schemaVersion: att-package/v2\nobservability:\n"
                        + "  metricSources: [load/sut-metrics/sources/host.yaml]\n"
                        + "  groups: [load/sut-metrics/groups/runtime.yaml]\n"
                        + "  profiles: [load/sut-metrics/profiles/default.yaml]\n");

        assertEquals("att-package/v2", config.schemaVersion());
        assertEquals(Collections.singleton("host-source"), config.metricSourceResources().keySet());
        assertEquals(Collections.singleton("runtime"), config.metricGroupResources().keySet());
        assertEquals(Collections.singleton("default-profile"), config.metricProfileResources().keySet());
        assertTrue(config.hasMetricInventory());
        assertEquals(pkg.resolve("load/sut-metrics/sources/host.yaml").toRealPath(),
                config.metricSourceResources().get("host-source").canonicalPath());
        assertEquals("load/sut-metrics/sources/host.yaml", config.metricSourceResources().get("host-source").relativePath());
        assertTrue(config.metricSourceResources().get("host-source").digest().matches("[a-f0-9]{64}"));
        assertEquals(Collections.singletonList("load/sut-metrics/sources/host.yaml"),
                ((java.util.Map<?, ?>) config.declared(pkg).get("observability")).get("metricSources"));
        assertThrows(UnsupportedOperationException.class,
                () -> config.metricSourceResources().put("extra", config.metricSourceResources().get("host-source")));
        java.util.Map<String, Object> definition = config.metricSourceResources().get("host-source").definition();
        assertThrows(UnsupportedOperationException.class, () -> ((java.util.Map) definition).put("id", "changed"));
        java.util.Map<?, ?> mapping = (java.util.Map<?, ?>) definition.get("mapping");
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) mapping.get("outputs")).clear());
    }

    @Test void packageV1RemainsCompatibleAndDoesNotDiscoverUnlistedMetricYaml() throws Exception {
        Path pkg = root.resolve("packages/v1");
        write(pkg.resolve("load/sut-metrics/sources/host.yaml"), METRIC_SOURCE);
        PackageConfig config = PackageConfig.parse(pkg, "schemaVersion: att-package/v1\n");
        assertEquals("att-package/v1", config.schemaVersion());
        assertTrue(config.metricSourceResources().isEmpty());
        assertTrue(config.metricGroupResources().isEmpty());
        assertTrue(config.metricProfileResources().isEmpty());
        assertFalse(config.declared(pkg).containsKey("observability"));
        PackageConfig v2WithoutInventory = PackageConfig.parse(pkg, "schemaVersion: att-package/v2\n");
        assertTrue(v2WithoutInventory.metricSourceResources().isEmpty());
        assertTrue(v2WithoutInventory.metricGroupResources().isEmpty());
        assertTrue(v2WithoutInventory.metricProfileResources().isEmpty());
        assertFalse(v2WithoutInventory.hasMetricInventory());
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(pkg, "metadata: {name: Legacy}\n"));
        assertThrows(IllegalArgumentException.class,
                () -> PackageConfig.parse(pkg, "schemaVersion: att-package/v1\nobservability: {metricSources: []}\n"));
    }

    @Test void metricInventoryRejectsWrongSubtreesMissingFilesInvalidDefinitionsAndDuplicateIdsOnLoad() throws Exception {
        Path pkg = root.resolve("packages/invalid-inventory");
        write(pkg.resolve("load/sut-metrics/sources/host.yaml"), METRIC_SOURCE);
        write(pkg.resolve("load/sut-metrics/sources/host-copy.yaml"), METRIC_SOURCE);
        write(pkg.resolve("load/sut-metrics/groups/runtime.yaml"), METRIC_GROUP);
        write(pkg.resolve("load/sut-metrics/profiles/legacy-composite.yaml"), LEGACY_COMPOSITE_PROFILE);
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(pkg,
                "schemaVersion: att-package/v2\nobservability: {metricSources: [load/sut-metrics/groups/runtime.yaml]}\n"));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(pkg,
                "schemaVersion: att-package/v2\nobservability: {metricSources: [load/sut-metrics/sources/missing.yaml]}\n").metricSourceResources());
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(pkg,
                "schemaVersion: att-package/v2\nobservability: {profiles: [load/sut-metrics/profiles/legacy-composite.yaml]}\n").metricProfileResources());
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(pkg,
                "schemaVersion: att-package/v2\nobservability: {metricSources: [../outside.yaml]}\n"));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(pkg,
                "schemaVersion: att-package/v2\nobservability:\n  metricSources: [load/sut-metrics/sources/host.yaml, load/sut-metrics/sources/host-copy.yaml]\n").metricSourceResources());
        Files.createSymbolicLink(pkg.resolve("load/sut-metrics/sources/alias.yaml"), pkg.resolve("load/sut-metrics/sources/host.yaml"));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(pkg,
                "schemaVersion: att-package/v2\nobservability:\n  metricSources: [load/sut-metrics/sources/host.yaml, load/sut-metrics/sources/alias.yaml]\n").metricSourceResources());
        Files.createSymbolicLink(pkg.resolve("load/sut-metrics/sources/cross-kind.yaml"), pkg.resolve("load/sut-metrics/groups/runtime.yaml"));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.parse(pkg,
                "schemaVersion: att-package/v2\nobservability: {metricSources: [load/sut-metrics/sources/cross-kind.yaml]}\n").metricSourceResources());
    }

    @Test void malformedOrMissingLoadMetricsDoNotBreakUnrelatedRuntimeConfiguration() throws Exception {
        Path pkg = root.resolve("packages/broken-metrics");
        String resourcePath = "load/sut-metrics/sources/host.yaml";
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v2\nobservability: {metricSources: [" + resourcePath + "]}\n");
        Path metricSource = write(pkg.resolve(resourcePath), "schemaVersion: [broken\n");
        Path config = global("");

        FrameworkConfig ordinaryRun = load(config, pkg, "SIT");
        assertEquals("SIT", ordinaryRun.environment());
        PackageConfig metricsSelection = PackageConfig.load(pkg);
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class, metricsSelection::metricSourceResources);
        assertTrue(malformed.getMessage().contains("invalid-yaml"), String.valueOf(malformed));

        Files.delete(metricSource);
        PackageConfig missingSelection = PackageConfig.load(pkg);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, missingSelection::metricSourceResources);
        assertTrue(missing.getMessage().contains("missing-or-unsafe-package-file"), String.valueOf(missing));
        assertEquals("SIT", load(config, pkg, "SIT").environment(), "Ordinary RUN/DEBUG config remains independent of LOAD-only definitions.");
    }

    @Test void candidateMetricResourceOverrideIsBoundedAndDoesNotWriteLivePackageFiles() throws Exception {
        Path pkg = root.resolve("packages/resource-overlay");
        String resourcePath = "load/sut-metrics/sources/host.yaml";
        Path liveResource = write(pkg.resolve(resourcePath), METRIC_SOURCE);
        write(pkg.resolve("load/sut-metrics/sources/unlisted.yaml"), METRIC_SOURCE.replace("host-source", "unlisted"));
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v2\n"
                + "observability: {metricSources: [" + resourcePath + "]}\n");
        byte[] before = Files.readAllBytes(liveResource);

        PackageConfig candidate = PackageConfig.loadWithMetricResourceOverride(pkg, resourcePath,
                METRIC_SOURCE.replace("Host metrics", "Candidate metrics"));
        PackageConfig.PackageMetricResource resolved = candidate.metricSourceResources().get("host-source");
        assertEquals("Candidate metrics", resolved.definition().get("name"));
        assertNotEquals(PackageConfig.load(pkg).metricSourceResources().get("host-source").digest(), resolved.digest());
        assertArrayEquals(before, Files.readAllBytes(liveResource));

        assertThrows(IllegalArgumentException.class, () -> PackageConfig.loadWithMetricResourceOverride(pkg,
                "load/sut-metrics/sources/unlisted.yaml", METRIC_SOURCE));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.loadWithMetricResourceOverride(pkg,
                "load/sut-metrics/sources/../groups/runtime.yaml", METRIC_SOURCE));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.loadWithMetricResourceOverride(pkg,
                resourcePath, "schemaVersion: att-sut-metrics-metric-source/v1\nid: invalid\n"));
        assertArrayEquals(before, Files.readAllBytes(liveResource));

        byte[] manifestPrefix = "schemaVersion: att-package/v2\n# ".getBytes("UTF-8");
        byte[] malformedManifest = Arrays.copyOf(manifestPrefix, manifestPrefix.length + 3);
        malformedManifest[manifestPrefix.length] = (byte) 0xc3;
        malformedManifest[manifestPrefix.length + 1] = '(';
        malformedManifest[manifestPrefix.length + 2] = '\n';
        Files.write(pkg.resolve("package.yaml"), malformedManifest);
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.load(pkg));
    }

    @Test void candidateManifestCanActivateOneNewMetricResourceWithoutWritingEitherFile() throws Exception {
        Path pkg = root.resolve("packages/create-first-metric");
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v2\nmetadata: {name: Metrics}");
        String path = "load/sut-metrics/sources/first.yaml";
        String source = METRIC_SOURCE.replace("host-source", "first-source");
        String candidateManifest = "schemaVersion: att-package/v2\nmetadata: {name: Metrics}\n"
                + "observability: {metricSources: [" + path + "]}\n";
        String expectedManifestDigest = PackageConfig.load(pkg).digest();
        byte[] manifestBefore = Files.readAllBytes(pkg.resolve("package.yaml"));

        PackageConfig candidate = PackageConfig.loadWithCandidateManifestAndMetricResourceOverride(
                pkg, candidateManifest, path, source, expectedManifestDigest);
        assertEquals("first-source", candidate.metricSourceResources().get("first-source").id());
        assertEquals("load/sut-metrics/sources/first.yaml",
                candidate.metricSourceResources().get("first-source").relativePath());
        assertEquals(PackageConfig.sha256(source.getBytes("UTF-8")),
                candidate.metricSourceResources().get("first-source").digest());
        assertFalse(Files.exists(pkg.resolve(path)));
        assertArrayEquals(manifestBefore, Files.readAllBytes(pkg.resolve("package.yaml")));

        assertThrows(IllegalArgumentException.class, () -> PackageConfig.loadWithCandidateManifestAndMetricResourceOverride(
                pkg, candidateManifest.replace("metadata: {name: Metrics}", "metadata: {name: Changed}"),
                path, source, expectedManifestDigest));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.loadWithCandidateManifestAndMetricResourceOverride(
                pkg, candidateManifest.replace(path, "load/sut-metrics/groups/other.yaml"), path,
                source, expectedManifestDigest));
        Files.createDirectories(pkg.resolve(path).getParent());
        Files.write(pkg.resolve(path), "unrelated content".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> PackageConfig.loadWithCandidateManifestAndMetricResourceOverride(
                pkg, candidateManifest, path, source, expectedManifestDigest));
        assertArrayEquals(manifestBefore, Files.readAllBytes(pkg.resolve("package.yaml")));
    }

    @Test void lazyBaseValidationStillRequiresExplicitInvocationAndFullPackageValidation() throws Exception {
        Path missing = root.resolve("testdata/missing.yaml");
        Path invalid = write(root.resolve("testdata/unused.yaml"), "id: unused\nrecords: invalid\n");
        Path valid = descriptor(root.resolve("testdata/valid.yaml"), "accounts", "first");
        TestdataRegistry base = new TestdataRegistry(root, Arrays.asList(missing, invalid, valid), Collections.emptyList());
        base.validateInvocation();
        assertEquals("accounts", base.resolve("accounts").id());
        assertThrows(Exception.class, base::validateAll);
        assertThrows(Exception.class, () -> base.resolve("unused"));
        TestdataRegistry local = new TestdataRegistry(root, Collections.singletonList(valid), Collections.singletonList(missing));
        assertThrows(Exception.class, local::validateInvocation);
        Path overlay = descriptor(root.resolve("testdata/override.yaml"), "accounts", "second");
        TestdataRegistry replaced = new TestdataRegistry(root, Collections.singletonList(valid), Collections.singletonList(overlay));
        assertEquals("load-local", replaced.layer("accounts"));
        assertEquals(overlay.toRealPath(), replaced.resolve("accounts").source());
        assertThrows(Exception.class, () -> new TestdataRegistry(root, Collections.emptyList(), Arrays.asList(valid, overlay)).validateInvocation());
    }

    @Test void explicitLegacyCompatibilityIsNeverUnionedWithManifestRegistry() throws Exception {
        Path config = global("packagePolicy: {legacyTestdata: true}\ntestdata: [testdata/old.yaml]\n");
        Path pkg = root.resolve("packages/a");
        assertEquals(Collections.singletonList(pkg.resolve("testdata/old.yaml")), load(config, pkg, null).testdataDescriptors());
        write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\n");
        assertTrue(load(config, pkg, null).testdataDescriptors().isEmpty());
    }

    @Test void packageInspectionIsSeparateAndIdentityTracksOnlySelectedManifest() throws Exception {
        Path config = global(""); Path a = root.resolve("packages/a"), b = root.resolve("packages/b");
        write(a.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n");
        write(b.resolve("package.yaml"), "schemaVersion: att-package/v1\n");
        String beforeA = load(config, a, null).effectiveConfiguration().identity();
        String beforeB = load(config, b, null).effectiveConfiguration().identity();
        java.util.Map<String,Object> view = new att.resource.PackageManifestInspector(a, config).inspect("compare", null);
        assertEquals("UAT", view.get("environment")); assertEquals("package", view.get("environmentOrigin"));
        assertNotNull(view.get("globalFields"));
        assertEquals("SIT", new FrameworkConfigLoader().loadGlobalDiscovery(config, root, null).environment());
        write(a.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n");
        assertNotEquals(beforeA, load(config, a, null).effectiveConfiguration().identity());
        assertEquals(beforeB, load(config, b, null).effectiveConfiguration().identity());
    }

    @Test void registriesWithTheSameLogicalIdStayInTheirSelectedPackage() throws Exception {
        Path config = global(""); Path a = root.resolve("packages/a"), b = root.resolve("packages/b");
        for (Path pkg : Arrays.asList(a,b)) write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\ntestdata: {descriptors: [testdata/accounts.yaml]}\n");
        descriptor(a.resolve("testdata/accounts.yaml"), "accounts", "first");
        descriptor(b.resolve("testdata/accounts.yaml"), "accounts", "second");
        TestdataRegistry first = new TestdataRegistry(a, load(config, a, null).testdataDescriptors(), Collections.emptyList());
        TestdataRegistry second = new TestdataRegistry(b, load(config, b, null).testdataDescriptors(), Collections.emptyList());
        assertEquals(Collections.singletonMap("value", "first"), first.resolve("accounts").record(0));
        assertEquals(Collections.singletonMap("value", "second"), second.resolve("accounts").record(0));
    }

    @Test void dependencyDiagnosticsContainLayerAndLogicalSourceWithoutRecordOrHostValues() throws Exception {
        Path invalid = write(root.resolve("testdata/bad.yaml"), "schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: {password: PRIVATE-RECORD-VALUE}\n");
        TestdataRegistry registry = new TestdataRegistry(root, Collections.singletonList(invalid), Collections.emptyList());
        String error = assertThrows(IllegalArgumentException.class, () -> registry.resolve("accounts")).getMessage();
        assertTrue(error.contains("Manifest/env")); assertTrue(error.contains("testdata/bad.yaml"));
        assertTrue(error.contains("descriptor=accounts")); assertTrue(error.contains("invalid-schema"));
        assertFalse(error.contains("PRIVATE-RECORD-VALUE")); assertFalse(error.contains(root.toString()));
        TestdataRegistry local = new TestdataRegistry(root, Collections.emptyList(), Collections.singletonList(root.resolve("testdata/missing.yaml")));
        String missing = assertThrows(IllegalArgumentException.class, local::validateInvocation).getMessage();
        assertTrue(missing.contains("Load-local")); assertTrue(missing.contains("reason=missing"));
        assertFalse(missing.contains(root.toString()));
    }

    @Test void candidateValidationNeverChangesTheLiveManifestOrCentralConfig() throws Exception {
        Path config = global("packagePolicy: {maxTimeoutMs: 16000}\n"); Path pkg = root.resolve("packages/a");
        Path live = write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n");
        byte[] before = Files.readAllBytes(live), beforeGlobal = Files.readAllBytes(config);
        att.resource.PackageManifestInspector inspector = new att.resource.PackageManifestInspector(pkg, config);
        java.util.Map<String,Object> candidate = inspector.validateCandidate("schemaVersion: att-package/v1\ndefaultEnvironment: UAT\ndefaults: {timeoutMs: 15000}\n", null);
        assertEquals("UAT", candidate.get("environment")); assertEquals(true, candidate.get("valid"));
        assertArrayEquals(before, Files.readAllBytes(live)); assertArrayEquals(beforeGlobal, Files.readAllBytes(config));
        assertEquals("SIT", load(config, pkg, null).environment());
        assertThrows(IllegalArgumentException.class, () -> inspector.validateCandidate("schemaVersion: att-package/v1\ndefaults: {timeoutMs: 16001}\n", null));
        assertThrows(IllegalArgumentException.class, () -> inspector.validateCandidate("schemaVersion: att-package/v1\ntestdata: {descriptors: [testdata/missing.yaml]}\n", null));
        assertArrayEquals(before, Files.readAllBytes(live));
    }

    private Path descriptor(Path path, String id, String value) throws Exception {
        return write(path, "schemaVersion: att-testdata/v1.0\nid: " + id + "\nrecords:\n  - {value: " + value + "}\n");
    }
}
