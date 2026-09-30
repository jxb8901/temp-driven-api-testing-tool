package att.validation;

import att.config.YamlSupport;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers valid package descriptors that use a registered historical schema. */
public final class SchemaVersionDiagnostics {
    private static final Set<String> EXCLUDED_DIRECTORIES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(".git", ".codex", "docs", "dist", "output", "schemas", "src", "target")));
    private SchemaVersionDiagnostics() { }

    public static List<Diagnostic> collect(final Path projectRoot) {
        final Path root = projectRoot.toAbsolutePath().normalize();
        final Map<String, String> historical;
        try { historical = SchemaFiles.historicalSchemaVersions(root); }
        catch (RuntimeException invalidCatalog) { return Collections.emptyList(); }
        // Descriptor families with new 3.6.0 contracts are current-schema-only.
        // Sidecar v2.1 remains supported by SuiteConfigResolver and can still
        // receive an advisory about the available v2.2 schema.
        Map<String, String> supportedHistorical = new LinkedHashMap<String, String>();
        String legacySidecar = att.Version.LEGACY_SIDECAR_SCHEMA;
        if (historical.containsKey(legacySidecar))
            supportedHistorical.put(legacySidecar, historical.get(legacySidecar));
        if (supportedHistorical.isEmpty() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            return Collections.emptyList();

        final List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        final Set<Path> visited = new LinkedHashSet<Path>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (Files.isSymbolicLink(directory) || excluded(root.relativize(directory))) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile() || Files.isSymbolicLink(file) || !yaml(file)
                            || !visited.add(file.toAbsolutePath().normalize())) return FileVisitResult.CONTINUE;
                    inspect(root, file, supportedHistorical, diagnostics);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // Package validation reports its own inaccessible/invalid resources.
        }
        return Collections.unmodifiableList(diagnostics);
    }

    private static void inspect(Path root, Path file, Map<String, String> historical,
                                List<Diagnostic> diagnostics) {
        try {
            Object document = YamlSupport.load(file);
            if (!(document instanceof Map)) return;
            Object rawVersion = ((Map<?, ?>) document).get("schemaVersion");
            if (!(rawVersion instanceof String)) return;
            String declared = (String) rawVersion;
            String current = historical.get(declared);
            if (current == null) return;

            // Warn only when the descriptor is valid under the exact historical
            // schema it declares. The normal validator remains responsible for
            // reporting errors and migration guidance for invalid descriptors.
            JsonSchemaVerifier.verify(SchemaFiles.resolveVersion(root, declared), document);
            String family = family(declared);
            String message = "Descriptor uses supported historical schemaVersion '" + declared
                    + "'; current " + family + " schemaVersion is '" + current + "'.";
            SourceLocation source = YamlSupport.location(file, "schemaVersion", null);
            diagnostics.add(new Diagnostic(DiagnosticCodes.SCHEMA_VERSION_OLD, Diagnostic.Severity.WARNING,
                    message, file.toString(), "schemaVersion", null, null, null, null, null,
                    "Review the migration guidance and the changes between these schemas before upgrading. "
                            + "The descriptor remains validated against its declared historical schema.",
                    message, "declaredSchemaVersion=" + declared + "\ncurrentSchemaVersion=" + current,
                    source, DiagnosticContext.EMPTY));
        } catch (Exception ignored) {
            // Invalid/unsupported descriptors are reported by their owning validator.
        }
    }

    private static boolean excluded(Path relative) {
        for (Path segment : relative) if (EXCLUDED_DIRECTORIES.contains(segment.toString())) return true;
        return false;
    }

    private static boolean yaml(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static String family(String schemaVersion) {
        int marker = schemaVersion.lastIndexOf("/v");
        return marker < 0 ? "descriptor" : schemaVersion.substring(0, marker);
    }
}
