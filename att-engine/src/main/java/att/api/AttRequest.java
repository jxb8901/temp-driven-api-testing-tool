package att.api;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Common package and configuration coordinates for a command-neutral engine operation. */
public abstract class AttRequest {
    private final Path packageRoot;
    private final Path configPath;
    private final String environment;
    private final Path outputDirectory;
    private final String runId;

    protected AttRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId) {
        if (packageRoot == null) throw new IllegalArgumentException("packageRoot is required");
        this.packageRoot = packageRoot.toAbsolutePath().normalize();
        this.configPath = configPath == null ? Paths.get("config/config.yaml") : configPath;
        this.environment = environment;
        this.outputDirectory = outputDirectory;
        this.runId = runId;
    }
    public Path packageRoot() { return packageRoot; }
    public Path configPath() { return configPath; }
    public String environment() { return environment; }
    public Path outputDirectory() { return outputDirectory; }
    public String runId() { return runId; }
}
