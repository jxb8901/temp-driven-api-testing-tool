package att.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Value-free descriptor failure; never retains parser exceptions or source excerpts. */
public final class HelperDiscoveryFailure {
    private final String type, descriptor, state;

    HelperDiscoveryFailure(String type, String descriptor, String state) {
        this.type = type;
        this.descriptor = descriptor;
        this.state = state;
    }

    public Map<String, Object> diagnostic(String environment) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", "ATT-RESOURCE-HELPER-" + ("invalid".equals(state) ? "INVALID" : "UNAVAILABLE"));
        result.put("severity", "warning");
        result.put("environment", environment);
        result.put("helperType", type);
        result.put("descriptor", descriptor);
        result.put("field", type + "helpers");
        result.put("state", state);
        result.put("summary", "Helper descriptor is " + state + "; dependent targets are not execution-ready");
        result.put("remediation", "Correct the registered Helper descriptor, refresh resources, then validate the selected target.");
        return result;
    }
}
