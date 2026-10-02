package att.exec;

import att.config.FrameworkConfig;
import att.config.SshConfig;
import att.config.SshHelperConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.StageCaseData;
import att.core.TestCase;
import att.template.DefaultBuiltInProvider;
import att.template.StageTemplate;
import att.template.StageTemplateRunner;
import att.template.TemplateAction;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertFalse(((Map<?, ?>) result.invocation().get("input")).containsKey("command"));
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
        assertEquals("sftp", ((Map<?, ?>) result.invocation().get("SSH")).get("transport"));
        assertTrue(elapsedMs < 1000L, "transfer exceeded absolute deadline: " + elapsedMs + "ms");
        release.countDown();
    }

    @Test void stubbornSftpTransferReturnsAtDeadlineButKeepsPermitUntilWorkerStops() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean postTimeoutSideEffect = new AtomicBoolean();
        SshTransferClient stubborn = new FakeTransfer() {
            @Override public long upload(SshConfig target, Path source, byte[] payload, String remotePath,
                                          boolean overwrite, Duration timeout, Path projectRoot) throws Exception {
                entered.countDown();
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted.countDown();
                    }
                }
                postTimeoutSideEffect.set(true);
                return 0L;
            }
        };
        SshResourceExecutor executor = executor(new CommandResult(0, "ok", "", false), stubborn,
                "single", Collections.singletonMap("one", new SshConfig("one.example", "deploy", 22, "")),
                1, 10000, null);
        Path source = root.resolve("stubborn.txt");
        Files.write(source, "stubborn".getBytes(StandardCharsets.UTF_8));
        FutureTask<ToolInvocationResult> first = new FutureTask<ToolInvocationResult>(() -> executor.execute(
                "application", "upload", map("remotePath", "/srv/stubborn", "localPath", source.toString()),
                context(), 60L, "ssh-stubborn-1", new CaseExecutionLog(root.resolve("stubborn-1.log"))));
        Thread firstThread = new Thread(first, "ssh-stubborn-first");
        firstThread.start();

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        ToolInvocationResult firstResult = first.get(1, TimeUnit.SECONDS);
        assertFalse(firstResult.executionSuccess());
        assertEquals("SSH_TIMEOUT", ((Map<?, ?>) firstResult.invocation().get("error")).get("category"));
        assertFalse(postTimeoutSideEffect.get(), "transfer must not complete a side effect while timeout is pending");
        ToolInvocationResult second = executor.execute("application", "upload",
                map("remotePath", "/srv/second", "payload", "second"), context(), 60L,
                "ssh-stubborn-2", new CaseExecutionLog(root.resolve("stubborn-2.log")));
        assertFalse(second.executionSuccess());
        assertEquals("SSH_POOL_TIMEOUT", ((Map<?, ?>) second.invocation().get("error")).get("category"));

        release.countDown();
        long waitStarted = System.nanoTime();
        while (!postTimeoutSideEffect.get() && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStarted) < 1000L)
            Thread.yield();
        assertTrue(postTimeoutSideEffect.get());
        ToolInvocationResult third = executor.execute("application", "upload",
                map("remotePath", "/srv/third", "payload", "third"), context(), 1000L,
                "ssh-stubborn-3", new CaseExecutionLog(root.resolve("stubborn-3.log")));
        assertTrue(third.executionSuccess());
    }

    @Test void nativeSshTimeoutRetriesThroughTheStandardActionPath() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SshCommandRunner runner = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> calls.incrementAndGet() == 1
                        ? new CommandResult(-1, "", "timed out", true)
                        : new CommandResult(0, "ready", "", false), System.err);
        SshResourceExecutor executor = executor(new CommandResult(0, "unused", "", false), null,
                "single", Collections.singletonMap("one", new SshConfig("one.example", "deploy", 22, "")),
                1, 10000, runner);
        CaseRuntimeContext runtime = context();
        runtime.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", root);
        TemplateAction action = new TemplateAction("ssh", map("type", "tool",
                "call", "#{ssh.application.execute(command='health')}",
                "retry", map("maxAttempts", 2, "intervalMs", 0, "retryOn", Collections.singletonList("TIMEOUT"))));

        List<att.core.ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(
                null, null, null, null, executor, new DefaultBuiltInProvider()))
                .execute("invoke", new StageTemplate("T", root, Collections.singletonList(action)), runtime,
                        new CaseExecutionLog(root.resolve("ssh-retry.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        assertEquals(2, calls.get());
        List<?> attempts = (List<?>) runtime.resolve("ACTIONS.ssh.output.attempts");
        assertEquals(2, attempts.size());
        assertFalse(((Map<?, ?>) ((Map<?, ?>) attempts.get(0)).get("input")).containsKey("command"));
        assertEquals("TIMEOUT", runtime.resolve("ACTIONS.ssh.output.attempts[0].retryReason"));
        assertEquals("ready", String.valueOf(runtime.resolve("ACTIONS.ssh.output.result")).trim());
    }

    @Test void callBackedSshTimeoutRetriesThroughTheStandardActionPath() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SshCommandRunner runner = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> calls.incrementAndGet() == 1
                        ? new CommandResult(-1, "", "timed out", true)
                        : new CommandResult(0, "ready", "", false), System.err);
        SshResourceExecutor executor = executor(new CommandResult(0, "unused", "", false), null,
                "single", Collections.singletonMap("one", new SshConfig("one.example", "deploy", 22, "")),
                1, 10000, runner);
        att.config.ToolConfig tool = new att.config.ToolConfig("checkRemote", "checkRemote", "", "Check", "",
                Collections.<String>emptyList(), "#{ssh.application.execute(command=${TOOL.input.command})}",
                Collections.<String>emptyList(), "",
                Collections.singletonMap("command", new att.config.ToolArgumentConfig("command", "", "", true, "")),
                null, null);
        FrameworkConfig toolConfig = new FrameworkConfig(root, root, root, "SIT", 5000, root,
                Collections.singletonMap("checkRemote", tool), null, null);
        CaseRuntimeContext runtime = context();
        runtime.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", root);
        TemplateAction action = new TemplateAction("ssh", map("type", "tool",
                "call", "#{checkRemote(command='health')}",
                "retry", map("maxAttempts", 2, "intervalMs", 0, "retryOn", Collections.singletonList("TIMEOUT"))));

        List<att.core.ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(
                new ToolInvoker(root, toolConfig), null, null, null, executor, new DefaultBuiltInProvider()))
                .execute("invoke", new StageTemplate("T", root, Collections.singletonList(action)), runtime,
                        new CaseExecutionLog(root.resolve("ssh-retry.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        assertEquals(2, calls.get());
        List<?> attempts = (List<?>) runtime.resolve("ACTIONS.ssh.output.attempts");
        assertEquals(2, attempts.size());
        assertEquals("call", runtime.resolve("ACTIONS.ssh.output.attempts[0].implementation"));
        assertEquals("SSH_TIMEOUT", runtime.resolve("ACTIONS.ssh.output.attempts[0].SSH.error.category"));
        assertEquals("TIMEOUT", runtime.resolve("ACTIONS.ssh.output.attempts[0].retryReason"));
        assertEquals("ready", String.valueOf(runtime.resolve("ACTIONS.ssh.output.result")).trim());
    }

    @Test void sftpConnectTimeoutBeforeActionDeadlineHasTypedTimeoutEvidence() throws Exception {
        Path knownHosts = root.resolve("known_hosts");
        Files.write(knownHosts, new byte[0]);
        // A real TCP peer accepts the connection but never sends an SSH banner.
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            FutureTask<java.net.Socket> accepted = new FutureTask<java.net.Socket>(() -> server.accept());
            Thread peer = new Thread(accepted, "ssh-silent-peer");
            peer.setDaemon(true);
            peer.start();
            SshResourceExecutor executor = executor(new CommandResult(0, "unused", "", false),
                    new JschSshTransferClient(knownHosts), "single",
                    Collections.singletonMap("one", new SshConfig("127.0.0.1", "deploy", server.getLocalPort(), "")),
                    1, 100, null);
            ToolInvocationResult result;
            long started = System.nanoTime();
            try {
                result = executor.execute("application", "upload",
                        map("remotePath", "/srv/value", "payload", "value"), context(), 5000L,
                        "ssh-connect-timeout", new CaseExecutionLog(root.resolve("connect-timeout.log")));
            } finally {
                accepted.get(1, TimeUnit.SECONDS).close();
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertFalse(result.executionSuccess());
            assertEquals("SSH_TIMEOUT", ((Map<?, ?>) result.invocation().get("error")).get("category"));
            Map<?, ?> evidence = (Map<?, ?>) result.invocation().get("SSH");
            assertEquals("SSH_TIMEOUT", ((Map<?, ?>) evidence.get("error")).get("category"));
            assertEquals("sftp", evidence.get("transport"));
            assertEquals("connect", evidence.get("phase"));
            assertTrue(((Number) evidence.get("connectTimeoutMs")).longValue() <= 100L);
            assertTrue(elapsedMs < 2000L, "connect timeout must precede the 5s Action deadline");
        }
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

    @Test void nonTimeoutSftpConnectionFailureHasConnectionCategoryForBothOperations() throws Exception {
        Path knownHosts = root.resolve("refused_known_hosts");
        Files.write(knownHosts, new byte[0]);
        // Accept the TCP connection and close it before the SSH handshake so the failure
        // is a deterministic connection error rather than a platform-dependent refusal timeout.
        for (String operation : new String[]{"upload", "download"}) {
            try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1,
                    java.net.InetAddress.getByName("127.0.0.1"))) {
                FutureTask<Void> peer = new FutureTask<Void>(() -> {
                    try (java.net.Socket accepted = server.accept()) {
                        return null;
                    }
                });
                Thread peerThread = new Thread(peer, "ssh-closing-peer");
                peerThread.setDaemon(true);
                peerThread.start();
                SshResourceExecutor executor = executor(new CommandResult(0, "unused", "", false),
                        new JschSshTransferClient(knownHosts), "single",
                        Collections.singletonMap("one", new SshConfig("127.0.0.1", "deploy", server.getLocalPort(), "")),
                        1, 1000, null);
                Map<String, Object> input = "upload".equals(operation)
                        ? map("remotePath", "/srv/value", "payload", "value")
                        : map("remotePath", "/srv/value", "localPath", "refused.txt");
                ToolInvocationResult result = executor.execute("application", operation, input,
                        context(), 5000L, "ssh-refused-" + operation,
                        new CaseExecutionLog(root.resolve("refused-" + operation + ".log")));
                assertFalse(result.executionSuccess());
                assertEquals("SSH_CONNECTION_ERROR", ((Map<?, ?>) result.invocation().get("error")).get("category"));
                Map<?, ?> evidence = (Map<?, ?>) result.invocation().get("SSH");
                assertEquals("SSH_CONNECTION_ERROR", ((Map<?, ?>) evidence.get("error")).get("category"));
                assertEquals("connect", evidence.get("phase"));
                assertEquals(operation, evidence.get("operation"));
                assertEquals("sftp", evidence.get("transport"));
                assertNull(peer.get(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test void commandAuthenticationFailureRetainsItsCategoryThroughTheBoundary() throws Exception {
        SshCommandRunner runner = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> {
                    throw new IOException("SSH execution failed", new com.jcraft.jsch.JSchException("Auth fail"));
                }, System.err);
        ToolInvocationResult result = executor(new CommandResult(0, "unused", "", false), null,
                "single", Collections.singletonMap("one", new SshConfig("one.example", "deploy", 22, "")),
                1, 1000, runner).execute("application", "execute", map("command", "health"),
                context(), 5000L, "ssh-auth", new CaseExecutionLog(root.resolve("auth.log")));
        assertFalse(result.executionSuccess());
        assertEquals("SSH_AUTH_ERROR", ((Map<?, ?>) result.invocation().get("error")).get("category"));
        assertEquals("SSH_AUTH_ERROR", ((Map<?, ?>) ((Map<?, ?>) result.invocation().get("SSH")).get("error")).get("category"));
    }

    @Test void sftpIdentityInitializationFailureHasAuthenticationCategory() throws Exception {
        Path knownHosts = root.resolve("identity_known_hosts");
        Files.write(knownHosts, new byte[0]);
        ToolInvocationResult result = executor(new CommandResult(0, "unused", "", false),
                new JschSshTransferClient(knownHosts), "single",
                Collections.singletonMap("one", new SshConfig("127.0.0.1", "deploy", 22,
                        root.resolve("missing-private-key").toString())),
                1, 1000, null).execute("application", "upload", map("remotePath", "/srv/value", "payload", "value"),
                context(), 5000L, "ssh-identity", new CaseExecutionLog(root.resolve("identity.log")));
        assertFalse(result.executionSuccess());
        assertEquals("SSH_AUTH_ERROR", ((Map<?, ?>) result.invocation().get("error")).get("category"));
        assertEquals("sftp", ((Map<?, ?>) result.invocation().get("SSH")).get("transport"));
    }

    @Test void sftpTransferFailuresHaveOperationSpecificCategories() throws Exception {
        SshTransferClient failed = new FakeTransfer() {
            @Override public long upload(SshConfig target, Path source, byte[] payload, String remotePath,
                    boolean overwrite, Duration timeout, Path projectRoot) throws Exception {
                throw new com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_PERMISSION_DENIED, "permission denied");
            }
            @Override public long download(SshConfig target, String remotePath, Path localPath,
                    boolean overwrite, Duration timeout, Path projectRoot) throws Exception {
                throw new com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE, "remote missing");
            }
        };
        SshResourceExecutor executor = executor(new CommandResult(0, "unused", "", false), failed);
        for (String operation : new String[]{"upload", "download"}) {
            Map<String, Object> input = "upload".equals(operation)
                    ? map("remotePath", "/srv/value", "payload", "value")
                    : map("remotePath", "/srv/value", "localPath", "failed.txt");
            String category = "upload".equals(operation) ? "SSH_UPLOAD_ERROR" : "SSH_DOWNLOAD_ERROR";
            ToolInvocationResult result = executor.execute("application", operation, input,
                    context(), 1000L, "ssh-failed-" + operation,
                    new CaseExecutionLog(root.resolve("failed-" + operation + ".log")));
            assertFalse(result.executionSuccess());
            assertEquals(category, ((Map<?, ?>) result.invocation().get("error")).get("category"));
            Map<?, ?> evidence = (Map<?, ?>) result.invocation().get("SSH");
            assertEquals(category, ((Map<?, ?>) evidence.get("error")).get("category"));
            assertEquals(operation, evidence.get("operation"));
            assertEquals("sftp", evidence.get("transport"));
        }
    }

    @Test void openSshExit255HasDocumentedAmbiguousTransportCategory() throws Exception {
        CommandRunner commandRunner = new CommandRunner() {
            @Override public CommandResult run(List<String> argv, Duration timeout, Path project) {
                return new CommandResult(255, "", "Permission denied (publickey).", false);
            }
        };
        SshCommandRunner runner = new SshCommandRunner(commandRunner, () -> true,
                (target, command, timeout, project) -> new CommandResult(0, "", "", false), System.err);
        ToolInvocationResult result = executor(new CommandResult(0, "unused", "", false), null,
                "single", Collections.singletonMap("one", new SshConfig("one.example", "deploy", 22, "")),
                1, 1000, runner).execute("application", "execute", map("command", "health"),
                context(), 5000L, "ssh-openssh", new CaseExecutionLog(root.resolve("openssh.log")));
        assertFalse(result.executionSuccess());
        assertEquals("SSH_TRANSPORT_ERROR", ((Map<?, ?>) result.invocation().get("error")).get("category"));
        Map<?, ?> evidence = (Map<?, ?>) result.invocation().get("SSH");
        assertEquals("openssh", evidence.get("transport"));
        assertEquals(255, evidence.get("exitCode"));
    }

    @Test void filesystemOperationsShareTypedExecutionAndRejectArgumentsBeforeConnecting() throws Exception {
        SftpFilesystemOperationsTest.MemorySftp remote = new SftpFilesystemOperationsTest.MemorySftp();
        remote.nodes.put("/file", SftpFilesystemOperationsTest.attr(false, 12));
        FakeTransfer transfer = new FakeTransfer() {
            @Override public Map<String, Object> filesystem(SshConfig target, String operation, Map<String, Object> arguments,
                    Duration connectTimeout, Duration timeout, Path project, SshTransferCancellation cancellation) throws Exception {
                assertEquals("example.test", target.host());
                assertTrue(timeout.toMillis() <= 5000); assertTrue(connectTimeout.toMillis() <= timeout.toMillis() + 1);
                return SftpFilesystemOperations.execute(remote, operation, arguments);
            }
        };
        SshResourceExecutor executor = executor(new CommandResult(0, "unused", "", false), transfer);
        for (String operation : new String[]{"stat", "mkdirs", "move", "delete"}) {
            Map<String, Object> arguments = "move".equals(operation)
                    ? map("sourcePath", "/file", "targetPath", "/moved")
                    : map("remotePath", "stat".equals(operation) ? "/absent" : "/created");
            ToolInvocationResult result = executor.execute("application", operation, arguments, context(), 5000L, "fs-" + operation, null);
            assertTrue(result.executionSuccess(), String.valueOf(result.invocation()));
            assertTrue(result.output() instanceof Map);
            Map<?, ?> evidence = (Map<?, ?>) result.invocation().get("SSH");
            assertEquals("sftp", evidence.get("transport")); assertEquals(operation, evidence.get("operation"));
            assertEquals("example.test", evidence.get("host"));
            if ("stat".equals(operation)) assertEquals(false, ((Map<?, ?>) result.output()).get("exists"));
        }
        int before = remote.calls;
        for (Map<String, Object> arguments : java.util.Arrays.asList(
                map(), map("remotePath", ""), map("remotePath", "/x", "recursive", true),
                map("remotePath", "/*"), map("remotePath", "/x", "missingOk", "true"),
                map("remotePath", "/x", "timeoutMs", 0))) {
            ToolInvocationResult failed = executor.execute("application", "delete", arguments, context(), 5000L, "invalid", null);
            assertFalse(failed.executionSuccess()); assertEquals("SSH_ARGUMENT", ((Map<?, ?>) failed.invocation().get("error")).get("category"));
        }
        assertEquals(before, remote.calls, "Invalid calls must not connect or inspect the server");
        ToolInvocationResult denied = executor.execute("application", "stat", map("remotePath", "/denied"), context(), 5000L, "denied", null);
        assertEquals("SSH_STAT_ERROR", ((Map<?, ?>) denied.invocation().get("error")).get("category"));
    }

    @Test void filesystemDeadlineCancelsTheSameSftpTransport() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        FakeTransfer transfer = new FakeTransfer() {
            @Override public Map<String, Object> filesystem(SshConfig target, String operation, Map<String, Object> arguments,
                    Duration connectTimeout, Duration timeout, Path project, SshTransferCancellation cancellation) throws Exception {
                cancellation.register(() -> closed.countDown());
                Thread.sleep(10000);
                return map("exists", true);
            }
        };
        ToolInvocationResult result = executor(new CommandResult(0, "", "", false), transfer)
                .execute("application", "stat", map("remotePath", "/x"), context(), 1000L, "timeout", null);
        assertFalse(result.executionSuccess());
        assertEquals("SSH_TIMEOUT", ((Map<?, ?>) result.invocation().get("error")).get("category"));
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }

    @Test void validatesFilesystemContractsAndExecutesTheSameTypedStatThroughTheActionEngine() throws Exception {
        FakeTransfer transfer = new FakeTransfer() {
            @Override public Map<String, Object> filesystem(SshConfig target, String operation, Map<String, Object> arguments,
                    Duration connectTimeout, Duration timeout, Path project, SshTransferCancellation cancellation) {
                return map("path", arguments.get("remotePath"), "exists", false);
            }
        };
        SshResourceExecutor executor = executor(new CommandResult(0, "", "", false), transfer);
        java.lang.reflect.Field configured = SshResourceExecutor.class.getDeclaredField("config"); configured.setAccessible(true);
        FrameworkConfig config = (FrameworkConfig) configured.get(executor);
        att.validation.PackageValidator validator = new att.validation.PackageValidator(root, config);
        java.lang.reflect.Method validate = att.validation.PackageValidator.class.getDeclaredMethod("validateSshCall",
                att.template.ToolCallParser.ParsedCall.class, FrameworkConfig.class); validate.setAccessible(true);
        att.template.ToolCallParser parser = new att.template.ToolCallParser();
        for (String call : new String[] {
                "#{ssh.application.stat(remotePath='/missing')}",
                "#{ssh.application.mkdirs(remotePath=${EXEC.INPUT.path}, timeoutMs=5000)}",
                "#{ssh.application.move(sourcePath='/a', targetPath='/b', overwrite=false)}",
                "#{ssh.application.delete(remotePath='/a', missingOk=true)}" })
            assertDoesNotThrow(() -> validate.invoke(validator, parser.parse(call), config));
        for (String call : new String[] {
                "#{ssh.application.stat('/x')}", "#{ssh.application.stat()}", "#{ssh.missing.stat(remotePath='/x')}",
                "#{ssh.application.move(sourcePath='/a')}", "#{ssh.application.move(sourcePath='/a', targetPath='/b', overwrite='yes')}",
                "#{ssh.application.delete(remotePath='/x', missingOk='true')}", "#{ssh.application.delete(remotePath='/x', recursive=true)}",
                "#{ssh.application.stat(remotePath='/*')}", "#{ssh.application.stat(remotePath='/x', timeoutMs=0)}",
                "#{ssh.application.copy(sourcePath='/a', targetPath='/b')}" })
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(validator, parser.parse(call), config));
        CaseRuntimeContext context = context(); context.beginStage(new StageCaseData("invoke", "T", Collections.emptyMap()), "T", root);
        TemplateAction action = new TemplateAction("inspect", map("type", "tool", "call", "#{ssh.application.stat(remotePath='/missing')}",
                "assert", "#{${output.result.exists} == false}"));
        try (CaseExecutionLog log = new CaseExecutionLog(root.resolve("stat.log"))) {
            List<att.core.ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null, null, null, null,
                    executor, new DefaultBuiltInProvider())).execute("invoke", new StageTemplate("T", root, Collections.singletonList(action)), context, log);
            assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        }
        assertEquals(false, context.resolve("ACTIONS.inspect.output.result.exists"));
        assertTrue(new String(Files.readAllBytes(root.resolve("stat.log")), "UTF-8").contains("sftp"));
    }

    @Test void filesystemTimeoutRetryValidationMatchesNativeAndCallBackedOperationPolicy() throws Exception {
        SshResourceExecutor executor = executor(new CommandResult(0, "", "", false), null);
        java.lang.reflect.Field configured = SshResourceExecutor.class.getDeclaredField("config"); configured.setAccessible(true);
        FrameworkConfig config = (FrameworkConfig) configured.get(executor);
        java.lang.reflect.Method contract = att.validation.PackageValidator.class.getDeclaredMethod("validateSshRetryContract",
                TemplateAction.class, FrameworkConfig.class); contract.setAccessible(true);
        for (String operation : new String[]{"execute", "stat", "mkdirs", "move", "delete", "upload", "download"}) {
            String call = "#{ssh.application." + operation + "()}";
            att.config.ToolConfig wrapper = new att.config.ToolConfig("wrapped", "wrapped", "", "Wrapper", "",
                    Collections.<String>emptyList(), call, Collections.<String>emptyList(), "",
                    Collections.emptyMap(), null, null);
            FrameworkConfig facadeConfig = new FrameworkConfig(root, root, root, "SIT", 5000, root,
                    Collections.singletonMap("wrapped", wrapper), null, null);
            for (boolean facade : new boolean[]{false, true}) {
                FrameworkConfig selected = facade ? facadeConfig : config;
                att.validation.PackageValidator validator = new att.validation.PackageValidator(root, selected);
                TemplateAction action = new TemplateAction("retry", map("type", "tool",
                        "call", facade ? "#{wrapped()}" : call,
                        "retry", map("maxAttempts", 2, "intervalMs", 0, "retryOn", Collections.singletonList("TIMEOUT"))));
                if (java.util.Arrays.asList("execute", "stat", "mkdirs").contains(operation))
                    assertDoesNotThrow(() -> contract.invoke(validator, action, selected), operation);
                else {
                    java.lang.reflect.InvocationTargetException rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
                            () -> contract.invoke(validator, action, selected), operation);
                    assertTrue(rejected.getCause().getMessage().contains("retryOn TIMEOUT is not supported"), operation);
                }
            }
        }
    }

    @Test void idempotentFilesystemTimeoutsActuallyRetryAndRespectTheBooleanGate() throws Exception {
        for (String operation : new String[]{"stat", "mkdirs"}) for (String category : new String[]{"SSH_TIMEOUT", "SSH_POOL_TIMEOUT"}) for (boolean facade : new boolean[]{false, true})
            for (boolean allow : new boolean[]{false, true}) {
                AtomicInteger calls = new AtomicInteger();
                FakeTransfer transfer = new FakeTransfer() {
                    @Override public Map<String, Object> filesystem(SshConfig target, String requested, Map<String, Object> arguments,
                            Duration connectTimeout, Duration timeout, Path project, SshTransferCancellation cancellation) throws Exception {
                        assertEquals(operation, requested);
                        if (calls.incrementAndGet() == 1)
                            throw new SshResourceExecutor.SshOperationException(category, "Filesystem deadline expired", null);
                        return "stat".equals(requested) ? map("path", "/x", "exists", false) : map("path", "/x", "created", false);
                    }
                };
                SshResourceExecutor executor = executor(new CommandResult(0, "", "", false), transfer);
                String call = "#{ssh.application." + operation + "(remotePath='/x')}";
                att.config.ToolConfig wrapper = new att.config.ToolConfig("wrapped", "wrapped", "", "Wrapper", "",
                        Collections.<String>emptyList(), call, Collections.<String>emptyList(), "",
                        Collections.emptyMap(), null, null);
                FrameworkConfig config = new FrameworkConfig(root, root, root, "SIT", 5000, root,
                        Collections.singletonMap("wrapped", wrapper), null, null);
                CaseRuntimeContext runtime = context();
                runtime.beginStage(new StageCaseData("invoke", "T", Collections.emptyMap()), "T", root);
                TemplateAction action = new TemplateAction("filesystem", map("type", "tool",
                        "call", facade ? "#{wrapped()}" : call,
                        "retry", map("maxAttempts", 2, "intervalMs", 0, "retryOn", Collections.singletonList("TIMEOUT"),
                                "when", allow ? "#{true}" : "#{false}")));
                try (CaseExecutionLog log = new CaseExecutionLog(root.resolve(operation + "-" + category + "-" + facade + "-" + allow + ".log"))) {
                    List<att.core.ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(
                            facade ? new ToolInvoker(root, config) : null, null, null, null, executor, new DefaultBuiltInProvider()))
                            .execute("invoke", new StageTemplate("T", root, Collections.singletonList(action)), runtime, log);
                    assertEquals(allow ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status(), results.get(0).message());
                }
                assertEquals(allow ? 2 : 1, calls.get());
                assertEquals("TIMEOUT", runtime.resolve("ACTIONS.filesystem.output.attempts[0].status"));
                assertEquals(category, runtime.resolve("ACTIONS.filesystem.output.attempts[0].SSH.error.category"));
                assertEquals(allow ? "RETRY" : "WHEN_FALSE",
                        runtime.resolve("ACTIONS.filesystem.output.attempts[0].retryDecision.reason"));
                if (allow) assertEquals(false, runtime.resolve("ACTIONS.filesystem.output.result." + ("stat".equals(operation) ? "exists" : "created")));
                else assertEquals("TIMEOUT", runtime.resolve("ACTIONS.filesystem.output.status"));
            }
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
