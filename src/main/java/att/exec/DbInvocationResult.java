/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Typed result and evidence for one explicit or expression-backed JDBC invocation. */
public final class DbInvocationResult {
    private final Object result;
    private final Map<String, Object> evidence;

    public DbInvocationResult(Object result, Map<String, Object> evidence) {
        this.result = result;
        this.evidence = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(evidence));
    }

    public Object result() { return result; }
    public Map<String, Object> evidence() { return evidence; }
    public boolean success() {
        return result instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) result).get("success"));
    }
}
