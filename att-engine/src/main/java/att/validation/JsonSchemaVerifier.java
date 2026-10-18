package att.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Draft 2020-12 validation backed by the NetworkNT production validator. */
public final class JsonSchemaVerifier {
    private static final ObjectMapper JSON = JsonSupport.mapper();
    private static final int MAX_CACHED_SCHEMAS = 128;
    private static final Map<SchemaKey, com.networknt.schema.JsonSchema> CACHE =
            new LinkedHashMap<SchemaKey, com.networknt.schema.JsonSchema>(16, 0.75f, true);
    private static final AtomicLong COMPILES = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final Pattern UNKNOWN_PROPERTY = Pattern.compile("property '([A-Za-z_][A-Za-z0-9_-]*)' is not defined in the schema");
    private JsonSchemaVerifier() {}
    public static void verify(Path schemaFile, Object document) throws Exception { JsonNode value = document instanceof JsonNode ? (JsonNode) document : JSON.valueToTree(document); verify(compiled(schemaFile), value); }
    public static void verifyJson(Path schemaFile, Path documentFile) throws Exception { try (java.io.InputStream input = Files.newInputStream(documentFile)) { verify(compiled(schemaFile), JSON.readTree(input)); } }
    public static void verifyJson(Path schemaFile, String document) throws Exception { verify(compiled(schemaFile), JSON.readTree(document)); }
    public static void compile(Path schemaFile) throws Exception { compiled(schemaFile); }
    private static void verify(com.networknt.schema.JsonSchema validator, JsonNode value) {
        Set<com.networknt.schema.ValidationMessage> errors = validator.validate(value);
        if (!errors.isEmpty()) {
            java.util.List<String> messages = new java.util.ArrayList<String>();
            java.util.List<SchemaViolation> details = new java.util.ArrayList<SchemaViolation>();
            String firstField = null;
            for (com.networknt.schema.ValidationMessage error : errors) {
                String field = String.valueOf(error.getInstanceLocation());
                if (field == null || "null".equals(field) || field.isEmpty()) field = "$";
                Matcher unknownProperty = UNKNOWN_PROPERTY.matcher(error.getMessage());
                if (unknownProperty.find()) field += "." + unknownProperty.group(1);
                if (firstField == null || field.compareTo(firstField) < 0) firstField = field;
                String migration = field.endsWith(".level") && field.contains("actions")
                        ? " Log.level was removed; delete level and use a plain Log message or value + format. Severity does not affect Action status." : "";
                messages.add(field + ": " + error.getMessage() + migration + " (keyword=" + error.getCode() + ")");
                details.add(new SchemaViolation(field, error.getMessage(), error.getCode()));
            }
            java.util.Collections.sort(messages);
            throw new SchemaValidationException(firstField, messages, details);
        }
    }
    private static com.networknt.schema.JsonSchema schema(JsonNode schema) { com.networknt.schema.SchemaValidatorsConfig config = new com.networknt.schema.SchemaValidatorsConfig(); config.setFailFast(false); config.setTypeLoose(false); return com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schema, config); }

    private static com.networknt.schema.JsonSchema compiled(Path schemaFile) throws Exception {
        SchemaFiles.SchemaResource resource = SchemaFiles.readSchema(schemaFile);
        SchemaKey key = new SchemaKey(resource.identity());
        synchronized (CACHE) {
            com.networknt.schema.JsonSchema cached = CACHE.get(key);
            if (cached != null) { HITS.incrementAndGet(); return cached; }
        }
        JsonNode source = JSON.readTree(resource.bytes());
        validateLocalReferences(source, "$");
        com.networknt.schema.JsonSchema loaded = schema(source);
        synchronized (CACHE) {
            com.networknt.schema.JsonSchema previous = CACHE.put(key, loaded);
            if (previous != null) return previous;
            COMPILES.incrementAndGet();
            while (CACHE.size() > MAX_CACHED_SCHEMAS) {
                Iterator<SchemaKey> keys = CACHE.keySet().iterator();
                keys.next();
                keys.remove();
            }
        }
        return loaded;
    }

    /** Embedded ATT schemas may only reference fragments in the same schema document. */
    private static void validateLocalReferences(JsonNode node, String path) {
        if (node == null) return;
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode value = field.getValue();
                if ("$ref".equals(name) || "$dynamicRef".equals(name) || "$recursiveRef".equals(name)) {
                    if (!value.isTextual() || !value.asText().startsWith("#"))
                        throw new IllegalArgumentException("Schema reference must remain within its embedded resource at " + path + "." + name);
                }
                validateLocalReferences(value, path + "." + name);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) validateLocalReferences(node.get(i), path + "[" + i + "]");
        }
    }

    public static Stats stats() { return new Stats(COMPILES.get(), HITS.get()); }
    static int cachedValidatorCount() { synchronized (CACHE) { return CACHE.size(); } }
    static void clearForTests() { synchronized (CACHE) { CACHE.clear(); } COMPILES.set(0); HITS.set(0); }

    public static final class Stats {
        private final long compiles; private final long hits;
        private Stats(long compiles, long hits) { this.compiles = compiles; this.hits = hits; }
        public long compiles() { return compiles; }
        public long hits() { return hits; }
    }

    private static final class SchemaKey {
        private final String identity;
        private SchemaKey(String identity) { this.identity = identity; }
        @Override public boolean equals(Object value) { return value instanceof SchemaKey && identity.equals(((SchemaKey) value).identity); }
        @Override public int hashCode() { return identity.hashCode(); }
    }

    public static final class SchemaValidationException extends IllegalArgumentException {
        private final String field;
        private final java.util.List<String> violations;
        private final java.util.List<SchemaViolation> structuredViolations;
        private SchemaValidationException(String field, java.util.List<String> violations, java.util.List<SchemaViolation> structuredViolations) {
            super("Schema validation failed with " + violations.size() + " violation(s): " + String.join("; ", violations));
            this.field = field;
            this.violations = java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(violations));
            this.structuredViolations = java.util.Collections.unmodifiableList(new java.util.ArrayList<SchemaViolation>(structuredViolations));
        }
        public String field() { return field; }
        public java.util.List<String> violations() { return violations; }
        public java.util.List<SchemaViolation> structuredViolations() { return structuredViolations; }
        public static SchemaValidationException find(Throwable value) {
            Throwable current = value;
            while (current != null) { if (current instanceof SchemaValidationException) return (SchemaValidationException) current; current = current.getCause(); }
            return null;
        }
    }

    /** One schema violation retains the validator path and keyword separately from display text. */
    public static final class SchemaViolation {
        private final String path, message, keyword;
        private final SourceLocation source;
        private SchemaViolation(String path, String message, String keyword) { this(path, message, keyword, null); }
        private SchemaViolation(String path, String message, String keyword, SourceLocation source) {
            this.path = path; this.message = message; this.keyword = keyword; this.source = source;
        }
        public SchemaViolation withSource(SourceLocation source) { return new SchemaViolation(path, message, keyword, source); }
        public String path() { return path; }
        public String message() { return message; }
        public String keyword() { return keyword; }
        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
            map.put("path", path); map.put("message", message); map.put("keyword", keyword);
            if (source != null) map.put("source", source.toMap());
            return map;
        }
    }
}
