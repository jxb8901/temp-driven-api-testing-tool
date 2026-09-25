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
import att.template.EvidenceCollector;

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

    /** Validates only the selected debug target and its Flow dependency closure. */
    public void validateDebugTarget(StageTemplate template, TestCase testCase, StageCaseData stage,
                                    att.flow.FlowRegistry selectedFlows, Path debugInput) throws Exception {
        validateDebugTarget(template, testCase, stage, selectedFlows, debugInput, "debug", null);
    }

    /** Validates a selected target against the Context tree for its execution mode. */
    public void validateDebugTarget(StageTemplate template, TestCase testCase, StageCaseData stage,
                                    att.flow.FlowRegistry selectedFlows, Path debugInput,
                                    String executionMode, Map<String, Object> legacyInputs) throws Exception {
        this.flows = selectedFlows;
        validateTemplate(template, global);
        validateReferencedToolsClosure(template, global, new LinkedHashSet<String>());
        validateTemplateValues(template, testCase, stage, global, debugInput, new LinkedHashSet<String>(), executionMode, legacyInputs);
    }

    private void validateReferencedToolsClosure(StageTemplate template, FrameworkConfig config, Set<String> visitedFlows) {
        validateReferencedTools(template, config);
        for (TemplateAction action : template.actions()) {
            if (!"flow".equalsIgnoreCase(action.type())) continue;
            if (!visitedFlows.add(action.use())) continue;
            att.flow.FlowDefinition flow = flows.get(action.use());
            if (flow == null) throw new IllegalArgumentException("Unresolved Flow reference '" + action.use() + "'");
            StageTemplate body = new StageTemplate(flow.name(), flow.directory(), flow.actions(), att.Version.TEMPLATE_SCHEMA,
                    flow.directory().resolve("flow.yaml"));
            validateReferencedToolsClosure(body, config, visitedFlows);
        }
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
        // Package validation owns the complete Tool catalog.  Selected
        // validation adds warnings only while walking its dependency closure.
        // This keeps an unrelated Tool from affecting a selected run.
        if ("package".equals(options.validationScope())) addToolInputShorthandWarnings(diagnostics, global.tools());
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
            for (String reference : loader.paths()) try {
                templates.add(reference);
                StageTemplate template = loader.load(reference);
                addContextMigrationWarnings(diagnostics, template);
                validateTemplate(template, global);
            }
            catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, projectRoot.resolve(global.templatesRoot()).resolve(reference).resolve("template.yaml"))); }
            for (att.flow.FlowDefinition flow : flows.all()) try {
                StageTemplate body = new StageTemplate(flow.name(), flow.directory(), flow.actions(), att.Version.TEMPLATE_SCHEMA, flow.directory().resolve("flow.yaml"));
                addContextMigrationWarnings(diagnostics, body);
                validateReferencedTools(body, global);
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
                            addCaseContextMigrationWarnings(diagnostics, template, testCase, stage, config,
                                    resolved, assignedCaseVariables);
                            validateTemplateValues(template, testCase, stage, config, resolved, assignedCaseVariables, "testcase", null);
                            if ("package".equals(options.validationScope())) continue;
                            if (!templates.add(template.name())) continue;
                            validateTemplate(template, config);
                            validateReferencedTools(template, config, diagnostics);
                        } catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, resolved)); }
                    }
                }
            } catch (Exception e) {
                diagnostics.add(diagnostic(DiagnosticCodes.TESTCASE_INVALID, e, suite));
            }
        }
        addWindowsShellExecutableWarning(diagnostics);
        deduplicateMigrationDiagnostics(diagnostics);
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
        validateReferencedTools(template, config, null);
    }

    private void validateReferencedTools(StageTemplate template, FrameworkConfig config,
                                         List<Diagnostic> diagnostics) {
        validateReferencedTools(template, config, diagnostics, new LinkedHashSet<String>());
    }

    private void validateReferencedTools(StageTemplate template, FrameworkConfig config,
                                         List<Diagnostic> diagnostics, Set<String> visitedFlows) {
        att.template.UnifiedTemplateEngine syntaxEngine = new att.template.UnifiedTemplateEngine(null);
        for (TemplateAction action : template.actions()) {
            String prefix = "actions." + action.id();
            if (diagnostics != null) {
                for (String expression : actionExpressions(action))
                    warnReferencedToolDefinitions(diagnostics, expression, syntaxEngine, config);
            }
            if ("tool".equalsIgnoreCase(action.type())) {
                try {
                    validateToolCall(action.call(), config);
                    // validateToolCall checks the invocation shape and supplied arguments;
                    // every referenced definition still needs the same package-level
                    // checks as a tool used from an expression (including nested calls).
                    for (ToolCallParser.ParsedCall call : syntaxEngine.parseCalls(action.call()))
                        validateReferencedCall(call, config);
                    for (Map.Entry<String, EvidenceCollector> collector : action.evidence().entrySet()) {
                        String collectorField = prefix + ".evidence." + collector.getKey() + ".call";
                        try {
                            validateToolCall(collector.getValue().call(), config, false);
                            for (ToolCallParser.ParsedCall call : syntaxEngine.parseCalls(collector.getValue().call()))
                                validateReferencedCall(call, config);
                        } catch (Exception error) {
                            throw locateReferencedError(error, template, action, collectorField, template.sourceFile());
                        }
                    }
                }
                catch (Exception error) { throw locateReferencedError(error, template, action, prefix + ".call", template.sourceFile()); }
            }
            validateReferencedExpression(action.description(), prefix + ".description", template, action, template.sourceFile(), syntaxEngine, config);
            validateReferencedExpression(action.expected(), prefix + ".expected", template, action, template.sourceFile(), syntaxEngine, config);
            validateReferencedExpression(action.actual(), prefix + ".actual", template, action, template.sourceFile(), syntaxEngine, config);
            validateReferencedExpression(action.assertion(), prefix + ".assert", template, action, template.sourceFile(), syntaxEngine, config);
            validateReferencedExpression(action.runWhen(), prefix + ".runWhen", template, action, template.sourceFile(), syntaxEngine, config);
            validateReferencedExpression(action.expression(), prefix + ".expression", template, action, template.sourceFile(), syntaxEngine, config);
            validateReferencedExpression(action.message(), prefix + ".message", template, action, template.sourceFile(), syntaxEngine, config);
            validateReferencedExpression(action.file(), prefix + ".file", template, action, template.sourceFile(), syntaxEngine, config);
            validateReferencedExpression(action.saveAs(), prefix + ".saveAs", template, action, template.sourceFile(), syntaxEngine, config);
            for (Map.Entry<String, Object> field : action.fields().entrySet())
                validateReferencedExpression(String.valueOf(field.getValue()), prefix + ".fields." + field.getKey(), template, action, template.sourceFile(), syntaxEngine, config);
            if ("db".equalsIgnoreCase(action.type())) {
                Map<String, Object> operation = action.query().isEmpty() ? action.update() : action.query();
                String operationName = action.query().isEmpty() ? "update" : "query";
                if (operation.get("sql") != null)
                    validateReferencedExpression(String.valueOf(operation.get("sql")), prefix + "." + operationName + ".sql", template, action, template.sourceFile(), syntaxEngine, config);
                Object params = operation.get("params");
                if (params instanceof Iterable) {
                    int index = 0;
                    for (Object value : (Iterable<?>) params) {
                        if (value instanceof String) validateReferencedExpression((String) value, prefix + "." + operationName + ".params[" + index + "]", template, action, template.sourceFile(), syntaxEngine, config);
                        index++;
                    }
                } else if (params instanceof String) {
                    validateReferencedExpression((String) params, prefix + "." + operationName + ".params", template, action, template.sourceFile(), syntaxEngine, config);
                }
                Object parameters = operation.get("parameters");
                if (parameters instanceof Map) for (Map.Entry<?, ?> field : ((Map<?, ?>) parameters).entrySet()) {
                    if (field.getValue() instanceof String) validateReferencedExpression(String.valueOf(field.getValue()), prefix + "." + operationName + ".parameters." + field.getKey(), template, action, template.sourceFile(), syntaxEngine, config);
                }
            }
            if ("render".equalsIgnoreCase(action.type())) try {
                for (Path payload : new att.template.RenderPayloadResolver().resolve(template.directory(), action.payload())) {
                    String content = att.template.PayloadCache.readUtf8(payload);
                    if (diagnostics != null) warnReferencedToolDefinitions(diagnostics, content, syntaxEngine, config);
                    validateReferencedExpression(content, prefix + ".payload", template, action, payload, syntaxEngine, config);
                }
            } catch (Exception e) {
                DiagnosticException typed = DiagnosticException.find(e);
                if (typed != null) throw typed;
                throw new IllegalArgumentException("Unable to inspect render payload calls for action " + action.id() + ": " + e.getMessage(), e);
            }
            if ("flow".equalsIgnoreCase(action.type()) && flows != null && visitedFlows.add(action.use())) {
                att.flow.FlowDefinition target = flows.get(action.use());
                if (target != null) {
                    StageTemplate body = new StageTemplate(target.name(), target.directory(), target.actions(),
                            att.Version.TEMPLATE_SCHEMA, target.directory().resolve("flow.yaml"));
                    validateReferencedTools(body, config, diagnostics, visitedFlows);
                }
            }
        }
    }

    private void validateReferencedExpression(String expression, String field, StageTemplate template,
                                               TemplateAction action, Path sourceFile,
                                               att.template.UnifiedTemplateEngine syntaxEngine,
                                               FrameworkConfig config) {
        if (expression == null || expression.trim().isEmpty()) return;
        try {
            for (ToolCallParser.ParsedCall call : syntaxEngine.parseCalls(expression)) validateReferencedCall(call, config);
        } catch (Exception error) {
            throw locateReferencedError(error, template, action, field, sourceFile);
        }
    }

    /** Emits actionable 3.4.2 warnings for deterministic legacy aliases. */
    private void addContextMigrationWarnings(List<Diagnostic> diagnostics, StageTemplate template) {
        att.template.UnifiedTemplateEngine engine = new att.template.UnifiedTemplateEngine(null);
        for (TemplateAction action : template.actions())
            addContextMigrationWarningsForAction(diagnostics, engine, template, action,
                    MigrationPhase.STATIC, null);
    }

    /** Scans a concrete Case with the same Action publication phases as runtime. */
    private void addCaseContextMigrationWarnings(List<Diagnostic> diagnostics, StageTemplate template,
                                                 TestCase testCase, StageCaseData stage, FrameworkConfig config,
                                                 Path caseFile, Set<String> assignedCaseVariables) {
        try {
            att.core.CaseRuntimeContext context = new att.core.CaseRuntimeContext(testCase, projectRoot,
                    "VALIDATE", projectRoot, projectRoot.resolve(".att-validation.log"));
            context.setProject(projectRoot);
            context.put("CASE.environment", config.environment());
            for (String name : assignedCaseVariables) context.putValidationPlaceholder("EXEC.VARS." + name);
            context.beginStage(stage, template.name(), template.directory());
            att.template.UnifiedTemplateEngine engine = new att.template.UnifiedTemplateEngine(null);
            for (TemplateAction action : template.actions()) {
                addContextMigrationWarningsForAction(diagnostics, engine, template, action,
                        MigrationPhase.PRE_PUBLICATION, context);
                context.putValidationPlaceholder("EXEC.ACTIONS." + action.id() + ".output");
                if ("assign".equalsIgnoreCase(action.type())) context.putValidationPlaceholder("EXEC.VARS." + action.name());
                addContextMigrationWarningsForAction(diagnostics, engine, template, action,
                        MigrationPhase.POST_PUBLICATION, context);
            }
        } catch (Exception ignored) {
            // The normal case-bound validator owns Context errors. Migration
            // scanning must not replace its source-located diagnostic.
        }
    }

    private enum MigrationPhase { STATIC, PRE_PUBLICATION, POST_PUBLICATION }

    private void addContextMigrationWarningsForAction(List<Diagnostic> diagnostics,
                                                       att.template.UnifiedTemplateEngine engine,
                                                       StageTemplate template, TemplateAction action,
                                                       MigrationPhase phase,
                                                       att.core.CaseRuntimeContext context) {
        String prefix = "actions." + action.id();
        Path sourceFile = template.sourceFile();
        boolean staticScan = phase == MigrationPhase.STATIC;
        boolean pre = phase == MigrationPhase.PRE_PUBLICATION;
        boolean post = phase == MigrationPhase.POST_PUBLICATION;
        if (staticScan || post) addContextMigrationWarnings(diagnostics, engine, template, action,
                action.description(), prefix + ".description", sourceFile, context);
        if (staticScan || (pre && !"tool".equalsIgnoreCase(action.type())) || (post && "tool".equalsIgnoreCase(action.type())))
            addContextMigrationWarnings(diagnostics, engine, template, action,
                    action.expected(), prefix + ".expected", sourceFile, context);
        if (staticScan || post) addContextMigrationWarnings(diagnostics, engine, template, action,
                action.actual(), prefix + ".actual", sourceFile, context);
        if (staticScan || (pre && "tool".equalsIgnoreCase(action.type())) || (post && !"tool".equalsIgnoreCase(action.type())))
            addContextMigrationWarnings(diagnostics, engine, template, action,
                    action.assertion(), prefix + ".assert", sourceFile, context);
        if (staticScan || pre) {
            addContextMigrationWarnings(diagnostics, engine, template, action,
                    action.runWhen(), prefix + ".runWhen", sourceFile, context);
            addContextMigrationWarnings(diagnostics, engine, template, action,
                    action.expression(), prefix + ".expression", sourceFile, context);
            addContextMigrationWarnings(diagnostics, engine, template, action,
                    action.message(), prefix + ".message", sourceFile, context);
            addContextMigrationWarnings(diagnostics, engine, template, action,
                    action.file(), prefix + ".file", sourceFile, context);
            addContextMigrationWarnings(diagnostics, engine, template, action,
                    action.saveAs(), prefix + ".saveAs", sourceFile, context);
            addContextMigrationWarnings(diagnostics, engine, template, action,
                    action.call(), prefix + ".call", sourceFile, context);
            for (Map.Entry<String, Object> field : action.fields().entrySet())
                addContextMigrationWarnings(diagnostics, engine, template, action,
                        String.valueOf(field.getValue()), prefix + ".fields." + field.getKey(), sourceFile, context);
            for (EvidenceCollector collector : action.evidence().values())
                addContextMigrationWarnings(diagnostics, engine, template, action,
                        collector.call(), prefix + ".evidence." + collector.id() + ".call", sourceFile, context);
            if ("db".equalsIgnoreCase(action.type())) {
                Map<String, Object> operation = action.query().isEmpty() ? action.update() : action.query();
                String operationName = action.query().isEmpty() ? "update" : "query";
                if (operation.get("sql") != null) addContextMigrationWarnings(diagnostics, engine, template, action,
                        String.valueOf(operation.get("sql")), prefix + "." + operationName + ".sql", sourceFile, context);
                Object params = operation.get("params");
                if (params instanceof Iterable) {
                    int index = 0;
                    for (Object value : (Iterable<?>) params) {
                        if (value instanceof String) addContextMigrationWarnings(diagnostics, engine, template, action,
                                (String) value, prefix + "." + operationName + ".params[" + index + "]", sourceFile, context);
                        index++;
                    }
                } else if (params instanceof String) addContextMigrationWarnings(diagnostics, engine, template, action,
                        (String) params, prefix + "." + operationName + ".params", sourceFile, context);
                Object parameters = operation.get("parameters");
                if (parameters instanceof Map) for (Map.Entry<?, ?> field : ((Map<?, ?>) parameters).entrySet())
                    if (field.getValue() instanceof String) addContextMigrationWarnings(diagnostics, engine, template, action,
                            (String) field.getValue(), prefix + "." + operationName + ".parameters." + field.getKey(), sourceFile, context);
            }
            if ("render".equalsIgnoreCase(action.type())) try {
                for (Path payload : new att.template.RenderPayloadResolver().resolve(template.directory(), action.payload()))
                    addContextMigrationWarnings(diagnostics, engine, template, action,
                            att.template.PayloadCache.readUtf8(payload), prefix + ".payload", payload, context);
            } catch (Exception ignored) {
                // The normal validator reports payload discovery/read errors.
            }
        }
    }

    private void addContextMigrationWarnings(List<Diagnostic> diagnostics,
                                              att.template.UnifiedTemplateEngine engine,
                                              StageTemplate template, TemplateAction action,
                                              String text, String field, Path sourceFile) {
        addContextMigrationWarnings(diagnostics, engine, template, action, text, field, sourceFile, null);
    }

    private void addContextMigrationWarnings(List<Diagnostic> diagnostics,
                                              att.template.UnifiedTemplateEngine engine,
                                              StageTemplate template, TemplateAction action,
                                              String text, String field, Path sourceFile,
                                              att.core.CaseRuntimeContext context) {
        if (text == null || text.trim().isEmpty()) return;
        java.util.List<String> paths;
        try { paths = engine.parseContextPaths(text); }
        catch (Exception ignored) { return; }
        for (String original : paths) {
            String path = att.core.CaseRuntimeContext.requiredReferencePath(original);
            String replacement = legacyReplacement(path);
            if (replacement == null && isRootlessReference(path)) {
                replacement = rootlessReplacement(path, template, context);
                String legacy = "${" + original + "}";
                String message = "Rootless Context shorthand: " + legacy;
                String suggestion;
                if (replacement != null) {
                    message += "\nCanonical replacement: ${" + replacement + "}";
                    suggestion = "Replace " + legacy + " with ${" + replacement + "}.";
                } else {
                    message += context == null
                            ? "\nThe validator cannot prove one unique canonical replacement from the static template alone."
                            : "\nThe validator cannot prove one unique canonical replacement from the current scope.";
                    suggestion = "Resolve the shorthand against the current scope and replace it with the exact unique EXEC/META path; do not assume EXEC.INPUT.";
                }
                message += "\nThe shorthand remains compatible only when it resolves to one unique current-scope path."
                        + "\nMigrate new definitions to the canonical path.";
                diagnostics.add(new Diagnostic(DiagnosticCodes.CONTEXT_LEGACY_PATH,
                        Diagnostic.Severity.WARNING, message,
                        sourceFile == null ? null : sourceFile.toString(), field,
                        null, null, null, template.name(), action.id(),
                        suggestion, null, null, null, null));
                continue;
            }
            if (replacement == null) continue;
            String legacy = "${" + original + "}";
            String message = "Legacy Context path: " + legacy
                    + "\nCanonical replacement: ${" + replacement + "}"
                    + "\nThe legacy spelling remains compatible in 3.4.2 when it maps deterministically to the current scope."
                    + "\nMigrate new definitions to the canonical path.";
            diagnostics.add(new Diagnostic(DiagnosticCodes.CONTEXT_LEGACY_PATH,
                    Diagnostic.Severity.WARNING, message,
                    sourceFile == null ? null : sourceFile.toString(), field,
                    null, null, null, template.name(), action.id(),
                    "Replace " + legacy + " with ${" + replacement + "}.", null, null, null, null));
        }
    }

    private boolean isRootlessReference(String path) {
        if (path == null || path.isEmpty()) return false;
        String root = att.core.ContextPathPolicy.firstSegment(path);
        if (att.core.ContextPathPolicy.isExplicitRoot(root)) return false;
        // These names are removed/invalid Flow-only roots, not migration-safe
        // unique suffixes. Let the ordinary validator report them as errors.
        return !("input".equals(root) || "actions".equals(root) || "runtime".equals(root)
                || "flow".equals(root) || "stage".equals(root) || "stages".equals(root)
                || root.indexOf(':') >= 0);
    }

    private String rootlessReplacement(String path, StageTemplate template,
                                       att.core.CaseRuntimeContext context) {
        // A static Template does not know the Case input columns or the
        // runtime shape of EXEC.VARS.  An Action/variable name can therefore
        // still collide with a Case input suffix.  Do not guess a canonical
        // replacement here; a case-bound resolver may supply an exact path
        // when it can prove uniqueness.
        if (context != null) {
            String canonical = context.uniqueCanonicalPath(path);
            if (canonical != null && (canonical.startsWith("EXEC.") || canonical.startsWith("META."))) return canonical;
        }
        return null;
    }

    private String legacyReplacement(String path) {
        if (path == null) return null;
        if (path.startsWith("CASE.STAGES") || path.startsWith("TOOL") || path.startsWith("DB")) return null;
        if (path.startsWith("CASE.VARS.")) return "EXEC.VARS." + path.substring("CASE.VARS.".length());
        if ("CASE.VARS".equals(path)) return "EXEC.VARS";
        if ("CASE.outputDirectory".equals(path)) return "EXEC.OUTPUT_DIR";
        if (path.startsWith("ACTIONS.")) return "EXEC.ACTIONS." + path.substring("ACTIONS.".length());
        if ("ACTIONS".equals(path)) return "EXEC.ACTIONS";
        if ("RUN.id".equals(path) || "RUN.runId".equals(path)) return "EXEC.ID";
        if (path.startsWith("CASE.")) {
            String field = path.substring("CASE.".length());
            if (field.startsWith("caseId") || field.startsWith("workbookId") || field.startsWith("groupId")
                    || field.startsWith("rowCaseId")) return "META.SOURCE." + field;
            if (!field.isEmpty() && !field.startsWith("status") && !field.startsWith("durationMs")
                    && !field.startsWith("environment") && !field.startsWith("error")) return "EXEC.INPUT." + field;
        }
        return null;
    }

    private DiagnosticException locateReferencedError(Exception error, StageTemplate template,
                                                      TemplateAction action, String field, Path sourceFile) {
        DiagnosticException typed = DiagnosticException.find(error);
        DiagnosticException diagnostic = typed == null
                ? DiagnosticException.wrap(DiagnosticCodes.TEMPLATE_INVALID, "Invalid Action expression", error,
                        null, null, "Correct the expression at the reported source location.")
                : typed;
        DiagnosticException located = sourceFile.equals(template.sourceFile())
                ? att.config.YamlSupport.locate(diagnostic, sourceFile, field)
                : att.config.YamlSupport.locateText(diagnostic, sourceFile, field);
        return located.withLocation(null, null, null, null, null, template.name(), action.id());
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
            Object parameters = operation.get("parameters");
            if (parameters instanceof Map) for (Object value : ((Map<?, ?>) parameters).values()) {
                if (value instanceof String) expressions.add((String) value);
            }
        }
        for (Object value : action.fields().values()) expressions.add(String.valueOf(value));
        for (EvidenceCollector collector : action.evidence().values()) expressions.add(collector.call());
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

    /**
     * Reports only the deprecated Tool-local spelling ${argument}.  This is
     * deliberately narrower than general rootless Context shorthand: a bare
     * reference is a Tool input only when it is exactly one declared argument.
     */
    private void addToolInputShorthandWarnings(List<Diagnostic> diagnostics,
                                                Map<String, ToolConfig> tools) {
        for (ToolConfig tool : tools.values()) addToolInputShorthandWarnings(diagnostics, tool);
    }

    private void addToolInputShorthandWarnings(List<Diagnostic> diagnostics, ToolConfig tool) {
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\$\\{([^}]+)}");
        if (tool.commandBacked()) {
            for (int index = 0; index < tool.commandArgv().size(); index++) {
                String token = tool.commandArgv().get(index);
                addToolInputShorthandWarnings(diagnostics, tool, token,
                        "tools." + tool.key() + ".command[" + index + "]", placeholder);
            }
        } else {
            addToolInputShorthandWarnings(diagnostics, tool, tool.call(),
                    "tools." + tool.key() + ".call", placeholder);
        }
    }

    private void warnReferencedToolDefinitions(List<Diagnostic> diagnostics, String text,
                                                att.template.UnifiedTemplateEngine engine,
                                                FrameworkConfig config) {
        if (text == null || text.trim().isEmpty()) return;
        for (ToolCallParser.ParsedCall call : engine.parseCalls(text)) {
            ToolConfig tool = config.tool(call.name());
            if (tool != null) addToolInputShorthandWarnings(diagnostics, tool);
        }
    }

    /** Migration warnings describe source definitions, not every Case that uses them. */
    private void deduplicateMigrationDiagnostics(List<Diagnostic> diagnostics) {
        Set<String> seen = new LinkedHashSet<String>();
        List<Diagnostic> unique = new ArrayList<Diagnostic>();
        for (Diagnostic diagnostic : diagnostics) {
            if (!DiagnosticCodes.CONTEXT_LEGACY_PATH.equals(diagnostic.code())
                    && !DiagnosticCodes.CONTEXT_TOOL_INPUT_SHORTHAND.equals(diagnostic.code())) {
                unique.add(diagnostic);
                continue;
            }
            String message = diagnostic.message() == null ? "" : diagnostic.message();
            int newline = message.indexOf('\n');
            String legacyPath = newline < 0 ? message : message.substring(0, newline);
            String key = diagnostic.code() + "\u0000" + String.valueOf(diagnostic.file()) + "\u0000"
                    + String.valueOf(diagnostic.field()) + "\u0000" + legacyPath;
            if (seen.add(key)) unique.add(diagnostic);
            else {
                for (int index = 0; index < unique.size(); index++) {
                    Diagnostic existing = unique.get(index);
                    if (!sameMigrationKey(existing, key)) continue;
                    if (isGenericRootlessWarning(existing) && !isGenericRootlessWarning(diagnostic)) unique.set(index, diagnostic);
                    break;
                }
            }
        }
        diagnostics.clear();
        diagnostics.addAll(unique);
    }

    private boolean sameMigrationKey(Diagnostic diagnostic, String key) {
        String message = diagnostic.message() == null ? "" : diagnostic.message();
        int newline = message.indexOf('\n');
        String legacyPath = newline < 0 ? message : message.substring(0, newline);
        String existingKey = diagnostic.code() + "\u0000" + String.valueOf(diagnostic.file()) + "\u0000"
                + String.valueOf(diagnostic.field()) + "\u0000" + legacyPath;
        return existingKey.equals(key);
    }

    private boolean isGenericRootlessWarning(Diagnostic diagnostic) {
        return diagnostic.message() != null
                && diagnostic.message().contains("cannot prove one unique canonical replacement");
    }

    private void addToolInputShorthandWarnings(List<Diagnostic> diagnostics, ToolConfig tool,
                                                String text, String field,
                                                java.util.regex.Pattern placeholder) {
        if (text == null || text.isEmpty()) return;
        java.util.regex.Matcher matcher = placeholder.matcher(text);
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            boolean canonical = path.startsWith("input.");
            boolean explicitLegacy = path.startsWith("TOOL.input.");
            String argument = canonical ? path.substring("input.".length())
                    : explicitLegacy ? path.substring("TOOL.input.".length()) : path;
            if (!tool.arguments().containsKey(argument)) continue;
            if (canonical || (!explicitLegacy && !argument.matches("[A-Za-z_][A-Za-z0-9_]*"))) continue;
            String legacy = "${" + path + "}";
            String canonicalForm = "${input." + argument + "}";
            String message = "Legacy Tool argument reference: " + legacy
                    + "\nCanonical Tool argument reference: " + canonicalForm
                    + "\nThe shorthand remains compatible in 3.4.2 when it resolves unambiguously to a declared Tool-local argument."
                    + "\nTool: " + tool.key() + "\nField: " + field;
            diagnostics.add(new Diagnostic(DiagnosticCodes.CONTEXT_TOOL_INPUT_SHORTHAND,
                    Diagnostic.Severity.WARNING, message,
                    tool.sourceFile() == null ? null : tool.sourceFile().toString(), field,
                    null, null, null, null, null,
                    "Migrate to the canonical Tool-local input form " + canonicalForm + "."));
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
                    typed.sheet(), typed.row(), typed.column(), typed.template(), typed.action(), typed.suggestion(),
                    typed.summary(), typed.detail(), typed.source(), typed.context(), typed.schemaViolations());
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
        validateTemplateActions(template, config, actionIds, completedActions, assignmentNames);
    }

    private void validateTemplateActions(StageTemplate template, FrameworkConfig config,
                                         Set<String> actionIds, Set<String> completedActions,
                                         Set<String> assignmentNames) {
        att.template.UnifiedTemplateEngine syntaxEngine = new att.template.UnifiedTemplateEngine(null);
        for (TemplateAction action : template.actions()) {
          try {
            validateActionSyntax(action, template, syntaxEngine, config);
            if (action.id().contains(".")) throw new IllegalArgumentException("Action ID must not contain '.': " + action.id());
            if (!actionIds.add(action.id())) throw new IllegalArgumentException("Duplicate Action ID: " + action.id());
            String type = action.type().toLowerCase(java.util.Locale.ROOT);
            if (!("render".equals(type) || "tool".equals(type) || "db".equals(type) || "assert".equals(type) || "log".equals(type) || "assign".equals(type) || "flow".equals(type))) throw new IllegalArgumentException("Unsupported action type: " + action.type());
            if (!"tool".equals(type) && action.raw().containsKey("evidence")) {
                throw new IllegalArgumentException("Field 'evidence' is only supported for tool actions: " + action.id());
            }
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
                        validateStaticContextStructure(content, syntaxEngine, completedActions, false, action.id());
                        for (ToolCallParser.ParsedCall call : syntaxEngine.parseCalls(content)) validateCall(call, config);
                        if (!"file".equalsIgnoreCase(action.renderAs()) && !content.contains("${") && !content.contains("#{")) new att.exec.ToolInvoker(projectRoot, config).parseOutput(content, action.renderAs());
                    } catch (Exception e) {
                        throw att.config.YamlSupport.locateText(DiagnosticException.wrap(DiagnosticCodes.TEMPLATE_INVALID,
                                "Invalid render payload", e, null, null, "Check the payload expression and output format."),
                                payload, "actions." + action.id() + ".payload")
                                .withLocation(null, null, null, null, null, template.name(), action.id());
                    }
                }
            }
            if ("tool".equals(type)) { require(action.call(), "call is required for tool action " + action.id()); forbid(action, "name", "payload", "renderAs", "db", "query", "update", "expression", "message", "file", "level", "fields"); if (action.timeoutMs() != null && (action.timeoutMs() < 1 || action.timeoutMs() > 3600000)) throw new IllegalArgumentException("timeoutMs must be 1..3600000: " + action.id()); validateRetry(action); validateInlineExpressions(action.saveAs(), syntaxEngine, config); validateToolCall(action.call(), config); validateToolSaveAs(action, config); validateEvidence(action, template, syntaxEngine, config, completedActions); }
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
                        "Duplicate EXEC.VARS assignment '${EXEC.VARS." + action.name() + "}'",
                        "The same variable name is assigned more than once in template '" + template.name() + "'.",
                        null, "name", null, null, null, template.name(), action.id(),
                        "Use a unique Case-scoped variable name; assign does not overwrite.", null);
                forbid(action, "output", "payload", "renderAs", "saveAs", "overwrite", "call", "db", "query", "update", "expected", "actual", "message", "file", "level", "fields", "retry", "timeoutMs");
                validateInlineExpressions(action.expression(), syntaxEngine, config);
            validateStaticContextStructure(action.expression(), syntaxEngine, completedActions, false, action.id());
            }
            if ("flow".equals(type)) {
                if (!att.Version.TEMPLATE_SCHEMA.equals(template.schemaVersion())) throw new IllegalArgumentException("Flow actions require " + att.Version.TEMPLATE_SCHEMA + ": " + action.id());
                require(action.use(), "use is required for Flow action " + action.id());
                forbid(action, "name", "payload", "renderAs", "saveAs", "overwrite", "call", "db", "query", "update", "expression", "assert", "expected", "actual", "message", "file", "level", "fields", "retry", "timeoutMs");
                if (flows == null) throw new IllegalStateException("Flow registry is unavailable");
                flows.validateInvocation(action);
            }

            Set<String> afterCurrentAction = new LinkedHashSet<String>(completedActions);
            afterCurrentAction.add(action.id());
            validateStaticContextStructure(action.description(), syntaxEngine, afterCurrentAction, true, action.id());
            Set<String> assertionActions = "tool".equals(type) ? completedActions : afterCurrentAction;
            validateStaticContextStructure(action.assertion(), syntaxEngine, assertionActions, true, action.id());
            validateStaticContextStructure(action.actual(), syntaxEngine, afterCurrentAction, true, action.id());
            Set<String> expectedActions = "tool".equals(type) ? afterCurrentAction : completedActions;
            boolean expectedOutputAvailable = "tool".equals(type);
            validateStaticContextStructure(action.expected(), syntaxEngine, expectedActions, expectedOutputAvailable, action.id());
            validateStaticContextStructure(action.runWhen(), syntaxEngine, completedActions, false, action.id());
            if ("tool".equals(type)) {
                validateStaticContextStructure(action.call(), syntaxEngine, completedActions, false, action.id());
                validateStaticContextStructure(action.saveAs(), syntaxEngine, completedActions, false, action.id());
                for (EvidenceCollector collector : action.evidence().values()) {
                    validateStaticContextStructure(collector.call(), syntaxEngine, completedActions, true, action.id());
                }
            }
            if ("db".equals(type)) {
                validateStaticContextStructure(action.saveAs(), syntaxEngine, completedActions, false, action.id());
                Map<String, Object> operation = action.query().isEmpty() ? action.update() : action.query();
                if (operation.get("sql") != null) validateStaticContextStructure(String.valueOf(operation.get("sql")), syntaxEngine, completedActions, false, action.id());
                Object params = operation.get("params");
                if (params instanceof Iterable) {
                    for (Object value : (Iterable<?>) params) if (value instanceof String) {
                        validateStaticContextStructure((String) value, syntaxEngine, completedActions, false, action.id());
                    }
                } else if (params instanceof String) {
                    validateStaticContextStructure((String) params, syntaxEngine, completedActions, false, action.id());
                }
                Object parameters = operation.get("parameters");
                if (parameters instanceof Map) for (Object value : ((Map<?, ?>) parameters).values()) {
                    if (value instanceof String) validateStaticContextStructure((String) value, syntaxEngine, completedActions, false, action.id());
                }
            }
            if ("log".equals(type)) {
                validateStaticContextStructure(action.message(), syntaxEngine, completedActions, false, action.id());
                validateStaticContextStructure(action.file(), syntaxEngine, completedActions, false, action.id());
                for (Object value : action.fields().values()) validateStaticContextStructure(String.valueOf(value), syntaxEngine, completedActions, false, action.id());
            }
            if ("flow".equals(type)) {
                att.flow.FlowDefinition target = flows.get(action.use());
                StageTemplate body = new StageTemplate(target.name(), target.directory(), target.actions(), att.Version.TEMPLATE_SCHEMA, target.directory().resolve("flow.yaml"));
                // A Flow invocation owns a fresh Action namespace.  Its
                // internal IDs are intentionally not compared with the parent
                // Template or with another Flow invocation.
                validateTemplateActions(body, config, new LinkedHashSet<String>(),
                        new LinkedHashSet<String>(), assignmentNames);
            }
          } catch (DiagnosticException e) { throw att.config.YamlSupport.locate(e, template.sourceFile(), "actions." + action.id())
                  .withLocation(null, null, null, null, null, template.name(), action.id()); }
          catch (LocatedValidationException e) { throw e; }
          catch (Exception e) { throw att.config.YamlSupport.locate(DiagnosticException.wrap(
                  DiagnosticCodes.TEMPLATE_INVALID, "Invalid template action", e, null, null,
                  "Check the reported Action field and expression syntax."), template.sourceFile(), "actions." + action.id())
                  .withLocation(null, null, null, null, null, template.name(), action.id()); }
          completedActions.add(action.id());
        }
    }

    private void validateActionSyntax(TemplateAction action, StageTemplate template,
                                      att.template.UnifiedTemplateEngine engine, FrameworkConfig config) {
        String[] fields = {"description", "expected", "actual", "assert", "runWhen", "call", "expression", "message", "file", "saveAs"};
        String[] values = {action.description(), action.expected(), action.actual(), action.assertion(), action.runWhen(),
                action.call(), action.expression(), action.message(), action.file(), action.saveAs()};
        for (int index = 0; index < fields.length; index++) {
            try {
                if (("assert".equals(fields[index]) || "runWhen".equals(fields[index])) && !values[index].trim().isEmpty())
                    validateAssertionExpression(values[index], engine, config);
                else { engine.validateValueSyntax(values[index]); engine.parseCalls(values[index]); }
            } catch (Exception error) {
                throw att.config.YamlSupport.locate(DiagnosticException.wrap(DiagnosticCodes.TEMPLATE_INVALID,
                        "Invalid Action expression", error, null, null, "Correct the expression at the reported source location."),
                        template.sourceFile(), "actions." + action.id() + "." + fields[index]);
            }
        }
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
                "sql", "sqlFile", "params", "parameters");
        boolean hasSql = operation.get("sql") != null;
        boolean hasFile = operation.get("sqlFile") != null;
        if (hasSql == hasFile) throw new IllegalArgumentException("DB action SQL block requires exactly one sql or sqlFile: " + action.id());
        String sqlText;
        if (hasSql) {
            sqlText = String.valueOf(operation.get("sql"));
            validateDbSql(sqlText, engine, config);
        } else {
            att.exec.DbHelperExecutor executor = new att.exec.DbHelperExecutor(projectRoot, config);
            Path sqlFile = executor.resolveSqlFile(String.valueOf(operation.get("sqlFile")));
            sqlText = new String(Files.readAllBytes(sqlFile), java.nio.charset.StandardCharsets.UTF_8);
            validateDbSql(sqlText, engine, config);
        }
        Object params = operation.get("params");
        Object parameters = operation.get("parameters");
        if (params != null && parameters != null) throw new IllegalArgumentException("DB action cannot use both params and parameters: " + action.id());
        if (params != null && !(params instanceof Iterable) && !(params instanceof String)) {
            throw new IllegalArgumentException("DB action params must be a list or exact List expression: " + action.id());
        }
        if (params instanceof Iterable) for (Object value : (Iterable<?>) params) {
            if (value instanceof String) validateInlineExpressions((String) value, engine, config);
        }
        else if (params instanceof String) validateInlineExpressions((String) params, engine, config);
        if (parameters != null) {
            if (!(parameters instanceof Map)) throw new IllegalArgumentException("DB action parameters must be a map: " + action.id());
            Map<String, Object> shape = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) parameters).entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid named SQL parameter: " + name);
                shape.put(name, null);
                if (entry.getValue() instanceof String) validateInlineExpressions((String) entry.getValue(), engine, config);
            }
            att.template.NamedSqlParameters.bind(sqlText, shape);
        }
        validateInlineExpressions(action.saveAs(), engine, config);
        if (action.saveConfig().configured()) {
            String format = action.saveConfig().format().trim().toLowerCase(java.util.Locale.ROOT);
            if (!("text".equals(format) || "json".equals(format) || "yaml".equals(format) || "xml".equals(format))) {
                throw new IllegalArgumentException("DB saveAs.format must be text, json, yaml, or xml: " + action.id());
            }
        }
    }

    private void validateToolSaveAs(TemplateAction action, FrameworkConfig config) {
        if (!action.saveConfig().specified()) return;
        ToolCallParser.ParsedCall parsed = callParser.parse(action.call());
        if (parsed.name().startsWith("mq.")) {
            String format = action.saveConfig().format().trim().toLowerCase(java.util.Locale.ROOT);
            if (format.isEmpty()) format = "raw";
            if (!("raw".equals(format) || "text".equals(format) || "json".equals(format)
                    || "yaml".equals(format) || "xml".equals(format))) {
                throw new IllegalArgumentException("MQ saveAs.format must be raw, text, json, yaml, or xml: " + action.id());
            }
            return;
        }
        if (!action.saveConfig().configured()) return;
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

    private void validateEvidence(TemplateAction action, StageTemplate template,
                                  att.template.UnifiedTemplateEngine syntaxEngine,
                                  FrameworkConfig config, Set<String> completedActions) {
        if (action.evidence().isEmpty()) return;
        for (Map.Entry<String, EvidenceCollector> entry : action.evidence().entrySet()) {
            String id = entry.getKey();
            if (id == null || id.trim().isEmpty() || id.contains(".")) {
                throw new IllegalArgumentException("Evidence collector ID must be non-blank and dot-free: " + id);
            }
            EvidenceCollector collector = entry.getValue();
            require(collector.call(), "call is required for evidence collector " + id + " on Action " + action.id());
            if (collector.timeoutMs() != null && (collector.timeoutMs() < 1 || collector.timeoutMs() > 3600000)) {
                throw new IllegalArgumentException("Evidence collector timeoutMs must be 1..3600000: " + id);
            }
            validateToolCall(collector.call(), config, false);
            validateStaticContextStructure(collector.call(), syntaxEngine, completedActions, true, action.id());
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
        validateStaticContextStructure(text, engine, availableActions, currentOutputAvailable, null);
    }

    private void validateStaticContextStructure(String text, att.template.UnifiedTemplateEngine engine,
                                                Set<String> availableActions, boolean currentOutputAvailable,
                                                String currentActionId) {
        for (String path : engine.parseContextPaths(text)) {
            String referencePath = att.core.CaseRuntimeContext.requiredReferencePath(path);
            String root = firstPathSegment(referencePath);
            if ("LOAD".equals(root)) {
                throw incompatibleContextPath(path, DiagnosticCodes.CONTEXT_LEGACY_PATH,
                        "Root-level LOAD is a pre-release compatibility spelling, not a public Context namespace.",
                        "Use the canonical EXEC.LOAD.* path inside a load iteration.");
            }
            if (referencePath.equals("CASE.STAGES") || referencePath.startsWith("CASE.STAGES.")
                    || referencePath.startsWith("CASE.STAGES[")) {
                throw incompatibleContextPath(path, DiagnosticCodes.CONTEXT_CROSS_SCOPE,
                        "CASE.STAGES is retained only as execution-result/report evidence; it is not an expression data API.",
                        "Use EXEC.INPUT for current Stage caller values or EXEC.ACTIONS for an Action in the current scope. Cross-Stage history reads require a dedicated data-passing contract.");
            }
            if ("TOOL".equals(root) || "DB".equals(root)) {
                throw incompatibleContextPath(path, DiagnosticCodes.CONTEXT_LEGACY_PATH,
                        "TOOL.* and DB.* are transient helper/runtime views, not general 3.4.2 expression roots.",
                        "Use local output during the current Action or EXEC.ACTIONS.<actionId> after publication; helper identity belongs in curated META metadata.");
            }
            if (isActionReference(referencePath)) {
                validateActionReference(path, referencePath, availableActions);
                continue;
            }
            if ("EXEC".equals(root) || "META".equals(root)) {
                validateCanonicalPath(path, referencePath);
                continue;
            }
            if ("CASE".equals(root)) continue;
            if ("RUN".equals(root)) {
                String field = firstChildSegment(referencePath, "RUN");
                if (field.isEmpty() || java.util.Arrays.asList("runId", "id", "runDirectory", "caseLog").contains(field)) continue;
                throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                        "Unknown RUN Context variable '${" + path + "}'",
                        "Available RUN fields: runId, id, runDirectory, caseLog", null, path,
                        null, null, null, null, null,
                        "Use the exact case-sensitive RUN field name.", null);
            }
            if ("ACTIONS".equals(root)) continue;
            if ("output".equals(root)) {
                if (currentOutputAvailable) continue;
                throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                        "Current action output is not available at '${" + path + "}'",
                        "This field is rendered before the current action outcome is created.", null, path,
                        null, null, null, null, null,
                        "Use Case input or an earlier EXEC.ACTIONS.<id> result in this field.", null);
            }

            String nearest = nearestAction(root, availableActions);
            String suffix = referencePath.length() > root.length() ? referencePath.substring(root.length()) : "";
            if (currentActionId != null && currentActionId.equals(root) && !availableActions.contains(root)) {
                throw unavailableActionContext(path, root, availableActions);
            }
            if (availableActions.contains(root)) continue; // Unique action-id suffix; its dynamic output shape is checked at runtime.
            if (nearest == null) continue; // Potential CASE/data-column suffix requires a concrete Case binding.
            throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                    "Unknown Context scope in '${" + path + "}'",
                    "Root '" + root + "' is not one of CASE, RUN, ACTIONS, TOOL, DB, or output.", null, path,
                    null, null, null, null, null,
                    nearest == null
                            ? "Use an uppercase Context scope and an exact case-sensitive field/action name."
                            : "Use '${EXEC.ACTIONS." + nearest + suffix + "}' if that completed action is intended.", null);
        }
    }

    private DiagnosticException unavailableActionContext(String path, String id, Set<String> availableActions) {
        return new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                "Unknown or unavailable action Context '${" + path + "}'",
                "Action '" + id + "' has not completed at the point where this value is rendered. Available actions: " + String.join(", ", availableActions),
                null, path, null, null, null, null, null,
                "Reference an earlier action ID, or move this reference to a field rendered after that action completes.", null);
    }

    private boolean isActionReference(String path) {
        return "ACTIONS".equals(path) || path.startsWith("ACTIONS.") || path.startsWith("ACTIONS[")
                || "EXEC.ACTIONS".equals(path) || path.startsWith("EXEC.ACTIONS.") || path.startsWith("EXEC.ACTIONS[");
    }

    private void validateActionReference(String originalPath, String referencePath, Set<String> availableActions) {
        String root = referencePath.startsWith("EXEC.ACTIONS") ? "EXEC.ACTIONS" : "ACTIONS";
        String id = firstChildSegment(referencePath, root);
        if (id.isEmpty()) throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                "Action Context root requires an Action ID '${" + originalPath + "}'",
                "Reference the completed Action below " + root + ".",
                null, originalPath, null, null, null, null, null,
                "Use ${" + root + ".<actionId>.output...}.", null);
        if (!availableActions.contains(id)) throw unavailableActionContext(originalPath, id, availableActions);
        String remainder = remainderAfterActionId(referencePath, root);
        if (remainder.equals(".flow") || remainder.startsWith(".flow.") || remainder.startsWith(".flow[")
                || remainder.equals(".actions") || remainder.startsWith(".actions.") || remainder.startsWith(".actions[")) {
            throw new DiagnosticException(DiagnosticCodes.CONTEXT_CROSS_SCOPE,
                    "Flow internal Actions are not visible through '${" + originalPath + "}'",
                    "Flow evidence is execution-result data, not a parent or sibling Context namespace.",
                    null, originalPath, null, null, null, null, null,
                    "If a value must cross the Flow boundary, publish it through EXEC.VARS; do not read Flow internal Actions after return.", null);
        }
    }

    private String remainderAfterActionId(String path, String root) {
        String remainder = path.substring(root.length());
        if (remainder.startsWith(".")) {
            String child = remainder.substring(1);
            int dot = child.indexOf('.');
            int bracket = child.indexOf('[');
            int end = child.length();
            if (dot >= 0 && dot < end) end = dot;
            if (bracket >= 0 && bracket < end) end = bracket;
            return end == child.length() ? "" : child.substring(end);
        }
        if (remainder.startsWith("[")) {
            int end = remainder.indexOf(']');
            if (end < 0) return "";
            return remainder.substring(end + 1);
        }
        return "";
    }

    private void validateCanonicalPath(String originalPath, String referencePath) {
        String root = firstPathSegment(referencePath);
        if ("EXEC".equals(root)) {
            String child = firstChildSegment(referencePath, "EXEC");
            if (child.isEmpty()) throw invalidCanonicalPath(originalPath,
                    "EXEC must be followed by one of ID, MODE, STARTED_AT, OUTPUT_DIR, INPUT, VARS, ACTIONS, or LOAD.");
            String execPath = referencePath.substring("EXEC.".length());
            if (att.core.ContextPathPolicy.isUnsupportedExecPath(execPath)) {
                throw invalidCanonicalPath(originalPath,
                        att.core.ContextPathPolicy.isUnsupportedStagePath(execPath)
                                ? "3.4.2 does not define EXEC.STAGE or EXEC.STAGES; Stage history is result evidence."
                                : "3.4.2 does not define helper, resource, output, load, call, invocation, or orchestration namespaces below EXEC.");
            }
            if (!att.core.ContextPathPolicy.isCanonicalExecField(child)) {
                throw invalidCanonicalPath(originalPath,
                        "Unknown EXEC field '" + child + "'; the public tree contains only ID, MODE, STARTED_AT, OUTPUT_DIR, INPUT, VARS, ACTIONS, and load-only LOAD.");
            }
            if ("LOAD".equals(child)) {
                String loadField = firstChildSegment(referencePath, "EXEC.LOAD");
                if (!loadField.isEmpty() && !att.core.ContextPathPolicy.isCanonicalLoadField(loadField)) {
                    throw invalidCanonicalPath(originalPath,
                            "Unknown EXEC.LOAD field '" + loadField + "'; use RUN_ID, MODEL, USER_ID, ITERATION_ID, ITERATION, PHASE, or optional RUN_STARTED_AT.");
                }
            }
            return;
        }
        String child = firstChildSegment(referencePath, "META");
        if (child.isEmpty() || !att.core.ContextPathPolicy.isCanonicalMetaField(child)) {
            throw invalidCanonicalPath(originalPath,
                    "Unknown META field '" + child + "'; use PROJECT, SOURCE, TARGET, TEMPLATE, FLOW, TOOL, DBHELPER, or MQHELPER.");
        }
    }

    private DiagnosticException invalidCanonicalPath(String path, String detail) {
        return new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                "Unsupported canonical Context path '${" + path + "}'", detail,
                null, path, null, null, null, null, null,
                "Use the 3.4.2 EXEC/META tree documented in the Reference Manual.", null);
    }

    private DiagnosticException incompatibleContextPath(String path, String code, String detail, String suggestion) {
        return new DiagnosticException(code,
                "Unsupported legacy Context expression '${" + path + "}'", detail,
                null, path, null, null, null, null, null, suggestion, null);
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
        validateTemplateValues(template, testCase, stage, config, null, new LinkedHashSet<String>(), "testcase", null);
    }

    private void validateTemplateValues(StageTemplate template, TestCase testCase, StageCaseData stage,
                                        FrameworkConfig config, Path caseFile) {
        validateTemplateValues(template, testCase, stage, config, caseFile, new LinkedHashSet<String>(), "testcase", null);
    }

    /** Retained for package-validation reflection tests and internal callers. */
    private void validateTemplateValues(StageTemplate template, TestCase testCase, StageCaseData stage,
                                        FrameworkConfig config, Path caseFile, Set<String> assignedCaseVariables) {
        validateTemplateValues(template, testCase, stage, config, caseFile, assignedCaseVariables, "testcase", null);
    }

    private void validateTemplateValues(StageTemplate template, TestCase testCase, StageCaseData stage,
                                        FrameworkConfig config, Path caseFile, Set<String> assignedCaseVariables,
                                        String executionMode, Map<String, Object> legacyInputs) {
        att.core.CaseRuntimeContext context = new att.core.CaseRuntimeContext(testCase, projectRoot, "VALIDATE", projectRoot,
                projectRoot.resolve(".att-validation.log"), executionMode);
        context.setProject(projectRoot);
        context.put("CASE.environment", config.environment());
        context.setLegacyInputsView(legacyInputs);
        // Load-only fields are scheduler-owned. Their names and nested paths
        // are validated here, while values remain deferred until a load
        // iteration creates EXEC.LOAD.
        if ("load".equalsIgnoreCase(executionMode)) {
            for (String field : new String[] {"RUN_ID", "MODEL", "USER_ID", "ITERATION_ID", "ITERATION", "PHASE", "RUN_STARTED_AT"}) {
                context.putValidationPlaceholder("EXEC.LOAD." + field);
            }
        }
        for (String name : assignedCaseVariables) context.putValidationPlaceholder("EXEC.VARS." + name);
        context.beginStage(stage, template.name(), template.directory());
        att.template.UnifiedTemplateEngine engine = new att.template.UnifiedTemplateEngine(new att.exec.ToolInvoker(projectRoot, config));
        Set<String> completedActions = new LinkedHashSet<String>();
        Set<String> actionIds = new LinkedHashSet<String>();
        validateTemplateRuntimeActions(template, testCase, config, caseFile, assignedCaseVariables,
                context, engine, actionIds, completedActions);
    }

    private void validateTemplateRuntimeActions(StageTemplate template, TestCase testCase, FrameworkConfig config,
                                                Path caseFile, Set<String> assignedCaseVariables,
                                                att.core.CaseRuntimeContext context,
                                                att.template.UnifiedTemplateEngine engine,
                                                Set<String> actionIds, Set<String> completedActions) {
        for (TemplateAction action : template.actions()) {
            Path sourceFile = template.sourceFile();
            String sourceField = "actions." + action.id();
            try {
                if (!actionIds.add(action.id())) throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                        "Duplicate expanded Action ID '" + action.id() + "'",
                        "Action IDs must be unique within the current Stage/Template/Flow scope.", null, sourceField,
                        testCase.sheetName(), testCase.rowNumber(), null, template.name(), action.id(),
                        "Use a unique Action ID in this scope; separate Flow invocations may reuse internal IDs.", null);
                Set<String> afterCurrentAction = new LinkedHashSet<String>(completedActions);
                afterCurrentAction.add(action.id());

                sourceField = "actions." + action.id() + ".runWhen";
                validateContextStructure(action.runWhen(), engine, context, testCase, completedActions, action.id());
                engine.renderValidationValues(action.runWhen(), context);
                validateCallArgumentsIn(action.runWhen(), context, engine);

                if ("assign".equalsIgnoreCase(action.type())) {
                    sourceField = "actions." + action.id() + ".name";
                    context.requireCaseVariableAvailable(action.name());
                    sourceField = "actions." + action.id() + ".expression";
                    validateContextStructure(action.expression(), engine, context, testCase, completedActions, action.id());
                    engine.renderValidationValues(action.expression(), context);
                    for (ToolCallParser.ParsedCall call : engine.parseCalls(action.expression())) {
                        validateCallArguments(call, context, engine);
                    }
                }

                sourceField = "actions." + action.id() + ".message";
                validateContextStructure(action.message(), engine, context, testCase, completedActions, action.id());
                engine.renderValidationValues(action.message(), context);
                validateCallArgumentsIn(action.message(), context, engine);

                sourceField = "actions." + action.id() + ".file";
                validateContextStructure(action.file(), engine, context, testCase, completedActions, action.id());
                engine.renderValidationValues(action.file(), context);
                validateCallArgumentsIn(action.file(), context, engine);

                sourceField = "actions." + action.id() + ".saveAs";
                validateContextStructure(action.saveAs(), engine, context, testCase, completedActions, action.id());
                engine.renderValidationValues(action.saveAs(), context);
                validateCallArgumentsIn(action.saveAs(), context, engine);

                for (Object key : action.fields().keySet()) {
                    sourceField = "actions." + action.id() + ".fields." + key;
                    Object value = action.fields().get(key);
                    validateContextStructure(String.valueOf(value), engine, context, testCase, completedActions, action.id());
                    engine.renderValidationValues(String.valueOf(value), context);
                    validateCallArgumentsIn(String.valueOf(value), context, engine);
                }
                if ("tool".equalsIgnoreCase(action.type())) {
                    sourceField = "actions." + action.id() + ".call";
                    validateContextStructure(action.call(), engine, context, testCase, completedActions, action.id());
                    engine.renderValidationValues(action.call(), context);
                    validateCallArgumentsIn(action.call(), context, engine);
                    for (EvidenceCollector collector : action.evidence().values()) {
                        sourceField = "actions." + action.id() + ".evidence." + collector.id() + ".call";
                        validateContextStructure(collector.call(), engine, context, testCase, completedActions, action.id());
                        engine.renderValidationValues(collector.call(), context);
                        validateCallArgumentsIn(collector.call(), context, engine);
                    }
                }
                if ("db".equalsIgnoreCase(action.type())) {
                    Map<String, Object> operation = action.query().isEmpty() ? action.update() : action.query();
                    if (operation.get("sql") != null) {
                        sourceField = "actions." + action.id() + "." + (action.query().isEmpty() ? "update" : "query") + ".sql";
                        String sql = String.valueOf(operation.get("sql"));
                        validateContextStructure(sql, engine, context, testCase, completedActions, action.id());
                        engine.renderValidationValues(sql, context);
                    }
                    Object params = operation.get("params");
                    if (params instanceof Iterable) {
                        int index = 0;
                        for (Object value : (Iterable<?>) params) {
                            if (value instanceof String) {
                                sourceField = "actions." + action.id() + ".params[" + index + "]";
                                validateContextStructure((String) value, engine, context, testCase, completedActions, action.id());
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
                    Object parameters = operation.get("parameters");
                    if (parameters instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) parameters).entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof String) {
                            sourceField = "actions." + action.id() + ".parameters." + entry.getKey();
                            validateContextStructure((String) value, engine, context, testCase, completedActions, action.id());
                            engine.renderValidationValues((String) value, context);
                            validateCallArgumentsIn((String) value, context, engine);
                        }
                    }
                }
                if ("render".equalsIgnoreCase(action.type())) {
                    for (Path payload : new att.template.RenderPayloadResolver().resolve(template.directory(), action.payload())) {
                        sourceFile = payload;
                        sourceField = "actions." + action.id() + ".payload";
                        String content = att.template.PayloadCache.readUtf8(payload);
                        validateContextStructure(content, engine, context, testCase, completedActions, action.id());
                        String partial = engine.renderValidationValues(content, context);
                        validateCallArgumentsIn(content, context, engine);
                        if (!"file".equalsIgnoreCase(action.renderAs()) && !partial.contains("${") && !partial.contains("#{")) engine.parseRendered(partial, action.renderAs());
                    }
                }

                // Publish the deferred Action shape only after all fields that
                // runtime renders before context.addAction(...) have passed.
                // This keeps validation-time shorthand resolution aligned with
                // the runtime publication boundary.
                context.putValidationPlaceholder("EXEC.ACTIONS." + action.id() + ".output");
                if ("assign".equalsIgnoreCase(action.type())) context.putValidationPlaceholder("EXEC.VARS." + action.name());

                sourceField = "actions." + action.id() + ".description";
                validateContextStructure(action.description(), engine, context, testCase, afterCurrentAction, action.id());
                engine.renderValidationValues(action.description(), context);
                validateCallArgumentsIn(action.description(), context, engine);

                sourceField = "actions." + action.id() + ".assert";
                Set<String> assertionActions = ("tool".equalsIgnoreCase(action.type())
                        || "flow".equalsIgnoreCase(action.type())) ? completedActions : afterCurrentAction;
                validateContextStructure(action.assertion(), engine, context, testCase, assertionActions, action.id());
                engine.renderValidationValues(action.assertion(), context);
                validateCallArgumentsIn(action.assertion(), context, engine);

                sourceField = "actions." + action.id() + ".actual";
                validateContextStructure(action.actual(), engine, context, testCase, afterCurrentAction, action.id());
                engine.renderValidationValues(action.actual(), context);
                validateCallArgumentsIn(action.actual(), context, engine);

                sourceField = "actions." + action.id() + ".expected";
                Set<String> expectedActions = "tool".equalsIgnoreCase(action.type())
                        ? afterCurrentAction : completedActions;
                validateContextStructure(action.expected(), engine, context, testCase, expectedActions, action.id());
                engine.renderValidationValues(action.expected(), context);
                validateCallArgumentsIn(action.expected(), context, engine);

                if ("flow".equalsIgnoreCase(action.type())) {
                    att.flow.FlowDefinition target = flows.get(action.use());
                    StageTemplate body = new StageTemplate(target.name(), target.directory(), target.actions(), att.Version.TEMPLATE_SCHEMA, target.directory().resolve("flow.yaml"));
                    context.beginFlow(action.use(), action.id());
                    try {
                        validateTemplateRuntimeActions(body, testCase, config, caseFile, assignedCaseVariables,
                                context, engine, new LinkedHashSet<String>(), new LinkedHashSet<String>());
                    } finally {
                        context.finishFlow();
                    }
                }
            } catch (Exception e) {
                DiagnosticException typed = DiagnosticException.find(e);
                String caseSource = caseFile == null ? null : portable(caseFile) + "!" + testCase.sheetName() + ":" + testCase.rowNumber();
                if (typed != null) throw sourceDiagnostic(typed, sourceFile, sourceField, testCase, template, action, caseSource);
                throw sourceDiagnostic(new DiagnosticException(DiagnosticCodes.TEMPLATE_INVALID,
                        "Invalid runtime-rendered value in template action", appendCaseSource(e.getMessage(), caseSource), sourceFile.toString(),
                        sourceField, testCase.sheetName(), testCase.rowNumber(), null, template.name(), action.id(),
                        "Check every Context path, inline call, action field, and render payload used by this action.", e),
                        sourceFile, sourceField, testCase, template, action, caseSource);
            } finally {
                completedActions.add(action.id());
            }
            if ("assign".equalsIgnoreCase(action.type())) assignedCaseVariables.add(action.name());
        }
    }

    private DiagnosticException sourceDiagnostic(DiagnosticException error, Path sourceFile, String sourceField,
                                                 TestCase testCase, StageTemplate template, TemplateAction action,
                                                 String caseSource) {
        DiagnosticException located = sourceFile.equals(template.sourceFile())
                ? att.config.YamlSupport.locate(error, sourceFile, sourceField)
                : att.config.YamlSupport.locateText(error, sourceFile, sourceField);
        return located.withLocation(null, null, testCase.sheetName(), testCase.rowNumber(), null,
                template.name(), action.id()).withContext(new DiagnosticContext(caseSource, testCase.caseId(),
                null, null, java.util.Collections.<String>emptyList()));
    }

    private String appendCaseSource(String detail, String caseSource) {
        if (caseSource == null || caseSource.isEmpty()) return detail;
        String suffix = "Case source: " + caseSource;
        return detail == null || detail.trim().isEmpty() ? suffix : detail + "; " + suffix;
    }

    private void validateContextStructure(String text, att.template.UnifiedTemplateEngine engine,
                                          att.core.CaseRuntimeContext context, TestCase testCase,
                                          Set<String> availableActions) {
        validateContextStructure(text, engine, context, testCase, availableActions, null);
    }

    private void validateContextStructure(String text, att.template.UnifiedTemplateEngine engine,
                                          att.core.CaseRuntimeContext context, TestCase testCase,
                                          Set<String> availableActions, String currentActionId) {
        for (String path : engine.parseContextPaths(text)) {
            String referencePath = att.core.CaseRuntimeContext.requiredReferencePath(path);
            if (referencePath.equals("CASE.STAGES") || referencePath.startsWith("CASE.STAGES.")
                    || referencePath.startsWith("CASE.STAGES[")) {
                throw incompatibleContextPath(path, DiagnosticCodes.CONTEXT_CROSS_SCOPE,
                        "CASE.STAGES contains Stage/Template history and evidence, not a supported cross-scope expression namespace.",
                        "Use EXEC.INPUT for the current Stage caller values or EXEC.ACTIONS.<actionId> only when that Action belongs to the current scope. Do not read another Stage's history.");
            }
            String rootBeforeCanonical = firstPathSegment(referencePath);
            if ("LOAD".equals(rootBeforeCanonical)) {
                throw incompatibleContextPath(path, DiagnosticCodes.CONTEXT_LEGACY_PATH,
                        "Root-level LOAD is a pre-release compatibility spelling, not a public Context namespace.",
                        "Use the canonical EXEC.LOAD.* path inside a load iteration.");
            }
            if ("TOOL".equals(rootBeforeCanonical) || "DB".equals(rootBeforeCanonical)) {
                throw incompatibleContextPath(path, DiagnosticCodes.CONTEXT_LEGACY_PATH,
                        "TOOL.* and DB.* are transient helper/runtime views and cannot be used as general 3.4.2 Context APIs.",
                        "Use ${output...} while the Action is active or ${EXEC.ACTIONS.<actionId>...} after publication; use curated META helper identity only for static metadata.");
            }
            if (isActionReference(referencePath)) {
                validateActionReference(path, referencePath, availableActions);
                continue;
            }
            if (referencePath.startsWith("EXEC.ACTIONS.")) {
                String id = firstPathSegment(referencePath.substring("EXEC.ACTIONS.".length()));
                if (!availableActions.contains(id)) throw unavailableActionContext(path, id, availableActions);
            }
            if (referencePath.startsWith("CASE.STAGES.")) {
                String key = firstPathSegment(referencePath.substring("CASE.STAGES.".length()));
                if (!testCase.stages().containsKey(key)) throw new DiagnosticException(DiagnosticCodes.CONTEXT_INVALID,
                        "Unknown Case stage Context '${" + path + "}'",
                        "Stage '" + key + "' is not declared by this Case. Available stage keys: " + String.join(", ", testCase.stages().keySet()),
                        null, path, null, null, null, null, null,
                        "Use a stage key declared by this Case sidecar selector/data mapping.", null);
            }
            String root = firstPathSegment(referencePath);
            if ("EXEC".equals(root)) {
                validateCanonicalPath(path, referencePath);
                String field = firstChildSegment(referencePath, "EXEC");
                try {
                    if (att.core.CaseRuntimeContext.isOptionalReference(path)) context.requireOptional(path);
                    else context.require(referencePath);
                } catch (DiagnosticException e) {
                    if (context.isValidationDeferred(referencePath)) continue;
                    throw e;
                }
                continue;
            }
            if ("META".equals(root)) {
                validateCanonicalPath(path, referencePath);
                String field = firstChildSegment(referencePath, "META");
                if (!("TEMPLATE".equals(field) || "FLOW".equals(field) || "TOOL".equals(field)
                        || "DBHELPER".equals(field) || "MQHELPER".equals(field))) {
                    try {
                        if (att.core.CaseRuntimeContext.isOptionalReference(path)) context.requireOptional(path);
                        else context.require(referencePath);
                    } catch (DiagnosticException e) {
                        if (context.isValidationDeferred(referencePath)) continue;
                        throw e;
                    }
                }
                continue;
            }
            if (!("CASE".equals(root) || "RUN".equals(root) || "ACTIONS".equals(root)
                    || "TOOL".equals(root) || "DB".equals(root) || "output".equals(root))) {
                if (currentActionId != null && currentActionId.equals(root) && !availableActions.contains(root)) {
                    throw unavailableActionContext(path, root, availableActions);
                }
                try {
                    if (att.core.CaseRuntimeContext.isOptionalReference(path)) context.requireOptional(path);
                    else context.require(referencePath);
                }
                catch (DiagnosticException e) {
                    // Only an explicitly deferred value may be unresolved at validation time. Do not
                    // turn arbitrary unknown shorthand paths into deferred values: that masks typos
                    // and causes failures to surface only when a run has already started.
                    if (context.isValidationDeferred(referencePath)) continue;
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
        String value = text == null ? "" : text.trim();
        if (value.startsWith("#{") && value.endsWith("}")) engine.validateExpressionBlockSyntax(value);
        else expressionEvaluator.validateSyntax(engine.maskCalls(text));
    }

    private static final class LocatedValidationException extends IllegalArgumentException {
        private final String template, action, field;
        private LocatedValidationException(String message, String template, String action, String field, Throwable cause) { super(message, cause); this.template = template; this.action = action; this.field = field; }
    }

    private void validateToolCall(String call, FrameworkConfig config) {
        validateToolCall(call, config, true);
    }

    private void validateToolCall(String call, FrameworkConfig config, boolean allowMqPrimary) {
        java.util.List<ToolCallParser.ParsedCall> calls = expressionEngine.parseCalls(call);
        if (calls.isEmpty()) throw new IllegalArgumentException("Tool call must be one exact #{...} expression");
        ToolCallParser.ParsedCall parsed = callParser.parse(call);
        if (parsed.name().startsWith("db.")) {
            throw new IllegalArgumentException("A DB query cannot be the primary call of type: tool; use type: db or an ordinary expression");
        }
        validateCall(parsed, config, allowMqPrimary);
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
        if (toolName.startsWith("mq.")) {
            if (!allowWriteFacade) throw new IllegalArgumentException("MQ operations may only be the primary call of a type: tool Action");
            validateMqCall(parsed, config);
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

    private void validateMqCall(ToolCallParser.ParsedCall parsed, FrameworkConfig config) {
        String[] parts = parsed.name().split("\\.", -1);
        if (parts.length != 3 || !"mq".equals(parts[0]) || parts[1].isEmpty()) {
            throw new IllegalArgumentException("MQ call must be mq.<instance>.send|receive|request: " + parsed.name());
        }
        if (config.mqHelper(parts[1]) == null) {
            throw new IllegalArgumentException("Unknown mqhelper instance '" + parts[1] + "'");
        }
        String operation = parts[2];
        Set<String> allowed = new LinkedHashSet<String>();
        Set<String> required = new LinkedHashSet<String>();
        if ("send".equals(operation)) {
            allowed.add("queue"); allowed.add("file"); required.add("queue"); required.add("file");
        } else if ("receive".equals(operation)) {
            allowed.add("queue"); allowed.add("waitMs"); allowed.add("correlationId"); required.add("queue");
        } else if ("request".equals(operation)) {
            allowed.add("requestQueue"); allowed.add("replyQueue"); allowed.add("file"); allowed.add("waitMs");
            required.add("file");
        } else {
            throw new IllegalArgumentException("Unknown MQ operation '" + operation + "'; use send, receive, or request");
        }
        Set<String> supplied = new LinkedHashSet<String>();
        for (ToolCallParser.Argument argument : parsed.arguments()) {
            if (argument.positional()) throw new IllegalArgumentException(parsed.name() + " requires named arguments");
            if (!allowed.contains(argument.key())) throw new IllegalArgumentException("Unknown MQ argument '" + argument.key() + "' for " + parsed.name());
            if (!supplied.add(argument.key())) throw new IllegalArgumentException("Duplicate MQ argument '" + argument.key() + "'");
            String value = argument.expression().trim();
            boolean dynamic = value.contains("${") || value.contains("#{");
            if (dynamic) continue;
            Object literal = callParser.literal(value);
            if ("waitMs".equals(argument.key())) {
                if (!(literal instanceof Number)) throw new IllegalArgumentException(parsed.name() + ".waitMs must be an integer from 0 to 3600000");
                Number number = (Number) literal;
                if (number.doubleValue() != number.longValue() || number.longValue() < 0 || number.longValue() > 3600000) {
                    throw new IllegalArgumentException(parsed.name() + ".waitMs must be an integer from 0 to 3600000");
                }
            } else {
                if (!(literal instanceof String) || String.valueOf(literal).trim().isEmpty()) {
                    throw new IllegalArgumentException(parsed.name() + "." + argument.key() + " must be a non-blank string");
                }
                if ("queue".equals(argument.key()) || "requestQueue".equals(argument.key()) || "replyQueue".equals(argument.key())) {
                    String queue = String.valueOf(literal);
                    if (!queue.matches("[A-Za-z0-9_.%/-]{1,48}")) throw new IllegalArgumentException("Invalid MQ queue name: " + queue);
                }
            }
        }
        for (String name : required) if (!supplied.contains(name)) throw new IllegalArgumentException("Missing required MQ argument '" + name + "' for " + parsed.name());
        if ("request".equals(operation)) {
            att.config.MqHelperConfig helper = config.mqHelper(parts[1]);
            if (!supplied.contains("requestQueue") && helper.requestQueue().isEmpty()) {
                throw new IllegalArgumentException("Missing requestQueue: supply it in the call or mqhelper.message.requestQueue");
            }
            if (!supplied.contains("replyQueue") && helper.replyQueue().isEmpty()) {
                throw new IllegalArgumentException("Missing replyQueue: supply it in the call or mqhelper.message.replyQueue");
            }
        }
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
        public ValidationSummary(String mode, int suites, int cases, int templates, int tools, List<Diagnostic> diagnostics) {
            this.mode = mode; this.suites = suites; this.cases = cases; this.templates = templates; this.tools = tools;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<Diagnostic>(DiagnosticAggregator.aggregate(diagnostics)));
        }
        public boolean valid() { for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == Diagnostic.Severity.ERROR) return false; return true; }
        public long errors() { return count(Diagnostic.Severity.ERROR); }
        public long warnings() { return count(Diagnostic.Severity.WARNING); }
        public long errorOccurrences() { return occurrences(Diagnostic.Severity.ERROR); }
        public long warningOccurrences() { return occurrences(Diagnostic.Severity.WARNING); }
        private long count(Diagnostic.Severity severity) { long count = 0; for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == severity) count++; return count; }
        private long occurrences(Diagnostic.Severity severity) { long count = 0; for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == severity) count += diagnostic.occurrences(); return count; }
        public String toJson() {
            Map<String,Object> root = new java.util.LinkedHashMap<String,Object>(); root.put("schemaVersion", "att-validation/v2.1"); root.put("attVersion", att.Version.PRODUCT); root.put("valid", valid()); root.put("mode", mode);
            Map<String,Object> summary = new java.util.LinkedHashMap<String,Object>(); summary.put("errors", errors()); summary.put("warnings", warnings());
            if (errorOccurrences() != errors()) summary.put("errorOccurrences", errorOccurrences());
            if (warningOccurrences() != warnings()) summary.put("warningOccurrences", warningOccurrences());
            summary.put("suites", suites); summary.put("cases", cases); summary.put("templates", templates); summary.put("tools", tools); root.put("summary", summary);
            List<Map<String,Object>> items = new java.util.ArrayList<Map<String,Object>>(); for (Diagnostic diagnostic : diagnostics) items.add(diagnostic.toMap()); root.put("diagnostics", items);
            return JsonSupport.write(root);
        }
        @Override public String toString() { return suites + " suites, " + cases + " cases, " + templates + " templates, " + tools + " tools"; }
    }
}
