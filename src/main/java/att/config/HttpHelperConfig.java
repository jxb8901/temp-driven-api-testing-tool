package att.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable HTTP transport configuration; credentials are never rendered as evidence. */
public final class HttpHelperConfig {
    private ResourceOutputConfig evidenceOutput;
    public ResourceOutputConfig evidenceOutput() { return evidenceOutput; }
    HttpHelperConfig withEvidenceOutput(Object evidence) {
        this.evidenceOutput = ResourceOutputConfig.from(evidence);
        return this;
    }
    private final String id;
    private final URI baseUrl;
    private final Map<String, String> headers;
    private final int connectTimeoutMs, readTimeoutMs, maxConnections, maxConnectionsPerRoute;
    private final String responseFormat;
    private final int connectionRequestTimeoutMs, keepAliveMs, idleEvictMs;
    private final boolean followRedirects;
    private final String authType, username, password, token;
    private final Path trustStore;
    private final String trustStorePassword;

    public HttpHelperConfig(String id, URI baseUrl, Map<String, String> headers, int connectTimeoutMs,
                            int readTimeoutMs, boolean followRedirects, int maxConnections,
                            int maxConnectionsPerRoute, int connectionRequestTimeoutMs, int keepAliveMs,
                            int idleEvictMs, String authType, String username, String password, String token,
                            Path trustStore, String trustStorePassword) {
        this(id, baseUrl, headers, connectTimeoutMs, readTimeoutMs, followRedirects, maxConnections,
                maxConnectionsPerRoute, connectionRequestTimeoutMs, keepAliveMs, idleEvictMs,
                authType, username, password, token, trustStore, trustStorePassword, "auto");
    }

    public HttpHelperConfig(String id, URI baseUrl, Map<String, String> headers, int connectTimeoutMs,
                            int readTimeoutMs, boolean followRedirects, int maxConnections,
                            int maxConnectionsPerRoute, int connectionRequestTimeoutMs, int keepAliveMs,
                            int idleEvictMs, String authType, String username, String password, String token,
                            Path trustStore, String trustStorePassword, String responseFormat) {
        this.id = id; this.baseUrl = baseUrl;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
        this.connectTimeoutMs = connectTimeoutMs; this.readTimeoutMs = readTimeoutMs;
        this.followRedirects = followRedirects; this.maxConnections = maxConnections;
        this.maxConnectionsPerRoute = maxConnectionsPerRoute;
        this.connectionRequestTimeoutMs = connectionRequestTimeoutMs;
        this.keepAliveMs = keepAliveMs; this.idleEvictMs = idleEvictMs;
        this.authType = authType; this.username = username; this.password = password; this.token = token;
        this.trustStore = trustStore; this.trustStorePassword = trustStorePassword;
        this.responseFormat = responseFormat == null ? "auto" : responseFormat;
    }
    public String id() { return id; }
    public URI baseUrl() { return baseUrl; }
    public Map<String, String> headers() { return headers; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public String responseFormat() { return responseFormat; }
    public boolean followRedirects() { return followRedirects; }
    public int maxConnections() { return maxConnections; }
    public int maxConnectionsPerRoute() { return maxConnectionsPerRoute; }
    public int connectionRequestTimeoutMs() { return connectionRequestTimeoutMs; }
    public int keepAliveMs() { return keepAliveMs; }
    public int idleEvictMs() { return idleEvictMs; }
    public String authType() { return authType; }
    public String username() { return username; }
    public String password() { return password; }
    public String token() { return token; }
    public Path trustStore() { return trustStore; }
    public String trustStorePassword() { return trustStorePassword; }
}
