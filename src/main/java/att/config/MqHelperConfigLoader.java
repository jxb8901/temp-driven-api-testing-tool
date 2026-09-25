/* Author: Jeffrey + ChatGPT */
package att.config;

import att.Version;
import att.core.IdentifierValidator;
import att.validation.JsonSchemaVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads the explicit list of att-mqhelper/v1.0 and v1.1 descriptor files. */
public final class MqHelperConfigLoader {
    public Map<String, MqHelperConfig> load(Object configured, Path projectRoot) throws Exception {
        if (configured == null) return Collections.emptyMap();
        if (!(configured instanceof Iterable)) throw new IllegalArgumentException("mqhelpers must be a list of package-relative YAML paths");
        Path canonicalRoot = projectRoot.toRealPath();
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
                Path schema = schema(projectRoot, map);
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

    private Path schema(Path projectRoot, Map<?, ?> map) {
        return Version.MQHELPER_SCHEMA_V1_1.equals(map.get("schemaVersion"))
                ? projectRoot.resolve("schemas/att-mqhelper-v1.1.schema.json")
                : projectRoot.resolve("schemas/att-mqhelper-v1.0.schema.json");
    }

    private MqHelperConfig parse(Map<?, ?> map, Path file) {
        if (Version.MQHELPER_SCHEMA_V1_1.equals(map.get("schemaVersion"))) return parseV11(map, file);
        return parseV10(map, file);
    }

    private MqHelperConfig parseV10(Map<?, ?> map, Path file) {
        SchemaSupport.requireVersion(map, Version.MQHELPER_SCHEMA, "mqhelper");
        SchemaSupport.rejectUnknown(map, "mqhelper", "schemaVersion", "id", "name", "description", "connection", "message", "requestReply", "evidence", "pool");
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
        SchemaSupport.rejectUnknown(message, "mqhelper.message", "charset", "ccsid", "encoding", "format", "persistence", "expiry", "requestQueue", "replyQueue");
        Integer charsetValue = optionalInteger(message.get("charset"), 1, 65535, "mqhelper.message.charset");
        Integer ccsidValue = optionalInteger(message.get("ccsid"), 1, 65535, "mqhelper.message.ccsid");
        if (charsetValue != null && ccsidValue != null && !charsetValue.equals(ccsidValue)) {
            throw new IllegalArgumentException("mqhelper.message.charset and ccsid must resolve to the same IBM MQ CCSID");
        }
        int charset = charsetValue != null ? charsetValue.intValue() : (ccsidValue == null ? 1208 : ccsidValue.intValue());
        Integer encoding = optionalInteger(message.get("encoding"), 0, Integer.MAX_VALUE, "mqhelper.message.encoding");
        Integer expiry = optionalInteger(message.get("expiry"), -1, Integer.MAX_VALUE, "mqhelper.message.expiry");
        String format = format(message.get("format"));
        String persistence = persistence(message.get("persistence"));
        String requestQueue = queue(message.get("requestQueue"), "mqhelper.message.requestQueue");
        String replyQueue = queue(message.get("replyQueue"), "mqhelper.message.replyQueue");
        Map<?, ?> requestReply = optionalMap(map.get("requestReply"), "mqhelper.requestReply");
        SchemaSupport.rejectUnknown(requestReply, "mqhelper.requestReply", "waitMs");
        int waitMs = integer(requestReply.get("waitMs"), 10000, 0, 3600000, "mqhelper.requestReply.waitMs");
        Map<?, ?> evidence = optionalMap(map.get("evidence"), "mqhelper.evidence");
        SchemaSupport.rejectUnknown(evidence, "mqhelper.evidence", "payload");
        String payload = choice(evidence.get("payload"), "metadata", "mqhelper.evidence.payload", "metadata");
        Map<?, ?> pool = optionalMap(map.get("pool"), "mqhelper.pool");
        SchemaSupport.rejectUnknown(pool, "mqhelper.pool", "maxSize", "minIdle", "borrowTimeout");
        int poolMaxSize = integer(pool.get("maxSize"), 20, 1, 10000, "mqhelper.pool.maxSize");
        int poolMinIdle = integer(pool.get("minIdle"), 0, 0, poolMaxSize, "mqhelper.pool.minIdle");
        long borrowTimeout = durationMs(pool.get("borrowTimeout"), 2000L, "mqhelper.pool.borrowTimeout");
        return new MqHelperConfig(id, name, description, queueManager, host, port, channel,
                username, password, charset, encoding, expiry, format, persistence,
                requestQueue, replyQueue, waitMs, payload, poolMaxSize, poolMinIdle, borrowTimeout, file);
    }

    private MqHelperConfig parseV11(Map<?, ?> map, Path file) {
        SchemaSupport.requireVersion(map, Version.MQHELPER_SCHEMA_V1_1, "mqhelper");
        SchemaSupport.rejectUnknown(map, "mqhelper", "schemaVersion", "id", "name", "description", "defaults", "instances", "selection", "evidence");
        String id = SchemaSupport.string(map.get("id"), "mqhelper.id", true);
        if (!id.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("mqhelper.id must match [A-Za-z_][A-Za-z0-9_-]*: " + id);
        String name = SchemaSupport.string(map.get("name"), "mqhelper.name", true);
        String description = SchemaSupport.string(map.get("description"), "mqhelper.description", true);

        Map<?, ?> defaults = optionalMap(map.get("defaults"), "mqhelper.defaults");
        SchemaSupport.rejectUnknown(defaults, "mqhelper.defaults", "connection", "message", "requestReply", "pool");
        Map<?, ?> defaultConnection = section(defaults, "connection", "mqhelper.defaults.connection");
        Map<?, ?> defaultMessage = section(defaults, "message", "mqhelper.defaults.message");
        Map<?, ?> defaultRequestReply = section(defaults, "requestReply", "mqhelper.defaults.requestReply");
        Map<?, ?> defaultPool = section(defaults, "pool", "mqhelper.defaults.pool");
        validateConnectionFields(defaultConnection, "mqhelper.defaults.connection", false);
        validateMessageFields(defaultMessage, "mqhelper.defaults.message");
        validateRequestReplyFields(defaultRequestReply, "mqhelper.defaults.requestReply");
        validatePoolFields(defaultPool, "mqhelper.defaults.pool");

        Map<?, ?> evidence = optionalMap(map.get("evidence"), "mqhelper.evidence");
        SchemaSupport.rejectUnknown(evidence, "mqhelper.evidence", "payload");
        String payload = choice(evidence.get("payload"), "metadata", "mqhelper.evidence.payload", "metadata");
        Map<?, ?> selection = optionalMap(map.get("selection"), "mqhelper.selection");
        SchemaSupport.rejectUnknown(selection, "mqhelper.selection", "strategy");
        String configuredStrategy = selection.get("strategy") == null ? "" : SchemaSupport.string(selection.get("strategy"), "mqhelper.selection.strategy", true);
        if (!configuredStrategy.isEmpty() && !("random".equals(configuredStrategy) || "roundRobin".equals(configuredStrategy))) {
            throw new IllegalArgumentException("mqhelper.selection.strategy must be random or roundRobin");
        }

        Object rawInstances = map.get("instances");
        if (!(rawInstances instanceof Iterable)) throw new IllegalArgumentException("mqhelper.instances must be a non-empty list");
        List<Object> values = new ArrayList<Object>();
        for (Object value : (Iterable<?>) rawInstances) values.add(value);
        if (values.isEmpty()) throw new IllegalArgumentException("mqhelper.instances must be a non-empty list");
        Map<String, MqHelperConfig> instances = new LinkedHashMap<String, MqHelperConfig>();
        Set<String> instanceIds = new LinkedHashSet<String>();
        for (Object value : values) {
            Map<?, ?> instance = SchemaSupport.map(value, "mqhelper.instances[]");
            SchemaSupport.rejectUnknown(instance, "mqhelper.instances[]", "id", "connection", "message", "requestReply", "pool");
            String instanceId = SchemaSupport.string(instance.get("id"), "mqhelper.instances[].id", true);
            if (!instanceId.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("mqhelper.instances[].id must match [A-Za-z_][A-Za-z0-9_-]*: " + instanceId);
            if (!instanceIds.add(instanceId.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate MQ physical instance id ignoring case: " + instanceId);
            Map<?, ?> instanceConnection = section(instance, "connection", "mqhelper.instances[].connection");
            Map<?, ?> instanceMessage = section(instance, "message", "mqhelper.instances[].message");
            Map<?, ?> instanceRequestReply = section(instance, "requestReply", "mqhelper.instances[].requestReply");
            Map<?, ?> instancePool = section(instance, "pool", "mqhelper.instances[].pool");
            validateConnectionFields(instanceConnection, "mqhelper.instances[].connection", false);
            validateMessageFields(instanceMessage, "mqhelper.instances[].message");
            validateRequestReplyFields(instanceRequestReply, "mqhelper.instances[].requestReply");
            validatePoolFields(instancePool, "mqhelper.instances[].pool");
            Map<?, ?> connection = merge(defaultConnection, instanceConnection);
            Map<?, ?> message = merge(defaultMessage, instanceMessage);
            Map<?, ?> requestReply = merge(defaultRequestReply, instanceRequestReply);
            Map<?, ?> pool = merge(defaultPool, instancePool);
            MqHelperConfig physical = parseV11Instance(id, name, description, instanceId, connection, message, requestReply, pool, payload, file);
            instances.put(instanceId, physical);
        }
        if (instances.size() > 1 && configuredStrategy.isEmpty()) {
            throw new IllegalArgumentException("mqhelper.selection.strategy is required when more than one physical instance is declared");
        }
        String strategy = instances.size() == 1 ? "single" : configuredStrategy;
        return MqHelperConfig.group(id, name, description, strategy, instances, payload, file);
    }

    private MqHelperConfig parseV11Instance(String logicalId, String name, String description, String instanceId,
                                            Map<?, ?> connection, Map<?, ?> message, Map<?, ?> requestReply,
                                            Map<?, ?> pool, String payload, Path file) {
        validateConnectionFields(connection, "mqhelper.instances[" + instanceId + "].connection", true);
        String queueManager = text(connection.get("queueManager"), "mqhelper.instances[" + instanceId + "].connection.queueManager");
        String host = text(connection.get("host"), "mqhelper.instances[" + instanceId + "].connection.host");
        String channel = text(connection.get("channel"), "mqhelper.instances[" + instanceId + "].connection.channel");
        int port = integer(connection.get("port"), 0, 1, 65535, "mqhelper.instances[" + instanceId + "].connection.port");
        if (containsUnsafeConnectionText(queueManager) || containsUnsafeConnectionText(host) || containsUnsafeConnectionText(channel)) {
            throw new IllegalArgumentException("mqhelper connection values must not contain whitespace or control characters");
        }
        String username = environment(connection.get("username"), "mqhelper.instances[" + instanceId + "].connection.username");
        String password = environment(connection.get("password"), "mqhelper.instances[" + instanceId + "].connection.password");
        Integer charsetValue = optionalInteger(message.get("charset"), 1, 65535, "mqhelper.instances[" + instanceId + "].message.charset");
        Integer ccsidValue = optionalInteger(message.get("ccsid"), 1, 65535, "mqhelper.instances[" + instanceId + "].message.ccsid");
        if (charsetValue != null && ccsidValue != null && !charsetValue.equals(ccsidValue)) {
            throw new IllegalArgumentException("mqhelper message charset and ccsid must resolve to the same IBM MQ CCSID");
        }
        int charset = charsetValue != null ? charsetValue.intValue() : (ccsidValue == null ? 1208 : ccsidValue.intValue());
        Integer encoding = optionalInteger(message.get("encoding"), 0, Integer.MAX_VALUE, "mqhelper.instances[" + instanceId + "].message.encoding");
        Integer expiry = optionalInteger(message.get("expiry"), -1, Integer.MAX_VALUE, "mqhelper.instances[" + instanceId + "].message.expiry");
        String format = format(message.get("format"));
        String persistence = persistence(message.get("persistence"));
        String requestQueue = queue(message.get("requestQueue"), "mqhelper.instances[" + instanceId + "].message.requestQueue");
        String replyQueue = queue(message.get("replyQueue"), "mqhelper.instances[" + instanceId + "].message.replyQueue");
        int waitMs = integer(requestReply.get("waitMs"), 10000, 0, 3600000, "mqhelper.instances[" + instanceId + "].requestReply.waitMs");
        int poolMaxSize = integer(pool.get("maxSize"), 20, 1, 10000, "mqhelper.instances[" + instanceId + "].pool.maxSize");
        int poolMinIdle = integer(pool.get("minIdle"), 0, 0, poolMaxSize, "mqhelper.instances[" + instanceId + "].pool.minIdle");
        long borrowTimeout = durationMs(pool.get("borrowTimeout"), 2000L, "mqhelper.instances[" + instanceId + "].pool.borrowTimeout");
        MqHelperConfig flat = new MqHelperConfig(instanceId, name, description, queueManager, host, port, channel,
                username, password, charset, encoding, expiry, format, persistence, requestQueue, replyQueue,
                waitMs, payload, poolMaxSize, poolMinIdle, borrowTimeout, file);
        return MqHelperConfig.physical(flat, logicalId, instanceId);
    }

    private Map<?, ?> section(Map<?, ?> owner, String key, String path) {
        return optionalMap(owner.get(key), path);
    }

    private Map<?, ?> merge(Map<?, ?> inherited, Map<?, ?> override) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : inherited.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        for (Map.Entry<?, ?> entry : override.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    private void validateConnectionFields(Map<?, ?> connection, String owner, boolean effective) {
        SchemaSupport.rejectUnknown(connection, owner, "queueManager", "host", "port", "channel", "username", "password");
        if (effective) {
            if (connection.get("queueManager") == null || connection.get("host") == null || connection.get("port") == null || connection.get("channel") == null) {
                throw new IllegalArgumentException(owner + " must resolve queueManager, host, port, and channel for every physical instance");
            }
        }
        if (connection.get("queueManager") != null) text(connection.get("queueManager"), owner + ".queueManager");
        if (connection.get("host") != null) text(connection.get("host"), owner + ".host");
        if (connection.get("channel") != null) text(connection.get("channel"), owner + ".channel");
        if (connection.get("port") != null) integer(connection.get("port"), 0, 1, 65535, owner + ".port");
        if (connection.get("username") != null) SchemaSupport.string(connection.get("username"), owner + ".username", false);
        if (connection.get("password") != null) SchemaSupport.string(connection.get("password"), owner + ".password", false);
    }

    private void validateMessageFields(Map<?, ?> message, String owner) {
        SchemaSupport.rejectUnknown(message, owner, "charset", "ccsid", "encoding", "format", "persistence", "expiry", "requestQueue", "replyQueue");
    }

    private void validateRequestReplyFields(Map<?, ?> requestReply, String owner) {
        SchemaSupport.rejectUnknown(requestReply, owner, "waitMs");
        if (requestReply.get("waitMs") != null) integer(requestReply.get("waitMs"), 0, 0, 3600000, owner + ".waitMs");
    }

    private void validatePoolFields(Map<?, ?> pool, String owner) {
        SchemaSupport.rejectUnknown(pool, owner, "maxSize", "minIdle", "borrowTimeout");
        if (pool.get("maxSize") != null) integer(pool.get("maxSize"), 0, 1, 10000, owner + ".maxSize");
        if (pool.get("minIdle") != null) integer(pool.get("minIdle"), 0, 0, 10000, owner + ".minIdle");
        if (pool.get("borrowTimeout") != null) durationMs(pool.get("borrowTimeout"), 2000L, owner + ".borrowTimeout");
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

    private Integer optionalInteger(Object value, int min, int max, String owner) {
        return value == null ? null : Integer.valueOf(integer(value, 0, min, max, owner));
    }

    private long durationMs(Object value, long fallback, String owner) {
        if (value == null) return fallback;
        if (value instanceof Number) return Math.max(0L, ((Number) value).longValue());
        if (!(value instanceof String) || !((String) value).matches("[0-9]+(ms|s|m)")) throw new IllegalArgumentException(owner + " must use <integer>ms, s, or m");
        String text = (String) value; int suffix = text.endsWith("ms") ? 2 : 1; long amount = Long.parseLong(text.substring(0, text.length() - suffix));
        if (text.endsWith("ms")) return amount; if (text.endsWith("s")) return Math.multiplyExact(amount, 1000L); return Math.multiplyExact(amount, 60000L);
    }

    private String choice(Object value, String fallback, String owner, String... allowed) {
        String result = value == null ? fallback : SchemaSupport.string(value, owner, true);
        for (String candidate : allowed) if (candidate.equals(result)) return result;
        throw new IllegalArgumentException(owner + " must be one of " + java.util.Arrays.asList(allowed));
    }

    private String format(Object value) {
        if (value == null) return "MQSTR";
        if (!(value instanceof String)) throw new IllegalArgumentException("mqhelper.message.format must be a string");
        String result = (String) value;
        for (String candidate : new String[]{"", "MQSTR", "MQHRF2", "MQFMT_STRING", "MQFMT_NONE", "NONE"}) {
            if (candidate.equals(result)) return result;
        }
        throw new IllegalArgumentException("mqhelper.message.format must be one of [\"\", MQSTR, MQHRF2, MQFMT_STRING, MQFMT_NONE, NONE]");
    }

    private String persistence(Object value) {
        if (value == null) return "asQueue";
        if (value instanceof Number) {
            Number number = (Number) value;
            if (number.doubleValue() != number.longValue() || number.longValue() < 0 || number.longValue() > 2) {
                throw new IllegalArgumentException("mqhelper.message.persistence numeric value must be 0, 1, or 2");
            }
            return String.valueOf(number.longValue());
        }
        return choice(value, "asQueue", "mqhelper.message.persistence", "asQueue", "persistent", "notPersistent", "nonPersistent");
    }

    private String queue(Object value, String owner) {
        if (value == null) return "";
        String result = SchemaSupport.string(value, owner, true);
        if (!result.matches("[A-Za-z0-9_.%/-]{1,48}")) throw new IllegalArgumentException(owner + " must be a valid IBM MQ queue name");
        return result;
    }
}
