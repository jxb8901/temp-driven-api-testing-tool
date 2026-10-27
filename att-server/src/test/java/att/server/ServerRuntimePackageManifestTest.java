package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimePackageManifestTest {
    @TempDir Path temp;
    private Path pkg;
    private ServerRuntime runtime(String policy) throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("packages"));pkg=Files.createDirectories(allowed.resolve("owner"));
        Path config=Files.createDirectories(temp.resolve("central/config")).resolve("config.yaml");
        Files.writeString(config,"schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}, UAT: {}}\n");
        Path template=Files.createDirectories(pkg.resolve("templates/FORM")).resolve("template.yaml");
        Files.writeString(template,"schemaVersion: att-template/v3.6\nname: FORM\ndescription: Manifest integration\nactions:\n  log:\n    type: log\n    message: hello\n");
        Files.createDirectories(pkg.resolve("testcase"));
        Path server=temp.resolve("server.yaml");
        Files.writeString(server,"server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("data"))+"\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(config)+"\n"
                +"  packageConfiguration:\n    sourceEditingEnabled: true\n"+policy
                +"  inlineLoad: {enabled: true}\n  scheduling: {enabled: true}\nworkers: {}\npackages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries: {owner: "+ServerRuntimeTestSupport.yaml(pkg)+"}\n");
        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        return new ServerRuntime(ServerConfig.load(server),libs.toString());
    }
    @Test void stagesPrivateWorkerCandidateAndPublishesOnlyForRegisteredOwner() throws Exception {
        try(var runtime=runtime("")) {
            String source="schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n";
            String before=PackageManifestPublisher.digest(new byte[0]);
            assertThrows(PackageManifestPublisher.Denied.class,()->runtime.manifestPublisher.stage("owner","alice",source,before));
            assertThrows(PackageManifestPublisher.Missing.class,()->runtime.manifestPublisher.stage("unknown","unknown",source,before));
            String id=(String)runtime.manifestPublisher.stage("owner","owner",source,before).get("draftId");
            assertFalse(Files.exists(pkg.resolve("package.yaml")));
            Map<String,Object> validated=runtime.manifestPublisher.validate("owner","owner",id);
            assertFalse(Files.exists(pkg.resolve("package.yaml")));
            assertEquals("validated",validated.get("state"));
            runtime.manifestPublisher.review("owner","owner",id);runtime.manifestPublisher.publish("owner","owner",id);
            assertEquals(source,Files.readString(pkg.resolve("package.yaml")));
            assertEquals("UAT",runtime.inspectPackageConfiguration("owner","effective",null,"alice").get("environment"));
            assertEquals(false,runtime.inspectPackageConfiguration("owner","declared",null,"alice").get("canEdit"));
            assertEquals(true,runtime.inspectPackageConfiguration("owner","declared",null,"owner").get("canEdit"));
        }
    }
    @Test void publishesSmallManifestInLargePackageAndStillFencesDeclaredDependencyChanges() throws Exception {
        try(var runtime=runtime("")) {
            Path archive=Files.createDirectories(pkg.resolve("archive"));
            // Sparse file crosses the previous 256 MiB whole-tree cap without allocating that much disk.
            try(var file=new java.io.RandomAccessFile(archive.resolve("business-input.bin").toFile(),"rw")) {
                file.setLength(268435457L);
            }
            for(int i=0;i<10001;i++)Files.createFile(archive.resolve("fixture-"+i));
            Path descriptor=Files.createDirectories(pkg.resolve("testdata")).resolve("base.yaml");
            Files.writeString(descriptor,"schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: first}]\n");
            String source="schemaVersion: att-package/v1\ndefaultEnvironment: UAT\ntestdata: {descriptors: [testdata/base.yaml]}\n";
            String before=PackageManifestPublisher.digest(new byte[0]);
            String id=(String)runtime.manifestPublisher.stage("owner","owner",source,before).get("draftId");
            runtime.manifestPublisher.validate("owner","owner",id);runtime.manifestPublisher.review("owner","owner",id);
            Files.writeString(archive.resolve("unrelated.yaml"),"changed after review");
            assertEquals(true,runtime.manifestPublisher.publish("owner","owner",id).get("published"));
            assertEquals(source,Files.readString(pkg.resolve("package.yaml")));
            String updated=source+"metadata: {name: Revised}\n";
            String next=(String)runtime.manifestPublisher.stage("owner","owner",updated,PackageManifestPublisher.digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))).get("draftId");
            runtime.manifestPublisher.validate("owner","owner",next);runtime.manifestPublisher.review("owner","owner",next);
            Files.writeString(descriptor,"schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: second}]\n");
            assertThrows(PackageManifestPublisher.Conflict.class,()->runtime.manifestPublisher.publish("owner","owner",next));
            assertEquals(source,Files.readString(pkg.resolve("package.yaml")));
        }
    }
    @Test void candidateDescriptorFailureIncludesRegisteredPackageAndSafeDeclaration() throws Exception {
        try(var runtime=runtime("")) {
            String source="schemaVersion: att-package/v1\ntestdata: {descriptors: [testdata/missing.yaml]}\n";
            String id=(String)runtime.manifestPublisher.stage("owner","owner",source,PackageManifestPublisher.digest(new byte[0])).get("draftId");
            ServerRuntime.DraftValidationException failure=assertThrows(ServerRuntime.DraftValidationException.class,()->runtime.manifestPublisher.validate("owner","owner",id));
            assertEquals("owner",failure.diagnostics.get(0).get("packageId"));
            String diagnostics=failure.diagnostics.toString();assertTrue(diagnostics.contains("testdata/missing.yaml"),diagnostics);
            assertTrue(diagnostics.contains("missing"),diagnostics);assertTrue(diagnostics.contains("Manifest/env"),diagnostics);
            assertFalse(diagnostics.contains(temp.toString()),diagnostics);assertFalse(Files.exists(pkg.resolve("package.yaml")));
        }
    }

    @Test void retainsApprovedOriginThroughJobsSchedulesArtifactsReplayAndRestart() throws Exception {
        String jobId,scheduleId;Map<String,Object> approved;
        try(var runtime=runtime("")) {
            Files.writeString(pkg.resolve("package.yaml"),"schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n");
            var body=ServerRuntime.JSON.createObjectNode();body.put("packageId","owner");body.put("model","virtualUsers");
            body.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","FORM")));
            body.set("input",ServerRuntime.JSON.valueToTree(Map.of()));body.set("load",ServerRuntime.JSON.valueToTree(Map.of("users",1,"duration","100ms")));
            Map<String,Object> draft=runtime.createQuickLoadDraft(body,"owner");
            approved=(Map<String,Object>)draft.get("configuration");
            assertEquals("package",approved.get("environmentOrigin"));assertEquals("UAT",approved.get("environment"));
            assertEquals("owner",approved.get("packageId"));assertNotNull(approved.get("packageRevision"));
            assertFalse(approved.toString().contains(temp.toString()));
            var scheduleRequest=ServerRuntime.JSON.valueToTree(Map.of("name","Pinned Manifest","packageId","owner","draftId",draft.get("draftId"),"timezone","UTC","cron","0 2 * * *","enabled",false));
            LoadSchedule schedule=runtime.createLoadSchedule(scheduleRequest,"owner");scheduleId=schedule.id;
            assertEquals(approved,schedule.configuration);
            var submitted=runtime.submitQuickLoadDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","owner","draftId",draft.get("draftId"))),"owner");
            jobId=(String)submitted.get("jobId");assertEquals(approved,submitted.get("configuration"));
            ServerRuntimeTestSupport.awaitUnifiedJob(runtime,jobId);
            Map<String,byte[]> originalEvidence=captureConfigurationEvidence(runtime,jobId,approved);
            assertEquals("PASS",runtime.jobRecord(jobId).get("status"),runtime.resultRecord(jobId).toString());
            assertEquals(ServerRuntime.JSON.valueToTree(approved),ServerRuntime.JSON.valueToTree(runtime.resultRecord(jobId).get("result")).path("summary").get("configuration"));
            assertEquals(approved,runtime.jobReplay(jobId,"owner").get("configuration"));
            Map<String,Object> review=runtime.createJobReplayDraft(jobId,"owner");
            assertEquals(approved,((Map<?,?>)review.get("preview")).get("configuration"));
            Map<String,Object> replayed=runtime.rerunJob(jobId,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId"))),"owner");
            String replayedId=String.valueOf(replayed.get("jobId"));
            ServerRuntimeTestSupport.awaitUnifiedJob(runtime,replayedId);
            assertEquals("package",((Map<?,?>)runtime.jobRecord(replayedId).get("configuration")).get("environmentOrigin"));
            JobExecutionSpec replayedSpec=JobExecutionSpec.parse(runtime.store.executionSpec(replayedId).get("spec"));
            assertEquals("package",replayedSpec.configuration().get("environmentOrigin"));
            assertEquals("package.yaml",((Map<?,?>)replayedSpec.configuration().get("authoredPaths")).get("environment"));
            assertEquals(approved,runtime.jobRecord(jobId).get("configuration"),"Rerunning must leave the original Job configuration unchanged");
            Path originalOutput=runtime.config.dataDir.resolve("jobs").resolve(jobId).resolve("output");
            if(Files.isDirectory(originalOutput))for(Map.Entry<String,byte[]> entry:originalEvidence.entrySet())
                assertArrayEquals(entry.getValue(),Files.readAllBytes(originalOutput.resolve(entry.getKey())),"Rerunning must not change the original Job's evidence");
            var forged=ServerRuntime.JSON.createObjectNode();forged.put("packageId","owner");forged.set("configurationProvenance",ServerRuntime.JSON.valueToTree(approved));
            assertThrows(IllegalArgumentException.class,()->runtime.submit("debug",forged,"owner"));
        }
        try(var reopened=runtime("")) {
            assertEquals(approved,reopened.jobRecord(jobId).get("configuration"));
            assertEquals(approved,reopened.loadSchedule(scheduleId).configuration);
            assertEquals(approved,reopened.jobReplay(jobId,"owner").get("configuration"));
            Map<String,Object> occurrence=reopened.triggerLoadSchedule(scheduleId,"owner");
            assertNotNull(occurrence.get("jobId"),occurrence.toString());
            String scheduled=(String)occurrence.get("jobId");ServerRuntimeTestSupport.awaitUnifiedJob(reopened,scheduled);
            assertEquals("PASS",reopened.jobRecord(scheduled).get("status"),reopened.resultRecord(scheduled).toString());
            assertEquals(approved,reopened.jobRecord(scheduled).get("configuration"));
        }
    }

    private static Map<String,byte[]> captureConfigurationEvidence(ServerRuntime runtime,String jobId,Map<String,Object> approved) throws Exception {
        Path output=runtime.config.dataDir.resolve("jobs").resolve(jobId).resolve("output");
        Map<String,byte[]> found=new java.util.LinkedHashMap<>();
        try(var files=Files.walk(output)) {
            List<Path> evidence=files.filter(file->file.getFileName().toString().equals("configuration.json")).toList();
            assertTrue(evidence.size()>=2,"Job and operation artifacts must retain evidence before archival");
            for(Path file:evidence) {
                byte[] bytes=Files.readAllBytes(file);
                assertEquals(approved,ServerRuntime.JSON.readValue(bytes,Map.class));
                found.put(output.relativize(file).toString(),bytes);
            }
        }
        return found;
    }

    @Test void pinsResolvedEnvironmentAndRejectsImplicitRestrictedDefaultsBeforeTargetValidation() throws Exception {
        try(var runtime=runtime("    allowedEnvironments: [SIT, UAT]\n    requireExplicitEnvironments: [UAT]\n")) {
            Files.writeString(pkg.resolve("package.yaml"),"schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n");
            var body=ServerRuntime.JSON.createObjectNode();body.put("packageId","owner");body.put("model","virtualUsers");
            body.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","FORM")));
            body.set("input",ServerRuntime.JSON.valueToTree(Map.of()));body.set("load",ServerRuntime.JSON.valueToTree(Map.of("users",1,"duration","1s")));
            Map<String,Object> draft=runtime.createQuickLoadDraft(body,"alice");
            assertEquals("SIT",draft.get("environment"));
            Files.writeString(pkg.resolve("package.yaml"),"schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n");
            assertEquals("SIT",runtime.getQuickLoadDraft((String)draft.get("draftId"),"alice").get("environment"));
            assertThrows(PackageConfigurationPolicy.EnvironmentDeniedException.class,()->runtime.createQuickLoadDraft(body,"alice"));
            body.put("environment","uat");assertEquals("UAT",runtime.createQuickLoadDraft(body,"alice").get("environment"));
        }
    }
}
