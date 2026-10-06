/* Author: Jeffrey + ChatGPT */
package att.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** V3.4 diagnostic Tool call executed between a primary result and its assertion. */
public final class EvidenceCollector {
    private final String id;
    private final String call;
    private final Long timeoutMs;
    private final String onFailure;

    public EvidenceCollector(String id, Map<String, Object> values) {
        this.id = id;
        Map<String, Object> data = values == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(values);
        this.call = data.get("call") == null ? "" : String.valueOf(data.get("call"));
        this.timeoutMs = data.get("timeoutMs") == null ? null : Long.valueOf(String.valueOf(data.get("timeoutMs")));
        String mode = data.get("onFailure") == null ? "continue" : String.valueOf(data.get("onFailure")).trim();
        if (!("continue".equals(mode) || "stop".equals(mode))) throw new IllegalArgumentException("Evidence collector onFailure must be continue or stop: " + id);
        this.onFailure = mode;
    }

    public String id() { return id; }
    public String call() { return call; }
    public Long timeoutMs() { return timeoutMs; }
    public String onFailure() { return onFailure; }
}
