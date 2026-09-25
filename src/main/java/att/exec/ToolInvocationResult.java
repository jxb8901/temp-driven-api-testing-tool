/*
 * Author: Jeffrey + ChatGPT
 */

package att.exec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result view of one V2 tool invocation.
 *
 * <p>The operation snapshot is detached from the mutable invocation log map;
 * Action retry and publication state remains owned by the Action runner.</p>
 */
public class ToolInvocationResult {
    private final String toolName;
    private final String invocationId;
    private final Object output;
    private final Map<String, Object> invocation;
    private final boolean executionSuccess;
    private final ActionExecutionResult operationResult;

    public ToolInvocationResult(String toolName, String invocationId, Object output, Map<String, Object> invocation) {
        this(toolName, invocationId, output, invocation, true);
    }

    public ToolInvocationResult(String toolName, String invocationId, Object output, Map<String, Object> invocation, boolean executionSuccess) {
        this(toolName, invocationId, output, invocation, executionSuccess, defaultEvidence(invocation));
    }

    public ToolInvocationResult(String toolName, String invocationId, Object output, Map<String, Object> invocation,
                                 boolean executionSuccess, Map<String, Object> evidence) {
        this(toolName, invocationId, output, invocation, executionSuccess,
                new ActionExecutionResult(output, evidence, executionSuccess));
    }

    public ToolInvocationResult(String toolName, String invocationId, Object output, Map<String, Object> invocation,
                                boolean executionSuccess, ActionExecutionResult operationResult) {
        this.toolName = toolName;
        this.invocationId = invocationId;
        this.output = output;
        this.invocation = invocation;
        this.executionSuccess = executionSuccess;
        this.operationResult = operationResult;
    }

    public String toolName() { return toolName; }
    public String invocationId() { return invocationId; }
    public Object output() { return output; }
    public Map<String, Object> invocation() { return invocation; }
    public boolean executionSuccess() { return executionSuccess; }
    public ActionExecutionResult operationResult() { return operationResult; }
    /** @deprecated Use {@link #operationResult()}; this is not an Action lifecycle result. */
    @Deprecated public ActionExecutionResult actionResult() { return operationResult; }
    public Map<String, Object> evidence() { return operationResult.evidence(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> defaultEvidence(Map<String, Object> invocation) {
        if (invocation != null && invocation.get("MQ") instanceof Map) {
            return ActionExecutionResult.evidence("mq", (Map<String, Object>) invocation.get("MQ"));
        } else if (invocation != null && invocation.get("DB") instanceof Map) {
            return ActionExecutionResult.evidence("db", (Map<String, Object>) invocation.get("DB"));
        } else if (invocation != null && invocation.get("TOOL") instanceof Map) {
            return ActionExecutionResult.evidence("tool", (Map<String, Object>) invocation.get("TOOL"));
        } else if (invocation != null) {
            return ActionExecutionResult.evidence("tool", invocation);
        }
        return new LinkedHashMap<String, Object>();
    }
}
