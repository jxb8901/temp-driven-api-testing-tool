/* Author: Jeffrey + ChatGPT */
package att.config;

import att.Version;
import att.core.IdentifierValidator;
import att.validation.JsonSchemaVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads the explicit list of independent att-mqhelper/v1.0 files. */
public final class MqHelperConfigLoader {
    public Map<String, MqHelperConfig> load(Object configured, Path projectRoot) throws Exception {
        if (configured == null) return Collections.emptyMap();
        if (!(configured instanceof Iterable)) throw new IllegalArgumentException("mqhelpers must be a list of package-relative YAML paths");
        Path canonicalRoot = projectRoot.toRealPath();
        Path schema = projectRoot.resolve("schemas/att-mqhelper-v1.0.schema.json");
        Set<Path> files = new LinkedHashSet<Path>();
        Set<String> ids = new LinkedHashSet<String>();
        Map<String, MqHelperConfig> result = new LinkedHashMap<String, MqHelperConfig>();
        for (Object value : (Iterable<?>) configured) {
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw new IllegalArgumentException("mqhelpers paths must be non-blank strings");
            }
            String text = ((String) value).trim();
            if (!(text.endsWith(".yaml") || text.endsWith(".yml"))) {
                throw new IllegalArgumentException("mqhelper path must end in .yaml or .yml: " + text);
            }
            Path relative = IdentifierValidator.relativePath(text, "mqhelper path");
            Path logical = projectRoot.resolve(relative).normalize();
            if (!logical.startsWith(projectRoot.normalize())) throw new IllegalArgumentException("MQ helper path escapes package root: " + text);
            Path file = logical.toRealPath();
            if (!file.startsWith(canonicalRoot) || Files.isSymbolicLink(logical) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Missing/unsafe MQ helper file: " + text);
            }
            if (!files.add(file)) throw new IllegalArgumentException("Duplicate MQ helper path: " + text);
            MqHelperConfig helper;
            try {
                Map<?, ?> map = yaml(file);
                if (Files.isRegularFile(schema)) JsonSchemaVerifier.verify(schema, map);
                helper = parse(map, file);
            } catch (Exception error) {
                JsonSchemaVerifier.SchemaValidationException invalid = JsonSchemaVerifier.SchemaValidationException.find(error);
                String field = invalid == null ? "mqhelper" : invalid.field();
                att.validation.DiagnosticException diagnostic = att.validation.DiagnosticException.wrap(
                        att.validation.DiagnosticCodes.CONFIG_INVALID, "Invalid MQ helper configuration", error,
                        file.toString(), field,
                        "Correct this MQ helper field. Credential values are intentionally excluded from diagnostics.");
                if (invalid == null) throw YamlSupport.locate(diagnostic, file, field);
                throw YamlSupport.locateSchema(diagnostic, file, invalid.structuredViolations());
            }
            String normalizedId = helper.id().toLowerCase(Locale.ROOT);
            if (!ids.add(normalizedId)) throw new IllegalArgumentException("Duplicate MQ helper id ignoring case: " + helper.id());
            result.put(helper.id(), helper);
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<?, ?> yaml(Path file) throws Exception {
        Object loaded = YamlSupport.load(file);
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("MQ helper file must be a YAML map: " + file);
        return (Map<?, ?>) loaded;
    }

    private MqHelperConfig parse(Map<?, ?> map, Path file) {
        SchemaSupport.requireVersion(map, Version.MQHELPER_SCHEMA, "mqhelper");
        SchemaSupport.rejectUnknown(map, "mqhelper", "schemaVersion", "id", "name", "description", "connection", "message", "requestReply", "evidence");
        String id = SchemaSupport.string(map.get("id"), "mqhelper.id", true);
        if (!id.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("mqhelper.id must match [A-Za-z_][A-Za-z0-9_-]*: " + id);
        String name = SchemaSupport.string(map.get("name"), "mqhelper.name", true);
        String description = SchemaSupport.string(map.get("description"), "mqhelper.description", true);
        Map<?, ?> connection = SchemaSupport.map(map.get("connection"), "mqhelper.connection");
        SchemaSupport.rejectUnknown(connection, "mqhelper.connection", "queueManager", "host", "port", "channel", "username", "password");
        String queueManager = text(connection.get("queueManager"), "mqhelper.connection.queueManager");
        String host = text(connection.get("host"), "mqhelper.connection.host");
        String channel = text(connection.get("channel"), "mqhelper.connection.channel");
        int port = integer(connection.get("port"), 1414, 1, 65535, "mqhelper.connection.port");
        if (containsUnsafeConnectionText(queueManager) || containsUnsafeConnectionText(host) || containsUnsafeConnectionText(channel)) {
            throw new IllegalArgumentException("mqhelper connection values must not contain whitespace or control characters");
        }
        String username = environment(connection.get("username"), "mqhelper.connection.username");
        String password = environment(connection.get("password"), "mqhelper.connection.password");

        Map<?, ?> message = optionalMap(map.get("message"), "mqhelper.message");
        SchemaSupport.rejectUnknown(message, "mqhelper.message", "ccsid", "format", "persistence");
        int ccsid = integer(message.get("ccsid"), 1208, 1, 65535, "mqhelper.message.ccsid");
        String format = choice(message.get("format"), "MQSTR", "mqhelper.message.format", "MQSTR", "MQHRF2", "MQFMT_STRING", "MQFMT_NONE", "NONE");
        String persistence = choice(message.get("persistence"), "asQueue", "mqhelper.message.persistence", "asQueue", "persistent", "notPersistent", "nonPersistent");
        Map<?, ?> requestReply = optionalMap(map.get("requestReply"), "mqhelper.requestReply");
        SchemaSupport.rejectUnknown(requestReply, "mqhelper.requestReply", "waitMs");
        int waitMs = integer(requestReply.get("waitMs"), 10000, 0, 3600000, "mqhelper.requestReply.waitMs");
        Map<?, ?> evidence = optionalMap(map.get("evidence"), "mqhelper.evidence");
        SchemaSupport.rejectUnknown(evidence, "mqhelper.evidence", "payload");
        String payload = choice(evidence.get("payload"), "metadata", "mqhelper.evidence.payload", "metadata");
        return new MqHelperConfig(id, name, description, queueManager, host, port, channel,
                username, password, ccsid, format, persistence, waitMs, payload, file);
    }

    private Map<?, ?> optionalMap(Object value, String owner) {
        return value == null ? Collections.emptyMap() : SchemaSupport.map(value, owner);
    }

    private String text(Object value, String owner) {
        String result = SchemaSupport.string(value, owner, true);
        return result;
    }

    private String environment(Object value, String owner) {
        if (value == null) return "";
        String result = SchemaSupport.string(value, owner, false);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{ENV:([A-Za-z_][A-Za-z0-9_]*)}").matcher(result);
        if (!matcher.matches()) return result;
        String variable = matcher.group(1);
        String resolved = System.getenv(variable);
        if (resolved == null) throw new IllegalArgumentException(owner + " references missing environment variable " + variable);
        return resolved;
    }

    private boolean containsUnsafeConnectionText(String value) { return value.matches(".*[\\s\\p{Cntrl}].*"); }

    private int integer(Object value, int fallback, int min, int max, String owner) {
        if (value == null) return fallback;
        if (!(value instanceof Number)) throw new IllegalArgumentException(owner + " must be an integer");
        Number number = (Number) value;
        long integer = number.longValue();
        if (integer < min || integer > max || integer != number.doubleValue()) throw new IllegalArgumentException(owner + " must be an integer from " + min + " to " + max);
        return (int) integer;
    }

    private String choice(Object value, String fallback, String owner, String... allowed) {
        String result = value == null ? fallback : SchemaSupport.string(value, owner, true);
        for (String candidate : allowed) if (candidate.equals(result)) return result;
        throw new IllegalArgumentException(owner + " must be one of " + java.util.Arrays.asList(allowed));
    }
}
