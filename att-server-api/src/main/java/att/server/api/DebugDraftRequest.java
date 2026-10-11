package att.server.api;

import java.util.Map;

/** Strict public request for validating one fixed-target inline Debug input. */
public final class DebugDraftRequest {
    public String packageId;
    public String environment;
    public Map<String, Object> target;
    public Map<String, Object> input;
}
