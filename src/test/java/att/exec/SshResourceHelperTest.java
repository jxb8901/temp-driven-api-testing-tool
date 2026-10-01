package att.exec;

import att.config.FrameworkConfig;
import att.config.SshConfig;
import att.config.SshHelperConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.TestCase;
import att.template.DefaultBuiltInProvider;
import att.template.UnifiedTemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SshResourceHelperTest {
    @TempDir Path root;

    @Test void executesTypedStdoutAndRetainsRemoteDiagnostics() throws Exception {
        SshResourceExecutor executor = executor(new CommandResult(0, "{\"ok\":true}\n", "warning\n", false), null);
        CaseExecutionLog log = new CaseExecutionLog(root.resolve("case.log"));
        ToolInvocationResult result = executor.execute("application", "execute",
                map("command", "health", "stdoutFormat", "json"), context(), 1000L, "ssh-1", log);

        assertTrue(result.executionSuccess());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) result.output()).get("ok"));
        assertEquals("SSH", String.valueOf(result.invocation().get("type")).toUpperCase());
        assertEquals("warning\n", ((Map<?, ?>) result.invocation().get("SSH")).get("stderr"));
        assertEquals("execute", ((Map<?, ?>) result.invocation().get("SSH")).get("operation"));
    }

    @Test void parsesYamlAndXmlStdoutAtTheSshIngressBoundary() throws Exception {
        ToolInvocationResult yaml = executor(new CommandResult(0, "enabled: true\n", "", false), null).execute(
                "application", "execute", map("command", "status", "stdoutFormat", "yaml"),
                context(), 1000L, "ssh-yaml", new CaseExecutionLog(root.resolve("yaml.log")));
        assertTrue(yaml.executionSuccess());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) yaml.output()).get("enabled"));

        ToolInvocationResult xml = executor(new CommandResult(0, "<root><value>1</value></root>\n", "", false), null).execute(
                "application", "execute", map("command", "status", "stdoutFormat", "xml"),
                context(), 1000L, "ssh-xml", new CaseExecutionLog(root.resolve("xml.log")));
        assertTrue(xml.executionSuccess());
        assertEquals("root", ((Map<?, ?>) xml.output()).get("name"));
        assertEquals("1", ((Map<?, ?>) xml.output()).get("value"));
    }

    @Test void distinguishesRemoteExitAndRejectsUnrepresentedStructuredPayload() throws Exception {
        SshResourceExecutor executor = executor(new CommandResult(12, "partial", "denied", false), null);
        ToolInvocationResult failed = executor.execute("application", "execute",
                map("command", "health"), context(), 1000L, "ssh-2", new CaseExecutionLog(root.resolve("exit.log")));
        assertFalse(failed.executionSuccess());
        assertEquals("SSH_REMOTE_EXIT", ((Map<?, ?>) failed.invocation().get("error")).get("category"));
        assertEquals(12, ((Map<?, ?>) failed.invocation().get("SSH")).get("exitCode"));

        ToolInvocationResult rejected = executor.execute("application", "upload",
                map("remotePath", "/tmp/value", "payload", Collections.singletonMap("a", 1)),
                context(), 1000L, "ssh-3", new CaseExecutionLog(root.resolve("payload.log")));
        assertFalse(rejected.executionSuccess());
        assertEquals("SSH_ARGUMENT", ((Map<?, ?>) rejected.invocation().get("error")).get("category"));
    }

    @Test void uploadsAndDownloadsOnlyThroughControlledCaseOutput() throws Exception {
        FakeTransfer transfer = new FakeTransfer();
        SshResourceExecutor executor = executor(new CommandResult(0, "ok", "", false), transfer);
        Path source = root.resolve("source.txt");
        Files.write(source, "upload".getBytes(StandardCharsets.UTF_8));
        CaseRuntimeContext context = context();
        ToolInvocationResult uploaded = executor.execute("application", "upload",
                map("remotePath", "/srv/value", "localPath", source.toString()), context, 1000L, "ssh-4",
                new CaseExecutionLog(root.resolve("upload.log")));
        assertTrue(uploaded.executionSuccess());
        assertEquals(6L, ((Number) ((Map<?, ?>) uploaded.output()).get("bytesTransferred")).longValue());

        ToolInvocationResult downloaded = executor.execute("application", "download",
                map("remotePath", "/srv/value", "localPath", "nested/result.txt"), context, 1000L, "ssh-5",
                new CaseExecutionLog(root.resolve("download.log")));
        assertTrue(downloaded.executionSuccess());
        assertEquals("upload", new String(Files.readAllBytes(context.caseOutputDirectory().resolve("nested/result.txt")), StandardCharsets.UTF_8));
        assertFalse(executor.execute("application", "download",
                map("remotePath", "/srv/value", "localPath", "../escape.txt"), context, 1000L, "ssh-6",
                new CaseExecutionLog(root.resolve("escape.log"))).executionSuccess());
    }

    @Test void unifiedTemplateEngineDispatchesNativeSshCalls() throws Exception {
        SshResourceExecutor executor = executor(new CommandResult(0, "ready\n", "", false), null);
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null, null, null, null, executor, new DefaultBuiltInProvider());
        assertEquals("ssh", engine.callKind("#{ssh.application.execute(command='health')}") );
        ToolInvocationResult result = engine.executeToolAttempt("#{ssh.application.execute(command='health')}", context(),
                new CaseExecutionLog(root.resolve("engine.log")), "ssh-7", "ssh-action", 1000L, "", "", false, false);
        assertEquals("ready\n", result.output());
        assertTrue(result.executionSuccess());
    }

    private SshResourceExecutor executor(final CommandResult commandResult, SshTransferClient transfer) {
        SshHelperConfig helper = new SshHelperConfig("application", "Application", "", "single", 2,
                Collections.singletonMap("one", new SshConfig("example.test", "deploy", 22, "")));
        Map<String, SshHelperConfig> helpers = Collections.singletonMap("application", helper);
        FrameworkConfig config = new FrameworkConfig(root, root.resolve("report"), root.resolve("logs"), "SIT", 5000,
                root, root, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), helpers,
                Collections.emptyMap(), null, null, Collections.emptyList(), "", "", Collections.emptyList(),
                Collections.emptyList(), 1, "ignore", "", false, null);
        SshCommandRunner runner = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> commandResult, System.err);
        return new SshResourceExecutor(root, config, runner, transfer == null ? new FakeTransfer() : transfer);
    }

    private CaseRuntimeContext context() {
        TestCase test = new TestCase(2, "group", "sheet", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        return new CaseRuntimeContext(test, root.resolve("case"), "RUN", root, root.resolve("case.log"));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private static final class FakeTransfer implements SshTransferClient {
        private final Map<String, byte[]> remote = new LinkedHashMap<String, byte[]>();

        @Override public long upload(SshConfig target, Path source, byte[] payload, String remotePath, boolean overwrite,
                                     Duration timeout, Path projectRoot) throws Exception {
            if (!overwrite && remote.containsKey(remotePath)) throw new IOException("remote exists");
            byte[] bytes = source == null ? payload.clone() : Files.readAllBytes(source);
            remote.put(remotePath, bytes);
            return bytes.length;
        }

        @Override public long download(SshConfig target, String remotePath, Path localPath, boolean overwrite,
                                       Duration timeout, Path projectRoot) throws Exception {
            if (!overwrite && Files.exists(localPath)) throw new IOException("local exists");
            byte[] bytes = remote.get(remotePath);
            if (bytes == null) throw new IOException("remote missing");
            Files.write(localPath, bytes);
            return bytes.length;
        }
    }
}
