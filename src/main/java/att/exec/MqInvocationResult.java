/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.util.Map;

/** Result of one invocation-scoped MQ operation. */
public final class MqInvocationResult {
    private final Map<String, Object> result;
    private final Map<String, Object> evidence;
    private final boolean success;

    public MqInvocationResult(Map<String, Object> result, Map<String, Object> evidence, boolean success) {
        this.result = result; this.evidence = evidence; this.success = success;
    }
    public Map<String, Object> result() { return result; }
    public Map<String, Object> evidence() { return evidence; }
    public boolean success() { return success; }
}
