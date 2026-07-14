/* Author: Jeffrey + ChatGPT */
package att.template;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The only built-in provider registered by ATT V2.2. */
public final class DefaultBuiltInProvider implements BuiltInProvider {
    private static final int MAX_TEXT_LENGTH = 10000;
    private static final DateTimeFormatter SYSTEM_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "upper", "lower", "trim", "ltrim", "rtrim", "string", "number", "boolean", "length",
            "concat", "coalesce", "nvl", "iif", "nchar", "substr", "indexof", "contains",
            "startswith", "endswith", "replace", "padleft", "padright", "sysdate", "systimestamp",
            "formatdate", "dateadd")));

    private final Clock clock;

    public DefaultBuiltInProvider() {
        this(Clock.systemDefaultZone());
    }

    DefaultBuiltInProvider(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock is required");
        this.clock = clock;
    }

    @Override public Set<String> names() { return NAMES; }

    @Override public Object invoke(String name, Map<String, Object> input) {
        String function = name.toLowerCase(Locale.ROOT);
        if (!NAMES.contains(function)) throw new IllegalArgumentException("Unknown built-in: " + name);

        if ("sysdate".equals(function)) {
            requireNone(input, "sysdate");
            return DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.now(clock));
        }
        if ("systimestamp".equals(function)) {
            requireNone(input, "systimestamp");
            return SYSTEM_TIMESTAMP.format(OffsetDateTime.ofInstant(clock.instant(), clock.getZone()));
        }
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

    private static void requireNone(Map<String, Object> input, String function) {
        if (!input.isEmpty()) throw new IllegalArgumentException(function + "() does not accept arguments");
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
