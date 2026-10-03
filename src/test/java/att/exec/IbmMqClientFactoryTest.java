package att.exec;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IbmMqClientFactoryTest {
    public static final class StringConstants {
        public static final String TRANSPORT_PROPERTY = "transport";
        public static final String TRANSPORT_MQSERIES_CLIENT = "MQSeries Client";
        public static final String TRANSPORT_MQSERIES = "MQSeries";
        public static final String TRANSPORT_MQSERIES_BINDINGS = "MQSeries Bindings";
    }
    public static final class MissingBindingsConstant {
        public static final String TRANSPORT_PROPERTY = "transport";
        public static final String TRANSPORT_MQSERIES_CLIENT = "client";
        public static final String TRANSPORT_MQSERIES = "mqseries";
    }
    public static final class FakeMqMessage {
        int bufferSize;
        long totalMessageLength;
        public void resizeBuffer(int value) { bufferSize = value; }
        public long getTotalMessageLength() { return totalMessageLength; }
    }
    public static final class FakeGetOptions {}
    public static final class FakeMqQueue {
        int boundedMaximum = -1;
        boolean unboundedOverloadCalled;
        public void get(FakeMqMessage message, FakeGetOptions options) { unboundedOverloadCalled = true; }
        public void get(FakeMqMessage message, FakeGetOptions options, int maxMsgSize) { boundedMaximum = maxMsgSize; }
    }
    public static final class FakeMqException extends Exception {
        public int completionCode = 2;
        public int reasonCode = 2080;
    }

    @Test void readsSelectedTransportConstantWithoutCoercingItsType() {
        assertEquals("transport", IbmMqClientFactory.transportPropertyName(StringConstants.class));
        assertEquals("MQSeries Client", IbmMqClientFactory.transportConstant(StringConstants.class, "MQSeries Client"));
        assertEquals("MQSeries", IbmMqClientFactory.transportConstant(StringConstants.class, "MQSeries"));
        assertEquals("MQSeries Bindings", IbmMqClientFactory.transportConstant(StringConstants.class, "MQSeries Bindings"));
    }

    @Test void missingSelectedTransportConstantIsActionable() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IbmMqClientFactory.transportConstant(MissingBindingsConstant.class, "MQSeries Bindings"));
        assertTrue(error.getMessage().contains("TRANSPORT_MQSERIES_BINDINGS"));
        assertTrue(error.getMessage().contains("allclient"));
    }

    @Test void receiveBufferUsesSupportedResizeBufferApi() throws Exception {
        FakeMqMessage message = new FakeMqMessage();
        IbmMqClientFactory.resizeReceiveBuffer(message, 2048);
        assertEquals(2048, message.bufferSize);
    }

    @Test void receiveUsesThreeArgumentGetWithConfiguredMaximum() throws Exception {
        FakeMqQueue queue = new FakeMqQueue();
        IbmMqClientFactory.getWithMaxMessageSize(queue, new FakeMqMessage(), new FakeGetOptions(), 4096);
        assertEquals(4096, queue.boundedMaximum);
        assertFalse(queue.unboundedOverloadCalled);
    }

    @Test void truncatedMessageFailedMapsToTooLargeAndPreservesTotalLength() {
        FakeMqMessage message = new FakeMqMessage();
        message.totalMessageLength = 4097L;
        MqTransport.Exception translated = IbmMqClientFactory.translateGetFailure(
                new FakeMqException(), message, 2048);
        assertEquals("MQ_RESPONSE_TOO_LARGE", translated.reason());
        assertTrue(translated.getMessage().contains("maxResponseBytes=2048"));
        assertTrue(translated.getMessage().contains("messageBytes=4097"));
    }
}
