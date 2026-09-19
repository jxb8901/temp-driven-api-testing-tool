/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Typed result and evidence for one explicit or expression-backed JDBC invocation. */
public final class DbInvocationResult {
    private final Object result;
    private final Map<String, Object> evidence;
    private final ActionExecutionResult actionResult;

    public DbInvocationResult(Object result, Map<String, Object> evidence) {
        this.result = result;
        this.evidence = new LinkedHashMap<String, Object>(evidence);
        this.actionResult = new ActionExecutionResult(result,
                ActionExecutionResult.evidence("db", this.evidence), successValue(result));
    }

    public Object result() { return result; }
    public Map<String, Object> evidence() { return evidence; }
    public ActionExecutionResult actionResult() { return actionResult; }
    public boolean success() {
        return actionResult.success();
    }

    private static boolean successValue(Object value) {
        return value instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) value).get("success"));
    }
}
