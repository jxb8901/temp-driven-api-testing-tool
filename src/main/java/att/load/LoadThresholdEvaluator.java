package att.load;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic measured-phase threshold evaluator. */
public final class LoadThresholdEvaluator {
    private static final Pattern EXPRESSION = Pattern.compile("^(<|<=|>|>=|==)\\s*([0-9]+(?:\\.[0-9]+)?)(%|ms|/s|/m)$");
    private static final Set<String> SUPPORTED = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "errorRate", "p95", "p99", "minThroughput", "droppedRate", "achievedArrivalRate")));

    static boolean isSupportedName(String name) { return SUPPORTED.contains(name); }

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
        double expected = Double.parseDouble(matcher.group(2)); String unit = matcher.group(3);
        validateUnit(name, unit);
        double actual = actual(scenario, name, unit, metrics);
        if ("%".equals(unit)) expected /= 100.0;
        else if ("/m".equals(unit)) expected /= 60.0;
        boolean passed = compare(actual, matcher.group(1), expected);
        String actualText = "%".equals(unit) ? String.format(Locale.ROOT, "%.4f%%", actual * 100.0) : "ms".equals(unit) ? String.format(Locale.ROOT, "%.3fms", actual) : String.format(Locale.ROOT, "%.3f/s", actual);
        String diagnostic = passed ? null : String.format(Locale.ROOT, "%s measured %s; expected %s", name, actualText, expression);
        return new ThresholdResult(name, expression, actualText, passed, diagnostic);
    }

    private void validateUnit(String name, String unit) {
        if (("errorRate".equals(name) || "droppedRate".equals(name)) && !"%".equals(unit))
            throw new IllegalArgumentException(name + " threshold must use %");
        if (("p95".equals(name) || "p99".equals(name)) && !"ms".equals(unit))
            throw new IllegalArgumentException(name + " threshold must use ms");
        if ("minThroughput".equals(name) && !("/s".equals(unit) || "/m".equals(unit)))
            throw new IllegalArgumentException("minThroughput threshold must use /s or /m");
        if ("achievedArrivalRate".equals(name)
                && !("%".equals(unit) || "/s".equals(unit) || "/m".equals(unit)))
            throw new IllegalArgumentException("achievedArrivalRate threshold must use %, /s, or /m");
    }

    private double actual(LoadScenario scenario, String name, String unit, LoadMetricsSnapshot metrics) {
        if (!isSupportedName(name)) throw new IllegalArgumentException("Unsupported threshold: " + name);
        if ("errorRate".equals(name)) return metrics.doubleValue("sutErrorRate");
        if ("droppedRate".equals(name)) return metrics.doubleValue("droppedRate");
        if ("p95".equals(name)) return metrics.doubleValue("p95Ms");
        if ("p99".equals(name)) return metrics.doubleValue("p99Ms");
        if ("minThroughput".equals(name)) return metrics.doubleValue("completedThroughput");
        if ("achievedArrivalRate".equals(name)) {
            if ("%".equals(unit)) {
                Object scheduledValue = metrics.value("measuredScheduled");
                Object startedValue = metrics.value("measuredStarted");
                if (!(scheduledValue instanceof Number) || !(startedValue instanceof Number)) {
                    scheduledValue = metrics.value("scheduled");
                    startedValue = metrics.value("started");
                }
                if (scheduledValue instanceof Number && startedValue instanceof Number) {
                    double scheduled = ((Number) scheduledValue).doubleValue();
                    return scheduled == 0.0 ? 0.0 : ((Number) startedValue).doubleValue() / scheduled;
                }
                Object achievedValue = metrics.value("achievedArrivalRate");
                if (achievedValue instanceof Number)
                    return scenario.arrivalRatePerSecond() == 0.0 ? 0.0 : ((Number) achievedValue).doubleValue() / scenario.arrivalRatePerSecond();
                throw new IllegalArgumentException("achievedArrivalRate requires scheduled/started or achievedArrivalRate metrics");
            }
            return metrics.doubleValue("achievedArrivalRate");
        }
        throw new IllegalArgumentException("Unsupported threshold: " + name);
    }
    private boolean compare(double actual, String operator, double expected) {
        if ("<".equals(operator)) return actual < expected; if ("<=".equals(operator)) return actual <= expected;
        if (">".equals(operator)) return actual > expected; if (">=".equals(operator)) return actual >= expected; return Math.abs(actual - expected) < 0.0000001;
    }
}
