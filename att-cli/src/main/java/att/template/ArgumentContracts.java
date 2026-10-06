/* Author: Jeffrey + ChatGPT */
package att.template;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared final-value type contracts and coercion for framework and configured Tool calls. */
public final class ArgumentContracts {
    private ArgumentContracts() { }

    /** Coerces only parameters whose native contracts explicitly accept scalar strings. */
    public static Map<String, Object> coerce(String call, Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(arguments);
        String[] parts = call == null ? new String[0] : call.toLowerCase(java.util.Locale.ROOT).split("\\.", -1);
        if (parts.length != 3) return result;
        String family = parts[0];
        if ("mq".equals(family)) {
            coerceType(call, result, "queue", String.class, "String");
            coerceType(call, result, "requestQueue", String.class, "String");
            coerceType(call, result, "replyQueue", String.class, "String");
            coerceType(call, result, "correlationId", String.class, "String");
            coerceType(call, result, "instance", String.class, "String");
            coerceTextOrStructure(call, result, "payload");
            coerceInteger(call, result, "waitMs", 0, 3600000);
            coerceEnum(call, result, "requestFormat", "text", "json", "yaml", "xml");
            coerceEnum(call, result, "responseFormat", "text", "json", "yaml", "xml");
        } else if ("ssh".equals(family)) {
            coerceType(call, result, "command", String.class, "String");
            coerceType(call, result, "remotePath", String.class, "String");
            coerceType(call, result, "sourcePath", String.class, "String");
            coerceType(call, result, "targetPath", String.class, "String");
            coerceStringOrBytes(call, result, "payload");
            coerceEnum(call, result, "stdoutFormat", "text", "json", "yaml", "xml");
            coerceInteger(call, result, "timeoutMs", 1, 3600000);
            coerceBoolean(call, result, "overwrite");
            coerceBoolean(call, result, "missingOk");
        } else if ("http".equals(family)) {
            coerceType(call, result, "path", String.class, "String");
            coerceType(call, result, "contentType", String.class, "String");
            coerceType(call, result, "query", Map.class, "Map");
            coerceStringMap(call, result, "headers");
            coerceTextOrStructure(call, result, "body");
            coerceInteger(call, result, "connectionRequestTimeoutMs", 1, 3600000);
            coerceInteger(call, result, "connectTimeoutMs", 1, 3600000);
            coerceInteger(call, result, "readTimeoutMs", 1, 3600000);
            coerceBoolean(call, result, "followRedirects");
            coerceEnum(call, result, "method", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
            coerceEnum(call, result, "requestFormat", "text", "json", "yaml", "xml");
            coerceEnum(call, result, "responseFormat", "auto", "text", "json", "yaml", "xml");
        } else if ("db".equals(family)) {
            coerceType(call, result, "sql", String.class, "String");
            coerceType(call, result, "sqlFile", String.class, "String");
            coerceType(call, result, "params", List.class, "List");
            coerceType(call, result, "parameters", Map.class, "Map");
        }
        return result;
    }

    /** Applies an explicitly declared configured-Tool input type after expression evaluation. */
    public static Map<String, Object> coerce(String call, Map<String, Object> arguments,
                                             att.config.ToolConfig tool) {
        Map<String, Object> result = coerce(call, arguments);
        if (tool == null) return result;
        for (att.config.ToolArgumentConfig contract : tool.arguments().values()) {
            if (!result.containsKey(contract.key()) || "any".equals(contract.type())) continue;
            Object value = result.get(contract.key());
            result.put(contract.key(), coerceDeclared(call, contract, value));
        }
        return result;
    }

    /** Rejects only statically known return types that cannot satisfy their target contract. */
    public static void validateInferredTypes(String call,
            Map<String, ExpressionBlockEvaluator.ValueType> arguments, att.config.ToolConfig tool) {
        if (arguments == null || arguments.isEmpty()) return;
        if (tool != null) {
            for (att.config.ToolArgumentConfig contract : tool.arguments().values()) {
                ExpressionBlockEvaluator.ValueType type = arguments.get(contract.key());
                if (type != null && type != ExpressionBlockEvaluator.ValueType.UNKNOWN
                        && !acceptsDeclared(contract.type(), type)) {
                    throw incompatible(call, contract.key(), type, contract.type());
                }
            }
            return;
        }
        for (Map.Entry<String, ExpressionBlockEvaluator.ValueType> argument : arguments.entrySet()) {
            ExpressionBlockEvaluator.ValueType type = argument.getValue();
            if (type == null || type == ExpressionBlockEvaluator.ValueType.UNKNOWN) continue;
            String target = nativeTarget(call, argument.getKey());
            if (target != null && !accepts(target, type)) throw incompatible(call, argument.getKey(), type, target);
        }
    }

    private static boolean acceptsDeclared(String target, ExpressionBlockEvaluator.ValueType type) {
        if ("any".equals(target)) return true;
        if ("string".equals(target) || "enum".equals(target)) return type == ExpressionBlockEvaluator.ValueType.STRING;
        if ("integer".equals(target) || "long".equals(target) || "decimal".equals(target))
            return type == ExpressionBlockEvaluator.ValueType.NUMBER || type == ExpressionBlockEvaluator.ValueType.STRING;
        if ("boolean".equals(target)) return type == ExpressionBlockEvaluator.ValueType.BOOLEAN || type == ExpressionBlockEvaluator.ValueType.STRING;
        if ("bytes".equals(target)) return type == ExpressionBlockEvaluator.ValueType.BYTES || type == ExpressionBlockEvaluator.ValueType.STRING;
        if ("map".equals(target)) return type == ExpressionBlockEvaluator.ValueType.MAP;
        if ("list".equals(target)) return type == ExpressionBlockEvaluator.ValueType.LIST;
        return true;
    }

    private static String nativeTarget(String call, String argument) {
        argument = argument.toLowerCase(java.util.Locale.ROOT);
        String[] parts = call == null ? new String[0] : call.toLowerCase(java.util.Locale.ROOT).split("\\.", -1);
        if (parts.length != 3) return null;
        String family = parts[0];
        if ("mq".equals(family)) {
            if (java.util.Arrays.asList("queue", "requestqueue", "replyqueue", "correlationid", "instance").contains(argument.toLowerCase(java.util.Locale.ROOT))) return "string";
            if ("payload".equals(argument)) return "payload";
            if ("waitms".equals(argument)) return "numeric";
            if ("requestformat".equals(argument) || "responseformat".equals(argument)) return "string";
        } else if ("ssh".equals(family)) {
            if (java.util.Arrays.asList("command", "remotepath", "sourcepath", "targetpath").contains(argument.toLowerCase(java.util.Locale.ROOT))) return "string";
            if ("payload".equals(argument)) return "string-bytes";
            if ("stdoutformat".equals(argument)) return "string";
            if ("timeoutms".equals(argument)) return "numeric";
            if ("overwrite".equals(argument) || "missingok".equals(argument)) return "boolean";
        } else if ("http".equals(family)) {
            if ("path".equals(argument) || "contenttype".equals(argument)
                    || "method".equals(argument) || "requestformat".equals(argument) || "responseformat".equals(argument)) return "string";
            if ("query".equals(argument) || "headers".equals(argument)) return "map";
            if ("body".equals(argument)) return "payload";
            if ("connectionrequesttimeoutms".equals(argument) || "connecttimeoutms".equals(argument)
                    || "readtimeoutms".equals(argument)) return "numeric";
            if ("followredirects".equals(argument)) return "boolean";
        } else if ("db".equals(family)) {
            if ("sql".equals(argument) || "sqlfile".equals(argument)) return "string";
            if ("params".equals(argument)) return "list";
            if ("parameters".equals(argument)) return "map";
        }
        return null;
    }

    private static boolean accepts(String target, ExpressionBlockEvaluator.ValueType type) {
        if ("string".equals(target) || "enum".equals(target)) return type == ExpressionBlockEvaluator.ValueType.STRING;
        if ("numeric".equals(target)) return type == ExpressionBlockEvaluator.ValueType.NUMBER || type == ExpressionBlockEvaluator.ValueType.STRING;
        if ("boolean".equals(target)) return type == ExpressionBlockEvaluator.ValueType.BOOLEAN || type == ExpressionBlockEvaluator.ValueType.STRING;
        if ("map".equals(target)) return type == ExpressionBlockEvaluator.ValueType.MAP;
        if ("list".equals(target)) return type == ExpressionBlockEvaluator.ValueType.LIST;
        if ("payload".equals(target)) return type == ExpressionBlockEvaluator.ValueType.STRING
                || type == ExpressionBlockEvaluator.ValueType.BYTES || type == ExpressionBlockEvaluator.ValueType.MAP
                || type == ExpressionBlockEvaluator.ValueType.LIST;
        if ("string-bytes".equals(target)) return type == ExpressionBlockEvaluator.ValueType.STRING
                || type == ExpressionBlockEvaluator.ValueType.BYTES;
        return true;
    }

    private static IllegalArgumentException incompatible(String call, String key,
            ExpressionBlockEvaluator.ValueType type, String target) {
        return new IllegalArgumentException(call + "." + key + " has statically known " + type
                + " expression result, incompatible with target contract " + target);
    }

    private static Object coerceDeclared(String call, att.config.ToolArgumentConfig contract, Object value) {
        String type = contract.type();
        if ("string".equals(type)) return requireType(call, contract.key(), value, String.class, "String");
        if ("integer".equals(type)) return Integer.valueOf((int) integral(call, contract.key(), value, Integer.MIN_VALUE, Integer.MAX_VALUE, "Integer"));
        if ("long".equals(type)) return Long.valueOf(integral(call, contract.key(), value, Long.MIN_VALUE, Long.MAX_VALUE, "Long"));
        if ("decimal".equals(type)) {
            if (value instanceof Number) return new BigDecimal(value.toString());
            if (value instanceof String) try { return new BigDecimal(((String) value).trim()); }
            catch (RuntimeException ignored) { }
            throw invalid(call, contract.key(), value, "a decimal number");
        }
        if ("boolean".equals(type)) return coerceBooleanValue(call, contract.key(), value);
        if ("enum".equals(type)) {
            if (!(value instanceof String)) throw invalid(call, contract.key(), value, "one of " + contract.enumValues());
            for (String allowed : contract.enumValues()) if (allowed.equals(value)) return value;
            throw invalid(call, contract.key(), value, "one of " + contract.enumValues());
        }
        if ("bytes".equals(type)) {
            if (value instanceof byte[]) return value;
            if (value instanceof String) return ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            throw invalid(call, contract.key(), value, "a byte array or UTF-8 String");
        }
        if ("map".equals(type)) return requireType(call, contract.key(), value, Map.class, "Map");
        if ("list".equals(type)) return requireType(call, contract.key(), value, List.class, "List");
        return value;
    }

    private static void coerceInteger(String call, Map<String, Object> values, String key, long min, long max) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        Object value = values.get(key);
        long number = integral(call, key, value, min, max, "an integer from " + min + " to " + max);
        values.put(key, Integer.valueOf((int) number));
    }

    private static long integral(String call, String key, Object value, long min, long max, String expected) {
        long number;
        try {
            if (value instanceof Number) number = new BigDecimal(value.toString()).longValueExact();
            else if (value instanceof String) number = new BigDecimal(((String) value).trim()).longValueExact();
            else throw new NumberFormatException("not a number or string");
        } catch (RuntimeException error) { throw invalid(call, key, value, expected); }
        if (number < min || number > max) throw invalid(call, key, value, expected);
        return number;
    }

    private static void coerceBoolean(String call, Map<String, Object> values, String key) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        Object value = values.get(key);
        values.put(key, coerceBooleanValue(call, key, value));
    }

    private static Boolean coerceBooleanValue(String call, String key, Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String normalized = ((String) value).trim();
            if ("true".equalsIgnoreCase(normalized)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(normalized)) return Boolean.FALSE;
        }
        throw invalid(call, key, value, "true or false");
    }

    private static void coerceEnum(String call, Map<String, Object> values, String key, String... allowed) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        Object value = values.get(key);
        if (!(value instanceof String)) throw invalid(call, key, value, "one of " + java.util.Arrays.toString(allowed));
        for (String choice : allowed) if (choice.equalsIgnoreCase((String) value)) { values.put(key, choice); return; }
        throw invalid(call, key, value, "one of " + java.util.Arrays.toString(allowed));
    }

    private static void coerceType(String call, Map<String, Object> values, String key,
                                   Class<?> type, String expected) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        if (!type.isInstance(values.get(key))) throw invalid(call, key, values.get(key), expected);
    }

    private static void coerceTextOrStructure(String call, Map<String, Object> values, String key) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        Object value = values.get(key);
        if (!(value instanceof String || value instanceof Map || value instanceof List || value instanceof byte[]))
            throw invalid(call, key, value, "a String, byte array, Map, or List");
    }

    private static void coerceStringOrBytes(String call, Map<String, Object> values, String key) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        Object value = values.get(key);
        if (!(value instanceof String || value instanceof byte[])) throw invalid(call, key, value, "a String or byte array");
    }

    private static void coerceStringMap(String call, Map<String, Object> values, String key) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        Object value = values.get(key);
        if (!(value instanceof Map)) throw invalid(call, key, value, "a Map<String, String>");
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))
                throw invalid(call, key, value, "a Map<String, String>");
    }

    private static <T> T requireType(String call, String key, Object value, Class<T> type, String expected) {
        if (type.isInstance(value)) return type.cast(value);
        throw invalid(call, key, value, expected);
    }

    private static IllegalArgumentException invalid(String call, String argument, Object value, String expected) {
        String type = value == null ? "null" : value.getClass().getSimpleName();
        return new IllegalArgumentException(call + "." + argument + " cannot coerce resolved " + type
                + " value '" + String.valueOf(value) + "' to " + expected);
    }
}
