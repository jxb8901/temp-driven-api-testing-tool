package att.debug;

import att.Version;
import att.config.FrameworkConfig;
import att.config.SchemaSupport;
import att.config.ToolConfig;
import att.config.YamlSupport;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ExecutionOptions;
import att.core.IdentifierValidator;
import att.core.ResultAggregator;
import att.core.ResultStatus;
import att.core.StageCaseData;
import att.core.TestCase;
import att.core.ValidationResult;
import att.exec.DbHelperExecutor;
import att.exec.MqHelperExecutor;
import att.exec.ToolInvoker;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.TemplateAction;
import att.template.UnifiedTemplateEngine;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import att.validation.JsonSchemaVerifier;
import att.validation.PackageValidator;

import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs one real Template, Flow, or Tool outside the Excel testcase loop. */
public final class DebugEngine {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final att.exec.MqTransport.Factory mqTransportFactory;

    public DebugEngine(Path projectRoot, FrameworkConfig config) {
        this(projectRoot, config, new att.exec.IbmMqClientFactory());
    }

    /** Injectable transport boundary for repeatable Debug integration tests. */
    public DebugEngine(Path projectRoot, FrameworkConfig config, att.exec.MqTransport.Factory mqTransportFactory) {
        if (mqTransportFactory == null) throw new IllegalArgumentException("MQ transport factory is required");
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
        this.mqTransportFactory = mqTransportFactory;
    }

    /** Reads and validates a Debug sidecar for in-memory Load promotion without executing the target. */
    public Map<String, Object> loadBootstrapInputForLoad(ExecutionOptions options) throws Exception {
        DebugInput input = loadInput(options, options.debugTargetType(), options.debugTargetId(),
                att.core.ExecutionBootstrapVariables.Scope.LOAD);
        if (!input.testdataDescriptors.isEmpty())
            throw debugError("Debug-local testdata imports cannot be promoted by load --debug",
                    "Use a Load scenario with its own top-level testdata imports.");
        Map<String, Object> promoted = new LinkedHashMap<String, Object>();
        promoted.put("source", input.path);
        promoted.put("inputs", input.inputs);
        promoted.put("vars", input.vars);
        promoted.put("arguments", input.arguments);
        return promoted;
    }

    /** Validates one discovery candidate without creating output or executing a resource call. */
    public Path validateDiscoverableTarget(String type, String id) throws Exception {
        ExecutionOptions options = targetOptions("debug", type, id);
        Path sidecar = autoInput(type, id);
        DebugInput input;
        if (Files.isRegularFile(sidecar) && !Files.isSymbolicLink(sidecar)) input = loadInput(options, type, id);
        else {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("schemaVersion", Version.DEBUG_SCHEMA);
            input = new DebugInput(sidecar, empty, type, id, config);
        }
        ResolvedTarget resolved = resolveTarget(type, id, input);
        StageCaseData stage = input.stage(resolved.template.name());
        TestCase testCase = syntheticCase(type, id, input, stage);
        if ("template".equals(type) || "flow".equals(type)) {
            UnifiedTemplateEngine bootstrapEngine = new UnifiedTemplateEngine(null, null, null, null,
                    new att.template.DefaultBuiltInProvider(new att.template.SequenceService()));
            att.core.ExecutionBootstrapVariables.validate(input.vars, bootstrapEngine, input.inputs, input.path,
                    "vars", DiagnosticCodes.DEBUG_INVALID, att.core.ExecutionBootstrapVariables.Scope.DEBUG);
        }
        new PackageValidator(projectRoot, config).validateDebugTarget(resolved.template, testCase, stage,
                resolved.flows, input.path, "debug", input.inputs, input.vars, input.testdataDescriptors);
        return Files.isRegularFile(sidecar) && !Files.isSymbolicLink(sidecar) ? sidecar : null;
    }

    /** Returns the exact optional sidecar path used by Debug auto-discovery. */
    public Path discoverableInputPath(String type, String id) throws Exception {
        return autoInput(type, id);
    }

    /** Validates a sidecar using the exact Load promotion path, not standalone Debug root availability. */
    public Path validateDiscoverableTargetForLoad(String type, String id,
                                                  Map<String, Object> quickLoadPolicy) throws Exception {
        ExecutionOptions options = targetOptions("load", type, id);
        Map<String, Object> promoted = loadBootstrapInputForLoad(options);
        att.load.LoadScenario scenario = new att.load.LoadScenarioLoader(projectRoot).fromDebugInput(
                (Path) promoted.get("source"), type, id, DebugInput.map(promoted.get("inputs")),
                DebugInput.map(promoted.get("vars")), DebugInput.map(promoted.get("arguments")),
                quickLoadPolicy, options);
        att.load.LoadTarget target = new att.load.LoadTargetResolver(projectRoot, config).resolve(scenario);
        new att.load.LoadTargetValidator(projectRoot, config).validate(scenario, target);
        return (Path) promoted.get("source");
    }

    public Result run(ExecutionOptions options) throws Exception {
        String target = options.debugTargetType() + ":" + safeConsoleIdentity(options.debugTargetId());
        att.core.ConsoleCancellationHook cancellation = new att.core.ConsoleCancellationHook(new Runnable() {
            @Override public void run() { options.emitEvent(new att.api.ExecutionEvent(att.api.ExecutionEvent.Type.STATUS,
                    null,null,null,null,"CANCELLED",null,null,java.util.Collections.<String,Object>singletonMap("target",target))); }
        });
        try { return runInternal(options); }
        finally { cancellation.close(); }
    }

    private ExecutionOptions targetOptions(String command, String type, String id) {
        return ExecutionOptions.forApi(command, projectRoot.resolve("config/config.yaml"), config.environment(),
                Collections.<Path>emptyList(), null, Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), null, false, false, false, false, null, "selected", type, id,
                null, false, null, null, null, null, null, null, null, null, null, null,
                Collections.<String>emptyList());
    }

    private Result runInternal(ExecutionOptions options) throws Exception {
        Instant started = Instant.now();
        String targetType = options.debugTargetType();
        String targetId = options.debugTargetId();
        if (options.unsafeFailureDetails()) options.emitEvent(new att.api.ExecutionEvent(att.api.ExecutionEvent.Type.WARNING,
                null,null,null,null,"UNSAFE_FAILURE_DETAILS",null,
                "[ATT WARNING] --unsafe-failure-details is enabled: collector diagnostics may expose local data. Configured secrets remain redacted. Use only with trusted local data.",
                java.util.Collections.<String,Object>emptyMap()));
        Path debugDirectory = createDebugDirectory(options, targetType, targetId);
        Path artifacts = debugDirectory.resolve("artifacts");
        Path logPath = debugDirectory.resolve("case.log");
        Path resultPath = debugDirectory.resolve("result.yaml");
        Files.createDirectories(artifacts);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", Version.DEBUG_SCHEMA);
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("type", targetType);
        target.put("id", targetId);
        result.put("target", target);
        result.put("debugId", debugDirectory.getFileName().toString());
        result.put("outputDirectory", att.core.PathPresentation.displayPath(debugDirectory, projectRoot));
        result.put("log", att.core.PathPresentation.displayPath(logPath, projectRoot));
        result.put("artifacts", att.core.PathPresentation.displayPath(artifacts, projectRoot));
        result.put("failureDetailMode", options.unsafeFailureDetails() ? "local-unsafe" : "safe-default");

        DebugInput input = null;
        CaseRuntimeContext context = null;
        CaseExecutionLog log = null;
        DbHelperExecutor db = null;
        att.exec.HttpHelperExecutor http = null;
        boolean caseStarted = false;
        boolean stageStarted = false;
        boolean stageFinished = false;
        ResultStatus status = ResultStatus.ERROR;
        int exitCode = 3;
        DiagnosticException diagnostic = null;
        List<ValidationResult> actionResults = new ArrayList<ValidationResult>();

        try {
            if (options.hasObserver()) {
                Map<String,Object> eventData=new LinkedHashMap<String,Object>(); eventData.put("event","DEBUG_STARTED");
                eventData.put("targetType",targetType); eventData.put("targetId",targetId);
                eventData.put("input",options.debugInput()==null?"auto":options.debugInput().toString());
                eventData.put("output",att.core.PathPresentation.displayPath(debugDirectory,projectRoot));
                options.emitEvent(new att.api.ExecutionEvent(att.api.ExecutionEvent.Type.DEBUG,debugDirectory.getFileName().toString(),null,null,null,"START",null,null,eventData));
            }
            log = new CaseExecutionLog(logPath, config.caseLogYamlAnchors(),
                    options.hasObserver()
                            ? new att.core.CaseLogConsoleMirror("debug:" + targetType + ":" + targetId,
                                new java.util.function.Consumer<String>() {
                                    @Override public void accept(String text) { options.emitCaseLog(debugDirectory.getFileName().toString(), "debug:" + targetType + ":" + targetId, text); }
                                })
                            : null);
            log.setProjectRoot(projectRoot);
            input = loadInput(options, targetType, targetId);
            result.put("input", att.core.PathPresentation.displayPath(input.path, projectRoot));
            ResolvedTarget resolved = resolveTarget(targetType, targetId, input);
            StageCaseData stage = input.stage(resolved.template.name());
            TestCase testCase = syntheticCase(targetType, targetId, input, stage);
            if ("tool".equals(targetType))
                att.testdata.TestdataSyntax.rejectDirectReferences(input.arguments, "Debug Tool arguments");
            UnifiedTemplateEngine bootstrapEngine = new UnifiedTemplateEngine(null, null, null, null,
                    new att.template.DefaultBuiltInProvider(new att.template.SequenceService()));
            att.core.ExecutionBootstrapVariables.validate(input.vars, bootstrapEngine, input.inputs, input.path,
                    "vars", DiagnosticCodes.DEBUG_INVALID, att.core.ExecutionBootstrapVariables.Scope.DEBUG);
            if (options.hasObserver()) {
                Map<String,Object> eventData=new LinkedHashMap<String,Object>(); eventData.put("event","DEBUG_INPUT_RESOLVED");
                eventData.put("targetType",targetType); eventData.put("targetId",targetId);
                eventData.put("resolved",att.core.PathPresentation.displayPath(input.path,projectRoot));
                options.emitEvent(new att.api.ExecutionEvent(att.api.ExecutionEvent.Type.DEBUG,debugDirectory.getFileName().toString(),
                        testCase.caseId(),null,null,"INPUT_RESOLVED",null,null,eventData));
            }

            new PackageValidator(projectRoot, config).validateDebugTarget(resolved.template, testCase, stage,
                    resolved.flows, input.path, "debug", testCase.caseData(), input.vars, input.testdataDescriptors);

            context = new CaseRuntimeContext(testCase, artifacts, debugDirectory.getFileName().toString(), debugDirectory, logPath, "debug");
            context.setProject(projectRoot);
            context.setUnsafeFailureDetails(options.unsafeFailureDetails());
            context.setSourceMetadata("debug", input.path, testCase.caseId());
            context.setTargetMetadata(targetType, targetId);
            context.setTemplateMetadata(resolved.template.name(), resolved.template.directory());
            context.put("CASE.environment", config.environment());
            context.put("CASE.failureDetailMode", options.unsafeFailureDetails() ? "local-unsafe" : "safe-default");
            att.testdata.TestdataInputResolver testdata = new att.testdata.TestdataInputResolver(
                    new att.testdata.TestdataRegistry(projectRoot, config.testdataDescriptors(), input.testdataDescriptors,
                            "debug-local"));
            context.replaceInputValues(testdata.resolve(testCase.caseData(), context, null, null));
            if ("template".equals(targetType) || "flow".equals(targetType))
                att.core.ExecutionBootstrapVariables.evaluate(input.vars, context, bootstrapEngine,
                        att.core.ExecutionBootstrapVariables.Scope.DEBUG);
            Map<String, Object> resolvedStageValues = testdata.resolve(stage.values(), context, null, null);
            if (!testdata.selectionEvidence().isEmpty())
                context.put("CASE.testdataSelections", testdata.selectionEvidence());
            stage = new StageCaseData(stage.key(), stage.templateName(), resolvedStageValues);
            context.put("CASE.debugInput", input.path.toString());
            Map<String, Object> debugHeader = new LinkedHashMap<String, Object>();
            debugHeader.put("target", target);
            debugHeader.put("input", input.path.toString());
            debugHeader.put("caseId", testCase.caseId());
            log.append("DEBUG TARGET", debugHeader);
            context.beginStage(stage, resolved.template.name(), resolved.template.directory());
            stageStarted = true;

            db = new DbHelperExecutor(projectRoot, config);
            db.beginCase();
            caseStarted = true;
            ToolInvoker toolInvoker = new ToolInvoker(projectRoot, config);
            MqHelperExecutor mq = new MqHelperExecutor(projectRoot, config, mqTransportFactory);
            http = new att.exec.HttpHelperExecutor(projectRoot, config);
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(toolInvoker, db, mq, http,
                    new att.template.DefaultBuiltInProvider(new att.template.SequenceService()));
            actionResults.addAll(new att.template.StageTemplateRunner(engine, resolved.flows)
                    .execute(stage.key(), resolved.template, context, log));
            actionResults.addAll(db.finishCase(context, log));
            status = ResultAggregator.aggregate(statuses(actionResults));
            exitCode = ResultAggregator.exitCode(status);
            context.put("CASE.status", status.name());
            context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            context.finishStage(status.name(), Duration.between(started, Instant.now()).toMillis());
            stageFinished = true;
        } catch (DiagnosticException e) {
            diagnostic = e.withDetail("Debug input: " + (input == null ? expectedInput(options, targetType, targetId) : input.path));
            status = ResultStatus.INVALID;
            exitCode = 2;
            if (context != null) context.put("CASE.status", status.name());
            appendError(log, diagnostic);
        } catch (IllegalArgumentException e) {
            DiagnosticException typed = DiagnosticException.find(e);
            diagnostic = (typed == null
                    ? new DiagnosticException(DiagnosticCodes.DEBUG_INVALID, "Invalid debug target", e.getMessage(),
                    null, "debug", null, null, null, targetId, null,
                    "Correct the target, sidecar schema, or selected dependency input.", e)
                    : typed).withDetail("Debug input: " + (input == null ? expectedInput(options, targetType, targetId) : input.path));
            status = ResultStatus.INVALID;
            exitCode = 2;
            if (context != null) context.put("CASE.status", status.name());
            appendError(log, diagnostic);
        } catch (Exception e) {
            diagnostic = new DiagnosticException(DiagnosticCodes.RUN_FAILED, "Debug execution failed",
                    e.getMessage(), null, "debug", null, null, null, targetId, null,
                    "Inspect case.log and artifacts; use --quiet to suppress live progress.", e)
                    .withDetail("Debug input: " + (input == null ? expectedInput(options, targetType, targetId) : input.path));
            status = ResultStatus.ERROR;
            exitCode = 3;
            if (context != null) context.put("CASE.status", status.name());
            appendError(log, diagnostic);
            if (caseStarted && db != null) db.abortCase();
        } finally {
            if (context != null) {
                context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
                if (stageStarted && !stageFinished) {
                    try { context.finishStage(status.name(), Duration.between(started, Instant.now()).toMillis()); } catch (Exception ignored) { }
                }
                try { Files.write(artifacts.resolve("case.yaml"), new Yaml().dump(att.core.PathPresentation.displayStructure(context.caseTree(), projectRoot)).getBytes(StandardCharsets.UTF_8)); }
                catch (Exception ignored) { /* result.yaml still records the primary outcome */ }
            }
            if (log != null) try { log.close(); } catch (Exception ignored) { }
            if (db != null) db.close();
            if (http != null) http.close();
        }

        long durationMs = Duration.between(started, Instant.now()).toMillis();
        result.put("status", status.name());
        result.put("exitCode", Integer.valueOf(exitCode));
        result.put("durationMs", Long.valueOf(durationMs));
        result.put("caseId", "DEBUG." + targetType + "." + safeRowId(targetId));
        result.put("actions", actionMaps(actionResults));
        if (context != null) result.put("case", att.core.PathPresentation.displayStructure(context.caseTree(), projectRoot));
        if (diagnostic != null) result.put("diagnostic", diagnostic.toDiagnostic().toMap());
        Files.write(resultPath, new Yaml().dump(att.core.PathPresentation.displayStructure(result, projectRoot)).getBytes(StandardCharsets.UTF_8));
        return new Result(status, exitCode, durationMs, debugDirectory, logPath, resultPath, diagnostic);
    }

    private ResolvedTarget resolveTarget(String type, String id, DebugInput input) throws Exception {
        if ("template".equals(type)) {
            StageTemplate template = new StageTemplateLoader(projectRoot, config.templatesRoot(), false).loadSelected(id);
            return new ResolvedTarget(template, new FlowRegistry(projectRoot, config.templatesRoot(), false));
        }
        FlowRegistry flows = new FlowRegistry(projectRoot, config.templatesRoot(), false);
        if ("flow".equals(type)) {
            FlowDefinition flow = flows.get(id);
            if (flow == null) throw debugError("Unknown Flow '" + id + "'", "Define the Flow below templates/flows and use its canonical .vN id.");
            Map<String, Object> action = new LinkedHashMap<String, Object>();
            action.put("type", "flow");
            action.put("use", id);
            List<TemplateAction> actions = Collections.singletonList(new TemplateAction("debugFlow", action, Version.TEMPLATE_SCHEMA));
            return new ResolvedTarget(new StageTemplate(flow.name(), flow.directory(), actions, Version.TEMPLATE_SCHEMA,
                    flow.directory().resolve("flow.yaml")), flows);
        }
        ToolConfig tool = findTool(id);
        if (tool == null) throw debugError("Unknown Tool '" + id + "'", "Use the qualified group.tool key from config/config.yaml or define that Tool.");
        Map<String, Object> action = new LinkedHashMap<String, Object>();
        action.put("type", "tool");
        action.put("call", call(tool.key(), input.arguments));
        List<TemplateAction> actions = Collections.singletonList(new TemplateAction("debugTool", action, Version.TEMPLATE_SCHEMA));
        Path source = tool.sourceFile() == null ? projectRoot.resolve("config/config.yaml") : tool.sourceFile();
        return new ResolvedTarget(new StageTemplate(tool.name(), source.getParent(), actions, Version.TEMPLATE_SCHEMA, source), flows);
    }

    private TestCase syntheticCase(String type, String id, DebugInput input, StageCaseData stage) {
        Map<String, Object> caseData = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : input.caseValues.entrySet())
            if (!isFrameworkCaseField(entry.getKey())) caseData.put(entry.getKey(), entry.getValue());
        for (Map.Entry<String, Object> entry : input.inputs.entrySet()) if (!caseData.containsKey(entry.getKey())) caseData.put(entry.getKey(), entry.getValue());
        if (!caseData.containsKey("caseName")) caseData.put("caseName", "DEBUG " + type + " " + id);
        Map<String, StageCaseData> stages = new LinkedHashMap<String, StageCaseData>();
        stages.put(stage.key(), stage);
        return new TestCase(1, "DEBUG", type, "debug", safeRowId(id), Collections.<String>emptyList(), caseData, stages, "");
    }

    private boolean isFrameworkCaseField(String name) {
        if (name == null) return false;
        String normalized = name.toUpperCase(java.util.Locale.ROOT);
        return java.util.Arrays.asList("CASEID", "WORKBOOKID", "GROUPID", "ROWCASEID", "WORKBOOK",
                "SHEET", "ROWNUMBER", "TAGS", "STATUS", "STARTEDAT", "OUTPUTDIRECTORY",
                "DURATIONMS", "ENVIRONMENT", "DEBUGINPUT", "VARS", "DB", "STAGES").contains(normalized);
    }

    private DebugInput loadInput(ExecutionOptions options, String type, String id) throws Exception {
        return loadInput(options, type, id, att.core.ExecutionBootstrapVariables.Scope.DEBUG);
    }

    private DebugInput loadInput(ExecutionOptions options, String type, String id,
                                 att.core.ExecutionBootstrapVariables.Scope bootstrapScope) throws Exception {
        Path path = options.debugInput() == null ? autoInput(type, id) : resolveInput(options.debugInput());
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
            throw debugError("Debug input file does not exist: " + path, "Create the sidecar file or pass --input <path>.");
        try {
            Object loaded = YamlSupport.load(path);
            if (!(loaded instanceof Map)) throw debugError("Debug input must be a YAML map: " + path, "Use schemaVersion: " + Version.DEBUG_SCHEMA + ".");
            Map<String, Object> map = objectMap((Map<?, ?>) loaded);
            Object declaredVersion = map.get("schemaVersion");
            String schemaVersion = declaredVersion == null ? "" : String.valueOf(declaredVersion);
            Path schema = att.validation.SchemaFiles.resolveVersion(projectRoot, schemaVersion);
            JsonSchemaVerifier.verify(schema, map);
            if (Version.PREVIOUS_DEBUG_SCHEMA.equals(schemaVersion) || Version.OLDER_DEBUG_SCHEMA.equals(schemaVersion)) {
                throw new DiagnosticException(DiagnosticCodes.SCHEMA_VERSION_OLD,
                        "Debug input uses a historical schemaVersion",
                        "declaredSchemaVersion=" + schemaVersion
                                + "\ncurrentSchemaVersion=" + Version.DEBUG_SCHEMA,
                        path.toString(), "schemaVersion", null, null, null, null, null,
                        "Upgrade schemaVersion to " + Version.DEBUG_SCHEMA + "; use top-level vars for initial EXEC.VARS values.", null);
            }
            SchemaSupport.requireVersion(map, Version.DEBUG_SCHEMA, "debug input");
            List<String> overrides = options.setOverrides();
            if (att.core.CliSetOverrides.hasNamespace(overrides, "arg") && !"tool".equals(type))
                throw debugError("--set arg.* is valid only for a Tool target", "Use --set input.* for EXEC.INPUT or select a Tool target for arg.* overrides.");
            if (att.core.CliSetOverrides.hasNamespace(overrides, "vars") && "tool".equals(type))
                throw debugError("--set vars.* is not supported for Tool targets", "Tool Debug uses its explicit arguments contract.");
            Map<String, Object> inputs = DebugInput.map(map.get("inputs"));
            Object rawVars = map.get("vars");
            UnifiedTemplateEngine bootstrapEngine = new UnifiedTemplateEngine(null, null, null, null,
                    new att.template.DefaultBuiltInProvider(new att.template.SequenceService()));
            if (rawVars != null && !(rawVars instanceof Map))
                validateDebugVariables(rawVars, type, path, inputs, bootstrapEngine, bootstrapScope);
            Map<String, Object> vars = DebugInput.map(rawVars);
            inputs = att.core.CliSetOverrides.apply(inputs, overrides, "input");
            vars = att.core.CliSetOverrides.apply(vars, overrides, "vars");
            vars = validateDebugVariables(vars, type, path, inputs, bootstrapEngine, bootstrapScope);
            if (map.containsKey("inputs") || att.core.CliSetOverrides.hasNamespace(overrides, "input")) map.put("inputs", inputs);
            if (map.containsKey("vars") || att.core.CliSetOverrides.hasNamespace(overrides, "vars")) map.put("vars", vars);
            Map<String, Object> effectiveToolArguments = null;
            if ("tool".equals(type)) {
                // Preserve the existing root-versus-group resolution, then carry its result
                // forward explicitly so later construction cannot re-read stale sidecar values.
                effectiveToolArguments = new LinkedHashMap<String, Object>(
                        new DebugInput(path, map, type, id, config).arguments);
                effectiveToolArguments = att.core.CliSetOverrides.apply(effectiveToolArguments, overrides, "arg");
            }
            List<Path> debugTestdata = projectRelativeDescriptors(map.get("testdata"));
            if (!debugTestdata.isEmpty())
                new att.testdata.TestdataRegistry(projectRoot, Collections.<Path>emptyList(), debugTestdata,
                        "debug-local").validateAll();
            return new DebugInput(path, map, type, id, config, debugTestdata, effectiveToolArguments);
        } catch (DiagnosticException e) {
            throw e;
        } catch (Exception e) {
            throw new DiagnosticException(DiagnosticCodes.DEBUG_INVALID, "Invalid debug input", e.getMessage(), path.toString(),
                    "debug", null, null, null, id, null,
                    "Correct the debug YAML and validate it against schemas/att-debug-v1.2.schema.json.", e);
        }
    }

    private List<Path> projectRelativeDescriptors(Object raw) {
        if (!(raw instanceof List)) return Collections.emptyList();
        List<Path> result = new ArrayList<Path>();
        Path root = projectRoot.toAbsolutePath().normalize();
        for (Object value : (List<?>) raw) {
            if (!(value instanceof String) || ((String) value).trim().isEmpty())
                throw new IllegalArgumentException("Debug testdata entries must be non-empty package-relative paths");
            Path declared = java.nio.file.Paths.get((String) value);
            if (declared.isAbsolute()) throw new IllegalArgumentException("Debug testdata paths must be package-relative");
            Path resolved = root.resolve(declared).normalize();
            if (!resolved.startsWith(root)) throw new IllegalArgumentException("Debug testdata path escapes package root");
            result.add(resolved);
        }
        return Collections.unmodifiableList(result);
    }

    private Path autoInput(String type, String id) throws Exception {
        if ("template".equals(type)) {
            StageTemplate template = new StageTemplateLoader(projectRoot, config.templatesRoot(), false).loadSelected(id);
            return template.directory().resolve("debug.yaml");
        }
        if ("flow".equals(type)) {
            FlowDefinition flow = new FlowRegistry(projectRoot, config.templatesRoot(), false).get(id);
            if (flow == null) throw debugError("Unknown Flow '" + id + "'", "Define the Flow before creating its debug.yaml sidecar.");
            return flow.directory().resolve("debug.yaml");
        }
        ToolConfig tool = findTool(id);
        if (tool == null) throw debugError("Unknown Tool '" + id + "'", "Define the Tool before creating its debug.yaml sidecar.");
        String group = tool.groupId().isEmpty() ? tool.localKey() : tool.groupId();
        return projectRoot.resolve("config/tools").resolve(group + ".debug.yaml");
    }

    private Path resolveInput(Path configured) {
        return (configured.isAbsolute() ? configured : projectRoot.resolve(configured)).toAbsolutePath().normalize();
    }

    private Path createDebugDirectory(ExecutionOptions options, String type, String id) throws Exception {
        Path root = options.outputDirectory() == null ? projectRoot.resolve(config.outputDirectory()) : resolveInput(options.outputDirectory());
        Path debugRoot = root.resolve("debug").normalize();
        boolean explicit = options.runId() != null && !options.runId().trim().isEmpty();
        boolean configured = !config.run().debugIdFormat().isEmpty();
        String safe;
        if (explicit) safe = IdentifierValidator.runId(options.runId());
        else if (configured) safe = att.core.ExecutionIdentityFormat.debugId(config.run().debugIdFormat(),
                type + "-" + id, projectRoot, type, id,
                options.debugInput() == null ? projectRoot : resolveInput(options.debugInput()));
        else safe = IdentifierValidator.runId((type + "-" + id).replaceAll("[^A-Za-z0-9_.-]", "_"));
        Path result = debugRoot.resolve(safe).normalize();
        Files.createDirectories(debugRoot);
        try {
            Files.createDirectory(result);
        } catch (java.nio.file.FileAlreadyExistsException collision) {
            if (explicit || configured) throw new IllegalArgumentException("Debug ID already exists: " + safe + " (" + result + "). Choose a different --debug-id.");
            result = debugRoot.resolve(IdentifierValidator.runId(safe + "-" + System.currentTimeMillis()));
            Files.createDirectory(result);
        }
        return result;
    }

    private String expectedInput(ExecutionOptions options, String type, String id) {
        if (options.debugInput() != null) return resolveInput(options.debugInput()).toString();
        try { return autoInput(type, id).toString(); }
        catch (Exception ignored) { return type + " sidecar for " + id; }
    }

    private ToolConfig findTool(String id) {
        ToolConfig direct = config.tool(id);
        if (direct != null) return direct;
        ToolConfig match = null;
        for (ToolConfig candidate : config.tools().values()) {
            if (!candidate.localKey().equals(id)) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    private String safeRowId(String id) { return id.replaceAll("[^A-Za-z0-9_.-]", "_"); }

    private DiagnosticException debugError(String message, String suggestion) {
        return new DiagnosticException(DiagnosticCodes.DEBUG_INVALID, message, null, null, "debug", null, null, null,
                null, null, suggestion, null);
    }

    private Map<String, Object> validateDebugVariables(Object raw, String targetType, Path path,
                                                        Map<String, Object> inputs,
                                                        UnifiedTemplateEngine bootstrapEngine,
                                                        att.core.ExecutionBootstrapVariables.Scope bootstrapScope) {
        if (raw != null && !(raw instanceof Map)) {
            DiagnosticException error = new DiagnosticException(DiagnosticCodes.DEBUG_INVALID,
                    "Invalid debug.vars", "vars must be a YAML object/map", path.toString(), "vars",
                    null, null, null, null, null,
                    "Use vars: {name: value} only for standalone Template or Flow Debug.", null);
            throw YamlSupport.locate(error, path, "vars");
        }
        Map<String, Object> vars = DebugInput.map(raw);
        if ("tool".equals(targetType)) {
            if (!vars.isEmpty()) {
                DiagnosticException error = new DiagnosticException(DiagnosticCodes.DEBUG_INVALID,
                        "Debug vars are not supported for Tool targets",
                        "debug.vars is supported only for standalone Template and Flow targets",
                        path.toString(), "vars", null, null, null, null, null,
                        "Use the Tool arguments contract for standalone Tool Debug.", null);
                throw YamlSupport.locate(error, path, "vars");
            }
            return vars;
        }
        return att.core.ExecutionBootstrapVariables.validate(vars, bootstrapEngine, inputs, path,
                "vars", DiagnosticCodes.DEBUG_INVALID, bootstrapScope);
    }

    private void appendError(CaseExecutionLog log, DiagnosticException error) {
        if (log == null) return;
        try { log.append("DEBUG ERROR", error.toDiagnostic().toMap()); } catch (Exception ignored) { }
    }

    private static void consoleLine(java.io.PrintStream output, String line) {
        synchronized (output) {
            output.println(line);
            output.flush();
        }
    }

    private static String safeConsoleIdentity(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private List<ResultStatus> statuses(List<ValidationResult> values) {
        List<ResultStatus> result = new ArrayList<ResultStatus>();
        for (ValidationResult value : values) result.add(value.status());
        return result;
    }

    private List<Map<String, Object>> actionMaps(List<ValidationResult> values) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ValidationResult value : values) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("source", value.source()); map.put("name", value.name()); map.put("status", value.status().name());
            map.put("description", value.description()); map.put("expected", value.expected()); map.put("actual", value.actual()); map.put("message", value.message());
            if (value.diagnostic() != null) map.put("diagnostic", value.diagnostic().toMap());
            result.add(map);
        }
        return result;
    }

    private String call(String tool, Map<String, Object> arguments) {
        StringBuilder result = new StringBuilder("#{").append(tool).append('(');
        int index = 0;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (index++ > 0) result.append(", ");
            result.append(entry.getKey()).append('=').append(literal(entry.getValue()));
        }
        return result.append(")}").toString();
    }

    private String literal(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof Iterable) {
            StringBuilder result = new StringBuilder("["); int index = 0;
            for (Object item : (Iterable<?>) value) { if (index++ > 0) result.append(", "); result.append(literal(item)); }
            return result.append(']').toString();
        }
        if (value instanceof Map) throw debugError("Tool debug arguments do not support map literals: " + value, "Pass a scalar or list argument, or use a normal Tool Action for structured input.");
        String text = String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n");
        return "'" + text + "'";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : value.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    public static final class Result {
        private final ResultStatus status; private final int exitCode; private final long durationMs;
        private final Path outputDirectory, logPath, resultPath; private final DiagnosticException diagnostic;
        private Result(ResultStatus status, int exitCode, long durationMs, Path outputDirectory, Path logPath, Path resultPath, DiagnosticException diagnostic) {
            this.status = status; this.exitCode = exitCode; this.durationMs = durationMs; this.outputDirectory = outputDirectory; this.logPath = logPath; this.resultPath = resultPath; this.diagnostic = diagnostic;
        }
        public ResultStatus status() { return status; } public int exitCode() { return exitCode; } public long durationMs() { return durationMs; }
        public Path outputDirectory() { return outputDirectory; } public Path logPath() { return logPath; } public Path resultPath() { return resultPath; }
        public DiagnosticException diagnostic() { return diagnostic; }
        public String executionId() { return outputDirectory == null ? null : outputDirectory.getFileName().toString(); }
    }

    private static final class ResolvedTarget {
        private final StageTemplate template; private final FlowRegistry flows;
        private ResolvedTarget(StageTemplate template, FlowRegistry flows) { this.template = template; this.flows = flows; }
    }

    private static final class DebugInput {
        private final Path path; private final Map<String, Object> caseValues; private final Map<String, Object> inputs; private final Map<String, Object> vars; private final Map<String, Object> arguments; private final Map<String, Object> stageValues; private final String stageKey; private final List<Path> testdataDescriptors;
        private DebugInput(Path path, Map<String, Object> root, String type, String id, FrameworkConfig config) {
            this(path, root, type, id, config, Collections.<Path>emptyList(), null);
        }
        private DebugInput(Path path, Map<String, Object> root, String type, String id, FrameworkConfig config,
                           List<Path> testdataDescriptors, Map<String, Object> effectiveToolArguments) {
            this.path = path;
            this.caseValues = map(root.get("case"));
            this.inputs = map(root.get("inputs"));
            this.vars = map(root.get("vars"));
            this.stageValues = map(map(root.get("stage")).get("values"));
            this.testdataDescriptors = testdataDescriptors;
            Object key = map(root.get("stage")).get("key");
            this.stageKey = key == null ? "DEBUG" : String.valueOf(key);
            Map<String, Object> rootArguments = map(root.get("arguments"));
            Map<String, Object> selectedArguments = effectiveToolArguments == null ? rootArguments : effectiveToolArguments;
            if ("tool".equals(type) && effectiveToolArguments == null) {
                Map<String, Object> tools = configMap(root.get("tools"));
                ToolConfig tool = findTool(config, id);
                String local = tool == null ? id : tool.localKey();
                Map<String, Object> selected = map(tools.get(local));
                if (selected.isEmpty()) selected = map(tools.get(id));
                Map<String, Object> toolArguments = map(selected.get("arguments"));
                if (!toolArguments.isEmpty()) selectedArguments = toolArguments;
            }
            this.arguments = selectedArguments;
        }
        private StageCaseData stage(String templateName) { return new StageCaseData(stageKey, templateName, stageValues); }
        private static ToolConfig findTool(FrameworkConfig config, String id) {
            ToolConfig direct = config.tool(id);
            if (direct != null) return direct;
            ToolConfig match = null;
            for (ToolConfig candidate : config.tools().values()) {
                if (!candidate.localKey().equals(id)) continue;
                if (match != null) return null;
                match = candidate;
            }
            return match;
        }
        @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
            return value instanceof Map ? objectMap((Map<?, ?>) value) : new LinkedHashMap<String, Object>();
        }
        private static Map<String, Object> configMap(Object value) {
            Map<String, Object> result = map(value);
            java.util.Iterator<String> keys = result.keySet().iterator();
            while (keys.hasNext()) if (SchemaSupport.isDisabledKey(keys.next())) keys.remove();
            return result;
        }
    }
}
