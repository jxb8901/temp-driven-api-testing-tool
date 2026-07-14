/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBuiltInProviderTest {
    @TempDir Path tempDir;

    @Test void supportsSafeEverydayFileOperations() throws Exception {
        DefaultBuiltInProvider provider = new DefaultBuiltInProvider(Clock.systemUTC(), new LastChoiceRandom());
        Path directory = tempDir.resolve("nested/output");
        assertEquals(directory.toAbsolutePath().normalize().toString(), provider.invoke("makeDirectories", args("path", directory)));
        assertTrue(Files.isDirectory(directory));
        assertEquals("true", provider.invoke("directoryExists", args("path", directory)));
        assertEquals("false", provider.invoke("fileExists", args("path", directory)));

        Path source = tempDir.resolve("source.txt");
        Files.write(source, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("true", provider.invoke("fileExists", args("path", source)));
        assertEquals("3", provider.invoke("fileSize", args("path", source)));

        Path copied = directory.resolve("copied.txt");
        assertEquals(copied.toAbsolutePath().normalize().toString(), provider.invoke("copyFile", args("source", source, "target", copied)));
        assertEquals("abc", new String(Files.readAllBytes(copied), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("copyFile", args("source", source, "target", copied)));
        Files.write(source, "updated".getBytes(StandardCharsets.UTF_8));
        provider.invoke("copyFile", args("source", source, "target", copied, "overwrite", true));
        assertEquals("updated", new String(Files.readAllBytes(copied), StandardCharsets.UTF_8));

        Path moved = directory.resolve("moved.txt");
        assertEquals(moved.toAbsolutePath().normalize().toString(), provider.invoke("moveFile", args("source", copied, "target", moved)));
        assertFalse(Files.exists(copied));
        assertEquals("true", provider.invoke("deleteFile", args("path", moved)));
        assertEquals("false", provider.invoke("deleteFile", args("path", moved, "missingOk", true)));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("deleteFile", args("path", moved)));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("deleteFile", args("path", directory)));
    }

    @Test void randomChoiceAcceptsCompleteNamedOrPositionalLists() {
        DefaultBuiltInProvider provider = new DefaultBuiltInProvider(Clock.systemUTC(), new LastChoiceRandom());
        assertEquals("C", provider.invoke("randomChoice", args("arg0", "A", "arg1", "B", "arg2", "C")));
        assertEquals(Integer.valueOf(3), provider.invoke("randomChoice", args("first", 1, "second", 2, "third", 3)));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("randomChoice", new LinkedHashMap<String, Object>()));
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("randomChoice", args("arg0", "A", "second", "B")));
    }

    @Test void fileOperationsDoNotFollowFinalSymbolicLinks() throws Exception {
        Assumptions.assumeFalse(java.io.File.separatorChar == '\\', "symbolic-link contract is exercised on POSIX");
        DefaultBuiltInProvider provider = new DefaultBuiltInProvider(Clock.systemUTC(), new LastChoiceRandom());
        Path source = tempDir.resolve("source.txt");
        Path link = tempDir.resolve("source-link.txt");
        Files.write(source, "content".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(link, source);

        assertEquals("false", provider.invoke("fileExists", args("path", link)));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("copyFile", args("source", link, "target", tempDir.resolve("copy.txt"))));
        assertEquals("true", provider.invoke("deleteFile", args("path", link)));
        assertTrue(Files.exists(source));
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
