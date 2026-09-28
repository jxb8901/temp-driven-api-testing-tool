package att.config;

import att.Version;
import att.core.IdentifierValidator;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import att.validation.JsonSchemaVerifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates selected environment HTTPHelper descriptors before any network I/O. */
public final class HttpHelperConfigLoader {
    public Map<String, HttpHelperConfig> load(Object configured, Path projectRoot) throws Exception {
        if (configured == null) return Collections.emptyMap();
        if (!(configured instanceof List)) throw new IllegalArgumentException("httphelpers must be a list of YAML paths");
        Path root = projectRoot.toRealPath();
        Map<String, HttpHelperConfig> result = new LinkedHashMap<String, HttpHelperConfig>();
        Set<Path> files = new LinkedHashSet<Path>();
        Set<String> ids = new LinkedHashSet<String>();
        for (Object value : (List<?>) configured) {
            if (!(value instanceof String) || !((String) value).matches(".+\\.ya?ml"))
                throw new IllegalArgumentException("httphelper path must be a YAML file");
            Path logical = projectRoot.resolve(IdentifierValidator.relativePath((String) value, "httphelper path")).normalize();
            if (!logical.startsWith(projectRoot.normalize()) || Files.isSymbolicLink(logical))
                throw new IllegalArgumentException("Unsafe HTTP helper path: " + value);
            Path file = logical.toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !files.add(file))
                throw new IllegalArgumentException("Missing, unsafe or duplicate HTTP helper file: " + value);
            HttpHelperConfig helper;
            try {
                Object loaded = YamlSupport.load(file);
                if (!(loaded instanceof Map)) throw new IllegalArgumentException("HTTP helper must be a YAML map");
                Map<?, ?> map = (Map<?, ?>) loaded;
                Path localSchema = java.nio.file.Paths.get("schemas/att-httphelper-v1.0.schema.json").toAbsolutePath();
                Path schema = Files.isRegularFile(localSchema) ? localSchema
                        : projectRoot.resolve("schemas/att-httphelper-v1.0.schema.json");
                if (Files.isRegularFile(schema)) JsonSchemaVerifier.verify(schema, map);
                helper = parse(map, projectRoot);
            } catch (Exception error) {
                JsonSchemaVerifier.SchemaValidationException invalid = JsonSchemaVerifier.SchemaValidationException.find(error);
                String field = invalid == null ? "httphelper" : invalid.field();
                DiagnosticException diagnostic = DiagnosticException.wrap(DiagnosticCodes.CONFIG_INVALID,
                        "Invalid HTTP helper configuration", error, file.toString(), field,
                        "Correct this HTTP helper descriptor. Secret values are omitted from diagnostics.");
                if (invalid == null) throw YamlSupport.locate(diagnostic, file, field);
                throw YamlSupport.locateSchema(diagnostic, file, invalid.structuredViolations());
            }
            if (!ids.add(helper.id().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate HTTP helper id ignoring case: " + helper.id());
            result.put(helper.id(), helper);
        }
        return Collections.unmodifiableMap(result);
    }

    private HttpHelperConfig parse(Map<?, ?> map, Path root) throws Exception {
        SchemaSupport.requireVersion(map, Version.HTTPHELPER_SCHEMA, "httphelper");
        String id = SchemaSupport.string(map.get("id"), "httphelper.id", true);
        if (!id.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid HTTP helper id");
        String rawBase = SchemaSupport.string(map.get("baseUrl"), "httphelper.baseUrl", true);
        URI base;
        try { base = new URI(rawBase); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid HTTP helper baseUrl", error); }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getFragment() != null
                || base.getRawQuery() != null) throw new IllegalArgumentException("HTTP helper baseUrl must be an absolute HTTP(S) URL without credentials, query or fragment");
        Map<?, ?> defaults = section(map.get("defaults"), "defaults");
        Map<?, ?> pool = section(map.get("pool"), "pool");
        Map<?, ?> auth = section(map.get("auth"), "auth");
        Map<?, ?> tls = section(map.get("tls"), "tls");
        Map<String, String> headers = headers(defaults.get("headers"));
        int max = number(pool.get("maxConnections"), 50, "pool.maxConnections");
        int perRoute = number(pool.get("maxConnectionsPerRoute"), 20, "pool.maxConnectionsPerRoute");
        if (perRoute > max) throw new IllegalArgumentException("pool.maxConnectionsPerRoute must not exceed pool.maxConnections");
        String type = auth.get("type") == null ? "none" : String.valueOf(auth.get("type"));
        if (!("none".equals(type) || "basic".equals(type) || "bearer".equals(type))) throw new IllegalArgumentException("Invalid HTTP auth.type");
        if ("none".equals(type) && (auth.containsKey("username") || auth.containsKey("password") || auth.containsKey("token")))
            throw new IllegalArgumentException("HTTP auth.type none cannot include credentials");
        if ("basic".equals(type) && (!auth.containsKey("username") || !auth.containsKey("password") || auth.containsKey("token")))
            throw new IllegalArgumentException("HTTP basic auth requires username and password only");
        if ("bearer".equals(type) && (!auth.containsKey("token") || auth.containsKey("username") || auth.containsKey("password")))
            throw new IllegalArgumentException("HTTP bearer auth requires token only");
        String username = secret(auth.get("username"), "auth.username");
        String password = secret(auth.get("password"), "auth.password");
        String token = secret(auth.get("token"), "auth.token");
        if (("basic".equals(type) && (username.isEmpty() || password.isEmpty()))
                || ("bearer".equals(type) && token.isEmpty()))
            throw new IllegalArgumentException("HTTP auth credentials must not be empty");
        if (tls.containsKey("verifyHostname") && !Boolean.TRUE.equals(tls.get("verifyHostname")))
            throw new IllegalArgumentException("TLS hostname verification cannot be disabled");
        Path trust = null;
        if (tls.get("trustStore") != null) {
            String configured = secret(tls.get("trustStore"), "tls.trustStore");
            Path logical = root.resolve(IdentifierValidator.relativePath(configured, "tls.trustStore")).normalize();
            if (!logical.startsWith(root.normalize()) || Files.isSymbolicLink(logical)) throw new IllegalArgumentException("Unsafe HTTP TLS trustStore path");
            trust = logical.toRealPath();
            if (!trust.startsWith(root.toRealPath()) || !Files.isRegularFile(trust)) throw new IllegalArgumentException("Missing or unsafe HTTP TLS trustStore");
        }
        return new HttpHelperConfig(id, base, headers,
                number(defaults.get("connectTimeoutMs"), 5000, "defaults.connectTimeoutMs"),
                number(defaults.get("readTimeoutMs"), 30000, "defaults.readTimeoutMs"),
                Boolean.TRUE.equals(defaults.get("followRedirects")), max, perRoute,
                number(pool.get("connectionRequestTimeoutMs"), 5000, "pool.connectionRequestTimeoutMs"),
                number(pool.get("keepAliveMs"), 30000, "pool.keepAliveMs"),
                number(pool.get("idleEvictMs"), 60000, "pool.idleEvictMs"), type, username, password, token,
                trust, secret(tls.get("trustStorePassword"), "tls.trustStorePassword"));
    }

    private static Map<?, ?> section(Object value, String field) {
        return value == null ? Collections.emptyMap() : SchemaSupport.map(value, "httphelper." + field);
    }
    private static int number(Object value, int fallback, String field) {
        if (value == null) return fallback;
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                || ((Number) value).intValue() < 1 || ((Number) value).intValue() > 3600000)
            throw new IllegalArgumentException("Invalid HTTP " + field + " (must be a positive bounded integer)");
        return ((Number) value).intValue();
    }
    private static Map<String, String> headers(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value == null) return result;
        for (Map.Entry<?, ?> entry : SchemaSupport.map(value, "httphelper.defaults.headers").entrySet()) {
            String key = String.valueOf(entry.getKey());
            String header = String.valueOf(entry.getValue());
            if (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || header.contains("\r") || header.contains("\n"))
                throw new IllegalArgumentException("Invalid HTTP default header");
            for (String existing : result.keySet()) if (existing.equalsIgnoreCase(key)) throw new IllegalArgumentException("Duplicate HTTP default header ignoring case: " + key);
            result.put(key, header);
        }
        return result;
    }
    private static String secret(Object value, String field) {
        if (value == null) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("HTTP " + field + " must be a string");
        String configured = (String) value;
        if (configured.matches("\\$\\{ENV:[A-Za-z_][A-Za-z0-9_]*}")) {
            String name = configured.substring(6, configured.length() - 1);
            String resolved = System.getenv(name);
            if (resolved == null || resolved.isEmpty()) throw new IllegalArgumentException("HTTP " + field + " references missing or empty environment variable " + name);
            return resolved;
        }
        if (configured.contains("${")) throw new IllegalArgumentException("HTTP " + field + " has an invalid environment reference");
        return configured;
    }
}
