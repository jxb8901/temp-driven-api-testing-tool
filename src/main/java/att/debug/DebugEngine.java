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

    public DebugEngine(Path projectRoot, FrameworkConfig config) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
    }

    public Result run(ExecutionOptions options) throws Exception {
        Instant started = Instant.now();
        String targetType = options.debugTargetType();
        String targetId = options.debugTargetId();
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
        result.put("outputDirectory", debugDirectory.toString());
        result.put("log", logPath.toString());
        result.put("artifacts", artifacts.toString());

        DebugInput input = null;
        CaseRuntimeContext context = null;
        CaseExecutionLog log = null;
        DbHelperExecutor db = null;
        boolean caseStarted = false;
        boolean stageStarted = false;
        boolean stageFinished = false;
        ResultStatus status = ResultStatus.ERROR;
        int exitCode = 3;
        DiagnosticException diagnostic = null;
        List<ValidationResult> actionResults = new ArrayList<ValidationResult>();

        try {
            log = new CaseExecutionLog(logPath, config.caseLogYamlAnchors());
            input = loadInput(options, targetType, targetId);
            result.put("input", input.path.toString());
            ResolvedTarget resolved = resolveTarget(targetType, targetId, input);
            StageCaseData stage = input.stage(resolved.template.name());
            TestCase testCase = syntheticCase(targetType, targetId, input, stage);

            new PackageValidator(projectRoot, config).validateDebugTarget(resolved.template, testCase, stage,
                    resolved.flows, input.path, "debug", input.inputs);

            context = new CaseRuntimeContext(testCase, artifacts, debugDirectory.getFileName().toString(), debugDirectory, logPath, "debug");
            context.setProject(projectRoot);
            context.setSourceMetadata("debug", input.path, testCase.caseId());
            context.setTargetMetadata(targetType, targetId);
            context.setLegacyInputsView(input.inputs);
            context.put("CASE.environment", config.environment());
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
            MqHelperExecutor mq = new MqHelperExecutor(projectRoot, config);
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(toolInvoker, db, mq,
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
                    "Inspect case.log and artifacts, then rerun with --verbose.", e)
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
                try { Files.write(artifacts.resolve("case.yaml"), new Yaml().dump(context.caseTree()).getBytes(StandardCharsets.UTF_8)); }
                catch (Exception ignored) { /* result.yaml still records the primary outcome */ }
            }
            if (log != null) try { log.close(); } catch (Exception ignored) { }
            if (db != null) db.close();
        }

        long durationMs = Duration.between(started, Instant.now()).toMillis();
        result.put("status", status.name());
        result.put("exitCode", Integer.valueOf(exitCode));
        result.put("durationMs", Long.valueOf(durationMs));
        result.put("caseId", "DEBUG." + targetType + "." + safeRowId(targetId));
        result.put("actions", actionMaps(actionResults));
        if (context != null) result.put("case", context.caseTree());
        if (diagnostic != null) result.put("diagnostic", diagnostic.toDiagnostic().toMap());
        Files.write(resultPath, new Yaml().dump(result).getBytes(StandardCharsets.UTF_8));
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
        Map<String, Object> caseData = new LinkedHashMap<String, Object>(input.caseValues);
        for (Map.Entry<String, Object> entry : input.inputs.entrySet()) if (!caseData.containsKey(entry.getKey())) caseData.put(entry.getKey(), entry.getValue());
        if (!caseData.containsKey("caseName")) caseData.put("caseName", "DEBUG " + type + " " + id);
        Map<String, StageCaseData> stages = new LinkedHashMap<String, StageCaseData>();
        stages.put(stage.key(), stage);
        return new TestCase(1, "DEBUG", type, "debug", safeRowId(id), Collections.<String>emptyList(), caseData, stages, "");
    }

    private DebugInput loadInput(ExecutionOptions options, String type, String id) throws Exception {
        Path path = options.debugInput() == null ? autoInput(type, id) : resolveInput(options.debugInput());
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
            throw debugError("Debug input file does not exist: " + path, "Create the sidecar file or pass --input <path>.");
        try {
            Object loaded = YamlSupport.load(path);
            if (!(loaded instanceof Map)) throw debugError("Debug input must be a YAML map: " + path, "Use schemaVersion: " + Version.DEBUG_SCHEMA + ".");
            Map<String, Object> map = objectMap((Map<?, ?>) loaded);
            Path schema = projectRoot.resolve("schemas/att-debug-v1.0.schema.json");
            if (Files.isRegularFile(schema)) JsonSchemaVerifier.verify(schema, map);
            SchemaSupport.requireVersion(map, Version.DEBUG_SCHEMA, "debug input");
            return new DebugInput(path, map, type, id, config);
        } catch (DiagnosticException e) {
            throw e;
        } catch (Exception e) {
            throw new DiagnosticException(DiagnosticCodes.DEBUG_INVALID, "Invalid debug input", e.getMessage(), path.toString(),
                    "debug", null, null, null, id, null,
                    "Correct the debug YAML and validate it against schemas/att-debug-v1.0.schema.json.", e);
        }
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
        Files.createDirectories(debugRoot);
        String safe = (type + "-" + id).replaceAll("[^A-Za-z0-9_.-]", "_");
        safe = IdentifierValidator.runId(safe);
        Path result = debugRoot.resolve(safe).normalize();
        if (Files.exists(result)) result = debugRoot.resolve(safe + "-" + System.currentTimeMillis());
        Files.createDirectories(result);
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

    private void appendError(CaseExecutionLog log, DiagnosticException error) {
        if (log == null) return;
        try { log.append("DEBUG ERROR", error.toDiagnostic().toMap()); } catch (Exception ignored) { }
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
    }

    private static final class ResolvedTarget {
        private final StageTemplate template; private final FlowRegistry flows;
        private ResolvedTarget(StageTemplate template, FlowRegistry flows) { this.template = template; this.flows = flows; }
    }

    private static final class DebugInput {
        private final Path path; private final Map<String, Object> caseValues; private final Map<String, Object> inputs; private final Map<String, Object> arguments; private final Map<String, Object> stageValues; private final String stageKey;
        private DebugInput(Path path, Map<String, Object> root, String type, String id, FrameworkConfig config) {
            this.path = path;
            this.caseValues = map(root.get("case"));
            this.inputs = map(root.get("inputs"));
            this.stageValues = map(map(root.get("stage")).get("values"));
            Object key = map(root.get("stage")).get("key");
            this.stageKey = key == null ? "DEBUG" : String.valueOf(key);
            Map<String, Object> rootArguments = map(root.get("arguments"));
            Map<String, Object> selectedArguments = rootArguments;
            if ("tool".equals(type)) {
                Map<String, Object> tools = map(root.get("tools"));
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
    }
}
