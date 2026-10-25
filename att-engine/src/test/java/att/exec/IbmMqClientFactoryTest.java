package att.exec;

import att.config.MqHelperConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test void timedPreparationConnectIsIsolatedFromOrdinaryMqConnect() throws Exception {
        String connectKey="com.ibm.mq.cfg.TCP.Connect_Timeout";
        String negotiationKey="com.ibm.mq.cfg.TCP.QmgrNegotiationFlowTimeout";
        String previousConnect=System.getProperty(connectKey),previousNegotiation=System.getProperty(negotiationKey);
        ExecutorService callers=Executors.newFixedThreadPool(2);
        com.ibm.mq.MQQueueManager.reset(true);
        try {
            System.setProperty(connectKey,"17");System.setProperty(negotiationKey,"23");
            IbmMqClientFactory factory=new IbmMqClientFactory();
            MqHelperConfig config=new MqHelperConfig("mq","MQ","","QM1","localhost",1414,"ATT.SVRCONN","","",
                    1208,"MQSTR","asQueue",1000,null,java.nio.file.Paths.get("mq.yaml"));
            Future<MqTransport.Connection> timed=callers.submit(()->factory.connect(config,1000L));
            assertTrue(com.ibm.mq.MQQueueManager.firstConnect.await(5,TimeUnit.SECONDS));
            Future<MqTransport.Connection> ordinary=callers.submit(()->factory.connect(config));
            assertFalse(com.ibm.mq.MQQueueManager.secondConnect.await(150,TimeUnit.MILLISECONDS),
                    "ordinary connects must wait while temporary process-wide timeouts are installed");

            com.ibm.mq.MQQueueManager.releaseFirstConnect.countDown();
            MqTransport.Connection timedConnection=timed.get(5,TimeUnit.SECONDS);
            MqTransport.Connection ordinaryConnection=ordinary.get(5,TimeUnit.SECONDS);
            timedConnection.disconnect();ordinaryConnection.disconnect();

            List<String[]> observed=com.ibm.mq.MQQueueManager.observedTimeouts;
            assertEquals(2,observed.size());
            assertArrayEquals(new String[]{"1","1"},observed.get(0),"the readiness connect should use the bounded timeout");
            assertArrayEquals(new String[]{"17","23"},observed.get(1),"the ordinary connect should see configured defaults");
            assertEquals("17",System.getProperty(connectKey));
            assertEquals("23",System.getProperty(negotiationKey));
        } finally {
            com.ibm.mq.MQQueueManager.releaseFirstConnect.countDown();
            callers.shutdownNow();
            restoreProperty(connectKey,previousConnect);restoreProperty(negotiationKey,previousNegotiation);
        }
    }

    private static void restoreProperty(String name,String value) {
        if(value==null)System.clearProperty(name);else System.setProperty(name,value);
    }
}
