package att.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadMqIdleTeardownTest {
    @TempDir Path temp;

    @Test void passedIterationsKeepReportAndJobErrorWhenIdleDisconnectsBreak() throws Exception {
        Path root = temp.resolve("load-mq-2009");
        Files.createDirectories(root.resolve("config/mqhelpers"));
        Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("tools"));
        Files.createDirectories(root.resolve("load"));
        att.TestSchemas.install(root);
        writeHelper(root, "fromeaimqhk", "mq01", 2);
        writeHelper(root, "fromeaimqcb", "mq01", 1);
        writeHelper(root, "toeaimq", "hk02", 1);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\ntemplates: {root: templates}\n"
                + "testcase: {root: testcase}\n"
                + "mqhelpers: [config/mqhelpers/fromeaimqhk.yaml, config/mqhelpers/fromeaimqcb.yaml, config/mqhelpers/toeaimq.yaml]\n")
                .getBytes("UTF-8"));
        Files.write(root.resolve("templates/SIMPLE/template.yaml"), ("schemaVersion: att-template/v3.4\n"
                + "name: SIMPLE\ndescription: idle disconnect diagnostic\nactions:\n"
                + "  hk:\n    type: tool\n    call: \"#{mq.fromeaimqhk.send(queue='REQUEST.Q', payload='ok')}\"\n"
                + "  cb:\n    type: tool\n    call: \"#{mq.fromeaimqcb.send(queue='REQUEST.Q', payload='ok')}\"\n"
                + "  toe:\n    type: tool\n    call: \"#{mq.toeaimq.send(queue='REQUEST.Q', payload='ok')}\"\n").getBytes("UTF-8"));
        Path scenario = root.resolve("load/scenario.yaml");
        Files.write(scenario, ("schemaVersion: att-load/v1.9\nworkloads:\n"
                + "  - id: steady\n    target: {type: template, id: SIMPLE}\n"
                + "    load: {users: 1, duration: 300ms}\n    execution: {thinkTime: 0ms}\n"
                + "evidence: {mode: metrics}\n").getBytes("UTF-8"));

        AtomicInteger brokenDisconnects = new AtomicInteger();
        att.exec.MqTransport.Factory transport = helper -> new att.exec.MqTransport.Connection() {
            private final boolean fail = "fromeaimqhk::mq01".equals(helper.poolKey());
            @Override public att.exec.MqTransport.Queue open(String queue, boolean input, boolean output) {
                return new att.exec.MqTransport.Queue() {
                    @Override public att.exec.MqTransport.Message put(byte[] payload, att.exec.MqTransport.PutRequest request) {
                        return new att.exec.MqTransport.Message(new byte[] {1}, null, payload);
                    }
                    @Override public att.exec.MqTransport.Message get(att.exec.MqTransport.GetRequest request) {
                        return new att.exec.MqTransport.Message(new byte[] {1}, null, new byte[0]);
                    }
                    @Override public void close() { }
                };
            }
            @Override public void disconnect() throws Exception {
                if (!fail) return;
                brokenDisconnects.incrementAndGet();
                throw new att.exec.MqTransport.Exception(
                        "MQJE001: Completion Code '2', Reason '2009' /Users/alice/private",
                        2, 2009, "MQRC_CONNECTION_BROKEN", null);
            }
            @Override public void close() throws Exception { disconnect(); }
        };
        DefaultAttService service = new DefaultAttService((outputRoot, loadResult) ->
                new att.load.LoadReportWriter().write(outputRoot, loadResult),
                (packageRoot, config) -> att.load.LoadRunResourcesTestSupport.withMqTransport(packageRoot, config, transport));
        LoadResult result = service.load(new LoadRequest(root, Paths.get("config/config.yaml"), null, null, "mq-2009",
                scenario, null, null, null, null, null, null, null, null, null, null, null, Collections.<String>emptyList()));

        assertEquals("ERROR", result.status());
        assertEquals(3, result.exitCode());
        assertEquals(2, brokenDisconnects.get());
        assertTrue(result.paths().containsKey("report"));
        Path report = Paths.get(result.paths().get("report"));
        assertTrue(Files.isRegularFile(report));
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("PRE_TEARDOWN"), html);
        assertTrue(html.contains("POST_TEARDOWN"), html);

        Map<?, ?> summary = (Map<?, ?>) result.toMap().get("summary");
        assertEquals("ERROR", summary.get("executionStatus"));
        assertEquals("NOT_EVALUATED", summary.get("performanceVerdict"));
        Map<?, ?> metrics = (Map<?, ?>) summary.get("metrics");
        assertTrue(((Number) metrics.get("completed")).longValue() > 0L, metrics.toString());
        assertEquals(0L, ((Number) metrics.get("runtimeError")).longValue(), metrics.toString());
        Map<?, ?> resources = (Map<?, ?>) summary.get("resources");
        Map<?, ?> pre = (Map<?, ?>) ((Map<?, ?>) resources.get("mq")).get("fromeaimqhk::mq01");
        assertEquals("PRE_TEARDOWN", pre.get("observation"));
        assertEquals(0, ((Number) pre.get("failures")).intValue());
        assertEquals(2, ((Number) pre.get("idle")).intValue());
        Map<?, ?> post = (Map<?, ?>) ((Map<?, ?>) resources.get("postTeardown")).get("mq");
        assertEquals("POST_TEARDOWN", ((Map<?, ?>) resources.get("postTeardown")).get("observation"));
        assertEquals(2, ((Number) ((Map<?, ?>) post.get("fromeaimqhk::mq01")).get("disconnectFailureCount")).intValue());
        assertEquals(0, ((Number) ((Map<?, ?>) post.get("fromeaimqcb::mq01")).get("disconnectFailureCount")).intValue());
        assertEquals(0, ((Number) ((Map<?, ?>) post.get("toeaimq::hk02")).get("disconnectFailureCount")).intValue());

        Map<?, ?> teardown = (Map<?, ?>) summary.get("teardown");
        assertEquals(2L, ((Number) teardown.get("failedCloseCount")).longValue());
        List<?> teardownResources = (List<?>) teardown.get("resources");
        assertEquals(1, teardownResources.size());
        Map<?, ?> failed = (Map<?, ?>) teardownResources.get(0);
        assertEquals("fromeaimqhk:mq01", failed.get("logicalId"));
        assertEquals("fromeaimqhk::mq01", failed.get("poolKey"));
        assertEquals(2L, ((Number) failed.get("mqCompletionCode")).longValue());
        assertEquals(2009L, ((Number) failed.get("mqReasonCode")).longValue());
        assertEquals("PHYSICAL_DISCONNECT_FAILED", failed.get("failureClass"));
        assertEquals("NOT_APPLIED", failed.get("safeCloseClassification"));
        assertTrue(String.valueOf(failed.get("causeCodes")).contains("MQRC_CONNECTION_BROKEN"));
        assertTrue(String.valueOf(failed.get("causeCodes")).contains("MQJE001"));
        assertFalse(failed.toString().contains("fromeaimqcb"));
        assertFalse(failed.toString().contains("toeaimq"));
        assertFalse(result.toMap().toString().contains("/Users/alice/private"));

        Map<?, ?> worker = result.toWorkerMap();
        Map<?, ?> workerSummary = (Map<?, ?>) worker.get("summary");
        Map<?, ?> primary = (Map<?, ?>) ((Map<?, ?>) workerSummary.get("diagnostics")).get("primaryError");
        assertEquals("MQ_RESOURCE_TEARDOWN_FAILED", primary.get("code"));
        assertEquals("fromeaimqhk:mq01", primary.get("logicalId"));
        assertEquals("fromeaimqhk::mq01", primary.get("poolKey"));
        assertEquals(2L, ((Number) primary.get("failedCloseCount")).longValue());
        assertEquals(2009L, ((Number) primary.get("mqReasonCode")).longValue());
        assertEquals("PHYSICAL_DISCONNECT_FAILED", primary.get("failureClass"));
        assertEquals("NOT_APPLIED", primary.get("safeCloseClassification"));
        assertFalse(primary.toString().contains("fromeaimqcb"));
        assertFalse(primary.toString().contains("toeaimq"));
        assertFalse(primary.toString().contains("/Users/alice"));
        assertFalse(primary.toString().contains("REQUEST.Q"));
        Map<?, ?> projectedAgain = att.server.api.LoadTerminalResult.projectOperationResult(worker, "ERROR", 3);
        Map<?, ?> second = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) projectedAgain.get("summary")).get("diagnostics")).get("primaryError");
        assertEquals("fromeaimqhk::mq01", second.get("poolKey"));
        assertEquals("fromeaimqhk:mq01", second.get("logicalId"));
        assertEquals(2009L, ((Number) second.get("mqReasonCode")).longValue());
        assertEquals("PHYSICAL_DISCONNECT_FAILED", second.get("failureClass"));
    }

    private static void writeHelper(Path root, String logicalId, String instanceId, int minIdle) throws Exception {
        Files.write(root.resolve("config/mqhelpers/" + logicalId + ".yaml"), ("schemaVersion: att-mqhelper/v1.3\n"
                + "id: " + logicalId + "\nname: " + logicalId + "\ndescription: teardown identity\n"
                + "defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: CH}\n"
                + "  message: {requestQueues: [REQUEST.Q]}\n"
                + "instances:\n  - id: " + instanceId + "\n    pool: {maxSize: 2, minIdle: " + minIdle + ", borrowTimeout: 2s}\n")
                .getBytes("UTF-8"));
    }
}
