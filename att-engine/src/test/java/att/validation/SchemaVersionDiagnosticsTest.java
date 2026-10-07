package att.validation;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SchemaVersionDiagnosticsTest {
    @TempDir Path root;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(root); }

    @Test void warnsOnlyForHistoricalDescriptorsThatRemainRuntimeSupported() throws Exception {
        write("testcase/payment.yaml", "schemaVersion: att-sidecar/v2.1\nid: payment\nexcel: {sheet: Cases, caseId: CaseID, tags: Tags}\nstages: [{key: invoke, template: PAYMENT_INVOKE}]\n");
        write("templates/flows/payment/flow.yaml", "schemaVersion: att-flow/v3.0\nid: payment.flow.v1\nname: Payment\ndescription: Legacy flow\nactions:\n  note: {type: log, message: ok}\n");
        write("templates/payment/template.yaml", "schemaVersion: att-template/v3.1\ndescription: Legacy template\nactions:\n  note: {type: log, message: ok}\n");
        write("templates/current/template.yaml", "schemaVersion: att-template/v3.4\ndescription: Current template\nactions:\n  note: {type: log, message: ok}\n");
        write("config/tools/legacy.yaml", "schemaVersion: att-tool-group/v2.7\nid: legacy\nname: Legacy\ndescription: Legacy tools\ntools:\n  ping: {name: Ping, description: Ping, command: 'echo ok'}\n");
        write("config/config.yaml", "schemaVersion: att-config/v2.8\n");
        write("config/mqhelpers/legacy.yaml", "schemaVersion: att-mqhelper/v1.0\nid: legacy\nname: Legacy MQ\ndescription: Legacy MQ helper\nconnection: {queueManager: QM1, host: localhost, port: 1414, channel: APP.SVRCONN}\n");
        write("templates/unsupported/template.yaml", "schemaVersion: att-template/v99.0\ndescription: Unknown\nactions: {note: {type: log, message: ok}}\n");
        write("templates/invalid/template.yaml", "schemaVersion: att-template/v3.1\ndescription: Invalid under old schema\nactions:\n  note: {type: log, message: ok}\nnewField: true\n");

        List<Diagnostic> warnings = SchemaVersionDiagnostics.collect(root);
        assertEquals(1, warnings.size());
        assertTrue(warnings.stream().anyMatch(item -> item.file().endsWith("/testcase/payment.yaml")
                && item.message().contains("att-sidecar/v2.1") && item.message().contains("att-sidecar/v2.2")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/templates/flows/payment/flow.yaml")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/templates/payment/template.yaml")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/config/tools/legacy.yaml")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/config/config.yaml")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/config/mqhelpers/legacy.yaml")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/templates/current/")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/templates/unsupported/")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/templates/invalid/")));
        assertFalse(warnings.stream().anyMatch(item -> item.file().contains("/schemas/history/")));

        List<Diagnostic> repeated = SchemaVersionDiagnostics.collect(root);
        assertEquals(warnings.size(), repeated.size());
        assertEquals(warnings.stream().map(Diagnostic::file).distinct().count(), warnings.size());
    }

    @Test void packageValidationPublishesHistoricalSchemaAdvisories() throws Exception {
        for (String directory : new String[]{"config", "testcase", "templates", "tools"}) Files.createDirectories(root.resolve(directory));
        write("config/config.yaml", "schemaVersion: att-config/v2.10\n");
        write("testcase/empty.yaml", "schemaVersion: att-sidecar/v2.1\nid: empty\nexcel: {sheet: Cases, caseId: CaseID, tags: Tags}\nstages: [{key: invoke, template: PAYMENT_INVOKE}]\n");
        Files.write(root.resolve("testcase/empty.xlsx"), new byte[]{0});
        Files.createFile(root.resolve("att.sh"));
        Files.createFile(root.resolve("att.bat"));
        FrameworkConfig config = new FrameworkConfig(root, root, root, "SIT", 1000, root,
                Collections.<String, ToolConfig>emptyMap(), null, null);

        PackageValidator.ValidationSummary summary = new PackageValidator(root, config)
                .validate(att.core.ExecutionOptionsTestSupport.parse(new String[]{"validate", "--package"}));
        assertTrue(summary.diagnostics.stream().anyMatch(item -> DiagnosticCodes.SCHEMA_VERSION_OLD.equals(item.code())
                && item.file().endsWith("/testcase/empty.yaml")),
                summary.diagnostics.stream().map(item -> item.code() + ":" + item.file() + ":" + item.message())
                        .collect(java.util.stream.Collectors.joining(" | ")));
    }

    private void write(String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
