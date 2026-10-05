/* Author: Jeffrey + ChatGPT */
package att.template;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** ATT-owned in-process built-in provider. */
public final class DefaultBuiltInProvider implements BuiltInProvider {
    private static final int MAX_TEXT_LENGTH = 10000;
    private static final int MAX_RANDOM_CHOICES = 1000;
    private static final DateTimeFormatter SYSTEM_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
    private static final Map<String, String> ALIASES = aliases();
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<String>(ALIASES.keySet()));
    private static final Set<String> EXECUTION_ID_FUNCTIONS = Collections.unmodifiableSet(new LinkedHashSet<String>(java.util.Arrays.asList(
            "upper", "lower", "trim", "ltrim", "rtrim", "string", "number", "boolean", "length",
            "concat", "coalesce", "nvl", "iif", "nchar", "substr", "indexof", "contains", "startswith",
            "endswith", "replace", "padleft", "padright", "dateadd", "format")));
    private static final Set<String> BOOTSTRAP_SAFE_FUNCTIONS = Collections.unmodifiableSet(new LinkedHashSet<String>(java.util.Arrays.asList(
            "upper", "lower", "trim", "ltrim", "rtrim", "string", "number", "boolean", "length",
            "concat", "coalesce", "nvl", "iif", "nchar", "substr", "indexof", "contains", "startswith",
            "endswith", "replace", "padleft", "padright", "dateadd", "formatdate", "format")));

    private final Clock clock;
    private final Random random;
    private final SequenceService sequences;

    public DefaultBuiltInProvider() {
        this(Clock.systemDefaultZone(), new Random(), new SequenceService());
    }

    public DefaultBuiltInProvider(SequenceService sequences) { this(Clock.systemDefaultZone(), new Random(), sequences); }

    DefaultBuiltInProvider(Clock clock) {
        this(clock, new Random());
    }

    DefaultBuiltInProvider(Clock clock, Random random) {
        this(clock, random, new SequenceService());
    }

    private DefaultBuiltInProvider(Clock clock, Random random, SequenceService sequences) {
        if (clock == null) throw new IllegalArgumentException("clock is required");
        if (random == null) throw new IllegalArgumentException("random is required");
        if (sequences == null) throw new IllegalArgumentException("sequence service is required");
        this.clock = clock;
        this.random = random;
        this.sequences = sequences;
    }

    @Override public Set<String> names() { return NAMES; }

    /** Pure deterministic built-ins allowed while ATT resolves an execution identity. */
    public static boolean isSafeForExecutionIdentity(String name) {
        String function = name == null ? null : ALIASES.get(name.toLowerCase(Locale.ROOT));
        return function != null && EXECUTION_ID_FUNCTIONS.contains(function);
    }

    /** Pure deterministic built-ins allowed while bootstrap vars are evaluated. */
    public static boolean isSafeForBootstrap(String name) {
        String function = name == null ? null : ALIASES.get(name.toLowerCase(Locale.ROOT));
        return function != null && BOOTSTRAP_SAFE_FUNCTIONS.contains(function);
    }

    /** Returns a built-in's statically declared result type without evaluating arguments. */
    public static ExpressionBlockEvaluator.ValueType inferredResultType(String name,
            Map<String, ExpressionBlockEvaluator.ValueType> arguments) {
        String function = name == null ? null : ALIASES.get(name.toLowerCase(Locale.ROOT));
        if (function == null) return ExpressionBlockEvaluator.ValueType.UNKNOWN;
        if ("nvl".equals(function)) return commonType(argumentType(arguments, "value", "arg0"),
                argumentType(arguments, "defaultValue", "arg1"));
        if ("iif".equals(function)) return commonType(argumentType(arguments, "trueValue", "arg1"),
                argumentType(arguments, "falseValue", "arg2"));
        if ("coalesce".equals(function) || "randomchoice".equals(function)) {
            ExpressionBlockEvaluator.ValueType result = ExpressionBlockEvaluator.ValueType.UNKNOWN;
            for (ExpressionBlockEvaluator.ValueType value : arguments.values()) {
                if (value == ExpressionBlockEvaluator.ValueType.UNKNOWN) return ExpressionBlockEvaluator.ValueType.UNKNOWN;
                if (result == ExpressionBlockEvaluator.ValueType.UNKNOWN) result = value;
                else if (result != value) return ExpressionBlockEvaluator.ValueType.UNKNOWN;
            }
            return result;
        }
        if ("seq.next".equals(function) || "sysdate".equals(function) || "systimestamp".equals(function)
                || "upper".equals(function) || "lower".equals(function) || "trim".equals(function)
                || "ltrim".equals(function) || "rtrim".equals(function) || "string".equals(function)
                || "number".equals(function) || "boolean".equals(function) || "length".equals(function)
                || "concat".equals(function) || "nchar".equals(function) || "substr".equals(function)
                || "indexof".equals(function) || "contains".equals(function) || "startswith".equals(function)
                || "endswith".equals(function) || "replace".equals(function) || "padleft".equals(function)
                || "padright".equals(function) || "formatdate".equals(function) || "dateadd".equals(function)
                || "format".equals(function)) return ExpressionBlockEvaluator.ValueType.STRING;
        return ExpressionBlockEvaluator.ValueType.UNKNOWN;
    }

    private static ExpressionBlockEvaluator.ValueType argumentType(
            Map<String, ExpressionBlockEvaluator.ValueType> arguments, String named, String positional) {
        ExpressionBlockEvaluator.ValueType value = arguments.get(named);
        return value == null ? arguments.get(positional) : value;
    }

    private static ExpressionBlockEvaluator.ValueType commonType(ExpressionBlockEvaluator.ValueType left,
            ExpressionBlockEvaluator.ValueType right) {
        return left != null && left == right ? left : ExpressionBlockEvaluator.ValueType.UNKNOWN;
    }

    @Override public Object invoke(String name, Map<String, Object> input) {
        String function = resolve(name);

        if ("sysdate".equals(function)) {
            return systemTime(input, "sysdate", DateTimeFormatter.ISO_LOCAL_DATE,
                    LocalDate.now(clock));
        }
        if ("systimestamp".equals(function)) {
            return systemTime(input, "systimestamp", SYSTEM_TIMESTAMP,
                    OffsetDateTime.ofInstant(clock.instant(), clock.getZone()));
        }
        if ("format".equals(function)) {
            require(input, "format", 2, 2, "format", "obj");
            return new TypedValueFormatter().format(argument(input, "obj", "arg1"),
                    text(argument(input, "format", "arg0")));
        }
        if ("seq.next".equals(function)) return nextSequence(input);
        if (isSingleValueFunction(function)) return invokeSingleValue(function, singleValue(input, function));
        if ("concat".equals(function)) {
            rejectMixedArgumentStyles(input, "concat");
            StringBuilder result = new StringBuilder();
            for (Object item : input.values()) result.append(text(item));
            return result.toString();
        }
        if ("coalesce".equals(function)) {
            rejectMixedArgumentStyles(input, "coalesce");
            for (Object item : input.values()) if (item != null && !text(item).trim().isEmpty()) return item;
            return "";
        }
        if ("nvl".equals(function)) {
            require(input, "nvl", 2, 2, "value", "defaultValue");
            Object value = argument(input, "value", "arg0");
            Object defaultValue = argument(input, "defaultValue", "arg1");
            return value == null || text(value).isEmpty() ? defaultValue : value;
        }
        if ("iif".equals(function)) {
            require(input, "iif", 3, 3, "condition", "trueValue", "falseValue");
            Object condition = argument(input, "condition", "arg0");
            return booleanValue(condition, "iif") ? argument(input, "trueValue", "arg1") : argument(input, "falseValue", "arg2");
        }
        if ("nchar".equals(function)) {
            require(input, "nchar", 2, 2, "count", "value");
            int count = boundedSize(argument(input, "count", "arg0"), "nchar", "count");
            return repeat(text(argument(input, "value", "arg1")), count);
        }
        if ("randomchoice".equals(function)) return randomChoice(input);
        if ("substr".equals(function)) return substr(input);
        if ("indexof".equals(function)) return indexOf(input);
        if ("contains".equals(function) || "startswith".equals(function) || "endswith".equals(function)) {
            String secondName = "contains".equals(function) ? "search" : ("startswith".equals(function) ? "prefix" : "suffix");
            require(input, displayName(function), 2, 2, "value", secondName);
            String value = text(argument(input, "value", "arg0"));
            String search = text(argument(input, secondName, "arg1"));
            boolean result = "contains".equals(function) ? value.contains(search)
                    : ("startswith".equals(function) ? value.startsWith(search) : value.endsWith(search));
            return String.valueOf(result);
        }
        if ("replace".equals(function)) {
            require(input, "replace", 3, 3, "value", "target", "replacement");
            return text(argument(input, "value", "arg0")).replace(
                    text(argument(input, "target", "arg1")), text(argument(input, "replacement", "arg2")));
        }
        if ("padleft".equals(function) || "padright".equals(function)) return pad(input, function);
        if ("formatdate".equals(function)) return formatDate(input);
        if ("dateadd".equals(function)) return dateAdd(input);
        throw new IllegalArgumentException("Unknown built-in: " + name);
    }

    /** Validates call names/counts/styles without evaluating runtime argument values. */
    public static void validateInvocation(String name, Map<String, Object> input) {
        String function = resolve(name);
        if ("format".equals(function)) { require(input, "format", 2, 2, "format", "obj"); return; }
        if ("seq.next".equals(function)) { require(input, "seq.next", 0, 2, "name", "width"); return; }
        if ("sysdate".equals(function) || "systimestamp".equals(function)) { require(input, function, 0, 1, "format"); return; }
        if (isSingleValueFunction(function)) { singleValue(input, function); return; }
        if ("concat".equals(function) || "coalesce".equals(function)) { rejectMixedArgumentStyles(input, function); return; }
        if ("randomchoice".equals(function)) {
            rejectMixedArgumentStyles(input, "randomChoice");
            if (input.isEmpty() || input.size() > MAX_RANDOM_CHOICES) throw new IllegalArgumentException("randomChoice() requires 1 to " + MAX_RANDOM_CHOICES + " arguments");
            return;
        }
        if ("nvl".equals(function)) { require(input, "nvl", 2, 2, "value", "defaultValue"); return; }
        if ("iif".equals(function)) { require(input, "iif", 3, 3, "condition", "trueValue", "falseValue"); return; }
        if ("nchar".equals(function)) { require(input, "nchar", 2, 2, "count", "value"); return; }
        if ("substr".equals(function)) { require(input, "substr", 2, 3, "value", "start", "length"); return; }
        if ("indexof".equals(function)) { require(input, "indexOf", 2, 3, "value", "search", "fromIndex"); return; }
        if ("contains".equals(function)) { require(input, "contains", 2, 2, "value", "search"); return; }
        if ("startswith".equals(function)) { require(input, "startsWith", 2, 2, "value", "prefix"); return; }
        if ("endswith".equals(function)) { require(input, "endsWith", 2, 2, "value", "suffix"); return; }
        if ("replace".equals(function)) { require(input, "replace", 3, 3, "value", "target", "replacement"); return; }
        if ("padleft".equals(function) || "padright".equals(function)) { require(input, displayName(function), 2, 3, "value", "length", "pad"); return; }
        if ("formatdate".equals(function)) { require(input, "formatDate", 2, 3, "value", "pattern", "zoneId"); return; }
        if ("dateadd".equals(function)) { require(input, "dateAdd", 3, 3, "value", "amount", "unit"); return; }
        throw new IllegalArgumentException("Unknown built-in: " + name);
    }

    private static String resolve(String name) {
        String function = name == null ? "" : ALIASES.get(name.toLowerCase(Locale.ROOT));
        rejectRemoved(name);
        if (function == null) throw new IllegalArgumentException("Unknown built-in: " + name);
        return function;
    }

    /** Shared runtime/static migration diagnostic; removed names are not registered built-ins. */
    public static void rejectRemoved(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (java.util.Arrays.asList("dbtext", "misc.dbtext", "prettyprint", "misc.prettyprint", "format.pretty").contains(normalized))
            throw new IllegalArgumentException("Removed presentation built-in " + name
                    + "; keep the value typed and use Log value + format: sqlplus, json or yaml, or resource evidence.output.");
        if (normalized.startsWith("file.") || java.util.Arrays.asList("fileexists", "directoryexists", "filesize",
                "makedirectories", "copyfile", "movefile", "deletefile").contains(normalized))
            throw new IllegalArgumentException("Removed local file built-in " + name
                    + "; use &{...} for package content or ssh.<helper>.stat/mkdirs/move/delete/upload/download for remote files; use execute for explicit remote copy.");
    }

    private static Map<String, String> aliases() {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        String[] legacy = {"upper", "lower", "trim", "ltrim", "rtrim", "string", "number", "boolean", "length",
                "concat", "coalesce", "nvl", "iif", "nchar", "substr", "indexof", "contains", "startswith",
                "endswith", "replace", "padleft", "padright", "sysdate", "systimestamp", "formatdate",
                "dateadd", "randomchoice"};
        for (String name : legacy) result.put(name, name);
        alias(result, "format", "format");
        alias(result, "seq.next", "seq.next");

        alias(result, "str.upper", "upper"); alias(result, "str.lower", "lower");
        alias(result, "str.trim", "trim"); alias(result, "str.ltrim", "ltrim");
        alias(result, "str.rtrim", "rtrim"); alias(result, "str.length", "length");
        alias(result, "str.concat", "concat"); alias(result, "str.substr", "substr");
        alias(result, "str.indexof", "indexof"); alias(result, "str.contains", "contains");
        alias(result, "str.startswith", "startswith"); alias(result, "str.endswith", "endswith");
        alias(result, "str.replace", "replace"); alias(result, "str.lpad", "padleft");
        alias(result, "str.rpad", "padright"); alias(result, "str.repeat", "nchar");

        alias(result, "date.sysdate", "sysdate"); alias(result, "date.systimestamp", "systimestamp");
        alias(result, "date.format", "formatdate"); alias(result, "date.add", "dateadd");


        alias(result, "misc.string", "string"); alias(result, "misc.number", "number");
        alias(result, "misc.boolean", "boolean"); alias(result, "misc.coalesce", "coalesce");
        alias(result, "misc.nvl", "nvl"); alias(result, "misc.iif", "iif");
        alias(result, "misc.randomchoice", "randomchoice");
        return Collections.unmodifiableMap(result);
    }

    private Object nextSequence(Map<String, Object> input) {
        Object first = input.containsKey("arg0") ? input.get("arg0") : input.get("name");
        Object second = input.containsKey("arg1") ? input.get("arg1") : input.get("width");
        String name = "default";
        Integer width = null;
        if (first instanceof Number) width = boundedSize(first, "seq.next", "width");
        else if (first != null) {
            if (!(first instanceof String)) throw new IllegalArgumentException("seq.next name must be text");
            name = (String) first;
        }
        if (second != null) {
            if (width != null) throw new IllegalArgumentException("seq.next(width) accepts one argument");
            width = boundedSize(second, "seq.next", "width");
        }
        return sequences.next(name, width);
    }

    private static void alias(Map<String, String> aliases, String name, String function) {
        aliases.put(name, function);
    }

    private Object systemTime(Map<String, Object> input, String function, DateTimeFormatter fallback,
                              TemporalAccessor value) {
        require(input, function, 0, 1, "format");
        if (input.isEmpty()) return fallback.format(value);
        String pattern = text(argument(input, "format", "arg0"));
        if (pattern.trim().isEmpty()) throw builtInError(function, "format", pattern,
                "The optional format must be a non-blank Java DateTimeFormatter pattern.", null);
        try { return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(value); }
        catch (IllegalArgumentException e) {
            throw builtInError(function, "format", pattern, "The supplied Java DateTimeFormatter pattern is invalid.", e);
        } catch (DateTimeException e) {
            throw builtInError(function, "format", pattern, "The pattern requests fields unavailable from this system-time value.", e);
        } catch (RuntimeException e) {
            throw builtInError(function, "format", pattern, "The supplied Java DateTimeFormatter pattern cannot be applied.", e);
        }
    }

    private att.validation.DiagnosticException builtInError(String function, String argument, String value,
                                                            String suggestion, RuntimeException cause) {
        String detail = "function=" + function + ", argument=" + argument + ", value='" + value + "'";
        if (cause != null && cause.getMessage() != null) detail += ", formatterCause=" + cause.getMessage();
        return new att.validation.DiagnosticException(att.validation.DiagnosticCodes.BUILTIN_INVALID,
                "Invalid " + function + "() " + argument + " argument", detail, null,
                function + "." + argument, null, null, null, null, null, suggestion, cause);
    }

    private Object randomChoice(Map<String, Object> input) {
        rejectMixedArgumentStyles(input, "randomChoice");
        if (input.isEmpty() || input.size() > MAX_RANDOM_CHOICES) throw new IllegalArgumentException("randomChoice() requires 1 to " + MAX_RANDOM_CHOICES + " arguments");
        List<Object> values = new ArrayList<Object>(input.values());
        return values.get(random.nextInt(values.size()));
    }

    private Object invokeSingleValue(String function, Object raw) {
        String value = text(raw);
        if ("upper".equals(function)) return value.toUpperCase(Locale.ROOT);
        if ("lower".equals(function)) return value.toLowerCase(Locale.ROOT);
        if ("trim".equals(function)) return value.trim();
        if ("ltrim".equals(function)) return trimLeft(value);
        if ("rtrim".equals(function)) return trimRight(value);
        if ("string".equals(function)) return value;
        if ("number".equals(function)) {
            try { return new BigDecimal(value.trim()).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("number() requires a Number literal: " + value); }
        }
        if ("boolean".equals(function)) return String.valueOf(booleanValue(raw, "boolean"));
        return String.valueOf(value.length());
    }

    private Object substr(Map<String, Object> input) {
        require(input, "substr", 2, 3, "value", "start", "length");
        String value = text(argument(input, "value", "arg0"));
        int start = integer(argument(input, "start", "arg1"), "substr", "start");
        int normalizedStart = start < 0 ? value.length() + start : start;
        if (normalizedStart < 0 || normalizedStart > value.length()) {
            throw new IllegalArgumentException("substr() start is outside the text: " + start);
        }
        Object rawLength = argument(input, "length", "arg2");
        if (rawLength == null) return value.substring(normalizedStart);
        int length = integer(rawLength, "substr", "length");
        if (length < 0) throw new IllegalArgumentException("substr() length must be zero or greater: " + length);
        long requestedEnd = (long) normalizedStart + length;
        int end = requestedEnd > value.length() ? value.length() : (int) requestedEnd;
        return value.substring(normalizedStart, end);
    }

    private Object indexOf(Map<String, Object> input) {
        require(input, "indexOf", 2, 3, "value", "search", "fromIndex");
        String value = text(argument(input, "value", "arg0"));
        String search = text(argument(input, "search", "arg1"));
        Object rawFrom = argument(input, "fromIndex", "arg2");
        int found = rawFrom == null ? value.indexOf(search)
                : value.indexOf(search, integer(rawFrom, "indexOf", "fromIndex"));
        return String.valueOf(found);
    }

    private Object pad(Map<String, Object> input, String function) {
        String display = displayName(function);
        require(input, display, 2, 3, "value", "length", "pad");
        String value = text(argument(input, "value", "arg0"));
        int targetLength = boundedSize(argument(input, "length", "arg1"), display, "length");
        String padding = input.containsKey("pad") || input.containsKey("arg2")
                ? text(argument(input, "pad", "arg2")) : " ";
        if (padding.isEmpty()) throw new IllegalArgumentException(display + "() pad must not be empty");
        if (value.length() >= targetLength) return value;
        String fill = repeatToLength(padding, targetLength - value.length());
        return "padleft".equals(function) ? fill + value : value + fill;
    }

    private Object formatDate(Map<String, Object> input) {
        require(input, "formatDate", 2, 3, "value", "pattern", "zoneId");
        String value = text(argument(input, "value", "arg0"));
        String pattern = text(argument(input, "pattern", "arg1"));
        if (pattern.isEmpty()) throw new IllegalArgumentException("formatDate() pattern must not be empty");
        boolean zoneConfigured = input.containsKey("zoneId") || input.containsKey("arg2");
        ZoneId zone = zoneConfigured ? zoneId(argument(input, "zoneId", "arg2"), "formatDate") : clock.getZone();
        ParsedTemporal parsed = parseTemporal(value, "formatDate");
        TemporalAccessor temporal = parsed.forFormat(zone, zoneConfigured);
        try { return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(temporal); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("formatDate() has an invalid pattern: " + pattern, e); }
        catch (DateTimeException e) { throw new IllegalArgumentException("formatDate() pattern is incompatible with value: " + value, e); }
    }

    private Object dateAdd(Map<String, Object> input) {
        require(input, "dateAdd", 3, 3, "value", "amount", "unit");
        String value = text(argument(input, "value", "arg0"));
        long amount = longInteger(argument(input, "amount", "arg1"), "dateAdd", "amount");
        ChronoUnit unit = chronoUnit(text(argument(input, "unit", "arg2")));
        ParsedTemporal parsed = parseTemporal(value, "dateAdd");
        try { return parsed.formatSame(parsed.temporal.plus(amount, unit)); }
        catch (DateTimeException e) {
            throw new IllegalArgumentException("dateAdd() unit " + unit.name().toLowerCase(Locale.ROOT)
                    + " is incompatible with value: " + value, e);
        }
    }

    private static boolean isSingleValueFunction(String function) {
        return "upper".equals(function) || "lower".equals(function) || "trim".equals(function)
                || "ltrim".equals(function) || "rtrim".equals(function) || "string".equals(function)
                || "number".equals(function) || "boolean".equals(function) || "length".equals(function);
    }

    private static Object singleValue(Map<String, Object> input, String function) {
        if (input.size() != 1 || !(input.containsKey("value") || input.containsKey("arg0"))) {
            throw new IllegalArgumentException(displayName(function) + "() requires exactly one value argument");
        }
        return argument(input, "value", "arg0");
    }

    private static Object argument(Map<String, Object> input, String named, String positional) {
        return input.containsKey(named) ? input.get(named) : input.get(positional);
    }

    private static void require(Map<String, Object> input, String function, int minimum, int maximum, String... named) {
        if (input.size() < minimum || input.size() > maximum) {
            String count = minimum == maximum ? String.valueOf(minimum) : minimum + " to " + maximum;
            throw new IllegalArgumentException(function + "() requires " + count + " arguments");
        }
        for (int i = 0; i < minimum; i++) if (!(input.containsKey("arg" + i) || input.containsKey(named[i]))) {
            throw new IllegalArgumentException(function + "() requires argument " + named[i]);
        }
        for (String key : input.keySet()) {
            boolean allowed = false;
            for (int i = 0; i < maximum; i++) if (key.equals("arg" + i) || key.equals(named[i])) allowed = true;
            if (!allowed) throw new IllegalArgumentException("Unknown argument '" + key + "' for built-in " + function);
        }
        rejectMixedArgumentStyles(input, function);
    }

    private static void rejectMixedArgumentStyles(Map<String, Object> input, String function) {
        boolean positional = false, namedArguments = false;
        for (String key : input.keySet()) {
            if (key.matches("arg[0-9]+")) positional = true;
            else namedArguments = true;
        }
        if (positional && namedArguments) throw new IllegalArgumentException(function + "() must not mix named and positional arguments");
    }

    private static boolean booleanValue(Object value, String function) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        String text = text(value);
        if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text) || "0".equals(text)) return false;
        throw new IllegalArgumentException(function + "() requires true/false, yes/no, or 1/0: " + text);
    }

    private static int integer(Object value, String function, String argument) {
        long number = longInteger(value, function, argument);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(function + "() " + argument + " must be an integer: " + value);
        }
        return (int) number;
    }

    private static int boundedSize(Object value, String function, String argument) {
        int count = integer(value, function, argument);
        if (count < 0 || count > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(function + "() " + argument + " must be between 0 and " + MAX_TEXT_LENGTH + ": " + count);
        }
        return count;
    }

    private static long longInteger(Object value, String function, String argument) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        try { return new BigDecimal(text(value).trim()).longValueExact(); }
        catch (RuntimeException e) { throw new IllegalArgumentException(function + "() " + argument + " must be an integer: " + value); }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static String repeatToLength(String value, int length) {
        StringBuilder result = new StringBuilder(length);
        while (result.length() < length) result.append(value);
        if (result.length() > length) result.setLength(length);
        return result.toString();
    }

    private static String trimLeft(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) <= ' ') index++;
        return value.substring(index);
    }

    private static String trimRight(String value) {
        int index = value.length();
        while (index > 0 && value.charAt(index - 1) <= ' ') index--;
        return value.substring(0, index);
    }

    private static ZoneId zoneId(Object value, String function) {
        try { return ZoneId.of(text(value)); }
        catch (DateTimeException e) { throw new IllegalArgumentException(function + "() has an invalid zoneId: " + value, e); }
    }

    private static ChronoUnit chronoUnit(String value) {
        String unit = value.trim().toLowerCase(Locale.ROOT);
        if (unit.endsWith("s")) unit = unit.substring(0, unit.length() - 1);
        if ("year".equals(unit)) return ChronoUnit.YEARS;
        if ("month".equals(unit)) return ChronoUnit.MONTHS;
        if ("week".equals(unit)) return ChronoUnit.WEEKS;
        if ("day".equals(unit)) return ChronoUnit.DAYS;
        if ("hour".equals(unit)) return ChronoUnit.HOURS;
        if ("minute".equals(unit)) return ChronoUnit.MINUTES;
        if ("second".equals(unit)) return ChronoUnit.SECONDS;
        if ("millisecond".equals(unit) || "milli".equals(unit)) return ChronoUnit.MILLIS;
        throw new IllegalArgumentException("dateAdd() unit must be year, month, week, day, hour, minute, second, or millisecond: " + value);
    }

    private static ParsedTemporal parseTemporal(String value, String function) {
        try { return new ParsedTemporal(Instant.parse(value), TemporalKind.INSTANT); }
        catch (DateTimeParseException ignored) { }
        try { return new ParsedTemporal(ZonedDateTime.parse(value), TemporalKind.ZONED_DATE_TIME); }
        catch (DateTimeParseException ignored) { }
        try { return new ParsedTemporal(OffsetDateTime.parse(value), TemporalKind.OFFSET_DATE_TIME); }
        catch (DateTimeParseException ignored) { }
        try { return new ParsedTemporal(LocalDateTime.parse(value), TemporalKind.LOCAL_DATE_TIME); }
        catch (DateTimeParseException ignored) { }
        try { return new ParsedTemporal(LocalDate.parse(value), TemporalKind.LOCAL_DATE); }
        catch (DateTimeParseException ignored) { }
        throw new IllegalArgumentException(function + "() requires an ISO-8601 date or timestamp: " + value);
    }

    private static String displayName(String function) {
        if ("indexof".equals(function)) return "indexOf";
        if ("startswith".equals(function)) return "startsWith";
        if ("endswith".equals(function)) return "endsWith";
        if ("padleft".equals(function)) return "padLeft";
        if ("padright".equals(function)) return "padRight";
        if ("formatdate".equals(function)) return "formatDate";
        if ("dateadd".equals(function)) return "dateAdd";
        if ("randomchoice".equals(function)) return "randomChoice";
        return function;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private enum TemporalKind { INSTANT, ZONED_DATE_TIME, OFFSET_DATE_TIME, LOCAL_DATE_TIME, LOCAL_DATE }

    private static final class ParsedTemporal {
        private final Temporal temporal;
        private final TemporalKind kind;

        private ParsedTemporal(Temporal temporal, TemporalKind kind) {
            this.temporal = temporal;
            this.kind = kind;
        }

        private TemporalAccessor forFormat(ZoneId zone, boolean zoneConfigured) {
            if (kind == TemporalKind.INSTANT) return ((Instant) temporal).atZone(zone);
            if (kind == TemporalKind.ZONED_DATE_TIME && zoneConfigured) return ((ZonedDateTime) temporal).withZoneSameInstant(zone);
            if (kind == TemporalKind.OFFSET_DATE_TIME && zoneConfigured) return ((OffsetDateTime) temporal).atZoneSameInstant(zone);
            if (kind == TemporalKind.LOCAL_DATE_TIME && zoneConfigured) return ((LocalDateTime) temporal).atZone(zone);
            return (TemporalAccessor) temporal;
        }

        private String formatSame(Temporal value) {
            if (kind == TemporalKind.INSTANT) return DateTimeFormatter.ISO_INSTANT.format(value);
            if (kind == TemporalKind.ZONED_DATE_TIME) return DateTimeFormatter.ISO_ZONED_DATE_TIME.format(value);
            if (kind == TemporalKind.OFFSET_DATE_TIME) return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value);
            if (kind == TemporalKind.LOCAL_DATE_TIME) return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value);
            return DateTimeFormatter.ISO_LOCAL_DATE.format(value);
        }
    }
}
