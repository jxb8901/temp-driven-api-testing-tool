package att.server.api;

import java.util.Map;

/** Full typed att-load/v1.6 scenario validated before Advanced Load submission. */
public final class AdvancedLoadDraftRequest {
    public String packageId;
    public String environment;
    public Map<String, Object> scenario;
}
