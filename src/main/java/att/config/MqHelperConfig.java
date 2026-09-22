/* Author: Jeffrey + ChatGPT */
package att.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable invocation-scoped IBM MQ client configuration. */
public final class MqHelperConfig {
    private final String id;
    private final String name;
    private final String description;
    private final String queueManager;
    private final String host;
    private final int port;
    private final String channel;
    private final String username;
    private final String password;
    private final int ccsid;
    private final String format;
    private final String persistence;
    private final int requestReplyWaitMs;
    private final String evidencePayload;
    private final Path sourceFile;
    private final int poolMaxSize, poolMinIdle;
    private final long poolBorrowTimeoutMs;

    public MqHelperConfig(String id, String name, String description, String queueManager,
                          String host, int port, String channel, String username, String password,
                          int ccsid, String format, String persistence, int requestReplyWaitMs,
                          String evidencePayload, Path sourceFile) {
        this(id, name, description, queueManager, host, port, channel, username, password, ccsid, format, persistence,
                requestReplyWaitMs, evidencePayload, 20, 0, 2000L, sourceFile);
    }

    public MqHelperConfig(String id, String name, String description, String queueManager,
                          String host, int port, String channel, String username, String password,
                          int ccsid, String format, String persistence, int requestReplyWaitMs,
                          String evidencePayload, int poolMaxSize, int poolMinIdle, long poolBorrowTimeoutMs, Path sourceFile) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.queueManager = queueManager;
        this.host = host;
        this.port = port;
        this.channel = channel;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.ccsid = ccsid;
        this.format = format;
        this.persistence = persistence;
        this.requestReplyWaitMs = requestReplyWaitMs;
        this.evidencePayload = evidencePayload;
        if (poolMaxSize < 1 || poolMinIdle < 0 || poolMinIdle > poolMaxSize || poolBorrowTimeoutMs < 0L) throw new IllegalArgumentException("Invalid MQ pool configuration");
        this.poolMaxSize = poolMaxSize; this.poolMinIdle = poolMinIdle; this.poolBorrowTimeoutMs = poolBorrowTimeoutMs;
        this.sourceFile = sourceFile;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String queueManager() { return queueManager; }
    public String host() { return host; }
    public int port() { return port; }
    public String channel() { return channel; }
    public String username() { return username; }
    public String password() { return password; }
    public boolean credentialsConfigured() { return !username.isEmpty() || !password.isEmpty(); }
    public int ccsid() { return ccsid; }
    public String format() { return format; }
    public String persistence() { return persistence; }
    public int requestReplyWaitMs() { return requestReplyWaitMs; }
    public String evidencePayload() { return evidencePayload; }
    public Path sourceFile() { return sourceFile; }
    public int poolMaxSize() { return poolMaxSize; }
    public int poolMinIdle() { return poolMinIdle; }
    public long poolBorrowTimeoutMs() { return poolBorrowTimeoutMs; }

    /** Safe diagnostic metadata; credentials are deliberately absent. */
    public Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("instance", id);
        result.put("queueManager", queueManager);
        result.put("host", host);
        result.put("port", port);
        result.put("channel", channel);
        result.put("credentialsConfigured", credentialsConfigured());
        result.put("ccsid", ccsid);
        result.put("format", format);
        result.put("persistence", persistence);
        return Collections.unmodifiableMap(result);
    }

    @Override public String toString() {
        return "MqHelperConfig{" + id + ", " + host + ":" + port + ", queueManager=" + queueManager + "}";
    }
}
