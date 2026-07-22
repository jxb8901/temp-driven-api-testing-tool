/* Author: Jeffrey + ChatGPT */
package att.config;

/** Bounded in-memory previews plus bounded on-disk artifacts for process streams. */
public final class ProcessOutputConfig {
    private final int memoryLimitBytes;
    private final long artifactLimitBytes;

    public ProcessOutputConfig(int memoryLimitBytes, long artifactLimitBytes) {
        if (memoryLimitBytes < 1024 || memoryLimitBytes > 1048576) throw new IllegalArgumentException("execution.processOutput.memoryLimitBytes must be between 1024 and 1048576");
        if (artifactLimitBytes < memoryLimitBytes || artifactLimitBytes > 1073741824L) throw new IllegalArgumentException("execution.processOutput.artifactLimitBytes must be between memoryLimitBytes and 1073741824");
        this.memoryLimitBytes = memoryLimitBytes;
        this.artifactLimitBytes = artifactLimitBytes;
    }

    public int memoryLimitBytes() { return memoryLimitBytes; }
    public long artifactLimitBytes() { return artifactLimitBytes; }
    public static ProcessOutputConfig defaults() { return new ProcessOutputConfig(65536, 104857600L); }
}
