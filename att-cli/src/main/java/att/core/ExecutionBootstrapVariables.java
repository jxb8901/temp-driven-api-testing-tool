package att.core;

import att.config.YamlSupport;
import att.template.DefaultBuiltInProvider;
import att.template.ToolCallParser;
import att.template.UnifiedTemplateEngine;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves pure, typed EXEC.VARS definitions after execution identity is ready and before actions start. */
public final class ExecutionBootstrapVariables {
    private ExecutionBootstrapVariables() { }

    private static final Pattern INPUT_MAPPING_CONTEXT = Pattern.compile("\\$\\{([^{}]+)}");

    /** Execution mode controls which framework roots are initialized before bootstrap evaluation. */
    public enum Scope { DEBUG, LOAD }

    /** Mode-specific Context roots that have been initialized before input mappings are resolved. */
    public enum InputMappingMode { TESTCASE, DEBUG, LOAD }

    /** Validates every Context reference in an input mapping against its pre-input execution phase. */
    public static void validateInputMapping(Map<String, Object> mapping, UnifiedTemplateEngine engine,
                                            Path source, String fieldPrefix, String diagnosticCode,
                                            InputMappingMode mode, Set<String> availableLoadFields) {
        if (mapping == null) return;
        att.testdata.TestdataSyntax.references(mapping);
        Scope scope = mode == InputMappingMode.LOAD ? Scope.LOAD : Scope.DEBUG;
        Validation validation = new Validation(null, source, fieldPrefix, diagnosticCode,
                false, scope, availableLoadFields, mode);
        validateInputMappingTree(mapping, fieldPrefix == null ? "inputs" : fieldPrefix, engine, validation);
    }

    private static void validateInputMappingTree(Object value, String field, UnifiedTemplateEngine engine,
                                                 Validation validation) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String))
                    throw validation.invalid("Input mapping keys must be strings", field);
                validateInputMappingTree(entry.getValue(), field + "." + entry.getKey(), engine, validation);
            }
        } else if (value instanceof Iterable) {
            int index = 0;
            for (Object child : (Iterable<?>) value)
                validateInputMappingTree(child, field + "[" + index++ + "]", engine, validation);
        } else if (value instanceof String) {
            String text = (String) value;
            Matcher matcher = INPUT_MAPPING_CONTEXT.matcher(text);
            while (matcher.find()) {
                String expression = "${" + matcher.group(1) + "}";
                for (String path : parsePaths(engine, expression, field, validation))
                    validatePath(path, field, validation, validation.inputMappingMode);
            }
        }
    }

    public static Map<String, Object> validate(Map<String, Object> definitions, UnifiedTemplateEngine engine) {
        return validate(definitions, engine, null, null, "vars", DiagnosticCodes.DEBUG_INVALID, Scope.DEBUG);
    }

    /** Validates definitions against statically available inputs and attaches YAML field provenance. */
    public static Map<String, Object> validate(Map<String, Object> definitions, UnifiedTemplateEngine engine,
                                               Map<String, Object> inputs, Path source, String fieldPrefix,
                                               String diagnosticCode) {
        return validate(definitions, engine, inputs, source, fieldPrefix, diagnosticCode, Scope.DEBUG);
    }

    /** Validates definitions against the initialized roots available to the selected execution mode. */
    public static Map<String, Object> validate(Map<String, Object> definitions, UnifiedTemplateEngine engine,
                                               Map<String, Object> inputs, Path source, String fieldPrefix,
                                               String diagnosticCode, Scope scope) {
        return validate(definitions, engine, inputs, source, fieldPrefix, diagnosticCode, scope, null);
    }

    /** Validates Load references against the fields guaranteed by the selected workload. */
    public static Map<String, Object> validate(Map<String, Object> definitions, UnifiedTemplateEngine engine,
                                               Map<String, Object> inputs, Path source, String fieldPrefix,
                                               String diagnosticCode, Scope scope, Set<String> availableLoadFields) {
        Map<String, Object> vars = definitions == null ? new LinkedHashMap<String, Object>() : definitions;
        Validation validation = new Validation(inputs, source, fieldPrefix, diagnosticCode,
                inputs != null || source != null, scope, availableLoadFields);
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String name = entry.getKey();
            String field = validation.variableField(name);
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw validation.invalid("Invalid bootstrap variable name '" + name + "'", field);
            validateTree(entry.getValue(), field, engine, validation);
        }
        Map<String, Set<String>> graph = dependencies(vars, engine, validation);
        List<String> order = new ArrayList<String>();
        Set<String> visited = new LinkedHashSet<String>();
        Set<String> active = new LinkedHashSet<String>();
        for (String name : vars.keySet()) visit(name, graph, visited, active, order, validation);
        return vars;
    }

    public static void evaluate(Map<String, Object> definitions, CaseRuntimeContext context,
                                UnifiedTemplateEngine engine) throws Exception {
        evaluate(definitions, context, engine, Scope.DEBUG);
    }

    public static void evaluate(Map<String, Object> definitions, CaseRuntimeContext context,
                                UnifiedTemplateEngine engine, Scope scope) throws Exception {
        Map<String, Object> vars = validate(definitions, engine, null, null, "vars",
                DiagnosticCodes.DEBUG_INVALID, scope);
        Map<String, Set<String>> dependencies = dependencies(vars, engine,
                new Validation(null, null, "vars", DiagnosticCodes.DEBUG_INVALID, false, scope, null));
        List<String> order = new ArrayList<String>();
        Set<String> visited = new LinkedHashSet<String>();
        Set<String> active = new LinkedHashSet<String>();
        Validation validation = new Validation(null, null, "vars", DiagnosticCodes.DEBUG_INVALID, false, scope, null);
        for (String name : vars.keySet()) visit(name, dependencies, visited, active, order, validation);

        Set<String> published = new LinkedHashSet<String>();
        try {
            for (String name : order) {
                Object value = engine.evaluateTypedTree(vars.get(name), context, null);
                context.seedExecutionBootstrapVariable(name, value);
                published.add(name);
            }
        } catch (Exception failure) {
            context.clearExecutionBootstrapVariables(published);
            throw failure;
        }
    }

    private static Map<String, Set<String>> dependencies(Map<String, Object> vars, UnifiedTemplateEngine engine,
                                                          Validation validation) {
        Map<String, Set<String>> graph = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            Set<String> refs = new LinkedHashSet<String>();
            collectReferences(entry.getValue(), refs, engine, validation.variableField(entry.getKey()), validation);
            for (String ref : refs) if (!vars.containsKey(ref))
                throw validation.invalid("Bootstrap variable '" + entry.getKey()
                        + "' references missing EXEC.VARS." + ref, validation.variableField(entry.getKey()));
            graph.put(entry.getKey(), refs);
        }
        return graph;
    }

    private static void visit(String name, Map<String, Set<String>> graph, Set<String> visited,
                              Set<String> active, List<String> order, Validation validation) {
        if (visited.contains(name)) return;
        if (!active.add(name)) {
            List<String> path = new ArrayList<String>(active);
            List<String> cycle = new ArrayList<String>(path.subList(path.indexOf(name), path.size()));
            cycle.add(name);
            throw validation.invalid("Bootstrap variable dependency cycle: " + join(cycle),
                    validation.variableField(name));
        }
        for (String dependency : graph.get(name)) visit(dependency, graph, visited, active, order, validation);
        active.remove(name);
        visited.add(name);
        order.add(name);
    }

    private static void collectReferences(Object value, Set<String> refs, UnifiedTemplateEngine engine,
                                          String field, Validation validation) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> child : ((Map<?, ?>) value).entrySet()) {
                if (!(child.getKey() instanceof String)) throw validation.invalid("Bootstrap map keys must be strings", field);
                collectReferences(child.getValue(), refs, engine, field + "." + child.getKey(), validation);
            }
        } else if (value instanceof Iterable) {
            int index = 0;
            for (Object child : (Iterable<?>) value) collectReferences(child, refs, engine, field + "[" + index++ + "]", validation);
        } else if (value instanceof String) {
            for (String path : parsePaths(engine, (String) value, field, validation)) {
                validatePath(path, field, validation);
                String variable = CaseRuntimeContext.executionVariableName(path);
                if (variable != null) refs.add(variable);
            }
        }
    }

    private static void validateTree(Object value, String field, UnifiedTemplateEngine engine, Validation validation) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw validation.invalid("Bootstrap map keys must be strings", field);
                validateTree(entry.getValue(), field + "." + entry.getKey(), engine, validation);
            }
        } else if (value instanceof Iterable) {
            int index = 0;
            for (Object child : (Iterable<?>) value) validateTree(child, field + "[" + index++ + "]", engine, validation);
        } else if (value instanceof String) {
            String text = (String) value;
            for (String path : parsePaths(engine, text, field, validation)) validatePath(path, field, validation);
            try {
                for (ToolCallParser.ParsedCall call : engine.parseCalls(text)) {
                    DefaultBuiltInProvider.rejectRemoved(call.name());
                    if (!DefaultBuiltInProvider.isSafeForBootstrap(call.name()))
                        throw validation.invalid("Bootstrap expressions may call only safe built-ins; rejected call '"
                                + call.name() + "'", field);
                }
            } catch (DiagnosticException error) { throw error; }
            catch (RuntimeException error) {
                throw validation.invalid("Invalid bootstrap expression: " + error.getMessage(), field);
            }
        } else if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
            throw validation.invalid("Unsupported bootstrap value type " + value.getClass().getName(), field);
        }
    }

    private static List<String> parsePaths(UnifiedTemplateEngine engine, String text, String field,
                                           Validation validation) {
        try { return engine.parseContextPaths(text); }
        catch (RuntimeException error) {
            throw validation.invalid((validation.inputMappingMode == null
                    ? "Invalid bootstrap expression: " : "Invalid input mapping expression: ")
                    + error.getMessage(), field);
        }
    }

    private static void validatePath(String path, String field, Validation validation) {
        validatePath(path, field, validation, null);
    }

    private static void validatePath(String path, String field, Validation validation,
                                     InputMappingMode inputMappingMode) {
        if (path == null || path.trim().isEmpty())
            throw validation.invalid("Bootstrap expression contains an empty Context path", field);
        String requiredPath;
        try { requiredPath = CaseRuntimeContext.requiredReferencePath(path); }
        catch (RuntimeException error) {
            throw validation.invalid("Invalid optional Context path '" + path + "': " + error.getMessage(), field);
        }
        final List<CaseRuntimeContext.Segment> segments;
        try { segments = CaseRuntimeContext.parsePath(requiredPath); }
        catch (RuntimeException error) {
            throw validation.invalid("Invalid Context path '" + path + "': " + error.getMessage(), field);
        }
        if (segments.isEmpty())
            throw validation.invalid("Bootstrap expression contains an empty Context path", field);

        String root = keyAt(segments, 0);
        String child = keyAt(segments, 1);
        if (("EXEC".equals(root) && "ACTIONS".equals(child))
                || "ACTIONS".equals(root) || "output".equals(root)
                || ("CASE".equals(root) && ("ACTIONS".equals(child) || "STAGES".equals(child)))
                || ("META".equals(root) && isOneOf(child, "TOOL", "DBHELPER", "MQHELPER", "HTTPHELPER")))
            throw validation.invalid("Context path '" + path + "' is unavailable during "
                    + (inputMappingMode == null ? "execution bootstrap" : "input mapping"), field);

        boolean inputPath = false;
        boolean loadPath = false;
        boolean allowed = false;
        if ("EXEC".equals(root) && child != null) {
            if ("INPUT".equals(child)) {
                inputPath = true;
                allowed = inputMappingMode == null;
            } else if ("VARS".equals(child)) {
                allowed = inputMappingMode == null && segments.size() >= 3 && keyAt(segments, 2) != null;
            } else if ("LOAD".equals(child)) {
                allowed = validation.scope == Scope.LOAD
                        && (inputMappingMode == null || inputMappingMode == InputMappingMode.LOAD);
                loadPath = allowed;
            } else if (isOneOf(child, "ID", "RUN_ID", "OUTPUT_DIR", "STARTED_AT", "RUN_STARTED_AT")) {
                allowed = segments.size() == 2;
                if (inputMappingMode == InputMappingMode.LOAD
                        && isOneOf(child, "ID", "OUTPUT_DIR")) allowed = false;
            }
        } else if ("META".equals(root) && isOneOf(child, "PACKAGE_ROOT", "SOURCE", "TARGET", "TEMPLATE")) {
            allowed = "PACKAGE_ROOT".equals(child) ? segments.size() == 2
                    : inputMappingMode != InputMappingMode.TESTCASE || !"TEMPLATE".equals(child);
        }
        if (!allowed) throw validation.invalid(inputMappingMode == null
                ? "Context path '" + path + "' is not an initialized bootstrap root"
                : "Context path '" + path + "' is not initialized before input mapping", field);
        if (inputPath && validation.checkInputReferences) {
            CaseRuntimeContext.InputPathStatus status = CaseRuntimeContext.probeInputPath(validation.inputs, path);
            boolean optional = CaseRuntimeContext.isOptionalReference(path);
            if (status == CaseRuntimeContext.InputPathStatus.INVALID_PATH)
                throw validation.invalid("Bootstrap expression references structurally invalid input path '"
                        + path + "'", field);
            if (!optional && status != CaseRuntimeContext.InputPathStatus.FOUND)
                throw validation.invalid("Bootstrap expression references missing input '" + path + "'", field);
        }
        if (loadPath) validateLoadPath(segments, path, field, validation);
    }

    private static void validateLoadPath(List<CaseRuntimeContext.Segment> segments, String path, String field,
                                         Validation validation) {
        if (segments.size() == 2) return; // EXEC.LOAD is the initialized identity map itself.
        CaseRuntimeContext.Segment selector = segments.get(2);
        if (selector.index != null)
            throw validation.invalid("Bootstrap expression contains an invalid selector under EXEC.LOAD in '"
                    + path + "'", field);
        String loadField = selector.key;
        boolean known = CaseRuntimeContext.isLoadContextField(loadField);
        boolean optional = CaseRuntimeContext.isOptionalReference(path);
        if (!known) {
            if (!optional) throw validation.invalid("Bootstrap expression references missing Load field '"
                    + path + "'", field);
            return; // Optional Context lookup turns a missing map key into null.
        }
        if (validation.availableLoadFields != null && !validation.availableLoadFields.contains(loadField)) {
            if (!optional) throw validation.invalid("Bootstrap expression references unavailable Load field '"
                    + path + "'", field);
            return; // For example, USER_ID is absent in arrival-rate workloads.
        }
        if (segments.size() > 3)
            throw validation.invalid("Bootstrap expression contains structurally invalid Load path '"
                    + path + "'", field);
    }

    private static String keyAt(List<CaseRuntimeContext.Segment> segments, int index) {
        if (index < 0 || index >= segments.size()) return null;
        CaseRuntimeContext.Segment segment = segments.get(index);
        return segment.index == null ? segment.key : null;
    }

    private static boolean isOneOf(String value, String... choices) {
        if (value == null) return false;
        for (String choice : choices) if (choice.equals(value)) return true;
        return false;
    }

    private static String join(List<String> names) {
        StringBuilder result = new StringBuilder();
        for (String name : names) { if (result.length() > 0) result.append(" -> "); result.append(name); }
        return result.toString();
    }

    private static final class Validation {
        private final Map<String, Object> inputs;
        private final Path source;
        private final String fieldPrefix;
        private final String diagnosticCode;
        private final boolean checkInputReferences;
        private final Scope scope;
        private final Set<String> availableLoadFields;
        private final InputMappingMode inputMappingMode;

        private Validation(Map<String, Object> inputs, Path source, String fieldPrefix, String diagnosticCode,
                           boolean checkInputReferences, Scope scope, Set<String> availableLoadFields) {
            this(inputs, source, fieldPrefix, diagnosticCode, checkInputReferences, scope,
                    availableLoadFields, null);
        }

        private Validation(Map<String, Object> inputs, Path source, String fieldPrefix, String diagnosticCode,
                           boolean checkInputReferences, Scope scope, Set<String> availableLoadFields,
                           InputMappingMode inputMappingMode) {
            this.inputs = inputs;
            this.source = source;
            this.fieldPrefix = fieldPrefix == null || fieldPrefix.trim().isEmpty() ? "vars" : fieldPrefix;
            this.diagnosticCode = diagnosticCode == null ? DiagnosticCodes.DEBUG_INVALID : diagnosticCode;
            this.checkInputReferences = checkInputReferences;
            this.scope = scope == null ? Scope.DEBUG : scope;
            this.availableLoadFields = availableLoadFields;
            this.inputMappingMode = inputMappingMode;
        }

        private String variableField(String name) { return fieldPrefix + "." + name; }

        private DiagnosticException invalid(String message, String field) {
            DiagnosticException error = new DiagnosticException(diagnosticCode,
                    inputMappingMode == null ? "Invalid execution bootstrap variables" : "Invalid testdata input mapping Context",
                    message + " (" + field + ")", source == null ? null : source.toString(), field,
                    null, null, null, null, null,
                    inputMappingMode == null
                            ? "Correct the bootstrap value or reference at the reported field before starting execution."
                            : "Correct the Context reference at the reported input mapping field before starting execution.", null);
            return source == null ? error : YamlSupport.locate(error, source, field);
        }
    }
}
