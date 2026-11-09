package att.server;

import att.server.api.source.SourceManagementContracts.ActivationStatus;
import att.server.api.source.SourceManagementContracts.CandidateValidation;
import att.server.api.source.SourceManagementContracts.EffectiveResolution;
import att.server.api.source.SourceManagementContracts.ImpactReport;
import att.server.api.source.SourceManagementContracts.ProviderState;
import att.server.api.source.SourceManagementContracts.PublicationEvent;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import att.validation.SchemaFiles;
import att.validation.SourceCandidateValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConfigImpactAnalyzerConcurrencyTest {
    @TempDir Path temp;

    @Test void resolverUsesAnImmutablePackageSnapshotAndReportsStaleWhenSourceChangesMidAnalysis() throws Exception {
        Path runtime = Files.createDirectories(temp.resolve("runtime"));
        Path packageRoot = Files.createDirectories(temp.resolve("packages/default"));
        Path data = Files.createDirectories(temp.resolve("data"));
        Files.createDirectories(runtime.resolve("config"));
        Files.createDirectories(packageRoot.resolve("templates"));
        Files.createDirectories(packageRoot.resolve("testcase"));
        Path probe = Files.createDirectories(packageRoot.resolve("tools")).resolve("probe.sh");
        Files.writeString(probe, "#!/bin/sh\nexit 0\n");
        String manifest = "schemaVersion: att-package/v1\nmetadata:\n  name: Impact snapshot test\n";
        Files.writeString(packageRoot.resolve("package.yaml"), manifest);
        String config = "schemaVersion: att-config/v2.13\nenvironment: SIT\ntimeoutMs: 10000\n"
                + "testcase:\n  root: testcase\ntemplates:\n  root: templates\nenvironments:\n  SIT: {}\n";
        Path configFile = runtime.resolve("config/config.yaml");
        Files.writeString(configFile, config);

        AtomicReference<PackageRegistry.Snapshot> registry = new AtomicReference<>(
                registrySnapshot(Map.of("default", packageRoot), "c".repeat(64)));
        AtomicReference<List<LoadSchedule>> schedules = new AtomicReference<>(List.of());
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        AtomicBoolean firstResolution = new AtomicBoolean(true);
        SourceCandidateValidator validator = new SourceCandidateValidator();
        ConfigImpactAnalyzer.ImpactEngine engine = new ConfigImpactAnalyzer.ImpactEngine() {
            @Override public CandidateValidation validateCentralConfig(Path candidate, Path root,
                    RevisionSet revisions, String fingerprint) throws Exception {
                return validator.validateCentralConfig(candidate, root, revisions, fingerprint);
            }
            @Override public CandidateValidation validatePackage(Path pkg, Path candidate, Path root,
                    String packageId, RevisionSet revisions, String fingerprint) throws Exception {
                return validator.validatePackage(pkg, candidate, root, packageId, revisions, fingerprint);
            }
            @Override public EffectiveResolution resolveEffective(String packageId, String environment, Path pkg,
                    Path candidate, Path root, RevisionSet provenance) throws Exception {
                assertNotEquals(packageRoot.toRealPath(), pkg.toRealPath(),
                        "impact resolution must use the captured Package tree, not the live root");
                assertNotNull(provenance.packageSourceRevision);
                assertEquals(att.server.api.source.SourceManagementContracts.RevisionKind.PACKAGE_SOURCE,
                        provenance.packageSourceRevision.kind);
                if (firstResolution.compareAndSet(true, false)) {
                    resolverEntered.countDown();
                    assertTrue(releaseResolver.await(2, TimeUnit.SECONDS), "test must release the stalled resolver");
                }
                EffectiveResolution resolution = validator.resolveEffective(packageId, environment, pkg,
                        candidate, root, provenance);
                assertNotNull(resolution.revisions.executionRevisionDigest);
                assertEquals(att.server.api.source.SourceManagementContracts.RevisionKind.EXECUTION_REVISION,
                        resolution.revisions.executionRevisionDigest.kind);
                assertEquals(packageId + "@" + resolution.environment,
                        resolution.revisions.executionRevisionDigest.scopeId);
                return resolution;
            }
        };
        ConfigActivationCoordinator guard = new ConfigActivationCoordinator(new ConfigActivationCoordinator.RuntimeGuard() {
            @Override public boolean available() { return true; }
            @Override public ActivationStatus activate(PublicationEvent event) { return null; }
            @Override public void invalidate(att.server.api.source.SourceManagementContracts.InvalidationScope scope) { }
            @Override public ActivationStatus status(String scopeId) { return null; }
        });
        ConfigImpactAnalyzer analyzer = new ConfigImpactAnalyzer(new ConfigImpactAnalyzer.Inventory() {
            @Override public PackageRegistry.Snapshot registry() { return registry.get(); }
            @Override public List<LoadSchedule> schedules() { return schedules.get(); }
        }, PackageConfigurationPolicy.load(null), "strict", engine, guard, 2500);
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        ChangeSetPublisher publisher = new ChangeSetPublisher(Clock.systemUTC(), 8, 2, 300,
                (domain, scopeId, actor, admin) -> new ChangeSetPublisher.Scope(domain, scopeId,
                        transaction.pin(domain, scopeId, runtime), runtime, null,
                        att.validation.SchemaFiles.registryDigest()),
                new ChangeSetCandidateValidator(), ChangeSetPublisher.unavailableConfigGate(),
                new ChangeSetPublisher.Audit() {
                    @Override public void record(String actor, String action, String domain, String scopeId, String detail) { }
                }, transaction, null, analyzer);
        ExecutorService request = Executors.newSingleThreadExecutor();
        try (publisher) {
            Map<String, String> revisions = publisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true);
            Map<String, Object> draft = publisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, revisions.get("baseSourceRevision"), null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",
                            (config + "# candidate comment\n").getBytes(StandardCharsets.UTF_8))));
            String id = String.valueOf(draft.get("changeSetId"));
            publisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                    "admin", true, id);

            var report = request.submit(() -> publisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, id, null));
            assertTrue(resolverEntered.await(2, TimeUnit.SECONDS), "analysis should reach its Engine resolver");
            Files.writeString(probe, "#!/bin/sh\n# source changed during impact resolution\nexit 0\n");
            releaseResolver.countDown();

            ImpactReport result = report.get(4, TimeUnit.SECONDS);
            assertEquals(ProviderState.STALE, result.providerState);
            assertEquals("ATT-CONFIG-IMPACT-STALE", result.unavailableCode);
        } finally {
            releaseResolver.countDown();
            request.shutdownNow();
        }
    }

    @Test void stalledResolverIsCancelledAndReturnsSanitizedAnalysisLimit() throws Exception {
        Path runtime = Files.createDirectories(temp.resolve("timeout-runtime"));
        Path packageRoot = Files.createDirectories(temp.resolve("timeout-packages/default"));
        Path data = Files.createDirectories(temp.resolve("timeout-data"));
        Files.createDirectories(runtime.resolve("config"));
        Files.createDirectories(packageRoot.resolve("templates"));
        Files.createDirectories(packageRoot.resolve("testcase"));
        Files.createDirectories(packageRoot.resolve("tools"));
        Files.writeString(packageRoot.resolve("package.yaml"),
                "schemaVersion: att-package/v1\nmetadata:\n  name: Resolver timeout test\n");
        String config = "schemaVersion: att-config/v2.13\nenvironment: SIT\ntimeoutMs: 10000\n"
                + "testcase:\n  root: testcase\ntemplates:\n  root: templates\nenvironments:\n  SIT: {}\n";
        Files.writeString(runtime.resolve("config/config.yaml"), config);

        PackageRegistry.Snapshot registry = registrySnapshot(Map.of("default", packageRoot), "e".repeat(64));
        SourceCandidateValidator validator = new SourceCandidateValidator();
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch resolverInterrupted = new CountDownLatch(1);
        ConfigImpactAnalyzer.ImpactEngine engine = new ConfigImpactAnalyzer.ImpactEngine() {
            @Override public CandidateValidation validateCentralConfig(Path candidate, Path root,
                    RevisionSet revisions, String fingerprint) throws Exception {
                return validator.validateCentralConfig(candidate, root, revisions, fingerprint);
            }
            @Override public CandidateValidation validatePackage(Path pkg, Path candidate, Path root,
                    String packageId, RevisionSet revisions, String fingerprint) throws Exception {
                return validator.validatePackage(pkg, candidate, root, packageId, revisions, fingerprint);
            }
            @Override public EffectiveResolution resolveEffective(String packageId, String environment, Path pkg,
                    Path candidate, Path root, RevisionSet provenance) throws Exception {
                resolverEntered.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("the intentionally stalled resolver must be interrupted");
                } catch (InterruptedException cancelled) {
                    resolverInterrupted.countDown();
                    throw cancelled;
                }
            }
        };
        ConfigActivationCoordinator guard = new ConfigActivationCoordinator(new ConfigActivationCoordinator.RuntimeGuard() {
            @Override public boolean available() { return true; }
            @Override public ActivationStatus activate(PublicationEvent event) { return null; }
            @Override public void invalidate(att.server.api.source.SourceManagementContracts.InvalidationScope scope) { }
            @Override public ActivationStatus status(String scopeId) { return null; }
        });
        ConfigImpactAnalyzer analyzer = new ConfigImpactAnalyzer(new ConfigImpactAnalyzer.Inventory() {
            @Override public PackageRegistry.Snapshot registry() { return registry; }
            @Override public List<LoadSchedule> schedules() { return List.of(); }
        }, PackageConfigurationPolicy.load(null), "strict", engine, guard, 300);
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        ChangeSetPublisher publisher = new ChangeSetPublisher(Clock.systemUTC(), 8, 2, 300,
                (domain, scopeId, actor, admin) -> new ChangeSetPublisher.Scope(domain, scopeId,
                        transaction.pin(domain, scopeId, runtime), runtime, null, SchemaFiles.registryDigest()),
                new ChangeSetCandidateValidator(), ChangeSetPublisher.unavailableConfigGate(),
                new ChangeSetPublisher.Audit() {
                    @Override public void record(String actor, String action, String domain, String scopeId, String detail) { }
                }, transaction, null, analyzer);
        ExecutorService request = Executors.newSingleThreadExecutor();
        try (publisher) {
            Map<String, String> revisions = publisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true);
            Map<String, Object> draft = publisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, revisions.get("baseSourceRevision"), null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",
                            (config + "# stalled resolver probe\n").getBytes(StandardCharsets.UTF_8))));
            String id = String.valueOf(draft.get("changeSetId"));
            publisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                    "admin", true, id);

            long started = System.nanoTime();
            var report = request.submit(() -> publisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, id, null));
            assertTrue(resolverEntered.await(2, TimeUnit.SECONDS), "analysis should enter the stalled resolver");
            ImpactReport result = report.get(2, TimeUnit.SECONDS);
            assertEquals(ProviderState.UNAVAILABLE, result.providerState);
            assertEquals("ATT-CONFIG-IMPACT-ANALYSIS-LIMIT", result.unavailableCode);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500,
                    "a stalled Engine call must not hold the analysis request indefinitely");
            assertTrue(resolverInterrupted.await(1, TimeUnit.SECONDS), "timed out Engine work must receive cancellation");
        } finally {
            request.shutdownNow();
        }
    }

    private static PackageRegistry.Snapshot registrySnapshot(Map<String, Path> packages, String revision) throws Exception {
        Constructor<?> constructor = PackageRegistry.Snapshot.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (PackageRegistry.Snapshot) constructor.newInstance(1L, revision, "READY", Instant.now(), null,
                null, null, packages, List.copyOf(packages.values()),
                packages.keySet().stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> "READY")));
    }
}
