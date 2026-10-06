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
    public Map<String, Object> toMap() { Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("status", passed ? "PASS" : "FAIL"); List<Map<String, Object>> items = new ArrayList<Map<String, Object>>(); for (ThresholdResult item : results) items.add(item.toMap()); result.put("results", items); return result; }
}
