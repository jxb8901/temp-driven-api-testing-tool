/* Author: Jeffrey + ChatGPT */
package att.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import att.validation.DiagnosticException;
import att.validation.JsonSchemaVerifier;
import att.validation.SourceLocation;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.Reader;
import java.io.StringReader;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates the strict safe YAML parser used by V2 configuration and cells. */
public final class YamlSupport {
    private YamlSupport() {}
    // Reuse a single configured Yaml instance to avoid repeated construction overhead
    private static final Yaml PARSER;
    static { PARSER = new Yaml(new SafeConstructor(options())); }
    private static LoaderOptions options() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(3_000_000);
        return options;
    }
    public static Yaml parser() { return PARSER; }

    /** Parses an already bounded source string with the same safe duplicate-key and alias policy. */
    public static Object parse(String source) {
        if (source == null) throw new IllegalArgumentException("YAML source is required");
        return new Yaml(new SafeConstructor(options())).load(source);
    }

    private static final Map<Path, YamlSource> SOURCES = new LinkedHashMap<Path, YamlSource>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, YamlSource> eldest) { return size() > 4096; }
    };
    private static final ThreadLocal<Map<Path,YamlSource>> SNAPSHOT_SOURCES = new ThreadLocal<Map<Path,YamlSource>>();

    /** Safe construction plus source capture in a single YAML parse. */
    public static Object load(Path file) throws IOException {
        clearSnapshotSource(file);
        long size = Files.size(file);
        return load(file, Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8), size);
    }

    /** Safe construction from an already captured byte snapshot, preserving its exact source revision. */
    public static Object load(Path file, byte[] bytes) throws IOException {
        if (bytes == null) throw new IllegalArgumentException("YAML source bytes are required");
        String sourceText;
        try {
            sourceText = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            clearSnapshotSource(file);
            throw failure;
        }
        Path key = file.toAbsolutePath().normalize();
        Map<Path,YamlSource> snapshots = snapshotSources();
        snapshots.put(key, new YamlSource(file, null, sourceText));
        long inspectionStarted = System.nanoTime();
        att.metrics.InspectionMetrics.JVM.add(att.metrics.InspectionMetrics.Stage.READ_BYTES, bytes.length);
        SourceConstructor constructor = new SourceConstructor(options());
        try (Reader reader = new StringReader(sourceText)) {
            Object value = new Yaml(constructor).load(reader);
            snapshots.put(key, new YamlSource(file, constructor.root, sourceText));
            return value;
        } finally {
            att.metrics.InspectionMetrics.JVM.record(att.metrics.InspectionMetrics.Stage.YAML_PARSE, System.nanoTime() - inspectionStarted);
        }
    }

    private static Object load(Path file, Reader input, long sourceBytes) throws IOException {
        long inspectionStarted = System.nanoTime();
        att.metrics.InspectionMetrics.JVM.add(att.metrics.InspectionMetrics.Stage.READ_BYTES, sourceBytes);
        Path key = file.toAbsolutePath().normalize();
        synchronized (SOURCES) { SOURCES.remove(key); }
        SourceConstructor constructor = new SourceConstructor(options());
        try (Reader reader = input) {
            Object value = new Yaml(constructor).load(reader);
            YamlSource source = new YamlSource(file, constructor.root);
            synchronized (SOURCES) { SOURCES.put(key, source); }
            return value;
        } finally {
            att.metrics.InspectionMetrics.JVM.record(att.metrics.InspectionMetrics.Stage.YAML_PARSE, System.nanoTime() - inspectionStarted);
        }
    }

    public static SourceLocation location(Path file, String field, Throwable failure) {
        YamlSource source = snapshotSource(file);
        if (source == null) synchronized (SOURCES) { source = SOURCES.get(file.toAbsolutePath().normalize()); }
        java.util.List<String> capturedLines = source == null ? null : source.capturedLines();
        // Parser errors already have precise physical marks, even when no document was constructed.
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof MarkedYAMLException) {
                org.yaml.snakeyaml.error.Mark mark = ((MarkedYAMLException) cause).getProblemMark();
                if (mark != null) return withExcerpt(YamlSource.range(file.toString(), mark, mark), file, field, capturedLines);
            }
        }
        if (source != null) {
            att.template.ExpressionSyntaxException syntax = att.template.ExpressionSyntaxException.find(failure);
            SourceLocation range = source.location(field);
            try {
                java.util.List<String> lines = lines(file, capturedLines);
                String reference = syntax == null ? diagnosticReference(failure, source.scalarValue(field)) : null;
                int offset = syntax == null
                        ? (reference == null ? -1 : source.scalarValue(field).indexOf(reference))
                        : syntax.offset();
                if (offset >= 0) {
                    SourceLocation location = source.expressionLocation(field, offset, lines);
                    if (source.isBlockScalar(field) && location.line() == range.line() && reference != null) {
                        SourceLocation physical = source.blockScalarTextLocation(field, reference, lines);
                        if (physical != null) location = physical;
                    }
                    return withExcerpt(location, file, field, source.isBlockScalar(field) ? range : null, lines);
                }
                if (source.isBlockScalar(field)) {
                    SourceLocation body = source.blockScalarBodyLocation(field, lines);
                    return withExcerpt(body == null ? range : body, file, field, range, lines);
                }
            } catch (IOException ignored) { /* Retain the captured scalar range. */ }
            return withExcerpt(range, file, field, capturedLines);
        }
        return null;
    }

    /** Clears the calling thread's captured source metadata after its snapshot has been validated. */
    public static void clearSnapshotSource(Path file) {
        if (file == null) return;
        Map<Path,YamlSource> snapshots = SNAPSHOT_SOURCES.get();
        if (snapshots == null) return;
        snapshots.remove(file.toAbsolutePath().normalize());
        if (snapshots.isEmpty()) SNAPSHOT_SOURCES.remove();
    }

    private static Map<Path,YamlSource> snapshotSources() {
        Map<Path,YamlSource> snapshots = SNAPSHOT_SOURCES.get();
        if (snapshots == null) {
            snapshots = new LinkedHashMap<Path,YamlSource>();
            SNAPSHOT_SOURCES.set(snapshots);
        }
        return snapshots;
    }

    private static YamlSource snapshotSource(Path file) {
        Map<Path,YamlSource> snapshots = SNAPSHOT_SOURCES.get();
        return snapshots == null ? null : snapshots.get(file.toAbsolutePath().normalize());
    }

    private static java.util.List<String> lines(Path file, java.util.List<String> captured) throws IOException {
        return captured == null ? Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8) : captured;
    }

    private static String diagnosticReference(Throwable failure, String scalar) {
        if (scalar == null) return null;
        DiagnosticException diagnostic = DiagnosticException.find(failure);
        if (diagnostic == null) return null;
        String[] messages = {diagnostic.summary(), diagnostic.detail()};
        for (String message : messages) {
            if (message == null) continue;
            int start = 0;
            while ((start = message.indexOf("${", start)) >= 0) {
                int end = message.indexOf('}', start + 2);
                if (end < 0) break;
                String reference = message.substring(start, end + 1);
                if (scalar.contains(reference)) return reference;
                start = end + 1;
            }
        }
        return null;
    }

    private static SourceLocation withExcerpt(SourceLocation location, Path file, String field) {
        return withExcerpt(location, file, field, (java.util.List<String>) null);
    }

    private static SourceLocation withExcerpt(SourceLocation location, Path file, String field,
                                              java.util.List<String> capturedLines) {
        if (location == null || field == null || sensitive(field)) return location;
        try {
            java.util.List<String> lines = lines(file, capturedLines);
            String line = location.line() <= lines.size() ? lines.get(location.line() - 1) : null;
            // Inline YAML maps may contain secrets in neighbouring fields.
            return line == null || sensitive(line) ? location : new SourceLocation(location.file(), location.line(), location.column(),
                    location.endLine(), location.endColumn(), line);
        } catch (Exception ignored) { return location; }
    }

    private static SourceLocation withExcerpt(SourceLocation location, Path file, String field, SourceLocation blockRange) {
        return withExcerpt(location, file, field, blockRange, null);
    }

    private static SourceLocation withExcerpt(SourceLocation location, Path file, String field,
                                              SourceLocation blockRange, java.util.List<String> capturedLines) {
        if (location == null || blockRange == null || field == null || sensitive(field))
            return withExcerpt(location, file, field, capturedLines);
        final int maxLines = 12;
        try {
            java.util.List<String> lines = lines(file, capturedLines);
            int first = Math.max(blockRange.line(), location.line() - maxLines / 2);
            if (first + maxLines - 1 < location.line()) first = location.line() - maxLines + 1;
            int last = Math.min(blockRange.endLine(), Math.min(lines.size(), first + maxLines - 1));
            if (last < location.line()) return location;
            StringBuilder excerpt = new StringBuilder();
            for (int line = first; line <= last; line++) {
                String text = lines.get(line - 1);
                if (sensitive(text)) return location;
                if (excerpt.length() > 0) excerpt.append('\n');
                excerpt.append(text);
            }
            return new SourceLocation(location.file(), location.line(), location.column(),
                    location.endLine(), location.endColumn(), excerpt.toString(), first);
        } catch (Exception ignored) { return location; }
    }
    private static boolean sensitive(String field) {
        String value = field.toLowerCase(java.util.Locale.ROOT);
        return value.contains("password") || value.contains("secret") || value.contains("token")
                || value.contains("credential") || value.contains("privatekey") || value.contains("identityfile")
                || value.contains("apikey") || value.contains("authorization") || value.contains("bearer")
                || value.contains("accesskey") || value.contains("connectionstring");
    }

    public static DiagnosticException locate(DiagnosticException error, Path file, String field) {
        DiagnosticException located = error.atSource(file.toString(), field);
        // An inner error from a different source owns its own marks.
        if (!file.toString().equals(located.file())) return located;
        return located.withSource(location(file, located.field(), error));
    }

    /** Payload offsets refer directly to physical text, without YAML decoding. */
    public static DiagnosticException locateText(DiagnosticException error, Path file, String field) {
        DiagnosticException located = error.atSource(file.toString(), field);
        if (!file.toString().equals(located.file())) return located;
        att.template.ExpressionSyntaxException syntax = att.template.ExpressionSyntaxException.find(error);
        if (syntax == null) return located;
        try {
            String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            int line = 1, column = 1;
            for (int index = 0; index < Math.min(syntax.offset(), text.length()); index++) {
                if (text.charAt(index) == '\n') { line++; column = 1; } else column++;
            }
            return located.withSource(withExcerpt(new SourceLocation(file.toString(), line, column, line, column, null), file, field));
        } catch (IOException ignored) { return located; }
    }

    public static DiagnosticException locateSchema(DiagnosticException error, Path file,
                                                   java.util.List<JsonSchemaVerifier.SchemaViolation> violations) {
        if (violations == null || violations.isEmpty()) return locate(error, file, error.field());
        java.util.List<JsonSchemaVerifier.SchemaViolation> located = new java.util.ArrayList<JsonSchemaVerifier.SchemaViolation>();
        for (JsonSchemaVerifier.SchemaViolation violation : violations)
            located.add(violation.withSource(location(file, violation.path(), null)));
        return locate(error.withSchemaViolations(located), file, violations.get(0).path());
    }

    private static final class SourceConstructor extends SafeConstructor {
        private Node root;
        SourceConstructor(LoaderOptions options) { super(options); }
        @Override protected Object constructObject(Node node) {
            if (root == null) root = node;
            return super.constructObject(node);
        }
    }
}
