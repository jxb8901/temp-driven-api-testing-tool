package att.core;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultAggregatorTest {
    @Test void appliesAuthoritativePriorityAndExitCodes() {
        assertEquals(ResultStatus.ERROR, ResultAggregator.aggregate(Arrays.asList(ResultStatus.FAIL, ResultStatus.ERROR)));
        assertEquals(3, ResultAggregator.exitCode(ResultStatus.ERROR));
        assertEquals(ResultStatus.INVALID, ResultAggregator.aggregate(Arrays.asList(ResultStatus.PASS, ResultStatus.INVALID)));
        assertEquals(2, ResultAggregator.exitCode(ResultStatus.INVALID));
        assertEquals(ResultStatus.FAIL, ResultAggregator.aggregate(Arrays.asList(ResultStatus.PASS, ResultStatus.FAIL)));
        assertEquals(1, ResultAggregator.exitCode(ResultStatus.FAIL));
        assertEquals(ResultStatus.SKIPPED, ResultAggregator.aggregate(Arrays.asList(ResultStatus.SKIPPED, ResultStatus.SKIPPED)));
    }
}
