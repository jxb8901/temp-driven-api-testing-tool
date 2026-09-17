/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.FrameworkConfig;
import att.config.MqHelperConfig;
import att.config.ProcessOutputConfig;
import att.core.CaseRuntimeContext;
import att.core.TestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
        assertFalse(result.evidence().containsKey("payload"));
        assertEquals(1, factory.disconnects);
        assertEquals(1, factory.queueCloses);
    }

    @Test void requestMatchesReplyCorrelationAndWritesReplyFile() throws Exception {
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
        assertArrayEquals(new byte[]{1, 2}, factory.getRequest.correlationId());
        assertEquals(321, factory.getRequest.waitMs());
        Path replyFile = Paths.get(String.valueOf(result.result().get("replyFile")));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(replyFile));
        assertEquals(3, result.result().get("replyBytes"));
        assertEquals("REPLY.Q", result.evidence().get("replyQueue"));
        assertEquals(2, factory.queueCloses);
        assertEquals(1, factory.disconnects);
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

    private FrameworkConfig config() {
        MqHelperConfig helper = new MqHelperConfig("broker", "Broker", "test broker", "QM1", "localhost", 1414,
                "DEV.APP.SVRCONN", "user", "secret", 1208, "MQSTR", "asQueue", 10000, "metadata", tempDir.resolve("mq.yaml"));
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
        int disconnects;
        int queueCloses;

        @Override public MqTransport.Connection connect(att.config.MqHelperConfig config) {
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    return new MqTransport.Queue() {
                        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) {
                            putPayload = payload.clone(); putRequest = request;
                            return new MqTransport.Message(new byte[]{1, 2}, null, payload);
                        }
                        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
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
