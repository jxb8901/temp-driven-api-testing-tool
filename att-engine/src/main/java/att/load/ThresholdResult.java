package att.load;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ThresholdResult {
    private final String name, expression, actual, status;
    private final boolean passed;
    private final String diagnostic;
    public ThresholdResult(String name, String expression, String actual, boolean passed, String diagnostic) {
        this(name, expression, actual, passed, diagnostic, passed ? "PASS" : "FAIL");
    }
    public ThresholdResult(String name, String expression, String actual, boolean passed, String diagnostic, String status) {
        this.name = name; this.expression = expression; this.actual = actual; this.passed = passed; this.diagnostic = diagnostic; this.status = status;
    }
    public String name() { return name; } public String expression() { return expression; } public String actual() { return actual; } public boolean passed() { return passed; } public String diagnostic() { return diagnostic; }
    public String status() { return status; }
    public Map<String, Object> toMap() { Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("name", name); result.put("expected", expression); result.put("actual", actual); result.put("status", status); if (diagnostic != null) result.put("diagnostic", diagnostic); return result; }
}
