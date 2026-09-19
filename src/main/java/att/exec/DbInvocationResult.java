/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Typed result and evidence for one explicit or expression-backed JDBC invocation. */
public final class DbInvocationResult {
    private final Object result;
    private final Map<String, Object> evidence;

    public DbInvocationResult(Object result, Map<String, Object> evidence) {
        this.result = result;
        this.evidence = new LinkedHashMap<String, Object>(evidence);
    }

    public Object result() { return result; }
    public Map<String, Object> evidence() { return evidence; }
    /** Returns an operation snapshot, including evidence added during logging. */
    public ActionExecutionResult operationResult() {
        return new ActionExecutionResult(result, ActionExecutionResult.evidence("db", evidence), successValue(result));
    }
    /** @deprecated Use {@link #operationResult()}; this is not an Action lifecycle result. */
    @Deprecated public ActionExecutionResult actionResult() { return operationResult(); }
    public boolean success() {
        return operationResult().executionSuccess();
    }

    private static boolean successValue(Object value) {
        return value instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) value).get("success"));
    }
}
