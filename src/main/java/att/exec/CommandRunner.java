/*
 * Author: Jeffrey + ChatGPT
 */

package att.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs shell commands with timeout handling and stdout/stderr capture.
 */
public class CommandRunner {
    private static final AtomicLong STDOUT_BYTES = new AtomicLong();
    private static final AtomicLong STDERR_BYTES = new AtomicLong();
    private static final AtomicLong TRUNCATED_STREAMS = new AtomicLong();
    private final ThreadLocal<CapturePolicy> capture = new ThreadLocal<CapturePolicy>();
    public CommandResult run(String command, Duration timeout) throws IOException, InterruptedException {
        return run(command, timeout, null);
    }

    public CommandResult run(String command, Duration timeout, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
        return run(parseCommand(command), timeout, workingDirectory);
    }

    /** Runs an already constructed argv without any further tokenization. */
    public CommandResult run(List<String> commandArguments, Duration timeout, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
        return run(commandArguments, timeout, workingDirectory, Collections.<String, String>emptyMap());
    }

    /** Runs argv with explicit framework-owned environment overrides. */
    public CommandResult run(List<String> commandArguments, Duration timeout, java.nio.file.Path workingDirectory,
                             Map<String, String> environment) throws IOException, InterruptedException {
        if (commandArguments.isEmpty()) throw new IOException("Tool command is blank");
        ProcessBuilder builder = new ProcessBuilder(commandArguments);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        if (environment != null && !environment.isEmpty()) builder.environment().putAll(environment);
        Process process = builder.start();
        // Drain both streams concurrently so a verbose script cannot block on a full pipe.
        CapturePolicy policy = capture.get();
        if (policy == null) policy = CapturePolicy.previewOnly(65536);
        BoundedStreamCapture stdoutCapture = new BoundedStreamCapture(policy.memoryLimitBytes, policy.artifactLimitBytes, policy.stdoutArtifact);
        BoundedStreamCapture stderrCapture = new BoundedStreamCapture(policy.memoryLimitBytes, policy.artifactLimitBytes, policy.stderrArtifact);
        StreamCollector stdout = new StreamCollector(process.getInputStream(), stdoutCapture);
        StreamCollector stderr = new StreamCollector(process.getErrorStream(), stderrCapture);
        Thread outThread = new Thread(stdout);
        Thread errThread = new Thread(stderr);
        outThread.start();
        errThread.start();

        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            join(outThread, process.getInputStream());
            join(errThread, process.getErrorStream());
            stdoutCapture.close(); stderrCapture.close();
            return result(-1, stdoutCapture, stderrCapture, true);
        }
        join(outThread, process.getInputStream());
        join(errThread, process.getErrorStream());
        stdoutCapture.close(); stderrCapture.close();
        return result(process.exitValue(), stdoutCapture, stderrCapture, false);
    }

    public CommandResult runWithCapture(List<String> commandArguments, Duration timeout, java.nio.file.Path workingDirectory,
                                        Map<String, String> environment, CapturePolicy policy) throws IOException, InterruptedException {
        CapturePolicy previous = capture.get();
        capture.set(policy);
        try { return run(commandArguments, timeout, workingDirectory, environment); }
        finally { if (previous == null) capture.remove(); else capture.set(previous); }
    }

    private CommandResult result(int exitCode, BoundedStreamCapture stdout, BoundedStreamCapture stderr, boolean timedOut) {
        STDOUT_BYTES.addAndGet(stdout.bytes()); STDERR_BYTES.addAndGet(stderr.bytes());
        if (stdout.memoryTruncated() || stdout.artifactTruncated()) TRUNCATED_STREAMS.incrementAndGet();
        if (stderr.memoryTruncated() || stderr.artifactTruncated()) TRUNCATED_STREAMS.incrementAndGet();
        return new CommandResult(exitCode, stdout.preview(), stderr.preview(), timedOut,
                stdout.bytes(), stderr.bytes(), stdout.memoryTruncated(), stderr.memoryTruncated(), stdout.artifactTruncated(), stderr.artifactTruncated(),
                stdout.artifact(), stderr.artifact());
    }

    public static Stats stats() { return new Stats(STDOUT_BYTES.get(), STDERR_BYTES.get(), TRUNCATED_STREAMS.get()); }
    public static final class Stats {
        private final long stdoutBytes; private final long stderrBytes; private final long truncatedStreams;
        private Stats(long stdoutBytes, long stderrBytes, long truncatedStreams) { this.stdoutBytes = stdoutBytes; this.stderrBytes = stderrBytes; this.truncatedStreams = truncatedStreams; }
        public long stdoutBytes() { return stdoutBytes; }
        public long stderrBytes() { return stderrBytes; }
        public long truncatedStreams() { return truncatedStreams; }
    }

    private void join(Thread thread, InputStream input) throws InterruptedException {
        thread.join(5000L);
        if (thread.isAlive()) {
            try { input.close(); } catch (IOException ignored) {}
            thread.join(1000L);
        }
    }

    /** Parses the configured command without invoking a shell. */
    public static List<String> parseCommand(String command) throws IOException {
        List<String> result = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        boolean single = false, dual = false, escaped = false;
        boolean tokenStarted = false;
        boolean tokenQuoted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) { token.append(c); escaped = false; tokenStarted = true; }
            else if (c == '\\' && !single) escaped = true;
            else if (c == '\'' && !dual) { single = !single; tokenStarted = true; tokenQuoted = true; }
            else if (c == '"' && !single) { dual = !dual; tokenStarted = true; tokenQuoted = true; }
            else if (Character.isWhitespace(c) && !single && !dual) {
                if (tokenStarted) {
                    result.add(token.toString());
                    token.setLength(0);
                    tokenStarted = false;
                    tokenQuoted = false;
                }
            } else {
                token.append(c);
                tokenStarted = true;
            }
        }
        if (escaped || single || dual) throw new IOException("Unclosed quote/escape in tool command");
        if (tokenStarted) {
            if (token.length() > 0 || tokenQuoted) result.add(token.toString());
        } else if (token.length() > 0) {
            result.add(token.toString());
        }
        return result;
    }

    private static class StreamCollector implements Runnable {
        private final InputStream input;
        private final BoundedStreamCapture output;

        StreamCollector(InputStream input, BoundedStreamCapture output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            int read;
            try {
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // Best effort log capture.
            }
        }

    }

    public static final class CapturePolicy {
        private final int memoryLimitBytes; private final long artifactLimitBytes; private final java.nio.file.Path stdoutArtifact; private final java.nio.file.Path stderrArtifact;
        public CapturePolicy(int memoryLimitBytes, long artifactLimitBytes, java.nio.file.Path stdoutArtifact, java.nio.file.Path stderrArtifact) {
            if (memoryLimitBytes < 1 || artifactLimitBytes < memoryLimitBytes) throw new IllegalArgumentException("Invalid process output capture limits");
            this.memoryLimitBytes = memoryLimitBytes; this.artifactLimitBytes = artifactLimitBytes; this.stdoutArtifact = stdoutArtifact; this.stderrArtifact = stderrArtifact;
        }
        static CapturePolicy previewOnly(int memoryLimitBytes) { return new CapturePolicy(memoryLimitBytes, memoryLimitBytes, null, null); }
    }
}
