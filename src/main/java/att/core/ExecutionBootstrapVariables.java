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

/** Resolves pure, typed EXEC.VARS definitions after execution identity is ready and before actions start. */
public final class ExecutionBootstrapVariables {
    private ExecutionBootstrapVariables() { }

    /** Execution mode controls which framework roots are initialized before bootstrap evaluation. */
    public enum Scope { DEBUG, LOAD }

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
        Map<String, Object> vars = definitions == null ? new LinkedHashMap<String, Object>() : definitions;
        Validation validation = new Validation(inputs, source, fieldPrefix, diagnosticCode,
                inputs != null || source != null, scope);
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
                new Validation(null, null, "vars", DiagnosticCodes.DEBUG_INVALID, false, scope));
        List<String> order = new ArrayList<String>();
        Set<String> visited = new LinkedHashSet<String>();
        Set<String> active = new LinkedHashSet<String>();
        Validation validation = new Validation(null, null, "vars", DiagnosticCodes.DEBUG_INVALID, false, scope);
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
            throw validation.invalid("Invalid bootstrap expression: " + error.getMessage(), field);
        }
    }

    private static void validatePath(String path, String field, Validation validation) {
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
            throw validation.invalid("Context path '" + path + "' is unavailable during execution bootstrap", field);

        boolean inputPath = false;
        boolean allowed = false;
        if ("EXEC".equals(root) && child != null) {
            if ("INPUT".equals(child)) {
                inputPath = true;
                allowed = true;
            } else if ("VARS".equals(child)) {
                allowed = segments.size() >= 3 && keyAt(segments, 2) != null;
            } else if ("LOAD".equals(child)) {
                allowed = validation.scope == Scope.LOAD;
            } else if (isOneOf(child, "ID", "RUN_ID", "OUTPUT_DIR", "STARTED_AT", "RUN_STARTED_AT")) {
                allowed = segments.size() == 2;
            }
        } else if ("META".equals(root) && isOneOf(child, "PROJECT", "SOURCE", "TARGET", "TEMPLATE")) {
            allowed = true;
        }
        if (!allowed) throw validation.invalid("Context path '" + path + "' is not an initialized bootstrap root", field);
        if (inputPath && validation.checkInputReferences) {
            CaseRuntimeContext.InputPathStatus status = CaseRuntimeContext.probeInputPath(validation.inputs, path);
            boolean optional = CaseRuntimeContext.isOptionalReference(path);
            if (status == CaseRuntimeContext.InputPathStatus.INVALID_PATH)
                throw validation.invalid("Bootstrap expression references structurally invalid input path '"
                        + path + "'", field);
            if (!optional && status != CaseRuntimeContext.InputPathStatus.FOUND)
                throw validation.invalid("Bootstrap expression references missing input '" + path + "'", field);
        }
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

        private Validation(Map<String, Object> inputs, Path source, String fieldPrefix, String diagnosticCode,
                           boolean checkInputReferences, Scope scope) {
            this.inputs = inputs;
            this.source = source;
            this.fieldPrefix = fieldPrefix == null || fieldPrefix.trim().isEmpty() ? "vars" : fieldPrefix;
            this.diagnosticCode = diagnosticCode == null ? DiagnosticCodes.DEBUG_INVALID : diagnosticCode;
            this.checkInputReferences = checkInputReferences;
            this.scope = scope == null ? Scope.DEBUG : scope;
        }

        private String variableField(String name) { return fieldPrefix + "." + name; }

        private DiagnosticException invalid(String message, String field) {
            DiagnosticException error = new DiagnosticException(diagnosticCode, "Invalid execution bootstrap variables",
                    message + " (" + field + ")", source == null ? null : source.toString(), field,
                    null, null, null, null, null,
                    "Correct the bootstrap value or reference at the reported field before starting execution.", null);
            return source == null ? error : YamlSupport.locate(error, source, field);
        }
    }
}
