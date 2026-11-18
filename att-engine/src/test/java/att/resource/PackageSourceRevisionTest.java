package att.resource;

import att.config.PackageConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackageSourceRevisionTest {
    @TempDir Path root;
    private static final String SOURCE = "schemaVersion: att-package/v1\ntestdata: {descriptors: [testdata/base.yaml]}\n";
    private static final String METRIC_INVENTORY = "schemaVersion: att-package/v2\nobservability:\n"
            + "  metricSources: [load/sut-metrics/sources/host.yaml]\n"
            + "  groups: [load/sut-metrics/groups/runtime.yaml]\n"
            + "  profiles: [load/sut-metrics/profiles/default.yaml]\n";
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
    private Path write(String name,String text) throws Exception {
        Path file=root.resolve(name);Files.createDirectories(file.getParent());Files.write(file,text.getBytes("UTF-8"));return file;
    }
    private String capture(Path pkg,Path config) throws Exception {
        return capture(pkg, config, SOURCE);
    }
    private String capture(Path pkg, Path config, String source) throws Exception {
        return PackageSourceRevision.capture(pkg, config, PackageConfig.parse(pkg, source));
    }
    @Test void tracksDeclaredDependenciesAndRootBindingsWithoutWalkingUnrelatedFiles() throws Exception {
        Path config=write("central/config/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                +"environments: {SIT: {}, UAT: {httphelpers: [config/uat.yaml]}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        write("package/testdata/base.yaml","value: first\n");
        String first=capture(pkg,config);
        write("package/package.yaml","schemaVersion: att-package/v1\n");
        write("package/output/result.txt","volatile");write("package/archive/fixture.yaml","unrelated");
        write("central/config/unreferenced.yaml","unrelated shared file");
        assertEquals(first,capture(pkg,config));
        write("package/testdata/base.yaml","value: second\n");
        String changed=capture(pkg,config);assertNotEquals(first,changed);
        write("central/config/uat.yaml","helpers: {}\n");
        String sharedChanged=capture(pkg,config);assertNotEquals(changed,sharedChanged);
        Files.createDirectories(pkg.resolve("templates"));
        assertNotEquals(sharedChanged,capture(pkg,config));
        String rootsChanged=capture(pkg,config);
        write("package/templates/example.yaml","not read by Manifest validation\n");
        assertEquals(rootsChanged,capture(pkg,config));
        write("central/config/config.yaml","schemaVersion: att-config/v2.13\nenvironment: UAT\nenvironments: {SIT: {}, UAT: {}}\n");
        assertNotEquals(rootsChanged,capture(pkg,config));
    }
    @Test void rejectsUnsafeDeclaredDependenciesButIgnoresUnrelatedSymlinksAndDeepArchives() throws Exception {
        Path config=write("central/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        write("package/archive/"+String.join("/",java.util.Collections.nCopies(20,"nested"))+"/file","ignored");
        Path outside=write("outside","secret");Files.createSymbolicLink(pkg.resolve("unrelated-link"),outside);
        assertNotNull(capture(pkg,config));
        Files.createDirectories(pkg.resolve("testdata"));Files.createSymbolicLink(pkg.resolve("testdata/base.yaml"),outside);
        Exception error=assertThrows(Exception.class,()->capture(pkg,config));
        assertFalse(error.getMessage().contains(outside.toString()));
    }
    @Test void prewarmValidationRevisionTracksDispatcherAndMemberScriptChanges() throws Exception {
        Path config = write("central/config/config.yaml",
                "schemaVersion: att-config/v2.13\nenvironment: SIT\n");
        Path pkg = Files.createDirectories(root.resolve("package"));
        write("package/tools/sample/tool-group.yaml",
                "schemaVersion: att-tool-group/v3.0\nid: sample\nname: Sample\n"
                + "description: Group\nscript: [./tools/dispatch.sh]\ntools: [date.yaml]\n");
        write("package/tools/sample/date.yaml",
                "schemaVersion: att-tool/v1\nid: date\nname: Date\n"
                + "description: Date tool\ncommand: [./tools/date.sh]\n");
        Path dispatcher = write("package/tools/dispatch.sh", "#!/bin/sh\necho dispatch\n");
        Path member = write("package/tools/date.sh", "#!/bin/sh\necho first\n");
        String prepared = capture(pkg, config);
        Files.write(member, "#!/bin/sh\necho second\n".getBytes("UTF-8"));
        String changedMember = capture(pkg, config);
        assertNotEquals(prepared, changedMember,
                "Prewarmed validation must invalidate on a member script edit");
        Files.write(dispatcher, "#!/bin/sh\necho updated\n".getBytes("UTF-8"));
        assertNotEquals(changedMember, capture(pkg, config),
                "Prewarmed validation must invalidate on a Group dispatcher edit");
    }

    @Test void prewarmRevisionTracksExtensionlessInterpreterOperands() throws Exception {
        Path config = write("central/config/config.yaml", "schemaVersion: att-config/v2.13\n");
        Path pkg = Files.createDirectories(root.resolve("package"));
        write("package/tools/sample/tool-group.yaml",
                "schemaVersion: att-tool-group/v3.0\nid: sample\nname: Sample\n"
                + "description: Group\nscript: [bash, ./tools/dispatch]\ntools: [task.yaml]\n");
        write("package/tools/sample/task.yaml",
                "schemaVersion: att-tool/v1\nid: task\nname: Task\n"
                + "description: Task\ncommand: [bash, ./tools/task]\nstdoutFormat: text\n");
        Path dispatcher = write("package/tools/dispatch", "#!/bin/sh\necho start\n");
        Path member = write("package/tools/task", "#!/bin/sh\necho first\n");
        String accepted = capture(pkg, config);
        Files.write(member, "#!/bin/sh\necho second\n".getBytes("UTF-8"));
        String afterMember = capture(pkg, config);
        assertNotEquals(accepted, afterMember, "Extensionless member must stale prewarmed source");
        Files.write(dispatcher, "#!/bin/sh\necho new\n".getBytes("UTF-8"));
        assertNotEquals(afterMember, capture(pkg, config),
                "Extensionless Group dispatcher must stale prewarmed source");
    }

    @Test void candidateValidationRevisionChangesForDeclaredDescriptorsButNotUnrelatedBusinessFiles() throws Exception {
        Path config=write("central/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        Path descriptor=write("package/testdata/base.yaml","schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: first}]\n");
        PackageManifestInspector inspector=new PackageManifestInspector(pkg,config);
        Object first=inspector.validateCandidate(SOURCE,null).get("validationRevision");
        write("package/templates/example.yaml","value: unrelated\n");
        // Creation of the conventional root changes its binding once; its contents do not.
        Object withRoot=inspector.validateCandidate(SOURCE,null).get("validationRevision");
        write("package/templates/example.yaml","value: revised\n");
        assertEquals(withRoot,inspector.validateCandidate(SOURCE,null).get("validationRevision"));
        Files.write(descriptor,"schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: second}]\n".getBytes("UTF-8"));
        assertNotEquals(withRoot,inspector.validateCandidate(SOURCE,null).get("validationRevision"));
        assertNotNull(first);assertFalse(Files.exists(pkg.resolve("package.yaml")));
    }

    @Test void candidateValidationRevisionHashesEveryListedMetricYamlButIgnoresUnlistedYaml() throws Exception {
        Path config = write("central/config.yaml", "schemaVersion: att-config/v2.13\n");
        Path pkg = Files.createDirectories(root.resolve("package"));
        Path metricSource = write("package/load/sut-metrics/sources/host.yaml", METRIC_SOURCE);
        Path group = write("package/load/sut-metrics/groups/runtime.yaml", METRIC_GROUP);
        Path profile = write("package/load/sut-metrics/profiles/default.yaml", METRIC_PROFILE);
        Path unlisted = write("package/load/sut-metrics/sources/unlisted.yaml", METRIC_SOURCE.replace("host-source", "unlisted-source"));
        String baseline = capture(pkg, config, METRIC_INVENTORY);
        Files.write(unlisted, METRIC_SOURCE.replace("host-source", "changed-unlisted-source").getBytes("UTF-8"));
        assertEquals(baseline, capture(pkg, config, METRIC_INVENTORY));

        Files.write(metricSource, METRIC_SOURCE.replace("Host metrics", "Changed metrics").getBytes("UTF-8"));
        String sourceChanged = capture(pkg, config, METRIC_INVENTORY);
        assertNotEquals(baseline, sourceChanged, "listed Metric Source bytes must be included");
        Files.write(metricSource, METRIC_SOURCE.getBytes("UTF-8"));

        Files.write(group, METRIC_GROUP.replace("Runtime", "Changed runtime").getBytes("UTF-8"));
        String groupChanged = capture(pkg, config, METRIC_INVENTORY);
        assertNotEquals(baseline, groupChanged, "listed Metric Group bytes must be included");
        Files.write(group, METRIC_GROUP.getBytes("UTF-8"));

        Files.write(profile, METRIC_PROFILE.replace("Default", "Changed default").getBytes("UTF-8"));
        String profileChanged = capture(pkg, config, METRIC_INVENTORY);
        assertNotEquals(baseline, profileChanged, "listed profile bytes must be included");
    }
}
