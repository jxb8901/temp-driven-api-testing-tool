package att.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.lang.reflect.InvocationTargetException;

/** Writes bounded, secret-safe Java diagnostics for unexpected ATT failures. */
public final class InternalExceptionLogger {
    private static final int MAX_CHARS = 16000;
    private static final int MAX_LINES = 180;

    private InternalExceptionLogger() { }

    /** True when the exception chain contains a likely ATT/adapter invariant failure. */
    public static boolean isInternal(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof NullPointerException || current instanceof ClassCastException
                    || current instanceof UnsupportedOperationException
                    || current instanceof ExceptionInInitializerError || current instanceof LinkageError) return true;
            if (current instanceof ReflectiveOperationException
                    && (!(current instanceof InvocationTargetException) || current.getCause() == null)) return true;
            if (current instanceof RuntimeException && !expectedRuntimeFailure((RuntimeException) current)) return true;
        }
        return false;
    }

    private static boolean expectedRuntimeFailure(RuntimeException error) {
        if (error instanceof IllegalArgumentException || error instanceof CancellationException) return true;
        return error instanceof IllegalStateException && expectedStateFailure(error.getMessage());
    }

    private static boolean expectedStateFailure(String message) {
        if (message == null) return false;
        String value = message.trim().toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("flow execution is unavailable")
                || value.startsWith("db action execution is unavailable")
                || value.startsWith("http invocation is unavailable")
                || value.startsWith("mq invocation is unavailable")
                || value.startsWith("configured tool invocation is unavailable")
                || value.startsWith("db invocation is unavailable")
                || value.startsWith("db expression invocation is unavailable")
                || value.startsWith("case execution log is required")
                || value.startsWith("call-backed tool must be executed")
                || value.startsWith("missing ssh helper:")
                || value.startsWith("db.") && value.contains(".scalar requires exactly one row")
                || value.startsWith("db.") && value.contains(".scalar requires exactly one column")
                || value.startsWith("db query failed for ")
                || value.startsWith("built-in tool failed:")
                || value.equals("http resources are closed")
                || value.equals("resource pool is closed")
                || value.equals("load run resources are closed")
                || value.equals("resource lease is closed")
                || value.startsWith("sequence '") && (value.contains("' overflowed long") || value.contains("' exceeded width "));
    }

    /** Expected transport/validation errors remain concise and do not get stack dumps. */
    public static boolean logIfInternal(CaseExecutionLog log, String phase, Throwable error, List<String> secrets) {
        if (log == null || error == null) return false;
        log.registerSecretRedactions(secrets);
        if (!isInternal(error)) return false;
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        String stack = sanitize(buffer.toString(), secrets);
        String[] lines = stack.split("\\r?\\n", -1);
        StringBuilder bounded = new StringBuilder();
        int count = Math.min(lines.length, MAX_LINES);
        for (int index = 0; index < count; index++) {
            if (bounded.length() + lines[index].length() + 1 > MAX_CHARS) break;
            bounded.append(lines[index]).append('\n');
        }
        if (count < lines.length || bounded.length() < stack.length()) bounded.append("  ... stack trace truncated ...\n");
        String message = sanitize(error.getMessage() == null ? "" : error.getMessage(), secrets);
        String content = "type: " + error.getClass().getName() + "\n"
                + "message: " + (message.isEmpty() ? "<empty>" : message) + "\n"
                + "phase: " + safePhase(phase) + "\n"
                + "stackTrace:\n" + bounded;
        try {
            return log.appendInternalErrorOnce(error, content);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Applies configured-secret replacement and conservative key/value masking. */
    public static String sanitize(String value, List<String> secrets) {
        return att.security.SecretProtection.text(value, secrets);
    }

    private static String safePhase(String phase) {
        if (phase == null || !phase.matches("[A-Za-z0-9_.-]{1,100}")) return "unknown";
        return phase;
    }
}
