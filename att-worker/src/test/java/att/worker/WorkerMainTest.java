package att.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import att.api.DebugRequest;
import att.api.DebugResult;
import att.api.DefaultAttService;
import att.api.LoadRequest;
import att.api.LoadResult;
import att.api.RunRequest;
import att.api.RunResult;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.io.TempDir;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkerMainTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void unsupportedProtocolVersionHasStructuredTerminalFailure() throws Exception {
        String request="{\"protocolVersion\":\"att-worker/v9\",\"jobId\":\"J-invalid\",\"command\":\"validate\",\"packageRoot\":\".\"}";
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("java.class.path"),WorkerMain.class.getName()).start();
        process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
        String stdout=read(process.getInputStream()); String stderr=read(process.getErrorStream());
        assertEquals(2,process.waitFor(),stderr);
        String[] lines=stdout.trim().split("\\R"); assertEquals(3,lines.length,stdout);
        List<String> types=new ArrayList<String>();
        for(String line:lines) { JsonNode node=mapper.readTree(line); assertTrue(node.isObject(),line); assertEquals("J-invalid",node.get("jobId").asText()); types.add(node.get("type").asText()); }
        assertEquals(java.util.Arrays.asList("STATUS","DIAGNOSTIC","RESULT"),types);
        for(String line:lines) assertTrue(line.startsWith("{"),line);
    }

    @Test void workerAndDirectApiReturnEquivalentDebugOutcomesAndKeepStdoutMachineReadable() throws Exception {
        Path root=temp.resolve("package"); Files.createDirectories(root.resolve("config")); Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("testcase"));
        try(java.util.stream.Stream<Path> paths=Files.walk(Paths.get("schemas"))) {
            for(Path source:(Iterable<Path>)paths::iterator) { Path dest=root.resolve(source); if(Files.isDirectory(source)) Files.createDirectories(dest); else Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING); }
        }
        Files.write(root.resolve("config/config.yaml"),("schemaVersion: att-config/v2.11\nenvironment: SIT\ntemplates: {root: templates}\ntestcase: {root: testcase}\ntools: {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/SIMPLE/template.yaml"),("schemaVersion: att-template/v3.4\nname: SIMPLE\ndescription: worker parity\nactions: {show: {type: log, message: worker-parity}}\n").getBytes(StandardCharsets.UTF_8));
        DebugResult direct=new DefaultAttService().debug(new DebugRequest(root,null,null,null,"api-job","template","SIMPLE",null,false));
        String request=mapper.writeValueAsString(fields("protocolVersion","att-worker/v1","jobId","worker-job","command","debug","packageRoot",root.toString(),"target",fields("type","template","id","SIMPLE")));
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),WorkerMain.class.getName()).start();
        process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
        String stdout=read(process.getInputStream()); String stderr=read(process.getErrorStream());
        assertEquals(direct.exitCode(),process.waitFor(),stderr);
        String[] lines=stdout.trim().split("\\R"); assertEquals(2,lines.length,stdout);
        JsonNode status=mapper.readTree(lines[0]), terminal=mapper.readTree(lines[1]);
        assertEquals("STATUS",status.get("type").asText()); assertEquals("RESULT",terminal.get("type").asText());
        assertEquals(direct.status(),terminal.get("result").get("status").asText());
        assertEquals(direct.exitCode(),terminal.get("result").get("exitCode").asInt());
        assertTrue(stdout.startsWith("{\"type\":\"STATUS\""),stdout);
        Process cli=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"att.FrameworkRunner","debug","template","SIMPLE","--output-dir",root.resolve("output-cli").toString(),"--format","json","--quiet").directory(root.toFile()).start();
        String cliOut=read(cli.getInputStream()); String cliErr=read(cli.getErrorStream()); assertEquals(direct.exitCode(),cli.waitFor(),cliErr+"\\n"+cliOut);
        JsonNode cliResult=mapper.readTree(cliOut); assertEquals(direct.status(),cliResult.get("status").asText()); assertEquals(direct.exitCode(),cliResult.get("exitCode").asInt());
    }


    @Test void workerAndDirectApiReturnEquivalentSmallLoadOutcomes() throws Exception {
        Path root=temp.resolve("load-package"); Files.createDirectories(root.resolve("config")); Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("testcase")); Files.createDirectories(root.resolve("load"));
        try(java.util.stream.Stream<Path> paths=Files.walk(Paths.get("schemas"))) {
            for(Path source:(Iterable<Path>)paths::iterator) { Path dest=root.resolve(source); if(Files.isDirectory(source)) Files.createDirectories(dest); else Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING); }
        }
        Files.write(root.resolve("config/config.yaml"),("schemaVersion: att-config/v2.11\nenvironment: SIT\ntemplates: {root: templates}\ntestcase: {root: testcase}\ntools: {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/SIMPLE/template.yaml"),("schemaVersion: att-template/v3.4\nname: SIMPLE\ndescription: worker load parity\nactions: {show: {type: log, message: worker-load-parity}}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("load/scenario.yaml"),("schemaVersion: att-load/v1.6\nworkloads:\n- id: worker-parity\n  target: {type: template, id: SIMPLE}\n  load: {users: 1, duration: 20ms}\n").getBytes(StandardCharsets.UTF_8));
        LoadResult direct=new DefaultAttService().load(new LoadRequest(root,null,null,null,"api-load",root.resolve("load/scenario.yaml"),null,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList()));
        String request=mapper.writeValueAsString(fields("protocolVersion","att-worker/v1","jobId","worker-load-job","command","load","packageRoot",root.toString(),"scenario","load/scenario.yaml","runId","worker-load"));
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),WorkerMain.class.getName()).start();
        process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
        String stdout=read(process.getInputStream()); String stderr=read(process.getErrorStream());
        assertEquals(direct.exitCode(),process.waitFor(),stderr);
        String[] lines=stdout.trim().split("\\R"); assertEquals(2,lines.length,stdout);
        JsonNode terminal=mapper.readTree(lines[1]);
        assertEquals("RESULT",terminal.get("type").asText());
        assertEquals(direct.status(),terminal.get("result").get("status").asText());
        assertEquals(direct.exitCode(),terminal.get("result").get("exitCode").asInt());
        assertTrue(Files.isRegularFile(root.resolve("output/load/api-load/load-summary.json")));
        assertTrue(Files.isRegularFile(root.resolve("output/load/worker-load/load-summary.json")));
        Process cli=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"att.FrameworkRunner","load","load/scenario.yaml","--run-id","cli-load","--output-dir",root.resolve("output-cli").toString(),"--format","json","--quiet").directory(root.toFile()).start();
        String cliOut=read(cli.getInputStream()); String cliErr=read(cli.getErrorStream()); assertEquals(direct.exitCode(),cli.waitFor(),cliErr+"\\n"+cliOut);
        JsonNode cliResult=mapper.readTree(cliOut); assertEquals(direct.status(),cliResult.get("status").asText()); assertEquals(direct.exitCode(),cliResult.get("exitCode").asInt());
    }

    @Test void cliWorkerAndDirectApiReturnEquivalentRunOutcomes() throws Exception {
        Path root=temp.resolve("run-package"); Files.createDirectories(root.resolve("config/tools")); Files.createDirectories(root.resolve("templates")); Files.createDirectories(root.resolve("testcase")); Files.createDirectories(root.resolve("tools"));
        try(java.util.stream.Stream<Path> paths=Files.walk(Paths.get("schemas"))) {
            for(Path source:(Iterable<Path>)paths::iterator) { Path dest=root.resolve(source); if(Files.isDirectory(source)) Files.createDirectories(dest); else Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING); }
        }
        Files.copy(Paths.get("testcase/quick_start.xlsx"),root.resolve("testcase/quick_start.xlsx"));
        Files.copy(Paths.get("testcase/quick_start.yaml"),root.resolve("testcase/quick_start.yaml"));
        Files.copy(Paths.get("testcase/quick_start.xml"),root.resolve("testcase/quick_start.xml"));
        copyTree(Paths.get("templates/QUICK_START"),root.resolve("templates/QUICK_START"));
        Files.copy(Paths.get("config/tools/sample.yaml"),root.resolve("config/tools/sample.yaml"));
        Files.copy(Paths.get("tools/get_ac_date.sh"),root.resolve("tools/get_ac_date.sh"));
        Path dispatcher=root.resolve("tools/tool_group_dispatch.sh"); Files.copy(Paths.get("tools/tool_group_dispatch.sh"),dispatcher);
        dispatcher.toFile().setExecutable(true,true); root.resolve("tools/get_ac_date.sh").toFile().setExecutable(true,true);
        Files.write(root.resolve("config/config.yaml"),("schemaVersion: att-config/v2.11\nenvironment: SIT\noutputDirectory: output\ntemplates: {root: templates}\ntestcase: {root: testcase}\ntoolGroups: [config/tools/sample.yaml]\n").getBytes(StandardCharsets.UTF_8));
        RunResult direct=new DefaultAttService().run(new RunRequest(root,null,null,null,"api-run",Collections.singletonList(Paths.get("testcase/quick_start.xlsx")),null,Collections.<String>emptySet(),Collections.<String>emptySet(),Collections.<String>emptySet(),false,false,false,false));
        String request=mapper.writeValueAsString(fields("protocolVersion","att-worker/v1","jobId","worker-run-job","command","run","packageRoot",root.toString(),"runId","worker-run","suites",Collections.singletonList("testcase/quick_start.xlsx")));
        Process worker=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),WorkerMain.class.getName()).start();
        worker.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); worker.getOutputStream().close();
        String stdout=read(worker.getInputStream()); String stderr=read(worker.getErrorStream()); assertEquals(direct.exitCode(),worker.waitFor(),stderr);
        String[] workerLines=stdout.trim().split("\\R"); assertEquals(2,workerLines.length,stdout);
        JsonNode workerResult=mapper.readTree(workerLines[1]).get("result");
        Process cli=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"att.FrameworkRunner","run","--suite","testcase/quick_start.xlsx","--run-id","cli-run","--output-dir",root.resolve("output-cli").toString(),"--format","json","--quiet").directory(root.toFile()).start();
        String cliOut=read(cli.getInputStream()); String cliErr=read(cli.getErrorStream()); assertEquals(direct.exitCode(),cli.waitFor(),cliErr+"\\n"+cliOut);
        JsonNode cliResult=mapper.readTree(cliOut);
        assertEquals(direct.status(),workerResult.get("status").asText()); assertEquals(direct.exitCode(),workerResult.get("exitCode").asInt());
        assertNotNull(cliResult.get("status"),cliOut+cliErr); assertNotNull(cliResult.get("exitCode"),cliOut+cliErr);
        assertEquals(direct.status(),cliResult.get("status").asText()); assertEquals(direct.exitCode(),cliResult.get("exitCode").asInt());
    }

    private static Map<String,Object> fields(Object... values) { Map<String,Object> result=new java.util.LinkedHashMap<String,Object>(); for(int i=0;i+1<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]); return result; }
    private static void copyTree(Path source,Path destination) throws Exception { try(java.util.stream.Stream<Path> paths=Files.walk(source)) { for(Path item:(Iterable<Path>)paths::iterator) { Path target=destination.resolve(source.relativize(item)); if(Files.isDirectory(item))Files.createDirectories(target);else Files.copy(item,target,StandardCopyOption.REPLACE_EXISTING); } } }
    private static String read(InputStream stream) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n; while((n=stream.read(buffer))>=0)out.write(buffer,0,n); return new String(out.toByteArray(),StandardCharsets.UTF_8); }
}
