/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBuiltInProviderTest {
    @Test void exposesCanonicalPackageQualifiedAliases() throws Exception {
        DefaultBuiltInProvider provider = new DefaultBuiltInProvider();
        assertEquals("0007", provider.invoke("str.lpad", args("value", "7", "length", 4, "pad", "0")));
        assertEquals("fallback", provider.invoke("misc.nvl", args("value", "", "defaultValue", "fallback")));
        assertFalse(provider.names().contains("file.move"));
        assertTrue(provider.names().contains("seq.next"));
    }

    @Test void sequenceBuiltInSupportsIndependentCountersAndPadding() {
        SequenceService service = new SequenceService();
        DefaultBuiltInProvider provider = new DefaultBuiltInProvider(service);
        assertEquals(Long.valueOf(1), provider.invoke("seq.next", Collections.<String, Object>emptyMap()));
        assertEquals("02", provider.invoke("seq.next", args("arg0", 2)));
        assertEquals("0001", provider.invoke("seq.next", args("arg0", "payment", "arg1", 4)));
        assertEquals(Long.valueOf(1), provider.invoke("seq.next", args("arg0", "other")));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("seq.next", args("arg0", "  ")));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("seq.next", args("arg0", 0)));
        SequenceService overflow = new SequenceService();
        assertEquals("1", overflow.next("tiny", Integer.valueOf(1)));
        for (int i = 0; i < 8; i++) overflow.next("tiny", Integer.valueOf(1));
        assertThrows(IllegalStateException.class, () -> overflow.next("tiny", Integer.valueOf(1)));
    }

    @Test void sequenceServiceIsConcurrentAndFreshInstancesResetAtOne() throws Exception {
        SequenceService shared = new SequenceService();
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.Set<Long> values = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) futures.add(workers.submit(() -> {
                for (int index = 0; index < 125; index++) values.add(Long.valueOf(((Number) shared.next("run-sequence", null)).longValue()));
            }));
            for (java.util.concurrent.Future<?> future : futures) future.get();
        } finally { workers.shutdownNow(); }
        assertEquals(1000, values.size());
        assertTrue(values.contains(Long.valueOf(1)));
        assertTrue(values.contains(Long.valueOf(1000)));
        assertEquals(Long.valueOf(1), new SequenceService().next("run-sequence", null));
    }
    @TempDir Path tempDir;


    @Test void randomChoiceAcceptsCompleteNamedOrPositionalLists() {
        DefaultBuiltInProvider provider = new DefaultBuiltInProvider(Clock.systemUTC(), new LastChoiceRandom());
        assertEquals("C", provider.invoke("randomChoice", args("arg0", "A", "arg1", "B", "arg2", "C")));
        assertEquals(Integer.valueOf(3), provider.invoke("randomChoice", args("first", 1, "second", 2, "third", 3)));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("randomChoice", new LinkedHashMap<String, Object>()));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("randomChoice", args("arg0", "A", "second", "B")));
    }




    @Test void removedPresentationAndFileAliasesHaveMigrationGuidance() {
        DefaultBuiltInProvider provider = new DefaultBuiltInProvider();
        for (String name : Arrays.asList("dbText", "misc.dbText", "prettyPrint", "misc.prettyPrint", "format.pretty",
                "file.exists", "file.directoryExists", "file.size", "file.mkdirs", "file.copy", "file.move", "file.delete",
                "fileExists", "directoryExists", "fileSize", "makeDirectories", "copyFile", "moveFile", "deleteFile")) {
            assertFalse(provider.names().contains(name.toLowerCase(java.util.Locale.ROOT)), name);
            assertFalse(DefaultBuiltInProvider.isSafeForBootstrap(name), name);
            assertFalse(DefaultBuiltInProvider.isSafeForExecutionIdentity(name), name);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> provider.invoke(name, Collections.emptyMap()));
            assertTrue(failure.getMessage().contains("Removed"), failure.getMessage());
            assertThrows(IllegalArgumentException.class, () -> provider.validateInvocation(name, Collections.emptyMap()));
        }
    }

    private static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private static final class LastChoiceRandom extends Random {
        @Override public int nextInt(int bound) { return bound - 1; }
    }
}
