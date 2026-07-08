/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.exec;

import java.nio.file.Path;
import java.util.Map;

/**
 * Result of one V1.1 tool invocation.
 */
public class ToolInvocationResult {
    private final String toolName;
    private final Object output;
    private final Path outputFile;
    private final Map<String, Object> invocation;

    public ToolInvocationResult(String toolName, Object output, Path outputFile, Map<String, Object> invocation) {
        this.toolName = toolName;
        this.output = output;
        this.outputFile = outputFile;
        this.invocation = invocation;
    }

    public String toolName() { return toolName; }
    public Object output() { return output; }
    public Path outputFile() { return outputFile; }
    public Map<String, Object> invocation() { return invocation; }
}
