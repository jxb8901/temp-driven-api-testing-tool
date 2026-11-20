package att.worker;

import att.server.api.internal.WorkerProtocol;
import att.exec.SourceSnapshotIsolation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PrewarmedWorkerTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();
    private final Map<Process,Path> errors=new HashMap<Process,Path>();

    @Test void provesReadyBeforeAssignmentThenExecutesOneJobWithItsAssignedEnvironment() throws Exception {
        Path root=fixture();
        Process process=start(root);
        try {
            JsonNode ready=ready(process);
            assertEquals("att-prewarm/v1",ready.path("protocolVersion").asText());
            assertEquals(root.toRealPath().toString(),ready.path("cwd").asText());
            assertTrue(ready.path("schemaRegistryDigest").asText().matches("[a-f0-9]{64}"));
            assertTrue(process.isAlive());
            assertFalse(Files.exists(root.resolve("output/business-started")));
            Map<String,Object> request=request(root,"debug");
            Map<String,String> env=new HashMap<String,String>(System.getenv());env.put("LEASE_MARKER","assigned");
            request.put("executionEnvironment",env);
            String output=assign(process,mapper.writeValueAsString(request));
            assertEquals(0,process.exitValue(),output);
            assertEquals(1,countResults(output));
            assertTrue(output.contains("\"status\":\"PASS\""),output);
            assertTrue(Files.exists(root.resolve("output/business-started")),output);
            assertEquals("env-ok",new String(Files.readAllBytes(root.resolve("output/business-started")),StandardCharsets.UTF_8));
        } finally {process.destroyForcibly();}
    }

    @Test void coldWorkerUsesTheAcceptedSnapshotAfterTheOwnerMutatesItsSourceTree() throws Exception {
        Path root=fixture();SourceArchive accepted=sourceArchive(root);
        Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
        org.junit.jupiter.api.Assumptions.assumeTrue(osSandboxAvailable(accepted,root.resolve("output")),"OS-enforced Worker sandbox unavailable in this host");
        Files.write(root.resolve("templates/ENV/debug.yaml"),"not: [valid\n".getBytes(StandardCharsets.UTF_8));
        Process process=startCold(root,accepted);
        try {
            String output=assign(process,mapper.writeValueAsString(request));
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("\"status\":\"PASS\""),output);
            assertTrue(Files.exists(root.resolve("output/business-started")),output);
            assertEquals("env-ok",new String(Files.readAllBytes(root.resolve("output/business-started")),StandardCharsets.UTF_8));
        } finally {process.destroyForcibly();}
    }

    @Test void prewarmedWorkerRejectsSourceSnapshotsBecauseItCannotInheritTheReadOnlyBoundary() throws Exception {
        Path root=fixture();SourceArchive accepted=sourceArchive(root);
        Process process=start(root);
        try {
            ready(process);Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
            Map<String,String> env=new HashMap<String,String>(System.getenv());env.put("LEASE_MARKER","assigned");
            request.put("executionEnvironment",env);
            Files.write(root.resolve("templates/ENV/debug.yaml"),"not: [valid\n".getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("config/config.yaml"),"schemaVersion: invalid\n".getBytes(StandardCharsets.UTF_8));
            String output=assign(process,mapper.writeValueAsString(request));
            assertNotEquals(0,process.exitValue(),output);
            assertTrue(output.contains("WORKER_SOURCE_SNAPSHOT_INVALID"),output);
            assertFalse(Files.exists(root.resolve("output/business-started")),output);
        } finally {process.destroyForcibly();}
    }

    @Test void acceptsLegacyExecutionRevisionAliasDuringWorkerUpgrade() throws Exception {
        Path root=fixture();
        Process process=start(root);
        try {
            ready(process);
            Map<String,Object> request=request(root,"debug");
            String revision=String.valueOf(request.remove("expectedExecutionRevisionDigest"));
            request.put("expectedRevisionDigest",revision);
            Map<String,String> env=new HashMap<String,String>(System.getenv());env.put("LEASE_MARKER","assigned");
            request.put("executionEnvironment",env);
            String output=assign(process,mapper.writeValueAsString(request));
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("\"status\":\"PASS\""),output);
            assertEquals("env-ok",new String(Files.readAllBytes(root.resolve("output/business-started")),StandardCharsets.UTF_8));
        } finally {process.destroyForcibly();}
    }

    @Test void workerRejectsSnapshotWhoseExecutionIdentityDiffersFromTheRequest() throws Exception {
        Path root=fixture();SourceArchive accepted=sourceArchive(root);
        Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
        org.junit.jupiter.api.Assumptions.assumeTrue(osSandboxAvailable(accepted,root.resolve("output")),"OS-enforced Worker sandbox unavailable in this host");
        request.put("expectedExecutionRevisionDigest",String.join("",java.util.Collections.nCopies(64,"0")));
        Process process=startCold(root,accepted);
        try {
            String output=assign(process,mapper.writeValueAsString(request));
            assertNotEquals(0,process.exitValue(),output);
            assertTrue(output.contains("WORKER_SOURCE_SNAPSHOT_INVALID"),output);
        } finally {process.destroyForcibly();}
    }

    @Test void packageLocalExecutableToolSurvivesColdAndWarmRunDebugAndLoadSnapshots() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"Package-local shell executable requires POSIX permissions");
        Path probe=fixture(temp.resolve("sandbox-probe-"+UUID.randomUUID()));SourceArchive source=sourceArchive(probe);
        org.junit.jupiter.api.Assumptions.assumeTrue(osSandboxAvailable(source,probe.resolve("output")),"OS-enforced Worker sandbox unavailable in this host");
        for(String command:Arrays.asList("debug","run","load"))verifyPackageLocalExecutableTool(command,false);
    }

    @Test void sameOwnerToolCannotChangePackageOrConfigClosureOrPerformItsFollowUpSideEffect() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"The OS source sandbox is not available on Windows");
        Path root=fixture(temp.resolve("sandbox-mutation-package-"+UUID.randomUUID()));
        Files.createDirectories(root.resolve("files"));
        Files.write(root.resolve("files/watched.txt"),"accepted-value\n".getBytes(StandardCharsets.UTF_8));
        Path acceptedConfig=root.resolve("config/config.yaml");
        String acceptedConfigBytes=readUtf8(acceptedConfig);
        Path started=root.resolve("output/mutation-started.marker"),release=root.resolve("output/mutation-release.marker");
        Path firstPackageRead=root.resolve("output/package-first-read.txt"),firstConfigRead=root.resolve("output/config-first-read.txt");
        Path secondPackageRead=root.resolve("output/package-second-read.txt"),secondConfigRead=root.resolve("output/config-second-read.txt");
        Path attempts=root.resolve("output/mutation-attempts.txt"),completed=root.resolve("output/mutation-completed.marker");
        Path sideEffect=root.resolve("output/mutation-side-effect.marker");
        String script="#!/bin/sh\nset +e\ntool_dir=$(CDPATH= cd \"$(dirname \"$0\")\" && pwd)\n"
                +"package=\"$tool_dir/../files/watched.txt\"; config=\"$tool_dir/../../config/config.yaml\"\n"
                +"cat \"$package\" > \"$3\" 2>/dev/null; cat \"$config\" > \"$4\" 2>/dev/null; touch \"$1\"\n"
                +"while [ ! -e \"$2\" ]; do sleep 0.01; done\nmutation_succeeded=0\n"
                +"try_mutations() { source=\"$1\"; label=\"$2\"; backup=\"$3\"; "
                +"cp \"$source\" \"$backup\" 2>/dev/null; "
                +"chmod u+w \"$source\" >/dev/null 2>&1; chmod_status=$?; "
                +"printf 'mutated-value\\n' > \"$source\" 2>/dev/null; write_status=$?; "
                +"cat \"$backup\" > \"$source\" 2>/dev/null; restore_status=$?; "
                +"mv \"$source\" \"$source.renamed\" >/dev/null 2>&1; rename_status=$?; "
                +"printf '%s:%s,%s,%s,%s\\n' \"$label\" \"$chmod_status\" \"$write_status\" "
                +"\"$restore_status\" \"$rename_status\" >> \"$4\"; "
                +"if [ \"$chmod_status\" -eq 0 ] || [ \"$write_status\" -eq 0 ] || "
                +"[ \"$restore_status\" -eq 0 ] || [ \"$rename_status\" -eq 0 ]; then mutation_succeeded=1; fi; }\n"
                +"try_mutations \"$package\" package \"$1.package-backup\" \"$7\"\n"
                +"try_mutations \"$config\" config \"$1.config-backup\" \"$7\"\n"
                +"cat \"$package\" > \"$5\" 2>/dev/null; cat \"$config\" > \"$6\" 2>/dev/null; touch \"$8\"\n"
                +"if [ \"$mutation_succeeded\" -eq 1 ]; then touch \"$9\"; exit 1; fi\nprintf 'mutation-denied\\n'\n";
        fixtureWithLocalTool(root,started,script,release.toString(),firstPackageRead.toString(),firstConfigRead.toString(),
                secondPackageRead.toString(),secondConfigRead.toString(),attempts.toString(),completed.toString(),sideEffect.toString());
        SourceArchive accepted=sourceArchive(root);
        Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
        Files.createDirectories(Paths.get(String.valueOf(request.get("outputDirectory"))));
        boolean sandboxAvailable=osSandboxAvailable(accepted,root.resolve("output"));
        Process process;
        try { process=startCold(root,accepted); }
        catch(java.io.IOException unavailable) {
            assertFalse(sandboxAvailable,"An available OS sandbox must launch the Worker process");
            assertFalse(Files.exists(sideEffect),"The Tool must not run without OS isolation");
            assertEquals("accepted-value",readUtf8(accepted.packageRoot.resolve("files/watched.txt")).trim());
            return;
        }
        try {
            CompletableFuture<String> outputFuture=CompletableFuture.supplyAsync(()->{
                try{return assign(process,mapper.writeValueAsString(request));}
                catch(Exception failure){throw new CompletionException(failure);}
            });
            try { awaitFile(started,process,"Package-local Tool did not pause between accepted source reads"); }
            catch(AssertionError unavailable) {
                if(workerError(process).contains("sandbox_apply: Operation not permitted")) {
                    assertFalse(Files.exists(sideEffect),"A failed sandbox launch cannot cause the attack follow-up side effect");
                    assertEquals("accepted-value",readUtf8(accepted.packageRoot.resolve("files/watched.txt")).trim());
                    assertEquals(acceptedConfigBytes,readUtf8(accepted.configRoot.resolve("config/config.yaml")));
                    org.junit.jupiter.api.Assumptions.assumeTrue(false,
                            "This macOS host does not permit sandbox-exec to launch the Worker Tool");
                }
                throw unavailable;
            }
            assertEquals("accepted-value",readUtf8(firstPackageRead).trim());
            assertEquals(acceptedConfigBytes,readUtf8(firstConfigRead));
            Files.createFile(release);
            String output=outputFuture.get(20,TimeUnit.SECONDS);
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"The Worker must finish after the synchronized source reads");
            assertEquals(0,process.exitValue(),output+"; stderr="+workerError(process));
            assertTrue(Files.exists(completed),"The Worker continued after all source mutation attempts were denied");
            assertFalse(Files.exists(sideEffect),"The Tool's forbidden follow-up side effect must not occur");
            Map<String,String> attemptsBySource=new HashMap<String,String>();
            for(String line:readUtf8(attempts).split("\\R")) {
                String[] pair=line.split(":",2);assertEquals(2,pair.length,line);attemptsBySource.put(pair[0],pair[1]);
            }
            for(String resource:Arrays.asList("package","config")) {
                assertTrue(attemptsBySource.containsKey(resource),"Missing mutation results for "+resource+": "+attemptsBySource);
                String[] statuses=attemptsBySource.get(resource).split(",");
                assertEquals(4,statuses.length,attemptsBySource.toString());
                for(String status:statuses)assertNotEquals("0",status,
                        resource+" source must reject chmod, write, restore, and rename: "+attemptsBySource);
            }
            assertEquals("accepted-value",readUtf8(secondPackageRead).trim(),"Package source B must still be revision A");
            assertEquals(acceptedConfigBytes,readUtf8(secondConfigRead),"Config source B must still be revision A");
            assertEquals("accepted-value",readUtf8(accepted.packageRoot.resolve("files/watched.txt")).trim(),
                    "Accepted Package bytes must remain unchanged");
            assertEquals(acceptedConfigBytes,readUtf8(accepted.configRoot.resolve("config/config.yaml")),
                    "Accepted Config bytes must remain unchanged");
            assertFalse(Files.exists(accepted.packageRoot.resolve("files/watched.txt.renamed")));
            assertFalse(Files.exists(accepted.configRoot.resolve("config/config.yaml.renamed")));
            if(sandboxAvailable) {
                assertTrue(output.contains("\"status\":\"PASS\""),output);
            }
            else assertNotEquals(0,process.exitValue(),"Unavailable OS isolation must fail before Worker execution");
        } finally {process.destroyForcibly();}
    }

    @Test void sameOwnerToolCannotRenameOrUnlinkAcceptedSourceOrPerformItsFollowUpSideEffect() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"The OS source sandbox is not available on Windows");
        for(String operation:Arrays.asList("rename","unlink")) {
            Path root=fixture(temp.resolve("sandbox-"+operation+"-package-"+UUID.randomUUID()));
            Files.createDirectories(root.resolve("files"));
            Path acceptedFile=root.resolve("files/watched.txt");
            Files.write(acceptedFile,"accepted-value\n".getBytes(StandardCharsets.UTF_8));
            Path renamedFile=root.resolve("files/watched.txt.renamed");
            Path sideEffect=root.resolve("output/"+operation+"-side-effect.marker");
            String script="#!/bin/sh\nset -e\ntool_dir=$(CDPATH= cd \"$(dirname \"$0\")\" && pwd)\n"
                    +"source=\"$tool_dir/../files/watched.txt\"\n"
                    +"if [ \"$2\" = rename ]; then mv \"$source\" \"$source.renamed\"; else rm \"$source\"; fi\n"
                    +"touch \"$1\"\n";
            fixtureWithLocalTool(root,sideEffect,script,operation);
            SourceArchive accepted=sourceArchive(root);
            Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
            Files.createDirectories(Paths.get(String.valueOf(request.get("outputDirectory"))));
            boolean sandboxAvailable=osSandboxAvailable(accepted,root.resolve("output"));
            Process process;
            try { process=startCold(root,accepted); }
            catch(java.io.IOException unavailable) {
                assertFalse(sandboxAvailable,"An available OS sandbox must launch the Worker process");
                assertFalse(Files.exists(sideEffect),"The Tool must not run without OS isolation");
                assertEquals("accepted-value",readUtf8(accepted.packageRoot.resolve("files/watched.txt")).trim());
                continue;
            }
            try {
                String output=assign(process,mapper.writeValueAsString(request));
                assertFalse(Files.exists(sideEffect),"The Tool must stop before its follow-up side effect: "+operation);
                assertTrue(Files.isRegularFile(accepted.packageRoot.resolve("files/watched.txt")),
                        "The accepted source path must remain present: "+operation);
                assertEquals("accepted-value",readUtf8(accepted.packageRoot.resolve("files/watched.txt")).trim(),
                        "Accepted source bytes must remain unchanged: "+operation);
                assertFalse(Files.exists(accepted.packageRoot.resolve("files/watched.txt.renamed")),
                        "The accepted source must not be renamed: "+operation);
                if(sandboxAvailable) assertTrue(output.contains("exitCode: 1"),output);
                else assertNotEquals(0,process.exitValue(),"Unavailable OS isolation must fail before Worker execution");
            } finally {process.destroyForcibly();}
        }
    }

    @Test void workerRejectsAStagedSourceMutationBetweenReadsDuringExecution() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"The mutation fixture uses a POSIX package-local executable");
        Path root=fixture(temp.resolve("mutation-package-"+UUID.randomUUID()));
        Path outputDirectory=root.resolve("output");Files.createDirectories(outputDirectory);
        Path started=outputDirectory.resolve("mutation-tool-started.marker"),release=outputDirectory.resolve("mutation-tool-release.marker");
        Path firstRead=outputDirectory.resolve("mutation-first-read.marker"),secondRead=outputDirectory.resolve("mutation-second-read.marker");
        Path secondReadDone=outputDirectory.resolve("mutation-second-read-done.marker"),finish=outputDirectory.resolve("mutation-tool-finish.marker");
        String script="#!/bin/sh\nset -e\ntool_dir=$(CDPATH= cd \"$(dirname \"$0\")\" && pwd)\n"
                +"cat \"$tool_dir/../files/watched.txt\" > \"$3\"\ntouch \"$1\"\n"
                +"while [ ! -e \"$2\" ]; do sleep 0.01; done\n"
                +"cat \"$tool_dir/../files/watched.txt\" > \"$4\"\ntouch \"$5\"\n"
                +"while [ ! -e \"$6\" ]; do sleep 0.01; done\nprintf 'race-tool-ok\\n'\n";
        fixtureWithLocalTool(root,started,script,release.toString(),firstRead.toString(),secondRead.toString(),
                secondReadDone.toString(),finish.toString());
        Files.createDirectories(root.resolve("files"));Files.write(root.resolve("files/watched.txt"),"accepted-value\n".getBytes(StandardCharsets.UTF_8));
        SourceArchive accepted=sourceArchive(root);Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
        org.junit.jupiter.api.Assumptions.assumeTrue(osSandboxAvailable(accepted,outputDirectory),"OS-enforced Worker sandbox unavailable in this host");
        Process process=startCold(root,accepted);
        try {
            CompletableFuture<String> output=CompletableFuture.supplyAsync(()->{
                try{return assign(process,mapper.writeValueAsString(request));}
                catch(Exception failure){throw new CompletionException(failure);}
            });
            awaitFile(started,process,"Package-local Tool did not start");
            Path protectedSource=accepted.packageRoot.resolve("files/watched.txt");
            assertEquals("accepted-value",new String(Files.readAllBytes(firstRead),StandardCharsets.UTF_8).trim());

            java.util.Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(protectedSource,LinkOption.NOFOLLOW_LINKS);
            permissions.add(PosixFilePermission.OWNER_WRITE);Files.setPosixFilePermissions(protectedSource,permissions);
            Files.write(protectedSource,"mutated-during-run\n".getBytes(StandardCharsets.UTF_8));
            String workerOutput=output.get(20,TimeUnit.SECONDS);
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"A source mutation must terminate the active Worker Tool");
            assertNotEquals(0,process.exitValue(),workerOutput);
            assertTrue(workerOutput.contains("The accepted source snapshot changed during Worker execution"),workerOutput);
            assertFalse(workerOutput.contains("\"status\":\"PASS\""),workerOutput);
            assertFalse(Files.exists(secondReadDone),"A Tool must not continue to its next source read after a staged source mutation");
            assertFalse(Files.exists(secondRead),"The changed bytes must not be consumed by a later Tool read");
        } finally {
            if(!Files.exists(release))Files.createFile(release);
            process.destroyForcibly();
        }
    }

    @Test void workerRejectsAStagedSourceMutationDuringLoadBeforeTheNextRead() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"The mutation fixture uses a POSIX package-local executable");
        Path root=fixture(temp.resolve("load-mutation-package-"+UUID.randomUUID()));
        Path outputDirectory=root.resolve("output");Files.createDirectories(outputDirectory);
        Path started=outputDirectory.resolve("load-mutation-tool-started.marker"),release=outputDirectory.resolve("load-mutation-tool-release.marker");
        Path firstRead=outputDirectory.resolve("load-mutation-first-read.marker"),secondRead=outputDirectory.resolve("load-mutation-second-read.marker");
        Path secondReadDone=outputDirectory.resolve("load-mutation-second-read-done.marker"),finish=outputDirectory.resolve("load-mutation-tool-finish.marker");
        String script="#!/bin/sh\nset -e\ntool_dir=$(CDPATH= cd \"$(dirname \"$0\")\" && pwd)\n"
                +"cat \"$tool_dir/../files/watched.txt\" > \"$3\"\ntouch \"$1\"\n"
                +"while [ ! -e \"$2\" ]; do sleep 0.01; done\n"
                +"cat \"$tool_dir/../files/watched.txt\" > \"$4\"\ntouch \"$5\"\n"
                +"while [ ! -e \"$6\" ]; do sleep 0.01; done\nprintf 'load-race-tool-ok\\n'\n";
        fixtureWithLocalTool(root,started,script,release.toString(),firstRead.toString(),secondRead.toString(),
                secondReadDone.toString(),finish.toString());
        Files.createDirectories(root.resolve("files"));Files.write(root.resolve("files/watched.txt"),"accepted-value\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("load/scenario.yaml"),("schemaVersion: att-load/v1.6\nworkloads:\n"
                +"  - id: package-local-tool\n    target: {type: template, id: ENV}\n"
                +"    load: {users: 1, duration: 10s}\n    execution: {thinkTime: 100ms}\n").getBytes(StandardCharsets.UTF_8));
        SourceArchive accepted=sourceArchive(root);Map<String,Object> request=request(root,"load");
        request.put("scenario","load/scenario.yaml");attachSnapshot(request,accepted);
        org.junit.jupiter.api.Assumptions.assumeTrue(osSandboxAvailable(accepted,outputDirectory),"OS-enforced Worker sandbox unavailable in this host");
        Process process=startCold(root,accepted);
        try {
            CompletableFuture<String> output=CompletableFuture.supplyAsync(()->{
                try{return assign(process,mapper.writeValueAsString(request));}
                catch(Exception failure){throw new CompletionException(failure);}
            });
            awaitFile(started,process,"Package-local Tool did not start during LOAD");
            Path stagedFile=accepted.packageRoot.resolve("files/watched.txt");
            assertEquals("accepted-value",new String(Files.readAllBytes(firstRead),StandardCharsets.UTF_8).trim());
            java.util.Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(stagedFile,LinkOption.NOFOLLOW_LINKS);
            permissions.add(PosixFilePermission.OWNER_WRITE);Files.setPosixFilePermissions(stagedFile,permissions);
            Files.write(stagedFile,"mutated-during-load\n".getBytes(StandardCharsets.UTF_8));

            String workerOutput=output.get(20,TimeUnit.SECONDS);
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"A staged source mutation must terminate the active LOAD Worker");
            assertNotEquals(0,process.exitValue(),workerOutput);
            assertTrue(workerOutput.contains("WORKER_SOURCE_SNAPSHOT_CHANGED"),workerOutput);
            assertFalse(workerOutput.contains("\"status\":\"PASS\""),workerOutput);
            assertFalse(Files.exists(secondReadDone),"The LOAD Tool must not continue to its next source read after mutation");
            assertFalse(Files.exists(secondRead),"The changed staged bytes must not be consumed by a later LOAD read");
        } finally {
            if(!Files.exists(release))Files.createFile(release);
            process.destroyForcibly();
        }
    }

    private void verifyPackageLocalExecutableTool(String command,boolean warm) throws Exception {
        Path marker=temp.resolve("package-tool-"+command+"-"+(warm?"warm":"cold")+".marker");
        Path root=fixture(temp.resolve("package-tool-"+command+"-"+(warm?"warm":"cold")+"-"+UUID.randomUUID()));
        marker=root.resolve("output/package-tool.marker");
        fixtureWithLocalTool(root,marker,"#!/bin/sh\nprintf 'package-tool-ok\\n' > \"$1\"\n");
        SourceArchive accepted=sourceArchive(root);
        assertTrue(Files.isExecutable(accepted.packageRoot.resolve("tools/local-tool.sh")),"the archive input retains executable metadata");
        Map<String,Object> request=request(root,command);
        if("load".equals(command)) {
            request.remove("target");request.put("scenario","load/scenario.yaml");request.put("runId","snapshot-load");
        }
        attachSnapshot(request,accepted);
        Process process=startCold(root,accepted);
        try {
            if(warm) {
                ready(process);
                Map<String,String> env=new HashMap<String,String>(System.getenv());env.put("LEASE_MARKER","assigned");
                request.put("executionEnvironment",env);
            }
            String output=assign(process,mapper.writeValueAsString(request));
            assertEquals(0,process.exitValue(),command+" "+output+" "+workerError(process));
            assertTrue(output.contains("\"status\":\"PASS\""),command+" "+output);
            assertTrue(Files.isRegularFile(marker),command+" must invoke the restored Package-local executable");
            assertEquals("package-tool-ok",new String(Files.readAllBytes(marker),StandardCharsets.UTF_8).trim());
        } finally {process.destroyForcibly();}
    }

    @Test void rejectsMultipleRequestsLoadAndWrongPackageWithoutBusinessExecution() throws Exception {
        Path root=fixture();
        for(String variant:Arrays.asList("two","load","package","registry")) {
            Process process=start(root);
            try {
                ready(process);
                Map<String,Object> request=request(root,"load".equals(variant)?"load":"debug");
                request.put("executionEnvironment",Collections.emptyMap());
                if("package".equals(variant))request.put("packageRoot",temp.toString());
                if("registry".equals(variant))request.put("expectedSchemaRegistryDigest",String.join("",Collections.nCopies(64,"0")));
                String input=mapper.writeValueAsString(request);
                if("two".equals(variant))input+="\n"+input;
                String output=assign(process,input);
                assertNotEquals(0,process.exitValue(),variant+" "+output);
                assertEquals(1,countResults(output));
                assertFalse(Files.exists(root.resolve("output/business-started")));
            } finally {process.destroyForcibly();}
        }
    }

    private Path fixture() throws Exception {
        return fixture(temp.resolve("package"));
    }
    private Path fixture(Path rootPath) throws Exception {
        Path root=Files.createDirectories(rootPath);
        Files.createDirectories(root.resolve("config"));Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates/ENV"));
        String java=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        String cp=System.getProperty("java.class.path");
        Files.write(root.resolve("config/config.yaml"),("schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\n").getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("tools"));
        Files.write(root.resolve("tools/env.yaml"),("schemaVersion: att-tool/v1\nid: env\n"
                +"name: Environment fixture\ndescription: Assigned environment\n"
                +"command: ["+quote(java)+", '-cp', "+quote(cp)+", 'att.worker.PrewarmEnvironmentFixture', "+quote(root.resolve("output/business-started").toString())+"]\n"
                +"stdoutFormat: text\narguments: {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/ENV/template.yaml"),("schemaVersion: att-template/v3.6\nname: ENV\ndescription: Environment fixture\nactions:\n"
                +"  call:\n    type: tool\n    call: \"#{env()}\"\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/ENV/debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n".getBytes(StandardCharsets.UTF_8));
        return root;
    }
    private Path fixtureWithLocalTool(Path marker,String script,String... additionalArguments) throws Exception {
        Path root=fixture(temp.resolve("package-"+UUID.randomUUID()));Path tools=Files.createDirectories(root.resolve("tools"));
        return fixtureWithLocalTool(root,marker,script,additionalArguments);
    }
    private Path fixtureWithLocalTool(Path root,Path marker,String script,String... additionalArguments) throws Exception {
        Path tools=Files.createDirectories(root.resolve("tools"));
        Path executable=tools.resolve("local-tool.sh");Files.write(executable,script.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(executable,java.util.EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));
        StringBuilder command=new StringBuilder("['./tools/local-tool.sh', ").append(quote(marker.toString()));
        for(String argument:additionalArguments)command.append(", ").append(quote(argument));
        command.append(']');
        Files.write(tools.resolve("local.yaml"),("schemaVersion: att-tool/v1\nid: local\n"
                +"name: Package-local executable\ndescription: Snapshot mode regression\n"
                +"command: "+command+"\nstdoutFormat: text\narguments: {}\n").getBytes(StandardCharsets.UTF_8));
        Path template=root.resolve("templates/ENV/template.yaml");
        String templateText=new String(Files.readAllBytes(template),StandardCharsets.UTF_8);
        templateText+="  packageTool:\n    type: tool\n    call: \"#{local()}\"\n";
        Files.write(template,templateText.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("load"));
        Files.write(root.resolve("load/scenario.yaml"),("schemaVersion: att-load/v1.6\nworkloads:\n"
                +"- id: package-local-tool\n  target: {type: template, id: ENV}\n"
                +"  load: {users: 1, duration: 300ms}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }
    private Process start(Path root) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                "-Djava.io.tmpdir="+temp.toAbsolutePath(),"-cp",System.getProperty("java.class.path"),WorkerMain.class.getName(),"--prewarmed-one-shot");
        builder.directory(root.toFile());builder.environment().clear();
        Path error=temp.resolve(UUID.randomUUID()+".stderr");builder.redirectError(error.toFile());
        Process process=builder.start();errors.put(process,error);return process;
    }
    private Process startCold(Path root) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                "-Djava.io.tmpdir="+temp.toAbsolutePath(),"-cp",System.getProperty("java.class.path"),WorkerMain.class.getName());
        builder.directory(root.toFile());builder.environment().put("LEASE_MARKER","assigned");
        Path error=temp.resolve(UUID.randomUUID()+".stderr");builder.redirectError(error.toFile());
        Process process=builder.start();errors.put(process,error);return process;
    }
    private Process startCold(Path root,SourceArchive source) throws Exception {
        Path output=root.resolve("output");Files.createDirectories(output);
        List<String> command=new ArrayList<String>(Arrays.asList(
                Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                "-Djava.io.tmpdir="+temp.toAbsolutePath(),
                "-cp",System.getProperty("java.class.path"),WorkerMain.class.getName()));
        command=new ArrayList<String>(SourceSnapshotIsolation.wrap(command,source.directory,source.archive,output));
        ProcessBuilder builder=new ProcessBuilder(command);
        builder.directory(root.toFile());builder.environment().put("LEASE_MARKER","assigned");
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("linux"))builder.environment().put("TMPDIR","/tmp");
        Path error=temp.resolve(UUID.randomUUID()+".stderr");builder.redirectError(error.toFile());
        Process process=builder.start();errors.put(process,error);return process;
    }
    private boolean osSandboxAvailable(SourceArchive source,Path output) throws Exception {
        Files.createDirectories(output);
        List<String> command=new ArrayList<String>(Arrays.asList(
                Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                "-Djava.io.tmpdir="+temp.toAbsolutePath(),"-version"));
        List<String> isolated;
        try { isolated=SourceSnapshotIsolation.wrap(command,source.directory,source.archive,output); }
        catch(java.io.IOException unavailable) { return false; }
        ProcessBuilder builder=new ProcessBuilder(isolated);
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("linux"))builder.environment().put("TMPDIR","/tmp");
        builder.redirectError(temp.resolve(UUID.randomUUID()+".sandbox-probe.stderr").toFile());
        Process process=builder.start();
        if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();return false;}
        return process.exitValue()==0;
    }
    private String readUtf8(Path path) throws java.io.IOException {
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
    private JsonNode ready(Process process) throws Exception {
        CompletableFuture<String> frame=CompletableFuture.supplyAsync(()->{
            try {WorkerProtocol.Frame value=WorkerProtocol.read(process.getInputStream(),8192);return value==null?"{}":value.text();}
            catch(Exception failure){throw new CompletionException(failure);}
        });
        JsonNode value=mapper.readTree(frame.get(10,TimeUnit.SECONDS));
        assertEquals("READY",value.path("type").asText(),new String(Files.readAllBytes(errors.get(process)),StandardCharsets.UTF_8));return value;
    }
    private Map<String,Object> request(Path root,String command) throws Exception {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("protocolVersion","att-worker/v1");result.put("jobId","prewarm-test");result.put("command",command);
        result.put("packageRoot",root.toString());result.put("runId","test");
        try(att.resource.PackageResourceInspector inspector=new att.resource.PackageResourceInspector(root,root.resolve("config/config.yaml"),null,
                Collections.<String>emptyList(),65536,262144)) {
            result.put("expectedExecutionRevisionDigest",inspector.executionRevisionDigest());
        }
        result.put("outputDirectory",root.resolve("output").toString());
        result.put("registeredPackageId","p");
        Map<String,String> target=new LinkedHashMap<String,String>();target.put("type","template");target.put("id","ENV");
        result.put("target",target);return result;
    }

    private static final class SourceArchive {
        final Path archive;final String sha256;final Path directory;final Path packageRoot;final Path configRoot;final String executionRevisionDigest;
        SourceArchive(Path archive,String sha256,Path directory,Path packageRoot,Path configRoot,String executionRevisionDigest){
            this.archive=archive;this.sha256=sha256;this.packageRoot=packageRoot;this.configRoot=configRoot;
            this.directory=directory;
            this.executionRevisionDigest=executionRevisionDigest;
        }
    }
    private SourceArchive sourceArchive(Path root) throws Exception {
        Path directory=Files.createDirectories(temp.resolve("worker-source-"+UUID.randomUUID()));
        Path packageRoot=Files.createDirectories(directory.resolve("package"));
        Path configRoot=Files.createDirectories(directory.resolve("config"));
        try(java.util.stream.Stream<Path> entries=Files.walk(root)) {
            for(Path source:(Iterable<Path>)entries::iterator)if(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)) {
                Path relative=root.relativize(source);
                if(relative.getNameCount()>0&&"config".equals(relative.getName(0).toString())) {
                    Path target=configRoot.resolve(relative);Files.createDirectories(target.getParent());Files.copy(source,target,StandardCopyOption.COPY_ATTRIBUTES);
                } else {Path target=packageRoot.resolve(relative);Files.createDirectories(target.getParent());Files.copy(source,target,StandardCopyOption.COPY_ATTRIBUTES);}
            }
        }
        Map<String,Object> manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",WorkerSourceSnapshot.VERSION);
        manifest.put("packageId","p");manifest.put("packageRevision","R00000000000000000000000000000001");
        manifest.put("configRevision","R00000000000000000000000000000002");manifest.put("configurationPath","config/config.yaml");
        manifest.put("packageTreeDigest",treeDigest(packageRoot));manifest.put("configTreeDigest",treeDigest(configRoot));
        java.util.List<String> packageExecutables=executablePaths(packageRoot),configExecutables=executablePaths(configRoot);
        manifest.put("packageExecutableFiles",packageExecutables);manifest.put("configExecutableFiles",configExecutables);
        manifest.put("packageExecutableDigest",executableDigest(packageExecutables));manifest.put("configExecutableDigest",executableDigest(configExecutables));
        String executionRevisionDigest;
        try(att.resource.PackageResourceInspector inspector=new att.resource.PackageResourceInspector(packageRoot,
                configRoot.resolve("config/config.yaml"),null,Collections.<String>emptyList(),65536,262144)) {
            executionRevisionDigest=inspector.executionRevisionDigest();
        }
        manifest.put("executionRevisionDigest",executionRevisionDigest);
        manifest.put("logicalPaths",Arrays.asList("templates/ENV/template.yaml","templates/ENV/debug.yaml","config/config.yaml"));
        manifest.put("dependencies",Collections.emptyList());manifest.put("packageSymlinks",Collections.emptyMap());manifest.put("configSymlinks",Collections.emptyMap());
        byte[] compiledPlans=att.template.FileExpressionResolver.FileExpressionSnapshot.empty(packageRoot).serialize();
        manifest.put("compiledFilePlansSha256",digest(compiledPlans));
        Path archive=temp.resolve("accepted-"+UUID.randomUUID()+".zip");
        try(OutputStream file=Files.newOutputStream(archive);ZipOutputStream zip=new ZipOutputStream(file,StandardCharsets.UTF_8)) {
            writeTree(zip,packageRoot,"package");writeTree(zip,configRoot,"config");
            zip.putNextEntry(new ZipEntry("file-plans.bin"));zip.write(compiledPlans);zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(mapper.writeValueAsBytes(manifest));zip.closeEntry();
        }
        return new SourceArchive(archive,digestFile(archive),directory,packageRoot,configRoot,executionRevisionDigest);
    }
    private void attachSnapshot(Map<String,Object> request,SourceArchive archive) throws Exception {
        request.put("protocolVersion","att-worker/v2");
        request.put("sourceSnapshotArchivePath",archive.archive.toAbsolutePath().toString());
        request.put("sourceSnapshotDirectory",archive.directory.toAbsolutePath().toString());
        request.put("sourceSnapshotArchiveSha256",archive.sha256);request.put("sourceSnapshotConfigPath","config/config.yaml");
        request.put("expectedExecutionRevisionDigest",archive.executionRevisionDigest);
    }
    private static void writeTree(ZipOutputStream zip,Path root,String prefix)throws Exception {
        List<Path> files=new ArrayList<Path>();try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            paths.filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)).forEach(files::add);
        }
        Collections.sort(files,Comparator.comparing(path->root.relativize(path).toString().replace('\\','/')));
        for(Path path:files) {String relative=root.relativize(path).toString().replace('\\','/');zip.putNextEntry(new ZipEntry(prefix+"/"+relative));Files.copy(path,zip);zip.closeEntry();}
    }
    private static String treeDigest(Path root)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");List<Path> files=new ArrayList<Path>();
        try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)).forEach(files::add);}
        Collections.sort(files,Comparator.comparing(path->root.relativize(path).toString().replace('\\','/')));
        for(Path file:files) {String relative=root.relativize(file).toString().replace('\\','/');digest.update(relative.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update((byte)'F');digest.update(hexBytes(digestFile(file)));digest.update((byte)0xff);}
        return hex(digest.digest());
    }
    private static List<String> executablePaths(Path root)throws Exception {
        List<Path> files=new ArrayList<Path>();try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            paths.filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.isExecutable(path)).forEach(files::add);
        }
        Collections.sort(files,Comparator.comparing(path->root.relativize(path).toString().replace('\\','/')));
        List<String> result=new ArrayList<String>();for(Path file:files)result.add(root.relativize(file).toString().replace('\\','/'));
        return result;
    }
    private static String executableDigest(List<String> paths)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String path:paths){digest.update(path.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
        return hex(digest.digest());
    }
    private static String digestFile(Path file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(java.io.InputStream input=Files.newInputStream(file)){byte[] buffer=new byte[8192];int read;while((read=input.read(buffer))>=0)digest.update(buffer,0,read);}return hex(digest.digest());
    }
    private static String digest(byte[] bytes)throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static byte[] hexBytes(String value) {byte[] result=new byte[value.length()/2];for(int i=0;i<result.length;i++)result[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return result;}
    private static String hex(byte[] bytes) {StringBuilder result=new StringBuilder(bytes.length*2);for(byte value:bytes)result.append(String.format("%02x",value&255));return result.toString();}

    @Test void resourceClientOrChangedRevisionCannotExecuteInsideWarmJvm() throws Exception {
        Path root=fixture();
        for(String variant:Arrays.asList("native","stale","missing-revision")) {
            Process process=start(root);
            try {
                ready(process);Map<String,Object> request=request(root,"debug");
                request.put("executionEnvironment",Collections.emptyMap());
                if("native".equals(variant)) {
                    Files.write(root.resolve("config/db.yaml"),("schemaVersion: att-dbhelper/v2.6\nid: db\nname: DB\ndescription: Cold native client\n"
                            +"connection: {url: 'jdbc:unavailable:no-connection'}\n").getBytes(StandardCharsets.UTF_8));
                    Files.write(root.resolve("config/config.yaml"),"\ndbhelpers: [config/db.yaml]\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                    request=request(root,"debug");request.put("executionEnvironment",Collections.emptyMap());
                } else if("stale".equals(variant)) {
                    Files.write(root.resolve("templates/ENV/template.yaml"),"\n# revision changed after eligibility\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                } else request.remove("expectedExecutionRevisionDigest");
                String output=assign(process,mapper.writeValueAsString(request));
                assertNotEquals(0,process.exitValue(),variant+" "+output);
                assertEquals(1,countResults(output));assertFalse(Files.exists(root.resolve("output/business-started")));
                if("native".equals(variant)) {
                    String config=new String(Files.readAllBytes(root.resolve("config/config.yaml")),StandardCharsets.UTF_8);
                    Files.write(root.resolve("config/config.yaml"),config.replace("\ndbhelpers: [config/db.yaml]\n","").getBytes(StandardCharsets.UTF_8));
                }
            } finally {process.destroyForcibly();}
        }
    }
    private String assign(Process process,String request) throws Exception {
        try {
            process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
        } catch(java.io.IOException unavailable) {
            if(process.isAlive())throw unavailable;
        }
        CompletableFuture<String> output=CompletableFuture.supplyAsync(()->{
            try {java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=process.getInputStream().read(buffer))!=-1)bytes.write(buffer,0,count);
                return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            }catch(Exception failure){throw new CompletionException(failure);}
        });
        assertTrue(process.waitFor(15,TimeUnit.SECONDS));return output.get(5,TimeUnit.SECONDS);
    }
    private void awaitFile(Path file,Process process,String message)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline&&!Files.exists(file)&&process.isAlive())Thread.sleep(20L);
        assertTrue(Files.exists(file),message+"; stderr="+workerError(process));
    }
    private String workerError(Process process)throws Exception {
        Path error=errors.get(process);return error==null?"":new String(Files.readAllBytes(error),StandardCharsets.UTF_8);
    }
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
    private int countResults(String output) throws Exception {
        int count=0;for(String line:output.split("\\R"))if("RESULT".equals(mapper.readTree(line).path("type").asText()))count++;
        return count;
    }
    private String quote(String value) {return "'"+value.replace("'","''")+"'";}
}
