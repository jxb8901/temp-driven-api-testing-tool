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
        reportInProgress(resolve(root, config.outputDirectory()));
        List<Path> targets = Arrays.asList(resolve(root, config.outputDirectory()), resolve(root, config.reportDirectory()),
                resolve(root, config.logDirectory()), root.resolve("build/docs"));
        for (Path target : targets) deleteSafe(root, target);
        deleteMatchingFiles(root.resolve("build"), "att-", ".tar.gz");
    }

    private void reportInProgress(Path outputRoot) throws IOException {
        Path progress = outputRoot.resolve(".in-progress");
        if (!Files.isDirectory(progress)) return;
        try (java.util.stream.Stream<Path> entries = Files.list(progress)) {
            java.util.Iterator<Path> iterator = entries.sorted().iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                long ageSeconds = Math.max(0, (System.currentTimeMillis() - Files.getLastModifiedTime(entry).toMillis()) / 1000);
                System.err.println("ATT-CLEAN-STALE: in-progress run age=" + ageSeconds + "s path=" + entry);
            }
        }
    }

    private void deleteMatchingFiles(Path directory, String prefix, String suffix) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            java.util.Iterator<Path> iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && name.startsWith(prefix) && name.endsWith(suffix)) Files.delete(file);
            }
        }
    }

    private Path resolve(Path root, Path configured) {
        if (configured == null) throw new IllegalArgumentException("clean requires an explicit generated-output directory");
        return (configured.isAbsolute() ? configured : root.resolve(configured)).toAbsolutePath().normalize();
    }

    private void deleteSafe(Path root, Path target) throws IOException {
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("clean refuses a directory outside the ATT package: " + target);
        }
        if (target.equals(root.resolve("build")) || target.equals(root.resolve("target")) || target.equals(root.resolve("dist"))) throw new IllegalArgumentException("clean refuses a shared build/development directory: " + target);
        for (String sourceDirectory : new String[]{"config", "testcase", "templates", "tools", "docs", "schemas", "lib", "src", ".git"}) {
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
