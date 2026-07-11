/* Author: Jeffrey + ChatGPT */
package att.report;

import att.config.FrameworkConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Removes only known ATT-generated directories below the package root. */
public final class GeneratedOutputCleaner {
    public void clean(Path projectRoot, FrameworkConfig config) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Path> targets = Arrays.asList(resolve(root, config.outputDirectory()), resolve(root, config.reportDirectory()),
                resolve(root, config.logDirectory()), root.resolve("build/docs"), root.resolve("build"), root.resolve("target"));
        for (Path target : targets) deleteSafe(root, target);
    }

    private Path resolve(Path root, Path configured) {
        if (configured == null) throw new IllegalArgumentException("clean requires an explicit generated-output directory");
        return (configured.isAbsolute() ? configured : root.resolve(configured)).toAbsolutePath().normalize();
    }

    private void deleteSafe(Path root, Path target) throws IOException {
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("clean refuses a directory outside the ATT package: " + target);
        }
        for (String sourceDirectory : new String[]{"config", "testcase", "templates", "tools", "docs", "lib", "src", ".git"}) {
            if (target.startsWith(root.resolve(sourceDirectory))) {
                throw new IllegalArgumentException("clean refuses an ATT source directory: " + target);
            }
        }
        deleteDirectory(target);
    }

    static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); }
                catch (IOException e) { throw new CleanupFailure(e); }
            });
        } catch (CleanupFailure e) {
            throw e.cause;
        }
    }

    private static final class CleanupFailure extends RuntimeException {
        private final IOException cause;
        private CleanupFailure(IOException cause) { this.cause = cause; }
    }
}
