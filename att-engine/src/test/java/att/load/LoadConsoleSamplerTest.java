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
}
