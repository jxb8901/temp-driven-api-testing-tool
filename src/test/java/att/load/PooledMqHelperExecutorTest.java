package att.load;

import att.config.FrameworkConfig;
import att.config.MqHelperConfig;
import att.config.ProcessOutputConfig;
import att.core.CaseRuntimeContext;
import att.core.TestCase;
import att.exec.MqHelperExecutor;
import att.exec.MqTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PooledMqHelperExecutorTest {
    @TempDir Path tempDir;

    @Test
    void helperExecutorReturnsInvocationQueueAndReusesThePhysicalConnection() throws Exception {
        FakeFactory delegate = new FakeFactory();
        PooledMqTransportFactory factory = new PooledMqTransportFactory(delegate, 20, 2000L);
        MqHelperExecutor executor = new MqHelperExecutor(tempDir, config(2, 0, 100L), factory);
        Path caseDir = Files.createDirectories(tempDir.resolve("case"));
        Path payload = caseDir.resolve("request.bin");
        Files.write(payload, new byte[]{1, 2, 3});

        assertTrue(executor.execute("broker", "send", map("queue", "REQUEST.Q", "file", payload.toString()),
                context(caseDir), null, "send-1").success());
        assertTrue(executor.execute("broker", "send", map("queue", "REQUEST.Q", "file", payload.toString()),
                context(caseDir), null, "send-2").success());
        assertEquals(1, delegate.connections.get());
        assertEquals(2, delegate.queueCloses.get());
        assertEquals(0, factory.pool("broker").active());
        assertEquals(1, factory.pool("broker").total());

        factory.close();
        assertEquals(1, delegate.disconnects.get());
    }

    @Test
    void helperExecutorPublishesDistinctPoolTimeoutEvidence() throws Exception {
        FakeFactory delegate = new FakeFactory();
        MqHelperConfig helper = config(1, 0, 25L).mqHelper("broker");
        PooledMqTransportFactory factory = new PooledMqTransportFactory(delegate, 20, 2000L);
        MqHelperExecutor executor = new MqHelperExecutor(tempDir, config(helper), factory);
        Path caseDir = Files.createDirectories(tempDir.resolve("timeout-case"));
        Path payload = caseDir.resolve("request.bin");
        Files.write(payload, new byte[]{1});
        MqTransport.Connection held = factory.connect(helper);
        try {
            Map<String, Object> result = executor.execute("broker", "send",
                    map("queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null, "timeout-1").result();
            assertFalse(Boolean.TRUE.equals(result.get("sent")));
            assertEquals("MQ_POOL_TIMEOUT", ((Map<?, ?>) result.get("error")).get("type"));
            assertEquals(1, ((Number) factory.pool("broker").metrics().get("timeoutCount")).intValue());
        } finally {
            held.disconnect();
            factory.close();
        }
    }

    @Test
    void helperExecutorPreservesNoMessageSuccessAndHealthyReuse() throws Exception {
        FakeFactory delegate = new FakeFactory(); delegate.noMessage = true;
        MqHelperConfig helper = config(1, 0, 100L).mqHelper("broker");
        PooledMqTransportFactory factory = new PooledMqTransportFactory(delegate, 20, 2000L);
        MqHelperExecutor executor = new MqHelperExecutor(tempDir, config(helper), factory);
        Path caseDir = Files.createDirectories(tempDir.resolve("receive-case"));
        Map<String, Object> result = executor.execute("broker", "receive",
                map("queue", "REPLY.Q", "waitMs", 0), context(caseDir), null, "receive-1").result();

        assertEquals(Boolean.FALSE, result.get("received"));
        assertEquals(2033, result.get("reasonCode"));
        assertEquals(1, delegate.connections.get());
        assertEquals(0, factory.pool("broker").discarded());
        factory.close();
    }

    @Test
    void closingRunPoolDisconnectsAnActiveLeaseExactlyOnce() throws Exception {
        FakeFactory delegate = new FakeFactory();
        PooledMqTransportFactory factory = new PooledMqTransportFactory(delegate, 20, 2000L);
        MqTransport.Connection held = factory.connect(config(1, 0, 100L).mqHelper("broker"));
        assertEquals(1, factory.pool("broker").active());
        factory.close();
        held.disconnect();
        assertEquals(1, delegate.disconnects.get());
        assertEquals(0, factory.metrics().size());
    }

    private FrameworkConfig config(int maxSize, int minIdle, long timeoutMs) {
        return config(helper(maxSize, minIdle, timeoutMs));
    }

    private FrameworkConfig config(MqHelperConfig helper) {
        Map<String, MqHelperConfig> helpers = new LinkedHashMap<String, MqHelperConfig>();
        helpers.put("broker", helper);
        return new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir,
                tempDir, Collections.emptyMap(), Collections.emptyMap(), helpers, null, null,
                null, "", "", null, null, 1, "ignore", "", false, ProcessOutputConfig.defaults());
    }

    private MqHelperConfig helper(int maxSize, int minIdle, long timeoutMs) {
        return new MqHelperConfig("broker", "Broker", "test broker", "QM1", "localhost", 1414,
                "DEV.APP.SVRCONN", "user", "secret", 1208, "MQSTR", "asQueue", 1000,
                "metadata", maxSize, minIdle, timeoutMs, tempDir.resolve("mq.yaml"));
    }

    private CaseRuntimeContext context(Path caseDir) {
        return new CaseRuntimeContext(new TestCase(2, "g", "sheet", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null), caseDir, "R", tempDir,
                caseDir.resolve("case.log"));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private static final class FakeFactory implements MqTransport.Factory {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicInteger queueCloses = new AtomicInteger();
        volatile boolean noMessage;

        @Override public MqTransport.Connection connect(MqHelperConfig config) {
            connections.incrementAndGet();
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    return new MqTransport.Queue() {
                        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) {
                            return new MqTransport.Message(new byte[]{1}, null, payload);
                        }
                        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
                            if (noMessage) throw new MqTransport.Exception("No message", 2, 2033, "MQRC_NO_MSG_AVAILABLE", null);
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
