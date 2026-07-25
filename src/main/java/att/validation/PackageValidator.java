/* Author: Jeffrey + ChatGPT */
package att.validation;

import att.config.FrameworkConfig;
import att.config.SuiteConfigResolver;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.core.ExecutionOptions;
import att.core.StageCaseData;
import att.core.TestCase;
import att.excel.ExcelTestSuiteLoader;
import att.snapshot.TestcaseSnapshotService;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.TemplateAction;
import att.template.ToolCallParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Collections;
import java.util.stream.Stream;

/** Performs V2 package/suite/template/tool validation without running tools. */
public final class PackageValidator {
    private final ToolCallParser callParser = new ToolCallParser();
    private final att.template.ExpressionEvaluator expressionEvaluator = new att.template.ExpressionEvaluator();
    private final att.template.UnifiedTemplateEngine expressionEngine = new att.template.UnifiedTemplateEngine(null);
    private final att.template.DefaultBuiltInProvider builtIns = new att.template.DefaultBuiltInProvider();
    private static final Set<String> BUILT_INS = new att.template.DefaultBuiltInProvider().names();
    private final Path projectRoot;
    private final FrameworkConfig global;
    private final boolean windows;
    private final Set<String> skippedWindowsShellExecutableChecks = new LinkedHashSet<String>();
    private att.flow.FlowRegistry flows;

    public PackageValidator(Path projectRoot, FrameworkConfig global) {
        this(projectRoot, global, java.io.File.separatorChar == '\\');
    }

    PackageValidator(Path projectRoot, FrameworkConfig global, boolean windows) {
        this.projectRoot = projectRoot; this.global = global; this.windows = windows;
    }

    public ValidationSummary validate(ExecutionOptions options) throws Exception {
        skippedWindowsShellExecutableChecks.clear();
        try {
            Path configured = options.configPath().isAbsolute() ? options.configPath() : projectRoot.resolve(options.configPath());
            if (!att.core.IdentifierValidator.canonicalPath(configured, "configuration").startsWith(att.core.IdentifierValidator.canonicalPath(projectRoot, "package root")) || Files.isSymbolicLink(configured)) throw new IllegalArgumentException("Configuration escapes package root or is a symbolic link: " + options.configPath());
        } catch (Exception e) { return invalid(options.validationScope(), Collections.singletonList(diagnostic(DiagnosticCodes.PATH_INVALID, e, null))); }
        List<Path> suites;
        try { suites = suites(options); }
        catch (Exception e) { return invalid(options.validationScope(), Collections.singletonList(diagnostic(DiagnosticCodes.TESTCASE_INVALID, e, null))); }
        int cases = 0;
        Set<String> templates = new LinkedHashSet<String>();
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        Map<String, Path> workbookIds = new LinkedHashMap<String, Path>();
        Map<String, CaseLocation> fullCaseIds = new LinkedHashMap<String, CaseLocation>();
        SuiteConfigResolver resolver = new SuiteConfigResolver(projectRoot, global);
        StageTemplateLoader loader;
        try { loader = new StageTemplateLoader(projectRoot, global.templatesRoot()); }
        catch (Exception e) { return invalid(options.validationScope(), Collections.singletonList(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, global.templatesRoot()))); }
        try { flows = new att.flow.FlowRegistry(projectRoot, global.templatesRoot(), "package".equals(options.validationScope())); }
        catch (Exception e) { return invalid(options.validationScope(), Collections.singletonList(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, global.templatesRoot().resolve("flows")))); }
        if ("package".equals(options.validationScope())) {
            validatePackageLayout(diagnostics);
            for (String reference : loader.paths()) try { templates.add(reference); StageTemplate template = loader.load(reference); validateTemplate(template, global); }
            catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, projectRoot.resolve(global.templatesRoot()).resolve(reference).resolve("template.yaml"))); }
            for (att.flow.FlowDefinition flow : flows.all()) try {
                StageTemplate body = new StageTemplate(flow.name(), flow.directory(), flow.actions(), att.Version.TEMPLATE_SCHEMA);
                validateTemplate(body, global); validateReferencedTools(body, global);
            } catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, flow.directory().resolve("flow.yaml"))); }
            validatePackageTools(diagnostics);
        }
        for (Path suite : suites) {
            try {
                Path resolved = suite.isAbsolute() ? suite : projectRoot.resolve(suite).normalize();
                Path canonicalProject = att.core.IdentifierValidator.canonicalPath(projectRoot, "package root");
                Path canonicalSuite = att.core.IdentifierValidator.canonicalPath(resolved, "workbook");
                if (!canonicalSuite.startsWith(canonicalProject) || Files.isSymbolicLink(resolved)) throw new IllegalArgumentException("Workbook escapes package root or is a symbolic link: " + suite);
                resolved = canonicalSuite;
                FrameworkConfig config = resolver.resolve(resolved);
                Path previousWorkbook = workbookIds.put(config.workbookId(), resolved);
                if (previousWorkbook != null) diagnostics.add(diagnostic(DiagnosticCodes.TESTCASE_INVALID,
                        new IllegalArgumentException("Duplicate workbook id '" + config.workbookId() + "'; first declared by " + previousWorkbook), resolved));
                List<TestCase> loaded = new ExcelTestSuiteLoader(config).load(resolved);
                try { new TestcaseSnapshotService().verify(resolved, config, loaded); }
                catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TESTCASE_INVALID, e, resolved)); }
                for (TestCase testCase : loaded) {
                    if ("selected".equals(options.validationScope()) && !options.matches(testCase)) continue;
                    CaseLocation previousCase = fullCaseIds.put(testCase.caseId(), new CaseLocation(resolved, testCase.sheetName(), testCase.rowNumber()));
                    if (previousCase != null) diagnostics.add(new Diagnostic(DiagnosticCodes.TESTCASE_INVALID, Diagnostic.Severity.ERROR,
                            "Duplicate full Case ID '" + testCase.caseId() + "'; first declared at " + previousCase,
                            resolved.toString(), testCase.sheetName(), testCase.rowNumber(), null, null, null));
                    cases++;
                    Set<String> assignedCaseVariables = new LinkedHashSet<String>();
                    for (StageCaseData stage : testCase.stages().values()) {
                        try {
                            StageTemplate template = loader.load(stage.templateName());
                            validateTemplateValues(template, testCase, stage, config, resolved, assignedCaseVariables);
                            if ("package".equals(options.validationScope())) continue;
                            if (!templates.add(template.name())) continue;
                            validateTemplate(template, config);
                            validateReferencedTools(template, config);
                        } catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, resolved)); }
                    }
                }
            } catch (Exception e) {
                diagnostics.add(diagnostic(DiagnosticCodes.TESTCASE_INVALID, e, suite));
            }
        }
        addWindowsShellExecutableWarning(diagnostics);
        if (cases == 0) diagnostics.add(new Diagnostic(DiagnosticCodes.SELECTION_EMPTY, Diagnostic.Severity.ERROR, "Case selection is empty", null, null, null, null, null, null));
        if ("selected".equals(options.validationScope())) diagnostics.add(new Diagnostic(DiagnosticCodes.SELECTED_SCOPE, Diagnostic.Severity.INFO, "Only the selected dependency closure was validated; unselected package content was not validated", null, null, null, null, null, null));
        Collections.sort(diagnostics);
        return new ValidationSummary(options.validationScope(), suites.size(), cases, templates.size(), global.tools().size(), diagnostics);
    }

    private static final class CaseLocation {
        private final Path file; private final String sheet; private final int row;
        private CaseLocation(Path file, String sheet, int row) { this.file = file; this.sheet = sheet; this.row = row; }
        @Override public String toString() { return file + "!" + sheet + ":" + row; }
    }

    private void validateReferencedTools(StageTemplate template, FrameworkConfig config) {
        att.template.UnifiedTemplateEngine syntaxEngine = new att.template.UnifiedTemplateEngine(null);
        for (TemplateAction action : template.actions()) {
            if ("tool".equalsIgnoreCase(action.type())) validateReferencedCall(callParser.parse(action.call()), config);
            for (String expression : actionExpressions(action)) for (ToolCallParser.ParsedCall call : syntaxEngine.parseCalls(expression)) validateReferencedCall(call, config);
            if ("render".equalsIgnoreCase(action.type())) try {
                for (Path payload : new att.template.RenderPayloadResolver().resolve(template.directory(), action.payload())) {
                    String content = att.template.PayloadCache.readUtf8(payload);
                    for (ToolCallParser.ParsedCall call : syntaxEngine.parseCalls(content)) validateReferencedCall(call, config);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Unable to inspect render payload calls for action " + action.id() + ": " + e.getMessage(), e);
            }
        }
    }

    private List<String> actionExpressions(TemplateAction action) {
        List<String> expressions = new ArrayList<String>();
        expressions.add(action.description()); expressions.add(action.assertion()); expressions.add(action.expected());
        expressions.add(action.actual()); expressions.add(action.message()); expressions.add(action.file()); expressions.add(action.expression());
        expressions.add(action.saveAs()); expressions.add(action.call());
        for (Map<String, Object> operation : java.util.Arrays.asList(action.query(), action.update())) {
            if (operation.get("sql") != null) expressions.add(String.valueOf(operation.get("sql")));
            Object params = operation.get("params");
            if (params instanceof Iterable) {
                for (Object value : (Iterable<?>) params) if (value instanceof String) expressions.add((String) value);
            } else if (params instanceof String) expressions.add((String) params);
        }
        for (Object value : action.fields().values()) expressions.add(String.valueOf(value));
        return expressions;
    }

    private void validateReferencedCall(ToolCallParser.ParsedCall call, FrameworkConfig config) {
        if (BUILT_INS.contains(call.name().toLowerCase(java.util.Locale.ROOT))) return;
        ToolConfig tool = config.tool(call.name());
        if (tool != null && tool.commandBacked()) validateToolExecutable(tool);
        else if (tool != null && tool.callBacked()) validateCallBackedDefinition(tool, config);
    }

    @SuppressWarnings("unchecked")
    private void validatePackageLayout(List<Diagnostic> diagnostics) {
        for (String required : new String[]{"config/config.yaml", "testcase", "templates", "tools", "schemas/catalog.yaml", "att.sh", "att.bat"}) {
            Path path = projectRoot.resolve(required);
            if (!Files.exists(path)) diagnostics.add(diagnostic(DiagnosticCodes.PACKAGE_INVALID, new IllegalArgumentException("Missing required package path: " + required), path));
        }
        Path catalog = projectRoot.resolve("schemas/catalog.yaml");
        if (Files.isRegularFile(catalog)) try {
            Object loaded = att.config.YamlSupport.parser().load(new String(Files.readAllBytes(catalog), java.nio.charset.StandardCharsets.UTF_8));
            if (!(loaded instanceof Map) || !"att-schema-catalog/v3.0".equals(String.valueOf(((Map<?, ?>) loaded).get("schemaVersion")))) throw new IllegalArgumentException("Invalid schema catalog version");
            Object schemas = ((Map<?, ?>) loaded).get("schemas"); if (!(schemas instanceof Map)) throw new IllegalArgumentException("Schema catalog requires schemas map");
            for (Object value : ((Map<?, ?>) schemas).values()) {
                Path schema = projectRoot.resolve("schemas").resolve(String.valueOf(value)).normalize();
                if (!schema.startsWith(projectRoot.resolve("schemas").normalize()) || !Files.isRegularFile(schema) || Files.isSymbolicLink(schema)) throw new IllegalArgumentException("Missing/unsafe schema file: " + value);
                if (schema.getFileName().toString().endsWith(".json")) att.validation.JsonSchemaVerifier.compile(schema);
                else if (schema.getFileName().toString().endsWith(".xsd")) { javax.xml.validation.SchemaFactory factory = javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI); factory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); factory.newSchema(schema.toFile()); }
                else throw new IllegalArgumentException("Unsupported catalog schema file: " + value);
            }
        } catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.PACKAGE_INVALID, e, catalog)); }
        try {
            Path output = projectRoot.resolve(global.outputDirectory()).normalize();
            Path ancestor = output; while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
            if (ancestor == null || !Files.isDirectory(ancestor) || !Files.isWritable(ancestor)) throw new IllegalArgumentException("Output directory has no writable ancestor: " + global.outputDirectory());
        } catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.PATH_INVALID, e, null)); }
    }

    private void validatePackageTools(List<Diagnostic> diagnostics) {
        boolean hasSshTool = false;
        for (ToolConfig tool : global.tools().values()) if (tool.ssh() != null) { hasSshTool = true; break; }
        if (hasSshTool && !att.exec.SshCommandRunner.localSshAvailable()) {
            diagnostics.add(new Diagnostic(DiagnosticCodes.TOOL_INVALID, Diagnostic.Severity.WARNING,
                    att.exec.SshCommandRunner.FALLBACK_WARNING, null, null, null, null, null, null, null, null));
        }
        for (ToolConfig tool : global.tools().values()) {
            try {
                if (tool.commandBacked()) validateToolExecutable(tool);
                else validateCallBackedDefinition(tool, global);
            }
            catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TOOL_INVALID, e, null)); }
        }
    }

    private void validateToolExecutable(ToolConfig tool) {
        try {
            if (tool.callBacked()) return;
            if (tool.ssh() != null) {
                String configured = tool.ssh().identityFile();
                if (!configured.isEmpty()) {
                    Path identity = java.nio.file.Paths.get(configured);
                    if (!identity.isAbsolute()) identity = projectRoot.resolve(identity).normalize();
                    Path canonicalIdentity = identity.toRealPath();
                    if (Files.isSymbolicLink(identity) || !Files.isRegularFile(canonicalIdentity) || !Files.isReadable(canonicalIdentity)) {
                        throw new IllegalArgumentException("Missing/unsafe SSH identity file for " + tool.key() + ": " + configured);
                    }
                }
                return;
            }
            java.util.List<String> command = tool.groupScriptArgv().isEmpty() ? tool.commandArgv() : tool.groupScriptArgv();
            if (command.isEmpty()) throw new IllegalArgumentException("Tool command is blank: " + tool.key());
            String first = command.get(0);
            boolean skipShellExecutableCheck = windows && first.toLowerCase(java.util.Locale.ROOT).endsWith(".sh");
            Path executable = java.nio.file.Paths.get(first);
            boolean packageRelative = first.startsWith("./") || first.startsWith("../");
            if (!executable.isAbsolute() && packageRelative) executable = projectRoot.resolve(executable).normalize();
            else if (!executable.isAbsolute()) executable = findOnPath(first, !skipShellExecutableCheck);
            Path canonicalProject = projectRoot.toRealPath();
            Path canonicalExecutable = executable.toRealPath();
            if (packageRelative && !canonicalExecutable.startsWith(canonicalProject)) throw new IllegalArgumentException("Package-local tool executable escapes package root: " + first);
            if (Files.isSymbolicLink(executable) || !Files.isRegularFile(canonicalExecutable)) throw new IllegalArgumentException("Missing/unsafe tool executable: " + first);
            if (skipShellExecutableCheck) {
                skippedWindowsShellExecutableChecks.add(tool.key());
                return;
            }
            if (!Files.isExecutable(canonicalExecutable)) throw new IllegalArgumentException("Tool is not executable: " + first);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("Missing/unsafe tool executable for " + tool.key() + ": " + e.getMessage(), e); }
    }
    private Path findOnPath(String executable, boolean requireExecutable) throws java.io.IOException {
        String path = System.getenv("PATH");
        if (path != null) for (String directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            for (String name : executableCandidates(executable, windows, System.getenv("PATHEXT"))) {
                Path candidate = java.nio.file.Paths.get(directory).resolve(name);
                if (Files.isRegularFile(candidate) && (!requireExecutable || Files.isExecutable(candidate))) return candidate;
            }
        }
        throw new java.io.IOException("Executable not found on PATH: " + executable);
    }

    private void addWindowsShellExecutableWarning(List<Diagnostic> diagnostics) {
        if (skippedWindowsShellExecutableChecks.isEmpty()) return;
        diagnostics.add(new Diagnostic(DiagnosticCodes.TOOL_INVALID, Diagnostic.Severity.WARNING,
                "Windows validation did not check whether POSIX .sh tools can be launched: "
                        + String.join(", ", skippedWindowsShellExecutableChecks)
                        + ". File existence and path safety were checked; provide Windows .bat/.cmd/.exe equivalents before run.",
                null, "tools", null, null, null, null, null,
                "Treat validation PASS as configuration validation only for these .sh tools; test the Windows-native executables before execution."));
    }

    static List<String> executableCandidates(String executable, boolean windows, String pathExt) {
        List<String> result = new ArrayList<String>();
        result.add(executable);
        String fileName = java.nio.file.Paths.get(executable).getFileName().toString();
        if (!windows || fileName.lastIndexOf('.') > 0) return result;
        String configured = pathExt == null || pathExt.trim().isEmpty() ? ".COM;.EXE;.BAT;.CMD" : pathExt;
        for (String item : configured.split(";")) {
            String extension = item.trim();
            if (extension.isEmpty()) continue;
            if (!extension.startsWith(".")) extension = "." + extension;
            String candidate = executable + extension;
            if (!result.contains(candidate)) result.add(candidate);
        }
        return result;
    }

    private ValidationSummary invalid(String mode, List<Diagnostic> diagnostics) { return new ValidationSummary(mode, 0, 0, 0, global.tools().size(), diagnostics); }
    private Diagnostic diagnostic(String code, Exception exception, Path file) {
        DiagnosticException typed = DiagnosticException.find(exception);
        if (typed != null) {
            String locatedFile = typed.file() == null ? portable(file) : portable(java.nio.file.Paths.get(typed.file()));
            String message = typed.detail() == null ? typed.summary() : typed.summary() + ": " + typed.detail();
            return new Diagnostic(typed.code(), Diagnostic.Severity.ERROR, message, locatedFile, typed.field(),
                    typed.sheet(), typed.row(), typed.column(), typed.template(), typed.action(), typed.suggestion());
        }
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        LocatedValidationException located = exception instanceof LocatedValidationException ? (LocatedValidationException) exception : null;
        return new Diagnostic(code, Diagnostic.Severity.ERROR, message, portable(file), located == null ? null : located.field, null, null, null, located == null ? null : located.template, located == null ? null : located.action, suggestion(code));
    }

    private String portable(Path file) {
        if (file == null) return null;
        Path absolute = file.isAbsolute() ? file.toAbsolutePath().normalize() : projectRoot.resolve(file).toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/') : absolute.toString();
    }

    private String suggestion(String code) {
        if (DiagnosticCodes.CONFIG_INVALID.equals(code)) return "Check the reported config field against the strict configuration schema.";
        if (DiagnosticCodes.TESTCASE_INVALID.equals(code)) return "Check the workbook, sidecar, Sheet, row, and required headers at the reported location.";
        if (DiagnosticCodes.TEMPLATE_INVALID.equals(code)) return "Check the template/action field and every referenced Context variable or inline call.";
        if (DiagnosticCodes.TOOL_INVALID.equals(code)) return "Check the qualified tool name, descriptor, declared arguments, command placeholders, and executable.";
        if (DiagnosticCodes.PATH_INVALID.equals(code)) return "Use a safe path below the ATT package root and avoid symbolic-link escapes.";
        return null;
    }

    private void validateTemplate(StageTemplate template, FrameworkConfig config) {
        Set<String> actionIds = new LinkedHashSet<String>();
        Set<String> completedActions = new LinkedHashSet<String>();
        Set<String> assignmentNames = new LinkedHashSet<String>();
        att.template.UnifiedTemplateEngine syntaxEngine = new att.template.UnifiedTemplateEngine(null);
        for (TemplateAction action : template.actions()) {
          try {
            if (action.id().contains(".")) throw new IllegalArgumentException("Action ID must not contain '.': " + action.id());
            if (!actionIds.add(action.id())) throw new IllegalArgumentException("Duplicate Action ID: " + action.id());
            String type = action.type().toLowerCase(java.util.Locale.ROOT);
            if (!("render".equals(type) || "tool".equals(type) || "db".equals(type) || "assert".equals(type) || "log".equals(type) || "assign".equals(type) || "flow".equals(type))) throw new IllegalArgumentException("Unsupported action type: " + action.type());
            validateInlineExpressions(action.description(), syntaxEngine, config);
            validateInlineExpressions(action.expected(), syntaxEngine, config);
            validateInlineExpressions(action.actual(), syntaxEngine, config);
            if (!action.assertion().trim().isEmpty()) validateAssertionExpression(action.assertion(), syntaxEngine, config);
            if (!action.runWhen().trim().isEmpty()) validateAssertionExpression(action.runWhen(), syntaxEngine, config);
            if ("render".equals(type)) {
                require(action.payload(), "payload is required for render action " + action.id());
                require(action.renderAs(), "renderAs is required for render action " + action.id());
                if (!java.util.Arrays.asList("file", "text", "json", "yaml", "xml").contains(action.renderAs().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Invalid renderAs: " + action.renderAs());
                forbid(action, "name", "saveAs", "overwrite", "output", "call", "db", "query", "update", "expression", "expected", "actual", "message", "file", "level", "fields", "retry", "timeoutMs");
                List<Path> payloads = new att.template.RenderPayloadResolver().resolve(template.directory(), action.payload());
                for (Path payload : payloads) {
                    try {
                        String content = att.template.PayloadCache.readUtf8(payload);
                        validateStaticContextStructure(content, syntaxEngine, completedActions, false);
                        for (ToolCallParser.ParsedCall call : syntaxEngine.parseCalls(content)) validateCall(call, config);
                        if (!"file".equalsIgnoreCase(action.renderAs()) && !content.contains("${") && !content.contains("#{")) new att.exec.ToolInvoker(projectRoot, config).parseOutput(content, action.renderAs());
                    } catch (DiagnosticException e) {
                        throw e.withLocation(payload.toString(), "actions." + action.id() + ".payload", null, null, null, template.name(), action.id());
                    }
                }
            }
            if ("tool".equals(type)) { require(action.call(), "call is required for tool action " + action.id()); forbid(action, "name", "payload", "renderAs", "db", "query", "update", "expression", "message", "file", "level", "fields"); if (action.timeoutMs() != null && (action.timeoutMs() < 1 || action.timeoutMs() > 3600000)) throw new IllegalArgumentException("timeoutMs must be 1..3600000: " + action.id()); validateRetry(action); validateInlineExpressions(action.saveAs(), syntaxEngine, config); validateToolCall(action.call(), config); validateToolSaveAs(action, config); }
            if ("db".equals(type)) validateDbAction(action, template, syntaxEngine, config, completedActions);
            if ("assert".equals(type)) { require(action.assertion(), "assert is required for assert action " + action.id()); forbid(action, "name", "payload", "renderAs", "saveAs", "overwrite", "expression", "call", "db", "query", "update", "message", "file", "level", "fields", "retry", "timeoutMs"); }
            if ("log".equals(type)) {
                if (action.message().trim().isEmpty() && action.file().trim().isEmpty()) throw new IllegalArgumentException("message or file is required for log action " + action.id());
                if (!("TRACE".equals(action.level()) || "DEBUG".equals(action.level()) || "INFO".equals(action.level()) || "WARN".equals(action.level()) || "ERROR".equals(action.level()))) throw new IllegalArgumentException("Invalid log level: " + action.level());
                forbid(action, "name", "payload", "renderAs", "saveAs", "overwrite", "call", "db", "query", "update", "expression", "expected", "actual", "retry", "timeoutMs");
                for (Object key : action.fields().keySet()) if (!(key instanceof String)) throw new IllegalArgumentException("Log fields keys must be strings: " + action.id());
                validateInlineExpressions(action.message(), syntaxEngine, config);
                validateInlineExpressions(action.file(), syntaxEngine, config);
                for (Object value : action.fields().values()) validateInlineExpressions(String.valueOf(value), syntaxEngine, config);
            }
            if ("assign".equals(type)) {
                require(action.name(), "name is required for assign action " + action.id());
                require(action.expression(), "expression is required for assign action " + action.id());
                if (!action.name().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Assign name must match [A-Za-z_][A-Za-z0-9_]*: " + action.name());
                if (!assignmentNames.add(action.name())) throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                        "Duplicate CASE.VARS assignment '${CASE.VARS." + action.name() + "}'",
                        "The same variable name is assigned more than once in template '" + template.name() + "'.",
                        null, "name", null, null, null, template.name(), action.id(),
                        "Use a unique Case-scoped variable name; assign does not overwrite.", null);
                forbid(action, "output", "payload", "renderAs", "saveAs", "overwrite", "call", "db", "query", "update", "expected", "actual", "message", "file", "level", "fields", "retry", "timeoutMs");
                validateInlineExpressions(action.expression(), syntaxEngine, config);
                validateStaticContextStructure(action.expression(), syntaxEngine, completedActions, false);
            }
            if ("flow".equals(type)) {
                if (!att.Version.TEMPLATE_SCHEMA.equals(template.schemaVersion())) throw new IllegalArgumentException("Flow actions require " + att.Version.TEMPLATE_SCHEMA + ": " + action.id());
                require(action.use(), "use is required for Flow action " + action.id());
                forbid(action, "name", "payload", "renderAs", "saveAs", "overwrite", "call", "db", "query", "update", "expression", "assert", "expected", "actual", "message", "file", "level", "fields", "retry", "timeoutMs");
                if (flows == null) throw new IllegalStateException("Flow registry is unavailable");
                flows.validateInvocation(action);
                validateBindingExpressions(action.with(), syntaxEngine, config);
            }

            Set<String> afterCurrentAction = new LinkedHashSet<String>(completedActions);
            afterCurrentAction.add(action.id());
            validateStaticContextStructure(action.description(), syntaxEngine, afterCurrentAction, true);
            validateStaticContextStructure(action.assertion(), syntaxEngine, afterCurrentAction, true);
            validateStaticContextStructure(action.actual(), syntaxEngine, afterCurrentAction, true);
            validateStaticContextStructure(action.expected(), syntaxEngine, completedActions, false);
            validateStaticContextStructure(action.runWhen(), syntaxEngine, completedActions, false);
            if ("tool".equals(type)) {
                validateStaticContextStructure(action.call(), syntaxEngine, completedActions, false);
                validateStaticContextStructure(action.saveAs(), syntaxEngine, completedActions, false);
            }
            if ("db".equals(type)) {
                validateStaticContextStructure(action.saveAs(), syntaxEngine, completedActions, false);
                Map<String, Object> operation = action.query().isEmpty() ? action.update() : action.query();
                if (operation.get("sql") != null) validateStaticContextStructure(String.valueOf(operation.get("sql")), syntaxEngine, completedActions, false);
                Object params = operation.get("params");
                if (params instanceof Iterable) {
                    for (Object value : (Iterable<?>) params) if (value instanceof String) {
                        validateStaticContextStructure((String) value, syntaxEngine, completedActions, false);
                    }
                } else if (params instanceof String) {
                    validateStaticContextStructure((String) params, syntaxEngine, completedActions, false);
                }
            }
            if ("log".equals(type)) {
                validateStaticContextStructure(action.message(), syntaxEngine, completedActions, false);
                validateStaticContextStructure(action.file(), syntaxEngine, completedActions, false);
                for (Object value : action.fields().values()) validateStaticContextStructure(String.valueOf(value), syntaxEngine, completedActions, false);
            }
            if ("flow".equals(type)) validateBindingContext(action.with(), syntaxEngine, completedActions);
          } catch (DiagnosticException e) { throw e.withLocation(null, "actions." + action.id(), null, null, null, template.name(), action.id()); }
          catch (LocatedValidationException e) { throw e; }
          catch (Exception e) { throw new LocatedValidationException(e.getMessage(), template.name(), action.id(), "actions." + action.id(), e); }
          completedActions.add(action.id());
        }
    }

    private void validateBindingExpressions(Object value, att.template.UnifiedTemplateEngine engine, FrameworkConfig config) {
        if (value instanceof Map) { for (Object nested : ((Map<?, ?>) value).values()) validateBindingExpressions(nested, engine, config); return; }
        if (value instanceof Iterable) { for (Object nested : (Iterable<?>) value) validateBindingExpressions(nested, engine, config); return; }
        if (value instanceof String) validateInlineExpressions((String) value, engine, config);
    }

    private void validateBindingContext(Object value, att.template.UnifiedTemplateEngine engine, Set<String> completedActions) {
        if (value instanceof Map) { for (Object nested : ((Map<?, ?>) value).values()) validateBindingContext(nested, engine, completedActions); return; }
        if (value instanceof Iterable) { for (Object nested : (Iterable<?>) value) validateBindingContext(nested, engine, completedActions); return; }
        if (value instanceof String) validateStaticContextStructure((String) value, engine, completedActions, false);
    }

    private void validateDbAction(TemplateAction action, StageTemplate template,
                                  att.template.UnifiedTemplateEngine engine, FrameworkConfig config,
                                  Set<String> completedActions) throws Exception {
        require(action.db(), "db is required for DB action " + action.id());
        att.config.DbHelperConfig helper = config.dbHelper(action.db());
        if (helper == null) throw new IllegalArgumentException("Unknown dbhelper instance '" + action.db() + "'");
        boolean query = !action.query().isEmpty();
        boolean update = !action.update().isEmpty();
        if (query == update) throw new IllegalArgumentException("DB action requires exactly one query or update block: " + action.id());
        if (update && helper.readOnly()) throw new IllegalArgumentException("Dbhelper '" + helper.id() + "' is readOnly and cannot execute update Actions");
        forbid(action, "name", "payload", "renderAs", "call", "expression", "expected", "actual",
                "message", "file", "level", "fields", "retry", "timeoutMs", "overwrite");
        Map<String, Object> operation = query ? action.query() : action.update();
        att.config.SchemaSupport.rejectUnknown(operation, "actions." + action.id() + "." + (query ? "query" : "update"),
                "sql", "sqlFile", "params");
        boolean hasSql = operation.get("sql") != null;
        boolean hasFile = operation.get("sqlFile") != null;
        if (hasSql == hasFile) throw new IllegalArgumentException("DB action SQL block requires exactly one sql or sqlFile: " + action.id());
        if (hasSql) validateDbSql(String.valueOf(operation.get("sql")), engine, config);
        else {
            att.exec.DbHelperExecutor executor = new att.exec.DbHelperExecutor(projectRoot, config);
            Path sqlFile = executor.resolveSqlFile(String.valueOf(operation.get("sqlFile")));
            String content = new String(Files.readAllBytes(sqlFile), java.nio.charset.StandardCharsets.UTF_8);
            validateDbSql(content, engine, config);
        }
        Object params = operation.get("params");
        if (params != null && !(params instanceof Iterable) && !(params instanceof String)) {
            throw new IllegalArgumentException("DB action params must be a list or exact List expression: " + action.id());
        }
        if (params instanceof Iterable) for (Object value : (Iterable<?>) params) {
            if (value instanceof String) validateInlineExpressions((String) value, engine, config);
        }
        else if (params instanceof String) validateInlineExpressions((String) params, engine, config);
        validateInlineExpressions(action.saveAs(), engine, config);
        if (action.saveConfig().configured()) {
            String format = action.saveConfig().format().trim().toLowerCase(java.util.Locale.ROOT);
            if (!("text".equals(format) || "json".equals(format) || "yaml".equals(format) || "xml".equals(format))) {
                throw new IllegalArgumentException("DB saveAs.format must be text, json, yaml, or xml: " + action.id());
            }
        }
    }

    private void validateToolSaveAs(TemplateAction action, FrameworkConfig config) {
        if (!action.saveConfig().configured()) return;
        ToolCallParser.ParsedCall parsed = callParser.parse(action.call());
        boolean builtIn = BUILT_INS.contains(parsed.name().toLowerCase(java.util.Locale.ROOT));
        ToolConfig configured = config.tool(parsed.name());
        boolean callBacked = configured != null && configured.callBacked();
        if (action.saveConfig().legacy()) {
            if (callBacked) throw new IllegalArgumentException("call-backed Tool saveAs must use {path, format, overwrite}: " + action.id());
            return;
        }
        String format = action.saveConfig().format().trim().toLowerCase(java.util.Locale.ROOT);
        if (format.isEmpty()) {
            if (callBacked) throw new IllegalArgumentException("call-backed Tool saveAs.format is required: " + action.id());
            return; // Runtime default is text for built-ins and raw for configured process Tools.
        }
        if (!("raw".equals(format) || "text".equals(format) || "json".equals(format)
                || "yaml".equals(format) || "xml".equals(format))) {
            throw new IllegalArgumentException("Tool saveAs.format must be raw, text, json, yaml, or xml: " + action.id());
        }
        if ((builtIn || callBacked) && "raw".equals(format)) {
            throw new IllegalArgumentException("Built-in and call-backed Tool saveAs.format do not support raw: " + action.id());
        }
    }

    private void validateDbSql(String sql, att.template.UnifiedTemplateEngine engine, FrameworkConfig config) {
        require(sql, "DB SQL must not be blank");
        engine.validateValueSyntax(sql);
        for (ToolCallParser.ParsedCall call : engine.parseCalls(sql)) {
            if (!BUILT_INS.contains(call.name().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("DB SQL rendering permits pure built-ins only; external call is invalid: " + call.name());
            }
            validateCall(call, config);
        }
    }

    /**
     * Validates Context scopes and same-template action timing without requiring a concrete Case.
     * This is the package-level contract for templates that are not referenced by any workbook.
     */
    private void validateStaticContextStructure(String text, att.template.UnifiedTemplateEngine engine,
                                                Set<String> availableActions, boolean currentOutputAvailable) {
        for (String path : engine.parseContextPaths(text)) {
            String root = firstPathSegment(path);
            if ("CASE".equals(root) || "TOOL".equals(root) || "DB".equals(root)) continue;
            if ("RUN".equals(root)) {
                String field = firstChildSegment(path, "RUN");
                if (field.isEmpty() || java.util.Arrays.asList("runId", "id", "runDirectory", "caseLog").contains(field)) continue;
                throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                        "Unknown RUN Context variable '${" + path + "}'",
                        "Available RUN fields: runId, id, runDirectory, caseLog", null, path,
                        null, null, null, null, null,
                        "Use the exact case-sensitive RUN field name.", null);
            }
            if ("ACTIONS".equals(root)) {
                String id = firstChildSegment(path, "ACTIONS");
                if (id.isEmpty()) continue;
                if (availableActions.contains(id)) {
                    String prefix = "ACTIONS." + id;
                    String remainder = path.length() <= prefix.length() ? "" : path.substring(prefix.length());
                    if (remainder.equals(".flow") || remainder.startsWith(".flow.") || remainder.startsWith(".flow[")
                            || remainder.equals(".actions") || remainder.startsWith(".actions.") || remainder.startsWith(".actions[")) {
                        throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                                "Flow internals are not a stable Context contract '${" + path + "}'",
                                "Callers may access only ACTIONS.<flowAction>.output.outputs.<name>.", null, path,
                                null, null, null, null, null,
                                "Declare and use a Flow output instead of an internal Action path.", null);
                    }
                    continue;
                }
                throw unavailableActionContext(path, id, availableActions);
            }
            if ("output".equals(root)) {
                if (currentOutputAvailable) continue;
                throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                        "Current action output is not available at '${" + path + "}'",
                        "This field is rendered before the current action outcome is created.", null, path,
                        null, null, null, null, null,
                        "Use CASE data or an earlier ACTIONS.<id> result in this field.", null);
            }

            String nearest = nearestAction(root, availableActions);
            String suffix = path.length() > root.length() ? path.substring(root.length()) : "";
            if (availableActions.contains(root)) continue; // Unique action-id suffix; its dynamic output shape is checked at runtime.
            if (nearest == null) continue; // Potential CASE/data-column suffix requires a concrete Case binding.
            throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                    "Unknown Context scope in '${" + path + "}'",
                    "Root '" + root + "' is not one of CASE, RUN, ACTIONS, TOOL, DB, or output.", null, path,
                    null, null, null, null, null,
                    nearest == null
                            ? "Use an uppercase Context scope and an exact case-sensitive field/action name."
                            : "Use '${ACTIONS." + nearest + suffix + "}' if that completed action is intended.", null);
        }
    }

    private DiagnosticException unavailableActionContext(String path, String id, Set<String> availableActions) {
        return new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                "Unknown or unavailable action Context '${" + path + "}'",
                "Action '" + id + "' has not completed at the point where this value is rendered. Available actions: " + String.join(", ", availableActions),
                null, path, null, null, null, null, null,
                "Reference an earlier action ID, or move this reference to a field rendered after that action completes.", null);
    }

    private String firstChildSegment(String path, String root) {
        if (path.equals(root)) return "";
        String remainder = path.substring(root.length());
        if (remainder.startsWith(".")) return firstPathSegment(remainder.substring(1));
        if (remainder.startsWith("[")) {
            int end = remainder.indexOf(']');
            if (end < 0) return "";
            String selector = remainder.substring(1, end).trim();
            if (selector.length() >= 2 && ((selector.startsWith("'") && selector.endsWith("'"))
                    || (selector.startsWith("\"") && selector.endsWith("\"")))) return selector.substring(1, selector.length() - 1);
            return selector;
        }
        return "";
    }

    private String nearestAction(String requested, Set<String> candidates) {
        String best = null; int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = editDistance(requested, candidate);
            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
        }
        return bestDistance <= Math.max(2, requested.length() / 3) ? best : null;
    }

    private void validateTemplateValues(StageTemplate template, TestCase testCase, StageCaseData stage, FrameworkConfig config) {
        validateTemplateValues(template, testCase, stage, config, null, new LinkedHashSet<String>());
    }

    private void validateTemplateValues(StageTemplate template, TestCase testCase, StageCaseData stage,
                                        FrameworkConfig config, Path caseFile) {
        validateTemplateValues(template, testCase, stage, config, caseFile, new LinkedHashSet<String>());
    }

    private void validateTemplateValues(StageTemplate template, TestCase testCase, StageCaseData stage,
                                        FrameworkConfig config, Path caseFile, Set<String> assignedCaseVariables) {
        att.core.CaseRuntimeContext context = new att.core.CaseRuntimeContext(testCase, projectRoot, "VALIDATE", projectRoot, projectRoot.resolve(".att-validation.log"));
        context.put("CASE.environment", config.environment());
        for (String name : assignedCaseVariables) context.put("CASE.VARS." + name, null);
        context.beginStage(stage, template.name(), template.directory());
        att.template.UnifiedTemplateEngine engine = new att.template.UnifiedTemplateEngine(new att.exec.ToolInvoker(projectRoot, config));
        Set<String> completedActions = new LinkedHashSet<String>();
        for (TemplateAction action : template.actions()) {
            Path sourceFile = template.directory().resolve("template.yaml");
            String sourceField = "actions." + action.id();
            try {
                Set<String> afterCurrentAction = new LinkedHashSet<String>(completedActions);
                afterCurrentAction.add(action.id());

                sourceField = "actions." + action.id() + ".description";
                validateContextStructure(action.description(), engine, context, testCase, afterCurrentAction);
                engine.renderValidationValues(action.description(), context);
                validateCallArgumentsIn(action.description(), context, engine);

                sourceField = "actions." + action.id() + ".runWhen";
                validateContextStructure(action.runWhen(), engine, context, testCase, completedActions);
                engine.renderValidationValues(action.runWhen(), context);
                validateCallArgumentsIn(action.runWhen(), context, engine);

                if ("flow".equalsIgnoreCase(action.type())) {
                    att.flow.FlowDefinition target = flows.get(action.use());
                    for (Map.Entry<String, Object> binding : action.with().entrySet()) {
                        sourceField = "actions." + action.id() + ".with." + binding.getKey();
                        validateBindingRuntimeValue(binding.getValue(), engine, context, testCase, completedActions);
                        Object known = knownBindingValue(binding.getValue(), engine, context);
                        if (known != UnknownValue.INSTANCE) {
                            att.flow.FlowDefinition.Input declared = target.inputs().get(binding.getKey());
                            att.flow.FlowRegistry.requireType("Flow input " + target.id() + "." + binding.getKey(), declared.type(), known, !declared.required());
                        }
                    }
                }

                if ("assign".equalsIgnoreCase(action.type())) {
                    sourceField = "actions." + action.id() + ".name";
                    context.requireCaseVariableAvailable(action.name());
                    sourceField = "actions." + action.id() + ".expression";
                    validateContextStructure(action.expression(), engine, context, testCase, completedActions);
                    engine.renderValidationValues(action.expression(), context);
                    for (ToolCallParser.ParsedCall call : engine.parseCalls(action.expression())) {
                        validateCallArguments(call, context, engine);
                    }
                    context.put("CASE.VARS." + action.name(), null);
                }

                sourceField = "actions." + action.id() + ".assert";
                validateContextStructure(action.assertion(), engine, context, testCase, afterCurrentAction);
                engine.renderValidationValues(action.assertion(), context);
                validateCallArgumentsIn(action.assertion(), context, engine);

                sourceField = "actions." + action.id() + ".actual";
                validateContextStructure(action.actual(), engine, context, testCase, afterCurrentAction);
                engine.renderValidationValues(action.actual(), context);
                validateCallArgumentsIn(action.actual(), context, engine);

                sourceField = "actions." + action.id() + ".expected";
                validateContextStructure(action.expected(), engine, context, testCase, completedActions);
                engine.renderValidationValues(action.expected(), context);
                validateCallArgumentsIn(action.expected(), context, engine);

                sourceField = "actions." + action.id() + ".message";
                validateContextStructure(action.message(), engine, context, testCase, completedActions);
                engine.renderValidationValues(action.message(), context);
                validateCallArgumentsIn(action.message(), context, engine);

                sourceField = "actions." + action.id() + ".file";
                validateContextStructure(action.file(), engine, context, testCase, completedActions);
                engine.renderValidationValues(action.file(), context);
                validateCallArgumentsIn(action.file(), context, engine);

                sourceField = "actions." + action.id() + ".saveAs";
                validateContextStructure(action.saveAs(), engine, context, testCase, completedActions);
                engine.renderValidationValues(action.saveAs(), context);
                validateCallArgumentsIn(action.saveAs(), context, engine);

                for (Object key : action.fields().keySet()) {
                    sourceField = "actions." + action.id() + ".fields." + key;
                    Object value = action.fields().get(key);
                    validateContextStructure(String.valueOf(value), engine, context, testCase, completedActions);
                    engine.renderValidationValues(String.valueOf(value), context);
                    validateCallArgumentsIn(String.valueOf(value), context, engine);
                }
                if ("tool".equalsIgnoreCase(action.type())) {
                    sourceField = "actions." + action.id() + ".call";
                    validateContextStructure(action.call(), engine, context, testCase, completedActions);
                    engine.renderValidationValues(action.call(), context);
                    validateCallArgumentsIn(action.call(), context, engine);
                }
                if ("db".equalsIgnoreCase(action.type())) {
                    Map<String, Object> operation = action.query().isEmpty() ? action.update() : action.query();
                    if (operation.get("sql") != null) {
                        sourceField = "actions." + action.id() + "." + (action.query().isEmpty() ? "update" : "query") + ".sql";
                        String sql = String.valueOf(operation.get("sql"));
                        validateContextStructure(sql, engine, context, testCase, completedActions);
                        engine.renderValidationValues(sql, context);
                    }
                    Object params = operation.get("params");
                    if (params instanceof Iterable) {
                        int index = 0;
                        for (Object value : (Iterable<?>) params) {
                            if (value instanceof String) {
                                sourceField = "actions." + action.id() + ".params[" + index + "]";
                                validateContextStructure((String) value, engine, context, testCase, completedActions);
                                engine.renderValidationValues((String) value, context);
                                validateCallArgumentsIn((String) value, context, engine);
                            }
                            index++;
                        }
                    } else if (params instanceof String) {
                        sourceField = "actions." + action.id() + ".params";
                        validateContextStructure((String) params, engine, context, testCase, completedActions);
                        engine.renderValidationValues((String) params, context);
                        validateCallArgumentsIn((String) params, context, engine);
                    }
                }
                if ("render".equalsIgnoreCase(action.type())) {
                    for (Path payload : new att.template.RenderPayloadResolver().resolve(template.directory(), action.payload())) {
                        sourceFile = payload;
                        sourceField = "actions." + action.id() + ".payload";
                        String content = att.template.PayloadCache.readUtf8(payload);
                        validateContextStructure(content, engine, context, testCase, completedActions);
                        String partial = engine.renderValidationValues(content, context);
                        validateCallArgumentsIn(content, context, engine);
                        if (!"file".equalsIgnoreCase(action.renderAs()) && !partial.contains("${") && !partial.contains("#{")) engine.parseRendered(partial, action.renderAs());
                    }
                }
            } catch (Exception e) {
                DiagnosticException typed = DiagnosticException.find(e);
                String caseSource = caseFile == null ? null : portable(caseFile) + "!" + testCase.sheetName() + ":" + testCase.rowNumber();
                if (typed != null) throw sourceDiagnostic(typed, sourceFile, sourceField, testCase, template, action, caseSource);
                throw new DiagnosticException(DiagnosticCodes.TEMPLATE_INVALID,
                        "Invalid runtime-rendered value in template action", appendCaseSource(e.getMessage(), caseSource), sourceFile.toString(),
                        sourceField, testCase.sheetName(), testCase.rowNumber(), null, template.name(), action.id(),
                        "Check every Context path, inline call, action field, and render payload used by this action.", e);
            } finally {
                completedActions.add(action.id());
            }
            if ("assign".equalsIgnoreCase(action.type())) assignedCaseVariables.add(action.name());
        }
    }

    private void validateBindingRuntimeValue(Object value, att.template.UnifiedTemplateEngine engine,
                                             att.core.CaseRuntimeContext context, TestCase testCase,
                                             Set<String> completedActions) {
        if (value instanceof Map) { for (Object nested : ((Map<?, ?>) value).values()) validateBindingRuntimeValue(nested, engine, context, testCase, completedActions); return; }
        if (value instanceof Iterable) { for (Object nested : (Iterable<?>) value) validateBindingRuntimeValue(nested, engine, context, testCase, completedActions); return; }
        if (!(value instanceof String)) return;
        validateContextStructure((String) value, engine, context, testCase, completedActions);
        engine.renderValidationValues((String) value, context);
        validateCallArgumentsIn((String) value, context, engine);
    }

    private Object knownBindingValue(Object value, att.template.UnifiedTemplateEngine engine,
                                     att.core.CaseRuntimeContext context) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object nested = knownBindingValue(entry.getValue(), engine, context);
                if (nested == UnknownValue.INSTANCE) return nested;
                result.put(String.valueOf(entry.getKey()), nested);
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object nestedValue : (Iterable<?>) value) {
                Object nested = knownBindingValue(nestedValue, engine, context);
                if (nested == UnknownValue.INSTANCE) return nested;
                result.add(nested);
            }
            return result;
        }
        if (!(value instanceof String)) return value;
        String text = String.valueOf(value).trim();
        java.util.List<String> paths = engine.parseContextPaths(text);
        if (paths.size() == 1 && text.equals("${" + paths.get(0) + "}")) {
            String path = paths.get(0);
            // Validation tracks only that an ordered assign has made this
            // Case variable available. Its value and type remain runtime
            // data, so a null placeholder must not be mistaken for a known
            // null Flow input.
            if (path.startsWith("ACTIONS.") || path.startsWith("CASE.VARS.")) return UnknownValue.INSTANCE;
            return context.require(path);
        }
        String rendered = engine.renderValidationValues(String.valueOf(value), context);
        return rendered.contains("${") ? UnknownValue.INSTANCE : rendered;
    }

    private enum UnknownValue { INSTANCE }

    private DiagnosticException sourceDiagnostic(DiagnosticException error, Path sourceFile, String sourceField,
                                                 TestCase testCase, StageTemplate template, TemplateAction action,
                                                 String caseSource) {
        return new DiagnosticException(error.code(), error.summary(), appendCaseSource(error.detail(), caseSource),
                sourceFile.toString(), sourceField, testCase.sheetName(), testCase.rowNumber(), null,
                template.name(), action.id(), error.suggestion(), error);
    }

    private String appendCaseSource(String detail, String caseSource) {
        if (caseSource == null || caseSource.isEmpty()) return detail;
        String suffix = "Case source: " + caseSource;
        return detail == null || detail.trim().isEmpty() ? suffix : detail + "; " + suffix;
    }

    private void validateContextStructure(String text, att.template.UnifiedTemplateEngine engine,
                                          att.core.CaseRuntimeContext context, TestCase testCase,
                                          Set<String> availableActions) {
        for (String path : engine.parseContextPaths(text)) {
            if (path.startsWith("ACTIONS.")) {
                String id = firstPathSegment(path.substring("ACTIONS.".length()));
                if (!availableActions.contains(id)) throw unavailableActionContext(path, id, availableActions);
            }
            if (path.startsWith("CASE.STAGES.")) {
                String key = firstPathSegment(path.substring("CASE.STAGES.".length()));
                if (!testCase.stages().containsKey(key)) throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                        "Unknown Case stage Context '${" + path + "}'",
                        "Stage '" + key + "' is not declared by this Case. Available stage keys: " + String.join(", ", testCase.stages().keySet()),
                        null, path, null, null, null, null, null,
                        "Use a stage key declared by this Case sidecar selector/data mapping.", null);
            }
            String root = firstPathSegment(path);
            if (!("CASE".equals(root) || "RUN".equals(root) || "ACTIONS".equals(root)
                    || "TOOL".equals(root) || "DB".equals(root) || "output".equals(root))) {
                try { context.require(path); }
                catch (DiagnosticException e) {
                    // Dynamic action/tool result shapes are unavailable during validation. A shorthand
                    // that cannot yet be proven is preserved, then resolved strictly at runtime.
                    if (!availableActions.isEmpty() && DiagnosticCodes.CONTEXT_INVALID.equals(e.code())) continue;
                    throw e;
                }
            }
        }
    }

    private String firstPathSegment(String path) {
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        int end = dot < 0 ? path.length() : dot;
        if (bracket >= 0 && bracket < end) end = bracket;
        return path.substring(0, end);
    }

    private void validateCallArguments(ToolCallParser.ParsedCall call, att.core.CaseRuntimeContext context,
                                       att.template.UnifiedTemplateEngine engine) {
        for (ToolCallParser.Argument argument : call.arguments()) {
            validateCallArgumentValue(argument.expression(), context, engine);
        }
    }

    private void validateCallArgumentValue(String value, att.core.CaseRuntimeContext context,
                                           att.template.UnifiedTemplateEngine engine) {
        String expression = value.trim();
        if (expression.startsWith("[") && expression.endsWith("]")) {
            for (String item : new ToolCallParser().listItems(expression)) validateCallArgumentValue(item, context, engine);
        } else {
            rejectBareCallReference(expression, engine);
            engine.renderValidationValues(value, context);
        }
    }

    private void validateCallArgumentsIn(String text, att.core.CaseRuntimeContext context,
                                         att.template.UnifiedTemplateEngine engine) {
        for (ToolCallParser.ParsedCall call : engine.parseCalls(text)) validateCallArguments(call, context, engine);
    }

    private static void require(String value, String message) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message); }
    private static void forbid(TemplateAction action, String... fields) {
        for (String field : fields) if (action.raw().containsKey(field)) throw new IllegalArgumentException("Field '" + field + "' is forbidden for " + action.type() + " action " + action.id());
    }
    private static void validateRetry(TemplateAction action) {
        Map<String, Object> retry = action.retry(); if (retry.isEmpty()) return;
        att.config.SchemaSupport.rejectUnknown(retry, "actions." + action.id() + ".retry", "maxAttempts", "intervalMs", "retryOn");
        if (!retry.containsKey("maxAttempts") || !retry.containsKey("intervalMs") || !retry.containsKey("retryOn")) throw new IllegalArgumentException("retry requires maxAttempts, intervalMs, and retryOn: " + action.id());
        int attempts = integer(retry.get("maxAttempts"), 0); if (attempts < 2 || attempts > 10) throw new IllegalArgumentException("retry.maxAttempts must be 2..10: " + action.id());
        int interval = integer(retry.get("intervalMs"), -1); if (interval < 0 || interval > 3600000) throw new IllegalArgumentException("retry.intervalMs must be 0..3600000: " + action.id());
        Object retryOn = retry.get("retryOn"); if (!(retryOn instanceof Iterable)) throw new IllegalArgumentException("retry.retryOn must be a list: " + action.id());
        java.util.Set<String> categories = new java.util.LinkedHashSet<String>();
        for (Object category : (Iterable<?>) retryOn) {
            if (!(category instanceof String)) throw new IllegalArgumentException("retry.retryOn values must be strings");
            String value = String.valueOf(category);
            if (!("ASSERTION".equals(value) || "TIMEOUT".equals(value))) throw new IllegalArgumentException("Unknown retry category '" + value + "'; use ASSERTION or TIMEOUT: " + action.id());
            if (!categories.add(value)) throw new IllegalArgumentException("Duplicate retry category: " + value);
        }
        if (categories.isEmpty()) throw new IllegalArgumentException("retry.retryOn must not be empty: " + action.id());
        if (categories.contains("ASSERTION") && action.assertion().trim().isEmpty()) throw new IllegalArgumentException("retryOn ASSERTION requires action assert: " + action.id());
    }
    private static int integer(Object value, int fallback) { if (value == null) return fallback; if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer retry value"); return ((Number) value).intValue(); }

    private void validateInlineExpressions(String text, att.template.UnifiedTemplateEngine engine, FrameworkConfig config) {
        engine.validateValueSyntax(text);
        for (ToolCallParser.ParsedCall call : engine.parseCalls(text)) validateCall(call, config);
    }

    private void validateAssertionExpression(String text, att.template.UnifiedTemplateEngine engine, FrameworkConfig config) {
        validateInlineExpressions(text, engine, config);
        expressionEvaluator.validateSyntax(engine.maskCalls(text));
    }

    private static final class LocatedValidationException extends IllegalArgumentException {
        private final String template, action, field;
        private LocatedValidationException(String message, String template, String action, String field, Throwable cause) { super(message, cause); this.template = template; this.action = action; this.field = field; }
    }

    private void validateToolCall(String call, FrameworkConfig config) {
        java.util.List<ToolCallParser.ParsedCall> calls = expressionEngine.parseCalls(call);
        if (calls.isEmpty()) throw new IllegalArgumentException("Tool call must be one exact #{...} expression");
        ToolCallParser.ParsedCall parsed = callParser.parse(call);
        if (parsed.name().startsWith("db.")) {
            throw new IllegalArgumentException("A DB query cannot be the primary call of type: tool; use type: db or an ordinary expression");
        }
        validateCall(parsed, config, true);
        for (int index = 1; index < calls.size(); index++) validateCall(calls.get(index), config, false);
    }

    private void validateCall(ToolCallParser.ParsedCall parsed, FrameworkConfig config) {
        validateCall(parsed, config, false);
    }

    private void validateCall(ToolCallParser.ParsedCall parsed, FrameworkConfig config, boolean allowWriteFacade) {
        for (ToolCallParser.Argument argument : parsed.arguments()) {
            rejectBareCallReference(argument.expression(), expressionEngine);
        }
        String toolName = parsed.name();
        if (toolName.startsWith("db.")) {
            validateDbExpressionCall(parsed, config);
            return;
        }
        if (BUILT_INS.contains(toolName.toLowerCase(java.util.Locale.ROOT))) {
            Map<String,Object> shape = new LinkedHashMap<String,Object>();
            boolean staticArguments = true;
            for (ToolCallParser.Argument argument : parsed.arguments()) {
                boolean dynamic = argument.expression().contains("${") || argument.expression().contains("#{");
                Object value = dynamic ? "<validation-value>" : callParser.literal(argument.expression());
                staticArguments &= !dynamic;
                if (shape.put(argument.key(), value) != null) throw builtInCallError(toolName,
                        "Duplicate built-in argument '" + argument.key() + "'", "Supply each argument once.", null);
            }
            try {
                builtIns.validateInvocation(toolName, shape);
                if (staticArguments && ("sysdate".equalsIgnoreCase(toolName) || "systimestamp".equalsIgnoreCase(toolName))) builtIns.invoke(toolName, shape);
            } catch (Exception e) {
                DiagnosticException typed = DiagnosticException.find(e);
                if (typed != null) throw typed;
                throw builtInCallError(toolName, e.getMessage(), "Use the documented positional list or exact named arguments.", e);
            }
            return;
        }
        ToolConfig tool = config.tool(toolName);
        if (tool == null) {
            String suggestion = nearestTool(toolName, config.tools().keySet());
            throw new DiagnosticException(DiagnosticCodes.TOOL_INVALID, "Unknown configured tool '" + toolName + "'",
                    "The template call does not match any global or qualified group.tool name. Available tools: " + String.join(", ", config.tools().keySet()),
                    null, "call", null, null, null, null, null,
                    suggestion == null ? "Define the tool in config/tools or correct the qualified group.tool name." : "Use '#{" + suggestion + "(...)}' if that is the intended tool.", null);
        }
        if (tool.callBacked() && isWriteFacade(tool) && !allowWriteFacade) {
            throw new IllegalArgumentException("DB update Tool may only be the primary call of a type: tool Action: " + tool.key());
        }
        Set<String> supplied = new LinkedHashSet<String>();
        for (ToolCallParser.Argument argument : parsed.arguments()) {
            if (argument.positional() && !(tool.arguments().size() == 1 && parsed.arguments().size() == 1)) {
                throw toolCallError(tool, "Positional arguments are not allowed for this tool",
                        "The tool declares " + tool.arguments().size() + " arguments; positional shorthand requires exactly one declared and supplied argument.",
                        "Use one of the declared names: " + tool.arguments().keySet());
            }
            String key = argument.positional() ? tool.arguments().keySet().iterator().next() : argument.key();
            if (!supplied.add(key)) throw toolCallError(tool, "Duplicate tool argument '" + key + "'", "The call supplies the same argument more than once.", "Supply each argument once.");
            if (!tool.arguments().containsKey(key)) throw toolCallError(tool, "Unknown argument '" + key + "'",
                    "Declared arguments: " + tool.arguments().keySet(), "Use an exact case-sensitive declared argument name.");
        }
        for (ToolArgumentConfig argument : tool.arguments().values()) if (argument.required() && !supplied.contains(argument.key())) throw toolCallError(tool,
                "Missing required argument '" + argument.key() + "'", "required=true; supplied arguments=" + supplied,
                "Add " + argument.key() + "=<value> to the call.");
    }

    private boolean isWriteFacade(ToolConfig tool) {
        return tool != null && tool.callBacked()
                && callParser.parse(tool.call()).name().matches("db\\.[^.]+\\.update");
    }

    private void validateCallBackedDefinition(ToolConfig tool, FrameworkConfig config) {
        ToolCallParser.ParsedCall target = callParser.parse(tool.call());
        if (!target.name().startsWith("db.")) return;
        String[] parts = target.name().split("\\.", -1);
        if (parts.length != 3 || config.dbHelper(parts[1]) == null
                || !("query".equals(parts[2]) || "scalar".equals(parts[2]) || "update".equals(parts[2]))) {
            throw new IllegalArgumentException("Invalid DB target for call-backed Tool " + tool.key() + ": " + target.name());
        }
        if ("update".equals(parts[2]) && config.dbHelper(parts[1]).readOnly()) {
            throw new IllegalArgumentException("Dbhelper '" + parts[1] + "' is readOnly and cannot back update Tool " + tool.key());
        }
        Set<String> supplied = new LinkedHashSet<String>();
        Map<String, ToolCallParser.Argument> byName = new LinkedHashMap<String, ToolCallParser.Argument>();
        for (ToolCallParser.Argument argument : target.arguments()) {
            if (argument.positional()) throw new IllegalArgumentException(target.name() + " requires named arguments");
            if (!("sql".equals(argument.key()) || "sqlFile".equals(argument.key()) || "params".equals(argument.key()))) {
                throw new IllegalArgumentException("Unknown DB call argument in Tool " + tool.key() + ": " + argument.key());
            }
            if (!supplied.add(argument.key())) throw new IllegalArgumentException("Duplicate DB call argument in Tool " + tool.key() + ": " + argument.key());
            byName.put(argument.key(), argument);
        }
        if (supplied.contains("sql") == supplied.contains("sqlFile")) {
            throw new IllegalArgumentException(target.name() + " requires exactly one of sql or sqlFile");
        }
        if (byName.containsKey("sqlFile")) {
            String expression = byName.get("sqlFile").expression().trim();
            boolean dynamic = expression.contains("${") || expression.contains("#{")
                    || expression.startsWith("input.") || expression.startsWith("TOOL.input.");
            if (dynamic) throw new IllegalArgumentException(target.name() + ".sqlFile must be a static package-relative path");
            try {
                String configured = String.valueOf(callParser.literal(expression));
                Path file = new att.exec.DbHelperExecutor(projectRoot, config).resolveSqlFile(configured);
                validateDbSql(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8), expressionEngine, config);
            } catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalArgumentException(error.getMessage(), error); }
        }
    }

    private void validateDbExpressionCall(ToolCallParser.ParsedCall parsed, FrameworkConfig config) {
        String[] parts = parsed.name().split("\\.", -1);
        if (parts.length != 3 || !"db".equals(parts[0]) || parts[1].isEmpty()
                || !("query".equals(parts[2]) || "scalar".equals(parts[2]))) {
            throw new IllegalArgumentException("DB expression must be db.<instance>.query(...) or db.<instance>.scalar(...): " + parsed.name());
        }
        if (config.dbHelper(parts[1]) == null) throw new IllegalArgumentException("Unknown dbhelper instance '" + parts[1] + "'");
        Set<String> supplied = new LinkedHashSet<String>();
        Map<String, ToolCallParser.Argument> arguments = new LinkedHashMap<String, ToolCallParser.Argument>();
        for (ToolCallParser.Argument argument : parsed.arguments()) {
            if (argument.positional()) throw new IllegalArgumentException(parsed.name() + " requires named arguments");
            if (!("sql".equals(argument.key()) || "sqlFile".equals(argument.key()) || "params".equals(argument.key()))) {
                throw new IllegalArgumentException("Unknown DB expression argument: " + argument.key());
            }
            if (!supplied.add(argument.key())) throw new IllegalArgumentException("Duplicate DB expression argument: " + argument.key());
            arguments.put(argument.key(), argument);
        }
        if (supplied.contains("sql") == supplied.contains("sqlFile")) {
            throw new IllegalArgumentException(parsed.name() + " requires exactly one of sql or sqlFile");
        }
        if (arguments.containsKey("sqlFile")) {
            String expression = arguments.get("sqlFile").expression().trim();
            if (expression.contains("${") || expression.contains("#{")) {
                throw new IllegalArgumentException(parsed.name() + ".sqlFile must be a static package-relative path");
            }
            try {
                String configured = String.valueOf(callParser.literal(expression));
                Path file = new att.exec.DbHelperExecutor(projectRoot, config).resolveSqlFile(configured);
                validateDbSql(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8), expressionEngine, config);
            } catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalArgumentException(error.getMessage(), error); }
        } else {
            String expression = arguments.get("sql").expression().trim();
            if (!expression.contains("${")) {
                validateDbSql(String.valueOf(callParser.literal(expression)), expressionEngine, config);
            }
        }
        if (arguments.containsKey("params")) {
            String expression = arguments.get("params").expression().trim();
            if (expression.startsWith("[") && expression.endsWith("]")) callParser.listItems(expression);
            else if (!expression.startsWith("${")) {
                throw new IllegalArgumentException(parsed.name() + ".params must be an inline list or exact Context List expression");
            }
        }
    }

    private void rejectBareCallReference(String value, att.template.UnifiedTemplateEngine engine) {
        String expression = value == null ? "" : value.trim();
        if (expression.startsWith("[") && expression.endsWith("]")) {
            for (String item : callParser.listItems(expression)) rejectBareCallReference(item, engine);
            return;
        }
        if (engine.isExplicitContextPath(expression)) {
            throw new IllegalArgumentException("Context references in calls must use ${...}: ${" + expression + "}");
        }
    }

    private DiagnosticException builtInCallError(String function, String detail, String suggestion, Throwable cause) {
        return new DiagnosticException(DiagnosticCodes.BUILTIN_INVALID, "Invalid built-in call '#{" + function + "(...)}'",
                detail, null, "call", null, null, null, null, null, suggestion, cause);
    }

    private DiagnosticException toolCallError(ToolConfig tool, String summary, String detail, String suggestion) {
        return new DiagnosticException(DiagnosticCodes.TOOL_INVALID, summary + " for tool '" + tool.key() + "'", detail,
                tool.sourceFile() == null ? null : tool.sourceFile().toString(), "tools." + tool.key() + ".arguments",
                null, null, null, null, null, suggestion, null);
    }

    private String nearestTool(String requested, Set<String> candidates) {
        String best = null; int bestDistance = Integer.MAX_VALUE;
        String requestedLocal = localToolName(requested);
        for (String candidate : candidates) {
            int distance = Math.min(editDistance(requested, candidate), editDistance(requestedLocal, localToolName(candidate)));
            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
        }
        return bestDistance <= Math.max(2, requestedLocal.length() / 3) ? best : null;
    }

    private String localToolName(String value) {
        int dot = value == null ? -1 : value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1]; for (int i = 0; i <= right.length(); i++) previous[i] = i;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return previous[right.length()];
    }

    private List<Path> suites(ExecutionOptions options) throws Exception {
        List<Path> result = new ArrayList<Path>();
        if (options.suiteDirectory() != null || options.suitePaths().isEmpty()) {
            Path configured = options.suiteDirectory() == null ? global.testcasesRoot() : options.suiteDirectory();
            Path directory = configured.isAbsolute() ? configured : projectRoot.resolve(configured);
            try (Stream<Path> stream = Files.walk(directory)) {
                stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")).sorted().forEach(result::add);
            }
        } else result.addAll(options.suitePaths());
        if (result.isEmpty()) throw new IllegalArgumentException("No Excel suites selected");
        return result;
    }

    public static final class ValidationSummary {
        public final int suites, cases, templates, tools;
        public final String mode;
        public final List<Diagnostic> diagnostics;
        public ValidationSummary(String mode, int suites, int cases, int templates, int tools, List<Diagnostic> diagnostics) { this.mode = mode; this.suites = suites; this.cases = cases; this.templates = templates; this.tools = tools; this.diagnostics = Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics)); }
        public boolean valid() { for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == Diagnostic.Severity.ERROR) return false; return true; }
        public long errors() { return count(Diagnostic.Severity.ERROR); }
        public long warnings() { return count(Diagnostic.Severity.WARNING); }
        private long count(Diagnostic.Severity severity) { long count = 0; for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == severity) count++; return count; }
        public String toJson() {
            Map<String,Object> root = new java.util.LinkedHashMap<String,Object>(); root.put("schemaVersion", "att-validation/v2.1"); root.put("attVersion", att.Version.PRODUCT); root.put("valid", valid()); root.put("mode", mode);
            Map<String,Object> summary = new java.util.LinkedHashMap<String,Object>(); summary.put("errors", errors()); summary.put("warnings", warnings()); summary.put("suites", suites); summary.put("cases", cases); summary.put("templates", templates); summary.put("tools", tools); root.put("summary", summary);
            List<Map<String,Object>> items = new java.util.ArrayList<Map<String,Object>>(); for (Diagnostic diagnostic : diagnostics) items.add(diagnostic.toMap()); root.put("diagnostics", items);
            return JsonSupport.write(root);
        }
        @Override public String toString() { return suites + " suites, " + cases + " cases, " + templates + " templates, " + tools + " tools"; }
    }
}
