package att;

import java.io.InputStream;
import java.util.Properties;

/** Authoritative ATT product and schema version constants. */
public final class Version {
    public static final String PRODUCT = property("att.version", "unknown");
    public static final String DISPLAY = "ATT V" + PRODUCT;
    public static final String BUILD_TIME = property("att.buildTime", "unknown");
    public static final String GIT_COMMIT = property("att.gitCommit", "unknown");
    public static final String CONFIG_SCHEMA = "att-config/v2.11";
    public static final String PREVIOUS_CONFIG_SCHEMA = "att-config/v2.10";
    public static final String OLDER_CONFIG_SCHEMA = "att-config/v2.7";
    public static final String LEGACY_CONFIG_SCHEMA = "att-config/v2.5";
    public static final String DBHELPER_SCHEMA = "att-dbhelper/v2.6";
    public static final String TOOL_GROUP_SCHEMA = "att-tool-group/v2.9";
    public static final String PREVIOUS_TOOL_GROUP_SCHEMA = "att-tool-group/v2.7";
    public static final String OLDER_TOOL_GROUP_SCHEMA = "att-tool-group/v2.6";
    public static final String SSHHELPER_SCHEMA = "att-sshhelper/v1.0";
    public static final String HTTPHELPER_SCHEMA = "att-httphelper/v1.1";
    public static final String LEGACY_TOOL_GROUP_SCHEMA = "att-tool-group/v2.2";
    public static final String SIDECAR_SCHEMA = "att-sidecar/v2.2";
    public static final String LEGACY_SIDECAR_SCHEMA = "att-sidecar/v2.1";
    public static final String TESTCASE_SNAPSHOT_SCHEMA = "att-testcases/v2.4";
    public static final String TESTDATA_SCHEMA = "att-testdata/v1.0";
    public static final String TEMPLATE_SCHEMA = "att-template/v3.6";
    public static final String HISTORICAL_TEMPLATE_SCHEMA_V3_5 = "att-template/v3.5";
    public static final String HISTORICAL_TEMPLATE_SCHEMA_V3_4 = "att-template/v3.4";
    public static final String HISTORICAL_TEMPLATE_SCHEMA_V3_3 = "att-template/v3.3";
    public static final String PREVIOUS_TEMPLATE_SCHEMA = "att-template/v3.1";
    public static final String PREVIOUS2_TEMPLATE_SCHEMA = "att-template/v3.0";
    public static final String LEGACY_TEMPLATE_SCHEMA = "att-template/v2.6";
    public static final String OLDER_TEMPLATE_SCHEMA = "att-template/v2.5";
    public static final String OLDEST_TEMPLATE_SCHEMA = "att-template/v2.3";
    public static final String FLOW_SCHEMA = "att-flow/v3.6";
    public static final String HISTORICAL_FLOW_SCHEMA_V3_5 = "att-flow/v3.5";
    public static final String HISTORICAL_FLOW_SCHEMA_V3_4 = "att-flow/v3.4";
    public static final String HISTORICAL_FLOW_SCHEMA_V3_3 = "att-flow/v3.3";
    public static final String PREVIOUS_FLOW_SCHEMA = "att-flow/v3.1";
    public static final String OLDER_FLOW_SCHEMA = "att-flow/v3.0";
    public static final String RUN_SCHEMA = "att-run/v2.1";
    public static final String VALIDATION_SCHEMA = "att-validation/v2.1";
    public static final String CI_SUMMARY_SCHEMA = "att-ci-summary/v2.1";
    public static final String MQHELPER_SCHEMA_V1_0 = "att-mqhelper/v1.0";
    public static final String MQHELPER_SCHEMA_V1_1 = "att-mqhelper/v1.1";
    public static final String MQHELPER_SCHEMA = "att-mqhelper/v1.2";
    public static final String MQHELPER_SCHEMA_CURRENT = MQHELPER_SCHEMA;
    public static final String OLDER_DEBUG_SCHEMA = "att-debug/v1.0";
    public static final String PREVIOUS_DEBUG_SCHEMA = "att-debug/v1.1";
    public static final String DEBUG_SCHEMA = "att-debug/v1.2";
    public static final String LOAD_SCHEMA_V1_0 = "att-load/v1.0";
    public static final String LOAD_SCHEMA_V1_1 = "att-load/v1.1";
    public static final String LOAD_SCHEMA_V1_2 = "att-load/v1.2";
    public static final String LOAD_SCHEMA_V1_3 = "att-load/v1.3";
    public static final String LOAD_SCHEMA_V1_4 = "att-load/v1.4";
    public static final String LOAD_SCHEMA_V1_5 = "att-load/v1.5";
    public static final String PREVIOUS_LOAD_SCHEMA = LOAD_SCHEMA_V1_5;
    public static final String LOAD_SCHEMA = "att-load/v1.6";
    public static final String LOAD_SCHEMA_CURRENT = LOAD_SCHEMA;
    public static final String LOAD_SUMMARY_SCHEMA = "att-load-summary/v1.1";

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
