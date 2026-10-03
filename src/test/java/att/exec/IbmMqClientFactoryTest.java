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
        public void resizeBuffer(int value) { bufferSize = value; }
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
}
