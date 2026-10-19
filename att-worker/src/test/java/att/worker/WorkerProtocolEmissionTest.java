package att.worker;

import att.api.DebugResult;
import att.api.LoadResult;
import att.api.OperationResult;
import att.api.RunResult;
import att.server.api.internal.WorkerProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProtocolEmissionTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void oversizedRunDebugAndLoadResultsRetainOutcomeMetricsAndArtifactDetails() throws Exception {
        StringBuilder value=new StringBuilder();
        for(int i=0;i<400000;i++)value.append('\u4e2d');
        Map<String,Object> details=Collections.<String,Object>singletonMap("large",value.toString());
        OperationResult[] outcomes={
            new RunResult("run-id","PASS",0,42,null,null,details),
            new DebugResult("debug-id","ERROR",3,42,null,null,details),
            new LoadResult("load-id","FAIL",1,42,null,null,details)
        };
        for(OperationResult outcome:outcomes) {
            Path output=Files.createDirectory(temp.resolve(outcome.executionId()));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();WorkerMain worker=worker(bytes,output);
            Map<String,Object> result=outcome.toMap();
            result.put("diagnostics",Collections.singletonList(fields("code","CONNECTION_ERROR","summary","JDBC driver unavailable")));
            invoke(worker,"emit",new Class<?>[]{WorkerEvent.Type.class,Map.class},WorkerEvent.Type.PROGRESS,fields("message",value.toString()));
            invoke(worker,"emitResult",new Class<?>[]{Map.class},fields("status",outcome.status(),"exitCode",outcome.exitCode(),"durationMs",42,"result",result));
            try(ByteArrayInputStream stream=new ByteArrayInputStream(bytes.toByteArray())) {
                WorkerProtocol.Frame progress=WorkerProtocol.read(stream,WorkerProtocol.MAX_FRAME_BYTES);
                assertFalse(progress.oversized());assertTrue(mapper.readTree(progress.text()).path("truncated").asBoolean());
                WorkerProtocol.Frame terminal=WorkerProtocol.read(stream,WorkerProtocol.MAX_FRAME_BYTES);
                assertFalse(terminal.oversized());JsonNode event=mapper.readTree(terminal.text());
                assertEquals("RESULT",event.path("type").asText());assertEquals(outcome.status(),event.path("status").asText());
                assertEquals(outcome.exitCode(),event.path("exitCode").asInt());
                assertEquals(outcome.executionId(),event.path("result").path("executionId").asText());
                assertEquals("CONNECTION_ERROR",event.path("result").path("diagnostics").get(0).path("code").asText());
                assertTrue(event.path("workerMetrics").path("sampleCount").asLong()>0);
                assertTrue(event.path("protocolLimit").path("sizeBytes").asLong()>WorkerProtocol.MAX_FRAME_BYTES);
                assertEquals("worker-protocol-result.json",event.path("result").path("detailsArtifact").asText());
                assertNull(WorkerProtocol.read(stream,WorkerProtocol.MAX_FRAME_BYTES));
            }
            JsonNode stored=mapper.readTree(output.resolve("worker-protocol-result.json").toFile());
            assertEquals(value.toString(),stored.path("result").path("summary").path("large").asText());
        }
    }

    @Test void artifactWriteFailureStillDeliversTheTerminalResult() throws Exception {
        Path output=Files.createDirectory(temp.resolve("occupied"));
        Files.write(output.resolve("worker-protocol-result.json"),new byte[]{1});
        StringBuilder large=new StringBuilder();for(int i=0;i<1100000;i++)large.append('x');
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();WorkerMain worker=worker(bytes,output);
        invoke(worker,"emitResult",new Class<?>[]{Map.class},fields("status","ERROR","exitCode",3,"result",fields("detail",large.toString())));
        JsonNode event=mapper.readTree(bytes.toByteArray());
        assertEquals("ERROR",event.path("status").asText());assertEquals(3,event.path("exitCode").asInt());
        assertTrue(event.path("result").path("detailsUnavailable").asBoolean());
        assertArrayEquals(new byte[]{1},Files.readAllBytes(output.resolve("worker-protocol-result.json")));
    }

    private WorkerMain worker(ByteArrayOutputStream out,Path output)throws Exception {
        Constructor<WorkerMain> constructor=WorkerMain.class.getDeclaredConstructor(PrintStream.class);constructor.setAccessible(true);
        WorkerMain worker=constructor.newInstance(new PrintStream(out,true,"UTF-8"));
        Field field=WorkerMain.class.getDeclaredField("executionOutput");field.setAccessible(true);field.set(worker,output);
        return worker;
    }
    private static void invoke(WorkerMain worker,String name,Class<?>[] types,Object... args)throws Exception {
        Method method=WorkerMain.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(worker,args);
    }
    private static Map<String,Object> fields(Object... fields) {
        Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<fields.length;i+=2)out.put((String)fields[i],fields[i+1]);return out;
    }
}
