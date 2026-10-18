package att.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** A value-free dependency on process configuration, not a connectivity test. */
public final class EnvironmentRequirement {
    private final String helperType, helperId, descriptor, field, variable;

    EnvironmentRequirement(String helperType, String helperId, String descriptor, String field, String variable) {
        this.helperType = helperType;
        this.helperId = helperId;
        this.descriptor = descriptor;
        this.field = field;
        this.variable = variable;
    }

    public String helperType() { return helperType; }
    public String helperId() { return helperId; }
    public String descriptor() { return descriptor; }
    public String field() { return field; }
    public String variable() { return variable; }

    public Map<String, Object> diagnostic(String environment) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", "ATT-RESOURCE-ENV-REQUIRED");
        result.put("severity", "warning");
        result.put("environment", environment);
        result.put("helperType", helperType);
        result.put("helperId", helperId);
        result.put("descriptor", descriptor);
        result.put("field", field);
        result.put("environmentVariable", variable);
        result.put("summary", "Execution requires environment variable " + variable);
        result.put("remediation", "Configure the variable in the Worker process environment, then validate the selected target.");
        return result;
    }
}
