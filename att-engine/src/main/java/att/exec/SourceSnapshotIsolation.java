package att.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Builds an OS-enforced read-only boundary for a Worker using an accepted source snapshot. */
public final class SourceSnapshotIsolation {
    public static final String WORKER_PROPERTY = "att.worker.source-snapshot.isolated";
    private static final String PROPERTY_VALUE = "os-read-only-v1";

    private SourceSnapshotIsolation() { }

    /** Wraps one cold Worker command so its descendants cannot write accepted source bytes. */
    public static List<String> wrap(List<String> workerCommand, Path snapshotDirectory,
                                    Path archive, Path outputDirectory) throws IOException {
        if (workerCommand == null || workerCommand.isEmpty()) throw new IllegalArgumentException("A Worker command is required");
        Path source = requiredDirectory(snapshotDirectory, "accepted source snapshot");
        Path acceptedPackage = requiredDirectory(source.resolve("package"), "accepted Package source");
        Path bundle = requiredFile(archive, "accepted source archive");
        Path output = requiredDirectory(outputDirectory, "Worker output directory");
        if (!acceptedPackage.startsWith(source))
            throw new IOException("The accepted Package source escapes its snapshot directory");
        if (overlaps(source, output) || overlaps(bundle, output) || overlaps(source, bundle))
            throw new IOException("Accepted source paths overlap each other or a writable Worker output path");

        List<String> command = new ArrayList<String>();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        List<String> effectiveWorkerCommand = new ArrayList<String>(workerCommand);
        if (os.contains("mac")) {
            Path sandbox = executable(Paths.get("/usr/bin/sandbox-exec"), "macOS Worker source sandbox");
            command.add(sandbox.toString());
            command.add("-p");
            command.add(macProfile(source, bundle));
            appendWorkerCommand(command, workerCommand);
        } else if (os.contains("linux")) {
            int javaIndex = effectiveWorkerCommand.indexOf(effectiveWorkerCommand.get(0));
            boolean tempConfigured = false;
            for (int index = javaIndex + 1; index < effectiveWorkerCommand.size(); index++) {
                if (effectiveWorkerCommand.get(index).startsWith("-Djava.io.tmpdir=")) {
                    effectiveWorkerCommand.set(index, "-Djava.io.tmpdir=/tmp");
                    tempConfigured = true;
                    break;
                }
            }
            if (!tempConfigured) effectiveWorkerCommand.add(javaIndex + 1, "-Djava.io.tmpdir=/tmp");
            Path bubblewrap = executableOnPath("bwrap", "Linux Worker source sandbox (bubblewrap)");
            command.add(bubblewrap.toString());
            command.add("--die-with-parent");
            command.add("--new-session");
            command.add("--unshare-user");
            command.add("--unshare-pid");
            command.add("--disable-userns");
            command.add("--cap-drop");
            command.add("ALL");
            command.add("--ro-bind");
            command.add("/");
            command.add("/");
            command.add("--dev");
            command.add("/dev");
            command.add("--proc");
            command.add("/proc");
            command.add("--tmpfs");
            command.add("/tmp");
            // Mount host inputs after the tmpfs overlay. Bubblewrap resolves each SRC as a host
            // path, so accepted snapshots under host /tmp are then restored at their original
            // in-sandbox paths, while Worker temporary files still use the new tmpfs.
            command.add("--bind");
            command.add(output.toString());
            command.add(output.toString());
            command.add("--ro-bind");
            command.add(source.toString());
            command.add(source.toString());
            command.add("--ro-bind");
            command.add(bundle.toString());
            command.add(bundle.toString());
            command.add("--chdir");
            command.add(acceptedPackage.toString());
            command.add("--");
            appendWorkerCommand(command, effectiveWorkerCommand);
        } else {
            throw new IOException("Accepted source execution requires an OS-enforced Worker sandbox; this platform is not supported");
        }
        // WorkerSourceSnapshot refuses an archive-backed request unless Server launched it through
        // this boundary. The operating-system sandbox, inherited by Tool descendants, is the
        // authority; this marker only catches accidental unwrapped launches.
        int javaIndex = command.indexOf(effectiveWorkerCommand.get(0));
        if (javaIndex < 0) throw new IOException("Unable to identify the Worker Java executable");
        command.add(javaIndex + 1, "-D" + WORKER_PROPERTY + "=" + PROPERTY_VALUE);
        return command;
    }

    public static boolean workerBoundaryDeclared() {
        return PROPERTY_VALUE.equals(System.getProperty(WORKER_PROPERTY));
    }

    private static void appendWorkerCommand(List<String> command, List<String> workerCommand) {
        command.addAll(workerCommand);
    }

    private static String macProfile(Path source, Path archive) throws IOException {
        return "(version 1) (allow default) (deny file-write* (subpath \"" + profilePath(source)
                + "\")) (deny file-write* (subpath \"" + profilePath(archive) + "\"))";
    }

    private static String profilePath(Path path) throws IOException {
        String value = path.toString();
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)
            throw new IOException("Accepted source path cannot be represented in the OS sandbox policy");
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean overlaps(Path left, Path right) {
        return left.startsWith(right) || right.startsWith(left);
    }

    private static Path requiredDirectory(Path path, String label) throws IOException {
        if (path == null || Files.isSymbolicLink(path)) throw new IOException("The " + label + " is unavailable");
        Path real = path.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The " + label + " is unavailable");
        return real;
    }

    private static Path requiredFile(Path path, String label) throws IOException {
        if (path == null || Files.isSymbolicLink(path)) throw new IOException("The " + label + " is unavailable");
        Path real = path.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The " + label + " is unavailable");
        return real;
    }

    private static Path executable(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(path))
            throw new IOException("The " + label + " is unavailable");
        return path;
    }

    private static Path executableOnPath(String name, String label) throws IOException {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry.isEmpty()) continue;
                Path candidate = Paths.get(entry).resolve(name);
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(candidate))
                    return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IOException("The " + label + " is unavailable; configure the host before accepting source-pinned Jobs");
    }
}
