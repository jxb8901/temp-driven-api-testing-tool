package att.load;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test void mixIdentityAndTargetInputsAreImmutableAndAppearInEvents() {
        IterationRequest base = IterationRequest.closed("run", "iteration", 2, "STEADY", Instant.now(), "VU-2",
                Collections.singletonMap("common", "value"));
        Map<String, Object> selected = new LinkedHashMap<String, Object>();
        selected.put("common", "overridden"); selected.put("targetOnly", Integer.valueOf(7));
        IterationRequest selectedRequest = base.withWorkloadId("checkout")
                .withMixIdentity("purchase", "flow", "PURCHASE", selected);
        assertEquals("purchase", selectedRequest.mixId());
        assertEquals("flow", selectedRequest.targetType());
        assertEquals("PURCHASE", selectedRequest.targetId());
        assertEquals("overridden", selectedRequest.inputs().get("common"));
        assertThrows(UnsupportedOperationException.class, () -> selectedRequest.inputs().put("x", "y"));
        LoadEvent event = LoadEvent.started("run", "closed", "STEADY", "iteration", "VU-2", 2,
                System.currentTimeMillis(), System.currentTimeMillis())
                .withWorkloadIdentity("checkout", "flow", "PURCHASE")
                .withMixIdentity("purchase", "flow", "PURCHASE");
        Map<String, Object> item = event.toMap();
        assertEquals("purchase", item.get("mixId"));
        assertEquals("PURCHASE", item.get("targetId"));
    }

    @Test void mixedMetadataCopiesReuseTheAlreadyFrozenInputTree() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("value", "frozen");
        List<Object> entries = new ArrayList<Object>();
        entries.add(nested);
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("nested", nested);
        input.put("entries", entries);
        IterationRequest mixed = IterationRequest.closed("mixed-run", "mixed-iteration", 1, "STEADY",
                Instant.now(), "VU-1", input).withWorkloadId("checkout")
                .withMixIdentity("purchase", "flow", "PURCHASE");

        assertTreeReused(mixed, mixed.withOutputDirectory(java.nio.file.Paths.get("output")));
        assertTreeReused(mixed, mixed.withFailureEvidence(false));
        assertTreeReused(mixed, mixed.withEvidenceRetention(false, true));
        assertTreeReused(mixed, mixed.withFailureLogCapture(false));
        assertTreeReused(mixed, mixed.withWorkloadId("checkout-again"));
        assertTreeReused(mixed, mixed.withTestdataWaitAllowed(() -> false));
        assertTreeReused(mixed, mixed.withTestdataOrdinal(3L));
    }

    private static void assertTreeReused(IterationRequest source, IterationRequest copy) {
        assertSame(source.inputs(), copy.inputs());
        assertSame(source.inputs().get("nested"), copy.inputs().get("nested"));
        assertSame(source.inputs().get("entries"), copy.inputs().get("entries"));
        assertSame(((List<?>) source.inputs().get("entries")).get(0),
                ((List<?>) copy.inputs().get("entries")).get(0));
    }
}
