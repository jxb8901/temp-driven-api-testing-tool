package att.exec;

import java.util.Map;

/** Categorized tool failure used by the bounded action retry policy. */
public final class ToolExecutionException extends Exception {
    private final String category;
    private final Map<String, Object> evidence;
    private final Integer exitCode;
    public ToolExecutionException(String category, String message, Map<String, Object> evidence, Integer exitCode, Throwable cause) { super(message, cause); this.category = category; this.evidence = evidence; this.exitCode = exitCode; }
    public String category() { return category; }
    public Map<String, Object> evidence() { return evidence; }
    public Integer exitCode() { return exitCode; }
}
