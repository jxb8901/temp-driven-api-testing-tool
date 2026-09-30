package att.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Applies repeatable --set vars.path=value overrides before bootstrap expression evaluation. */
public final class BootstrapVariableOverrides {
    private BootstrapVariableOverrides() { }

    public static Map<String, Object> apply(Map<String, Object> original, List<String> assignments) {
        return CliSetOverrides.apply(original, assignments, "vars");
    }

    public static List<String> copyAssignments(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }

}
