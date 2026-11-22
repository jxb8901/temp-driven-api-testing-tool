package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
        Path excelDefinition=packageRoot.resolve("testcase/payments/excel/testcase.yaml");
        Files.createDirectories(excelDefinition.getParent());
        Files.writeString(excelDefinition,excelDefinitionYaml());
        Path excelWorkbook=writeExcelWorkbook(packageRoot.resolve("testdata/native-cases.xlsx"));
        byte[] originalExcel=Files.readAllBytes(excelWorkbook);

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

            Map<String,Object> excelDraft=runtime.createRunDraft(nativeRun("payments.excel","beta"),"alice");
            assertEquals(List.of("beta"),excelDraft.get("selectedCaseIds"));
            @SuppressWarnings("unchecked") List<Map<String,Object>> excelCases=(List<Map<String,Object>>)excelDraft.get("cases");
            assertEquals(1,excelCases.size(),"Excel preview exposes only the explicitly selected Case");
            assertEquals("payments.excel.beta",excelCases.get(0).get("qualifiedCaseId"));
            assertEquals("beta",excelCases.get(0).get("sourceRecordId"));
            Map<?,?> excelProvenance=(Map<?,?>)excelCases.get(0).get("provenance");
            assertEquals("excel",excelProvenance.get("sourceType"));
            assertEquals("Cases",excelProvenance.get("sheet"));
            assertEquals(Integer.valueOf(3),excelProvenance.get("row"));
            assertArrayEquals(originalExcel,Files.readAllBytes(excelWorkbook),"Native Excel draft validation must not mutate the source workbook");

            Files.write(excelWorkbook,"changed after validation".getBytes(StandardCharsets.UTF_8));
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",excelDraft.get("draftId"))),"alice","alice"),
                    "Changing the source workbook after preview makes the accepted draft stale");
            Files.write(excelWorkbook,originalExcel);

            Map<String,Object> acceptedExcelDraft=runtime.createRunDraft(nativeRun("payments.excel","beta"),"alice");
            Map<String,Object> acceptedExcel=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",acceptedExcelDraft.get("draftId"))),"alice","alice");
            String excelJobId=String.valueOf(acceptedExcel.get("jobId"));
            Job excelJob=runtime.jobs.get(excelJobId);
            assertEquals("payments.excel",excelJob.request.nativeCaseDefinitionId);
            Path frozenExcel=Path.of(excelJob.request.sourceSnapshotDirectory).resolve("package/testdata/native-cases.xlsx");
            assertArrayEquals(originalExcel,Files.readAllBytes(frozenExcel),"Accepted Native RUN must pin the exact workbook revision");
            Map<String,Object> excelResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,excelJobId);
            assertEquals("PASS",((Map<?,?>)excelResult.get("job")).get("status"),excelResult.toString());
            assertNativeCaseStatus(runtime,excelJobId,"payments.excel.beta","PASS");
            assertArrayEquals(originalExcel,Files.readAllBytes(excelWorkbook),"Native Excel RUN must leave the source workbook byte-identical");

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
                +"  scheduling: {enabled: true, minimumIntervalMinutes: 1, maxConcurrentRuns: 1}\n"
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

    @Test void implicitSuiteDraftFreezesSelectionForJobsAndSchedules() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("suite-allowed"));
        Path packageRoot=Files.createDirectories(allowed.resolve("p"));
        Path transferFlow=Files.createDirectories(packageRoot.resolve("templates/flows/payments/transfer/v1"))
                .resolve("flow.yaml");
        Path refundFlow=Files.createDirectories(packageRoot.resolve("templates/flows/payments/refund/v1"))
                .resolve("flow.yaml");
        String flow="schemaVersion: att-flow/v3.6\nid: %s\nname: %s\ndescription: Native Suite fixture\nactions:\n"
                +"  verify: {type: assert, assert: '#{\u0024{EXEC.INPUT.amount} <= 13}'}\n"
                +"  log: {type: log, message: 'amount=\u0024{EXEC.INPUT.amount}'}\n";
        Files.writeString(transferFlow,String.format(flow,"payments.transfer.v1","Transfer"));
        Files.writeString(refundFlow,String.format(flow,"payments.refund.v1","Refund"));
        Path transferDefinition=packageRoot.resolve("testcase/payments/transfer/testcase.yaml");
        Path refundDefinition=packageRoot.resolve("testcase/payments/refund/testcase.yaml");
        Files.createDirectories(transferDefinition.getParent());Files.createDirectories(refundDefinition.getParent());
        Files.writeString(transferDefinition,definitionYaml(7,13));
        Files.writeString(refundDefinition,"schemaVersion: att-testcase/v1\nid: payments.refund\nname: Refund\n"
                +"flow: payments.refund.v1\ndataSource:\n  type: inline\n  records:\n"
                +"    - recordId: refund1\n      data: {amount: 5}\ninputs:\n  amount:\n    from: DATA.amount\n");
        Path runtimeConfig=Files.createDirectories(temp.resolve("suite-runtime/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("suite-data"),serverConfig=temp.resolve("suite-server.yaml");
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  auth: {mode: local}\n  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"  scheduling: {enabled: true, minimumIntervalMinutes: 1, maxConcurrentRuns: 1}\n"
                +"workers: {maxConcurrent: 1, queuedLimit: 2}\npackages:\n  allowedRoots: ["
                +ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries: {p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"}\n");
        Path libs=Files.createDirectories(temp.resolve("suite-libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            JsonNode body=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion",RunDefinition.NATIVE_CASE_VERSION,
                    "executionType","run","packageId","p","target",Map.of("type","nativeSuite",
                            "definitionIds",List.of("payments.transfer","payments.refund"),
                            "selection",Map.of("qualifiedCaseIds",List.of("payments.transfer.first","payments.refund.refund1"))),
                    "options",Map.of("debug",false,"failFast",true,"runId","native-suite-cross-flow")));
            assertThrows(IllegalArgumentException.class,()->runtime.submit("run",body,"alice"),
                    "Native Suite requests must pass through validated draft inspection");
            Map<String,Object> draft=runtime.createRunDraft(body,"alice");
            assertEquals(List.of("payments.refund.refund1","payments.transfer.first"),draft.get("selectedCaseIds"),
                    "Qualified identities are ordered by Definition ID, regardless of request order");
            @SuppressWarnings("unchecked") List<Map<String,Object>> preview=(List<Map<String,Object>>)draft.get("cases");
            assertEquals(List.of("payments.refund.v1","payments.transfer.v1"),preview.stream()
                    .map(row->String.valueOf(row.get("flowId"))).toList());
            Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",draft.get("draftId"))),"alice","alice");
            String jobId=String.valueOf(accepted.get("jobId"));Job job=runtime.jobs.get(jobId);
            assertEquals("native-suite-cross-flow",job.request.runId);
            assertEquals(Map.of("payments.refund","testcase/payments/refund/testcase.yaml",
                    "payments.transfer","testcase/payments/transfer/testcase.yaml"),job.request.nativeSuiteSourcePaths);
            Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,jobId);
            assertEquals("PASS",((Map<?,?>)result.get("job")).get("status"),result.toString());
            @SuppressWarnings("unchecked") Map<String,Object> canonical=ServerRuntime.JSON.convertValue(result.get("result"),Map.class);
            @SuppressWarnings("unchecked") Map<String,Object> summary=(Map<String,Object>)canonical.get("summary");
            assertEquals(2,summary.get("total"));assertEquals(2,summary.get("passed"));
            @SuppressWarnings("unchecked") List<Map<String,Object>> cases=(List<Map<String,Object>>)summary.get("cases");
            assertEquals(List.of("payments.refund.refund1","payments.transfer.first"),cases.stream()
                    .map(row->String.valueOf(row.get("qualifiedCaseId"))).toList());
            assertEquals(List.of("payments.refund.v1","payments.transfer.v1"),cases.stream()
                    .map(row->String.valueOf(row.get("flowId"))).toList());

            JsonNode scheduledBody=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion",RunDefinition.NATIVE_CASE_VERSION,
                    "executionType","run","packageId","p","target",Map.of("type","nativeSuite",
                            "definitionIds",List.of("payments.transfer","payments.refund"),
                            "selection",Map.of("qualifiedCaseIds",List.of("payments.transfer.first","payments.refund.refund1"))),
                    "options",Map.of("debug",false,"failFast",true,"runId","native-suite-scheduled")));
            Map<String,Object> scheduledDraft=runtime.createRunDraft(scheduledBody,"alice");
            LoadSchedule schedule=runtime.createLoadSchedule(ServerRuntime.JSON.valueToTree(Map.of("name","Native Suite schedule",
                    "packageId","p","executionType","run","draftId",scheduledDraft.get("draftId"),
                    "timezone","UTC","cron","0 2 * * *","enabled",false)),"alice");
            assertEquals(List.of("payments.refund.refund1","payments.transfer.first"),
                    schedule.runInput.get("nativeSuiteQualifiedCaseIds"));
            Map<String,Object> occurrence=runtime.triggerLoadSchedule(schedule.id,"alice");
            assertEquals("submitted",occurrence.get("status"),occurrence.toString());
            Map<String,Object> scheduledResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,
                    String.valueOf(occurrence.get("jobId")));
            assertEquals("PASS",((Map<?,?>)scheduledResult.get("job")).get("status"),scheduledResult.toString());

            JsonNode staleBody=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion",RunDefinition.NATIVE_CASE_VERSION,
                    "executionType","run","packageId","p","target",Map.of("type","nativeSuite",
                            "definitionIds",List.of("payments.transfer","payments.refund"),
                            "selection",Map.of("all",true)),
                    "options",Map.of("debug",false,"runId","native-suite-stale")));
            Map<String,Object> staleDraft=runtime.createRunDraft(staleBody,"alice");
            int jobsBeforeStaleSubmit=runtime.jobs.size();
            Files.writeString(refundDefinition,Files.readString(refundDefinition)+"\n# changed after suite draft\n");
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",staleDraft.get("draftId"))),"alice","alice"));
            assertEquals(jobsBeforeStaleSubmit,runtime.jobs.size(),
                    "A changed Definition must reject the complete Suite before creating a Job");
        }
    }

    @Test void nativeSuiteRerunFailedFreezesOwnedSourceAndRetriesOnlyExecutedFailedCases() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("retry-allowed"));
        Path packageRoot=Files.createDirectories(allowed.resolve("p"));
        Path flow=Files.createDirectories(packageRoot.resolve("templates/flows/payments/transfer/v1"))
                .resolve("flow.yaml");
        Files.createDirectories(packageRoot.resolve("testcase/payments/transfer"));
        Files.writeString(flow,"schemaVersion: att-flow/v3.6\nid: payments.transfer.v1\nname: Transfer\n"
                +"description: Native Suite retry fixture\nactions:\n"
                +"  verify: {type: assert, assert: '#{${EXEC.INPUT.amount} <= 7}'}\n"
                +"  log: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n");
        Path definition=packageRoot.resolve("testcase/payments/transfer/testcase.yaml");
        Files.writeString(definition,definitionYaml(7,13));

        Path runtimeConfig=Files.createDirectories(temp.resolve("retry-runtime/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("retry-data"),serverConfig=temp.resolve("retry-server.yaml");
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  auth: {mode: local}\n  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"  scheduling: {enabled: true, minimumIntervalMinutes: 1, maxConcurrentRuns: 1}\n"
                +"workers: {maxConcurrent: 1, queuedLimit: 2}\npackages:\n  allowedRoots: ["
                +ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries: {p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"}\n");
        Path libs=Files.createDirectories(temp.resolve("retry-libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            JsonNode sourceBody=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion",RunDefinition.NATIVE_CASE_VERSION,
                    "executionType","run","packageId","p","target",Map.of("type","nativeSuite",
                            "definitionIds",List.of("payments.transfer"),"selection",Map.of("all",true)),
                    "options",Map.of("debug",false,"failFast",true,"runId","native-suite-retry-source")));
            Map<String,Object> sourceDraft=runtime.createRunDraft(sourceBody,"alice");
            Map<String,Object> sourceAccepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",sourceDraft.get("draftId"))),"alice","alice");
            String sourceJobId=String.valueOf(sourceAccepted.get("jobId"));
            Map<String,Object> sourceResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,sourceJobId);
            assertEquals("FAIL",((Map<?,?>)sourceResult.get("job")).get("status"),sourceResult.toString());
            @SuppressWarnings("unchecked") Map<String,Object> sourceCanonical=ServerRuntime.JSON.convertValue(sourceResult.get("result"),Map.class);
            @SuppressWarnings("unchecked") Map<String,Object> sourceSummary=(Map<String,Object>)sourceCanonical.get("summary");
            assertEquals(2,sourceSummary.get("total"));
            assertEquals(2,sourceSummary.get("executionCount"));
            assertEquals(1,sourceSummary.get("failed"));
            assertEquals(0,sourceSummary.get("skipped"));

            JsonNode retryBody=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion",RunDefinition.NATIVE_CASE_VERSION,
                    "executionType","run","packageId","p","target",Map.of("type","nativeSuite",
                            "definitionIds",List.of("payments.transfer"),"selection",Map.of("rerunFailed",true)),
                    "options",Map.of("debug",false,"runId","native-suite-retry-attempt-1")));
            Map<String,Object> staleRetryDraft=runtime.createRunDraft(retryBody,"alice");
            var newerSourceBody=(com.fasterxml.jackson.databind.node.ObjectNode)sourceBody.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)newerSourceBody.get("options")).put("runId","native-suite-retry-newer-source");
            Map<String,Object> newerSourceDraft=runtime.createRunDraft(newerSourceBody,"alice");
            Map<String,Object> newerSourceAccepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",newerSourceDraft.get("draftId"))),"alice","alice");
            String newerSourceJobId=String.valueOf(newerSourceAccepted.get("jobId"));
            Map<String,Object> newerSourceResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,newerSourceJobId);
            assertEquals("FAIL",((Map<?,?>)newerSourceResult.get("job")).get("status"),newerSourceResult.toString());
            int jobsBeforeStaleRetrySubmit=runtime.jobs.size();
            assertThrows(ServerRuntime.JobConflictException.class,()->runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",staleRetryDraft.get("draftId"))),"alice","alice"),
                    "A retry draft must be refreshed when a newer eligible Native Suite RUN completes");
            assertEquals(jobsBeforeStaleRetrySubmit,runtime.jobs.size(),"A stale retry draft must not create a Job");

            Map<String,Object> retryDraft=runtime.createRunDraft(retryBody,"alice");
            assertEquals(List.of("payments.transfer.second"),retryDraft.get("selectedCaseIds"));
            @SuppressWarnings("unchecked") Map<String,Object> frozenLineage=(Map<String,Object>)retryDraft.get("retryLineage");
            assertEquals(newerSourceJobId,frozenLineage.get("sourceJobId"));
            assertEquals("native-suite-retry-newer-source",frozenLineage.get("sourceRunId"));
            assertEquals(2,frozenLineage.get("sourceSelectedCaseCount"));
            assertEquals(1,frozenLineage.get("retryAttemptNumber"));
            assertEquals(1,frozenLineage.get("retryCaseCount"));
            assertEquals(List.of("payments.transfer.second"),frozenLineage.get("failedQualifiedCaseIds"));

            Map<String,Object> retryAccepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",retryDraft.get("draftId"))),"alice","alice");
            String retryJobId=String.valueOf(retryAccepted.get("jobId"));
            assertEquals(newerSourceJobId,runtime.jobs.get(retryJobId).rerunOfJobId);
            assertEquals(newerSourceJobId,runtime.jobs.get(retryJobId).request.nativeSuiteRetry.get("sourceJobId"));
            Map<String,Object> retryResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,retryJobId);
            assertEquals("FAIL",((Map<?,?>)retryResult.get("job")).get("status"),retryResult.toString());
            @SuppressWarnings("unchecked") Map<String,Object> retryCanonical=ServerRuntime.JSON.convertValue(retryResult.get("result"),Map.class);
            @SuppressWarnings("unchecked") Map<String,Object> retrySummary=(Map<String,Object>)retryCanonical.get("summary");
            assertEquals(1,retrySummary.get("total"));
            assertEquals(1,retrySummary.get("executionCount"));
            assertEquals(1,retrySummary.get("failed"));
            assertEquals(0,retrySummary.get("skipped"));
            Map<String,Object> retryManifest=readArchivedRunManifest(runtime,retryJobId);
            @SuppressWarnings("unchecked") Map<String,Object> retryRun=(Map<String,Object>)retryManifest.get("run");
            @SuppressWarnings("unchecked") Map<String,Object> recordedLineage=(Map<String,Object>)retryRun.get("retryLineage");
            assertEquals(newerSourceJobId,recordedLineage.get("sourceJobId"));
            assertEquals("native-suite-retry-newer-source",recordedLineage.get("sourceRunId"));
            assertEquals(1,((Number)recordedLineage.get("retryExecutionCount")).intValue());
            assertEquals(0,((Number)recordedLineage.get("retrySkippedCaseCount")).intValue());

            var scheduledRetryBody=(com.fasterxml.jackson.databind.node.ObjectNode)retryBody.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)scheduledRetryBody.get("options")).put("runId","native-suite-retry-scheduled");
            Map<String,Object> scheduledRetryDraft=runtime.createRunDraft(scheduledRetryBody,"alice");
            LoadSchedule retrySchedule=runtime.createLoadSchedule(ServerRuntime.JSON.valueToTree(Map.of("name","Native Suite retry schedule",
                    "packageId","p","executionType","run","draftId",scheduledRetryDraft.get("draftId"),
                    "timezone","UTC","cron","0 2 * * *","enabled",false)),"alice");
            Map<String,Object> retryOccurrence=runtime.triggerLoadSchedule(retrySchedule.id,"alice");
            String scheduledRetryJobId=String.valueOf(retryOccurrence.get("jobId"));
            assertEquals("submitted",retryOccurrence.get("status"),retryOccurrence.toString());
            assertEquals(retryJobId,runtime.jobs.get(scheduledRetryJobId).rerunOfJobId);
            Map<String,Object> scheduledRetryResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,scheduledRetryJobId);
            assertEquals("FAIL",((Map<?,?>)scheduledRetryResult.get("job")).get("status"),scheduledRetryResult.toString());
            Map<String,Object> scheduledManifest=readArchivedRunManifest(runtime,scheduledRetryJobId);
            @SuppressWarnings("unchecked") Map<String,Object> scheduledRun=(Map<String,Object>)scheduledManifest.get("run");
            @SuppressWarnings("unchecked") Map<String,Object> scheduledLineage=(Map<String,Object>)scheduledRun.get("retryLineage");
            assertEquals(retryJobId,scheduledLineage.get("sourceJobId"));
            assertEquals(2,scheduledLineage.get("retryAttemptNumber"));
            assertEquals(1,((Number)scheduledLineage.get("retryExecutionCount")).intValue());
            Map<String,Object> nextRetryDraft=runtime.createRunDraft(retryBody,"alice");
            @SuppressWarnings("unchecked") Map<String,Object> nextRetryLineage=(Map<String,Object>)nextRetryDraft.get("retryLineage");
            assertEquals(scheduledRetryJobId,nextRetryLineage.get("sourceJobId"));
            assertEquals(3,nextRetryLineage.get("retryAttemptNumber"),
                    "The next retry attempt must increment from the scheduled Job's retained lineage");
            runtime.deleteLoadSchedule(retrySchedule.id,"alice");

            Files.writeString(flow,Files.readString(flow)+"# changed after the completed retry\n");
            assertThrows(ServerRuntime.JobConflictException.class,()->runtime.createRunDraft(retryBody,"alice"),
                    "A retry draft must reject source revision changes since the latest eligible Native Suite RUN");
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

    private static Map<String,Object> readArchivedRunManifest(ServerRuntime runtime,String jobId) throws Exception {
        String yaml=null;
        try(ZipFile archive=new ZipFile(awaitArtifactArchive(runtime,jobId).toFile())) {
            var entries=archive.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(!entry.isDirectory()&&entry.getName().endsWith("/run.yaml")) {
                    assertNull(yaml,"A completed Native Suite Job must publish one canonical run manifest");
                    yaml=read(archive.getInputStream(entry));
                }
            }
        }
        assertNotNull(yaml,"A completed Native Suite Job must publish its run manifest");
        Object loaded=new org.yaml.snakeyaml.Yaml().load(yaml);
        assertInstanceOf(Map.class,loaded);
        return ServerRuntime.JSON.convertValue(loaded,Map.class);
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

    private static String excelDefinitionYaml() {
        return "schemaVersion: att-testcase/v1\nid: payments.excel\nname: Excel cases\nflow: payments.transfer.v1\n"
                +"dataSource:\n  type: excel\n  path: testdata/native-cases.xlsx\n  sheet: Cases\n"
                +"  caseId: Case ID\n  dataColumns: amount=Amount\n"
                +"inputs:\n  amount:\n    from: DATA.amount\n";
    }

    private static Path writeExcelWorkbook(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try(XSSFWorkbook workbook=new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet=workbook.createSheet("Cases");
            Row header=sheet.createRow(0);
            header.createCell(0).setCellValue("Case ID");
            header.createCell(1).setCellValue("Amount");
            String[] ids={"alpha","beta","gamma"};
            int[] amounts={7,13,70};
            for(int index=0;index<ids.length;index++) {
                Row row=sheet.createRow(index+1);
                row.createCell(0).setCellValue(ids[index]);
                row.createCell(1).setCellValue(amounts[index]);
            }
            try(java.io.OutputStream output=Files.newOutputStream(path)){workbook.write(output);}
        }
        return path;
    }

    private static String read(InputStream input) throws Exception {
        try(input){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
}
