package att.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadPerformanceAcceptanceVerdictTest {
    @Test void readsNativeVerdictFromTheCompactLoadSummaryContract() throws Exception {
        assertEquals("MET", ServerRuntime.nativePerformanceVerdict(ServerRuntime.JSON.readTree(
                "{\"performanceVerdict\":\"MET\",\"thresholds\":{\"total\":2,\"performanceVerdict\":\"MET\"}}")));
        assertEquals("NOT_MET", ServerRuntime.nativePerformanceVerdict(ServerRuntime.JSON.readTree(
                "{\"thresholds\":{\"performanceVerdict\":\"NOT_MET\"}}")));
    }

    @Test void provenFailureTakesPrecedenceOverPendingOrIncompleteComponents() {
        assertEquals("NOT_MET", ServerRuntime.combinedPerformanceVerdict(true, true, true, "NOT_MET", "PENDING", 0));
        assertEquals("NOT_MET", ServerRuntime.combinedPerformanceVerdict(false, true, true, "MET", "NOT_MET", 2));
    }

    @Test void requiredExternalEvaluationStaysPendingThenRequiresAllConfiguredComponentsToPass() {
        assertEquals("PENDING", ServerRuntime.combinedPerformanceVerdict(false, true, true, "MET", "EVALUATING", 1));
        assertEquals("NOT_EVALUATED", ServerRuntime.combinedPerformanceVerdict(false, true, true, "MET", "NOT_EVALUATED", 2));
        assertEquals("PENDING", ServerRuntime.combinedPerformanceVerdict(false, true, true, "MET", "ERROR", 1));
        assertEquals("NOT_EVALUATED", ServerRuntime.combinedPerformanceVerdict(false, true, true, "MET", "ERROR", 2));
        assertEquals("MET", ServerRuntime.combinedPerformanceVerdict(false, true, true, "MET", "MET", 1));
        assertEquals("MET", ServerRuntime.combinedPerformanceVerdict(false, true, false, "NOT_EVALUATED", "MET", 1));
    }

    @Test void optionalExternalRulesDoNotChangeNativeVerdict() {
        assertEquals("MET", ServerRuntime.combinedPerformanceVerdict(false, false, true, "MET", "MET", 1));
        assertEquals("NOT_EVALUATED", ServerRuntime.combinedPerformanceVerdict(false, false, true, "NOT_EVALUATED", "MET", 1));
        assertEquals("NOT_EVALUATED", ServerRuntime.combinedPerformanceVerdict(false, false, false, "NOT_EVALUATED", "MET", 1));
    }

    @Test void activeExecutionCannotPublishAnEarlyMet() {
        assertEquals("PENDING", ServerRuntime.combinedPerformanceVerdict(true, true, false, "NOT_EVALUATED", "MET", 1));
        assertEquals("PENDING", ServerRuntime.combinedPerformanceVerdict(true, false, true, "MET", "MET", 1));
    }
}
