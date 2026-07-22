/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.SshConfig;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Selects local OpenSSH first and falls back to the bundled Java SSH client. */
public final class SshCommandRunner {
    public static final String FALLBACK_WARNING = "WARN: local ssh command not found; ATT will use Java SSH library mwiede/jsch.";

    interface JavaClient {
        CommandResult run(SshConfig ssh, String remoteCommand, Duration timeout, Path projectRoot)
                throws IOException, InterruptedException;
    }

    static final class Execution {
        private final CommandResult result;
        private final List<String> argv;
        private final String transport;
        Execution(CommandResult result, List<String> argv, String transport) {
            this.result = result; this.argv = argv; this.transport = transport;
        }
        CommandResult result() { return result; }
        List<String> argv() { return argv; }
        String transport() { return transport; }
    }

    private final CommandRunner commandRunner;
    private final BooleanSupplier localAvailable;
    private final JavaClient javaClient;
    private final PrintStream warningOutput;
    private final AtomicBoolean warned = new AtomicBoolean();

    SshCommandRunner(CommandRunner commandRunner) {
        this(commandRunner, new BooleanSupplier() {
            @Override public boolean getAsBoolean() { return localSshAvailable(); }
        }, new JschSshClient(), System.err);
    }

    SshCommandRunner(CommandRunner commandRunner, BooleanSupplier localAvailable, JavaClient javaClient, PrintStream warningOutput) {
        this.commandRunner = commandRunner;
        this.localAvailable = localAvailable;
        this.javaClient = javaClient;
        this.warningOutput = warningOutput;
    }

    String transportName() { return localAvailable.getAsBoolean() ? "openssh" : "mwiede/jsch"; }

    Execution run(SshConfig ssh, List<String> logicalArgv, Duration timeout, Path projectRoot)
            throws IOException, InterruptedException {
        return run(ssh, logicalArgv, timeout, projectRoot, null);
    }

    Execution run(SshConfig ssh, List<String> logicalArgv, Duration timeout, Path projectRoot, CommandRunner.CapturePolicy capture)
            throws IOException, InterruptedException {
        String remoteCommand = remoteCommand(logicalArgv);
        if (localAvailable.getAsBoolean()) {
            List<String> argv = openSshArgv(ssh, remoteCommand, projectRoot);
            CommandResult result = capture == null ? commandRunner.run(argv, timeout, projectRoot)
                    : commandRunner.runWithCapture(argv, timeout, projectRoot, java.util.Collections.<String, String>emptyMap(), capture);
            return new Execution(result, argv, "openssh");
        }
        if (warned.compareAndSet(false, true)) warningOutput.println(FALLBACK_WARNING);
        return new Execution(javaClient.run(ssh, remoteCommand, timeout, projectRoot),
                new ArrayList<String>(logicalArgv), "mwiede/jsch");
    }

    public static boolean localSshAvailable() {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isEmpty()) continue;
            for (String name : new String[]{"ssh", "ssh.exe"}) {
                Path candidate = Paths.get(directory).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
            }
        }
        return false;
    }

    static List<String> openSshArgv(SshConfig ssh, String remoteCommand, Path projectRoot) {
        List<String> argv = new ArrayList<String>();
        argv.add("ssh");
        argv.add("-o"); argv.add("BatchMode=yes");
        argv.add("-o"); argv.add("StrictHostKeyChecking=yes");
        argv.add("-p"); argv.add(String.valueOf(ssh.port()));
        if (!ssh.identityFile().isEmpty()) {
            Path identity = Paths.get(ssh.identityFile());
            if (!identity.isAbsolute()) identity = projectRoot.resolve(identity).normalize();
            argv.add("-i"); argv.add(identity.toString());
        }
        argv.add("--"); argv.add(ssh.destination()); argv.add(remoteCommand);
        return argv;
    }

    static String remoteCommand(List<String> logicalArgv) {
        StringBuilder command = new StringBuilder();
        for (String item : logicalArgv) {
            if (command.length() > 0) command.append(' ');
            command.append('\'').append(item.replace("'", "'\"'\"'")).append('\'');
        }
        return command.toString();
    }
}
