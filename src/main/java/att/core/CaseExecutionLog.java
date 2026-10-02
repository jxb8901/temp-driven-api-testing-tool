/*
 * Author: Jeffrey + ChatGPT
 */

package att.core;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedWriter;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Appends ordered V2 case, stage and action diagnostics into one UTF-8 case log.
 */
public class CaseExecutionLog implements AutoCloseable {
    private static final String TRUNCATION_MARKER = "... earlier case log events omitted ...\n";
    private final Path path;
    private final boolean yamlAnchors;
    private final java.util.function.Consumer<String> mirror;
    private final BufferedWriter writer;
    private final Yaml yaml;
    private final StringBuilder deferred;
    private final ArrayDeque<String> boundedDeferred;
    private final int deferredCharacterLimit;
    private final boolean discarding;
    private boolean truncated;
    private int boundedDeferredCharacters;
    private final List<String> secretRedactions;
    private final java.util.Set<Throwable> loggedInternalErrors;

    public CaseExecutionLog(Path path) throws IOException {
        this(path, false);
    }

    public CaseExecutionLog(Path path, boolean yamlAnchors) throws IOException {
        this(path, yamlAnchors, null);
    }

    public CaseExecutionLog(Path path, boolean yamlAnchors, java.util.function.Consumer<String> mirror) throws IOException {
        this(path, yamlAnchors, mirror, true, 0, false);
    }

    private CaseExecutionLog(Path path, boolean yamlAnchors, java.util.function.Consumer<String> mirror,
                             boolean physical, int deferredCharacterLimit, boolean discarding) throws IOException {
        this.path = path;
        this.yamlAnchors = yamlAnchors;
        this.mirror = mirror;
        this.discarding = discarding;
        this.deferredCharacterLimit = deferredCharacterLimit;
        if (discarding) {
            this.writer = null;
            this.deferred = null;
            this.boundedDeferred = null;
            this.yaml = null;
            this.secretRedactions = java.util.Collections.emptyList();
            this.loggedInternalErrors = java.util.Collections.emptySet();
        } else {
            this.secretRedactions = new ArrayList<String>();
            this.loggedInternalErrors = java.util.Collections.newSetFromMap(
                    new IdentityHashMap<Throwable, Boolean>());
            if (physical) {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                this.deferred = null;
                this.boundedDeferred = null;
                this.yaml = new Yaml();
            } else {
                this.writer = null;
                if (deferredCharacterLimit > 0) {
                    this.deferred = null;
                    this.boundedDeferred = new ArrayDeque<String>();
                } else {
                    this.deferred = new StringBuilder();
                    this.boundedDeferred = null;
                }
                this.yaml = new Yaml();
            }
        }
    }

    /**
     * Creates a full deferred log for iterations selected for complete evidence.
     * The path is logical; no directory or file is created until materialization.
     */
    public static CaseExecutionLog lightweight(Path logicalPath, boolean yamlAnchors) throws IOException {
        return new CaseExecutionLog(logicalPath, yamlAnchors, null, false, 0, false);
    }

    public static CaseExecutionLog lightweight(Path logicalPath) throws IOException {
        return lightweight(logicalPath, false);
    }

    /**
     * Keeps the executor log/path contract while discarding writes without serialization,
     * buffering, mirroring or file reads. Collector runners own the public log record.
     */
    public static CaseExecutionLog discarding(Path logicalPath) throws IOException {
        return new CaseExecutionLog(logicalPath, false, null, false, 0, true);
    }

    /**
     * Creates a deferred log that retains only its most recent characters.
     * This keeps failure diagnostics useful while bounding per-iteration memory.
     */
    public static CaseExecutionLog bounded(Path logicalPath, boolean yamlAnchors, int maxCharacters) throws IOException {
        if (maxCharacters <= TRUNCATION_MARKER.length())
            throw new IllegalArgumentException("Bounded case log must allow the truncation marker and content");
        return new CaseExecutionLog(logicalPath, yamlAnchors, null, false, maxCharacters, false);
    }

    public Path path() {
        return path;
    }

    /** Registers resource-specific secret spellings for all subsequent log writes. */
    public synchronized void registerSecretRedactions(List<String> values) {
        if (discarding) return;
        if (values == null) return;
        for (String value : values) {
            if (value != null && !value.isEmpty() && !secretRedactions.contains(value)) secretRedactions.add(value);
        }
        secretRedactions.sort((left, right) -> Integer.compare(right.length(), left.length()));
    }

    /** Appends at most one internal stack trace for the same Throwable in this Case log. */
    public synchronized boolean appendInternalErrorOnce(Throwable error, String content) throws IOException {
        if (discarding) return false;
        if (error == null || !loggedInternalErrors.add(error)) return false;
        try {
            appendRaw("ATT INTERNAL ERROR", content);
            return true;
        } catch (IOException | RuntimeException failure) {
            loggedInternalErrors.remove(error);
            throw failure;
        }
    }

    public synchronized void append(String section, Object data) throws IOException {
        if (discarding) return;
        StringBuilder text = new StringBuilder();
        if (abnormal(section, data)) text.append("【!!!!!】");
        text.append("[").append(section).append("]\n");
        if (data == null) {
            text.append("\n");
        } else if (data instanceof String) {
            text.append(data).append("\n\n");
        } else {
            Object serializable = serializable(data, new IdentityHashMap<Object, Object>(), new IdentityHashMap<Object, Boolean>());
            text.append(yaml.dump(serializable)).append("\n");
        }
        write(text.toString());
    }

    /** Writes resolved user/process content as text instead of YAML-escaping line breaks. */
    public synchronized void appendRaw(String section, String content) throws IOException {
        if (discarding) return;
        String normalized = normalizeLines(content == null ? "" : content);
        StringBuilder text = new StringBuilder();
        text.append("[").append(section).append("]\n");
        text.append(normalized);
        if (!normalized.endsWith("\n")) text.append('\n');
        text.append('\n');
        write(text.toString());
    }

    /** Streams a bounded temporary process spool into the Case log, then leaves cleanup to the caller. */
    public synchronized void appendRawFile(String section, Path source, boolean truncated, long totalBytes) throws IOException {
        appendRawFile(section, source, truncated, totalBytes, java.util.Collections.<String>emptyList());
    }

    /** Redacts sensitive tokens even when they span reader-buffer boundaries. */
    public synchronized void appendRawFile(String section, Path source, boolean truncated, long totalBytes,
                                           List<String> redactions) throws IOException {
        if (discarding) return;
        if (source == null || !Files.isRegularFile(source)) return;
        write("[" + section + "]\n");
        boolean previousCarriageReturn = false;
        boolean endedWithNewline = false;
        int longestRedaction = 0;
        if (redactions != null) for (String token : redactions)
            if (token != null) longestRedaction = Math.max(longestRedaction, token.length());
        StringBuilder pending = longestRedaction == 0 ? null : new StringBuilder();
        char[] buffer = new char[8192];
        try (Reader reader = new InputStreamReader(Files.newInputStream(source), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE))) {
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                StringBuilder chunk = new StringBuilder(count + 1);
                for (int index = 0; index < count; index++) {
                    char value = buffer[index];
                    if (previousCarriageReturn) {
                        if (value != '\n') chunk.append('\n');
                        previousCarriageReturn = false;
                    }
                    if (value == '\r') previousCarriageReturn = true;
                    else chunk.append(value);
                }
                if (chunk.length() > 0) {
                    writeRedactedChunk(chunk.toString(), pending, redactions, longestRedaction);
                    endedWithNewline = chunk.charAt(chunk.length() - 1) == '\n';
                }
            }
        }
        if (previousCarriageReturn) {
            writeRedactedChunk("\n", pending, redactions, longestRedaction);
            endedWithNewline = true;
        }
        if (pending != null && pending.length() > 0) {
            String tail = pending.toString();
            List<String> ordered = new ArrayList<String>(redactions);
            ordered.sort((left, right) -> Integer.compare(right == null ? 0 : right.length(), left == null ? 0 : left.length()));
            for (String token : ordered) if (token != null && !token.isEmpty()) tail = tail.replace(token, "[REDACTED_SECRET]");
            write(tail);
        }
        if (!endedWithNewline) write("\n");
        if (truncated) write("... ATT process output truncated; totalBytes=" + totalBytes + " ...\n");
        write("\n");
    }

    private void writeRedactedChunk(String chunk, StringBuilder pending, List<String> redactions,
                                    int longestRedaction) throws IOException {
        if (pending == null) { write(chunk); return; }
        pending.append(chunk);
        int safeEnd = pending.length() - longestRedaction + 1;
        if (safeEnd <= 0) return;
        String content = pending.toString();
        StringBuilder emitted = new StringBuilder();
        int cursor = 0;
        while (cursor < safeEnd) {
            int match = -1;
            String matchedToken = null;
            for (String token : redactions) {
                if (token == null || token.isEmpty()) continue;
                int candidate = content.indexOf(token, cursor);
                if (candidate >= 0 && candidate < safeEnd &&
                        (match < 0 || candidate < match || (candidate == match && token.length() > matchedToken.length()))) {
                    match = candidate;
                    matchedToken = token;
                }
            }
            if (match < 0) { emitted.append(content, cursor, safeEnd); cursor = safeEnd; }
            else {
                emitted.append(content, cursor, match).append("[REDACTED_SECRET]");
                cursor = match + matchedToken.length();
            }
        }
        if (emitted.length() > 0) write(emitted.toString());
        pending.delete(0, cursor);
    }

    private synchronized void write(String text) throws IOException {
        if (discarding) return;
        String safeText = text;
        for (String secret : secretRedactions) safeText = safeText.replace(secret, "[REDACTED_SECRET]");
        if (writer != null) {
            writer.write(safeText);
            writer.flush();
        } else if (deferredCharacterLimit > 0) {
            appendBounded(safeText);
        } else {
            deferred.append(safeText);
        }
        if (mirror != null) mirror.accept(safeText);
    }

    private void appendBounded(String text) {
        int contentLimit = deferredCharacterLimit - TRUNCATION_MARKER.length();
        if (text.length() >= contentLimit) {
            if (text.length() > contentLimit || !boundedDeferred.isEmpty()) truncated = true;
            boundedDeferred.clear();
            String tail = text.substring(text.length() - contentLimit);
            boundedDeferred.addLast(tail);
            boundedDeferredCharacters = tail.length();
            return;
        }
        int overflow = boundedDeferredCharacters + text.length() - contentLimit;
        boolean removed = overflow > 0;
        while (overflow > 0 && !boundedDeferred.isEmpty()) {
            String first = boundedDeferred.removeFirst();
            if (first.length() <= overflow) {
                overflow -= first.length();
                boundedDeferredCharacters -= first.length();
            } else {
                String remainder = first.substring(overflow);
                boundedDeferred.addFirst(remainder);
                boundedDeferredCharacters -= overflow;
                overflow = 0;
            }
        }
        if (text.length() > 0) {
            boundedDeferred.addLast(text);
            boundedDeferredCharacters += text.length();
        }
        if (removed) truncated = true;
    }

    /** Materializes an in-memory log into a caller-selected retained location. */
    public synchronized Path materialize(Path destination) throws IOException {
        if (discarding) return path;
        if (writer != null) return path;
        Path target = destination == null ? path : destination.toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        StringBuilder contents = new StringBuilder(boundedDeferred == null
                ? deferred.length() + (truncated ? TRUNCATION_MARKER.length() : 0)
                : boundedDeferredCharacters + (truncated ? TRUNCATION_MARKER.length() : 0));
        if (truncated) contents.append(TRUNCATION_MARKER);
        if (boundedDeferred == null) contents.append(deferred);
        else for (String chunk : boundedDeferred) contents.append(chunk);
        Files.write(target, contents.toString().getBytes(StandardCharsets.UTF_8));
        return target;
    }

    private String normalizeLines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    @Override public synchronized void close() throws IOException { if (writer != null) writer.close(); }

    /** Writes the human-readable action log without repeating the complete state retained in case.yaml. */
    public void appendAction(String section, Map<String, Object> action) throws IOException {
        if (discarding) return;
        append(section, compactAction(action));
    }

    /** Writes one standalone expression Tool invocation as a compact attempt record. */
    public void appendToolInvocation(String section, Map<String, Object> invocation) throws IOException {
        if (discarding) return;
        append(section, compactAttempt(invocation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compactAction(Map<String, Object> action) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        copyIfPresent(action, result, "id", "type");
        Object description = action.get("description");
        if (description != null && !String.valueOf(description).isEmpty()) result.put("description", description);
        Object rawOutput = action.get("output");
        if (!(rawOutput instanceof Map)) return result;
        Map<String, Object> output = (Map<String, Object>) rawOutput;
        Map<String, Object> compact = new LinkedHashMap<String, Object>();
        copyIfPresent(output, compact, "status", "durationMs");
        copyNonEmpty(output, compact, "targetFiles");
        if ("tool".equalsIgnoreCase(String.valueOf(action.get("type")))) {
            Object attempts = output.get("attempts");
            if (attempts instanceof Iterable) {
                java.util.List<Map<String, Object>> compactAttempts = new ArrayList<Map<String, Object>>();
                for (Object attempt : (Iterable<?>) attempts) if (attempt instanceof Map) compactAttempts.add(compactAttempt((Map<String, Object>) attempt));
                if (!compactAttempts.isEmpty()) compact.put("attempts", compactAttempts);
            }
            copyIfPresent(output, compact, "winningAttempt", "assertion");
        } else if ("log".equalsIgnoreCase(String.valueOf(action.get("type")))) {
            copyIfPresent(output, compact, "sourceFile", "fields", "assertion");
        } else {
            copyResultUnlessTargetDuplicate(output, compact);
            copyIfPresent(output, compact, "format", "sources", "sourceFile", "fields", "name", "assertion", "expected", "actual");
        }
        Object exception = output.get("exception");
        if (exception instanceof Map) compact.put("exception", compactException((Map<String, Object>) exception));
        else if (output.get("diagnostic") instanceof Map)
            compact.put("diagnostic", compactException((Map<String, Object>) output.get("diagnostic")));
        result.put("output", compact);
        return result;
    }

    private Map<String, Object> compactAttempt(Map<String, Object> attempt) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        copyIfPresent(attempt, result, "attempt", "id", "status", "durationMs", "input", "logicalArgv");
        Object logical = attempt.get("logicalArgv"), executed = attempt.get("argv");
        if (executed != null && !executed.equals(logical)) result.put("argv", executed);
        Object parsed = presentationValue(attempt.get("output")), stdout = attempt.get("stdout");
        if (!(parsed instanceof String) || stdout == null || !String.valueOf(parsed).equals(String.valueOf(stdout).trim())) {
            if (attempt.containsKey("output")) result.put("output", parsed);
        }
        copyIfPresent(attempt, result, "exitCode", "timeoutMs", "outputFile", "stdoutBytes", "stderrBytes", "stdoutTruncated", "stderrTruncated", "stdoutArtifactTruncated", "stderrArtifactTruncated", "stdoutArtifact", "stderrArtifact", "stdoutCaptureError", "stderrCaptureError", "category", "retryDecision", "parserDiagnostic", "sshDestination", "sshPort", "sshTransport", "cleanupWarning", "evidenceError", "evidence", "MQ");
        return result;
    }

    private Map<String, Object> compactException(Map<String, Object> exception) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        // summary/detail are the canonical diagnostic text.  The rendered
        // message normally repeats detail (and can therefore duplicate the
        // original failure in the human-readable case log), so retain it
        // only when it adds information that is not already present.
        copyIfPresent(exception, result, "type", "code", "summary", "detail", "location", "suggestion", "context", "cause");
        Object message = exception.get("message");
        if (message != null) {
            String rendered = String.valueOf(message);
            String summary = exception.get("summary") == null ? "" : String.valueOf(exception.get("summary"));
            String detail = exception.get("detail") == null ? "" : String.valueOf(exception.get("detail"));
            if (!rendered.equals(summary) && !rendered.equals(detail)
                    && !detail.contains(rendered) && !rendered.contains(detail)) result.put("message", message);
        }
        return result;
    }

    private void copyResultUnlessTargetDuplicate(Map<String, Object> source, Map<String, Object> target) {
        if (!source.containsKey("result")) return;
        Object value = presentationValue(source.get("result"));
        if (value != null && value.equals(source.get("targetFiles"))) return;
        target.put("result", value);
    }

    /** Raw MQ bytes stay byte[] in runtime Context; only human log rendering decodes them. */
    private Object presentationValue(Object value) {
        return value instanceof byte[] ? new String((byte[]) value, java.nio.charset.Charset.defaultCharset()) : value;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private void copyNonEmpty(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value == null || (value instanceof String && ((String) value).isEmpty())
                || (value instanceof java.util.Collection && ((java.util.Collection<?>) value).isEmpty())
                || (value instanceof Map && ((Map<?, ?>) value).isEmpty())) return;
        target.put(key, value);
    }

    private boolean abnormal(String section, Object data) {
        if (section != null && section.matches("(?i).*(^|\\s)(ERROR|FAIL|INVALID)(\\s|$).*")) return true;
        return abnormalValue(data, new IdentityHashMap<Object, Boolean>());
    }

    private boolean abnormalValue(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null) return false;
        if (value instanceof ResultStatus) return abnormalStatus(String.valueOf(value));
        if (!(value instanceof Map) && !(value instanceof Iterable) && !value.getClass().isArray()) return false;
        if (visited.put(value, Boolean.TRUE) != null) return false;
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if ("status".equalsIgnoreCase(String.valueOf(entry.getKey())) && abnormalStatus(String.valueOf(entry.getValue()))) return true;
                if (abnormalValue(entry.getValue(), visited)) return true;
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) if (abnormalValue(item, visited)) return true;
        } else {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) if (abnormalValue(java.lang.reflect.Array.get(value, index), visited)) return true;
        }
        return false;
    }

    private boolean abnormalStatus(String value) {
        return "ERROR".equalsIgnoreCase(value) || "FAIL".equalsIgnoreCase(value) || "INVALID".equalsIgnoreCase(value);
    }

    /** Converts represented runtime values to evidence maps and applies the configured YAML alias policy. */
    private Object serializable(Object value, IdentityHashMap<Object, Object> copies,
                                IdentityHashMap<Object, Boolean> active) {
        if (value == null) return null;
        boolean container = value instanceof Map || value instanceof Iterable || value.getClass().isArray();
        if (!container) return value;
        if (active.containsKey(value)) throw new IllegalArgumentException("Cyclic data cannot be written to the case log");
        if (yamlAnchors && copies.containsKey(value)) return copies.get(value);
        active.put(value, Boolean.TRUE);
        try {
            if (value instanceof Map) {
                Map<Object, Object> copy = new LinkedHashMap<Object, Object>();
                if (yamlAnchors) copies.put(value, copy);
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    copy.put(serializable(entry.getKey(), copies, active), serializable(entry.getValue(), copies, active));
                }
                return copy;
            }
            ArrayList<Object> copy = new ArrayList<Object>();
            if (yamlAnchors) copies.put(value, copy);
            if (value instanceof Iterable) {
                for (Object item : (Iterable<?>) value) copy.add(serializable(item, copies, active));
            } else {
                int length = java.lang.reflect.Array.getLength(value);
                for (int index = 0; index < length; index++) copy.add(serializable(java.lang.reflect.Array.get(value, index), copies, active));
            }
            return copy;
        } finally {
            active.remove(value);
        }
    }
}
