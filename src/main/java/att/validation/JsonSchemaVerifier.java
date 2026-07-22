package att.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Draft 2020-12 validation backed by the NetworkNT production validator. */
public final class JsonSchemaVerifier {
    private static final ObjectMapper JSON = JsonSupport.mapper();
    private static final Map<SchemaKey, com.networknt.schema.JsonSchema> CACHE = new LinkedHashMap<SchemaKey, com.networknt.schema.JsonSchema>();
    private static final AtomicLong COMPILES = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private JsonSchemaVerifier() {}
    public static void verify(Path schemaFile, Object document) throws Exception { JsonNode value = document instanceof JsonNode ? (JsonNode) document : JSON.valueToTree(document); verify(compiled(schemaFile), value); }
    public static void verifyJson(Path schemaFile, Path documentFile) throws Exception { try (java.io.InputStream input = Files.newInputStream(documentFile)) { verify(compiled(schemaFile), JSON.readTree(input)); } }
    public static void verifyJson(Path schemaFile, String document) throws Exception { verify(compiled(schemaFile), JSON.readTree(document)); }
    public static void compile(Path schemaFile) throws Exception { compiled(schemaFile); }
    private static void verify(com.networknt.schema.JsonSchema validator, JsonNode value) {
        Set<com.networknt.schema.ValidationMessage> errors = validator.validate(value);
        if (!errors.isEmpty()) {
            java.util.List<String> messages = new java.util.ArrayList<String>();
            String firstField = null;
            for (com.networknt.schema.ValidationMessage error : errors) {
                String field = String.valueOf(error.getInstanceLocation());
                if (field == null || "null".equals(field) || field.isEmpty()) field = "$";
                if (firstField == null || field.compareTo(firstField) < 0) firstField = field;
                messages.add(field + ": " + error.getMessage() + " (keyword=" + error.getCode() + ")");
            }
            java.util.Collections.sort(messages);
            throw new SchemaValidationException(firstField, messages);
        }
    }
    private static com.networknt.schema.JsonSchema schema(JsonNode schema) { com.networknt.schema.SchemaValidatorsConfig config = new com.networknt.schema.SchemaValidatorsConfig(); config.setFailFast(false); config.setTypeLoose(false); return com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schema, config); }

    private static com.networknt.schema.JsonSchema compiled(Path schemaFile) throws Exception {
        Path canonical = schemaFile.toRealPath();
        SchemaKey key = new SchemaKey(canonical, Files.size(canonical), Files.getLastModifiedTime(canonical).toMillis());
        synchronized (CACHE) {
            com.networknt.schema.JsonSchema cached = CACHE.get(key);
            if (cached != null) { HITS.incrementAndGet(); return cached; }
        }
        JsonNode source;
        try (java.io.InputStream input = Files.newInputStream(canonical)) { source = JSON.readTree(input); }
        com.networknt.schema.JsonSchema loaded = schema(source);
        synchronized (CACHE) {
            java.util.Iterator<SchemaKey> keys = CACHE.keySet().iterator();
            while (keys.hasNext()) if (keys.next().path.equals(canonical)) keys.remove();
            com.networknt.schema.JsonSchema previous = CACHE.put(key, loaded);
            if (previous == null) COMPILES.incrementAndGet(); else return previous;
        }
        return loaded;
    }

    public static Stats stats() { return new Stats(COMPILES.get(), HITS.get()); }
    static void clearForTests() { synchronized (CACHE) { CACHE.clear(); } COMPILES.set(0); HITS.set(0); }

    public static final class Stats {
        private final long compiles; private final long hits;
        private Stats(long compiles, long hits) { this.compiles = compiles; this.hits = hits; }
        public long compiles() { return compiles; }
        public long hits() { return hits; }
    }

    private static final class SchemaKey {
        private final Path path; private final long size; private final long modified;
        private SchemaKey(Path path, long size, long modified) { this.path = path; this.size = size; this.modified = modified; }
        @Override public boolean equals(Object value) { if (!(value instanceof SchemaKey)) return false; SchemaKey other = (SchemaKey) value; return size == other.size && modified == other.modified && path.equals(other.path); }
        @Override public int hashCode() { int result = path.hashCode(); result = 31 * result + Long.valueOf(size).hashCode(); return 31 * result + Long.valueOf(modified).hashCode(); }
    }

    public static final class SchemaValidationException extends IllegalArgumentException {
        private final String field;
        private final java.util.List<String> violations;
        private SchemaValidationException(String field, java.util.List<String> violations) {
            super("Schema validation failed with " + violations.size() + " violation(s): " + String.join("; ", violations));
            this.field = field;
            this.violations = java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(violations));
        }
        public String field() { return field; }
        public java.util.List<String> violations() { return violations; }
        public static SchemaValidationException find(Throwable value) {
            Throwable current = value;
            while (current != null) { if (current instanceof SchemaValidationException) return (SchemaValidationException) current; current = current.getCause(); }
            return null;
        }
    }
}
