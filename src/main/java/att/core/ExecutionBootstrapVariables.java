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
    private static final Pattern VARIABLE = Pattern.compile("EXEC\\.VARS\\.([A-Za-z_][A-Za-z0-9_]*)");

    private ExecutionBootstrapVariables() { }

    public static Map<String, Object> validate(Map<String, Object> definitions, UnifiedTemplateEngine engine) {
        return validate(definitions, engine, null, null, "vars", DiagnosticCodes.DEBUG_INVALID);
    }

    /** Validates definitions against statically available inputs and attaches YAML field provenance. */
    public static Map<String, Object> validate(Map<String, Object> definitions, UnifiedTemplateEngine engine,
                                               Map<String, Object> inputs, Path source, String fieldPrefix,
                                               String diagnosticCode) {
        Map<String, Object> vars = definitions == null ? new LinkedHashMap<String, Object>() : definitions;
        Validation validation = new Validation(inputs, source, fieldPrefix, diagnosticCode,
                inputs != null || source != null);
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
        Map<String, Object> vars = validate(definitions, engine);
        Map<String, Set<String>> dependencies = dependencies(vars, engine,
                new Validation(null, null, "vars", DiagnosticCodes.DEBUG_INVALID, false));
        List<String> order = new ArrayList<String>();
        Set<String> visited = new LinkedHashSet<String>();
        Set<String> active = new LinkedHashSet<String>();
        Validation validation = new Validation(null, null, "vars", DiagnosticCodes.DEBUG_INVALID, false);
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
                Matcher matcher = VARIABLE.matcher(path);
                if (matcher.find()) refs.add(matcher.group(1));
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
        String upper = requiredPath.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("EXEC.ACTIONS") || upper.startsWith("EXEC.ACTIONS.") || upper.startsWith("EXEC.ACTIONS[")
                || upper.equals("ACTIONS") || upper.startsWith("ACTIONS.") || upper.startsWith("ACTIONS[")
                || upper.equals("OUTPUT") || upper.startsWith("OUTPUT.") || upper.startsWith("OUTPUT[")
                || upper.startsWith("CASE.ACTIONS") || upper.startsWith("CASE.STAGES")
                || upper.startsWith("META.TOOL") || upper.startsWith("META.DBHELPER")
                || upper.startsWith("META.MQHELPER") || upper.startsWith("META.HTTPHELPER"))
            throw validation.invalid("Context path '" + path + "' is unavailable during execution bootstrap", field);

        boolean inputPath = upper.equals("EXEC.INPUT") || upper.startsWith("EXEC.INPUT.") || upper.startsWith("EXEC.INPUT[");
        boolean allowed = upper.equals("EXEC.RUN_ID") || upper.equals("EXEC.ID") || upper.equals("EXEC.OUTPUT_DIR")
                || upper.equals("EXEC.STARTED_AT") || upper.equals("EXEC.RUN_STARTED_AT")
                || inputPath
                || upper.equals("EXEC.LOAD") || upper.startsWith("EXEC.LOAD.") || upper.startsWith("EXEC.LOAD[")
                || upper.startsWith("EXEC.VARS.")
                || upper.equals("META.PROJECT") || upper.startsWith("META.PROJECT.") || upper.startsWith("META.PROJECT[")
                || upper.equals("META.SOURCE") || upper.startsWith("META.SOURCE.") || upper.startsWith("META.SOURCE[")
                || upper.equals("META.TARGET") || upper.startsWith("META.TARGET.") || upper.startsWith("META.TARGET[")
                || upper.equals("META.TEMPLATE") || upper.startsWith("META.TEMPLATE.") || upper.startsWith("META.TEMPLATE[");
        if (!allowed) throw validation.invalid("Context path '" + path + "' is not an initialized bootstrap root", field);
        boolean optionalInputReference = CaseRuntimeContext.isOptionalReference(path);
        if (inputPath && validation.checkInputReferences && !optionalInputReference
                && !CaseRuntimeContext.containsInputPath(validation.inputs, path))
            throw validation.invalid("Bootstrap expression references missing input '" + path + "'", field);
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

        private Validation(Map<String, Object> inputs, Path source, String fieldPrefix, String diagnosticCode,
                           boolean checkInputReferences) {
            this.inputs = inputs;
            this.source = source;
            this.fieldPrefix = fieldPrefix == null || fieldPrefix.trim().isEmpty() ? "vars" : fieldPrefix;
            this.diagnosticCode = diagnosticCode == null ? DiagnosticCodes.DEBUG_INVALID : diagnosticCode;
            this.checkInputReferences = checkInputReferences;
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
