package att.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LoadThresholdSummary {
    private final List<ThresholdResult> results;
    private final boolean passed;
    public LoadThresholdSummary(List<ThresholdResult> results) { this.results = Collections.unmodifiableList(new ArrayList<ThresholdResult>(results)); boolean ok = true; for (ThresholdResult result : results) ok &= result.passed(); this.passed = ok; }
    public static LoadThresholdSummary empty() { return new LoadThresholdSummary(Collections.<ThresholdResult>emptyList()); }
    public List<ThresholdResult> results() { return results; } public boolean passed() { return passed; }
    public String status() {
        if (results.isEmpty()) return "NOT_EVALUATED";
        for (ThresholdResult item : results) if ("FAIL".equals(item.status())) return "NOT_MET";
        for (ThresholdResult item : results) if ("NOT_EVALUATED".equals(item.status())) return "NOT_EVALUATED";
        return "MET";
    }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String verdict = status();
        result.put("status", verdict);
        result.put("performanceVerdict", verdict);
        result.put("legacyPassed", passed);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ThresholdResult item : results) items.add(item.toMap());
        result.put("results", items);
        return result;
    }
}
