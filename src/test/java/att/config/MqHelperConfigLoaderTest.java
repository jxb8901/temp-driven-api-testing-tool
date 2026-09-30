/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MqHelperConfigLoaderTest {
    @TempDir Path tempDir;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(tempDir); }

    @Test void loadsV12MqHelperWithoutExposingPasswordInMetadata() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("broker.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.2\n" +
                "id: broker\nname: Broker\ndescription: Test broker\n" +
                "defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN, username: att, password: secret}\n" +
                "  requestReply: {waitMs: 2500}\n" +
                "instances: [{id: primary}]\n").getBytes("UTF-8"));

        FrameworkConfig loaded = new FrameworkConfigLoader().load(config);
        MqHelperConfig broker = loaded.mqHelper("BROKER");
        assertNotNull(broker);
        assertEquals(1414, broker.port());
        assertEquals(2500, broker.requestReplyWaitMs());
        assertEquals("MQSeries Client", broker.transport());
        assertEquals("text", broker.responseFormat());
        assertTrue(broker.credentialsConfigured());
        assertFalse(broker.metadata().toString().contains("secret"));
        assertFalse(broker.toString().contains("secret"));
    }

    @Test void rejectsDuplicateMqHelperIdsIgnoringCase() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        String content = "schemaVersion: att-mqhelper/v1.2\nid: %s\nname: Broker\ndescription: Test broker\n" +
                "defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN}\n" +
                "instances: [{id: primary}]\n";
        Files.write(directory.resolve("one.yaml"), String.format(content, "broker").getBytes("UTF-8"));
        Files.write(directory.resolve("two.yaml"), String.format(content, "BROKER").getBytes("UTF-8"));
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/one.yaml, config/mqhelpers/two.yaml]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config));
    }

    @Test void loadsIssue59MessageDefaultsAndKeepsEmptyFormat() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("broker.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.2\n" +
                "id: broker\nname: Broker\ndescription: Test broker\n" +
                "defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN}\n" +
                "  message: {charset: 1208, encoding: 273, format: \"\", persistence: 0, expiry: -1, requestQueue: REQUEST.Q, replyQueue: REPLY.Q}\n" +
                "instances: [{id: primary}]\n").getBytes("UTF-8"));

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

    @Test void loadsTransportAndResponseFormatForV12Instances() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path group = directory.resolve("group.yaml");
        Files.write(group, ("schemaVersion: att-mqhelper/v1.2\n" +
                "id: group\nname: Group\ndescription: Group MQ\n" +
                "defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.CH, transport: 'MQSeries Bindings'}\n" +
                "  requestReply: {responseFormat: json}\n" +
                "instances:\n  - id: a\n  - id: b\n    connection: {transport: MQSeries}\n    requestReply: {responseFormat: xml}\n" +
                "selection: {strategy: roundRobin}\n").getBytes("UTF-8"));
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/group.yaml]\n").getBytes("UTF-8"));

        FrameworkConfig loaded = new FrameworkConfigLoader().load(config);
        assertEquals("MQSeries Bindings", loaded.mqHelper("group").instance("a").transport());
        assertEquals("json", loaded.mqHelper("group").instance("a").responseFormat());
        assertEquals("MQSeries", loaded.mqHelper("group").instance("b").transport());
        assertEquals("xml", loaded.mqHelper("group").instance("b").responseFormat());
        assertEquals("MQSeries Bindings", loaded.mqHelper("group").instance("a").metadata().get("transport"));
    }

    @Test void v10RejectsV11OnlyTransportAndResponseFormatFields() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("legacy.yaml");
        String prefix = "schemaVersion: att-mqhelper/v1.0\nid: legacy\nname: Legacy\ndescription: Legacy MQ\n";
        String connection = "connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.CH, transport: MQSeries}\n";
        String requestReply = "requestReply: {responseFormat: yaml}\n";
        Files.write(helper, (prefix + connection).getBytes("UTF-8"));
        assertThrows(att.validation.DiagnosticException.class,
                () -> new MqHelperConfigLoader().load(java.util.Collections.singletonList("config/mqhelpers/legacy.yaml"), tempDir));
        Files.write(helper, (prefix + "connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.CH}\n" + requestReply).getBytes("UTF-8"));
        assertThrows(att.validation.DiagnosticException.class,
                () -> new MqHelperConfigLoader().load(java.util.Collections.singletonList("config/mqhelpers/legacy.yaml"), tempDir));
    }

    @Test void rejectsConflictingCharsetAndCcsid() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("broker.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.2\n" +
                "id: broker\nname: Broker\ndescription: Test broker\n" +
                "defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN}\n" +
                "  message: {charset: 1208, ccsid: 819}\n" +
                "instances: [{id: primary}]\n").getBytes("UTF-8"));

        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config));
    }

    @Test void rejectsInvalidMqEncodingBits() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("broker.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.2\n" +
                "id: broker\nname: Broker\ndescription: Test broker\n" +
                "defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.APP.SVRCONN}\n" +
                "  message: {encoding: 999}\n" +
                "instances: [{id: primary}]\n").getBytes("UTF-8"));

        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config));
        assertTrue(MqEncoding.isValid(273));
        assertTrue(MqEncoding.isValid(546));
        assertFalse(MqEncoding.isValid(999));
    }

    @Test void loadsV11InstancesWithSectionInheritanceAndPhysicalIdentity() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("payment.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/payment.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.2\n" +
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
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/payment.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.2\n" +
                "id: payment\nname: Payment\ndescription: Payment\n" +
                "defaults: {connection: {queueManager: QM1, host: localhost, port: 1414, channel: CH}}\n" +
                "instances: [{id: a}, {id: b}]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config));
    }

    @Test void olderMqSchemaFailureIncludesCurrentSchemaMigrationContext() throws Exception {
        Path directory = tempDir.resolve("config/mqhelpers"); Files.createDirectories(directory);
        Path helper = directory.resolve("broker.yaml");
        Path config = tempDir.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(helper, ("schemaVersion: att-mqhelper/v1.0\nid: broker\nname: Broker\ndescription: Test broker\n"
                + "connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV.CH}\n"
                + "selection: {strategy: random}\n").getBytes("UTF-8"));
        att.validation.DiagnosticException error = assertThrows(att.validation.DiagnosticException.class,
                () -> new FrameworkConfigLoader().load(config));
        assertTrue(error.detail().contains("att-mqhelper/v1.0"));
        assertTrue(error.detail().contains("att-mqhelper/v1.2"));
        assertTrue(error.detail().contains("selection"));
        assertFalse(error.schemaViolations().isEmpty());
    }
}
