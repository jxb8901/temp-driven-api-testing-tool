package att.remote.sse;

import att.remote.RemoteException;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class SseReaderTest {
    @Test void readsCommentsFieldsAndMultilineDataInOrder() throws Exception {
        SseReader reader=new SseReader(new StringReader(": keepalive\nid: 7\nevent: progress\ndata: {\ndata: \"status\":\"RUNNING\"}\n\nid: 8\nevent: result\ndata: {\"status\":\"PASS\"}\n\n"));
        SseEvent first=reader.next();assertEquals("7",first.id);assertEquals("progress",first.event);assertEquals("{\n\"status\":\"RUNNING\"}",first.data);
        SseEvent second=reader.next();assertEquals("8",second.id);assertEquals("result",second.event);assertEquals("{\"status\":\"PASS\"}",second.data);assertNull(reader.next());
    }
    @Test void boundsUntrustedSseLinesAndEventData() {
        StringBuilder huge=new StringBuilder();for(int i=0;i<256*1024+1;i++)huge.append('x');
        assertThrows(RemoteException.class,()->new SseReader(new StringReader("data: "+huge+"\n\n")).next());
    }
}
