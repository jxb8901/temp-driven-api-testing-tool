package att.server;

import att.server.api.WorkerRequest;
import att.server.api.internal.WorkerProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProtocolFailureTest {
    @TempDir Path temp;

    @Test void protocolCauseSurvivesExitFallbackAndRestartWithoutLeakingPayload() throws Exception {
        ServerConfig config;
        try(ServerRuntime runtime=runtime()) {
            config=runtime.config;Job job=job(runtime);
            WorkerProtocol.Frame frame=oversized();runtime.recordProtocolLimit(job,frame);
            runtime.handleWorkerEvent(job,"{\"type\":\"DIAGNOSTIC\",\"code\":\"SECONDARY\",\"message\":\"Worker could not finish\"}");
            runtime.recordWorkerExit(job,3);runtime.finish(job,"ERROR",3);
            assertDiagnostic(runtime.store.get(job.id));
            String journal=Files.readString(config.dataDir.resolve("jobs").resolve(job.id).resolve("events.jsonl"));
            assertFalse(journal.contains("private-canary"));
        }
        try(ServerRuntime reopened=new ServerRuntime(config,temp.resolve("libs").toString())) {
            assertDiagnostic(reopened.store.get("J1234567890ABCDEF"));
        }
    }

    @Test void abnormalWorkerExitIsAnExecutionErrorWithSafeExitEvidence() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            Job job=job(runtime);
            runtime.recordWorkerExit(job,137);
            runtime.finish(job,"ERROR",137);
            var diagnostic=ServerRuntime.JSON.readTree(job.diagnosticJson);
            assertEquals("ATT-SERVER-WORKER-EXITED",diagnostic.path("code").asText());
            assertEquals("EXECUTION_ERROR",diagnostic.path("outcome").asText());
            assertEquals("worker.processTermination",diagnostic.path("limitName").asText());
            assertEquals(137,diagnostic.path("exitCode").asInt());
            assertEquals("ERROR",job.status);assertEquals(137,job.exitCode);
            assertFalse(diagnostic.toString().contains(runtime.config.dataDir.toString()));
        }
    }

    @Test void structuredRuntimeCauseRemainsPrimaryAfterProtocolTruncationAndWorkerExit() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            Job job=job(runtime);runtime.recordProtocolLimit(job,oversized());
            runtime.handleWorkerEvent(job,"{\"type\":\"DIAGNOSTIC\",\"code\":\"HTTP_RESPONSE_TOO_LARGE\",\"message\":\"HTTP response exceeded its configured byte limit\",\"outcome\":\"EXECUTION_ERROR\",\"stage\":\"execution\"}");
            runtime.recordWorkerExit(job,3);runtime.finish(job,"ERROR",3);
            var diagnostic=ServerRuntime.JSON.readTree(job.diagnosticJson);
            assertEquals("HTTP_RESPONSE_TOO_LARGE",diagnostic.path("code").asText());
            assertEquals("EXECUTION_ERROR",diagnostic.path("outcome").asText());
            String events=Files.readString(runtime.config.dataDir.resolve("jobs").resolve(job.id).resolve("events.jsonl"));
            assertTrue(events.contains("ATT-SERVER-WORKER-EVENT-TOO-LARGE"));
            assertFalse(events.contains("private-canary"));
        }
    }

    @Test void classifiedCauseStaysPrimaryWhenSecondaryArrivesBeforeTerminalResult() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            Job job=job(runtime,"load");
            runtime.handleWorkerEvent(job,"{\"type\":\"DIAGNOSTIC\",\"code\":\"HTTP_RESPONSE_TOO_LARGE\",\"message\":\"bounded helper response exceeded\",\"outcome\":\"EXECUTION_ERROR\",\"stage\":\"execution\"}");
            runtime.handleWorkerEvent(job,"{\"type\":\"DIAGNOSTIC\",\"code\":\"SECONDARY\",\"message\":\"optional detail was omitted\"}");
            runtime.handleWorkerEvent(job,"{\"type\":\"RESULT\",\"status\":\"ERROR\",\"exitCode\":3,\"result\":{\"status\":\"ERROR\",\"diagnostics\":[]}}");
            assertEquals("ERROR",job.status);assertEquals(3,job.exitCode);
            assertEquals("HTTP_RESPONSE_TOO_LARGE",ServerRuntime.JSON.readTree(job.diagnosticJson).path("code").asText());
            var diagnostics=ServerRuntime.JSON.readTree(job.resultJson).path("summary").path("diagnostics");
            assertEquals("HTTP_RESPONSE_TOO_LARGE",diagnostics.path("primaryError").path("code").asText());
            assertTrue(diagnostics.toString().contains("SECONDARY"),diagnostics.toString());
        }
    }

    @Test void classifiedCauseStaysPrimaryWhenSecondaryArrivesBeforeMissingResultExit() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            Job job=job(runtime,"load");
            runtime.handleWorkerEvent(job,"{\"type\":\"DIAGNOSTIC\",\"code\":\"HTTP_RESPONSE_TOO_LARGE\",\"message\":\"bounded helper response exceeded\",\"outcome\":\"EXECUTION_ERROR\",\"stage\":\"execution\"}");
            runtime.handleWorkerEvent(job,"{\"type\":\"DIAGNOSTIC\",\"code\":\"SECONDARY\",\"message\":\"optional detail was omitted\"}");
            runtime.recordWorkerExit(job,3);runtime.finish(job,"ERROR",3);
            assertEquals("ERROR",job.status);assertEquals(3,job.exitCode);
            assertEquals("HTTP_RESPONSE_TOO_LARGE",ServerRuntime.JSON.readTree(job.diagnosticJson).path("code").asText());
            var diagnostics=ServerRuntime.JSON.readTree(job.resultJson).path("summary").path("diagnostics");
            assertEquals("HTTP_RESPONSE_TOO_LARGE",diagnostics.path("primaryError").path("code").asText());
            assertTrue(diagnostics.toString().contains("SECONDARY"),diagnostics.toString());
        }
    }

    @Test void oversizedProgressDoesNotPreventAValidLaterErrorResultAndMetrics() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            Job job=job(runtime);runtime.recordProtocolLimit(job,oversized());
            runtime.handleWorkerEvent(job,"{\"type\":\"RESULT\",\"status\":\"ERROR\",\"exitCode\":3,\"workerMetrics\":{\"sampleCount\":3},\"result\":{\"status\":\"ERROR\",\"diagnostics\":[{\"code\":\"CONNECTION_ERROR\"}]}}");
            assertTrue(job.resultReceived);assertEquals("ERROR",job.status);assertEquals(3,job.exitCode);
            assertEquals(3,job.workerMetrics.get("sampleCount"));assertTrue(job.resultJson.contains("CONNECTION_ERROR"));
            assertFalse(job.diagnosticJson.contains("WORKER-EXITED"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"PROGRESS","RESULT"})
    void realProcessReaderDrainsFramesAndKeepsTheTerminalOutcomeOrCausalError(String type)throws Exception {
        try(ServerRuntime runtime=runtime(builder->new ProcessBuilder(
                Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java").toString(),
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ProtocolProcess.class.getName(),type).start())) {
            String id=(String)runtime.submit("run",ServerRuntime.JSON.createObjectNode().put("packageId","p"),"ci").get("jobId");
            Map<String,Object> record=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline) {
                record=runtime.store.get(id);
                if("ERROR".equals(record.get("status"))&&!runtime.jobs.containsKey(id))break;
                Thread.sleep(20);
            }
            assertNotNull(record);assertEquals("ERROR",record.get("status"));assertEquals(3,record.get("exitCode"));
            var diagnostic=ServerRuntime.JSON.readTree((String)record.get("diagnosticJson"));
            assertEquals("ATT-SERVER-WORKER-EVENT-TOO-LARGE",diagnostic.path("code").asText());
            assertEquals(type,diagnostic.path("eventType").asText());
            if("PROGRESS".equals(type)) {
                assertTrue(String.valueOf(record.get("resultJson")).contains("CONNECTION_ERROR"));
                assertTrue(String.valueOf(record.get("performanceJson")).contains("sampleCount"));
                assertFalse(diagnostic.has("workerExit"));
            } else {
                assertEquals("ATT-SERVER-WORKER-EXITED",diagnostic.path("workerExit").path("code").asText());
            }
        }
    }

    public static class ProtocolProcess {
        public static void main(String[] args)throws Exception {
            new java.io.BufferedReader(new java.io.InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
            System.out.print("{\"type\":\""+args[0]+"\",\"message\":\"");
            System.out.print("private-canary"+"x".repeat(WorkerProtocol.MAX_FRAME_BYTES));System.out.println("\"}");
            if("PROGRESS".equals(args[0]))System.out.println("{\"type\":\"RESULT\",\"status\":\"ERROR\",\"exitCode\":3,\"workerMetrics\":{\"sampleCount\":3},\"result\":{\"diagnostics\":[{\"code\":\"CONNECTION_ERROR\"}]}}");
            System.out.flush();System.exit(3);
        }
    }

    private void assertDiagnostic(Map<String,Object> record)throws Exception {
        Object raw=record.get("diagnosticJson");
        assertNotNull(raw,record.toString());
        var diagnostic=ServerRuntime.JSON.readTree(String.valueOf(raw));
        assertEquals("ATT-SERVER-WORKER-EVENT-TOO-LARGE",diagnostic.path("code").asText());
        assertEquals("DATA_TRUNCATED",diagnostic.path("outcome").asText());
        assertEquals("worker.protocol.maxFrameBytes",diagnostic.path("limitName").asText());
        assertEquals("PROGRESS",diagnostic.path("eventType").asText());
        assertTrue(diagnostic.path("sizeBytes").asLong()>diagnostic.path("limitBytes").asLong());
        assertEquals("ATT-SERVER-WORKER-EXITED",diagnostic.path("workerExit").path("code").asText());
        assertEquals("EXECUTION_ERROR",diagnostic.path("workerExit").path("outcome").asText());
        assertFalse(String.valueOf(raw).contains("private-canary"));
    }
    private WorkerProtocol.Frame oversized()throws Exception {
        String line="{\"type\":\"PROGRESS\",\"message\":\"private-canary"+"x".repeat(WorkerProtocol.MAX_FRAME_BYTES)+"\"}\n";
        return WorkerProtocol.read(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)),WorkerProtocol.MAX_FRAME_BYTES);
    }
    private Job job(ServerRuntime runtime)throws Exception {
        return job(runtime,"debug");
    }
    private Job job(ServerRuntime runtime,String command)throws Exception {
        Path directory=Files.createDirectories(runtime.config.dataDir.resolve("jobs/J1234567890ABCDEF"));
        Job job=new Job("J1234567890ABCDEF",command,"p","ci",new WorkerRequest(),new JobEvents(directory.resolve("events.jsonl"),100));
        job.status="RUNNING";runtime.store.insert(job,"{}");return job;
    }
    private ServerRuntime runtime()throws Exception {
        return runtime(ProcessBuilder::start);
    }
    private ServerRuntime runtime(ServerRuntime.WorkerProcessLauncher launcher)throws Exception {
        Path pkg=Files.createDirectories(temp.resolve("packages/p"));Files.createDirectory(temp.resolve("libs"));
        Path yaml=temp.resolve("server.yaml");Files.writeString(yaml,"server:\n  dataDir: '"+temp.resolve("data")+"'\nworkers: {maxConcurrent: 1, queuedLimit: 1, maxConcurrentLoad: 1}\npackages:\n  allowedRoots: ['"+pkg.getParent()+"']\n  entries: {p: '"+pkg+"'}\n");
        return new ServerRuntime(ServerConfig.load(yaml),temp.resolve("libs").toString(),ServerRuntimeTestSupport.resolvingConfiguration(launcher));
    }
}
