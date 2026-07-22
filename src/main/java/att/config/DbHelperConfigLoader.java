/* Author: Jeffrey + ChatGPT */
package att.config;

import att.core.IdentifierValidator;
import att.validation.JsonSchemaVerifier;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads the explicit list of independent att-dbhelper/v2.5 configuration files. */
public final class DbHelperConfigLoader {
    public Map<String, DbHelperConfig> load(Object configured, Path projectRoot) throws Exception {
        if (configured == null) return Collections.emptyMap();
        if (!(configured instanceof Iterable)) throw new IllegalArgumentException("dbhelpers must be a list of package-relative YAML paths");
        Path canonicalRoot = projectRoot.toRealPath();
        Path schema = projectRoot.resolve("schemas/att-dbhelper-v2.5.schema.json");
        Set<Path> files = new LinkedHashSet<Path>();
        Set<String> ids = new LinkedHashSet<String>();
        Map<String, DbHelperConfig> result = new LinkedHashMap<String, DbHelperConfig>();
        for (Object value : (Iterable<?>) configured) {
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw new IllegalArgumentException("dbhelpers paths must be non-blank strings");
            }
            String text = ((String) value).trim();
            if (!(text.endsWith(".yaml") || text.endsWith(".yml"))) {
                throw new IllegalArgumentException("dbhelper path must end in .yaml or .yml: " + text);
            }
            Path relative = IdentifierValidator.relativePath(text, "dbhelper path");
            Path logical = projectRoot.resolve(relative).normalize();
            if (!logical.startsWith(projectRoot.normalize())) throw new IllegalArgumentException("Dbhelper path escapes package root: " + text);
            Path file = logical.toRealPath();
            if (!file.startsWith(canonicalRoot) || Files.isSymbolicLink(logical) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Missing/unsafe dbhelper file: " + text);
            }
            if (!files.add(file)) throw new IllegalArgumentException("Duplicate dbhelper path: " + text);
            Map<?, ?> map = yaml(file);
            if (Files.isRegularFile(schema)) JsonSchemaVerifier.verify(schema, map);
            DbHelperConfig helper = parse(map, file);
            String normalizedId = helper.id().toLowerCase(Locale.ROOT);
            if (!ids.add(normalizedId)) throw new IllegalArgumentException("Duplicate dbhelper id ignoring case: " + helper.id());
            result.put(helper.id(), helper);
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<?, ?> yaml(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object loaded;
            synchronized (YamlSupport.parser()) { loaded = YamlSupport.parser().load(reader); }
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Dbhelper file must be a YAML map: " + file);
            return (Map<?, ?>) loaded;
        }
    }

    private DbHelperConfig parse(Map<?, ?> map, Path file) {
        SchemaSupport.requireVersion(map, "att-dbhelper/v2.5", "dbhelper");
        SchemaSupport.rejectUnknown(map, "dbhelper", "schemaVersion", "id", "name", "description",
                "connection", "statement", "transaction", "result", "evidence");
        String id = SchemaSupport.string(map.get("id"), "dbhelper.id", true);
        if (!id.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("dbhelper.id must match [A-Za-z_][A-Za-z0-9_-]*: " + id);
        String name = SchemaSupport.string(map.get("name"), "dbhelper.name", true);
        String description = SchemaSupport.string(map.get("description"), "dbhelper.description", true);

        Map<?, ?> connection = SchemaSupport.map(map.get("connection"), "dbhelper.connection");
        SchemaSupport.rejectUnknown(connection, "dbhelper.connection", "url", "username", "password",
                "driverClass", "properties", "readOnly", "isolation");
        String url = environment(SchemaSupport.string(connection.get("url"), "dbhelper.connection.url", true), "dbhelper.connection.url");
        String username = optionalEnvironment(connection.get("username"), "dbhelper.connection.username");
        String password = optionalEnvironment(connection.get("password"), "dbhelper.connection.password");
        String driverClass = optional(connection.get("driverClass"), "dbhelper.connection.driverClass");
        boolean readOnly = bool(connection.get("readOnly"), false, "dbhelper.connection.readOnly");
        String isolation = choice(connection.get("isolation"), "driverDefault", "dbhelper.connection.isolation",
                "driverDefault", "readUncommitted", "readCommitted", "repeatableRead", "serializable");
        Map<String, String> properties = new LinkedHashMap<String, String>();
        if (connection.get("properties") != null) {
            for (Map.Entry<?, ?> entry : SchemaSupport.map(connection.get("properties"), "dbhelper.connection.properties").entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                    throw new IllegalArgumentException("dbhelper.connection.properties must contain string keys and values");
                }
                properties.put((String) entry.getKey(), environment((String) entry.getValue(),
                        "dbhelper.connection.properties." + entry.getKey()));
            }
        }

        Map<?, ?> statement = optionalMap(map.get("statement"), "dbhelper.statement");
        SchemaSupport.rejectUnknown(statement, "dbhelper.statement", "timeoutSeconds");
        int timeout = integer(statement.get("timeoutSeconds"), 30, 1, 3600, "dbhelper.statement.timeoutSeconds");

        Map<?, ?> transaction = optionalMap(map.get("transaction"), "dbhelper.transaction");
        SchemaSupport.rejectUnknown(transaction, "dbhelper.transaction", "scope", "onEnd");
        String scope = choice(transaction.get("scope"), "case", "dbhelper.transaction.scope", "case", "statement");
        String onEnd = choice(transaction.get("onEnd"), "rollback", "dbhelper.transaction.onEnd", "commit", "rollback");

        Map<?, ?> result = optionalMap(map.get("result"), "dbhelper.result");
        SchemaSupport.rejectUnknown(result, "dbhelper.result", "maxRows", "maxCellBytes", "maxBytes");
        int maxRows = integer(result.get("maxRows"), 1000, 1, 1000000, "dbhelper.result.maxRows");
        int maxCellBytes = integer(result.get("maxCellBytes"), 1048576, 1, 1073741824, "dbhelper.result.maxCellBytes");
        int maxBytes = integer(result.get("maxBytes"), 10485760, 1, 1073741824, "dbhelper.result.maxBytes");
        if (maxBytes < maxCellBytes) throw new IllegalArgumentException("dbhelper.result.maxBytes must be at least maxCellBytes");

        Map<?, ?> evidence = optionalMap(map.get("evidence"), "dbhelper.evidence");
        SchemaSupport.rejectUnknown(evidence, "dbhelper.evidence", "sql", "parameters");
        String evidenceSql = choice(evidence.get("sql"), "full", "dbhelper.evidence.sql", "full", "hash");
        String evidenceParameters = choice(evidence.get("parameters"), "masked", "dbhelper.evidence.parameters", "masked", "types", "values");

        return new DbHelperConfig(id, name, description, url, username, password, driverClass,
                properties, readOnly, isolation, timeout, scope, onEnd, maxRows, maxCellBytes, maxBytes,
                evidenceSql, evidenceParameters, file);
    }

    private Map<?, ?> optionalMap(Object value, String owner) {
        return value == null ? Collections.emptyMap() : SchemaSupport.map(value, owner);
    }

    private String optional(Object value, String owner) {
        return value == null ? "" : SchemaSupport.string(value, owner, false);
    }

    private String optionalEnvironment(Object value, String owner) {
        return value == null ? "" : environment(SchemaSupport.string(value, owner, false), owner);
    }

    private String environment(String value, String owner) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{ENV:([A-Za-z_][A-Za-z0-9_]*)}").matcher(value);
        if (!matcher.matches()) return value;
        String resolved = System.getenv(matcher.group(1));
        if (resolved == null) throw new IllegalArgumentException(owner + " references missing environment variable " + matcher.group(1));
        return resolved;
    }

    private boolean bool(Object value, boolean fallback, String owner) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(owner + " must be a boolean");
        return ((Boolean) value).booleanValue();
    }

    private int integer(Object value, int fallback, int min, int max, String owner) {
        if (value == null) return fallback;
        if (!(value instanceof Number)) throw new IllegalArgumentException(owner + " must be an integer");
        long number = ((Number) value).longValue();
        if (number < min || number > max || number != ((Number) value).doubleValue()) {
            throw new IllegalArgumentException(owner + " must be an integer from " + min + " to " + max);
        }
        return (int) number;
    }

    private String choice(Object value, String fallback, String owner, String... allowed) {
        String result = value == null ? fallback : SchemaSupport.string(value, owner, true);
        for (String candidate : allowed) if (candidate.equals(result)) return result;
        throw new IllegalArgumentException(owner + " must be one of " + java.util.Arrays.asList(allowed));
    }
}
