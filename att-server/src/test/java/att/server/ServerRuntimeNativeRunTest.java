package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end Server contract for native Test Case draft validation and immutable execution. */
class ServerRuntimeNativeRunTest {
    @TempDir Path temp;

    @Test void validatesExactNativeSelectionRejectsStaleDraftAndRunsAcceptedSourceSnapshot() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("allowed"));
        Path packageRoot=Files.createDirectories(allowed.resolve("p"));
        Path flow=Files.createDirectories(packageRoot.resolve("templates/flows/payments/transfer/v1"))
                .resolve("flow.yaml");
        Files.createDirectories(packageRoot.resolve("testcase/payments/transfer"));
        Files.writeString(flow,"schemaVersion: att-flow/v3.6\nid: payments.transfer.v1\nname: Transfer\n"
                +"description: Native Case immutable-source fixture\nactions:\n"
                +"  verify: {type: assert, assert: '#{${EXEC.INPUT.amount} <= 13}'}\n"
                +"  log: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n");
        Path definition=packageRoot.resolve("testcase/payments/transfer/testcase.yaml");
        String original=definitionYaml(7,13);
        Files.writeString(definition,original);

        Path runtimeConfig=Files.createDirectories(temp.resolve("runtime/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("server-data"),serverConfig=temp.resolve("server.yaml");
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  auth: {mode: local}\n  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"  scheduling: {enabled: true, minimumIntervalMinutes: 1, maxConcurrentRuns: 1}\n"
                +"workers: {maxConcurrent: 1, queuedLimit: 2}\npackages:\n  allowedRoots: ["
                +ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries: {p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"}\n");
        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        CountDownLatch acceptedWorkerLaunch=new CountDownLatch(1),releaseAcceptedWorker=new CountDownLatch(1);
        ServerConfig loadedServerConfig=ServerConfig.load(serverConfig);
        Path materializedSnapshots=loadedServerConfig.dataDir.resolve("job-source-closures/materialized").normalize();
        ServerRuntime.WorkerProcessLauncher processLauncher=builder->{
            Path workingDirectory=builder.directory()==null?null:builder.directory().toPath().toAbsolutePath().normalize();
            if(workingDirectory!=null&&workingDirectory.startsWith(materializedSnapshots)) {
                acceptedWorkerLaunch.countDown();
                try {
                    if(!releaseAcceptedWorker.await(10,TimeUnit.SECONDS))
                        throw new java.io.IOException("Timed out waiting to release the source-snapshot Worker");
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();throw new java.io.IOException("Interrupted before Worker launch",interrupted);
                }
            }
            return builder.start();
        };
        try(ServerRuntime runtime=new ServerRuntime(loadedServerConfig,libs.toString(),processLauncher)) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            runtime.store.createAuthUser("bob","Bob","unused","USER","csrf");
            int jobsBeforeDirectRequest=runtime.jobs.size();
            assertThrows(IllegalArgumentException.class,()->runtime.submit("run",nativeRun("first"),"alice"),
                    "A Native Case target must not bypass the validated draft endpoint");
            assertEquals(jobsBeforeDirectRequest,runtime.jobs.size(),"Direct Native Case requests must not create Jobs");

            Files.writeString(flow,"schemaVersion: att-flow/v3.6\nid: payments.transfer.v1\nname: Transfer\n"
                    +"description: Invalid Tool dependency fixture\nactions:\n"
                    +"  invoke: {type: tool, call: \"#{missing.tool()}\"}\n");
            ServerRuntime.DraftValidationException badDependency=assertThrows(ServerRuntime.DraftValidationException.class,
                    ()->runtime.createRunDraft(nativeRun("first"),"alice"),
                    "Native RUN draft validation must reject an unresolved selected Flow Tool dependency");
            assertFalse(badDependency.diagnostics.isEmpty());
            assertTrue(badDependency.diagnostics.toString().contains("missing.tool"),badDependency.diagnostics.toString());
            Files.writeString(flow,"schemaVersion: att-flow/v3.6\nid: payments.transfer.v1\nname: Transfer\n"
                    +"description: Native Case immutable-source fixture\nactions:\n"
                    +"  verify: {type: assert, assert: '#{${EXEC.INPUT.amount} <= 13}'}\n"
                    +"  log: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n");

            Map<String,Object> staleDraft=runtime.createRunDraft(nativeRun("first"),"alice");
            assertDraftSelection(staleDraft);
            assertEquals(original,Files.readString(definition),"Draft validation must not mutate the authored Definition");
            Files.writeString(definition,definitionYaml(70,13));
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.submitRunDraft(
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",staleDraft.get("draftId"))),"alice","alice"));

            Files.writeString(definition,original);
            Map<String,Object> acceptedDraft=runtime.createRunDraft(nativeRun("first"),"alice");
            assertDraftSelection(acceptedDraft);
            assertEquals("native-first",((Map<?,?>)acceptedDraft.get("options")).get("runId"));
            int jobsBeforeUnauthorizedSubmit=runtime.jobs.size();
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",acceptedDraft.get("draftId"))),"bob","bob"),
                    "A different active user must not submit Alice's validated Native Case draft");
            assertEquals(jobsBeforeUnauthorizedSubmit,runtime.jobs.size(),
                    "An unauthorized Native Case draft submission must not create a Job");
            String revision=String.valueOf(acceptedDraft.get("revisionDigest"));
            Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",acceptedDraft.get("draftId"))),"alice","alice");
            String jobId=String.valueOf(accepted.get("jobId"));
            Job job=runtime.jobs.get(jobId);
            assertEquals("native-first",job.request.runId,"The accepted Worker request preserves the explicit Native RUN ID");
            assertNotNull(job.request.sourceSnapshotArchiveSha256,"Accepted Native RUN must pin its source closure");
            assertEquals(revision,job.request.expectedRevisionDigest);
            assertEquals("testcase/payments/transfer/testcase.yaml",job.request.nativeCaseSourcePath);
            Path frozenDefinition=Path.of(job.request.sourceSnapshotDirectory).resolve("package")
                    .resolve(job.request.nativeCaseSourcePath);
            assertTrue(Files.isRegularFile(frozenDefinition),"Native Definition must be present in the accepted source snapshot");
            assertEquals(original,Files.readString(frozenDefinition));

            // Stop the accepted Worker immediately before process start, mutate the live Package,
            // then prove the Worker executes the frozen Definition rather than the live file.
            assertTrue(acceptedWorkerLaunch.await(10,TimeUnit.SECONDS),
                    "The accepted cold Worker must reach the launch barrier before the live Definition changes");
            Files.writeString(definition,definitionYaml(70,130));
            releaseAcceptedWorker.countDown();
            Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,jobId);
            assertEquals("PASS",((Map<?,?>)result.get("job")).get("status"),result.toString());
            assertTrue(runtime.events(jobId).after(0).stream().anyMatch(event->"result".equals(event.get("event"))));

            try(ZipFile archive=new ZipFile(awaitArtifactArchive(runtime,jobId).toFile())) {
                List<String> caseTrees=new ArrayList<>(),caseLogs=new ArrayList<>(),runManifests=new ArrayList<>();
                var entries=archive.entries();
                while(entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    if(entry.isDirectory())continue;
                    String name=entry.getName();
                    if(name.endsWith("/case.yaml"))caseTrees.add(read(archive.getInputStream(entry)));
                    if(name.endsWith("/case.log"))caseLogs.add(read(archive.getInputStream(entry)));
                    if(name.endsWith("/run.yaml"))runManifests.add(name);
                }
                assertEquals(1,caseTrees.size(),"Only the explicitly selected Native Case should produce case evidence");
                assertTrue(caseTrees.get(0).contains("definitionId: payments.transfer"),caseTrees.get(0));
                assertTrue(caseTrees.get(0).contains("qualifiedCaseId: payments.transfer.first"),caseTrees.get(0));
                assertFalse(caseTrees.get(0).contains("payments.transfer.second"),caseTrees.get(0));
                assertEquals(1,caseLogs.size(),"Only one selected Case should execute");
                assertTrue(caseLogs.get(0).contains("amount=7"),caseLogs.get(0));
                assertFalse(caseLogs.get(0).contains("amount=70"),caseLogs.get(0));
                assertTrue(runManifests.stream().anyMatch(name->name.contains("native-first")),
                        "The explicit runId is used for the native execution output");
            }

            Files.writeString(definition,original);
            Files.writeString(flow,"schemaVersion: att-flow/v3.6\nid: payments.transfer.v1\nname: Transfer\n"
                    +"description: Native Case FAIL outcome fixture\nactions:\n"
                    +"  verify: {type: assert, assert: '#{${EXEC.INPUT.amount} <= 12}'}\n"
                    +"  log: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n");
            Map<String,Object> scheduledDraft=runtime.createRunDraft(nativeRun("second"),"alice");
            LoadSchedule schedule=runtime.createLoadSchedule(ServerRuntime.JSON.valueToTree(Map.of("name","Native Case schedule",
                    "packageId","p","executionType","run","draftId",scheduledDraft.get("draftId"),
                    "timezone","UTC","cron","0 2 * * *","enabled",false)),"alice");
            assertEquals("payments.transfer",schedule.runInput.get("nativeCaseDefinitionId"));
            assertEquals(List.of("second"),schedule.runInput.get("nativeResolvedCaseIds"));
            Map<String,Object> occurrence=runtime.triggerLoadSchedule(schedule.id,"alice");
            String scheduledJobId=String.valueOf(occurrence.get("jobId"));
            assertEquals("native-second",runtime.jobs.get(scheduledJobId).request.runId,
                    "The scheduled Native RUN retains its selected runId");
            Map<String,Object> scheduledResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,scheduledJobId);
            assertEquals("FAIL",((Map<?,?>)scheduledResult.get("job")).get("status"),scheduledResult.toString());
            assertNativeCaseStatus(runtime,scheduledJobId,"payments.transfer.second","FAIL");

            Path errorFlow=Files.createDirectories(packageRoot.resolve("templates/flows/payments/error/v1"))
                    .resolve("flow.yaml");
            Files.writeString(errorFlow,"schemaVersion: att-flow/v3.6\nid: payments.error.v1\nname: Error outcome\n"
                    +"description: Native Case ERROR outcome fixture\nactions:\n  call:\n    type: tool\n"
                    +"    call: \"#{upper('ok')}\"\n    assert: >-\n      ${output.result.missing} == 'OK'\n");
            Path errorDefinition=packageRoot.resolve("testcase/payments/error/testcase.yaml");
            Files.createDirectories(errorDefinition.getParent());
            Files.writeString(errorDefinition,"schemaVersion: att-testcase/v1\nid: payments.error\nname: Error outcome\n"
                    +"flow: payments.error.v1\ndataSource:\n  type: inline\n  records:\n"
                    +"    - recordId: error\n      data: {amount: 7}\ninputs:\n  amount:\n    from: DATA.amount\n");
            Map<String,Object> errorDraft=runtime.createRunDraft(nativeRun("payments.error","error"),"alice");
            assertEquals(List.of("error"),errorDraft.get("selectedCaseIds"));
            Map<String,Object> errorAccepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",errorDraft.get("draftId"))),"alice","alice");
            String errorJobId=String.valueOf(errorAccepted.get("jobId"));
            Map<String,Object> errorResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,errorJobId);
            assertEquals("ERROR",((Map<?,?>)errorResult.get("job")).get("status"),errorResult.toString());
            assertNativeCaseStatus(runtime,errorJobId,"payments.error.error","ERROR");
        } finally {
            releaseAcceptedWorker.countDown();
        }
    }

    @Test void reversedMultiCaseSelectionUsesSourceOrderForPreviewAndFailFast() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("allowed"));
        Path packageRoot=Files.createDirectories(allowed.resolve("p"));
        Path flow=Files.createDirectories(packageRoot.resolve("templates/flows/payments/transfer/v1"))
                .resolve("flow.yaml");
        Files.createDirectories(packageRoot.resolve("testcase/payments/transfer"));
        Files.writeString(flow,"schemaVersion: att-flow/v3.6\nid: payments.transfer.v1\nname: Transfer\n"
                +"description: Reversed selection fail-fast fixture\nactions:\n"
                +"  verify: {type: assert, assert: '#{${EXEC.INPUT.amount} <= 12}'}\n"
                +"  log: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n");
        Path definition=packageRoot.resolve("testcase/payments/transfer/testcase.yaml");
        Files.writeString(definition,definitionYaml(70,13));

        Path runtimeConfig=Files.createDirectories(temp.resolve("runtime/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("server-data"),serverConfig=temp.resolve("server.yaml");
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  auth: {mode: local}\n  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"workers: {maxConcurrent: 1, queuedLimit: 2}\npackages:\n  allowedRoots: ["
                +ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries: {p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"}\n");
        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            JsonNode reversed=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion",RunDefinition.NATIVE_CASE_VERSION,
                    "executionType","run","packageId","p","target",Map.of("type","nativeCase",
                    "definitionId","payments.transfer","selection",Map.of("resolvedCaseIds",List.of("second","first"))),
                    "options",Map.of("debug",false,"failFast",true,"runId","native-reversed")));
            Map<String,Object> draft=runtime.createRunDraft(reversed,"alice");
            assertEquals(List.of("first","second"),draft.get("selectedCaseIds"),
                    "Draft preview must expose S01 Definition/source order, not request order");
            @SuppressWarnings("unchecked") List<Map<String,Object>> cases=(List<Map<String,Object>>)draft.get("cases");
            assertEquals(List.of("first","second"),
                    List.of(String.valueOf(cases.get(0).get("caseId")),String.valueOf(cases.get(1).get("caseId"))));

            Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",draft.get("draftId"))),"alice","alice");
            String jobId=String.valueOf(accepted.get("jobId"));
            assertEquals(List.of("first","second"),runtime.jobs.get(jobId).request.nativeResolvedCaseIds);
            Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,jobId);
            assertEquals("FAIL",((Map<?,?>)result.get("job")).get("status"),result.toString());
            try(ZipFile archive=new ZipFile(awaitArtifactArchive(runtime,jobId).toFile())) {
                List<String> caseTrees=new ArrayList<>();var entries=archive.entries();
                while(entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    if(!entry.isDirectory()&&entry.getName().endsWith("/case.yaml"))
                        caseTrees.add(read(archive.getInputStream(entry)));
                }
                assertEquals(1,caseTrees.size(),"failFast must stop after the first source-order failure");
                assertTrue(caseTrees.get(0).contains("qualifiedCaseId: payments.transfer.first"),caseTrees.get(0));
                assertTrue(caseTrees.get(0).contains("status: FAIL"),caseTrees.get(0));
                assertFalse(caseTrees.get(0).contains("payments.transfer.second"),caseTrees.get(0));
            }
        }
    }

    @Test void nativeCaseReplayStrictRejectsMutationAndCurrentRetainsSelectionWithLineage() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("allowed"));
        Path packageRoot=Files.createDirectories(allowed.resolve("p"));
        Path flow=Files.createDirectories(packageRoot.resolve("templates/flows/payments/transfer/v1"))
                .resolve("flow.yaml");
        Files.createDirectories(packageRoot.resolve("testcase/payments/transfer"));
        Files.writeString(flow,"schemaVersion: att-flow/v3.6\nid: payments.transfer.v1\nname: Transfer\n"
                +"description: Native Case replay fixture\nactions:\n"
                +"  verify: {type: assert, assert: '#{${EXEC.INPUT.amount} <= 130}'}\n"
                +"  log: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n");
        Path definition=packageRoot.resolve("testcase/payments/transfer/testcase.yaml");
        String originalDefinition=definitionYaml(7,13);
        Files.writeString(definition,originalDefinition);

        Path runtimeConfig=Files.createDirectories(temp.resolve("runtime/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("server-data"),serverConfig=temp.resolve("server.yaml");
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  auth: {mode: local}\n  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"workers: {maxConcurrent: 1, queuedLimit: 2}\npackages:\n  allowedRoots: ["
                +ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries: {p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"}\n");
        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            Map<String,Object> acceptedDraft=runtime.createRunDraft(nativeRun("first"),"alice");
            assertDraftSelection(acceptedDraft);
            Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",acceptedDraft.get("draftId"))),"alice","alice");
            String original=String.valueOf(accepted.get("jobId"));
            Map<String,Object> originalResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,original);
            assertEquals("PASS",((Map<?,?>)originalResult.get("job")).get("status"),originalResult.toString());
            String originalRevision=JobExecutionSpec.parse(runtime.store.executionSpec(original).get("spec")).revision();
            long jobsBeforeReplay=runtime.jobs.size();

            Files.writeString(definition,definitionYaml(70,13));
            ServerRuntime.JobConflictException strict=assertThrows(ServerRuntime.JobConflictException.class,
                    ()->runtime.createJobReplayDraft(original,"alice",
                            ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","strict"))),
                    "Strict native Case replay must reject a mutated Package revision");
            assertEquals("ATT-SERVER-REPLAY-REVISION-CONFLICT",strict.code);
            assertEquals(jobsBeforeReplay,runtime.jobs.size(),"A rejected strict replay must not create a Job");

            Map<String,Object> review=runtime.createJobReplayDraft(original,"alice",
                    ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current")));
            assertEquals("att-job-replay-draft/v1",review.get("schemaVersion"));
            assertEquals("current",review.get("sourcePolicy"));
            assertEquals(original,review.get("sourceJobId"));
            assertEquals(originalRevision,review.get("originalRevision"));
            assertNotEquals(originalRevision,review.get("currentRevision"));
            assertNotNull(review.get("intentId"),"A valid current-source native replay must include a confirmation intent");
            assertEquals(List.of("first"),((Map<?,?>)review.get("preview")).get("selectedCaseIds"),
                    "Current-source replay must retain the original local Case ID selection");
            assertEquals("first",ServerRuntime.JSON.valueToTree(review.get("definition")).path("target")
                    .path("selection").path("resolvedCaseIds").get(0).asText());

            Map<String,Object> rerun=runtime.rerunJob(original,replayConfirmation(review,true,"current"),"alice");
            String rerunId=String.valueOf(rerun.get("jobId"));
            assertEquals(original,runtime.jobRecord(rerunId).get("rerunOfJobId"));
            assertEquals(rerunId,runtime.rerunJob(original,replayConfirmation(review,true,"current"),"alice").get("jobId"),
                    "Confirming the same replay intent is idempotent");
            JobExecutionSpec rerunSpec=JobExecutionSpec.parse(runtime.store.executionSpec(rerunId).get("spec"));
            assertEquals("current",rerunSpec.value.path("replay").path("sourcePolicy").asText());
            assertEquals(originalRevision,rerunSpec.value.path("replay").path("originalRevision").asText());
            assertEquals(review.get("currentRevision"),rerunSpec.value.path("replay").path("effectiveRevision").asText());
            assertEquals(List.of("first"),runtime.jobs.get(rerunId).request.nativeResolvedCaseIds);
            Map<String,Object> rerunResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,rerunId);
            assertEquals("PASS",((Map<?,?>)rerunResult.get("job")).get("status"),rerunResult.toString());
            try(ZipFile archive=new ZipFile(awaitArtifactArchive(runtime,rerunId).toFile())) {
                List<String> caseLogs=new ArrayList<>();var entries=archive.entries();
                while(entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    if(!entry.isDirectory()&&entry.getName().endsWith("/case.log"))
                        caseLogs.add(read(archive.getInputStream(entry)));
                }
                assertEquals(1,caseLogs.size(),"Current-source replay executes only the retained native Case");
                assertTrue(caseLogs.get(0).matches("(?s).*\\bamount=70\\b.*"),
                        "Current-source replay must execute the mutated Definition: "+caseLogs.get(0));
                assertFalse(caseLogs.get(0).matches("(?s).*\\bamount=7\\b.*"),
                        "Current-source replay must not reuse the original accepted amount: "+caseLogs.get(0));
            }

            Files.writeString(definition,definitionYamlWithoutFirst(13));
            long jobsBeforeInvalid=runtime.jobs.size();
            Map<String,Object> invalid=runtime.createJobReplayDraft(original,"alice",
                    ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current")));
            assertEquals("invalid",invalid.get("validationState"));
            assertEquals("ATT-SERVER-REPLAY-VALIDATION-FAILED",invalid.get("errorCode"));
            assertNull(invalid.get("intentId"),
                    "A removed selected Case must not receive a runnable confirmation intent");
            assertEquals("first",ServerRuntime.JSON.valueToTree(invalid.get("definition")).path("target")
                    .path("selection").path("resolvedCaseIds").get(0).asText(),
                    "Invalid current-source replay retains the original selection instead of guessing another Case");
            assertFalse(((List<?>)invalid.get("diagnostics")).isEmpty());
            assertEquals(jobsBeforeInvalid,runtime.jobs.size(),
                    "An invalid native Case replay review must not create a Job");
            assertEquals(originalRevision,JobExecutionSpec.parse(runtime.store.executionSpec(original).get("spec")).revision(),
                    "Replay validation must not rewrite the original Job execution spec");
        }
    }

    private static JsonNode nativeRun(String localCaseId) {
        return nativeRun("payments.transfer",localCaseId);
    }

    private static JsonNode nativeRun(String definitionId,String localCaseId) {
        return ServerRuntime.JSON.valueToTree(Map.of("schemaVersion",RunDefinition.NATIVE_CASE_VERSION,
                "executionType","run","packageId","p","target",Map.of("type","nativeCase",
                "definitionId",definitionId,"selection",Map.of("resolvedCaseIds",List.of(localCaseId))),
                "options",Map.of("debug",false,"failFast",true,"runId","native-"+localCaseId)));
    }

    private static void assertNativeCaseStatus(ServerRuntime runtime,String jobId,String qualifiedCaseId,String status)
            throws Exception {
        try(ZipFile archive=new ZipFile(awaitArtifactArchive(runtime,jobId).toFile())) {
            List<String> caseTrees=new ArrayList<>();var entries=archive.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(!entry.isDirectory()&&entry.getName().endsWith("/case.yaml"))
                    caseTrees.add(read(archive.getInputStream(entry)));
            }
            assertEquals(1,caseTrees.size(),"One explicitly selected Native Case should produce canonical evidence");
            assertTrue(caseTrees.get(0).contains("qualifiedCaseId: "+qualifiedCaseId),caseTrees.get(0));
            assertTrue(caseTrees.get(0).contains("status: "+status),caseTrees.get(0));
        }
    }

    private static Path awaitArtifactArchive(ServerRuntime runtime,String jobId) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        Map<String,Object> index=runtime.artifactIndex(jobId,0,1);
        while(!"READY".equals(index.get("archiveStatus"))&&System.nanoTime()<deadline) {
            if("FAILED".equals(index.get("archiveStatus")))fail("Artifact publication failed for "+jobId+": "+index);
            Thread.sleep(25);
            index=runtime.artifactIndex(jobId,0,1);
        }
        assertEquals("READY",index.get("archiveStatus"),"Native RUN evidence must be published before inspection");
        return runtime.artifactArchivePath(jobId);
    }

    private static void assertDraftSelection(Map<String,Object> draft) {
        assertEquals(RunDefinition.NATIVE_CASE_VERSION,draft.get("schemaVersion"));
        assertEquals(Map.of("type","nativeCase","definitionId","payments.transfer"),draft.get("target"));
        assertEquals(List.of("first"),draft.get("selectedCaseIds"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> cases=(List<Map<String,Object>>)draft.get("cases");
        assertNotNull(cases,"Native preview must expose resolved Case identities and provenance");
        assertEquals(1,cases.size());
        assertEquals("payments.transfer.first",cases.get(0).get("qualifiedCaseId"));
        assertEquals("first",cases.get(0).get("sourceRecordId"));
    }

    private static String definitionYaml(int firstAmount,int secondAmount) {
        return "schemaVersion: att-testcase/v1\nid: payments.transfer\nname: Transfer\nflow: payments.transfer.v1\n"
                +"dataSource:\n  type: inline\n  records:\n"
                +"    - recordId: first\n      data: {amount: "+firstAmount+"}\n"
                +"    - recordId: second\n      data: {amount: "+secondAmount+"}\n"
                +"inputs:\n  amount:\n    from: DATA.amount\n";
    }

    private static String definitionYamlWithoutFirst(int secondAmount) {
        return "schemaVersion: att-testcase/v1\nid: payments.transfer\nname: Transfer\nflow: payments.transfer.v1\n"
                +"dataSource:\n  type: inline\n  records:\n"
                +"    - recordId: second\n      data: {amount: "+secondAmount+"}\n"
                +"inputs:\n  amount:\n    from: DATA.amount\n";
    }

    private static JsonNode replayConfirmation(Map<String,Object> review,boolean confirm,String sourcePolicy) {
        var body=ServerRuntime.JSON.createObjectNode();body.put("confirm",confirm);
        Object intent=review.get("intentId");if(intent!=null)body.put("intentId",String.valueOf(intent));
        if(sourcePolicy!=null)body.put("sourcePolicy",sourcePolicy);
        return body;
    }

    private static String read(InputStream input) throws Exception {
        try(input){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
}
