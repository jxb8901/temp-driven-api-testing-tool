package att.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic measured-phase threshold evaluator. */
public final class LoadThresholdEvaluator {
    private static final Pattern EXPRESSION = Pattern.compile("^(<|<=|>|>=|==)\\s*([0-9]+(?:\\.[0-9]+)?)(%|ms|/s|/m)$");
    public LoadThresholdSummary evaluate(LoadScenario scenario, LoadMetricsSnapshot metrics) {
        List<ThresholdResult> results = new ArrayList<ThresholdResult>();
        for (Map.Entry<String, Object> entry : scenario.thresholds().entrySet()) {
            String name = entry.getKey(); String expression = String.valueOf(entry.getValue()).trim();
            try { results.add(evaluateOne(scenario, metrics, name, expression)); }
            catch (RuntimeException error) { results.add(new ThresholdResult(name, expression, "invalid", false, error.getMessage())); }
        }
        return new LoadThresholdSummary(results);
    }

    private ThresholdResult evaluateOne(LoadScenario scenario, LoadMetricsSnapshot metrics, String name, String expression) {
        Matcher matcher = EXPRESSION.matcher(expression); if (!matcher.matches()) throw new IllegalArgumentException("Threshold must use operator and %, ms, /s, or /m");
        double expected = Double.parseDouble(matcher.group(2)); String unit = matcher.group(3); double actual = actual(scenario, name, unit, metrics);
        if ("%".equals(unit)) expected /= 100.0;
        else if ("/m".equals(unit)) expected /= 60.0;
        boolean passed = compare(actual, matcher.group(1), expected);
        String actualText = "%".equals(unit) ? String.format(Locale.ROOT, "%.4f%%", actual * 100.0) : "ms".equals(unit) ? String.format(Locale.ROOT, "%.3fms", actual) : String.format(Locale.ROOT, "%.3f/s", actual);
        return new ThresholdResult(name, expression, actualText, passed, passed ? null : "Measured value did not satisfy the required threshold");
    }

    private double actual(LoadScenario scenario, String name, String unit, LoadMetricsSnapshot metrics) {
        if ("errorRate".equals(name)) return metrics.doubleValue("sutErrorRate");
        if ("droppedRate".equals(name)) return metrics.doubleValue("droppedRate");
        if ("p95".equals(name)) return metrics.doubleValue("p95Ms");
        if ("p99".equals(name)) return metrics.doubleValue("p99Ms");
        if ("minThroughput".equals(name)) return metrics.doubleValue("completedThroughput");
        if ("achievedArrivalRate".equals(name)) {
            double achieved = metrics.doubleValue("achievedArrivalRate");
            if (!"%".equals(unit)) return achieved;
            Object scheduledValue = metrics.value("scheduled");
            Object startedValue = metrics.value("started");
            if (scheduledValue instanceof Number && startedValue instanceof Number) {
                double scheduled = ((Number) scheduledValue).doubleValue();
                return scheduled == 0.0 ? 0.0 : ((Number) startedValue).doubleValue() / scheduled;
            }
            // Keep snapshots assembled by older callers readable while all
            // scheduler-produced snapshots use started/scheduled coverage.
            return scenario.arrivalRatePerSecond() == 0.0 ? 0.0 : achieved / scenario.arrivalRatePerSecond();
        }
        throw new IllegalArgumentException("Unsupported threshold: " + name);
    }
    private boolean compare(double actual, String operator, double expected) {
        if ("<".equals(operator)) return actual < expected; if ("<=".equals(operator)) return actual <= expected;
        if (">".equals(operator)) return actual > expected; if (">=".equals(operator)) return actual >= expected; return Math.abs(actual - expected) < 0.0000001;
    }
}
