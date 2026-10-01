/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.SshConfig;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Minimal mwiede/jsch exec-channel fallback used when local OpenSSH is unavailable. */
final class JschSshClient implements SshCommandRunner.JavaClient {
    private final Path knownHosts;

    JschSshClient() {
        this(Paths.get(System.getProperty("user.home", ""), ".ssh", "known_hosts"));
    }

    JschSshClient(Path knownHosts) {
        this.knownHosts = knownHosts;
    }

    @Override
    public CommandResult run(SshConfig ssh, String remoteCommand, Duration timeout, Path projectRoot)
            throws IOException, InterruptedException {
        return run(ssh, remoteCommand, timeout, timeout, projectRoot, null);
    }

    @Override
    public CommandResult run(SshConfig ssh, String remoteCommand, Duration timeout, Path projectRoot,
                             CommandRunner.CapturePolicy capture) throws IOException, InterruptedException {
        return run(ssh, remoteCommand, timeout, timeout, projectRoot, capture);
    }

    @Override
    public CommandResult run(SshConfig ssh, String remoteCommand, Duration connectTimeout, Duration timeout,
                             Path projectRoot) throws IOException, InterruptedException {
        return run(ssh, remoteCommand, connectTimeout, timeout, projectRoot, null);
    }

    @Override
    public CommandResult run(SshConfig ssh, String remoteCommand, Duration connectTimeout, Duration timeout,
                             Path projectRoot, CommandRunner.CapturePolicy capture) throws IOException, InterruptedException {
        if (!Files.isRegularFile(knownHosts) || Files.isSymbolicLink(knownHosts) || !Files.isReadable(knownHosts)) {
            throw new IOException("Java SSH fallback requires a readable non-symlink known_hosts file: " + knownHosts);
        }
        JSch jsch = new JSch();
        try {
            jsch.setKnownHosts(knownHosts.toString());
            if (!ssh.identityFile().isEmpty()) jsch.addIdentity(identityFile(ssh, projectRoot).toString());
        } catch (JSchException e) {
            throw new SshResourceExecutor.SshOperationException("SSH_AUTH_ERROR",
                    "Unable to initialize Java SSH authentication: " + e.getMessage(), e);
        }

        Session session = null;
        ChannelExec channel = null;
        int memoryLimit = capture == null ? 65536 : capture.memoryLimitBytes();
        long artifactLimit = capture == null ? 65536 : capture.artifactLimitBytes();
        BoundedStreamCapture stdout = new BoundedStreamCapture(memoryLimit, artifactLimit,
                capture == null ? null : capture.stdoutArtifact());
        BoundedStreamCapture stderr = new BoundedStreamCapture(memoryLimit, artifactLimit,
                capture == null ? null : capture.stderrArtifact());
        long deadline = System.nanoTime() + timeout.toNanos();
        long connectDeadline = Math.min(deadline, System.nanoTime() + connectTimeout.toNanos());
        boolean connectionPhase = true;
        try {
            session = jsch.getSession(ssh.user(), ssh.host(), ssh.port());
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "publickey,gssapi-with-mic");
            session.connect(remainingMillis(connectDeadline));

            channel = (ChannelExec) session.openChannel("exec");
            channel.setPty(false);
            channel.setInputStream(null);
            channel.setOutputStream(stdout);
            channel.setErrStream(stderr);
            channel.setCommand(remoteCommand);
            channel.connect(remainingMillis(deadline));
            session.setTimeout(remainingMillis(deadline));
            connectionPhase = false;
            while (!channel.isClosed()) {
                if (System.nanoTime() >= deadline) {
                    channel.disconnect();
                    return result(-1, stdout, stderr, true);
                }
                Thread.sleep(20L);
            }
            return result(channel.getExitStatus(), stdout, stderr, false);
        } catch (JSchException e) {
            if (System.nanoTime() >= deadline || (connectionPhase && System.nanoTime() >= connectDeadline)
                    || (e.getMessage() != null && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout")))
                return result(-1, stdout, stderr, true);
            throw new IOException("Java SSH execution failed for " + ssh.destination() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            if (System.nanoTime() >= deadline) return result(-1, stdout, stderr, true);
            throw e;
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
            stdout.close(); stderr.close();
        }
    }

    private static Path identityFile(SshConfig ssh, Path projectRoot) {
        Path identity = Paths.get(ssh.identityFile());
        return identity.isAbsolute() ? identity : projectRoot.resolve(identity).normalize();
    }

    private static int remainingMillis(long deadline) throws IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new IOException("Java SSH execution timed out before connection completed");
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, remaining));
    }

    private static CommandResult result(int exitCode, BoundedStreamCapture stdout, BoundedStreamCapture stderr, boolean timedOut) {
        return new CommandResult(exitCode, stdout.preview(), stderr.preview(), timedOut, stdout.bytes(), stderr.bytes(),
                stdout.memoryTruncated(), stderr.memoryTruncated(), stdout.artifactTruncated(), stderr.artifactTruncated(),
                stdout.artifact(), stderr.artifact());
    }
}
