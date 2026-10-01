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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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

    @Test void rejectsAllSelectionForNativeResourceCallsInsteadOfSelectingOneHost() throws Exception {
        Map<String, SshConfig> instances = new LinkedHashMap<String, SshConfig>();
        instances.put("one", new SshConfig("one.example", "deploy", 22, ""));
        instances.put("two", new SshConfig("two.example", "deploy", 22, ""));
        ToolInvocationResult result = executor(new CommandResult(0, "ok", "", false), null, "all", instances)
                .execute("application", "execute", map("command", "health"), context(), 1000L, "ssh-all",
                        new CaseExecutionLog(root.resolve("all.log")));

        assertFalse(result.executionSuccess());
        assertEquals("SSH_ARGUMENT", ((Map<?, ?>) result.invocation().get("error")).get("category"));
        assertTrue(String.valueOf(((Map<?, ?>) result.invocation().get("error")).get("message")).contains("selection.strategy=all"));
    }

    @Test void poolWaitConsumesTheSameActionDeadlineAsTheRunningOperation() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SshCommandRunner runner = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> {
                    entered.countDown();
                    release.await();
                    return new CommandResult(0, "ok", "", false);
                }, System.err);
        Map<String, SshConfig> instances = Collections.singletonMap("one", new SshConfig("one.example", "deploy", 22, ""));
        SshResourceExecutor executor = executor(new CommandResult(0, "ok", "", false), null,
                "single", instances, 1, 10000, runner);
        FutureTask<ToolInvocationResult> first = new FutureTask<ToolInvocationResult>(() -> executor.execute(
                "application", "execute", map("command", "slow"), context(), 1000L, "ssh-pool-1",
                new CaseExecutionLog(root.resolve("pool-1.log"))));
        Thread firstThread = new Thread(first, "ssh-pool-first");
        firstThread.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        ToolInvocationResult second = executor.execute("application", "execute", map("command", "wait"),
                context(), 60L, "ssh-pool-2", new CaseExecutionLog(root.resolve("pool-2.log")));

        assertFalse(second.executionSuccess());
        assertEquals("SSH_POOL_TIMEOUT", ((Map<?, ?>) second.invocation().get("error")).get("category"));
        release.countDown();
        assertTrue(first.get(1, TimeUnit.SECONDS).executionSuccess());
    }

    @Test void nativeCommandReceivesCappedConnectAndOuterTimeouts() throws Exception {
        final List<Duration> connectTimeouts = new ArrayList<Duration>();
        final List<Duration> operationTimeouts = new ArrayList<Duration>();
        SshCommandRunner.JavaClient javaClient = new SshCommandRunner.JavaClient() {
            @Override public CommandResult run(SshConfig target, String command, Duration timeout, Path projectRoot) {
                return new CommandResult(0, "ok", "", false);
            }

            @Override public CommandResult run(SshConfig target, String command, Duration connectTimeout,
                                               Duration timeout, Path projectRoot) {
                connectTimeouts.add(connectTimeout);
                operationTimeouts.add(timeout);
                return new CommandResult(0, "ok", "", false);
            }
        };
        SshCommandRunner runner = new SshCommandRunner(new CommandRunner(), () -> false, javaClient, System.err);
        Map<String, SshConfig> instances = Collections.singletonMap("one", new SshConfig("one.example", "deploy", 22, ""));
        ToolInvocationResult result = executor(new CommandResult(0, "ok", "", false), null,
                "single", instances, 2, 25, runner).execute("application", "execute",
                map("command", "health", "timeoutMs", 500), context(), 40L, "ssh-timeout",
                new CaseExecutionLog(root.resolve("timeout.log")));

        assertTrue(result.executionSuccess());
        assertEquals(1, connectTimeouts.size());
        assertEquals(1, operationTimeouts.size());
        assertTrue(connectTimeouts.get(0).toMillis() <= 25L);
        assertTrue(operationTimeouts.get(0).toMillis() <= 40L);
    }

    @Test void blockedSftpTransferIsBoundedByTheActionDeadline() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SshTransferClient blocked = new FakeTransfer() {
            @Override public long upload(SshConfig target, Path source, byte[] payload, String remotePath,
                                          boolean overwrite, Duration timeout, Path projectRoot) throws Exception {
                entered.countDown();
                release.await();
                return 0L;
            }
        };
        SshResourceExecutor executor = executor(new CommandResult(0, "ok", "", false), blocked);
        Path source = root.resolve("blocked.txt");
        Files.write(source, "blocked".getBytes(StandardCharsets.UTF_8));

        long started = System.nanoTime();
        ToolInvocationResult result = executor.execute("application", "upload",
                map("remotePath", "/srv/blocked", "localPath", source.toString()), context(), 60L,
                "ssh-transfer-timeout", new CaseExecutionLog(root.resolve("transfer-timeout.log")));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(result.executionSuccess());
        assertEquals("SSH_TIMEOUT", ((Map<?, ?>) result.invocation().get("error")).get("category"));
        assertTrue(elapsedMs < 1000L, "transfer exceeded absolute deadline: " + elapsedMs + "ms");
        release.countDown();
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
        return executor(commandResult, transfer, "single",
                Collections.singletonMap("one", new SshConfig("example.test", "deploy", 22, "")));
    }

    private SshResourceExecutor executor(final CommandResult commandResult, SshTransferClient transfer,
                                         String strategy, Map<String, SshConfig> instances) {
        return executor(commandResult, transfer, strategy, instances, 2, 10000, null);
    }

    private SshResourceExecutor executor(final CommandResult commandResult, SshTransferClient transfer,
                                         String strategy, Map<String, SshConfig> instances,
                                         int maxConcurrency, int connectTimeoutMs, SshCommandRunner providedRunner) {
        SshHelperConfig helper = new SshHelperConfig("application", "Application", "", strategy, maxConcurrency,
                connectTimeoutMs, 60000, instances);
        Map<String, SshHelperConfig> helpers = Collections.singletonMap("application", helper);
        FrameworkConfig config = new FrameworkConfig(root, root.resolve("report"), root.resolve("logs"), "SIT", 5000,
                root, root, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), helpers,
                Collections.emptyMap(), null, null, Collections.emptyList(), "", "", Collections.emptyList(),
                Collections.emptyList(), 1, "ignore", "", false, null);
        SshCommandRunner runner = providedRunner == null
                ? new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> commandResult, System.err)
                : providedRunner;
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

    private static class FakeTransfer implements SshTransferClient {
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
