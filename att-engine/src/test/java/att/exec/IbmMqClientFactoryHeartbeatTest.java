package att.exec;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbmMqClientFactoryHeartbeatTest {
    @Test void heartbeatIsOmittedUnlessAPositiveIntervalAndPropertyNameAreBothPresent() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        IbmMqClientFactory.applyHeartbeat(properties, "heartbeat", 0);
        IbmMqClientFactory.applyHeartbeat(properties, "  ", 30);
        IbmMqClientFactory.applyHeartbeat(properties, null, 30);
        assertTrue(properties.isEmpty());
        IbmMqClientFactory.applyHeartbeat(properties, "HEARTBEAT_INTERVAL_PROPERTY", 30);
        assertEquals(Integer.valueOf(30), properties.get("HEARTBEAT_INTERVAL_PROPERTY"));
    }

    @Test void validateManagerUsesIsConnectedThenInquireAndDoesNotInventABusinessCall() throws Exception {
        AtomicInteger inquiries = new AtomicInteger();
        Probe broken = new Probe(false, inquiries);
        MqTransport.Exception failure = assertThrows(MqTransport.Exception.class,
                () -> IbmMqClientFactory.validateManager(broken, null, 200L));
        assertEquals(Integer.valueOf(2009), failure.reasonCode());
        assertEquals(Integer.valueOf(2), failure.completionCode());
        assertEquals("MQRC_CONNECTION_BROKEN", failure.reason());
        assertEquals(0, inquiries.get());

        Probe healthy = new Probe(true, inquiries);
        IbmMqClientFactory.validateManager(healthy, null, 200L);
        assertEquals(1, inquiries.get());

        IbmMqClientFactory.validateManager(new ConnectedOnly(), null, 200L);

        MqTransport.Exception unsupported = assertThrows(MqTransport.Exception.class,
                () -> IbmMqClientFactory.validateManager(new Object(), null, 200L));
        assertEquals("MQ_IDLE_VALIDATION_UNSUPPORTED", unsupported.reason());
    }

    @Test void invocationFailureFromIsConnectedIsTranslated() {
        assertThrows(MqTransport.Exception.class, () -> IbmMqClientFactory.validateManager(new Object() {
            @SuppressWarnings("unused") public boolean isConnected() { throw new IllegalStateException("MQRC_CONNECTION_BROKEN"); }
        }, null, 50L));
    }

    public static final class Probe {
        private final boolean connected;
        private final AtomicInteger inquiries;
        Probe(boolean connected, AtomicInteger inquiries) {
            this.connected = connected;
            this.inquiries = inquiries;
        }
        public boolean isConnected() { return connected; }
        public void inquire(int[] selectors, int[] ints, byte[] chars) { inquiries.incrementAndGet(); }
    }

    public static final class ConnectedOnly {
        public boolean isConnected() { return true; }
    }
}
