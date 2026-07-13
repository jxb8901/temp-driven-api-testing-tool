/* Author: Jeffrey + ChatGPT */
package att.template;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The only built-in provider registered by ATT V2.2. */
public final class DefaultBuiltInProvider implements BuiltInProvider {
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "upper", "lower", "trim", "string", "number", "boolean", "length", "concat", "coalesce",
            "nvl", "iif", "nchar")));

    @Override public Set<String> names() { return NAMES; }

    @Override public Object invoke(String name, Map<String, Object> input) {
        String function = name.toLowerCase(Locale.ROOT);
        if (!NAMES.contains(function)) throw new IllegalArgumentException("Unknown built-in: " + name);
        Object raw = argument(input, "value", "arg0");
        String value = text(raw);
        if ("upper".equals(function)) return value.toUpperCase(Locale.ROOT);
        if ("lower".equals(function)) return value.toLowerCase(Locale.ROOT);
        if ("trim".equals(function)) return value.trim();
        if ("string".equals(function)) return value;
        if ("number".equals(function)) {
            try { return new BigDecimal(value.trim()).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("number() requires a Number literal: " + value); }
        }
        if ("boolean".equals(function)) return String.valueOf(booleanValue(raw, "boolean"));
        if ("length".equals(function)) return String.valueOf(value.length());
        if ("concat".equals(function)) {
            StringBuilder result = new StringBuilder();
            for (Object item : input.values()) result.append(text(item));
            return result.toString();
        }
        if ("coalesce".equals(function)) {
            for (Object item : input.values()) if (item != null && !text(item).trim().isEmpty()) return item;
            return "";
        }
        if ("nvl".equals(function)) {
            require(input, "nvl", 2, "value", "defaultValue");
            Object defaultValue = argument(input, "defaultValue", "arg1");
            return raw == null || text(raw).isEmpty() ? defaultValue : raw;
        }
        if ("iif".equals(function)) {
            require(input, "iif", 3, "condition", "trueValue", "falseValue");
            Object condition = argument(input, "condition", "arg0");
            return booleanValue(condition, "iif") ? argument(input, "trueValue", "arg1") : argument(input, "falseValue", "arg2");
        }
        require(input, "nchar", 2, "count", "value");
        int count = integer(argument(input, "count", "arg0"), "nchar");
        if (count < 0 || count > 10000) throw new IllegalArgumentException("nchar() count must be between 0 and 10000: " + count);
        String repeated = text(argument(input, "value", "arg1"));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(repeated);
        return result.toString();
    }

    private static Object argument(Map<String, Object> input, String named, String positional) {
        return input.containsKey(named) ? input.get(named) : input.get(positional);
    }

    private static void require(Map<String, Object> input, String function, int count, String... named) {
        for (int i = 0; i < count; i++) if (!(input.containsKey("arg" + i) || input.containsKey(named[i]))) {
            throw new IllegalArgumentException(function + "() requires " + count + " arguments");
        }
    }

    private static boolean booleanValue(Object value, String function) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        String text = text(value);
        if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text) || "0".equals(text)) return false;
        throw new IllegalArgumentException(function + "() requires true/false, yes/no, or 1/0: " + text);
    }

    private static int integer(Object value, String function) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException(function + "() count must be an integer: " + value);
            return (int) number;
        }
        try { return new BigDecimal(text(value).trim()).intValueExact(); }
        catch (RuntimeException e) { throw new IllegalArgumentException(function + "() count must be an integer: " + value); }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
