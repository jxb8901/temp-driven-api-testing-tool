package att.testdata;

import att.Version;
import att.config.YamlSupport;
import att.validation.JsonSchemaVerifier;
import att.validation.SchemaFiles;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads, schema-validates, and semantically validates one att-testdata descriptor. */
public final class TestdataDescriptorLoader {
    private static final Pattern SEQUENCE_REFERENCE = Pattern.compile("%\\{([^{}]*)}");
    private static final Pattern FORMAT = Pattern.compile("%d|%0[1-9][0-9]?d");
    private final Path projectRoot;

    public TestdataDescriptorLoader(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /** Reads only the logical id so ordinary execution can leave unreferenced descriptors inactive. */
    public String readId(Path file) throws Exception {
        Map<?, ?> map = readMap(file);
        Object id = map.get("id");
        if (!(id instanceof String) || !((String) id).matches("[A-Za-z][A-Za-z0-9_-]*"))
            throw new IllegalArgumentException("Testdata descriptor id must match [A-Za-z][A-Za-z0-9_-]*");
        return (String) id;
    }

    public TestdataDescriptor load(Path file) throws Exception {
        Map<?, ?> map = readMap(file);
        Object version = map.get("schemaVersion");
        if (!Version.TESTDATA_SCHEMA.equals(version))
            throw new IllegalArgumentException("Unsupported testdata schemaVersion; expected " + Version.TESTDATA_SCHEMA);
        JsonSchemaVerifier.verify(SchemaFiles.resolveVersion(projectRoot, Version.TESTDATA_SCHEMA), map);
        Object rawId = map.get("id");
        if (!(rawId instanceof String) || !((String) rawId).matches("[A-Za-z][A-Za-z0-9_-]*"))
            throw new IllegalArgumentException("testdata.id must match [A-Za-z][A-Za-z0-9_-]*");
        Object rawRecords = map.get("records");
        List<Object> literal = null;
        Long from = null;
        long count;
        String format = null;
        Object recordTemplate = null;
        if (rawRecords instanceof Map) {
            Map<?, ?> records = (Map<?, ?>) rawRecords;
            if (!records.containsKey("generate"))
                throw new IllegalArgumentException("testdata.records object must use the generated-record form");
            if (records.containsKey("record") == false || records.size() != 2)
                throw new IllegalArgumentException("testdata.records generated form requires only generate and record");
            Map<?, ?> generate = object(records.get("generate"), "testdata.records.generate");
            if (generate.size() != 1 || !generate.containsKey("seq"))
                throw new IllegalArgumentException("testdata.records.generate must declare exactly one seq integer range");
            Map<?, ?> sequence = object(generate.get("seq"), "testdata.records.generate.seq");
            long start = integer(sequence.get("from"), "testdata.records.generate.seq.from");
            long end = integer(sequence.get("to"), "testdata.records.generate.seq.to");
            if (start > end) throw new IllegalArgumentException("testdata.records.generate.seq.from must be <= to");
            try { count = Math.addExact(Math.subtractExact(end, start), 1L); }
            catch (ArithmeticException error) { throw new IllegalArgumentException("Generated testdata range is too large"); }
            if (count < 1 || count > TestdataDescriptor.MAX_GENERATED_RECORDS)
                throw new IllegalArgumentException("Generated testdata record count must be between 1 and " + TestdataDescriptor.MAX_GENERATED_RECORDS);
            Object rawFormat = sequence.get("format");
            format = rawFormat == null ? "%d" : String.valueOf(rawFormat);
            if (!FORMAT.matcher(format).matches())
                throw new IllegalArgumentException("testdata.records.generate.seq.format must be %d or a zero-padded integer format such as %04d");
            try { String.format(java.util.Locale.ROOT, format, Long.valueOf(start)); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Invalid generated testdata sequence format"); }
            recordTemplate = records.get("record");
            validateGeneratorReferences(recordTemplate);
            rejectCredentialFields(recordTemplate, "testdata.records.record");
            from = Long.valueOf(start);
        } else {
            if (!(rawRecords instanceof List)) throw new IllegalArgumentException("testdata.records must be a non-empty literal list or generated declaration");
            literal = new ArrayList<Object>((List<?>) rawRecords);
            if (literal.isEmpty()) throw new IllegalArgumentException("testdata.records literal list must not be empty");
            count = literal.size();
            rejectGeneratorReferences(literal, "testdata.records");
            rejectCredentialFields(literal, "testdata.records");
        }

        TestdataSelectionPolicy selection = null;
        if (map.get("selection") instanceof Map)
            selection = TestdataSelectionPolicy.parse((Map<?, ?>) map.get("selection"), "testdata.selection");
        if (count > 1 && selection == null)
            throw new IllegalArgumentException("testdata.selection is required when records contains more than one record");
        return new TestdataDescriptor((String) rawId, canonicalFile(file), literal, from, count,
                format, recordTemplate, selection);
    }

    private Map<?, ?> readMap(Path file) throws Exception {
        Path canonicalRoot = projectRoot.toRealPath();
        Path candidate = file.isAbsolute() ? file.normalize() : canonicalRoot.resolve(file).normalize();
        Path canonical = candidate.toRealPath();
        if (!canonical.startsWith(canonicalRoot) || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Testdata descriptor must be a regular package-contained non-symlink file");
        if (!(canonical.getFileName().toString().endsWith(".yaml") || canonical.getFileName().toString().endsWith(".yml")))
            throw new IllegalArgumentException("Testdata descriptor path must end in .yaml or .yml");
        Object loaded = YamlSupport.load(canonical);
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("Testdata descriptor must be a YAML map");
        return (Map<?, ?>) loaded;
    }

    private Path canonicalFile(Path file) {
        try { return (file.isAbsolute() ? file : projectRoot.resolve(file)).toRealPath(); }
        catch (Exception error) { throw new IllegalArgumentException("Unable to resolve testdata descriptor path"); }
    }

    private static Map<?, ?> object(Object value, String field) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(field + " must be an object");
        return (Map<?, ?>) value;
    }

    private static long integer(Object value, String field) {
        if (!(value instanceof Number) || value instanceof Float || value instanceof Double)
            throw new IllegalArgumentException(field + " must be an integer");
        try { return new java.math.BigDecimal(String.valueOf(value)).longValueExact(); }
        catch (ArithmeticException | NumberFormatException error) { throw new IllegalArgumentException(field + " must be a 64-bit integer"); }
    }

    private static void validateGeneratorReferences(Object value) {
        if (value instanceof String) {
            Matcher matcher = SEQUENCE_REFERENCE.matcher((String) value);
            while (matcher.find()) if (!"seq".equals(matcher.group(1)))
                throw new IllegalArgumentException("Only the declared %{seq} variable is valid in generated testdata records");
            if (((String) value).contains("%{") && !SEQUENCE_REFERENCE.matcher((String) value).find())
                throw new IllegalArgumentException("Malformed generated-record substitution");
            if (((String) value).contains("${") || ((String) value).contains("@{") || ((String) value).contains("#{"))
                throw new IllegalArgumentException("Generated testdata records support only %{seq} substitution");
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                validateGeneratorReferences(entry.getKey()); validateGeneratorReferences(entry.getValue());
            }
        } else if (value instanceof Iterable) for (Object item : (Iterable<?>) value) validateGeneratorReferences(item);
    }

    private static void rejectGeneratorReferences(Object value, String field) {
        if (value instanceof String && ((String) value).contains("%{"))
            throw new IllegalArgumentException("%{...} is reserved for generated record templates: " + field);
        if (value instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            rejectGeneratorReferences(entry.getKey(), field); rejectGeneratorReferences(entry.getValue(), field);
        }
        if (value instanceof Iterable) for (Object item : (Iterable<?>) value) rejectGeneratorReferences(item, field);
    }

    private static void rejectCredentialFields(Object value, String field) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.matches("(?i).*(password|passwd|passphrase|secret|token|authorization|credential|api[_-]?key|access[_-]?key|private[_-]?key).*"))
                    throw new IllegalArgumentException("Credential-like field names are not allowed in testdata records: " + field + "." + key);
                rejectCredentialFields(entry.getValue(), field + "." + key);
            }
        } else if (value instanceof Iterable) {
            int index = 0;
            for (Object item : (Iterable<?>) value) rejectCredentialFields(item, field + "[" + index++ + "]");
        }
    }

}
