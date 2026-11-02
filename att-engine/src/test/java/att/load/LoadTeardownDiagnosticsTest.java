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

    @Test void mergesBoundedSessionDetailsAndDropsSecretsPathsAndQueueNames() {
        Map<String, Object> session = new java.util.LinkedHashMap<String, Object>();
        session.put("physicalId", "p2");
        session.put("confirmationState", "QUARANTINED");
        session.put("failureClass", "PHYSICAL_DISCONNECT_FAILED");
        session.put("mqReasonCode", 2009);
        session.put("firstObserved", "TEARDOWN");
        session.put("queue", "SECRET.QUEUE");
        session.put("payload", "password=secret");
        Map<String, Object> details = new java.util.LinkedHashMap<String, Object>();
        details.put("poolKey", "fromeaimqhk::mq01");
        details.put("failureClass", "PHYSICAL_DISCONNECT_FAILED");
        details.put("safeCloseClassification", "NOT_APPLIED");
        details.put("safeCloseReason", "DISCONNECT_NOT_CONFIRMED");
        details.put("knownDeadBeforeTeardown", Boolean.FALSE);
        details.put("physicalSessions", java.util.Collections.singletonList(session));
        details.put("path", "/Users/alice/private/mq.properties");
        LoadResourceTeardownException typed = new LoadResourceTeardownException("MQ", "fromeaimqhk:mq01", 2L,
                new IOException("MQ_CONNECTION_DISCONNECT_FAILED /Users/alice/private/mq.properties"), details);
        Map<String, Object> diagnostic = LoadTeardownDiagnostics.from(typed);
        assertEquals("PHYSICAL_DISCONNECT_FAILED", diagnostic.get("failureClass"));
        assertEquals("fromeaimqhk::mq01", diagnostic.get("poolKey"));
        assertEquals("fromeaimqhk:mq01", diagnostic.get("logicalId"));
        assertEquals("NOT_APPLIED", diagnostic.get("safeCloseClassification"));
        assertFalse(diagnostic.toString().contains("/Users/alice"));
        assertFalse(diagnostic.toString().contains("SECRET.QUEUE"));
        assertFalse(diagnostic.toString().contains("password"));
        Map<?, ?> resource = (Map<?, ?>) ((List<?>) diagnostic.get("resources")).get(0);
        List<?> sessions = (List<?>) resource.get("physicalSessions");
        assertEquals("p2", ((Map<?, ?>) sessions.get(0)).get("physicalId"));
    }
}
