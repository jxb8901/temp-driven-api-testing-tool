package att.load;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ThresholdResult {
    private final String name, expression, actual;
    private final boolean passed;
    private final String diagnostic;
    public ThresholdResult(String name, String expression, String actual, boolean passed, String diagnostic) { this.name = name; this.expression = expression; this.actual = actual; this.passed = passed; this.diagnostic = diagnostic; }
    public String name() { return name; } public String expression() { return expression; } public String actual() { return actual; } public boolean passed() { return passed; } public String diagnostic() { return diagnostic; }
    public Map<String, Object> toMap() { Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("name", name); result.put("expected", expression); result.put("actual", actual); result.put("status", passed ? "PASS" : "FAIL"); if (diagnostic != null) result.put("diagnostic", diagnostic); return result; }
}
