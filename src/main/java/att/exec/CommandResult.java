/*
 * Author: Jeffrey + ChatGPT
 */

package att.exec;

import java.nio.file.Path;

/**
 * Holds the captured outcome of a shell command invocation.
 */
public class CommandResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;
    private final long stdoutBytes;
    private final long stderrBytes;
    private final boolean stdoutTruncated;
    private final boolean stderrTruncated;
    private final boolean stdoutArtifactTruncated;
    private final boolean stderrArtifactTruncated;
    private final Path stdoutArtifact;
    private final Path stderrArtifact;

    public CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        this(exitCode, stdout, stderr, timedOut, utf8(stdout), utf8(stderr), false, false, false, false, null, null);
    }

    public CommandResult(int exitCode, String stdout, String stderr, boolean timedOut,
                         long stdoutBytes, long stderrBytes, boolean stdoutTruncated, boolean stderrTruncated,
                         boolean stdoutArtifactTruncated, boolean stderrArtifactTruncated,
                         Path stdoutArtifact, Path stderrArtifact) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.timedOut = timedOut;
        this.stdoutBytes = stdoutBytes;
        this.stderrBytes = stderrBytes;
        this.stdoutTruncated = stdoutTruncated;
        this.stderrTruncated = stderrTruncated;
        this.stdoutArtifactTruncated = stdoutArtifactTruncated;
        this.stderrArtifactTruncated = stderrArtifactTruncated;
        this.stdoutArtifact = stdoutArtifact;
        this.stderrArtifact = stderrArtifact;
    }

    public int exitCode() { return exitCode; }
    public String stdout() { return stdout; }
    public String stderr() { return stderr; }
    public boolean timedOut() { return timedOut; }
    public long stdoutBytes() { return stdoutBytes; }
    public long stderrBytes() { return stderrBytes; }
    public boolean stdoutTruncated() { return stdoutTruncated; }
    public boolean stderrTruncated() { return stderrTruncated; }
    public boolean stdoutArtifactTruncated() { return stdoutArtifactTruncated; }
    public boolean stderrArtifactTruncated() { return stderrArtifactTruncated; }
    public Path stdoutArtifact() { return stdoutArtifact; }
    public Path stderrArtifact() { return stderrArtifact; }
    private static long utf8(String value) { return value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
}
