package att.api;

import java.nio.file.Path;

/** Typed single-target Debug execution intent. */
public final class DebugRequest extends AttRequest {
    private final String targetType, targetId; private final Path input; private final boolean unsafeFailureDetails;
    public DebugRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                        String targetType, String targetId, Path input, boolean unsafeFailureDetails) {
        super(packageRoot, configPath, environment, outputDirectory, runId);
        this.targetType = required(targetType, "targetType"); this.targetId = required(targetId, "targetId");
        this.input = input; this.unsafeFailureDetails = unsafeFailureDetails;
    }
    private static String required(String value, String label) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required"); return value; }
    public String targetType() { return targetType; } public String targetId() { return targetId; } public Path input() { return input; } public boolean unsafeFailureDetails() { return unsafeFailureDetails; }
}
