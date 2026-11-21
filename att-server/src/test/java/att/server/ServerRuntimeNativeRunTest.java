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

        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
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

            // Mutate the live Package after admission; the Worker must execute the frozen Definition.
            Files.writeString(definition,definitionYaml(70,130));
            Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,jobId);
            assertEquals("PASS",((Map<?,?>)result.get("job")).get("status"),result.toString());
            assertTrue(runtime.events(jobId).after(0).stream().anyMatch(event->"result".equals(event.get("event"))));

            try(ZipFile archive=new ZipFile(runtime.artifactArchivePath(jobId).toFile())) {
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
            assertEquals("PASS",((Map<?,?>)scheduledResult.get("job")).get("status"),scheduledResult.toString());
        }
    }

    private static JsonNode nativeRun(String localCaseId) {
        return ServerRuntime.JSON.valueToTree(Map.of("schemaVersion",RunDefinition.NATIVE_CASE_VERSION,
                "executionType","run","packageId","p","target",Map.of("type","nativeCase",
                "definitionId","payments.transfer","selection",Map.of("resolvedCaseIds",List.of(localCaseId))),
                "options",Map.of("debug",false,"failFast",true,"runId","native-"+localCaseId)));
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

    private static String read(InputStream input) throws Exception {
        try(input){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
}
