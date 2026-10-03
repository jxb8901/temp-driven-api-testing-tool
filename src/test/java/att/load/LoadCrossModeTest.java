package att.load;

import att.config.FrameworkConfig;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ExecutionOptions;
import att.core.ResultStatus;
import att.core.StageCaseData;
import att.core.TestCase;
import att.debug.DebugEngine;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.StageTemplateRunner;
import att.template.UnifiedTemplateEngine;
import att.exec.ToolInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression proof that one canonical component is portable across execution modes. */
class LoadCrossModeTest {
    @TempDir Path temp;

    @Test void mqReplyTimeoutAndReplayGateRemainConsistentInRunDebugAndLoad() throws Exception {
        Path project = fixture();
        write(project, "templates/SHARED/template.yaml", "schemaVersion: att-template/v3.6\n"
                + "name: SHARED\ndescription: MQ timeout parity\nactions:\n"
                + "  payment:\n    type: tool\n"
                + "    call: \"#{mq.broker.request(requestQueue='REQUEST.Q', replyQueue='REPLY.Q', payload='request', waitMs=321)}\"\n"
                + "    retry:\n      maxAttempts: 3\n      intervalMs: 0\n      retryOn: [TIMEOUT]\n"
                + "      when: \"#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}\"\n");
        att.config.MqHelperConfig mq = new att.config.MqHelperConfig("broker", "Broker", "Parity MQ",
                "QM1", "localhost", 1414, "APP.CHANNEL", "", "", 1208, "MQSTR", "asQueue", 1000,
                "metadata", project.resolve("mq.yaml"));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"),
                "SIT", 10000, Paths.get("templates"), project, Collections.emptyMap(), Collections.emptyMap(),
                Collections.singletonMap("broker", mq), null, null, null, "", "", null, null, 1, "ignore", "", false,
                att.config.ProcessOutputConfig.defaults());
        StageTemplate template = new StageTemplateLoader(project, config.templatesRoot(), false).loadSelected("SHARED");
        FlowRegistry flows = new FlowRegistry(project, config.templatesRoot(), false);
        CompiledExecutionPlan plan = CompiledExecutionPlan.compile(template, flows, config);
        assertSame(mq, plan.action(template.actions().get(0)).primaryTarget().mq());
        NoReplyFactory runFactory = new NoReplyFactory();
        CaseRuntimeContext testcase = testcaseContext(project, template, Collections.<String,Object>emptyMap());
        Files.createDirectories(testcase.caseOutputDirectory());
        List<att.core.ValidationResult> run;
        try (CaseExecutionLog log = new CaseExecutionLog(testcase.caseOutputDirectory().resolve("case.log"))) {
            run = new StageTemplateRunner(new UnifiedTemplateEngine(null, null,
                    new att.exec.MqHelperExecutor(project, config, runFactory)), flows).execute("testcase", template, testcase, log);
        }
        assertMqTimeout(testcase, run);
        assertEquals(1, runFactory.puts.get());

        NoReplyFactory debugFactory = new NoReplyFactory();
        DebugEngine.Result debug = new DebugEngine(project, config, debugFactory).run(ExecutionOptions.parse(new String[]{
                "debug", "template", "SHARED", "--output-dir", temp.resolve("mq-debug-output").toString(), "--format", "json"}));
        assertEquals(ResultStatus.ERROR, debug.status());
        assertEquals(1, debugFactory.puts.get());
        String debugLog = new String(Files.readAllBytes(debug.logPath()), StandardCharsets.UTF_8);
        for (String value : new String[]{"TIMEOUT", "2033", "MQRC_NO_MSG_AVAILABLE", "WHEN_FALSE"})
            assertTrue(debugLog.contains(value), value + "\n" + debugLog);

        Path scenarioFile = write(project, "mq-load.yaml", "schemaVersion: att-load/v1.4\n"
                + "workloads:\n  - id: payment\n    target: {type: template, id: SHARED}\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        NoReplyFactory loadFactory = new NoReplyFactory();
        try (LoadRunResources resources = new LoadRunResources(project, config, loadFactory)) {
            IterationResult load = new IterationExecutor(project, config, target, resources).execute(
                    IterationRequest.closed("mq-parity", "mq-parity-1", 1, "STEADY", Instant.now(), "VU-1", scenario.inputs()));
            assertMqTimeout(load.context(), load.validations());
            assertEquals(1, loadFactory.puts.get());
            assertEquals(0, resources.mqPool("broker").active());
            assertEquals(0, resources.mqPool("broker").discarded());
        }

        att.core.TestResult reportCase = new att.core.TestResult("MQ-parity", "MQ reply wait", ResultStatus.ERROR,
                java.time.Duration.ZERO, "", "", testcase.caseOutputDirectory().resolve("case.log"), run, "mq", "request",
                Collections.<String>emptyList());
        Path report = new att.report.HtmlReportGenerator().generate(temp.resolve("mq-report"), "mq-parity",
                new att.core.RunSummary(Collections.singletonList(reportCase), temp.resolve("mq-report")), Instant.now(), Instant.now());
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("TIMEOUT"), html);
        assertTrue(html.contains("2033"), html);
        assertTrue(html.contains("MQRC_NO_MSG_AVAILABLE"), html);
    }

    @Test void sharedIterationExecutorRunsConfiguredToolAndNestedFlowWithIsolatedInputs() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        Path scenarioFile = write(project, "concurrent-tool-load.yaml", "schemaVersion: att-load/v1.5\n"
                + "workloads:\n  - id: shared\n    target: {type: template, id: SHARED}\n"
                + "    inputs: {value: seed}\n    load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationExecutor executor = new IterationExecutor(project, config, target, resources,
                    temp.resolve("concurrent-configured-tool-load"));
            List<Future<String>> results = new java.util.ArrayList<Future<String>>();
            for (int index = 0; index < 24; index++) {
                final int iteration = index;
                results.add(workers.submit(new Callable<String>() {
                    @Override public String call() {
                        String input = "iteration-" + iteration;
                        IterationResult result = executor.execute(IterationRequest.closed("shared-engine-load",
                                "iteration-" + iteration, iteration + 1L, "STEADY", Instant.now(),
                                "VU-" + (iteration + 1), Collections.<String, Object>singletonMap("value", input)));
                        assertEquals(ResultStatus.PASS, result.status(), result.validations().toString());
                        assertEquals(3, result.validations().size());
                        assertEquals(input, result.context().resolve("EXEC.INPUT.value"));
                        assertEquals(input, String.valueOf(result.context().resolve("EXEC.ACTIONS.invoke.output.result")).trim());
                        Map<?, ?> stages = (Map<?, ?>) result.context().caseTree().get("STAGES");
                        String stageKey = String.valueOf(stages.keySet().iterator().next());
                        assertEquals("flow=" + input,
                                CaseRuntimeContext.getPath(result.context().caseTree(), "STAGES." + stageKey
                                        + ".TEMPLATE.ACTIONS.nested.flow.actions.flowLog.output.result"));
                        return String.valueOf(result.context().resolve("EXEC.INPUT.value"));
                    }
                }));
            }
            for (int index = 0; index < results.size(); index++) assertEquals("iteration-" + index, results.get(index).get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void loadCompilesCallBackedToolImplementationAndUsesBoundTarget() throws Exception {
        Path project = fixture();
        Map<String, ToolArgumentConfig> arguments = Collections.singletonMap("value",
                new ToolArgumentConfig("value", "Value", "Value", true, ""));
        ToolConfig wrapper = new ToolConfig("wrapped", "wrapped", "", "Wrapped", "Call-backed wrapper",
                Collections.<String>emptyList(), "#{upper(value=${input.value})}",
                Collections.<String>emptyList(), "text", arguments, null, null);
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"),
                "SIT", 10000, Paths.get("templates"), Collections.singletonMap("wrapped", wrapper), null, null);
        write(project, "templates/CALLBACK/template.yaml", "schemaVersion: att-template/v3.6\n"
                + "name: CALLBACK\ndescription: call-backed Load target\nactions:\n"
                + "  invoke:\n    type: tool\n    call: \"#{wrapped(value=${EXEC.INPUT.value})}\"\n");
        StageTemplate template = new StageTemplateLoader(project, config.templatesRoot(), false).loadSelected("CALLBACK");
        CompiledExecutionPlan plan = CompiledExecutionPlan.compile(template, new FlowRegistry(project, config.templatesRoot(), false), config);
        CompiledExecutionPlan.ActionPlan actionPlan = plan.action(template.actions().get(0));
        assertEquals("upper", actionPlan.configuredToolCall().name());

        Path scenarioFile = write(project, "call-backed-load.yaml", "schemaVersion: att-load/v1.5\n"
                + "workloads:\n  - id: wrapped\n    target: {type: template, id: CALLBACK}\n"
                + "    inputs: {value: bound-input}\n    load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationResult result = new IterationExecutor(project, config, target, resources).execute(
                    IterationRequest.closed("call-backed", "call-backed-1", 1, "STEADY", Instant.now(), "VU-1",
                            Collections.<String, Object>singletonMap("value", "bound-input")));
            assertEquals(ResultStatus.PASS, result.status(), result.validations().toString());
            assertEquals("BOUND-INPUT", result.context().resolve("EXEC.ACTIONS.invoke.output.result"));
        }
    }

    private void assertMqTimeout(CaseRuntimeContext context, List<att.core.ValidationResult> results) {
        assertNotNull(context);
        assertEquals(1, results.size());
        assertEquals(ResultStatus.ERROR, results.get(0).status(), results.get(0).message());
        String action = "EXEC.ACTIONS.payment.output.";
        assertEquals("TIMEOUT", context.resolve(action + "status"));
        assertEquals(1, ((List<?>) context.resolve(action + "attempts")).size());
        assertEquals("WHEN_FALSE", context.resolve(action + "attempts[0].retryDecision.reason"));
        assertEquals(Boolean.TRUE, context.resolve(action + "evidence.mq.invocations[0].sent"));
        assertEquals(Boolean.FALSE, context.resolve(action + "evidence.mq.invocations[0].replyReceived"));
        assertEquals(2033, context.resolve(action + "evidence.mq.invocations[0].reasonCode"));
        assertEquals(2, context.resolve(action + "evidence.mq.invocations[0].completionCode"));
        assertEquals(321, context.resolve(action + "evidence.mq.invocations[0].waitMs"));
    }

    private static final class NoReplyFactory implements att.exec.MqTransport.Factory {
        final java.util.concurrent.atomic.AtomicInteger puts = new java.util.concurrent.atomic.AtomicInteger();
        @Override public att.exec.MqTransport.Connection connect(att.config.MqHelperConfig config) {
            return new att.exec.MqTransport.Connection() {
                @Override public att.exec.MqTransport.Queue open(String queue, boolean input, boolean output) {
                    return new att.exec.MqTransport.Queue() {
                        @Override public att.exec.MqTransport.Message put(byte[] payload, att.exec.MqTransport.PutRequest request) {
                            puts.incrementAndGet();
                            return new att.exec.MqTransport.Message(new byte[]{1}, null, payload);
                        }
                        @Override public att.exec.MqTransport.Message get(att.exec.MqTransport.GetRequest request) throws Exception {
                            throw new att.exec.MqTransport.Exception("No reply", 2, 2033, "MQRC_NO_MSG_AVAILABLE", null);
                        }
                        @Override public void close() { }
                    };
                }
                @Override public void disconnect() { }
                @Override public void close() { }
            };
        }
    }

    @Test void sameTemplateFlowAndToolRunWithCanonicalInputInTestcaseDebugAndLoad() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();

        StageTemplate template = new StageTemplateLoader(project, config.templatesRoot(), false).loadSelected("SHARED");
        FlowRegistry flows = new FlowRegistry(project, config.templatesRoot(), false);
        Map<String, Object> testcaseInput = Collections.<String, Object>singletonMap("value", "shared-value");
        CaseRuntimeContext testcase = testcaseContext(project, template, testcaseInput);
        List<att.core.ValidationResult> testcaseResults = execute(project, config, template, flows, testcase, "testcase");
        assertPortableResult(testcase, testcaseResults);

        DebugEngine.Result debug = new DebugEngine(project, config).run(ExecutionOptions.parse(new String[]{
                "debug", "template", "SHARED", "--output-dir", temp.resolve("debug-output").toString(), "--format", "json"}));
        assertEquals(ResultStatus.PASS, debug.status());
        String debugLog = new String(Files.readAllBytes(debug.logPath()), StandardCharsets.UTF_8);
        assertTrue(debugLog.contains("value=shared-value"), debugLog);
        assertTrue(debugLog.contains("flow=shared-value"), debugLog);
        assertTrue(debugLog.contains("shared-value"), debugLog);

        Path scenarioFile = write(project, "shared-load.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: SHARED}\n"
                + "inputs: {value: shared-value}\n"
                + "load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        IterationResult load = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("cross-mode", "cross-mode-1", 1, "STEADY", Instant.now(), "VU-1", scenario.inputs()));
        assertPortableResult(load.context(), load.validations());
    }

    @Test void testdataInputsFeedLoadBootstrapVarsAndExecutionIdFormat() throws Exception {
        Path project = fixture();
        write(project, "templates/BOOTSTRAP/template.yaml", "schemaVersion: att-template/v3.6\n"
                + "name: BOOTSTRAP\ndescription: testdata bootstrap input\nactions:\n"
                + "  show: {type: log, message: 'copied=${EXEC.VARS.copied}|input=${EXEC.INPUT.accountId}'}\n");
        write(project, "data/accounts.yaml", "schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: 42}]\n");
        Path source = write(project, "load/testdata-bootstrap.yaml", "schemaVersion: att-load/v1.5\n"
                + "testdata: [data/accounts.yaml]\nworkloads:\n  - id: bootstrap\n"
                + "    target: {type: template, id: BOOTSTRAP}\n"
                + "    inputs: {accountId: '@{accounts.id}'}\n"
                + "    vars: {copied: '${EXEC.INPUT.accountId}'}\n"
                + "    load: {users: 1, duration: 1s}\n"
                + "execution: {execIdFormat: '${EXEC.RUN_ID}-${EXEC.INPUT.accountId}'}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(source);
        LoadTarget target = new LoadTargetResolver(project, config()).resolve(scenario);
        new LoadTargetValidator(project, config()).validate(scenario, target);
        LoadWorkload workload = scenario.workload();
        att.testdata.TestdataInputResolver testdata = new att.testdata.TestdataInputResolver(
                new att.testdata.TestdataRegistry(project, Collections.<Path>emptyList(), scenario.testdataDescriptors()),
                workload.testdata(), workload.id(), workload.model().wireName(), scenario.seed(), workload.users());

        try (LoadRunResources resources = new LoadRunResources(project, config())) {
            IterationExecutor executor = new IterationExecutor(project, config(), target, resources,
                    temp.resolve("testdata-bootstrap-load"), testdata);
            IterationResult result = executor.execute(IterationRequest.closed("testdata-bootstrap", "testdata-bootstrap-1", 1,
                    "STEADY", Instant.now(), "VU-1", scenario.inputs()).withWorkloadId(workload.id()));

            assertEquals(ResultStatus.PASS, result.status());
            assertEquals(42L, ((Number) result.context().require("EXEC.INPUT.accountId")).longValue());
            assertEquals(42L, ((Number) result.context().require("EXEC.VARS.copied")).longValue());
            assertEquals("testdata-bootstrap-42", result.context().require("EXEC.ID"));
            assertTrue(String.valueOf(result.context().resolve("EXEC.ACTIONS.show.output.result"))
                    .contains("copied=42|input=42"));
        }
    }

    @Test void loadInputMappingRejectsUnavailablePreIdentityContextBeforeScheduling() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        for (String reference : Arrays.asList("${EXEC.ID}", "${EXEC.ACTIONS.previous.output.result}")) {
            Path source = write(project, "load/input-mapping-phase-" + Math.abs(reference.hashCode()) + ".yaml",
                    "schemaVersion: att-load/v1.5\nworkloads:\n  - id: phase\n"
                            + "    target: {type: template, id: SHARED}\n"
                            + "    inputs: {value: '" + reference + "'}\n"
                            + "    load: {users: 1, duration: 1s}\n");
            LoadScenario scenario = new LoadScenarioLoader(project).load(source);
            LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
            att.validation.DiagnosticException invalid = assertThrows(att.validation.DiagnosticException.class,
                    () -> new LoadTargetValidator(project, config).validate(scenario, target), reference);
            assertEquals("workloads[0].inputs.value", invalid.field());
            assertTrue(invalid.detail().contains("input mapping"), invalid.format());
        }

        Path validSource = write(project, "load/input-mapping-phase-valid.yaml",
                "schemaVersion: att-load/v1.5\nworkloads:\n  - id: phase\n"
                        + "    target: {type: template, id: SHARED}\n"
                        + "    inputs: {value: ready, run: '${EXEC.RUN_ID}', user: '${EXEC.LOAD.USER_ID}'}\n"
                        + "    load: {users: 1, duration: 1s}\n");
        LoadScenario validScenario = new LoadScenarioLoader(project).load(validSource);
        LoadTarget validTarget = new LoadTargetResolver(project, config).resolve(validScenario);
        new LoadTargetValidator(project, config).validate(validScenario, validTarget);

        LoadWorkload workload = validScenario.workload();
        att.testdata.TestdataInputResolver testdata = new att.testdata.TestdataInputResolver(
                new att.testdata.TestdataRegistry(project, config.testdataDescriptors(),
                        validScenario.testdataDescriptors()), workload.testdata(), workload.id(),
                workload.model().wireName(), validScenario.seed(), workload.users());
        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationResult result = new IterationExecutor(project, config, validTarget, resources,
                    temp.resolve("input-mapping-phase-load"), testdata).execute(
                    IterationRequest.closed("phase-run", "phase-run-VU-1-1", 1, "STEADY",
                            Instant.now(), "VU-1", validScenario.inputs()).withWorkloadId(workload.id()));
            assertEquals(ResultStatus.PASS, result.status());
            assertEquals("phase-run", result.context().resolve("EXEC.INPUT.run"));
            assertEquals("VU-1", result.context().resolve("EXEC.INPUT.user"));
        }
    }

    @Test void loadDebugPromotionEvaluatesOverridesPerExecutionWithoutSharingValues() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        write(project, "templates/SHARED/template.yaml", "schemaVersion: att-template/v3.4\n"
                + "name: SHARED\ndescription: bootstrap fixture\nactions:\n"
                + "  check:\n    type: log\n    message: 'value=${EXEC.VARS.refNo}|id=${EXEC.VARS.executionId}'\n");
        write(project, "templates/SHARED/debug.yaml", "schemaVersion: att-debug/v1.1\ninputs: {amount: 17}\n"
                + "vars: {refNo: sidecar, executionId: '${EXEC.ID}', loadUser: '${EXEC.LOAD.USER_ID}'}\n");
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", "--debug", "template", "SHARED",
                "--input", "templates/SHARED/debug.yaml", "--users", "1", "--duration", "1s",
                "--run-id", "bootstrap-load", "--output-dir", temp.resolve("load-output").toString(),
                "--set", "vars.refNo=${EXEC.INPUT.amount}"});
        Map<String, Object> promoted = new DebugEngine(project, config).loadBootstrapInputForLoad(options);
        LoadScenario scenario = new LoadScenarioLoader(project).fromDebugInput((Path) promoted.get("source"),
                options.debugTargetType(), options.debugTargetId(), map(promoted.get("inputs")), map(promoted.get("vars")), options);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);

        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationExecutor executor = new IterationExecutor(project, config, target, resources, temp.resolve("load-output"));
            IterationResult first = executor.execute(IterationRequest.closed("bootstrap-load", "i-1", 1,
                    "STEADY", Instant.now(), "VU-1", scenario.inputs()).withWorkloadId(scenario.workloadId()));
            IterationResult second = executor.execute(IterationRequest.closed("bootstrap-load", "i-2", 2,
                    "STEADY", Instant.now(), "VU-1", scenario.inputs()).withWorkloadId(scenario.workloadId()));
            assertEquals(ResultStatus.PASS, first.status());
            assertEquals(ResultStatus.PASS, second.status());
            assertEquals(17L, ((Number) first.context().require("EXEC.VARS.refNo")).longValue());
            assertEquals(first.context().resolve("EXEC.ID"), first.context().resolve("EXEC.VARS.executionId"));
            assertEquals(second.context().resolve("EXEC.ID"), second.context().resolve("EXEC.VARS.executionId"));
            assertEquals("VU-1", first.context().resolve("EXEC.VARS.loadUser"));
            assertEquals("VU-1", second.context().resolve("EXEC.VARS.loadUser"));
            assertNotEquals(first.context().resolve("EXEC.VARS.executionId"), second.context().resolve("EXEC.VARS.executionId"));
            assertTrue(String.valueOf(first.context().resolve("EXEC.ACTIONS.check.output.result")).contains("value=17|id="));
        }
    }

    @Test void loadDebugPromotesTypedToolArgumentsIntoTheNormalLoadRuntime() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        write(project, "config/tools/echo.debug.yaml", "schemaVersion: att-debug/v1.1\narguments: {value: sidecar}\n");
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", "--debug", "tool", "echo",
                "--users", "1", "--duration", "1s", "--set", "arg.value=typed-load"});
        Map<String, Object> promoted = new DebugEngine(project, config).loadBootstrapInputForLoad(options);
        LoadScenario scenario = new LoadScenarioLoader(project).fromDebugInput((Path) promoted.get("source"),
                options.debugTargetType(), options.debugTargetId(), map(promoted.get("inputs")), map(promoted.get("vars")),
                map(promoted.get("arguments")), null, options);
        assertEquals("typed-load", scenario.targetArguments().get("value"));
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        IterationResult result = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("tool-debug-load", "tool-debug-load-1", 1,
                        "STEADY", Instant.now(), "VU-1", scenario.inputs()));
        assertEquals(ResultStatus.PASS, result.status());
        assertTrue(String.valueOf(result.context().resolve("EXEC.ACTIONS.loadTool.output.result")).contains("typed-load"));
    }

    @Test void groupedToolSidecarOverridesSurviveQuickLoadPromotion() throws Exception {
        Path project = fixture();
        write(project, "config/tools/group.debug.yaml", "schemaVersion: att-debug/v1.1\narguments: {value: from-root}\n"
                + "tools:\n  echo:\n    arguments: {value: from-sidecar}\n");
        Map<String, att.config.ToolArgumentConfig> arguments = Collections.singletonMap("value",
                new att.config.ToolArgumentConfig("value", "Value", "Value", true, ""));
        ToolConfig grouped = new ToolConfig("group.echo", "echo", "group", "Echo", "Grouped Echo",
                java.util.Arrays.asList("/bin/echo", "${value}"), Collections.<String>emptyList(), "txt", arguments, null);
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("group.echo", grouped), null, null);
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", "--debug", "tool", "group.echo",
                "--users", "1", "--duration", "1s", "--set", "arg.value=typed-load"});

        Map<String, Object> promoted = new DebugEngine(project, config).loadBootstrapInputForLoad(options);

        assertEquals("typed-load", map(promoted.get("arguments")).get("value"));
        LoadScenario scenario = new LoadScenarioLoader(project).fromDebugInput((Path) promoted.get("source"),
                options.debugTargetType(), options.debugTargetId(), map(promoted.get("inputs")), map(promoted.get("vars")),
                map(promoted.get("arguments")), null, options);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        IterationResult result = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("group-tool-debug-load", "group-tool-debug-load-1", 1,
                        "STEADY", Instant.now(), "VU-1", scenario.inputs()));

        assertEquals(ResultStatus.PASS, result.status());
        String output = String.valueOf(result.context().resolve("EXEC.ACTIONS.loadTool.output.result"));
        assertTrue(output.contains("typed-load"), output);
        assertFalse(output.contains("from-sidecar"), output);
    }

    @Test void missingStaticBootstrapInputFailsBeforeLoadSchedulingWithWorkloadSourceLocation() throws Exception {
        Path project = fixture();
        Path source = write(project, "load/missing-bootstrap-input.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: first\n    target: {type: template, id: SHARED}\n    load: {users: 1, duration: 1s}\n"
                + "  - id: second\n    target: {type: template, id: SHARED}\n"
                + "    inputs: {customer: {id: C001}}\n"
                + "    vars:\n      account: '${EXEC.INPUT.customer.account}'\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario parsed = new LoadScenarioLoader(project).load(source);
        LoadScenario selected = parsed.forWorkload(parsed.workload("second"));
        FrameworkConfig config = config();
        LoadTarget target = new LoadTargetResolver(project, config).resolve(selected);

        att.validation.DiagnosticException diagnostic = assertThrows(att.validation.DiagnosticException.class,
                () -> new LoadTargetValidator(project, config).validate(selected, target));

        assertEquals("workloads[1].vars.account", diagnostic.field());
        assertEquals(source.toString(), diagnostic.file());
        assertNotNull(diagnostic.source());
        assertEquals(10, diagnostic.source().line());
        assertTrue(diagnostic.format().contains("^"), diagnostic.format());
        assertTrue(diagnostic.detail().contains("workloadId: second"), diagnostic.detail());
    }

    @Test void optionalMissingInputsSurviveLoadBootstrapIncludingExpressionBlocks() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        write(project, "templates/OPTIONAL/template.yaml", "schemaVersion: att-template/v3.4\nname: OPTIONAL\n"
                + "description: optional bootstrap references\nactions:\n"
                + "  check: {type: log, message: 'optional=${EXEC.VARS.customerId}|block=${EXEC.VARS.region}'}\n");
        Path source = write(project, "load/optional-bootstrap-input.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: optional\n    target: {type: template, id: OPTIONAL}\n    inputs: {}\n"
                + "    vars:\n      customerId: '${EXEC.INPUT.customerId?}'\n"
                + "      loadUser: '${EXEC.LOAD.USER_ID}'\n"
                + "      iteration: '${EXEC.LOAD.ITERATION}'\n"
                + "      region: '#{${EXEC.INPUT.region?}}'\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(source);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);

        IterationResult result = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("optional-bootstrap", "optional-bootstrap-1", 1,
                        "STEADY", Instant.now(), "VU-1", scenario.inputs()));

        assertEquals(ResultStatus.PASS, result.status());
        assertNull(result.context().require("EXEC.VARS.customerId"));
        assertNull(result.context().require("EXEC.VARS.region"));
        assertEquals("VU-1", result.context().require("EXEC.VARS.loadUser"));
        assertEquals(1L, ((Number) result.context().require("EXEC.VARS.iteration")).longValue());
    }

    @Test void loadBootstrapValidatesKnownFieldsAndPreservesOptionalArrivalUserId() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        Path source = write(project, "load/arrival-optional-user.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: arrival\n    target: {type: template, id: SHARED}\n    inputs: {value: ready}\n"
                + "    vars:\n      userId: '${EXEC.LOAD.USER_ID?}'\n"
                + "      iteration: '${EXEC.LOAD.ITERATION}'\n"
                + "    load: {arrivalRate: 1/s, duration: 1s, maxConcurrent: 1, overloadPolicy: drop}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(source);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationExecutor executor = new IterationExecutor(project, config, target, resources,
                    temp.resolve("arrival-load-bootstrap"));
            IterationResult result = executor.execute(IterationRequest.arrivalRate("arrival-user", "arrival-1", 1,
                    "STEADY", Instant.now(), scenario.inputs()).withWorkloadId(scenario.workloadId()));
            assertEquals(ResultStatus.PASS, result.status());
            assertNull(result.context().require("EXEC.VARS.userId"));
            assertEquals(1L, ((Number) result.context().require("EXEC.VARS.iteration")).longValue());
        }

        Path typoSource = write(project, "load/arrival-typo-user.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: arrival\n    target: {type: template, id: SHARED}\n    inputs: {value: ready}\n"
                + "    vars:\n      userId: '${EXEC.LOAD.USRE_ID}'\n"
                + "    load: {arrivalRate: 1/s, duration: 1s, maxConcurrent: 1, overloadPolicy: drop}\n");
        LoadScenario typoScenario = new LoadScenarioLoader(project).load(typoSource);
        LoadTarget typoTarget = new LoadTargetResolver(project, config).resolve(typoScenario);
        Path output = temp.resolve("arrival-typo-output");
        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationExecutor seed = new IterationExecutor(project, config, typoTarget, resources, output);
            att.validation.DiagnosticException diagnostic = assertThrows(att.validation.DiagnosticException.class,
                    () -> LoadRunCoordinator.runFrom(typoScenario, seed, "arrival-typo", null, output));
            assertEquals("workloads[0].vars.userId", diagnostic.field());
            assertTrue(diagnostic.detail().contains("missing Load field"), diagnostic.format());
        }
        assertFalse(Files.exists(output.resolve("load/arrival-typo")),
                "a deterministic Load-field typo must fail before the scheduler creates run output");

        Path nestedSource = write(project, "load/closed-nested-load-field.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: closed\n    target: {type: template, id: SHARED}\n    inputs: {value: ready}\n"
                + "    vars:\n      nested: '${EXEC.LOAD.USER_ID.value}'\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario nestedScenario = new LoadScenarioLoader(project).load(nestedSource);
        LoadTarget nestedTarget = new LoadTargetResolver(project, config).resolve(nestedScenario);
        att.validation.DiagnosticException nested = assertThrows(att.validation.DiagnosticException.class,
                () -> new LoadTargetValidator(project, config).validate(nestedScenario, nestedTarget));
        assertEquals("workloads[0].vars.nested", nested.field());
        assertTrue(nested.detail().contains("structurally invalid Load path"), nested.format());

        Path unavailableSource = write(project, "load/arrival-required-user.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: arrival\n    target: {type: template, id: SHARED}\n    inputs: {value: ready}\n"
                + "    vars:\n      userId: '${EXEC.LOAD.USER_ID}'\n"
                + "    load: {arrivalRate: 1/s, duration: 1s, maxConcurrent: 1, overloadPolicy: drop}\n");
        LoadScenario unavailableScenario = new LoadScenarioLoader(project).load(unavailableSource);
        LoadTarget unavailableTarget = new LoadTargetResolver(project, config).resolve(unavailableScenario);
        att.validation.DiagnosticException unavailable = assertThrows(att.validation.DiagnosticException.class,
                () -> new LoadTargetValidator(project, config).validate(unavailableScenario, unavailableTarget));
        assertEquals("workloads[0].vars.userId", unavailable.field());
        assertTrue(unavailable.detail().contains("unavailable Load field"), unavailable.format());
    }

    @Test void optionalStructurallyInvalidInputPathFailsBeforeLoadSchedulerStarts() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        Path source = write(project, "load/optional-invalid-input-path.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: invalidPath\n    target: {type: template, id: SHARED}\n"
                + "    inputs: {value: ready, customer: C001}\n"
                + "    vars:\n      customerId: '${EXEC.INPUT.customer.id?}'\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(source);
        LoadTarget seedTarget = new LoadTargetResolver(project, config).resolve(scenario);
        Path output = temp.resolve("invalid-input-path-output");

        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationExecutor seed = new IterationExecutor(project, config, seedTarget, resources, output);
            att.validation.DiagnosticException diagnostic = assertThrows(att.validation.DiagnosticException.class,
                    () -> LoadRunCoordinator.runFrom(scenario, seed, "invalid-input-path-run", null, output));
            assertEquals("workloads[0].vars.customerId", diagnostic.field());
            assertTrue(diagnostic.detail().contains("structurally invalid input path"), diagnostic.format());
        }
        assertFalse(Files.exists(output.resolve("load/invalid-input-path-run")),
                "preflight failure must happen before a workload scheduler creates run output");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private List<att.core.ValidationResult> execute(Path project, FrameworkConfig config, StageTemplate template,
                                                      FlowRegistry flows, CaseRuntimeContext context, String stageName) throws Exception {
        Files.createDirectories(context.caseOutputDirectory());
        try (CaseExecutionLog log = new CaseExecutionLog(context.caseOutputDirectory().resolve("case.log"))) {
            return new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(project, config)), flows)
                    .execute(stageName, template, context, log);
        }
    }

    private void assertPortableResult(CaseRuntimeContext context, List<att.core.ValidationResult> results) {
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(ResultStatus.PASS, results.get(1).status());
        assertEquals(ResultStatus.PASS, results.get(2).status());
        assertEquals("shared-value", context.resolve("EXEC.INPUT.value"));
        assertEquals("value=shared-value", context.resolve("EXEC.ACTIONS.direct.output.result"));
        assertEquals("shared-value", String.valueOf(context.resolve("EXEC.ACTIONS.invoke.output.result")).trim());
        Map<?, ?> caseTree = context.caseTree();
        Map<?, ?> stages = (Map<?, ?>) caseTree.get("STAGES");
        String stageKey = String.valueOf(stages.keySet().iterator().next());
        assertEquals("flow=shared-value", CaseRuntimeContext.getPath(caseTree,
                "STAGES." + stageKey + ".TEMPLATE.ACTIONS.nested.flow.actions.flowLog.output.result"));
    }

    private CaseRuntimeContext testcaseContext(Path project, StageTemplate template, Map<String, Object> input) {
        StageCaseData stage = new StageCaseData("testcase", template.name(), Collections.<String, Object>emptyMap());
        TestCase testCase = new TestCase(1, "shared", "group", "TC1", Collections.<String>emptyList(), input,
                Collections.singletonMap("testcase", stage), "");
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, temp.resolve("testcase-output"), "testcase-run",
                temp.resolve("testcase-run"), temp.resolve("testcase-output/case.log"));
        context.setProject(project);
        context.beginStage(stage, template.name(), template.directory());
        return context;
    }

    private FrameworkConfig config() {
        Map<String, ToolArgumentConfig> arguments = Collections.singletonMap("value",
                new ToolArgumentConfig("value", "Value", "Value", true, ""));
        Map<String, ToolConfig> tools = Collections.singletonMap("echo",
                new ToolConfig("echo", "Echo", "Echo", "/bin/echo ${value}", "text", arguments));
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), tools, null, null);
    }

    private Path fixture() throws Exception {
        Path project = temp.resolve("project-" + System.nanoTime());
        att.TestSchemas.install(project);
        Files.createDirectories(project.resolve("templates/SHARED"));
        Files.createDirectories(project.resolve("templates/flows/shared/echo"));
        write(project, "templates/SHARED/template.yaml", "schemaVersion: att-template/v3.4\n"
                + "name: SHARED\ndescription: cross-mode fixture\nactions:\n"
                + "  direct:\n    type: log\n    message: \"value=${EXEC.INPUT.value}\"\n"
                + "  invoke:\n    type: tool\n    call: \"#{echo(value=${EXEC.INPUT.value})}\"\n"
                + "  nested:\n    type: flow\n    use: shared.echo.v1\n");
        write(project, "templates/flows/shared/echo/flow.yaml", "schemaVersion: att-flow/v3.4\n"
                + "id: shared.echo.v1\nname: Shared Echo\ndescription: cross-mode flow\nactions:\n"
                + "  flowLog:\n    type: log\n    message: \"flow=${EXEC.INPUT.value}\"\n");
        write(project, "templates/SHARED/debug.yaml", "schemaVersion: att-debug/v1.1\ninputs:\n  value: shared-value\n");
        return project;
    }

    private Path write(Path project, String relative, String content) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        return LoadTestSupport.writeScenario(file, content);
    }
}
