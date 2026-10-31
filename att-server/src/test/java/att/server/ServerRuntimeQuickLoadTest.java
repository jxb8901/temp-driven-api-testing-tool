package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Quick Load draft cases. A separate class so Surefire can run them on their own JVM. */
class ServerRuntimeQuickLoadTest {
    @TempDir Path temp;

    @Test void quickLoadDraftOmitsDebugImportsAndRunsOnlyExplicitLoadTestdata() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("quick-load-packages"));Path packageRoot=Files.createDirectory(allowed.resolve("p"));
        Path templates=Files.createDirectories(packageRoot.resolve("templates/FORM"));Files.createDirectories(packageRoot.resolve("testcase"));
        Path toolInvocations=packageRoot.resolve("tools/invocations.log");
        Path runtimeConfig=temp.resolve("quick-load-runtime/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Path toolScript=Files.createDirectories(packageRoot.resolve("tools")).resolve("check-load-inputs.sh");
        Files.writeString(toolScript,"#!/bin/sh\nprintf 'called\\n' >> \"$ATT_ROOT_DIR/tools/invocations.log\"\n[ \"$1\" = CORR-176 ] && [ \"$2\" = ARG-176 ]\n");
        toolScript.toFile().setExecutable(true);
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n"
                +"tools:\n  echo:\n    name: Echo\n    description: Echo typed Load values\n"
                +"    command: [./tools/check-load-inputs.sh, \"${input.correlationId}\", \"${value}\"]\n    stdoutFormat: text\n"
                +"    arguments:\n      correlationId: {name: Correlation ID, description: Correlation ID, required: false}\n"
                +"      value: {name: Value, description: Value, required: true}\n");
        Files.writeString(templates.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: Quick Load test\nactions:\n"
                +"  log:\n    type: log\n    message: '${EXEC.INPUT.value}'\n");
        Path brokenFlow=Files.createDirectories(packageRoot.resolve("templates/flows/demo")).resolve("flow.yaml");
        Files.writeString(brokenFlow,"schemaVersion: att-flow/v3.6\nid: broken.outer.v1\nname: Broken outer\ndescription: Nested dependency regression\nactions:\n"
                +"  nested:\n    type: flow\n    use: missing.inner.v1\n");
        Path debugData=Files.createDirectories(packageRoot.resolve("debug-data")).resolve("debug.yaml");
        Files.writeString(debugData,"schemaVersion: att-testdata/v1.0\nid: debugAccounts\nrecords: [{id: 17}]\n");
        Path loadData=Files.createDirectories(packageRoot.resolve("testdata")).resolve("load.yaml");
        Files.writeString(loadData,"schemaVersion: att-testdata/v1.0\nid: loadAccounts\nrecords: [{id: 42}]\n");
        Path debugSidecar=templates.resolve("debug.yaml");
        Files.writeString(debugSidecar,"schemaVersion: att-debug/v1.2\ntestdata: [debug-data/debug.yaml]\ninputs: {value: default}\nvars: {reference: REF001}\n");
        byte[] originalSidecar=Files.readAllBytes(debugSidecar);
        Path toolSidecar=Files.createDirectories(runtimeConfig.getParent().resolve("tools")).resolve("echo.debug.yaml");
        Files.writeString(toolSidecar,"schemaVersion: att-debug/v1.2\ninputs: {correlationId: CORR-176}\narguments: {value: ARG-176}\n");
        byte[] originalToolSidecar=Files.readAllBytes(toolSidecar);
        Path loadPolicy=Files.createDirectories(packageRoot.resolve("load")).resolve("load.visualuser.yaml");
        Files.writeString(loadPolicy,"schemaVersion: att-load/v1.6\nload: {users: 1, duration: 1s}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("quick-load-server.yaml");Files.writeString(configFile,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("quick-load-data"))+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  inlineLoad:\n    enabled: true\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(allowed)+"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n");
        Path libs=Files.createDirectory(temp.resolve("quick-load-WEB-INF-lib"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")
                    &&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        ServerRuntimeTestSupport.MutableClock clock=new ServerRuntimeTestSupport.MutableClock();AtomicInteger inspectorStarts=new AtomicInteger();
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(configFile),libs.toString(),builder->{
            if(builder.command().contains("--inspection-daemon"))inspectorStarts.incrementAndGet();
            return builder.start();
        },clock);
        try {
            Path arrivalPolicy=Files.createFile(packageRoot.resolve("load/load.arrivalrate.yaml"));
            Files.writeString(arrivalPolicy,"schemaVersion: att-load/v1.6\nload: {arrivalRate: '2/s', maxConcurrent: 3, overloadPolicy: drop, duration: 2m}\n");
            Map<String,Object> selectedPolicy=runtime.inspectQuickLoadPolicy("p","arrivalRate",null,"load/load.arrivalrate.yaml","alice");
            @SuppressWarnings("unchecked") Map<String,Object> selectedPolicyBody=(Map<String,Object>)selectedPolicy.get("policy");
            @SuppressWarnings("unchecked") Map<String,Object> selectedLoad=(Map<String,Object>)selectedPolicyBody.get("load");
            assertEquals("2/s",selectedLoad.get("arrivalRate"));
            @SuppressWarnings("unchecked") List<Map<String,Object>> policyTemplates=(List<Map<String,Object>>)selectedPolicy.get("templates");
            assertTrue(policyTemplates.stream().anyMatch(item->"load/load.arrivalrate.yaml".equals(item.get("id"))));
            Map<String,Object> page=runtime.inspectResource("p","list","template",null,null,10,null,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
            String resourceId=String.valueOf(items.get(0).get("resourceId"));
            Map<String,Object> form=runtime.inspectQuickLoadForm("p","template",resourceId,"virtualUsers",null,"alice");
            assertEquals(Boolean.TRUE,form.get("debugLocalTestdataOmitted"));
            assertFalse(form.toString().contains("debug-data/debug.yaml"));assertFalse(form.toString().contains("debugAccounts"));
            com.fasterxml.jackson.databind.node.ObjectNode body=ServerRuntime.JSON.createObjectNode();body.put("packageId","p");
            body.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","FORM")));
            body.put("model","virtualUsers");body.set("input",ServerRuntime.JSON.valueToTree(Map.of("inputs",Map.of("value","draft"),"vars",Map.of("reference","REF002"))));
            body.set("load",ServerRuntime.JSON.valueToTree(Map.of("users",1,"duration","1s")));
            body.set("testdata",ServerRuntime.JSON.valueToTree(List.of("testdata/load.yaml")));
            com.fasterxml.jackson.databind.node.ObjectNode brokenFlowBody=body.deepCopy();
            brokenFlowBody.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","flow","id","broken.outer.v1")));
            ServerRuntime.DraftValidationException brokenFlowError=assertThrows(ServerRuntime.DraftValidationException.class,
                    ()->runtime.createQuickLoadDraft(brokenFlowBody,"alice"));
            assertFalse(brokenFlowError.diagnostics.isEmpty());
            assertTrue(brokenFlowError.diagnostics.toString().contains("missing.inner.v1"),brokenFlowError.diagnostics.toString());
            assertTrue(brokenFlowError.diagnostics.toString().contains("suggestion"),brokenFlowError.diagnostics.toString());
            Map<String,Object> draft=runtime.createQuickLoadDraft(body,"alice");String draftId=String.valueOf(draft.get("draftId"));
            assertEquals(1,inspectorStarts.get(),"Quick Load inspection and draft validation should reuse one bounded Inspector Worker");
            assertFalse(Files.exists(toolInvocations),"Draft validation must not invoke package Tools");
            assertFalse(draft.toString().contains("debugAccounts"));assertFalse(draft.toString().contains("testdata/load.yaml"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getQuickLoadDraft(draftId,"bob"));
            assertArrayEquals(originalSidecar,Files.readAllBytes(debugSidecar),"Draft validation must leave Debug sidecars unchanged");
            Map<String,Object> accepted=runtime.submitQuickLoadDraft(ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+draftId+"\"}"),"alice");
            String jobId=String.valueOf(accepted.get("jobId"));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Map<String,Object> record=runtime.jobRecord(jobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(record.get("status"))&&System.nanoTime()<deadline){Thread.sleep(20);record=runtime.jobRecord(jobId);}
            assertEquals("PASS",record.get("status"),String.valueOf(runtime.resultRecord(jobId)));
            assertEquals("load",record.get("command"));assertArrayEquals(originalSidecar,Files.readAllBytes(debugSidecar));
            long releaseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(((Number)runtime.counts().get("activeWorkers")).intValue()>0&&System.nanoTime()<releaseDeadline)Thread.sleep(10);
            assertEquals(0,((Number)runtime.counts().get("activeWorkers")).intValue(),"Completed Load jobs must release admission before the stale-draft check");
            assertTrue(Files.exists(loadPolicy));

            Map<String,Object> toolScenario=Map.of("schemaVersion","att-load/v1.6","load",Map.of("users",1,"duration","1s"),
                    "workloads",List.of(Map.of("id","echo-tool","load",Map.of("users",1),
                            "target",Map.of("type","tool","id","echo","arguments",Map.of("value",Map.of("$attDebugKeepDefault","/arguments/value"))),
                            "inputs",Map.of("correlationId",Map.of("$attDebugKeepDefault","/inputs/correlationId")))));
            com.fasterxml.jackson.databind.node.ObjectNode advancedBody=ServerRuntime.JSON.createObjectNode();advancedBody.put("packageId","p");advancedBody.set("scenario",ServerRuntime.JSON.valueToTree(toolScenario));
            Map<String,Object> brokenAdvancedScenario=Map.of("schemaVersion","att-load/v1.6","load",Map.of("users",1,"duration","1s"),
                    "workloads",List.of(Map.of("id","broken-flow","load",Map.of("users",1),
                            "target",Map.of("type","flow","id","broken.outer.v1"))));
            com.fasterxml.jackson.databind.node.ObjectNode brokenAdvancedBody=advancedBody.deepCopy();
            brokenAdvancedBody.set("scenario",ServerRuntime.JSON.valueToTree(brokenAdvancedScenario));
            ServerRuntime.DraftValidationException brokenAdvancedError=assertThrows(ServerRuntime.DraftValidationException.class,
                    ()->runtime.createAdvancedLoadDraft(brokenAdvancedBody,"alice"));
            assertFalse(brokenAdvancedError.diagnostics.isEmpty());
            assertTrue(brokenAdvancedError.diagnostics.toString().contains("missing.inner.v1"),brokenAdvancedError.diagnostics.toString());
            assertEquals(1,inspectorStarts.get(),"Failed Advanced Load validation should stay on the existing Inspector");
            assertFalse(Files.exists(toolInvocations),"A rejected Advanced Load draft must not invoke package Tools");
            Map<String,Object> advancedDraft;
            try { advancedDraft=runtime.createAdvancedLoadDraft(advancedBody,"alice"); }
            catch(ServerRuntime.DraftValidationException invalid) { throw new AssertionError(invalid.diagnostics.toString(),invalid); }
            String advancedDraftId=String.valueOf(advancedDraft.get("draftId"));
            assertEquals(Boolean.FALSE,advancedDraft.get("redacted"));assertTrue(advancedDraft.toString().contains("CORR-176"));assertTrue(advancedDraft.toString().contains("ARG-176"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getAdvancedLoadDraft(advancedDraftId,"bob"));
            com.fasterxml.jackson.databind.node.ObjectNode save=ServerRuntime.JSON.createObjectNode();
            save.put("draftId",advancedDraftId);save.put("name","Tool scenario");save.putArray("tags").add("nightly");
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.saveLoadScenario(save,"bob"));
            Map<String,Object> saved=runtime.saveLoadScenario(save,"alice");
            SavedLoadScenario savedRecord=runtime.store.savedLoadScenario(String.valueOf(saved.get("id")),"container:alice");
            assertNotNull(savedRecord);assertTrue(savedRecord.authoredJson.contains("$attDebugKeepDefault"));
            assertFalse(savedRecord.authoredJson.contains("CORR-176"));assertFalse(savedRecord.authoredJson.contains("ARG-176"));
            assertNull(runtime.store.savedLoadScenario(savedRecord.id,"container:bob"));
            com.fasterxml.jackson.databind.node.ObjectNode sensitiveBody=advancedBody.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)sensitiveBody.path("scenario").path("workloads").get(0).path("inputs")).put("password","private-value");
            Map<String,Object> sensitiveDraft=runtime.createAdvancedLoadDraft(sensitiveBody,"alice");
            save.put("draftId",String.valueOf(sensitiveDraft.get("draftId")));
            assertThrows(IllegalArgumentException.class,()->runtime.saveLoadScenario(save,"alice"));
            assertEquals(1,runtime.store.savedLoadScenarioSummaries("container:alice",10,0).size());
            assertArrayEquals(originalToolSidecar,Files.readAllBytes(toolSidecar),"Advanced Load validation must leave Tool sidecars unchanged");
            assertEquals(1,inspectorStarts.get(),"Quick and Advanced Load validation should share the bounded Inspector Worker");
            assertFalse(Files.exists(toolInvocations),"Advanced Load draft validation must not invoke package Tools");
            Map<String,Object> advancedAccepted=runtime.submitAdvancedLoadDraft(ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+advancedDraftId+"\"}"),"alice");
            String advancedJobId=String.valueOf(advancedAccepted.get("jobId"));long advancedDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Map<String,Object> advancedRecord=runtime.jobRecord(advancedJobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(advancedRecord.get("status"))&&System.nanoTime()<advancedDeadline){Thread.sleep(20);advancedRecord=runtime.jobRecord(advancedJobId);}
            assertEquals("PASS",advancedRecord.get("status"),"The executable Tool asserts it received both restored values: "+runtime.resultRecord(advancedJobId));
            long advancedReleaseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(((Number)runtime.counts().get("activeWorkers")).intValue()>0&&System.nanoTime()<advancedReleaseDeadline)Thread.sleep(10);
            assertEquals(0,((Number)runtime.counts().get("activeWorkers")).intValue(),"The Advanced Load Worker must exit before the test temp directory is removed");
            assertTrue(Files.readString(toolInvocations).contains("called\n"),"Advanced Load submission should invoke the configured Tool");
            assertArrayEquals(originalToolSidecar,Files.readAllBytes(toolSidecar));
            Files.delete(toolInvocations);
            // Catalog discovery has no Environment; composition must still pin the selected executable revision.
            Map<String,Object> catalogPage=runtime.inspectLoadTemplates("p",null,0,100,"alice");
            assertEquals(6,((List<?>)catalogPage.get("items")).size());
            Map<String,Object> binding=Map.of("target",Map.of("type","tool","id","echo"));
            Map<String,Object> composition=Map.of("templateId","builtin:multi-vu","revision",catalogPage.get("revisionDigest"),
                    "bindings",Map.of("schemaVersion","att-load-bindings/v1","bindings",Map.of("workload-1",binding,"workload-2",binding)));
            com.fasterxml.jackson.databind.node.ObjectNode composedBody=ServerRuntime.JSON.createObjectNode();
            composedBody.put("packageId","p");composedBody.put("environment","SIT");composedBody.set("templateComposition",ServerRuntime.JSON.valueToTree(composition));
            Map<String,Object> composedDraft=runtime.createAdvancedLoadDraft(composedBody,"alice");
            assertTrue(String.valueOf(composedDraft.get("previewYaml")).contains("workload-2"));
            assertTrue(String.valueOf(composedDraft.get("previewYaml")).contains("CORR-176"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getAdvancedLoadDraft(String.valueOf(composedDraft.get("draftId")),"bob"));
            assertFalse(Files.exists(toolInvocations),"Template composition validation must not invoke package Tools");
            assertEquals(0,((Number)runtime.counts().get("activeWorkers")).intValue(),"Template validation starts no execution Worker");
            composedBody.withObject("/templateComposition").put("revision","0".repeat(64));
            assertThrows(ServerRuntime.StaleCursorException.class,()->runtime.createAdvancedLoadDraft(composedBody,"alice"));

            Map<String,Object> stale=runtime.createQuickLoadDraft(body,"alice");
            Files.writeString(templates.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: changed\nactions:\n"
                    +"  log:\n    type: log\n    message: '${EXEC.INPUT.value}'\n");
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.submitQuickLoadDraft(
                    ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+stale.get("draftId")+"\"}"),"alice"));

            Map<String,Object> expired=runtime.createQuickLoadDraft(body,"alice");
            clock.advance(Duration.ofMinutes(11));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getQuickLoadDraft(String.valueOf(expired.get("draftId")),"alice"));
        } finally {runtime.close();}
    }
}
