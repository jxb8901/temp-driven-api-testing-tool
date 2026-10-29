package att.load;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LoadTeardownDiagnosticsTest {
    @Test void collectsMultipleResourceFailuresWithoutPublishingCauseTextOrPaths() {
        RuntimeException aggregate=new RuntimeException("cleanup failed");
        aggregate.addSuppressed(new LoadResourceTeardownException("MQ","payments-mq",2L,
                new IOException("MQ_DISCONNECT_FAILED /Users/alice/private/mq.properties")));
        aggregate.addSuppressed(new LoadResourceTeardownException("DB","/Users/alice/private/db",1L,
                new IOException("DB_CLOSE_FAILED password=secret")));

        Map<String,Object> diagnostic=LoadTeardownDiagnostics.from(aggregate);
        assertEquals("MQ_RESOURCE_TEARDOWN_FAILED",diagnostic.get("code"));
        assertEquals("TEARDOWN",diagnostic.get("phase"));
        assertEquals(3L,diagnostic.get("failedCloseCount"));
        List<?> resources=(List<?>)diagnostic.get("resources");
        assertEquals(2,resources.size());
        assertTrue(diagnostic.toString().contains("DB_CLOSE_FAILED"));
        assertFalse(diagnostic.toString().contains("/Users/alice"));
        assertFalse(diagnostic.toString().contains("password=secret"));
    }
}
