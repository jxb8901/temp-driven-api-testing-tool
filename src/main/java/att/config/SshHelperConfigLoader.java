package att.config;

import att.core.IdentifierValidator;
import att.validation.JsonSchemaVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict, package-contained loader for att-sshhelper/v1.0 descriptors. */
public final class SshHelperConfigLoader {
    public Map<String, SshHelperConfig> load(Object configured, Path projectRoot) throws Exception {
        if (configured == null) return Collections.emptyMap();
        if (!(configured instanceof List)) throw new IllegalArgumentException("sshhelpers must be a list of YAML paths");
        Path root = projectRoot.toRealPath();
        Set<Path> files = new LinkedHashSet<Path>();
        Set<String> ids = new LinkedHashSet<String>();
        Map<String, SshHelperConfig> result = new LinkedHashMap<String, SshHelperConfig>();
        for (Object item : (List<?>) configured) {
            if (!(item instanceof String) || !((String) item).matches(".+\\.ya?ml")) throw new IllegalArgumentException("sshhelpers path must be a YAML file");
            Path logical = projectRoot.resolve(IdentifierValidator.relativePath((String) item, "sshhelper path")).normalize();
            if (!logical.startsWith(projectRoot.normalize()) || Files.isSymbolicLink(logical)) throw new IllegalArgumentException("Unsafe SSH helper path: " + item);
            Path file = logical.toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !files.add(file)) throw new IllegalArgumentException("Missing, unsafe or duplicate SSH helper file: " + item);
            Object loaded = YamlSupport.load(file);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("SSH helper must be a YAML map: " + file);
            Map<?, ?> map = (Map<?, ?>) loaded;
            Path schema = projectRoot.resolve("schemas/att-sshhelper-v1.0.schema.json");
            if (Files.isRegularFile(schema)) JsonSchemaVerifier.verify(schema, map);
            SshHelperConfig helper = parse(map);
            if (!ids.add(helper.id().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate SSH helper id ignoring case: " + helper.id());
            result.put(helper.id(), helper);
        }
        return Collections.unmodifiableMap(result);
    }

    private SshHelperConfig parse(Map<?, ?> map) {
        SchemaSupport.requireVersion(map, "att-sshhelper/v1.0", "sshhelper");
        SchemaSupport.rejectUnknown(map, "sshhelper", "schemaVersion", "id", "name", "description", "defaults", "instances", "selection", "fanout");
        String id = identifier(map.get("id"), "sshhelper.id");
        String name = optional(map.get("name"), "sshhelper.name");
        String description = optional(map.get("description"), "sshhelper.description");
        Map<?, ?> defaults = section(map.get("defaults"), "sshhelper.defaults", "user", "port", "identityFile");
        String defaultUser = optional(defaults.get("user"), "sshhelper.defaults.user");
        int defaultPort = port(defaults.get("port"), 22);
        String defaultIdentity = optional(defaults.get("identityFile"), "sshhelper.defaults.identityFile");
        Map<?, ?> selection = section(map.get("selection"), "sshhelper.selection", "strategy");
        String strategy = optional(selection.get("strategy"), "sshhelper.selection.strategy");
        if (!strategy.isEmpty() && !strategy.matches("random|roundRobin|all")) throw new IllegalArgumentException("Invalid SSH selection strategy: " + strategy);
        Map<?, ?> fanout = section(map.get("fanout"), "sshhelper.fanout", "maxConcurrency");
        int maxConcurrency = number(fanout.get("maxConcurrency"), 4, 1, 256, "sshhelper.fanout.maxConcurrency");
        if (!(map.get("instances") instanceof List) || ((List<?>) map.get("instances")).isEmpty()) throw new IllegalArgumentException("sshhelper.instances must be non-empty");
        Map<String, SshConfig> instances = new LinkedHashMap<String, SshConfig>();
        Set<String> keys = new LinkedHashSet<String>();
        for (Object item : (List<?>) map.get("instances")) {
            Map<?, ?> instance = section(item, "sshhelper.instances[]", "id", "host", "user", "port", "identityFile");
            String instanceId = identifier(instance.get("id"), "sshhelper.instances[].id");
            if (!keys.add(instanceId.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate SSH instance id ignoring case: " + instanceId);
            String host = required(instance.get("host"), "sshhelper.instances[].host");
            String user = instance.get("user") == null ? defaultUser : required(instance.get("user"), "sshhelper.instances[].user");
            if (user.isEmpty()) throw new IllegalArgumentException("sshhelper.instances[].user requires a value or defaults.user");
            if (host.matches(".*[\\s\\p{Cntrl}].*") || user.matches(".*[\\s\\p{Cntrl}].*")) throw new IllegalArgumentException("SSH host/user must not contain whitespace or controls");
            int port = port(instance.get("port"), defaultPort);
            String configuredIdentity = instance.get("identityFile") == null ? defaultIdentity : required(instance.get("identityFile"), "sshhelper.instances[].identityFile");
            java.util.regex.Matcher secret = java.util.regex.Pattern.compile("\\$\\{ENV:([A-Za-z_][A-Za-z0-9_]*)}").matcher(configuredIdentity);
            boolean fromEnvironment = secret.matches();
            String identity = configuredIdentity;
            if (fromEnvironment) {
                identity = System.getenv(secret.group(1));
                if (identity == null || identity.trim().isEmpty()) throw new IllegalArgumentException("sshhelper identityFile references missing or empty environment variable " + secret.group(1));
            }
            instances.put(instanceId, new SshConfig(host, user, port, identity, fromEnvironment));
        }
        if (instances.size() > 1 && strategy.isEmpty()) throw new IllegalArgumentException("sshhelper.selection.strategy is required for multiple instances");
        return new SshHelperConfig(id, name, description, strategy.isEmpty() ? "single" : strategy, maxConcurrency, instances);
    }

    private static Map<?, ?> section(Object value, String field, String... allowed) {
        if (value == null) return Collections.emptyMap();
        Map<?, ?> map = SchemaSupport.map(value, field);
        SchemaSupport.rejectUnknown(map, field, allowed);
        return map;
    }
    private static String identifier(Object value, String field) {
        String text = required(value, field);
        if (!text.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException(field + " has an invalid id");
        return text;
    }
    private static String required(Object value, String field) { return SchemaSupport.string(value, field, true); }
    private static String optional(Object value, String field) { return value == null ? "" : required(value, field); }
    private static int port(Object value, int fallback) { return number(value, fallback, 1, 65535, "sshhelper port"); }
    private static int number(Object value, int fallback, int min, int max, String field) {
        if (value == null) return fallback;
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()) throw new IllegalArgumentException(field + " must be an integer");
        int number = ((Number) value).intValue();
        if (number < min || number > max) throw new IllegalArgumentException(field + " must be " + min + ".." + max);
        return number;
    }
}
