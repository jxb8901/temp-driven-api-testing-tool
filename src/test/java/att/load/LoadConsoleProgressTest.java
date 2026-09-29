package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class LoadConsoleProgressTest {
    @Test void streamsPeriodicBoundedProgressAndNeverPrintsEachSuccess() throws Exception {
        LoadScenario scenario = scenario();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, "UTF-8");
        LoadConsoleProgress progress = new LoadConsoleProgress("live-run", scenario, output, 20L, 0L, false, true);
        LoadEvidenceStore store = new LoadEvidenceStore(LoadEvidencePolicy.from(scenario), progress);
        for (int i = 1; i <= 1000; i++) {
            store.onEvent(LoadEvent.completed("live-run", "closed", "STEADY", "secret-iteration-" + i,
                    "VU-1", i, 1L, 2L, 5L, ResultStatus.PASS));
        }
        Thread.sleep(75L);
        String live = bytes.toString("UTF-8");
        assertTrue(live.contains("[LOAD] START runId=live-run"));
        assertTrue(live.contains("[LOAD] PROGRESS runId=live-run"));
        assertTrue(live.contains("completed=1000"));
        assertFalse(live.contains("secret-iteration"));
        assertFalse(live.contains("sequence="));
        assertTrue(live.length() < 5000, "progress output should remain bounded");
        progress.finish("PASS");
        assertTrue(bytes.toString("UTF-8").contains("[LOAD] COMPLETE runId=live-run status=PASS"));
        progress.close();
    }

    @Test void reportsErrorsImmediatelyWithSafeFieldsAndRateLimitsTheConsole() throws Exception {
        LoadScenario scenario = scenario();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LoadConsoleProgress progress = new LoadConsoleProgress("error-run", scenario,
                new PrintStream(bytes, true, "UTF-8"), 60000L, 60000L, false, true);
        progress.onEvent(LoadEvent.completed("error-run", "closed", "STEADY", "iteration-secret", "VU-1",
                1L, 1L, 2L, 5L, ResultStatus.ERROR, "TOKEN\nvalue", null));
        progress.onEvent(LoadEvent.completed("error-run", "closed", "STEADY", "iteration-secret-2", "VU-1",
                2L, 1L, 2L, 5L, ResultStatus.ERROR, "RUNTIME_ERROR", null));
        String during = bytes.toString("UTF-8");
        assertEquals(1, occurrences(during, "[LOAD] ERROR"));
        assertFalse(during.contains("TOKEN\nvalue"));
        assertFalse(during.contains("iteration-secret"));
        progress.finish("ERROR");
        assertTrue(bytes.toString("UTF-8").contains("suppressedErrors=1"));
        progress.close();
    }

    @Test void quietProgressRetainsOnlyTheFinalSummary() throws Exception {
        LoadScenario scenario = scenario();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LoadConsoleProgress progress = new LoadConsoleProgress("quiet-run", scenario,
                new PrintStream(bytes, true, "UTF-8"), 10L, 10L, false, false);
        progress.onEvent(LoadEvent.completed("quiet-run", "closed", "STEADY", "iteration", "VU-1",
                1L, 1L, 2L, 5L, ResultStatus.PASS));
        Thread.sleep(35L);
        assertEquals("", bytes.toString("UTF-8"));
        progress.finish("PASS");
        assertTrue(bytes.toString("UTF-8").contains("[LOAD] SUMMARY runId=quiet-run model=closed status=PASS"));
        assertFalse(bytes.toString("UTF-8").contains("PROGRESS"));
        progress.close();
    }

    private static LoadScenario scenario() {
        return new LoadScenario(Paths.get("progress.yaml"), "template", "LOAD_TEMPLATE",
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                LoadScenario.Model.CLOSED, 2, 0.0, null, Duration.ZERO, Duration.ZERO,
                Duration.ofSeconds(5L), Duration.ZERO, Duration.ZERO, 0, "fail",
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(value, index)) >= 0; index += value.length()) count++;
        return count;
    }
}
