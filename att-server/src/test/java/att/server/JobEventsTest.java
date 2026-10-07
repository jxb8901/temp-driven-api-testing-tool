package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JobEventsTest {
    @TempDir Path temp;
    @Test void retainedEventsUseIncreasingJobLocalIdsAndReplayAfterLastEventId() throws Exception {
        Path file=temp.resolve("events.jsonl");JobEvents events=new JobEvents(file,3);
        events.append("status",Map.of("status","QUEUED"));events.append("status",Map.of("status","RUNNING"));events.append("result",Map.of("status","PASS"));
        JobEvents reopened=new JobEvents(file,3);
        assertEquals(3,reopened.latest());assertEquals(2,reopened.after(1).size());assertEquals(3L,((Number)reopened.after(1).get(1).get("id")).longValue());
        reopened.append("log",Map.of("message","x".repeat(70000)));
        assertEquals(true,reopened.after(3).get(0).get("data").toString().contains("truncated"));
        assertTrue(Files.size(file)>0);
    }
    @Test void slowObserverNotificationsAreBoundedWhileJournalRetainsEveryEvent() throws Exception {
        JobEvents events=new JobEvents(temp.resolve("fanout.jsonl"),10);java.util.concurrent.ArrayBlockingQueue<Boolean> wakeup=new java.util.concurrent.ArrayBlockingQueue<>(1);
        AutoCloseable subscription=events.listen(event->wakeup.offer(Boolean.TRUE));
        events.append("progress",Map.of("sequence",1));events.append("progress",Map.of("sequence",2));
        assertEquals(1,wakeup.size());assertEquals(2,events.after(0).size());subscription.close();
    }
}
