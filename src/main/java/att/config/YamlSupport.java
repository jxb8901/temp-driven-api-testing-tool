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
import java.io.IOException;
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

    private static final Map<Path, YamlSource> SOURCES = new LinkedHashMap<Path, YamlSource>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, YamlSource> eldest) { return size() > 4096; }
    };

    /** Safe construction plus source capture in a single YAML parse. */
    public static Object load(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        synchronized (SOURCES) { SOURCES.remove(key); }
        SourceConstructor constructor = new SourceConstructor(options());
        try (Reader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            Object value = new Yaml(constructor).load(reader);
            YamlSource source = new YamlSource(file, constructor.root);
            synchronized (SOURCES) { SOURCES.put(key, source); }
            return value;
        }
    }

    public static SourceLocation location(Path file, String field, Throwable failure) {
        // Parser errors already have precise physical marks, even when no document was constructed.
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof MarkedYAMLException) {
                org.yaml.snakeyaml.error.Mark mark = ((MarkedYAMLException) cause).getProblemMark();
                if (mark != null) return withExcerpt(YamlSource.range(file.toString(), mark, mark), file, field);
            }
        }
        synchronized (SOURCES) {
            YamlSource source = SOURCES.get(file.toAbsolutePath().normalize());
            if (source == null) return null;
            att.template.ExpressionSyntaxException syntax = att.template.ExpressionSyntaxException.find(failure);
            if (syntax != null) try {
                return withExcerpt(source.expressionLocation(field, syntax.offset(),
                        Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)), file, field);
            } catch (IOException ignored) { /* Retain the captured scalar range. */ }
            return withExcerpt(source.location(field), file, field);
        }
    }

    private static SourceLocation withExcerpt(SourceLocation location, Path file, String field) {
        if (location == null || field == null || sensitive(field)) return location;
        try {
            java.util.List<String> lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
            String line = location.line() <= lines.size() ? lines.get(location.line() - 1) : null;
            // Inline YAML maps may contain secrets in neighbouring fields.
            return line == null || sensitive(line) ? location : new SourceLocation(location.file(), location.line(), location.column(),
                    location.endLine(), location.endColumn(), line);
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
