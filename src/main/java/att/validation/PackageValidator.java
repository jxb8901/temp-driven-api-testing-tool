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
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.TemplateAction;
import att.template.ToolCallParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Collections;
import java.util.stream.Stream;

/** Performs V2 package/suite/template/tool validation without running tools. */
public final class PackageValidator {
    private final ToolCallParser callParser = new ToolCallParser();
    private final att.template.ExpressionEvaluator expressionEvaluator = new att.template.ExpressionEvaluator();
    private static final Set<String> BUILT_INS = new LinkedHashSet<String>(java.util.Arrays.asList("upper", "lower", "trim", "string", "number", "boolean", "length", "concat", "coalesce"));
    private final Path projectRoot;
    private final FrameworkConfig global;

    public PackageValidator(Path projectRoot, FrameworkConfig global) { this.projectRoot = projectRoot; this.global = global; }

    public ValidationSummary validate(ExecutionOptions options) throws Exception {
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
        SuiteConfigResolver resolver = new SuiteConfigResolver(projectRoot, global);
        StageTemplateLoader loader;
        try { loader = new StageTemplateLoader(projectRoot, global.templatesRoot()); }
        catch (Exception e) { return invalid(options.validationScope(), Collections.singletonList(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, global.templatesRoot()))); }
        if ("package".equals(options.validationScope())) {
            validatePackageLayout(diagnostics);
            for (String reference : loader.paths()) try { templates.add(reference); StageTemplate template = loader.load(reference); validateTemplate(template, global); }
            catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, projectRoot.resolve(global.templatesRoot()).resolve(reference).resolve("template.yaml"))); }
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
                List<TestCase> loaded = new ExcelTestSuiteLoader(config).load(resolved);
                for (TestCase testCase : loaded) {
                    if ("selected".equals(options.validationScope()) && !options.matches(testCase)) continue;
                    cases++;
                    for (StageCaseData stage : testCase.stages().values()) {
                        try {
                            StageTemplate template = loader.load(stage.templateName());
                            if ("package".equals(options.validationScope())) continue;
                            if (!templates.add(template.name())) continue;
                            validateTemplate(template, config);
                        } catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TEMPLATE_INVALID, e, resolved)); }
                    }
                }
            } catch (Exception e) {
                diagnostics.add(diagnostic(DiagnosticCodes.TESTCASE_INVALID, e, suite));
            }
        }
        if (cases == 0) diagnostics.add(new Diagnostic(DiagnosticCodes.SELECTION_EMPTY, Diagnostic.Severity.ERROR, "Case selection is empty", null, null, null, null, null, null));
        if ("selected".equals(options.validationScope())) diagnostics.add(new Diagnostic(DiagnosticCodes.SELECTED_SCOPE, Diagnostic.Severity.INFO, "Only the selected dependency closure was validated; unselected package content was not validated", null, null, null, null, null, null));
        Collections.sort(diagnostics);
        return new ValidationSummary(options.validationScope(), suites.size(), cases, templates.size(), global.tools().size(), diagnostics);
    }

    @SuppressWarnings("unchecked")
    private void validatePackageLayout(List<Diagnostic> diagnostics) {
        for (String required : new String[]{"config/config.yaml", "testcase", "templates", "tools", "schemas/catalog.yaml", "att.sh"}) {
            Path path = projectRoot.resolve(required);
            if (!Files.exists(path)) diagnostics.add(diagnostic(DiagnosticCodes.PACKAGE_INVALID, new IllegalArgumentException("Missing required package path: " + required), path));
        }
        Path catalog = projectRoot.resolve("schemas/catalog.yaml");
        if (Files.isRegularFile(catalog)) try {
            Object loaded = att.config.YamlSupport.parser().load(new String(Files.readAllBytes(catalog), java.nio.charset.StandardCharsets.UTF_8));
            if (!(loaded instanceof Map) || !"att-schema-catalog/v2.1".equals(String.valueOf(((Map<?, ?>) loaded).get("schemaVersion")))) throw new IllegalArgumentException("Invalid schema catalog version");
            Object schemas = ((Map<?, ?>) loaded).get("schemas"); if (!(schemas instanceof Map)) throw new IllegalArgumentException("Schema catalog requires schemas map");
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            json.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            for (Object value : ((Map<?, ?>) schemas).values()) {
                Path schema = projectRoot.resolve("schemas").resolve(String.valueOf(value)).normalize();
                if (!schema.startsWith(projectRoot.resolve("schemas").normalize()) || !Files.isRegularFile(schema) || Files.isSymbolicLink(schema)) throw new IllegalArgumentException("Missing/unsafe schema file: " + value);
                if (schema.getFileName().toString().endsWith(".json")) json.readTree(Files.readAllBytes(schema));
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
        for (ToolConfig tool : global.tools().values()) {
            try {
                java.util.List<String> command = att.exec.CommandRunner.parseCommand(tool.command());
                if (command.isEmpty()) throw new IllegalArgumentException("Tool command is blank: " + tool.key());
                String first = command.get(0);
                if (first.startsWith("./") || first.startsWith("../")) {
                    Path executable = projectRoot.resolve(first).normalize();
                    if (!executable.startsWith(projectRoot.normalize()) || Files.isSymbolicLink(executable) || !Files.isRegularFile(executable)) throw new IllegalArgumentException("Missing/unsafe package-local tool executable: " + first);
                    if (!Files.isExecutable(executable)) throw new IllegalArgumentException("Package-local tool is not executable: " + first);
                }
            } catch (Exception e) { diagnostics.add(diagnostic(DiagnosticCodes.TOOL_INVALID, e, null)); }
        }
    }

    private ValidationSummary invalid(String mode, List<Diagnostic> diagnostics) { return new ValidationSummary(mode, 0, 0, 0, global.tools().size(), diagnostics); }
    private Diagnostic diagnostic(String code, Exception exception, Path file) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        LocatedValidationException located = exception instanceof LocatedValidationException ? (LocatedValidationException) exception : null;
        return new Diagnostic(code, Diagnostic.Severity.ERROR, message, file == null ? null : projectRoot.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'), located == null ? null : located.field, null, null, null, located == null ? null : located.template, located == null ? null : located.action, null);
    }

    private void validateTemplate(StageTemplate template, FrameworkConfig config) {
        Set<String> actionIds = new LinkedHashSet<String>();
        for (TemplateAction action : template.actions()) {
          try {
            if (action.id().contains(".")) throw new IllegalArgumentException("Action ID must not contain '.': " + action.id());
            if (!actionIds.add(action.id())) throw new IllegalArgumentException("Duplicate Action ID: " + action.id());
            String type = action.type().toLowerCase(java.util.Locale.ROOT);
            if (!("render".equals(type) || "tool".equals(type) || "assert".equals(type) || "log".equals(type))) throw new IllegalArgumentException("Unsupported action type: " + action.type());
            if ("render".equals(type)) {
                require(action.payload(), "payload is required for render action " + action.id());
                forbid(action, "call", "expression", "message", "level", "fields", "retry", "timeoutMs");
                Path payload = template.directory().resolve(att.core.IdentifierValidator.relativePath(action.payload(), "render payload")).normalize();
                Path canonicalTemplate = template.directory().toRealPath(), canonicalPayload;
                try { canonicalPayload = payload.toRealPath(); } catch (java.io.IOException e) { throw new IllegalArgumentException("Missing render payload: " + action.payload()); }
                if (!canonicalPayload.startsWith(canonicalTemplate) || Files.isSymbolicLink(payload) || !Files.isRegularFile(canonicalPayload)) throw new IllegalArgumentException("Missing/unsafe render payload: " + action.payload());
                String mode = action.output().get("mode") == null ? "file" : String.valueOf(action.output().get("mode"));
                att.config.SchemaSupport.rejectUnknown(action.output(), "actions." + action.id() + ".output", "mode");
                if (!"file".equals(mode)) throw new IllegalArgumentException("render output.mode must be file: " + action.id());
                require(action.saveAs(), "saveAs is required for render action " + action.id());
                att.core.IdentifierValidator.relativePath(action.saveAs(), "render saveAs");
            }
            if ("tool".equals(type)) { require(action.call(), "call is required for tool action " + action.id()); forbid(action, "payload", "saveAs", "expression", "message", "level", "fields"); if (action.timeoutMs() != null && (action.timeoutMs() < 1 || action.timeoutMs() > 86400000)) throw new IllegalArgumentException("timeoutMs must be 1..86400000: " + action.id()); validateRetry(action); validateToolCall(action.call(), config); }
            if ("assert".equals(type)) { require(action.expression(), "expression is required for assert action " + action.id()); forbid(action, "payload", "saveAs", "call", "message", "level", "fields", "retry", "timeoutMs"); expressionEvaluator.validateSyntax(action.expression()); }
            if ("log".equals(type)) {
                require(action.message(), "message is required for log action " + action.id());
                if (!("TRACE".equals(action.level()) || "DEBUG".equals(action.level()) || "INFO".equals(action.level()) || "WARN".equals(action.level()) || "ERROR".equals(action.level()))) throw new IllegalArgumentException("Invalid log level: " + action.level());
                forbid(action, "payload", "saveAs", "call", "expression", "retry", "timeoutMs");
                for (Object key : action.fields().keySet()) if (!(key instanceof String)) throw new IllegalArgumentException("Log fields keys must be strings: " + action.id());
            }
          } catch (LocatedValidationException e) { throw e; }
          catch (Exception e) { throw new LocatedValidationException(e.getMessage(), template.name(), action.id(), "actions." + action.id(), e); }
        }
    }

    private static void require(String value, String message) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message); }
    private static void forbid(TemplateAction action, String... fields) {
        for (String field : fields) if (action.raw().containsKey(field)) throw new IllegalArgumentException("Field '" + field + "' is forbidden for " + action.type() + " action " + action.id());
    }
    private static void validateRetry(TemplateAction action) {
        Map<String, Object> retry = action.retry(); if (retry.isEmpty()) return;
        att.config.SchemaSupport.rejectUnknown(retry, "actions." + action.id() + ".retry", "maxAttempts", "retryOn", "exitCodes");
        int attempts = integer(retry.get("maxAttempts"), 1); if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("retry.maxAttempts must be 1..10: " + action.id());
        Object retryOn = retry.get("retryOn"); if (!(retryOn instanceof Iterable)) throw new IllegalArgumentException("retry.retryOn must be a list: " + action.id());
        boolean includesExitCode = false;
        for (Object category : (Iterable<?>) retryOn) { if (!(category instanceof String)) throw new IllegalArgumentException("retry.retryOn values must be strings"); includesExitCode |= "EXIT_CODE".equals(category); if (!("EXIT_CODE".equals(category) || "OUTPUT_PARSE".equals(category) || "IO_ERROR".equals(category))) throw new IllegalArgumentException("Unknown retry category: " + category); }
        if (retry.get("exitCodes") != null) {
            if (!includesExitCode) throw new IllegalArgumentException("retry.exitCodes requires EXIT_CODE in retryOn");
            if (!(retry.get("exitCodes") instanceof Iterable)) throw new IllegalArgumentException("retry.exitCodes must be a list");
            for (Object code : (Iterable<?>) retry.get("exitCodes")) if (!(code instanceof Number) || ((Number) code).intValue() < 1 || ((Number) code).intValue() > 255) throw new IllegalArgumentException("retry.exitCodes values must be integers between 1 and 255");
        }
    }
    private static int integer(Object value, int fallback) { if (value == null) return fallback; if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer retry value"); return ((Number) value).intValue(); }

    private static final class LocatedValidationException extends IllegalArgumentException {
        private final String template, action, field;
        private LocatedValidationException(String message, String template, String action, String field, Throwable cause) { super(message, cause); this.template = template; this.action = action; this.field = field; }
    }

    private void validateToolCall(String call, FrameworkConfig config) {
        ToolCallParser.ParsedCall parsed = callParser.parse(call);
        String toolName = parsed.name();
        if (BUILT_INS.contains(toolName.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Tool action requires a configured external tool, not built-in function: " + toolName);
        ToolConfig tool = config.tool(toolName);
        if (tool == null) throw new IllegalArgumentException("Unknown tool in template: " + toolName);
        Set<String> supplied = new LinkedHashSet<String>();
        for (ToolCallParser.Argument argument : parsed.arguments()) {
            if (argument.positional()) throw new IllegalArgumentException("Configured tools require named arguments: " + argument.expression());
            String key = argument.key();
            if (!supplied.add(key)) throw new IllegalArgumentException("Duplicate tool argument: " + key);
            if (!tool.arguments().containsKey(key)) throw new IllegalArgumentException("Unknown argument '" + key + "' for tool " + toolName);
        }
        for (ToolArgumentConfig argument : tool.arguments().values()) if (argument.required() && !supplied.contains(argument.key())) throw new IllegalArgumentException("Missing required argument '" + argument.key() + "' for tool " + toolName);
    }

    private List<Path> suites(ExecutionOptions options) throws Exception {
        List<Path> result = new ArrayList<Path>();
        if (options.suiteDirectory() != null) {
            Path directory = options.suiteDirectory().isAbsolute() ? options.suiteDirectory() : projectRoot.resolve(options.suiteDirectory());
            try (Stream<Path> stream = Files.list(directory)) { stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx")).sorted().forEach(result::add); }
        } else result.addAll(options.suitePaths());
        if (result.isEmpty()) throw new IllegalArgumentException("No Excel suites selected");
        return result;
    }

    public static final class ValidationSummary {
        public final int suites, cases, templates, tools;
        public final String mode;
        public final List<Diagnostic> diagnostics;
        ValidationSummary(String mode, int suites, int cases, int templates, int tools, List<Diagnostic> diagnostics) { this.mode = mode; this.suites = suites; this.cases = cases; this.templates = templates; this.tools = tools; this.diagnostics = Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics)); }
        public boolean valid() { for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == Diagnostic.Severity.ERROR) return false; return true; }
        public long errors() { return count(Diagnostic.Severity.ERROR); }
        public long warnings() { return count(Diagnostic.Severity.WARNING); }
        private long count(Diagnostic.Severity severity) { long count = 0; for (Diagnostic diagnostic : diagnostics) if (diagnostic.severity() == severity) count++; return count; }
        public String toJson() {
            StringBuilder output = new StringBuilder("{\"schemaVersion\":\"att-validation/v2.1\",\"attVersion\":\"").append(att.Version.PRODUCT).append("\",\"valid\":").append(valid()).append(",\"mode\":\"").append(mode).append("\",\"summary\":{\"errors\":").append(errors()).append(",\"warnings\":").append(warnings()).append(",\"suites\":").append(suites).append(",\"cases\":").append(cases).append(",\"templates\":").append(templates).append(",\"tools\":").append(tools).append("},\"diagnostics\":[");
            for (int i = 0; i < diagnostics.size(); i++) { if (i > 0) output.append(','); output.append(diagnostics.get(i).toJson()); }
            return output.append("]}").toString();
        }
        @Override public String toString() { return suites + " suites, " + cases + " cases, " + templates + " templates, " + tools + " tools"; }
    }
}
