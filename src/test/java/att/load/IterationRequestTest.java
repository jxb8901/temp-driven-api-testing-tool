package att.load;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class IterationRequestTest {
    @Test void withCopyMethodsReuseTheFrozenTreeToAvoidRepeatedNestedAllocations() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("value", "frozen");
        List<Object> entries = new ArrayList<Object>();
        entries.add(nested);
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("nested", nested);
        source.put("entries", entries);
        IterationRequest request = IterationRequest.closed("copy-run", "copy-iteration", 1, "STEADY",
                Instant.now(), "VU-1", source);

        assertTreeReused(request, request.withOutputDirectory(java.nio.file.Paths.get("output")));
        assertTreeReused(request, request.withFailureEvidence(false));
        assertTreeReused(request, request.withEvidenceRetention(false, true));
        assertTreeReused(request, request.withFailureLogCapture(false));
        assertTreeReused(request, request.withWorkloadId("payments"));
        assertTreeReused(request, request.withTestdataWaitAllowed(() -> false));
        assertTreeReused(request, request.withTestdataOrdinal(3L));
    }

    private static void assertTreeReused(IterationRequest source, IterationRequest copy) {
        assertSame(source.inputs(), copy.inputs());
        assertSame(source.inputs().get("nested"), copy.inputs().get("nested"));
        assertSame(source.inputs().get("entries"), copy.inputs().get("entries"));
        assertSame(((List<?>) source.inputs().get("entries")).get(0),
                ((List<?>) copy.inputs().get("entries")).get(0));
    }
}
