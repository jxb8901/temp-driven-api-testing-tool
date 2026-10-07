package att.api;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Typed single-target Debug execution intent. */
public final class DebugRequest extends AttRequest {
    private final String targetType, targetId; private final Path input; private final boolean unsafeFailureDetails;
    private final String outputFormat; private final boolean quiet, verbose; private final Consumer<String> outputListener;
    public DebugRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                        String targetType, String targetId, Path input, boolean unsafeFailureDetails) {
        this(packageRoot, configPath, environment, outputDirectory, runId, targetType, targetId, input,
                unsafeFailureDetails, "machine", true, false, null);
    }
    public DebugRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                        String targetType, String targetId, Path input, boolean unsafeFailureDetails,
                        String outputFormat, boolean quiet, boolean verbose, Consumer<String> outputListener) {
        super(packageRoot, configPath, environment, outputDirectory, runId);
        this.targetType = required(targetType, "targetType"); this.targetId = required(targetId, "targetId");
        this.input = input; this.unsafeFailureDetails = unsafeFailureDetails;
        this.outputFormat = outputFormat == null ? "machine" : outputFormat;
        this.quiet = quiet; this.verbose = verbose; this.outputListener = outputListener;
    }
    private static String required(String value, String label) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required"); return value; }
    public String targetType() { return targetType; } public String targetId() { return targetId; } public Path input() { return input; } public boolean unsafeFailureDetails() { return unsafeFailureDetails; }
    public String outputFormat() { return outputFormat; } public boolean quiet() { return quiet; } public boolean verbose() { return verbose; }
    public Consumer<String> outputListener() { return outputListener; }
}
