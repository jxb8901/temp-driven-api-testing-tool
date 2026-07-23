package att;

import java.io.InputStream;
import java.util.Properties;

/** Authoritative ATT product and schema version constants. */
public final class Version {
    public static final String PRODUCT = property("att.version", "unknown");
    public static final String DISPLAY = "ATT V" + PRODUCT;
    public static final String BUILD_TIME = property("att.buildTime", "unknown");
    public static final String GIT_COMMIT = property("att.gitCommit", "unknown");
    public static final String CONFIG_SCHEMA = "att-config/v2.6";
    public static final String LEGACY_CONFIG_SCHEMA = "att-config/v2.5";
    public static final String DBHELPER_SCHEMA = "att-dbhelper/v2.5";
    public static final String TOOL_GROUP_SCHEMA = "att-tool-group/v2.6";
    public static final String LEGACY_TOOL_GROUP_SCHEMA = "att-tool-group/v2.2";
    public static final String SIDECAR_SCHEMA = "att-sidecar/v2.1";
    public static final String TESTCASE_SNAPSHOT_SCHEMA = "att-testcases/v2.4";
    public static final String TEMPLATE_SCHEMA = "att-template/v2.5";
    public static final String LEGACY_TEMPLATE_SCHEMA = "att-template/v2.3";
    public static final String RUN_SCHEMA = "att-run/v2.1";
    public static final String VALIDATION_SCHEMA = "att-validation/v2.1";
    public static final String CI_SUMMARY_SCHEMA = "att-ci-summary/v2.1";

    private Version() {}

    private static String property(String key, String fallback) {
        try (InputStream input = Version.class.getResourceAsStream("/att-build.properties")) {
            if (input == null) return fallback;
            Properties properties = new Properties();
            properties.load(input);
            String value = properties.getProperty(key);
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
