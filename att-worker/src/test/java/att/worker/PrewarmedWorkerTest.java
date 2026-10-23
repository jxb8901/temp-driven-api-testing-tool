package att.worker;

import att.server.api.internal.WorkerProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
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
            assertEquals("env-ok",new String(Files.readAllBytes(root.resolve("business-started")),StandardCharsets.UTF_8));
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
        Path root=Files.createDirectories(temp.resolve("package"));
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
    private Process start(Path root) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                "-Djava.io.tmpdir="+temp.toAbsolutePath(),"-cp",System.getProperty("java.class.path"),WorkerMain.class.getName(),"--prewarmed-one-shot");
        builder.directory(root.toFile());builder.environment().clear();
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
            result.put("expectedRevisionDigest",inspector.executionRevisionDigest());
        }
        result.put("outputDirectory",root.resolve("output").toString());
        Map<String,String> target=new LinkedHashMap<String,String>();target.put("type","template");target.put("id","ENV");
        result.put("target",target);return result;
    }

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
                } else request.remove("expectedRevisionDigest");
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
    private int countResults(String output) throws Exception {
        int count=0;for(String line:output.split("\\R"))if("RESULT".equals(mapper.readTree(line).path("type").asText()))count++;
        return count;
    }
    private String quote(String value) {return "'"+value.replace("'","''")+"'";}
}
