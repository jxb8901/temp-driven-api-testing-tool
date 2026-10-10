package att.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed single-target Debug execution intent. */
public final class DebugRequest extends AttRequest {
    private final String targetType, targetId, debugId; private final Path input; private final boolean unsafeFailureDetails, profile;
    private final ExecutionEventListener observer;
    private final List<String> overrides;
    private final DebugStartupMetrics startupMetrics;
    public DebugRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                        String targetType, String targetId, Path input, boolean unsafeFailureDetails) {
        this(packageRoot, configPath, environment, outputDirectory, runId, targetType, targetId, input,
                unsafeFailureDetails, null, null, Collections.<String>emptyList());
    }
    public DebugRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                        String targetType, String targetId, Path input, boolean unsafeFailureDetails,
                        ExecutionEventListener observer) {
        this(packageRoot, configPath, environment, outputDirectory, runId, targetType, targetId, input,
                unsafeFailureDetails, observer, null, Collections.<String>emptyList());
    }
    public DebugRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                        String targetType, String targetId, Path input, boolean unsafeFailureDetails,
                        ExecutionEventListener observer, String debugId) {
        this(packageRoot, configPath, environment, outputDirectory, runId, targetType, targetId, input,
                unsafeFailureDetails, observer, debugId, Collections.<String>emptyList());
    }
    public DebugRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                        String targetType, String targetId, Path input, boolean unsafeFailureDetails,
                        ExecutionEventListener observer, String debugId, List<String> overrides) {
        this(packageRoot, configPath, environment, outputDirectory, runId, targetType, targetId, input,
                unsafeFailureDetails, observer, debugId, overrides, false, null);
    }
    public DebugRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                        String targetType, String targetId, Path input, boolean unsafeFailureDetails,
                        ExecutionEventListener observer, String debugId, List<String> overrides,
                        boolean profile, DebugStartupMetrics startupMetrics) {
        super(packageRoot, configPath, environment, outputDirectory, runId);
        this.targetType = required(targetType, "targetType"); this.targetId = required(targetId, "targetId");
        this.input = input; this.unsafeFailureDetails = unsafeFailureDetails;
        this.observer = observer;
        this.debugId = debugId;
        this.profile = profile;
        this.startupMetrics = startupMetrics;
        this.overrides = Collections.unmodifiableList(new ArrayList<String>(
                overrides == null ? Collections.<String>emptyList() : overrides));
    }
    private static String required(String value, String label) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required"); return value; }
    public String targetType() { return targetType; } public String targetId() { return targetId; } public Path input() { return input; } public boolean unsafeFailureDetails() { return unsafeFailureDetails; }
    public String debugId() { return debugId; }
    public boolean profileEnabled() { return profile; }
    public DebugStartupMetrics startupMetrics() { return startupMetrics; }
    public ExecutionEventListener observer() { return observer; }
    public List<String> overrides() { return overrides; }
}
