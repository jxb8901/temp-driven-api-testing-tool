/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.FrameworkConfig;
import att.config.MqHelperConfig;
import att.config.ProcessOutputConfig;
import att.core.CaseRuntimeContext;
import att.core.CaseExecutionLog;
import att.core.TestCase;
import att.core.StageCaseData;
import att.core.ResultStatus;
import att.template.StageTemplate;
import att.template.StageTemplateRunner;
import att.template.TemplateAction;
import att.template.UnifiedTemplateEngine;
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
        factory.reply = new MqTransport.Message(new byte[]{3, 4}, new byte[]{1, 2}, "reply".getBytes("UTF-8"));
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
        assertEquals("reply", result.result().get("result"));
        assertEquals(5, result.result().get("replyBytes"));
        assertEquals("REPLY.Q", result.evidence().get("replyQueue"));
        assertEquals(2, factory.queueCloses);
        assertEquals(1, factory.disconnects);
    }

    @Test void queueDefaultsAreOperationSpecificAndExplicitArgumentsWin() throws Exception {
        Path caseDir = tempDir.resolve("queue-defaults"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{1, 2});
        FrameworkConfig defaults = configWithQueues("REQUEST.DEFAULT", "REPLY.DEFAULT");

        FakeFactory sendFactory = new FakeFactory();
        MqInvocationResult send = new MqHelperExecutor(tempDir, defaults, sendFactory).execute("broker", "send",
                map("file", payload.toString()), context(caseDir), null, "send-default-queue");
        assertTrue(send.success());
        assertEquals("REQUEST.DEFAULT", send.result().get("queue"));

        FakeFactory receiveFactory = new FakeFactory();
        MqInvocationResult receive = new MqHelperExecutor(tempDir, defaults, receiveFactory).execute("broker", "receive",
                Collections.<String, Object>emptyMap(), context(caseDir), null, "receive-default-queue");
        assertTrue(receive.success());
        assertEquals("REPLY.DEFAULT", receive.result().get("queue"));

        FakeFactory requestFactory = new FakeFactory();
        MqInvocationResult request = new MqHelperExecutor(tempDir, defaults, requestFactory).execute("broker", "request",
                map("file", payload.toString()), context(caseDir), null, "request-default-queues");
        assertTrue(request.success());
        assertEquals("REQUEST.DEFAULT", request.result().get("queue"));
        assertEquals("REPLY.DEFAULT", requestFactory.putRequest.replyQueue());
        assertEquals("REPLY.DEFAULT", request.result().get("replyQueue"));

        FakeFactory overrideFactory = new FakeFactory();
        MqInvocationResult override = new MqHelperExecutor(tempDir, defaults, overrideFactory).execute("broker", "send",
                map("queue", "REQUEST.EXPLICIT", "file", payload.toString()), context(caseDir), null,
                "send-explicit-queue");
        assertTrue(override.success());
        assertEquals("REQUEST.EXPLICIT", override.result().get("queue"));
    }

    @Test void timedOutSendReceiveAndRequestAttemptsAreRetriedAsTimeouts() throws Exception {
        for (String operation : new String[]{"send", "receive", "request"}) {
            Path caseDir = tempDir.resolve("retry-" + operation);
            Files.createDirectories(caseDir);
            Files.write(caseDir.resolve("payload.bin"), new byte[]{1, 2, 3});
            FakeFactory factory = new FakeFactory();
            if ("send".equals(operation) || "request".equals(operation)) factory.firstPutDelayMs = 60L;
            else factory.firstGetDelayMs = 60L;
            Map<String, Object> args = "send".equals(operation)
                    ? map("queue", "REQUEST.Q", "file", "payload.bin")
                    : "receive".equals(operation)
                    ? map("queue", "REPLY.Q", "waitMs", 100)
                    : map("requestQueue", "REQUEST.Q", "replyQueue", "REPLY.Q", "file", "payload.bin", "waitMs", 100);
            String call = "#{mq.broker." + operation + "(" + callArguments(args) + ")}";
            Map<String, Object> retry = map("maxAttempts", 2, "intervalMs", 0, "retryOn", Collections.singletonList("TIMEOUT"));
            TemplateAction action = new TemplateAction("mqRetry", map("type", "tool", "call", call,
                    "timeoutMs", 20, "retry", retry));
            CaseRuntimeContext context = context(caseDir);
            context.beginStage(new StageCaseData("mq", "MQ", Collections.<String, Object>emptyMap()), "MQ", tempDir);

            List<att.core.ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(
                    null, null, new MqHelperExecutor(tempDir, config(), factory)))
                    .execute("mq", new StageTemplate("MQ", tempDir, Collections.singletonList(action)), context,
                            new CaseExecutionLog(caseDir.resolve("case.log")));

            assertEquals(ResultStatus.PASS, results.get(0).status(), operation + ": " + results.get(0).message());
            assertEquals(2, ((List<?>) context.resolve("ACTIONS.mqRetry.output.attempts")).size(), operation);
            assertEquals("TIMEOUT", context.resolve("ACTIONS.mqRetry.output.attempts[0].retryReason"), operation);
            assertEquals("PASS", context.resolve("ACTIONS.mqRetry.output.attempts[1].status"), operation);
        }
    }

    @Test void receiveRecalculatesGetWaitAfterQueueOpenConsumesDeadline() throws Exception {
        Path caseDir = tempDir.resolve("remaining-mq-deadline"); Files.createDirectories(caseDir);
        FakeFactory factory = new FakeFactory();
        factory.firstOpenDelayMs = 180L;
        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "receive",
                map("queue", "REPLY.Q", "waitMs", 1000), context(caseDir), Long.valueOf(1000L), "receive-deadline");

        assertTrue(result.success());
        assertNotNull(factory.getRequest);
        assertTrue(factory.getRequest.waitMs() < 900, "GET wait must be recomputed after the delayed open");
    }

    @Test void rawActionResultFormatIsRejectedBeforeConnecting() throws Exception {
        Path caseDir = tempDir.resolve("saved-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{7, 8, 9});
        FakeFactory factory = new FakeFactory();
        factory.reply = new MqTransport.Message(new byte[]{3}, new byte[]{1, 2}, "reply".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new MqHelperExecutor(tempDir, config(), factory).execute("broker", "request",
                map("requestQueue", "REQUEST.Q", "replyQueue", "REPLY.Q", "file", payload.toString()),
                context(caseDir), null, "request-2", "request", "responses/reply.txt", "raw", false));
        assertTrue(factory.connectedInstances.isEmpty());
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
        assertTrue(log.contains("[ACTION reply RESULT]"), log);
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

    @Test void sendRejectsResultPersistenceBeforeConnecting() throws Exception {
        Path caseDir = tempDir.resolve("send-save-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{1});
        FakeFactory factory = new FakeFactory();
        MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "send",
                map("queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null, "send-save",
                "send", "sent.bin", "raw", false);

        assertFalse(result.success());
        assertTrue(String.valueOf(((Map<?, ?>) result.result().get("error")).get("message")).contains("does not support result persistence"));
        assertTrue(factory.connectedInstances.isEmpty());
    }

    @Test void savedMqArtifactUsesCommonFlowActionRoot() throws Exception {
        Path caseDir = tempDir.resolve("flow-save-case"); Files.createDirectories(caseDir);
        FakeFactory factory = new FakeFactory();
        factory.reply = new MqTransport.Message(new byte[]{3}, new byte[]{1}, "reply".getBytes("UTF-8"));
        CaseRuntimeContext context = context(caseDir);
        context.beginFlow("common.save.v1", "saveFlow");
        try {
            MqInvocationResult result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "receive",
                    map("queue", "REPLY.Q"), context, null, "receive-flow", "reply", "responses/reply.txt", "text", false);
            assertTrue(result.success());
            Path expected = caseDir.resolve("flows/saveFlow/actions/reply/responses/reply.txt");
            assertEquals(expected.toString(), result.result().get("outputFile"));
            assertEquals("reply", new String(Files.readAllBytes(expected), "UTF-8"));
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
        Path logPath = caseDir.resolve("case.log");
        MqInvocationResult result;
        try (CaseExecutionLog log = new CaseExecutionLog(logPath)) {
            result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "receive",
                    map("queue", "REPLY.Q", "waitMs", 0), context(caseDir), null, "receive-1",
                    "receive", null, "text", false, log);
        }

        assertTrue(result.success());
        assertEquals(Boolean.FALSE, result.result().get("received"));
        assertEquals(2033, result.result().get("reasonCode"));
        assertFalse(result.result().containsKey("replyFile"));
        assertEquals("PASS", result.evidence().get("status"));
        assertFalse(new String(Files.readAllBytes(logPath), "UTF-8").contains("ATT INTERNAL ERROR"));
    }

    @Test void unexpectedWrappedAdapterFailureLogsPhaseCauseAndSanitizedStackOnly() throws Exception {
        Path caseDir = tempDir.resolve("internal-mq-case"); Files.createDirectories(caseDir);
        Path payload = caseDir.resolve("request.bin"); Files.write(payload, new byte[]{1});
        FakeFactory factory = new FakeFactory();
        factory.connectFailure = new NullPointerException("password=secret");
        Path logPath = caseDir.resolve("case.log");
        MqInvocationResult result;
        try (CaseExecutionLog log = new CaseExecutionLog(logPath)) {
            result = new MqHelperExecutor(tempDir, config(), factory).execute("broker", "send",
                    map("queue", "REQUEST.Q", "file", payload.toString()), context(caseDir), null, "internal-1",
                    "send", null, "text", false, log);
        }

        String caseLog = new String(Files.readAllBytes(logPath), "UTF-8");
        assertFalse(result.success());
        assertTrue(caseLog.contains("[ATT INTERNAL ERROR]"), caseLog);
        assertTrue(caseLog.contains("phase: mq.connect"), caseLog);
        assertTrue(caseLog.contains("java.lang.NullPointerException"), caseLog);
        assertTrue(caseLog.contains("att.exec.MqHelperExecutor.execute"), caseLog);
        assertTrue(caseLog.contains("password=[REDACTED_SECRET]"), caseLog);
        assertFalse(caseLog.contains("password=secret"), caseLog);
        Map<?, ?> error = (Map<?, ?>) result.result().get("error");
        assertEquals(Boolean.TRUE, error.get("internal"));
        assertEquals("mq.connect", error.get("phase"));
        assertFalse(result.operationResult().evidence().toString().contains("stackTrace"));
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

    private FrameworkConfig configWithQueues(String requestQueue, String replyQueue) {
        MqHelperConfig helper = new MqHelperConfig("broker", "Broker", "test broker", "QM1", "localhost", 1414,
                "DEV.APP.SVRCONN", "user", "secret", 1208, null, null, "MQSTR", "asQueue",
                requestQueue, replyQueue, 10000, "metadata", 20, 0, 2000L, tempDir.resolve("mq.yaml"));
        Map<String, MqHelperConfig> helpers = new LinkedHashMap<String, MqHelperConfig>();
        helpers.put("broker", helper);
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

    private String callArguments(Map<String, Object> args) {
        List<String> values = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : args.entrySet()) values.add(entry.getKey() + "='" + entry.getValue() + "'");
        return String.join(", ", values);
    }

    private static final class FakeFactory implements MqTransport.Factory {
        byte[] putPayload;
        MqTransport.PutRequest putRequest;
        MqTransport.GetRequest getRequest;
        MqTransport.Message reply;
        boolean noMessage;
        final List<Boolean> bindNotFixed = new CopyOnWriteArrayList<Boolean>();
        int disconnects;
        long firstPutDelayMs;
        long firstGetDelayMs;
        long firstOpenDelayMs;
        int putCalls;
        int getCalls;
        int openCalls;
        Throwable connectFailure;
        int queueCloses;
        String connectedInstance;
        final List<String> connectedInstances = new CopyOnWriteArrayList<String>();
        final List<String> putInstances = new CopyOnWriteArrayList<String>();
        final List<String> getInstances = new CopyOnWriteArrayList<String>();

        @Override public MqTransport.Connection connect(att.config.MqHelperConfig config) throws Exception {
            if (connectFailure instanceof NullPointerException) throw new MqTransport.Exception("MQ adapter failed",
                    null, null, null, new NullPointerException(connectFailure.getMessage()));
            if (connectFailure instanceof Exception) throw (Exception) connectFailure;
            if (connectFailure instanceof Error) throw (Error) connectFailure;
            connectedInstance = config.instanceId();
            connectedInstances.add(config.instanceId());
            final String connectionInstance = config.instanceId();
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    if (openCalls++ == 0) delay(firstOpenDelayMs);
                    bindNotFixed.add(Boolean.FALSE);
                    return queue(queue, input, output);
                }
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output, boolean bind) {
                    if (openCalls++ == 0) delay(firstOpenDelayMs);
                    bindNotFixed.add(Boolean.valueOf(bind));
                    return queue(queue, input, output);
                }
                private MqTransport.Queue queue(String queue, boolean input, boolean output) {
                    return new MqTransport.Queue() {
                        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) {
                            if (putCalls++ == 0) delay(firstPutDelayMs);
                            putInstances.add(connectionInstance);
                            putPayload = payload.clone(); putRequest = request;
                            return new MqTransport.Message(new byte[]{1, 2}, null, payload);
                        }
                        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
                            if (getCalls++ == 0) Thread.sleep(firstGetDelayMs);
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

        private void delay(long millis) {
            try { Thread.sleep(millis); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
