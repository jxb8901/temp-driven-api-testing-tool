package att.core;

import att.template.ToolCallParser;
import att.template.UnifiedTemplateEngine;

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
    private static final Set<String> PURE_BUILT_INS = new LinkedHashSet<String>(java.util.Arrays.asList(
            "upper", "lower", "trim", "ltrim", "rtrim", "string", "number", "boolean", "length",
            "concat", "coalesce", "nvl", "iif", "nchar", "substr", "indexof", "contains", "startswith",
            "endswith", "replace", "padleft", "padright", "dateadd", "formatdate", "dbtext", "prettyprint",
            "format", "str.upper", "str.lower", "str.trim", "str.ltrim", "str.rtrim", "str.length",
            "str.concat", "str.substr", "str.indexof", "str.contains", "str.startswith", "str.endswith",
            "str.replace", "str.lpad", "str.rpad", "str.repeat", "date.add", "date.format"));

    private ExecutionBootstrapVariables() { }

    public static Map<String, Object> validate(Map<String, Object> definitions, UnifiedTemplateEngine engine) {
        Map<String, Object> vars = definitions == null ? new LinkedHashMap<String, Object>() : definitions;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String name = entry.getKey();
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw invalid("Invalid bootstrap variable name '" + name + "'", "vars." + name);
            validateTree(entry.getValue(), "vars." + name, engine);
        }
        Map<String, Set<String>> graph = dependencies(vars, engine);
        List<String> order = new ArrayList<String>();
        Set<String> visited = new LinkedHashSet<String>();
        Set<String> active = new LinkedHashSet<String>();
        for (String name : vars.keySet()) visit(name, graph, visited, active, order);
        return vars;
    }

    public static void evaluate(Map<String, Object> definitions, CaseRuntimeContext context,
                                UnifiedTemplateEngine engine) throws Exception {
        Map<String, Object> vars = validate(definitions, engine);
        Map<String, Set<String>> dependencies = dependencies(vars, engine);
        List<String> order = new ArrayList<String>();
        Set<String> visited = new LinkedHashSet<String>();
        Set<String> active = new LinkedHashSet<String>();
        for (String name : vars.keySet()) visit(name, dependencies, visited, active, order);

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

    private static Map<String, Set<String>> dependencies(Map<String, Object> vars, UnifiedTemplateEngine engine) {
        Map<String, Set<String>> graph = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            Set<String> refs = new LinkedHashSet<String>();
            collectReferences(entry.getValue(), refs, engine);
            for (String ref : refs) if (!vars.containsKey(ref))
                throw invalid("Bootstrap variable '" + entry.getKey() + "' references missing EXEC.VARS." + ref,
                        "vars." + entry.getKey());
            graph.put(entry.getKey(), refs);
        }
        return graph;
    }

    private static void visit(String name, Map<String, Set<String>> graph, Set<String> visited,
                              Set<String> active, List<String> order) {
        if (visited.contains(name)) return;
        if (!active.add(name)) {
            List<String> path = new ArrayList<String>(active);
            List<String> cycle = new ArrayList<String>(path.subList(path.indexOf(name), path.size()));
            cycle.add(name);
            throw invalid("Bootstrap variable dependency cycle: " + join(cycle), "vars." + name);
        }
        for (String dependency : graph.get(name)) visit(dependency, graph, visited, active, order);
        active.remove(name);
        visited.add(name);
        order.add(name);
    }

    private static void collectReferences(Object value, Set<String> refs, UnifiedTemplateEngine engine) {
        if (value instanceof Map) {
            for (Object child : ((Map<?, ?>) value).values()) collectReferences(child, refs, engine);
        } else if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) collectReferences(child, refs, engine);
        } else if (value instanceof String) {
            for (String path : engine.parseContextPaths((String) value)) {
                validatePath(path);
                Matcher matcher = VARIABLE.matcher(path);
                if (matcher.find()) refs.add(matcher.group(1));
            }
        }
    }

    private static void validateTree(Object value, String field, UnifiedTemplateEngine engine) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw invalid("Bootstrap map keys must be strings", field);
                validateTree(entry.getValue(), field + "." + entry.getKey(), engine);
            }
        } else if (value instanceof Iterable) {
            int index = 0;
            for (Object child : (Iterable<?>) value) validateTree(child, field + "[" + index++ + "]", engine);
        } else if (value instanceof String) {
            String text = (String) value;
            for (String path : engine.parseContextPaths(text)) validatePath(path);
            for (ToolCallParser.ParsedCall call : engine.parseCalls(text)) {
                if (!PURE_BUILT_INS.contains(call.name().toLowerCase(java.util.Locale.ROOT)))
                    throw invalid("Bootstrap expressions may call only safe built-ins; rejected call '" + call.name() + "'", field);
            }
        } else if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
            throw invalid("Unsupported bootstrap value type " + value.getClass().getName(), field);
        }
    }

    private static void validatePath(String path) {
        if (path == null || path.trim().isEmpty()) throw invalid("Bootstrap expression contains an empty Context path", "vars");
        String upper = path.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("EXEC.ACTIONS") || upper.startsWith("EXEC.ACTIONS.") || upper.startsWith("EXEC.ACTIONS[")
                || upper.equals("ACTIONS") || upper.startsWith("ACTIONS.") || upper.startsWith("ACTIONS[")
                || upper.equals("OUTPUT") || upper.startsWith("OUTPUT.") || upper.startsWith("OUTPUT[")
                || upper.startsWith("CASE.ACTIONS") || upper.startsWith("CASE.STAGES")
                || upper.startsWith("META.TOOL") || upper.startsWith("META.DBHELPER")
                || upper.startsWith("META.MQHELPER") || upper.startsWith("META.HTTPHELPER"))
            throw invalid("Context path '" + path + "' is unavailable during execution bootstrap", "vars");

        boolean allowed = upper.equals("EXEC.RUN_ID") || upper.equals("EXEC.ID") || upper.equals("EXEC.OUTPUT_DIR")
                || upper.equals("EXEC.STARTED_AT") || upper.equals("EXEC.RUN_STARTED_AT")
                || upper.equals("EXEC.INPUT") || upper.startsWith("EXEC.INPUT.") || upper.startsWith("EXEC.INPUT[")
                || upper.equals("EXEC.LOAD") || upper.startsWith("EXEC.LOAD.") || upper.startsWith("EXEC.LOAD[")
                || upper.startsWith("EXEC.VARS.")
                || upper.equals("META.PROJECT") || upper.startsWith("META.PROJECT.") || upper.startsWith("META.PROJECT[")
                || upper.equals("META.SOURCE") || upper.startsWith("META.SOURCE.") || upper.startsWith("META.SOURCE[")
                || upper.equals("META.TARGET") || upper.startsWith("META.TARGET.") || upper.startsWith("META.TARGET[")
                || upper.equals("META.TEMPLATE") || upper.startsWith("META.TEMPLATE.") || upper.startsWith("META.TEMPLATE[");
        if (!allowed) throw invalid("Context path '" + path + "' is not an initialized bootstrap root", "vars");
    }

    private static String join(List<String> names) {
        StringBuilder result = new StringBuilder();
        for (String name : names) { if (result.length() > 0) result.append(" -> "); result.append(name); }
        return result.toString();
    }

    private static IllegalArgumentException invalid(String message, String field) {
        return new IllegalArgumentException(message + " (" + field + ")");
    }
}
