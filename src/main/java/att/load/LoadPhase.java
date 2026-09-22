package att.load;

import java.time.Duration;

/** Deterministic V1 phase boundaries shared by both schedulers. */
public enum LoadPhase {
    WARMUP, RAMP_UP, STEADY, RAMP_DOWN, COMPLETE;

    public static LoadPhase at(LoadScenario scenario, long elapsedMs) {
        long cursor = 0L;
        cursor += scenario.warmup().toMillis(); if (elapsedMs < cursor) return WARMUP;
        cursor += scenario.rampUp().toMillis(); if (elapsedMs < cursor) return RAMP_UP;
        cursor += scenario.duration().toMillis(); if (elapsedMs < cursor) return STEADY;
        cursor += scenario.rampDown().toMillis(); if (elapsedMs < cursor) return RAMP_DOWN;
        return COMPLETE;
    }

    public static long totalMs(LoadScenario scenario) {
        return safeAdd(safeAdd(scenario.warmup().toMillis(), scenario.rampUp().toMillis()),
                safeAdd(scenario.duration().toMillis(), scenario.rampDown().toMillis()));
    }

    public static double fraction(LoadScenario scenario, long elapsedMs) {
        long warmup = scenario.warmup().toMillis();
        long rampUp = scenario.rampUp().toMillis();
        long duration = scenario.duration().toMillis();
        long rampDown = scenario.rampDown().toMillis();
        if (elapsedMs < warmup) return 1.0;
        long afterWarmup = elapsedMs - warmup;
        if (rampUp > 0L && afterWarmup < rampUp) return Math.max(0.0, Math.min(1.0, ((double) afterWarmup) / rampUp));
        long afterRampUp = afterWarmup - rampUp;
        if (afterRampUp < duration) return 1.0;
        long afterSteady = afterRampUp - duration;
        if (rampDown > 0L && afterSteady < rampDown) return Math.max(0.0, 1.0 - ((double) afterSteady) / rampDown);
        return 0.0;
    }

    private static long safeAdd(long left, long right) {
        try { return Math.addExact(left, right); } catch (ArithmeticException e) { return Long.MAX_VALUE; }
    }
}
