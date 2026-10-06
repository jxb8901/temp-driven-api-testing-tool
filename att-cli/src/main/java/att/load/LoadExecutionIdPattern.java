package att.load;

import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.template.DefaultBuiltInProvider;
import att.template.UnifiedTemplateEngine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates and evaluates a Load EXEC.ID format using the ordinary expression engine. */
public final class LoadExecutionIdPattern {
    private static final Pattern VALUE = Pattern.compile("\\$\\{([^{}]+)}");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9_-]|\\.(?=[A-Za-z0-9_-])){0,239}$");

    private LoadExecutionIdPattern() { }

    public static void validate(String format, LoadScenario.Model model) {
        if (format == null || format.isEmpty()) return;
        if (format.indexOf('/') >= 0 || format.indexOf('\\') >= 0)
            throw new IllegalArgumentException("execution.execIdFormat must produce one path-safe segment and cannot contain path separators");
        UnifiedTemplateEngine syntax = new UnifiedTemplateEngine(null, null, null, null, null);
        syntax.validateValueSyntax(format);
        Matcher refs = VALUE.matcher(format);
        while (refs.find()) {
            String path = refs.group(1);
            if (!allowedContextPath(path))
                throw new IllegalArgumentException("execution.execIdFormat cannot read ${" + path
                        + "}; EXEC.ID, EXEC.OUTPUT_DIR, EXEC.ACTIONS, EXEC.VARS, and invocation-scoped META are unavailable during ID initialization");
            if ("EXEC.LOAD.USER_ID".equals(path) && model == LoadScenario.Model.ARRIVAL_RATE)
                throw new IllegalArgumentException("EXEC.LOAD.USER_ID is unavailable for arrivalRate workloads");
        }
        for (att.template.ToolCallParser.ParsedCall call : syntax.parseCalls(format)) {
            if (!DefaultBuiltInProvider.isSafeForExecutionIdentity(call.name()))
                throw new IllegalArgumentException("execution.execIdFormat permits pure deterministic built-ins only; stateful, random, clock, filesystem, external Tool, DB, MQ, HTTP, and SSH calls are unavailable: " + call.name());
            java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<String, Object>();
            for (att.template.ToolCallParser.Argument argument : call.arguments()) {
                if (arguments.put(argument.key(), "<expression>") != null)
                    throw new IllegalArgumentException("Duplicate execution.execIdFormat argument '" + argument.key() + "' in " + call.name());
            }
            DefaultBuiltInProvider.validateInvocation(call.name(), arguments);
        }
    }

    public static String evaluate(String format, LoadScenario.Model model, CaseRuntimeContext context,
                                  UnifiedTemplateEngine engine, CaseExecutionLog log) throws Exception {
        validate(format, model);
        String value = engine.render(format, context, log);
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("Generated EXEC.ID must be a safe path segment matching [A-Za-z0-9](?:[A-Za-z0-9_-]|\\.(?=[A-Za-z0-9_-])){0,239}");
        return value;
    }

    private static boolean allowedContextPath(String path) {
        if (path == null || path.endsWith("?")) return false;
        if (path.startsWith("EXEC.INPUT.") || path.startsWith("EXEC.INPUT[")) return true;
        if ("EXEC.RUN_ID".equals(path) || "EXEC.STARTED_AT".equals(path) || "EXEC.RUN_STARTED_AT".equals(path)) return true;
        if ("EXEC.LOAD.MODEL".equals(path) || "EXEC.LOAD.WORKLOAD_ID".equals(path)
                || "EXEC.LOAD.USER_ID".equals(path) || "EXEC.LOAD.ITERATION".equals(path)
                || "EXEC.LOAD.PHASE".equals(path)) return true;
        return "META.PACKAGE_ROOT".equals(path) || path.startsWith("META.SOURCE.")
                || path.startsWith("META.TARGET.") || path.startsWith("META.TEMPLATE.");
    }
}
