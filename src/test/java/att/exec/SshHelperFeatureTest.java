package att.exec;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.SshConfig;
import att.config.SshHelperConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.StageCaseData;
import att.core.TestCase;
import att.core.ResultStatus;
import att.core.ValidationResult;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SshHelperFeatureTest {
    @TempDir Path root;

    private Path write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private void group(String strategy) throws IOException {
        write("config/tools/remote.yaml", "schemaVersion: att-tool-group/v2.7\nid: remote\nname: Remote\ndescription: Remote commands\n"
                + "ssh:\n  helper: application\n" + (strategy == null ? "" : "  selection: {strategy: " + strategy + "}\n")
                + "tools:\n  echo:\n    name: Echo\n    description: Echo\n    command: [echo, ok]\n    output: txt\n");
    }

    private Path profileConfig() throws IOException {
        return write("config/config.yaml", "schemaVersion: att-config/v2.7\nenvironment: SIT\n"
                + "toolGroups: [config/tools/remote.yaml]\n"
                + "environments:\n  SIT:\n    sshhelpers: [config/ssh/sit.yaml]\n"
                + "  UAT:\n    sshhelpers: [config/ssh/uat.yaml]\n");
    }

    private String descriptor(String hosts, String strategy) {
        return "schemaVersion: att-sshhelper/v1.0\nid: application\nname: Application\n"
                + "description: Application servers\ndefaults: {user: deploy, port: 2222}\n"
                + "selection: {strategy: " + strategy + "}\nfanout: {maxConcurrency: 2}\ninstances:\n" + hosts;
    }

    @Test void profilesBindOneLogicalNameAndInheritDefaults() throws Exception {
        group(null);
        Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: sit.example}\n", "roundRobin"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example, user: ops, port: 2200}\n", "roundRobin"));
        FrameworkConfig sit = new FrameworkConfigLoader().load(config, root, "sit");
        FrameworkConfig uat = new FrameworkConfigLoader().load(config, root, "UAT");
        assertEquals("sit.example", sit.sshHelper("application").instances().get("one").host());
        assertEquals("deploy", sit.sshHelper("application").instances().get("one").user());
        assertEquals(2222, sit.sshHelper("application").instances().get("one").port());
        assertEquals("uat.example", uat.sshHelper("application").instances().get("one").host());
        assertEquals("ops", uat.sshHelper("application").instances().get("one").user());
        assertEquals(2200, uat.sshHelper("application").instances().get("one").port());
        assertEquals("application", uat.tool("remote.echo").sshHelper());
        assertNull(uat.tool("remote.echo").ssh());
    }

    @Test void singleInstanceNeedsNoStrategyAndPhysicalKeyOverrideWins() throws Exception {
        group(null); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: only, host: sit.example, identityFile: keys/other_key}\n", "random")
                .replace("selection: {strategy: random}\n", "")
                .replace("defaults: {user: deploy, port: 2222}", "defaults: {user: deploy, port: 2222, identityFile: keys/default_key}"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "random"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        assertEquals("single", effective.sshHelper("application").strategy());
        assertEquals("keys/other_key", effective.sshHelper("application").instances().get("only").identityFile());
        SshCommandRunner remote = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> new CommandResult(0, target.host(), "", false), System.err);
        ToolInvocationResult result = new ToolInvoker(root, effective, new CommandRunner(), remote).invokeAttempt("single", "remote.echo",
                Collections.<String, Object>emptyMap(), context(), new CaseExecutionLog(root.resolve("single.log")), 1000L);
        assertEquals("sit.example", result.output());
        assertEquals("single", result.invocation().get("selectionStrategy"));
    }

    @Test void concurrentRoundRobinUsesEveryPositionOncePerCycle() throws Exception {
        group(null); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: a, host: a.example}\n  - {id: b, host: b.example}\n", "roundRobin"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "roundRobin"));
        SshHelperConfig helper = new FrameworkConfigLoader().load(config, root, "SIT").sshHelper("application");
        List<String> selections = Collections.synchronizedList(new ArrayList<String>());
        java.util.stream.IntStream.range(0, 100).parallel().forEach(index -> selections.add(helper.select("roundRobin")));
        assertEquals(50, Collections.frequency(selections, "a"));
        assertEquals(50, Collections.frequency(selections, "b"));
    }

    @Test void roundRobinIsCyclicAndSingleHostOutputRemainsText() throws Exception {
        group("roundRobin");
        Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: a, host: a.example}\n  - {id: b, host: b.example}\n", "random"));
        write("config/ssh/uat.yaml", descriptor("  - {id: a, host: c.example}\n", "random"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        List<String> hosts = Collections.synchronizedList(new ArrayList<String>());
        SshCommandRunner remote = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> { hosts.add(target.host()); return new CommandResult(0, target.host(), "", false); }, System.err);
        ToolInvoker invoker = new ToolInvoker(root, effective, new CommandRunner(), remote);
        for (int i = 0; i < 4; i++) {
            ToolInvocationResult result = invoker.invokeAttempt("one" + i, "remote.echo", Collections.<String, Object>emptyMap(),
                    context(), new CaseExecutionLog(root.resolve("case.log")), 1000L);
            assertEquals(hosts.get(i), result.output());
            assertEquals("application", result.invocation().get("sshHelper"));
            assertEquals("toolGroup", result.invocation().get("selectionSource"));
        }
        assertEquals(Arrays.asList("a.example", "b.example", "a.example", "b.example"), hosts);
    }

    @Test void allFanoutRetainsEveryInstanceAndMixedOutcome() throws Exception {
        group("all");
        Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: first, host: first.example}\n  - {id: second, host: second.example}\n  - {id: third, host: third.example}\n", "random"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "random"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        AtomicInteger active = new AtomicInteger(); AtomicInteger peak = new AtomicInteger();
        List<String> visited = Collections.synchronizedList(new ArrayList<String>());
        SshCommandRunner remote = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> {
                    int now = active.incrementAndGet(); peak.accumulateAndGet(now, Math::max);
                    try {
                        visited.add(target.host());
                        Thread.sleep(20L);
                        return new CommandResult(target.host().startsWith("second") ? 9 : 0, target.host(), "diagnostic", false);
                    } finally { active.decrementAndGet(); }
                }, System.err);
        ToolInvocationResult invocation = new ToolInvoker(root, effective, new CommandRunner(), remote)
                .invokeAttempt("all", "remote.echo", Collections.<String, Object>emptyMap(),
                        context(), new CaseExecutionLog(root.resolve("all.log")), 1000L);
        assertEquals("PASS", invocation.invocation().get("status"));
        Map<?, ?> output = (Map<?, ?>) invocation.output();
        Map<?, ?> results = (Map<?, ?>) output.get("instances");
        assertEquals(Arrays.asList("first", "second", "third"), new ArrayList<Object>(results.keySet()));
        assertEquals("PASS", ((Map<?, ?>) results.get("first")).get("status"));
        assertEquals("PASS", ((Map<?, ?>) results.get("second")).get("status"));
        assertEquals(9, ((Map<?, ?>) results.get("second")).get("exitCode"));
        assertFalse(((Map<?, ?>) results.get("second")).containsKey("error"));
        assertEquals("PASS", ((Map<?, ?>) results.get("third")).get("status"));
        assertEquals(3, visited.size());
        assertTrue(peak.get() <= 2);
    }

    @Test void allFanoutNonzeroExitReachesActionAssertion() throws Exception {
        group("all"); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: first, host: first.example}\n  - {id: second, host: second.example}\n", "all"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "roundRobin"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        SshCommandRunner remote = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> target.host().startsWith("second")
                        ? new CommandResult(9, "expected", "diagnostic", false)
                        : new CommandResult(0, "ready", "", false), System.err);
        CaseRuntimeContext runtime = context();
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("type", "tool"); fields.put("call", "#{remote.echo()}");
        fields.put("assert", "${output.result.instances.second.exitCode} == 9");
        TemplateAction accepted = new TemplateAction("accepted", fields);
        List<ValidationResult> outcomes = new StageTemplateRunner(new UnifiedTemplateEngine(
                new ToolInvoker(root, effective, new CommandRunner(), remote))).execute("invoke",
                new StageTemplate("T", root, Collections.singletonList(accepted)), runtime,
                new CaseExecutionLog(root.resolve("assertion.log")));
        assertEquals(ResultStatus.PASS, outcomes.get(0).status(), outcomes.get(0).message());
        assertEquals(9, runtime.resolve("ACTIONS.accepted.output.result.instances.second.exitCode"));
        assertEquals(Boolean.TRUE, runtime.resolve("ACTIONS.accepted.output.assertion.passed"));
    }

    @Test void allFanoutTransportErrorStillRetainsPeerEvidence() throws Exception {
        group("all"); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: first, host: first.example}\n  - {id: second, host: second.example}\n", "all"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "roundRobin"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        SshCommandRunner remote = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> {
                    if (target.host().startsWith("second")) throw new IOException("connection refused");
                    return new CommandResult(0, "ready", "", false);
                }, System.err);
        ToolExecutionException failure = assertThrows(ToolExecutionException.class,
                () -> new ToolInvoker(root, effective, new CommandRunner(), remote).invokeAttempt("error", "remote.echo",
                        Collections.<String, Object>emptyMap(), context(), new CaseExecutionLog(root.resolve("error.log")), 1000L));
        assertEquals("SSH_FANOUT", failure.category());
        Map<?, ?> results = (Map<?, ?>) ((Map<?, ?>) failure.evidence().get("output")).get("instances");
        assertEquals("PASS", ((Map<?, ?>) results.get("first")).get("status"));
        assertEquals("ready", ((Map<?, ?>) results.get("first")).get("output"));
        assertEquals("ERROR", ((Map<?, ?>) results.get("second")).get("status"));
        assertTrue(String.valueOf(((Map<?, ?>) results.get("second")).get("error")).contains("connection refused"));
    }

    @Test void rejectsDuplicateInstancesAndUnknownBindingsBeforeExecution() throws Exception {
        group(null); Path config = profileConfig();
        Path sit = write("config/ssh/sit.yaml", descriptor("  - {id: First, host: one.example}\n  - {id: first, host: two.example}\n", "all"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "random"));
        assertThrows(Exception.class, () -> new FrameworkConfigLoader().load(config, root, "SIT"));
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: sit.example}\n", "random") + "unsupported: true\n");
        assertThrows(Exception.class, () -> new FrameworkConfigLoader().load(config, root, "SIT"));
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: sit.example}\n", "random"));
        write("config/tools/remote.yaml", "schemaVersion: att-tool-group/v2.7\nid: remote\nname: Remote\ndescription: Remote\nssh: {helper: absent}\ntools:\n  echo: {name: Echo, description: Echo, command: [echo, ok]}\n");
        assertThrows(Exception.class, () -> new FrameworkConfigLoader().load(config, root, "SIT"));
    }

    @Test void rejectsDuplicateLogicalIdsMissingMultiStrategyAndMixedSshBinding() throws Exception {
        group(null); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: sit.example}\n", "random"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "random"));
        write("config/ssh/duplicate.yaml", descriptor("  - {id: one, host: other.example}\n", "random").replace("id: application", "id: APPLICATION"));
        write("config/config.yaml", "schemaVersion: att-config/v2.7\nenvironment: SIT\n"
                + "toolGroups: [config/tools/remote.yaml]\n"
                + "environments:\n  SIT:\n    sshhelpers: [config/ssh/sit.yaml, config/ssh/duplicate.yaml]\n");
        assertThrows(Exception.class, () -> new FrameworkConfigLoader().load(config, root, "SIT"));
        write("config/config.yaml", "schemaVersion: att-config/v2.7\nenvironment: SIT\n"
                + "toolGroups: [config/tools/remote.yaml]\nsshhelpers: [config/ssh/sit.yaml]\n");
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: one.example}\n  - {id: two, host: two.example}\n", "random")
                .replace("selection: {strategy: random}\n", ""));
        assertThrows(Exception.class, () -> new FrameworkConfigLoader().load(config, root));
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: one.example}\n", "random"));
        write("config/tools/remote.yaml", "schemaVersion: att-tool-group/v2.7\nid: remote\nname: Remote\ndescription: Remote\n"
                + "ssh: {helper: application, host: bypass.example}\ntools:\n  echo: {name: Echo, description: Echo, command: [echo, ok]}\n");
        assertThrows(Exception.class, () -> new FrameworkConfigLoader().load(config, root));
    }

    @Test void openSshUsesEffectiveInstanceAndSafeArgv() throws Exception {
        group(null); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: sit.example, port: 2200}\n", "roundRobin"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "roundRobin"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        final List<String> captured = new ArrayList<String>();
        CommandRunner process = new CommandRunner() {
            @Override public CommandResult run(List<String> argv, Duration timeout, Path workingDirectory,
                                               Map<String, String> environment) {
                captured.addAll(argv); return new CommandResult(0, "ok", "", false);
            }
        };
        SshCommandRunner remote = new SshCommandRunner(process, () -> true,
                (target, command, timeout, project) -> { throw new AssertionError("Java transport was not selected"); }, System.err);
        ToolInvocationResult result = new ToolInvoker(root, effective, process, remote).invokeAttempt("open", "remote.echo",
                Collections.<String, Object>emptyMap(), context(), new CaseExecutionLog(root.resolve("open.log")), 1000L);
        assertEquals("ok", result.output());
        assertTrue(captured.contains("deploy@sit.example"));
        assertTrue(captured.contains("2200"));
        assertTrue(captured.contains("'echo' 'ok'"));
        Map<?, ?> remoteTool = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) result.invocation().get("TOOL")).get("remote")).get("echo");
        assertEquals("openssh", ((Map<?, ?>) remoteTool.get("ssh")).get("transport"));
    }

    @Test void environmentKeyPathIsResolvedButNeverRecordedInEvidence() throws Exception {
        group(null); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: sit.example}\n", "roundRobin")
                .replace("defaults: {user: deploy, port: 2222}", "defaults: {user: deploy, port: 2222, identityFile: '${ENV:HOME}'}"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "roundRobin"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        SshConfig target = effective.sshHelper("application").instances().get("one");
        assertTrue(target.identityFileFromEnvironment());
        assertEquals(System.getenv("HOME"), target.identityFile());
        CommandRunner process = new CommandRunner() {
            @Override public CommandResult run(List<String> argv, Duration timeout, Path workingDirectory,
                                               Map<String, String> environment) {
                return new CommandResult(0, "ok", "Identity file: " + System.getenv("HOME"), false);
            }
        };
        SshCommandRunner remote = new SshCommandRunner(process, () -> true,
                (physical, command, timeout, project) -> { throw new AssertionError(); }, System.err);
        ToolInvocationResult result = new ToolInvoker(root, effective, process, remote).invokeAttempt("key", "remote.echo",
                Collections.<String, Object>emptyMap(), context(), new CaseExecutionLog(root.resolve("key.log")), 1000L);
        assertFalse(result.invocation().toString().contains(System.getenv("HOME")));
        assertTrue(result.invocation().toString().contains("[REDACTED_SECRET]"));
        assertFalse(new String(Files.readAllBytes(root.resolve("key.log")), StandardCharsets.UTF_8)
                .contains(System.getenv("HOME")));
        write("config/ssh/sit.yaml", descriptor("  - {id: one, host: sit.example}\n", "roundRobin")
                .replace("defaults: {user: deploy, port: 2222}", "defaults: {user: deploy, port: 2222, identityFile: '${ENV:ATT_SSH_HELPER_MISSING_TEST_VARIABLE}'}"));
        Exception missing = assertThrows(Exception.class, () -> new FrameworkConfigLoader().load(config, root, "SIT"));
        assertTrue(missing.getMessage().contains("ATT_SSH_HELPER_MISSING_TEST_VARIABLE"));
    }

    @Test void environmentKeyPathIsRedactedFromSingleAndFanoutDiagnosticsAndCaseLog() throws Exception {
        group("roundRobin"); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: first, host: first.example}\n  - {id: second, host: second.example}\n", "roundRobin")
                .replace("defaults: {user: deploy, port: 2222}",
                        "defaults: {user: deploy, port: 2222, identityFile: '${ENV:HOME}'}"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "roundRobin"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        String identityPath = effective.sshHelper("application").instances().get("first").identityFile();
        StringBuilder diagnostic = new StringBuilder();
        for (int index = 0; index < 8170; index++) diagnostic.append('x');
        diagnostic.append("Identity file: ").append(identityPath).append(" could not be loaded\n");
        String warning = diagnostic.toString();
        CommandRunner process = new CommandRunner() {
            @Override public CommandResult runWithCapture(List<String> argv, Duration timeout, Path workingDirectory,
                                                           Map<String, String> environment, CapturePolicy capture) throws IOException {
                byte[] stderr = warning.getBytes(StandardCharsets.UTF_8);
                Files.write(capture.stderrArtifact(), stderr);
                return new CommandResult(255, "ok", warning, false, 2, stderr.length,
                        false, false, false, false, null, capture.stderrArtifact());
            }
        };
        SshCommandRunner remote = new SshCommandRunner(process, () -> true,
                (target, command, timeout, project) -> { throw new AssertionError("OpenSSH should be selected"); }, System.err);
        ToolInvocationResult single;
        Path singleLog = root.resolve("single-diagnostic.log");
        try (CaseExecutionLog log = new CaseExecutionLog(singleLog)) {
            single = new ToolInvoker(root, effective, process, remote).invoke("single", "remote.echo",
                    Collections.<String, Object>emptyMap(), context(), log);
        }
        assertEquals("ok", single.output());
        assertFalse(single.invocation().toString().contains(identityPath));
        assertFalse(new String(Files.readAllBytes(singleLog), StandardCharsets.UTF_8).contains(identityPath));
        assertTrue(new String(Files.readAllBytes(singleLog), StandardCharsets.UTF_8).contains("[REDACTED_SECRET]"));

        group("all");
        FrameworkConfig fanoutConfig = new FrameworkConfigLoader().load(config, root, "SIT");
        ToolInvocationResult fanout;
        Path fanoutLog = root.resolve("fanout-diagnostic.log");
        try (CaseExecutionLog log = new CaseExecutionLog(fanoutLog)) {
            fanout = new ToolInvoker(root, fanoutConfig, process, remote).invoke("fanout", "remote.echo",
                    Collections.<String, Object>emptyMap(), context(), log);
        }
        assertFalse(fanout.invocation().toString().contains(identityPath));
        Map<?, ?> instances = (Map<?, ?>) ((Map<?, ?>) fanout.output()).get("instances");
        assertEquals(255, ((Map<?, ?>) instances.get("first")).get("exitCode"));
        assertTrue(String.valueOf(((Map<?, ?>) instances.get("first")).get("stderr")).contains("[REDACTED_SECRET]"));
        assertFalse(new String(Files.readAllBytes(fanoutLog), StandardCharsets.UTF_8).contains(identityPath));
    }

    @Test void randomOverrideChoosesOnlyConfiguredMembersAndReachesBoth() throws Exception {
        group("random"); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: a, host: a.example}\n  - {id: b, host: b.example}\n", "all"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "roundRobin"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        List<String> visited = new ArrayList<String>();
        SshCommandRunner remote = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> { visited.add(target.host()); return new CommandResult(0, target.host(), "", false); }, System.err);
        ToolInvoker invoker = new ToolInvoker(root, effective, new CommandRunner(), remote);
        for (int i = 0; i < 100; i++) {
            ToolInvocationResult result = invoker.invokeAttempt("random" + i, "remote.echo", Collections.<String, Object>emptyMap(),
                    context(), new CaseExecutionLog(root.resolve("random.log")), 1000L);
            assertEquals("random", result.invocation().get("selectionStrategy"));
            assertTrue(Arrays.asList("a.example", "b.example").contains(result.output()));
        }
        assertTrue(visited.contains("a.example")); assertTrue(visited.contains("b.example"));
    }

    @Test void allFanoutPreservesTimeoutAndCancelsActiveTasks() throws Exception {
        group("all"); Path config = profileConfig();
        write("config/ssh/sit.yaml", descriptor("  - {id: slow, host: slow.example}\n  - {id: fast, host: fast.example}\n", "all"));
        write("config/ssh/uat.yaml", descriptor("  - {id: one, host: uat.example}\n", "roundRobin"));
        FrameworkConfig effective = new FrameworkConfigLoader().load(config, root, "SIT");
        SshCommandRunner timeoutRemote = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> target.host().startsWith("slow")
                        ? new CommandResult(-1, "", "", true) : new CommandResult(0, "ok", "", false), System.err);
        ToolExecutionException failure = assertThrows(ToolExecutionException.class,
                () -> new ToolInvoker(root, effective, new CommandRunner(), timeoutRemote).invokeAttempt("timeout", "remote.echo",
                        Collections.<String, Object>emptyMap(), context(), new CaseExecutionLog(root.resolve("timeout.log")), 1000L));
        assertEquals("TIMEOUT", failure.category());
        Map<?, ?> outcomes = (Map<?, ?>) ((Map<?, ?>) failure.evidence().get("output")).get("instances");
        assertEquals("TIMEOUT", ((Map<?, ?>) outcomes.get("slow")).get("status"));
        assertEquals("PASS", ((Map<?, ?>) outcomes.get("fast")).get("status"));

        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        SshCommandRunner blocking = new SshCommandRunner(new CommandRunner(), () -> false,
                (target, command, timeout, project) -> {
                    active.incrementAndGet(); started.countDown();
                    try { Thread.sleep(10000L); return new CommandResult(0, "", "", false); }
                    finally { active.decrementAndGet(); finished.countDown(); }
                }, System.err);
        Thread parent = new Thread(() -> {
            try {
                new ToolInvoker(root, effective, new CommandRunner(), blocking).invokeAttempt("cancel", "remote.echo",
                        Collections.<String, Object>emptyMap(), context(), new CaseExecutionLog(root.resolve("cancel.log")), 1000L);
            } catch (Exception expected) { }
        });
        parent.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        parent.interrupt(); parent.join(2000L);
        assertFalse(parent.isAlive());
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(0, active.get());
    }

    private CaseRuntimeContext context() {
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, root.resolve("case"), "R", root, root.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", root);
        return context;
    }
}
