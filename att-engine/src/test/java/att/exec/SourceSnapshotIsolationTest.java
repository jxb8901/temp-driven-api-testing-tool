package att.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSnapshotIsolationTest {
    @TempDir Path temporary;

    @Test void temporaryWorkerClasspathRestoresOnlyRequiredJarsAsReadOnlyMounts() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "The /tmp overlay is specific to Unix hosts");
        Path alias = Paths.get("/tmp").resolve("att-source-isolation-" + java.util.UUID.randomUUID());
        Files.createSymbolicLink(alias, temporary);
        Path runtime = Files.createDirectories(temporary.resolve("worker-libs"));
        Path runtimeJar = Files.write(runtime.resolve("att-local.jar"), new byte[]{1});
        Path dependencyJar = Files.write(runtime.resolve("dependency.jar"), new byte[]{1});
        Path unrelatedFile = Files.write(runtime.resolve("private.txt"), new byte[]{1});
        Path sandboxRuntime = alias.resolve("worker-libs");
        Path snapshot = Files.createDirectories(temporary.resolve("accepted/source/package")).getParent();
        String classpath = sandboxRuntime.resolve("att-local.jar") + java.io.File.pathSeparator + sandboxRuntime.resolve("*");
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        List<String> workerCommand = Arrays.asList(java.toString(), "-cp", classpath, "att.worker.WorkerMain");
        List<String> mounts = new ArrayList<String>();

        try {
            SourceSnapshotIsolation.appendWorkerClasspathMounts(mounts, workerCommand, snapshot.resolve("package"));

            assertTrue(hasReadOnlyBind(mounts, runtimeJar.toRealPath(), sandboxRuntime.resolve("att-local.jar")),
                    "Direct runtime JAR classpath entries must be restored");
            assertTrue(hasReadOnlyBind(mounts, dependencyJar.toRealPath(), sandboxRuntime.resolve("dependency.jar")),
                    "Wildcard runtime JAR entries must be restored");
            assertFalse(hasReadOnlyBind(mounts, runtime, sandboxRuntime), "A wildcard must not expose its entire temporary directory");
            assertFalse(hasReadOnlyBind(mounts, unrelatedFile.toRealPath(), sandboxRuntime.resolve("private.txt")),
                    "Unrelated temporary files must remain hidden");
        } finally {
            Files.deleteIfExists(alias);
        }
    }

    private static boolean hasReadOnlyBind(List<String> command, Path source, Path destination) {
        for (int index = 0; index + 2 < command.size(); index++) {
            if ("--ro-bind".equals(command.get(index))
                    && source.toString().equals(command.get(index + 1))
                    && destination.toString().equals(command.get(index + 2))) return true;
        }
        return false;
    }
}
