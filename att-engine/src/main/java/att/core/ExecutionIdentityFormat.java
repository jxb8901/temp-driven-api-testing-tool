package att.core;

import att.template.DefaultBuiltInProvider;
import att.template.ToolCallParser;
import att.template.UnifiedTemplateEngine;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validates and evaluates configured outer Run and standalone Debug identities. */
public final class ExecutionIdentityFormat {
    private ExecutionIdentityFormat() { }

    public static String runId(String configured, String fallback, Path packageRoot) {
        return runId(configured, fallback, packageRoot, "testcase", packageRoot);
    }

    public static String runId(String configured, String fallback, Path packageRoot,
                               String sourceType, Path sourcePath) {
        return resolve(configured, fallback, "execution.runIdFormat", packageRoot, sourceType,
                sourcePath, null, null);
    }

    public static String debugId(String configured, String fallback, Path packageRoot,
                                 String targetType, String targetId) {
        return debugId(configured, fallback, packageRoot, targetType, targetId, packageRoot);
    }

    public static String debugId(String configured, String fallback, Path packageRoot,
                                 String targetType, String targetId, Path sourcePath) {
        return resolve(configured, fallback, "execution.debugIdFormat", packageRoot, "debug",
                sourcePath, targetType, targetId);
    }

    private static String resolve(String configured, String fallback, String field, Path packageRoot,
                                  String sourceType, Path sourcePath, String targetType, String targetId) {
        if (configured == null || configured.isEmpty())
            return IdentifierValidator.runId(fallback);
        if (configured.trim().isEmpty())
            throw new IllegalArgumentException(field + " must not be whitespace-only");
        String value;
        try {
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null, null, null, null,
                    new DefaultBuiltInProvider());
            engine.validateValueSyntax(configured);
            for (String path : engine.parseContextPaths(configured)) {
                if (!allowedPath(path, sourceType.equals("debug")))
                    throw new IllegalArgumentException(field + " cannot read ${" + path
                            + "}; identity formats have no access to the identity being created or invocation state");
            }
            for (ToolCallParser.ParsedCall call : engine.parseCalls(configured)) {
                if (!DefaultBuiltInProvider.isSafeForExecutionIdentity(call.name())
                        && !"sysdate".equalsIgnoreCase(call.name())
                        && !"systimestamp".equalsIgnoreCase(call.name())
                        && !"date.sysdate".equalsIgnoreCase(call.name())
                        && !"date.systimestamp".equalsIgnoreCase(call.name()))
                    throw new IllegalArgumentException(field + " permits pure identity-format built-ins only; unavailable call: " + call.name());
                Map<String, Object> arguments = new LinkedHashMap<String, Object>();
                for (ToolCallParser.Argument argument : call.arguments()) {
                    if (arguments.put(argument.key(), "<expression>") != null)
                        throw new IllegalArgumentException(field + " has a duplicate argument '" + argument.key() + "' in " + call.name());
                }
                DefaultBuiltInProvider.validateInvocation(call.name(), arguments);
            }
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            Map<String, Object> source = mapOf("type", sourceType, null, null);
            String logicalSource = logicalPackageName(packageRoot, sourcePath);
            if (logicalSource != null) source.put("path", logicalSource);
            metadata.put("SOURCE", source);
            if (targetType != null) metadata.put("TARGET", mapOf("type", targetType, "id", targetId));
            Map<String, Object> execution = new LinkedHashMap<String, Object>();
            String startedAt = Instant.now().toString();
            execution.put("STARTED_AT", startedAt);
            execution.put("RUN_STARTED_AT", startedAt);
            Map<String, Object> scope = new LinkedHashMap<String, Object>();
            scope.put("META", metadata);
            scope.put("EXEC", execution);
            value = engine.renderScoped(configured, scope);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid " + field + ": " + error.getMessage(), error);
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to evaluate " + field + ": " + error.getMessage(), error);
        }
        try {
            return IdentifierValidator.runId(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(field + " generated an invalid Run ID: " + safe(value), invalid);
        }
    }

    private static boolean allowedPath(String path, boolean debug) {
        if ("META.SOURCE.type".equals(path) || "META.SOURCE.path".equals(path) || "EXEC.STARTED_AT".equals(path)
                || "EXEC.RUN_STARTED_AT".equals(path)) return true;
        return debug && ("META.TARGET.type".equals(path) || "META.TARGET.id".equals(path));
    }

    private static String logicalPackageName(Path packageRoot, Path sourcePath) {
        if (packageRoot == null || sourcePath == null) return null;
        try {
            Path root = packageRoot.toRealPath();
            Path source = sourcePath.isAbsolute() ? sourcePath.toRealPath() : root.resolve(sourcePath).normalize().toRealPath();
            return source.startsWith(root) ? root.relativize(source).toString().replace('\\', '/') : null;
        } catch (Exception unavailable) {
            Path root = packageRoot.toAbsolutePath().normalize();
            Path source = sourcePath.isAbsolute() ? sourcePath.toAbsolutePath().normalize() : root.resolve(sourcePath).normalize();
            return source.startsWith(root) ? root.relativize(source).toString().replace('\\', '/') : null;
        }
    }

    private static Map<String, Object> mapOf(String key, Object value, String key2, Object value2) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key, value);
        if (key2 != null) result.put(key2, value2);
        return result;
    }

    private static String safe(String value) {
        if (value == null) return "<null>";
        return value.length() <= 128 ? value : value.substring(0, 128) + "…";
    }
}
