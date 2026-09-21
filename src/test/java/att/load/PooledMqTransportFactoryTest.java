package att.load;

import att.config.MqHelperConfig;
import att.exec.MqTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PooledMqTransportFactoryTest {
    @Test
    void minIdleCreatesOnePhysicalConnectionAndHealthyLeaseIsReused() throws Exception {
        FakeFactory delegate = new FakeFactory();
        PooledMqTransportFactory factory = new PooledMqTransportFactory(delegate, 20, 2000L);
        MqTransport.Connection first = factory.connect(config(2, 1, 100L));
        assertEquals(1, delegate.connections.get());
        MqTransport.Queue queue = first.open("REQUEST.Q", false, true);
        queue.close();
        first.disconnect();

        MqTransport.Connection second = factory.connect(config(2, 1, 100L));
        second.disconnect();
        assertEquals(1, delegate.connections.get());
        assertEquals(1, factory.pool("broker").created());
        factory.close();
        assertEquals(1, delegate.disconnects.get());
    }

    @Test
    void maxSizeAndBorrowTimeoutAreBoundedAndDistinct() throws Exception {
        FakeFactory delegate = new FakeFactory();
        PooledMqTransportFactory factory = new PooledMqTransportFactory(delegate, 20, 2000L);
        MqTransport.Connection held = factory.connect(config(1, 0, 50L));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MqTransport.Connection> waiting = executor.submit(() -> factory.connect(config(1, 0, 50L)));
            ExecutionException error = assertThrows(ExecutionException.class, () -> waiting.get(1L, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof MqTransport.Exception);
            assertEquals("MQ_POOL_TIMEOUT", ((MqTransport.Exception) error.getCause()).reason());
            assertEquals(1, factory.pool("broker").timeoutCount());
        } finally {
            held.disconnect();
            executor.shutdownNow();
            factory.close();
        }
    }

    @Test
    void mqrc2033DoesNotInvalidateHealthyPhysicalConnection() throws Exception {
        FakeFactory delegate = new FakeFactory(); delegate.noMessage = true;
        PooledMqTransportFactory factory = new PooledMqTransportFactory(delegate, 20, 2000L);
        MqTransport.Connection first = factory.connect(config(1, 0, 100L));
        MqTransport.Queue queue = first.open("REPLY.Q", true, false);
        assertThrows(MqTransport.Exception.class, () -> queue.get(new MqTransport.GetRequest(null, 0)));
        queue.close(); first.disconnect();

        delegate.noMessage = false;
        MqTransport.Connection second = factory.connect(config(1, 0, 100L));
        second.disconnect();
        assertEquals(1, delegate.connections.get());
        assertEquals(0, factory.pool("broker").discarded());
        factory.close();
    }

    @Test
    void operationFailureInvalidatesAndReplacesPhysicalConnection() throws Exception {
        FakeFactory delegate = new FakeFactory(); delegate.failPut = true;
        PooledMqTransportFactory factory = new PooledMqTransportFactory(delegate, 20, 2000L);
        MqTransport.Connection first = factory.connect(config(1, 0, 100L));
        MqTransport.Queue queue = first.open("REQUEST.Q", false, true);
        assertThrows(MqTransport.Exception.class, () -> queue.put(new byte[]{1}, new MqTransport.PutRequest("", 1208, "MQSTR", "asQueue")));
        queue.close(); first.disconnect();

        delegate.failPut = false;
        MqTransport.Connection second = factory.connect(config(1, 0, 100L));
        second.disconnect();
        assertEquals(2, delegate.connections.get());
        assertEquals(1, factory.pool("broker").discarded());
        factory.close();
        assertEquals(2, delegate.disconnects.get());
    }

    private MqHelperConfig config(int maxSize, int minIdle, long timeoutMs) {
        return new MqHelperConfig("broker", "Broker", "test broker", "QM1", "localhost", 1414,
                "DEV.APP.SVRCONN", "user", "secret", 1208, "MQSTR", "asQueue", 1000, "metadata",
                maxSize, minIdle, timeoutMs, null);
    }

    private static final class FakeFactory implements MqTransport.Factory {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicInteger queueCloses = new AtomicInteger();
        volatile boolean noMessage;
        volatile boolean failPut;

        @Override public MqTransport.Connection connect(MqHelperConfig config) {
            connections.incrementAndGet();
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    return new MqTransport.Queue() {
                        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) throws Exception {
                            if (failPut) throw new MqTransport.Exception("put failed", null, 2009, "MQRC_CONNECTION_BROKEN", null);
                            return new MqTransport.Message(new byte[]{1}, null, payload);
                        }
                        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
                            if (noMessage) throw new MqTransport.Exception("no message", 2, 2033, "MQRC_NO_MSG_AVAILABLE", null);
                            return new MqTransport.Message(new byte[]{1}, null, new byte[0]);
                        }
                        @Override public void close() { queueCloses.incrementAndGet(); }
                    };
                }
                @Override public void disconnect() { disconnects.incrementAndGet(); }
                @Override public void close() { disconnect(); }
            };
        }
    }
}
