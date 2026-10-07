package att.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import att.api.DebugRequest;
import att.api.DebugResult;
import att.api.DefaultAttService;
import att.api.ExecutionEvent;
import att.api.LoadRequest;
import att.api.LoadResult;
import att.api.RunRequest;
import att.api.RunResult;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    @Test void terminatingWorkerStopsAnActiveCommandBackedTool() throws Exception {
        Path root=temp.resolve("worker-tool-termination");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("templates/SLOW"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("tools"));
        try(java.util.stream.Stream<Path> paths=Files.walk(Paths.get("schemas"))) {
            for(Path source:(Iterable<Path>)paths::iterator) {
                Path dest=root.resolve(source);
                if(Files.isDirectory(source)) Files.createDirectories(dest);
                else Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path parentStarted=root.resolve("tool-parent-started.marker");
        Path started=root.resolve("tool-descendant-started.marker"), completed=root.resolve("tool-descendant-completed.marker");
        String javaExecutable=Paths.get(System.getProperty("java.home"),"bin",isWindows()?"java.exe":"java").toString();
        String classpath=System.getProperty("java.class.path");
        Path config=root.resolve("config/config.yaml");
        Files.write(config,("schemaVersion: att-config/v2.11\nenvironment: SIT\noutputDirectory: output\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\ntools:\n"
                +"  slow:\n    name: Slow command\n    description: Worker termination regression\n"
                +"    command: ["+yamlQuote(javaExecutable)+", '-cp', "+yamlQuote(classpath)+", 'att.worker.ToolProcessFixture', 'parent', "
                +yamlQuote(parentStarted)+", "+yamlQuote(started)+", "+yamlQuote(completed)+"]\n"
                +"    stdoutFormat: text\n    arguments: {}\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/SLOW/template.yaml"),("schemaVersion: att-template/v3.4\nname: SLOW\n"
                +"description: command termination boundary\nactions:\n  invoke:\n    type: tool\n    call: \"#{slow()}\"\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/SLOW/debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n".getBytes(StandardCharsets.UTF_8));
        String request=mapper.writeValueAsString(fields("protocolVersion","att-worker/v1","jobId","terminate-tool-job",
                "command","debug","packageRoot",root.toString(),"target",fields("type","template","id","SLOW")));
        Path workerError=root.resolve("worker.stderr");
        Process worker=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("java.class.path"),WorkerMain.class.getName()).redirectError(workerError.toFile()).start();
        worker.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        worker.getOutputStream().close();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline&&!Files.exists(started)&&worker.isAlive()) Thread.sleep(25L);
        if(!Files.exists(started)) {
            boolean exited=worker.waitFor(1,java.util.concurrent.TimeUnit.SECONDS);
            if(!exited) {
                worker.destroyForcibly();
                exited=worker.waitFor(2,java.util.concurrent.TimeUnit.SECONDS);
            }
            String out=read(worker.getInputStream()), err=read(worker.getErrorStream());
            assertTrue(Files.exists(started),"Worker never started the descendant Tool process. exit="
                    +(exited?worker.exitValue():"still running")+"\n"+out+"\n"+err);
        }
        assertTrue(Files.exists(parentStarted),"Command-backed Tool parent did not start");
        worker.destroy();
        if(!worker.waitFor(4,java.util.concurrent.TimeUnit.SECONDS)) {
            worker.destroyForcibly();
            assertTrue(worker.waitFor(3,java.util.concurrent.TimeUnit.SECONDS),"Worker process could not be stopped");
        }
        if (!isWindowsJava8()) {
            Thread.sleep(7000L);
            assertFalse(Files.exists(completed),"ATT-started Tool command completed after its Worker was terminated. stderr="
                    +new String(Files.readAllBytes(workerError),StandardCharsets.UTF_8));
        }
    }

    @Test void terminatingActiveWorkerLeavesPackageAuthoredFilesUntouched() throws Exception {
        Path root=temp.resolve("terminated-worker-package");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("load"));
        try(java.util.stream.Stream<Path> paths=Files.walk(Paths.get("schemas"))) {
            for(Path source:(Iterable<Path>)paths::iterator) {
                Path dest=root.resolve(source);
                if(Files.isDirectory(source)) Files.createDirectories(dest);
                else Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path config=root.resolve("config/config.yaml");
        Path template=root.resolve("templates/SIMPLE/template.yaml");
        Path scenario=root.resolve("load/long-running.yaml");
        Files.write(config,("schemaVersion: att-config/v2.11\nenvironment: SIT\noutputDirectory: output\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\ntools: {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(template,("schemaVersion: att-template/v3.4\nname: SIMPLE\ndescription: termination boundary\n"
                +"actions: {show: {type: log, message: worker-termination}}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(scenario,("schemaVersion: att-load/v1.6\nworkloads:\n- id: long-running\n"
                +"  target: {type: template, id: SIMPLE}\n  load: {users: 1, duration: 30s}\n")
                .getBytes(StandardCharsets.UTF_8));
        byte[] originalConfig=Files.readAllBytes(config);
        byte[] originalTemplate=Files.readAllBytes(template);
        byte[] originalScenario=Files.readAllBytes(scenario);
        String request=mapper.writeValueAsString(fields("protocolVersion","att-worker/v1","jobId","terminate-job",
                "command","load","packageRoot",root.toString(),"scenario",root.relativize(scenario).toString(),
                "runId","terminate-run"));
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("java.class.path"),WorkerMain.class.getName()).start();
        process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        BufferedReader stdout=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
        String statusLine=stdout.readLine();
        assertNotNull(statusLine,"Worker did not initialize its protocol stream");
        assertEquals("STATUS",mapper.readTree(statusLine).get("type").asText());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        boolean sawProgress=false;
        while(System.nanoTime()<deadline&&!sawProgress) {
            if(stdout.ready()) {
                String line=stdout.readLine();
                if(line==null) break;
                sawProgress="PROGRESS".equals(mapper.readTree(line).get("type").asText());
            } else Thread.sleep(25L);
        }
        process.destroy();
        if(!process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS),"Worker process could not be forcibly stopped");
        }
        assertTrue(sawProgress,"Worker never entered its active operation before termination");
        assertArrayEquals(originalConfig,Files.readAllBytes(config));
        assertArrayEquals(originalTemplate,Files.readAllBytes(template));
        assertArrayEquals(originalScenario,Files.readAllBytes(scenario));
        try(java.util.stream.Stream<Path> children=Files.list(root)) {
            assertEquals(new java.util.HashSet<String>(java.util.Arrays.asList("config","templates","testcase","load","schemas","output")),
                    children.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertTrue(Files.isDirectory(root.resolve("output")));
    }
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

    @Test void malformedEmptyTruncatedAndMultipleRequestsHaveStructuredTerminalFailure() throws Exception {
        for (String request : java.util.Arrays.asList("", "{\"protocolVersion\":", "{not-json",
                "{\"protocolVersion\":\"att-worker/v1\",\"jobId\":\"multi\",\"command\":\"validate\",\"packageRoot\":\".\"} {}")) {
            Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                    System.getProperty("java.class.path"),WorkerMain.class.getName()).start();
            process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
            String stdout=read(process.getInputStream()); String stderr=read(process.getErrorStream());
            assertEquals(2,process.waitFor(),stderr);
            String[] lines=stdout.trim().split("\\R"); assertEquals(3,lines.length,stdout);
            JsonNode status=mapper.readTree(lines[0]), diagnostic=mapper.readTree(lines[1]), result=mapper.readTree(lines[2]);
            assertEquals("STATUS",status.get("type").asText(),stdout);
            assertEquals("DIAGNOSTIC",diagnostic.get("type").asText(),stdout);
            assertEquals("WORKER_REQUEST_INVALID",diagnostic.get("code").asText(),stdout);
            assertEquals("RESULT",result.get("type").asText(),stdout);
            assertEquals(2,result.get("exitCode").asInt(),stdout);
            assertFalse(stdout.contains("Exception"),stdout);
        }
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
        String request=mapper.writeValueAsString(fields("protocolVersion","att-worker/v1","jobId","worker-job","command","debug","packageRoot",root.toString(),"outputDirectory",root.resolve("output-worker").toString(),"target",fields("type","template","id","SIMPLE")));
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),WorkerMain.class.getName()).start();
        process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
        String stdout=read(process.getInputStream()); String stderr=read(process.getErrorStream());
        assertEquals(direct.exitCode(),process.waitFor(),stderr);
        String[] lines=stdout.trim().split("\\R"); assertTrue(lines.length>2,stdout);
        JsonNode status=mapper.readTree(lines[0]), terminal=mapper.readTree(lines[lines.length-1]);
        assertEquals("STATUS",status.get("type").asText()); assertEquals("RESULT",terminal.get("type").asText());
        assertTrue(java.util.Arrays.stream(lines).map(line -> { try { return mapper.readTree(line).get("type").asText(); } catch(Exception error) { throw new IllegalStateException(error); } }).anyMatch("PROGRESS"::equals),stdout);
        assertTrue(java.util.Arrays.stream(lines).map(line -> { try { return mapper.readTree(line).get("type").asText(); } catch(Exception error) { throw new IllegalStateException(error); } }).anyMatch("LOG"::equals),stdout);
        assertEquals("worker-job",terminal.get("jobId").asText());
        assertEquals(direct.status(),terminal.get("result").get("status").asText());
        assertEquals(direct.exitCode(),terminal.get("result").get("exitCode").asInt());
        assertEquals(direct.executionId(),terminal.get("result").get("executionId").asText());
        assertFalse(terminal.get("result").has("jobId"));
        assertTrue(stdout.startsWith("{\"type\":\"STATUS\""),stdout);
        Process cli=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"att.FrameworkRunner","debug","template","SIMPLE","--output-dir",root.resolve("output-cli").toString(),"--format","json","--quiet").directory(root.toFile()).start();
        String cliOut=read(cli.getInputStream()); String cliErr=read(cli.getErrorStream()); assertEquals(direct.exitCode(),cli.waitFor(),cliErr+"\\n"+cliOut);
        JsonNode cliResult=mapper.readTree(cliOut); assertEquals(direct.status(),cliResult.get("status").asText()); assertEquals(direct.exitCode(),cliResult.get("exitCode").asInt());
        assertEquals(direct.executionId(),cliResult.get("executionId").asText());
        assertEquals(direct.diagnostics().size(),cliResult.get("diagnostics").size());
        DebugResult failedDirect=new DefaultAttService().debug(new DebugRequest(root,null,null,null,null,"template","MISSING",null,false));
        Process failedCli=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("java.class.path"),"att.FrameworkRunner","debug","template","MISSING","--output-dir",
                root.resolve("output-failed-cli").toString(),"--format","json","--quiet").directory(root.toFile()).start();
        String failedOut=read(failedCli.getInputStream()); String failedErr=read(failedCli.getErrorStream());
        assertEquals(failedDirect.exitCode(),failedCli.waitFor(),failedErr+"\\n"+failedOut);
        JsonNode failedResult=mapper.readTree(failedOut);
        JsonNode cliDiagnostic=failedResult.get("diagnostics").get(0);
        assertEquals(failedDirect.diagnostics().get(0).code(),cliDiagnostic.get("code").asText());
        assertEquals(failedDirect.diagnostics().get(0).summary(),cliDiagnostic.get("summary").asText());
        assertEquals(failedDirect.diagnostics().get(0).detail(),cliDiagnostic.get("detail").asText());
        assertEquals(failedDirect.diagnostics().get(0).suggestion(),cliDiagnostic.get("suggestion").asText());
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
        String[] lines=stdout.trim().split("\\R"); assertTrue(lines.length>2,stdout);
        JsonNode terminal=mapper.readTree(lines[lines.length-1]);
        assertEquals("RESULT",terminal.get("type").asText());
        assertEquals("worker-load-job",terminal.get("jobId").asText());
        assertEquals(direct.status(),terminal.get("result").get("status").asText());
        assertEquals(direct.exitCode(),terminal.get("result").get("exitCode").asInt());
        assertEquals("worker-load",terminal.get("result").get("executionId").asText());
        assertFalse(terminal.get("result").has("jobId"));
        assertEquals(toMap(direct.summary().get("metrics")).keySet(),toMap(terminal.get("result").get("summary").get("metrics")).keySet());
        assertTrue(Files.isRegularFile(root.resolve("output/load/api-load/load-summary.json")));
        assertTrue(Files.isRegularFile(root.resolve("output/load/worker-load/load-summary.json")));
        Process cli=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"att.FrameworkRunner","load","load/scenario.yaml","--run-id","cli-load","--output-dir",root.resolve("output-cli").toString(),"--format","json","--quiet").directory(root.toFile()).start();
        String cliOut=read(cli.getInputStream()); String cliErr=read(cli.getErrorStream()); assertEquals(direct.exitCode(),cli.waitFor(),cliErr+"\\n"+cliOut);
        JsonNode cliResult=mapper.readTree(cliOut); assertEquals(direct.status(),cliResult.get("status").asText()); assertEquals(direct.exitCode(),cliResult.get("exitCode").asInt());
        assertEquals("cli-load",cliResult.get("executionId").asText());
        assertEquals(toMap(direct.summary().get("metrics")).keySet(),toMap(cliResult.get("metrics")).keySet());
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
        List<ExecutionEvent> directEvents=new ArrayList<ExecutionEvent>();
        RunResult direct=new DefaultAttService().run(new RunRequest(root,null,null,null,null,
                Collections.singletonList(Paths.get("testcase/quick_start.xlsx")),null,Collections.<String>emptySet(),
                Collections.<String>emptySet(),Collections.<String>emptySet(),false,false,false,false,
                null,"reject",false,directEvents::add));
        assertNotNull(direct.executionId()); assertFalse(direct.executionId().trim().isEmpty());
        assertEquals(1,directEvents.stream().filter(event->"RUN_VALIDATION_SUMMARY".equals(event.data().get("event"))).count());
        assertEquals(0,directEvents.stream().filter(event->event.type()==ExecutionEvent.Type.LOG
                && "RUN_VALIDATION_SUMMARY".equals(event.data().get("event"))).count());
        String request=mapper.writeValueAsString(fields("protocolVersion","att-worker/v1","jobId","worker-run-job","command","run","packageRoot",root.toString(),"runId","worker-run","suites",Collections.singletonList("testcase/quick_start.xlsx")));
        Process worker=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),WorkerMain.class.getName()).start();
        worker.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); worker.getOutputStream().close();
        String stdout=read(worker.getInputStream()); String stderr=read(worker.getErrorStream()); assertEquals(direct.exitCode(),worker.waitFor(),stderr);
        String[] workerLines=stdout.trim().split("\\R"); assertTrue(workerLines.length>2,stdout);
        JsonNode terminal=mapper.readTree(workerLines[workerLines.length-1]);
        JsonNode workerResult=terminal.get("result");
        assertEquals("RESULT",terminal.get("type").asText());
        assertEquals("worker-run-job",terminal.get("jobId").asText());
        assertTrue(java.util.Arrays.stream(workerLines).map(line -> { try { return mapper.readTree(line).get("type").asText(); } catch(Exception error) { throw new IllegalStateException(error); } }).anyMatch("PROGRESS"::equals),stdout);
        assertTrue(java.util.Arrays.stream(workerLines).map(line -> { try { return mapper.readTree(line).get("type").asText(); } catch(Exception error) { throw new IllegalStateException(error); } }).anyMatch("LOG"::equals),stdout);
        assertEquals(1,java.util.Arrays.stream(workerLines).map(line -> { try { return mapper.readTree(line); } catch(Exception error) { throw new IllegalStateException(error); } })
                .filter(event->"PROGRESS".equals(event.get("type").asText())&&"RUN_VALIDATION_SUMMARY".equals(event.path("event").asText())).count(),stdout);
        assertEquals(0,java.util.Arrays.stream(workerLines).map(line -> { try { return mapper.readTree(line); } catch(Exception error) { throw new IllegalStateException(error); } })
                .filter(event->"LOG".equals(event.get("type").asText())&&"RUN_VALIDATION_SUMMARY".equals(event.path("event").asText())).count(),stdout);
        assertEquals("worker-run",workerResult.get("executionId").asText());
        assertFalse(workerResult.has("jobId"));
        Process cli=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"att.FrameworkRunner","run","--suite","testcase/quick_start.xlsx","--run-id","cli-run","--output-dir",root.resolve("output-cli").toString(),"--format","json","--quiet").directory(root.toFile()).start();
        String cliOut=read(cli.getInputStream()); String cliErr=read(cli.getErrorStream()); assertEquals(direct.exitCode(),cli.waitFor(),cliErr+"\\n"+cliOut);
        JsonNode cliResult=mapper.readTree(cliOut);
        assertEquals(direct.status(),workerResult.get("status").asText()); assertEquals(direct.exitCode(),workerResult.get("exitCode").asInt());
        assertEquals(direct.summary().keySet(),toMap(workerResult.get("summary")).keySet());
        assertEquals(direct.summary().get("total"),workerResult.get("summary").get("total").asLong());
        assertEquals(java.util.Arrays.asList("total", "passed", "failed", "error", "skipped", "invalid", "report"),
                iterableNames(cliResult.fieldNames()), cliOut+cliErr);
        assertEquals(((Number)direct.summary().get("total")).longValue(),cliResult.get("total").asLong());
        assertEquals(((Number)direct.summary().get("passed")).longValue(),cliResult.get("passed").asLong());
        assertFalse(cliResult.has("status")); assertFalse(cliResult.has("executionId"));
        assertTrue(cliResult.get("report").asText().endsWith("/cli-run/report/index.html"));
    }

    private static Map<String,Object> fields(Object... values) { Map<String,Object> result=new java.util.LinkedHashMap<String,Object>(); for(int i=0;i+1<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]); return result; }
    private static Map<String,Object> toMap(JsonNode node) { return new ObjectMapper().convertValue(node,Map.class); }
    private static java.util.List<String> iterableNames(java.util.Iterator<String> names) { java.util.List<String> result=new java.util.ArrayList<String>(); while(names.hasNext())result.add(names.next()); return result; }
    @SuppressWarnings("unchecked") private static Map<String,Object> toMap(Object value) { return (Map<String,Object>)value; }
    private static void copyTree(Path source,Path destination) throws Exception { try(java.util.stream.Stream<Path> paths=Files.walk(source)) { for(Path item:(Iterable<Path>)paths::iterator) { Path target=destination.resolve(source.relativize(item)); if(Files.isDirectory(item))Files.createDirectories(target);else Files.copy(item,target,StandardCopyOption.REPLACE_EXISTING); } } }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
    private static boolean isWindowsJava8() { return isWindows() && "1.8".equals(System.getProperty("java.specification.version")); }
    private static String yamlQuote(Object value) { return "'"+String.valueOf(value).replace("'", "''")+"'"; }
    private static String read(InputStream stream) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n; while((n=stream.read(buffer))>=0)out.write(buffer,0,n); return new String(out.toByteArray(),StandardCharsets.UTF_8); }
}
