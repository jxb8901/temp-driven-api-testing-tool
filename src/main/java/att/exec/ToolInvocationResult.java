/*
 * Author: Jeffrey + ChatGPT
 */

package att.exec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable result of one V2 tool invocation.
 */
public class ToolInvocationResult {
    private final String toolName;
    private final String invocationId;
    private final Object output;
    private final Map<String, Object> invocation;
    private final boolean executionSuccess;
    private final ActionExecutionResult actionResult;

    public ToolInvocationResult(String toolName, String invocationId, Object output, Map<String, Object> invocation) {
        this(toolName, invocationId, output, invocation, true);
    }

    public ToolInvocationResult(String toolName, String invocationId, Object output, Map<String, Object> invocation, boolean executionSuccess) {
        this(toolName, invocationId, output, invocation, executionSuccess, defaultEvidence(invocation));
    }

    public ToolInvocationResult(String toolName, String invocationId, Object output, Map<String, Object> invocation,
                                 boolean executionSuccess, Map<String, Object> evidence) {
        this.toolName = toolName;
        this.invocationId = invocationId;
        this.output = output;
        this.invocation = invocation;
        this.executionSuccess = executionSuccess;
        this.actionResult = new ActionExecutionResult(output, evidence, executionSuccess);
    }

    public String toolName() { return toolName; }
    public String invocationId() { return invocationId; }
    public Object output() { return output; }
    public Map<String, Object> invocation() { return invocation; }
    public boolean executionSuccess() { return executionSuccess; }
    public ActionExecutionResult actionResult() { return actionResult; }
    public Map<String, Object> evidence() { return actionResult.evidence(); }

    private static Map<String, Object> defaultEvidence(Map<String, Object> invocation) {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        if (invocation != null && invocation.get("MQ") instanceof Map) {
            evidence.put("mq", invocation.get("MQ"));
        } else if (invocation != null && invocation.get("DB") instanceof Map) {
            evidence.put("db", invocation.get("DB"));
        } else if (invocation != null && invocation.get("TOOL") instanceof Map) {
            evidence.put("tool", invocation.get("TOOL"));
        } else if (invocation != null) {
            evidence.put("tool", invocation);
        }
        return evidence;
    }
}
