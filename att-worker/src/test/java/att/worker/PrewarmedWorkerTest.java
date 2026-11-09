package att.worker;

import att.server.api.internal.WorkerProtocol;
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
            assertFalse(Files.exists(root.resolve("business-started")));
            Map<String,Object> request=request(root,"debug");
            Map<String,String> env=new HashMap<String,String>(System.getenv());env.put("LEASE_MARKER","assigned");
            request.put("executionEnvironment",env);
            String output=assign(process,mapper.writeValueAsString(request));
            assertEquals(0,process.exitValue(),output);
            assertEquals(1,countResults(output));
            assertTrue(output.contains("\"status\":\"PASS\""),output);
            assertTrue(Files.exists(root.resolve("business-started")),output);
            assertEquals("env-ok",new String(Files.readAllBytes(root.resolve("business-started")),StandardCharsets.UTF_8));
        } finally {process.destroyForcibly();}
    }

    @Test void coldWorkerUsesTheAcceptedSnapshotAfterTheOwnerMutatesItsSourceTree() throws Exception {
        Path root=fixture();SourceArchive accepted=sourceArchive(root);
        Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
        Files.write(root.resolve("templates/ENV/debug.yaml"),"not: [valid\n".getBytes(StandardCharsets.UTF_8));
        Process process=startCold(root);
        try {
            String output=assign(process,mapper.writeValueAsString(request));
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("\"status\":\"PASS\""),output);
            assertTrue(Files.exists(root.resolve("business-started")),output);
            assertEquals("env-ok",new String(Files.readAllBytes(root.resolve("business-started")),StandardCharsets.UTF_8));
        } finally {process.destroyForcibly();}
    }

    @Test void prewarmedWorkerUsesTheAcceptedSnapshotAfterItsOwnerMutatesPackageAndConfig() throws Exception {
        Path root=fixture();SourceArchive accepted=sourceArchive(root);
        Process process=start(root);
        try {
            ready(process);Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
            Map<String,String> env=new HashMap<String,String>(System.getenv());env.put("LEASE_MARKER","assigned");
            request.put("executionEnvironment",env);
            Files.write(root.resolve("templates/ENV/debug.yaml"),"not: [valid\n".getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("config/config.yaml"),"schemaVersion: invalid\n".getBytes(StandardCharsets.UTF_8));
            String output=assign(process,mapper.writeValueAsString(request));
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("\"status\":\"PASS\""),output);
            assertTrue(Files.exists(root.resolve("business-started")),output);
            assertEquals("env-ok",new String(Files.readAllBytes(root.resolve("business-started")),StandardCharsets.UTF_8));
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
            assertEquals("env-ok",new String(Files.readAllBytes(root.resolve("business-started")),StandardCharsets.UTF_8));
        } finally {process.destroyForcibly();}
    }

    @Test void workerRejectsSnapshotWhoseExecutionIdentityDiffersFromTheRequest() throws Exception {
        Path root=fixture();SourceArchive accepted=sourceArchive(root);
        Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
        request.put("expectedExecutionRevisionDigest","0".repeat(64));
        Process process=startCold(root);
        try {
            String output=assign(process,mapper.writeValueAsString(request));
            assertNotEquals(0,process.exitValue(),output);
            assertTrue(output.contains("WORKER_SOURCE_SNAPSHOT_INVALID"),output);
        } finally {process.destroyForcibly();}
    }

    @Test void packageLocalExecutableToolSurvivesColdAndWarmRunDebugAndLoadSnapshots() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"Package-local shell executable requires POSIX permissions");
        for(String command:Arrays.asList("debug","run"))for(Boolean warm:Arrays.asList(Boolean.FALSE,Boolean.TRUE))
            verifyPackageLocalExecutableTool(command,warm.booleanValue());
        verifyPackageLocalExecutableTool("load",false);
    }

    @Test void workerRejectsAStagedSourceMutationBetweenReadsDuringExecution() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"The mutation fixture uses a POSIX package-local executable");
        Path started=temp.resolve("mutation-tool-started.marker"),release=temp.resolve("mutation-tool-release.marker");
        Path firstRead=temp.resolve("mutation-first-read.marker"),secondRead=temp.resolve("mutation-second-read.marker");
        Path secondReadDone=temp.resolve("mutation-second-read-done.marker"),finish=temp.resolve("mutation-tool-finish.marker");
        String script="#!/bin/sh\nset -e\ntool_dir=$(CDPATH= cd \"$(dirname \"$0\")\" && pwd)\n"
                +"cat \"$tool_dir/../files/watched.txt\" > \"$3\"\ntouch \"$1\"\n"
                +"while [ ! -e \"$2\" ]; do sleep 0.01; done\n"
                +"cat \"$tool_dir/../files/watched.txt\" > \"$4\"\ntouch \"$5\"\n"
                +"while [ ! -e \"$6\" ]; do sleep 0.01; done\nprintf 'race-tool-ok\\n'\n";
        Path root=fixtureWithLocalTool(started,script,release.toString(),firstRead.toString(),secondRead.toString(),
                secondReadDone.toString(),finish.toString());
        Files.createDirectories(root.resolve("files"));Files.write(root.resolve("files/watched.txt"),"accepted-value\n".getBytes(StandardCharsets.UTF_8));
        SourceArchive accepted=sourceArchive(root);Map<String,Object> request=request(root,"debug");attachSnapshot(request,accepted);
        Process process=startCold(root);
        try {
            CompletableFuture<String> output=CompletableFuture.supplyAsync(()->{
                try{return assign(process,mapper.writeValueAsString(request));}
                catch(Exception failure){throw new CompletionException(failure);}
            });
            awaitFile(started,process,"Package-local Tool did not start");
            Path stagedPackage=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline&&stagedPackage==null) {
                try(java.util.stream.Stream<Path> directories=Files.list(temp)) {
                    stagedPackage=directories.filter(path->path.getFileName().toString().startsWith("att-source-snapshot-"))
                            .map(path->Files.isRegularFile(path.resolve("a-source/files/watched.txt"),LinkOption.NOFOLLOW_LINKS)
                                    ?path.resolve("a-source/files/watched.txt"):path.resolve("z-source/files/watched.txt"))
                            .filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)).findFirst().orElse(null);
                }
                if(stagedPackage==null)Thread.sleep(20L);
            }
            assertNotNull(stagedPackage,"the Worker must be executing from its private source snapshot");
            Path protectedSource=stagedPackage;
            assertEquals("accepted-value",new String(Files.readAllBytes(firstRead),StandardCharsets.UTF_8).trim());
            assertFalse(Files.isWritable(protectedSource),"staged source files are frozen for the Worker lifetime");
            assertThrows(java.io.IOException.class,()->Files.write(protectedSource,"direct-write".getBytes(StandardCharsets.UTF_8)));

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
        Path started=temp.resolve("load-mutation-tool-started.marker"),release=temp.resolve("load-mutation-tool-release.marker");
        Path firstRead=temp.resolve("load-mutation-first-read.marker"),secondRead=temp.resolve("load-mutation-second-read.marker");
        Path secondReadDone=temp.resolve("load-mutation-second-read-done.marker"),finish=temp.resolve("load-mutation-tool-finish.marker");
        String script="#!/bin/sh\nset -e\ntool_dir=$(CDPATH= cd \"$(dirname \"$0\")\" && pwd)\n"
                +"cat \"$tool_dir/../files/watched.txt\" > \"$3\"\ntouch \"$1\"\n"
                +"while [ ! -e \"$2\" ]; do sleep 0.01; done\n"
                +"cat \"$tool_dir/../files/watched.txt\" > \"$4\"\ntouch \"$5\"\n"
                +"while [ ! -e \"$6\" ]; do sleep 0.01; done\nprintf 'load-race-tool-ok\\n'\n";
        Path root=fixtureWithLocalTool(started,script,release.toString(),firstRead.toString(),secondRead.toString(),
                secondReadDone.toString(),finish.toString());
        Files.createDirectories(root.resolve("files"));Files.write(root.resolve("files/watched.txt"),"accepted-value\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("load/scenario.yaml"),("schemaVersion: att-load/v1.6\nworkloads:\n"
                +"  - id: package-local-tool\n    target: {type: template, id: ENV}\n"
                +"    load: {users: 1, duration: 10s}\n    execution: {thinkTime: 100ms}\n").getBytes(StandardCharsets.UTF_8));
        SourceArchive accepted=sourceArchive(root);Map<String,Object> request=request(root,"load");
        request.put("scenario","load/scenario.yaml");attachSnapshot(request,accepted);
        Process process=startCold(root);
        try {
            CompletableFuture<String> output=CompletableFuture.supplyAsync(()->{
                try{return assign(process,mapper.writeValueAsString(request));}
                catch(Exception failure){throw new CompletionException(failure);}
            });
            awaitFile(started,process,"Package-local Tool did not start during LOAD");
            Path stagedFile=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline&&stagedFile==null) {
                try(java.util.stream.Stream<Path> directories=Files.list(temp)) {
                    stagedFile=directories.filter(path->path.getFileName().toString().startsWith("att-source-snapshot-"))
                            .map(path->Files.isRegularFile(path.resolve("a-source/files/watched.txt"),LinkOption.NOFOLLOW_LINKS)
                                    ?path.resolve("a-source/files/watched.txt"):path.resolve("z-source/files/watched.txt"))
                            .filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)).findFirst().orElse(null);
                }
                if(stagedFile==null)Thread.sleep(20L);
            }
            assertNotNull(stagedFile,"The LOAD Worker must read from its private source snapshot");
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
        Path root=fixtureWithLocalTool(marker,"#!/bin/sh\nprintf 'package-tool-ok\\n' > \"$1\"\n");
        SourceArchive accepted=sourceArchive(root);
        assertTrue(Files.isExecutable(accepted.packageRoot.resolve("tools/local-tool.sh")),"the archive input retains executable metadata");
        Map<String,Object> request=request(root,command);
        if("load".equals(command)) {
            request.remove("target");request.put("scenario","load/scenario.yaml");request.put("runId","snapshot-load");
        }
        attachSnapshot(request,accepted);
        Process process=warm?start(root):startCold(root);
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
                assertFalse(Files.exists(root.resolve("business-started")));
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
        Files.write(root.resolve("config/config.yaml"),("schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\ntools:\n  env:\n"
                +"    name: Environment fixture\n    description: Assigned environment\n    command: ["+quote(java)+", '-cp', "+quote(cp)+", 'att.worker.PrewarmEnvironmentFixture', "+quote(root.resolve("business-started").toString())+"]\n"
                +"    stdoutFormat: text\n    arguments: {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/ENV/template.yaml"),("schemaVersion: att-template/v3.6\nname: ENV\ndescription: Environment fixture\nactions:\n"
                +"  call:\n    type: tool\n    call: \"#{env()}\"\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/ENV/debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n".getBytes(StandardCharsets.UTF_8));
        return root;
    }
    private Path fixtureWithLocalTool(Path marker,String script,String... additionalArguments) throws Exception {
        Path root=fixture(temp.resolve("package-"+UUID.randomUUID()));Path tools=Files.createDirectories(root.resolve("tools"));
        Path executable=tools.resolve("local-tool.sh");Files.write(executable,script.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(executable,java.util.EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));
        StringBuilder command=new StringBuilder("['./tools/local-tool.sh', ").append(quote(marker.toString()));
        for(String argument:additionalArguments)command.append(", ").append(quote(argument));
        command.append(']');
        Path config=root.resolve("config/config.yaml");
        String current=new String(Files.readAllBytes(config),StandardCharsets.UTF_8);
        current+="  local:\n    name: Package-local executable\n    description: Snapshot mode regression\n"
                +"    command: "+command+"\n    stdoutFormat: text\n    arguments: {}\n";
        Files.write(config,current.getBytes(StandardCharsets.UTF_8));
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
        final Path archive;final String sha256;final Path packageRoot;final Path configRoot;final String executionRevisionDigest;
        SourceArchive(Path archive,String sha256,Path packageRoot,Path configRoot,String executionRevisionDigest){
            this.archive=archive;this.sha256=sha256;this.packageRoot=packageRoot;this.configRoot=configRoot;
            this.executionRevisionDigest=executionRevisionDigest;
        }
    }
    private SourceArchive sourceArchive(Path root) throws Exception {
        Path packageRoot=Files.createDirectories(temp.resolve("snapshot-package-"+UUID.randomUUID()));
        Path configRoot=Files.createDirectories(temp.resolve("snapshot-config-"+UUID.randomUUID()));
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
        return new SourceArchive(archive,digestFile(archive),packageRoot,configRoot,executionRevisionDigest);
    }
    private void attachSnapshot(Map<String,Object> request,SourceArchive archive) throws Exception {
        request.put("sourceSnapshotArchivePath",archive.archive.toAbsolutePath().toString());
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
                assertEquals(1,countResults(output));assertFalse(Files.exists(root.resolve("business-started")));
                if("native".equals(variant)) {
                    String config=new String(Files.readAllBytes(root.resolve("config/config.yaml")),StandardCharsets.UTF_8);
                    Files.write(root.resolve("config/config.yaml"),config.replace("\ndbhelpers: [config/db.yaml]\n","").getBytes(StandardCharsets.UTF_8));
                }
            } finally {process.destroyForcibly();}
        }
    }
    private String assign(Process process,String request) throws Exception {
        process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
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
