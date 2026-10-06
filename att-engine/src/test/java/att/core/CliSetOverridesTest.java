package att.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliSetOverridesTest {
    @Test void parsesTypedValuesAndAppliesNestedMapAndListPaths() {
        Map<String, Object> original = new LinkedHashMap<String, Object>();
        Map<String, Object> customer = new LinkedHashMap<String, Object>();
        customer.put("id", "old");
        customer.put("ids", new java.util.ArrayList<Object>(Arrays.<Object>asList(3, 4)));
        original.put("customer", customer);

        Map<String, Object> result = CliSetOverrides.apply(original, Arrays.asList(
                "input.customer.id=42", "input.customer.ids[1]=false", "input.customer.ids[2]=null",
                "input.customer.enabled=true", "input.customer.tags=[sit, qa]", "input.customer.id=43"), "input");
        Map<?, ?> updated = (Map<?, ?>) result.get("customer");
        assertEquals(43, updated.get("id"));
        assertEquals(Arrays.asList(3, false, null), updated.get("ids"));
        assertEquals(Boolean.TRUE, updated.get("enabled"));
        assertEquals(Arrays.asList("sit", "qa"), updated.get("tags"));
        assertEquals("old", ((Map<?, ?>) original.get("customer")).get("id"), "override application must not mutate the sidecar map");
    }

    @Test void createsNestedMapsAndListsWithoutEvaluatingExpressionText() {
        List<String> overrides = Arrays.asList("vars.session.ids[0]=7", "vars.value=#{touch()}");
        Map<String, Object> result = CliSetOverrides.apply(Collections.<String, Object>emptyMap(), overrides, "vars");
        Map<?, ?> session = (Map<?, ?>) result.get("session");
        assertEquals(Collections.singletonList(7), session.get("ids"));
        assertEquals("#{touch()}", result.get("value"));
    }

    @Test void rejectsInvalidNamespacesAndScalarTraversal() {
        assertThrows(IllegalArgumentException.class, () -> CliSetOverrides.validate(Collections.singletonList("other.x=1")));
        assertThrows(IllegalArgumentException.class, () -> CliSetOverrides.validate(Collections.singletonList("input..x=1")));
        Map<String, Object> original = Collections.<String, Object>singletonMap("customer", "scalar");
        assertThrows(IllegalArgumentException.class,
                () -> CliSetOverrides.apply(original, Collections.singletonList("input.customer.id=1"), "input"));
    }
}
