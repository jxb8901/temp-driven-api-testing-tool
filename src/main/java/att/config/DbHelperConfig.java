/* Author: Jeffrey + ChatGPT */
package att.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable configuration for one independently declared V2.5 dbhelper instance. */
public final class DbHelperConfig {
    private final String id;
    private final String name;
    private final String description;
    private final String url;
    private final String username;
    private final String password;
    private final String driverClass;
    private final Map<String, String> properties;
    private final boolean readOnly;
    private final String isolation;
    private final int timeoutSeconds;
    private final String transactionScope;
    private final String transactionOnEnd;
    private final int maxRows;
    private final int maxCellBytes;
    private final int maxBytes;
    private final String evidenceSql;
    private final String evidenceParameters;
    private final Path sourceFile;

    public DbHelperConfig(String id, String name, String description,
                          String url, String username, String password, String driverClass,
                          Map<String, String> properties, boolean readOnly, String isolation,
                          int timeoutSeconds, String transactionScope, String transactionOnEnd,
                          int maxRows, int maxCellBytes, int maxBytes,
                          String evidenceSql, String evidenceParameters, Path sourceFile) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.url = url;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.driverClass = driverClass == null ? "" : driverClass;
        this.properties = Collections.unmodifiableMap(properties == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(properties));
        this.readOnly = readOnly;
        this.isolation = isolation;
        this.timeoutSeconds = timeoutSeconds;
        this.transactionScope = transactionScope;
        this.transactionOnEnd = transactionOnEnd;
        this.maxRows = maxRows;
        this.maxCellBytes = maxCellBytes;
        this.maxBytes = maxBytes;
        this.evidenceSql = evidenceSql;
        this.evidenceParameters = evidenceParameters;
        this.sourceFile = sourceFile;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String url() { return url; }
    public String username() { return username; }
    public String password() { return password; }
    public String driverClass() { return driverClass; }
    public Map<String, String> properties() { return properties; }
    public boolean readOnly() { return readOnly; }
    public String isolation() { return isolation; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public String transactionScope() { return transactionScope; }
    public String transactionOnEnd() { return transactionOnEnd; }
    public int maxRows() { return maxRows; }
    public int maxCellBytes() { return maxCellBytes; }
    public int maxBytes() { return maxBytes; }
    public String evidenceSql() { return evidenceSql; }
    public String evidenceParameters() { return evidenceParameters; }
    public Path sourceFile() { return sourceFile; }
}
