/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.exec;

import java.nio.file.Path;
import java.util.Map;

/**
 * Immutable result of one V2 tool invocation.
 */
public class ToolInvocationResult {
    private final String toolName;
    private final String invocationId;
    private final Object output;
    private final Map<String, Object> invocation;

    public ToolInvocationResult(String toolName, String invocationId, Object output, Map<String, Object> invocation) {
        this.toolName = toolName;
        this.invocationId = invocationId;
        this.output = output;
        this.invocation = invocation;
    }

    public String toolName() { return toolName; }
    public String invocationId() { return invocationId; }
    public Object output() { return output; }
    public Map<String, Object> invocation() { return invocation; }
}
