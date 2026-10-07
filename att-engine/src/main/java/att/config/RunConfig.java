/*
 * Author: Jeffrey + ChatGPT
 */

package att.config;

/**
 * Configures V1.2 run identity generation.
 */
public class RunConfig {
    private final String defaultMode;
    private final String timestampFormat;
    private final String runIdFormat;
    private final String debugIdFormat;

    public RunConfig(String defaultMode, String timestampFormat) {
        this(defaultMode, timestampFormat, "", "");
    }

    public RunConfig(String defaultMode, String timestampFormat, String runIdFormat, String debugIdFormat) {
        this.defaultMode = defaultMode == null || defaultMode.trim().isEmpty() ? "timestamp" : defaultMode;
        this.timestampFormat = timestampFormat == null || timestampFormat.trim().isEmpty() ? "yyyyMMdd-HHmmss" : timestampFormat;
        this.runIdFormat = runIdFormat == null ? "" : runIdFormat.trim();
        this.debugIdFormat = debugIdFormat == null ? "" : debugIdFormat.trim();
    }

    public String defaultMode() { return defaultMode; }
    public String timestampFormat() { return timestampFormat; }
    public String runIdFormat() { return runIdFormat; }
    public String debugIdFormat() { return debugIdFormat; }
}
