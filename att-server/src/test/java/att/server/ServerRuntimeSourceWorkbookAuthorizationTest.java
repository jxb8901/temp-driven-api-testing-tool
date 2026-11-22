package att.server;

import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeSourceWorkbookAuthorizationTest {
    @TempDir Path temp;

    @Test void backgroundPublicationUsesTrustedManualIdentityAndCurrentEditorGrant() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("packages"));
        Path shared=Files.createDirectories(allowed.resolve("shared"));
        Path admin=Files.createDirectories(allowed.resolve("admin"));
        Path runtimeConfig=Files.createDirectories(temp.resolve("runtime/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.15\nenvironment: SIT\nenvironments: {SIT: {}}\n");
        Path serverConfig=temp.resolve("server.yaml");
        writeServerConfig(serverConfig,allowed,shared,admin,"alice");
        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        try(var runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            Map<String,Object> alice=manualJob("alice");
            assertFalse(runtime.canEditPackageContent("shared","alice"),
                    "The request-scoped container USER check is absent on the archive worker thread");
            assertTrue(runtime.withPackageSourceAuthority("alice",login->true,
                    ()->runtime.canEditPackageContent("shared","alice")));
            assertTrue(runtime.canPublishNativeSourceWorkbook(alice),
                    "A persisted manual Job from the currently assigned editor may publish the optional report");

            assertFalse(runtime.canPublishNativeSourceWorkbook(manualJob("bob")),
                    "A manual Job from a non-editor cannot publish a source workbook");
            Map<String,Object> mismatchedSubmitter=new HashMap<>(alice);
            mismatchedSubmitter.put("submittedBy","bob");
            assertFalse(runtime.canPublishNativeSourceWorkbook(mismatchedSubmitter));
            Map<String,Object> mismatchedExecutor=new HashMap<>(alice);
            mismatchedExecutor.put("executionPrincipal","bob");
            assertFalse(runtime.canPublishNativeSourceWorkbook(mismatchedExecutor));
            Map<String,Object> scheduled=new HashMap<>(alice);
            scheduled.put("submissionSource","scheduled");
            scheduled.put("originatingScheduleId","S123");
            scheduled.put("scheduleId","S123");
            assertFalse(runtime.canPublishNativeSourceWorkbook(scheduled));
            Map<String,Object> ambiguous=new HashMap<>(alice);
            ambiguous.put("triggeredBy","scheduler");
            assertFalse(runtime.canPublishNativeSourceWorkbook(ambiguous));

            String jobId="J0123456789ABCDEF";
            Path jobDirectory=Files.createDirectories(temp.resolve("data/jobs").resolve(jobId));
            Job job=new Job(jobId,"run","shared","alice",new WorkerRequest(),
                    new JobEvents(jobDirectory.resolve("events.jsonl"),10));
            job.status="PASS";
            job.submissionSource="manual";
            job.executionPrincipal="alice";
            job.submittedBy="alice";
            runtime.store.insert(job,"{}");

            assertThrows(ServerRuntime.AuthoringSourceDeniedException.class,
                    ()->runtime.withPackageSourceAuthority("alice",login->false,
                            ()->runtime.archiveEntry(jobId,ArtifactArchive.NATIVE_SOURCE_WORKBOOK_XLSX,"alice")),
                    "The current request's revoked USER role must prevent reading the private entry");
            assertThrows(ServerRuntime.AuthoringSourceDeniedException.class,
                    ()->runtime.withPackageSourceAuthority("bob",login->true,
                            ()->runtime.archiveEntry(jobId,ArtifactArchive.NATIVE_SOURCE_WORKBOOK_XLSX,"bob")),
                    "A current USER without the package editor grant cannot read the private entry");

            writeServerConfig(serverConfig,allowed,shared,admin,"bob");
            runtime.packageRegistry.pollNow();
            Thread.sleep(PackageRegistry.DEBOUNCE_MILLIS+50);
            runtime.packageRegistry.pollNow();
            assertEquals(List.of("bob"),runtime.packageRegistry.snapshot().editors.get("shared"));
            assertFalse(runtime.canPublishNativeSourceWorkbook(alice),
                    "Publication rechecks the latest package editor assignment");
            assertTrue(runtime.canPublishNativeSourceWorkbook(manualJob("bob")));
            assertThrows(ServerRuntime.AuthoringSourceDeniedException.class,
                    ()->runtime.withPackageSourceAuthority("alice",login->true,
                            ()->runtime.archiveEntry(jobId,ArtifactArchive.NATIVE_SOURCE_WORKBOOK_XLSX,"alice")),
                    "A package editor grant revoked after publication authorization cannot read the private entry");
        }
    }

    private Map<String,Object> manualJob(String principal) {
        return Map.of("jobId","J0123456789ABCDEF","command","run","packageId","shared",
                "principal",principal,"submissionSource","manual","executionPrincipal",principal,
                "submittedBy",principal);
    }

    private static void writeServerConfig(Path serverConfig,Path allowed,Path shared,Path admin,String editor)
            throws Exception {
        Files.writeString(serverConfig,"server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(serverConfig.getParent().resolve("data"))+"\n"
                +"  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(serverConfig.getParent().resolve("runtime/config/config.yaml"))+"\n"
                +"  authenticationRequired: true\n  auth: {mode: container}\n"
                +"  packageConfiguration: {sourceEditingEnabled: true}\nworkers: {}\npackages:\n"
                +"  allowedRoots: ["+ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries:\n"
                +"    shared:\n      path: "+ServerRuntimeTestSupport.yaml(shared)+"\n      editors: ["+editor+"]\n"
                +"    admin:\n      path: "+ServerRuntimeTestSupport.yaml(admin)+"\n      editors: [admin]\n");
    }
}
