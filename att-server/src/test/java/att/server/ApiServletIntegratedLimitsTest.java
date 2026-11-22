package att.server;

import att.server.api.WorkerRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

class ApiServletIntegratedLimitsTest {
    private static final int DEFAULT_INSPECTION_TIMEOUT_MS=30_000;
    @TempDir Path temp;

    @Test void nativeArtifactStatusSurvivesNonLoadJobsAndCorruptOptionalLoadMetadata() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("artifact-status-packages"));
        createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"artifact-status-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("artifact-status-libs"))) {
            ApiServlet servlet=servlet(runtime);
            for(String command:List.of("run","debug")) {
                String id="debug".equals(command)?"J0000000000000731":"J0000000000000730";
                Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
                Files.writeString(output.resolve("native.txt"),"native report");
                Job job=new Job(id,command,"p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
                runtime.store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();runtime.store.update(job);
                ArtifactArchive.build(output,runtime.artifactArchivePath(id));
                Files.writeString(runtime.artifactArchivePath(id).resolveSibling("archive-status.json"),"{\"state\":\"READY\"}");
                Captured response=invoke(servlet,"GET","/jobs/"+id+"/artifacts/status",Collections.emptyMap(),new byte[0]);
                JsonNode overview=ServerRuntime.JSON.readTree(response.body());
                assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
                assertEquals("READY",overview.path("archiveStatus").asText());
                assertFalse(overview.has("loadReportV2"),command+" jobs must not expose a LOAD report extension.");
            }

            String id="J0000000000000732";
            Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
            Files.writeString(output.resolve("native.txt"),"native report");
            Job job=new Job(id,"load","p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
            runtime.store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();runtime.store.update(job);
            ArtifactArchive.build(output,runtime.artifactArchivePath(id));
            Files.writeString(runtime.artifactArchivePath(id).resolveSibling("archive-status.json"),"{\"state\":\"READY\"}");
            Path malformedSnapshot=output.getParent().resolve("sut-metrics/snapshot.json");
            Files.createDirectories(malformedSnapshot.getParent());
            Files.writeString(malformedSnapshot,"{not-json");
            Captured response=invoke(servlet,"GET","/jobs/"+id+"/artifacts/status",Collections.emptyMap(),new byte[0]);
            JsonNode overview=ServerRuntime.JSON.readTree(response.body());
            assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
            assertEquals("READY",overview.path("archiveStatus").asText());
            assertEquals("FAILED",overview.path("loadReportV2").path("state").asText());
        }
    }

    @Test void nativeResultWorkbookArchiveEntryDownloadsWithXlsxHeaders() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("native-xlsx-download-packages"));
        createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"native-xlsx-download-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("native-xlsx-download-libs"))) {
            String id="J0000000000000781";
            Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
            String runId="native-download";
            Path runDirectory=Files.createDirectories(output.resolve(runId));
            Path workbookPath=runDirectory.resolve(ArtifactArchive.NATIVE_CASE_RESULT_XLSX);Files.createDirectories(workbookPath.getParent());
            try(org.apache.poi.xssf.usermodel.XSSFWorkbook workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                java.io.OutputStream stream=Files.newOutputStream(workbookPath)) {
                workbook.createSheet("Cases").createRow(0).createCell(0).setCellValue("Case ID");
                workbook.write(stream);
            }
            Files.writeString(runDirectory.resolve("run.yaml"),"schemaVersion: att-run/v2.1\nrun:\n"
                    +"  id: "+runId+"\n  targetType: nativeCase\n  nativeReportProtection: att-native-report/v1\n"
                    +"outputs:\n  html: report/index.html\n  xlsx: workbooks/native-cases.result.xlsx\n");
            byte[] expected=Files.readAllBytes(workbookPath);
            Job job=new Job(id,"run","p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
            job.executionSpec=nativeExecutionSpec("p",runId);
            job.resultJson=ServerRuntime.JSON.writeValueAsString(Map.of("executionId",runId,"status","PASS","exitCode",0));
            runtime.store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();runtime.store.update(job);
            ArtifactArchive.build(output,runtime.artifactArchivePath(id));
            Files.writeString(runtime.artifactArchivePath(id).resolveSibling("archive-status.json"),"{\"state\":\"READY\"}");

            ApiServlet servlet=servlet(runtime);
            Captured indexResponse=invoke(servlet,"GET","/jobs/"+id+"/artifacts",Collections.emptyMap(),new byte[0]);
            JsonNode index=ServerRuntime.JSON.readTree(indexResponse.body());
            assertEquals(200,indexResponse.status,index.toString());
            JsonNode workbookEntry=null;
            String expectedPath=runId+"/"+ArtifactArchive.NATIVE_CASE_RESULT_XLSX;
            for(JsonNode item:index.path("items"))if(expectedPath.equals(item.path("path").asText()))workbookEntry=item;
            assertNotNull(workbookEntry,"The XLSX must be visible in the archive-backed artifact index");
            assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbookEntry.path("mediaType").asText());
            assertFalse(workbookEntry.path("preview").asBoolean());

            Captured download=invoke(servlet,"GET","/jobs/"+id+"/artifacts/view/"+expectedPath,
                    Collections.emptyMap(),new byte[0]);
            assertEquals(200,download.status);
            assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",download.headers.get("Content-Type"));
            assertEquals("attachment; filename=\"native-cases.result.xlsx\"",download.headers.get("Content-Disposition"));
            assertArrayEquals(expected,download.body());
        }
    }

    @Test void nativeSourceWorkbookArchiveRequiresCurrentPackageEditor() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("native-source-xlsx-packages"));
        Path packageRoot=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig("native-source-xlsx-runtime");
        Path data=temp.resolve("native-source-xlsx-data");
        Path serverFile=temp.resolve("native-source-xlsx-server.yaml");
        Path libs=workerLibraries("native-source-xlsx-libs");
        String server="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n"
                +"  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n"
                +"  authenticationRequired: true\n  auth: {mode: local}\n"
                +"  packageConfiguration: {sourceEditingEnabled: true}\n  inspection: {enabled: true}\nworkers: {}\npackages:\n"
                +"  allowedRoots: ["+ServerRuntimeTestSupport.yaml(packages)+"]\n  entries:\n"
                +"    alice:\n      path: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n";
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(Files.writeString(serverFile,server)),libs.toString())) {
            runtime.auth.createUser("alice","Alice","unused Alice password","USER");
            runtime.auth.createUser("bob","Bob","unused Bob password","USER");
            String id="J0000000000000791",runId="native-source-download";
            byte[] sourceWorkbookBytes=workbookBytes("Source",true);
            byte[] resultWorkbookBytes=workbookBytes("Cases",false);
            createScheduledNativeSourceWorkbookFixture(runtime,id,runId,"alice",sourceWorkbookBytes,resultWorkbookBytes);
            String nonEditorJobId="J0000000000000792",nonEditorRunId="native-source-no-editor";
            createScheduledNativeSourceWorkbookFixture(runtime,nonEditorJobId,nonEditorRunId,"bob",
                    sourceWorkbookBytes,resultWorkbookBytes);

            ApiServlet servlet=servlet(runtime);
            String sourcePath=runId+"/"+ArtifactArchive.NATIVE_SOURCE_WORKBOOK_XLSX;
            Captured editorZip=invokeAs(servlet,"alice","GET","/jobs/"+id+"/artifacts/archive.zip",Collections.emptyMap(),new byte[0],false);
            assertEquals(200,editorZip.status);
            Captured editorWorkbook=invokeAs(servlet,"alice","GET","/jobs/"+id+"/artifacts/view/"+sourcePath,Collections.emptyMap(),new byte[0],false);
            assertEquals(200,editorWorkbook.status);
            assertEquals("attachment; filename=\"native-cases.source-workbook.xlsx\"",editorWorkbook.headers.get("Content-Disposition"));
            assertArrayEquals(sourceWorkbookBytes,editorWorkbook.body());

            Captured nonEditorZip=invokeAs(servlet,"bob","GET","/jobs/"+id+"/artifacts/archive.zip",Collections.emptyMap(),new byte[0],false);
            assertEquals(403,nonEditorZip.status,"A RUN-only USER cannot download an archive containing the source workbook");
            Captured nonEditorWorkbook=invokeAs(servlet,"bob","GET","/jobs/"+id+"/artifacts/view/"+sourcePath,Collections.emptyMap(),new byte[0],false);
            assertEquals(403,nonEditorWorkbook.status,"A RUN-only USER cannot download the source workbook entry");
            Captured nonEditorStandardReport=invokeAs(servlet,"bob","GET","/jobs/"+id+"/artifacts/view/case.log",Collections.emptyMap(),new byte[0],false);
            assertEquals(200,nonEditorStandardReport.status,"Standard artifacts retain their existing access policy");
            Captured nonEditorIndex=invokeAs(servlet,"bob","GET","/jobs/"+id+"/artifacts",Collections.emptyMap(),new byte[0],false);
            assertFalse(new String(nonEditorIndex.body(),StandardCharsets.UTF_8).contains(sourcePath),
                    "The source-workbook path is not listed after editor access is revoked or absent");

            String excludedPath=nonEditorRunId+"/"+ArtifactArchive.NATIVE_SOURCE_WORKBOOK_XLSX;
            Captured nonEditorJobZip=invokeAs(servlet,"bob","GET","/jobs/"+nonEditorJobId+"/artifacts/archive.zip",Collections.emptyMap(),new byte[0],false);
            assertEquals(200,nonEditorJobZip.status,"A non-editor Job still receives ordinary artifacts when its source workbook is omitted at build time");
            Captured nonEditorJobIndex=invokeAs(servlet,"bob","GET","/jobs/"+nonEditorJobId+"/artifacts",Collections.emptyMap(),new byte[0],false);
            assertFalse(new String(nonEditorJobIndex.body(),StandardCharsets.UTF_8).contains(excludedPath),
                    "A source workbook is omitted when the stored Job principal is not a current Package editor");

            runtime.auth.disable("alice");
            assertThrows(ServerRuntime.AuthoringSourceDeniedException.class,()->runtime.openArtifactArchive(id,true,"alice"),
                    "A revoked editor loses access to the containing archive");
            assertThrows(ServerRuntime.AuthoringSourceDeniedException.class,()->runtime.archiveEntry(id,sourcePath,"alice"),
                    "A revoked editor loses access to the individual source workbook");
        }
    }

    private static void createScheduledNativeSourceWorkbookFixture(ServerRuntime runtime,String id,String runId,
            String principal,byte[] sourceWorkbookBytes,byte[] resultWorkbookBytes) throws Exception {
        Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
        Files.writeString(output.resolve("case.log"),"ordinary report remains available\n");
        Path runDirectory=Files.createDirectories(output.resolve(runId));
        Path sourceWorkbook=runDirectory.resolve(ArtifactArchive.NATIVE_SOURCE_WORKBOOK_XLSX);
        Path resultWorkbook=runDirectory.resolve(ArtifactArchive.NATIVE_CASE_RESULT_XLSX);
        Files.createDirectories(sourceWorkbook.getParent());Files.write(sourceWorkbook,sourceWorkbookBytes);
        Files.createDirectories(resultWorkbook.getParent());Files.write(resultWorkbook,resultWorkbookBytes);
        String outputDigest=sha256(sourceWorkbookBytes);
        String formulaDigest=att.report.NativeSourceWorkbookReportWriter.formulaInventorySha256(sourceWorkbookBytes);
        Files.writeString(runDirectory.resolve("run.yaml"),"schemaVersion: att-run/v2.1\nrun:\n"
                +"  id: "+runId+"\n  targetType: nativeCase\n  nativeReportProtection: att-native-report/v1\n"
                +"  sourceWorkbookReport:\n    schemaVersion: att-native-source-workbook-report/v1\n"
                +"    status: written\n    outputPath: "+ArtifactArchive.NATIVE_SOURCE_WORKBOOK_XLSX+"\n"
                +"    sourceSha256: "+outputDigest+"\n    outputSha256: "+outputDigest+"\n"
                +"    formulaSha256: "+formulaDigest+"\noutputs:\n"
                +"  html: report/index.html\n  xlsx: "+ArtifactArchive.NATIVE_CASE_RESULT_XLSX+"\n"
                +"  sourceWorkbookXlsx: "+ArtifactArchive.NATIVE_SOURCE_WORKBOOK_XLSX+"\n");

        Job job=new Job(id,"run","alice",principal,new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
        job.executionSpec=nativeExecutionSpec("alice",runId);
        job.resultJson=ServerRuntime.JSON.writeValueAsString(Map.of("executionId",runId,"status","PASS","exitCode",0));
        runtime.store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();runtime.store.update(job);
        runtime.scheduleArtifactArchive(id);
        Path status=runtime.artifactArchivePath(id).resolveSibling("archive-status.json");
        for(int attempt=0;attempt<400;attempt++) {
            if(Files.isRegularFile(status)) {
                JsonNode state=ServerRuntime.JSON.readTree(status.toFile()).path("state");
                if("READY".equals(state.asText()))return;
                if("FAILED".equals(state.asText()))throw new AssertionError("Archive publication failed for "+id+": "+Files.readString(status));
            }
            Thread.sleep(25L);
        }
        fail("Timed out waiting for artifact archive for "+id);
    }

    private static byte[] workbookBytes(String sheetName,boolean includeFormula) throws Exception {
        try(XSSFWorkbook workbook=new XSSFWorkbook();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            Sheet sheet=workbook.createSheet(sheetName);Row header=sheet.createRow(0);header.createCell(0).setCellValue("ID");
            Row row=sheet.createRow(1);row.createCell(0).setCellValue("case-1");
            if(includeFormula)row.createCell(1).setCellFormula("1+1");
            workbook.write(bytes);return bytes.toByteArray();
        }
    }

    private static String nativeExecutionSpec(String packageId,String runId) throws Exception {
        var definition=ServerRuntime.JSON.createObjectNode();
        definition.put("schemaVersion",RunDefinition.NATIVE_CASE_VERSION);
        definition.put("executionType","run");definition.put("packageId",packageId);
        var target=definition.putObject("target");target.put("type","nativeCase");target.put("definitionId","fixture.case");
        target.putObject("selection").putArray("resolvedCaseIds").add("one");
        definition.putObject("options").put("runId",runId);
        return new JobExecutionSpec(definition,"a".repeat(64),null).json();
    }

    @Test void staleCentralImpactReturnsConflictThroughPublishRoute() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("impact-api-packages"));
        Path packageRoot=createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("impact-api-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"impact-api-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,libs,true,true)) {
            Map<String,String> revisions=runtime.changeSetPublisher.currentRevisions(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true);
            String candidate=Files.readString(runtimeConfig)+"# impact API candidate\n";
            Map<String,Object> draft=runtime.changeSetPublisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,revisions.get("baseSourceRevision"),null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",candidate.getBytes(StandardCharsets.UTF_8))));
            String id=String.valueOf(draft.get("changeSetId"));
            runtime.changeSetPublisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id);
            runtime.changeSetPublisher.review(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id);
            var impact=runtime.changeSetPublisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id,null);
            assertNotNull(impact.freshness);
            String freshness=ConfigImpactAnalyzer.freshnessFingerprint(impact.freshness);
            att.server.api.source.SourceManagementContracts.ImpactAcknowledgement acknowledgement=
                    new att.server.api.source.SourceManagementContracts.ImpactAcknowledgement(freshness);

            Path manifest=packageRoot.resolve("package.yaml");
            Files.writeString(manifest,Files.readString(manifest)+"# concurrent manifest change\n");
            Captured response=invokeAsAdmin(servlet(runtime),"alice","POST",
                    "/configuration/change-sets/"+id+"/publish",Map.of(),ServerRuntime.JSON.writeValueAsBytes(Map.of(
                            "impactAcknowledgement",Map.of("freshnessFingerprint",freshness))));
            JsonNode error=ServerRuntime.JSON.readTree(response.body()).path("error");
            assertEquals(409,response.status,new String(response.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-CONFIG-IMPACT-STALE",error.path("code").asText());
            assertFalse(Files.readString(runtimeConfig).contains("# impact API candidate"),
                    "a stale impact must leave the central source unchanged");
        }
    }

    @Test void centralPublishRouteReturnsFlatActivationWireContract() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("activation-wire-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"activation-wire-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,workerLibraries("activation-wire-libs"),true,true)) {
            Map<String,String> revisions=runtime.changeSetPublisher.currentRevisions(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true);
            String candidate=Files.readString(runtimeConfig)+"# wire contract candidate\n";
            Map<String,Object> draft=runtime.changeSetPublisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,revisions.get("baseSourceRevision"),null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",candidate.getBytes(StandardCharsets.UTF_8))));
            String id=String.valueOf(draft.get("changeSetId"));
            runtime.changeSetPublisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id);
            runtime.changeSetPublisher.review(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id);
            var impact=runtime.changeSetPublisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id,null);
            String freshness=ConfigImpactAnalyzer.freshnessFingerprint(impact.freshness);
            Captured response=invokeAsAdmin(servlet(runtime),"alice","POST",
                    "/configuration/change-sets/"+id+"/publish",Map.of(),ServerRuntime.JSON.writeValueAsBytes(Map.of(
                            "impactAcknowledgement",Map.of("freshnessFingerprint",freshness))));
            JsonNode body=ServerRuntime.JSON.readTree(response.body());
            assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
            assertTrue(body.path("published").asBoolean(),body.toString());
            assertEquals("ACTIVE",body.path("activationState").asText(),body.toString());
            assertEquals(body.path("revision").asText(),body.path("publishedRevision").asText(),body.toString());
            assertEquals(body.path("revision").asText(),body.path("activeRevision").asText(),body.toString());
            assertTrue(body.path("auditConfirmed").asBoolean(),body.toString());
            assertFalse(body.has("activationStatus"),"Publish response must match the flat Studio SourcePublishResult contract.");
        }
    }

    @Test void centralHistoryRestoreRequiresFreshImpactAndActivatesThroughTheNormalGate() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("central-restore-impact-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"central-restore-impact-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("central-restore-impact-libs"),true,true)) {
            ApiServlet servlet=servlet(runtime);
            String original=Files.readString(runtimeConfig);
            Map<String,String> revisions=runtime.changeSetPublisher.currentRevisions(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true);
            String candidate=original+"# published candidate for history restore\n";
            Map<String,Object> firstDraft=runtime.changeSetPublisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,revisions.get("baseSourceRevision"),null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",candidate.getBytes(StandardCharsets.UTF_8))));
            String firstId=String.valueOf(firstDraft.get("changeSetId"));
            runtime.changeSetPublisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,firstId);
            runtime.changeSetPublisher.review(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,firstId);
            var firstImpact=runtime.changeSetPublisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,firstId,null);
            String firstFreshness=ConfigImpactAnalyzer.freshnessFingerprint(firstImpact.freshness);
            Captured firstPublished=invokeAsAdmin(servlet,"alice","POST",
                    "/configuration/change-sets/"+firstId+"/publish",Map.of(),ServerRuntime.JSON.writeValueAsBytes(Map.of(
                            "impactAcknowledgement",Map.of("freshnessFingerprint",firstFreshness))));
            assertEquals(200,firstPublished.status,new String(firstPublished.body(),StandardCharsets.UTF_8));
            assertEquals("ACTIVE",ServerRuntime.JSON.readTree(firstPublished.body()).path("activationState").asText());

            @SuppressWarnings("unchecked") List<Map<String,Object>> history=(List<Map<String,Object>>)runtime.changeSetPublisher.sourceHistory(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,0,20).get("items");
            String originalSnapshot=String.valueOf(history.get(0).get("beforeSnapshotId"));
            assertTrue(originalSnapshot.matches("R[A-F0-9]{32}"),"The pre-publication Config is retained as an immutable history snapshot");
            String currentRevision=runtime.changeSetPublisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true).get("baseSourceRevision");
            Captured staged=invokeAsAdmin(servlet,"alice","POST","/configuration/source-history/restore",Map.of(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("snapshotId",originalSnapshot,
                            "expectedSourceRevision",currentRevision)));
            assertEquals(201,staged.status,new String(staged.body(),StandardCharsets.UTF_8));
            JsonNode restore=ServerRuntime.JSON.readTree(staged.body());
            assertEquals(originalSnapshot,restore.path("restoredFromSnapshotId").asText());
            String restoreId=restore.path("changeSetId").asText();
            for(String action:List.of("validate","review")) {
                Captured response=invokeAsAdmin(servlet,"alice","POST","/configuration/change-sets/"+restoreId+"/"+action,
                        Map.of(),"{}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
            }

            Captured withoutImpact=invokeAsAdmin(servlet,"alice","POST",
                    "/configuration/change-sets/"+restoreId+"/publish",Map.of(),"{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(409,withoutImpact.status,new String(withoutImpact.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-CONFIG-IMPACT-STALE",ServerRuntime.JSON.readTree(withoutImpact.body())
                    .path("error").path("code").asText());
            assertEquals(candidate,Files.readString(runtimeConfig),"A restore without fresh impact must not change the active Config");

            Captured reportResponse=invokeAsAdmin(servlet,"alice","GET",
                    "/configuration/change-sets/"+restoreId+"/impact",Map.of(),new byte[0]);
            assertEquals(200,reportResponse.status,new String(reportResponse.body(),StandardCharsets.UTF_8));
            var restoreImpact=runtime.configurationImpactReport("alice",restoreId,null);
            assertNotNull(restoreImpact.freshness);
            assertEquals(att.server.api.source.SourceManagementContracts.ImpactDecision.COMPATIBLE,restoreImpact.decision);
            String restoreFreshness=ConfigImpactAnalyzer.freshnessFingerprint(restoreImpact.freshness);
            Captured restored=invokeAsAdmin(servlet,"alice","POST",
                    "/configuration/change-sets/"+restoreId+"/publish",Map.of(),ServerRuntime.JSON.writeValueAsBytes(Map.of(
                            "impactAcknowledgement",Map.of("freshnessFingerprint",restoreFreshness))));
            JsonNode restoredBody=ServerRuntime.JSON.readTree(restored.body());
            assertEquals(200,restored.status,new String(restored.body(),StandardCharsets.UTF_8));
            assertEquals("ACTIVE",restoredBody.path("activationState").asText(),restoredBody.toString());
            assertEquals(restoredBody.path("revision").asText(),restoredBody.path("activeRevision").asText(),restoredBody.toString());
            assertEquals(original,Files.readString(runtimeConfig));
            @SuppressWarnings("unchecked") List<Map<String,Object>> restoredHistory=(List<Map<String,Object>>)runtime.changeSetPublisher.sourceHistory(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,0,20).get("items");
            assertEquals("SOURCE_RESTORE_PUBLISH",restoredHistory.get(0).get("action"));
            assertEquals(originalSnapshot,restoredHistory.get(0).get("restoredFromSnapshotId"));
        }
    }

    @Test void centralPublishActivationFieldsSerializeEveryStatusOutcome() throws Exception {
        String scope=ChangeSetPublisher.CENTRAL_SCOPE_ID;
        var published=att.server.api.source.SourceManagementContracts.RevisionIdentity.of(
                att.server.api.source.SourceManagementContracts.RevisionKind.CONFIG_SOURCE,scope,"a".repeat(64));
        var active=att.server.api.source.SourceManagementContracts.RevisionIdentity.of(
                att.server.api.source.SourceManagementContracts.RevisionKind.CONFIG_SOURCE,scope,"b".repeat(64));
        for(var state:att.server.api.source.SourceManagementContracts.ActivationState.values()) {
            Map<String,Object> publication=new LinkedHashMap<>();
            publication.put("published",true);publication.put("revision",published.revision);publication.put("auditConfirmed",true);
            String failure=state==att.server.api.source.SourceManagementContracts.ActivationState.FAILED
                    ?"ATT-CONFIG-ACTIVATION-FAILED":null;
            var status=new att.server.api.source.SourceManagementContracts.ActivationStatus(scope,published,
                    state==att.server.api.source.SourceManagementContracts.ActivationState.UNAVAILABLE?null:active,state,failure);
            ApiServlet.addActivationResultFields(publication,status);
            JsonNode wire=ServerRuntime.JSON.readTree(ServerRuntime.JSON.writeValueAsBytes(publication));
            assertEquals(state.name(),wire.path("activationState").asText(),wire.toString());
            assertEquals(published.revision,wire.path("publishedRevision").asText(),wire.toString());
            if(state==att.server.api.source.SourceManagementContracts.ActivationState.UNAVAILABLE)
                assertTrue(wire.path("activeRevision").isNull(),wire.toString());
            else assertEquals(active.revision,wire.path("activeRevision").asText(),wire.toString());
            if(state==att.server.api.source.SourceManagementContracts.ActivationState.FAILED)
                assertEquals(failure,wire.path("activationFailureCode").asText(),wire.toString());
            assertTrue(wire.path("auditConfirmed").asBoolean(),wire.toString());
            assertFalse(wire.has("activationStatus"),wire.toString());
        }
    }

    @Test void centralExternalConfigEditsStayUnreviewedAcrossRestartAndPauseNewJobs() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("external-config-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("external-config-libs");
        String dataName="external-config-data";
        String original=Files.readString(runtimeConfig);
        String baseRevision;
        ServerRuntime first=runtime(packages,runtimeConfig,dataName,DEFAULT_INSPECTION_TIMEOUT_MS,null,libs,true,true);
        try {
            baseRevision=first.changeSetPublisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true).get("baseSourceRevision");
            Files.writeString(runtimeConfig,original+"# unreviewed deployment edit\n");
            var status=first.configurationActivationStatus();
            assertEquals(att.server.api.source.SourceManagementContracts.ActivationState.FAILED,status.state);
            assertEquals("ATT-CONFIG-EXTERNAL-SOURCE-DRIFT",status.failureCode);
            assertEquals(baseRevision,first.changeSetPublisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true).get("baseSourceRevision"),
                    "An out-of-band edit must not become the Studio draft base revision.");
            Captured blocked=invokeAs(servlet(first),"POST","/jobs/run",Map.of(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("packageId","p")),"alice",
                    Set.of(first.config.authorization.userRole));
            assertEquals(409,blocked.status,new String(blocked.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-CONFIG-ACTIVATION-FAILED",ServerRuntime.JSON.readTree(blocked.body()).path("error").path("code").asText());
        } finally { first.close(); }

        try(ServerRuntime restarted=runtime(packages,runtimeConfig,dataName,DEFAULT_INSPECTION_TIMEOUT_MS,null,libs,true,true)) {
            var status=restarted.configurationActivationStatus();
            assertEquals(att.server.api.source.SourceManagementContracts.ActivationState.FAILED,status.state);
            assertEquals("ATT-CONFIG-EXTERNAL-SOURCE-DRIFT",status.failureCode);
            assertEquals(baseRevision,restarted.changeSetPublisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true).get("baseSourceRevision"));

            Map<String,Object> draft=restarted.changeSetPublisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,baseRevision,null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",
                            (original+"# reviewed Studio publication\n").getBytes(StandardCharsets.UTF_8))));
            String id=String.valueOf(draft.get("changeSetId"));
            restarted.changeSetPublisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id);
            restarted.changeSetPublisher.review(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id);
            var impact=restarted.changeSetPublisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id,null);
            String freshness=ConfigImpactAnalyzer.freshnessFingerprint(impact.freshness);
            Captured published=invokeAsAdmin(servlet(restarted),"alice","POST",
                    "/configuration/change-sets/"+id+"/publish",Map.of(),ServerRuntime.JSON.writeValueAsBytes(Map.of(
                            "impactAcknowledgement",Map.of("freshnessFingerprint",freshness))));
            JsonNode body=ServerRuntime.JSON.readTree(published.body());
            assertEquals(200,published.status,new String(published.body(),StandardCharsets.UTF_8));
            assertEquals("ACTIVE",body.path("activationState").asText(),body.toString());
            assertEquals(body.path("revision").asText(),body.path("activeRevision").asText(),body.toString());
            assertTrue(Files.readString(runtimeConfig).contains("# reviewed Studio publication"));
            assertFalse(Files.readString(runtimeConfig).contains("# unreviewed deployment edit"));
            assertEquals(att.server.api.source.SourceManagementContracts.ActivationState.ACTIVE,
                    restarted.configurationActivationStatus().state);
        }
    }

    @Test void centralExternalConfigDriftClearsAfterMirrorRestoreAndResumesNewJobs() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("external-drift-recovery-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("external-drift-recovery-libs");
        String original=Files.readString(runtimeConfig);
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"external-drift-recovery-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,libs,true,true)) {
            Files.writeString(runtimeConfig,original+"# unreviewed deployment edit\n");
            var drift=runtime.configurationActivationStatus();
            assertEquals(att.server.api.source.SourceManagementContracts.ActivationState.FAILED,drift.state);
            assertEquals("ATT-CONFIG-EXTERNAL-SOURCE-DRIFT",drift.failureCode);

            byte[] runRequest=ServerRuntime.JSON.writeValueAsBytes(Map.of("packageId","p","all",true));
            Captured blocked=invokeAs(servlet(runtime),"POST","/jobs/run",Map.of(),runRequest,"alice",
                    Set.of(runtime.config.authorization.userRole));
            assertEquals(409,blocked.status,new String(blocked.body(),StandardCharsets.UTF_8));

            Files.writeString(runtimeConfig,original);
            var recovered=runtime.configurationActivationStatus();
            assertEquals(att.server.api.source.SourceManagementContracts.ActivationState.ACTIVE,recovered.state);
            assertNull(recovered.failureCode);
            Captured admitted=invokeAs(servlet(runtime),"POST","/jobs/run",Map.of(),runRequest,"alice",
                    Set.of(runtime.config.authorization.userRole));
            assertEquals(202,admitted.status,new String(admitted.body(),StandardCharsets.UTF_8));
        }
    }

    @Test void emptyRegisteredPackageReturnsEmptyInspectorInventoryAndDoesNotAffectAnotherPackage() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("empty-package-packages"));
        Files.createDirectory(packages.resolve("lilyzhou"));
        createPackage(packages.resolve("healthy"));
        Path broken=createPackage(packages.resolve("broken"));
        Files.delete(broken.resolve("templates"));
        Files.writeString(broken.resolve("templates"),"not a directory");
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"empty-package-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("empty-package-libs"))) {
            ApiServlet servlet=servlet(runtime);
            Captured catalogResponse=invoke(servlet,"GET","/packages/lilyzhou/load-catalog",Map.of("limit","100"),new byte[0]);
            JsonNode catalog=ServerRuntime.JSON.readTree(catalogResponse.body());
            assertEquals(200,catalogResponse.status,new String(catalogResponse.body(),StandardCharsets.UTF_8));
            assertTrue(catalog.path("items").isArray());assertEquals(0,catalog.path("items").size());
            assertEquals(0,catalog.path("total").asInt());assertTrue(catalog.path("nextCursor").isMissingNode()||catalog.path("nextCursor").isNull(),catalog.toString());
            assertTrue(catalogResponse.headers.get("X-ATT-Inspection-Revision").matches("[A-Za-z0-9_-]{43}"),catalogResponse.headers.toString());

            Captured resourcesResponse=invoke(servlet,"GET","/packages/lilyzhou/resources",Map.of("type","template"),new byte[0]);
            JsonNode resources=ServerRuntime.JSON.readTree(resourcesResponse.body());
            assertEquals(200,resourcesResponse.status,new String(resourcesResponse.body(),StandardCharsets.UTF_8));
            assertTrue(resources.path("items").isArray());assertEquals(0,resources.path("items").size());
            assertFalse(Files.exists(packages.resolve("lilyzhou/templates")));
            assertFalse(Files.exists(packages.resolve("lilyzhou/testcase")));
            assertFalse(Files.exists(packages.resolve("lilyzhou/load")));

            Captured invalidRoot=invoke(servlet,"GET","/packages/broken/resources",Map.of("type","template"),new byte[0]);
            JsonNode invalidBody=ServerRuntime.JSON.readTree(invalidRoot.body());
            assertEquals(422,invalidRoot.status,new String(invalidRoot.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-RESOURCE-ROOT-INVALID",invalidBody.path("diagnostics").path(0).path("code").asText());
            assertEquals("resources.templatesRoot",invalidBody.path("diagnostics").path(0).path("field").asText());
            assertFalse(new String(invalidRoot.body(),StandardCharsets.UTF_8).contains(packages.toString()));

            Captured nextPackage=invoke(servlet,"GET","/packages/healthy/resources",Map.of("type","template"),new byte[0]);
            assertEquals(200,nextPackage.status,new String(nextPackage.body(),StandardCharsets.UTF_8));
            Captured retried=invoke(servlet,"GET","/packages/lilyzhou/load-catalog",Map.of("limit","100"),new byte[0]);
            assertEquals(200,retried.status,new String(retried.body(),StandardCharsets.UTF_8));
            assertEquals(0,ServerRuntime.JSON.readTree(retried.body()).path("items").size());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void packageFileIndexPagesCanonicalReferencesWithCallSitesAndRejectsStaleCursors() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("package-file-index-packages"));
        Path packageRoot=createPackage(packages.resolve("alice"));
        Path template=Files.createDirectories(packageRoot.resolve("templates/SAMPLE")).resolve("template.yaml");
        Files.writeString(template,"schemaVersion: att-template/v3.6\nname: SAMPLE\ndescription: File index fixture\nactions:\n"
                +"  sendRequest:\n    type: log\n    message: '&{./input.json}'\n"
                +"  retryRequest:\n    type: log\n    message: '&{./input.json}'\n"
                +"  sendArchive:\n    type: log\n    message: '&{./archive.xml}'\n");
        Files.writeString(packageRoot.resolve("templates/SAMPLE/input.json"),"{\"ok\":true}\n");
        Files.writeString(packageRoot.resolve("templates/SAMPLE/archive.xml"),"<archive/>\n");
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"package-file-index-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,
                workerLibraries("package-file-index-libs"))) {
            ApiServlet servlet=servlet(runtime);
            Captured resources=invokeAs(servlet,"alice","GET","/packages/alice/resources",
                    Map.of("type","template"),new byte[0]);
            JsonNode resourcePage=ServerRuntime.JSON.readTree(resources.body());
            assertEquals(200,resources.status,new String(resources.body(),StandardCharsets.UTF_8));
            assertEquals(1,resourcePage.path("total").asInt(),resourcePage.toString());
            assertEquals("ready",resourcePage.path("items").get(0).path("state").asText(),resourcePage.toString());
            assertEquals(true,resourcePage.path("items").get(0).path("sourceAvailable").asBoolean(),resourcePage.toString());
            Captured first=invokeAs(servlet,"alice","GET","/packages/alice/resources/files",
                    Map.of("limit","1"),new byte[0]);
            JsonNode firstPage=ServerRuntime.JSON.readTree(first.body());
            assertEquals(200,first.status,new String(first.body(),StandardCharsets.UTF_8));
            assertEquals(2,firstPage.path("total").asInt(),firstPage.toString());
            assertEquals("COMPLETE",firstPage.path("coverage").path("state").asText());
            JsonNode input=firstPage.path("items").get(0);
            assertEquals("templates/SAMPLE/archive.xml",input.path("path").asText());
            assertFalse(firstPage.path("nextCursor").asText().isBlank());

            String cursor=firstPage.path("nextCursor").asText();
            Captured second=invokeAs(servlet,"alice","GET","/packages/alice/resources/files",
                    Map.of("limit","1","cursor",cursor),new byte[0]);
            JsonNode secondPage=ServerRuntime.JSON.readTree(second.body());
            assertEquals(200,second.status,new String(second.body(),StandardCharsets.UTF_8));
            assertEquals("templates/SAMPLE/input.json",secondPage.path("items").get(0).path("path").asText());
            JsonNode use=secondPage.path("items").get(0).path("uses").get(0);
            assertEquals("SAMPLE",use.path("label").asText());
            assertEquals(2,use.path("callSites").size());
            assertEquals(Set.of("sendRequest","retryRequest"),java.util.stream.StreamSupport.stream(
                    secondPage.path("items").get(0).path("uses").get(0).path("callSites").spliterator(),false)
                    .map(callSite->callSite.path("actionId").asText()).collect(java.util.stream.Collectors.toSet()));
            Files.writeString(packageRoot.resolve("templates/SAMPLE/input.json"),"{\"ok\":false}\n");
            Captured stale=invokeAs(servlet,"alice","GET","/packages/alice/resources/files",
                    Map.of("limit","1","cursor",cursor),new byte[0]);
            JsonNode staleBody=ServerRuntime.JSON.readTree(stale.body());
            assertEquals(409,stale.status,new String(stale.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-RESOURCE-CURSOR-STALE",staleBody.path("error").path("code").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void asynchronousValidationRecordIsDurableOwnerScopedAndCreatesNoJob() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("validation-record-packages"));
        createPackage(packages.resolve("payments"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path sharedHelper=Files.createDirectories(runtimeConfig.getParent().resolve("dbhelpers/sit")).resolve("orders.yaml");
        Files.writeString(sharedHelper,sharedDbHelper("initial helper"));
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.15\nenvironment: SIT\n"
                +"environments:\n  SIT:\n    dbhelpers: [config/dbhelpers/sit/orders.yaml]\ntimeoutMs: 10000\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\n");
        Path libs=workerLibraries("validation-record-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"validation-record-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs,
                true,1,ref->System.getenv("ATT_SECRET_"+ref),true)) {
            ApiServlet servlet=servlet(runtime);
            byte[] body="{\"command\":\"validate\",\"targetKind\":\"config\"}".getBytes(StandardCharsets.UTF_8);
            Captured accepted=invokeAs(servlet,"POST","/validations",Map.of(),body,"alice");
            JsonNode queued=ServerRuntime.JSON.readTree(accepted.body());
            assertEquals(202,accepted.status,new String(accepted.body(),StandardCharsets.UTF_8));
            assertTrue(queued.path("validationId").asText().matches("V[0-9A-F]{32}"));
            String id=queued.path("validationId").asText();
            assertEquals("QUEUED",queued.path("status").asText());
            assertFalse(queued.has("environment"),"Shared Config context is canonical with a null Environment");
            assertTrue(accepted.headers.get("Location").endsWith("/api/v1/validations/"+id));

            Captured otherOwner=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"bob");
            assertEquals(404,otherOwner.status,"Record identifiers must not disclose another owner's validation");
            Captured list=invokeAs(servlet,"GET","/validations",Map.of("limit","10"),new byte[0],"alice");
            JsonNode listed=ServerRuntime.JSON.readTree(list.body());
            assertEquals(200,list.status,new String(list.body(),StandardCharsets.UTF_8));
            assertEquals(id,listed.path("items").path(0).path("validationId").asText());
            assertFalse(listed.path("items").path(0).has("report"),"List rows must not duplicate durable reports");

            Captured result=null;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline) {
                result=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"alice");
                JsonNode record=ServerRuntime.JSON.readTree(result.body());
                if(!List.of("QUEUED","RUNNING").contains(record.path("status").asText()))break;
                Thread.sleep(25);
            }
            assertNotNull(result);
            JsonNode completed=ServerRuntime.JSON.readTree(result.body());
            assertEquals(200,result.status,new String(result.body(),StandardCharsets.UTF_8));
            assertEquals("COMPLETED",completed.path("status").asText());
            assertEquals("att-validation/v1",completed.path("report").path("schemaVersion").asText());
            assertFalse(completed.has("environment"),"The report may describe Runtime default SIT without changing the Shared Config context key");
            assertEquals("SIT",completed.path("report").path("context").path("environment").asText());
            assertTrue(completed.path("sourceRevision").asText().matches("[a-f0-9]{64}"));
            assertEquals("CURRENT",completed.path("applicability").asText());
            assertEquals(0,runtime.jobsFor("alice",null).size(),"Read-only validation must not create a Job");

            Captured matching=invokeAs(servlet,"GET","/validations",Map.of("scope","config","matchContext","true","limit","1"),new byte[0],"alice");
            assertEquals(id,ServerRuntime.JSON.readTree(matching.body()).path("items").path(0).path("validationId").asText());
            Captured summary=invokeAs(servlet,"GET","/validations/summary",Map.of(),new byte[0],"alice");
            JsonNode summaryRecord=ServerRuntime.JSON.readTree(summary.body()).path("record");
            assertEquals(id,summaryRecord.path("validationId").asText());
            assertFalse(summaryRecord.has("report"));

            Semaphore validationAdmission=semaphore(runtime,"validationAdmissionSlots");
            long admissionDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(validationAdmission.availablePermits()==0&&System.nanoTime()<admissionDeadline)Thread.sleep(5);
            assertTrue(validationAdmission.availablePermits()>0,"The next submission must have a free validation admission slot");

            Files.writeString(runtimeConfig,Files.readString(runtimeConfig)+"\n# changed after validation\n");
            Captured mirrorEdit=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"alice");
            assertEquals("CURRENT",ServerRuntime.JSON.readTree(mirrorEdit.body()).path("applicability").asText(),
                    "Unpublished edits to the deployment mirror do not change the active Config used by validation");

            Files.writeString(sharedHelper,sharedDbHelper("edited external child helper"));
            Captured sharedHelperStale=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"alice");
            assertEquals("CURRENT",ServerRuntime.JSON.readTree(sharedHelperStale.body()).path("applicability").asText(),
                    "Unpublished helper edits do not change the active Config used by validation");

            long packageAdmissionDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(validationAdmission.availablePermits()==0&&System.nanoTime()<packageAdmissionDeadline)Thread.sleep(5);
            byte[] packageBody="{\"command\":\"validate\",\"targetKind\":\"config\",\"packageId\":\"payments\"}"
                    .getBytes(StandardCharsets.UTF_8);
            Captured packageAccepted=invokeAs(servlet,"POST","/validations",Map.of(),packageBody,"alice");
            assertEquals(202,packageAccepted.status,new String(packageAccepted.body(),StandardCharsets.UTF_8));
            String packageValidationId=ServerRuntime.JSON.readTree(packageAccepted.body()).path("validationId").asText();
            Captured packageResult=null;long packageDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<packageDeadline) {
                packageResult=invokeAs(servlet,"GET","/validations/"+packageValidationId,Map.of(),new byte[0],"alice");
                JsonNode record=ServerRuntime.JSON.readTree(packageResult.body());
                if(!List.of("QUEUED","RUNNING").contains(record.path("status").asText()))break;
                Thread.sleep(25);
            }
            assertNotNull(packageResult);
            JsonNode packageCompleted=ServerRuntime.JSON.readTree(packageResult.body());
            assertEquals("COMPLETED",packageCompleted.path("status").asText());
            assertEquals("CURRENT",packageCompleted.path("applicability").asText());
            Files.writeString(sharedHelper,sharedDbHelper("edited again for Package validation"));
            Captured packageHelperStale=invokeAs(servlet,"GET","/validations/"+packageValidationId,Map.of(),new byte[0],"alice");
            assertEquals("CURRENT",ServerRuntime.JSON.readTree(packageHelperStale.body()).path("applicability").asText(),
                    "Package validation also remains bound to the active central Config revision");

            // The publication transaction imports the deployment mirror only after a caller has
            // reviewed it. Restore the accepted mirror bytes so this test can publish an explicit,
            // independently staged Config revision after proving the unreviewed edits stayed inert.
            Path activeRoot=runtime.executionCentralSourceRoot();
            Path activeConfig=activeRoot.resolve("config/config.yaml");
            Files.writeString(runtimeConfig,Files.readString(activeConfig));
            Files.writeString(sharedHelper,Files.readString(activeRoot.resolve("config/dbhelpers/sit/orders.yaml")));
            Map<String,String> revisions=runtime.changeSetPublisher.currentRevisions(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true);
            String candidate=Files.readString(activeConfig)+"\n# published validation source revision\n";
            Map<String,Object> draft=runtime.changeSetPublisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,revisions.get("baseSourceRevision"),null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",candidate.getBytes(StandardCharsets.UTF_8))));
            String changeSetId=String.valueOf(draft.get("changeSetId"));
            try {
                runtime.changeSetPublisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                        ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,changeSetId);
            } catch(ChangeSetPublisher.ValidationRejected rejected) {
                throw new AssertionError("validation-source revision candidate rejected with "+rejected.code,rejected);
            }
            runtime.changeSetPublisher.review(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,changeSetId);
            var impact=runtime.changeSetPublisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,changeSetId,null);
            assertNotEquals(att.server.api.source.SourceManagementContracts.ImpactDecision.BLOCKED,impact.decision,
                    "Impact provider="+impact.providerState+" code="+impact.unavailableCode+" coverage="+impact.coverage);
            String freshness=ConfigImpactAnalyzer.freshnessFingerprint(impact.freshness);
            att.server.api.source.SourceManagementContracts.ImpactAcknowledgement acknowledgement=
                    new att.server.api.source.SourceManagementContracts.ImpactAcknowledgement(freshness,"alice",
                            ConfigImpactAnalyzer.acknowledgementDigest(freshness,"alice"));
            Map<String,Object> publication=runtime.publishConfigurationChangeSet("alice",changeSetId,
                    "validation-source-revision-test",acknowledgement);
            assertEquals(Boolean.TRUE,publication.get("published"));

            Captured sharedConfigStale=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"alice");
            assertEquals("STALE",ServerRuntime.JSON.readTree(sharedConfigStale.body()).path("applicability").asText(),
                    "Publishing a new active central Config revision stales the prior Shared Config validation");
            Captured packageConfigStale=invokeAs(servlet,"GET","/validations/"+packageValidationId,Map.of(),new byte[0],"alice");
            assertEquals("STALE",ServerRuntime.JSON.readTree(packageConfigStale.body()).path("applicability").asText(),
                    "Publishing a new active central Config revision stales Package validation too");

            ReentrantLock workerLock=inspectionWorkerLock(runtime);workerLock.lock();
            java.util.concurrent.CountDownLatch blockerStarted=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseBlocker=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ThreadPoolExecutor executor=validationExecutor(runtime);
            executor.execute(()->{blockerStarted.countDown();try{releaseBlocker.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            assertTrue(blockerStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            String secondId;
            try {
                Captured secondAccepted=invokeAs(servlet,"POST","/validations",Map.of(),body,"alice");
                secondId=ServerRuntime.JSON.readTree(secondAccepted.body()).path("validationId").asText();
                assertEquals(202,secondAccepted.status);
                JsonNode queuedAgain=ServerRuntime.JSON.readTree(secondAccepted.body());
                assertEquals("QUEUED",queuedAgain.path("status").asText());
                assertFalse(queuedAgain.has("environment"));
                Captured queuedLookup=invokeAs(servlet,"GET","/validations",Map.of("scope","config","matchContext","true","limit","1"),new byte[0],"alice");
                assertEquals(secondId,ServerRuntime.JSON.readTree(queuedLookup.body()).path("items").path(0).path("validationId").asText(),
                        "A Shared Config lookup recovers the queued record without another submission");
                releaseBlocker.countDown();
                boolean sawRunning=false;long runningDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<runningDeadline) {
                    JsonNode current=ServerRuntime.JSON.readTree(invokeAs(servlet,"GET","/validations/"+secondId,Map.of(),new byte[0],"alice").body());
                    if("RUNNING".equals(current.path("status").asText())){sawRunning=true;break;}
                    Thread.sleep(10);
                }
                assertTrue(sawRunning,"Validation should enter RUNNING while waiting for the held inspection Worker");
                Captured runningLookup=invokeAs(servlet,"GET","/validations",Map.of("scope","config","matchContext","true","limit","1"),new byte[0],"alice");
                assertEquals(secondId,ServerRuntime.JSON.readTree(runningLookup.body()).path("items").path(0).path("validationId").asText(),
                        "A Shared Config lookup recovers the running record without another submission");
            } finally {releaseBlocker.countDown();workerLock.unlock();}
            Captured secondResult=null;long secondDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<secondDeadline) {
                secondResult=invokeAs(servlet,"GET","/validations/"+secondId,Map.of(),new byte[0],"alice");
                if(!List.of("QUEUED","RUNNING").contains(ServerRuntime.JSON.readTree(secondResult.body()).path("status").asText()))break;
                Thread.sleep(25);
            }
            assertNotNull(secondResult);
            assertEquals("COMPLETED",ServerRuntime.JSON.readTree(secondResult.body()).path("status").asText());
            Captured completedLookup=invokeAs(servlet,"GET","/validations",Map.of("scope","config","matchContext","true","limit","1"),new byte[0],"alice");
            assertEquals(secondId,ServerRuntime.JSON.readTree(completedLookup.body()).path("items").path(0).path("validationId").asText(),
                    "The Shared Config lookup also recovers the completed record");
            stopInspectionProcesses(runtime);
        }
    }

    @Test void queuedPlanFreezesTheResolvedEnvironmentAcrossConfigurationChanges() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("validation-environment-packages"));
        createPackage(packages.resolve("payments"));
        Path runtimeConfig=sharedRuntimeConfig();
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\n  UAT: {}\ntimeoutMs: 10000\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\n");
        Path libs=workerLibraries("validation-environment-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"validation-environment-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs,true,1)) {
            ApiServlet servlet=servlet(runtime);
            java.util.concurrent.CountDownLatch blockerStarted=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseBlocker=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ThreadPoolExecutor executor=validationExecutor(runtime);
            String validationId;
            executor.execute(()->{blockerStarted.countDown();try{releaseBlocker.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            assertTrue(blockerStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            try {
                byte[] body="{\"command\":\"validate\",\"targetKind\":\"config\",\"packageId\":\"payments\"}"
                        .getBytes(StandardCharsets.UTF_8);
                Captured accepted=invokeAs(servlet,"POST","/validations",Map.of(),body,"alice");
                JsonNode queued=ServerRuntime.JSON.readTree(accepted.body());validationId=queued.path("validationId").asText();
                assertEquals(202,accepted.status,new String(accepted.body(),StandardCharsets.UTF_8));
                assertEquals("QUEUED",queued.path("status").asText());
                assertEquals("SIT",queued.path("environment").asText(),"The durable record must capture the submitted effective Environment");

                Files.writeString(runtimeConfig,Files.readString(runtimeConfig).replace("environment: SIT","environment: UAT"));
                JsonNode stillQueued=ServerRuntime.JSON.readTree(invokeAs(servlet,"GET","/validations/"+validationId,Map.of(),new byte[0],"alice").body());
                assertEquals("QUEUED",stillQueued.path("status").asText());
                assertEquals("SIT",stillQueued.path("environment").asText());
            } finally {releaseBlocker.countDown();}

            JsonNode completed=null;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline) {
                Captured result=invokeAs(servlet,"GET","/validations/"+validationId,Map.of(),new byte[0],"alice");
                completed=ServerRuntime.JSON.readTree(result.body());
                if(!List.of("QUEUED","RUNNING").contains(completed.path("status").asText()))break;
                Thread.sleep(25);
            }
            assertNotNull(completed);
            assertEquals("COMPLETED",completed.path("status").asText());
            assertEquals("SIT",completed.path("environment").asText());
            assertEquals("SIT",completed.path("report").path("context").path("environment").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void inspectionEndpointRejectsDeterministicWorkbookCountLimit() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("packages"));
        Path counted=createPackage(packages.resolve("counted"));
        Path cases=Files.createDirectories(counted.resolve("testcase"));
        for(int index=0;index<=500;index++)Files.createFile(cases.resolve(String.format("case-%03d.xlsx",index)));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("inspection-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"inspection-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            ApiServlet servlet=servlet(runtime);
            Captured scan=invoke(servlet,"GET","/packages/counted/resources",Collections.singletonMap("type","case"),new byte[0]);
            JsonNode rejected=ServerRuntime.JSON.readTree(scan.body());
            JsonNode diagnostic=rejected.path("diagnostics").path(0);
            assertEquals(413,scan.status);
            assertEquals("ATT-RESOURCE-LIMIT",diagnostic.path("code").asText());
            assertEquals("resourceInspector.maxWorkbooks",diagnostic.path("limitName").asText(),new String(scan.body(),StandardCharsets.UTF_8));
            assertEquals(500,diagnostic.path("configuredLimit").asInt());
            assertEquals(501,diagnostic.path("observed").asInt());
            assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
            assertFalse(new String(scan.body(),StandardCharsets.UTF_8).contains(packages.toString()));
            stopInspectionProcesses(runtime);
        }
    }

    @Test void inspectionEndpointReturnsPartialSuiteForOversizedWorkbook() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("partial-packages"));
        Path oversized=createPackage(packages.resolve("oversized"));
        Path oversizedCases=Files.createDirectories(oversized.resolve("testcase"));
        try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(oversizedCases.resolve("large.xlsx").toFile(),"rw")) {
            file.setLength(32L*1024L*1024L+1L);
        }
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("partial-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"partial-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            ApiServlet servlet=servlet(runtime);
            Captured partial=invoke(servlet,"GET","/packages/oversized/resources",Collections.singletonMap("type","case"),new byte[0]);
            JsonNode response=ServerRuntime.JSON.readTree(partial.body());
            assertEquals(200,partial.status);
            assertEquals("invalid",response.path("items").path(0).path("state").asText());
            JsonNode suiteDiagnostic=response.path("items").path(0).path("diagnostics").path(0);
            assertEquals("ATT-RESOURCE-CASE-INVALID",suiteDiagnostic.path("code").asText());
            assertEquals("resourceInspector.maxWorkbookBytes",suiteDiagnostic.path("limitName").asText(),new String(partial.body(),StandardCharsets.UTF_8));
            assertEquals(32L*1024L*1024L,suiteDiagnostic.path("configuredLimit").asLong());
            assertEquals("DATA_TRUNCATED",suiteDiagnostic.path("outcome").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void inspectionEndpointRejectsWhenQueueIsSaturated() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("saturated-packages"));
        createPackage(packages.resolve("oversized"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("saturated-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"saturated-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            ApiServlet servlet=servlet(runtime);
            Semaphore inspectionSlots=semaphore(runtime,"inspectionTaskSlots");
            inspectionSlots.acquire();
            try {
                Captured saturated=invoke(servlet,"GET","/packages/oversized/resources",Collections.singletonMap("type","case"),new byte[0]);
                JsonNode capacity=ServerRuntime.JSON.readTree(saturated.body()).path("diagnostics").path(0);
                assertEquals(503,saturated.status);
                assertEquals("ATT-SERVER-INSPECTION-CAPACITY",capacity.path("code").asText());
                assertEquals("SUBMISSION_REJECTED",capacity.path("outcome").asText());
                assertEquals("server.inspection.capacity",capacity.path("limitName").asText());
            } finally {inspectionSlots.release();}
            stopInspectionProcesses(runtime);
        }
    }

    @Test void requestAndWorkerAdmissionFailuresUseTheActualServletRoutes() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("admission-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("admission-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"admission-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            ApiServlet servlet=servlet(runtime);
            byte[] oversizedBody=new byte[1025];
            Captured tooLarge=invoke(servlet,"POST","/jobs/run",Collections.emptyMap(),oversizedBody);
            JsonNode requestLimit=ServerRuntime.JSON.readTree(tooLarge.body()).path("diagnostics").path(0);
            assertEquals(413,tooLarge.status);
            assertEquals("ATT-SERVER-REQUEST-TOO-LARGE",requestLimit.path("code").asText());
            assertEquals("SUBMISSION_REJECTED",requestLimit.path("outcome").asText());
            assertEquals("server.maxRequestBytes",requestLimit.path("limitName").asText());

            Semaphore admission=semaphore(runtime,"admissionSlots");
            admission.acquire();
            try {
                Captured full=invoke(servlet,"POST","/jobs/run",Collections.emptyMap(),"{\"packageId\":\"p\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                JsonNode queue=ServerRuntime.JSON.readTree(full.body()).path("diagnostics").path(0);
                assertEquals(429,full.status);
                assertEquals("ATT-SERVER-CAPACITY-EXCEEDED",queue.path("code").asText());
                assertEquals("SUBMISSION_REJECTED",queue.path("outcome").asText());
                assertEquals("server.admission.queue",queue.path("limitName").asText());
            } finally {admission.release();}

            Semaphore inspections=semaphore(runtime,"inspectionTaskSlots");
            inspections.acquire();
            try {
                Captured full=invoke(servlet,"GET","/packages/p/resources",Collections.singletonMap("type","template"),new byte[0]);
                JsonNode queue=ServerRuntime.JSON.readTree(full.body()).path("diagnostics").path(0);
                assertEquals(503,full.status);
                assertEquals("ATT-SERVER-INSPECTION-CAPACITY",queue.path("code").asText());
                assertEquals("SUBMISSION_REJECTED",queue.path("outcome").asText());
            } finally {inspections.release();}
        }
    }

    @Test void sutMetricsRoutesEnforceAdminWritesBoundBodiesAndFilterSourcesForReaders() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-route-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        List<Map<String,Object>> dataSources=List.of(
                Map.of("schemaVersion","att-sut-metrics-source/v1","id","prom","name","Local Prometheus",
                        "url","http://127.0.0.1:9090","auth",Map.of("mode","BEARER","secretRef","PROM_TOKEN"),
                        "readers",List.of("alice"),"readRoles",List.of(),"allowedTenants",List.of("tenant-a"),"defaultTenant","tenant-a"),
                Map.of("schemaVersion","att-sut-metrics-source/v1","id","role-prom","name","Role-readable Prometheus",
                        "url","http://127.0.0.1:9091","auth",Map.of("mode","NONE"),"readers",List.of(),
                        "readRoles",List.of("USER"),"allowedTenants",List.of()));
        List<Map<String,Object>> metricSources=List.of(
                Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","private-prom-metrics","name","Private Prometheus metrics",
                        "connection",Map.of("kind","DATA_SOURCE","id","prom"),
                        "fetch",Map.of("id","prom-fetch","mode","prometheus-range","query","up{instance=\"sentinel_prometheus_query\"}",
                                "parameters",Map.of("region","sentinel_prometheus_parameter"),"sampling","range","step","15s"),
                        "mapping",Map.of("labels",Map.of("retain",List.of("instance")),
                                "outputs",List.of(Map.of("id","prom-up","title","Prometheus up","unit","count","kind","gauge","metricName","up"))),
                        "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576)),
                Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","private-db-metrics","name","Private DB metrics",
                        "connection",Map.of("kind","DBHELPER","id","orders"),
                        "fetch",Map.of("id","db-fetch","mode","dbhelper-query",
                                "call","#{db.orders.query(sql='select sentinel_dbhelper_sql from private_table')}",
                                "parameters",Map.of("tenant","sentinel_dbhelper_parameter"),"sampling","afterDrain"),
                        "mapping",Map.of("labels",Map.of("instance","sentinel_dbhelper_column"),
                                "outputs",List.of(Map.of("id","db-count","title","DB count","unit","count","kind","gauge","column","COUNT_VALUE"))),
                        "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576)),
                Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","private-tool-metrics","name","Private tool metrics",
                        "connection",Map.of("kind","PACKAGE_TOOL","id","private-tool"),
                        "fetch",Map.of("id","tool-fetch","mode","package-tool",
                                "call","#{tools.private.read(path='sentinel_package_tool_call')}",
                                "parameters",Map.of("scope","sentinel_package_tool_parameter"),"sampling","afterDrain"),
                        "mapping",Map.of("labels",Map.of("instance","sentinel_package_tool_column"),
                                "outputs",List.of(Map.of("id","tool-count","title","Tool count","unit","count","kind","gauge","column","COUNT_VALUE"))),
                        "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576)));
        Map<String,Object> groupedProfile=Map.of("schemaVersion","att-sut-metrics-profile/v2","id","grouped",
                "name","Grouped profile","metricSourceIds",List.of("private-prom-metrics"),
                "groupIds",List.of("private-prom-group"),
                "allowedBindings",Map.of("environments",List.of(),"targets",List.of(Map.of("type","target","id","sut-1")),
                        "tenants",List.of(),"parameters",Map.of()),
                "budgets",Map.of("maxFetches",4,"maxObservations",512,"maxArtifactBytes",1048576));
        List<Map<String,Object>> profiles=List.of(Map.of("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile",
                "kind","base","category","app","metrics",List.of(Map.of("id","requests","title","Requests","unit","count",
                        "kind","gauge","query","up"))),groupedProfile);
        List<Map<String,Object>> groups=List.of(Map.of("schemaVersion","att-sut-metrics-group/v1","id","private-prom-group",
                "title","Private Prometheus group","order",1,"match",Map.of("metricIds",List.of("prom-up"),
                        "sourceIds",List.of("prom"),"labels",Map.of("tenant","sentinel_private_tenant")),
                "groupBy",List.of("instance"),"display","table"));
        writeSutMetricsConfig(runtimeConfig,dataSources,metricSources,groups,profiles,true);
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"sut-metrics-route-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,workerLibraries("sut-metrics-route-libs"),true,ref->"PROM_TOKEN".equals(ref)?"api-test-token":null)) {
            ApiServlet servlet=servlet(runtime);
            String userRole=runtime.config.authorization.userRole;
            String adminRole=runtime.config.authorization.adminRole;
            byte[] source=ServerRuntime.JSON.writeValueAsBytes(Map.of("schemaVersion","att-sut-metrics-source/v1","id","prom",
                    "name","Local Prometheus","url","http://127.0.0.1:9090","auth",Map.of("mode","BEARER","secretRef","PROM_TOKEN"),
                    "readers",List.of("alice"),"readRoles",List.of(),"allowedTenants",List.of("tenant-a"),"defaultTenant","tenant-a"));

            Captured anonymousRead=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],null,Set.of());
            assertEquals(401,anonymousRead.status);
            assertEquals("private, no-store",anonymousRead.headers.get("Cache-Control"));
            Captured readerWrite=invokeAs(servlet,"POST","/admin/sut-metrics/sources",Collections.emptyMap(),source,"alice",Set.of(userRole));
            assertEquals(403,readerWrite.status);
            assertEquals("private, no-store",readerWrite.headers.get("Cache-Control"));

            byte[] oversized=new byte[1025];
            Captured oversizedWrite=invokeAs(servlet,"POST","/admin/sut-metrics/sources",Collections.emptyMap(),oversized,"admin",Set.of(adminRole));
            JsonNode diagnostic=ServerRuntime.JSON.readTree(oversizedWrite.body()).path("diagnostics").path(0);
            assertEquals(413,oversizedWrite.status);
            assertEquals("ATT-SERVER-REQUEST-TOO-LARGE",diagnostic.path("code").asText());
            assertEquals("server.maxRequestBytes",diagnostic.path("limitName").asText());
            assertEquals("private, no-store",oversizedWrite.headers.get("Cache-Control"));

            Captured adminWrite=invokeAs(servlet,"POST","/admin/sut-metrics/sources",Collections.emptyMap(),source,"admin",Set.of(adminRole));
            assertEquals(409,adminWrite.status,new String(adminWrite.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-SUT-METRICS-CONFIG-READ-ONLY",ServerRuntime.JSON.readTree(adminWrite.body()).path("error").path("code").asText());
            byte[] invalidGroup=ServerRuntime.JSON.writeValueAsBytes(Map.of("schemaVersion","att-sut-metrics-group/v1",
                    "id","invalid-group","title","Invalid group","order",10,
                    "match",Map.of("metricIds",List.of("cpu")),"groupBy",List.of(17),"display","table"));
            Captured invalidGroupWrite=invokeAs(servlet,"POST","/admin/sut-metrics/groups",Collections.emptyMap(),
                    invalidGroup,"admin",Set.of(adminRole));
            assertEquals(409,invalidGroupWrite.status,new String(invalidGroupWrite.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-SUT-METRICS-CONFIG-READ-ONLY",ServerRuntime.JSON.readTree(invalidGroupWrite.body())
                    .path("error").path("code").asText());
            Captured readerList=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],"alice",Set.of(userRole));
            JsonNode readerItem=ServerRuntime.JSON.readTree(readerList.body()).path("items").path(0);
            assertEquals(200,readerList.status);
            assertEquals("private, no-store",readerList.headers.get("Cache-Control"));
            assertEquals("Local Prometheus",readerItem.path("name").asText());
            assertTrue(readerItem.path("authConfigured").asBoolean());
            assertFalse(readerItem.has("url"),readerItem.toString());
            assertFalse(readerList.bodyText().contains("PROM_TOKEN"),readerList.bodyText());

            byte[] roleSource=ServerRuntime.JSON.writeValueAsBytes(Map.of("schemaVersion","att-sut-metrics-source/v1","id","role-prom",
                    "name","Role-readable Prometheus","url","http://127.0.0.1:9091","auth",Map.of("mode","NONE"),
                    "readers",List.of(),"readRoles",List.of("USER"),"allowedTenants",List.of()));
            Captured roleWrite=invokeAs(servlet,"POST","/admin/sut-metrics/sources",Collections.emptyMap(),roleSource,"admin",Set.of(adminRole));
            assertEquals(409,roleWrite.status,new String(roleWrite.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-SUT-METRICS-CONFIG-READ-ONLY",ServerRuntime.JSON.readTree(roleWrite.body()).path("error").path("code").asText());

            Captured otherReader=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],"bob",Set.of(userRole));
            JsonNode roleItem=ServerRuntime.JSON.readTree(otherReader.body()).path("items").path(0);
            assertEquals(200,otherReader.status);
            assertEquals(1,ServerRuntime.JSON.readTree(otherReader.body()).path("items").size());
            assertEquals("role-prom",roleItem.path("id").asText());
            Captured adminList=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],"admin",Set.of(adminRole));
            JsonNode adminItem=ServerRuntime.JSON.readTree(adminList.body()).path("items").path(0);
            assertEquals("http://127.0.0.1:9090",adminItem.path("url").asText());
            assertEquals("PROM_TOKEN",adminItem.path("auth").path("secretRef").asText());

            Captured readerMetricSources=invokeAs(servlet,"GET","/sut-metrics/metric-sources",Collections.emptyMap(),
                    new byte[0],"alice",Set.of(userRole));
            assertEquals(200,readerMetricSources.status,readerMetricSources.bodyText());
            assertEquals("private, no-store",readerMetricSources.headers.get("Cache-Control"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_prometheus_query"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_prometheus_parameter"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_dbhelper_sql"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_dbhelper_parameter"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_dbhelper_column"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_package_tool_call"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_package_tool_parameter"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_package_tool_column"));
            assertFalse(readerMetricSources.bodyText().contains("a".repeat(64)));
            JsonNode safeMetricSource=ServerRuntime.JSON.readTree(readerMetricSources.body()).path("items").path(0);
            assertFalse(safeMetricSource.has("fetch"),safeMetricSource.toString());
            assertEquals("DBHELPER",safeMetricSource.path("connection").path("kind").asText());
            assertEquals("db-count",safeMetricSource.path("mapping").path("outputs").path(0).path("id").asText());

            Captured readerGroups=invokeAs(servlet,"GET","/sut-metrics/groups",Collections.emptyMap(),
                    new byte[0],"bob",Set.of(userRole));
            assertEquals(200,readerGroups.status,readerGroups.bodyText());
            JsonNode safeGroup=ServerRuntime.JSON.readTree(readerGroups.body()).path("items").path(0);
            assertEquals("private-prom-group",safeGroup.path("id").asText());
            assertFalse(safeGroup.has("match"),safeGroup.toString());
            assertFalse(readerGroups.bodyText().contains("sentinel_private_tenant"),readerGroups.bodyText());
            assertFalse(readerGroups.bodyText().contains("prom-up"),readerGroups.bodyText());
            assertFalse(readerGroups.bodyText().contains("sourceIds"),readerGroups.bodyText());

            Captured adminGroups=invokeAs(servlet,"GET","/sut-metrics/groups",Collections.emptyMap(),
                    new byte[0],"admin",Set.of(adminRole));
            assertEquals(200,adminGroups.status,adminGroups.bodyText());
            assertTrue(adminGroups.bodyText().contains("sentinel_private_tenant"),adminGroups.bodyText());
            assertTrue(adminGroups.bodyText().contains("prom-up"),adminGroups.bodyText());

            Captured adminMetricSources=invokeAs(servlet,"GET","/sut-metrics/metric-sources",Collections.emptyMap(),
                    new byte[0],"admin",Set.of(adminRole));
            assertEquals(200,adminMetricSources.status,adminMetricSources.bodyText());
            assertTrue(adminMetricSources.bodyText().contains("sentinel_prometheus_query"));
            assertTrue(adminMetricSources.bodyText().contains("sentinel_dbhelper_sql"));
            assertTrue(adminMetricSources.bodyText().contains("sentinel_package_tool_call"));

            String groupedJobId="J0000000000000812";
            Map<String,Object> groupedScenario=Map.of("schemaVersion","att-load-observability/v1",
                    "collections",List.of(Map.of("id","grouped-binding","profile","grouped","targetIds",List.of("sut-1"))),
                    "collection",Map.of("mode","post-run","before","1m","after","1m","step","15s"),
                    "livePreview",Map.of("enabled",false));
            Map<String,Object> groupedSnapshot=runtime.sutMetrics.resolveScenario(groupedScenario,"alice",false);
            seedLoadJob(runtime,groupedJobId,"alice",groupedSnapshot);
            Captured groupedJobResponse=invokeAs(servlet,"GET","/jobs/"+groupedJobId+"/sut-metrics",Collections.emptyMap(),
                    new byte[0],"alice",Set.of(userRole));
            assertEquals(200,groupedJobResponse.status,groupedJobResponse.bodyText());
            JsonNode apiGroup=ServerRuntime.JSON.readTree(groupedJobResponse.body()).path("groups").path(0);
            assertEquals("private-prom-group",apiGroup.path("id").asText(),groupedJobResponse.bodyText());
            assertEquals("Private Prometheus group",apiGroup.path("title").asText());
            assertFalse(apiGroup.has("match"),apiGroup.toString());
            assertFalse(groupedJobResponse.bodyText().contains("sentinel_private_tenant"),groupedJobResponse.bodyText());

            Map<String,Object> wrongTenant=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                    "source","prom","tenant","tenant-b","targets",List.of(Map.of("id","sut-1","type","app")))),
                    "collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"));
            byte[] previewBody=ServerRuntime.JSON.writeValueAsBytes(Map.of("observability",wrongTenant));
            Captured tenantDenied=invokeAs(servlet,"POST","/admin/sut-metrics/profiles/preview",Collections.emptyMap(),previewBody,"admin",Set.of(adminRole));
            assertEquals(400,tenantDenied.status,new String(tenantDenied.body(),StandardCharsets.UTF_8));
            assertEquals("private, no-store",tenantDenied.headers.get("Cache-Control"));
            assertTrue(tenantDenied.bodyText().contains("tenant"),tenantDenied.bodyText());
        }
    }

    @Test void sutMetricsJobRouteRequiresJobOwnershipAndAuthorizedSourceReaders() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-job-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=writeSutMetricsConfig(sharedRuntimeConfig(),
                List.of(prometheusSource("prom","Prometheus","http://127.0.0.1:9090",List.of("alice"),List.of(),
                        List.of("tenant-a"),"tenant-a",Map.of(),Map.of())),List.of(),List.of(),
                List.of(baseProfile("base","Base profile",List.of(Map.of("id","requests","title","Requests","unit","count",
                        "kind","gauge","query","up")))),false);
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"sut-metrics-job-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,workerLibraries("sut-metrics-job-libs"),true)) {
            Map<String,Object> scenario=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                    "source","prom","tenant","tenant-a","targets",List.of(Map.of("id","sut-1","type","app")))),
                    "collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"),"livePreview",Map.of("enabled",true));
            Map<String,Object> snapshot=runtime.sutMetrics.resolveScenario(scenario,"alice",false);
            seedLoadJob(runtime,"J0000000000000711","alice",snapshot);
            seedLoadJob(runtime,"J0000000000000712","bob",snapshot);

            ApiServlet servlet=servlet(runtime);
            Captured otherOwner=invokeAs(servlet,"GET","/jobs/J0000000000000711/sut-metrics",Collections.emptyMap(),new byte[0],"bob",Set.of(runtime.config.authorization.userRole));
            Captured unauthorizedSource=invokeAs(servlet,"GET","/jobs/J0000000000000712/sut-metrics",Collections.emptyMap(),new byte[0],"bob",Set.of(runtime.config.authorization.userRole));
            Captured owner=invokeAs(servlet,"GET","/jobs/J0000000000000711/sut-metrics",Collections.emptyMap(),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));

            assertEquals(404,otherOwner.status,"Job ownership is checked before reading its SUT Metrics snapshot.");
            assertEquals("private, no-store",otherOwner.headers.get("Cache-Control"));
            assertEquals(404,unauthorizedSource.status,"Owning a Job does not grant access to a Data Source the caller cannot read.");
            assertEquals("private, no-store",unauthorizedSource.headers.get("Cache-Control"));
            assertEquals(200,owner.status,new String(owner.body(),StandardCharsets.UTF_8));
            assertEquals("PENDING",ServerRuntime.JSON.readTree(owner.body()).path("state").asText());
            assertEquals("private, no-store",owner.headers.get("Cache-Control"));
        }
    }

    @Test void sutMetricsRouteExposesCompletedBusinessPhaseAsProvisionalDuringPrometheusBuffer() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-metrics-provisional-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000811";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0);
             ServerRuntime runtime = runtime(packages, writeSutMetricsConfig(sharedRuntimeConfig(),
                     List.of(prometheusSource("prom","Prometheus",fixture.url(),List.of("alice"),List.of(),List.of(),null,Map.of(),Map.of())),
                     List.of(
                             Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","prom-metrics","name","Prometheus metrics",
                                     "connection",Map.of("kind","DATA_SOURCE","id","prom"),
                                     "fetch",Map.of("id","prom-fetch","mode","prometheus-range","query","up","parameters",Map.of(),"sampling","range","step","5m"),
                                     "mapping",Map.of("labels",Map.of("retain",List.of("instance")),"outputs",List.of(Map.of(
                                             "id","requests","title","Requests","unit","count","kind","gauge","metricName","up"))),
                                     "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576)),
                             Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","business-metrics","name","Business metrics",
                                     "connection",Map.of("kind","DBHELPER","id","orders"),
                                     "fetch",Map.of("id","business-fetch","mode","dbhelper-query",
                                             "call","#{db.orders.query(sql='select count(*) as COUNT_VALUE from orders')}",
                                             "parameters",Map.of(),"sampling","afterDrain"),
                                     "mapping",Map.of("labels",Map.of(),"outputs",List.of(Map.of(
                                             "id","count","title","Count","unit","count","kind","gauge","column","COUNT_VALUE"))),
                                     "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576))),
                     List.of(),List.of(
                             flatProfile("base","Base",List.of("prom-metrics"),List.of(Map.of("type","target","id","sut-1"))),
                             flatProfile("business","Business",List.of("business-metrics"),List.of())),true), "sut-metrics-provisional-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-metrics-provisional-libs"), true)) {
            Map<String, Object> scenario = new java.util.LinkedHashMap<>();
            scenario.put("schemaVersion", "att-load-observability/v1");
            scenario.put("collections", List.of(
                    Map.of("id", "prom-binding", "profile", "base", "targetIds", List.of("sut-1")),
                    Map.of("id", "business-binding", "profile", "business")));
            scenario.put("collection", Map.of("mode", "post-run", "before", "1m", "after", "1h", "step", "5m"));
            scenario.put("livePreview", Map.of("enabled", false));
            scenario.put("thresholds", List.of(Map.of("id", "count-max", "profile", "business", "metric", "count", "max", 10.0)));
            Map<String, Object> snapshot = runtime.sutMetrics.resolveScenario(scenario, "alice", false,
                    businessCallContext(runtime,packages.resolve("p")));
            runtime.sutMetrics.setBusinessCallExecutor((ignoredJob, ignoredSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) { return 12; }
                @Override public void close() { }
            });
            seedLoadJob(runtime, jobId, "alice", snapshot, "{}");
            Instant finishedAt = Instant.now().minusSeconds(2), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Map<String, Object> manifest;
            do {
                manifest = runtime.sutMetrics.collectionStatus(jobId);
                if (manifest.get("collectionProgress") instanceof Map<?, ?> progress
                        && "COMPLETE".equals(progress.get("business"))) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertEquals("PENDING", manifest.get("state"));
            assertEquals(0, fixture.requests.get(), "Prometheus must remain in its configured after-buffer.");

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/sut-metrics",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode body = ServerRuntime.JSON.readTree(response.body());
            assertEquals(200, response.status, response.bodyText());
            assertTrue(body.path("provisional").asBoolean(), body.toString());
            assertEquals("PENDING", body.path("state").asText());
            assertEquals("COMPLETE", body.path("collection").path("collectionProgress").path("business").asText());
            assertEquals("PENDING", body.path("collection").path("collectionProgress").path("prometheus").asText());
            assertTrue(body.path("collection").path("eligibleAt").isTextual(), body.toString());
            assertEquals(1, body.path("items").size(), "The API exposes the completed business value before Prometheus is eligible.");
            assertEquals("count", body.path("items").get(0).path("metricId").asText());
            assertEquals("PENDING", body.path("thresholdEvaluation").path("state").asText());
        }
    }

    @Test void loadResultRouteReturnsNativeAndSutCombinedPerformanceVerdict() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-result-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000731";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.1);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-result-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-result-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));

            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode acceptance = ServerRuntime.JSON.readTree(response.body()).path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", acceptance.path("nativePerformanceVerdict").asText());
            assertEquals("NOT_MET", acceptance.path("sutThresholds").path("status").asText());
            assertEquals("NOT_MET", acceptance.path("performanceVerdict").asText());
            JsonNode sutTargets = acceptance.path("sutThresholds").path("items").path(0).path("targets");
            assertEquals("NOT_MET", sutTargets.get(0).path("status").asText());
            assertEquals("sut-a", sutTargets.get(0).path("target").path("id").asText());
            assertEquals("NOT_EVALUATED", sutTargets.get(1).path("status").asText());
            assertEquals("sut-b", sutTargets.get(1).path("target").path("id").asText());
        }
    }

    @Test void corruptExternalEvaluationDoesNotBreakNativeLoadResultRoute() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-corrupt-result-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000735";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.0);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-corrupt-result-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-corrupt-result-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);
            Path evaluation = runtime.config.dataDir.resolve("jobs").resolve(jobId)
                    .resolve("sut-metrics/threshold-evaluation.json");
            Files.writeString(evaluation, "{truncated");

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode body = ServerRuntime.JSON.readTree(response.body());
            JsonNode acceptance = body.path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", body.path("result").path("summary").path("performance").path("verdict").asText());
            assertEquals("ERROR", acceptance.path("externalEvaluationStatus").asText());
            assertEquals("NOT_EVALUATED", acceptance.path("performanceVerdict").asText());
            assertEquals("THRESHOLD_EVALUATION_UNAVAILABLE",
                    acceptance.path("externalEvaluation").path("diagnostics").path(0).path("code").asText());
        }
    }

    @Test void corruptSutSnapshotDoesNotBreakNativeLoadResultRoute() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-corrupt-snapshot-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000736";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.0);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-corrupt-snapshot-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-corrupt-snapshot-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);
            Path savedSnapshot = runtime.config.dataDir.resolve("jobs").resolve(jobId)
                    .resolve("sut-metrics/snapshot.json");
            Files.writeString(savedSnapshot, "{truncated");

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode body = ServerRuntime.JSON.readTree(response.body());
            JsonNode acceptance = body.path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", body.path("result").path("summary").path("performance").path("verdict").asText());
            assertEquals("ERROR", acceptance.path("externalEvaluationStatus").asText());
            assertEquals("NOT_EVALUATED", acceptance.path("performanceVerdict").asText());
            assertEquals("SUT_SNAPSHOT_UNAVAILABLE",
                    acceptance.path("externalEvaluation").path("diagnostics").path(0).path("code").asText());
        }
    }

    @Test void corruptExternalEvaluationDoesNotBreakSutMetricsRoute() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-corrupt-sut-metrics-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000737";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.0);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-corrupt-sut-metrics-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-corrupt-sut-metrics-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);
            Path evaluation = runtime.config.dataDir.resolve("jobs").resolve(jobId)
                    .resolve("sut-metrics/threshold-evaluation.json");
            Files.writeString(evaluation, "{truncated");

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/sut-metrics",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode body = ServerRuntime.JSON.readTree(response.body());
            assertEquals(200, response.status, response.bodyText());
            assertEquals("READY", body.path("collection").path("state").asText());
            assertTrue(body.path("items").isArray() && body.path("items").size() > 0,
                    "Valid SUT collection evidence should remain available.");
            assertEquals("ERROR", body.path("thresholdEvaluation").path("state").asText());
            assertEquals("THRESHOLD_EVALUATION_UNAVAILABLE",
                    body.path("thresholdEvaluation").path("diagnostics").path(0).path("code").asText());
            assertEquals("NOT_EVALUATED", body.path("performanceAcceptance").path("performanceVerdict").asText());
        }
    }

    @Test void loadResultRouteKeepsAProvenViolationWhenAnotherTargetHasNoEvidence() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-missing-result-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000732";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.1, "sut-b");
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-missing-result-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-missing-result-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode acceptance = ServerRuntime.JSON.readTree(response.body()).path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", acceptance.path("nativePerformanceVerdict").asText());
            assertEquals("NOT_MET", acceptance.path("sutThresholds").path("status").asText());
            assertEquals("NOT_MET", acceptance.path("performanceVerdict").asText());
            JsonNode targets = acceptance.path("sutThresholds").path("items").path(0).path("targets");
            assertTargetStatus(targets, "sut-a", "NOT_MET");
            assertTargetStatus(targets, "sut-b", "NOT_EVALUATED");
        }
    }

    @Test void loadResultRouteDoesNotLetAnOptionalSutFailureOverrideNativeMet() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-optional-result-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000733";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.1);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-optional-result-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-optional-result-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, false);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode acceptance = ServerRuntime.JSON.readTree(response.body()).path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", acceptance.path("nativePerformanceVerdict").asText());
            assertEquals("MET", acceptance.path("sutThresholds").path("status").asText());
            assertEquals("MET", acceptance.path("performanceVerdict").asText());
            JsonNode rule = acceptance.path("sutThresholds").path("items").path(0);
            assertFalse(rule.path("required").asBoolean());
            assertEquals("NOT_MET", rule.path("status").asText());
        }
    }

    @Test void loadResultRouteResumesPendingEvaluationAfterRestart() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-restart-result-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig = sharedRuntimeConfig();
        Path libs = workerLibraries("sut-threshold-restart-result-libs");
        String dataName = "sut-threshold-restart-result-data";
        String jobId = "J0000000000000734";
        Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.0)) {
            writeTwoTargetRuntimeConfig(runtimeConfig, fixture);
            ServerRuntime first = runtime(packages, runtimeConfig, dataName, DEFAULT_INSPECTION_TIMEOUT_MS, null, libs, true);
            CountDownLatch releaseEvaluators = null;
            try {
                Map<String, Object> snapshot = twoTargetThresholdSnapshot(first, fixture, true);
                seedLoadJob(first, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
                releaseEvaluators = blockThresholdEvaluators(first);
                first.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                        List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                                "endAt", finishedAt.toString(), "measured", true)));
                awaitCollectionState(first, jobId, "READY");
                awaitEvaluationState(first, jobId, "EVALUATING");

                Captured pendingResponse = invokeAs(servlet(first), "GET", "/jobs/" + jobId + "/result",
                        Collections.emptyMap(), new byte[0], "alice", Set.of(first.config.authorization.userRole));
                JsonNode pendingAcceptance = ServerRuntime.JSON.readTree(pendingResponse.body()).path("performanceAcceptance");
                assertEquals(200, pendingResponse.status, pendingResponse.bodyText());
                assertEquals("EVALUATING", pendingAcceptance.path("externalEvaluationStatus").asText());
                assertEquals("PENDING", pendingAcceptance.path("performanceVerdict").asText());
            } finally {
                first.close();
                if (releaseEvaluators != null) releaseEvaluators.countDown();
            }

            try (ServerRuntime recovered = runtime(packages, runtimeConfig, dataName, DEFAULT_INSPECTION_TIMEOUT_MS, null, libs, true)) {
                assertEquals("MET", awaitThresholdEvaluation(recovered, jobId).get("state"));
                Captured finalResponse = invokeAs(servlet(recovered), "GET", "/jobs/" + jobId + "/result",
                        Collections.emptyMap(), new byte[0], "alice", Set.of(recovered.config.authorization.userRole));
                JsonNode finalAcceptance = ServerRuntime.JSON.readTree(finalResponse.body()).path("performanceAcceptance");
                assertEquals(200, finalResponse.status, finalResponse.bodyText());
                assertEquals("MET", finalAcceptance.path("externalEvaluationStatus").asText());
                assertEquals("MET", finalAcceptance.path("performanceVerdict").asText());
            }
        }
    }

    @Test void sutMetricsLiveRouteUsesPinnedTenantAndReturnsBoundedTimeoutAndResponseFailures() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-live-api-packages"));
        createPackage(packages.resolve("p"));
        Map<String,Map<String,Object>> cases=new LinkedHashMap<>();
        cases.put("ready",Map.of("connectTimeoutMs",30_000,"requestTimeoutMs",30_000));
        cases.put("timeout",Map.of("connectTimeoutMs",30_000,"requestTimeoutMs",5_000));
        cases.put("response-limit",Map.of("connectTimeoutMs",30_000,"requestTimeoutMs",30_000,"maxResponseBytes",1024));
        for(Map.Entry<String,Map<String,Object>> entry:cases.entrySet()) {
            String name=entry.getKey();boolean delay="timeout".equals(name);int responseBytes="response-limit".equals(name)?2048:0;
            try(ApiPrometheusFixture fixture=new ApiPrometheusFixture(delay?7_000:0,responseBytes);
                ServerRuntime runtime=runtime(packages,writeSutMetricsConfig(sharedRuntimeConfig("sut-metrics-live-api-"+name+"-runtime"),
                        List.of(prometheusSource("prom","Prometheus",fixture.url(),List.of(),List.of("USER"),List.of("tenant-a"),"tenant-a",
                                entry.getValue(),Map.of())),List.of(),List.of(),List.of(baseProfile("base","Base profile",
                                List.of(Map.of("id","requests","title","Requests","unit","count","kind","gauge","query","up")))),false),
                        "sut-metrics-live-api-"+name,DEFAULT_INSPECTION_TIMEOUT_MS,
                        null,workerLibraries("sut-metrics-live-api-"+name+"-libs"),true)) {
                Map<String,Object> scenario=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                        "source","prom","tenant","tenant-a","targets",List.of(Map.of("id","sut-1","type","app")))),
                        "collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"),"livePreview",Map.of("enabled",true));
                Map<String,Object> snapshot=runtime.sutMetrics.resolveScenario(scenario,"alice",false);
                seedRunningLoadJob(runtime,"J0000000000000721","alice",snapshot);

                Captured result=invokeAs(servlet(runtime),"GET","/jobs/J0000000000000721/sut-metrics/live",
                        Map.of("range","15m","step","30s"),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));
                JsonNode response=ServerRuntime.JSON.readTree(result.body());
                assertEquals(200,result.status,new String(result.body(),StandardCharsets.UTF_8));
                assertEquals("ready".equals(name)?"READY":"UNAVAILABLE",response.path("state").asText(),response.toString());
                if(!"timeout".equals(name)||fixture.requests.get()>0) {
                    assertEquals(1,fixture.requests.get(),name+": the live query must reach its isolated Prometheus fixture; "+response);
                    assertEquals("tenant-a",fixture.tenant.get(),name);
                    assertEquals("up",fixture.query.get(),name);
                }
                assertEquals("private, no-store",result.headers.get("Cache-Control"));
                assertFalse(result.bodyText().contains(fixture.url()),result.bodyText());
                if(!"ready".equals(name)) {
                    JsonNode item=response.path("items").path(0);
                    assertEquals("UNAVAILABLE",item.path("queryStatus").asText(),item.toString());
                    assertTrue(item.path("error").asText().toLowerCase().contains("timeout")
                            || item.path("error").asText().toLowerCase().contains("timed out")
                            || item.path("error").asText().toLowerCase().contains("limit"),name+" (fixture requests="+fixture.requests.get()+"): "+item);
                }
            }
        }
    }

    @Test void sutMetricsLiveRouteCoalescesConcurrentViewersAndForwardsBearerSecretWithoutLeakingIt() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-live-auth-packages"));
        createPackage(packages.resolve("p"));
        try(ApiPrometheusFixture fixture=new ApiPrometheusFixture(800,0);
            ServerRuntime runtime=runtime(packages,writeSutMetricsConfig(sharedRuntimeConfig(),
                    List.of(prometheusSource("prom","Authenticated Prometheus",fixture.url(),List.of("alice"),List.of("USER"),
                            List.of("tenant-a"),"tenant-a",Map.of(),Map.of("mode","BEARER","secretRef","PROM_TOKEN"))),
                    List.of(),List.of(),List.of(baseProfile("base","Base profile",
                            List.of(Map.of("id","requests","title","Requests","unit","count","kind","gauge","query","up")))),false),
                    "sut-metrics-live-auth-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                    null,workerLibraries("sut-metrics-live-auth-libs"),true,ref->"PROM_TOKEN".equals(ref)?"api-test-token":"wrong-ref")) {
            Map<String,Object> scenario=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                    "source","prom","tenant","tenant-a","targets",List.of(Map.of("id","sut-1","type","app")))),
                    "collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"),"livePreview",Map.of("enabled",true));
            Map<String,Object> snapshot=runtime.sutMetrics.resolveScenario(scenario,"alice",false);
            seedRunningLoadJob(runtime,"J0000000000000722","alice",snapshot);
            ApiServlet api=servlet(runtime);

            while(Instant.now().getEpochSecond()%30<8||Instant.now().getEpochSecond()%30>21)Thread.sleep(20);
            ExecutorService viewers=Executors.newFixedThreadPool(2);
            CountDownLatch start=new CountDownLatch(1);
            try {
                Future<Captured> first=viewers.submit(()->{start.await();return invokeAs(api,"GET","/jobs/J0000000000000722/sut-metrics/live",
                        Map.of("range","15m","step","30s"),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));});
                Future<Captured> second=viewers.submit(()->{start.await();return invokeAs(api,"GET","/jobs/J0000000000000722/sut-metrics/live",
                        Map.of("range","15m","step","30s"),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));});
                start.countDown();
                Captured firstResponse=first.get(5,TimeUnit.SECONDS),secondResponse=second.get(5,TimeUnit.SECONDS);
                assertEquals(200,firstResponse.status,firstResponse.bodyText());assertEquals(200,secondResponse.status,secondResponse.bodyText());
                assertEquals("READY",ServerRuntime.JSON.readTree(firstResponse.body()).path("state").asText());
                assertEquals("READY",ServerRuntime.JSON.readTree(secondResponse.body()).path("state").asText());
                assertEquals(1,fixture.requests.get(),"Concurrent viewers through the Server route share one Prometheus request.");
                assertEquals("Bearer api-test-token",fixture.authorization.get());
                assertEquals("tenant-a",fixture.tenant.get());
                assertFalse(firstResponse.bodyText().contains("api-test-token"));
                assertFalse(secondResponse.bodyText().contains("api-test-token"));
                assertFalse(runtime.sutMetrics.listSources("alice",false).toString().contains("api-test-token"));
            } finally {viewers.shutdownNow();viewers.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }

    @Test void sutMetricsLiveRouteReturns503WhenBoundedPreviewCapacityIsSaturated() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-live-capacity-packages"));
        createPackage(packages.resolve("p"));
        try(ApiPrometheusFixture fixture=new ApiPrometheusFixture(10000,0);
            ServerRuntime runtime=runtime(packages,writeSutMetricsConfig(sharedRuntimeConfig(),
                    List.of(prometheusSource("prom","Prometheus",fixture.url(),List.of("alice"),List.of("USER"),List.of(),null,
                            Map.of("requestTimeoutMs",60000),Map.of())),List.of(),List.of(),
                    List.of(baseProfile("base","Base profile",List.of(Map.of("id","requests","title","Requests","unit","count",
                            "kind","gauge","query","up")))),false),
                    "sut-metrics-live-capacity-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                    null,workerLibraries("sut-metrics-live-capacity-libs"),true,ref->null)) {
            List<Map<String,Object>> targets=new java.util.ArrayList<>();
            for(int index=0;index<21;index++)targets.add(Map.of("id","sut-"+index,"type","app"));
            Map<String,Object> scenario=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                    "source","prom","targets",targets)),"collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"),
                    "livePreview",Map.of("enabled",true));
            Map<String,Object> snapshot=runtime.sutMetrics.resolveScenario(scenario,"alice",false);
            seedRunningLoadJob(runtime,"J0000000000000723","alice",snapshot);
            ApiServlet api=servlet(runtime);
            ExecutorService viewers=Executors.newFixedThreadPool(21);
            ExecutorCompletionService<Captured> completed=new ExecutorCompletionService<>(viewers);
            CountDownLatch start=new CountDownLatch(1);
            try {
                for(int index=0;index<4;index++) {
                    String target="sut-"+index;
                    completed.submit(()->{start.await();return invokeAs(api,"GET","/jobs/J0000000000000723/sut-metrics/live",
                            Map.of("range","15m","step","30s","targetId",target),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));});
                }
                start.countDown();
                long sourceDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(fixture.requests.get()<4&&System.nanoTime()<sourceDeadline)Thread.sleep(10);
                assertEquals(4,fixture.requests.get(),"The four active preview workers must be held at the source before filling the queue.");
                for(int index=4;index<21;index++) {
                    String target="sut-"+index;
                    completed.submit(()->invokeAs(api,"GET","/jobs/J0000000000000723/sut-metrics/live",
                            Map.of("range","15m","step","30s","targetId",target),new byte[0],"alice",Set.of(runtime.config.authorization.userRole)));
                }
                Future<Captured> rejected=completed.poll(5,TimeUnit.SECONDS);
                Captured saturated=rejected==null?null:rejected.get(5,TimeUnit.SECONDS);
                assertNotNull(saturated,"A request should be rejected as soon as all bounded preview slots are occupied.");
                JsonNode diagnostic=ServerRuntime.JSON.readTree(saturated.body()).path("diagnostics").path(0);
                assertEquals(503,saturated.status,saturated.bodyText());
                assertEquals("ATT-SUT-METRICS-CAPACITY",diagnostic.path("code").asText());
                assertEquals("private, no-store",saturated.headers.get("Cache-Control"));
                assertEquals(4,fixture.requests.get(),"Only the fixed-size live-preview worker pool may reach Prometheus while saturated.");
            } finally {viewers.shutdownNow();viewers.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }

    private void seedLoadJob(ServerRuntime runtime,String id,String owner,Map<String,Object> snapshot) throws Exception {
        seedLoadJob(runtime, id, owner, snapshot, "{}");
    }

    private void seedLoadJob(ServerRuntime runtime,String id,String owner,Map<String,Object> snapshot,String resultJson) throws Exception {
        Path jobDir=runtime.config.dataDir.resolve("jobs").resolve(id);
        Files.createDirectories(jobDir.resolve("output"));
        Job job=new Job(id,"load","p",owner,new WorkerRequest(),new JobEvents(jobDir.resolve("events.jsonl"),100));
        job.status="PASS";job.exitCode=0;job.finishedAt=Instant.now();
        runtime.store.insert(job,resultJson);
        job.resultJson=resultJson;
        runtime.store.update(job);
        runtime.sutMetrics.writeSnapshot(id,snapshot);
    }

    private Map<String, Object> twoTargetThresholdSnapshot(ServerRuntime runtime, ApiPrometheusFixture fixture, boolean required) throws Exception {
        Map<String, Object> scenario = Map.of("schemaVersion", "att-load-observability/v1",
                "collections", List.of(Map.of("id", "primary", "profile", "base", "source", "prom",
                        "targets", List.of(
                                Map.of("id", "sut-a", "type", "app", "parameters", Map.of("instance", "sut-a")),
                                Map.of("id", "sut-b", "type", "app", "parameters", Map.of("instance", "sut-b"))))),
                "collection", Map.of("mode", "post-run", "step", "15s", "before", "1m", "after", "1m"),
                "thresholds", List.of(Map.of("id", "cpu-max-90", "profile", "primary", "metric", "cpu", "max", 90.0,
                        "unit", "percent", "minimumCoverage", 0.95, "required", required)),
                "livePreview", Map.of("enabled", true));
        return runtime.sutMetrics.resolveScenario(scenario, "alice", false);
    }

    private String nativeLoadResult(String jobId, String verdict) throws Exception {
        return ServerRuntime.JSON.writeValueAsString(Map.of("executionId", jobId, "status", "PASS", "exitCode", 0,
                "summary", Map.of("executionStatus", "PASS", "performanceVerdict", verdict,
                        "thresholds", Map.of("total", 1, "performanceVerdict", verdict, "results", List.of()))));
    }

    private void assertTargetStatus(JsonNode targets, String targetId, String expectedStatus) {
        for (JsonNode target : targets) {
            if (targetId.equals(target.path("target").path("id").asText())) {
                assertEquals(expectedStatus, target.path("status").asText(), target.toString());
                return;
            }
        }
        fail("Missing SUT threshold result for target " + targetId + ": " + targets);
    }

    private CountDownLatch blockThresholdEvaluators(ServerRuntime runtime) throws Exception {
        Field field = SutMetricsService.class.getDeclaredField("evaluators");
        field.setAccessible(true);
        ExecutorService evaluators = (ExecutorService) field.get(runtime.sutMetrics);
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int index = 0; index < 2; index++) evaluators.execute(() -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        if (!started.await(5, TimeUnit.SECONDS)) {
            release.countDown();
            fail("Threshold evaluation workers did not start in time.");
        }
        return release;
    }

    private Map<String, Object> awaitCollectionState(ServerRuntime runtime, String jobId, String expectedState) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            Map<String, Object> manifest = runtime.sutMetrics.collectionStatus(jobId);
            if (expectedState.equals(manifest.get("state"))) return manifest;
            if (Set.of("FAILED", "UNAVAILABLE").contains(manifest.get("state")))
                fail("Collection ended before the expected state: " + manifest);
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("SUT Metrics collection did not reach " + expectedState + " before the test deadline.");
        return Map.of();
    }

    private Map<String, Object> awaitEvaluationState(ServerRuntime runtime, String jobId, String expectedState) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            Map<String, Object> evaluation = runtime.sutMetrics.thresholdEvaluationStatus(jobId);
            if (expectedState.equals(evaluation.get("state"))) return evaluation;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("SUT threshold evaluation did not reach " + expectedState + " before the test deadline.");
        return Map.of();
    }

    private Map<String,Object> awaitThresholdEvaluation(ServerRuntime runtime,String jobId) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        do {
            Map<String,Object> evaluation=runtime.sutMetrics.thresholdEvaluationStatus(jobId);
            if(Set.of("MET","NOT_MET","NOT_EVALUATED","ERROR").contains(evaluation.get("state")))return evaluation;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        fail("SUT threshold evaluation did not finish before the test deadline.");
        return Map.of();
    }

    private void seedRunningLoadJob(ServerRuntime runtime,String id,String owner,Map<String,Object> snapshot) throws Exception {
        Path jobDir=runtime.config.dataDir.resolve("jobs").resolve(id);
        Files.createDirectories(jobDir.resolve("output"));
        Job job=new Job(id,"load","p",owner,new WorkerRequest(),new JobEvents(jobDir.resolve("events.jsonl"),100));
        job.status="RUNNING";job.startedAt=Instant.now();job.loadProgress=Map.of("capturedAt",Instant.now().toString(),"elapsedMs",300_000L);
        runtime.store.insert(job,"{}");runtime.jobs.put(id,job);runtime.sutMetrics.writeSnapshot(id,snapshot);
    }

    @Test void inspectionDeadlineFlowsThroughTheServletAs504WithAContractDiagnostic() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("timeout-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("timeout-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"timeout-data",100,null,libs)) {
            Object worker=inspectionWorkers(runtime).get(0);
            Field lockField=worker.getClass().getDeclaredField("lock");lockField.setAccessible(true);
            ReentrantLock lock=(ReentrantLock)lockField.get(worker);
            lock.lock();
            Captured timeout;
            try {timeout=invoke(servlet(runtime),"GET","/packages/p/resources",Collections.singletonMap("type","template"),new byte[0]);}
            finally {lock.unlock();}
            JsonNode diagnostic=ServerRuntime.JSON.readTree(timeout.body()).path("diagnostics").path(0);
            assertEquals(504,timeout.status);
            assertEquals("ATT-SERVER-INSPECTION-TIMEOUT",diagnostic.path("code").asText());
            assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
            assertEquals("server.inspection.timeoutMs",diagnostic.path("limitName").asText());
        }
    }

    @Test void privilegedAuthoringSourceCapacityIs503ForPackageAndCentralRoutes() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("authoring-capacity-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"authoring-capacity-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("authoring-capacity-libs"))) {
            addAuthoringUsers(runtime);
            ApiServlet servlet=servlet(runtime);Semaphore inspections=semaphore(runtime,"inspectionTaskSlots");inspections.acquire();
            try {
                Captured packageSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                        Collections.singletonMap("type","manifest"),new byte[0]);
                Captured centralSource=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                        Collections.singletonMap("path","config/config.yaml"),new byte[0]);
                assertSafeSourceFailure(packageSource,503,"ATT-SERVER-INSPECTION-CAPACITY");
                assertSafeSourceFailure(centralSource,503,"ATT-SERVER-INSPECTION-CAPACITY");
            } finally {inspections.release();}
            Captured protectedPath=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/att-server.yaml"),new byte[0]);
            assertSafeSourceFailure(protectedPath,404,"ATT-SERVER-NOT-FOUND");
        }
    }

    @Test void authoringSourceReadsReturnRequiredChangeSetRevisionPreconditions() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("authoring-revisions-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"authoring-revisions-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("authoring-revisions-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            Captured packageSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Collections.singletonMap("type","manifest"),new byte[0]);
            JsonNode packageBody=ServerRuntime.JSON.readTree(packageSource.body());
            assertEquals(200,packageSource.status,new String(packageSource.body(),StandardCharsets.UTF_8));
            assertTrue(packageBody.path("baseSourceRevision").asText().matches("[a-f0-9]{64}"),packageBody.toString());
            assertTrue(packageBody.path("registryRevision").asText().matches("[a-f0-9]{64}"),packageBody.toString());

            Captured centralSource=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/config.yaml"),new byte[0]);
            JsonNode centralBody=ServerRuntime.JSON.readTree(centralSource.body());
            assertEquals(200,centralSource.status,new String(centralSource.body(),StandardCharsets.UTF_8));
            assertTrue(centralBody.path("baseSourceRevision").asText().matches("[a-f0-9]{64}"),centralBody.toString());
            assertTrue(centralBody.path("registryRevision").isMissingNode(),centralBody.toString());

            String baseRevision=packageBody.path("baseSourceRevision").asText();
            String registryRevision=packageBody.path("registryRevision").asText();
            Map<String,Object> operation=Map.of("operation","create","path","templates/stale-editor.yaml","content","id: stale\n");
            Captured missingBase=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("registryRevision",registryRevision,"operations",List.of(operation))));
            assertEquals(400,missingBase.status,new String(missingBase.body(),StandardCharsets.UTF_8));
            Captured missingRegistry=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("baseSourceRevision",baseRevision,"operations",List.of(operation))));
            assertEquals(400,missingRegistry.status,new String(missingRegistry.body(),StandardCharsets.UTF_8));

            Field transactionField=ServerRuntime.class.getDeclaredField("sourceFileTransaction");transactionField.setAccessible(true);
            SourceFileTransaction transaction=(SourceFileTransaction)transactionField.get(runtime);
            Path configuredPackage=runtime.packageRegistry.snapshot().packages.get("alice").toRealPath();
            Path active=transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,"alice",configuredPackage);
            byte[] external= "id: external\n".getBytes(StandardCharsets.UTF_8);
            transaction.commit(ChangeSetPublisher.PACKAGE_SOURCE,"alice",active,"alice",baseRevision,"c".repeat(64),
                    Map.of("templates/external.yaml",new SourceFileTransaction.Write("templates/external.yaml",external,null)),()->{});
            Captured stale=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("baseSourceRevision",baseRevision,
                            "registryRevision",registryRevision,"operations",List.of(operation))));
            assertEquals(409,stale.status,new String(stale.body(),StandardCharsets.UTF_8));
            stopInspectionProcesses(runtime);
        }
    }

    @Test void nativeTestCasePreviewIsOwnerOnlyReadOnlyAndIncludesExcelRevisionAndDiagnostics() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("native-preview-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path workbook=writeNativePreviewPackage(ownerPackage,"case-1","17");
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"native-preview-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("native-preview-libs"),"container")) {
            ApiServlet servlet=servlet(runtime);
            byte[] sourceBefore=Files.readAllBytes(ownerPackage.resolve("testcase/native/testcase.yaml"));
            byte[] workbookBefore=Files.readAllBytes(workbook);
            int jobsBefore=runtime.jobs.size(),draftsBefore=runDraftCount(runtime);

            Captured editableSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Map.of("type","native-testcase","path","testcase/native/testcase.yaml"),new byte[0]);
            JsonNode sourceBody=ServerRuntime.JSON.readTree(editableSource.body());
            assertEquals(200,editableSource.status,new String(editableSource.body(),StandardCharsets.UTF_8));
            assertEquals("testcase/native/testcase.yaml",sourceBody.path("logicalPath").asText());
            assertEquals(new String(sourceBefore,StandardCharsets.UTF_8),sourceBody.path("text").asText(),
                    "A supplementary Unicode comment before dataColumns must not shift source redaction offsets");
            assertTrue(sourceBody.path("text").asText().contains("password=Password"),sourceBody.toString());
            assertTrue(sourceBody.path("baseSourceRevision").asText().matches("[a-f0-9]{64}"));
            assertTrue(sourceBody.path("registryRevision").asText().matches("[a-f0-9]{64}"));

            Captured response=invokeAs(servlet,"alice","POST","/packages/alice/native-testcase-preview",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("path","testcase/native/testcase.yaml","source",nativePreviewDefinition())));
            JsonNode body=ServerRuntime.JSON.readTree(response.body());JsonNode item=body.path("cases").path(0);
            assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
            assertTrue(body.path("valid").asBoolean(),body.toString());
            assertTrue(body.path("packageSourceRevision").asText().matches("[a-f0-9]{64}"),body.toString());
            assertTrue(body.path("registryRevision").asText().matches("[a-f0-9]{64}"),body.toString());
            assertEquals(1,body.path("resolvedCount").asInt());
            assertEquals("native.case.case-1",item.path("qualifiedCaseId").asText());
            assertEquals("Cases",item.path("provenance").path("sheet").asText());
            assertEquals(2,item.path("provenance").path("row").asInt());
            assertEquals(sha256(workbookBefore),body.path("workbookRevision").asText());
            assertEquals("[REDACTED_SECRET]",item.path("inputs").path("password").asText());
            assertTrue(item.path("redacted").asBoolean());
            assertFalse(new String(response.body(),StandardCharsets.UTF_8).contains("native-preview-secret-canary"));
            assertArrayEquals(sourceBefore,Files.readAllBytes(ownerPackage.resolve("testcase/native/testcase.yaml")));
            assertArrayEquals(workbookBefore,Files.readAllBytes(workbook),"The source workbook must not be modified by preview");
            assertEquals(jobsBefore,runtime.jobs.size(),"Read-only preview must not create a Job");
            assertEquals(draftsBefore,runDraftCount(runtime),"Read-only preview must not create a RunDraft");

            Path badWorkbook=ownerPackage.resolve("testcase/native/bad-cases.xlsx");
            writeNativePreviewWorkbook(badWorkbook,"not-used","17",true);
            String badDefinition=nativePreviewDefinition().replace("testcase/native/cases.xlsx","testcase/native/bad-cases.xlsx");
            Captured invalid=invokeAs(servlet,"alice","POST","/packages/alice/native-testcase-preview",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("path","testcase/native/testcase.yaml","source",badDefinition)));
            JsonNode invalidBody=ServerRuntime.JSON.readTree(invalid.body());JsonNode diagnostic=invalidBody.path("diagnostics").path(0);
            assertEquals(200,invalid.status,new String(invalid.body(),StandardCharsets.UTF_8));
            assertFalse(invalidBody.path("valid").asBoolean(),invalidBody.toString());
            assertEquals("Cases",diagnostic.path("sheet").asText(),diagnostic.toString());
            assertEquals(2,diagnostic.path("row").asInt(),diagnostic.toString());
            assertEquals(1,diagnostic.path("column").asInt(),diagnostic.toString());
            assertFalse(new String(invalid.body(),StandardCharsets.UTF_8).contains("native-preview-secret-canary"));

            Captured denied=invokeAsAdmin(servlet,"admin","POST","/packages/alice/native-testcase-preview",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("path","testcase/native/testcase.yaml","source",nativePreviewDefinition())));
            assertEquals(403,denied.status,new String(denied.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-PACKAGE-SOURCE-DENIED",ServerRuntime.JSON.readTree(denied.body()).path("error").path("code").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void nativeTestCasePreviewRejectsPackageMutationDuringWorkerResolution() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("native-preview-stale-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path workbook=writeNativePreviewPackage(ownerPackage,"case-1","17");
        Path runtimeConfig=sharedRuntimeConfig();
        java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
        ServerRuntime.WorkerProcessLauncher mutatingLauncher=builder->{
            try{return mutateWorkbookAfterPreviewRequest(builder,workbook,changed);}
            catch(Exception failure){throw new IllegalStateException("Unable to launch mutating preview worker",failure);}
        };
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"native-preview-stale-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,mutatingLauncher,workerLibraries("native-preview-stale-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            byte[] before=Files.readAllBytes(workbook);
            Captured stale=invokeAs(servlet,"alice","POST","/packages/alice/native-testcase-preview",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("path","testcase/native/testcase.yaml","source",nativePreviewDefinition())));
            assertTrue(changed.get(),"The test mutator must change the workbook after Server revision capture and before Worker resolution");
            assertEquals(409,stale.status,new String(stale.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-PACKAGE-SOURCE-CONFLICT",ServerRuntime.JSON.readTree(stale.body()).path("error").path("code").asText());
            assertFalse(java.util.Arrays.equals(before,Files.readAllBytes(workbook)));
            assertEquals(0,runtime.jobs.size());assertEquals(0,runDraftCount(runtime));
            stopInspectionProcesses(runtime);
        }
    }

    @Test void realChangeSetServletResponsesMatchVersionedSchemaAndNoStoreContract() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("change-set-schema-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("change-set-schema-libs");
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"change-set-schema-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            addAuthoringUsers(runtime);
            ApiServlet servlet=servlet(runtime);
            com.fasterxml.jackson.databind.JsonNode schemaNode;
            try(java.io.InputStream stream=getClass().getResourceAsStream("/schemas/att-source-management-v1.schema.json")) {
                assertNotNull(stream);
                schemaNode=ServerRuntime.JSON.readTree(stream);
            }
            com.networknt.schema.JsonSchema schema=com.networknt.schema.JsonSchemaFactory
                    .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schemaNode);

            Captured packageSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Collections.singletonMap("type","manifest"),new byte[0]);
            JsonNode packageRevisions=ServerRuntime.JSON.readTree(packageSource.body());
            assertEquals(200,packageSource.status);
            Map<String,Object> packageOperation=Map.of("operation","create","path","files/wire.txt","content","safe static file\n");
            Map<String,Object> packageStage=Map.of("baseSourceRevision",packageRevisions.path("baseSourceRevision").asText(),
                    "registryRevision",packageRevisions.path("registryRevision").asText(),"operations",List.of(packageOperation));
            Captured staged=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(packageStage));
            JsonNode stageBody=assertChangeSetSchema(schema,staged,201);
            assertEquals("draft",stageBody.path("state").asText());
            assertTrue(stageBody.path("revisions").path("packageRegistration").isObject());
            String changeId=stageBody.path("changeSetId").asText();

            for(String action:List.of("validate","review")) {
                Captured response=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+changeId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
                assertChangeSetSchema(schema,response,200);
            }
            Captured published=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+changeId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertChangeSetSchema(schema,published,200);

            Captured currentPackage=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Collections.singletonMap("type","manifest"),new byte[0]);
            JsonNode currentPackageRevisions=ServerRuntime.JSON.readTree(currentPackage.body());
            Captured discardStage=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("baseSourceRevision",currentPackageRevisions.path("baseSourceRevision").asText(),
                            "registryRevision",currentPackageRevisions.path("registryRevision").asText(),"operations",
                            List.of(Map.of("operation","create","path","files/discard.txt","content","discard me\n")))));
            JsonNode discardDraft=assertChangeSetSchema(schema,discardStage,201);
            Captured discarded=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"
                            +discardDraft.path("changeSetId").asText()+"/discard",Collections.emptyMap(),
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertChangeSetSchema(schema,discarded,200);

            Captured centralSource=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/config.yaml"),new byte[0]);
            JsonNode centralRevisions=ServerRuntime.JSON.readTree(centralSource.body());
            Captured centralStage=invokeAs(servlet,"admin","POST","/configuration/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("baseSourceRevision",centralRevisions.path("baseSourceRevision").asText(),
                            "operations",List.of(Map.of("operation","replace","path","config/config.yaml",
                                    "content",Files.readString(runtimeConfig))))));
            JsonNode centralDraft=assertChangeSetSchema(schema,centralStage,201);
            assertTrue(centralDraft.path("revisions").path("packageRegistration").isNull(),
                    "central Config responses preserve absent Package registration identities as null");
            Captured centralDiscard=invokeAs(servlet,"admin","POST","/configuration/change-sets/"
                            +centralDraft.path("changeSetId").asText()+"/discard",Collections.emptyMap(),
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertChangeSetSchema(schema,centralDiscard,200);
        }
    }

    @Test void packageZipImportStagesReviewsAndPublishesPackageLocalChanges() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("package-zip-import-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"package-zip-import-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("package-zip-import-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            byte[] archive=packageZip("files/z-imported.txt","last\n","files/a-imported.txt","first\n");
            byte[] runtimeConfigBefore=Files.readAllBytes(runtimeConfig);

            Captured wrongType=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),archive);
            assertEquals(415,wrongType.status,new String(wrongType.body(),StandardCharsets.UTF_8));

            Captured malformed=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),new byte[]{1,2,3},"application/zip");
            assertEquals(400,malformed.status,new String(malformed.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-PACKAGE-ZIP-INVALID",ServerRuntime.JSON.readTree(malformed.body())
                    .path("error").path("code").asText());

            Captured unauthorized=invokeAsContentType(servlet,"alice","POST","/packages/bob/change-sets/import",
                    Collections.emptyMap(),new byte[]{1,2,3},"application/zip");
            assertEquals(403,unauthorized.status,new String(unauthorized.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-PACKAGE-SOURCE-DENIED",ServerRuntime.JSON.readTree(unauthorized.body())
                    .path("error").path("code").asText(),"Package ownership is checked before parsing an archive");

            byte[] traversal=packageZip("files/partial-import.txt","must-not-publish\n","../outside.yaml","escaped\n");
            Captured rejectedTraversal=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),traversal,"application/zip");
            assertEquals(400,rejectedTraversal.status,new String(rejectedTraversal.body(),StandardCharsets.UTF_8));
            assertFalse(Files.exists(ownerPackage.getParent().resolve("outside.yaml")),"A rejected traversal ZIP writes no outside file");
            assertFalse(Files.exists(ownerPackage.resolve("files/partial-import.txt")),"A rejected ZIP stages no valid prefix entry");

            Captured imported=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),archive,"application/zip");
            assertEquals(201,imported.status,new String(imported.body(),StandardCharsets.UTF_8));
            assertEquals("private, no-store",imported.headers.get("Cache-Control"));
            JsonNode draft=ServerRuntime.JSON.readTree(imported.body());
            assertEquals("draft",draft.path("state").asText());
            assertEquals(2,draft.path("changedFiles").size());
            assertEquals("create",draft.path("changedFiles").path(0).path("operation").asText());
            assertEquals("files/a-imported.txt",draft.path("changedFiles").path(0).path("path").asText(),
                    "ZIP entries should produce a deterministic path-sorted Change Set diff");
            assertEquals("files/z-imported.txt",draft.path("changedFiles").path(1).path("path").asText());
            String id=draft.path("changeSetId").asText();
            assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig),
                    "Package ZIP import must not modify the globally owned Runtime Config");
            assertFalse(Files.exists(ownerPackage.resolve("files/a-imported.txt"))
                            ||Files.exists(ownerPackage.resolve("files/z-imported.txt")),
                    "Import creates an ordinary Change Set and must leave published Package files untouched");

            for(String action:List.of("validate","review")) {
                Captured response=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+id+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
                assertFalse(Files.exists(ownerPackage.resolve("files/a-imported.txt"))
                                ||Files.exists(ownerPackage.resolve("files/z-imported.txt")),
                        "Validation and review must not publish imported files");
                assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig),
                        "Package Change Set gates must not modify the global Runtime Config");
            }
            Captured published=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+id+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200,published.status,new String(published.body(),StandardCharsets.UTF_8));
            assertEquals("first\n",Files.readString(ownerPackage.resolve("files/a-imported.txt")));
            assertEquals("last\n",Files.readString(ownerPackage.resolve("files/z-imported.txt")));
            assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig));

            byte[] replacementArchive=packageZip("files/a-imported.txt","updated\n","files/new-imported.txt","new\n");
            Captured replacement=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),replacementArchive,"application/zip");
            assertEquals(201,replacement.status,new String(replacement.body(),StandardCharsets.UTF_8));
            JsonNode replacementDraft=ServerRuntime.JSON.readTree(replacement.body());
            assertEquals("replace",replacementDraft.path("changedFiles").path(0).path("operation").asText());
            assertEquals("files/a-imported.txt",replacementDraft.path("changedFiles").path(0).path("path").asText());
            assertEquals("create",replacementDraft.path("changedFiles").path(1).path("operation").asText());
            String replacementId=replacementDraft.path("changeSetId").asText();
            for(String action:List.of("validate","review")) {
                Captured response=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+replacementId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
            }
            Captured replacementPublished=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+replacementId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200,replacementPublished.status,new String(replacementPublished.body(),StandardCharsets.UTF_8));
            assertEquals("updated\n",Files.readString(ownerPackage.resolve("files/a-imported.txt")));
            assertEquals("new\n",Files.readString(ownerPackage.resolve("files/new-imported.txt")));
            assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig));

            byte[] malformedWorkbookArchive=packageZip("files/valid-prefix.txt","must-stay-private\n",
                    "testcase/malformed.xlsx","not an XLSX workbook");
            Captured malformedWorkbookImport=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),malformedWorkbookArchive,"application/zip");
            assertEquals(400,malformedWorkbookImport.status,new String(malformedWorkbookImport.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-PACKAGE-ZIP-INVALID",ServerRuntime.JSON.readTree(malformedWorkbookImport.body())
                    .path("error").path("code").asText());
            assertFalse(Files.exists(ownerPackage.resolve("files/valid-prefix.txt")),
                    "A valid ZIP prefix must not be staged when another entry contains a malformed workbook");
            assertFalse(Files.exists(ownerPackage.resolve("testcase/malformed.xlsx")),
                    "A malformed workbook must never become a published Package source");
            assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig));
        }
    }

    @Test void packagePrivilegedReadsKeepRegistryAuthorityThroughResponseSerialization() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("package-response-boundary-packages"));
        Path packageRoot=Files.createDirectories(packages.resolve("shared"));
        Files.createDirectories(packageRoot.resolve("testcase"));Files.createDirectories(packageRoot.resolve("templates"));
        Files.createDirectories(packageRoot.resolve("files"));Files.createDirectories(packageRoot.resolve("load/sut-metrics/sources"));
        String manifest="schemaVersion: att-package/v2\nmetadata: {name: Shared Package}\n"
                +"# authoring-boundary-marker\nobservability:\n  metricSources: [load/sut-metrics/sources/cpu.yaml]\n  groups: []\n  profiles: []\n";
        Files.writeString(packageRoot.resolve("package.yaml"),manifest);
        String metricSource="schemaVersion: att-sut-metrics-metric-source/v1\nid: cpu\nname: CPU\n"
                +"connection: {kind: DATA_SOURCE, id: prom-main}\n"
                +"fetch: {id: cpu-range, mode: prometheus-range, query: raw-metric-boundary-marker, parameters: {}, sampling: range, step: 30s}\n"
                +"mapping: {labels: {retain: []}, outputs: [{id: up, title: Up, unit: count, kind: gauge, metricName: up}]}\n"
                +"limits: {maxRows: 1024, maxSeries: 128, maxSamples: 10000, maxArtifactBytes: 1048576}\n";
        Files.writeString(packageRoot.resolve("load/sut-metrics/sources/cpu.yaml"),metricSource);
        Path runtimeConfig=sharedRuntimeConfig("package-response-boundary-runtime");
        Path serverFile=temp.resolve("package-response-boundary-server.yaml");
        Path data=temp.resolve("package-response-boundary-data");
        String configured="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n"
                +"  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n"
                +"  authenticationRequired: true\n  auth: {mode: local}\n"
                +"  packageConfiguration: {sourceEditingEnabled: true}\n  inspection: {enabled: true}\nworkers: {}\npackages:\n"
                +"  allowedRoots: ["+ServerRuntimeTestSupport.yaml(packages)+"]\n  entries:\n"
                +"    shared:\n      path: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n      editors: [alice, bob]\n";
        Files.writeString(serverFile,configured);
        Path libs=workerLibraries("package-response-boundary-libs");
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverFile),libs.toString())) {
            runtime.auth.createUser("alice","Alice","response-boundary Alice password","USER");
            runtime.auth.createUser("bob","Bob","response-boundary Bob password","USER");
            runtime.packageRegistry.close(); // Registry transitions below are driven deterministically by the test.
            ApiServlet servlet=servlet(runtime);

            Captured first=invokeAsContentType(servlet,"alice","POST","/packages/shared/change-sets/import",
                    Collections.emptyMap(),packageZip("files/history-race.txt","history-before-boundary-marker\n"),"application/zip");
            assertEquals(201,first.status,first.bodyText());
            String firstId=ServerRuntime.JSON.readTree(first.body()).path("changeSetId").asText();
            for(String action:List.of("validate","review"))
                assertEquals(200,invokeAs(servlet,"alice","POST","/packages/shared/change-sets/"+firstId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8)).status);
            assertEquals(200,invokeAs(servlet,"alice","POST","/packages/shared/change-sets/"+firstId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8)).status);
            JsonNode firstHistory=ServerRuntime.JSON.readTree(invokeAs(servlet,"alice","GET","/packages/shared/source-history",
                    Collections.emptyMap(),new byte[0]).body());
            String beforeSnapshot=firstHistory.path("items").path(0).path("afterSnapshotId").asText();
            Captured second=invokeAsContentType(servlet,"alice","POST","/packages/shared/change-sets/import",
                    Collections.emptyMap(),packageZip("files/history-race.txt","history-after-boundary-marker\n"),"application/zip");
            assertEquals(201,second.status,second.bodyText());
            String secondId=ServerRuntime.JSON.readTree(second.body()).path("changeSetId").asText();
            for(String action:List.of("validate","review"))
                assertEquals(200,invokeAs(servlet,"alice","POST","/packages/shared/change-sets/"+secondId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8)).status);
            assertEquals(200,invokeAs(servlet,"alice","POST","/packages/shared/change-sets/"+secondId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8)).status);
            JsonNode secondHistory=ServerRuntime.JSON.readTree(invokeAs(servlet,"alice","GET","/packages/shared/source-history",
                    Collections.emptyMap(),new byte[0]).body());
            String afterSnapshot=secondHistory.path("items").path(0).path("afterSnapshotId").asText();
            assertTrue(beforeSnapshot.matches("R[A-F0-9]{32}"));assertTrue(afterSnapshot.matches("R[A-F0-9]{32}"));
            Map<String,String> diffParameters=Map.of("beforeSnapshotId",beforeSnapshot,"afterSnapshotId",afterSnapshot,
                    "path","files/history-race.txt");

            String expectedManifest=PackageManifestPublisher.digest(manifest.getBytes(StandardCharsets.UTF_8));
            String candidate=manifest+"# draft-source-boundary-marker\n";
            Captured draftResponse=invokeAs(servlet,"alice","POST","/packages/shared/package-configuration/drafts",
                    Collections.emptyMap(),ServerRuntime.JSON.writeValueAsBytes(Map.of("source",candidate,
                            "expectedManifestDigest",expectedManifest)));
            assertEquals(200,draftResponse.status,draftResponse.bodyText());
            String manifestDraftId=ServerRuntime.JSON.readTree(draftResponse.body()).path("draftId").asText();
            assertTrue(runtime.canEditPackageContent("shared","alice"),"Alice remains an assigned active USER editor");
            assertEquals(candidate,runtime.manifestPublisher.previewDraftSource("shared","alice",manifestDraftId).get("source"),
                    "The staged draft must remain present before the servlet read");
            Captured manifestDraftSource=invokeAs(servlet,"alice","GET",
                    "/packages/shared/package-configuration/drafts/"+manifestDraftId+"/source",Collections.emptyMap(),new byte[0]);
            assertEquals(200,manifestDraftSource.status,manifestDraftSource.bodyText());
            assertTrue(manifestDraftSource.bodyText().contains("draft-source-boundary-marker"),manifestDraftSource.bodyText());

            String current=configured;
            current=assertRegistryRevocationWaitsForProtectedResponse(runtime,servlet,serverFile,current,
                    "/packages/shared/package-configuration/drafts/"+manifestDraftId+"/source",Collections.emptyMap(),
                    "draft-source-boundary-marker");
            current=assertRegistryRevocationWaitsForProtectedResponse(runtime,servlet,serverFile,current,
                    "/packages/shared/authoring-source",Map.of("type","manifest"),"authoring-boundary-marker");
            current=assertRegistryRevocationWaitsForProtectedResponse(runtime,servlet,serverFile,current,
                    "/packages/shared/source-history/diff",diffParameters,"history-after-boundary-marker");
            assertRegistryRevocationWaitsForProtectedResponse(runtime,servlet,serverFile,current,
                    "/packages/shared/sut-metrics/resource",Map.of("kind","source","id","cpu"),"raw-metric-boundary-marker");
        }
    }

    @Test void trustedEditorsSharePackageEditingAcrossScopesAndRevocationInvalidatesZipDrafts() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("shared-editor-packages"));
        Path alicePackage=createPackage(packages.resolve("alice"));
        Path commonOne=createPackage(packages.resolve("common-one"));
        Path commonTwo=createPackage(packages.resolve("common-two"));
        Path adminPackage=createPackage(packages.resolve("admin"));
        Path runtimeConfig=sharedRuntimeConfig("shared-editor-runtime");
        Path serverFile=temp.resolve("shared-editor-server.yaml");
        Path data=temp.resolve("shared-editor-data");
        String configured="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n"
                +"  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n"
                +"  authenticationRequired: true\n  auth: {mode: local}\n"
                +"  centralConfigurationEditingEnabled: true\n  packageConfiguration: {sourceEditingEnabled: true}\n"
                +"  inspection: {enabled: true}\nworkers: {}\npackages:\n"
                +"  allowedRoots: ["+ServerRuntimeTestSupport.yaml(packages)+"]\n  entries:\n"
                +"    alice: "+ServerRuntimeTestSupport.yaml(alicePackage)+"\n"
                +"    common-one:\n      path: "+ServerRuntimeTestSupport.yaml(commonOne)+"\n      editors: [alice]\n"
                +"    common-two:\n      path: "+ServerRuntimeTestSupport.yaml(commonTwo)+"\n      editors: [alice, bob]\n"
                +"    admin:\n      path: "+ServerRuntimeTestSupport.yaml(adminPackage)+"\n      editors: [admin]\n";
        Files.writeString(serverFile,configured);
        Path libs=workerLibraries("shared-editor-libs");
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverFile),libs.toString())) {
            runtime.auth.createUser("alice","Alice","shared package editor password","USER");
            runtime.auth.createUser("bob","Bob","unassigned package user password","USER");
            runtime.auth.createUser("admin","Admin","shared package admin password","ADMIN");
            ApiServlet servlet=servlet(runtime);

            Captured alicePackages=invokeAs(servlet,"alice","GET","/packages",Collections.emptyMap(),new byte[0]);
            assertEquals(200,alicePackages.status,new String(alicePackages.body(),StandardCharsets.UTF_8));
            JsonNode packageItems=ServerRuntime.JSON.readTree(alicePackages.body()).path("items");
            Map<String,Boolean> capabilities=new HashMap<>();
            for(JsonNode item:packageItems)capabilities.put(item.path("packageId").asText(),item.path("canEditPackageContent").asBoolean());
            assertEquals(Boolean.TRUE,capabilities.get("alice"),"The editor keeps ownership of their registered Package");
            assertEquals(Boolean.TRUE,capabilities.get("common-one"));
            assertEquals(Boolean.TRUE,capabilities.get("common-two"),"One USER may be assigned to multiple Package IDs");
            assertEquals(Boolean.FALSE,capabilities.get("admin"),"An explicit editor list does not grant ADMIN identities Package USER capability");

            Captured sharedSource=invokeAs(servlet,"alice","GET","/packages/common-one/authoring-source",
                    Map.of("type","manifest"),new byte[0]);
            assertEquals(200,sharedSource.status,new String(sharedSource.body(),StandardCharsets.UTF_8));
            assertEquals(200,invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Map.of("type","manifest"),new byte[0]).status,
                    "The registered owner retains the existing private Package editing rule");
            Captured bobPackages=invokeAs(servlet,"bob","GET","/packages",Collections.emptyMap(),new byte[0]);
            assertEquals(200,bobPackages.status,bobPackages.bodyText());
            JsonNode bobCommonTwo=null;
            for(JsonNode item:ServerRuntime.JSON.readTree(bobPackages.body()).path("items"))
                if("common-two".equals(item.path("packageId").asText()))bobCommonTwo=item;
            assertNotNull(bobCommonTwo,bobPackages.bodyText());
            assertTrue(bobCommonTwo.path("canEditPackageContent").asBoolean(),
                    "A second explicitly assigned USER receives the package edit capability");
            Captured bobSharedSource=invokeAs(servlet,"bob","GET","/packages/common-two/authoring-source",
                    Map.of("type","manifest"),new byte[0]);
            assertEquals(200,bobSharedSource.status,bobSharedSource.bodyText());
            assertEquals(403,invokeAs(servlet,"bob","GET","/packages/alice/authoring-source",
                    Map.of("type","manifest"),new byte[0]).status,
                    "An unassigned USER cannot edit another user's private Package");
            assertEquals(403,invokeAs(servlet,"bob","GET","/packages/common-one/authoring-source",
                    Map.of("type","manifest"),new byte[0]).status,"An unassigned USER cannot read full authoring source");
            assertEquals(403,invokeAsAdmin(servlet,"admin","GET","/packages/admin/authoring-source",
                    Map.of("type","manifest"),new byte[0]).status,"An ADMIN whose login matches a Package ID remains denied");
            assertEquals(403,invokeAs(servlet,"alice","GET","/configuration/authoring-source",
                    Map.of("path","config/config.yaml"),new byte[0]).status,
                    "Package editor grants do not imply CENTRAL_RUNTIME_CONFIG or SYSTEM_SETTINGS_WRITE");

            Captured fileIndex=invokeAs(servlet,"alice","GET","/packages/common-one/resources/files",
                    Collections.emptyMap(),new byte[0]);
            assertEquals(200,fileIndex.status,new String(fileIndex.body(),StandardCharsets.UTF_8));
            assertTrue(ServerRuntime.JSON.readTree(fileIndex.body()).path("canEdit").asBoolean());
            Captured readOnlyIndex=invokeAs(servlet,"bob","GET","/packages/common-one/resources/files",
                    Collections.emptyMap(),new byte[0]);
            assertEquals(200,readOnlyIndex.status,new String(readOnlyIndex.body(),StandardCharsets.UTF_8));
            assertFalse(ServerRuntime.JSON.readTree(readOnlyIndex.body()).path("canEdit").asBoolean());
            assertEquals(403,invokeAsContentType(servlet,"bob","POST","/packages/common-one/change-sets/import",
                    Collections.emptyMap(),packageZip("files/unassigned-bob.txt","must remain private\n"),"application/zip").status,
                    "An active USER without a common-one editor assignment cannot import a ZIP change set");
            assertFalse(Files.exists(commonOne.resolve("files/unassigned-bob.txt")));

            byte[] firstArchive=packageZip("files/shared-editor.txt","published by assigned editor\n");
            Captured imported=invokeAsContentType(servlet,"alice","POST","/packages/common-two/change-sets/import",
                    Collections.emptyMap(),firstArchive,"application/zip");
            assertEquals(201,imported.status,new String(imported.body(),StandardCharsets.UTF_8));
            String importedId=ServerRuntime.JSON.readTree(imported.body()).path("changeSetId").asText();
            for(String action:List.of("validate","review"))
                assertEquals(200,invokeAs(servlet,"alice","POST","/packages/common-two/change-sets/"+importedId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8)).status);
            assertEquals(200,invokeAs(servlet,"alice","POST","/packages/common-two/change-sets/"+importedId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8)).status);
            assertEquals("published by assigned editor\n",Files.readString(commonTwo.resolve("files/shared-editor.txt")));
            Captured editorHistory=invokeAs(servlet,"alice","GET","/packages/common-two/source-history",
                    Collections.emptyMap(),new byte[0]);
            assertEquals(200,editorHistory.status,new String(editorHistory.body(),StandardCharsets.UTF_8));
            assertTrue(new String(editorHistory.body(),StandardCharsets.UTF_8).contains("files/shared-editor.txt"),
                    "An assigned editor can read full source history for that Package");

            String sharedBaselineSnapshot=ServerRuntime.JSON.readTree(editorHistory.body())
                    .path("items").path(0).path("afterSnapshotId").asText();
            assertTrue(sharedBaselineSnapshot.matches("R[A-F0-9]{32}"),editorHistory.bodyText());
            Captured aliceRace=invokeAsContentType(servlet,"alice","POST","/packages/common-two/change-sets/import",
                    Collections.emptyMap(),packageZip("files/alice-race.txt","published by Alice\n"),"application/zip");
            Captured bobRace=invokeAsContentType(servlet,"bob","POST","/packages/common-two/change-sets/import",
                    Collections.emptyMap(),packageZip("files/bob-race.txt","must lose stale publish\n"),"application/zip");
            assertEquals(201,aliceRace.status,aliceRace.bodyText());
            assertEquals(201,bobRace.status,bobRace.bodyText());
            String aliceRaceId=ServerRuntime.JSON.readTree(aliceRace.body()).path("changeSetId").asText();
            String bobRaceId=ServerRuntime.JSON.readTree(bobRace.body()).path("changeSetId").asText();
            assertEquals(ServerRuntime.JSON.readTree(aliceRace.body()).path("baseSourceRevision").asText(),
                    ServerRuntime.JSON.readTree(bobRace.body()).path("baseSourceRevision").asText(),
                    "Both assigned editors stage against the same source revision");
            for(String id:List.of(aliceRaceId,bobRaceId))for(String action:List.of("validate","review")) {
                String actor=id.equals(aliceRaceId)?"alice":"bob";
                Captured prepared=invokeAs(servlet,actor,"POST","/packages/common-two/change-sets/"+id+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200,prepared.status,prepared.bodyText());
            }
            Captured alicePublished=invokeAs(servlet,"alice","POST","/packages/common-two/change-sets/"+aliceRaceId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200,alicePublished.status,alicePublished.bodyText());
            Captured bobStale=invokeAs(servlet,"bob","POST","/packages/common-two/change-sets/"+bobRaceId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(409,bobStale.status,bobStale.bodyText());
            assertTrue(Files.exists(commonTwo.resolve("files/alice-race.txt")));
            assertFalse(Files.exists(commonTwo.resolve("files/bob-race.txt")),
                    "A second assigned editor cannot publish a Change Set prepared against stale source");

            JsonNode historyBeforeRestore=ServerRuntime.JSON.readTree(invokeAs(servlet,"bob","GET",
                    "/packages/common-two/source-history",Collections.emptyMap(),new byte[0]).body());
            Captured restore=invokeAs(servlet,"bob","POST","/packages/common-two/source-history/restore",
                    Collections.emptyMap(),ServerRuntime.JSON.writeValueAsBytes(Map.of(
                            "snapshotId",sharedBaselineSnapshot,
                            "expectedSourceRevision",historyBeforeRestore.path("currentSourceRevision").asText(),
                            "registryRevision",historyBeforeRestore.path("registryRevision").asText())));
            assertEquals(201,restore.status,restore.bodyText());
            String restoreId=ServerRuntime.JSON.readTree(restore.body()).path("changeSetId").asText();
            for(String action:List.of("validate","review")) {
                Captured prepared=invokeAs(servlet,"bob","POST","/packages/common-two/change-sets/"+restoreId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200,prepared.status,prepared.bodyText());
            }
            Captured restored=invokeAs(servlet,"bob","POST","/packages/common-two/change-sets/"+restoreId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200,restored.status,restored.bodyText());
            assertEquals("published by assigned editor\n",Files.readString(commonTwo.resolve("files/shared-editor.txt")));
            assertFalse(Files.exists(commonTwo.resolve("files/alice-race.txt")),
                    "An assigned editor can restore the shared Package source to its retained snapshot");
            JsonNode restoredHistory=ServerRuntime.JSON.readTree(invokeAs(servlet,"alice","GET",
                    "/packages/common-two/source-history",Collections.emptyMap(),new byte[0]).body());
            assertEquals("SOURCE_RESTORE_PUBLISH",restoredHistory.path("items").path(0).path("action").asText());
            assertEquals(sharedBaselineSnapshot,restoredHistory.path("items").path(0).path("restoredFromSnapshotId").asText());

            byte[] revocationArchive=packageZip("files/revoked-before-publish.txt","must remain private\n");
            Captured revocationImport=invokeAsContentType(servlet,"alice","POST","/packages/common-one/change-sets/import",
                    Collections.emptyMap(),revocationArchive,"application/zip");
            assertEquals(201,revocationImport.status,new String(revocationImport.body(),StandardCharsets.UTF_8));
            String revocationId=ServerRuntime.JSON.readTree(revocationImport.body()).path("changeSetId").asText();
            for(String action:List.of("validate","review"))
                assertEquals(200,invokeAs(servlet,"alice","POST","/packages/common-one/change-sets/"+revocationId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8)).status);
            String previousRegistryRevision=runtime.packageRegistry.snapshot().revision;
            Files.writeString(serverFile,configured.replace("editors: [alice]","editors: []"));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(previousRegistryRevision.equals(runtime.packageRegistry.snapshot().revision)&&System.nanoTime()<deadline)
                Thread.sleep(20);
            assertNotEquals(previousRegistryRevision,runtime.packageRegistry.snapshot().revision,
                    "Changing trusted editors advances the live Package registry revision");
            assertTrue(runtime.packageRegistry.snapshot().editors.get("common-one").isEmpty());

            assertEquals(403,invokeAs(servlet,"alice","GET","/packages/common-one/authoring-source",
                    Map.of("type","manifest"),new byte[0]).status);
            assertEquals(403,invokeAsContentType(servlet,"alice","POST","/packages/common-one/change-sets/import",
                    Collections.emptyMap(),revocationArchive,"application/zip").status);
            Captured stalePublish=invokeAs(servlet,"alice","POST","/packages/common-one/change-sets/"+revocationId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertTrue(stalePublish.status==403||stalePublish.status==409,
                    "Revocation must deny or stale an outstanding Change Set: "+new String(stalePublish.body(),StandardCharsets.UTF_8));
            assertFalse(Files.exists(commonOne.resolve("files/revoked-before-publish.txt")));
            Captured revokedHistory=invokeAs(servlet,"alice","GET","/packages/common-one/source-history",
                    Collections.emptyMap(),new byte[0]);
            assertEquals(200,revokedHistory.status);
            assertFalse(new String(revokedHistory.body(),StandardCharsets.UTF_8).contains("files/shared-editor.txt"),
                    "Revoked editors receive only the safe history summary");
            assertEquals(200,invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Map.of("type","manifest"),new byte[0]).status,
                    "Revoking shared assignments does not remove the USER's own Package ownership");
        }
    }

    private String assertRegistryRevocationWaitsForProtectedResponse(ServerRuntime runtime,ApiServlet servlet,
            Path serverFile,String grantedConfiguration,String path,Map<String,String> parameters,
            String expectedResponseMarker) throws Exception {
        assertTrue(grantedConfiguration.contains("editors: [alice, bob]"),grantedConfiguration);
        String revokedConfiguration=grantedConfiguration.replace("editors: [alice, bob]","editors: [bob]");
        assertNotEquals(grantedConfiguration,revokedConfiguration);
        String admittedRevision=runtime.packageRegistry.snapshot().revision;
        CountDownLatch responseWriting=new CountDownLatch(1),allowResponse=new CountDownLatch(1);
        Captured response=new Captured(responseWriting,allowResponse);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<Captured> request=executor.submit(()->invokeAsCaptured(servlet,"GET",path,parameters,new byte[0],
                    "alice",null,false,"application/json",response));
            assertTrue(responseWriting.await(10,TimeUnit.SECONDS),"The authorized response did not reach JSON serialization: "+path);
            if(response.status!=200) {
                allowResponse.countDown();
                request.get(5,TimeUnit.SECONDS);
            }
            assertEquals(200,response.status,"The privileged response should succeed before its serialization is paused: "+path
                    +"; body="+response.bodyText());
            Files.writeString(serverFile,revokedConfiguration);
            runtime.packageRegistry.pollNow();
            Field pendingSince=PackageRegistry.class.getDeclaredField("pendingSince");pendingSince.setAccessible(true);
            pendingSince.set(runtime.packageRegistry,Instant.EPOCH);
            CountDownLatch reloadStarted=new CountDownLatch(1);
            AtomicReference<Thread> reloadThread=new AtomicReference<>();
            Future<?> registryUpdate=executor.submit(()->{reloadThread.set(Thread.currentThread());reloadStarted.countDown();runtime.packageRegistry.pollNow();return null;});
            assertTrue(reloadStarted.await(5,TimeUnit.SECONDS));
            Field lifecycleField=ServerRuntime.class.getDeclaredField("admissionLifecycle");lifecycleField.setAccessible(true);
            ReentrantReadWriteLock lifecycle=(ReentrantReadWriteLock)lifecycleField.get(runtime);
            assertTrue(lifecycle.getReadLockCount()>0,"The response must retain registry read authority while its body is serialized: "+path);
            long queueDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            Thread writer=reloadThread.get();
            while(writer!=null&&!lifecycle.hasQueuedThread(writer)&&!registryUpdate.isDone()
                    &&System.nanoTime()<queueDeadline)Thread.yield();
            assertTrue(writer!=null&&lifecycle.hasQueuedThread(writer),
                    "The registry reload must be queued behind response serialization before the response is released: "+path
                            +"; readers="+lifecycle.getReadLockCount()+"; done="+registryUpdate.isDone()
                            +"; writerState="+(writer==null?"missing":writer.getState())
                            +"; writerStack="+(writer==null?"missing":java.util.Arrays.toString(writer.getStackTrace())));
            assertFalse(registryUpdate.isDone(),"A registry revoke must wait for response serialization: "+path);
            assertEquals(admittedRevision,runtime.packageRegistry.snapshot().revision,
                    "The old capability remains installed until the authorized response finishes: "+path);
            allowResponse.countDown();
            Captured completed=request.get(10,TimeUnit.SECONDS);
            registryUpdate.get(10,TimeUnit.SECONDS);
            assertEquals(200,completed.status,completed.bodyText());
            assertTrue(completed.bodyText().contains(expectedResponseMarker),completed.bodyText());
            assertNotEquals(admittedRevision,runtime.packageRegistry.snapshot().revision);
            assertFalse(runtime.packageRegistry.snapshot().editors.get("shared").contains("alice"));

            Captured afterRevoke=invokeAs(servlet,"alice","GET",path,parameters,new byte[0]);
            if(path.endsWith("/source-history/diff")) {
                assertEquals(200,afterRevoke.status,afterRevoke.bodyText());
                assertFalse(afterRevoke.bodyText().contains(expectedResponseMarker),afterRevoke.bodyText());
                assertEquals(false,ServerRuntime.JSON.readTree(afterRevoke.body()).path("items").path(0).path("contentAvailable").asBoolean());
            } else if(path.endsWith("/sut-metrics/resource"))assertEquals(404,afterRevoke.status,afterRevoke.bodyText());
            else assertEquals(403,afterRevoke.status,afterRevoke.bodyText());

            String restoredConfiguration=revokedConfiguration.replace("editors: [bob]","editors: [alice, bob]");
            Files.writeString(serverFile,restoredConfiguration);
            runtime.packageRegistry.pollNow();pendingSince.set(runtime.packageRegistry,Instant.EPOCH);
            runtime.packageRegistry.pollNow();
            assertTrue(runtime.packageRegistry.snapshot().editors.get("shared").contains("alice"));
            return restoredConfiguration;
        } finally {
            allowResponse.countDown();executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);
        }
    }

    private static JsonNode assertChangeSetSchema(com.networknt.schema.JsonSchema schema,Captured response,int status) throws Exception {
        assertEquals(status,response.status,new String(response.body(),StandardCharsets.UTF_8));
        assertEquals("private, no-store",response.headers.get("Cache-Control"));
        JsonNode body=ServerRuntime.JSON.readTree(response.body());
        assertEquals("limits-test",body.path("requestId").asText());
        java.util.Set<com.networknt.schema.ValidationMessage> errors=schema.validate(body);
        assertTrue(errors.isEmpty(),errors.toString()+" response="+body);
        return body;
    }

    @Test void packageAuthoringSourceTimeoutIs504() throws Exception {
        assertAuthoringSourceTimeout(false,"package-authoring-timeout");
    }

    @Test void centralAuthoringSourceTimeoutIs504() throws Exception {
        assertAuthoringSourceTimeout(true,"central-authoring-timeout");
    }

    @Test void privilegedAuthoringSourceUnexpectedWorkerFailuresAreSafe500ForBothRoutes() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("authoring-failure-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        ServerRuntime.WorkerProcessLauncher brokenLauncher=builder->{throw new java.io.IOException("source-error-excerpt-canary");};
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"authoring-failure-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,brokenLauncher,workerLibraries("authoring-failure-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            Captured packageSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Collections.singletonMap("type","manifest"),new byte[0]);
            Captured centralSource=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/config.yaml"),new byte[0]);
            assertSafeSourceFailure(packageSource,500,"ATT-SERVER-REQUEST-FAILED");
            assertSafeSourceFailure(centralSource,500,"ATT-SERVER-REQUEST-FAILED");
            assertFalse(new String(packageSource.body(),StandardCharsets.UTF_8).contains("source-error-excerpt-canary"));
            assertFalse(new String(centralSource.body(),StandardCharsets.UTF_8).contains("source-error-excerpt-canary"));
        }
    }

    private void assertAuthoringSourceTimeout(boolean central,String dataName) throws Exception {
        Path packages=Files.createDirectories(temp.resolve(dataName+"-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,dataName+"-data",100,null,
                workerLibraries(dataName+"-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            java.util.List<ReentrantLock> held=new java.util.ArrayList<ReentrantLock>();
            for(Object worker:inspectionWorkers(runtime)) {
                Field lockField=worker.getClass().getDeclaredField("lock");lockField.setAccessible(true);
                ReentrantLock lock=(ReentrantLock)lockField.get(worker);lock.lock();held.add(lock);
            }
            Captured response;
            try {
                response=central
                        ?invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                                Collections.singletonMap("path","config/config.yaml"),new byte[0])
                        :invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                                Collections.singletonMap("type","manifest"),new byte[0]);
            } finally {for(int index=held.size()-1;index>=0;index--)held.get(index).unlock();}
            assertSafeSourceFailure(response,504,"ATT-SERVER-INSPECTION-TIMEOUT");
            stopInspectionProcesses(runtime);
        }
    }

    private ServerRuntime authoringRuntime(Path packages,Path ownerPackage,Path runtimeConfig,String dataName,int timeout,
                                            ServerRuntime.WorkerProcessLauncher launcher,Path libs) throws Exception {
        return authoringRuntime(packages,ownerPackage,runtimeConfig,dataName,timeout,launcher,libs,"local");
    }

    private ServerRuntime authoringRuntime(Path packages,Path ownerPackage,Path runtimeConfig,String dataName,int timeout,
                                            ServerRuntime.WorkerProcessLauncher launcher,Path libs,String authMode) throws Exception {
        Path yaml=temp.resolve(dataName+".yaml");
        String config="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve(dataName))
                +"\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  authenticationRequired: true\n  auth: {mode: "+authMode+"}\n  centralConfigurationEditingEnabled: true\n"
                +"  packageConfiguration: {sourceEditingEnabled: true}\n  inspection:\n    enabled: true\n    maxConcurrent: 1\n"
                +"    queuedLimit: 0\n    timeoutMs: "+timeout+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 0\n"
                +"packages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(packages)+"]\n  entries:\n    alice: "
                +ServerRuntimeTestSupport.yaml(ownerPackage)+"\n";
        ServerConfig serverConfig=ServerConfig.load(Files.writeString(yaml,config));
        return new ServerRuntime(serverConfig,libs.toString(),launcher==null?ProcessBuilder::start:launcher);
    }

    private Path writeNativePreviewPackage(Path packageRoot,String caseId,String amount) throws Exception {
        Path workbook=packageRoot.resolve("testcase/native/cases.xlsx");
        writeNativePreviewWorkbook(workbook,caseId,amount,false);
        Files.createDirectories(packageRoot.resolve("templates/flows/checkout/v1"));
        Files.writeString(packageRoot.resolve("templates/flows/checkout/v1/flow.yaml"),
                "schemaVersion: att-flow/v3.6\nid: checkout.v1\nname: Checkout\ndescription: Preview fixture\nactions:\n"
                        +"  record:\n    type: log\n    message: Preview must not execute a Flow\n");
        Files.createDirectories(packageRoot.resolve("testcase/native"));
        // Keep the active editor source safe to return; the unsaved preview candidate below adds a sensitive input mapping.
        Files.writeString(packageRoot.resolve("testcase/native/testcase.yaml"),nativePreviewDefinition());
        return workbook;
    }

    private void writeNativePreviewWorkbook(Path path,String caseId,String amount,boolean numericCaseId) throws Exception {
        Files.createDirectories(path.getParent());
        try(Workbook workbook=new XSSFWorkbook();OutputStream output=Files.newOutputStream(path)) {
            Sheet sheet=workbook.createSheet("Cases");Row header=sheet.createRow(0);
            header.createCell(0).setCellValue("ID");header.createCell(1).setCellValue("Amount");header.createCell(2).setCellValue("Password");
            Row row=sheet.createRow(1);
            if(numericCaseId)row.createCell(0).setCellValue(17);else row.createCell(0).setCellValue(caseId);
            row.createCell(1).setCellValue(amount);row.createCell(2).setCellValue("native-preview-secret-canary");
            workbook.write(output);
        }
    }

    private static String nativePreviewDefinition() {
        return "# supplementary Unicode before dataColumns: 🚀\n"
                +"schemaVersion: att-testcase/v1\nid: native.case\nflow: checkout.v1\n"
                +"caseIdExpression: \"${DATA.id}\"\ndataSource:\n  type: excel\n"
                +"  path: testcase/native/cases.xlsx\n  sheet: Cases\n  headerRows: 1\n  caseId: ID\n"
                +"  dataColumns: id=ID, amount=Amount, password=Password\n";
    }

    private Process mutateWorkbookAfterPreviewRequest(ProcessBuilder builder,Path workbook,
                                                       java.util.concurrent.atomic.AtomicBoolean changed) throws Exception {
        Process delegate=builder.start();
        OutputStream observed=new FilterOutputStream(delegate.getOutputStream()) {
            private final StringBuilder request=new StringBuilder();
            private void inspect(String value) throws java.io.IOException {
                request.append(value);
                if(!changed.get()&&request.indexOf("\"inspectionAction\":\"native-case-preview\"")>=0
                        &&changed.compareAndSet(false,true)) {
                    try {writeNativePreviewWorkbook(workbook,"case-1","99",false);}
                    catch(Exception failure){throw new java.io.IOException("Unable to mutate preview workbook for the stale-source regression",failure);}
                }
                if(request.length()>2_000_000)request.setLength(0);
            }
            @Override public void write(byte[] bytes,int offset,int length)throws java.io.IOException {
                inspect(new String(bytes,offset,length,StandardCharsets.UTF_8));out.write(bytes,offset,length);
            }
            @Override public void write(int value)throws java.io.IOException {
                inspect(String.valueOf((char)(value&0xff)));out.write(value);
            }
        };
        return new Process() {
            @Override public OutputStream getOutputStream(){return observed;}
            @Override public InputStream getInputStream(){return delegate.getInputStream();}
            @Override public InputStream getErrorStream(){return delegate.getErrorStream();}
            @Override public int waitFor() throws InterruptedException{return delegate.waitFor();}
            @Override public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{return delegate.waitFor(timeout,unit);}
            @Override public int exitValue(){return delegate.exitValue();}
            @Override public void destroy(){delegate.destroy();}
            @Override public Process destroyForcibly(){delegate.destroyForcibly();return this;}
            @Override public boolean isAlive(){return delegate.isAlive();}
        };
    }

    private int runDraftCount(ServerRuntime runtime) throws Exception {
        Field field=ServerRuntime.class.getDeclaredField("runDrafts");field.setAccessible(true);
        return ((Map<?,?>)field.get(runtime)).size();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result=new StringBuilder(digest.length*2);
        for(byte value:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",value&0xff));
        return result.toString();
    }

    @Test void centralAuthoringSourceWithholdsProtectedEmbeddedScalarsFromActiveRevision() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("central-active-secret-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        String protectedSource=Files.readString(runtimeConfig)
                +"x-authoring-secret-fixture: |\n  classification: secret\n  value: central-active-route-canary\n";
        Files.writeString(runtimeConfig,protectedSource);
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"central-active-secret-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("central-active-secret-libs"))) {
            addAuthoringUsers(runtime);
            Captured central=invokeAs(servlet(runtime),"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/config.yaml"),new byte[0]);
            assertSafeSourceFailure(central,404,"ATT-SERVER-NOT-FOUND");
            assertFalse(new String(central.body(),StandardCharsets.UTF_8).contains("central-active-route-canary"));
        }
    }

    private static void addAuthoringUsers(ServerRuntime runtime) throws Exception {
        runtime.auth.createUser("alice","Alice","alice source access password","USER");
        runtime.auth.createUser("admin","Admin","admin source access password","ADMIN");
    }

    private static void assertSafeSourceFailure(Captured response,int status,String code) throws Exception {
        JsonNode body=ServerRuntime.JSON.readTree(response.body());
        assertEquals(status,response.status,new String(response.body(),StandardCharsets.UTF_8));
        assertEquals(code,body.path("error").path("code").asText(),body.toString());
        assertFalse(new String(response.body(),StandardCharsets.UTF_8).contains("schemaVersion"),
                "Source failures must not include source excerpts");
    }

    @Test void packageConfigurationByteLimitHasStructuredFieldsOnTheRealInspectionRoute() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("configuration-limit-packages"));createPackage(packages.resolve("p"));
        Path config=sharedRuntimeConfig();
        try(java.io.OutputStream out=Files.newOutputStream(config,java.nio.file.StandardOpenOption.APPEND)) {
            out.write(("#"+"x".repeat(1024*1024)+"\n").getBytes(StandardCharsets.UTF_8));
        }
        try(ServerRuntime runtime=runtime(packages,config,"configuration-limit-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("configuration-limit-libs"))) {
            Captured response=invoke(servlet(runtime),"GET","/packages/p/configuration/effective",Collections.singletonMap("environment","SIT"),new byte[0]);
            JsonNode diagnostic=ServerRuntime.JSON.readTree(response.body()).path("diagnostics").path(0);
            assertEquals(413,response.status,new String(response.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-RESOURCE-LIMIT",diagnostic.path("code").asText());
            assertEquals("packageConfiguration.maxConfigBytes",diagnostic.path("limitName").asText(),diagnostic.toString());
            assertEquals(1024*1024,diagnostic.path("configuredLimit").asInt());
            assertEquals(1024*1024,diagnostic.path("limit").asInt());
            assertTrue(diagnostic.path("observed").asLong()>diagnostic.path("configuredLimit").asLong());
            assertEquals(diagnostic.path("observed").asLong(),diagnostic.path("actual").asLong());
            assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void resourcePageSizeOverOneHundredHasStructured400DiagnosticOnRealRoute() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("page-size-packages"));createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"page-size-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("page-size-libs"))) {
            Map<String,String> parameters=new HashMap<>();parameters.put("type","template");parameters.put("limit","101");
            Captured response=invoke(servlet(runtime),"GET","/packages/p/resources",parameters,new byte[0]);
            JsonNode diagnostic=ServerRuntime.JSON.readTree(response.body()).path("diagnostics").path(0);
            assertEquals(400,response.status);
            assertEquals("ATT-RESOURCE-LIMIT",diagnostic.path("code").asText());
            assertEquals("resourceInspector.maxPageSize",diagnostic.path("limitName").asText());
            assertEquals(100,diagnostic.path("configuredLimit").asInt());
            assertEquals(101,diagnostic.path("observed").asInt());
            assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
        }
    }

    @Test void artifactReadSlotExhaustionNamesItsOwnCapacityOnTheRealArtifactRoute() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("artifact-capacity-packages"));createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"artifact-capacity-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("artifact-capacity-libs"))) {
            String id="J0000000000000295";Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
            Files.writeString(output.resolve("case.log"),"approved");
            Job job=new Job(id,"run","p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
            job.status="PASS";job.exitCode=0;job.finishedAt=Instant.now();runtime.store.insert(job,"{}");
            ArtifactArchive.build(output,runtime.artifactArchivePath(id));
            Files.writeString(runtime.artifactArchivePath(id).resolveSibling("archive-status.json"),"{\"state\":\"READY\"}");
            int acquired=0;while(runtime.acquireArtifactReadSlot())acquired++;
            assertEquals(8,acquired);
            try {
                Captured response=invoke(servlet(runtime),"GET","/jobs/"+id+"/artifacts/view/case.log",Collections.emptyMap(),new byte[0]);
                JsonNode diagnostic=ServerRuntime.JSON.readTree(response.body()).path("diagnostics").path(0);
                assertEquals(503,response.status);
                assertEquals("ATT-SERVER-ARTIFACT-CAPACITY",diagnostic.path("code").asText());
                assertEquals("server.artifacts.readSlots",diagnostic.path("limitName").asText());
                assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
            } finally {for(int index=0;index<acquired;index++)runtime.releaseArtifactReadSlot();}
        }
    }

    @Test void artifactRouteReportsPendingArchivingFailedAndReadyStatesSeparately() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("archive-packages"));
        createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"archive-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("archive-libs"))) {
            ApiServlet servlet=servlet(runtime);
            assertArchiveState(servlet,runtime,"J0000000000000291","PENDING",false,"ATT-ARCHIVE-NOT-READY",null);
            assertArchiveState(servlet,runtime,"J0000000000000292","ARCHIVING",false,"ATT-ARCHIVE-NOT-READY",null);
            assertArchiveState(servlet,runtime,"J0000000000000293","FAILED",false,"ATT-SERVER-ARCHIVE-UNAVAILABLE","ARCHIVE_FAILED");
            Captured ready=assertArchiveState(servlet,runtime,"J0000000000000294","READY",true,null,null);
            assertEquals(200,ready.status);
            assertEquals("approved",new String(ready.body(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private Captured assertArchiveState(ApiServlet servlet,ServerRuntime runtime,String id,String state,boolean ready,
                                        String expectedCode,String expectedOutcome) throws Exception {
        Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
        Files.writeString(output.resolve("case.log"),"approved");
        Job job=new Job(id,"run","p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
        job.status="PASS";job.exitCode=0;job.finishedAt=Instant.now();runtime.store.insert(job,"{}");
        Path status=runtime.artifactArchivePath(id).resolveSibling("archive-status.json");
        if(ready) {
            ArtifactArchive.build(output,runtime.artifactArchivePath(id));
            Files.writeString(status,"{\"state\":\"READY\"}");
        } else if(!"PENDING".equals(state))Files.writeString(status,"{\"state\":\""+state+"\"}");
        Captured result=invoke(servlet,"GET","/jobs/"+id+"/artifacts/view/case.log",Collections.emptyMap(),new byte[0]);
        if(ready)return result;
        JsonNode error=ServerRuntime.JSON.readTree(result.body());
        JsonNode diagnostic=error.path("diagnostics").path(0);
        assertEquals(409,result.status);
        assertEquals(expectedCode,diagnostic.path("code").asText());
        assertEquals(state,diagnostic.path("archiveState").asText());
        if(expectedOutcome==null)assertFalse(diagnostic.has("outcome"));
        else assertEquals(expectedOutcome,diagnostic.path("outcome").asText());
        return result;
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,false,false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,0,
                ref->System.getenv("ATT_SECRET_"+ref),false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  int inspectionQueuedLimit) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,
                inspectionQueuedLimit,ref->System.getenv("ATT_SECRET_"+ref),false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  java.util.function.Function<String,String> secretResolver) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,0,secretResolver,false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,
                                  boolean authenticationRequired,boolean centralConfigurationEditingEnabled) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,0,
                ref->System.getenv("ATT_SECRET_"+ref),centralConfigurationEditingEnabled);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  int inspectionQueuedLimit,java.util.function.Function<String,String> secretResolver) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,
                inspectionQueuedLimit,secretResolver,false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  int inspectionQueuedLimit,java.util.function.Function<String,String> secretResolver,
                                  boolean centralConfigurationEditingEnabled) throws Exception {
        Path yaml=temp.resolve(dataName+".yaml");
        StringBuilder entries=new StringBuilder();
        try(java.util.stream.Stream<Path> children=Files.list(packages)) {
            children.filter(Files::isDirectory)
                    .filter(path->path.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                    .sorted().forEach(path->entries.append("    ").append(path.getFileName())
                    .append(": ").append(ServerRuntimeTestSupport.yaml(path)).append("\n"));
        }
        String config="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve(dataName))
                +"\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  maxRequestBytes: 1024\n  authenticationRequired: "+authenticationRequired
                +"\n  centralConfigurationEditingEnabled: "+centralConfigurationEditingEnabled
                +"\n  authorization:\n    allowAnonymousOperations: "+(!authenticationRequired)+"\n"
                +"  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: "+inspectionQueuedLimit+"\n    timeoutMs: "+timeout
                +"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 0\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(packages)
                +"\n  entries:\n"+entries;
        ServerConfig serverConfig=ServerConfig.load(Files.writeString(yaml,config));
        return new ServerRuntime(serverConfig,libs.toString(),launcher==null?ProcessBuilder::start:launcher,
                java.time.Clock.systemUTC(),(output,archive,validator)->ArtifactArchive.build(output,archive,validator,serverConfig.archiveCompression),
                null,secretResolver);
    }

    private Path createPackage(Path root) throws Exception {
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates"));
        Files.writeString(root.resolve("package.yaml"),"schemaVersion: att-package/v1\n");
        return root;
    }

    private Path sharedRuntimeConfig() throws Exception {
        return sharedRuntimeConfig("shared-runtime-config");
    }

    private Path sharedRuntimeConfig(String name) throws Exception {
        Path root=Files.createDirectories(temp.resolve(name+"/config"));
        return Files.writeString(root.resolve("config.yaml"),"schemaVersion: att-config/v2.15\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntimeoutMs: 10000\ntestcase: {root: testcase}\ntemplates: {root: templates}\n");
    }

    private Path writeTwoTargetRuntimeConfig(Path config, ApiPrometheusFixture fixture) throws Exception {
        Map<String,Object> profile=baseProfile("base","Base profile",List.of(Map.of("id","cpu","title","CPU","unit","percent",
                "kind","gauge","query","up{instance=\"{{instance}}\"}")));
        profile.put("params",Map.of("instance",Map.of("type","promLabelValue","default","sut-a")));
        profile.put("parameters",Map.of("instance","sut-a"));
        profile.put("overridableParams",List.of("instance"));
        return writeSutMetricsConfig(config,
                List.of(prometheusSource("prom","Prometheus",fixture.url(),List.of("alice"),List.of(),List.of(),null,Map.of(),Map.of())),
                List.of(),List.of(),List.of(profile),false);
    }

    private Map<String,Object> prometheusSource(String id,String name,String url,List<String> readers,List<String> roles,
                                                List<String> tenants,String defaultTenant,Map<String,Object> limits,
                                                Map<String,Object> auth) {
        Map<String,Object> source=new LinkedHashMap<>();
        source.put("schemaVersion","att-sut-metrics-source/v1");source.put("id",id);source.put("name",name);source.put("url",url);
        if(!auth.isEmpty())source.put("auth",auth);
        source.put("readers",readers);source.put("readRoles",roles);source.put("allowedTenants",tenants);
        if(defaultTenant!=null)source.put("defaultTenant",defaultTenant);
        if(!limits.isEmpty())source.put("limits",limits);
        return source;
    }

    private Map<String,Object> baseProfile(String id,String name,List<Map<String,Object>> metrics) {
        return new LinkedHashMap<>(Map.of("schemaVersion","att-sut-metrics-profile/v1","id",id,"name",name,
                "kind","base","category","app","metrics",metrics));
    }

    private Map<String,Object> flatProfile(String id,String name,List<String> metricSourceIds,List<Map<String,Object>> targets) {
        return Map.of("schemaVersion","att-sut-metrics-profile/v2","id",id,"name",name,
                "metricSourceIds",metricSourceIds,"groupIds",List.of(),
                "allowedBindings",Map.of("environments",List.of("SIT"),"targets",targets,"tenants",List.of(),"parameters",Map.of()),
                "budgets",Map.of("maxFetches",4,"maxObservations",512,"maxArtifactBytes",1048576));
    }

    private SutMetricsService.BusinessCallContext businessCallContext(ServerRuntime runtime,Path packageRoot) throws Exception {
        byte[] configBytes=Files.readAllBytes(runtime.config.runtimeConfigPath);
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(configBytes);
        StringBuilder runtimeRevision=new StringBuilder(64);
        for(byte value:digest)runtimeRevision.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        Path configRoot=ServerConfig.runtimeConfigRoot(runtime.config.runtimeConfigPath);
        att.config.FrameworkConfig framework=new att.config.FrameworkConfigLoader().load(
                runtime.config.runtimeConfigPath,configRoot,packageRoot,"SIT");
        return new SutMetricsService.BusinessCallContext("p",packageRoot,runtime.config.runtimeConfigPath,configRoot,"SIT",
                "a".repeat(64),runtimeRevision.toString(),Set.of(),List.of(),framework);
    }

    private Path writeSutMetricsConfig(Path config,List<Map<String,Object>> sources,List<Map<String,Object>> metricSources,
                                       List<Map<String,Object>> groups,List<Map<String,Object>> profiles,
                                       boolean includeOrdersDbHelper) throws Exception {
        Path configDirectory=config.getParent();
        String environment=includeOrdersDbHelper?"    dbhelpers: [config/dbhelpers/sit/orders.yaml]\n":"    {}\n";
        StringBuilder text=new StringBuilder("schemaVersion: att-config/v2.14\nenvironment: SIT\nenvironments:\n  SIT:\n")
                .append(environment).append("  UAT: {}\ntimeoutMs: 10000\ntestcase: {root: testcase}\ntemplates: {root: templates}\nsutMetrics:\n");
        appendYamlItems(text,"dataSources",sources);
        appendYamlItems(text,"metricSources",metricSources);
        appendYamlItems(text,"groups",groups);
        appendYamlItems(text,"profiles",profiles);
        Files.createDirectories(configDirectory);
        if(includeOrdersDbHelper) {
            Path helper=Files.createDirectories(configDirectory.resolve("dbhelpers/sit")).resolve("orders.yaml");
            Files.writeString(helper,sharedDbHelper("SUT Metrics API test helper"));
        }
        return Files.writeString(config,text.toString());
    }

    private void appendYamlItems(StringBuilder text,String name,List<Map<String,Object>> items) throws Exception {
        text.append("  ").append(name).append(":");
        if(items.isEmpty()) {text.append(" []\n");return;}
        text.append('\n');
        for(Map<String,Object> item:items)
            text.append("    - ").append(ServerRuntime.JSON.writeValueAsString(item)).append('\n');
    }

    private static String sharedDbHelper(String description) {
        return "schemaVersion: att-dbhelper/v2.6\nid: orders\nname: Orders\ndescription: " + description + "\n"
                + "connection:\n  url: 'jdbc:postgresql://localhost/orders'\n  username: test\n  password: test\n"
                + "  driverClass: org.postgresql.Driver\n  readOnly: false\n  isolation: readCommitted\n"
                + "  properties: {sslMode: disable}\nstatement: {timeoutSeconds: 30}\n"
                + "transaction: {scope: case, onEnd: rollback}\nevidence: {sql: full, parameters: masked}\n";
    }

    private Path workerLibraries(String name) throws Exception {
        Path libs=Files.createDirectory(temp.resolve(name));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        return libs;
    }

    private ApiServlet servlet(ServerRuntime runtime) throws Exception {
        ServletContext context=proxy(ServletContext.class,(self,method,args)->{
            if("getAttribute".equals(method.getName()))return runtime;
            return defaultValue(method.getReturnType());
        });
        ServletConfig config=proxy(ServletConfig.class,(self,method,args)->"getServletContext".equals(method.getName())?context:defaultValue(method.getReturnType()));
        ApiServlet servlet=new ApiServlet();servlet.init(config);return servlet;
    }

    private Captured invoke(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,"alice",null);
    }

    private Captured invokeAs(ApiServlet servlet,String login,String method,String path,
                              Map<String,String> parameters,byte[] body) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,login,null,false);
    }

    private Captured invokeAs(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body,String actor) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,actor,null,false);
    }

    private Captured invokeAsAdmin(ApiServlet servlet,String login,String method,String path,
                                   Map<String,String> parameters,byte[] body) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,login,null,true);
    }

    private Captured invokeAs(ApiServlet servlet,String login,String method,String path,Map<String,String> parameters,
                              byte[] body,boolean admin) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,login,null,admin);
    }

    private Captured invokeAs(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body,
                              String login,Set<String> roles) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,login,roles,false);
    }

    private Captured invokeAs(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body,
                              String login,Set<String> roles,boolean admin) throws Exception {
        return invokeAsContentType(servlet,method,path,parameters,body,login,roles,admin,"application/json");
    }

    private Captured invokeAsContentType(ApiServlet servlet,String login,String method,String path,
                                         Map<String,String> parameters,byte[] body,String contentType) throws Exception {
        return invokeAsContentType(servlet,method,path,parameters,body,login,null,false,contentType);
    }

    private Captured invokeAsContentType(ApiServlet servlet,String method,String path,Map<String,String> parameters,
                                         byte[] body,String login,Set<String> roles,boolean admin,String contentType) throws Exception {
        return invokeAsCaptured(servlet,method,path,parameters,body,login,roles,admin,contentType,new Captured());
    }

    private Captured invokeAsCaptured(ApiServlet servlet,String method,String path,Map<String,String> parameters,
                                      byte[] body,String login,Set<String> roles,boolean admin,String contentType,
                                      Captured response) throws Exception {
        ServletContext context=servlet.getServletContext();
        HttpServletRequest request=proxy(HttpServletRequest.class,(self,called,args)->{
            switch(called.getName()) {
                case "getServletContext": return context;
                case "getAttribute": return AuthService.IDENTITY.equals(args[0])?login:null;
                case "getMethod": return method;
                case "getPathInfo": return path;
                case "getContextPath": return "";
                case "getParameter": return parameters.get(args[0]);
                case "getHeader": return "X-Request-ID".equals(args[0])?"limits-test":null;
                case "getContentType": return contentType;
                case "getUserPrincipal": return login==null?null:(Principal)()->login;
                case "isUserInRole": return roles!=null?roles.contains(args[0])
                        :admin?runtimeAdminRole(servlet,args[0]):runtimeRole(servlet,args[0]);
                case "getInputStream": return input(body);
                case "getRemoteAddr": return "127.0.0.1";
                default: return defaultValue(called.getReturnType());
            }
        });
        HttpServletResponse httpResponse=proxy(HttpServletResponse.class,(self,called,args)->{
            if("setStatus".equals(called.getName()))response.status=(Integer)args[0];
            if("getOutputStream".equals(called.getName()))return response.stream;
            if("setHeader".equals(called.getName()))response.headers.put(String.valueOf(args[0]),String.valueOf(args[1]));
            if("setContentType".equals(called.getName()))response.headers.put("Content-Type",String.valueOf(args[0]));
            return defaultValue(called.getReturnType());
        });
        servlet.service(request,httpResponse);
        return response;
    }

    private static byte[] packageZip(String... pathsAndContents) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            for(int index=0;index<pathsAndContents.length;index+=2) {
                zip.putNextEntry(new java.util.zip.ZipEntry(pathsAndContents[index]));
                zip.write(pathsAndContents[index+1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private boolean runtimeRole(ApiServlet servlet,Object role) {
        try {
            Field field=ApiServlet.class.getDeclaredField("runtime");field.setAccessible(true);
            ServerRuntime runtime=(ServerRuntime)field.get(servlet);
            return runtime.config.authorization.userRole.equals(role);
        } catch(Exception failure){throw new IllegalStateException(failure);}
    }

    private boolean runtimeAdminRole(ApiServlet servlet,Object role) {
        try {
            Field field=ApiServlet.class.getDeclaredField("runtime");field.setAccessible(true);
            ServerRuntime runtime=(ServerRuntime)field.get(servlet);
            return runtime.config.authorization.adminRole.equals(role);
        } catch(Exception failure){throw new IllegalStateException(failure);}
    }

    private static ServletInputStream input(byte[] body) {
        InputStream input=new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() throws java.io.IOException {return input.read();}
            @Override public boolean isFinished() {try{return input.available()==0;}catch(Exception unavailable){return true;}}
            @Override public boolean isReady(){return true;}
            @Override public void setReadListener(ReadListener listener) { }
        };
    }

    private static Semaphore semaphore(ServerRuntime runtime,String fieldName) throws Exception {
        Field field=ServerRuntime.class.getDeclaredField(fieldName);field.setAccessible(true);return (Semaphore)field.get(runtime);
    }

    private static java.util.List<?> inspectionWorkers(ServerRuntime runtime) throws Exception {
        Field field=ServerRuntime.class.getDeclaredField("inspectionWorkerPool");field.setAccessible(true);
        return (java.util.List<?>)field.get(runtime);
    }

    private static ReentrantLock inspectionWorkerLock(ServerRuntime runtime) throws Exception {
        Object worker=inspectionWorkers(runtime).get(0);Field field=worker.getClass().getDeclaredField("lock");field.setAccessible(true);
        return (ReentrantLock)field.get(worker);
    }

    private static java.util.concurrent.ThreadPoolExecutor validationExecutor(ServerRuntime runtime) throws Exception {
        Field field=ServerRuntime.class.getDeclaredField("validationExecutor");field.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor)field.get(runtime);
    }

    private static void stopInspectionProcesses(ServerRuntime runtime) throws Exception {
        for(Object worker:inspectionWorkers(runtime)) {
            Field processField=worker.getClass().getDeclaredField("process");processField.setAccessible(true);
            Process process=(Process)processField.get(worker);
            if(process!=null&&process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase().contains("win");}

    private static Object defaultValue(Class<?> type) {
        if(type==boolean.class)return Boolean.FALSE;
        if(type==int.class)return Integer.valueOf(0);
        if(type==long.class)return Long.valueOf(0L);
        if(type==double.class)return Double.valueOf(0.0d);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }

    private static final class Captured {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Map<String,String> headers=new HashMap<String,String>();
        int status=200;
        private final CountDownLatch writeStarted,allowWrite;
        private final AtomicInteger writes=new AtomicInteger();
        Captured(){this(null,null);}
        Captured(CountDownLatch writeStarted,CountDownLatch allowWrite) {
            this.writeStarted=writeStarted;this.allowWrite=allowWrite;
        }
        private void waitForWriteGate() throws java.io.IOException {
            if(writeStarted==null||writes.getAndIncrement()!=0)return;
            writeStarted.countDown();
            try {
                if(!allowWrite.await(10,TimeUnit.SECONDS))throw new java.io.IOException("Timed out waiting for the test response gate");
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
        }
        final ServletOutputStream stream=new ServletOutputStream() {
            @Override public boolean isReady(){return true;}
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int value) throws java.io.IOException {waitForWriteGate();output.write(value);}
            @Override public void write(byte[] bytes,int offset,int length) throws java.io.IOException {waitForWriteGate();output.write(bytes,offset,length);}
        };
        byte[] body(){return output.toByteArray();}
        String bodyText(){return new String(body(),StandardCharsets.UTF_8);}
    }

    private static final class ApiPrometheusFixture implements AutoCloseable {
        final AtomicInteger requests=new AtomicInteger();
        final AtomicReference<String> tenant=new AtomicReference<>(),query=new AtomicReference<>(),authorization=new AtomicReference<>();
        private final HttpServer server;
        private final ExecutorService executor=Executors.newCachedThreadPool();
        private final long delayMs;
        private final int responseBytes;
        private final Double rangeValue;
        private final String unavailableInstance;
        ApiPrometheusFixture(long delayMs,int responseBytes) throws Exception {
            this(delayMs,responseBytes,null,null);
        }
        ApiPrometheusFixture(long delayMs,int responseBytes,double rangeValue) throws Exception {
            this(delayMs,responseBytes,Double.valueOf(rangeValue),null);
        }
        ApiPrometheusFixture(long delayMs,int responseBytes,double rangeValue,String unavailableInstance) throws Exception {
            this(delayMs,responseBytes,Double.valueOf(rangeValue),unavailableInstance);
        }
        private ApiPrometheusFixture(long delayMs,int responseBytes,Double rangeValue,String unavailableInstance) throws Exception {
            this.delayMs=delayMs;this.responseBytes=responseBytes;this.rangeValue=rangeValue;this.unavailableInstance=unavailableInstance;
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/api/v1/query_range",this::respond);server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        private void respond(HttpExchange exchange) {
            requests.incrementAndGet();tenant.set(exchange.getRequestHeaders().getFirst("X-Scope-OrgID"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String raw=exchange.getRequestURI().getRawQuery();
            query.set(java.util.Arrays.stream(raw.split("&")).filter(part->part.startsWith("query="))
                    .map(part->java.net.URLDecoder.decode(part.substring("query=".length()),StandardCharsets.UTF_8)).findFirst().orElse(""));
            try {
                if(delayMs>0)Thread.sleep(delayMs);
                long timestamp=Instant.now().getEpochSecond()-1;
                String queryText=queryParameter(raw,"query");
                if(unavailableInstance!=null&&queryText.contains(unavailableInstance)) {
                    exchange.sendResponseHeaders(503,-1);
                    return;
                }
                byte[] body;
                if(responseBytes>0)body=new byte[responseBytes];
                else if(rangeValue!=null) {
                    String startText=queryParameter(raw,"start"),endText=queryParameter(raw,"end"),stepText=queryParameter(raw,"step");
                    long start=Instant.parse(startText).getEpochSecond(),end=Instant.parse(endText).getEpochSecond();
                    long step=Long.parseLong(stepText.substring(0,stepText.length()-1));
                    String instance=queryText.contains("sut-a")?"sut-a":"sut-b";
                    double value="sut-a".equals(instance)?rangeValue:90.0;
                    StringBuilder values=new StringBuilder();
                    for(long at=start;at<=end;at+=step) {
                        if(values.length()>0)values.append(',');
                        values.append('[').append(at).append(",\"").append(value).append("\"]");
                    }
                    body=("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"instance\":\""+instance+"\"},\"values\":["
                            +values+"]}]}}").getBytes(StandardCharsets.UTF_8);
                } else body=("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"instance\":\"sut-1\"},\"values\":[["
                        +timestamp+",\"1.0\"]]}]}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            } catch(Exception ignored) { }
            finally {exchange.close();}
        }
        private static String queryParameter(String raw,String name) {
            return java.util.Arrays.stream(raw.split("&")).filter(part->part.startsWith(name+"="))
                    .map(part->java.net.URLDecoder.decode(part.substring(name.length()+1),StandardCharsets.UTF_8))
                    .findFirst().orElseThrow();
        }
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
}
