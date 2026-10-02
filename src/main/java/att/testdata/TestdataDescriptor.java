package att.testdata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validated inline testdata. Generated records are virtual and materialized by index. */
public final class TestdataDescriptor {
    public static final long MAX_GENERATED_RECORDS = 1_000_000L;
    private static final Pattern SEQUENCE_REFERENCE = Pattern.compile("%\\{([^{}]*)}");

    private final String id;
    private final Path source;
    private final List<Object> literalRecords;
    private final Long from;
    private final long count;
    private final String format;
    private final Object recordTemplate;
    private final TestdataSelectionPolicy selection;

    TestdataDescriptor(String id, Path source, List<Object> literalRecords,
                       Long from, long count, String format, Object recordTemplate,
                       TestdataSelectionPolicy selection) {
        this.id = id;
        this.source = source;
        this.literalRecords = literalRecords == null ? Collections.<Object>emptyList() : immutableList(literalRecords);
        this.from = from;
        this.count = count;
        this.format = format;
        this.recordTemplate = immutable(recordTemplate);
        this.selection = selection;
    }

    public String id() { return id; }
    public Path source() { return source; }
    public long count() { return count; }
    public boolean generated() { return from != null; }
    public TestdataSelectionPolicy selection() { return selection; }

    public Object record(long index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException("Testdata record index out of range");
        if (from == null) return immutable(literalRecords.get((int) index));
        long sequence;
        try { sequence = Math.addExact(from.longValue(), index); }
        catch (ArithmeticException error) { throw new IllegalStateException("Generated testdata sequence overflow"); }
        String value;
        try { value = String.format(Locale.ROOT, format, Long.valueOf(sequence)); }
        catch (RuntimeException error) { throw new IllegalStateException("Unable to format generated testdata sequence"); }
        return substitute(recordTemplate, value);
    }

    public Long sequence(long index) {
        if (!generated()) return null;
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException("Testdata record index out of range");
        return Long.valueOf(Math.addExact(from.longValue(), index));
    }

    private static Object substitute(Object value, String sequence) {
        if (value instanceof String) {
            Matcher matcher = SEQUENCE_REFERENCE.matcher((String) value);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                if (!"seq".equals(matcher.group(1))) throw new IllegalStateException("Only %{seq} is supported in generated testdata");
                matcher.appendReplacement(result, Matcher.quoteReplacement(sequence));
            }
            matcher.appendTail(result);
            if (result.indexOf("%{") >= 0) throw new IllegalStateException("Malformed generated-record substitution");
            return result.toString();
        }
        if (value instanceof Map) {
            Map<Object, Object> result = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object key = substitute(entry.getKey(), sequence);
                if (result.containsKey(key)) throw new IllegalStateException("Generated record substitution creates a duplicate map key");
                result.put(key, substitute(entry.getValue(), sequence));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(substitute(item, sequence));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    private static List<Object> immutableList(List<Object> values) {
        List<Object> result = new ArrayList<Object>(values.size());
        for (Object value : values) result.add(immutable(value));
        return Collections.unmodifiableList(result);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map) {
            Map<Object, Object> result = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) result.put(entry.getKey(), immutable(entry.getValue()));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(immutable(item));
            return Collections.unmodifiableList(result);
        }
        return value;
    }
}
