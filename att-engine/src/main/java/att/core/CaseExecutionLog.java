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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appends ordered V2 case, stage and action diagnostics into one UTF-8 case log.
 */
public class CaseExecutionLog implements AutoCloseable {
    private static final String TRUNCATION_MARKER = "... earlier case log events omitted ...\n";
    private static final int BATCHED_LOG_BUFFER_CHARS = 64 * 1024;
    private final Path path;
    private Path projectRoot;
    private final boolean yamlAnchors;
    private final java.util.function.Consumer<String> mirror;
    private final BufferedWriter writer;
    private final Yaml yaml;
    private final StringBuilder deferred;
    private final ArrayDeque<String> boundedDeferred;
    private final int deferredCharacterLimit;
    private final boolean discarding;
    private final boolean flushEachWrite;
    private boolean truncated;
    private int boundedDeferredCharacters;
    private long bufferedCharacters;
    private final List<String> secretRedactions;
    private final java.util.Set<Throwable> loggedInternalErrors;

    public CaseExecutionLog(Path path) throws IOException {
        this(path, false);
    }

    public CaseExecutionLog(Path path, boolean yamlAnchors) throws IOException {
        this(path, yamlAnchors, null);
    }

    public CaseExecutionLog(Path path, boolean yamlAnchors, java.util.function.Consumer<String> mirror) throws IOException {
        this(path, yamlAnchors, mirror, true, 0, false, true);
    }

    private CaseExecutionLog(Path path, boolean yamlAnchors, java.util.function.Consumer<String> mirror,
                             boolean physical, int deferredCharacterLimit, boolean discarding,
                             boolean flushEachWrite) throws IOException {
        this.path = path;
        this.yamlAnchors = yamlAnchors;
        this.mirror = mirror;
        this.discarding = discarding;
        this.flushEachWrite = flushEachWrite;
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
                BufferedWriter fileWriter = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                this.writer = flushEachWrite ? fileWriter : new BufferedWriter(fileWriter, BATCHED_LOG_BUFFER_CHARS);
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
        return new CaseExecutionLog(logicalPath, yamlAnchors, null, false, 0, false, true);
    }

    public static CaseExecutionLog lightweight(Path logicalPath) throws IOException {
        return lightweight(logicalPath, false);
    }

    /**
     * Keeps the executor log/path contract while discarding writes without serialization,
     * buffering, mirroring or file reads. Collector runners own the public log record.
     */
    public static CaseExecutionLog discarding(Path logicalPath) throws IOException {
        return new CaseExecutionLog(logicalPath, false, null, false, 0, true, true);
    }

    /**
     * Creates a deferred log that retains only its most recent characters.
     * This keeps failure diagnostics useful while bounding per-iteration memory.
     */
    public static CaseExecutionLog bounded(Path logicalPath, boolean yamlAnchors, int maxCharacters) throws IOException {
        if (maxCharacters <= TRUNCATION_MARKER.length())
            throw new IllegalArgumentException("Bounded case log must allow the truncation marker and content");
        return new CaseExecutionLog(logicalPath, yamlAnchors, null, false, maxCharacters, false, true);
    }

    /** Creates physical evidence with bounded batching for non-observed Run execution. */
    public static CaseExecutionLog buffered(Path path, boolean yamlAnchors) throws IOException {
        return new CaseExecutionLog(path, yamlAnchors, null, true, 0, false, false);
    }

    public Path path() {
        return path;
    }

    /** Sets the ATT root used to present local paths in human-readable log output. */
    public synchronized void setProjectRoot(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
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
        appendWithRemotePaths(section, data, java.util.Collections.<String>emptySet());
    }

    private synchronized void appendWithRemotePaths(String section, Object data, Set<String> remotePaths) throws IOException {
        if (discarding) return;
        Set<String> knownRemotePaths = new LinkedHashSet<String>(remotePaths);
        collectRemotePaths(data, knownRemotePaths, new IdentityHashMap<Object, Boolean>());
        StringBuilder text = new StringBuilder();
        if (abnormal(section, data)) text.append("【!!!!!】");
        text.append("[").append(section).append("]\n");
        if (data == null) {
            text.append("\n");
        } else if (data instanceof String) {
            String value = String.valueOf(data);
            text.append(diagnosticSection(section)
                    ? PathPresentation.displayDiagnosticText(redactSecrets(value), projectRoot, knownRemotePaths) : value);
            appendRecordSeparator(text);
            text.append('\n');
        } else {
            Object serializable = serializable(data, new IdentityHashMap<Object, Object>(), new IdentityHashMap<Object, Boolean>(),
                    false, false, false, knownRemotePaths);
            text.append(containsMultilineString(serializable) || containsHumanTextField(serializable, "")
                    ? renderHumanYaml(serializable, 0, "") : yaml.dump(serializable));
            appendRecordSeparator(text);
        }
        write(text.toString());
    }

    /** Writes resolved user/process content as text instead of YAML-escaping line breaks. */
    public synchronized void appendRaw(String section, String content) throws IOException {
        if (discarding) return;
        String value = content == null ? "" : content;
        StringBuilder text = new StringBuilder();
        text.append("[").append(section).append("]\n");
        String safeValue = redactSecrets(value);
        text.append(diagnosticSection(section)
                ? PathPresentation.displayDiagnosticText(safeValue, projectRoot,
                        java.util.Collections.<String>emptySet()) : safeValue);
        if (!endsInLineBreak(value)) text.append('\n');
        else if (endsInLoneCarriageReturn(value)) text.append("\r\n");
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
        boolean endedWithNewline = false;
        char lastCharacter = 0;
        int longestRedaction = 0;
        List<String> effectiveRedactions = new ArrayList<String>(secretRedactions);
        if (redactions != null) for (String token : redactions)
            if (token != null && !token.isEmpty() && !effectiveRedactions.contains(token)) effectiveRedactions.add(token);
        effectiveRedactions.sort((left, right) -> Integer.compare(right.length(), left.length()));
        if (!effectiveRedactions.isEmpty()) for (String token : effectiveRedactions)
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
                    chunk.append(value);
                }
                if (chunk.length() > 0) {
                    writeRedactedChunk(chunk.toString(), pending, effectiveRedactions, longestRedaction);
                    char last = chunk.charAt(chunk.length() - 1);
                    lastCharacter = last;
                    endedWithNewline = last == '\n' || last == '\r';
                }
            }
        }
        if (pending != null && pending.length() > 0) {
            String tail = pending.toString();
            List<String> ordered = new ArrayList<String>(redactions);
            ordered.sort((left, right) -> Integer.compare(right == null ? 0 : right.length(), left == null ? 0 : left.length()));
            for (String token : ordered) if (token != null && !token.isEmpty()) tail = tail.replace(token, "[REDACTED_SECRET]");
            write(tail);
        }
        if (!endedWithNewline) write("\n");
        else if (lastCharacter == '\r') write("\r\n");
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
        String safeText = PathPresentation.displayText(redactSecrets(text), projectRoot);
        if (writer != null) {
            writer.write(safeText);
            bufferedCharacters += safeText.length();
            if (flushEachWrite || mirror != null || bufferedCharacters >= BATCHED_LOG_BUFFER_CHARS) {
                writer.flush();
                bufferedCharacters = 0L;
            }
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

    private static boolean endsInLineBreak(String value) {
        return !value.isEmpty() && (value.charAt(value.length() - 1) == '\n' || value.charAt(value.length() - 1) == '\r');
    }

    private static boolean endsInLoneCarriageReturn(String value) {
        return !value.isEmpty() && value.charAt(value.length() - 1) == '\r';
    }

    /** Keeps a content-ending lone CR distinct from the following log-record separator. */
    private static void appendRecordSeparator(StringBuilder text) {
        if (text.length() > 0 && text.charAt(text.length() - 1) == '\r') text.append("\r\n");
        else text.append('\n');
    }

    /** Renders nested values for people while leaving multiline String characters intact. */
    private String renderHumanYaml(Object value, int indent, String parentPath) {
        StringBuilder out = new StringBuilder();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                indent(out, indent);
                out.append(yamlScalar(String.valueOf(entry.getKey()))).append(':');
                Object child = entry.getValue();
                String childPath = parentPath.isEmpty() ? String.valueOf(entry.getKey())
                        : parentPath + "." + entry.getKey();
                if (isEmptyContainer(child)) {
                    out.append(' ').append(child instanceof Map ? "{}" : "[]").append('\n');
                } else if (isContainer(child)) {
                    out.append('\n').append(renderHumanYaml(child, indent + 2, childPath));
                } else if (child instanceof String && isHumanTextField(childPath)) {
                    out.append(' ').append(renderBlock((String) child, indent + 2));
                } else if (child instanceof String && hasLineBreak((String) child)) {
                    out.append(' ').append(renderBlock((String) child, indent + 2));
                } else {
                    out.append(' ').append(yamlScalar(child)).append('\n');
                }
            }
        } else if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) {
                indent(out, indent);
                out.append('-');
                if (isEmptyContainer(child)) out.append(' ').append(child instanceof Map ? "{}" : "[]").append('\n');
                else if (isContainer(child)) out.append('\n').append(renderHumanYaml(child, indent + 2, parentPath));
                else if (child instanceof String && hasLineBreak((String) child)) out.append(' ').append(renderBlock((String) child, indent + 2));
                else out.append(' ').append(yamlScalar(child)).append('\n');
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> values = new ArrayList<Object>(length);
            for (int index = 0; index < length; index++) values.add(java.lang.reflect.Array.get(value, index));
            return renderHumanYaml(values, indent, parentPath);
        } else {
            indent(out, indent);
            out.append(yamlScalar(value)).append('\n');
        }
        return out.toString();
    }

    private boolean containsHumanTextField(Object value, String parentPath) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                String path = parentPath.isEmpty() ? key : parentPath + "." + key;
                if (isHumanTextField(path) || containsHumanTextField(entry.getValue(), path)) return true;
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) if (containsHumanTextField(item, parentPath)) return true;
        }
        return false;
    }

    private boolean isHumanTextField(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        String field = lower.substring(lower.lastIndexOf('.') + 1);
        if ("command".equals(field) || "resolvedcommand".equals(field) || "executedcommand".equals(field)) return true;
        if ("message".equals(field) || "summary".equals(field) || "detail".equals(field)) return true;
        return ("expression".equals(field) || "rendered".equals(field)) && lower.contains("assertion.");
    }

    private String renderBlock(String value, int contentIndent) {
        int trailingBreaks = 0;
        for (int index = value.length() - 1; index >= 0; index--) {
            char ch = value.charAt(index);
            if (ch == '\r' || ch == '\n') trailingBreaks++;
            else break;
        }
        String indicator = trailingBreaks == 0 ? "|-" : trailingBreaks == 1 ? "|" : "|+";
        StringBuilder out = new StringBuilder(indicator).append('\n');
        int lineStart = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch != '\r' && ch != '\n') continue;
            indent(out, contentIndent);
            out.append(value, lineStart, index).append(ch);
            if (ch == '\r' && index + 1 < value.length() && value.charAt(index + 1) == '\n') {
                out.append('\n');
                index++;
            }
            lineStart = index + 1;
        }
        if (lineStart < value.length()) {
            indent(out, contentIndent);
            out.append(value.substring(lineStart)).append('\n');
        } else if (value.isEmpty()) {
            indent(out, contentIndent);
            out.append('\n');
        }
        return out.toString();
    }

    private String yamlScalar(Object value) {
        String dumped = yaml.dump(value);
        if (dumped.endsWith("\n...\n")) return dumped.substring(0, dumped.length() - 5).trim();
        if (dumped.endsWith("\n")) dumped = dumped.substring(0, dumped.length() - 1);
        return dumped;
    }

    private boolean isContainer(Object value) { return value instanceof Map || value instanceof Iterable || (value != null && value.getClass().isArray()); }
    private boolean isEmptyContainer(Object value) {
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
        if (value instanceof java.util.Collection) return ((java.util.Collection<?>) value).isEmpty();
        return value != null && value.getClass().isArray() && java.lang.reflect.Array.getLength(value) == 0;
    }
    private boolean hasLineBreak(String value) { return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0; }
    private boolean containsMultilineString(Object value) {
        if (value instanceof String) return hasLineBreak((String) value);
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                if (containsMultilineString(entry.getValue())) return true;
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) if (containsMultilineString(item)) return true;
        }
        return false;
    }
    private void indent(StringBuilder out, int count) { for (int index = 0; index < count; index++) out.append(' '); }

    @Override public synchronized void close() throws IOException { if (writer != null) writer.close(); }

    /** Writes the human-readable action log without repeating the complete state retained in case.yaml. */
    public void appendAction(String section, Map<String, Object> action) throws IOException {
        if (discarding) return;
        appendWithRemotePaths(section, compactAction(action), knownSshRemotePaths(action));
    }

    /** Writes one standalone expression Tool invocation as a compact attempt record. */
    public void appendToolInvocation(String section, Map<String, Object> invocation) throws IOException {
        if (discarding) return;
        appendWithRemotePaths(section, compactAttempt(invocation), knownSshRemotePaths(invocation));
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
        copyIfPresent(attempt, result, "attempt", "id", "groupId", "toolKey", "status", "durationMs",
                "input", "command", "resolvedCommand", "executedCommand", "logicalArgv", "argv",
                "timeoutMs", "exitCode");
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

    private boolean diagnosticSection(String section) {
        return section != null && section.matches("(?i).*(^|\\s)(ERROR|FAIL|INVALID|DIAGNOSTIC|WARNING|RETRY)(\\s|$).*");
    }

    private boolean abnormalValue(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null) return false;
        if (value instanceof ResultStatus) return abnormalStatus(String.valueOf(value));
        if (value instanceof Path) return false;
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
                                IdentityHashMap<Object, Boolean> active, boolean preserveText,
                                boolean diagnosticText, boolean executionIdentity, Set<String> remotePaths) {
        if (value == null) return null;
        if (value instanceof Path) {
            String raw = value.toString();
            String redacted = redactSecrets(raw);
            if (!raw.equals(redacted)) return redacted;
            return executionIdentity ? PathPresentation.displayText(redacted, projectRoot)
                    : preserveText ? raw : PathPresentation.displayPath((Path) value, projectRoot);
        }
        if (value instanceof String) {
            String redacted = redactSecrets((String) value);
            if (executionIdentity) return PathPresentation.displayText(redacted, projectRoot);
            if (preserveText) return redacted;
            return diagnosticText
                    ? PathPresentation.displayDiagnosticText(redacted, projectRoot, remotePaths)
                    : PathPresentation.displayText(redacted, projectRoot);
        }
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
                    Object key = serializable(entry.getKey(), copies, active, false, false, false, remotePaths);
                    Object fieldValue = entry.getValue();
                    boolean remoteField = key instanceof String && PathPresentation.isRemoteField((String) key);
                    boolean diagnosticField = key instanceof String && PathPresentation.isDiagnosticField((String) key);
                    boolean identityField = key instanceof String && isExecutionIdentityField((String) key);
                    boolean identity = executionIdentity || identityField;
                    if (!identity && key instanceof String && PathPresentation.isLocalPathField((String) key) && fieldValue instanceof String) {
                        String originalPath = (String) fieldValue;
                        String redactedPath = redactSecrets(originalPath);
                        if (!redactedPath.equals(originalPath)) fieldValue = redactedPath;
                        else if (PathPresentation.isAbsolutePathText(originalPath))
                            fieldValue = PathPresentation.displayPathText(originalPath, projectRoot);
                    }
                    copy.put(key, serializable(fieldValue, copies, active, preserveText || remoteField,
                            diagnosticText || diagnosticField, identity, remotePaths));
                }
                return copy;
            }
            ArrayList<Object> copy = new ArrayList<Object>();
            if (yamlAnchors) copies.put(value, copy);
            if (value instanceof Iterable) {
                for (Object item : (Iterable<?>) value) copy.add(serializable(item, copies, active, preserveText, diagnosticText, executionIdentity, remotePaths));
            } else {
                int length = java.lang.reflect.Array.getLength(value);
                for (int index = 0; index < length; index++)
                    copy.add(serializable(java.lang.reflect.Array.get(value, index), copies, active, preserveText, diagnosticText, executionIdentity, remotePaths));
            }
            return copy;
        } finally {
            active.remove(value);
        }
    }

    private boolean isExecutionIdentityField(String field) {
        if (field == null) return false;
        String key = field.toLowerCase(java.util.Locale.ROOT);
        return "command".equals(key) || "resolvedcommand".equals(key) || "executedcommand".equals(key)
                || "logicalargv".equals(key) || "argv".equals(key);
    }

    private Set<String> knownSshRemotePaths(Object value) {
        Set<String> paths = new LinkedHashSet<String>();
        collectRemotePaths(value, paths, new IdentityHashMap<Object, Boolean>());
        return paths;
    }

    private void collectRemotePaths(Object value, Set<String> paths, IdentityHashMap<Object, Boolean> visited) {
        if (value instanceof Path) return;
        if (!(value instanceof Map) && !(value instanceof Iterable)
                && (value == null || !value.getClass().isArray())) return;
        if (value == null || visited.put(value, Boolean.TRUE) != null) return;
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
                Object item = entry.getValue();
                if (key.contains("remote") && (key.endsWith("path") || key.endsWith("file")) && item != null) {
                    String redacted = redactSecrets(String.valueOf(item));
                    if (!redacted.isEmpty()) paths.add(redacted);
                }
                collectRemotePaths(item, paths, visited);
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) collectRemotePaths(item, paths, visited);
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++)
                collectRemotePaths(java.lang.reflect.Array.get(value, index), paths, visited);
        }
    }

    private String redactSecrets(String value) {
        String redacted = value;
        for (String secret : secretRedactions) redacted = redacted.replace(secret, "[REDACTED_SECRET]");
        return redacted;
    }
}
