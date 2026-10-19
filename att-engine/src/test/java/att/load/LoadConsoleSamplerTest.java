package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoadConsoleSamplerTest {
    @Test void emitsOneStartPeriodicProgressAndNoSuccessfulIterationLine() throws Exception {
        LoadConsoleSampler sampler = new LoadConsoleSampler("load-1", 30L, 1000L);
        assertEquals("[LOAD] START runId=load-1 model=closed", sampler.startLine("closed"));
        assertNull(sampler.startLine("closed"));
        String progress = null;
        for (int i = 1; i <= 50; i++) {
            String line = sampler.onEvent(LoadEvent.completed("load-1", "closed", "STEADY", "secret-iteration-" + i,
                    "VU-1", i, 1L, 2L, 5L, ResultStatus.PASS));
            if (line != null) progress = line;
            if (line != null) assertFalse(line.contains("secret-iteration"));
        }
        Thread.sleep(40L);
        String later = sampler.onEvent(LoadEvent.completed("load-1", "closed", "STEADY", "secret-iteration-51",
                "VU-1", 51, 1L, 2L, 5L, ResultStatus.PASS));
        assertNotNull(later == null ? progress : later);
        String shown = later == null ? progress : later;
        assertTrue(shown.startsWith("[LOAD] PROGRESS runId=load-1"));
        assertFalse(shown.contains("secret-iteration"));
        String complete = sampler.finish("PASS");
        assertTrue(complete.startsWith("[LOAD] COMPLETE runId=load-1 status=PASS"));
        assertNull(sampler.finish("PASS"));
        assertNull(sampler.onEvent(LoadEvent.completed("load-1", "closed", "STEADY", "after", "VU-1", 99, 1L, 2L, 5L, ResultStatus.PASS)));
    }

    @Test void usesTheFirstEngineRunIdWhenTheRequestOmitsIt() throws Exception {
        LoadConsoleSampler sampler = new LoadConsoleSampler(null, 30L, 0L);
        assertEquals("[LOAD] START runId=load-generated model=closed", sampler.startLine("closed", "load-generated"));
        String error = sampler.onEvent(LoadEvent.completed("load-generated", "closed", "STEADY", "secret-iteration",
                "VU-1", 1, 1L, 2L, 5L, ResultStatus.FAIL, "timeout", null));
        assertNotNull(error);
        assertTrue(error.startsWith("[LOAD] ERROR runId=load-generated"));
        assertFalse(error.contains("secret-iteration"));
        Thread.sleep(40L);
        String progress = sampler.onEvent(LoadEvent.completed("load-other", "closed", "STEADY", "secret-iteration-2",
                "VU-1", 2, 1L, 2L, 5L, ResultStatus.PASS));
        assertNotNull(progress);
        assertTrue(progress.startsWith("[LOAD] PROGRESS runId=load-generated"));
        assertTrue(sampler.finish("FAIL").startsWith("[LOAD] COMPLETE runId=load-generated status=FAIL"));
    }

    @Test void keepsASuppliedRequestRunId() {
        LoadConsoleSampler sampler = new LoadConsoleSampler("requested-id", 5000L, 0L);
        assertEquals("[LOAD] START runId=requested-id model=closed", sampler.startLine("closed", "load-generated"));
        String error = sampler.onEvent(LoadEvent.dropped("load-generated", "closed", "STEADY", "iter", 1, 1L, 2L));
        assertNotNull(error);
        assertTrue(error.startsWith("[LOAD] ERROR runId=requested-id"));
        assertTrue(sampler.finish("PASS").startsWith("[LOAD] COMPLETE runId=requested-id status=PASS"));
    }

    @Test void finishBeforeAnyEventUsesTheRequestIdOrUnknown() {
        LoadConsoleSampler omitted = new LoadConsoleSampler("  ");
        String complete = omitted.finish("ERROR");
        assertTrue(complete.startsWith("[LOAD] COMPLETE runId=unknown status=ERROR"));
        assertNull(omitted.finish("ERROR"));
        assertNull(omitted.onEvent(LoadEvent.completed("load-too-late", "closed", "STEADY", "after", "VU-1", 1, 1L, 2L, 5L, ResultStatus.ERROR)));
        LoadConsoleSampler supplied = new LoadConsoleSampler("requested-id");
        assertTrue(supplied.finish("ERROR").startsWith("[LOAD] COMPLETE runId=requested-id status=ERROR"));
    }
}
