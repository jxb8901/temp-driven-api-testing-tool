package att.testdata;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompiledTestdataMappingTest {
    @Test void compilesNestedReferencesAndPreservesExactValueTypes() throws Exception {
        Map<String, Object> mapping = new LinkedHashMap<String, Object>();
        mapping.put("exact", "@{people.rows[1]}");
        mapping.put("embedded", "name=@{people.name};id=${CASE.id}");
        mapping.put("context", "${CASE.count}");
        mapping.put("nested", Arrays.<Object>asList("literal", "@{people.active}"));
        CompiledTestdataMapping plan = CompiledTestdataMapping.compile(mapping);

        Map<String, Object> resolved = plan.evaluate(new CompiledTestdataMapping.Resolver() {
            @Override public Object testdata(String id, String path) {
                if ("rows[1]".equals(path.substring(1))) return Collections.singletonMap("id", 2);
                if (".name".equals(path)) return "Ada";
                if (".active".equals(path)) return Boolean.TRUE;
                throw new AssertionError(id + path);
            }
            @Override public Object context(String path) {
                if ("CASE.id".equals(path)) return "run-7";
                if ("CASE.count".equals(path)) return Integer.valueOf(3);
                throw new AssertionError(path);
            }
        });
        assertEquals(Collections.singletonMap("id", 2), resolved.get("exact"));
        assertEquals("name=Ada;id=run-7", resolved.get("embedded"));
        assertEquals(Integer.valueOf(3), resolved.get("context"));
        assertEquals(Arrays.asList("literal", Boolean.TRUE), resolved.get("nested"));
        assertEquals(Collections.singleton("people"), plan.references());
    }

    @Test void sharedCompiledMappingEvaluatesSafelyWithConcurrentIterationValues() throws Exception {
        CompiledTestdataMapping plan = CompiledTestdataMapping.compile(Collections.singletonMap(
                "value", "@{rows.value}-${CASE.id}"));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<String>> results = new java.util.ArrayList<Future<String>>();
            for (int index = 0; index < 48; index++) {
                final int iteration = index;
                results.add(workers.submit(new Callable<String>() {
                    @Override public String call() throws Exception {
                        Map<String, Object> resolved = plan.evaluate(new CompiledTestdataMapping.Resolver() {
                            @Override public Object testdata(String id, String path) { return "row-" + iteration; }
                            @Override public Object context(String path) { return iteration; }
                        });
                        return String.valueOf(resolved.get("value"));
                    }
                }));
            }
            for (int index = 0; index < results.size(); index++)
                assertEquals("row-" + index + "-" + index, results.get(index).get());
        } finally { workers.shutdownNow(); }
    }

    @Test void rejectsUnsupportedOrSelfReferentialInputSyntaxAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> CompiledTestdataMapping.compile(
                Collections.singletonMap("value", "#{tool()}")));
        assertThrows(IllegalArgumentException.class, () -> CompiledTestdataMapping.compile(
                Collections.singletonMap("value", "${EXEC.INPUT.value}")));
    }
}
