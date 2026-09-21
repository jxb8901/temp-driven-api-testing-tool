package att.load;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import att.core.ResultStatus;

final class LoadSchedulerSupport {
    private LoadSchedulerSupport() {}
    static String runId(String requested) {
        return att.core.IdentifierValidator.runId(requested == null || requested.trim().isEmpty() ? "load-" + System.currentTimeMillis() : requested);
    }
    static void emit(LoadMetrics metrics, Consumer<LoadEvent> listener, LoadEvent event) { metrics.onEvent(event); if (listener != null) listener.accept(event); }
    static void sleep(long millis) throws InterruptedException { if (millis > 0L) Thread.sleep(Math.min(50L, millis)); else Thread.yield(); }
    static long now() { return System.currentTimeMillis(); }
    static Instant instant(long epochMs) { return Instant.ofEpochMilli(epochMs); }
    static long next(AtomicLong sequence) { return sequence.incrementAndGet(); }
    static double rampRate(LoadScenario scenario, long elapsedMs) { return scenario.arrivalRatePerSecond() * LoadPhase.fraction(scenario, elapsedMs); }
    static String errorType(IterationResult result) {
        if (result == null) return "RUNTIME_ERROR";
        if (result.diagnostic() != null && result.diagnostic().code() != null && !result.diagnostic().code().trim().isEmpty()) return result.diagnostic().code();
        if (result.status() == ResultStatus.ERROR) return "RUNTIME_ERROR";
        if (result.status() == ResultStatus.FAIL) return "SUT_ERROR";
        if (result.status() == ResultStatus.INVALID) return "INVALID_RESULT";
        if (result.status() == ResultStatus.SKIPPED) return "SKIPPED";
        return null;
    }
}
