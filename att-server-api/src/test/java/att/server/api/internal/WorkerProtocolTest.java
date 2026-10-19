package att.server.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProtocolTest {
    @Test void exactUtf8BudgetAndCrLfAreConsistentWithEmission() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        Object event=Collections.singletonMap("message","\u4e2d\u6587\ud83d\ude00");
        byte[] json=mapper.writeValueAsBytes(event);
        assertTrue(json.length>mapper.writeValueAsString(event).length());
        WorkerProtocol.Frame encoded=WorkerProtocol.encode(mapper,event,json.length);
        assertFalse(encoded.oversized());assertEquals(json.length,encoded.sizeBytes);
        String line=new String(json,StandardCharsets.UTF_8);
        try(ByteArrayInputStream stream=new ByteArrayInputStream((line+"\r\n").getBytes(StandardCharsets.UTF_8))) {
            WorkerProtocol.Frame frame=WorkerProtocol.read(stream,json.length);
            assertFalse(frame.oversized());assertEquals(line,frame.text());assertEquals(json.length,frame.sizeBytes);
            assertNull(WorkerProtocol.read(stream,json.length));
        }
        assertTrue(WorkerProtocol.encode(mapper,event,json.length-1).oversized());
        assertTrue(WorkerProtocol.read(new ByteArrayInputStream(json),json.length-1).oversized());
        assertTrue(WorkerProtocol.read(new ByteArrayInputStream("1234\r".getBytes(StandardCharsets.UTF_8)),4).oversized(),
                "A CR without a following LF is counted as payload");
    }

    @Test void drainsOversizedUnicodeFrameAndReportsOnlyKnownTypeAndActualBytes() throws Exception {
        StringBuilder large=new StringBuilder("{\"type\":\"PROGRESS\",\"message\":\"");
        for(int i=0;i<400000;i++)large.append('\u4e2d');
        large.append("\"}\n{\"type\":\"RESULT\",\"status\":\"PASS\"}\n");
        try(ByteArrayInputStream stream=new ByteArrayInputStream(large.toString().getBytes(StandardCharsets.UTF_8))) {
            WorkerProtocol.Frame frame=WorkerProtocol.read(stream,WorkerProtocol.MAX_FRAME_BYTES);
            assertTrue(frame.oversized());assertEquals("PROGRESS",frame.eventType());
            assertEquals(large.indexOf("\n")+800000L,frame.sizeBytes);
            assertThrows(IllegalStateException.class,frame::text);
            WorkerProtocol.Frame terminal=WorkerProtocol.read(stream,WorkerProtocol.MAX_FRAME_BYTES);
            assertFalse(terminal.oversized());assertEquals("RESULT",terminal.eventType());
            assertNull(WorkerProtocol.read(stream,32));
        }
        WorkerProtocol.Frame unknown=WorkerProtocol.read(new ByteArrayInputStream("private-canary-payload\n".getBytes(StandardCharsets.UTF_8)),4);
        assertEquals("UNKNOWN",unknown.eventType());
    }
}
