package att.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
            command.add("--bind");
            command.add(output.toString());
            command.add(output.toString());
            command.add("--ro-bind");
            command.add(source.toString());
            command.add(source.toString());
            command.add("--ro-bind");
            command.add(bundle.toString());
            command.add(bundle.toString());
            // Mount host inputs after the tmpfs overlay. Bubblewrap resolves each SRC as a host
            // path, so accepted snapshots under host /tmp are restored at their original paths,
            // while Worker temporary files still use the new tmpfs. Restore the executable and
            // only the classpath files needed by the Worker, read-only.
            appendWorkerExecutableMount(command, effectiveWorkerCommand, acceptedPackage);
            appendWorkerClasspathMounts(command, effectiveWorkerCommand, acceptedPackage);
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
        int separator = command.indexOf("--");
        int workerIndex = separator < 0 ? command.lastIndexOf(effectiveWorkerCommand.get(0)) : separator + 1;
        if (workerIndex < 0 || workerIndex >= command.size()
                || !effectiveWorkerCommand.get(0).equals(command.get(workerIndex)))
            throw new IOException("Unable to identify the Worker Java executable");
        command.add(workerIndex + 1, "-D" + WORKER_PROPERTY + "=" + PROPERTY_VALUE);
        return command;
    }

    public static boolean workerBoundaryDeclared() {
        return PROPERTY_VALUE.equals(System.getProperty(WORKER_PROPERTY));
    }

    private static void appendWorkerCommand(List<String> command, List<String> workerCommand) {
        command.addAll(workerCommand);
    }

    static void appendWorkerClasspathMounts(List<String> command, List<String> workerCommand,
                                            Path workingDirectory) throws IOException {
        String classpath = workerClasspathArgument(workerCommand);
        if (classpath == null || classpath.isEmpty()) return;
        Path temporaryRoot = Paths.get("/tmp").toAbsolutePath().normalize();
        Map<Path, Path> mounts = new LinkedHashMap<Path, Path>();
        for (String entry : classpath.split(Pattern.quote(java.io.File.pathSeparator), -1)) {
            if (entry.isEmpty()) continue;
            Path classpathPath = Paths.get(entry);
            if (!classpathPath.isAbsolute()) classpathPath = workingDirectory.resolve(classpathPath);
            classpathPath = classpathPath.toAbsolutePath().normalize();
            if (!classpathPath.startsWith(temporaryRoot)) continue;
            if ("*".equals(classpathPath.getFileName() == null ? "" : classpathPath.getFileName().toString())) {
                Path directory = classpathPath.getParent();
                if (directory == null || !Files.isDirectory(directory)) continue;
                try (java.util.stream.Stream<Path> children = Files.list(directory)) {
                    Path[] jars = children.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .sorted().toArray(Path[]::new);
                    for (Path jar : jars) addClasspathMount(mounts, jar);
                }
            } else {
                addClasspathMount(mounts, classpathPath);
            }
        }
        for (Map.Entry<Path, Path> mount : mounts.entrySet()) {
            command.add("--ro-bind");
            command.add(mount.getValue().toString());
            command.add(mount.getKey().toString());
        }
    }

    static void appendWorkerExecutableMount(List<String> command, List<String> workerCommand,
                                            Path workingDirectory) throws IOException {
        if (workerCommand == null || workerCommand.isEmpty()) return;
        Path temporaryRoot = Paths.get("/tmp").toAbsolutePath().normalize();
        Path executable = Paths.get(workerCommand.get(0));
        if (!executable.isAbsolute()) executable = workingDirectory.resolve(executable);
        executable = executable.toAbsolutePath().normalize();
        if (!executable.startsWith(temporaryRoot)) return;
        Map<Path, Path> mount = new LinkedHashMap<Path, Path>();
        addClasspathMount(mount, executable);
        for (Map.Entry<Path, Path> entry : mount.entrySet()) {
            command.add("--ro-bind");
            command.add(entry.getValue().toString());
            command.add(entry.getKey().toString());
        }
    }

    private static String workerClasspathArgument(List<String> workerCommand) {
        for (int index = 1; index < workerCommand.size(); index++) {
            String argument = workerCommand.get(index);
            if ("-cp".equals(argument) || "-classpath".equals(argument) || "--class-path".equals(argument))
                return index + 1 < workerCommand.size() ? workerCommand.get(index + 1) : null;
            if (argument.startsWith("--class-path=")) return argument.substring("--class-path=".length());
            if ("--".equals(argument) || !argument.startsWith("-")) return null;
        }
        return null;
    }

    private static void addClasspathMount(Map<Path, Path> mounts, Path destination) throws IOException {
        if (!Files.exists(destination)) return; // Java ignores missing classpath entries.
        Path source = destination.toRealPath();
        Path previous = mounts.put(destination, source);
        if (previous != null && !previous.equals(source))
            throw new IOException("Worker classpath entries resolve to conflicting paths in the source sandbox");
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
