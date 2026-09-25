/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MqHelperConfigLoaderTest {
    @TempDir Path tempDir;

    @Test void loadsV26MqHelperWithoutExposingPasswordInMetadata() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("broker.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.6\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.0\n" +
                "id: broker\nname: Broker\ndescription: Test broker\n" +
                "connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN, username: att, password: secret}\n" +
                "requestReply: {waitMs: 2500}\n").getBytes("UTF-8"));

        FrameworkConfig loaded = new FrameworkConfigLoader().load(config);
        MqHelperConfig broker = loaded.mqHelper("BROKER");
        assertNotNull(broker);
        assertEquals(1414, broker.port());
        assertEquals(2500, broker.requestReplyWaitMs());
        assertTrue(broker.credentialsConfigured());
        assertFalse(broker.metadata().toString().contains("secret"));
        assertFalse(broker.toString().contains("secret"));
    }

    @Test void rejectsDuplicateMqHelperIdsIgnoringCase() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        String content = "schemaVersion: att-mqhelper/v1.0\nid: %s\nname: Broker\ndescription: Test broker\n" +
                "connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN}\n";
        Files.write(directory.resolve("one.yaml"), String.format(content, "broker").getBytes("UTF-8"));
        Files.write(directory.resolve("two.yaml"), String.format(content, "BROKER").getBytes("UTF-8"));
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.6\nmqhelpers: [config/mqhelpers/one.yaml, config/mqhelpers/two.yaml]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config));
    }

    @Test void loadsIssue59MessageDefaultsAndKeepsEmptyFormat() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("broker.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.6\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.0\n" +
                "id: broker\nname: Broker\ndescription: Test broker\n" +
                "connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN}\n" +
                "message: {charset: 1208, encoding: 273, format: \"\", persistence: 0, expiry: -1, requestQueue: REQUEST.Q, replyQueue: REPLY.Q}\n").getBytes("UTF-8"));

        MqHelperConfig broker = new FrameworkConfigLoader().load(config).mqHelper("broker");
        assertEquals(1208, broker.charset());
        assertEquals(1208, broker.ccsid());
        assertEquals(Integer.valueOf(273), broker.encoding());
        assertEquals(Integer.valueOf(-1), broker.expiry());
        assertEquals("", broker.format());
        assertEquals("0", broker.persistence());
        assertEquals("REQUEST.Q", broker.requestQueue());
        assertEquals("REPLY.Q", broker.replyQueue());
    }

    @Test void rejectsConflictingCharsetAndCcsid() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("broker.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.6\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.0\n" +
                "id: broker\nname: Broker\ndescription: Test broker\n" +
                "connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN}\n" +
                "message: {charset: 1208, ccsid: 819}\n").getBytes("UTF-8"));

        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config));
    }

    @Test void loadsV11InstancesWithSectionInheritanceAndPhysicalIdentity() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("payment.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.6\nmqhelpers: [config/mqhelpers/payment.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.1\n" +
                "id: payment\nname: Payment MQ\ndescription: Payment endpoints\n" +
                "defaults:\n" +
                "  connection: {queueManager: QM1, host: mq.default, port: 1414, channel: APP.SVRCONN, username: att, password: secret}\n" +
                "  message: {charset: 1208, requestQueue: REQUEST.Q, replyQueue: REPLY.Q}\n" +
                "  requestReply: {waitMs: 2500}\n" +
                "  pool: {maxSize: 5, minIdle: 1, borrowTimeout: 3s}\n" +
                "instances:\n" +
                "  - id: payment-a\n" +
                "    connection: {host: mq-a}\n" +
                "  - id: payment-b\n" +
                "    connection: {host: mq-b}\n" +
                "    message: {replyQueue: REPLY.B}\n" +
                "    pool: {maxSize: 2}\n" +
                "selection: {strategy: roundRobin}\n" +
                "evidence: {payload: metadata}\n").getBytes("UTF-8"));

        MqHelperConfig payment = new FrameworkConfigLoader().load(config).mqHelper("PAYMENT");
        assertTrue(payment.isGroup());
        assertTrue(payment.isMultiInstance());
        assertEquals("payment", payment.logicalId());
        assertEquals("roundRobin", payment.selectionStrategy());
        assertEquals("mq-a", payment.instance("PAYMENT-A").host());
        assertEquals("QM1", payment.instance("payment-a").queueManager());
        assertEquals("REQUEST.Q", payment.instance("payment-b").requestQueue());
        assertEquals("REPLY.B", payment.instance("payment-b").replyQueue());
        assertEquals(2, payment.instance("payment-b").poolMaxSize());
        assertEquals("payment::payment-a", payment.instance("payment-a").poolKey());
        assertFalse(payment.instance("payment-a").metadata().toString().contains("secret"));
    }

    @Test void rejectsV11MultipleInstancesWithoutSelectionStrategy() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("payment.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.6\nmqhelpers: [config/mqhelpers/payment.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.1\n" +
                "id: payment\nname: Payment\ndescription: Payment\n" +
                "defaults: {connection: {queueManager: QM1, host: localhost, port: 1414, channel: CH}}\n" +
                "instances: [{id: a}, {id: b}]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config));
    }
}
