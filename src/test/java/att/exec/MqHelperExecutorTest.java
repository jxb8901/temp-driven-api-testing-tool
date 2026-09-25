/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.FrameworkConfig;
import att.config.MqHelperConfig;
import att.config.ProcessOutputConfig;
import att.core.CaseRuntimeContext;
import att.core.CaseExecutionLog;
import att.core.TestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MqHelperExecutorTest {
    @TempDir Path tempDir;

    @Test void sendPreservesPayloadBytesAndNeverCopiesPayloadIntoEvidence() throws Exception {
        Path caseDir = tempDir.resolve("case"); Files.createDirectories(caseDir);
        Path payload = tempDir.resolve("payload.bin");
        byte[] bytes = new byte[]{0, 1, (byte) 0xff, 10, 13}; Files.write(payload, bytes);
        FakeFactory factory = new FakeFactory();
        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "send",
                map("queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null, "send-1");

        assertTrue(result.success());
        assertArrayEquals(bytes, factory.putPayload);
        assertEquals("", factory.putRequest == null ? null : factory.putRequest.replyQueue());
        assertEquals(bytes.length, result.result().get("bytes"));
        assertEquals("single", result.operationResult().outputMetadata().get("selectionStrategy"));
        assertFalse(result.evidence().containsKey("payload"));
        Map<?, ?> mqEvidence = (Map<?, ?>) result.operationResult().evidence().get("mq");
        assertTrue(mqEvidence.get("invocations") instanceof java.util.List);
        assertEquals("broker", ((Map<?, ?>) ((java.util.List<?>) mqEvidence.get("invocations")).get(0)).get("helperId"));
        assertEquals(1, factory.disconnects);
        assertEquals(1, factory.queueCloses);
    }

    @Test void requestMatchesReplyCorrelationAndKeepsReplyInMemoryByDefault() throws Exception {
        Path caseDir = tempDir.resolve("request-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{7, 8, 9});
        FakeFactory factory = new FakeFactory();
        factory.reply = new MqTransport.Message(new byte[]{3, 4}, new byte[]{1, 2}, new byte[]{4, 5, 6});
        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "request",
                map("requestQueue", "REQUEST.Q", "replyQueue", "REPLY.Q", "file", payload.toString(), "waitMs", 321),
                context(caseDir), null, "request-1");

        assertTrue(result.success());
        assertEquals(Boolean.TRUE, result.result().get("sent"));
        assertEquals(Boolean.TRUE, result.result().get("replyReceived"));
        assertEquals("QM1", factory.putRequest.replyQueueManager());
        assertEquals("REPLY.Q", factory.putRequest.replyQueue());
        assertArrayEquals(new byte[]{1, 2}, factory.getRequest.correlationId());
        assertEquals(321, factory.getRequest.waitMs());
        assertNull(result.result().get("replyFile"));
        assertArrayEquals(new byte[]{4, 5, 6}, (byte[]) result.result().get("result"));
        assertEquals(3, result.result().get("replyBytes"));
        assertEquals("REPLY.Q", result.evidence().get("replyQueue"));
        assertEquals(2, factory.queueCloses);
        assertEquals(1, factory.disconnects);
    }

    @Test void explicitRawSaveAsWritesExactReplyBytesAndPublishesArtifactOnlyThen() throws Exception {
        Path caseDir = tempDir.resolve("saved-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{7, 8, 9});
        FakeFactory factory = new FakeFactory();
        factory.reply = new MqTransport.Message(new byte[]{3}, new byte[]{1, 2}, new byte[]{0, 1, (byte) 0xff});
        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "request",
                map("requestQueue", "REQUEST.Q", "replyQueue", "REPLY.Q", "file", payload.toString()),
                context(caseDir), null, "request-2", "request", "responses/reply.bin", "raw", false);

        assertTrue(result.success());
        Path output = Paths.get(String.valueOf(result.result().get("outputFile")));
        assertArrayEquals(new byte[]{0, 1, (byte) 0xff}, Files.readAllBytes(output));
        assertEquals(output.toString(), result.result().get("outputFile"));
    }

    @Test void typedReplyUsesReplyCcsidAndPathConsoleWritesPresentationOnly() throws Exception {
        Path caseDir = tempDir.resolve("typed-case"); Files.createDirectories(caseDir);
        FakeFactory factory = new FakeFactory();
        factory.reply = new MqTransport.Message(new byte[]{(byte) 0xe9}, new byte[]{1}, new byte[]{(byte) 0xe9},
                819, 273, "MQSTR   ");
        Path logPath = caseDir.resolve("case.log");
        MqInvocationResult result;
        try (CaseExecutionLog log = new CaseExecutionLog(logPath)) {
            result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "receive",
                    map("queue", "REPLY.Q"), context(caseDir), null, "receive-typed",
                    "reply", "console", "text", false, log);
        }

        assertTrue(result.success());
        assertEquals("é", result.result().get("result"));
        assertEquals(819, result.result().get("replyCcsid"));
        assertFalse(result.result().containsKey("outputFile"));
        String log = new String(Files.readAllBytes(logPath), "UTF-8");
        assertTrue(log.contains("[ACTION reply SAVE]"), log);
        assertTrue(log.contains("é"), log);
    }

    @Test void typedReplyUsesPaddedIbmCcsidAlias() throws Exception {
        Path caseDir = tempDir.resolve("ebcdic-case"); Files.createDirectories(caseDir);
        FakeFactory factory = new FakeFactory();
        factory.reply = new MqTransport.Message(new byte[]{3}, new byte[]{1},
                new byte[]{(byte) 0xc8, (byte) 0xc5, (byte) 0xd3, (byte) 0xd3, (byte) 0xd6},
                37, 273, "MQSTR   ");

        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "receive",
                map("queue", "REPLY.Q"), context(caseDir), null, "receive-ebcdic", null, null, "text", false);

        assertTrue(result.success());
        assertEquals("HELLO", result.result().get("result"));
    }

    @Test void noneEvidencePolicyOmitsPayloadEvidence() throws Exception {
        Path caseDir = tempDir.resolve("none-evidence-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{1, 2});
        MqInvocationResult result = new MqHelperExecutor(tempDir, config("none"), new FakeFactory()).execute("broker", "send",
                map("queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null, "send-none");

        assertTrue(result.success());
        assertFalse(result.evidence().containsKey("payloadEvidence"));
    }

    @Test void sendRejectsSaveAsBeforeConnecting() throws Exception {
        Path caseDir = tempDir.resolve("send-save-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{1});
        FakeFactory factory = new FakeFactory();
        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "send",
                map("queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null, "send-save",
                "send", "sent.bin", "raw", false);

        assertFalse(result.success());
        assertTrue(String.valueOf(((Map<?, ?>) result.result().get("error")).get("message")).contains("does not support saveAs"));
        assertTrue(factory.connectedInstances.isEmpty());
    }

    @Test void savedMqArtifactUsesCommonFlowActionRoot() throws Exception {
        Path caseDir = tempDir.resolve("flow-save-case"); Files.createDirectories(caseDir);
        FakeFactory factory = new FakeFactory();
        factory.reply = new MqTransport.Message(new byte[]{3}, new byte[]{1}, new byte[]{4, 5, 6});
        CaseRuntimeContext context = context(caseDir);
        context.beginFlow("common.save.v1", "saveFlow");
        try {
            MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "receive",
                    map("queue", "REPLY.Q"), context, null, "receive-flow", "reply", "responses/reply.bin", "raw", false);
            assertTrue(result.success());
            Path expected = caseDir.resolve("flows/saveFlow/actions/reply/responses/reply.bin");
            assertEquals(expected.toString(), result.result().get("outputFile"));
            assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(expected));
        } finally {
            context.finishFlow();
        }
    }

    @Test void requestNoReplyPreservesEffectiveWaitMsAndScopesBindNotFixedToRequestOutput() throws Exception {
        Path caseDir = tempDir.resolve("request-timeout-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{7, 8, 9});
        FakeFactory factory = new FakeFactory(); factory.noMessage = true;
        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "request",
                map("requestQueue", "REQUEST.Q", "replyQueue", "REPLY.Q", "file", payload.toString(), "waitMs", 321),
                context(caseDir), null, "request-2033");

        assertTrue(result.success());
        assertEquals(Boolean.FALSE, result.result().get("replyReceived"));
        assertEquals(321, result.result().get("waitMs"));
        assertEquals(java.util.Arrays.asList(Boolean.TRUE, Boolean.FALSE), factory.bindNotFixed);
    }

    @Test void noMessageAvailableIsSuccessfulReceiveWithoutResendSemantics() throws Exception {
        Path caseDir = tempDir.resolve("empty-case"); Files.createDirectories(caseDir);
        FakeFactory factory = new FakeFactory(); factory.noMessage = true;
        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "receive",
                map("queue", "REPLY.Q", "waitMs", 0), context(caseDir), null, "receive-1");

        assertTrue(result.success());
        assertEquals(Boolean.FALSE, result.result().get("received"));
        assertEquals(2033, result.result().get("reasonCode"));
        assertFalse(result.result().containsKey("replyFile"));
        assertEquals("PASS", result.evidence().get("status"));
    }

    @Test void explicitPhysicalInstanceIsSelectedBeforeOpeningTheConnection() throws Exception {
        Path caseDir = tempDir.resolve("selected-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{7, 8, 9});
        MqHelperConfig a = MqHelperConfig.physical(new MqHelperConfig("payment-a", "Payment", "a", "QM-A", "host-a", 1414,
                "CH-A", "user", "secret-a", 1208, "MQSTR", "asQueue", 10000, "metadata", tempDir.resolve("mq.yaml")), "payment", "payment-a");
        MqHelperConfig b = MqHelperConfig.physical(new MqHelperConfig("payment-b", "Payment", "b", "QM-B", "host-b", 1415,
                "CH-B", "user", "secret-b", 1208, "MQSTR", "asQueue", 10000, "metadata", tempDir.resolve("mq.yaml")), "payment", "payment-b");
        Map<String, MqHelperConfig> instances = new LinkedHashMap<String, MqHelperConfig>();
        instances.put("payment-a", a); instances.put("payment-b", b);
        MqHelperConfig payment = MqHelperConfig.group("payment", "Payment", "Payment MQ", "roundRobin", instances, "metadata", tempDir.resolve("mq.yaml"));
        Map<String, MqHelperConfig> helpers = new LinkedHashMap<String, MqHelperConfig>(); helpers.put("payment", payment);
        FrameworkConfig configured = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tempDir,
                Collections.emptyMap(), Collections.emptyMap(), helpers, null, null,
                null, "", "", null, null, 1, "ignore", "", false, ProcessOutputConfig.defaults());
        FakeFactory factory = new FakeFactory();

        MqInvocationResult result = new MqHelperExecutor(tempDir, configured, factory).execute("payment", "send",
                map("instance", "payment-b", "queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null, "send-selected");

        assertTrue(result.success());
        assertEquals("payment", result.result().get("mqHelper"));
        assertEquals("payment-b", result.result().get("instance"));
        assertEquals("roundRobin", result.result().get("selectionStrategy"));
        assertEquals("payment-b", factory.connectedInstance);
        assertEquals("payment", result.evidence().get("helperId"));
        assertEquals("payment-b", result.evidence().get("physicalInstance"));
        assertFalse(result.operationResult().evidence().toString().contains("secret-b"));
    }

    @Test void randomSelectionUsesOnlyConfiguredInstancesAndCanReachMultipleMembers() throws Exception {
        Path caseDir = tempDir.resolve("round-robin-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{1});
        FrameworkConfig configured = multiConfig("random");
        FakeFactory factory = new FakeFactory();
        MqHelperExecutor executor = new MqHelperExecutor(tempDir, configured, factory);
        for (int index = 0; index < 256; index++) {
            MqInvocationResult result = executor.execute("payment", "send",
                    map("queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null, "random-" + index);
            assertTrue(result.success());
            assertEquals("random", result.operationResult().outputMetadata().get("selectionStrategy"));
        }
        assertTrue(factory.connectedInstances.contains("a"));
        assertTrue(factory.connectedInstances.contains("b"));
        assertTrue(factory.connectedInstances.stream().allMatch(id -> "a".equals(id) || "b".equals(id)));
    }

    @Test void roundRobinSelectionIsBalancedUnderConcurrentInvocations() throws Exception {
        Path caseDir = tempDir.resolve("round-robin-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{1});
        FakeFactory factory = new FakeFactory();
        MqHelperExecutor executor = new MqHelperExecutor(tempDir, multiConfig("roundRobin"), factory);
        int invocations = 128;
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MqInvocationResult>> futures = new ArrayList<Future<MqInvocationResult>>();
        try {
            for (int index = 0; index < invocations; index++) {
                final int invocation = index;
                futures.add(workers.submit(() -> {
                    if (invocation < 8) ready.countDown();
                    start.await();
                    return executor.execute("payment", "send",
                            map("queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null,
                            "concurrent-" + invocation);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<MqInvocationResult> future : futures) assertTrue(future.get(10, TimeUnit.SECONDS).success());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
        long a = factory.connectedInstances.stream().filter("a"::equals).count();
        long b = factory.connectedInstances.stream().filter("b"::equals).count();
        assertEquals(invocations, factory.connectedInstances.size());
        assertTrue(Math.abs(a - b) <= 1, "round robin distribution: a=" + a + ", b=" + b);
    }

    @Test void multiInstanceRequestUsesSameSelectedInstanceForPutAndCorrelatedGet() throws Exception {
        Path caseDir = tempDir.resolve("multi-request-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{1});
        FakeFactory factory = new FakeFactory();
        factory.reply = new MqTransport.Message(new byte[]{2}, new byte[]{1}, new byte[]{3});
        MqInvocationResult result = new MqHelperExecutor(tempDir, multiConfig("roundRobin"), factory).execute("payment", "request",
                map("requestQueue", "REQUEST.Q", "replyQueue", "REPLY.Q", "file", payload.toString()),
                context(caseDir), null, "request-same-instance");

        assertTrue(result.success());
        assertEquals(java.util.Collections.singletonList("a"), factory.putInstances);
        assertEquals(java.util.Collections.singletonList("a"), factory.getInstances);
        assertEquals(factory.putInstances.get(0), factory.getInstances.get(0));
    }

    private FrameworkConfig multiConfig(String strategy) {
        MqHelperConfig a = MqHelperConfig.physical(new MqHelperConfig("a", "Payment", "a", "QM-A", "host-a", 1414,
                "CH-A", "", "", 1208, "MQSTR", "asQueue", 10000, "metadata", tempDir.resolve("mq.yaml")), "payment", "a");
        MqHelperConfig b = MqHelperConfig.physical(new MqHelperConfig("b", "Payment", "b", "QM-B", "host-b", 1414,
                "CH-B", "", "", 1208, "MQSTR", "asQueue", 10000, "metadata", tempDir.resolve("mq.yaml")), "payment", "b");
        Map<String, MqHelperConfig> instances = new LinkedHashMap<String, MqHelperConfig>(); instances.put("a", a); instances.put("b", b);
        Map<String, MqHelperConfig> helpers = new LinkedHashMap<String, MqHelperConfig>();
        helpers.put("payment", MqHelperConfig.group("payment", "Payment", "Payment MQ", strategy, instances, "metadata", tempDir.resolve("mq.yaml")));
        return new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tempDir,
                Collections.emptyMap(), Collections.emptyMap(), helpers, null, null,
                null, "", "", null, null, 1, "ignore", "", false, ProcessOutputConfig.defaults());
    }

    private FrameworkConfig config() { return config("metadata"); }

    private FrameworkConfig config(String evidencePayload) {
        MqHelperConfig helper = new MqHelperConfig("broker", "Broker", "test broker", "QM1", "localhost", 1414,
                "DEV.APP.SVRCONN", "user", "secret", 1208, "MQSTR", "asQueue", 10000, evidencePayload, tempDir.resolve("mq.yaml"));
        Map<String,MqHelperConfig> helpers = new LinkedHashMap<String,MqHelperConfig>(); helpers.put("broker", helper);
        return new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tempDir,
                Collections.emptyMap(), Collections.emptyMap(), helpers, null, null,
                null, "", "", null, null, 1, "ignore", "", false, ProcessOutputConfig.defaults());
    }

    private CaseRuntimeContext context(Path caseDir) {
        return new CaseRuntimeContext(new TestCase(2, "g", "sheet", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null), caseDir, "R", tempDir,
                caseDir.resolve("case.log"));
    }

    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private static final class FakeFactory implements MqTransport.Factory {
        byte[] putPayload;
        MqTransport.PutRequest putRequest;
        MqTransport.GetRequest getRequest;
        MqTransport.Message reply;
        boolean noMessage;
        final List<Boolean> bindNotFixed = new CopyOnWriteArrayList<Boolean>();
        int disconnects;
        int queueCloses;
        String connectedInstance;
        final List<String> connectedInstances = new CopyOnWriteArrayList<String>();
        final List<String> putInstances = new CopyOnWriteArrayList<String>();
        final List<String> getInstances = new CopyOnWriteArrayList<String>();

        @Override public MqTransport.Connection connect(att.config.MqHelperConfig config) {
            connectedInstance = config.instanceId();
            connectedInstances.add(config.instanceId());
            final String connectionInstance = config.instanceId();
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    bindNotFixed.add(Boolean.FALSE);
                    return queue(queue, input, output);
                }
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output, boolean bind) {
                    bindNotFixed.add(Boolean.valueOf(bind));
                    return queue(queue, input, output);
                }
                private MqTransport.Queue queue(String queue, boolean input, boolean output) {
                    return new MqTransport.Queue() {
                        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) {
                            putInstances.add(connectionInstance);
                            putPayload = payload.clone(); putRequest = request;
                            return new MqTransport.Message(new byte[]{1, 2}, null, payload);
                        }
                        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
                            getInstances.add(connectionInstance);
                            getRequest = request;
                            if (noMessage) throw new MqTransport.Exception("No message", 2, 2033, "MQRC_NO_MSG_AVAILABLE", null);
                            return reply == null ? new MqTransport.Message(new byte[]{9}, new byte[]{1, 2}, new byte[0]) : reply;
                        }
                        @Override public void close() { queueCloses++; }
                    };
                }
                @Override public void disconnect() { disconnects++; }
                @Override public void close() { disconnects++; }
            };
        }
    }
}
